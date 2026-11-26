package com.aicode.feature.agent.domain.container.chroot

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class ChrootRootBroker internal constructor(
    private val authority: RootAuthorityCoordinator,
    private val beforeBootstrap: () -> Unit
) {
    @Inject
    constructor(authority: RootAuthorityCoordinator) : this(authority, {})

    private class Opening(val server: LocalServerSocket) {
        val closed = AtomicBoolean()
        @Volatile var bootstrap: Process? = null
        fun close() {
            closed.set(true)
            runCatching { server.close() }
            runCatching { bootstrap?.destroy() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val openingMutex = Mutex()
    private val opening = AtomicReference<Opening?>()
    private val connection = AtomicReference<Connection?>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val retiring = CopyOnWriteArrayList<Process>()
    private val _currentSession = MutableStateFlow<ChrootRuntimeSession?>(null)
    val currentSession = _currentSession.asStateFlow()
    private val _registeredProcessCount = MutableStateFlow(0)
    val registeredProcessCount = _registeredProcessCount.asStateFlow()
    private val bootstraps = AtomicInteger()
    val bootstrapCount: Int get() = bootstraps.get()

    suspend fun closeIdleFromUser(expected: ChrootRuntimeSession) = withContext(Dispatchers.IO) {
        val live = authority.withLock {
            val current = connection.get() ?: throw ChrootRuntimeUnavailable("Root runtime session is unavailable")
            checkSession(expected)
            if (!current.isIdle()) throw IOException("Root runtime session still has registered tasks")
            current.draining.set(true)
            _currentSession.value = null
            listeners.forEach { runCatching(it) }
            current
        }
        try {
            withTimeout(5_000) { live.shutdownIdle() }
            invalidate(live)
            if (!live.bootstrap.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || live.bootstrap.exitValue() != 0) {
                throw IOException("Root runtime session shutdown could not be verified")
            }
            retiring.remove(live.bootstrap)
        } finally {
            invalidate(live)
        }
    }

    init {
        authority.registerRevocation({ invalidate() }) {
            withContext(Dispatchers.IO) {
                retiring.forEach { process -> runInterruptible { process.waitFor() }; retiring.remove(process) }
            }
        }
    }

    fun registerSessionRevocation(callback: () -> Unit): AutoCloseable {
        listeners.add(callback)
        return AutoCloseable { listeners.remove(callback) }
    }

    fun checkSession(expected: ChrootRuntimeSession) {
        val current = connection.get()
        if (current == null || current.closed.get() || current.draining.get() || current.session !== expected ||
            authority.withCurrent(expected.rootState.authorityId, expected.rootState.generation) { true } != true) {
            throw ChrootRuntimeUnavailable("Root runtime session is unavailable or changed")
        }
    }

    // Explicit CREATE/REMOVE callers retain their reviewed lifecycle entry point.
    suspend fun ensureFromUser(
        expected: RootAccessState,
        start: (List<String>) -> Process
    ): ChrootRuntimeSession = ensureSession(expected, start)

    // Policy entry: Root master state and the real su/OS grant still gate every bootstrap.
    suspend fun ensureSession(
        expected: RootAccessState,
        start: (List<String>) -> Process
    ): ChrootRuntimeSession = withContext(Dispatchers.IO) {
        openingMutex.withLock {
            connection.get()?.let { existing ->
                if (!existing.closed.get() && existing.session.rootState == expected) {
                    checkSession(existing.session)
                    return@withLock existing.session
                }
                invalidate()
            }
            requireCurrent(expected)
            val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
            val hex = nonce.joinToString("") { "%02x".format(it.toInt() and 255) }
            val pid = AndroidProcess.myPid()
            val uid = AndroidProcess.myUid()
            val startTime = processStartTime(pid)
            val name = "ajiu-root-$hex"
            val pending = Opening(LocalServerSocket(name))
            opening.set(pending)
            var accepted: LocalSocket? = null
            try {
                authority.withCurrent(expected.authorityId, expected.generation) {
                    if (pending.closed.get()) throw IOException("Root runtime authorization expired")
                    beforeBootstrap()
                    bootstraps.incrementAndGet()
                    pending.bootstrap = start(listOf("--broker", name, hex, uid.toString(), pid.toString(), startTime.toString()))
                } ?: throw IOException("Root runtime authorization expired")
                pollReadable(pending.server.fileDescriptor, 5_000)
                if (pending.closed.get()) throw IOException("Root runtime authorization expired")
                accepted = pending.server.accept().apply { soTimeout = 5_000 }
                val peer = accepted.peerCredentials
                if (peer.uid != 0 || peer.gid != 0 || peer.pid <= 1) throw IOException("Root channel peer is invalid")
                val hello = receive(accepted)
                if (hello.type != ChrootBrokerWire.HELLO || hello.descriptors.isNotEmpty()) {
                    hello.closeDescriptors()
                    throw IOException("Root channel handshake is invalid")
                }
                ChrootBrokerWire.checkHello(hello.body, nonce, uid, pid, startTime, peer.pid)
                send(accepted, ChrootBrokerWire.HELLO_ACK, hello.body)
                val session = ChrootRuntimeSession(hex, expected)
                val live = Connection(session, accepted, pending.bootstrap!!)
                val published = authority.withCurrent(expected.authorityId, expected.generation) {
                    if (pending.closed.get()) false else {
                        connection.set(live)
                        _currentSession.value = session
                        true
                    }
                } == true
                if (!published) throw IOException("Root runtime authorization expired")
                accepted = null
                opening.compareAndSet(pending, null)
                runCatching { pending.server.close() }
                live.observeConnection()
                scope.launch {
                    runCatching { runInterruptible { live.bootstrap.waitFor() } }
                    if (!live.draining.get()) invalidate(live)
                    retiring.remove(live.bootstrap)
                }
                session
            } catch (failure: Throwable) {
                runCatching { accepted?.close() }
                pending.close()
                throw failure
            } finally {
                opening.compareAndSet(pending, null)
            }
        }
    }

    fun start(operation: Int, instanceId: String? = null, identity: ChrootFileIdentity? = null, expectedSession: ChrootRuntimeSession? = null, checkHelper: () -> Unit = {}): Process = authority.withLock {
        val live = connection.get() ?: throw ChrootRuntimeUnavailable("Root runtime session is unavailable or changed")
        if (expectedSession != null && live.session !== expectedSession) {
            throw ChrootRuntimeUnavailable("Root runtime session changed before dispatch")
        }
        checkSession(live.session)
        live.start(operation, instanceId, identity, checkHelper)
    }

    private fun requireCurrent(expected: RootAccessState) {
        if (!expected.enabled || authority.withCurrent(expected.authorityId, expected.generation) { true } != true) {
            throw ChrootRuntimeUnavailable("Root runtime authorization expired")
        }
    }

    private fun invalidate(expected: Connection? = null) = authority.withLock {
        if (expected != null && connection.get() !== expected) {
            expected.close()
            return@withLock
        }
        opening.getAndSet(null)?.let { pending ->
            pending.bootstrap?.let { retiring.addIfAbsent(it) }
            pending.close()
        }
        val previous = connection.getAndSet(null)
        previous?.bootstrap?.let { retiring.addIfAbsent(it) }
        _currentSession.value = null
        _registeredProcessCount.value = 0
        listeners.forEach { runCatching(it) }
        previous?.close()
    }

    private inner class Connection(
        val session: ChrootRuntimeSession,
        val socket: LocalSocket,
        val bootstrap: Process
    ) {
        val closed = AtomicBoolean()
        val draining = AtomicBoolean()
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "chroot-root-channel").apply { isDaemon = true } }
        private var sequence = 0L
        private val processes = CopyOnWriteArrayList<ChrootBrokerProcess>()

        fun isIdle(): Boolean = processes.isEmpty()

        private fun remove(process: ChrootBrokerProcess) = authority.withLock {
            processes.remove(process)
            if (connection.get() === this) _registeredProcessCount.value = processes.size
        }

        suspend fun shutdownIdle() {
            val acknowledged = CompletableDeferred<Unit>()
            try {
                executor.execute {
                    try {
                        if (closed.get() || !draining.get() || !isIdle()) throw IOException("Root session is not idle")
                        val id = nextId()
                        val body = ByteBuffer.allocate(8).putLong(id).array()
                        send(socket, ChrootBrokerWire.SHUTDOWN, body)
                        val reply = receive(socket)
                        try {
                            if (reply.type != ChrootBrokerWire.BYE || reply.descriptors.isNotEmpty() ||
                                !reply.body.contentEquals(body)) throw IOException("Root shutdown acknowledgement is invalid")
                        } finally { reply.closeDescriptors() }
                        acknowledged.complete(Unit)
                    } catch (failure: Throwable) { acknowledged.completeExceptionally(failure) }
                }
            } catch (failure: Throwable) { acknowledged.completeExceptionally(failure) }
            acknowledged.await()
        }

        fun observeConnection() {
            scope.launch {
                try {
                    while (!closed.get()) {
                        val waiting = StructPollfd().apply { fd = socket.fileDescriptor; events = 0x2000.toShort() }
                        Os.poll(arrayOf(waiting), 1_000)
                        if (!closed.get() && waiting.revents.toInt() and (0x2000 or OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
                            if (!draining.get()) invalidate(this@Connection)
                            return@launch
                        }
                    }
                } catch (_: Exception) {
                    if (!draining.get()) invalidate(this@Connection)
                }
            }
        }

        fun start(operation: Int, instanceId: String?, identity: ChrootFileIdentity?, checkHelper: () -> Unit): ChrootBrokerProcess {
            if (closed.get() || processes.size >= 32) throw IOException("Root runtime channel is unavailable or full")
            val process = ChrootBrokerProcess { target -> stop(target) }
            processes.add(process)
            _registeredProcessCount.value = processes.size
            try {
                executor.execute {
                    if (closed.get() || process.cancelled.get()) {
                        process.fail(IOException("Root runtime request was cancelled"))
                        remove(process)
                        return@execute
                    }
                    try {
                        checkHelper()
                        val dispatch = authority.withCurrent(session.rootState.authorityId, session.rootState.generation) {
                            checkSession(session)
                            process.tryDispatch()
                        } == true
                        if (!dispatch) {
                            process.fail(IOException("Root runtime request was cancelled before dispatch"))
                            remove(process)
                            return@execute
                        }
                        val id = nextId()
                        process.requestId = id
                        send(socket, ChrootBrokerWire.START,
                            ChrootBrokerWire.start(id, operation, instanceId, identity?.device ?: 0, identity?.inode ?: 0))
                        val reply = receive(socket)
                        if (reply.type == ChrootBrokerWire.FAILED) {
                            if (reply.descriptors.isNotEmpty()) {
                                reply.closeDescriptors()
                                throw IOException("Root rejection supplied unexpected descriptors")
                            }
                            ChrootBrokerWire.checkFailed(reply.body, id)
                            process.fail(IOException("Root helper start was rejected"))
                            remove(process)
                        } else {
                            try {
                                if (reply.type != ChrootBrokerWire.STARTED || reply.descriptors.size != 4) throw IOException("Root helper descriptor response is invalid")
                                ChrootBrokerWire.checkStarted(reply.body, id)
                                validateDescriptors(reply.descriptors)
                                checkSession(session)
                                process.attach(reply.takeDescriptors()) { remove(process) }
                            } catch (failure: Throwable) {
                                reply.closeDescriptors()
                                throw failure
                            }
                            if (process.cancelled.get()) stop(process)
                        }
                    } catch (failure: Throwable) {
                        process.fail(failure)
                        invalidate(this@Connection)
                    }
                }
            } catch (failure: Throwable) {
                remove(process)
                process.fail(failure)
            }
            return process
        }

        private fun stop(process: ChrootBrokerProcess) {
            if (closed.get() || process.requestId <= 0) return
            runCatching {
                executor.execute {
                    if (closed.get() || process.requestId <= 0) return@execute
                    try {
                        val id = nextId()
                        val body = ByteBuffer.allocate(16).putLong(id).putLong(process.requestId).array()
                        send(socket, ChrootBrokerWire.STOP, body)
                        val reply = receive(socket)
                        try {
                            if (reply.type != ChrootBrokerWire.STOPPED || reply.descriptors.isNotEmpty() ||
                                !reply.body.contentEquals(body)) throw IOException("Root stop acknowledgement is invalid")
                        } finally { reply.closeDescriptors() }
                    } catch (_: Throwable) {
                        invalidate(this@Connection)
                    }
                }
            }
        }

        private fun nextId(): Long {
            if (sequence == Long.MAX_VALUE) throw IOException("Root request sequence is exhausted")
            return ++sequence
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            executor.shutdownNow()
            processes.forEach { it.fail(IOException("Root runtime session ended")) }
            processes.clear()
        }
    }

    private class Packet(val type: Int, val body: ByteArray, descriptors: List<FileDescriptor>) {
        var descriptors = descriptors
            private set
        fun takeDescriptors(): List<FileDescriptor> = descriptors.also { descriptors = emptyList() }
        fun closeDescriptors() = takeDescriptors().forEach { runCatching { Os.close(it) } }
    }

    private fun send(socket: LocalSocket, type: Int, body: ByteArray) {
        socket.soTimeout = 5_000
        socket.outputStream.write(ChrootBrokerWire.frame(type, body))
    }

    private fun receive(socket: LocalSocket): Packet {
        val descriptors = ArrayList<FileDescriptor>()
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            socket.soTimeout = 5_000
            val header = ByteArray(12)
            val first = socket.inputStream.read()
            if (first < 0) throw IOException("Root runtime channel ended")
            header[0] = first.toByte()
            socket.ancillaryFileDescriptors?.let { descriptors.addAll(it) }
            readExactly(socket, header, 1, 11, descriptors, deadline)
            val (type, size) = ChrootBrokerWire.header(header)
            val body = ByteArray(size)
            readExactly(socket, body, 0, size, descriptors, deadline)
            if (descriptors.size > 4) throw IOException("Root runtime channel supplied excess descriptors")
            return Packet(type, body, descriptors)
        } catch (failure: Throwable) {
            descriptors.forEach { runCatching { Os.close(it) } }
            throw failure
        }
    }

    private fun readExactly(socket: LocalSocket, bytes: ByteArray, offset: Int, count: Int, descriptors: MutableList<FileDescriptor>, deadline: Long) {
        var position = offset
        while (position < offset + count) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
            if (remaining <= 0) throw IOException("Root runtime channel frame timed out")
            socket.soTimeout = remaining
            val got = socket.inputStream.read(bytes, position, offset + count - position)
            if (got <= 0) throw IOException("Root runtime channel frame ended early")
            socket.ancillaryFileDescriptors?.takeIf { it.isNotEmpty() }?.let {
                descriptors.addAll(it)
                throw IOException("Root runtime descriptors were not attached to the frame boundary")
            }
            position += got
        }
    }

    private fun validateDescriptors(descriptors: List<FileDescriptor>) {
        val identities = HashSet<Pair<Long, Long>>()
        descriptors.forEachIndexed { index, fd ->
            val stat = Os.fstat(fd)
            val access = Os.fcntlInt(fd, OsConstants.F_GETFL, 0) and 3
            if (!OsConstants.S_ISFIFO(stat.st_mode) || !identities.add(stat.st_dev to stat.st_ino) ||
                access != if (index == 0) OsConstants.O_WRONLY else OsConstants.O_RDONLY) {
                throw IOException("Root helper descriptor identity is invalid")
            }
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFD, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFD, flags or OsConstants.FD_CLOEXEC)
        }
    }

    companion object {
        private fun processStartTime(pid: Int): Long {
            val stat = File("/proc/$pid/stat").readText()
            val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            return fields.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IOException("Application process identity is unavailable")
        }

        private fun pollReadable(fd: FileDescriptor, timeout: Int) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (true) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (remaining <= 0) throw IOException("Root channel connection timed out")
                val waiting = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                try {
                    if (Os.poll(arrayOf(waiting), remaining) > 0) {
                        if (waiting.revents.toInt() and OsConstants.POLLIN == 0) throw IOException("Root channel listener ended")
                        return
                    }
                } catch (failure: android.system.ErrnoException) {
                    if (failure.errno != OsConstants.EINTR) throw failure
                }
            }
        }
    }
}
