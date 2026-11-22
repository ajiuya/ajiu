package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.*
import org.junit.Test

class RemoteAgentConfigSshTransportTest {
    private val owner = mockk<SSHClient>()
    private val connection = mockk<RemoteSshConnection>()
    private val session = mockk<Session>(relaxed = true)
    private val command = mockk<Session.Command>(relaxed = true)
    private val identity = RemoteConnectionIdentity(owner, "/home/user")
    private val key = RemoteAgentConfigKey(RemoteAgentConfigServer("host", 22, "user", "/home/user"), identity,
        "/project", RemoteAgentConfigProfile("a", 1), AgentConfigKind.AGENTS, "~/.aicode/agents", "/home/user/.aicode/agents")
    private val input = ByteArrayOutputStream()
    private var current = identity

    private fun setup(output: InputStream, error: InputStream = ByteArrayInputStream(byteArrayOf())) {
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== current) throw CancellationException("connection changed")
        }
        every { owner.startSession() } returns session
        every { session.exec(any()) } returns command
        every { command.outputStream } returns input
        every { command.inputStream } returns output
        every { command.errorStream } returns error
        every { command.exitStatus } returns 0
        every { command.exitSignal } returns null
    }

    @Test fun realTransportParsesManifestAndWritesBatchedBodyRequestsOnCapturedOwnerOnly() = runBlocking {
        val body = "complete instructions"
        val path = Base64.getEncoder().encodeToString("agent.md".toByteArray())
        val fingerprint = RemoteAgentConfigFingerprint(RemoteAgentConfigProtocol.sha256(body.toByteArray()), body.toByteArray().size)
        val wire = "AICODE_CONFIG_1 M\nF\t$path\t${fingerprint.sha256}\t${fingerprint.size}\nEND\n"
        setup(wire.byteInputStream())
        val transport = SshAgentConfigTransport(connection, {})
        assertEquals(mapOf("agent.md" to fingerprint), transport.manifest(key))
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray())
        every { command.inputStream } returns "AICODE_CONFIG_1 B\nB\t$path\t$encoded\nEND\n".byteInputStream()
        assertEquals(mapOf("agent.md" to body), transport.bodies(key, mapOf("agent.md" to fingerprint)))
        assertArrayEquals(RemoteAgentConfigProtocol.request(listOf("agent.md")), input.toByteArray())
        verify(exactly = 2) { owner.startSession() }
        verify(exactly = 2) { session.close() }
        verify(exactly = 0) { owner.close() }
        verify(exactly = 0) { connection.startExecSessionWithStdin(any()) }
    }

    @Test fun changedIdentityDuringSetupClosesOnlyAcquiredOwnerChannel() = runBlocking {
        setup(ByteArrayInputStream(byteArrayOf()))
        every { session.exec(any()) } answers {
            current = RemoteConnectionIdentity(mockk(), identity.home)
            command
        }
        val failure = runCatching { SshAgentConfigTransport(connection, {}).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
        verify(exactly = 0) { connection.startExecSessionWithStdin(any()) }
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(): Int { entered.countDown(); check(closed.await(10, TimeUnit.SECONDS)); throw IOException("closed") }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = read()
        override fun close() { closed.countDown() }
    }

    @Test fun cancellationClosesOwnedChannelAndWakesBlockingReadersWithoutClosingTransport() = runBlocking {
        val output = BlockingInput()
        setup(output)
        every { session.close() } answers { output.close() }
        val operation = async(Dispatchers.Default) { SshAgentConfigTransport(connection, {}).execute(key, "command", byteArrayOf(), 10) }
        assertTrue(withContext(Dispatchers.IO) { output.entered.await(10, TimeUnit.SECONDS) })
        operation.cancelAndJoin()
        assertEquals(0L, output.closed.count)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun totalDeadlineClosesOwnedChannelOnBlockedOutput() = runBlocking {
        val output = BlockingInput()
        setup(output)
        every { session.close() } answers { output.close() }
        val failure = runCatching { SshAgentConfigTransport(connection, {}, 100).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0L, output.closed.count)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun stderrLimitFailureClosesConcurrentBlockedStdout() = runBlocking {
        val output = BlockingInput()
        setup(output, ByteArray(65537).inputStream())
        every { session.close() } answers { output.close() }
        val failure = runCatching { SshAgentConfigTransport(connection, {}, 5000).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0L, output.closed.count)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun cancellationWhileExecAckIsBlockedClosesSessionBeforeCommandExists() = runBlocking {
        setup(byteArrayOf().inputStream())
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { session.exec(any()) } answers {
            entered.countDown()
            try { check(closed.await(10, TimeUnit.SECONDS)) } catch (_: InterruptedException) { }
            throw IOException("exec closed")
        }
        every { session.close() } answers { closed.countDown() }
        val operation = async(Dispatchers.Default) { SshAgentConfigTransport(connection, {}).execute(key, "command", byteArrayOf(), 10) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
        operation.cancelAndJoin()
        assertEquals(0L, closed.count)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
        verify(exactly = 0) { connection.startExecSessionWithStdin(any()) }
    }

    @Test fun deadlineWhileExecAckIsBlockedClosesOnlyOwnedSession() = runBlocking {
        setup(byteArrayOf().inputStream())
        val closed = CountDownLatch(1)
        every { session.exec(any()) } answers {
            try { check(closed.await(10, TimeUnit.SECONDS)) } catch (_: InterruptedException) { }
            throw IOException("exec closed")
        }
        every { session.close() } answers { closed.countDown() }
        val failure = runCatching { SshAgentConfigTransport(connection, {}, 100).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0L, closed.count)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun activeOutputReadFailureRemainsAnIOException() = runBlocking {
        val expected = IOException("connection reset")
        setup(object : InputStream() {
            override fun read(): Int = throw expected
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = throw expected
        })
        val failure = runCatching { SshAgentConfigTransport(connection, {}).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(expected.message, failure!!.message)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun activeExecFailureRemainsAnIOException() = runBlocking {
        setup(byteArrayOf().inputStream())
        val expected = IOException("exec rejected")
        every { session.exec(any()) } throws expected
        val failure = runCatching { SshAgentConfigTransport(connection, {}).execute(key, "command", byteArrayOf(), 10) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(expected.message, failure!!.message)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
    }

    @Test fun nonzeroExitAndMalformedOutputDoNotBecomeEmptySuccessfulManifest() = runBlocking {
        setup("AICODE_CONFIG_1 M\nEND\n".byteInputStream())
        every { command.exitStatus } returns 1
        assertTrue(runCatching { SshAgentConfigTransport(connection, {}).manifest(key) }.exceptionOrNull() is IOException)
        every { command.exitStatus } returns 0
        every { command.inputStream } returns "bad manifest\n".byteInputStream()
        assertTrue(runCatching { SshAgentConfigTransport(connection, {}).manifest(key) }.exceptionOrNull() is IOException)
        verify(exactly = 2) { session.close() }
    }
}
