package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
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
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class RemoteSshForwardingClientTest {
    private val hostVerifier = mockk<SshHostKeyVerifier>()
    private val privateKeys = mockk<SshPrivateKeyStore>()
    private val connection = RemoteSshConnection(mockk(), hostVerifier, privateKeys, mockk())
    private val sharedClient = mockk<SSHClient>(relaxed = true)
    private val identity = RemoteConnectionIdentity(sharedClient, "/home/fixture")
    private val config = RemoteConnectionConfig("fixture.invalid", username = "fixture",
        auth = RemoteAuth.Password("fixture-password"), remoteWorkspacePath = "/workspace")
    private val transport = mockk<Transport>(relaxed = true)
    private val sshConnection = mockk<Connection>(relaxed = true)
    private var installedFactory: SocketFactory? = null
    private var onDisconnected: DisconnectListener? = null

    @Before fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { sharedClient.isConnected } returns true
        every { sharedClient.isAuthenticated } returns true
        field("sshClient", sharedClient)
        field("remoteHome", identity.home)
        field("activeIdentity", identity)
        field("config", config)
        mockkConstructor(SSHClient::class)
        every { anyConstructed<SSHClient>().socketFactory } returns SocketFactory.getDefault()
        every { anyConstructed<SSHClient>().socketFactory = any() } answers { installedFactory = firstArg() }
        every { anyConstructed<SSHClient>().socket } returns null
        every { anyConstructed<SSHClient>().addHostKeyVerifier(any<HostKeyVerifier>()) } just Runs
        every { anyConstructed<SSHClient>().connect(any<String>(), any<Int>()) } just Runs
        every { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) } just Runs
        every { anyConstructed<SSHClient>().connection } returns sshConnection
        every { anyConstructed<SSHClient>().transport } returns transport
        every { anyConstructed<SSHClient>().close() } just Runs
        every { transport.setDisconnectListener(any()) } answers { onDisconnected = firstArg() }
    }

    @After fun release() {
        unmockkConstructor(SSHClient::class)
        unmockkObject(FileLogger)
    }

    private fun field(name: String, value: Any?) {
        connection.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }

    @Test fun independentClientReusesHostVerificationPasswordAndKeepaliveWithoutReplacingSharedOwner(): Unit = runBlocking {
        var checks = 0
        val client = connection.openForwardingClient(identity, { checks++ }, { })
        try {
            assertNotSame(sharedClient, client)
            assertSame(identity, connection.captureIdentity())
            assertTrue(checks >= 3)
            verify(exactly = 1) { anyConstructed<SSHClient>().addHostKeyVerifier(hostVerifier) }
            verify(exactly = 1) { anyConstructed<SSHClient>().connect("fixture.invalid", 22) }
            verify(exactly = 1) { anyConstructed<SSHClient>().authPassword("fixture", "fixture-password") }
            verify(exactly = 1) { sshConnection.keepAlive.setKeepAliveInterval(30); sshConnection.keepAlive.start() }
            verify(exactly = 0) { sharedClient.close(); sharedClient.disconnect(); sharedClient.newSFTPClient(); sharedClient.startSession() }
        } finally { client.close() }
    }

    @Test fun privateKeyPipelineUsesExistingPrivateStoreAndPassphraseFinder(): Unit = runBlocking {
        val key = mockk<KeyProvider>()
        field("config", config.copy(auth = RemoteAuth.PrivateKey("fixture-key", "fixture-passphrase")))
        every { privateKeys.readPem("fixture-key") } returns "fixture-pem"
        every { anyConstructed<SSHClient>().loadKeys("fixture-pem", null, any<PasswordFinder>()) } returns key
        every { anyConstructed<SSHClient>().authPublickey("fixture", *anyVararg<KeyProvider>()) } just Runs
        val client = connection.openForwardingClient(identity, { }, { })
        client.close()
        verify(exactly = 1) { privateKeys.readPem("fixture-key") }
        verify(exactly = 1) { anyConstructed<SSHClient>().authPublickey("fixture", key) }
        verify(exactly = 0) { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) }
        assertSame(identity, connection.captureIdentity())
    }

    @Test fun initialSourceCheckFailureDoesNotCreateNewClient(): Unit = runBlocking {
        val expected = CancellationException("source changed")
        assertSame(expected, runCatching { connection.openForwardingClient(identity, { throw expected }, { }) }.exceptionOrNull())
        verify(exactly = 0) { anyConstructed<SSHClient>().connect(any<String>(), any<Int>()) }
        verify(exactly = 0) { sharedClient.close() }
    }

    @Test fun replacedIdentityIsRejectedBeforeNetworkSetup(): Unit = runBlocking {
        field("activeIdentity", RemoteConnectionIdentity(sharedClient, identity.home))
        val failure = runCatching { connection.openForwardingClient(identity, { }, { }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { anyConstructed<SSHClient>().connect(any<String>(), any<Int>()) }
        verify(exactly = 0) { sharedClient.close() }
    }

    @Test fun authenticationFailureClosesOnlyOwnedClient(): Unit = runBlocking {
        val expected = UserAuthException("fixture rejected")
        every { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) } throws expected
        assertSame(expected, runCatching { connection.openForwardingClient(identity, { }, { }) }.exceptionOrNull())
        verify(atLeast = 1) { anyConstructed<SSHClient>().close() }
        verify(exactly = 0) { sharedClient.close(); sharedClient.disconnect() }
        assertSame(identity, connection.captureIdentity())
    }

    @Test fun sourceChangeDuringAuthenticationClosesOwnedClientWithoutDelivery(): Unit = runBlocking {
        var current = true
        every { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) } answers { current = false }
        val failure = runCatching {
            connection.openForwardingClient(identity, { if (!current) throw CancellationException("source changed") }, { })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(atLeast = 1) { anyConstructed<SSHClient>().close() }
        verify(exactly = 0) { sharedClient.close() }
    }

    @Test fun connectionConfigurationReplacementDuringAuthenticationClosesOwnedClient(): Unit = runBlocking {
        every { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) } answers {
            field("config", config.copy(host = "replacement.invalid"))
        }
        val failure = runCatching { connection.openForwardingClient(identity, { }, { }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(atLeast = 1) { anyConstructed<SSHClient>().close() }
        verify(exactly = 0) { sharedClient.close() }
    }

    @Test fun forwardingDisconnectCallbackDoesNotInvalidateSharedIdentity(): Unit = runBlocking {
        var disconnected = false
        val client = connection.openForwardingClient(identity, { }, { disconnected = true })
        try {
            onDisconnected!!.notifyDisconnect(net.schmizz.sshj.common.DisconnectReason.CONNECTION_LOST, "fixture")
            assertTrue(disconnected)
            assertSame(identity, connection.captureIdentity())
            verify(exactly = 0) { sharedClient.close() }
        } finally { client.close() }
    }

    @Test fun cancellationDuringAuthenticationClosesPartiallyOwnedTransport(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) } answers {
            entered.countDown()
            check(closed.await(10, TimeUnit.SECONDS))
            throw IOException("auth socket closed")
        }
        every { anyConstructed<SSHClient>().close() } answers { closed.countDown() }
        val operation = async(Dispatchers.Default) { connection.openForwardingClient(identity, { }, { }) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { operation.cancelAndJoin() }
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0L, closed.count)
            verify(atLeast = 1) { anyConstructed<SSHClient>().close() }
            verify(exactly = 0) { sharedClient.close() }
        } finally { closed.countDown(); operation.cancel() }
    }

    private class BlockingSocket : Socket() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            entered.countDown()
            check(closed.await(10, TimeUnit.SECONDS))
            throw IOException("connect socket closed")
        }
        override fun close() { super.close(); closed.countDown() }
    }

    @Test fun cancellationClosesSocketEvenBeforeSshjPublishesItOnClient(): Unit = runBlocking {
        val socket = BlockingSocket()
        val delegate = object : SocketFactory() {
            override fun createSocket(): Socket = socket
            override fun createSocket(host: String, port: Int): Socket = error("unused")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("unused")
        }
        every { anyConstructed<SSHClient>().socketFactory } returns delegate
        every { anyConstructed<SSHClient>().connect(any<String>(), any<Int>()) } answers {
            installedFactory!!.createSocket().connect(java.net.InetSocketAddress("127.0.0.1", 22), 0)
        }
        val operation = async(Dispatchers.Default) { connection.openForwardingClient(identity, { }, { }) }
        try {
            assertTrue(withContext(Dispatchers.IO) { socket.entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { operation.cancelAndJoin() }
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            assertTrue(socket.isClosed)
            assertEquals(0L, socket.closed.count)
            verify(exactly = 0) { anyConstructed<SSHClient>().authPassword(any<String>(), any<String>()) }
            verify(exactly = 0) { sharedClient.close() }
        } finally { socket.close(); operation.cancel() }
    }

    @Test fun identityStateTracksPublishedConnectionDisconnectAndReconnect(): Unit = runBlocking {
        val session = mockk<Session>(relaxed = true)
        val command = mockk<Session.Command>(relaxed = true)
        every { anyConstructed<SSHClient>().startSession() } returns session
        every { session.exec("echo \$HOME") } returns command
        every { command.inputStream } answers { "/home/fixture\n".byteInputStream() }
        every { anyConstructed<SSHClient>().isConnected } returns true
        every { anyConstructed<SSHClient>().isAuthenticated } returns true
        every { anyConstructed<SSHClient>().disconnect() } just Runs
        field("sshClient", null)
        field("activeIdentity", null)
        field("remoteHome", null)
        connection.connect(config)
        val first = connection.identityState.value
        assertNotNull(first)
        assertSame(first, connection.captureIdentity())
        connection.disconnect()
        assertNull(connection.identityState.value)
        connection.connect(config)
        val second = connection.identityState.value
        assertNotNull(second)
        assertNotSame(first, second)
        assertNotEquals(first!!.generation, second!!.generation)
        onDisconnected!!.notifyDisconnect(net.schmizz.sshj.common.DisconnectReason.CONNECTION_LOST, "fixture")
        assertNull(connection.identityState.value)
        connection.disconnect()
    }
}
