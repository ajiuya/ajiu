package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.presentation.preview.PreviewFileReader
import com.aicode.feature.agent.presentation.preview.PreviewImageBytes
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolPreviewUiCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val local = mockk<LocalFileAccess>()
    private val access = mockk<FileAccessProvider>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val grants = mockk<LocalFolderGrantRepository>()
    private val factory = mockk<WorkspaceExecutionContextFactory>()
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(null))
    private val snapshots = MutableStateFlow(LocalFolderSnapshot.EMPTY)
    private val owners = mutableListOf<RuntimeOwner>()
    private val checks = mutableListOf<() -> Unit>()
    private var deny = false

    private fun adapter(): ToolPreviewFileAccessAdapter {
        val profile = ContainerProfile.BUILTIN_ALPINE
        val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 0, 0, Any())
        every { context.cacheDir } returns temporary.root
        every { workspace.currentPath() } returns "/app/workspace"
        every { workspace.current } returns MutableStateFlow(null)
        every { workspace.selection } returns selection
        every { connection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { settings.activeProfileIdFlow } returns flowOf(profile.id)
        every { settings.activeProfileSelectionRevisions } returns MutableStateFlow(0L)
        every { settings.profileConfigurationRevisions } returns MutableStateFlow(0L)
        coEvery { settings.captureProfileSnapshot() } returns snapshot
        every { settings.checkProfileSnapshot(snapshot) } returns Unit
        every { grants.snapshots } returns snapshots
        every { grants.snapshot() } answers { snapshots.value }
        every { access.cacheIdentity() } returns "chroot:tool-fixture"
        every { access.openRead(any(), "chroot:tool-fixture") } answers {
            assertEquals(RuntimeOwnerKind.FILE, WorkspaceExecutionContext.current()!!.runtimeOwner!!.kind)
            byteArrayOf(1, 2, 3).inputStream()
        }
        coEvery { factory.freezeForPaths(any(), any(), any(), any()) } coAnswers {
            val owner = requireNotNull(arg<RuntimeOwner?>(2))
            val check = requireNotNull(arg<(() -> Unit)?>(3))
            owners += owner; checks += check
            check()
            if (deny && arg<List<String>>(0).any { !com.aicode.feature.workspace.domain.isLocalFolderPath(it) }) {
                throw LocalRuntimeAuthorizationRequired()
            }
            WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "chroot:tool-fixture", access, null, null,
                snapshots.value, owner, checkSource = check)
        }
        return ToolPreviewFileAccessAdapter(access, local, ExecutionModeHolder(), connection, workspace, settings, grants, factory, context)
    }

    @Test fun fileCaptureIsLazyAndAuthorizationRetryKeepsItsOwnerWithoutBorrowingAChat(): Unit = runBlocking {
        val adapter = adapter()
        val ready = CompletableDeferred<PreviewFileReader>()
        val collecting = launch { adapter.readers.filterNotNull().collect { ready.complete(it) } }
        try {
            val reader = withTimeout(5_000) { ready.await() }
            coVerify(exactly = 0) { factory.freezeForPaths(any(), any(), any(), any()) }
            deny = true
            assertNull(reader.readVersionedFile("~/workspace/image.png"))
            val pending = owners.single()
            assertEquals(RuntimeOwnerKind.FILE, pending.kind)
            verify(exactly = 0) { access.openRead(any(), any()) }
            deny = false
            val payload = reader.readVersionedFile("~/workspace/image.png") as PreviewImageBytes
            assertEquals(listOf(pending, pending), owners)
            assertArrayEquals(byteArrayOf(1, 2, 3), payload.bytes)
            assertEquals(reader.source, payload.key.source)
            reader.readVersionedFile("~/workspace/another.png")
            assertNotEquals(pending, owners.last())
            collecting.cancelAndJoin()
            assertFalse(payload.isCurrent())
            assertThrows(CancellationException::class.java) { checks.first().invoke() }
        } finally { collecting.cancelAndJoin() }
    }

    @Test fun anAuthorizedHostFolderIsStillReadableWhenTheGuestRuntimeIsUnavailable() = runBlocking {
        val adapter = adapter()
        deny = true
        val root = localRootPath("grant", "epoch")
        val grant = LocalFolderGrant("grant", "epoch", "host", root, true)
        val directory = temporary.newFolder("host")
        File(directory, "image.png").writeBytes(byteArrayOf(7, 8))
        snapshots.value = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {}
        val ready = CompletableDeferred<PreviewFileReader>()
        val collecting = launch { adapter.readers.filterNotNull().collect { ready.complete(it) } }
        try {
            val reader = withTimeout(5_000) { ready.await() }
            val payload = reader.readVersionedFile("$root/image.png") as PreviewImageBytes
            assertArrayEquals(byteArrayOf(7, 8), payload.bytes)
            coVerify(exactly = 1) { factory.freezeForPaths(listOf("$root/image.png"), null, any(), any()) }
            verify(exactly = 0) { access.openRead(any(), any()) }
            verify(exactly = 0) { local.cacheIdentity() }
            selection.value = WorkspaceRepository.Selection(null)
            assertFalse(payload.isCurrent())
        } finally { collecting.cancelAndJoin() }
    }
}
