package com.aicode.feature.workspace.domain.remote

import com.aicode.core.util.FileLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * 单个文件上次成功同步后的指纹。索引按挂载持久化，用于「跳过未变文件」——远程 SFTP/FTP
 * 拿不到远端内容哈希，故只能靠本地记录的指纹做比对（对标的 rsync quick-check 思路）。
 */
@Serializable
data class FileFingerprint(
    val size: Long,
    val mtimeMs: Long,
    /** 内容 SHA-256（十六进制小写）；为空表示尚未计算（仅同尺寸改写时才补算，避免每次上传都全量读盘）。 */
    val sha256: String? = null
)

/** 旧 JSON 只读保留；新格式使用带校验的快照与有序批次 journal。 */
class SyncIndexStore internal constructor(
    private val dir: File,
    private val io: SyncIndexIo = SyncIndexIo(),
    private val compactAfterFrames: Int = 256
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable private data class Snapshot(
        val version: Int = 1,
        val sequence: Long,
        val entries: Map<String, FileFingerprint>,
        val observed: Map<String, FileFingerprint> = emptyMap(),
        val observationInitialized: Boolean = false
    )
    @Serializable private data class CheckedSnapshot(val payload: String, val crc: Long)
    @Serializable private data class Delta(val sequence: Long, val changes: Map<String, FileFingerprint?>)
    private data class State(
        val entries: MutableMap<String, FileFingerprint>,
        var sequence: Long = 0,
        var frames: Int = 0,
        var initialized: Boolean = false,
        var writable: Boolean = true,
        var repairTo: Long? = null,
        val observed: MutableMap<String, FileFingerprint> = linkedMapOf(),
        var observationInitialized: Boolean = false
    )

    private val states = HashMap<String, State>()
    private fun file(id: String, suffix: String): File {
        require(id.isNotBlank() && id != "." && id != ".." && '/' !in id && '\\' !in id)
        return File(dir, "$id.$suffix")
    }
    internal fun ownsPath(path: String): Boolean =
        File(path).absoluteFile.toPath().normalize().startsWith(dir.absoluteFile.toPath().normalize())

    @Synchronized fun load(mountId: String): Map<String, FileFingerprint> = HashMap(state(mountId).entries)

    @Synchronized internal fun observationBaseline(mountId: String): Map<String, FileFingerprint>? =
        state(mountId).let { if (it.observationInitialized) HashMap(it.observed) else null }

    @Synchronized internal fun initializeObservationBaseline(mountId: String, baseline: Map<String, FileFingerprint>) {
        val previous = state(mountId)
        if (previous.observationInitialized) return
        check(previous.writable) { "Sync index requires recovery: $mountId" }
        baseline.forEach { (key, value) -> validate(key, value) }
        ensureDirectory()
        previous.repairTo?.let { io.truncate(file(mountId, "journal"), it) }
        val candidate = previous.copy(observed = baseline.toMutableMap(), observationInitialized = true, repairTo = null)
        compact(mountId, candidate)
        states[mountId] = candidate
    }

    @Synchronized fun requireReconcile(mountId: String) {
        val marker = file(mountId, "rescan")
        if (marker.isFile) return
        ensureDirectory()
        io.atomicWrite(marker, byteArrayOf(1))
    }

    @Synchronized fun needsReconcile(mountId: String): Boolean =
        file(mountId, "rescan").exists() || state(mountId).let { !it.writable || it.repairTo != null }

    @Synchronized fun save(mountId: String, fingerprints: Map<String, FileFingerprint>) {
        val previous = state(mountId).entries
        val changes = LinkedHashMap<String, FileFingerprint?>()
        for (key in previous.keys) if (key !in fingerprints) changes[key] = null
        for ((key, value) in fingerprints) if (previous[key] != value) changes[key] = value
        applyChanges(mountId, changes)
    }

    @Synchronized fun applyChanges(mountId: String, changes: Map<String, FileFingerprint?>) {
        val state = state(mountId)
        val actual = changes.filter { (key, value) -> state.entries[key] != value || key in state.observed }
        if (actual.isEmpty()) return
        check(state.writable) { "Sync index requires recovery: $mountId" }
        actual.forEach { (key, value) -> validate(key, value) }
        ensureDirectory()
        state.repairTo?.let { valid ->
            io.truncate(file(mountId, "journal"), valid)
            state.repairTo = null
        }
        if (!state.initialized) compact(mountId, state)
        check(state.sequence < Long.MAX_VALUE) { "Sync index sequence exhausted" }
        val payload = json.encodeToString(Delta(state.sequence + 1, actual)).toByteArray(Charsets.UTF_8)
        val journal = file(mountId, "journal")
        val before = journal.length()
        try {
            io.append(journal, payload)
        } catch (error: Exception) {
            state.repairTo = before
            try {
                io.truncate(journal, before)
                state.repairTo = null
            } catch (rollback: Exception) {
                error.addSuppressed(rollback)
            }
            throw error
        }
        actual.forEach { (key, value) ->
            if (value == null) state.entries.remove(key) else state.entries[key] = value
            state.observed.remove(key)
        }
        state.sequence++
        state.frames++
        if (state.frames >= compactAfterFrames || journal.length() >= 1024L * 1024) {
            // The transaction is durable even if snapshot publication or rotation fails.
            runCatching { compact(mountId, state) }
                .onFailure { FileLogger.w(TAG, "同步索引压缩失败: $mountId", it) }
        }
    }

    @Synchronized fun clear(mountId: String) {
        val previous = state(mountId)
        ensureDirectory()
        val cleared = State(linkedMapOf(), sequence = previous.sequence,
            observed = (previous.observed + previous.entries).toMutableMap(),
            observationInitialized = previous.observationInitialized)
        compact(mountId, cleared)
        states[mountId] = cleared
    }

    private fun state(id: String): State = states.getOrPut(id) {
        try {
            val snapshot = file(id, "snapshot")
            val legacy = file(id, "json")
            val loaded = when {
                snapshot.exists() -> {
                    val checked = json.decodeFromString<CheckedSnapshot>(snapshot.readText())
                    require(crc(checked.payload.toByteArray(Charsets.UTF_8)) == checked.crc) { "Snapshot checksum mismatch" }
                    val data = json.decodeFromString<Snapshot>(checked.payload)
                    require(data.version == 1 && data.sequence >= 0) { "Unsupported snapshot" }
                    State(data.entries.toMutableMap(), sequence = data.sequence, initialized = true,
                        observed = data.observed.toMutableMap(), observationInitialized = data.observationInitialized)
                }
                legacy.exists() -> State(json.decodeFromString<Map<String, FileFingerprint>>(legacy.readText()).toMutableMap())
                else -> {
                    require(!file(id, "journal").exists()) { "Journal baseline missing" }
                    State(linkedMapOf())
                }
            }
            loaded.entries.forEach { (key, value) -> validate(key, value) }
            loaded.observed.forEach { (key, value) -> validate(key, value) }
            replay(id, loaded)
            loaded
        } catch (error: Exception) {
            FileLogger.w(TAG, "同步索引不可验证，保留原文件并按未同步处理: $id", error)
            State(linkedMapOf(), writable = false)
        }
    }

    private fun replay(id: String, state: State) {
        val journal = file(id, "journal")
        if (!journal.exists()) return
        var valid = 0L
        try {
            RandomAccessFile(journal, "r").use { input ->
                var preceding: Long? = null
                while (input.filePointer < input.length()) {
                    val length = input.readInt()
                    val checksum = input.readInt()
                    require(length > 0 && length.toLong() <= input.length() - input.filePointer) { "Incomplete journal frame" }
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    require(crc(payload).toInt() == checksum) { "Journal checksum mismatch" }
                    val delta = json.decodeFromString<Delta>(payload.toString(Charsets.UTF_8))
                    require(delta.sequence > 0 && (preceding == null || delta.sequence == preceding + 1)) { "Journal sequence gap" }
                    preceding = delta.sequence
                    delta.changes.forEach { (key, value) -> validate(key, value) }
                    if (delta.sequence > state.sequence) {
                        require(delta.sequence == state.sequence + 1) { "Snapshot sequence gap" }
                        delta.changes.forEach { (key, value) ->
                            if (value == null) state.entries.remove(key) else state.entries[key] = value
                            state.observed.remove(key)
                        }
                        state.sequence = delta.sequence
                    }
                    state.frames++
                    valid = input.filePointer
                }
            }
        } catch (error: Exception) {
            // Preserve the bad tail for diagnosis. A later append first durably removes it.
            state.repairTo = valid
            FileLogger.w(TAG, "同步索引尾批次不可验证，要求重新对账: $id", error)
        }
    }

    private fun compact(id: String, state: State) {
        val payload = json.encodeToString(Snapshot(sequence = state.sequence, entries = state.entries,
            observed = state.observed, observationInitialized = state.observationInitialized))
        val checked = CheckedSnapshot(payload, crc(payload.toByteArray(Charsets.UTF_8)))
        io.atomicWrite(file(id, "snapshot"), json.encodeToString(checked).toByteArray(Charsets.UTF_8))
        state.initialized = true
        io.atomicWrite(file(id, "journal"), ByteArray(0))
        state.frames = 0
    }

    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create sync index directory")
    }

    private fun validate(key: String, value: FileFingerprint?) {
        require(key.isNotEmpty() && !key.startsWith('/') && key.split('/').none { it == ".." || it == "." || it.isEmpty() })
        require(value == null || (value.size >= -1 && value.mtimeMs >= 0))
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    private companion object { const val TAG = "SyncIndexStore" }
}

