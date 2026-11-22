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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PathComponents
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceFilter
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CancellationException

class RemoteSftpTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val channel = mockk<SFTPClient>(relaxed = true)
    private var identity = RemoteConnectionIdentity(mockk(), "/home/test")
    private lateinit var root: File
    private lateinit var access: RemoteSftpFileAccess
    private lateinit var execution: WorkspaceExecutionContext
    private val readSizes = mutableListOf<Int>()
    private var afterRead: () -> Unit = {}
    private var atomicSupported = true
    private var zeroRead = false

    @Before
    fun prepare() {
        root = temporary.newFolder("remote")
        every { connection.config } returns RemoteConnectionConfig("test.invalid", username = "test", auth = RemoteAuth.Password("test-only"), remoteWorkspacePath = "/server")
        every { connection.remoteHome } returns "/home/test"
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("Remote identity changed")
        }
        every { workspace.currentPath() } returns "/server"
        coEvery { connection.openReadChannel(any()) } answers {
            connection.checkIdentity(firstArg())
            channel
        }
        every { channel.lstat(any()) } answers { attributes(firstArg()) ?: throw SFTPException(StatusCode.NO_SUCH_FILE, "missing") }
        every { channel.statExistence(any()) } answers { attributes(firstArg()) }
        every { channel.mkdir(any()) } answers { Files.createDirectory(local(firstArg()).toPath()); Unit }
        every { channel.rm(any()) } answers { Files.delete(local(firstArg()).toPath()); Unit }
        every { channel.rmdir(any()) } answers { Files.delete(local(firstArg()).toPath()); Unit }
        every { channel.readlink(any()) } answers { Files.readSymbolicLink(local(firstArg()).toPath()).toString() }
        every { channel.symlink(any(), any()) } answers {
            Files.createSymbolicLink(local(firstArg()).toPath(), File(secondArg<String>()).toPath()); Unit
        }
        every { channel.ls(any(), any<RemoteResourceFilter>()) } answers {
            val path = firstArg<String>()
            val filter = secondArg<RemoteResourceFilter>()
            local(path).listFiles()!!.map { file ->
                RemoteResourceInfo(PathComponents(path, file.name, "/"), attributes(path.trimEnd('/') + "/" + file.name))
            }.filter { filter.accept(it) }
        }
        every { channel.open(any<String>()) } answers { handle(firstArg(), setOf(OpenMode.READ)) }
        every { channel.open(any<String>(), any<Set<OpenMode>>()) } answers { handle(firstArg(), secondArg()) }
        every { channel.rename(any(), any()) } answers {
            Files.move(local(firstArg()).toPath(), local(secondArg()).toPath()); Unit
        }
        every { channel.rename(any(), any(), any<Set<RenameFlags>>()) } answers {
            assertEquals(setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC), thirdArg<Set<RenameFlags>>())
            if (!atomicSupported) throw SFTPException(StatusCode.OP_UNSUPPORTED, "atomic rename unavailable")
            Files.move(local(firstArg()).toPath(), local(secondArg()).toPath(), ATOMIC_MOVE, REPLACE_EXISTING); Unit
        }
        access = RemoteSftpFileAccess(connection, workspace, mockk<RemoteAgentConfigCache>(relaxed = true) {
            every { captureProfile() } returns RemoteAgentConfigProfile("fixture", 0)
        })
        val source = access.captureExecutionSource()
        execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) {}
    }

    private fun local(path: String): File {
        require(path == "/" || path == "/server" || path.startsWith("/server/"))
        return if (path == "/" || path == "/server") root else File(root, path.removePrefix("/server/"))
    }

    private fun attributes(path: String): FileAttributes? {
        val file = local(path)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        val type = when {
            Files.isSymbolicLink(file.toPath()) -> FileMode.Type.SYMLINK
            file.isDirectory -> FileMode.Type.DIRECTORY
            else -> FileMode.Type.REGULAR
        }
        return FileAttributes.Builder().withType(type).withSize(file.length()).withAtimeMtime(0, file.lastModified() / 1000).build()
    }

    private fun handle(path: String, modes: Set<OpenMode>): RemoteFile {
        val local = local(path)
        if (OpenMode.CREAT in modes) {
            if (OpenMode.EXCL in modes) Files.createFile(local.toPath()) else local.createNewFile()
        }
        return mockk<RemoteFile>(relaxed = true) {
            every { read(any(), any(), any(), any()) } answers {
                val count = if (zeroRead) 0 else RandomAccessFile(local, "r").use { input ->
                    readSizes += arg<Int>(3)
                    input.seek(firstArg())
                    input.read(secondArg(), thirdArg(), arg(3))
                }
                afterRead()
                count
            }
            every { write(any(), any(), any(), any()) } answers {
                RandomAccessFile(local, "rw").use { output ->
                    output.seek(firstArg())
                    output.write(secondArg<ByteArray>(), thirdArg(), arg(3))
                }
            }
            every { length() } answers { local.length() }
        }
    }

    private fun host(file: File): TransferBackend = HostTransferBackend({ relative, _ -> if (relative.isEmpty()) file else File(file, relative) }, {})

    @Test
    fun remoteTransferHashesBeyondEightMiBWithBoundedPrivateReads() = runBlocking {
        val file = File(root, "large")
        file.outputStream().use { output -> repeat(300) { output.write(ByteArray(TRANSFER_BUFFER_BYTES) { (it % 251).toByte() }) } }
        withContext(execution) {
            val backend = access.transferBackend("~/workspace/large") {}
            val version = transferVersion(backend) {}!!
            assertEquals(file.length(), version.nodes.single().bytes)
            assertTrue(readSizes.all { it <= TRANSFER_BUFFER_BYTES })
        }
        coVerify(exactly = 0) { connection.sftp() }
        verify(atLeast = 1) { channel.close() }
    }

    @Test
    fun replacementRequiresAtomicFlagsAndNeverDeletesTheOldTarget() = runBlocking {
        val source = temporary.newFile().apply { writeText("new") }
        val target = File(root, "target").apply { writeText("keep") }
        withContext(execution) {
            val destination = access.transferBackend("~/workspace/target") {}
            val version = transferVersion(host(source)) {}!!
            val previous = transferVersion(destination) {}
            atomicSupported = false
            assertThrows(SFTPException::class.java) {
                TransferOperation(host(source), destination, "source", "target", true, {}, {}).run(version, previous)
            }
        }
        assertEquals("keep", target.readText())
        assertEquals("new", source.readText())
        verify(exactly = 0) { channel.rm("/server/target") }
        verify(exactly = 0) { channel.rename(any(), "/server/target") }
        assertNoStages()
    }

    @Test
    fun supportedAtomicReplacementAndExclusiveStageKeepDataComplete() = runBlocking {
        val source = temporary.newFile().apply { writeText("new") }
        val target = File(root, "target").apply { writeText("old") }
        withContext(execution) {
            val destination = access.transferBackend("~/workspace/target") {}
            val version = transferVersion(host(source)) {}!!
            val previous = transferVersion(destination) {}
            assertTrue(TransferOperation(host(source), destination, "source", "target", false, {}, {}).run(version, previous) is TransferResult.Completed)
        }
        assertEquals("new", target.readText())
        verify(exactly = 1) { channel.rename(any(), "/server/target", setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC)) }
        verify(atLeast = 1) { channel.open(any<String>(), match { OpenMode.CREAT in it && OpenMode.EXCL in it && OpenMode.TRUNC !in it }) }
        verify(exactly = 0) { channel.rm("/server/target") }
        assertNoStages()
    }

    @Test
    fun remoteZeroProgressIsRejectedRatherThanSpinningForever(): Unit = runBlocking {
        File(root, "file").writeText("content")
        withContext(execution) {
            val reader = access.transferBackend("~/workspace/file") {}
            zeroRead = true
            assertThrows(IOException::class.java) { transferVersion(reader) {} }
        }
        Unit
    }

    @Test
    fun identityChangeDuringAHashNeverSwitchesToTheNewServer() = runBlocking {
        File(root, "file").writeText("content")
        val captured = identity
        withContext(execution) {
            val reader = access.transferBackend("~/workspace/file") {}
            afterRead = { identity = RemoteConnectionIdentity(mockk(), "/home/test") }
            assertThrows(CancellationException::class.java) { transferVersion(reader) {} }
        }
        coVerify(exactly = 0) { connection.openReadChannel(match { it !== captured }) }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test
    fun remoteDirectoryChildrenKeepLinkTextWithoutFollowingIt() = runBlocking {
        val source = temporary.newFolder()
        File(source, "file").writeText("content")
        Files.createSymbolicLink(File(source, "link").toPath(), File("../outside").toPath())
        withContext(execution) {
            val destination = access.transferBackend("~/workspace/tree") {}
            val version = transferVersion(host(source)) {}!!
            assertTrue(TransferOperation(host(source), destination, "source", "target", false, {}, {}).run(version, null) is TransferResult.Completed)
        }
        assertEquals("../outside", Files.readSymbolicLink(File(root, "tree/link").toPath()).toString())
        assertEquals("content", File(root, "tree/file").readText())
        verify(exactly = 1) { channel.symlink(match { it.endsWith("/payload/link") }, "../outside") }
        assertFalse(File(root, "outside").exists())
        assertNoStages()
    }

    private fun assertNoStages() {
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
    }
}
