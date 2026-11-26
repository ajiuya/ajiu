package com.aicode.feature.agent.domain.container.chroot

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ChrootControlTransport : AutoCloseable {
    suspend fun awaitHelper()
    fun trySend(type: ChrootFrameType, body: ByteArray = byteArrayOf()): Boolean
}

internal class ChrootControlChannel(token: ByteArray, appUid: Int) : ChrootControlTransport {
    private val expectedToken = token.copyOf()
    private val closed = AtomicBoolean()
    private val sendLock = ReentrantLock()
    private val server: LocalServerSocket
    @Volatile private var socket: LocalSocket? = null

    init {
        require(appUid == AndroidProcess.myUid() && appUid >= 10_000)
        server = LocalServerSocket(nameFor(expectedToken))
    }

    override suspend fun awaitHelper() = withContext(Dispatchers.IO) {
        if (closed.get() || socket != null) throw IOException("Runtime control channel is not awaiting a helper")
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var accepted: LocalSocket? = null
        try {
            while (true) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (closed.get() || remaining <= 0) throw IOException("Runtime control connection timed out")
                val waiting = StructPollfd().apply {
                    fd = server.fileDescriptor
                    events = OsConstants.POLLIN.toShort()
                }
                try {
                    if (Os.poll(arrayOf(waiting), remaining) == 0) throw IOException("Runtime control connection timed out")
                    if (waiting.revents.toInt() and OsConstants.POLLIN == 0) throw IOException("Runtime control listener closed")
                    break
                } catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EINTR) throw failure
                }
            }
            accepted = server.accept()
            val peer = accepted.peerCredentials
            if (peer.uid != 0 || peer.gid != 0 || peer.pid <= 1) throw IOException("Runtime control peer is not the root helper")
            val fd = accepted.fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            val bytes = ByteArray(44)
            var offset = 0
            while (offset < bytes.size) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (closed.get() || remaining <= 0) throw IOException("Runtime control handshake timed out")
                val waiting = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                try {
                    if (Os.poll(arrayOf(waiting), remaining) == 0) throw IOException("Runtime control handshake timed out")
                    val count = Os.read(fd, bytes, offset, bytes.size - offset)
                    if (count <= 0) throw IOException("Runtime control handshake ended early")
                    offset += count
                } catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EINTR && failure.errno != OsConstants.EAGAIN) throw failure
                }
            }
            val hello = ChrootWireProtocol.read(ByteArrayInputStream(bytes))
            if (hello?.type != ChrootFrameType.READY || !MessageDigest.isEqual(expectedToken, hello.body)) {
                throw IOException("Runtime control handshake does not match the launch")
            }
            sendLock.lock()
            try {
                if (closed.get() || socket != null) throw IOException("Runtime control capture expired")
                socket = accepted
                accepted = null
            } finally {
                sendLock.unlock()
            }
            server.close()
        } catch (failure: Exception) {
            close()
            throw failure
        } finally {
            runCatching { accepted?.close() }
        }
    }

    override fun trySend(type: ChrootFrameType, body: ByteArray): Boolean {
        val encoded = encodeControl(type, body)
        if (!sendLock.tryLock()) return false
        try {
            val connected = socket ?: return false
            if (closed.get()) return false
            return try {
                val written = Os.sendto(
                    connected.fileDescriptor, encoded, 0, encoded.size,
                    MSG_DONTWAIT or MSG_NOSIGNAL, null as InetAddress?, 0
                )
                if (written == encoded.size) true else {
                    close()
                    false
                }
            } catch (_: Exception) {
                close()
                false
            }
        } finally {
            sendLock.unlock()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
    }

    companion object {
        // Android's public OsConstants omits these Linux socket flags.
        private const val MSG_DONTWAIT = 0x40
        private const val MSG_NOSIGNAL = 0x4000

        internal fun nameFor(token: ByteArray): String {
            require(token.size == 32 && token.any { it != 0.toByte() })
            return "ajiu-chroot-control-" + token.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        internal fun encodeControl(type: ChrootFrameType, body: ByteArray): ByteArray {
            require(type in setOf(ChrootFrameType.GO, ChrootFrameType.HEARTBEAT, ChrootFrameType.STOP, ChrootFrameType.SIGNAL, ChrootFrameType.RESIZE))
            if (type == ChrootFrameType.SIGNAL) require(body.size == 1 && body[0].toInt() in setOf(1, 2, 15))
            if (type == ChrootFrameType.RESIZE) {
                require(body.size == 4)
                val size = ByteBuffer.wrap(body)
                require((size.short.toInt() and 0xffff) in 1..4096 && (size.short.toInt() and 0xffff) in 1..4096)
            }
            return ByteArrayOutputStream(16).also { ChrootWireProtocol.write(it, type, body) }.toByteArray()
        }
    }
}
