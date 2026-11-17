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
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RemoteSshConnectionResourcesTest {
    private val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())

    @Before
    fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.i(any(), any()) } just Runs
    }

    @After
    fun release() {
        unmockkConstructor(SSHClient::class)
        unmockkObject(FileLogger)
    }

    @Test
    fun authFailureClosesNewTransportWithoutPublishingAConnection() = runBlocking {
        mockkConstructor(SSHClient::class)
        every { anyConstructed<SSHClient>().addHostKeyVerifier(any<HostKeyVerifier>()) } just Runs
        every { anyConstructed<SSHClient>().connect(any<String>(), any<Int>()) } just Runs
        val failure = UserAuthException("fixture auth rejected")
        every { anyConstructed<SSHClient>().authPassword("fixture", "test-only") } throws failure
        every { anyConstructed<SSHClient>().close() } just Runs
        val actual = runCatching {
            connection.connect(RemoteConnectionConfig("test.invalid", username = "fixture",
                auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/workspace"))
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(connection.isConnected())
        assertTrue(runCatching { connection.captureIdentity() }.isFailure)
        verify(exactly = 1) { anyConstructed<SSHClient>().close() }
    }

    @Test
    fun execSetupFailureClosesSessionForBothStdinModesWithoutClosingTransport() {
        val client = mockk<SSHClient>(relaxed = true)
        val session = mockk<Session>(relaxed = true)
        val failure = IOException("exec rejected")
        connection.javaClass.getDeclaredField("sshClient").apply { isAccessible = true }.set(connection, client)
        every { client.startSession() } returns session
        every { session.exec(any()) } throws failure
        assertSame(failure, runCatching { connection.startExecSession("fake") }.exceptionOrNull())
        assertSame(failure, runCatching { connection.startExecSessionWithStdin("fake") }.exceptionOrNull())
        verify(exactly = 2) { session.close() }
        verify(exactly = 0) { client.close() }
        verify(exactly = 0) { client.disconnect() }
    }
}
