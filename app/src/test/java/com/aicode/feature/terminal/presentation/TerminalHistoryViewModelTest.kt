package com.aicode.feature.terminal.presentation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryRepository
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalTaskHistoryState
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskScopeResolver
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val localManager = mockk<TerminalSessionManager>()
    private val remoteManager = mockk<RemoteTerminalSessionManager>()
    private val engine = mockk<LinuxContainerEngine>()
    private val terminalSettings = mockk<TerminalSettingsRepository>()
    private val repository = mockk<TerminalTaskHistoryRepository>()
    private val resolver = mockk<TerminalTaskScopeResolver>()
    private val workspace = mockk<WorkspaceRepository>()
    private val containerSettings = mockk<ContainerSettingsRepository>()
    private val mode = ExecutionModeHolder()
    private val saved = MutableStateFlow(TerminalTaskHistoryState(loaded = true))
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("first", "/first")))
    private val profile = MutableStateFlow(ContainerProfile.BUILTIN_ALPINE)
    private val profileRevision = MutableStateFlow(0L)
    private val preparation = CompletableDeferred<Unit>()
    private var scope: TerminalTaskScope? = baseScope
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { engine.initProgress } returns MutableStateFlow(ContainerInitState.Idle)
        every { terminalSettings.settingsFlow } returns MutableStateFlow(TerminalSettings())
        every { repository.state } returns saved
        coEvery { repository.load() } coAnswers { saved.value }
        coEvery { resolver.capture() } coAnswers { scope }
        every { workspace.selection } returns selection
        every { containerSettings.activeProfileFlow } returns profile
        every { containerSettings.activeProfileSelectionRevisions } returns profileRevision
        every { containerSettings.activeProfileSelectionRevision } answers { profileRevision.value }
        coEvery { localManager.ensureInitialTab() } coAnswers { preparation.await() }
        viewModel = TerminalViewModel(
            mockk<Context>(), localManager, remoteManager, mode, engine, terminalSettings,
            repository, resolver, workspace, containerSettings
        )
        store.put("terminal", viewModel)
        settle()
    }

    @After
    fun tearDown() {
        viewModel.hideHistory()
        preparation.complete(Unit)
        settle()
        store.clear()
        settle()
        Dispatchers.resetMain()
    }

    @Test
    fun viewingHistoryDoesNotPrepareOrCreateATabAndWorksWhilePrepareIsLoading() {
        val first = record("first", "session-one")
        val second = record("second", "session-two")
        saved.value = saved.value.copy(records = listOf(first, second, record("private", "other", baseScope.copy(workspaceRoot = "/other"))))
        viewModel.showHistory()
        viewModel.showHistory()
        settle()
        assertEquals(TerminalViewModel.PrepareState.Loading, viewModel.prepareState.value)
        assertEquals(setOf("first", "second"), viewModel.historyState.value.records.map { it.historyId }.toSet())
        viewModel.selectHistory(first.historyId)
        assertEquals(first, viewModel.historyState.value.selectedRecord)
        viewModel.backToHistoryList()
        assertNull(viewModel.historyState.value.selectedRecord)
        coVerify(exactly = 1) { localManager.ensureInitialTab() }
        coVerify(exactly = 0) { remoteManager.ensureInitialTab() }
        coVerify(exactly = 0) { localManager.createInteractiveTab() }
        coVerify(exactly = 0) { remoteManager.createInteractiveTab() }
        coVerify(exactly = 1) { repository.load() }
    }

    @Test
    fun everyFrozenScopeFieldParticipatesInFiltering() {
        val others = listOf(
            baseScope.copy(profileId = "other-profile"),
            baseScope.copy(rootfsSourceIdentity = "b".repeat(64)),
            baseScope.copy(rootfsPath = "/other-rootfs"),
            baseScope.copy(rootfsIdentity = "replaced-rootfs"),
            baseScope.copy(workspaceRoot = "/other-workspace"),
            baseScope.copy(workspaceIdentity = "recreated-workspace")
        )
        saved.value = saved.value.copy(records = listOf(record("visible")) +
            others.mapIndexed { index, other -> record("hidden-$index", scope = other) })
        viewModel.showHistory()
        settle()
        assertEquals(listOf("visible"), viewModel.historyState.value.records.map { it.historyId })
        viewModel.selectHistory("hidden-0")
        assertNull(viewModel.historyState.value.selectedRecord)
    }

    @Test
    fun repositorySubscriptionsAndLoadingAreLimitedToTheOpenSheet() {
        assertEquals(0, saved.subscriptionCount.value)
        coVerify(exactly = 0) { repository.load() }
        val loading = CompletableDeferred<Unit>()
        saved.value = TerminalTaskHistoryState()
        coEvery { repository.load() } coAnswers { loading.await(); saved.value }
        viewModel.showHistory()
        settle()
        assertTrue(viewModel.historyState.value.loading)
        assertEquals(1, saved.subscriptionCount.value)
        viewModel.hideHistory()
        settle()
        assertEquals(TerminalHistoryUiState(), viewModel.historyState.value)
        assertEquals(0, saved.subscriptionCount.value)
        loading.complete(Unit)
        saved.value = TerminalTaskHistoryState(loaded = true, records = listOf(record("late")))
        settle()
        assertEquals(TerminalHistoryUiState(), viewModel.historyState.value)
        viewModel.showHistory()
        settle()
        assertEquals(listOf("late"), viewModel.historyState.value.records.map { it.historyId })
    }

    @Test
    fun workspaceChangeClearsSelectedOutputBeforeResolvingTheNewScope() {
        saved.value = saved.value.copy(records = listOf(record("first")))
        viewModel.showHistory()
        settle()
        viewModel.selectHistory("first")
        val resolving = CompletableDeferred<TerminalTaskScope?>()
        coEvery { resolver.capture() } coAnswers { resolving.await() }
        selection.value = WorkspaceRepository.Selection(Workspace("second", "/second"))
        settle()
        assertTrue(viewModel.historyState.value.loading)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertNull(viewModel.historyState.value.selectedRecord)
        val secondScope = baseScope.copy(workspaceRoot = "/second", workspaceIdentity = "second-directory")
        saved.value = saved.value.copy(records = saved.value.records + record("second", scope = secondScope))
        resolving.complete(secondScope)
        settle()
        assertEquals(listOf("second"), viewModel.historyState.value.records.map { it.historyId })
    }

    @Test
    fun profileAndWorkspaceAbaChangesResolveAgainWithoutRestoringOldDetail() {
        saved.value = saved.value.copy(records = listOf(record("first")))
        viewModel.showHistory()
        settle()
        viewModel.selectHistory("first")
        profileRevision.value = 2L
        selection.value = WorkspaceRepository.Selection(selection.value.workspace)
        settle()
        assertEquals(listOf("first"), viewModel.historyState.value.records.map { it.historyId })
        assertNull(viewModel.historyState.value.selectedRecord)
        coVerify(exactly = 4) { resolver.capture() }
        val changed = baseScope.copy(profileId = "other-profile", rootfsIdentity = "new-rootfs")
        profile.value = profile.value.copy(id = changed.profileId)
        scope = changed
        profileRevision.value++
        settle()
        assertTrue(viewModel.historyState.value.records.isEmpty())
    }

    @Test
    fun rootfsReplacementWithoutASelectionChangeHidesPreviouslyDisplayedHistory() {
        assertSameSelectionIdentityReplacementIsHidden(baseScope.copy(rootfsIdentity = "replaced-rootfs"))
    }

    @Test
    fun workspaceReplacementWithoutASelectionChangeHidesPreviouslyDisplayedHistory() {
        assertSameSelectionIdentityReplacementIsHidden(baseScope.copy(workspaceIdentity = "recreated-workspace"))
    }

    private fun assertSameSelectionIdentityReplacementIsHidden(replacement: TerminalTaskScope) {
        val frozenSelection = selection.value
        saved.value = saved.value.copy(records = listOf(record("first")))
        viewModel.showHistory()
        settle()
        viewModel.selectHistory("first")
        assertNotNull(viewModel.historyState.value.selectedRecord)
        coEvery { resolver.capture() } returns replacement
        saved.value = saved.value.copy(records = listOf(record("first").copy(output = "private-replaced-output")))
        settle()
        assertSame(frozenSelection, selection.value)
        assertEquals(0L, profileRevision.value)
        assertEquals(ExecutionMode.LOCAL_PROOT, mode.currentMode())
        assertTrue(viewModel.historyState.value.unavailable)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertNull(viewModel.historyState.value.selectedRecord)
        assertNull(viewModel.historyState.value.selectedHistoryId)
        saved.value = saved.value.copy(records = listOf(record("late-private-output")))
        settle()
        assertTrue(viewModel.historyState.value.unavailable)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertNull(viewModel.historyState.value.selectedRecord)
        assertFalse(viewModel.historyState.value.toString().contains("private-replaced-output"))
        assertEquals(TerminalViewModel.PrepareState.Loading, viewModel.prepareState.value)
        coVerify(exactly = 1) { localManager.ensureInitialTab() }
        coVerify(exactly = 0) { remoteManager.ensureInitialTab() }
        coVerify(exactly = 0) { localManager.createInteractiveTab() }
        coVerify(exactly = 0) { remoteManager.createInteractiveTab() }
        coVerify(exactly = 1) { repository.load() }
    }

    @Test
    fun modeChangeHidesLocalOutputAndRemoteCannotOpenHistory() {
        saved.value = saved.value.copy(records = listOf(record("first")))
        viewModel.showHistory()
        settle()
        viewModel.selectHistory("first")
        mode.setMode(ExecutionMode.REMOTE_SSH)
        settle()
        assertTrue(viewModel.historyState.value.unavailable)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertEquals(0, saved.subscriptionCount.value)
        viewModel.hideHistory()
        viewModel.showHistory()
        settle()
        assertFalse(viewModel.historyState.value.visible)
        coVerify(exactly = 1) { repository.load() }
    }

    @Test
    fun unknownSourceAndRawReadFailuresUseSafeNonEmptyStates() {
        scope = null
        viewModel.showHistory()
        settle()
        assertTrue(viewModel.historyState.value.unavailable)
        coVerify(exactly = 0) { repository.load() }
        viewModel.hideHistory()
        scope = baseScope
        coEvery { repository.load() } throws IOException("secret-private-command")
        viewModel.showHistory()
        settle()
        assertTrue(viewModel.historyState.value.storageError)
        assertFalse(viewModel.historyState.value.loading)
        assertFalse(viewModel.historyState.value.toString().contains("secret-private-command"))
    }

    @Test
    fun repositoryErrorsAndUnknownTaskOutcomesArePreservedWithoutInventingExitCodes() {
        saved.value = TerminalTaskHistoryState(
            loaded = true,
            records = listOf(record("unknown").copy(status = TerminalTaskStatus.UNKNOWN)),
            error = "private-diagnostic"
        )
        viewModel.showHistory()
        settle()
        assertTrue(viewModel.historyState.value.storageError)
        assertFalse(viewModel.historyState.value.loading)
        assertFalse(viewModel.historyState.value.toString().contains("private-diagnostic"))
        viewModel.selectHistory("unknown")
        assertEquals(TerminalTaskStatus.UNKNOWN, viewModel.historyState.value.selectedRecord?.status)
        assertNull(viewModel.historyState.value.selectedRecord?.exitCode)
    }

    @Test
    fun profileFlowFailureIsDisplayedWithoutAnUncaughtCollectorFailure() {
        viewModel.hideHistory()
        every { containerSettings.activeProfileFlow } returns flow { throw IOException("private-profile") }
        viewModel.showHistory()
        settle()
        assertTrue(viewModel.historyState.value.storageError)
        assertFalse(viewModel.historyState.value.toString().contains("private-profile"))
    }

    @Test
    fun lateProfileFlowFailureCancelsTheOldRepositoryCollectorAndDoesNotRestoreOutput() {
        val profileFailure = CompletableDeferred<Unit>()
        every { containerSettings.activeProfileFlow } returns flow {
            emit(profile.value)
            profileFailure.await()
            throw IOException("private-late-profile-failure")
        }
        saved.value = saved.value.copy(records = listOf(record("first")))
        viewModel.showHistory()
        settle()
        viewModel.selectHistory("first")
        assertNotNull(viewModel.historyState.value.selectedRecord)
        assertEquals(1, saved.subscriptionCount.value)
        profileFailure.complete(Unit)
        settle()
        assertTrue(viewModel.historyState.value.storageError)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertNull(viewModel.historyState.value.selectedRecord)
        saved.value = saved.value.copy(records = listOf(record("late-private-output")))
        settle()
        assertTrue(viewModel.historyState.value.storageError)
        assertTrue(viewModel.historyState.value.records.isEmpty())
        assertNull(viewModel.historyState.value.selectedRecord)
        assertEquals(0, saved.subscriptionCount.value)
        assertFalse(viewModel.historyState.value.toString().contains("private-late-profile-failure"))
    }

    private fun settle() = dispatcher.scheduler.runCurrent()

    companion object {
        private val baseScope = TerminalTaskScope(
            ContainerProfile.BUILTIN_ID, "a".repeat(64), "/rootfs", "rootfs-directory", "/first", "first-directory"
        )

        private fun record(id: String, session: String = "session", scope: TerminalTaskScope = baseScope) =
            TerminalTaskRecord(id, "term-$id", "Task $id", session, scope, 1, 2, output = "Saved $id output")
    }
}
