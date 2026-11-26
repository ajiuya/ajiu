package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.UiFileExecutionSource
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.localRootPath
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootFileSession
import com.aicode.feature.workspace.domain.chroot.ChrootFileStat
import com.aicode.feature.workspace.domain.chroot.ChrootFileWorkerClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException

class PlayerWorkspaceCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = mockk<WorkspaceRepository>()
    private val resolver = mockk<ProjectMemoryIdentityResolver>()
    private val factory = mockk<WorkspaceExecutionContextFactory>()
    private val access = mockk<FileAccessProvider>()
    private val current = MutableStateFlow<Workspace?>(Workspace("fixture", "/app/workspace", WorkspaceType.INTERNAL))
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(current.value))
    private val identity = ProjectMemoryIdentity("local_proot", projectRoot = "/app/workspace", profileId = "fixture")
    private val capture = ProjectMemoryCapture(identity) {}
    private val owners = mutableListOf<RuntimeOwner>()
    private val checks = mutableListOf<() -> Unit>()
    private var roots = LocalFolderSnapshot.EMPTY
    private var denied = false

    private fun prepare() {
        every { repository.current } returns current
        every { repository.selection } returns selection
        coEvery { resolver.capture("/app/workspace", any(), any()) } returns capture
        every { access.cacheIdentity() } returns "chroot:player-fixture"
        coEvery { factory.freezeForPaths(any(), capture, any(), any()) } coAnswers {
            val owner = requireNotNull(arg<RuntimeOwner?>(2))
            val check = requireNotNull(arg<(() -> Unit)?>(3))
            owners += owner; checks += check
            check()
            if (denied) throw LocalRuntimeAuthorizationRequired()
            WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "chroot:player-fixture", access, null, null,
                roots, owner, checkSource = check)
        }
    }

    @Test fun failedSessionCaptureKeepsTheActivityOwnerForAuthorizationRetry(): Unit = runBlocking {
        prepare()
        val uiScope = Job()
        val source = UiFileExecutionSource("player", "~/workspace/media", uiScope)
        val firstSession = Job()
        val nextSession = Job()
        try {
            denied = true
            assertTrue(runCatching {
                PlayerWorkspaceBinding.capture(repository, resolver, factory, firstSession, "~/workspace/media",
                    source.runtimeOwner, source::checkCurrent)
            }.exceptionOrNull() is LocalRuntimeAuthorizationRequired)
            firstSession.cancel()
            checks.single().invoke()
            verify(exactly = 0) { access.cacheIdentity() }
            denied = false
            val binding = requireNotNull(PlayerWorkspaceBinding.capture(repository, resolver, factory, nextSession,
                "~/workspace/media", source.runtimeOwner, source::checkCurrent))
            assertEquals(listOf(source.runtimeOwner, source.runtimeOwner), owners)
            assertEquals(RuntimeOwnerKind.FILE, owners.first().kind)
            binding.checkCurrent()
            assertTrue(nextSession.isActive)
            source.close()
            assertThrows(CancellationException::class.java) { binding.checkCurrent() }
        } finally { firstSession.cancel(); nextSession.cancel(); uiScope.cancel() }
    }

    @Test fun workspaceAbaAndClosedActivityRevokeBindingEvenAtTheSamePath(): Unit = runBlocking {
        prepare()
        val uiScope = Job()
        val session = Job()
        val source = UiFileExecutionSource("player", "media", uiScope)
        try {
            val binding = requireNotNull(PlayerWorkspaceBinding.capture(repository, resolver, factory, session,
                "~/workspace/media", source.runtimeOwner, source::checkCurrent))
            val initial = selection.value
            selection.value = WorkspaceRepository.Selection(current.value?.copy(name = "other"))
            selection.value = WorkspaceRepository.Selection(initial.workspace)
            assertThrows(CancellationException::class.java) { binding.checkCurrent() }
            assertThrows(CancellationException::class.java) { checks.first().invoke() }
            assertNotEquals(source.runtimeOwner, UiFileExecutionSource("player", "media", uiScope).runtimeOwner)
            uiScope.cancel()
            assertThrows(CancellationException::class.java) { source.checkCurrent() }
        } finally { session.cancel(); uiScope.cancel() }
    }

    @Test fun guestPlaybackUsesTheBoundedWorkerRootAndRejectsAnEscapingLeafLink() {
        val session = Job()
        val root = "/root/workspace/media"
        val path = "$root/clip.mp4"
        val client = mockk<ChrootFileWorkerClient>()
        var resolved = path
        var closed = 0
        every { client.close() } returns Unit
        every { client.resolve(root, true) } returns root
        every { client.resolve(path, true) } answers { resolved }
        every { client.stat(root, true) } returns ChrootFileStat(1, 0x41ff, 0, 7, 1, 2)
        every { client.stat(path, true) } returns ChrootFileStat(2, 0x81a4, 3, 7, 1, 3)
        every { client.openRead(path) } answers {
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun close() { closed++ } }
        }
        val backend = ChrootFileAccess({ "guest" }, {
            ChrootFileSession("guest", client, temporary.root, {}, emptyList(), closeOwner = {})
        })
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "guest", backend, null, null) {}
        val binding = PlayerWorkspaceBinding(execution, capture, session) {}
        try {
            binding.checkPlaybackPath(root, root)
            binding.checkPlaybackPath(path, root)
            val input = binding.openPlaybackRead(path, root)
            assertEquals(1, input.read())
            resolved = "/etc/private.mp4"
            assertThrows(IOException::class.java) { input.read() }
            assertEquals(1, closed)
            assertThrows(IOException::class.java) { binding.checkPlaybackPath(path, root) }
            verify(exactly = 1) { client.openRead(path) }
            assertTrue(temporary.root.listFiles().isNullOrEmpty())
        } finally { session.cancel() }
    }

    @Test fun hostMediaCapturePassesOnlyItsGrantedVirtualRootToTheFactory(): Unit = runBlocking {
        prepare()
        val path = localRootPath("grant", "epoch")
        val directory = temporary.newFolder("host")
        File(directory, "clip.mp4").writeText("clip")
        val grant = LocalFolderGrant("grant", "epoch", "host", path, true)
        roots = LocalFolderSnapshot(4, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {}
        val uiScope = Job()
        val session = Job()
        val source = UiFileExecutionSource("player", path, uiScope)
        try {
            val binding = requireNotNull(PlayerWorkspaceBinding.capture(repository, resolver, factory, session, path,
                source.runtimeOwner, source::checkCurrent))
            assertEquals("clip", binding.access.readFile("$path/clip.mp4"))
            coVerify(exactly = 1) { factory.freezeForPaths(listOf(path), capture, source.runtimeOwner, any()) }
            coVerify(exactly = 0) { factory.freeze(any(), any(), any()) }
            verify(exactly = 0) { access.readFile(any()) }
            binding.openPlaybackRead("$path/clip.mp4", path).use { input ->
                assertEquals('c'.code, input.read())
            }
            session.cancel()
            assertThrows(CancellationException::class.java) { binding.checkCurrent() }
        } finally { session.cancel(); uiScope.cancel() }
    }
}
