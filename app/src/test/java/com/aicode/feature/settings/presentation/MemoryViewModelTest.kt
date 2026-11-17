package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModelStore
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryManagementResult
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.memory.MemoryStorageTarget
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val current = MutableStateFlow<Workspace?>(Workspace("first", "/first"))
    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val holder = ExecutionModeHolder()
    private val workspace = mockk<WorkspaceRepository>()
    private val ssh = mockk<RemoteSshConnection>()
    private val repository = mockk<MemoryRepository>()
    private val store = ViewModelStore()
    private var config: RemoteConnectionConfig? = null
    private lateinit var viewModel: MemoryViewModel
    private val entry = MemoryEntry(MemoryScope.PROJECT, "entry.md", "entry", "summary", "preview", "version-1")
    private val document = MemoryDocument(entry, "# Complete body")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { workspace.current } returns current
        every { ssh.config } answers { config }
        every { ssh.connectionState } returns connection
        every { repository.captureStorageTarget(any()) } answers {
            MemoryStorageTarget(File("/global"), firstArg<String?>()?.let { File(it, ".aicode/memory") })
        }
        every { repository.listEntries(any()) } returns MemoryManagementResult.Success(listOf(entry))
        every { repository.readEntry(any(), any()) } returns MemoryManagementResult.Success(document)
        every { repository.createOnly(any(), any(), any(), any(), any()) } returns MemoryManagementResult.Success(Unit)
        every { repository.deleteEntry(any(), any()) } returns MemoryManagementResult.Success(Unit)
        viewModel = MemoryViewModel(repository, workspace, holder, ssh, dispatcher)
        store.put("memory", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun noWorkspaceKeepsGlobalAvailableAndRejectsProjectCreation() {
        current.value = null
        every { repository.listEntries(any()) } returns MemoryManagementResult.Success(emptyList())
        viewModel.open(); settle()
        assertFalse(viewModel.state.value.hasWorkspace)
        assertNull(viewModel.state.value.projectName)
        viewModel.startCreate()
        viewModel.create("entry", "", "body", MemoryScope.PROJECT)
        assertEquals(MemoryManagementError.NO_WORKSPACE, viewModel.state.value.error)
        verify(exactly = 0) { repository.createOnly(any(), any(), any(), any(), any()) }
        viewModel.create("entry", "", "body", MemoryScope.GLOBAL); settle()
        verify(exactly = 1) { repository.createOnly(MemoryStorageTarget(File("/global"), null), MemoryScope.GLOBAL, "entry", "", "body") }
        assertFalse(viewModel.state.value.creating)
    }

    @Test fun lateListFromPreviousWorkspaceCannotPopulateThePage() {
        every { repository.listEntries(any()) } answers {
            current.value = Workspace("second", "/second")
            MemoryManagementResult.Success(listOf(entry))
        }
        viewModel.open(); settle()
        assertTrue(viewModel.state.value.targetChanged)
        assertTrue(viewModel.state.value.entries.isEmpty())
        assertFalse(viewModel.state.value.loading)
    }

    @Test fun lateDetailAfterWorkspaceSwitchCannotDisplayOldContent() {
        viewModel.open(); settle()
        every { repository.readEntry(any(), any()) } answers {
            current.value = Workspace("second", "/second")
            MemoryManagementResult.Success(document)
        }
        viewModel.openDetail(entry); settle()
        assertTrue(viewModel.state.value.targetChanged)
        assertNull(viewModel.state.value.document)
        assertNull(viewModel.state.value.selectedEntry)
    }

    @Test fun closeDetailRejectsAnAlreadyQueuedRead() {
        viewModel.open(); settle()
        viewModel.openDetail(entry)
        viewModel.closeDetail()
        settle()
        assertNull(viewModel.state.value.document)
        assertNull(viewModel.state.value.selectedEntry)
    }

    @Test fun mutationRequestedAfterWorkspaceSwitchDoesNotReachStorage() {
        viewModel.open(); settle()
        viewModel.startCreate()
        viewModel.requestDelete(entry)
        current.value = Workspace("second", "/second")
        viewModel.create("entry", "", "body", MemoryScope.PROJECT)
        viewModel.confirmDelete()
        settle()
        assertTrue(viewModel.state.value.targetChanged)
        verify(exactly = 0) { repository.createOnly(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.deleteEntry(any(), any()) }
    }

    @Test fun switchingBeforeTheMainThreadLaunchRunsPreventsAQueuedWrite() {
        viewModel.open(); settle()
        viewModel.startCreate()
        viewModel.create("entry", "", "body", MemoryScope.PROJECT)
        current.value = Workspace("second", "/second")
        settle()
        verify(exactly = 0) { repository.createOnly(any(), any(), any(), any(), any()) }
        assertTrue(viewModel.state.value.targetChanged)
    }

    @Test fun writeUsesFrozenRootAndLateSuccessDoesNotUpdateTheNewEnvironment() {
        viewModel.open(); settle()
        val firstTarget = MemoryStorageTarget(File("/global"), File("/first/.aicode/memory"))
        every { repository.createOnly(any(), any(), any(), any(), any()) } answers {
            assertEquals(firstTarget, firstArg<MemoryStorageTarget>())
            current.value = Workspace("second", "/second")
            MemoryManagementResult.Success(Unit)
        }
        viewModel.startCreate()
        viewModel.create("entry", "", "body", MemoryScope.PROJECT); settle()
        verify(exactly = 1) { repository.createOnly(firstTarget, MemoryScope.PROJECT, "entry", "", "body") }
        assertTrue(viewModel.state.value.targetChanged)
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test fun sameRemotePathOnADifferentServerInvalidatesThePage() {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        config = config("192.0.2.1")
        viewModel.open(); settle()
        config = config("192.0.2.2")
        connection.value = ConnectionState.CONNECTING
        settle()
        assertTrue(viewModel.state.value.targetChanged)
        viewModel.startCreate()
        assertFalse(viewModel.state.value.creating)
    }

    @Test fun remoteUsernamePortAndRootArePartOfTheFrozenIdentity() {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        listOf(config("host", username = "other"), config("host", port = 2222), config("host", root = "/other-root")).forEach { changed ->
            config = config("host")
            viewModel.open(); settle()
            config = changed
            viewModel.startCreate()
            assertTrue(viewModel.state.value.targetChanged)
            assertFalse(viewModel.state.value.creating)
        }
    }

    @Test fun executionModeSwitchStopsFurtherReadsAndRequiresExplicitReopen() {
        viewModel.open(); settle()
        holder.setMode(ExecutionMode.REMOTE_SSH); settle()
        assertTrue(viewModel.state.value.targetChanged)
        holder.setMode(ExecutionMode.LOCAL_PROOT); settle()
        viewModel.startCreate()
        assertFalse(viewModel.state.value.creating)
        viewModel.open(); settle()
        assertFalse(viewModel.state.value.targetChanged)
        assertEquals(listOf(entry), viewModel.state.value.entries)
    }

    @Test fun duplicateCreateLeavesTheDraftOpenWithAConflict() {
        every { repository.createOnly(any(), any(), any(), any(), any()) } returns
            MemoryManagementResult.Failure(MemoryManagementError.NAME_CONFLICT)
        viewModel.open(); settle()
        viewModel.startCreate()
        viewModel.create("entry", "summary", "body", MemoryScope.PROJECT); settle()
        assertTrue(viewModel.state.value.creating)
        assertFalse(viewModel.state.value.saving)
        assertEquals(MemoryManagementError.NAME_CONFLICT, viewModel.state.value.error)
    }

    @Test fun deleteRequiresConfirmationAndPassesTheExactVersionAndScope() {
        viewModel.open(); settle()
        viewModel.requestDelete(entry)
        settle()
        verify(exactly = 0) { repository.deleteEntry(any(), any()) }
        viewModel.confirmDelete(); settle()
        verify(exactly = 1) { repository.deleteEntry(any(), entry) }
        assertNull(viewModel.state.value.pendingDelete)
        assertFalse(viewModel.state.value.deleting)
    }

    @Test fun changedDeleteReportsConflictWithoutDroppingTheVisibleEntry() {
        every { repository.deleteEntry(any(), any()) } returns MemoryManagementResult.Failure(MemoryManagementError.CHANGED)
        viewModel.open(); settle()
        viewModel.requestDelete(entry)
        viewModel.confirmDelete(); settle()
        assertEquals(MemoryManagementError.CHANGED, viewModel.state.value.error)
        assertEquals(listOf(entry), viewModel.state.value.entries)
        assertNull(viewModel.state.value.pendingDelete)
    }

    @Test fun detailUsesTheFreshVersionReturnedByTheExactScopeRead() {
        val fresh = document.copy(entry = entry.copy(version = "version-2"))
        every { repository.readEntry(any(), any()) } returns MemoryManagementResult.Success(fresh)
        viewModel.open(); settle()
        viewModel.openDetail(entry); settle()
        assertEquals("version-2", viewModel.state.value.selectedEntry?.version)
        viewModel.requestDelete(viewModel.state.value.document!!.entry)
        viewModel.confirmDelete(); settle()
        verify(exactly = 1) { repository.deleteEntry(any(), fresh.entry) }
        assertNull(viewModel.state.value.selectedEntry)
    }

    @Test fun closingPageAndReopeningRejectsTheOlderPendingResult() {
        viewModel.open()
        viewModel.close()
        current.value = Workspace("second", "/second")
        val second = entry.copy(name = "second", fileName = "second.md")
        every { repository.listEntries(any()) } returns MemoryManagementResult.Success(listOf(second))
        viewModel.open(); settle()
        assertEquals("second", viewModel.state.value.projectName)
        assertEquals(listOf(second), viewModel.state.value.entries)
        assertFalse(viewModel.state.value.targetChanged)
    }

    @Test fun cancellationIsNotConvertedToAnIoError() {
        every { repository.readEntry(any(), any()) } throws CancellationException("cancelled")
        viewModel.open(); settle()
        viewModel.openDetail(entry); settle()
        assertNull(viewModel.state.value.document)
        assertNull(viewModel.state.value.error)
        viewModel.closeDetail()
        assertFalse(viewModel.state.value.detailLoading)
    }

    private fun config(host: String, port: Int = 22, username: String = "user", root: String = "/root-path"): RemoteConnectionConfig = mockk {
        every { this@mockk.host } returns host
        every { this@mockk.port } returns port
        every { this@mockk.username } returns username
        every { remoteWorkspacePath } returns root
    }

    private fun settle() = dispatcher.scheduler.runCurrent()
}
