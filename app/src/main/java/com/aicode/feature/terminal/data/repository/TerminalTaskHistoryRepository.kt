package com.aicode.feature.terminal.data.repository

import android.content.Context
import android.util.AtomicFile
import com.aicode.feature.terminal.domain.TERMINAL_HISTORY_CORRUPT_ERROR
import com.aicode.feature.terminal.domain.TERMINAL_HISTORY_IO_ERROR
import com.aicode.feature.terminal.domain.TerminalTaskHistoryState
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import com.aicode.feature.terminal.domain.boundTerminalOutput
import com.aicode.feature.terminal.domain.requireTerminalHistoryId
import com.aicode.feature.terminal.domain.requireTerminalHistorySession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalTaskHistoryRepository internal constructor(
    private val files: TerminalTaskHistoryFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AtomicTerminalTaskHistoryFiles(File(context.noBackupFilesDir.canonicalFile, "terminal-task-history"))
    )

    private val mutex = Mutex()
    private val records = linkedMapOf<String, TerminalTaskRecord>()
    private val writeErrors = mutableSetOf<String>()
    private var loadError: String? = null
    private var cleanupError: String? = null
    private var initialized = false
    private val _state = MutableStateFlow(TerminalTaskHistoryState())
    val state: StateFlow<TerminalTaskHistoryState> = _state.asStateFlow()

    suspend fun load(): TerminalTaskHistoryState = withContext(ioDispatcher) {
        mutex.withLock { loadLocked(); _state.value }
    }

    suspend fun list(scope: TerminalTaskScope, sourceSessionId: String): List<TerminalTaskRecord> {
        scope.validate()
        requireTerminalHistorySession(sourceSessionId)
        return withContext(ioDispatcher) {
            mutex.withLock {
                loadLocked()
                checkReadable()
                orderedRecords().filter { it.scope == scope && it.sourceSessionId == sourceSessionId }
            }
        }
    }

    suspend fun read(historyId: String, scope: TerminalTaskScope, sourceSessionId: String): TerminalTaskRecord? {
        requireTerminalHistoryId(historyId)
        scope.validate()
        requireTerminalHistorySession(sourceSessionId)
        return withContext(ioDispatcher) {
            mutex.withLock {
                loadLocked()
                checkReadable()
                records[historyId]?.takeIf { it.scope == scope && it.sourceSessionId == sourceSessionId }
            }
        }
    }

    internal suspend fun create(record: TerminalTaskRecord) = withContext(ioDispatcher) {
        record.validate()
        require(record.status == TerminalTaskStatus.RUNNING && record.outputRevision == -1L && record.exitCode == null)
        mutex.withLock {
            loadLocked()
            checkLoaded()
            check(record.historyId !in records) { "Terminal history id already exists" }
            try {
                files.write(record)
                records[record.historyId] = record
                writeErrors -= record.historyId
                writeErrors.retainAll(records.keys)
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                writeErrors += record.historyId
                publish()
                throw IOException(TERMINAL_HISTORY_IO_ERROR)
            }
        }
    }

    internal suspend fun snapshot(historyId: String, output: String, revision: Long) = withContext(ioDispatcher) {
        requireTerminalHistoryId(historyId)
        require(revision >= 0)
        mutex.withLock {
            loadLocked()
            checkLoaded()
            val previous = records[historyId] ?: throw IOException(TERMINAL_HISTORY_IO_ERROR)
            if (previous.status != TerminalTaskStatus.RUNNING || revision <= previous.outputRevision) return@withLock
            persist(previous.copy(
                output = boundTerminalOutput(output), outputRevision = revision,
                updatedAt = maxOf(clock(), previous.updatedAt), historyError = null
            ))
        }
    }

    internal suspend fun finish(historyId: String, output: String, revision: Long, exitCode: Int) =
        terminalState(historyId, output, revision, exitCode)

    internal suspend fun unknown(historyId: String, output: String, revision: Long) =
        terminalState(historyId, output, revision, null)

    private suspend fun terminalState(historyId: String, output: String, revision: Long, exitCode: Int?) = withContext(ioDispatcher) {
        requireTerminalHistoryId(historyId)
        require(revision >= 0)
        mutex.withLock {
            loadLocked()
            checkLoaded()
            val previous = records[historyId] ?: throw IOException(TERMINAL_HISTORY_IO_ERROR)
            if (previous.status != TerminalTaskStatus.RUNNING) return@withLock
            val now = maxOf(clock(), previous.updatedAt)
            val next = previous.copy(
                status = if (exitCode == null) TerminalTaskStatus.UNKNOWN else TerminalTaskStatus.FINISHED,
                output = if (revision >= previous.outputRevision) boundTerminalOutput(output) else previous.output,
                outputRevision = maxOf(revision, previous.outputRevision),
                updatedAt = now, finishedAt = if (exitCode == null) null else now,
                exitCode = exitCode, historyError = null
            )
            try {
                persist(next)
            } finally {
                trimFinished()
            }
        }
    }

    private fun loadLocked() {
        if (initialized) return
        initialized = true
        try {
            for (id in files.ids()) {
                val record = files.read(id)
                record.validate()
                check(record.historyId == id) { "Terminal history file identity mismatch" }
                records[id] = record
            }
        } catch (cancelled: CancellationException) {
            initialized = false
            throw cancelled
        } catch (_: SerializationException) {
            loadError = TERMINAL_HISTORY_CORRUPT_ERROR
        } catch (_: IllegalArgumentException) {
            loadError = TERMINAL_HISTORY_CORRUPT_ERROR
        } catch (_: Exception) {
            loadError = TERMINAL_HISTORY_IO_ERROR
        }
        for (record in records.values.toList()) {
            if (record.status != TerminalTaskStatus.RUNNING) continue
            val unknown = record.copy(status = TerminalTaskStatus.UNKNOWN, updatedAt = maxOf(clock(), record.updatedAt))
            try {
                persist(unknown)
            } catch (_: IOException) {
                publish()
            }
        }
        if (loadError == null) trimFinished()
        publish()
    }

    private fun persist(record: TerminalTaskRecord) {
        record.validate()
        records[record.historyId] = record
        try {
            files.write(record)
            writeErrors -= record.historyId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            writeErrors += record.historyId
            records[record.historyId] = record.copy(historyError = TERMINAL_HISTORY_IO_ERROR)
            publish()
            throw IOException(TERMINAL_HISTORY_IO_ERROR)
        }
        publish()
    }

    private fun trimFinished() {
        val obsolete = records.values.filter { it.status == TerminalTaskStatus.FINISHED }
            .sortedWith(compareByDescending<TerminalTaskRecord> { it.finishedAt }.thenByDescending { it.startedAt }.thenBy { it.historyId })
            .drop(MAX_FINISHED_RECORDS)
        cleanupError = null
        for (record in obsolete) {
            try {
                files.delete(record.historyId)
                records.remove(record.historyId)
                writeErrors -= record.historyId
            } catch (_: Exception) {
                cleanupError = TERMINAL_HISTORY_IO_ERROR
            }
        }
        publish()
    }

    private fun checkLoaded() {
        if (loadError != null) throw IOException(loadError)
    }

    private fun checkReadable() {
        checkLoaded()
        _state.value.error?.let { throw IOException(it) }
    }

    private fun orderedRecords(): List<TerminalTaskRecord> = records.values
        .sortedWith(compareByDescending<TerminalTaskRecord> { it.startedAt }.thenBy { it.historyId })

    private fun publish() {
        _state.value = TerminalTaskHistoryState(
            loaded = initialized,
            records = orderedRecords(),
            error = loadError ?: cleanupError ?: TERMINAL_HISTORY_IO_ERROR.takeIf { writeErrors.isNotEmpty() }
        )
    }

    companion object {
        const val MAX_FINISHED_RECORDS = 50
    }
}