/** 单文件是否需要上传的纯判定逻辑（与网络/磁盘副作用解耦，便于单测）。 */
object SyncDecision {

    enum class Action { UPLOAD, SKIP_UNCHANGED }

    /**
     * 决定是否上传。**保守原则：任何不确定都判 [Action.UPLOAD]**，绝不误跳过导致远端缺文件。
     *
     * @param stored 上次成功同步的指纹；null 表示无记录
     * @param size 当前本地文件大小
     * @param mtime 当前本地文件修改时间（ms）
     * @param currentSha 惰性计算当前文件 SHA-256 的函数；仅在「size 相同但 mtime 不同」时需要时调用
     */
    fun decide(
        stored: FileFingerprint?,
        size: Long,
        mtime: Long,
        currentSha: () -> String?
    ): Action {
        if (stored == null) return Action.UPLOAD
        if (stored.size != size) return Action.UPLOAD
        // size+mtime 都一致：rsync quick-check 命中，跳过（不读文件内容）
        if (stored.mtimeMs == mtime) return Action.SKIP_UNCHANGED
        // size 相同、mtime 不同：可能是「改了又改回来」或同年份精度内的改动，用内容哈希兜底
        val oldSha = stored.sha256 ?: return Action.UPLOAD
        val newSha = currentSha() ?: return Action.UPLOAD
        return if (oldSha == newSha) Action.SKIP_UNCHANGED else Action.UPLOAD
    }
}
