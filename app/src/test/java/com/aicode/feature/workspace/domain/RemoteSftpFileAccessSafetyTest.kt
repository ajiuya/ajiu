package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
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
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.io.FileAlreadyExistsException
import java.nio.file.FileSystemLoopException

class RemoteSftpFileAccessSafetyTest {
    private data class Entry(val type: FileMode.Type, val link: String = "", var bytes: ByteArray = byteArrayOf())

    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val cache = mockk<RemoteAgentConfigCache>()
    private val sftp = mockk<SFTPClient>(relaxed = true)
    private val entries = linkedMapOf<String, Entry>()
    private val removed = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val invalidated = mutableListOf<String>()
    private var beforeRemove: (String) -> Unit = {}
    private var readFailure: IOException? = null
    private lateinit var access: RemoteSftpFileAccess

    private val root = "/projects/one"
    private fun path(relative: String): String = "$root/$relative"
    private fun dir(relative: String) { entries[path(relative)] = Entry(FileMode.Type.DIRECTORY) }
    private fun file(relative: String, content: String = "source") {
        entries[path(relative)] = Entry(FileMode.Type.REGULAR, bytes = content.toByteArray())
    }
    private fun link(relative: String, target: String) { entries[path(relative)] = Entry(FileMode.Type.SYMLINK, target) }
    private fun attrs(entry: Entry): FileAttributes = mockk {
        every { type } returns entry.type
        every { size } answers { entry.bytes.size.toLong() }
    }

    @Before
    fun prepare() {
        entries["/"] = Entry(FileMode.Type.DIRECTORY)
        entries["/projects"] = Entry(FileMode.Type.DIRECTORY)
        entries[root] = Entry(FileMode.Type.DIRECTORY)
        every { connection.config } returns RemoteConnectionConfig(
            "test.invalid", username = "alice", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = root
        )
        every { connection.remoteHome } returns "/home/alice"
        every { connection.sftpOperationMutex } returns Mutex()
        every { workspace.currentPath() } returns root
        coEvery { connection.sftp() } returns sftp
        coEvery { connection.invalidateSftp() } returns Unit
        every { cache.invalidation(any()) } answers {
            val captured = firstArg<String>()
            val invalidation: () -> Unit = { invalidated += captured }
            invalidation
        }
        every { sftp.lstat(any()) } answers {
            val requested = firstArg<String>()
            attrs(entries[requested] ?: throw SFTPException(StatusCode.NO_SUCH_FILE, "Missing: $requested"))
        }
        every { sftp.readlink(any()) } answers {
            entries[firstArg<String>()]?.takeIf { it.type == FileMode.Type.SYMLINK }?.link
                ?: throw SFTPException(StatusCode.FAILURE, "Not a link")
        }
        every { sftp.statExistence(any()) } answers { entries[firstArg<String>()]?.let(::attrs) }
        every { sftp.ls(any()) } answers {
            val directory = firstArg<String>()
            check(entries[directory]?.type == FileMode.Type.DIRECTORY) { "Listed a non-directory: $directory" }
            entries.filterKeys { it != directory && it.substringBeforeLast('/', "").ifEmpty { "/" } == directory }
                .map { (childPath, child) ->
                    mockk<RemoteResourceInfo> {
                        every { name } returns childPath.substringAfterLast('/')
                        every { path } returns childPath
                        every { attributes } returns attrs(child)
                        every { isDirectory } returns (child.type == FileMode.Type.DIRECTORY)
                    }
                }
        }
        every { sftp.rm(any()) } answers {
            val removedPath = firstArg<String>()
            beforeRemove(removedPath)
            check(entries[removedPath]?.type != FileMode.Type.DIRECTORY) { "rm followed a directory link" }
            entries.remove(removedPath)
            removed += removedPath
            Unit
        }
        every { sftp.rmdir(any()) } answers {
            val removedPath = firstArg<String>()
            beforeRemove(removedPath)
            check(entries[removedPath]?.type == FileMode.Type.DIRECTORY)
            check(entries.keys.none { it.startsWith("$removedPath/") }) { "Removed a non-empty directory" }
            entries.remove(removedPath)
            removed += removedPath
            Unit
        }
        every { sftp.rename(any(), any()) } answers {
            val from = firstArg<String>()
            val to = secondArg<String>()
            val moving = entries.filterKeys { it == from || it.startsWith("$from/") }
            check(moving.isNotEmpty()) { "Missing rename source: $from" }
            moving.forEach { (old, _) -> entries.remove(old) }
            moving.forEach { (old, entry) -> entries[to + old.removePrefix(from)] = entry }
            renamed += from to to
            Unit
        }
        every { sftp.mkdirs(any()) } answers { entries[firstArg()] = Entry(FileMode.Type.DIRECTORY); Unit }
        every { sftp.symlink(any(), any()) } answers {
            entries[firstArg()] = Entry(FileMode.Type.SYMLINK, secondArg())
            Unit
        }
        every { sftp.open(any<String>()) } answers {
            val entry = entries[firstArg<String>()] ?: error("Missing file")
            check(entry.type == FileMode.Type.REGULAR) { "Opened a link instead of preserving it" }
            mockk<RemoteFile>(relaxed = true) {
                every { read(any(), any(), any(), any()) } answers {
                    readFailure?.let { throw it }
                    val offset = firstArg<Long>().toInt()
                    val count = minOf(arg<Int>(3), entry.bytes.size - offset)
                    if (count <= 0) -1 else {
                        entry.bytes.copyInto(secondArg(), thirdArg(), offset, offset + count)
                        count
                    }
                }
            }
        }
        every { sftp.open(any<String>(), any<Set<OpenMode>>()) } answers {
            val entry = Entry(FileMode.Type.REGULAR)
            entries[firstArg()] = entry
            mockk<RemoteFile>(relaxed = true) {
                every { write(any(), any(), any(), any()) } answers {
                    val offset = firstArg<Long>().toInt()
                    val count = arg<Int>(3)
                    entry.bytes = entry.bytes.copyOf(maxOf(entry.bytes.size, offset + count))
                    secondArg<ByteArray>().copyInto(entry.bytes, offset, thirdArg(), thirdArg<Int>() + count)
                    Unit
                }
            }
        }
        access = RemoteSftpFileAccess(connection, workspace, cache)
    }

