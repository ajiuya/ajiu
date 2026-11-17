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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteSftpFingerprintTest {
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
    private val buffers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())
    private var afterRead: () -> Unit = {}

    @Before fun prepare() {
        backing = temporary.newFile().apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/fallback"
        )
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed connection")
        }
        every { workspace.currentPath() } answers { root }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed connection")
            channel
        }
        every { channel.statExistence(any()) } returns attributes
        every { attributes.type } returns FileMode.Type.REGULAR
        every { attributes.size } answers { backing.length() }
        every { channel.open(any<String>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>()
            val bytes = secondArg<ByteArray>()
            val start = thirdArg<Int>()
            val length = arg<Int>(3)
            requests += length; buffers += bytes
            val count = RandomAccessFile(backing, "r").use { input -> input.seek(offset); input.read(bytes, start, length) }
            afterRead()
            count
        }
        access = RemoteSftpFileAccess(connection, workspace)
    }

    @Test fun streamingHashMatchesTheContentFingerprintUsingOneBoundedBufferAndAPrivateCapturedChannel() {
        val captured = identity
        assertEquals(sha256Fingerprint(backing.readBytes()), access.fingerprint("~/workspace/link.bin"))
        assertEquals(1, buffers.size)
        assertTrue(buffers.single().size <= 32 * 1024)
        assertTrue(requests.all { it in 1..32 * 1024 })
        coVerify(exactly = 1) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.sftp() }
        verify(exactly = 1) { channel.statExistence("/projects/one/link.bin") }
        verify(exactly = 1) { channel.open("/projects/one/link.bin") }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test fun missingAndNonRegularFilesReturnNullWithoutOpeningAHandle() {
        every { channel.statExistence("/missing") } returns null
        assertNull(access.fingerprint("/missing"))
        every { attributes.type } returns FileMode.Type.DIRECTORY
        assertNull(access.fingerprint("/directory"))
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 2) { channel.close() }
    }

    @Test fun reportedOversizeIsRejectedBeforeOpeningAndTheCapturedChannelIsReleased() {
        every { attributes.size } returns 8L * 1024 * 1024 + 1
        assertThrows(RemoteOutputTooLargeException::class.java) { access.fingerprint("/large") }
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 1) { channel.close() }
    }

    @Test fun eightMiBBoundaryIsAcceptedAndGrowthBeyondItIsDetectedWithASingleByteProbe() {
        val limit = 8L * 1024 * 1024
        RandomAccessFile(backing, "rw").use { it.setLength(limit) }
        assertEquals(sha256Fingerprint(backing.readBytes()), access.fingerprint("/boundary"))
        assertEquals(1, requests.last())
        RandomAccessFile(backing, "rw").use { it.setLength(limit + 1) }
        every { attributes.size } returns 1L
        assertThrows(RemoteOutputTooLargeException::class.java) { access.fingerprint("/growing") }
        assertEquals(1, requests.last())
        verify(exactly = 2) { channel.close() }
        verify(exactly = 2) { file.close() }
    }

    @Test fun connectionOrWorkspaceChangeDuringReadCannotPublishAHashOrUseTheNewSource() {
        afterRead = { identity = RemoteConnectionIdentity(mockk(), "/home/bob") }
        assertThrows(CancellationException::class.java) { access.fingerprint("~/workspace/link.bin") }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
        afterRead = { root = "/projects/two" }
        assertThrows(CancellationException::class.java) { access.fingerprint("~/workspace/link.bin") }
        verify(exactly = 2) { channel.open("/projects/one/link.bin") }
        verify(exactly = 0) { channel.open("/projects/two/link.bin") }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test fun frozenExecutionRejectsStaleIdentityBeforeOpeningAChannel() {
        val source = access.captureExecutionSource()
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured", access, null, source) {
            access.checkExecutionSource(source)
        }
        identity = RemoteConnectionIdentity(mockk(), "/home/alice")
        assertThrows(CancellationException::class.java) { runBlocking(execution) { access.fingerprint("~/workspace/link") } }
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
    }

    @Test fun executionCancellationClosesTheCapturedChannelToWakeABlockedFingerprintRead() {
        val source = access.captureExecutionSource()
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured", access, null, source) {
            access.checkExecutionSource(source)
        }
        val owner = Job()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { channel.close() } answers { closed.countDown() }
        every { file.read(any(), any(), any(), any()) } answers {
            entered.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            throw IOException("Channel closed")
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<CancellationException> {
                assertThrows(CancellationException::class.java) {
                    runBlocking(owner + execution) { access.fingerprint("~/workspace/link") }
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            owner.cancel()
            result.get(3, TimeUnit.SECONDS)
            verify(exactly = 1) { channel.close() }
            verify(exactly = 1) { file.close() }
        } finally {
            owner.cancel(); worker.shutdownNow()
        }
    }

    @Test fun readOrOpenFailureRetainsItsTypeAndDoesNotInvalidateTheSharedSftpTransport() {
        val failure = IOException("broken private channel")
        every { channel.open("/broken") } throws failure
        assertEquals(failure, assertThrows(IOException::class.java) { access.fingerprint("/broken") })
        every { file.read(any(), any(), any(), any()) } throws failure
        assertEquals(failure, assertThrows(IOException::class.java) { access.fingerprint("/valid") })
        verify(exactly = 2) { channel.close() }
        verify(exactly = 1) { file.close() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }
}
