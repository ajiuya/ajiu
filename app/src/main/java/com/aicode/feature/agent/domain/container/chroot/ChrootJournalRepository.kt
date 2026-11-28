package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Historical IDs live in an indexed private SQLite database, not an in-memory lifetime set.
 * The old path is a small version-two identity marker. Each transaction uses a small durable
 * pending record so even an ambiguous cleanup COMMIT cannot become VERIFIED after a restart.
 */
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
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }
    )

    private data class Image(val bytes: ByteArray, val key: String)
    private data class Pending(val storeId: String, val operation: String, val entry: ChrootRecoveryEntry)
    private val gate = Any()
    private val parent get() = file.parentFile ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    private val databaseFile get() = File(file.path + ".sqlite")
    private val pendingFile get() = File(file.path + ".pending")
    private val failedFile get() = File(file.path + ".failed")
    private val backupFile get() = File(file.path + ".legacy-v1")
    private val database get() = ChrootJournalDatabase(databaseFile, storeId)
    private var marker: Image? = null
    private var directoryKey: String? = null
    private var databaseKey: String? = null
    private var storeId: String? = null
    private var initialized = false
    private var legacy: ChrootLegacyRecoveryJournal? = null
    private val unresolved = linkedMapOf<String, ChrootRecoveryEntry>()
    @Volatile private var unknowns: Map<String, Set<String>> = emptyMap()
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()
    private val _canRepairInterruptedWrite = MutableStateFlow(false)
    val canRepairInterruptedWrite: StateFlow<Boolean> = _canRepairInterruptedWrite.asStateFlow()
    private val _blockedInstances = MutableStateFlow<Set<String>>(emptySet())
    val blockedInstances: StateFlow<Set<String>> = _blockedInstances.asStateFlow()
    private val _lastFailure = MutableStateFlow<ChrootJournalFailure?>(null)
    val lastFailure: StateFlow<ChrootJournalFailure?> = _lastFailure.asStateFlow()

    init {
        synchronized(gate) {
            // A new installation is lazy: a cancelled request before INTENT creates no journal.
            try {
                if (exists(file) || exists(databaseFile) || exists(pendingFile) || exists(backupFile) || exists(failedFile)) loadLocked()
            } catch (failure: Exception) { failLocked(failure, ChrootJournalFailureReason.OPEN) }
        }
    }

    fun unknownLaunchIds(instanceId: String): Set<String> = unknowns[instanceId].orEmpty()
    @Suppress("UNUSED_PARAMETER") fun canStart(instanceId: String): Boolean = _isHealthy.value

    /** Diagnostic/test enumeration only; production authorization uses an indexed point query. */
    fun snapshot(): List<ChrootRecoveryEntry> = synchronized(gate) {
        if (legacy != null) return@synchronized legacy!!.snapshot()
        if (!initialized) return@synchronized unresolved.values.toList()
        try {
            checkIdentityLocked()
            val rows = database.snapshot().associateByTo(linkedMapOf()) { it.launchId }
            unresolved.forEach { (id, entry) -> rows[id] = entry }
            rows.values.toList()
        } catch (failure: Exception) {
            failLocked(failure, ChrootJournalFailureReason.READ)
            unresolved.values.toList()
        }
    }

    fun recordedLaunchIds(): Set<String> = snapshot().mapTo(linkedSetOf()) { it.launchId }

    fun containsLaunchId(launchId: String): Boolean = synchronized(gate) {
        require(NONCE.matches(launchId))
        checkHealthyLocked()
        try {
            if (!initialized && !exists(file)) return@synchronized false
            if (!initialized) loadLocked()
            checkIdentityLocked()
            database.contains(launchId)
        } catch (failure: Exception) { throw failLocked(failure, ChrootJournalFailureReason.READ) }
    }

    fun recordIntent(instanceId: String, launchId: String, rootfsIdentity: ChrootFileIdentity, ownerIdentity: String, rootGeneration: Long) =
        synchronized(gate) {
            require(NONCE.matches(instanceId) && NONCE.matches(launchId))
            require(ownerIdentity.isNotBlank() && ownerIdentity.length <= 512 && rootGeneration >= 0)
            checkHealthyLocked()
            try {
                if (!initialized) loadLocked()
                checkIdentityLocked()
                if (database.contains(launchId)) throw ChrootJournalException(ChrootJournalFailureReason.REPLAY)
                val kind = ownerIdentity.substringBefore(':').takeIf { it in OWNER_KINDS } ?: "owner"
                val entry = ChrootRecoveryEntry(instanceId, launchId, rootfsIdentity, kind, hash(ownerIdentity.toByteArray()),
                    rootGeneration, ChrootRecoveryPhase.INTENT, null)
                writeLocked(Pending(checkNotNull(storeId), "INTENT", entry)) { verify ->
                    database.insert(entry, verify)
                }
                unresolved[launchId] = entry
            } catch (failure: Exception) {
                if (failure is ChrootJournalException && failure.reason == ChrootJournalFailureReason.REPLAY) throw failure
                throw failLocked(failure, ChrootJournalFailureReason.WRITE)
            }
        }

    fun recordPhase(launchId: String, phase: ChrootRecoveryPhase) = synchronized(gate) {
        require(phase == ChrootRecoveryPhase.STARTING || phase == ChrootRecoveryPhase.RUNNING)
        updateLocked(launchId, phase, null)
    }

    fun recordCleanup(launchId: String, result: ChrootCleanupResult) = synchronized(gate) {
        updateLocked(launchId, if (result == ChrootCleanupResult.VERIFIED) ChrootRecoveryPhase.CLOSED else ChrootRecoveryPhase.UNKNOWN, result)
    }

    private fun updateLocked(launchId: String, phase: ChrootRecoveryPhase, cleanup: ChrootCleanupResult?) {
        require(NONCE.matches(launchId))
        checkHealthyLocked()
        try {
            if (!initialized) loadLocked()
            checkIdentityLocked()
            val previous = database.find(launchId) ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            if (previous.phase == ChrootRecoveryPhase.UNKNOWN || previous.phase == ChrootRecoveryPhase.CLOSED ||
                previous.phase == ChrootRecoveryPhase.RUNNING && phase == ChrootRecoveryPhase.STARTING) return
            val next = previous.copy(phase = phase, cleanup = cleanup)
            writeLocked(Pending(checkNotNull(storeId), "UPDATE", previous)) { verify ->
                database.update(launchId, phase, cleanup, verify)
            }
            if (phase == ChrootRecoveryPhase.CLOSED) unresolved.remove(launchId) else unresolved[launchId] = next
            publishLocked()
        } catch (failure: Exception) { throw failLocked(failure, ChrootJournalFailureReason.WRITE) }
    }

    private fun loadLocked() {
        ensureDirectoryLocked()
        if (exists(failedFile)) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (!exists(file)) {
            // An existing database/backup without its identity marker must never look like a
            // fresh install. Do not erase history or resurrect a stale version-one snapshot.
            if (exists(databaseFile) || exists(backupFile) || exists(pendingFile)) {
                throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
            }
            val empty = bytes { writeInt(MAGIC); writeInt(1); writeInt(0) }
            publishFile(file, empty, replace = false)
        }
        val image = readImage(file, LEGACY_BYTES)
        val version = DataInputStream(ByteArrayInputStream(image.bytes)).use { input ->
            if (input.readInt() != MAGIC) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            input.readInt()
        }
        if (version == 1) migrateLocked(image)
        else if (version == 2) {
            storeId = decodeMarker(image.bytes)
            marker = image
            checkDatabaseFiles()
            databaseKey = regularKey(databaseFile)
            // SQLite may need a write-capable connection to roll back a crashed transaction.
            // Permit that only with validated evidence from this exact store; the live pending
            // name remains a barrier and still requires explicit user repair afterward.
            val rollbackEvidence = if (exists(pendingFile)) {
                readImage(pendingFile, SMALL_BYTES).also { evidence ->
                    if (decodePending(evidence.bytes).storeId != storeId) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
                }
            } else null
            checkIdentityLocked()
            val identity = database.identity(rollbackEvidence != null && exists(File(databaseFile.path + "-journal")))
                ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            checkIdentityLocked()
            if (rollbackEvidence != null) requireImage(pendingFile, rollbackEvidence, SMALL_BYTES)
            if (identity.storeId != storeId) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        } else throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        initialized = true
        checkIdentityLocked()
        if (exists(pendingFile)) {
            val pending = readPendingLocked()
            unresolved.clear()
            database.snapshot(unresolvedOnly = true).forEach { unresolved[it.launchId] = unknown(it) }
            unresolved[pending.entry.launchId] = unknown(database.find(pending.entry.launchId) ?: pending.entry)
            throw ChrootJournalException(ChrootJournalFailureReason.INTERRUPTED)
        }
        // Restarted INTENT/STARTING/RUNNING rows are uncertain, never replayed. This transaction
        // updates only the indexed unresolved rows; CLOSED history is neither read nor rewritten.
        val running = database.snapshot(unresolvedOnly = true)
        if (running.any { it.phase != ChrootRecoveryPhase.UNKNOWN }) {
            val first = running.first { it.phase != ChrootRecoveryPhase.UNKNOWN }
            writeLocked(Pending(checkNotNull(storeId), "RECOVER", first)) { verify -> database.recover(null, verify) }
        }
        unresolved.clear()
        database.snapshot(unresolvedOnly = true).forEach { unresolved[it.launchId] = it }
        publishLocked()
        legacy = null
        _lastFailure.value = null
    }

    private fun migrateLocked(source: Image) {
        val old = ChrootLegacyRecoveryJournal(file, rewriteOnLoad = false, syncDirectory = syncDirectory)
        legacy = old
        if (!old.isHealthy.value) {
            _canRepairInterruptedWrite.value = old.canRepairInterruptedWrite.value
            throw ChrootJournalException(if (exists(pendingFile)) ChrootJournalFailureReason.INTERRUPTED else ChrootJournalFailureReason.CORRUPT)
        }
        requireImage(file, source, LEGACY_BYTES)
        val sourceHash = hash(source.bytes)
        if (exists(backupFile)) {
            if (!readImage(backupFile, LEGACY_BYTES).bytes.contentEquals(source.bytes)) {
                throw ChrootJournalException(ChrootJournalFailureReason.MIGRATION)
            }
        } else publishFile(backupFile, source.bytes, replace = false)
        requireImage(file, source, LEGACY_BYTES)
        checkDatabaseFiles(allowMissing = true)
        val migratingKey = if (exists(databaseFile)) regularKey(databaseFile) else null
        val backup = readImage(backupFile, LEGACY_BYTES)
        var identity = if (migratingKey != null) {
            // Only migration may have a hot SQLite journal without an application pending:
            // no task can run until publication, and the immutable source/backup still match.
            database.identity(recoverRollbackJournal = exists(File(databaseFile.path + "-journal"))).also {
                requireImage(file, source, LEGACY_BYTES)
                requireImage(backupFile, backup, LEGACY_BYTES)
                if (regularKey(databaseFile) != migratingKey) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
            }
        } else null
        if (identity == null) {
            identity = ChrootJournalDatabase.Identity(nonce(), sourceHash)
            database.initialize(identity, old.snapshot())
            Files.setPosixFilePermissions(databaseFile.toPath(), PRIVATE_FILE)
            syncChecked(parent)
        }
        if (identity.sourceHash != sourceHash || !NONCE.matches(identity.storeId)) {
            throw ChrootJournalException(ChrootJournalFailureReason.MIGRATION)
        }
        val pinnedDatabaseKey = regularKey(databaseFile)
        if (database.identity() != identity || regularKey(databaseFile) != pinnedDatabaseKey) {
            throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        }
        requireImage(file, source, LEGACY_BYTES)
        if (exists(pendingFile)) throw ChrootJournalException(ChrootJournalFailureReason.INTERRUPTED)
        storeId = identity.storeId
        databaseKey = pinnedDatabaseKey
        publishFile(file, encodeMarker(identity.storeId), replace = true)
        marker = readImage(file, SMALL_BYTES)
        if (regularKey(databaseFile) != pinnedDatabaseKey || database.identity() != identity) {
            throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        }
        legacy = null
    }

    /** Called only with launch exclusion held by the facade. Never replays any task. */
    fun repairInterruptedWriteFromUser(): Unit = synchronized(gate) {
        if (_isHealthy.value || !_canRepairInterruptedWrite.value) throw ChrootJournalException(ChrootJournalFailureReason.INTERRUPTED)
        _canRepairInterruptedWrite.value = false
        val old = legacy
        if (old != null) {
            try {
                old.repairInterruptedWriteFromUser()
                legacy = null
                loadLocked()
                _isHealthy.value = true
                _lastFailure.value = null
            } catch (failure: Exception) { throw failLocked(failure, ChrootJournalFailureReason.MIGRATION) }
            return@synchronized
        }
        var original: Image? = null
        try {
            checkIdentityLocked()
            original = readImage(pendingFile, SMALL_BYTES)
            val pending = decodePending(original.bytes)
            if (pending.storeId != storeId) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
            val archive = archivePending(original)
            database.recover(pending.entry) { checkIdentityLocked(); requireImage(pendingFile, original, SMALL_BYTES) }
            requireImage(pendingFile, original, SMALL_BYTES)
            Files.move(pendingFile.toPath(), File(archive, "pending-original.bin").toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncChecked(archive)
            syncChecked(parent)
            checkIdentityLocked()
            unresolved.clear()
            database.snapshot(unresolvedOnly = true).forEach { unresolved[it.launchId] = it }
            _isHealthy.value = true
            _lastFailure.value = null
            publishLocked()
        } catch (failure: Exception) {
            if (original != null) {
                try { restorePending(original.bytes) } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
            }
            throw failLocked(failure, ChrootJournalFailureReason.WRITE)
        }
    }

    private fun writeLocked(pending: Pending, action: (() -> Unit) -> Unit) {
        checkIdentityLocked()
        if (exists(pendingFile)) throw ChrootJournalException(ChrootJournalFailureReason.INTERRUPTED)
        val encoded = encodePending(pending)
        var published = false
        try {
            publishFile(pendingFile, encoded, replace = false)
            published = true
            val expected = readImage(pendingFile, SMALL_BYTES)
            if (!expected.bytes.contentEquals(encoded)) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
            val verify = { checkIdentityLocked(); requireImage(pendingFile, expected, SMALL_BYTES) }
            action(verify)
            verify()
            Files.delete(pendingFile.toPath())
            syncChecked(parent)
            checkIdentityLocked()
        } catch (failure: Exception) {
            if (published || exists(pendingFile)) {
                try { restorePending(encoded) } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
            }
            throw failure
        }
    }

    private fun failLocked(failure: Exception, fallback: ChrootJournalFailureReason): ChrootJournalException {
        val typed = journalException(failure, fallback)
        _isHealthy.value = false
        _lastFailure.value = typed.diagnostic
        unresolved.replaceAll { _, entry -> unknown(entry) }
        if (initialized) {
            // Reload only indexed unresolved rows. Keep in-memory uncertainty if a failed
            // cleanup transaction committed CLOSED before the pending marker was retired.
            runCatching {
                checkIdentityLocked()
                database.snapshot(unresolvedOnly = true).forEach { unresolved.putIfAbsent(it.launchId, unknown(it)) }
                if (exists(pendingFile)) readPendingLocked().let { pending ->
                    unresolved[pending.entry.launchId] = unknown(database.find(pending.entry.launchId) ?: pending.entry)
                }
            }
        }
        _canRepairInterruptedWrite.value = legacy?.canRepairInterruptedWrite?.value ?: runCatching {
            checkIdentityLocked()
            readPendingLocked()
            checkArchiveBudget()
            true
        }.getOrDefault(false)
        publishLocked()
        return typed
    }

    private fun checkHealthyLocked() {
        if (!_isHealthy.value) throw ChrootJournalException(_lastFailure.value?.reason ?: ChrootJournalFailureReason.INTERRUPTED,
            _lastFailure.value?.errno)
    }

    private fun publishLocked() {
        val entries = legacy?.snapshot() ?: unresolved.values
        unknowns = entries.filter { it.phase == ChrootRecoveryPhase.UNKNOWN }.groupBy { it.instanceId }
            .mapValues { (_, rows) -> rows.mapTo(linkedSetOf()) { it.launchId }.toSet() }
        _blockedInstances.value = unknowns.keys.toSet()
    }

    private fun readPendingLocked(): Pending {
        val pending = decodePending(readImage(pendingFile, SMALL_BYTES).bytes)
        if (pending.storeId != storeId) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        return pending
    }

    private fun checkIdentityLocked() {
        if (exists(failedFile)) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (directoryKey == null || regularDirectoryKey(parent) != directoryKey) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        val expected = marker ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        requireImage(file, expected, SMALL_BYTES)
        checkDatabaseFiles()
        if (databaseKey == null || regularKey(databaseFile) != databaseKey) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    }

    private fun checkDatabaseFiles(allowMissing: Boolean = false) {
        if (!allowMissing || exists(databaseFile)) regularKey(databaseFile)
        // SQLite must never follow a planted rollback journal or sidecar link.
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val sidecar = File(databaseFile.path + suffix)
            if (exists(sidecar)) regularKey(sidecar)
        }
    }

    private fun ensureDirectoryLocked() {
        if (!exists(parent)) {
            val ancestor = parent.parentFile ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
            regularDirectoryKey(ancestor)
            Files.createDirectory(parent.toPath(), PosixFilePermissions.asFileAttribute(PRIVATE_DIR))
            syncChecked(ancestor)
        }
        directoryKey = regularDirectoryKey(parent)
    }

    private fun syncChecked(directory: File) {
        val before = regularDirectoryKey(directory)
        try { syncDirectory(directory) } catch (failure: Exception) { throw journalException(failure, ChrootJournalFailureReason.SYNC) }
        if (regularDirectoryKey(directory) != before) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    }

    private fun publishFile(target: File, bytes: ByteArray, replace: Boolean) {
        regularDirectoryKey(target.parentFile)
        if (!replace && exists(target)) throw ChrootJournalException(ChrootJournalFailureReason.INTERRUPTED)
        val staging = Files.createTempFile(target.parentFile.toPath(), ".${target.name}-", ".stage",
            PosixFilePermissions.asFileAttribute(PRIVATE_FILE))
        try {
            FileChannel.open(staging, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (replace) Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncChecked(target.parentFile)
        } finally { Files.deleteIfExists(staging) }
    }

    private fun restorePending(bytes: ByteArray) {
        // A changed main/database identity is precisely why a transaction can fail. Retain the
        // original transaction evidence in the still-pinned private directory even in that case;
        // requiring the changed marker here could lose uncertainty after a cleanup COMMIT.
        if (directoryKey == null || regularDirectoryKey(parent) != directoryKey) {
            throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        }
        if (!exists(pendingFile)) publishFile(pendingFile, bytes, replace = false)
        else if (!runCatching { readImage(pendingFile, SMALL_BYTES).bytes.contentEquals(bytes) }.getOrDefault(false)) {
            // Keep both conflicting pieces of evidence. A different live pending record must
            // not make the original transaction's identity disappear across a restart.
            if (!exists(failedFile)) publishFile(failedFile, bytes, replace = false)
            throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        }
    }

    private fun archivePending(image: Image): File {
        checkArchiveBudget()
        val archive = Files.createTempDirectory(parent.toPath(), "${file.name}.recovery-", PosixFilePermissions.asFileAttribute(PRIVATE_DIR)).toFile()
        publishFile(File(archive, "pending.bin"), image.bytes, replace = false)
        publishFile(File(archive, "sha256.txt"), "${hash(image.bytes)}  pending.bin\n".toByteArray(Charsets.US_ASCII), replace = false)
        syncChecked(parent)
        return archive
    }

    private fun checkArchiveBudget() {
        var count = 0
        Files.newDirectoryStream(parent.toPath()).use { stream ->
            stream.forEach { path ->
                if (path.fileName.toString().startsWith("${file.name}.recovery-")) {
                    regularDirectoryKey(path.toFile())
                    if (++count >= 16) throw ChrootJournalException(ChrootJournalFailureReason.WRITE)
                }
            }
        }
    }

    private fun requireImage(target: File, expected: Image, limit: Long) {
        val actual = readImage(target, limit)
        if (actual.key != expected.key || !actual.bytes.contentEquals(expected.bytes)) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    }

    private fun readImage(target: File, limit: Long): Image {
        val dir = regularDirectoryKey(target.parentFile)
        val before = attributes(target.toPath()) ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() !in 1..limit) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        val key = before.fileKey()?.toString() ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        val bytes = FileChannel.open(target.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val out = ByteArrayOutputStream(before.size().toInt())
            val buffer = java.nio.ByteBuffer.allocate(8192)
            while (true) {
                buffer.clear()
                val size = channel.read(buffer)
                if (size < 0) break
                if (out.size().toLong() + size > limit) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
                out.write(buffer.array(), 0, size)
            }
            out.toByteArray()
        }
        val after = attributes(target.toPath()) ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.size() != after.size() || bytes.size.toLong() != after.size() || dir != regularDirectoryKey(target.parentFile)) {
            throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        }
        return Image(bytes, key)
    }

    private fun regularDirectoryKey(directory: File): String {
        val attrs = attributes(directory.toPath()) ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (!attrs.isDirectory || attrs.isSymbolicLink) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        return attrs.fileKey()?.toString() ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    }

    private fun regularKey(target: File): String {
        val attrs = attributes(target.toPath()) ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        if (!attrs.isRegularFile || attrs.isSymbolicLink) throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
        return attrs.fileKey()?.toString() ?: throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
    private fun exists(target: File): Boolean = attributes(target.toPath()) != null

    private fun encodeMarker(id: String): ByteArray = bytes { writeInt(MAGIC); writeInt(2); writeUTF(id) }
    private fun decodeMarker(encoded: ByteArray): String = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != 2) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        val id = input.readUTF()
        if (!NONCE.matches(id) || input.read() != -1) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        id
    }

    private fun encodePending(pending: Pending): ByteArray {
        val body = bytes {
            writeInt(PENDING_MAGIC); writeInt(1); writeUTF(pending.storeId); writeUTF(pending.operation)
            pending.entry.let { entry ->
                writeUTF(entry.instanceId); writeUTF(entry.launchId)
                writeLong(entry.rootfsIdentity.device); writeLong(entry.rootfsIdentity.inode)
                writeUTF(entry.ownerKind); writeUTF(entry.ownerHash); writeLong(entry.rootGeneration)
                writeByte(entry.phase.ordinal); writeByte(entry.cleanup?.ordinal ?: -1)
            }
        }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun decodePending(encoded: ByteArray): Pending {
        if (encoded.size < 40) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        val body = encoded.copyOfRange(0, encoded.size - 32)
        if (!MessageDigest.getInstance("SHA-256").digest(body).contentEquals(encoded.copyOfRange(body.size, encoded.size))) {
            throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            if (input.readInt() != PENDING_MAGIC || input.readInt() != 1) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            val id = input.readUTF()
            val operation = input.readUTF()
            val instanceId = input.readUTF()
            val launchId = input.readUTF()
            val identity = ChrootFileIdentity(input.readLong(), input.readLong())
            val kind = input.readUTF()
            val owner = input.readUTF()
            val generation = input.readLong()
            val phase = ChrootRecoveryPhase.entries.getOrNull(input.readUnsignedByte())
                ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            val cleanupCode = input.readByte().toInt()
            val cleanup = if (cleanupCode == -1) null else ChrootCleanupResult.entries.getOrNull(cleanupCode)
                ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            if (!NONCE.matches(id) || !NONCE.matches(instanceId) || !NONCE.matches(launchId) || operation !in setOf("INTENT", "UPDATE", "RECOVER") ||
                !kind.matches(Regex("[a-z]{1,24}")) || !owner.matches(Regex("[0-9a-f]{64}")) || generation < 0 ||
                phase == ChrootRecoveryPhase.CLOSED && cleanup != ChrootCleanupResult.VERIFIED ||
                phase == ChrootRecoveryPhase.UNKNOWN && cleanup != ChrootCleanupResult.UNKNOWN ||
                phase.ordinal < 3 && cleanup != null ||
                operation == "INTENT" && phase != ChrootRecoveryPhase.INTENT || input.read() != -1) {
                throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            }
            Pending(id, operation, ChrootRecoveryEntry(instanceId, launchId, identity, kind, owner, generation, phase, cleanup))
        }
    }

    private companion object {
        const val MAGIC = 0x414a434a
        const val PENDING_MAGIC = 0x414a5458
        const val LEGACY_BYTES = 2_000_000L
        const val SMALL_BYTES = 4096L
        val NONCE = Regex("[0-9a-f]{24}")
        val OWNER_KINDS = setOf("chat", "terminal", "file", "maintenance", "user")
        val PRIVATE_FILE = PosixFilePermissions.fromString("rw-------")
        val PRIVATE_DIR = PosixFilePermissions.fromString("rwx------")
        val random = SecureRandom()
        fun nonce(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun unknown(entry: ChrootRecoveryEntry) = entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use(write) }.toByteArray()
    }
}
