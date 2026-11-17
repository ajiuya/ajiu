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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteSftpRootReadTest {
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val channels = mutableListOf<SFTPClient>()
    private val handles = mutableListOf<RemoteFile>()
    private var identity = RemoteConnectionIdentity(mockk(), "/home/alice")
    private var workspacePath = "/projects/one"
    private var canonicalRoot = "/projects/one/site"
    private var target = "/projects/one/site/entry.html"
    private var fileType = FileMode.Type.REGULAR
    private var fileSize = 5L
    private var read: (Long, ByteArray, Int, Int) -> Int = { offset, bytes, off, len ->
        val content = "entry".toByteArray()
        val count = minOf(len, content.size - offset.toInt())
        if (count <= 0) -1 else {
            content.copyInto(bytes, off, offset.toInt(), offset.toInt() + count)
            count
        }
    }
    private var onChannel: (SFTPClient) -> Unit = {}
    private lateinit var access: RemoteSftpFileAccess

    @Before
    fun prepare() {
        every { workspace.currentPath() } answers { workspacePath }
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/fallback",
        )
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Connection changed")
        }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Connection changed")
            newChannel().also(onChannel)
        }
        access = RemoteSftpFileAccess(connection, workspace)
    }

    @Test
    fun captureFreezesCanonicalOriginalParentAndClosesItsMetadataChannel() {
        val root = access.captureReadRoot("~/workspace/site")
        assertEquals("/projects/one/site", root.remotePath)
        assertEquals("/projects/one/site", root.canonicalPath)
        assertSame(identity, root.source.identity)
        verify(exactly = 1) { channels.single().close() }
        verify(exactly = 0) { channels.single().open(any<String>()) }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test
    fun openUsesOnlyAPrivateChannelAndARegularCanonicalTarget() {
        val root = access.captureReadRoot("~/workspace/site")
        access.openRootRead("~/workspace/site/entry.html", root, 100).use { input ->
            assertEquals("entry", input.bufferedReader().readText())
        }
        assertEquals(2, channels.size)
        channels.forEach { channel -> verify(exactly = 1) { channel.close() } }
        verify(exactly = 1) { channels.last().open("/projects/one/site/entry.html") }
        verify(exactly = 1) { handles.last().close() }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun leafSymlinkOutsideCapturedOriginalParentIsRejectedWithoutOpening() {
        val root = access.captureReadRoot("~/workspace/site")
        target = "/etc/passwd"
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        verify(exactly = 0) { channels.last().open(any<String>()) }
        verify(exactly = 1) { channels.last().close() }
    }

    @Test
    fun canonicalPrefixLookalikeAndDirectLogicalEscapesAreRejected() {
        val root = access.captureReadRoot("~/workspace/site")
        target = "/projects/one/site-other/private"
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/other/private", root, 100) }
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/../private", root, 100) }
        channels.drop(1).forEach { channel -> verify(exactly = 0) { channel.open(any<String>()) } }
    }

    @Test
    fun changedCanonicalRootCannotBeRecapturedAsANewAuthorizedDirectory() {
        val root = access.captureReadRoot("~/workspace/site")
        canonicalRoot = "/etc"
        target = "/etc/entry.html"
        assertThrows(CancellationException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        verify(exactly = 0) { channels.last().open(any<String>()) }
    }

    @Test
    fun changedPathKeepsTheAuthorizedOpenHandleButRejectsANewOpen() {
        val root = access.captureReadRoot("~/workspace/site")
        val input = access.openRootRead("~/workspace/site/entry.html", root, 100)
        val openedChannel = channels.last()
        val openedHandle = handles.last()
        assertEquals('e'.code, input.read())
        canonicalRoot = "/etc"
        target = "/etc/private"
        input.use {
            assertEquals('n'.code, it.read())
            assertEquals('t'.code, it.read())
            assertEquals('r'.code, it.read())
            assertEquals('y'.code, it.read())
            assertEquals(-1, it.read())
        }
        verify(exactly = 4) { openedChannel.canonicalize(any()) }
        verify(exactly = 3) { openedChannel.statExistence(any()) }
        verify(exactly = 1) { openedChannel.open("/projects/one/site/entry.html") }
        verify(exactly = 6) { openedHandle.read(any(), any(), any(), any()) }
        verify(exactly = 1) { openedChannel.close() }
        verify(exactly = 1) { openedHandle.close() }
        assertThrows(CancellationException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        verify(exactly = 0) { channels.last().open(any<String>()) }
        assertThrows(CancellationException::class.java) { access.rootReadSize("~/workspace/site/entry.html", root) }
    }

    @Test
    fun nonRegularAndOversizedTargetsAreRejectedBeforeOpeningAHandle() {
        val root = access.captureReadRoot("~/workspace/site")
        fileType = FileMode.Type.DIRECTORY
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        fileType = FileMode.Type.REGULAR
        fileSize = 101
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        channels.drop(1).forEach { channel -> verify(exactly = 0) { channel.open(any<String>()) } }
        fileSize = 1
        access.openRootRead("~/workspace/site/entry.html", root, 4).use { input ->
            val bytes = ByteArray(4)
            assertEquals(4, input.read(bytes))
            assertEquals("entr", bytes.toString(Charsets.UTF_8))
            assertThrows(IOException::class.java) { input.read() }
        }
        verify(exactly = 1) { channels.last().close() }
        verify(exactly = 1) { handles.last().close() }
    }

    @Test
    fun missingTargetsAreNotReportedAsAnEmptySuccessfulFile() {
        val root = access.captureReadRoot("~/workspace/site")
        onChannel = { channel -> every { channel.statExistence(target) } returns null }
        assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        assertThrows(IOException::class.java) { access.rootReadSize("~/workspace/site/entry.html", root) }
    }

    @Test
    fun reconnectOrWorkspaceSwitchRejectsTheOldCaptureBeforeOpeningAnotherChannel() {
        val root = access.captureReadRoot("~/workspace/site")
        val captured = identity
        identity = RemoteConnectionIdentity(mockk(), "/home/alice")
        assertThrows(CancellationException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        identity = captured
        workspacePath = "/projects/two"
        assertThrows(CancellationException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        coVerify(exactly = 1) { connection.openReadChannel(any()) }
    }

    @Test
    fun frozenExecutionChecksArePreservedAcrossTheSynchronousFacade() {
        val root = access.captureReadRoot("~/workspace/site")
        var active = true
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured", access, null, root.source) {
            if (!active) throw CancellationException("Execution changed")
        }
        val input = runBlocking(execution) { access.openRootRead("~/workspace/site/entry.html", root, 100) }
        active = false
        assertThrows(CancellationException::class.java) {
            runBlocking(execution) { input.read() }
        }
        verify(exactly = 1) { channels.last().close() }
    }

    @Test
    fun metadataCancellationClosesTheChannelBeforeAHandleHasBeenOpened() {
        val root = access.captureReadRoot("~/workspace/site")
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        onChannel = { channel ->
            every { channel.canonicalize(root.remotePath) } answers {
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS))
                throw IOException("closed")
            }
            every { channel.close() } answers { released.countDown() }
        }
        val source = root.source
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured", access, null, source) {
            access.checkExecutionSource(source)
        }
        val owner = Job()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                runCatching { runBlocking(owner + execution) { access.openRootRead("~/workspace/site/entry.html", root, 100) } }.exceptionOrNull()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.cancel()
            val failure = result.get(5, TimeUnit.SECONDS)
            assertTrue("Expected cancellation, got $failure", failure is CancellationException)
            verify(exactly = 1) { channels.last().close() }
            verify(exactly = 0) { channels.last().open(any<String>()) }
        } finally {
            owner.cancel()
            released.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun captureCancellationClosesItsChannelWhileCanonicalizationIsStillBlocked() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        onChannel = { channel ->
            every { channel.canonicalize(any()) } answers {
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS))
                throw IOException("closed")
            }
            every { channel.close() } answers { released.countDown() }
        }
        val source = access.captureExecutionSource()
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured", access, null, source) {
            access.checkExecutionSource(source)
        }
        val owner = Job()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                runCatching { runBlocking(owner + execution) { access.captureReadRoot("~/workspace/site") } }.exceptionOrNull()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.cancel()
            val failure = result.get(5, TimeUnit.SECONDS)
            assertTrue("Expected cancellation, got $failure", failure is CancellationException)
            verify(exactly = 1) { channels.last().close() }
        } finally {
            owner.cancel()
            released.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun originalIOExceptionTypeIsPreservedWithoutDiscardingSharedSftpState() {
        val root = access.captureReadRoot("~/workspace/site")
        val failure = IOException("broken private read")
        onChannel = { channel -> every { channel.open(any<String>()) } throws failure }
        assertSame(failure, assertThrows(IOException::class.java) { access.openRootRead("~/workspace/site/entry.html", root, 100) })
        coVerify(exactly = 0) { connection.invalidateSftp() }
        verify(exactly = 1) { channels.last().close() }
    }

    @Test
    fun genericReadRootDoesNotReusePlaybackOrSharedChannelEntrypoints() {
        val root = access.captureReadRoot("~/workspace/site")
        assertEquals(5L, access.rootReadSize("~/workspace/site/entry.html", root))
        access.openRootRead("~/workspace/site/entry.html", root, 100).close()
        assertEquals(3, channels.size)
        coVerify(exactly = 3) { connection.openReadChannel(identity) }
        coVerify(exactly = 0) { connection.sftp() }
        coVerify(exactly = 0) { connection.disconnect() }
    }

    private fun newChannel(): SFTPClient {
        val channel = mockk<SFTPClient>(relaxed = true)
        val file = mockk<RemoteFile>(relaxed = true)
        channels += channel
        handles += file
        every { channel.canonicalize(any()) } answers {
            when (firstArg<String>()) {
                "/projects/one/site" -> canonicalRoot
                else -> target
            }
        }
        every { channel.statExistence(any()) } answers {
            val directory = firstArg<String>() == canonicalRoot
            mockk<FileAttributes>().also { attributes ->
                every { attributes.type } returns if (directory) FileMode.Type.DIRECTORY else fileType
                every { attributes.size } returns if (directory) 0 else fileSize
            }
        }
        every { channel.open(any<String>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            read(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        return channel
    }
}
