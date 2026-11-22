package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

class PlayerLocalFolderBindingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val owner = Job()
    private val virtualRoot = localRootPath("grant", "epoch")
    private val project = ProjectMemoryIdentity("remote_ssh", "host.example", 22, "alice", "/projects/one")
    private val capture = ProjectMemoryCapture(project, "generation") {}
    private lateinit var directory: File
    private lateinit var snapshot: LocalFolderSnapshot
    private lateinit var binding: PlayerWorkspaceBinding
    private var authorizationCurrent = true
    private var sourceCurrent = true
    private var workspaceCurrent = true

    @Before
    fun prepare() {
        directory = temporary.newFolder("host")
        File(directory, "media").mkdirs()
        File(directory, "media/clip.mp4").writeText("0123456789")
        File(directory, "media/lines.txt").writeText("one\ntwo\n")
        val grant = LocalFolderGrant("grant", "epoch", "host", virtualRoot, true)
        snapshot = LocalFolderSnapshot(11, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {
            if (!authorizationCurrent) throw CancellationException("Authorization changed")
        }
        every { remote.toDisplayPath(any<String>()) } answers { firstArg() }
        binding = binding(snapshot)
    }

    private fun binding(roots: LocalFolderSnapshot, backend: FileAccessProvider = remote): PlayerWorkspaceBinding {
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote-base", backend, null, null, roots) {
            if (!sourceCurrent) throw CancellationException("Source changed")
        }
        return PlayerWorkspaceBinding(execution, capture, owner) {
            if (!workspaceCurrent) throw CancellationException("Workspace changed")
        }
    }

    private fun path(relative: String) = "$virtualRoot/$relative"
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
    fun remoteWorkspaceHostMediaUsesCapturedProviderAndBaseExpectedIdentity() {
        val access = binding.access
        assertEquals("remote-base", access.cacheIdentity())
        assertNotEquals("remote-base", binding.identity)
        assertEquals(path("media/clip.mp4"), access.toDisplayPath(path("media/clip.mp4")))
        assertEquals(path("media"), access.parentPath(path("media/clip.mp4")))
        assertTrue(access.isDirectory(path("media")))
        assertTrue(access.exists(path("media/clip.mp4")))
        assertTrue(access.isFile(path("media/clip.mp4")))
        assertEquals(10L, access.fileSize(path("media/clip.mp4")))
        assertTrue(access.lastModified(path("media/clip.mp4")) > 0)
        assertTrue(access.permissions(path("media/clip.mp4")).startsWith("r"))
        assertTrue(access.fingerprint(path("media/clip.mp4"))!!.startsWith("sha256:"))
        assertTrue(access.listFiles(path("media")).all { it.localFile == null })
        assertTrue(access.listFilesRecursive(path("media"), 2).contains("clip.mp4"))
        assertEquals(File(directory, "media/clip.mp4").canonicalPath, access.resolvePath(path("media/clip.mp4")))
        assertEquals(File(directory, "media/clip.mp4"), access.copyToLocal(path("media/clip.mp4")))
        assertEquals("0123456789", access.readFile(path("media/clip.mp4")))
        assertEquals(listOf("one", "two"), access.readLines(path("media/lines.txt")).toList())
        assertArrayEquals("0123456789".toByteArray(), access.readBytes(path("media/clip.mp4"), 10))
        assertEquals('0'.code, access.openRead(path("media/clip.mp4"), "remote-base").use { it.read() })
        assertNull(access.agentConfigSnapshot(virtualRoot, AgentConfigKind.SKILLS))
        access.invalidateAgentConfig(path("media/clip.mp4"))
        verify(exactly = 0) { remote.openRead(any(), any()) }
        verify(exactly = 0) { remote.listFiles(any()) }
        verify(exactly = 0) { remote.toDisplayPath(any<String>()) }
    }

    @Test
    fun hostProviderRoutesWriteAndTwoPathMethodsWithoutLeakingVirtualPathsToRemote() {
        val access = binding.access
        access.mkdirs(path("new"))
        access.writeFile(path("new/text"), "text", false)
        access.writeBytes(path("new/bytes"), byteArrayOf(1, 2), false)
        assertEquals(3L, access.writeStream(path("new/stream"), ByteArrayInputStream(byteArrayOf(3, 4, 5)), false))
        access.copy(path("new/text"), path("new/copied"), false)
        access.move(path("new/copied"), path("new/moved"), false)
        access.rename(path("new/moved"), path("new/renamed"))
        assertEquals("text", File(directory, "new/renamed").readText())
        access.delete(path("new/renamed"))
        access.deleteRecursively(path("new"))
        assertFalse(File(directory, "new").exists())
        verify(exactly = 0) { remote.writeFile(any(), any(), any(), any()) }
        verify(exactly = 0) { remote.writeStream(any(), any(), any()) }
        verify(exactly = 0) { remote.copy(any(), any(), any()) }
        verify(exactly = 0) { remote.rename(any(), any()) }
    }

    @Test
    fun playerLibraryListsAndCopiesAuthorizedHostMediaWhileRemoteExecutionRemainsCaptured() {
        val ownedCopy = File(temporary.root, "owned.copy")
        PlayerLibrary(binding, path("media"), "/T/", PlayerResources(), createCopy = { ownedCopy }).use { library ->
            val items = library.json().getValue("items").jsonArray
            assertEquals(listOf("clip.mp4"), items.map { it.jsonObject.getValue("name").jsonPrimitive.content })
            library.openValidated(path("media/clip.mp4")).use { opened ->
                assertEquals(10L, opened.size)
                assertEquals("0123456789", ByteArray(10).also { opened.descriptor!!.readFully(it) }.toString(Charsets.UTF_8))
            }
            assertTrue(owner.isActive)
        }
        assertFalse(ownedCopy.exists())
        assertTrue(File(directory, "media/clip.mp4").exists())
        verify(exactly = 0) { remote.checkPlaybackPath(any(), any(), any()) }
        verify(exactly = 0) { remote.openPlaybackRead(any(), any(), any()) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun hostPlaybackProofRejectsLinksOutsideTheSessionDirectoryEvenInsideTheSameGrant() {
        File(directory, "private.mp4").writeText("private")
        link("media/escape.mp4", "../private.mp4")
        assertThrows(IOException::class.java) { binding.checkPlaybackPath(path("media/escape.mp4"), path("media")) }
        assertThrows(IOException::class.java) { binding.openPlaybackRead(path("media/escape.mp4"), path("media")) }
        PlayerLibrary(binding, path("media"), "/T/", PlayerResources(), createCopy = { error("No escaping media copy") }).use { library ->
            val names = library.json().getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("clip.mp4"), names)
            assertThrows(IOException::class.java) { library.openValidated(path("media/escape.mp4")) }
        }
    }

    @Test
    fun hostTraversalOutsideGrantAndMalformedReservedPathsNeverReachSftp() {
        val outside = temporary.newFolder("outside")
        File(outside, "clip.mp4").writeText("private")
        link("media/outside", outside.absolutePath)
        for (target in listOf(path("../outside/clip.mp4"), path("media/outside/clip.mp4"), "/mnt/.aicode-local-fs", "/mnt/.aicode-local-fs/grant")) {
            assertTrue(runCatching { binding.access.openRead(target, "remote-base") }.isFailure)
        }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun revokedAndRegrantedEpochCannotReviveAnOldBindingOrItsPlaybackCache() {
        val ownedCopy = File(temporary.root, "owned.copy")
        PlayerLibrary(binding, path("media"), "/T/", PlayerResources(), createCopy = { ownedCopy }).use { library ->
            library.openValidated(path("media/clip.mp4")).close()
            authorizationCurrent = false
            val replacementRoot = localRootPath("grant", "replacement")
            val replacementGrant = LocalFolderGrant("grant", "replacement", "host", replacementRoot, true)
            val replacement = LocalFolderSnapshot(12, listOf(replacementGrant), listOf(CapturedLocalFolderRoot(replacementGrant, directory))) {}
            val fresh = binding(replacement)
            assertNotEquals(binding.identity, fresh.identity)
            assertEquals(binding.namespace, fresh.namespace)
            assertThrows(CancellationException::class.java) { library.openValidated(path("media/clip.mp4")) }
            assertThrows(CancellationException::class.java) { binding.access.openRead("$replacementRoot/media/clip.mp4", "remote-base") }
            assertEquals('0'.code, fresh.access.openRead("$replacementRoot/media/clip.mp4", "remote-base").use { it.read() })
        }
        assertFalse(ownedCopy.exists())
    }

    @Test
    fun hostLongStreamsAndLazyLinesRejectTheCapturedAuthorizationChangeAndClose() {
        val input = binding.access.openRead(path("media/clip.mp4"), "remote-base")
        val lines = binding.access.readLines(path("media/lines.txt"))
        assertEquals('0'.code, input.read())
        authorizationCurrent = false
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
        assertThrows(CancellationException::class.java) { lines.toList() }
        assertThrows(CancellationException::class.java) { binding.checkCurrent() }
    }

    @Test
    fun hostPlaybackStreamRejectsChangedRootAndLeafLinksBeforeReturningAnotherByte() {
        link("media/alias.mp4", "clip.mp4")
        val input = binding.openPlaybackRead(path("media/alias.mp4"), path("media"))
        File(directory, "media/other.mp4").writeText("other")
        Files.delete(File(directory, "media/alias.mp4").toPath())
        link("media/alias.mp4", "other.mp4")
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun hostPlaybackReadsUseStableSourceChecksPerChunkAndAuthorizeAgainWhenClosed() {
        var authorizationChecks = 0
        var fileSourceChecks = 0
        val roots = LocalFolderSnapshot(12, snapshot.roots, snapshot.records,
            checkAuthorization = { authorizationChecks++ }, checkFileSource = { fileSourceChecks++ }) {}
        val captured = binding(roots)
        val input = captured.openPlaybackRead(path("media/clip.mp4"), path("media"))
        val openedChecks = authorizationChecks
        repeat(4) { assertTrue(input.read() >= 0) }
        assertEquals(openedChecks, authorizationChecks)
        assertTrue(fileSourceChecks > 4)
        input.close()
        assertTrue(authorizationChecks > openedChecks)
    }

    @Test
    fun ordinaryRemotePlaybackIgnoresUnrelatedGrantChangesAndKeepsBaseTokenAndProof() {
        every { remote.checkPlaybackPath("~/workspace/clip.mp4", "~/workspace", "remote-base") } returns Unit
        var closed = 0
        every { remote.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", "remote-base") } returns
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close() { closed++ }
            }
        val input = binding.openPlaybackRead("~/workspace/clip.mp4", "~/workspace")
        authorizationCurrent = false
        binding.checkCurrent()
        binding.checkPlaybackPath("~/workspace/clip.mp4", "~/workspace")
        assertArrayEquals(byteArrayOf(1, 2, 3), input.use { it.readBytes() })
        assertEquals(1, closed)
        verify(exactly = 1) { remote.checkPlaybackPath("~/workspace/clip.mp4", "~/workspace", "remote-base") }
        verify(exactly = 1) { remote.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", "remote-base") }
        verify(exactly = 0) { remote.openPlaybackRead(any(), any(), binding.identity) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun ordinaryRemoteLibraryStillCallsBothRemotePlaybackProofEntrypoints() {
        every { remote.isDirectory("~/workspace") } returns true
        every { remote.listFiles("~/workspace") } returns listOf(FileEntry("clip.mp4", false, 3, 1))
        every { remote.checkPlaybackPath(any(), "~/workspace", "remote-base") } returns Unit
        every { remote.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", "remote-base") } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val ownedCopy = File(temporary.root, "remote.copy")
        PlayerLibrary(binding, "~/workspace", "/T/", PlayerResources(), createCopy = { ownedCopy }).use { library ->
            assertEquals(1, library.json().getValue("items").jsonArray.size)
            library.openValidated("~/workspace/clip.mp4").use { opened ->
                assertEquals(3L, opened.size)
                assertArrayEquals(byteArrayOf(1, 2, 3), ByteArray(3).also { opened.descriptor!!.readFully(it) })
            }
        }
        verify(atLeast = 1) { remote.checkPlaybackPath("~/workspace/clip.mp4", "~/workspace", "remote-base") }
        verify(exactly = 1) { remote.openPlaybackRead("~/workspace/clip.mp4", "~/workspace", "remote-base") }
        verify(exactly = 0) { remote.openRead(any(), any()) }
        assertFalse(ownedCopy.exists())
    }

    @Test
    fun normalRemoteProofFailuresAreNotSwallowedByTheBindingAdapter() {
        every { remote.checkPlaybackPath("~/workspace/escape.mp4", "~/workspace", "remote-base") } throws IOException("outside playback root")
        every { remote.openPlaybackRead("~/workspace/escape.mp4", "~/workspace", "remote-base") } throws IOException("outside playback root")
        assertThrows(IOException::class.java) { binding.checkPlaybackPath("~/workspace/escape.mp4", "~/workspace") }
        assertThrows(IOException::class.java) { binding.openPlaybackRead("~/workspace/escape.mp4", "~/workspace") }
    }

    @Test
    fun defaultOpenReadDoesNotTransmitTheCompositePlayerCacheKeyToRemote() {
        every { remote.openRead("~/workspace/clip.mp4", "remote-base") } returns ByteArrayInputStream(byteArrayOf(7))
        assertEquals(7, binding.access.openRead("~/workspace/clip.mp4").use { it.read() })
        assertThrows(CancellationException::class.java) { binding.access.openRead("~/workspace/clip.mp4", binding.identity) }
        verify(exactly = 1) { remote.openRead("~/workspace/clip.mp4", "remote-base") }
        verify(exactly = 0) { remote.openRead(any(), binding.identity) }
    }

    @Test
    fun crossBackendCopyMoveRenameAndPlaybackRootsAreRejectedExplicitly() {
        assertThrows(IOException::class.java) { binding.access.copy(path("media/clip.mp4"), "~/workspace/clip.mp4") }
        assertThrows(IOException::class.java) { binding.access.move("~/workspace/clip.mp4", path("media/clip.mp4")) }
        assertThrows(IOException::class.java) { binding.access.rename(path("media/clip.mp4"), "~/workspace/clip.mp4") }
        assertThrows(IOException::class.java) { binding.openPlaybackRead(path("media/clip.mp4"), "~/workspace") }
        assertThrows(IOException::class.java) { binding.checkPlaybackPath("~/workspace/clip.mp4", path("media")) }
        verify(exactly = 0) { remote.copy(any(), any(), any()) }
        verify(exactly = 0) { remote.move(any(), any(), any()) }
        verify(exactly = 0) { remote.rename(any(), any()) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun capturedNativeBindingKeepsRawLocalFileDisplayAndEntryResolutionOutsideTheRouter() {
        val local = mockk<LocalFileAccess>()
        val file = File(directory, "media/clip.mp4")
        every { local.toDisplayPath("~/workspace") } returns "~/workspace"
        every { local.toDisplayPath("~/workspace/clip.mp4") } returns "~/workspace/clip.mp4"
        every { local.toDisplayPath(file) } returns "~/workspace/clip.mp4"
        every { local.copyToLocal("~/workspace/clip.mp4") } returns file
        every { local.listFiles("~/workspace") } returns listOf(FileEntry("clip.mp4", false, 10, 1, file))
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "local-base", local, null, null, snapshot) {}
        val native = PlayerWorkspaceBinding(execution, capture, owner) {}
        authorizationCurrent = false
        PlayerLibrary(native, "~/workspace", "/T/", PlayerResources(), createCopy = { error("Native files must not be copied") }).use { library ->
            library.openValidated("~/workspace/clip.mp4").use { opened ->
                assertEquals("0123456789", ByteArray(10).also { opened.descriptor!!.readFully(it) }.toString(Charsets.UTF_8))
            }
        }
        assertTrue(file.exists())
        verify(atLeast = 1) { local.toDisplayPath(file) }
        verify(exactly = 0) { local.toDisplayPath(file.canonicalPath) }
        verify(exactly = 0) { local.openRead(any(), any()) }
    }

    @Test
    fun ownerWorkspaceAndSourceChangesRejectReadsAndCloseTheCapturedStream() {
        val sourceInput = binding.access.openRead(path("media/clip.mp4"), "remote-base")
        sourceCurrent = false
        assertThrows(CancellationException::class.java) { sourceInput.read() }
        assertThrows(IOException::class.java) { sourceInput.read() }
        sourceCurrent = true
        workspaceCurrent = false
        assertThrows(CancellationException::class.java) { binding.access.fileSize(path("media/clip.mp4")) }
        workspaceCurrent = true
        val input = binding.access.openRead(path("media/clip.mp4"), "remote-base")
        owner.cancel()
        assertTrue(runCatching { input.read() }.isFailure)
        assertThrows(CancellationException::class.java) { binding.access.fileSize(path("media/clip.mp4")) }
    }
}
