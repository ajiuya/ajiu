package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkspaceRemoteExecutionContextTest {
    private val holder = ExecutionModeHolder()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val local = mockk<LocalFileAccess>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val owner = mockk<SSHClient>()
    private val newOwner = mockk<SSHClient>()
    private val channel = mockk<SFTPClient>(relaxed = true)
    private val newChannel = mockk<SFTPClient>(relaxed = true)
    private val file = mockk<RemoteFile>(relaxed = true)
    private val session = mockk<Session>(relaxed = true)
    private val command = mockk<Session.Command>(relaxed = true)
    private val attributes = mockk<FileAttributes>()
    private val captured = RemoteConnectionIdentity(owner, "/home/alice")
    private var current = captured
    private var root = "/projects/one"
    private val data = "captured\n".toByteArray()
    private lateinit var remote: RemoteSftpFileAccess
    private lateinit var access: DelegatingFileAccess
    private lateinit var factory: WorkspaceExecutionContextFactory
    private lateinit var engine: RemoteSshEngine

    @Before
    fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.v(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.i(any(), any()) } just Runs
        holder.setMode(ExecutionMode.REMOTE_SSH)
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/fallback"
        )
        every { connection.remoteHome } answers { current.home }
        every { connection.captureIdentity() } answers { current }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== current) throw CancellationException("Changed connection")
        }
        every { connection.isConnected() } returns true
        every { connection.sftpOperationMutex } returns Mutex()
        every { workspace.currentPath() } answers { root }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== current) throw CancellationException("Changed connection")
            channel
        }
        coEvery { connection.sftp() } returns newChannel
        every { channel.statExistence(any()) } returns attributes
        every { attributes.type } returns FileMode.Type.REGULAR
        every { attributes.size } returns data.size.toLong()
        every { channel.open(any<String>()) } returns file
        every { channel.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>().toInt()
            val count = minOf(arg<Int>(3), data.size - offset)
            if (count <= 0) -1 else {
                data.copyInto(secondArg(), thirdArg(), offset, offset + count)
                count
            }
        }
        every { owner.startSession() } returns session
        every { session.exec(any()) } returns command
        every { command.inputStream } answers { ByteArrayInputStream(data) }
        every { command.errorStream } answers { ByteArrayInputStream(byteArrayOf()) }
        every { command.outputStream } returns ByteArrayOutputStream()
        every { command.exitStatus } returns 0
        every { command.isOpen } returns true
        remote = RemoteSftpFileAccess(connection, workspace)
        access = DelegatingFileAccess(holder, local, remote)
        factory = WorkspaceExecutionContextFactory(access, holder, local, remote, settings)
        engine = RemoteSshEngine(connection)
    }

    @After
    fun release() { unmockkObject(FileLogger) }

    @Test
    fun frozenFilesUseCapturedPrivateChannelForTextBytesLinesAndWrites() = runBlocking {
        val execution = factory.freeze()
        withContext(execution) {
            access.writeFile("~/workspace/out", "payload")
            assertEquals("captured\n", access.readFile("~/workspace/in"))
            assertArrayEquals(data, access.openRead("~/workspace/in").use { it.readBytes() })
            assertEquals(listOf("captured"), access.readLines("~/workspace/in").toList())
        }
        coVerify(exactly = 4) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.connect() }
        verify(exactly = 1) { channel.rename("/projects/one/out.aicode-part", "/projects/one/out") }
        verify(exactly = 0) { newChannel.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) }
    }

    @Test
    fun reconnectWhileAcquiringChannelCannotWriteToEitherNewTransportOrReturnedStaleChannel() = runBlocking {
        val execution = factory.freeze()
        coEvery { connection.openReadChannel(captured) } answers {
            current = RemoteConnectionIdentity(newOwner, "/home/alice")
            channel
        }
        val failure = runCatching { withContext(execution) { access.writeFile("~/workspace/out", "no") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { channel.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) }
        verify(exactly = 1) { channel.close() }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.openReadChannel(match { it !== captured }) }
    }

    @Test
    fun oldProjectGenerationCannotBeFrozenAgainstReconnectedIdentity() = runBlocking {
        val capture = ProjectMemoryCapture(ProjectMemoryIdentity(
            "remote_ssh", host = "test.invalid", port = 22, username = "alice", projectRoot = root
        ), captured.generation.toString()) {}
        current = RemoteConnectionIdentity(newOwner, "/home/alice")
        assertTrue(runCatching { factory.freeze(capture) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
    }

    @Test
    fun oldRemoteProjectCannotFreezeNewWorkspaceOnTheSameTransport() = runBlocking {
        val capture = ProjectMemoryCapture(ProjectMemoryIdentity(
            "remote_ssh", host = "test.invalid", port = 22, username = "alice", projectRoot = root
        ), captured.generation.toString()) {}
        root = "/projects/two"
        assertTrue(runCatching { factory.freeze(capture) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
    }

    @Test
    fun syncAndStreamUseCapturedExecOwnerAndNeverLiveConnectionSessionRouting() = runBlocking {
        val execution = factory.freeze()
        withContext(execution) {
            assertEquals("captured\n", engine.runCommandSync("identity", root, 1_000))
            assertEquals(listOf(CommandEvent.Line("captured"), CommandEvent.Exit(0)), engine.runCommandStream("identity", root, 1_000).toList())
        }
        verify(exactly = 2) { owner.startSession() }
        verify(exactly = 0) { connection.startExecSession(any()) }
        verify(exactly = 2) { session.exec(match { it.contains("cd '/projects/one' && identity") && !it.contains("cd ~/workspace") }) }
        coVerify(exactly = 0) { connection.connect() }
        current = RemoteConnectionIdentity(newOwner, "/home/alice")
        assertTrue(runCatching { withContext(execution) { engine.runCommandSync("late", root, 1_000) } }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { withContext(execution) { engine.runCommandStream("late", root, 1_000).toList() } }.exceptionOrNull() is CancellationException)
        verify(exactly = 0) { newOwner.startSession() }
    }

    @Test
    fun reconnectDuringStartSessionClosesOldSessionBeforeExec() = runBlocking {
        val execution = factory.freeze()
        every { owner.startSession() } answers {
            current = RemoteConnectionIdentity(newOwner, "/home/bob")
            session
        }
        assertTrue(runCatching { withContext(execution) { engine.runCommandSync("must-not-exec", root, 1_000) } }.exceptionOrNull() is CancellationException)
        verify(exactly = 0) { session.exec(any()) }
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { newOwner.startSession() }
        verify(exactly = 0) { connection.startExecSession(any()) }
    }

    @Test
    fun staleExecutionCannotCrossModeButNormalBrowserStillUsesLiveSftp() = runBlocking {
        val execution = factory.freeze()
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        assertTrue(runCatching { withContext(execution) { access.writeBytes("out", data) } }.exceptionOrNull() is CancellationException)
        verify(exactly = 0) { local.writeBytes(any(), any(), any()) }
        holder.setMode(ExecutionMode.REMOTE_SSH)
        every { newChannel.statExistence("/projects/one/in") } returns attributes
        every { newChannel.open("/projects/one/in") } returns file
        assertArrayEquals(data, access.readBytes("~/workspace/in"))
        coVerify(exactly = 1) { connection.sftp() }
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
    }

    @Test
    fun cancellationClosesPrivateReadAfterRunBlockingReturnsWithoutWaitingForAnotherRead() = runBlocking {
        val execution = factory.freeze()
        val opened = CompletableDeferred<Unit>()
        val job = launch(execution + Dispatchers.IO) {
            val input = access.openRead("~/workspace/in")
            assertEquals('c'.code, input.read())
            opened.complete(Unit)
            try { awaitCancellation() } finally { input.close() }
        }
        opened.await()
        job.cancelAndJoin()
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test
    fun cancellingStreamClosesCapturedExecAndUnblocksItsRead() = runBlocking {
        val execution = factory.freeze()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { command.close() } answers { closed.countDown() }
        every { command.inputStream } returns object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(closed.await(3, TimeUnit.SECONDS)) { "Cancellation did not close the SSH command" }
                throw IOException("Command closed")
            }
        }
        val job = launch(execution + Dispatchers.IO) { engine.runCommandStream("blocking", root, 10_000).toList() }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertEquals(0L, closed.count)
        verify(atLeast = 1) { command.close() }
        verify(exactly = 0) { newOwner.startSession() }
    }
}
