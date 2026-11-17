package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerRemotePathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val channel = mockk<SFTPClient>(relaxed = true)
    private val file = mockk<RemoteFile>(relaxed = true)
    private val attributes = mockk<FileAttributes>()
    private var identity = RemoteConnectionIdentity(mockk(), "/home/alice")
    private var root = "/projects/one"
    private lateinit var access: RemoteSftpFileAccess

    @Before fun prepare() {
        every { connection.config } returns RemoteConnectionConfig("host.example", username = "alice",
            auth = RemoteAuth.Password("fixture-only"), remoteWorkspacePath = root)
        every { connection.remoteHome } answers { identity.home }
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed fixture connection")
        }
        every { connection.sftpOperationMutex } returns Mutex()
        every { workspace.currentPath() } answers { root }
        coEvery { connection.sftp() } returns channel
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed fixture connection")
            channel
        }
        every { channel.canonicalize(any()) } answers { firstArg() }
        every { channel.statExistence(any()) } returns attributes
        every { attributes.type } returns FileMode.Type.REGULAR
        every { channel.open(any<String>()) } returns file
        access = RemoteSftpFileAccess(connection, workspace)
    }

    @Test fun staticSymlinkOutsidePlaybackRootIsRejectedBeforeOpeningFileHandle() {
        every { channel.canonicalize("/projects/one/leak.mp4") } returns "/tmp/private.mp4"
        val expected = access.cacheIdentity()
        assertThrows(IOException::class.java) { access.checkPlaybackPath("~/workspace/leak.mp4", "~/workspace", expected) }
        assertThrows(IOException::class.java) { access.openPlaybackRead("~/workspace/leak.mp4", "~/workspace", expected) }
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 1) { channel.close() }
    }

    @Test fun canonicalParentAndSiblingPrefixesCannotEscapePlaybackRoot() {
        every { channel.canonicalize("/projects/one/folder/leak.mp4") } returns "/projects/one-other/leak.mp4"
        val expected = access.cacheIdentity()
        assertThrows(IOException::class.java) { access.openPlaybackRead("~/workspace/folder/leak.mp4", "~/workspace", expected) }
        every { channel.canonicalize("/projects/one/folder/leak.mp4") } returns "/projects/one/../private.mp4"
        assertThrows(IOException::class.java) { access.openPlaybackRead("~/workspace/folder/leak.mp4", "~/workspace", expected) }
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 2) { channel.close() }
    }

    @Test fun allowedSymlinkOpensItsCanonicalTargetOnTheOwnedChannel() {
        every { channel.canonicalize("/projects/one/alias.mp4") } returns "/projects/one/media/clip.mp4"
        every { file.read(any(), any(), any(), any()) } answers {
            if (firstArg<Long>() > 0) -1 else {
                "media".toByteArray().copyInto(secondArg(), thirdArg())
                5
            }
        }
        val captured = identity
        val input = access.openPlaybackRead("~/workspace/alias.mp4", "~/workspace", access.cacheIdentity())
        assertEquals("media", input.readBytes().toString(Charsets.UTF_8))
        input.close()
        input.close()
        verify(exactly = 1) { channel.open("/projects/one/media/clip.mp4") }
        verify(exactly = 0) { channel.open("/projects/one/alias.mp4") }
        coVerify(exactly = 1) { connection.openReadChannel(captured) }
        coVerify(exactly = 0) { connection.sftp() }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test fun nonRegularTargetsAreRejectedBeforeOpeningAHandle() {
        every { attributes.type } returns FileMode.Type.SYMLINK
        assertThrows(IOException::class.java) { access.openPlaybackRead("~/workspace/alias.mp4", "~/workspace", access.cacheIdentity()) }
        verify(exactly = 0) { channel.open(any<String>()) }
        verify(exactly = 1) { channel.close() }
    }

    @Test fun staleGenerationCannotAcquireANewPrivateChannel() {
        val expected = access.cacheIdentity()
        identity = RemoteConnectionIdentity(mockk(), "/home/alice")
        assertThrows(CancellationException::class.java) { access.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", expected) }
        coVerify(exactly = 0) { connection.openReadChannel(any()) }
        verify(exactly = 0) { channel.open(any<String>()) }
    }

    @Test fun generationChangeDuringReadClosesItsHandleAndChannelWithoutPublishingBytes() {
        val input = access.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", access.cacheIdentity())
        every { file.read(any(), any(), any(), any()) } answers {
            identity = RemoteConnectionIdentity(mockk(), "/home/other")
            secondArg<ByteArray>()[thirdArg()] = 1
            1
        }
        assertThrows(CancellationException::class.java) { input.read(ByteArray(10)) }
        input.close()
        verify(exactly = 1) { channel.close() }
        verify(exactly = 1) { file.close() }
    }

    @Test fun realFrozenBindingCopiesRemoteMediaDuringItsLiveOwnerJobAndClosesEveryChannel() {
        val channels = CopyOnWriteArrayList<SFTPClient>()
        val metadata = mockk<FileAttributes>()
        every { metadata.type } returns FileMode.Type.REGULAR
        every { metadata.size } returns 10L
        every { metadata.mtime } returns 1L
        every { metadata.permissions } returns emptySet()
        val info = mockk<RemoteResourceInfo>()
        every { info.name } returns "clip.mp4"
        every { info.isDirectory } returns false
        every { info.attributes } returns metadata
        val expectedBytes = "0123456789".toByteArray()
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>().toInt()
            if (offset >= expectedBytes.size) -1 else {
                val count = minOf(arg<Int>(3), expectedBytes.size - offset)
                expectedBytes.copyInto(secondArg(), thirdArg(), offset, offset + count)
                count
            }
        }
        coEvery { connection.openReadChannel(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Changed fixture connection")
            mockk<SFTPClient>(relaxed = true).also { owned ->
                every { owned.canonicalize(any()) } answers { firstArg() }
                every { owned.statExistence(any()) } returns metadata
                every { owned.ls("/projects/one") } returns listOf(info)
                every { owned.open("/projects/one/clip.mp4") } returns file
                channels += owned
            }
        }
        val source = access.captureExecutionSource()
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) {
            access.checkExecutionSource(source)
        }
        val owner = Job()
        val project = ProjectMemoryIdentity("remote_ssh", "host.example", 22, "alice", root)
        val capture = ProjectMemoryCapture(project, identity.generation.toString()) { connection.checkIdentity(identity) }
        val binding = PlayerWorkspaceBinding(execution, capture, owner) {}
        val ownedCopy = File(temporary.root, "remote.copy")
        val library = PlayerLibrary(binding, "~/workspace", "/T/", PlayerResources(), createCopy = { ownedCopy })
        try {
            library.openValidated("~/workspace/clip.mp4").use { opened ->
                assertEquals(10L, opened.size)
                assertArrayEquals(expectedBytes, ByteArray(10).also { opened.descriptor!!.readFully(it) })
            }
            assertTrue("Successful nested file operations must keep the session owner alive", owner.isActive)
            assertTrue(ownedCopy.exists())
            assertTrue(channels.isNotEmpty())
            channels.forEach { owned -> verify(exactly = 1) { owned.close() } }
            verify(exactly = 1) { file.close() }
            coVerify(exactly = 0) { connection.sftp() }
            library.close()
            assertFalse(ownedCopy.exists())
        } finally { library.close(); owner.cancel() }
    }

    @Test fun closingPlaybackReadInterruptsItsBlockedPrivateChannelImmediately() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        every { channel.close() } answers { released.countDown() }
        every { file.read(any(), any(), any(), any()) } answers {
            entered.countDown()
            check(released.await(2, TimeUnit.SECONDS))
            throw IOException("Channel closed")
        }
        val input = access.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", access.cacheIdentity())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val reading = pool.submit<IOException> { assertThrows(IOException::class.java) { input.read(ByteArray(10)) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            input.close()
            reading.get(2, TimeUnit.SECONDS)
            verify(exactly = 1) { channel.close() }
            verify(exactly = 1) { file.close() }
            coVerify(exactly = 0) { connection.invalidateSftp() }
        } finally { input.close(); released.countDown(); pool.shutdownNow() }
    }
}
