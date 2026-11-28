package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.net.Uri
import android.os.Process as AndroidProcess
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeRemoval
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PRIVATE_ROOTFS_IMAGE_BYTES = 1024L * 1024 * 1024

internal class ChrootRemovalBusyException : IOException("CHROOT instance is still in use; retry removal when its tasks have stopped")

internal fun openChrootRootfsSource(
    value: String,
    filesDir: File,
    openContent: (String) -> InputStream
): InputStream {
    val uri = try { URI(value) } catch (failure: Exception) { throw IOException("Invalid archive URI", failure) }
    if (uri.scheme == "content" && !uri.authority.isNullOrBlank()) return openContent(value)
    if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
        throw IOException("Only content URIs or private rootfs image files are supported")
    }
    val base = filesDir.toPath().toAbsolutePath()
    val directory = base.resolve("rootfs_images")
    val path = try { File(uri).toPath().toAbsolutePath() } catch (failure: Exception) {
        throw IOException("Invalid private archive file", failure)
    }
    if (base != base.normalize() || path != path.normalize() || path.parent != directory) {
        throw IOException("Archive file is outside the private rootfs image directory")
    }
    fun snapshot(): List<Any> {
        var ancestor = directory.root
        directory.forEach { part ->
            ancestor = ancestor.resolve(part)
            if (!Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) {
                throw IOException("Archive directory contains a symbolic link or non-directory")
            }
        }
        val owner = Files.getOwner(base, NOFOLLOW_LINKS)
        if (Files.getOwner(directory, NOFOLLOW_LINKS) != owner || Files.getOwner(path, NOFOLLOW_LINKS) != owner) {
            throw IOException("Archive file is not owned by the App")
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in 1..MAX_PRIVATE_ROOTFS_IMAGE_BYTES ||
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() != 1L
        ) throw IOException("Archive must be a bounded ordinary private file without hardlinks")
        return listOf(
            attributes.fileKey() ?: throw IOException("Archive identity is unavailable"),
            attributes.size(), attributes.lastModifiedTime(), Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS),
            Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                ?: throw IOException("Archive directory identity is unavailable")
        )
    }
    val expected = snapshot()
    val channel = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS))
    try {
        val frozenDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive opening was stopped")
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0 || size + count > MAX_PRIVATE_ROOTFS_IMAGE_BYTES) throw IOException("Invalid archive stream length")
            size += count
            frozenDigest.update(buffer.array(), 0, count)
        }
        if (snapshot() != expected || size != expected[1]) throw IOException("Private archive changed while opening")
        val hash = frozenDigest.digest()
        channel.position(0)
        return object : FilterInputStream(Channels.newInputStream(channel)) {
            private val digest = MessageDigest.getInstance("SHA-256")
            private var readBytes = 0L
            private var verified = false
            private var closed = false

            private fun recordRead(count: Int) {
                if (count < 0 && !verified) {
                    if (readBytes != size || !MessageDigest.isEqual(hash, digest.digest()) || snapshot() != expected) {
                        throw IOException("Private archive identity or content changed")
                    }
                    verified = true
                } else if (count > 0) {
                    readBytes += count
                    if (readBytes > size) throw IOException("Private archive grew while reading")
                }
            }

            override fun read(): Int = `in`.read().also { value ->
                if (value >= 0) digest.update(value.toByte())
                recordRead(if (value < 0) -1 else 1)
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                `in`.read(bytes, offset, length).also { count ->
                    if (count > 0) digest.update(bytes, offset, count)
                    recordRead(count)
                }

            override fun skip(count: Long): Long {
                var remaining = count.coerceAtLeast(0)
                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val read = read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                    if (read < 0) break
                    remaining -= read
                }
                return count.coerceAtLeast(0) - remaining
            }

            override fun markSupported(): Boolean = false
            override fun mark(readlimit: Int) = Unit
            override fun reset(): Unit = throw IOException("Private archive stream cannot be rewound")

            override fun close() {
                if (closed) return
                closed = true
                try {
                    if (!verified || snapshot() != expected) throw IOException("Private archive was not fully verified")
                } finally {
                    super.close()
                }
            }
        }
    } catch (failure: Throwable) {
        channel.close()
        throw failure
    }
}

