package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteSftpBoundedReadTest {
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val channel = mockk<SFTPClient>(relaxed = true)
    private val file = mockk<RemoteFile>(relaxed = true)
    private val attributes = mockk<FileAttributes>()
    private var identity = RemoteConnectionIdentity(mockk(), "/home/alice")
    private var root = "/projects/one"
    private var bytes = byteArrayOf(1, 2, 3)
    private var declaredSize = 3L
    private var transferred = 0L
    private var afterRead: () -> Unit = {}
    private val requests = mutableListOf<Pair<Long, Int>>()
    private lateinit var access: RemoteSftpFileAccess

    @Before
    fun prepare() {
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = "/fallback"
        )
        every { connection.remoteHome } answers { identity.home }
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Connection changed")
        }
        every { workspace.currentPath() } answers { root }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Connection changed")
            channel
        }
        every { channel.statExistence(any()) } returns attributes
        every { attributes.type } returns FileMode.Type.REGULAR
        every { attributes.size } answers { declaredSize }
        every { channel.open(any<String>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>()
            val length = arg<Int>(3)
            requests += offset to length
            val count = minOf(length.toLong(), bytes.size - offset).toInt()
            if (count < 1) -1 else {
                bytes.copyInto(secondArg(), thirdArg(), offset.toInt(), offset.toInt() + count)
                transferred += count
                afterRead()
                count
            }
        }
        access = RemoteSftpFileAccess(connection, workspace, mockk<RemoteAgentConfigCache>(relaxed = true) {
            every { captureProfile() } returns RemoteAgentConfigProfile("fixture", 0)
        })
    }

    private fun frozen(): WorkspaceExecutionContext {
        val source = access.captureExecutionSource()
        return WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) {
            access.checkExecutionSource(source)
        }
    }

    @Test
    fun exactLimitReadsOnCapturedPrivateChannelAndClosesAllResources() {
        val captured = identity
        assertArrayEquals(bytes, access.readBytes("~/workspace/file", 3))
        assertEquals(3L, transferred)
        assertTrue(requests.all { (offset, length) -> offset + length <= 4L })
        verify(exactly = 1) { channel.open("/projects/one/file") }
        verify(exactly = 1) { file.close() }
        verify(exactly = 1) { channel.close() }
        coVerify(exactly = 1) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun emptyZeroLimitUsesOneEofProbeAndCloses() {
        bytes = byteArrayOf()
        declaredSize = 0
        assertArrayEquals(byteArrayOf(), access.readBytes("/empty", 0))
        assertEquals(listOf(0L to 1), requests)
        verify(exactly = 1) { file.close() }
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun nonEmptyZeroLimitFailsPreflightWithoutOpeningFileOrInvalidatingSharedSftp() {
        val failure = assertThrows(FileReadTooLargeException::class.java) { access.readBytes("/nonempty", 0) }
        assertEquals(0L, failure.maxBytes)
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 0) { file.read(any(), any(), any(), any()) }
        verify(exactly = 1) { channel.close() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun growingFileIsStoppedAtLimitPlusOneAndDoesNotInvalidateGoodConnection() {
        val limit = 20_000L
        bytes = ByteArray(30_000)
        declaredSize = 1
        val failure = assertThrows(FileReadTooLargeException::class.java) { access.readBytes("/growing", limit) }
        assertEquals(limit, failure.maxBytes)
        assertEquals(limit + 1, transferred)
        assertTrue(requests.all { (offset, length) -> offset + length <= limit + 1 })
        verify(exactly = 1) { file.close() }
        verify(exactly = 1) { channel.close() }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun maximumSupportedLimitDoesNotOverflowOrAllocateTheWholeLimit() {
        assertArrayEquals(bytes, access.readBytes("/small", Int.MAX_VALUE.toLong()))
        assertTrue(requests.all { it.second <= 32 * 1024 })
        verify(exactly = 1) { file.close() }
    }

    @Test
    fun invalidLimitsFailBeforeCapturingAConnection() {
        for (limit in listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { access.readBytes("/file", limit) }
        }
        verify(exactly = 0) { connection.captureIdentity() }
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
    }

    @Test
    fun zeroProgressIsIoFailureAndClosesThePrivateLease() {
        every { file.read(any(), any(), any(), any()) } returns 0
        assertThrows(IOException::class.java) { access.readBytes("/stalled", 10) }
        verify(exactly = 1) { file.read(any(), any(), any(), any()) }
        verify(exactly = 1) { file.close() }
        verify(exactly = 1) { channel.close() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun reconnectDuringReadNeverOpensTheNewBackendOrReturnsOldBytes() {
        val captured = identity
        afterRead = { identity = RemoteConnectionIdentity(mockk(), "/home/bob") }
        assertThrows(CancellationException::class.java) { access.readBytes("~/workspace/file", 10) }
        coVerify(exactly = 1) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.openReadChannel(match { it !== captured }) }
        coVerify(exactly = 0) { connection.sftp() }
        verify(exactly = 1) { file.close() }
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun workspaceChangeDuringReadClosesCapturedLeaseWithoutOpeningNewPath() {
        afterRead = { root = "/projects/two" }
        assertThrows(CancellationException::class.java) { access.readBytes("~/workspace/file", 10) }
        verify(exactly = 0) { channel.open("/projects/two/file") }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test
    fun staleFrozenSourceCannotAcquireAChannelOnNewServer() = runBlocking {
        val execution = frozen()
        identity = RemoteConnectionIdentity(mockk(), "/home/bob")
        val failure = runCatching { withContext(execution) { access.readBytes("file", 10) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
        verify(exactly = 0) { channel.open(any<String>()) }
    }

    @Test
    fun cancellingOwnedPreflightClosesChannelBeforeAFileHandleExists() = runBlocking {
        val execution = frozen()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { channel.close() } answers { closed.countDown() }
        every { channel.statExistence(any()) } answers {
            entered.countDown()
            check(closed.await(3, TimeUnit.SECONDS)) { "Cancellation did not close preflight SFTP" }
            throw IOException("Channel closed")
        }
        val job = launch(execution + Dispatchers.IO) { access.readBytes("file", 10) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        verify(exactly = 1) { channel.close() }
        verify(exactly = 0) { channel.open(any<String>()) }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun cancellingOwnedBoundedReadClosesChannelAndUnblocksIo() = runBlocking {
        val execution = frozen()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { channel.close() } answers { closed.countDown() }
        every { file.read(any(), any(), any(), any()) } answers {
            entered.countDown()
            check(closed.await(3, TimeUnit.SECONDS)) { "Cancellation did not close private SFTP" }
            throw IOException("Channel closed")
        }
        val job = launch(execution + Dispatchers.IO) { access.readBytes("file", 10) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertEquals(0L, closed.count)
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }
}