    private fun tree() {
        dir("tree")
        dir("tree/sub")
        file("tree/sub/source")
        file("tree/keep", "keep")
    }

    private fun rejectBoth(source: String, target: String) {
        for (move in listOf(false, true)) {
            assertThrows(IOException::class.java) {
                if (move) access.move(source, target, overwrite = true) else access.copy(source, target, overwrite = true)
            }
        }
        assertTrue(removed.isEmpty())
        assertTrue(renamed.isEmpty())
        assertTrue(invalidated.isEmpty())
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.mkdirs(any()) }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun resolutionUsesLstatAndReadlinkBeforeParentSegmentsWithoutCanonicalize() {
        dir("target")
        dir("target/sub")
        link("relative", "target/sub")
        link("chain", "relative")
        assertEquals(path("target/probe"), access.resolvePath("~/workspace/chain/../probe"))
        verify(atLeast = 1) { sftp.lstat(path("chain")) }
        verify(atLeast = 1) { sftp.readlink(path("relative")) }
        verify(exactly = 0) { sftp.canonicalize(any()) }
    }

    @Test
    fun noFollowFinalLinkKeepsItsBodyWithTrailingSlashAndResolvesParentLink() {
        dir("target")
        link("parent", "/projects/one/target")
        link("target/leaf", "/missing/file")
        assertEquals(path("target/leaf"), access.resolvePath("parent/leaf/", false))
        verify(exactly = 0) { sftp.readlink(path("target/leaf")) }
        assertEquals("/missing/file", access.resolvePath("parent/leaf/"))
    }

