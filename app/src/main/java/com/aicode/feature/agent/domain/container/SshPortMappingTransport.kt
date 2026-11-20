package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

internal const val PORT_MAPPING_LOOPBACK = "127.0.0.1"
internal const val PORT_MAPPING_MAX_CLIENTS = 32

internal class PortMappingFailure(val error: PortMappingError, cause: Throwable? = null) : Exception(error.name, cause)

internal interface PortMappingResource : AutoCloseable {
    val localPort: Int
    suspend fun probe(checkCurrent: () -> Unit)
    suspend fun listen(checkCurrent: () -> Unit, onListening: () -> Unit)
}

internal fun interface PortMappingTransport {
    suspend fun open(
        identity: RemoteConnectionIdentity,
        remotePort: Int,
        localPort: Int?,
        checkCurrent: () -> Unit,
        onDisconnected: () -> Unit
    ): PortMappingResource
}

internal class SshPortMappingTransport(private val connection: RemoteSshConnection) : PortMappingTransport {
    override suspend fun open(
        identity: RemoteConnectionIdentity,
        remotePort: Int,
        localPort: Int?,
        checkCurrent: () -> Unit,
        onDisconnected: () -> Unit
    ): PortMappingResource {
        require(remotePort in 1..65535 && (localPort == null || localPort in 1..65535))
        var server: TrackedPortMappingSocket? = null
        var client: SSHClient? = null
        var resource: SshPortMappingResource? = null
        try {
            checkCurrent()
            server = acquireSshResource(close = { it: TrackedPortMappingSocket -> it.close() }) {
                TrackedPortMappingSocket.bind(localPort)
            }
            checkCurrent()
            client = connection.openForwardingClient(identity, checkCurrent, onDisconnected)
            checkCurrent()
            resource = SshPortMappingResource(client, server, remotePort)
            currentCoroutineContext().ensureActive()
            checkCurrent()
            return resource
        } catch (failure: Throwable) {
            resource?.close() ?: run {
                server?.let { runCatching { it.close() } }
                client?.let(::closePortMappingClient)
            }
            throw failure
        }
    }
}

internal class TrackedPortMappingSocket private constructor() : ServerSocket() {
    private val lock = Any()
    private var closing = false
    private val accepted = mutableSetOf<Socket>()
    var checkCurrent: () -> Unit = {}
    var onAccepting: () -> Unit = {}

    override fun accept(): Socket {
        while (true) {
            checkCurrent()
            onAccepting()
            val socket = super.accept()
            try {
                val permitted = synchronized(lock) {
                    if (closing) throw SocketException("Port mapping listener closed")
                    accepted.removeAll { it.isClosed }
                    if (accepted.size < PORT_MAPPING_MAX_CLIENTS) { accepted += socket; true } else false
                }
                if (!permitted) {
                    socket.close()
                    continue
                }
                checkCurrent()
                return socket
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }
    }

    override fun close() {
        val sockets = synchronized(lock) {
            if (closing) return
            closing = true
            accepted.toList().also { accepted.clear() }
        }
        runCatching { super.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    companion object {
        fun bind(port: Int?): TrackedPortMappingSocket {
            require(port == null || port in 1..65535)
            val server = TrackedPortMappingSocket()
            try {
                server.reuseAddress = false
                server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port ?: 0))
                return server
            } catch (failure: Throwable) {
                server.close()
                if (failure is Error) throw failure
                if (failure is BindException) throw PortMappingFailure(PortMappingError.PORT_IN_USE, failure)
                throw PortMappingFailure(PortMappingError.LISTEN_FAILED, failure)
            }
        }
    }
}

internal class SshPortMappingResource(
    private val client: SSHClient,
    private val server: TrackedPortMappingSocket,
    private val remotePort: Int
) : PortMappingResource {
    private val closed = AtomicBoolean()
    private val listenerLock = Any()
    private var listener: Thread? = null
    private val forwarder: LocalPortForwarder = client.newLocalPortForwarder(
        Parameters(PORT_MAPPING_LOOPBACK, server.localPort, PORT_MAPPING_LOOPBACK, remotePort), server
    )
    override val localPort: Int = server.localPort

    override suspend fun probe(checkCurrent: () -> Unit) {
        withCancellationCleanup {
            try {
                checkCurrent()
                acquireSshResource(close = { it: net.schmizz.sshj.connection.channel.direct.DirectConnection -> it.close() }) {
                    checkCurrent()
                    withSshSetup(client.newDirectConnection(PORT_MAPPING_LOOPBACK, remotePort)) {
                        checkCurrent()
                        it
                    }
                }.use { checkCurrent() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                checkCurrent()
                throw PortMappingFailure(PortMappingError.TARGET_UNREACHABLE, failure)
            }
        }
    }

    override suspend fun listen(checkCurrent: () -> Unit, onListening: () -> Unit) {
        withCancellationCleanup {
            runInterruptible(Dispatchers.IO) {
                val thread = Thread.currentThread()
                synchronized(listenerLock) { listener = thread }
                try {
                    checkCurrent()
                    if (closed.get()) throw CancellationException("Port mapping closed")
                    var announced = false
                    server.checkCurrent = checkCurrent
                    server.onAccepting = {
                        if (!announced) {
                            checkCurrent()
                            onListening()
                            announced = true
                        }
                    }
                    forwarder.listen(thread)
                    if (!closed.get()) throw PortMappingFailure(PortMappingError.LISTEN_FAILED)
                } finally {
                    synchronized(listenerLock) { listener = null }
                }
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> withCancellationCleanup(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        val cancellation = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) close()
        }
        return try { context.ensureActive(); block() } finally { cancellation?.dispose() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // sshj 0.38 only interrupts its listener; it does not own accepted browser sockets.
        synchronized(listenerLock) { listener?.interrupt() }
        server.close()
        closePortMappingClient(client)
        runCatching { forwarder.close() }
    }
}

private fun closePortMappingClient(client: SSHClient) {
    runCatching { client.socket?.close() }
    runCatching { client.close() }
}
