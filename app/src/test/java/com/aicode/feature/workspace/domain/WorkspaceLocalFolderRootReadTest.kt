package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WorkspaceLocalFolderRootReadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val mapper = mockk<WorkspacePathMapper>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val owner = Job()
    private val virtualRoot = localRootPath("grant", "epoch")
    private lateinit var directory: File
    private lateinit var snapshot: LocalFolderSnapshot
    private lateinit var execution: WorkspaceExecutionContext
    private var authorizationCurrent = true
    private var sourceCurrent = true
    private val facade = WorkspaceRootRead(mapper, remote)

    @Before
    fun prepare() {
        directory = temporary.newFolder("authorized")
        File(directory, "site/images").mkdirs()
        File(directory, "site/entry.html").writeText("<img src=\"images/a.png\">")
        File(directory, "site/style.css").writeText("body{}")
        File(directory, "site/images/a.png").writeBytes(byteArrayOf(1, 2, -1))
        File(directory, "private.css").writeText("private")
        val grant = LocalFolderGrant("grant", "epoch", "authorized", virtualRoot, true)
        snapshot = LocalFolderSnapshot(7, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {
            if (!authorizationCurrent) throw CancellationException("Authorization changed")
        }
        execution = frozen(ExecutionMode.REMOTE_SSH)
    }

    private fun frozen(mode: ExecutionMode): WorkspaceExecutionContext =
        WorkspaceExecutionContext(mode, "base", remote, null, null, snapshot) {
            if (!sourceCurrent) throw CancellationException("Source changed")
        }

    private fun path(relative: String) = "$virtualRoot/$relative"
    private fun capture(root: String = path("site")) = facade.capture(root, execution, owner)
    private fun link(name: String, target: String) {
        val file = File(directory, name)
        file.parentFile.mkdirs()
        Files.createSymbolicLink(file.toPath(), File(target).toPath())
    }

    @After
    fun finish() {
        owner.cancel()
    }

    @Test
    fun remoteExecutionReadsAuthorizedHostHtmlImagesAndRelatedCssWithoutSftpOrProot() {
        val read = capture()
        assertEquals(File(directory, "site/entry.html").canonicalFile, read.localFile(path("site/entry.html")))
        assertEquals(File(directory, "site/entry.html").length(), read.size(path("site/entry.html")))
        assertEquals("<img src=\"images/a.png\">", read.openRead(path("site/entry.html"), 100).bufferedReader().use { it.readText() })
        assertEquals("body{}", read.openRead(path("site/style.css"), 100).bufferedReader().use { it.readText() })
        assertArrayEquals(byteArrayOf(1, 2, -1), read.openRead(path("site/images/a.png"), 3).use { it.readBytes() })
        verify(exactly = 0) { mapper.resolveFile(any(), any()) }
        verify(exactly = 0) { remote.captureReadRoot(any()) }
        verify(exactly = 0) { remote.openRootRead(any(), any(), any()) }
    }

    @Test
    fun localExecutionAlsoUsesTheGrantProviderInsteadOfTheProotMapper() {
        val read = facade.capture(path("site"), frozen(ExecutionMode.LOCAL_PROOT), owner)
        assertEquals("body{}", read.openRead(path("site/style.css"), 100).bufferedReader().use { it.readText() })
        verify(exactly = 0) { mapper.resolveFile(any(), any()) }
    }

    @Test
    fun grantRootMayBeAReadRootWithoutBecomingAMutableTransferRoot() {
        val read = capture(virtualRoot)
        assertEquals("private", read.openRead(path("private.css"), 7).bufferedReader().use { it.readText() })
        assertThrows(IOException::class.java) { read.openRead(virtualRoot, 100) }
    }

    @Test
    fun relatedFilesRemainBoundedByThePreviewDirectoryRatherThanTheWholeGrant() {
        link("site/sibling.css", "../private.css")
        val read = capture()
        for (target in listOf(path("private.css"), path("site/../private.css"), path("site/sibling.css"))) {
            assertThrows(IOException::class.java) { read.size(target) }
            assertThrows(IOException::class.java) { read.localFile(target) }
            assertThrows(IOException::class.java) { read.openRead(target, 100) }
        }
    }

    @Test
    fun symlinkAndParentEscapesIncludingLeaveThenReenterAreRejected() {
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("secret")
        link("site/outside", outside.absolutePath)
        link("site/leave-back", "../../authorized/private.css")
        val read = capture(virtualRoot)
        for (target in listOf(path("../outside/secret"), path("site/outside/secret"), path("site/leave-back"))) {
            assertThrows(IOException::class.java) { read.openRead(target, 100) }
        }
        assertThrows(IOException::class.java) { read.openRead(File(outside, "secret").absolutePath, 100) }
    }

    @Test
    fun previewRootReplacementWithAnotherGrantDirectoryInvalidatesMetadataAndStreams() {
        val read = capture()
        val input = read.openRead(path("site/style.css"), 100)
        assertEquals('b'.code, input.read())
        assertTrue(File(directory, "site").renameTo(File(directory, "old-site")))
        File(directory, "replacement").mkdirs()
        link("site", "replacement")
        assertThrows(CancellationException::class.java) { read.checkCurrent() }
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun switchingAFileLinkToAnotherAllowedTargetRejectsAndClosesTheOpenStream() {
        link("site/link.css", "style.css")
        val read = capture()
        val input = read.openRead(path("site/link.css"), 100)
        Files.delete(File(directory, "site/link.css").toPath())
        link("site/link.css", "entry.html")
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun revokedAuthorizationRejectsMetadataNewReadsAndAlreadyOpenedStreams() {
        val read = capture()
        val input = read.openRead(path("site/style.css"), 100)
        authorizationCurrent = false
        assertThrows(CancellationException::class.java) { read.checkCurrent() }
        assertThrows(CancellationException::class.java) { read.size(path("site/style.css")) }
        assertThrows(CancellationException::class.java) { read.openRead(path("site/style.css"), 100) }
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun replacementEpochCannotBeSelectedByAnOldCaptureOrAnOperationReader() {
        val read = capture(virtualRoot)
        val operation = Job(owner)
        try {
            val operationRead = read.forOperation(operation)
            val replacementPath = localRootPath("grant", "replacement") + "/site/style.css"
            assertThrows(CancellationException::class.java) { operationRead.openRead(replacementPath, 100) }
            authorizationCurrent = false
            assertThrows(CancellationException::class.java) { operationRead.openRead(path("site/style.css"), 100) }
        } finally { operation.cancel() }
    }

    @Test
    fun operationReaderRetainsTheCaptureOwnerAndCannotReviveItWithAnotherJob() {
        val read = capture()
        val operation = Job()
        try {
            val operationRead = read.forOperation(operation)
            owner.cancel()
            assertThrows(CancellationException::class.java) { operationRead.openRead(path("site/style.css"), 100) }
        } finally { operation.cancel() }
    }

    @Test
    fun ownerAndExecutionCancellationRejectAndCloseReturnedStreams() {
        val read = capture()
        val sourceInput = read.openRead(path("site/style.css"), 100)
        sourceCurrent = false
        assertThrows(CancellationException::class.java) { sourceInput.read() }
        assertThrows(IOException::class.java) { sourceInput.read() }
        sourceCurrent = true
        val ownedInput = read.openRead(path("site/style.css"), 100)
        owner.cancel()
        assertTrue(runCatching { ownedInput.read() }.isFailure)
        assertThrows(CancellationException::class.java) { read.size(path("site/style.css")) }
    }

    @Test
    fun nonRegularFilesAndMetadataOversizeAreRejectedAndGrowingStreamsStayBounded() {
        val read = capture()
        assertThrows(IOException::class.java) { read.openRead(path("site/images"), 100) }
        assertThrows(IOException::class.java) { read.openRead(path("site/missing"), 100) }
        assertThrows(IOException::class.java) { read.openRead(path("site/style.css"), 5) }
        val input = read.openRead(path("site/style.css"), 6)
        File(directory, "site/style.css").appendText("extra")
        val buffer = ByteArray(6)
        assertEquals(6, input.read(buffer))
        assertThrows(IOException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun capturedHostStreamChecksFileSourcePerChunkWithoutRepeatingAuthorizationProbes() {
        var authorizationChecks = 0
        var fileSourceChecks = 0
        val roots = LocalFolderSnapshot(8, snapshot.roots, snapshot.records,
            checkAuthorization = { authorizationChecks++ }, checkFileSource = { fileSourceChecks++ }) {}
        val frozen = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "base", remote, null, null, roots) {}
        val read = facade.capture(path("site"), frozen, owner)
        val input = read.openRead(path("site/style.css"), 100)
        val openedChecks = authorizationChecks
        repeat(4) { assertTrue(input.read() >= 0) }
        assertEquals(openedChecks, authorizationChecks)
        assertTrue(fileSourceChecks > 4)
        input.close()
        assertTrue(authorizationChecks > openedChecks)
    }

    @Test
    fun malformedReservedPathsAndUnknownEpochNeverFallThroughToSftp() {
        for (root in listOf("/mnt/.aicode-local-fs", "/mnt/.aicode-local-fs/grant", localRootPath("grant", "old"))) {
            assertTrue(runCatching { capture(root) }.isFailure)
        }
        verify(exactly = 0) { remote.captureReadRoot(any()) }
        verify(exactly = 0) { mapper.resolveFile(any(), any()) }
    }

    @Test
    fun ordinaryRemoteRootsRetainRemoteReadRootProofAndIgnoreUnrelatedGrantChanges() {
        val root = mockk<RemoteSftpFileAccess.ReadRoot>()
        every { remote.captureReadRoot("~/workspace/site") } returns root
        every { remote.rootReadSize("~/workspace/site/file", root) } returns 3L
        every { remote.openRootRead("~/workspace/site/file", root, 3) } returns ByteArrayInputStream(byteArrayOf(4, 5, 6))
        val read = capture("~/workspace/site")
        authorizationCurrent = false
        assertNull(read.localFile("~/workspace/site/file"))
        assertEquals(3L, read.size("~/workspace/site/file"))
        assertArrayEquals(byteArrayOf(4, 5, 6), read.openRead("~/workspace/site/file", 3).use { it.readBytes() })
        verify(exactly = 1) { remote.captureReadRoot("~/workspace/site") }
        verify(exactly = 1) { remote.openRootRead("~/workspace/site/file", root, 3) }
        verify(exactly = 0) { mapper.resolveFile(any(), any()) }
    }
}
