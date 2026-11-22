package com.aicode.feature.agent.domain.container

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshPortMappingTransportTest {
    private fun peer(server: ServerSocket) = Socket().apply {
        soTimeout = 3_000
        connect(InetSocketAddress("127.0.0.1", server.localPort), 3_000)
    }

    private fun assertDisconnected(socket: Socket) {
        try { assertEquals(-1, socket.getInputStream().read()) }
        catch (timeout: SocketTimeoutException) { throw timeout }
        catch (_: SocketException) { }
    }

    private class Client {
        val client = mockk<SSHClient>(relaxed = true)
        val rawSocket = mockk<Socket>(relaxed = true)
        val forwarder = mockk<LocalPortForwarder>(relaxed = true)
        val probe = mockk<DirectConnection>(relaxed = true)
        var parameters: Parameters? = null
        var server: ServerSocket? = null
        init {
            every { client.socket } returns rawSocket
            every { client.newLocalPortForwarder(any(), any()) } answers {
                parameters = firstArg()
                server = secondArg()
                forwarder
            }
            every { client.newDirectConnection(any(), any()) } returns probe
        }
        fun resource(server: TrackedPortMappingSocket, remotePort: Int = 8080) = SshPortMappingResource(client, server, remotePort)
        fun realForwarder() {
            val loggerFactory = mockk<LoggerFactory>()
            every { loggerFactory.getLogger(any<Class<*>>()) } returns mockk(relaxed = true)
            every { client.newLocalPortForwarder(any(), any()) } answers {
                parameters = firstArg()
                server = secondArg()
                LocalPortForwarder(mockk(relaxed = true), firstArg(), secondArg(), loggerFactory)
            }
        }
    }

    @Test fun automaticBindUsesRealIpv4LoopbackAndReturnsActualPort() {
        TrackedPortMappingSocket.bind(null).use { server ->
            assertTrue(server.inetAddress is Inet4Address)
            assertEquals("127.0.0.1", server.inetAddress.hostAddress)
            assertFalse(server.inetAddress.isAnyLocalAddress)
            assertTrue(server.localPort in 1..65535)
            assertFalse(server.reuseAddress)
        }
    }

    @Test fun explicitPortBindReturnsRequestedRealPort() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        TrackedPortMappingSocket.bind(port).use { assertEquals(port, it.localPort) }
    }

    @Test fun specifiedPortConflictIsTypedAndDoesNotCloseExistingListener() {
        TrackedPortMappingSocket.bind(null).use { occupied ->
            val failure = runCatching { TrackedPortMappingSocket.bind(occupied.localPort) }.exceptionOrNull()
            assertTrue(failure is PortMappingFailure)
            assertEquals(PortMappingError.PORT_IN_USE, (failure as PortMappingFailure).error)
            assertFalse(occupied.isClosed)
            peer(occupied).use { client -> occupied.accept().use { accepted ->
                client.getOutputStream().write(17)
                accepted.soTimeout = 3_000
                assertEquals(17, accepted.getInputStream().read())
            } }
        }
    }

    @Test fun bindRejectsExternalZeroAndOutOfRangePorts() {
        listOf(-1, 0, 65536).forEach { port ->
            assertTrue(runCatching { TrackedPortMappingSocket.bind(port) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun trackedCloseDisconnectsAcceptedClientButOtherMappingContinues() {
        TrackedPortMappingSocket.bind(null).use { first ->
            TrackedPortMappingSocket.bind(null).use { second ->
                peer(first).use { one -> peer(second).use { two ->
                    val acceptedOne = first.accept()
                    val acceptedTwo = second.accept()
                    try {
                        first.close()
                        assertTrue(acceptedOne.isClosed)
                        assertDisconnected(one)
                        assertFalse(second.isClosed)
                        assertFalse(acceptedTwo.isClosed)
                        acceptedTwo.soTimeout = 3_000
                        two.getOutputStream().write(23)
                        assertEquals(23, acceptedTwo.getInputStream().read())
                        acceptedTwo.getOutputStream().write(29)
                        assertEquals(29, two.getInputStream().read())
                    } finally { acceptedOne.close(); acceptedTwo.close() }
                } }
            }
        }
    }

    @Test fun trackedCloseWhileAcceptingUnblocksWithoutLeakingAcceptedSocket(): Unit = runBlocking {
        val server = TrackedPortMappingSocket.bind(null)
        val accepting = CountDownLatch(1)
        server.onAccepting = { accepting.countDown() }
        val operation = async(Dispatchers.IO) { runCatching { server.accept() }.exceptionOrNull() }
        assertTrue(withContext(Dispatchers.IO) { accepting.await(5, TimeUnit.SECONDS) })
        server.close()
        assertTrue(withTimeout(5_000) { operation.await() } is SocketException)
        assertTrue(server.isClosed)
    }

    @Test fun acceptedSocketIsClosedIfSourceChangesImmediatelyAfterAccept(): Unit = runBlocking {
        TrackedPortMappingSocket.bind(null).use { server ->
            var checks = 0
            server.checkCurrent = { if (++checks == 2) throw CancellationException("source changed") }
            peer(server).use { client ->
                val failure = runCatching { server.accept() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertDisconnected(client)
            }
        }
    }

    @Test fun excessClientsAreClosedAndFreedSlotCanBeAcceptedLater(): Unit = runBlocking {
        TrackedPortMappingSocket.bind(null).use { server ->
            val clients = mutableListOf<Socket>()
            val accepted = mutableListOf<Socket>()
            try {
                repeat(PORT_MAPPING_MAX_CLIENTS) {
                    clients += peer(server)
                    accepted += server.accept()
                }
                val next = async(Dispatchers.IO) { server.accept() }
                peer(server).use { rejected -> assertDisconnected(rejected) }
                assertFalse(next.isCompleted)
                accepted.first().close()
                peer(server).use { replacement ->
                    val admitted = withTimeout(5_000) { next.await() }
                    admitted.use {
                        assertEquals(replacement.localPort, admitted.port)
                        admitted.soTimeout = 3_000
                        replacement.getOutputStream().write(37)
                        assertEquals(37, admitted.getInputStream().read())
                    }
                }
            } finally {
                clients.forEach { it.close() }
                accepted.forEach { it.close() }
            }
        }
    }

    @Test fun forwarderUsesActualLocalPortAndFixedRemoteLoopback(): Unit = runBlocking {
        val client = Client()
        val server = TrackedPortMappingSocket.bind(null)
        client.resource(server, 65535).use { resource ->
            val parameters = client.parameters!!
            assertEquals("127.0.0.1", parameters.localHost)
            assertEquals(server.localPort, parameters.localPort)
            assertEquals("127.0.0.1", parameters.remoteHost)
            assertEquals(65535, parameters.remotePort)
            resource.probe { }
            verify(exactly = 1) { client.client.newDirectConnection("127.0.0.1", 65535) }
            verify(exactly = 1) { client.probe.close() }
        }
    }

    @Test fun probeFailureIsTypedAndDoesNotPretendMappingIsActive(): Unit = runBlocking {
        val client = Client()
        every { client.client.newDirectConnection(any(), any()) } throws IOException("unreachable fixture")
        client.resource(TrackedPortMappingSocket.bind(null)).use { resource ->
            val failure = runCatching { resource.probe { } }.exceptionOrNull()
            assertTrue(failure is PortMappingFailure)
            assertEquals(PortMappingError.TARGET_UNREACHABLE, (failure as PortMappingFailure).error)
            verify(exactly = 0) { client.forwarder.listen(any<Thread>()) }
        }
    }

    @Test fun fatalProbeFailureIsNotConvertedToReachabilityError(): Unit = runBlocking {
        val client = Client()
        val fatal = AssertionError("fixture fatal")
        every { client.client.newDirectConnection(any(), any()) } answers { throw fatal }
        client.resource(TrackedPortMappingSocket.bind(null)).use { resource ->
            val failure = runCatching { resource.probe { } }.exceptionOrNull()
            assertEquals(fatal.javaClass, failure?.javaClass)
            assertEquals(fatal.message, failure?.message)
            val original = generateSequence(failure) { current ->
                current.cause?.takeIf { it.javaClass == current.javaClass && it.message == current.message }
            }.lastOrNull()
            assertSame(fatal, original)
        }
    }

    @Test fun closeBeforeListenClosesSocketBeforeSshjForwarderAndIsIdempotent() {
        val client = Client().apply { realForwarder() }
        val server = TrackedPortMappingSocket.bind(null)
        val resource = client.resource(server)
        resource.close()
        resource.close()
        assertTrue(server.isClosed)
        verify(exactly = 1) { client.rawSocket.close() }
        verify(exactly = 1) { client.client.close() }
    }

    @Test fun realSshjListenerCanBeCancelledWhileBlockedInAccept(): Unit = runBlocking {
        val client = Client().apply { realForwarder() }
        val server = TrackedPortMappingSocket.bind(null)
        val resource = client.resource(server)
        val listening = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) { resource.listen({ }, { listening.complete(Unit) }) }
        try {
            withTimeout(5_000) { listening.await() }
            withTimeout(5_000) { operation.cancelAndJoin() }
            assertTrue(server.isClosed)
            verify(exactly = 1) { client.client.close() }
            verify(exactly = 1) { client.rawSocket.close() }
        } finally { resource.close(); operation.cancel() }
    }

    @Test fun explicitCloseStopsRealSshjListenerWithoutBusyLoop(): Unit = runBlocking {
        val client = Client().apply { realForwarder() }
        val server = TrackedPortMappingSocket.bind(null)
        val resource = client.resource(server)
        val listening = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) { resource.listen({ }, { listening.complete(Unit) }) }
        try {
            withTimeout(5_000) { listening.await() }
            resource.close()
            withTimeout(5_000) { operation.await() }
            assertTrue(server.isClosed)
        } finally { resource.close(); operation.cancel() }
    }

    @Test fun listenerExceptionPropagatesAndOwnedResourcesCanStillBeClosed(): Unit = runBlocking {
        val client = Client()
        val expected = IOException("listener fixture failure")
        every { client.forwarder.listen(any<Thread>()) } answers { throw expected }
        val server = TrackedPortMappingSocket.bind(null)
        val resource = client.resource(server)
        val failure = runCatching { resource.listen({ }, { }) }.exceptionOrNull()
        assertEquals(expected.javaClass, failure?.javaClass)
        assertEquals(expected.message, failure?.message)
        val original = generateSequence(failure) { current ->
            current.cause?.takeIf { it.javaClass == current.javaClass && it.message == current.message }
        }.lastOrNull()
        assertSame(expected, original)
        resource.close()
        assertTrue(server.isClosed)
        verify(exactly = 1) { client.client.close() }
    }

    @Test fun transportUsesIndependentClientFactoryAndNeverBorrowedExecOrSftp(): Unit = runBlocking {
        val owner = RemoteConnectionIdentity(mockk(), "/home/fixture")
        val connection = mockk<RemoteSshConnection>()
        val client = Client()
        coEvery { connection.openForwardingClient(owner, any(), any()) } returns client.client
        val resource = SshPortMappingTransport(connection).open(owner, 8080, null, { }, { })
        try {
            assertTrue(resource.localPort in 1..65535)
            assertEquals(resource.localPort, client.parameters!!.localPort)
            coVerify(exactly = 1) { connection.openForwardingClient(owner, any(), any()) }
            coVerify(exactly = 0) { connection.sftp(); connection.openReadChannel(any()) }
            verify(exactly = 0) { connection.startExecSession(any()); connection.startShellSession() }
        } finally { resource.close() }
        assertTrue(client.server!!.isClosed)
        verify(exactly = 1) { client.client.close() }
    }

    @Test fun localPortConflictDoesNotOpenSshTransport(): Unit = runBlocking {
        TrackedPortMappingSocket.bind(null).use { occupied ->
            val connection = mockk<RemoteSshConnection>()
            val identity = RemoteConnectionIdentity(mockk(), "/home/fixture")
            val failure = runCatching { SshPortMappingTransport(connection).open(identity, 8080, occupied.localPort, { }, { }) }.exceptionOrNull()
            assertEquals(PortMappingError.PORT_IN_USE, (failure as PortMappingFailure).error)
            coVerify(exactly = 0) { connection.openForwardingClient(any(), any(), any()) }
        }
    }

    @Test fun sourceChangeAfterAuthenticationClosesIndependentClientAndListener(): Unit = runBlocking {
        val connection = mockk<RemoteSshConnection>()
        val identity = RemoteConnectionIdentity(mockk(), "/home/fixture")
        val client = Client()
        var current = true
        coEvery { connection.openForwardingClient(identity, any(), any()) } coAnswers { current = false; client.client }
        val failure = runCatching {
            SshPortMappingTransport(connection).open(identity, 8080, null, {
                if (!current) throw CancellationException("source changed")
            }, { })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 1) { client.rawSocket.close(); client.client.close() }
        verify(exactly = 0) { client.client.newLocalPortForwarder(any(), any()) }
    }
}
