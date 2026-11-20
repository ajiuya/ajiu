package com.aicode.feature.agent.presentation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.dao.RecoverySessionGate
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
class AgentRunLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val permissionGrants = mockk<SessionPermissionGrantRepository>(relaxed = true)
    private val permissions = ToolPermissionManager(permissionGrants)
    private val extraUiOwners = mutableListOf<SubAgentViewModelQueueFixture.UiOwner>()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private lateinit var context: Context
    private val vm get() = fixture.viewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        context = ApplicationProvider.getApplicationContext()
        fixture = SubAgentViewModelQueueFixture(context, permissionManager = permissions)
    }

    @After
    fun tearDown() {
        try {
            extraUiOwners.forEach { it.close() }
            if (::fixture.isInitialized) fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            unmockkObject(TaskKeepaliveController)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun clearingUiKeepsTheRealRunBodyToolsAndApprovalForAReopenedUiWithoutRepeatingExecution() = runTest(dispatcher, timeout = 15.seconds) {
        openSession()
        val approval = PendingToolPermission("approval", "Bash", "Run command", "Inspect files", "pwd", "pwd",
            sessionGrant = "command:pwd", sessionId = PARENT)
        val awaiting = CompletableDeferred<Unit>()
        val approved = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val choices = mutableListOf<PermissionChoice>()
        val requestContexts = mutableListOf<AgentContext>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            requestContexts += secondArg<AgentContext>()
            flow {
                emit(AgentEvent.ToolCallStarted("running", "Bash", "pwd"))
                emit(AgentEvent.ToolCallProgress("running", "Bash", "command output"))
                emit(AgentEvent.ReasoningDelta("reasoning before UI destruction"))
                emit(AgentEvent.AssistantDelta("body before UI destruction"))
                awaiting.complete(Unit)
                choices += permissions.awaitApproval(approval)
                approved.complete(Unit)
                finish.await()
                emit(AgentEvent.Completed)
            }
        }
        val first = vm
        observeProjection(first)
        val owner = first.executeAgentRequestStream("keep running", currentFile = ORIGINAL_FILE,
            selectedCode = ORIGINAL_SELECTION, targetSessionId = PARENT)
        awaiting.await()
        runCurrent()
        assertSame(owner, fixture.jobs[PARENT])
        assertEquals("body before UI destruction", first.streamingText.value)
        assertEquals("reasoning before UI destruction", first.streamingReasoning.value)
        assertEquals(listOf(RunningToolOutput("tool_running", "command output", "Bash", "pwd")), first.runningToolPreviewSnapshot.value.second)
        assertSame(approval, first.pendingToolPermission.value)
        assertEquals(setOf(PARENT), first.awaitingPermissionSessionIds.value)

        fixture.closeUi()
        runCurrent()
        assertFalse(requireNotNull(first.viewModelScope.coroutineContext[Job]).isActive)
        assertTrue(owner.isActive)
        assertSame(owner, fixture.jobs[PARENT])
        assertSame(approval, permissions.pendingRequest.value)
        assertTrue(choices.isEmpty())
        fixture.manager.start()
        fixture.manager.start()
        val second = fixture.reopenUi()
        assertNotSame(first, second)
        second.setWorkspace(WORKSPACE)
        second.selectSession(PARENT)
        observeProjection(second)
        runCurrent()
        assertEquals(AgentUIState.Streaming, second.agentState.value)
        assertEquals("body before UI destruction", second.streamingText.value)
        assertEquals("reasoning before UI destruction", second.streamingReasoning.value)
        assertEquals(PARENT to listOf(RunningToolOutput("tool_running", "command output", "Bash", "pwd")), second.runningToolPreviewSnapshot.value)
        assertSame(approval, second.pendingToolPermission.value)
        assertEquals(setOf(PARENT), second.approvalSessionIds.value)
        assertTrue(choices.isEmpty())
        assertEquals(1, requestContexts.size)
        assertEquals(PARENT, requestContexts.single().sessionId)
        assertEquals(WORKSPACE, requestContexts.single().projectRoot)
        assertEquals(ORIGINAL_FILE, requestContexts.single().currentFile)
        assertEquals(ORIGINAL_SELECTION, requestContexts.single().selectedCode)
        verify(exactly = 1) { fixture.workflow.executeEvents("keep running", any(), any()) }
        coVerify(exactly = 0) { permissionGrants.allow(any(), any()) }

        second.resolveToolPermission(approval.id, PermissionChoice.ALWAYS_SESSION)
        approved.await()
        runCurrent()
        second.resolveToolPermission(approval.id, PermissionChoice.ALWAYS_SESSION)
        runCurrent()
        assertEquals(listOf(PermissionChoice.ALWAYS_SESSION), choices)
        assertNull(second.pendingToolPermission.value)
        assertTrue(second.awaitingPermissionSessionIds.value.isEmpty())
        coVerify(exactly = 1) { permissionGrants.allow(PARENT, "command:pwd") }
        finish.complete(Unit)
        owner.join()
        runCurrent()
        assertTrue(PARENT in second.completedSessions.value)
        assertTrue(fixture.jobs.isEmpty())
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun noUiAndTwoUiOwnersStillHaveOneTerminalAndSubAgentCompletionConsumer() = runTest(dispatcher, timeout = 15.seconds) {
        openSession()
        val handled = Channel<Unit>(Channel.UNLIMITED)
        coEvery { fixture.recoveryRepository.canDispatch(PARENT) } coAnswers {
            handled.send(Unit)
            true
        }
        coEvery { fixture.messages.getMessagesBySessionOnce(CHILD) } returns emptyList()
        val busy = Job()
        fixture.jobs[PARENT] = busy
        fixture.closeUi()
        runCurrent()
        assertEquals(1, fixture.terminalEvents.subscriptionCount.value)
        fixture.terminalEvents.emit(terminalEvent("terminal-no-ui"))
        handled.receive()
        fixture.events.emit(completedEvent("subagent-no-ui"))
        handled.receive()
        runCurrent()
        val noUi = fixture.notifications.peek(PARENT)
        assertEquals(listOf(AgentNotificationKind.BACKGROUND_TASK, AgentNotificationKind.SUBAGENT), noUi.map { it.kind })
        assertEquals(listOf("terminal-no-ui", CHILD), noUi.map { it.sourceId })
        coVerify(exactly = 1) { fixture.messages.getMessagesBySessionOnce(CHILD) }
        assertEquals(SubAgentCardStatus.COMPLETED, fixture.manager.subAgentCards.value.getValue(CHILD).status)
        assertSame(busy, fixture.jobs[PARENT])

        val first = fixture.reopenUi()
        first.setWorkspace(WORKSPACE)
        first.selectSession(PARENT)
        val another = fixture.createUiOwner().also(extraUiOwners::add)
        another.viewModel.setWorkspace(WORKSPACE)
        another.viewModel.selectSession(PARENT)
        fixture.manager.start()
        runCurrent()
        assertEquals(1, fixture.terminalEvents.subscriptionCount.value)
        fixture.terminalEvents.emit(terminalEvent("terminal-two-ui"))
        handled.receive()
        fixture.events.emit(completedEvent("subagent-two-ui"))
        handled.receive()
        runCurrent()
        val pending = fixture.notifications.peek(PARENT)
        assertEquals(4, pending.size)
        assertEquals(listOf("terminal-no-ui", CHILD, "terminal-two-ui", CHILD), pending.map { it.sourceId })
        assertEquals(4, pending.map { it.seq }.distinct().size)
        assertEquals(listOf("subagent-no-ui", "subagent-two-ui"), pending.filter { it.kind == AgentNotificationKind.SUBAGENT }.map { it.eventId })
        coVerify(exactly = 2) { fixture.messages.getMessagesBySessionOnce(CHILD) }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        busy.cancel()
    }

    @Test
    fun initialCleanupGatesSubmitCommandCompactionRecoveryAndBackgroundDispatch() = runTest(dispatcher, timeout = 15.seconds) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = installRealCleanup {
            entered.complete(Unit)
            release.await()
        }
        val command = fixture.installCommand("/gate")
        val compactionSession = "compaction-gate"
        val recoverySession = "recovery-gate"
        val queueSession = "queue-gate"
        val recoveryObserved = CompletableDeferred<Unit>()
        var recoverySubscriptions = 0
        every { fixture.recoveryRepository.observePending(recoverySession) } returns flow {
            assertTrue(release.isCompleted)
            recoverySubscriptions++
            recoveryObserved.complete(Unit)
            emit(emptyList())
        }
        listOf(compactionSession, recoverySession, queueSession).forEach { id ->
            fixture.sessionRows[id] = fixture.sessionRows.getValue(PARENT).copy(id = id)
        }
        val started = Channel<String>(Channel.UNLIMITED)
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val sid = requireNotNull(secondArg<AgentContext>().sessionId)
            flow {
                started.send(sid)
                awaitCancellation()
            }
        }
        openSession()
        entered.await()
        val submit = vm.executeAgentRequestStream("blocked submit", targetSessionId = PARENT)
        val slash = vm.executeAgentRequestStream("/gate", targetSessionId = CHILD)
        vm.compactSession(compactionSession)
        vm.enqueueAgentRequest("queued before startup cleanup", targetSessionId = queueSession)
        vm.selectSession(recoverySession)
        vm.continueRecovery("missing-request")
        fixture.terminalEvents.emit(terminalEvent("gated-terminal"))
        runCurrent()
        assertFalse(submit.isCompleted)
        assertFalse(slash.isCompleted)
        verify(exactly = 0) { command.execute(any(), any()) }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.workflow.compactSession(any(), any(), any(), any()) }
        assertEquals(0, recoverySubscriptions)
        verify(exactly = 0) { fixture.recoveryRepository.observePending(recoverySession) }
        coVerify(exactly = 0) { fixture.recoveryRepository.claim("missing-request", any(), any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
        assertTrue(fixture.notifications.peek(PARENT).isEmpty())
        coVerify(exactly = 0) { fixture.messages.markPendingToolsInterrupted(any(), any(), any()) }
        fixture.manager.start()
        runCurrent()
        release.complete(Unit)
        val resumedSessions = setOf(started.receive(), started.receive())
        assertEquals(setOf(PARENT, queueSession), resumedSessions)
        slash.join()
        fixture.jobs[compactionSession]?.join()
        recoveryObserved.await()
        runCurrent()
        verify(exactly = 1) { command.execute(any(), "") }
        coVerify(exactly = 1) { fixture.workflow.compactSession(compactionSession, any(), any(), any()) }
        assertEquals(1, recoverySubscriptions)
        verify(exactly = 1) { fixture.recoveryRepository.observePending(recoverySession) }
        coVerify(exactly = 0) { fixture.recoveryRepository.claim("missing-request", any(), any()) }
        coVerify(exactly = 1) { fixture.recoveryRepository.initialize() }
        coVerify(exactly = 1) { fixture.messages.markPendingToolsInterrupted("TOOL", "${SessionUseCase.PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT) }
        coVerify(exactly = 1) { fixture.messages.markPendingToolsInterrupted("TOOL", "${SessionUseCase.LEGACY_PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT) }
        cleanup.initColdStartCleanup()
        coVerify(exactly = 1) { fixture.recoveryRepository.initialize() }
        submit.cancel()
        submit.join()
    }

    @Test
    fun failedInitialCleanupCannotBeBypassedByReopeningUiOrSubmittingAgain() = runTest(dispatcher, timeout = 15.seconds) {
        val failure = IllegalStateException("Cold start cleanup unavailable")
        installRealCleanup { throw failure }
        openSession()
        fixture.manager.startupError.first { it != null }
        val first = vm.executeAgentRequestStream("first after failure", targetSessionId = PARENT)
        first.join()
        fixture.closeUi()
        fixture.reopenUi()
        openSession()
        val second = vm.executeAgentRequestStream("second after failure", targetSessionId = PARENT)
        second.join()
        val command = fixture.installCommand("/blocked")
        vm.executeAgentRequestStream("/blocked", targetSessionId = CHILD).join()
        vm.compactSession(CHILD)
        vm.continueRecovery("blocked-recovery")
        fixture.terminalEvents.emit(terminalEvent("blocked-notification"))
        fixture.events.emit(completedEvent("blocked-subagent"))
        runCurrent()
        assertNotNull(fixture.manager.startupError.value)
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        verify(exactly = 0) { command.execute(any(), any()) }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.workflow.compactSession(any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.recoveryRepository.observePending(any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.claim("blocked-recovery", any(), any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { fixture.recoveryRepository.initialize() }
        coVerify(exactly = 0) { fixture.messages.markPendingToolsInterrupted(any(), any(), any()) }
        assertTrue(fixture.notifications.peek(PARENT).isEmpty())
    }

    @Test
    fun aSourceSelectionRoundTripWhileStartupIsBlockedCannotDispatchTheOldSubmission() = runTest(dispatcher, timeout = 15.seconds) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installRealCleanup {
            entered.complete(Unit)
            release.await()
        }
        openSession()
        entered.await()
        val owner = vm.executeAgentRequestStream("original source", targetSessionId = PARENT)
        runCurrent()
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        fixture.executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
        fixture.executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
        release.complete(Unit)
        owner.join()
        runCurrent()
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
        assertFalse(PARENT in vm.completedSessions.value)
    }

    @Test
    fun sourceInvalidationAfterUiClosureRefusesLateAssistantPersistenceAndAnyContinuation() = runTest(dispatcher, timeout = 15.seconds) {
        var sourceCurrent = true
        val capture = ProjectMemoryCapture(fixture.projectIdentity) {
            if (!sourceCurrent) throw CancellationException("Source changed")
        }
        coEvery { fixture.projectIdentityResolver.capture(any(), any(), any()) } returns capture
        openSession()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.AssistantDelta("original source body"))
            entered.complete(Unit)
            release.await()
            emit(AgentEvent.AssistantText("must not be persisted", messageId = "late-assistant", persisted = persisted))
            emit(AgentEvent.Completed)
        }
        val owner = vm.executeAgentRequestStream("capture original", targetSessionId = PARENT)
        entered.await()
        fixture.closeUi()
        sourceCurrent = false
        release.complete(Unit)
        owner.join()
        runCurrent()
        assertTrue(owner.isCancelled)
        assertFalse(persisted.isCompleted)
        assertFalse(PARENT in fixture.manager.completedSessions.value)
        assertNull(fixture.manager.streamingTexts.value[PARENT])
        coVerify(exactly = 0) {
            fixture.persistence.persist(
                PARENT, MessageRole.ASSISTANT, "must not be persisted", id = "late-assistant", toolCalls = any(),
                reasoning = any(), signature = any(), thinkingBlocksJson = any(), attachments = any(),
                inputTokens = any(), outputTokens = any(), cachedInputTokens = any(), contextInputTokens = any(),
                contextInputBudget = any(), contextEstimated = any(), contextBreakdown = any(),
                recoveryRunId = any(), recoveryStep = any()
            )
        }
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
        assertTrue(fixture.jobs.isEmpty())
    }

    @Test
    fun aNewProcessOwnerShowsRecoveryAndUnknownToolsWithoutClaimingOrExecutingThem() = runTest(dispatcher, timeout = 15.seconds) {
        fixture.closeUi()
        fixture.closeProcessOwner().join()
        val ledger = RecoveryDaoFixture()
        ledger.sessions[PARENT] = RecoverySessionGate(PARENT, 0, null)
        val previous = RecoveryRepository(ledger.dao)
        val request = previous.enqueue(PARENT, RecoveryRequestPayload("Interrupted process request", WORKSPACE),
            Json.encodeToString(fixture.projectIdentity), requestId = "process-request", inputMessageId = "process-input")
        val previousRun = checkNotNull(previous.claim(request.id, request.environment))
        assertTrue(previous.commitInput(previousRun.id, AgentMessageEntity(request.inputMessageId, PARENT,
            MessageRole.USER.name, "Interrupted process request", 1)))
        val step = checkNotNull(previous.beginStep(previousRun.id))
        assertTrue(previous.commitAssistant(previousRun.id, step, AgentMessageEntity("process-assistant", PARENT,
            MessageRole.ASSISTANT.name, "Proposed tool", 2,
            toolCallsJson = Json.encodeToString(listOf(ToolCall("unknown-tool", "Bash", emptyMap()))))))
        assertTrue(previous.markToolStarted(previousRun.id, "unknown-tool"))
        ledger.sessions[CHILD] = RecoverySessionGate(CHILD, 0, null)
        val draftRequest = previous.enqueue(CHILD, RecoveryRequestPayload("Interrupted draft request", WORKSPACE),
            request.environment, requestId = "draft-request", inputMessageId = "draft-input")
        val draftRun = checkNotNull(previous.claim(draftRequest.id, draftRequest.environment))
        val draftStep = checkNotNull(previous.beginStep(draftRun.id))
        assertTrue(previous.saveDraft(draftRun.id, draftStep, "Unverified process draft", force = true))
        val originalMessages = ledger.messages.toMap()
        val next = RecoveryRepository(ledger.dao)
        fixture = SubAgentViewModelQueueFixture(context, recovery = next)
        openSession()
        observe(vm.recoveryItems)
        val card = vm.recoveryItems.first { (sid, items) -> sid == PARENT && items.any { it.requestId == request.id } }
            .second.single()
        runCurrent()
        assertEquals("Interrupted process request", card.request)
        assertEquals("", card.draft)
        assertEquals(listOf("Bash"), card.unknownTools)
        assertTrue(card.environmentMatches)
        assertEquals("INTERRUPTED", ledger.requests.getValue(request.id).status)
        assertEquals("INTERRUPTED", ledger.runs.getValue(previousRun.id).status)
        assertEquals("UNKNOWN", ledger.tools.getValue(previousRun.id to "unknown-tool").status)
        assertEquals(setOf(previousRun.id, draftRun.id), ledger.runs.keys)
        vm.selectSession(CHILD)
        val draftCard = vm.recoveryItems.first { (sid, items) -> sid == CHILD && items.any { it.requestId == draftRequest.id } }
            .second.single()
        assertEquals("Interrupted draft request", draftCard.request)
        assertEquals("Unverified process draft", draftCard.draft)
        assertEquals("INTERRUPTED", ledger.runs.getValue(draftRun.id).status)
        assertEquals(originalMessages, ledger.messages)
        assertFalse(next.isCurrent(previousRun.id))
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(vm.queuedRequests.value.isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        verify(exactly = 0) { fixture.tools.getTool(any()) }
    }

    @Test
    fun aSuspendedCommandUsesItsFixedSessionWorkspaceAndEditorAfterUiSelectionChanges() = runTest(dispatcher, timeout = 15.seconds) {
        openSession()
        val original = vm
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invocation = slot<SlashCommandContext>()
        val handler = fixture.installCommand("/fixed")
        every { handler.execute(capture(invocation), "") } answers {
            firstArg<SlashCommandContext>().initProject("fixed command request")
        }
        coEvery {
            fixture.persistence.persist(PARENT, MessageRole.USER, "/fixed", id = any(), requireSession = false)
        } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val snapshots = Channel<AgentContext>(Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents("fixed command request", any(), any()) } answers {
            val snapshot = secondArg<AgentContext>()
            flow {
                snapshots.send(snapshot)
                finish.await()
                emit(AgentEvent.Completed)
            }
        }
        val command = original.executeAgentRequestStream("/fixed", currentFile = ORIGINAL_FILE,
            selectedCode = ORIGINAL_SELECTION, targetSessionId = PARENT)
        entered.await()
        original.selectSession(CHILD)
        fixture.closeUi()
        val second = fixture.reopenUi()
        second.setWorkspace("/different-workspace")
        second.selectSession(CHILD)
        runCurrent()
        release.complete(Unit)
        val snapshot = snapshots.receive()
        assertEquals(PARENT, snapshot.sessionId)
        assertEquals(WORKSPACE, snapshot.projectRoot)
        assertEquals(ORIGINAL_FILE, snapshot.currentFile)
        assertEquals(ORIGINAL_SELECTION, snapshot.selectedCode)
        assertEquals("kotlin", snapshot.language)
        assertNotSame(original, invocation.captured)
        assertRuntimeDoesNotCaptureUi(invocation.captured, original)
        finish.complete(Unit)
        command.join()
        verify(exactly = 1) { handler.execute(invocation.captured, "") }
        verify(exactly = 1) { fixture.workflow.executeEvents("fixed command request", any(), any()) }
        assertFalse(requireNotNull(original.viewModelScope.coroutineContext[Job]).isActive)
    }

    @Test
    fun aRunningOwnerAndItsDependenciesDoNotRetainTheClearedUiOrItsContextWrapper() = runTest(dispatcher, timeout = 15.seconds) {
        fixture.closeUi()
        fixture.closeProcessOwner().join()
        val uiContext = ContextWrapper(context)
        fixture = SubAgentViewModelQueueFixture(uiContext)
        openSession()
        val original = vm
        val entered = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.AssistantDelta("still running"))
            entered.complete(Unit)
            awaitCancellation()
        }
        val owner = original.executeAgentRequestStream("retain runtime only", targetSessionId = PARENT)
        entered.await()
        fixture.closeUi()
        runCurrent()
        assertTrue(owner.isActive)
        assertSame(context, fixture.dependencies.context)
        assertRuntimeDoesNotCaptureUi(fixture.manager, original, uiContext)
        assertRuntimeDoesNotCaptureUi(fixture.dependencies, original, uiContext)
        owner.cancel()
        owner.join()
    }

    private fun openSession() {
        vm.setWorkspace(WORKSPACE)
        dispatcher.scheduler.runCurrent()
        vm.selectSession(PARENT)
        dispatcher.scheduler.runCurrent()
    }

    private suspend fun installRealCleanup(initialize: suspend () -> Unit): SessionUseCase {
        fixture.closeUi()
        fixture.closeProcessOwner().join()
        lateinit var cleanup: SessionUseCase
        fixture = SubAgentViewModelQueueFixture(context, permissionManager = permissions, configureBeforeStart = {
            coEvery { recoveryRepository.initialize() } coAnswers { initialize() }
            cleanup = SessionUseCase(chatSessions, messages, mockk(relaxed = true), recoveryRepository, journal)
            coEvery { sessions.initColdStartCleanup() } coAnswers { cleanup.initColdStartCleanup() }
        })
        return cleanup
    }

    private fun TestScope.observeProjection(viewModel: AIAgentViewModel) {
        observe(viewModel.agentState)
        observe(viewModel.streamingText)
        observe(viewModel.streamingReasoning)
        observe(viewModel.runningToolPreviewSnapshot)
        observe(viewModel.approvalSessionIds)
        runCurrent()
    }

    private fun <T> TestScope.observe(state: StateFlow<T>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    }

    private fun terminalEvent(id: String) = TabFinishedEvent(id, "Completed command", "pwd", 0, PARENT, "command output")

    private fun completedEvent(id: String) = SubAgentEvent(CHILD, PARENT, SubAgentEventType.COMPLETED,
        eventId = id, generation = 0, parentGeneration = 0)

    private fun assertRuntimeDoesNotCaptureUi(root: Any, vararg forbidden: Any) {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val value = pending.removeFirst()
            if (!visited.add(value)) continue
            assertTrue("Runtime retained a cleared UI object: ${value.javaClass.name}", forbidden.none { it === value })
            assertFalse("Runtime retained ViewModel: ${value.javaClass.name}", value is ViewModel)
            assertFalse("Runtime retained Activity: ${value.javaClass.name}", value is Activity)
            if (value is Context) {
                assertSame(context, value)
                continue
            }
            when (value) {
                is Map<*, *> -> value.forEach { (key, item) -> key?.let(pending::add); item?.let(pending::add) }
                is Iterable<*> -> value.forEach { it?.let(pending::add) }
                is Array<*> -> value.forEach { it?.let(pending::add) }
                is CoroutineScope -> pending.add(value.coroutineContext)
            }
            val type = value.javaClass
            if (type.name.startsWith("kotlinx.coroutines.test.")) continue
            if (!type.name.startsWith("com.aicode.feature.agent.presentation.runtime.") &&
                !type.name.startsWith("kotlin.coroutines.") && !type.name.startsWith("kotlinx.coroutines.")) continue
            var parent: Class<*>? = type
            while (parent != null && parent != Any::class.java && parent != Enum::class.java) {
                parent.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }.forEach { field ->
                    field.isAccessible = true
                    field.get(value)?.let(pending::add)
                }
                parent = parent.superclass
            }
        }
    }

    companion object {
        private const val WORKSPACE = "/work"
        private const val ORIGINAL_FILE = "/work/src/Original.kt"
        private const val ORIGINAL_SELECTION = "original editor selection"
    }
}
