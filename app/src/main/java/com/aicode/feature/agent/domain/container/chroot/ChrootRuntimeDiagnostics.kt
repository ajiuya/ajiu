package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import javax.inject.Inject
import javax.inject.Singleton

internal enum class ChrootStopTrigger { STOP, TASK_EXIT, CLEANUP, HELPER_EXIT }

/** Frozen when the stop deadline claims the terminal state; never used to authorize recovery. */
internal data class ChrootCompletionEvidence(val trigger: ChrootStopTrigger, val flags: Int) {
    init {
        require(flags >= 0 && flags and ALL_FLAGS == flags)
        require(flags and CLEANUP_VERIFIED == 0 || flags and CLEANUP_SEEN != 0)
        require(flags and HELPER_STATUS_VERIFIED == 0 || flags and HELPER_EXITED != 0)
    }

    fun pending(): String = buildList {
        if (flags and CLEANUP_SEEN == 0) add("CLEANUP")
        else if (flags and CLEANUP_VERIFIED == 0) add("CLEANUP_VERIFIED")
        if (flags and STDOUT_ENDED == 0) add("STDOUT_EOF")
        if (flags and STDERR_ENDED == 0) add("STDERR_EOF")
        if (flags and HELPER_EXITED == 0) add("HELPER_EXIT")
        else if (flags and HELPER_STATUS_VERIFIED == 0) add("HELPER_STATUS")
    }.joinToString(",").ifEmpty { "NONE" }

    companion object {
        const val READY = 1
        const val GO = 2
        const val TASK_EXIT = 4
        const val CLEANUP_SEEN = 8
        const val CLEANUP_VERIFIED = 16
        const val STDOUT_ENDED = 32
        const val STDERR_ENDED = 64
        const val HELPER_EXITED = 128
        const val HELPER_STATUS_VERIFIED = 256
        const val ALL_FLAGS = 511
    }
}

internal data class ChrootRuntimeDiagnostic(
    val instanceId: String,
    val launchId: String,
    val failure: ChrootRuntimeFailure?,
    val nativeFailure: ChrootRuntimeFailure?,
    val cleanup: ChrootCleanupResult?,
    val completionEvidence: ChrootCompletionEvidence? = null
) {
    fun summary(): String = buildString {
        append("launch=").append(launchId)
        failure?.let {
            append(";failure=").append(it.kind.name).append('/').append(it.stage.name)
            it.journalFailure?.let { journal -> append(';').append(journal.summary()) }
        }
        nativeFailure?.let {
            append(";native=").append(it.nativeCode).append('/').append(it.nativeStage)
            append(";errno=").append(it.errno)
        }
        append(";cleanup=").append(cleanup?.name ?: "UNRECORDED")
        completionEvidence?.let {
            append(";trigger=").append(it.trigger.name)
            append(";proof=").append(it.flags)
            append(";pending=").append(it.pending())
        }
    }
}