    @Test
    fun missingParentsAndBothMissingStatusCodesRemainComparable() {
        every { sftp.lstat(path("absent")) } throws SFTPException(StatusCode.NO_SUCH_PATH, "Missing parent")
        every { sftp.lstat(path("absent/child")) } throws SFTPException(StatusCode.NO_SUCH_PATH, "Missing parent")
        assertEquals(path("absent/child"), access.resolvePath("absent/child"))
        assertEquals(path("new"), access.resolvePath("absent/../new"))
        assertEquals(path("leaf"), access.resolvePath("leaf", false))
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun permissionAndUnsupportedLstatErrorsAreNotSwallowedOrInvalidated() {
        for (status in listOf(StatusCode.PERMISSION_DENIED, StatusCode.OP_UNSUPPORTED)) {
            val failure = SFTPException(status, "Refused")
            every { sftp.lstat(path("denied")) } throws failure
            assertEquals(failure, assertThrows(SFTPException::class.java) { access.resolvePath("denied/child") })
        }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun readlinkPermissionFailureIsPropagatedAndDoesNotInvalidateGoodSftp() {
        link("denied", "/target")
        val failure = SFTPException(StatusCode.PERMISSION_DENIED, "Refused")
        every { sftp.readlink(path("denied")) } throws failure
        assertEquals(failure, assertThrows(SFTPException::class.java) { access.resolvePath("denied") })
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun fortyLinksAreAllowedAndFortyFirstRaisesLoopWithoutInvalidating() {
        for (index in 0..40) link("link$index", if (index == 40) "target" else "link${index + 1}")
        assertEquals(path("target"), access.resolvePath("link1"))
        assertThrows(FileSystemLoopException::class.java) { access.resolvePath("link0") }
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun loopDoesNotPreventNoFollowResolutionRenameOrDelete() {
        link("a", "b")
        link("b", "a")
        assertThrows(FileSystemLoopException::class.java) { access.resolvePath("a") }
        assertEquals(path("a"), access.resolvePath("a/", false))
        access.rename("a/", "renamed/")
        access.delete("renamed/")
        assertTrue(entries.containsKey(path("b")))
        assertFalse(entries.containsKey(path("renamed")))
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun businessReadLimitExceptionInsideGuardDoesNotDiscardSharedSftp() {
        val failure = FileReadTooLargeException(0)
        every { sftp.lstat(path("limited")) } throws failure
        assertEquals(failure, assertThrows(FileReadTooLargeException::class.java) { access.resolvePath("limited") })
        coVerify(exactly = 0) { connection.invalidateSftp() }
    }

    @Test
    fun danglingLinkCanBeRenamedAndDeletedWithTrailingSlashes() {
        link("dangling", "/missing")
        access.rename("dangling/", "renamed/")
        access.delete("renamed/")
        assertEquals(listOf(path("dangling") to path("renamed")), renamed)
        assertEquals(listOf(path("renamed")), removed)
        assertEquals(listOf(path("dangling/"), path("renamed/"), path("renamed/")), invalidated)
        verify(exactly = 0) { sftp.statExistence(any()) }
    }

    @Test
    fun renameRejectsDanglingDestinationWithoutRemovingEitherEntry() {
        file("source")
        link("target", "/missing")
        assertThrows(FileAlreadyExistsException::class.java) { access.rename("source", "target/") }
        assertEquals("source", entries[path("source")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals("/missing", entries[path("target")]!!.link)
        assertTrue(removed.isEmpty())
        assertTrue(renamed.isEmpty())
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun recursiveDeleteRemovesLinkChildrenWithoutFollowingExternalSubtrees() {
        dir("remove")
        dir("external")
        file("external/keep", "keep")
        link("remove/external", path("external"))
        link("remove/dangling", "/missing")
        access.deleteRecursively("remove/")
        assertEquals(setOf(path("remove/external"), path("remove/dangling"), path("remove")), removed.toSet())
        assertEquals("keep", entries[path("external/keep")]!!.bytes.toString(Charsets.UTF_8))
        verify(exactly = 0) { sftp.ls(path("external")) }
        verify(exactly = 0) { sftp.readlink(path("remove/external")) }
        assertEquals(listOf(path("remove/")), invalidated)
    }

    @Test
    fun deletingTopLevelDirectoryLinkNeverListsOrRemovesItsTarget() {
        dir("target")
        file("target/keep", "keep")
        link("link", path("target"))
        access.deleteRecursively("link/")
        assertEquals(listOf(path("link")), removed)
        assertTrue(entries.containsKey(path("target/keep")))
        verify(exactly = 0) { sftp.ls(any()) }
    }

    @Test
    fun recursivePartialDeletionStillInvalidatesTheCapturedSource() {
        dir("remove")
        file("remove/first")
        file("remove/second")
        beforeRemove = { if (it == path("remove/second")) throw SFTPException(StatusCode.PERMISSION_DENIED, "Refused") }
        assertThrows(SFTPException::class.java) { access.deleteRecursively("remove") }
        assertEquals(listOf(path("remove/first")), removed)
        assertEquals(listOf(path("remove")), invalidated)
        assertTrue(entries.containsKey(path("remove/second")))
    }

    @Test
    fun copyAndMoveSameItemAreRejectedBeforeAnyOverwrite() {
        file("source")
        rejectBoth("source", "./source")
        assertTrue(entries.containsKey(path("source")))
    }

    @Test
    fun copyAndMoveToAncestorAreRejectedBeforeDeletingSource() {
        tree()
        rejectBoth("tree/sub", "tree")
        assertTrue(entries.containsKey(path("tree/sub/source")))
        assertTrue(entries.containsKey(path("tree/keep")))
    }

    @Test
    fun copyAndMoveToDescendantThroughParentAliasAreRejectedBeforeDeletingTarget() {
        tree()
        link("alias", path("tree"))
        rejectBoth("tree", "alias/sub")
        assertTrue(entries.containsKey(path("tree/sub/source")))
    }

    @Test
    fun copyAndMoveToFinalSourceAliasAreRejectedBeforeDeletingLink() {
        tree()
        link("alias", path("tree"))
        rejectBoth("tree", "alias/")
        assertEquals(path("tree"), entries[path("alias")]!!.link)
    }

    @Test
    fun copyAndMoveToFinalAncestorAliasAreRejectedBeforeDeletingLink() {
        tree()
        link("alias", path("tree"))
        rejectBoth("tree/sub", "alias")
        assertTrue(entries.containsKey(path("tree/sub/source")))
    }

    @Test
    fun sourceAliasAndParentSegmentsCannotBypassOverlapChecks() {
        tree()
        link("alias", "tree/sub")
        rejectBoth("alias/..", "tree/sub")
        assertTrue(entries.containsKey(path("tree/sub/source")))
    }

    @Test
    fun targetParentSegmentsResolveAfterSymlinkBeforeOverlapCheck() {
        tree()
        link("alias", "tree/sub")
        rejectBoth("tree/sub", "alias/../")
        assertTrue(entries.containsKey(path("tree/sub/source")))
    }

    @Test
    fun copyingSourceLinkReadsTargetButDoesNotRemoveSourceOrTarget() {
        file("real", "keep")
        link("link", path("real"))
        access.copy("link", "copied")
        assertEquals("keep", entries[path("copied")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(path("real"), entries[path("link")]!!.link)
        assertEquals("keep", entries[path("real")]!!.bytes.toString(Charsets.UTF_8))
        assertTrue(removed.isEmpty())
        assertEquals(listOf(path("copied")), invalidated)
    }

    @Test
    fun movingSourceLinkMovesOnlyItsBodyAndKeepsTargetContents() {
        dir("real")
        file("real/keep", "keep")
        link("link", path("real"))
        access.move("link/", "moved")
        assertFalse(entries.containsKey(path("link")))
        assertEquals(path("real"), entries[path("moved")]!!.link)
        assertEquals("keep", entries[path("real/keep")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(listOf(path("link") to path("moved")), renamed)
        assertEquals(listOf(path("link/"), path("moved")), invalidated)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun danglingSourceCanBeMovedWithoutResolvingIntoItsMissingTarget() {
        link("link", "/missing")
        access.move("link/", "moved")
        assertEquals("/missing", entries[path("moved")]!!.link)
        assertFalse(entries.containsKey("/missing"))
        assertTrue(removed.isEmpty())
    }

    @Test
    fun copyAndMoveOverwriteTargetLinkBodyWithoutDeletingItsTarget() {
        file("source")
        dir("external")
        file("external/keep", "keep")
        link("copy-target", path("external"))
        link("move-target", path("external"))
        access.copy("source", "copy-target/", overwrite = true)
        access.move("source", "move-target/", overwrite = true)
        assertEquals("source", entries[path("copy-target")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals("source", entries[path("move-target")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals("keep", entries[path("external/keep")]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(listOf(path("copy-target"), path("move-target")), removed)
        verify(exactly = 0) { sftp.ls(path("external")) }
    }

    @Test
    fun copyAndMoveRejectDanglingTargetWithoutOverwriteAndCanReplaceItWithOverwrite() {
        file("source")
        link("destination", "/missing")
        assertThrows(FileAlreadyExistsException::class.java) { access.copy("source", "destination") }
        assertThrows(FileAlreadyExistsException::class.java) { access.move("source", "destination") }
        assertTrue(removed.isEmpty())
        access.copy("source", "destination", overwrite = true)
        assertEquals("source", entries[path("destination")]!!.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun recursiveCopyPreservesChildLinksWithoutFollowingThemOrExpandingCycles() {
        dir("source")
        dir("external")
        file("external/keep", "keep")
        link("source/external", path("external"))
        link("source/dangling", "/missing")
        link("source/back", path("source"))
        access.copy("source", "copy")
        assertEquals(path("external"), entries[path("copy/external")]!!.link)
        assertEquals("/missing", entries[path("copy/dangling")]!!.link)
        assertEquals(path("source"), entries[path("copy/back")]!!.link)
        verify(exactly = 0) { sftp.ls(path("external")) }
        verify(exactly = 0) { sftp.open(path("source/dangling")) }
        assertEquals(listOf(path("copy")), invalidated)
    }

    @Test
    fun partialCopyFailureAndOverwriteDeletionKeepTargetInvalidationTiming() {
        file("source")
        file("destination", "old")
        readFailure = IOException("Read failed")
        assertThrows(IOException::class.java) { access.copy("source", "destination", overwrite = true) }
        assertEquals(listOf(path("destination")), removed)
        assertEquals(listOf(path("destination"), path("destination")), invalidated)
        assertEquals("source", entries[path("source")]!!.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun failedMoveAfterTargetDeletionInvalidatesOnlyDeletedTarget() {
        file("source")
        file("destination", "old")
        every { sftp.rename(path("source"), path("destination")) } throws SFTPException(StatusCode.PERMISSION_DENIED, "Refused")
        assertThrows(SFTPException::class.java) { access.move("source", "destination", overwrite = true) }
        assertEquals(listOf(path("destination")), removed)
        assertEquals(listOf(path("destination")), invalidated)
        assertTrue(entries.containsKey(path("source")))
    }

    @Test
    fun writeBytesAndWriteStreamStillInvalidateOnlyAfterSuccessfulReplacement() {
        access.writeBytes("first", byteArrayOf(1))
        access.writeStream("second", ByteArrayInputStream(byteArrayOf(2)))
        assertEquals(listOf(path("first"), path("second")), invalidated)
        assertEquals(listOf(path("first.aicode-part") to path("first"), path("second.aicode-part") to path("second")), renamed)
    }
}
