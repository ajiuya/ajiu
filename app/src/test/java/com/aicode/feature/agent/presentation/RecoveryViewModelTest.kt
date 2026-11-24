package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.dao.CallSummary
import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ModelInputSnapshot
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequest
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryRun
import com.aicode.feature.agent.domain.recovery.RecoveryScope
import com.aicode.feature.agent.domain.recovery.RecoveryStatus
import com.aicode.feature.agent.domain.recovery.RecoveryToolCall
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
class RecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ledger = RecoveryDaoFixture()
    private val backendRuns = Channel<BackendRun>(Channel.UNLIMITED)
    private val backendCalls = CopyOnWriteArrayList<BackendRun>()
    private val environmentChanges = MutableSharedFlow<Unit>(replay = 1)
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private lateinit var repository: RecoveryRepository
    private lateinit var context: Context
    private val vm get() = fixture.viewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        context = ApplicationProvider.getApplicationContext()
        openViewModel()
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
    fun restartedRunningRequestShowsDraftCardWithoutStartingWorkflowOrRewritingMessages() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("interrupted", draft = "Unverified partial response")
        val original = ledger.messages.toMap()

        val bubble = snapshot(interrupted.request.id)
        val detail = repository.observePending(PARENT).first().single()
        runCurrent()

        assertEquals("Request interrupted", detail.payload.request)
        assertEquals("Unverified partial response", detail.draft?.text)
        assertTrue(bubble.items.single().environmentMatches)
        assertEquals("INTERRUPTED", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals("INTERRUPTED", ledger.runs.getValue(interrupted.run.id).status)
        assertEquals(original, ledger.messages)
        assertEquals(setOf(interrupted.run.id), ledger.runs.keys)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(vm.queuedRequests.value.isEmpty())
        assertTrue(backendCalls.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun restartedQueuedRequestStaysInCardUntilExplicitConfirmation() = runTest(dispatcher, timeout = 15.seconds) {
        val request = seedQueued("queued")

        val bubble = snapshot(request.id)
        assertTrue(bubble.items.single().environmentMatches)
        runCurrent()
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(request.id).status)
        assertTrue(ledger.runs.isEmpty())
        assertTrue(ledger.messages.isEmpty())
        assertTrue(vm.queuedRequests.value.isEmpty())
        assertTrue(backendCalls.isEmpty())

        vm.continueRecovery(bubble)
        val run = backendRuns.receive()
        assertEquals(request.inputMessageId, run.context.inputMessageId)
        assertEquals("Request queued", ledger.messages.getValue(request.inputMessageId).content)
        assertEquals(setOf(request.inputMessageId), ledger.messages.keys)
        assertNotNull(run.context.recoveryRunId)
        val hidden = vm.recoveryBubble.first { it.scope == bubble.scope && it.items.isEmpty() }
        assertTrue(hidden.batch.isEmpty())
        assertTrue(hidden.continueTargets.isEmpty())
        assertFalse(hidden.busy)
        assertEquals("RUNNING", ledger.requests.getValue(request.id).status)
        assertTrue(run.owner.isActive)
        complete(run)
        assertEquals("COMPLETED", ledger.requests.getValue(request.id).status)
        assertEquals(1, backendCalls.size)
    }

    @Test
    fun notificationBodySurvivesAcknowledgedInputBeforeModelSnapshotIsFrozen() = runTest(dispatcher, timeout = 15.seconds) {
        val notificationText = "[Saved task notification] child interrupted; inspect current files"
        val previous = RecoveryRepository(ledger.dao)
        val request = previous.enqueue(PARENT, RecoveryRequestPayload("Handle saved notifications", WORKSPACE,
            modelRequest = notificationText, origin = com.aicode.feature.agent.domain.recovery.RecoveryOrigin.NOTIFICATION,
            notificationSeqs = listOf(7)), Json.encodeToString(fixture.projectIdentity), requestId = "notice", inputMessageId = "notice-input")
        assertTrue(previous.awaitConfirmation(request.id))
        vm.continueRecovery(snapshot(request.id))
        val first = backendRuns.receive()
        assertEquals(notificationText, ledger.messages.getValue(request.inputMessageId).content)
        assertNull(ledger.messages.getValue(request.inputMessageId).modelInputSnapshotJson)
        assertTrue(7L in ledger.deliveredNotificationSeqs)
        first.owner.cancel()
        first.owner.join()
        fixture.closeUi()
        fixture.closeProcessOwner().join()
        openViewModel()
        vm.continueRecovery(snapshot(request.id))
        val resumed = backendRuns.receive()
        assertTrue(resumed.context.history.filterIsInstance<AgentMessage.UserMessage>().any {
            it.id == request.inputMessageId && it.content == notificationText
        })
        assertFalse(resumed.request.contains("Handle saved notifications"))
        complete(resumed)
    }

    @Test
    fun staleRecoveryCardAbandonDoesNotStopTheConfirmedWorkflow() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("late-abandon")
        val bubble = snapshot(interrupted.request.id)
        vm.continueRecovery(bubble)
        val run = backendRuns.receive()
        val runId = requireNotNull(run.context.recoveryRunId)
        val writes = ledger.writes.toList()

        vm.abandonRecovery(bubble)
        runCurrent()

        assertEquals(writes, ledger.writes)
        assertTrue(repository.isCurrent(runId))
        assertSame(run.owner, fixture.jobs[PARENT])
        assertEquals(1, backendCalls.size)
        complete(run)
        assertEquals("COMPLETED", ledger.requests.getValue(interrupted.request.id).status)
    }

    @Test
    fun pendingCardAbandonIsSessionScopedAndDoesNotExecuteAnything() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("abandon")
        val bubble = snapshot(interrupted.request.id)
        vm.selectSession(OTHER)
        vm.abandonRecovery(bubble)
        runCurrent()
        assertEquals("INTERRUPTED", ledger.requests.getValue(interrupted.request.id).status)
        vm.selectSession(PARENT)
        vm.abandonRecovery(snapshot(interrupted.request.id))
        runCurrent()

        assertEquals("ABANDONED", ledger.requests.getValue(interrupted.request.id).status)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(backendCalls.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun confirmationInDifferentProfileRefusesWorkflowAndDoesNotClearPersistentHold() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("profile")
        assertTrue(repository.holdSession(PARENT, "process_interrupted"))
        val matching = snapshot(interrupted.request.id)
        assertTrue(matching.items.single().environmentMatches)
        val otherProfile = fixture.projectIdentity.copy(profileId = "different-profile")
        coEvery { fixture.projectIdentityResolver.capture(any(), any(), any()) } returns ProjectMemoryCapture(otherProfile) {}
        environmentChanges.emit(Unit)
        val before = ledger.messages.toMap()
        val mismatched = vm.recoveryBubble.first { bubble ->
            bubble.scope == matching.scope && bubble.items.singleOrNull()?.environmentMatches == false
        }
        assertEquals(matching.items.single().target, mismatched.items.single().target)

        vm.continueRecovery(mismatched)
        vm.continueRecovery(matching)
        runCurrent()
        vm.recovering.first { !it }

        assertEquals("process_interrupted", ledger.sessions.getValue(PARENT).autoDispatchBlockedReason)
        assertEquals(setOf(interrupted.run.id), ledger.runs.keys)
        assertEquals(before, ledger.messages)
        assertTrue(backendCalls.isEmpty())
        assertTrue(fixture.jobs.isEmpty())
        assertFalse(vm.recovering.value)
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun repeatedConfirmationsCreateOnlyOneNewRunAcrossPreparationExecutionAndCompletion() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("duplicate")
        val bubble = snapshot(interrupted.request.id)
        val loadingAttachments = CompletableDeferred<Unit>()
        val releaseAttachments = CompletableDeferred<Unit>()
        coEvery { fixture.persistence.loadAttachmentImages(any()) } coAnswers {
            loadingAttachments.complete(Unit)
            releaseAttachments.await()
            emptyList()
        }

        vm.continueRecovery(bubble)
        vm.continueRecovery(bubble)
        loadingAttachments.await()
        runCurrent()
        assertTrue(vm.recovering.value)
        val preparing = vm.recoveryBubble.first { it.scope == bubble.scope && it.busy }
        assertEquals(bubble.items, preparing.items)
        assertEquals(bubble.continueTargets, preparing.continueTargets)
        vm.abandonRecovery(preparing)
        vm.continueRecovery(bubble)
        runCurrent()
        assertEquals(2, ledger.runs.size)
        assertTrue(backendCalls.isEmpty())

        releaseAttachments.complete(Unit)
        val run = backendRuns.receive()
        val runId = requireNotNull(run.context.recoveryRunId)
        assertNotEquals(interrupted.run.id, runId)
        assertEquals(interrupted.request.id, ledger.runs.getValue(runId).requestId)
        assertSame(run.owner, fixture.jobs[PARENT])
        vm.continueRecovery(bubble)
        runCurrent()
        assertEquals(2, ledger.runs.size)
        assertEquals(1, backendCalls.size)
        complete(run)
        vm.continueRecovery(bubble)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals(2, ledger.runs.size)
        assertEquals(1, backendCalls.size)
        assertFalse(vm.recovering.value)
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun loadingAndStreamingHideOnlyTheirSessionWithoutDisablingIdleChildDuringPreparation() = runTest(dispatcher, timeout = 15.seconds) {
        val parent = seedPending("preparing-parent")
        val parentLater = seedPending("preparing-parent-later")
        val initial = snapshot(parent.id, parentLater.id)
        val loadingAttachments = CompletableDeferred<Unit>()
        val releaseAttachments = CompletableDeferred<Unit>()
        coEvery { fixture.persistence.loadAttachmentImages(any()) } coAnswers {
            loadingAttachments.complete(Unit)
            releaseAttachments.await()
            emptyList()
        }
        vm.continueRecovery(initial)
        loadingAttachments.await()
        val child = seedPending("idle-child", CHILD)
        val preparing = snapshot(parentLater.id, child.id)
        assertTrue(preparing.busy)
        assertTrue(vm.recovering.value)
        val stored = ledger.requests.toMap()
        val writes = ledger.writes.toList()

        for (state in listOf(AgentUIState.Loading, AgentUIState.Streaming)) {
            fixture.agentStates.value += PARENT to state
            runCurrent()
            val visible = vm.recoveryBubble.first { bubble ->
                bubble.scope == initial.scope && bubble.items.map { it.target.requestId } == listOf(child.id)
            }
            assertEquals(1, visible.items.size)
            assertEquals(listOf(child.id), visible.batch.map { it.target.requestId })
            assertEquals(visible.batch.map { it.target }, visible.continueTargets)
            assertEquals(setOf(PARENT), visible.runningSessions)
            assertFalse(visible.busy)
            assertTrue(vm.recovering.value)
            assertEquals(stored, ledger.requests)
            assertEquals(writes, ledger.writes)
        }

        fixture.agentStates.value += PARENT to AgentUIState.Error("Preparation is still pending")
        runCurrent()
        val visibleAgain = vm.recoveryBubble.first { bubble ->
            bubble.scope == initial.scope && bubble.items == preparing.items
        }
        assertTrue(visibleAgain.busy)
        assertTrue(visibleAgain.runningSessions.isEmpty())
        releaseAttachments.complete(Unit)
        val run = backendRuns.receive()
        assertRecoveryRun(parent, run)
        val childOnly = snapshot(child.id)
        assertFalse(childOnly.busy)
        assertEquals(childOnly.batch.map { it.target }, childOnly.continueTargets)
        complete(run)
        val idle = snapshot(parentLater.id, child.id)
        assertFalse(idle.busy)
        assertTrue(idle.runningSessions.isEmpty())
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parentLater.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(child.id).status)
        assertEquals(1, backendCalls.size)
    }

    @Test
    fun failedPreparationRestoresTheClaimedRequestAndItsLaterPendingItem() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("handoff-failed")
        val later = seedPending("handoff-later")
        val bubble = snapshot(interrupted.request.id, later.id)
        val loadingAttachments = CompletableDeferred<Unit>()
        val releaseAttachments = CompletableDeferred<Unit>()
        coEvery { fixture.persistence.loadAttachmentImages(any()) } coAnswers {
            loadingAttachments.complete(Unit)
            releaseAttachments.await()
            throw IllegalStateException("Attachment unavailable")
        }

        vm.continueRecovery(bubble)
        loadingAttachments.await()
        val claimed = requireNotNull(ledger.requests.getValue(interrupted.request.id).activeRunId)
        assertNotEquals(interrupted.run.id, claimed)
        val preparing = vm.recoveryBubble.first { it.scope == bubble.scope && it.busy }
        assertEquals(bubble.items, preparing.items)
        assertTrue(backendCalls.isEmpty())
        val pendingOnly = snapshot(later.id)
        assertTrue(pendingOnly.busy)
        assertEquals("RUNNING", ledger.requests.getValue(interrupted.request.id).status)

        releaseAttachments.complete(Unit)
        vm.recovering.first { !it }
        ledger.refreshPendingScopeRows()
        val restored = vm.recoveryBubble.first { current ->
            current.scope == bubble.scope && !current.busy &&
                current.items.map { it.target.requestId } == listOf(interrupted.request.id, later.id) &&
                current.items.first().target.activeRunId == claimed
        }
        assertEquals("INTERRUPTED", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals("INTERRUPTED", ledger.runs.getValue(claimed).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(later.id).status)
        assertEquals(listOf(interrupted.request.id), restored.batch.map { it.target.requestId })
        assertEquals(restored.batch.map { it.target }, restored.continueTargets)
        assertTrue(restored.runningSessions.isEmpty())
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(backendCalls.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun failedConfirmedRunRestoresItsPendingItemsWithoutStartingTheLaterRequest() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("failed-confirmed")
        val later = seedPending("after-failure")
        vm.continueRecovery(snapshot(interrupted.request.id, later.id))
        val run = backendRuns.receive()
        val activeRunId = requireNotNull(run.context.recoveryRunId)
        val hidden = snapshot()
        assertEquals(setOf(PARENT), hidden.runningSessions)
        assertEquals("RUNNING", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(later.id).status)

        run.send(AgentEvent.Failed("Network unavailable"))
        run.events.close()
        run.owner.join()
        runCurrent()
        val restored = snapshot(interrupted.request.id, later.id)
        assertFalse(restored.busy)
        assertTrue(restored.runningSessions.isEmpty())
        assertEquals(activeRunId, restored.items.first().target.activeRunId)
        assertEquals(listOf(interrupted.request.id), restored.batch.map { it.target.requestId })
        assertEquals(restored.batch.map { it.target }, restored.continueTargets)
        assertEquals("FAILED", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(later.id).status)
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        assertEquals(1, backendCalls.size)
        assertTrue(fixture.jobs.isEmpty())
    }

    @Test
    fun confirmedRunPreservesOriginalUserIdContentTimestampAndFrozenInputSnapshot() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("stable-input", freezeInput = true)
        val original = ledger.messages.getValue(interrupted.request.inputMessageId)
        val originalWrites = ledger.writes.count { it == "message:${original.id}" }

        vm.continueRecovery(snapshot(interrupted.request.id))
        val run = backendRuns.receive()
        val runId = requireNotNull(run.context.recoveryRunId)
        val resumeId = "resume_$runId"

        assertEquals(original, ledger.messages[original.id])
        assertEquals(originalWrites, ledger.writes.count { it == "message:${original.id}" })
        assertEquals(original.id, repository.request(interrupted.request.id)?.inputMessageId)
        assertEquals(resumeId, run.context.inputMessageId)
        assertEquals(PARENT, ledger.messages.getValue(resumeId).sessionId)
        assertEquals(MessageRole.USER.name, ledger.messages.getValue(resumeId).role)
        assertNotNull(original.modelInputSnapshotJson)
        assertTrue(run.context.persistInputSnapshot)
        assertFalse(run.context.transientInput)
        assertTrue(run.request.contains("Do not replay previous tool calls"))
        assertFalse(run.request.contains("Request stable-input"))
        assertTrue(run.context.requireFreshFileRead)
        assertTrue(run.request.contains("Current Todo state"))
        assertEquals(original.id, ledger.messages.getValue(resumeId).continuationOfMessageId)
        complete(run)
        assertEquals(original, ledger.messages[original.id])
        assertEquals(setOf(original.id, resumeId), ledger.messages.keys)
    }

    @Test
    fun interruptedRunWithoutCommittedUserReusesStableInputIdInsteadOfCreatingResumeInput() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("before-input", commitInput = false)

        vm.continueRecovery(snapshot(interrupted.request.id))
        val run = backendRuns.receive()

        assertEquals(interrupted.request.inputMessageId, run.context.inputMessageId)
        assertEquals("Request before-input", run.request)
        assertEquals(setOf(interrupted.request.inputMessageId), ledger.messages.keys)
        assertEquals("Request before-input", ledger.messages.getValue(interrupted.request.inputMessageId).content)
        assertNotEquals(interrupted.run.id, run.context.recoveryRunId)
        complete(run)
        assertEquals(1, ledger.writes.count { it == "message:${interrupted.request.inputMessageId}" })
    }

    @Test
    fun unknownAndUnexecutedCallsGetPairedErrorResultsBeforeWorkflowWithoutReexecutingTools() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("tools", toolCalls = true)
        val tool = mockk<AgentTool>(relaxed = true)
        every { tool.name } returns "Bash"
        every { fixture.tools.getAvailableTools() } returns listOf(tool)

        val bubble = snapshot(interrupted.request.id)
        assertEquals(setOf(RecoveryToolCall("unknown-call", "Bash")), bubble.items.single().target.unknownTools)
        val writes = ledger.writes.toList()
        vm.continueRecovery(bubble, unknownToolsVerified = false)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals(writes, ledger.writes)
        assertTrue(backendCalls.isEmpty())
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }

        vm.continueRecovery(bubble, unknownToolsVerified = true)
        val run = backendRuns.receive()
        val expected = mapOf("unknown-call" to "RESULT_UNKNOWN", "waiting-call" to "NOT_EXECUTED", "declared-call" to "NOT_EXECUTED")
        assertEquals(listOf(tool), run.tools)

        expected.forEach { (callId, code) ->
            val result = ledger.messages.getValue("tool_$callId")
            assertEquals(PARENT, result.sessionId)
            assertEquals(MessageRole.TOOL.name, result.role)
            assertEquals(callId, result.toolCallId)
            assertEquals("Bash", result.toolName)
            assertTrue(result.isError)
            val transport = Json.parseToJsonElement(result.content).jsonObject
            assertEquals("error", transport.getValue("status").jsonPrimitive.content)
            assertEquals(code, transport.getValue("code").jsonPrimitive.content)
            val savedTool = ledger.tools.getValue(interrupted.run.id to callId)
            assertEquals("FINISHED", savedTool.status)
            assertEquals(result.id, savedTool.resultMessageId)
            assertEquals("assistant-tools", savedTool.assistantMessageId)
        }
        val paired = run.context.history.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(expected.keys, paired.map { it.id }.toSet())
        assertEquals(expected.keys, run.context.history.filterIsInstance<AgentMessage.AssistantMessage>()
            .single().toolCalls.map { it.id }.toSet())
        coVerify(exactly = 0) { tool.execute(any()) }
        verify(exactly = 0) { fixture.tools.getTool(any()) }
        complete(run)
        assertEquals("COMPLETED", ledger.requests.getValue(interrupted.request.id).status)
        coVerify(exactly = 0) { tool.execute(any()) }
    }

    @Test
    fun stoppingActiveGoalRunPersistsInterruptionAndCannotStartGoalContinuation() = runTest(dispatcher, timeout = 15.seconds) {
        activateGoal()
        val owner = vm.executeAgentRequestStream("Stop this goal run", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val run = backendRuns.receive()
        assertSame(owner, run.owner)
        assertNotNull(run.context.recoveryRunId)
        assertEquals(GoalStatus.ACTIVE, run.context.goalStatus)

        vm.stopAgentSession(PARENT)
        owner.join()
        runCurrent()

        assertTrue(owner.isCancelled)
        assertEquals("INTERRUPTED", ledger.runs.getValue(requireNotNull(run.context.recoveryRunId)).status)
        assertEquals(GoalStatus.PAUSED.name, fixture.sessionRows.getValue(PARENT).goalStatus)
        assertFalse(PARENT in vm.completedSessions.value)
        assertEquals(1, ledger.requests.size)
        assertEquals(1, backendCalls.size)
        assertTrue(fixture.jobs.isEmpty())
        coVerify(exactly = 1) { fixture.sessions.updateGoalStatus(PARENT, GoalStatus.PAUSED) }
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun failedGoalRunDoesNotContinueEvenIfCompletedArrivesAfterFailure() = runTest(dispatcher, timeout = 15.seconds) {
        activateGoal()
        vm.executeAgentRequestStream("Fail this goal run", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val run = backendRuns.receive()
        run.send(AgentEvent.Failed("Network unavailable"))
        run.send(AgentEvent.Completed)
        run.events.close()
        run.owner.join()
        runCurrent()

        assertEquals("FAILED", ledger.requests.values.single().status)
        assertEquals("FAILED", ledger.runs.getValue(requireNotNull(run.context.recoveryRunId)).status)
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        assertEquals(GoalStatus.BLOCKED.name, fixture.sessionRows.getValue(PARENT).goalStatus)
        coVerify(exactly = 1) { fixture.sessions.updateGoalStatus(PARENT, GoalStatus.BLOCKED) }
        assertFalse(PARENT in vm.completedSessions.value)
        assertEquals(1, backendCalls.size)
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun rejectedPlanKeepsPersistentHoldAcrossRestartAndCardConfirmationCannotRestoreDispatch() = runTest(dispatcher, timeout = 15.seconds) {
        activateGoal()
        vm.executeAgentRequestStream("Plan this task", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val rejected = backendRuns.receive()
        vm.enqueueAgentRequest("Request queued before rejection", projectRoot = WORKSPACE, targetSessionId = PARENT)
        runCurrent()
        val queuedId = vm.queuedRequests.value.single().recoveryRequestId!!
        rejected.send(AgentEvent.PlanRejected)
        rejected.events.close()
        rejected.owner.join()
        runCurrent()

        assertEquals("PLAN_REJECTED", ledger.sessions.getValue(PARENT).autoDispatchBlockedReason)
        assertEquals(listOf(queuedId), vm.queuedRequests.value.map { it.recoveryRequestId })
        assertEquals(1, backendCalls.size)
        fixture.closeUi()
        fixture.closeProcessOwner().join()
        openViewModel()

        val bubble = snapshot(queuedId)
        assertEquals("PLAN_REJECTED", repository.observePending(PARENT).first().single { it.request.id == queuedId }.holdReason)
        assertFalse(bubble.items.single().environmentMatches)
        vm.continueRecovery(bubble)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals("PLAN_REJECTED", ledger.sessions.getValue(PARENT).autoDispatchBlockedReason)
        assertEquals(1, backendCalls.size)
        assertEquals(1, ledger.runs.size)
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun queuedRequestHeldByRejectedPlanCannotLeaveStreamingAfterNewExplicitTurnCompletes() = runTest(dispatcher, timeout = 15.seconds) {
        vm.executeAgentRequestStream("Plan this task", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val rejected = backendRuns.receive()
        vm.enqueueAgentRequest("Queued before rejection", projectRoot = WORKSPACE, targetSessionId = PARENT)
        runCurrent()
        val queuedId = vm.queuedRequests.value.single().recoveryRequestId!!
        rejected.send(AgentEvent.PlanRejected)
        rejected.events.close()
        rejected.owner.join()
        runCurrent()
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(queuedId).status)

        vm.enqueueAgentRequest("New explicit scope", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val explicit = backendRuns.receive()
        complete(explicit)
        fixture.jobs[PARENT]?.join()
        runCurrent()

        assertEquals(2, backendCalls.size)
        assertEquals("New explicit scope", explicit.request)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(queuedId).status)
        assertTrue(vm.queuedRequests.value.isEmpty())
        assertTrue(fixture.jobs.isEmpty())
        assertEquals(AgentUIState.Idle, vm.agentStates.value[PARENT])
        assertTrue(snapshot(queuedId).items.single().environmentMatches)
        assertFalse(vm.recovering.value)
        assertNull(ledger.messages[ledger.requests.getValue(queuedId).inputMessageId])
        verify(exactly = 2) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun newExplicitRequestClearsPersistentPlanHoldWithoutResumingHistoricalRequest() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("held")
        assertTrue(repository.holdSession(PARENT, "PLAN_REJECTED"))
        val original = ledger.messages.getValue(interrupted.request.inputMessageId)
        assertFalse(snapshot(interrupted.request.id).items.single().environmentMatches)

        vm.enqueueAgentRequest("New explicit scope", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val run = backendRuns.receive()
        val newRun = ledger.runs.getValue(requireNotNull(run.context.recoveryRunId))

        assertNull(ledger.sessions.getValue(PARENT).autoDispatchBlockedReason)
        assertNotEquals(interrupted.request.id, newRun.requestId)
        assertEquals("New explicit scope", run.request)
        assertEquals(original, ledger.messages[original.id])
        assertEquals("INTERRUPTED", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals(1, backendCalls.size)
        complete(run)
        assertEquals("COMPLETED", ledger.requests.getValue(newRun.requestId).status)
        assertEquals("INTERRUPTED", ledger.requests.getValue(interrupted.request.id).status)
        assertEquals(1, backendCalls.size)
    }

    @Test
    fun sessionSwitchAndRemoteEnvironmentCannotResumeAnotherSessionsLocalRequest() = runTest(dispatcher, timeout = 15.seconds) {
        val local = seedInterrupted("local")
        val remoteIdentity = ProjectMemoryIdentity("remote_ssh", "example.invalid", 22, "tester", WORKSPACE)
        val remote = seedInterrupted("remote", sessionId = OTHER, identity = remoteIdentity)
        val originalLocal = ledger.messages.getValue(local.request.inputMessageId)
        val localBubble = snapshot(local.request.id)
        assertTrue(localBubble.items.single().environmentMatches)
        vm.selectSession(OTHER)
        val mismatched = snapshot(remote.request.id, scopeId = OTHER)
        assertFalse(mismatched.items.single().environmentMatches)
        vm.continueRecovery(localBubble)
        vm.continueRecovery(mismatched)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals(2, ledger.runs.size)
        assertTrue(backendCalls.isEmpty())
        assertNull(fixture.projectBindings.get(PARENT))
        assertNull(fixture.projectBindings.get(OTHER))

        coEvery { fixture.projectIdentityResolver.capture(any(), any(), any()) } returns
            ProjectMemoryCapture(remoteIdentity, "ssh-generation") {}
        environmentChanges.emit(Unit)
        val remoteBubble = vm.recoveryBubble.first { bubble ->
            bubble.scope == mismatched.scope && bubble.items.singleOrNull()?.environmentMatches == true
        }
        assertEquals(mismatched.items.single().target, remoteBubble.items.single().target)
        vm.continueRecovery(remoteBubble)
        val run = backendRuns.receive()
        val owned = ledger.runs.getValue(requireNotNull(run.context.recoveryRunId))

        assertEquals(OTHER, run.context.sessionId)
        assertEquals(remote.request.id, owned.requestId)
        assertEquals(remoteIdentity, run.context.projectMemoryIdentity)
        assertTrue(run.context.history.filterIsInstance<AgentMessage.UserMessage>().all { it.id != local.request.inputMessageId })
        assertEquals(originalLocal, ledger.messages[originalLocal.id])
        assertEquals("INTERRUPTED", ledger.requests.getValue(local.request.id).status)
        assertNull(fixture.projectBindings.get(PARENT))
        assertEquals(remoteIdentity, fixture.projectBindings.get(OTHER)?.identity)
        complete(run)
        assertEquals("COMPLETED", ledger.requests.getValue(remote.request.id).status)
        assertEquals("INTERRUPTED", ledger.requests.getValue(local.request.id).status)
        assertEquals(1, backendCalls.size)
    }

    @Test
    fun oneConfirmationContinuesParentAndChildWithoutStartingTheirLaterRequests() = runTest(dispatcher, timeout = 15.seconds) {
        val childFirst = seedPending("z-child-first", CHILD)
        val parentFirst = seedPending("z-parent-first")
        val childLater = seedPending("a-child-later", CHILD)
        val parentLater = seedPending("a-parent-later")
        val bubble = snapshot(parentFirst.id, parentLater.id, childFirst.id, childLater.id)
        assertTrue(bubble.items.all { it.environmentMatches })

        vm.continueRecovery(bubble)
        val started = List(2) { backendRuns.receive() }.associateBy { it.context.sessionId }
        val parentRun = started.getValue(PARENT)
        val childRun = started.getValue(CHILD)
        assertRecoveryRun(parentFirst, parentRun)
        assertRecoveryRun(childFirst, childRun)
        assertTrue(parentRun.owner.isActive)
        assertTrue(childRun.owner.isActive)
        vm.recovering.first { !it }
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parentLater.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(childLater.id).status)
        assertEquals(setOf(parentFirst.id, childFirst.id), ledger.runs.values.map { it.requestId }.toSet())
        assertEquals(2, backendCalls.size)
        val hidden = snapshot()
        assertTrue(hidden.items.isEmpty())
        assertTrue(hidden.batch.isEmpty())
        assertTrue(hidden.continueTargets.isEmpty())
        assertEquals(setOf(PARENT, CHILD), hidden.runningSessions)
        assertFalse(hidden.busy)

        complete(parentRun)
        val next = snapshot(parentLater.id)
        assertEquals(1, next.items.size)
        assertEquals(listOf(parentLater.id), next.batch.map { it.target.requestId })
        assertEquals(next.batch.map { it.target }, next.continueTargets)
        assertEquals(setOf(CHILD), next.runningSessions)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(childLater.id).status)
        assertEquals(2, backendCalls.size)
        vm.continueRecovery(next)
        val secondParentRun = backendRuns.receive()
        assertRecoveryRun(parentLater, secondParentRun)
        complete(secondParentRun)
        assertEquals("COMPLETED", ledger.requests.getValue(parentFirst.id).status)
        assertEquals("COMPLETED", ledger.requests.getValue(parentLater.id).status)
        assertEquals("RUNNING", ledger.requests.getValue(childFirst.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(childLater.id).status)
        assertEquals(3, backendCalls.size)

        complete(childRun)
        val remaining = snapshot(childLater.id)
        assertEquals(listOf(childLater.id), remaining.batch.map { it.target.requestId })
        assertEquals(remaining.batch.map { it.target }, remaining.continueTargets)
        assertTrue(remaining.runningSessions.isEmpty())
        assertFalse(remaining.busy)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(childLater.id).status)
        assertEquals(setOf(parentFirst.id, parentLater.id, childFirst.id), ledger.runs.values.map { it.requestId }.toSet())
        assertEquals(3, backendCalls.size)
    }

    @Test
    fun manualCompactionTemporarilyHidesOnlyItsSessionsPendingItems() = runTest(dispatcher, timeout = 15.seconds) {
        val parent = seedPending("compacting-parent")
        val stale = snapshot(parent.id)
        val child = seedPending("compacting-child", CHILD)
        val initial = snapshot(parent.id, child.id)
        val compacting = CompletableDeferred<Unit>()
        val releaseCompaction = CompletableDeferred<Unit>()
        coEvery { fixture.workflow.compactSession(any(), any(), any(), any()) } coAnswers {
            compacting.complete(Unit)
            releaseCompaction.await()
            true
        }
        val stored = ledger.requests.toMap()

        vm.compactCurrentSession()
        compacting.await()
        val owner = fixture.jobs.getValue(PARENT)
        val visible = snapshot(child.id)
        assertEquals(listOf(child.id), visible.batch.map { it.target.requestId })
        assertEquals(visible.batch.map { it.target }, visible.continueTargets)
        assertEquals(setOf(PARENT), visible.runningSessions)
        assertFalse(visible.busy)
        assertFalse(vm.agentStates.value[PARENT] == AgentUIState.Loading)
        assertFalse(vm.agentStates.value[PARENT] == AgentUIState.Streaming)
        assertEquals(stored, ledger.requests)
        assertTrue(backendCalls.isEmpty())
        val writes = ledger.writes.toList()
        vm.abandonRecovery(stale)
        runCurrent()
        assertEquals(writes, ledger.writes)
        assertEquals(stored, ledger.requests)
        assertTrue(owner.isActive)

        releaseCompaction.complete(Unit)
        owner.join()
        runCurrent()
        val restored = snapshot(parent.id, child.id)
        assertEquals(initial.items, restored.items)
        assertEquals(initial.continueTargets, restored.continueTargets)
        assertTrue(restored.runningSessions.isEmpty())
        assertFalse(restored.busy)
        assertEquals(stored, ledger.requests)
        assertTrue(fixture.jobs.isEmpty())
    }

    @Test
    fun runningCommandHidesItsPendingItemsAndAbandonTargetsOnlyTheVisibleChild() = runTest(dispatcher, timeout = 15.seconds) {
        val parent = seedPending("command-parent")
        val stale = snapshot(parent.id)
        val child = seedPending("command-child", CHILD)
        snapshot(parent.id, child.id)
        val loadingUsage = CompletableDeferred<Unit>()
        val releaseUsage = CompletableDeferred<Unit>()
        every { fixture.dependencies.llmCallRecordDao.getSummary(any()) } returns flow {
            loadingUsage.complete(Unit)
            releaseUsage.await()
            emit(CallSummary(0, 0, 0, 0, 0, 0, null, null))
        }
        val handler = fixture.installCommand("/usage")
        every { handler.execute(any(), any()) } answers { firstArg<SlashCommandContext>().showUsage() }

        val command = vm.executeAgentRequestStream("/usage", projectRoot = WORKSPACE, targetSessionId = PARENT)
        loadingUsage.await()
        val visible = snapshot(child.id)
        assertEquals(listOf(child.id), visible.batch.map { it.target.requestId })
        assertEquals(visible.batch.map { it.target }, visible.continueTargets)
        assertEquals(setOf(PARENT), visible.runningSessions)
        assertFalse(visible.busy)
        assertFalse(vm.agentStates.value[PARENT] == AgentUIState.Loading)
        assertFalse(vm.agentStates.value[PARENT] == AgentUIState.Streaming)
        val writes = ledger.writes.toList()
        vm.abandonRecovery(stale)
        runCurrent()
        assertEquals(writes, ledger.writes)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parent.id).status)
        vm.abandonRecovery(visible)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals("ABANDONED", ledger.requests.getValue(child.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parent.id).status)
        assertTrue(command.isActive)
        assertTrue(backendCalls.isEmpty())

        releaseUsage.complete(Unit)
        command.join()
        runCurrent()
        val restored = snapshot(parent.id)
        assertEquals(listOf(parent.id), restored.batch.map { it.target.requestId })
        assertEquals(restored.batch.map { it.target }, restored.continueTargets)
        assertTrue(restored.runningSessions.isEmpty())
        assertFalse(restored.busy)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parent.id).status)
    }

    @Test
    fun parentBubbleCanContinueOnlyItsChildWithoutStartingParent() = runTest(dispatcher, timeout = 15.seconds) {
        val child = seedPending("child-only", CHILD)
        val bubble = snapshot(child.id)
        assertEquals(PARENT, bubble.scope?.sessionId)
        assertEquals(CHILD, bubble.items.single().target.sessionId)
        assertTrue(bubble.items.single().environmentMatches)

        vm.continueRecovery(bubble)
        val run = backendRuns.receive()
        assertRecoveryRun(child, run)
        vm.recovering.first { !it }
        assertEquals(setOf(CHILD), fixture.jobs.keys)
        assertEquals(setOf(child.id), ledger.requests.keys)
        assertEquals(1, ledger.runs.size)
        assertEquals(1, backendCalls.size)
        run.owner.cancelAndJoin()
    }

    @Test
    fun abandonAffectsOnlyDisplayedParentAndChildHeadsAndPreservesRunningAndOutOfScopeRequests() = runTest(dispatcher, timeout = 15.seconds) {
        val root = fixture.sessionRows.getValue(PARENT)
        val grandchild = root.copy(id = "grandchild", parentId = CHILD)
        val foreignChild = root.copy(id = "foreign-child", parentId = PARENT, workspacePath = "/elsewhere")
        putSession(grandchild)
        putSession(foreignChild)
        vm.executeAgentRequestStream("Already running", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val running = backendRuns.receive()
        val runId = requireNotNull(running.context.recoveryRunId)
        val runningRequest = ledger.runs.getValue(runId).requestId
        val parent = seedPending("keep-pending-parent")
        val parentLater = seedPending("keep-later-parent")
        val child = seedPending("abandon-child", CHILD, fixture.projectIdentity.copy(profileId = "different-profile"))
        val childLater = seedPending("keep-later-child", CHILD)
        val other = seedPending("keep-other", OTHER)
        val nested = seedPending("keep-grandchild", grandchild.id)
        val foreign = seedPending("keep-foreign", foreignChild.id)
        val bubble = snapshot(child.id, childLater.id)
        assertEquals(2, bubble.items.size)
        assertEquals(listOf(child.id), bubble.batch.map { it.target.requestId })
        assertTrue(bubble.continueTargets.isEmpty())
        assertEquals(setOf(PARENT), bubble.runningSessions)
        assertFalse(bubble.busy)
        assertFalse(bubble.items.single { it.target.requestId == child.id }.environmentMatches)
        val unaffected = listOf(runningRequest, parent.id, parentLater.id, childLater.id, other.id, nested.id, foreign.id)
            .associateWith { ledger.requests.getValue(it) }
        val messages = ledger.messages.toMap()

        vm.abandonRecovery(bubble)
        runCurrent()
        vm.recovering.first { !it }

        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parent.id).status)
        assertEquals("ABANDONED", ledger.requests.getValue(child.id).status)
        assertEquals(unaffected, unaffected.keys.associateWith { ledger.requests.getValue(it) })
        assertEquals("RUNNING", ledger.runs.getValue(runId).status)
        assertTrue(repository.isCurrent(runId))
        assertSame(running.owner, fixture.jobs[PARENT])
        assertEquals(messages, ledger.messages)
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
        complete(running)
        val restored = snapshot(parent.id, parentLater.id, childLater.id)
        assertEquals(3, restored.items.size)
        assertEquals(listOf(parent.id, childLater.id), restored.batch.map { it.target.requestId })
        assertEquals(restored.batch.map { it.target }, restored.continueTargets)
        assertFalse(restored.busy)
    }

    @Test
    fun staleAbandonCannotConsumeAPendingHeadHiddenByANewExplicitRun() = runTest(dispatcher, timeout = 15.seconds) {
        val parent = seedPending("stale-pending-parent")
        val parentLater = seedPending("stale-pending-parent-later")
        val stale = snapshot(parent.id, parentLater.id)
        vm.executeAgentRequestStream("New explicit request", projectRoot = WORKSPACE, targetSessionId = PARENT)
        val running = backendRuns.receive()
        val runId = requireNotNull(running.context.recoveryRunId)
        val hidden = snapshot()
        assertEquals(setOf(PARENT), hidden.runningSessions)
        val writes = ledger.writes.toList()

        vm.abandonRecovery(stale)
        runCurrent()

        assertEquals(writes, ledger.writes)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parent.id).status)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(parentLater.id).status)
        assertTrue(repository.isCurrent(runId))
        assertSame(running.owner, fixture.jobs[PARENT])
        assertEquals(1, backendCalls.size)
        complete(running)
        val restored = snapshot(parent.id, parentLater.id)
        assertEquals(stale.items, restored.items)
        assertEquals(stale.continueTargets, restored.continueTargets)
        assertTrue(restored.runningSessions.isEmpty())
        assertEquals(1, backendCalls.size)
    }

    @Test
    fun oldParentGenerationCannotContinueOrAbandonAStillPendingChild() = runTest(dispatcher, timeout = 15.seconds) {
        val parentRows = MutableStateFlow(fixture.sessionRows.getValue(PARENT))
        every { fixture.chatSessions.getByIdFlow(PARENT) } returns parentRows
        val child = seedPending("parent-generation-changed", CHILD)
        val stale = snapshot(child.id)

        assertTrue(repository.invalidateSession(PARENT, "restore"))
        val newParent = parentRows.value.copy(executionGeneration = ledger.sessions.getValue(PARENT).executionGeneration)
        fixture.sessionRows[PARENT] = newParent
        parentRows.value = newParent
        val current = snapshot(child.id)
        assertEquals(RecoveryScope(PARENT, WORKSPACE, 0), stale.scope)
        assertEquals(RecoveryScope(PARENT, WORKSPACE, 1), current.scope)
        assertEquals(stale.items, current.items)
        val writes = ledger.writes.toList()

        vm.continueRecovery(stale)
        runCurrent()
        vm.recovering.first { !it }
        vm.abandonRecovery(stale)
        runCurrent()
        vm.recovering.first { !it }

        assertEquals(writes, ledger.writes)
        assertEquals("AWAITING_CONFIRMATION", ledger.requests.getValue(child.id).status)
        assertTrue(ledger.runs.isEmpty())
        assertTrue(ledger.messages.isEmpty())
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        vm.continueRecovery(current)
        val run = backendRuns.receive()
        assertRecoveryRun(child, run)
        run.owner.cancelAndJoin()
    }

    @Test
    fun verificationForAnOldRunCannotContinueOrAbandonANewerInterruptionOfTheSameRequest() = runTest(dispatcher, timeout = 15.seconds) {
        val interrupted = seedInterrupted("new-active-run", toolCalls = true)
        val request = interrupted.request
        val stale = snapshot(request.id)
        val staleTarget = stale.items.single().target
        val replacement = checkNotNull(repository.claimPending(requireNotNull(stale.scope), staleTarget, request.environment,
            unknownToolsVerified = true))
        assertTrue(repository.finish(replacement.id, RecoveryStatus.INTERRUPTED))
        ledger.refreshPendingScopeRows()
        val current = vm.recoveryBubble.first { bubble ->
            bubble.scope == stale.scope && bubble.items.singleOrNull()?.target?.activeRunId == replacement.id
        }
        assertEquals(interrupted.run.id, staleTarget.activeRunId)
        assertEquals(staleTarget.copy(activeRunId = replacement.id), current.items.single().target)
        assertEquals(setOf(RecoveryToolCall("unknown-call", "Bash")), staleTarget.unknownTools)
        val writes = ledger.writes.toList()

        vm.continueRecovery(stale, unknownToolsVerified = true)
        runCurrent()
        vm.recovering.first { !it }
        vm.abandonRecovery(stale)
        runCurrent()
        vm.recovering.first { !it }

        assertEquals(writes, ledger.writes)
        assertEquals("INTERRUPTED", ledger.requests.getValue(request.id).status)
        assertEquals(replacement.id, ledger.requests.getValue(request.id).activeRunId)
        assertEquals(setOf(interrupted.run.id, replacement.id), ledger.runs.keys)
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        vm.abandonRecovery(current)
        runCurrent()
        vm.recovering.first { !it }
        assertEquals("ABANDONED", ledger.requests.getValue(request.id).status)
    }

    private fun openViewModel() {
        repository = RecoveryRepository(ledger.dao)
        fixture = SubAgentViewModelQueueFixture(context, recovery = repository)
        fixture.sessionRows[OTHER] = fixture.sessionRows.getValue(PARENT).copy(id = OTHER, title = "Other")
        fixture.sessionRows.values.forEach { if (it.id !in ledger.sessions) ledger.putSession(it) }
        every { fixture.projectIdentityResolver.environmentChanges } returns environmentChanges
        coEvery { fixture.sessions.getMostRecentSessionOfWorkspace(WORKSPACE) } returns fixture.sessionRows.getValue(PARENT)
        coEvery { fixture.sessions.isSessionEmpty(any()) } returns true
        coEvery { fixture.sessions.updateGoalStatus(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val status = secondArg<GoalStatus>()
            fixture.sessionRows[sessionId] = fixture.sessionRows.getValue(sessionId).copy(goalStatus = status.name)
        }
        coEvery { fixture.messages.getMessageById(any()) } answers { ledger.messages[firstArg<String>()] }
        coEvery { fixture.messages.getMessagesBySessionOnce(any()) } coAnswers {
            val sessionId = firstArg<String>()
            withContext(Dispatchers.Main) {
                ledger.messages.values.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
            }
        }
        val persistence = MessagePersistenceUseCase(fixture.messages, mockk<AgentDatabase>(relaxed = true), repository)
        coEvery { fixture.persistence.buildHistory(any(), any()) } coAnswers {
            persistence.buildHistory(firstArg(), secondArg())
        }
        every { fixture.persistence.nextTimestamp() } answers { persistence.nextTimestamp() }
        every { fixture.persistence.invalidateHistory(any()) } answers { persistence.invalidateHistory(firstArg()) }
        coEvery { fixture.persistence.loadAttachmentImages(any()) } coAnswers { persistence.loadAttachmentImages(firstArg()) }
        coEvery {
            fixture.persistence.persist(
                sessionId = any(), role = any(), content = any(), id = any(), toolCalls = any(),
                toolCallId = any(), toolName = any(), toolArgs = any(), isError = any(), reasoning = any(),
                signature = any(), thinkingBlocksJson = any(), attachments = any(), inputTokens = any(),
                outputTokens = any(), cachedInputTokens = any(), isCompacted = any(), isContextExcluded = any(),
                contextInputTokens = any(), contextInputBudget = any(), contextEstimated = any(), contextBreakdown = any(),
                toolActionTitle = any(), toolStartedAtMs = any(), toolDurationMs = any(), turnDurationMs = any(),
                requireSession = any(), recoveryRunId = any(), recoveryStep = any(), recoveryPlaceholder = any(), recoverySupplemental = any(),
                continuationOfMessageId = any(), notificationSeqs = any()
            )
        } coAnswers {
            persistence.persist(
                sessionId = arg(0), role = arg(1), content = arg(2), id = arg(3), toolCalls = arg(4),
                toolCallId = arg(5), toolName = arg(6), toolArgs = arg(7), isError = arg(8), reasoning = arg(9),
                signature = arg(10), thinkingBlocksJson = arg(11), attachments = arg(12), inputTokens = arg(13),
                outputTokens = arg(14), cachedInputTokens = arg(15), isCompacted = arg(16), isContextExcluded = arg(17),
                contextInputTokens = arg(18), contextInputBudget = arg(19), contextEstimated = arg(20), contextBreakdown = arg(21),
                toolActionTitle = arg(22), toolStartedAtMs = arg(23), toolDurationMs = arg(24), turnDurationMs = arg(25),
                requireSession = arg(26), recoveryRunId = arg(27), recoveryStep = arg(28), recoveryPlaceholder = arg(29), recoverySupplemental = arg(30),
                continuationOfMessageId = arg(31), notificationSeqs = arg(32)
            )
        }
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val request = firstArg<String>()
            val snapshot = secondArg<AgentContext>()
            val tools = thirdArg<List<AgentTool>>()
            val owner = fixture.jobs.getValue(requireNotNull(snapshot.sessionId))
            flow {
                val run = BackendRun(request, snapshot, tools, owner)
                backendCalls += run
                backendRuns.send(run)
                for (delivery in run.events) {
                    emit(delivery.event)
                    delivery.consumed.complete(Unit)
                }
            }
        }
        vm.setWorkspace(WORKSPACE)
        dispatcher.scheduler.runCurrent()
        vm.selectSession(PARENT)
        dispatcher.scheduler.runCurrent()
    }

    private fun putSession(session: ChatSessionEntity) {
        fixture.sessionRows[session.id] = session
        ledger.putSession(session)
    }

    private suspend fun seedPending(
        id: String,
        sessionId: String = PARENT,
        identity: ProjectMemoryIdentity = fixture.projectIdentity
    ): RecoveryRequest {
        val workspace = fixture.sessionRows.getValue(sessionId).workspacePath
        val request = repository.enqueue(sessionId, RecoveryRequestPayload("Request $id", workspace), Json.encodeToString(identity),
            requestId = id, inputMessageId = "input-$id")
        assertTrue(repository.awaitConfirmation(request.id))
        return request
    }

    private fun assertRecoveryRun(request: RecoveryRequest, run: BackendRun) {
        val stored = ledger.runs.getValue(requireNotNull(run.context.recoveryRunId))
        assertEquals(request.id, stored.requestId)
        assertEquals(request.sessionId, stored.sessionId)
        assertEquals(request.sessionId, run.context.sessionId)
        assertEquals(request.inputMessageId, run.context.inputMessageId)
        assertEquals(request.sessionId, ledger.messages.getValue(request.inputMessageId).sessionId)
        assertEquals("Request ${request.id}", run.request)
        assertSame(run.owner, fixture.jobs[request.sessionId])
    }

    private suspend fun seedQueued(id: String): RecoveryRequest = RecoveryRepository(ledger.dao).enqueue(
        PARENT, RecoveryRequestPayload("Request $id", WORKSPACE), Json.encodeToString(fixture.projectIdentity),
        requestId = id, inputMessageId = "input-$id"
    )

    private suspend fun seedInterrupted(
        id: String,
        sessionId: String = PARENT,
        identity: ProjectMemoryIdentity = fixture.projectIdentity,
        draft: String? = null,
        commitInput: Boolean = true,
        freezeInput: Boolean = false,
        toolCalls: Boolean = false
    ): Interrupted {
        val previous = RecoveryRepository(ledger.dao)
        val request = previous.enqueue(sessionId, RecoveryRequestPayload("Request $id", WORKSPACE), Json.encodeToString(identity),
            requestId = id, inputMessageId = "input-$id")
        val run = checkNotNull(previous.claim(request.id, request.environment))
        if (commitInput) {
            assertTrue(previous.commitInput(run.id, AgentMessageEntity(request.inputMessageId, sessionId, MessageRole.USER.name,
                "Request $id", 123)))
        }
        if (freezeInput) {
            val snapshotContext = AgentContext(null, null, WORKSPACE, null, sessionId = sessionId, inputMessageId = request.inputMessageId)
            val snapshot = Json.encodeToString(ModelInputSnapshot.prepare(snapshotContext, "Request $id"))
            assertEquals(snapshot, previous.freezeInputSnapshot(run.id, snapshot))
        }
        if (draft != null || toolCalls) {
            val step = checkNotNull(previous.beginStep(run.id))
            if (draft != null) assertTrue(previous.saveDraft(run.id, step, draft, force = true))
            if (toolCalls) {
                val calls = listOf("unknown-call", "waiting-call", "declared-call").map { ToolCall(it, "Bash", emptyMap()) }
                assertTrue(previous.commitAssistant(run.id, step, AgentMessageEntity(
                    "assistant-tools", sessionId, MessageRole.ASSISTANT.name, "Proposed calls", 124,
                    toolCallsJson = Json.encodeToString(calls)
                )))
                assertTrue(previous.markToolStarted(run.id, "unknown-call"))
                assertTrue(previous.markToolWaitingApproval(run.id, "waiting-call"))
            }
        }
        return Interrupted(request, run)
    }

    private suspend fun snapshot(vararg requestIds: String, scopeId: String = PARENT): RecoveryBubbleUi {
        val scope = RecoveryScope(scopeId, WORKSPACE, fixture.sessionRows.getValue(scopeId).executionGeneration)
        ledger.refreshPendingScopeRows()
        return vm.recoveryBubble.first { bubble ->
            bubble.scope == scope && bubble.items.map { it.target.requestId } == requestIds.toList()
        }
    }

    private suspend fun complete(run: BackendRun) {
        run.send(AgentEvent.Completed)
        run.events.close()
        run.owner.join()
        dispatcher.scheduler.runCurrent()
    }

    private fun activateGoal() {
        fixture.sessionRows[PARENT] = fixture.sessionRows.getValue(PARENT).copy(goal = "Finish recovery tests", goalStatus = GoalStatus.ACTIVE.name)
    }

    private data class Interrupted(val request: RecoveryRequest, val run: RecoveryRun)
    private data class Delivery(val event: AgentEvent, val consumed: CompletableDeferred<Unit>)

    private class BackendRun(val request: String, val context: AgentContext, val tools: List<AgentTool>, val owner: Job) {
        val events = Channel<Delivery>(Channel.UNLIMITED)

        suspend fun send(event: AgentEvent) {
            val consumed = CompletableDeferred<Unit>()
            events.send(Delivery(event, consumed))
            consumed.await()
        }
    }

    companion object {
        private const val WORKSPACE = "/work"
        private const val OTHER = "other"
    }
}