@Singleton
class ChrootInstanceInstaller internal constructor(
    private val authority: RootAuthorityCoordinator,
    private val archiveWriter: ChrootArchiveWriter,
    private val start: (String, ChrootFileIdentity?) -> Process,
    private val inputProvider: (RootfsSource) -> InputStream,
    private val filesDir: File,
    private val journalFile: File,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val appUid: () -> Int,
    private val newId: () -> String = ::randomId,
    private val transactionTimeoutMs: Long = 40_000L,
    private val rollbackTimeoutMs: Long = 5_000L,
    private val startBound: ((String, ChrootFileIdentity?, ChrootRuntimeSession) -> Process)? = null,
    private val checkRecoverySession: ((ChrootRuntimeSession) -> Unit)? = null,
    private val startRecoveryBound: ((String, ChrootFileIdentity, ChrootRuntimeSession, () -> Unit) -> Process)? = null,
    private val recoveryTimeoutMs: Long = transactionTimeoutMs
) {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        authority: RootAuthorityCoordinator,
        factory: ChrootRuntimeProcessFactory,
        archiveWriter: ChrootArchiveWriter,
        containerInstaller: ContainerInstaller
    ) : this(
        authority, archiveWriter, { _, _ -> throw ChrootRuntimeUnavailable("Installer requires the user-authorized Root session") },
        { source ->
            when (source) {
                is RootfsSource.Asset -> {
                    if (source.path != "alpine-rootfs.bin") throw IOException("Unsupported bundled chroot archive")
                    context.assets.open(containerInstaller.ASSET_ROOTFS)
                }
                is RootfsSource.LocalFile -> {
                    openChrootRootfsSource(source.uri, context.filesDir) { value ->
                        context.contentResolver.openInputStream(Uri.parse(value))
                            ?: throw IOException("Archive content is unavailable")
                    }
                }
                is RootfsSource.RemoteSsh -> throw IOException("Remote sources cannot install a local chroot")
            }
        },
        context.filesDir, File(context.noBackupFilesDir, "chroot/installer-pending.bin"),
        CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO,
        System::currentTimeMillis, AndroidProcess::myUid,
        startBound = { id, identity, session -> factory.startInstaller(id, identity, session) },
        checkRecoverySession = factory::checkSession,
        startRecoveryBound = factory::startRecoveryRemoval,
        // Removal inspects every host process twice before unlinking. Its native deadline is
        // longer than installation's; leave time to collect the final evidence and exit status.
        recoveryTimeoutMs = 100_000L
    )

    private data class Pending(
        val id: String,
        val remove: Boolean,
        val generation: Long,
        val device: Long,
        val inode: Long,
        val recover: Boolean = false
    )

    private data class NativeResult(
        val passed: Boolean,
        val cleanup: String,
        val path: String?,
        val device: Long,
        val inode: Long,
        val entries: Long,
        val fileBytes: Long,
        val stage: String,
        val error: Int
    )

    private data class Exchange(
        val exit: Int,
        val result: NativeResult,
        val archive: ChrootArchiveWriter.Result?,
        val sendFailure: Throwable?
    )

    private inner class Transaction(val id: String) {
        val stopped = AtomicBoolean()
        val done = CompletableDeferred<Unit>()
        val source = AtomicReference<InputStream?>()
        val stdin = AtomicReference<OutputStream?>()
        val sender = AtomicReference<Deferred<ChrootArchiveWriter.Result?>?>()
        @Volatile var cleanupVerified = false
        // A verified receipt settles this helper transaction, not the unknown instance's cleanup.
        @Volatile var recoveryReceiptVerified = false
        @Volatile var process: Process? = null
        @Volatile var startAttempted = false

        fun stopOnly() {
            stopped.set(true)
            scope.launch(dispatcher) {
                sender.get()?.cancel()
                closeInput()
            }
        }

        fun checkSending() {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Installation was stopped")
        }

        fun closeInput() {
            runCatching { stdin.getAndSet(null)?.close() }
            runCatching { source.getAndSet(null)?.close() }
        }
    }

    private val transactionMutex = Mutex()
    private val journalGate = Any()
    private var pending = linkedMapOf<String, Pending>()
    private val activeIds = HashSet<String>()
    private val usedIds = HashSet<String>()
    private var lastGeneration = 0L
    private val _failedInstances = MutableStateFlow<Set<String>>(emptySet())
    val failedInstances: StateFlow<Set<String>> = _failedInstances.asStateFlow()
    private val _unpublishedInstalls = MutableStateFlow<Set<String>>(emptySet())
    val unpublishedInstalls: StateFlow<Set<String>> = _unpublishedInstalls.asStateFlow()
    private val _removalPending = MutableStateFlow<Set<String>>(emptySet())
    // Pending deletion survives an App restart; it is a retry hint, never proof of cleanup.
    val removalPending: StateFlow<Set<String>> = _removalPending.asStateFlow()
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()

    init {
        require(transactionTimeoutMs in 1..40_000L && rollbackTimeoutMs in 1..5_000L && recoveryTimeoutMs in 1..100_000L)
        synchronized(journalGate) {
            try {
                if (Files.exists(File(journalFile.path + ".pending").toPath(), NOFOLLOW_LINKS)) {
                    throw IOException("Installer journal has an unfinished write")
                }
                if (Files.exists(journalFile.toPath(), NOFOLLOW_LINKS)) pending = readJournal()
                usedIds.addAll(pending.keys)
                lastGeneration = pending.values.maxOfOrNull { it.generation } ?: 0L
            } catch (_: Exception) {
                _isHealthy.value = false
            }
            publishFailures()
        }
    }

    internal suspend fun installFromUser(
        profile: ContainerProfile,
        expectedRoot: RootAccessState,
        expectedSession: ChrootRuntimeSession? = null,
        publishInstalled: suspend (ChrootInstance) -> Unit = {}
    ): ChrootInstance =
        withContext(dispatcher) {
            require(profile.isChroot && profile.chrootInstance == null)
            require(PROFILE_ID.matches(profile.id) && profile.id != "." && profile.id != "..")
            require(profile.rootfsSource !is RootfsSource.RemoteSsh)
            require(expectedRoot.enabled && expectedRoot.authorityId.isNotBlank() && expectedRoot.generation >= 0)
            transactionMutex.withLock {
                ensureCurrent(expectedRoot)
                if (unpublishedInstalls.value.isNotEmpty()) throw IOException("An unpublished CHROOT installation requires explicit recovery")
                val id = synchronized(journalGate) {
                    checkHealthy()
                    var candidate: String? = null
                    repeat(8) {
                        if (candidate == null) newId().also {
                            require(NONCE.matches(it))
                            if (it !in usedIds) candidate = it
                        }
                    }
                    (candidate ?: throw IOException("Could not allocate a fresh instance ID")).also(usedIds::add)
                }
                val generation = synchronized(journalGate) {
                    if (lastGeneration == Long.MAX_VALUE) throw IOException("Install generation exhausted")
                    maxOf(1L, clock(), lastGeneration + 1).also { lastGeneration = it }
                }
                transact(Pending(id, false, generation, 0, 0), profile.rootfsSource, expectedRoot, expectedSession, publishInstalled) { exchange ->
                    val result = exchange.result
                    if (exchange.exit != 0 || !result.passed || result.cleanup != "installed" ||
                        result.path != rootPath(id) || result.device <= 0 || result.inode <= 0 ||
                        exchange.sendFailure != null || exchange.archive == null || exchange.archive.entryCount == 0 ||
                        result.entries != exchange.archive.nativeEntryCount.toLong() || result.fileBytes != exchange.archive.fileBytes
                    ) throw IOException("Native installation did not provide a verified installed instance")
                    ChrootInstance(id, result.device, result.inode, generation).also {
                        synchronized(journalGate) {
                            persist(LinkedHashMap(pending).apply {
                                put(id, Pending(id, false, generation, result.device, result.inode))
                            })
                        }
                        prepareHome(it)
                    }
                }
            }
        }

    internal suspend fun removeFromUser(
        instance: ChrootInstance,
        expectedRoot: RootAccessState,
        expectedSession: ChrootRuntimeSession? = null,
        publishRemoved: suspend () -> Unit = {}
    ) = withContext(dispatcher) {
        validateInstance(instance)
        require(expectedRoot.enabled && expectedRoot.authorityId.isNotBlank() && expectedRoot.generation >= 0)
        transactionMutex.withLock {
            ensureCurrent(expectedRoot)
            transact(Pending(instance.id, true, instance.installGeneration, instance.device, instance.inode), null, expectedRoot, expectedSession, { _: Unit -> publishRemoved() }) { exchange ->
                val result = exchange.result
                if (exchange.exit != 0 || !result.passed || result.cleanup != "removed" ||
                    result.path != rootPath(instance.id) || result.device != instance.device || result.inode != instance.inode ||
                    exchange.sendFailure != null
                ) throw IOException("Native removal did not provide verified identity-bound cleanup")
                Unit
            }
        }
    }

    internal suspend fun recoverAndRemoveFromUser(
        instance: ChrootInstance,
        expectedRoot: RootAccessState,
        expectedSession: ChrootRuntimeSession,
        checkCurrent: () -> Unit = {},
        publishRemoved: suspend () -> Unit = {}
    ): LocalRuntimeRemoval = withContext(dispatcher) {
        validateInstance(instance)
        require(expectedRoot.enabled && expectedRoot.authorityId.isNotBlank() && expectedRoot.generation >= 0)
        transactionMutex.withLock {
            checkCurrent()
            ensureCurrent(expectedRoot)
            requireRecoverySession(expectedRoot, expectedSession)
            val intent = Pending(instance.id, true, instance.installGeneration, instance.device, instance.inode, recover = true)
            transact(intent, null, expectedRoot, expectedSession, { removal: LocalRuntimeRemoval ->
                if (removal == LocalRuntimeRemoval.REMOVED) publishRemoved()
            }, checkRecoveryCurrent = checkCurrent) { exchange ->
                when {
                    verifiedRecoveryReceipt(intent, exchange) -> LocalRuntimeRemoval.RETRY_REQUIRED
                    exchange.sendFailure == null && verifiedCleanup(intent, exchange) -> LocalRuntimeRemoval.REMOVED
                    exchange.sendFailure == null && exchange.exit != 0 && !exchange.result.passed &&
                        exchange.result.stage == "recovery_busy" && exchange.result.error == 16 &&
                        exchange.result.cleanup == "cleanup_unknown" && exchange.result.path == rootPath(instance.id) &&
                        exchange.result.device == instance.device && exchange.result.inode == instance.inode ->
                        throw ChrootRemovalBusyException()
                    else -> throw IOException("Native recovery removal did not provide verified identity-bound evidence; " +
                        "stage=${exchange.result.stage}; errno=${exchange.result.error}; exit=${exchange.exit}")
                }
            }
        }
    }

    private fun requireRecoverySession(expectedRoot: RootAccessState, expectedSession: ChrootRuntimeSession?): ChrootRuntimeSession {
        val session = expectedSession ?: throw ChrootRuntimeUnavailable("Recovery removal requires the user-authorized Root session")
        if (session.rootState != expectedRoot) throw ChrootRuntimeUnavailable("Recovery removal Root session changed")
        checkRecoverySession?.invoke(session)
        return session
    }

    fun prepareHome(instance: ChrootInstance): File {
        validateInstance(instance)
        if (appUid() < 10_000) throw IOException("Chroot home requires an application identity")
        synchronized(journalGate) {
            checkHealthy()
            if (instance.id in _failedInstances.value) throw IOException("Instance requires explicit recovery")
        }
        requireDirectory(filesDir, filesDir, create = false)
        val homeBase = requireDirectory(File(filesDir, "chroot-home"), filesDir)
        val dnsBase = requireDirectory(File(filesDir, "chroot-dns"), filesDir)
        val marker = "${instance.id}\n${instance.device}\n${instance.inode}\n${instance.installGeneration}\n".toByteArray(Charsets.US_ASCII)
        val home = instanceDirectory(homeBase, instance.id, marker)
        val dns = instanceDirectory(dnsBase, instance.id, marker)
        requireDirectory(File(home, "workspace"), filesDir)
        val config = requireDirectory(File(home, ".aicode"), filesDir)
        CONFIG_DIRECTORIES.forEach { requireDirectory(File(config, it), filesDir) }
        prepareDns(File(dns, "resolv.conf"))
        return home
    }

    private suspend fun <T> transact(
        intent: Pending,
        source: RootfsSource?,
        expectedRoot: RootAccessState,
        expectedSession: ChrootRuntimeSession?,
        publish: suspend (T) -> Unit,
        checkRecoveryCurrent: () -> Unit = {},
        accept: (Exchange) -> T
    ): T {
        if (intent.recover) beginRecoveryIntent(intent) else beginIntent(intent)
        val tx = Transaction(intent.id)
        var registration: AutoCloseable? = null
        var settled = false
        var publicationPending = false
        var tasks: List<Deferred<*>> = emptyList()
        var stdout: Deferred<ByteArray>? = null
        var stderr: Deferred<ByteArray>? = null
        var exited: Deferred<Int>? = null
        suspend fun collect(): Exchange {
            val sent = runCatching { tx.sender.get()?.await() }
            val code = exited?.await() ?: throw IOException("Installer was not started")
            val output = stdout?.await() ?: throw IOException("Installer output is unavailable")
            stderr?.await() ?: throw IOException("Installer error output is unavailable")
            return Exchange(code, nativeResult(output), sent.getOrNull(), sent.exceptionOrNull())
        }
        try {
            authority.accessSnapshot()
            val process = authority.withCurrent(expectedRoot.authorityId, expectedRoot.generation) {
                registration = authority.registerRevocation(
                    onRevoke = tx::stopOnly,
                    awaitStopped = {
                        withTimeout(rollbackTimeoutMs) { tx.done.await() }
                        if (!tx.cleanupVerified && !tx.recoveryReceiptVerified) throw IOException("Installer cleanup remains unknown")
                    }
                )
                tx.checkSending()
                if (!intent.recover) tx.startAttempted = true
                val removeIdentity = if (intent.remove) ChrootFileIdentity(intent.device, intent.inode) else null
                val spawned = if (intent.recover) {
                    val starter = startRecoveryBound
                        ?: throw ChrootRuntimeUnavailable("Recovery removal requires its dedicated native starter")
                    val session = requireRecoverySession(expectedRoot, expectedSession)
                    checkRecoveryCurrent()
                    tx.startAttempted = true
                    starter(intent.id, checkNotNull(removeIdentity), session, checkRecoveryCurrent)
                } else if (startBound == null) start(intent.id, removeIdentity) else {
                    startBound.invoke(intent.id, removeIdentity, expectedSession
                        ?: throw ChrootRuntimeUnavailable("Installer requires the user-authorized Root session"))
                }
                spawned.also {
                    tx.process = it
                    tx.stdin.set(it.outputStream)
                }
            } ?: throw IOException("Root authority changed before installer start")
            stdout = scope.async(dispatcher) { readBounded(process.inputStream, MAX_STDOUT, tx) }
            stderr = scope.async(dispatcher) { readBounded(process.errorStream, MAX_STDERR, tx) }
            exited = scope.async(dispatcher) { runInterruptible { process.waitFor() } }
            val sender = scope.async(dispatcher) {
                try {
                    runInterruptible {
                        tx.checkSending()
                        if (source == null) {
                            // Recovery evidence cannot hide an unsuccessful stdin/EOF exchange.
                            if (intent.recover) tx.stdin.getAndSet(null)?.close()
                            null
                        } else sendArchive(tx, source)
                    }
                } catch (e: Throwable) {
                    tx.stopOnly()
                    throw e
                } finally {
                    tx.closeInput()
                }
            }
            tx.sender.set(sender)
            tasks = listOfNotNull(stdout, stderr, exited, sender)
            if (tx.stopped.get()) tx.stopOnly()
            val exchange = withTimeout(if (intent.remove) recoveryTimeoutMs else transactionTimeoutMs) { collect() }
            tx.cleanupVerified = verifiedCleanup(intent, exchange)
            tx.recoveryReceiptVerified = verifiedRecoveryReceipt(intent, exchange)
            if (intent.remove && tx.cleanupVerified) publicationPending = true
            if (tx.stopped.get()) throw IOException("Root authority was revoked during installation")
            if (intent.recover) checkRecoveryCurrent()
            val accepted = accept(exchange)
            publicationPending = true
            ensureCurrent(expectedRoot)
            if (intent.recover) {
                requireRecoverySession(expectedRoot, expectedSession)
                checkRecoveryCurrent()
            }
            tx.checkSending()
            if (!tx.recoveryReceiptVerified) withTimeout(transactionTimeoutMs) { publish(accepted) }
            return authority.withCurrent(expectedRoot.authorityId, expectedRoot.generation) {
                if (tx.stopped.get()) throw IOException("Root authority was revoked before publication")
                if (intent.recover) {
                    requireRecoverySession(expectedRoot, expectedSession)
                    // Removal publication changes the profile revision itself; only the receipt
                    // branch, which never published a profile change, still checks the old source.
                    if (tx.recoveryReceiptVerified) checkRecoveryCurrent()
                }
                finishIntent(intent.id, retain = tx.recoveryReceiptVerified)
                if (!tx.recoveryReceiptVerified) tx.cleanupVerified = true
                settled = true
                publicationPending = false
                accepted
            } ?: throw IOException("Root authority changed before publication")
        } catch (failure: Throwable) {
            tx.stopOnly()
            withContext(NonCancellable) {
                if (tx.startAttempted && !tx.cleanupVerified && !tx.recoveryReceiptVerified && tasks.isNotEmpty()) {
                    val rollback = withTimeoutOrNull(rollbackTimeoutMs) { runCatching { collect() }.getOrNull() }
                    if (rollback != null) {
                        tx.cleanupVerified = verifiedCleanup(intent, rollback)
                        tx.recoveryReceiptVerified = verifiedRecoveryReceipt(intent, rollback)
                        if (intent.remove && tx.cleanupVerified) publicationPending = true
                    }
                }
                if (!tx.startAttempted) tx.cleanupVerified = true
                if (!settled) {
                    try {
                        finishIntent(intent.id, retain = intent.recover || publicationPending || !tx.cleanupVerified)
                    } catch (journalFailure: Exception) {
                        failure.addSuppressed(journalFailure)
                    }
                }
            }
            throw failure
        } finally {
            tx.done.complete(Unit)
            registration?.close()
            tasks.forEach { it.cancel() }
            scope.launch(dispatcher) {
                tx.closeInput()
                runCatching { tx.process?.inputStream?.close() }
                runCatching { tx.process?.errorStream?.close() }
            }
        }
    }

    private fun sendArchive(tx: Transaction, source: RootfsSource): ChrootArchiveWriter.Result {
        tx.checkSending()
        val raw = inputProvider(source)
        tx.source.set(raw)
        raw.use {
            val input = PushbackInputStream(object : FilterInputStream(raw) {
                override fun read(): Int { tx.checkSending(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    tx.checkSending()
                    return super.read(bytes, offset, length)
                }
            }, 6)
            val signature = ByteArray(6)
            var count = 0
            while (count < signature.size) {
                val read = input.read(signature, count, signature.size - count)
                if (read <= 0) break
                count += read
            }
            input.unread(signature, 0, count)
            val format = when {
                count >= 2 && signature[0] == 31.toByte() && signature[1] == 139.toByte() -> ContainerInstaller.CompressedFormat.GZIP
                count == 6 && signature.contentEquals(byteArrayOf(253.toByte(), 55, 122, 88, 90, 0)) -> ContainerInstaller.CompressedFormat.XZ
                else -> throw IOException("Only gzip or xz tar archives are supported")
            }
            val nativeInput = tx.stdin.get() ?: throw IOException("Installer input is closed")
            val output = object : FilterOutputStream(nativeInput) {
                override fun write(value: Int) { tx.checkSending(); out.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    tx.checkSending()
                    out.write(bytes, offset, length)
                }
            }
            return archiveWriter.write(input, format, output).also { tx.checkSending(); output.flush() }
        }
    }

    private suspend fun readBounded(input: InputStream, limit: Int, tx: Transaction): ByteArray = runInterruptible {
        try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size() + count > limit) throw IOException("Installer output exceeds its bound")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } catch (failure: Throwable) {
            tx.stopOnly()
            throw failure
        }
    }

    private fun nativeResult(bytes: ByteArray): NativeResult {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val keys = Regex("\"([a-z_]+)\"\\s*:").findAll(text).map { it.groupValues[1] }.toList()
        if (keys.size != RESULT_KEYS.size || keys.toSet() != RESULT_KEYS ||
            text.count { it == '{' } != 1 || text.count { it == '}' } != 1 ||
            text.any { it.code == 92 || it == '[' || it == ']' }
        ) throw IOException("Invalid or duplicate native installer fields")
        val result = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid native installer result")
        if (result.keys != RESULT_KEYS) throw IOException("Incomplete native installer result")
        fun primitive(key: String) = result[key] as? JsonPrimitive ?: throw IOException("Invalid native result field")
        fun number(key: String): Long {
            val value = primitive(key)
            if (value.isString) throw IOException("Invalid native result number")
            return value.longOrNull ?: throw IOException("Invalid native result number")
        }
        fun string(key: String): String {
            val value = primitive(key)
            if (!value.isString) throw IOException("Invalid native result string")
            return value.content
        }
        val passed = primitive("passed").let {
            if (it.isString) throw IOException("Invalid native passed flag")
            it.booleanOrNull ?: throw IOException("Invalid native passed flag")
        }
        val path = primitive("created_directory").let {
            if (!it.isString && it.content == "null") null else string("created_directory")
        }
        val entries = number("entries")
        val fileBytes = number("file_bytes")
        val device = number("device")
        val inode = number("inode")
        val error = number("errno")
        if (!string("stage").matches(Regex("[a-z_]{1,64}")) || entries !in 0..65_536L ||
            fileBytes !in 0..512L * 1024 * 1024 || device < 0 || inode < 0 || error !in 0..Int.MAX_VALUE.toLong() ||
            passed && error != 0L
        ) throw IOException("Native result is outside protocol bounds")
        return NativeResult(passed, string("cleanup_state"), path, device, inode, entries, fileBytes, string("stage"), error.toInt())
    }

    private fun verifiedCleanup(intent: Pending, exchange: Exchange): Boolean {
        val result = exchange.result
        if (intent.recover && exchange.sendFailure != null) return false
        if (intent.remove) return exchange.exit == 0 && result.passed && result.cleanup == "removed" &&
            result.path == rootPath(intent.id) && result.device == intent.device && result.inode == intent.inode
        if (exchange.exit == 0 || result.passed) return false
        return when (result.cleanup) {
            "not_created" -> result.path == null && result.device == 0L && result.inode == 0L
            "removed" -> result.path == rootPath(intent.id) && result.device > 0 && result.inode > 0
            else -> false
        }
    }

    private fun verifiedRecoveryReceipt(intent: Pending, exchange: Exchange): Boolean {
        // Legacy installers may only acknowledge the durable deletion barrier. Keep the
        // instance pending for a direct retry; this response never publishes a removal.
        val result = exchange.result
        return intent.recover && exchange.exit == 0 && exchange.sendFailure == null && result.passed &&
            result.cleanup == "reboot_required" && result.path == rootPath(intent.id) &&
            result.device == intent.device && result.inode == intent.inode
    }

    private suspend fun ensureCurrent(expected: RootAccessState) {
        authority.accessSnapshot()
        if (authority.withCurrent(expected.authorityId, expected.generation) { true } != true) {
            throw IOException("Current enabled Root authority is required")
        }
    }

    private fun beginIntent(intent: Pending) = synchronized(journalGate) {
        checkHealthy()
        if (intent.id in pending || pending.size >= MAX_PENDING) throw IOException("Instance requires explicit recovery")
        recordActiveIntent(intent)
    }

    private fun beginRecoveryIntent(intent: Pending) = synchronized(journalGate) {
        checkHealthy()
        require(intent.recover && intent.remove)
        val previous = pending[intent.id]
        val sameRemoval = previous != null && previous.remove &&
            previous.id == intent.id && previous.generation == intent.generation &&
            previous.device == intent.device && previous.inode == intent.inode
        if (intent.id in activeIds || previous != null && !sameRemoval ||
            previous == null && pending.size >= MAX_PENDING
        ) throw IOException("Recovery removal requires the exact pending instance identity")
        usedIds.add(intent.id)
        recordActiveIntent(intent)
    }

    // Called only under journalGate. Ordinary intents still cannot resume any pending transaction.
    private fun recordActiveIntent(intent: Pending) {
        activeIds.add(intent.id)
        try {
            persist(LinkedHashMap(pending).apply { put(intent.id, intent) })
        } catch (failure: Exception) {
            activeIds.remove(intent.id)
            publishFailures()
            throw failure
        }
    }

    private fun finishIntent(id: String, retain: Boolean) = synchronized(journalGate) {
        activeIds.remove(id)
        val next = LinkedHashMap(pending)
        if (!retain) next.remove(id)
        persist(next)
        publishFailures()
    }

    private fun persist(next: LinkedHashMap<String, Pending>) {
        try {
            checkHealthy()
            writeJournal(next)
            pending = next
        } catch (failure: Exception) {
            pending = LinkedHashMap(pending).apply { putAll(next) }
            _isHealthy.value = false
            throw IOException("Installer journal requires explicit recovery", failure)
        } finally {
            publishFailures()
        }
    }

    private fun publishFailures() {
        _failedInstances.value = pending.values.filter { it.recover || it.id !in activeIds }.mapTo(linkedSetOf()) { it.id }
        _unpublishedInstalls.value = pending.values.filter { !it.remove && it.id !in activeIds }.mapTo(linkedSetOf()) { it.id }
        _removalPending.value = pending.values.filter { it.recover && it.id !in activeIds }.mapTo(linkedSetOf()) { it.id }
    }

    private fun checkHealthy() {
        if (!_isHealthy.value) throw IOException("Installer journal requires explicit recovery")
    }

    private fun readJournal(): LinkedHashMap<String, Pending> {
        val attributes = Files.readAttributes(journalFile.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in 12..MAX_JOURNAL_BYTES) throw IOException("Invalid installer journal")
        return DataInputStream(FileInputStream(journalFile)).use { input ->
            if (input.readInt() != JOURNAL_MAGIC) throw IOException("Invalid installer journal magic")
            val version = input.readInt()
            if (version !in 1..JOURNAL_VERSION) throw IOException("Invalid installer journal version")
            val count = input.readInt()
            if (count !in 0..MAX_PENDING) throw IOException("Invalid installer journal count")
            val loaded = linkedMapOf<String, Pending>()
            repeat(count) {
                val id = input.readUTF()
                val operation = input.readUnsignedByte()
                val generation = input.readLong()
                val device = input.readLong()
                val inode = input.readLong()
                val maximumOperation = if (version == 1) 1 else 2
                if (!NONCE.matches(id) || id in loaded || operation !in 0..maximumOperation || generation <= 0 || device < 0 || inode < 0 ||
                    operation != 0 && (device <= 0 || inode <= 0) || operation == 0 && ((device == 0L) != (inode == 0L))
                ) throw IOException("Invalid installer journal entry")
                loaded[id] = Pending(id, operation != 0, generation, device, inode, recover = operation == 2)
            }
            if (input.read() != -1) throw IOException("Trailing installer journal bytes")
            loaded
        }
    }

    private fun writeJournal(entries: Map<String, Pending>) {
        val parent = journalFile.parentFile ?: throw IOException("Installer journal directory is unavailable")
        if (!parent.exists()) Files.createDirectories(parent.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if (!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(journalFile.toPath())) {
            throw IOException("Installer journal is not an ordinary App file")
        }
        val temporary = File(journalFile.path + ".pending")
        Files.createFile(temporary.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        FileOutputStream(temporary).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(JOURNAL_MAGIC)
            output.writeInt(JOURNAL_VERSION)
            output.writeInt(entries.size)
            entries.values.forEach {
                output.writeUTF(it.id)
                output.writeByte(if (it.recover) 2 else if (it.remove) 1 else 0)
                output.writeLong(it.generation)
                output.writeLong(it.device)
                output.writeLong(it.inode)
            }
            output.flush()
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), journalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun instanceDirectory(base: File, id: String, marker: ByteArray): File {
        val directory = File(base, id)
        val exists = Files.exists(directory.toPath(), NOFOLLOW_LINKS)
        requireDirectory(directory, filesDir)
        val identity = File(directory, ".instance")
        if (exists && !Files.exists(identity.toPath(), NOFOLLOW_LINKS)) throw IOException("Existing instance home has no matching identity")
        fixedFile(identity, marker)
        return directory
    }

    private fun requireDirectory(file: File, ownerRoot: File, create: Boolean = true): File {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS) && create) {
            Files.createDirectory(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || Files.getOwner(file.toPath(), NOFOLLOW_LINKS) != Files.getOwner(ownerRoot.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Chroot home is not an owned ordinary App directory")
        }
        return file
    }

    private fun prepareDns(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            fixedFile(file, DNS.toByteArray(Charsets.US_ASCII))
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in 1..MAX_DNS_BYTES.toLong() ||
            Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(filesDir.toPath(), NOFOLLOW_LINKS) ||
            (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xfff != 0x180
        ) throw IOException("Existing App DNS file is unsafe; it was not replaced")
        val text = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(MAX_DNS_BYTES + 1)
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Incomplete App DNS file read")
            }
            if (buffer.position() !in 1..MAX_DNS_BYTES) throw IOException("App DNS file exceeds its bound")
            String(buffer.array(), 0, buffer.position(), Charsets.US_ASCII)
        }
        if (!validDnsContents(text)) throw IOException("Existing App DNS content is invalid; it was not replaced")
    }

    private fun validDnsContents(text: String): Boolean {
        val lines = text.removeSuffix("\n").split('\n')
        return lines.size in 1..3 && lines.all { line ->
            val address = DNS_NAMESERVER.matchEntire(line.removeSuffix("\r"))?.groupValues?.get(1) ?: return@all false
            val server = try {
                if (':' in address) {
                    InetAddress.getByName(address)
                } else {
                    val octets = address.split('.')
                    if (octets.size != 4 || octets.any { it.length !in 1..3 || it.toIntOrNull() !in 0..255 }) return@all false
                    InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
                }
            } catch (_: IOException) {
                return@all false
            }
            !server.isAnyLocalAddress && !server.isLoopbackAddress && !server.isMulticastAddress && !server.isLinkLocalAddress
        }
    }

    private fun fixedFile(file: File, bytes: ByteArray) {
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() != bytes.size.toLong() ||
                Files.getOwner(file.toPath(), NOFOLLOW_LINKS) != Files.getOwner(filesDir.toPath(), NOFOLLOW_LINKS) ||
                !Files.readAllBytes(file.toPath()).contentEquals(bytes)
            ) throw IOException("Existing App instance file differs; it was not replaced")
        } else {
            Files.newByteChannel(file.toPath(), setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) if (it.write(buffer) <= 0) throw IOException("Incomplete App instance file write")
            }
        }
    }

    private fun validateInstance(instance: ChrootInstance) {
        require(NONCE.matches(instance.id) && instance.device > 0 && instance.inode > 0 && instance.installGeneration > 0)
    }

    private companion object {
        const val MAX_STDOUT = 16 * 1024
        const val MAX_STDERR = 16 * 1024
        const val MAX_PENDING = 1024
        const val MAX_JOURNAL_BYTES = 128_000L
        const val JOURNAL_MAGIC = 0x414a4950
        // v1 contains install/remove (0/1); v2 also retains recovery removal requests (2).
        const val JOURNAL_VERSION = 2
        const val DNS = ChrootNetworkConfig.FALLBACK_RESOLV_CONF
        const val MAX_DNS_BYTES = 4096
        val DNS_NAMESERVER = Regex("nameserver[ \\t]+([0-9A-Fa-f:.]+)[ \\t]*")
        val NONCE = Regex("[0-9a-f]{24}")
        val PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val CONFIG_DIRECTORIES = listOf("skills", "agents", "memory", "docs", "bin", "lib", "plugins", "scripts", "prompts.custom", "tool-output")
        val RESULT_KEYS = setOf("passed", "stage", "errno", "entries", "file_bytes", "cleanup_state", "device", "inode", "created_directory")
        val random = SecureRandom()
        fun randomId(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun rootPath(id: String) = "/data/local/ajiu-chroot-rootfs-$id"
    }
}
