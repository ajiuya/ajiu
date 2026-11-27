package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

internal enum class ChrootRecoveryPhase { INTENT, STARTING, RUNNING, CLOSED, UNKNOWN }

internal data class ChrootRecoveryEntry(
    val instanceId: String,
    val launchId: String,
    val rootfsIdentity: ChrootFileIdentity,
    val ownerKind: String,
    val ownerHash: String,
    val rootGeneration: Long,
    val phase: ChrootRecoveryPhase,
    val cleanup: ChrootCleanupResult?
)

@Singleton
internal class ChrootRecoveryRepository internal constructor(
    private val file: File,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
    }
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "chroot/runtime-journal.bin"),
        { directory ->
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("Runtime journal parent is not a directory")
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    )

    private data class Fingerprint(val sha256: String, val size: Long, val fileKey: String, val modified: FileTime)
    private data class FileImage(val bytes: ByteArray, val fingerprint: Fingerprint)
    private data class JournalImage(val image: FileImage, val entries: LinkedHashMap<String, ChrootRecoveryEntry>)

    private val gate = Any()
    private val pending get() = File(file.path + ".pending")
    private var entries = linkedMapOf<String, ChrootRecoveryEntry>()
    // Captured at load or from our own fsynced staging file before an atomic move. A match is
    // only a recovery precondition; it does not turn a failed write or a running launch into success.
    private var mainFingerprint: Fingerprint? = null
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()
    private val _canRepairInterruptedWrite = MutableStateFlow(false)
    val canRepairInterruptedWrite: StateFlow<Boolean> = _canRepairInterruptedWrite.asStateFlow()
    private val _blockedInstances = MutableStateFlow<Set<String>>(emptySet())
    val blockedInstances: StateFlow<Set<String>> = _blockedInstances.asStateFlow()
    private val _recordedLaunchIds = MutableStateFlow<Set<String>>(emptySet())
    val recordedLaunchIds: StateFlow<Set<String>> = _recordedLaunchIds.asStateFlow()
    @Volatile private var publishedUnknownLaunches: Map<String, Set<String>> = emptyMap()

    // Diagnostic lookups can run under the authority gate; never acquire the disk-write lock here.
    fun unknownLaunchIds(instanceId: String): Set<String> = publishedUnknownLaunches[instanceId].orEmpty()

    init {
        synchronized(gate) {
            try {
                // Parse the main file first, even when pending is truncated. Never infer entries
                // from pending, and never rewrite either file during pending-blocked initialization.
                if (attributesOrNull(file.toPath()) != null) {
                    val loaded = readMainImage()
                    mainFingerprint = loaded.image.fingerprint
                    entries = conservativeEntries(loaded.entries)
                    if (attributesOrNull(pending.toPath()) != null) {
                        throw IOException("Runtime journal has an unfinished write")
                    }
                    if (entries != loaded.entries) writeLocked(entries)
                } else if (attributesOrNull(pending.toPath()) != null) {
                    throw IOException("Runtime journal has no valid main file")
                }
            } catch (_: Exception) {
                failClosedLocked()
            }
            publishLocked()
        }
    }

    fun snapshot(): List<ChrootRecoveryEntry> = synchronized(gate) { entries.values.toList() }

    @Suppress("UNUSED_PARAMETER")
    fun canStart(instanceId: String): Boolean = synchronized(gate) { _isHealthy.value }

    /**
     * The caller must explicitly authorize recovery, revoke bindings and exclude active execution.
     * Uses the ordinary write gate; does not replay launches or verify cleanup. Returns only after
     * evidence, the pending archive and the conservative main journal have all been synced.
     * On IOException health stays false. Retrying is possible only while canRepairInterruptedWrite
     * is true; a changed/invalid main file or exhausted evidence budget requires outside inspection.
     */
    fun repairInterruptedWriteFromUser(): Unit = synchronized(gate) {
        if (_isHealthy.value) throw IOException("Runtime journal has no interrupted write to repair")
        _canRepairInterruptedWrite.value = false
        var archivedPending: FileImage? = null
        try {
            val original = requireKnownMain()
            val interrupted = readFileImage(pending.toPath(), 0)
            val recovered = conservativeEntries(original.entries)
            val archive = archiveEvidenceLocked(original.image, interrupted)

            // Keep the live pending marker until the canonical main is durably replaced. The
            // unique staging file lives with the evidence and is never the original pending file.
            requireKnownMain()
            if (readFileImage(pending.toPath(), 0).fingerprint != interrupted.fingerprint) {
                throw IOException("Runtime journal pending file changed during recovery")
            }
            writeLocked(recovered, File(archive, "canonical.pending"))
            requireKnownMain()
            if (readFileImage(pending.toPath(), 0).fingerprint != interrupted.fingerprint) {
                throw IOException("Runtime journal pending file changed during recovery")
            }
            Files.move(pending.toPath(), File(archive, "pending-original.bin").toPath(), StandardCopyOption.ATOMIC_MOVE)
            archivedPending = interrupted
            syncDirectoryChecked(archive)
            syncDirectoryChecked(parentDirectory())
            requireKnownMain()
            if (attributesOrNull(pending.toPath()) != null ||
                readFileImage(File(archive, "pending-original.bin").toPath(), 0).fingerprint != interrupted.fingerprint
            ) throw IOException("Runtime journal pending archive changed before recovery completed")

            entries = recovered
            _isHealthy.value = true
            _canRepairInterruptedWrite.value = false
            publishLocked()
        } catch (failure: Exception) {
            // A failed move/sync/write must not leave a missing pending name looking successful.
            // Restore only with CREATE_NEW and only against the still-known main fingerprint.
            archivedPending?.let { image ->
                try {
                    restorePendingIfAbsentLocked(image.bytes)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            failClosedLocked()
            throw IOException("Interrupted journal recovery failed; retry only when canRepairInterruptedWrite is true", failure)
        }
    }

    fun recordIntent(
        instanceId: String,
        launchId: String,
        rootfsIdentity: ChrootFileIdentity,
        ownerIdentity: String,
        rootGeneration: Long
    ) = synchronized(gate) {
        require(nonce.matches(instanceId) && nonce.matches(launchId))
        require(ownerIdentity.isNotBlank() && ownerIdentity.length <= 512 && rootGeneration >= 0L)
        checkHealthyLocked()
        if (!canStart(instanceId) || launchId in entries || entries.size >= MAX_ENTRIES) {
            throw IOException("Runtime journal does not permit this launch")
        }
        val entry = ChrootRecoveryEntry(
            instanceId, launchId, rootfsIdentity, ownerKind(ownerIdentity), hash(ownerIdentity),
            rootGeneration, ChrootRecoveryPhase.INTENT, null
        )
        persistLocked(LinkedHashMap(entries).apply { put(launchId, entry) })
    }

    fun recordPhase(launchId: String, phase: ChrootRecoveryPhase) = synchronized(gate) {
        require(phase == ChrootRecoveryPhase.STARTING || phase == ChrootRecoveryPhase.RUNNING)
        checkHealthyLocked()
        val previous = entries[launchId] ?: throw IOException("Runtime launch is not journaled")
        if (previous.phase == ChrootRecoveryPhase.UNKNOWN || previous.phase == ChrootRecoveryPhase.CLOSED) return@synchronized
        if (phase == ChrootRecoveryPhase.STARTING && previous.phase == ChrootRecoveryPhase.RUNNING) return@synchronized
        persistLocked(LinkedHashMap(entries).apply { put(launchId, previous.copy(phase = phase)) })
    }

    fun recordCleanup(launchId: String, result: ChrootCleanupResult) = synchronized(gate) {
        checkHealthyLocked()
        val previous = entries[launchId] ?: throw IOException("Runtime launch is not journaled")
        if (previous.phase == ChrootRecoveryPhase.UNKNOWN || previous.phase == ChrootRecoveryPhase.CLOSED) return@synchronized
        val phase = if (result == ChrootCleanupResult.VERIFIED) ChrootRecoveryPhase.CLOSED else ChrootRecoveryPhase.UNKNOWN
        persistLocked(LinkedHashMap(entries).apply { put(launchId, previous.copy(phase = phase, cleanup = result)) })
    }

    private fun persistLocked(updated: LinkedHashMap<String, ChrootRecoveryEntry>) {
        try {
            writeLocked(updated)
            entries = updated
            publishLocked()
        } catch (e: Exception) {
            failClosedLocked()
            throw IOException("Runtime journal persistence is unavailable", e)
        }
    }

    private fun failClosedLocked() {
        _isHealthy.value = false
        entries = conservativeEntries(entries)
        // Reload a valid known main after an in-process failure too. Preserve every in-memory
        // UNKNOWN if a failed cleanup write put CLOSED on disk before its directory sync failed.
        try {
            entries = conservativeEntries(requireKnownMain().entries)
        } catch (_: Exception) {
            // Keep the read-only entries we already know; never promote arbitrary replacement data.
        }
        refreshCanRepairLocked()
        publishLocked()
    }

    private fun checkHealthyLocked() {
        if (!_isHealthy.value) throw IOException("Runtime journal requires explicit recovery")
    }

    private fun publishLocked() {
        val unknown = entries.values.filter { it.phase == ChrootRecoveryPhase.UNKNOWN }
        publishedUnknownLaunches = unknown.groupBy { it.instanceId }.mapValues { (_, launches) ->
            Collections.unmodifiableSet(launches.mapTo(linkedSetOf()) { it.launchId })
        }
        _blockedInstances.value = unknown.mapTo(linkedSetOf()) { it.instanceId }.toSet()
        _recordedLaunchIds.value = entries.keys.toSet()
    }

    private fun conservativeEntries(source: Map<String, ChrootRecoveryEntry>): LinkedHashMap<String, ChrootRecoveryEntry> {
        val result = source.mapValuesTo(linkedMapOf()) { (launch, entry) ->
            if (entry.phase == ChrootRecoveryPhase.CLOSED && entry.cleanup == ChrootCleanupResult.VERIFIED &&
                entries[launch]?.phase != ChrootRecoveryPhase.UNKNOWN
            ) entry else entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
        }
        // A reload must not drop previously captured uncertainty, even if a failed intent was not
        // included in the main image. Ordinary successful writes still use their original entries.
        entries.values.filter { it.phase == ChrootRecoveryPhase.UNKNOWN }.forEach { result.putIfAbsent(it.launchId, it) }
        return result
    }

    private fun requireKnownMain(): JournalImage {
        val expected = mainFingerprint ?: throw IOException("No trusted main journal is available")
        val actual = readMainImage()
        if (actual.image.fingerprint != expected) {
            mainFingerprint = null
            throw IOException("Runtime journal main file changed since it was captured")
        }
        return actual
    }

    private fun refreshCanRepairLocked() {
        _canRepairInterruptedWrite.value = !_isHealthy.value && try {
            requireKnownMain()
            readFileImage(pending.toPath(), 0)
            checkArchiveBudget()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readMainImage(): JournalImage {
        val image = readFileImage(file.toPath(), 12)
        val loaded = DataInputStream(ByteArrayInputStream(image.bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid runtime journal version")
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Invalid runtime journal entry count")
            val result = linkedMapOf<String, ChrootRecoveryEntry>()
            repeat(count) {
                val instance = input.readUTF()
                val launch = input.readUTF()
                val rootfs = ChrootFileIdentity(input.readLong(), input.readLong())
                val kind = input.readUTF()
                val owner = input.readUTF()
                val generation = input.readLong()
                val phase = ChrootRecoveryPhase.entries.getOrNull(input.readUnsignedByte())
                    ?: throw IOException("Invalid runtime journal phase")
                val cleanup = when (input.readByte().toInt()) {
                    -1 -> null
                    0 -> ChrootCleanupResult.VERIFIED
                    1 -> ChrootCleanupResult.UNKNOWN
                    else -> throw IOException("Invalid runtime journal cleanup")
                }
                if (!nonce.matches(instance) || !nonce.matches(launch) || !kind.matches(Regex("[a-z]{1,24}")) ||
                    !owner.matches(Regex("[0-9a-f]{64}")) || generation < 0 || launch in result ||
                    (phase == ChrootRecoveryPhase.CLOSED && cleanup != ChrootCleanupResult.VERIFIED) ||
                    (phase == ChrootRecoveryPhase.UNKNOWN && cleanup != ChrootCleanupResult.UNKNOWN)
                ) throw IOException("Invalid runtime journal entry")
                result[launch] = ChrootRecoveryEntry(instance, launch, rootfs, kind, owner, generation, phase, cleanup)
            }
            if (input.read() != -1) throw IOException("Trailing runtime journal bytes")
            result
        }
        return JournalImage(image, loaded)
    }

    private fun encodeEntries(updated: Map<String, ChrootRecoveryEntry>): ByteArray {
        if (updated.size > MAX_ENTRIES) throw IOException("Invalid runtime journal entry count")
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(updated.size)
            updated.values.forEach { entry ->
                output.writeUTF(entry.instanceId)
                output.writeUTF(entry.launchId)
                output.writeLong(entry.rootfsIdentity.device)
                output.writeLong(entry.rootfsIdentity.inode)
                output.writeUTF(entry.ownerKind)
                output.writeUTF(entry.ownerHash)
                output.writeLong(entry.rootGeneration)
                output.writeByte(entry.phase.ordinal)
                output.writeByte(when (entry.cleanup) {
                    null -> -1
                    ChrootCleanupResult.VERIFIED -> 0
                    ChrootCleanupResult.UNKNOWN -> 1
                })
            }
        }
        return bytes.toByteArray().also {
            if (it.size.toLong() !in 12L..MAX_FILE_BYTES) throw IOException("Invalid runtime journal size")
        }
    }

    private fun writeLocked(updated: Map<String, ChrootRecoveryEntry>, staging: File = pending) {
        val parent = ensureParentDirectoryLocked()
        checkMainTargetLocked()
        if (attributesOrNull(staging.toPath()) != null) throw IOException("Runtime journal has an unfinished write")
        val encoded = encodeEntries(updated)
        var moved: FileImage? = null
        try {
            writeNewFileAndSync(staging.toPath(), encoded)
            val staged = readFileImage(staging.toPath(), 12)
            if (!staged.bytes.contentEquals(encoded)) throw IOException("Runtime journal staging file changed")
            checkMainTargetLocked()
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Rename preserves the staging inode. Pin this exact image before the fallible
            // directory sync so a retry can recognize our own write, never an external replacement.
            mainFingerprint = staged.fingerprint
            moved = staged
            if (staging.parentFile != parent) syncDirectoryChecked(staging.parentFile)
            syncDirectoryChecked(parent)
            requireKnownMain()
        } catch (failure: Exception) {
            moved?.let { image ->
                try {
                    restorePendingIfAbsentLocked(image.bytes)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        }
    }

    private fun checkMainTargetLocked() {
        if (mainFingerprint != null) requireKnownMain()
        else if (attributesOrNull(file.toPath()) != null) throw IOException("Unexpected runtime journal main file")
    }

    private fun archiveEvidenceLocked(main: FileImage, interrupted: FileImage): File {
        checkArchiveBudget()
        val parent = parentDirectory()
        val archive = Files.createTempDirectory(
            parent.toPath(), "${file.name}.recovery-", PosixFilePermissions.asFileAttribute(privateDirectoryPermissions)
        ).toFile()
        writeNewFileAndSync(File(archive, "main.bin").toPath(), main.bytes)
        writeNewFileAndSync(File(archive, "pending.bin").toPath(), interrupted.bytes)
        val evidence = "${main.fingerprint.sha256}  main.bin\n${interrupted.fingerprint.sha256}  pending.bin\n"
        writeNewFileAndSync(File(archive, "sha256.txt").toPath(), evidence.toByteArray(Charsets.US_ASCII))
        syncDirectoryChecked(archive)
        syncDirectoryChecked(parent)
        return archive
    }

    private fun checkArchiveBudget() {
        val parent = parentDirectory()
        requireDirectory(parent.toPath())
        var count = 0
        Files.newDirectoryStream(parent.toPath()).use { paths ->
            for (path in paths) {
                if (!path.fileName.toString().startsWith("${file.name}.recovery-")) continue
                requireDirectory(path)
                if (++count >= MAX_ARCHIVES) throw IOException("Runtime journal evidence archive limit reached")
            }
        }
    }

    private fun restorePendingIfAbsentLocked(bytes: ByteArray) {
        requireKnownMain()
        if (attributesOrNull(pending.toPath()) != null) return
        writeNewFileAndSync(pending.toPath(), bytes)
        syncDirectoryChecked(parentDirectory())
    }

    private fun readFileImage(path: Path, minimum: Long): FileImage {
        val directory = requireDirectory(path.parent)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() !in minimum..MAX_FILE_BYTES) {
            throw IOException("Invalid runtime journal file")
        }
        val key = before.fileKey()?.toString() ?: throw IOException("Runtime journal file identity is unavailable")
        val output = ByteArrayOutputStream(before.size().toInt())
        FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            var total = 0L
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_FILE_BYTES) throw IOException("Runtime journal exceeds size limit")
                output.write(buffer.array(), 0, count)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.isSymbolicLink || before.fileKey() != after.fileKey() ||
            before.lastModifiedTime() != after.lastModifiedTime() || before.size() != after.size() ||
            after.size() != output.size().toLong() || directory.fileKey() != requireDirectory(path.parent).fileKey()
        ) throw IOException("Runtime journal changed while being read")
        val bytes = output.toByteArray()
        return FileImage(bytes, Fingerprint(hashBytes(bytes), bytes.size.toLong(), key, before.lastModifiedTime()))
    }

    private fun writeNewFileAndSync(path: Path, bytes: ByteArray) {
        requireDirectory(path.parent)
        FileChannel.open(
            path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(privateFilePermissions)
        ).use { channel ->
            BufferedOutputStream(Channels.newOutputStream(channel)).use { output ->
                output.write(bytes)
                output.flush()
                channel.force(true)
            }
        }
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun requireDirectory(path: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) throw IOException("Unsafe runtime journal directory")
        return attributes
    }

    private fun parentDirectory(): File = file.parentFile ?: throw IOException("Runtime journal has no directory")

    private fun ensureParentDirectoryLocked(): File {
        val parent = parentDirectory()
        if (attributesOrNull(parent.toPath()) == null) {
            val ancestor = parent.parentFile ?: throw IOException("Runtime journal has no parent directory")
            requireDirectory(ancestor.toPath())
            Files.createDirectory(parent.toPath(), PosixFilePermissions.asFileAttribute(privateDirectoryPermissions))
            syncDirectoryChecked(ancestor)
        }
        requireDirectory(parent.toPath())
        return parent
    }

    private fun syncDirectoryChecked(directory: File) {
        val before = requireDirectory(directory.toPath())
        syncDirectory(directory)
        if (before.fileKey() != requireDirectory(directory.toPath()).fileKey()) {
            throw IOException("Runtime journal directory changed while syncing")
        }
    }

    private companion object {
        const val MAGIC = 0x414a434a
        const val VERSION = 1
        const val MAX_ENTRIES = 4096
        const val MAX_FILE_BYTES = 2_000_000L
        // No automatic pruning: reaching this bound refuses further repair and retains evidence.
        // Each attempt creates at most two main-sized files plus two pending-sized files.
        const val MAX_ARCHIVES = 16
        val nonce = Regex("[0-9a-f]{24}")
        val privateDirectoryPermissions = PosixFilePermissions.fromString("rwx------")
        val privateFilePermissions = PosixFilePermissions.fromString("rw-------")

        fun hash(value: String): String = hashBytes(value.toByteArray(Charsets.UTF_8))

        fun hashBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun ownerKind(owner: String): String = owner.substringBefore(':').takeIf {
            it.matches(Regex("[a-z]{1,24}")) && it in setOf("chat", "terminal", "file", "maintenance", "user")
        } ?: "owner"
    }
}