internal interface TerminalTaskHistoryFiles {
    fun ids(): List<String>
    fun read(historyId: String): TerminalTaskRecord
    fun write(record: TerminalTaskRecord)
    fun delete(historyId: String)
}

internal class AtomicTerminalTaskHistoryFiles(private val root: File) : TerminalTaskHistoryFiles {
    private val json = Json { encodeDefaults = true }

    override fun ids(): List<String> {
        directory()
        return (root.listFiles() ?: throw IOException(TERMINAL_HISTORY_IO_ERROR)).map { file ->
            val base = file.name.removeSuffix(".bak").removeSuffix(".new")
            require(base.endsWith(".json")) { "Invalid terminal history filename" }
            base.removeSuffix(".json").also { checked(it) }
        }.distinct().sorted()
    }

    override fun read(historyId: String): TerminalTaskRecord {
        val file = checked(historyId)
        val bytes = AtomicFile(file).openRead().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > MAX_RECORD_BYTES) throw IOException(TERMINAL_HISTORY_CORRUPT_ERROR)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        return json.decodeFromString<TerminalTaskRecord>(bytes.toString(Charsets.UTF_8)).also { it.validate() }
    }

    override fun write(record: TerminalTaskRecord) {
        record.validate()
        val file = checked(record.historyId)
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        check(read(record.historyId) == record) { "Terminal history atomic write unavailable" }
    }

    override fun delete(historyId: String) {
        val file = checked(historyId)
        AtomicFile(file).delete()
        check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { it.exists() }) {
            "Terminal history cleanup unavailable"
        }
    }

    private fun directory() {
        require(root.absoluteFile == root.canonicalFile && !Files.isSymbolicLink(root.toPath()))
        if (!root.exists() && !root.mkdirs()) throw IOException(TERMINAL_HISTORY_IO_ERROR)
        check(Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory)
    }

    private fun checked(historyId: String): File {
        requireTerminalHistoryId(historyId)
        directory()
        return File(root, "$historyId.json").also { file ->
            for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
                require(candidate.canonicalFile.parentFile == root && !Files.isSymbolicLink(candidate.toPath()))
                if (Files.exists(candidate.toPath(), NOFOLLOW_LINKS)) {
                    check(Files.readAttributes(candidate.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile)
                }
            }
        }
    }

    private companion object {
        const val MAX_RECORD_BYTES = 2 * 1024 * 1024
    }
}
