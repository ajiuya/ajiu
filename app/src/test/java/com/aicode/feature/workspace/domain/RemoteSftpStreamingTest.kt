package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.sync.Mutex
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteSftpStreamingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val channel = mockk<SFTPClient>(relaxed = true)
    private val file = mockk<RemoteFile>(relaxed = true)
    private val attributes = mockk<FileAttributes>()
    private var identity = RemoteConnectionIdentity(mockk(), "/home/alice")
    private var root = "/projects/one"
    private lateinit var access: RemoteSftpFileAccess
    private lateinit var backing: java.io.File
    private val requests = mutableListOf<Int>()
    private var afterRead: () -> Unit = {}

    @Before
    fun prepare() {
        backing = temporary.newFile().apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/fallback"
        )
        every { connection.remoteHome } answers { identity.home }
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed connection")
        }
        every { connection.sftpOperationMutex } returns Mutex()
        every { workspace.currentPath() } answers { root }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed connection")
            channel
        }
        coEvery { connection.sftp() } returns channel
        every { channel.statExistence(any()) } returns attributes
        every { attributes.type } returns FileMode.Type.REGULAR
        every { attributes.size } answers { backing.length() }
        every { channel.open(any<String>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>()
            val bytes = secondArg<ByteArray>()
            val start = thirdArg<Int>()
            val length = arg<Int>(3)
            requests += length
            val count = RandomAccessFile(backing, "r").use { input -> input.seek(offset); input.read(bytes, start, length) }
            afterRead()
            count
        }
        access = RemoteSftpFileAccess(connection, workspace, mockk<RemoteAgentConfigCache>(relaxed = true) {
            every { captureProfile() } returns RemoteAgentConfigProfile("fixture", 0)
            every { invalidation(any()) } returns {}
        })
    }

    @Test
    fun streamingUsesCapturedPathAndPrivateChannelWithBoundedReadsAndIdempotentClose() {
        val captured = identity
        val input = access.openRead("~/workspace/image.bin", access.cacheIdentity())
        verify(exactly = 0) { file.read(any(), any(), any(), any()) }
        assertEquals(32 * 1024, input.read(ByteArray(100_000)))
        assertTrue(requests.all { it <= 32 * 1024 })
        input.close()
        input.close()
        verify(exactly = 1) { channel.open("/projects/one/image.bin") }
        coVerify(exactly = 1) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.sftp() }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test
    fun eofReleasesHandleAndChannelWithoutReopening() {
        val input = access.openRead("/images/source")
        assertEquals(backing.length(), input.readBytes().size.toLong())
        assertEquals(-1, input.read())
        input.close()
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        coVerify(exactly = 1) { connection.openReadChannel(any()) }
    }

    @Test
    fun connectionChangeDuringIoCancelsInsteadOfReturningBytesOrFallingBack() {
        val input = access.openRead("~/workspace/image.bin", access.cacheIdentity())
        afterRead = { identity = RemoteConnectionIdentity(mockk(), "/home/bob") }
        assertThrows(CancellationException::class.java) { input.readBytes() }
        input.close()
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        coVerify(exactly = 1) { connection.openReadChannel(any()) }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test
    fun staleIdentityCannotOpenAChannelOnTheNewServer() {
        val old = access.cacheIdentity()
        identity = RemoteConnectionIdentity(mockk(), "/home/alice")
        assertNotEquals(old, access.cacheIdentity())
        assertThrows(CancellationException::class.java) { access.openRead("~/workspace/image.bin", old) }
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
        verify(exactly = 0) { channel.open(any<String>()) }
    }

    @Test
    fun workspaceChangeDuringIoClosesTheCapturedLease() {
        val input = access.openRead("~/workspace/image.bin")
        afterRead = { root = "/projects/two" }
        assertThrows(CancellationException::class.java) { input.read(ByteArray(20)) }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        verify(exactly = 0) { channel.open("/projects/two/image.bin") }
    }

    @Test
    fun openFailureReleasesTheChannelAndReadFailureKeepsItsOriginalType() {
        val failure = IOException("broken stream")
        every { channel.open("/broken") } throws failure
        assertEquals(failure, assertThrows(IOException::class.java) { access.openRead("/broken") })
        verify(exactly = 1) { channel.close() }
        every { file.read(any(), any(), any(), any()) } throws failure
        val input = access.openRead("/valid")
        assertEquals(failure, assertThrows(IOException::class.java) { input.read() })
        verify(exactly = 2) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test
    fun cancellationIsNotConvertedToIoErrorOrSharedChannelInvalidation() {
        val cancelled = CancellationException("cancel read")
        every { file.read(any(), any(), any(), any()) } throws cancelled
        val input = access.openRead("/valid")
        assertEquals(cancelled, assertThrows(CancellationException::class.java) { input.read() })
        coVerify(exactly = 0) { connection.invalidateSftp() }
        coEvery { connection.sftp() } throws cancelled
        val propagated = assertThrows(CancellationException::class.java) { access.readBytes("/valid") }
        assertEquals(cancelled.message, propagated.message)
        assertTrue(propagated === cancelled || propagated.cause === cancelled)
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun closeInterruptsAnInFlightReadBeforeClosingItsHandle() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        every { channel.close() } answers { released.countDown() }
        every { file.read(any(), any(), any(), any()) } answers {
            entered.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            throw IOException("Channel closed")
        }
        val input = access.openRead("/valid")
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<IOException> { assertThrows(IOException::class.java) { input.read() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            input.close()
            read.get(2, TimeUnit.SECONDS)
            verify(exactly = 1) { channel.close() }
            verify(exactly = 1) { file.close() }
        } finally {
            input.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun readBytesKeepsItsEightMiBBoundaryEvenWhenFileGrowsDuringTransfer() {
        val limit = 8L * 1024 * 1024
        RandomAccessFile(backing, "rw").use { it.setLength(limit) }
        assertEquals(limit, access.readBytes("/boundary").size.toLong())
        RandomAccessFile(backing, "rw").use { it.setLength(limit + 1) }
        every { attributes.size } returns 1L
        assertThrows(RemoteOutputTooLargeException::class.java) { access.readBytes("/growing") }
        assertTrue(requests.all { it <= 32 * 1024 })
    }
}
