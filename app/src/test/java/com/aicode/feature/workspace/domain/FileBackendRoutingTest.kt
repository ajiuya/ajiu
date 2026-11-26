package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class FileBackendRoutingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val holder = ExecutionModeHolder()
    private val local = mockk<LocalFileAccess>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val repository = mockk<LocalFolderGrantRepository>()
    private lateinit var directory: File
    private lateinit var virtualRoot: String
    private lateinit var snapshot: LocalFolderSnapshot
    private lateinit var access: DelegatingFileAccess
    private lateinit var execution: WorkspaceExecutionContext
    private var rootsCurrent = true
    private var modeCurrent = true

    @Before
    fun prepare() {
        directory = temporary.newFolder("host")
        virtualRoot = localRootPath("grant", "epoch")
        val grant = LocalFolderGrant("grant", "epoch", "host", virtualRoot, true)
        snapshot = LocalFolderSnapshot(3, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {
            if (!rootsCurrent) throw CancellationException("Registry changed")
        }
        holder.setMode(ExecutionMode.REMOTE_SSH)
        every { repository.snapshot() } returns snapshot
        every { remote.cacheIdentity() } returns "remote-base"
        every { remote.agentConfigSourceIdentity() } returns "remote-agent-cache"
        access = DelegatingFileAccess(holder, local, remote, repository)
        execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote-base", remote, null, null, snapshot) {
            if (!modeCurrent || holder.currentMode() != ExecutionMode.REMOTE_SSH) throw CancellationException("Mode changed")
        }
    }

    @Test
    fun frozenRemoteWorkspaceRoutesVirtualPathsToIndependentHostAccessWithoutChangingBaseIdentity() = runBlocking {
        File(directory, "file").writeText("host bytes")
        withContext(execution) {
            assertEquals("remote-base", access.cacheIdentity())
            assertEquals("remote-agent-cache", access.agentConfigSourceIdentity())
            assertEquals("host bytes", access.readFile("$virtualRoot/file"))
            assertEquals("$virtualRoot/file", access.toDisplayPath("$virtualRoot/file"))
            assertEquals(null, access.agentConfigSnapshot(virtualRoot, AgentConfigKind.SKILLS))
            access.writeBytes("$virtualRoot/written", byteArrayOf(4, 5), false)
        }
        assertArrayEquals(byteArrayOf(4, 5), File(directory, "written").readBytes())
        verify(exactly = 0) { remote.readFile(any()) }
        verify(exactly = 0) { local.readFile(any()) }
        verify(exactly = 0) { remote.agentConfigSnapshot(any(), any(), any()) }
    }

    @Test
    fun nonVirtualPathsKeepOriginalRemoteMethodsAndOpenReadExpectedBaseToken() = runBlocking {
        every { remote.readBytes("~/workspace/file", 3) } returns byteArrayOf(1, 2, 3)
        every { remote.resolvePath("~/workspace/link", false) } returns "/server/link"
        every { remote.openRead("~/workspace/file", "remote-base") } returns ByteArrayInputStream(byteArrayOf(7))
        withContext(execution) {
            assertArrayEquals(byteArrayOf(1, 2, 3), access.readBytes("~/workspace/file", 3))
            assertEquals("/server/link", access.resolvePath("~/workspace/link", false))
            assertEquals(7, access.openRead("~/workspace/file", "remote-base").use { it.read() })
        }
        verify(exactly = 1) { remote.openRead("~/workspace/file", "remote-base") }
        verify(exactly = 0) { local.openRead(any(), any()) }
        verify(exactly = 0) { repository.snapshot() }
    }

    @Test
    fun malformedReservedPrefixAndOldEpochNeverFallThroughToRemote() = runBlocking {
        withContext(execution) {
            for (path in listOf(
                "/mnt/.aicode-local-fs", "/mnt/.aicode-local-fs/grant", "/mnt/.aicode-local-fs//epoch/file",
                "/mnt/.aicode-local-fs/grant/old/file", "/mnt/.aicode-local-fs/../epoch/file"
            )) {
                assertTrue(runCatching { access.exists(path) }.isFailure)
            }
        }
        verify(exactly = 0) { remote.exists(any()) }
        verify(exactly = 0) { local.exists(any()) }
        assertFalse(isLocalFolderPath("/mnt/.aicode-local-fs-other/grant/epoch"))
        assertEquals(virtualRoot, localRootPath("$virtualRoot/file"))
        assertEquals(null, localRootPath("/mnt/.aicode-local-fs/grant"))
    }

    @Test
    fun outsideWorkspaceContextHostStreamCapturesBothGrantAndDefaultMode() {
        File(directory, "file").writeText("host")
        val input = access.openRead("$virtualRoot/file", "remote-base")
        assertEquals('h'.code, input.read())
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        assertThrows(CancellationException::class.java) { input.read() }
        verify(exactly = 0) { local.openRead(any(), any()) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun capturedHostStreamRejectsRootRegistryRevisionAndNeverSelectsReplacementSnapshot() = runBlocking {
        File(directory, "file").writeText("old")
        val input = withContext(execution) { access.openRead("$virtualRoot/file", "remote-base") }
        rootsCurrent = false
        every { repository.snapshot() } returns LocalFolderSnapshot.EMPTY
        assertThrows(CancellationException::class.java) { input.read() }
        verify(exactly = 0) { repository.snapshot() }
    }

    @Test
    fun routeCaptureUsesGrantTokenButKeepsHostAndServerPhysicalNamespacesSeparate(): Unit = runBlocking {
        val capture = FileBackendRouting().capture(execution, listOf("$virtualRoot/file", "~/workspace/file"))
        withContext(execution) {
            val host = capture.endpoint("$virtualRoot/file")
            val server = capture.endpoint("~/workspace/file")
            assertTrue(host.provider is InternalLocalFolderAccess)
            assertEquals("host", host.physicalNamespace)
            assertTrue(server.physicalNamespace.startsWith("remote:"))
            assertNotEquals(server.identity, host.identity)
            assertEquals("remote-base", server.identity)
        }
        rootsCurrent = false
        assertThrows(CancellationException::class.java) { capture.checkCurrent() }
        assertThrows(CancellationException::class.java) { capture.endpoint("$virtualRoot/file") }
    }

    @Test
    fun crossBackendCopyMoveAndRenameAreRejectedInsteadOfSendingHostPathsToServer() = runBlocking {
        withContext(execution) {
            assertThrows(IOException::class.java) { access.copy("$virtualRoot/file", "~/workspace/file", false) }
            assertThrows(IOException::class.java) { access.move("~/workspace/file", "$virtualRoot/file", false) }
            assertThrows(IOException::class.java) { access.rename("$virtualRoot/file", "~/workspace/file") }
        }
        verify(exactly = 0) { remote.copy(any(), any(), any()) }
        verify(exactly = 0) { remote.move(any(), any(), any()) }
        verify(exactly = 0) { remote.rename(any(), any()) }
    }

    @Test
    fun prootAliasesAndGrantPathsShareOnePhysicalNamespaceAndCannotOverwriteEachOther() {
        val rootfs = temporary.newFolder("rootfs")
        val global = temporary.newFolder("global")
        val mapping = WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE, directory, rootfs, global, "/root")
        every { local.resolvePath("~/workspace/tree", any()) } returns File(directory, "tree").absolutePath
        File(directory, "tree/sub").mkdirs()
        val frozen = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "local", local, mapping, null, snapshot) {}
        val capture = FileBackendRouting().capture(frozen, listOf("~/workspace/tree", "$virtualRoot/tree/sub"))
        assertThrows(FilePathOverlapException::class.java) { capture.guardTransfer("~/workspace/tree", "$virtualRoot/tree/sub") }
        every { local.resolvePath("/alias-to-root", any()) } returns directory.absolutePath
        val aliasCapture = FileBackendRouting().capture(frozen, listOf("/alias-to-root"))
        assertThrows(IOException::class.java) { aliasCapture.guardMutablePath("/alias-to-root") }
    }

    @Test
    fun entryAndRealPathsBothGuardRootsAndAncestorOverlaps() {
        File(directory, "tree/sub").mkdirs()
        Files.createSymbolicLink(File(directory, "alias").toPath(), File(directory, "tree").toPath())
        val paths = listOf("$virtualRoot/tree/sub", "$virtualRoot/alias", virtualRoot)
        val capture = FileBackendRouting().capture(execution, paths)
        assertThrows(FilePathOverlapException::class.java) { capture.guardTransfer(paths[0], paths[1]) }
        assertThrows(IOException::class.java) { capture.guardMutablePath(virtualRoot) }
        assertThrows(IOException::class.java) { capture.endpoint("$virtualRoot/not-captured-in-another-grant/../..")
            .resolvePath() }
    }

    @Test
    fun watchMayReadTheAuthorizedRootWithoutGrantingMutableTransferAccess() {
        val host = InternalLocalFolderAccess(snapshot, execution)
        assertEquals(directory, host.directoryForWatch(virtualRoot))
        assertThrows(IOException::class.java) { host.transferFile(virtualRoot) }
        rootsCurrent = false
        assertThrows(CancellationException::class.java) { host.directoryForWatch(virtualRoot) }
    }

    @Test
    fun retainedEndpointProviderCannotSilentlyUseAnotherWorkspaceContext() = runBlocking {
        every { remote.readFile("~/workspace/file") } answers {
            assertEquals(execution, WorkspaceExecutionContext.current())
            "captured"
        }
        val capture = FileBackendRouting().capture(execution, listOf("~/workspace/file"))
        val endpoint = capture.endpoint("~/workspace/file")
        val retained = endpoint.provider
        assertEquals("captured", retained.readFile("~/workspace/file"))
        val other = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "other", remote, null, null) {}
        withContext(other) {
            assertThrows(CancellationException::class.java) { retained.readFile("~/workspace/file") }
        }
        assertThrows(CancellationException::class.java) { endpoint.transferBackend }
        withContext(execution) { assertEquals(remote, endpoint.transferBackend) }
        verify(exactly = 1) { remote.readFile("~/workspace/file") }
    }

    @Test
    fun chrootRoutesUseGuestNamespaceWhileAuthorizedFoldersStayOnHostWithoutWorkerQueries() = runBlocking {
        val worker = mockk<FileAccessProvider>()
        val owner = RuntimeOwner(RuntimeOwnerKind.FILE, "browser")
        val runtime = LocalRuntimeCapture(owner, "chroot", 1, ChrootInstance("instance", 1, 2, 3),
            directory.absolutePath, "/root", "generation", Any()) {}
        val frozen = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "chroot-generation", worker, null, null,
            snapshot, owner, runtime) {}
        val capture = FileBackendRouting().capture(frozen, listOf(virtualRoot, "~/workspace/file"))
        val guest = capture.endpoint("~/workspace/file")
        val host = capture.endpoint(virtualRoot)
        assertEquals("chroot:generation", guest.physicalNamespace)
        assertEquals("host", host.physicalNamespace)
        assertEquals("chroot-generation", guest.identity)
        withContext(frozen) {
            assertTrue(host.provider.isDirectory(virtualRoot))
            assertSame(worker, guest.transferBackend)
        }
        verify(exactly = 0) { worker.cacheIdentity() }
        verify(exactly = 0) { worker.isDirectory(any()) }
        verify(exactly = 0) { local.isDirectory(any()) }
        verify(exactly = 0) { remote.isDirectory(any()) }
    }

    @Test
    fun missingChrootCaptureDoesNotInterceptAnIndependentlyAuthorizedHostFolder() {
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        every { local.cacheIdentity() } throws LocalRuntimeAuthorizationRequired()
        File(directory, "file").writeText("host")
        assertEquals("host", access.readFile("$virtualRoot/file"))
        val source = access.capturePathSource("$virtualRoot/file")
        source.checkCurrent()
        source.checkAuthorization()
        assertEquals('h'.code, access.openRead("$virtualRoot/file").use { it.read() })
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { access.cacheIdentity() }
        every { local.readFile("/etc/hosts") } throws LocalRuntimeAuthorizationRequired()
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { access.readFile("/etc/hosts") }
        verify(exactly = 0) { remote.readFile(any()) }
        rootsCurrent = false
        assertThrows(CancellationException::class.java) { source.checkCurrent() }
    }

    @Test
    fun pathSourceCacheKeyIncludesGrantEpochAndRevisionWithoutChangingRemoteExpectedBase() {
        val captured = access.capturePathSource("$virtualRoot/file")
        assertEquals("remote-base", captured.expectedBaseIdentity)
        assertNotEquals("remote-base", captured.identity)
        rootsCurrent = false
        assertThrows(CancellationException::class.java) { captured.checkCurrent() }
        val remoteSource = access.capturePathSource("~/workspace/file")
        assertEquals("remote-base", remoteSource.identity)
        assertEquals("remote-base", remoteSource.expectedBaseIdentity)
        remoteSource.checkCurrent()
    }

    @Test
    fun retainedCapturedRemoteStreamChecksRouteRevisionOnEveryRead() {
        var closed = 0
        every { remote.openRead("~/workspace/file", "remote-base") } returns object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun close() { closed++ }
        }
        val capture = FileBackendRouting().capture(execution, listOf("~/workspace/file"))
        val input = capture.endpoint("~/workspace/file").provider.openRead("~/workspace/file")
        assertEquals(1, input.read())
        rootsCurrent = false
        assertThrows(CancellationException::class.java) { input.read() }
        assertEquals(1, closed)
    }

    @Test
    fun profileSelectionAbaInvalidatesFrozenWorkspaceEvenWhenProfileConfigurationIsEqual(): Unit = runBlocking {
        val rootfs = temporary.newFolder("rootfs")
        val global = temporary.newFolder("global")
        val mapping = WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE, directory, rootfs, global, "/root")
        val settings = mockk<ContainerSettingsRepository>()
        var selectionRevision = 1L
        every { settings.activeProfileSelectionRevision } answers { selectionRevision }
        every { settings.activeProfileIdFlow } returns flowOf(ContainerProfile.BUILTIN_ID)
        coEvery { settings.captureProfileSnapshot() } answers {
            ContainerProfileSnapshot(ContainerProfile.BUILTIN_ID, ContainerProfile.BUILTIN_ID,
                listOf(ContainerProfile.BUILTIN_ALPINE), selectionRevision, 0L, settings)
        }
        every { settings.checkProfileSnapshot(any()) } answers {
            if (firstArg<ContainerProfileSnapshot>().selectionRevision != selectionRevision) {
                throw CancellationException("Profile selection changed")
            }
        }
        every { local.captureExecutionMapping(any()) } returns mapping
        every { local.checkExecutionMapping(mapping) } returns Unit
        every { local.cacheIdentity() } returns "local"
        val emptyRoots = mockk<LocalFolderGrantRepository>(relaxed = true) {
            every { snapshot() } returns LocalFolderSnapshot.EMPTY
        }
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        val files = DelegatingFileAccess(holder, local, remote, emptyRoots)
        val factory = WorkspaceExecutionContextFactory(files, holder, local, remote, settings, emptyRoots)
        val frozen = factory.freeze()
        selectionRevision += 2
        assertThrows(CancellationException::class.java) { frozen.checkCurrent() }
    }
}