/** Diagnostic evidence only: this file must never authorize a launch or verify recovery. */
@Singleton
internal class ChrootRuntimeDiagnostics internal constructor(
    private val file: File,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "chroot/runtime-failures.bin"),
        { directory ->
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("Invalid diagnostic directory")
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    )

    private val gate = Any()
    @Volatile private var entries: Map<String, ChrootRuntimeDiagnostic> = emptyMap()
    @Volatile var isHealthy: Boolean = true
        private set

    init {
        try {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) entries = read()
            // A crash may leave a pending update next to valid committed evidence. Keep both;
            // disable further writes, but do not hide the last committed failure.
            if (Files.exists(pending.toPath(), NOFOLLOW_LINKS)) isHealthy = false
        } catch (_: Exception) {
            isHealthy = false
        }
    }

    // Reads are memory-only, including when a caller holds the Root authority lock.
    fun snapshot(): List<ChrootRuntimeDiagnostic> = entries.values.toList()

    fun latestForLaunches(instanceId: String, launchIds: Set<String>): ChrootRuntimeDiagnostic? = entries.values.lastOrNull {
        it.instanceId == instanceId && it.launchId in launchIds
    }

    fun latestUnknown(instanceId: String): ChrootRuntimeDiagnostic? = entries.values.lastOrNull {
        it.instanceId == instanceId && it.cleanup == ChrootCleanupResult.UNKNOWN
    }

    fun record(
        instanceId: String,
        launchId: String,
        failure: ChrootRuntimeFailure? = null,
        nativeFailure: ChrootRuntimeFailure? = null,
        cleanup: ChrootCleanupResult? = null,
        completionEvidence: ChrootCompletionEvidence? = null
    ) = synchronized(gate) {
        require(NONCE.matches(instanceId) && NONCE.matches(launchId))
        require(nativeFailure == null || nativeFailure.nativeFailureObserved)
        val previous = entries[launchId]
        require(previous == null || previous.instanceId == instanceId)
        if (previous == null && failure == null && nativeFailure == null && cleanup != ChrootCleanupResult.UNKNOWN) {
            return@synchronized
        }
        val firstFailure = previous?.failure?.let {
            if (it.journalFailure == null && failure?.journalFailure != null) it.copy(journalFailure = failure.journalFailure) else it
        } ?: failure
        val next = ChrootRuntimeDiagnostic(
            instanceId, launchId, firstFailure, previous?.nativeFailure ?: nativeFailure,
            if (previous?.cleanup == ChrootCleanupResult.UNKNOWN) ChrootCleanupResult.UNKNOWN else cleanup ?: previous?.cleanup,
            previous?.completionEvidence ?: completionEvidence
        )
        if (previous == next) return@synchronized
        val updated = LinkedHashMap(entries).apply {
            put(launchId, next)
            while (size > MAX_ENTRIES) remove(keys.first())
        }
        entries = updated
        if (isHealthy) {
            try {
                write(updated)
            } catch (_: Exception) {
                // Keep bounded in-memory evidence, report unavailable persistence, and leave runtime decisions alone.
                isHealthy = false
            }
        }
    }

    private val pending get() = File(file.path + ".pending")

    private fun read(): Map<String, ChrootRuntimeDiagnostic> {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || Files.size(file.toPath()) !in 12..MAX_FILE_BYTES) {
            throw IOException("Invalid diagnostic file")
        }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val input = DataInputStream(Channels.newInputStream(channel))
            if (input.readInt() != MAGIC) throw IOException("Invalid diagnostic version")
            val version = input.readInt()
            if (version !in 1..VERSION) throw IOException("Invalid diagnostic version")
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Invalid diagnostic count")
            val result = linkedMapOf<String, ChrootRuntimeDiagnostic>()
            repeat(count) {
                val instanceId = input.readUTF()
                val launchId = input.readUTF()
                val failure = input.readFailure(version)
                val nativeFailure = input.readFailure(version)
                val cleanup = input.readUTF().let { if (it.isEmpty()) null else ChrootCleanupResult.valueOf(it) }
                val evidence = if (version >= 2 && input.readBoolean()) {
                    ChrootCompletionEvidence(ChrootStopTrigger.valueOf(input.readUTF()), input.readInt())
                } else null
                if (!NONCE.matches(instanceId) || !NONCE.matches(launchId) || launchId in result ||
                    (nativeFailure != null && !nativeFailure.nativeFailureObserved) ||
                    (failure == null && nativeFailure == null && cleanup != ChrootCleanupResult.UNKNOWN)) {
                    throw IOException("Invalid diagnostic entry")
                }
                result[launchId] = ChrootRuntimeDiagnostic(instanceId, launchId, failure, nativeFailure, cleanup, evidence)
            }
            if (input.read() != -1) throw IOException("Trailing diagnostic bytes")
            result
        }
    }

    private fun write(updated: Map<String, ChrootRuntimeDiagnostic>) {
        val parent = file.parentFile ?: throw IOException("Missing diagnostic directory")
        Files.createDirectories(parent.toPath())
        if (!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid diagnostic directory")
        FileChannel.open(pending.toPath(), setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val output = DataOutputStream(Channels.newOutputStream(channel))
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(updated.size)
            updated.values.forEach {
                output.writeUTF(it.instanceId)
                output.writeUTF(it.launchId)
                output.writeFailure(it.failure)
                output.writeFailure(it.nativeFailure)
                output.writeUTF(it.cleanup?.name.orEmpty())
                output.writeBoolean(it.completionEvidence != null)
                it.completionEvidence?.let { evidence ->
                    output.writeUTF(evidence.trigger.name)
                    output.writeInt(evidence.flags)
                }
            }
            output.flush()
            if (channel.size() > MAX_FILE_BYTES) throw IOException("Diagnostic size exceeded")
            channel.force(true)
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(parent)
    }

    private fun DataOutputStream.writeFailure(failure: ChrootRuntimeFailure?) {
        writeBoolean(failure != null)
        if (failure == null) return
        writeUTF(failure.kind.name)
        writeUTF(failure.stage.name)
        if (failure.nativeFailureObserved) {
            writeUTF("code=${failure.nativeCode};stage=${failure.nativeStage};errno=${failure.errno}")
        }
        writeBoolean(failure.journalFailure != null)
        failure.journalFailure?.let { journal ->
            writeUTF(journal.reason.name)
            writeBoolean(journal.errno != null)
            journal.errno?.let { writeInt(it) }
        }
    }

    private fun DataInputStream.readFailure(version: Int): ChrootRuntimeFailure? {
        if (!readBoolean()) return null
        val kind = ChrootRuntimeFailureKind.valueOf(readUTF())
        val stage = ChrootRuntimeFailureStage.valueOf(readUTF())
        val failure = if (kind == ChrootRuntimeFailureKind.NATIVE_ERROR) {
            val parsed = ChrootRuntimeFailure.nativeError(readUTF().toByteArray(Charsets.UTF_8))
            if (!parsed.nativeFailureObserved || parsed.stage != stage) throw IOException("Invalid native diagnostic")
            parsed
        } else ChrootRuntimeFailure.local(kind, stage)
        val journalFailure = if (version >= 3 && readBoolean()) {
            ChrootJournalFailure(ChrootJournalFailureReason.valueOf(readUTF()), if (readBoolean()) readInt() else null)
        } else null
        return failure.copy(journalFailure = journalFailure)
    }

    internal companion object {
        const val MAX_ENTRIES = 64
        private const val MAX_FILE_BYTES = 65_536L
        private const val MAGIC = 0x414a4446
        private const val VERSION = 3
        private val NONCE = Regex("[0-9a-f]{24}")
    }
}
