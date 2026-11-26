package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
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

    private val gate = Any()
    private var entries = linkedMapOf<String, ChrootRecoveryEntry>()
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()
    private val _blockedInstances = MutableStateFlow<Set<String>>(emptySet())
    val blockedInstances: StateFlow<Set<String>> = _blockedInstances.asStateFlow()
    private val _recordedLaunchIds = MutableStateFlow<Set<String>>(emptySet())
    val recordedLaunchIds: StateFlow<Set<String>> = _recordedLaunchIds.asStateFlow()

    init {
        synchronized(gate) {
            try {
                if (File(file.path + ".pending").exists()) throw IOException("Runtime journal has an unfinished write")
                if (file.exists()) entries = readLocked()
                val recovered = entries.mapValuesTo(linkedMapOf()) { (_, entry) ->
                    if (entry.phase == ChrootRecoveryPhase.CLOSED && entry.cleanup == ChrootCleanupResult.VERIFIED) {
                        entry
                    } else {
                        entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
                    }
                }
                if (recovered != entries) {
                    entries = recovered
                    writeLocked(entries)
                }
            } catch (_: Exception) {
                _isHealthy.value = false
            }
            publishLocked()
        }
    }

    fun snapshot(): List<ChrootRecoveryEntry> = synchronized(gate) { entries.values.toList() }

    fun canStart(instanceId: String): Boolean = synchronized(gate) {
        _isHealthy.value && entries.values.none {
            it.instanceId == instanceId && it.phase == ChrootRecoveryPhase.UNKNOWN
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
            _isHealthy.value = false
            publishLocked()
            throw IOException("Runtime journal persistence is unavailable", e)
        }
    }

    private fun checkHealthyLocked() {
        if (!_isHealthy.value) throw IOException("Runtime journal requires explicit recovery")
    }

    private fun publishLocked() {
        _blockedInstances.value = entries.values.filter { it.phase == ChrootRecoveryPhase.UNKNOWN }
            .mapTo(linkedSetOf()) { it.instanceId }.toSet()
        _recordedLaunchIds.value = entries.keys.toSet()
    }

    private fun readLocked(): LinkedHashMap<String, ChrootRecoveryEntry> {
        if (!file.isFile || file.length() !in 12..MAX_FILE_BYTES) throw IOException("Invalid runtime journal size")
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid runtime journal version")
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Invalid runtime journal entry count")
            val loaded = linkedMapOf<String, ChrootRecoveryEntry>()
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
                    !owner.matches(Regex("[0-9a-f]{64}")) || generation < 0 || launch in loaded ||
                    (phase == ChrootRecoveryPhase.CLOSED && cleanup != ChrootCleanupResult.VERIFIED) ||
                    (phase == ChrootRecoveryPhase.UNKNOWN && cleanup != ChrootCleanupResult.UNKNOWN)
                ) throw IOException("Invalid runtime journal entry")
                loaded[launch] = ChrootRecoveryEntry(instance, launch, rootfs, kind, owner, generation, phase, cleanup)
            }
            if (input.read() != -1) throw IOException("Trailing runtime journal bytes")
            loaded
        }
    }

    private fun writeLocked(updated: Map<String, ChrootRecoveryEntry>) {
        val parent = file.parentFile ?: throw IOException("Runtime journal has no directory")
        val created = !parent.exists()
        if ((!parent.isDirectory && !parent.mkdirs()) || file.isDirectory) throw IOException("Runtime journal directory is unavailable")
        if (created) parent.parentFile?.let(syncDirectory)
        val pending = File(file.path + ".pending")
        if (pending.exists()) throw IOException("Runtime journal has an unfinished write")
        FileOutputStream(pending).use { stream ->
            val output = DataOutputStream(stream)
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
            output.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(parent)
    }

    private companion object {
        const val MAGIC = 0x414a434a
        const val VERSION = 1
        const val MAX_ENTRIES = 4096
        const val MAX_FILE_BYTES = 2_000_000L
        val nonce = Regex("[0-9a-f]{24}")

        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun ownerKind(owner: String): String = owner.substringBefore(':').takeIf {
            it.matches(Regex("[a-z]{1,24}")) && it in setOf("chat", "terminal", "file", "maintenance", "user")
        } ?: "owner"
    }
}
