package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SessionSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private val vm get() = fixture.viewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.sessionRows[OTHER] = row(OTHER)
        fixture.sessionRows[FOREIGN] = row(FOREIGN, "/other")
        coEvery { fixture.sessions.getFirstSessionOfWorkspace(any()) } coAnswers {
            val workspace = firstArg<String>()
            fixture.sessionRows.values.firstOrNull { it.workspacePath == workspace && it.parentId == null }
        }
        coEvery { fixture.sessions.getEmptySessionOfWorkspace(any()) } returns null
        every { fixture.sessions.newSessionEntity(any(), any(), any(), any()) } answers {
            row("created-${fixture.sessionRows.size}", firstArg())
        }
        coEvery { fixture.sessions.upsertSession(any()) } coAnswers {
            val entity = firstArg<ChatSessionEntity>()
            fixture.sessionRows[entity.id] = entity
        }
    }

    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            unmockkObject(TaskKeepaliveController)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun recentModeRestoresLastSelectedRatherThanMostRecentlyUpdatedSession() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun savedRunningSessionWinsEvenWhenStartupModeIsNew() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        coEvery { fixture.generalSettings.startupSessionMode() } returns StartupSessionMode.NEW_SESSION
        val running = Job()
        fixture.jobs[OTHER] = running
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertSame(running, fixture.jobs[OTHER])
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
        running.cancel()
    }

    @Test
    fun idleSavedSessionDoesNotOverrideNewSessionPreference() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        coEvery { fixture.generalSettings.startupSessionMode() } returns StartupSessionMode.NEW_SESSION
        coEvery { fixture.sessions.isSessionEmpty(PARENT) } returns false
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        val selected = requireNotNull(vm.currentSessionId.value)
        assertTrue(selected.startsWith("created-"))
        assertEquals(selected, fixture.savedSelections[WORKSPACE])
        coVerify(exactly = 1) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun newModeReusesAnEmptySessionInsteadOfCreatingAnotherOnReopen() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        coEvery { fixture.generalSettings.startupSessionMode() } returns StartupSessionMode.NEW_SESSION
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(PARENT, vm.currentSessionId.value)
        fixture.closeUi()
        fixture.reopenUi().setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(PARENT, vm.currentSessionId.value)
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun newModeReusesOlderEmptySessionWhenLatestSessionContainsMessages() = runTest(dispatcher) {
        coEvery { fixture.generalSettings.startupSessionMode() } returns StartupSessionMode.NEW_SESSION
        coEvery { fixture.sessions.isSessionEmpty(PARENT) } returns false
        coEvery { fixture.sessions.getEmptySessionOfWorkspace(WORKSPACE) } returns fixture.sessionRows.getValue(OTHER)
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun deletedSavedSessionFallsBackToValidRecentSession() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = "deleted"
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(PARENT, vm.currentSessionId.value)
        assertEquals(PARENT, fixture.savedSelections[WORKSPACE])
    }

    @Test
    fun savedSessionFromAnotherWorkspaceIsNeverRestored() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = FOREIGN
        val running = Job()
        fixture.jobs[FOREIGN] = running
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(PARENT, vm.currentSessionId.value)
        assertEquals(PARENT, fixture.savedSelections[WORKSPACE])
        running.cancel()
    }

    @Test
    fun explicitSelectionBeforeStartupCollectorRunsIsNotOverwritten() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = PARENT
        vm.setWorkspace(WORKSPACE)
        vm.selectSession(OTHER)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
    }

    @Test
    fun explicitSelectionWhileSavedSelectionIsLoadingWins() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.selectionStore.load(WORKSPACE) } coAnswers {
            entered.complete(Unit)
            release.await()
            PARENT
        }
        vm.setWorkspace(WORKSPACE)
        entered.await()
        vm.selectSession(OTHER)
        release.complete(Unit)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun sameSessionAbaSelectionStillFencesAnOlderStartupLoad() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.selectionStore.load(WORKSPACE) } coAnswers {
            entered.complete(Unit)
            release.await()
            PARENT
        }
        vm.setWorkspace(WORKSPACE)
        vm.selectSession(OTHER)
        entered.await()
        vm.selectSession(CHILD)
        vm.selectSession(OTHER)
        release.complete(Unit)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
    }

    @Test
    fun switchingWorkspaceClearsOldSelectionAndRestoresEachWorkspacesOwnChoice() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        fixture.savedSelections["/other"] = FOREIGN
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        vm.setWorkspace("/other")
        assertNull(vm.currentSessionId.value)
        runCurrent()
        assertEquals(FOREIGN, vm.currentSessionId.value)
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertEquals(FOREIGN, fixture.savedSelections["/other"])
        coVerify(exactly = 0) { fixture.selectionStore.save(OTHER, "/other") }
        coVerify(exactly = 0) { fixture.selectionStore.save(FOREIGN, WORKSPACE) }
    }

    @Test
    fun selectingAndSwitchingWorkspaceInTheSameTurnSavesTheOriginalWorkspace() = runTest(dispatcher) {
        fixture.savedSelections["/other"] = FOREIGN
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        vm.selectSession(OTHER)
        vm.setWorkspace("/other")
        runCurrent()
        assertEquals(OTHER, fixture.savedSelections[WORKSPACE])
        assertEquals(FOREIGN, fixture.savedSelections["/other"])
        assertEquals(FOREIGN, vm.currentSessionId.value)
        coVerify(exactly = 0) { fixture.selectionStore.save(OTHER, "/other") }
    }

    @Test
    fun switchingWorkspaceCancelsPendingRestoreWithoutCreatingOldWorkspaceSessions() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val oldLoad = CompletableDeferred<Unit>()
        coEvery { fixture.selectionStore.load(WORKSPACE) } coAnswers {
            entered.complete(Unit)
            oldLoad.await()
            OTHER
        }
        fixture.savedSelections["/other"] = FOREIGN
        vm.setWorkspace(WORKSPACE)
        entered.await()
        vm.setWorkspace("/other")
        runCurrent()
        oldLoad.complete(Unit)
        runCurrent()
        assertEquals(FOREIGN, vm.currentSessionId.value)
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
        coVerify(exactly = 0) { fixture.selectionStore.save(OTHER, "/other") }
    }

    @Test
    fun nonexistentExternalIdsAreNotPersistedAsLocalChatSelections() = runTest(dispatcher) {
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        vm.selectSession("external-provider-id")
        runCurrent()
        assertEquals(PARENT, fixture.savedSelections[WORKSPACE])
        coVerify(exactly = 0) { fixture.selectionStore.save("external-provider-id", any()) }
    }

    @Test
    fun deletingSelectedSessionPersistsTheDeletionFallback() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        vm.deleteSession(OTHER).join()
        runCurrent()
        assertFalse(OTHER in fixture.sessionRows)
        assertEquals(PARENT, vm.currentSessionId.value)
        assertEquals(PARENT, fixture.savedSelections[WORKSPACE])
    }

    @Test
    fun deletingLastSessionCreatesAndPersistsOnlyOneReplacement() = runTest(dispatcher) {
        fixture.sessionRows.keys.retainAll(setOf(PARENT))
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        vm.deleteSession(PARENT).join()
        runCurrent()
        val selected = requireNotNull(vm.currentSessionId.value)
        assertTrue(selected.startsWith("created-"))
        assertEquals(selected, fixture.savedSelections[WORKSPACE])
        assertEquals(setOf(selected), fixture.sessionRows.keys)
        coVerify(exactly = 1) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun recreationReturnsToRealRunningBodyWithoutManualSelectionOrASecondExecution() = runTest(dispatcher, timeout = 15.seconds) {
        fixture.savedSelections[WORKSPACE] = OTHER
        coEvery { fixture.generalSettings.startupSessionMode() } returns StartupSessionMode.NEW_SESSION
        vm.setWorkspace(WORKSPACE)
        vm.selectSession(OTHER)
        runCurrent()
        val started = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.AssistantDelta("still running"))
            started.complete(Unit)
            awaitCancellation()
        }
        val original = vm
        val run = original.executeAgentRequestStream("run once", targetSessionId = OTHER)
        started.await()
        runCurrent()
        fixture.closeUi()
        fixture.reopenUi().setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertSame(run, fixture.jobs[OTHER])
        assertTrue(run.isActive)
        assertEquals("still running", fixture.manager.streamingTexts.value[OTHER])
        verify(exactly = 1) { fixture.workflow.executeEvents("run once", any(), any()) }
        coVerify(exactly = 0) { fixture.sessions.upsertSession(any()) }
        run.cancel()
        run.join()
    }

    @Test
    fun simultaneousPageStartupAndNewSessionReuseTheSingleCreatedEmptySession() = runTest(dispatcher) {
        fixture.sessionRows.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.sessions.getEmptySessionOfWorkspace(WORKSPACE) } coAnswers {
            fixture.sessionRows.values.firstOrNull { it.workspacePath == WORKSPACE }
        }
        coEvery { fixture.dependencies.defaultModelSettingsRepository.getDefaultProviderId() } coAnswers {
            entered.complete(Unit)
            release.await()
            "provider"
        }
        vm.setWorkspace(WORKSPACE)
        entered.await()
        val create = vm.newSession()
        runCurrent()
        release.complete(Unit)
        create.join()
        runCurrent()
        assertEquals(1, fixture.sessionRows.size)
        assertEquals(fixture.sessionRows.keys.single(), vm.currentSessionId.value)
        coVerify(exactly = 1) { fixture.sessions.upsertSession(any()) }
    }

    @Test
    fun idleSelectionRestoreDoesNotDispatchRecoveryOrLlmOrApproveAPlan() = runTest(dispatcher) {
        fixture.savedSelections[WORKSPACE] = OTHER
        vm.setWorkspace(WORKSPACE)
        runCurrent()
        assertEquals(OTHER, vm.currentSessionId.value)
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.claim(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.clearExplicitHold(any()) }
        assertTrue(vm.queuedRequests.value.isEmpty())
    }

    private fun row(id: String, workspace: String = WORKSPACE) = ChatSessionEntity(
        id, id, 1, 2, workspacePath = workspace, providerId = "provider", model = "model"
    )

    private companion object {
        const val WORKSPACE = "/work"
        const val OTHER = "last-chosen"
        const val FOREIGN = "foreign"
    }
}
