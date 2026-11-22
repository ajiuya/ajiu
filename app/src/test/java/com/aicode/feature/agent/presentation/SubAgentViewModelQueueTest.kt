package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
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
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SubAgentViewModelQueueTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.viewModel.setWorkspace("/work")
        dispatcher.scheduler.runCurrent()
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
    fun resubscribingToTheSameChatRetainsSummariesAndEventsUntilFreshFactsArrive() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val event = SubAgentInteractionMetadata(
            id = "instruction", parentSessionId = PARENT, subSessionId = CHILD, kind = "MESSAGE_FROM_PARENT",
            timestamp = 2, origin = "USER", sourceMessageId = null, title = null, model = null, preview = "Check the list"
        )
        val stored = SubAgentInteractionFacts(listOf(event), listOf(fixture.sessionRows.getValue(CHILD)), emptyList())
        val releaseRefresh = CompletableDeferred<SubAgentInteractionFacts>()
        val refreshStarted = Channel<Unit>(Channel.UNLIMITED)
        every { fixture.interactionReader.summaryFacts(PARENT) } returns flowOf(stored)
        vm.selectSession(PARENT)
        val summaries = vm.subAgentInteractionSummaries
        val events = vm.subAgentInteractionEvents
        assertEquals(setOf(CHILD), summaries.first { CHILD in it.second }.second.keys)
        assertEquals(listOf(event), events.first { it.second.isNotEmpty() }.second)

        advanceTimeBy(5_001)
        runCurrent()
        every { fixture.interactionReader.summaryFacts(PARENT) } returns flow {
            refreshStarted.send(Unit)
            emit(releaseRefresh.await())
        }
        val summaryCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { summaries.collect {} }
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { events.collect {} }
        repeat(2) { refreshStarted.receive() }
        runCurrent()

        assertEquals(PARENT, summaries.value.first)
        assertEquals(setOf(CHILD), summaries.value.second.keys)
        assertEquals(PARENT to listOf(event), events.value)
        releaseRefresh.complete(SubAgentInteractionFacts(emptyList(), emptyList(), emptyList()))
        summaries.first { it.first == PARENT && it.second.isEmpty() }
        events.first { it.first == PARENT && it.second.isEmpty() }
        summaryCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun acceptedChildInstructionKeepsItsIdentityAndCannotBeEditedOrInterjected() = runTest(dispatcher) {
        val vm = fixture.viewModel
        fixture.jobs[CHILD] = Job()
        vm.selectSession(PARENT)
        assertTrue(vm.sendSubAgentInstruction(PARENT, CHILD, "  inspect child sources  ").sent)
        vm.selectSession(CHILD)
        runCurrent()
        val accepted = vm.queuedRequests.value.single()
        assertEquals("inspect child sources", accepted.request)
        assertEquals(accepted.request, accepted.modelRequest)
        assertEquals("/work", accepted.projectRoot)
        assertFalse(accepted.subAgentInstructionId.isNullOrBlank())
        coVerify(exactly = 1) {
            fixture.journal.record(match {
                it.eventId == accepted.subAgentInstructionId && it.subSessionId == CHILD &&
                    it.parentSessionId == PARENT && it.type == SubAgentEventType.MESSAGE_FROM_PARENT && it.origin == "USER"
            })
        }

        vm.updateQueuedRequest(accepted.id, "replace accepted instruction")
        vm.interjectQueuedRequest(accepted.id)
        runCurrent()

        assertEquals(listOf(accepted), vm.queuedRequests.value)
        assertTrue(fixture.notifications.peek(CHILD).isEmpty())
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun ordinaryQueueEntryStillEditsItsModelTextAndCanInterject() = runTest(dispatcher) {
        val vm = fixture.viewModel
        fixture.jobs[CHILD] = Job()
        vm.selectSession(CHILD)
        vm.enqueueAgentRequest("original", modelRequest = "original\n\nattachment manifest", targetSessionId = CHILD)
        runCurrent()
        val queued = vm.queuedRequests.value.single()
        assertNull(queued.subAgentInstructionId)

        vm.updateQueuedRequest(queued.id, "  revised  ")
        runCurrent()
        val edited = vm.queuedRequests.value.single()
        assertEquals("revised", edited.request)
        assertEquals("revised\n\nattachment manifest", edited.modelRequest)
        vm.interjectQueuedRequest(queued.id)
        runCurrent()

        assertTrue(vm.queuedRequests.value.isEmpty())
        val notification = fixture.notifications.peek(CHILD).single()
        assertEquals(AgentNotificationKind.USER_MESSAGE, notification.kind)
        assertEquals("revised", notification.message)
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun cancelledButNotCompletedJobStillQueuesIncomingInstruction() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val oldJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        fixture.jobs[CHILD] = oldJob
        fixture.viewModel.selectSession(CHILD)
        oldJob.cancel()
        runCurrent()
        try {
            assertTrue(cleanupStarted.isCompleted)
            assertFalse(oldJob.isActive)
            assertFalse(oldJob.isCompleted)
            fixture.viewModel.enqueueAgentRequest("wait for audit", targetSessionId = CHILD, subAgentInstructionId = "accepted")
            runCurrent()

            assertEquals("accepted", fixture.viewModel.queuedRequests.value.single().subAgentInstructionId)
            assertSame(oldJob, fixture.jobs[CHILD])
            coVerify(exactly = 1) { fixture.sessions.getSessionById(CHILD) }
        } finally {
            releaseCleanup.complete(Unit)
            oldJob.join()
        }
    }

    @Test
    fun stoppingWithoutJobQueuesAndCommandCompletionCannotFlushOrConsumeIt() = runTest(dispatcher) {
        val vm = fixture.viewModel
        fixture.stopping.add(CHILD)
        vm.selectSession(CHILD)
        val handler = fixture.installCommand("/drain")
        val invocation = slot<SlashCommandContext>()
        every { handler.execute(capture(invocation), "") } answers {
            firstArg<SlashCommandContext>().showUsage()
        }
        val notificationSeq = fixture.notifications.enqueue(CHILD, notification())
        coEvery { fixture.sessions.getSessionById(CHILD) } returns fixture.sessionRows.getValue(CHILD)
        vm.enqueueAgentRequest("next child run", targetSessionId = CHILD, subAgentInstructionId = "waiting")
        runCurrent()
        val waiting = vm.queuedRequests.value.single()
        assertNull(fixture.jobs[CHILD])

        val command = vm.executeAgentRequestStream("/drain", targetSessionId = CHILD)
        command.join()
        runCurrent()

        verify(exactly = 1) { handler.execute(invocation.captured, "") }
        coVerify(exactly = 1) {
            fixture.persistence.persist(CHILD, MessageRole.ASSISTANT, match { it.startsWith("| ") }, id = any(), isContextExcluded = true)
        }
        coVerify(exactly = 0) {
            fixture.persistence.persist(PARENT, MessageRole.ASSISTANT, any(), id = any(), isContextExcluded = true)
        }
        assertEquals(listOf(waiting), vm.queuedRequests.value)
        assertEquals(listOf(notificationSeq), fixture.notifications.peek(CHILD).map { it.seq })
        assertNull(fixture.jobs[CHILD])
        coVerify(exactly = 1) { fixture.sessions.getSessionById(CHILD) }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun deletionRejectsLateEnqueueBothBeforeAndAfterRuntimeCleanup() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val deleting = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.sessions.deleteSession(CHILD) } coAnswers {
            deleting.complete(Unit)
            releaseDeletion.await()
            fixture.sessionRows.remove(CHILD)
            listOf(CHILD)
        }
        fixture.jobs[CHILD] = Job()
        vm.selectSession(CHILD)
        vm.enqueueAgentRequest("already queued", targetSessionId = CHILD, subAgentInstructionId = "before-delete")
        runCurrent()
        val originalQueue = vm.queuedRequests.value
        val deletion = vm.deleteSession(CHILD)
        runCurrent()
        try {
            assertTrue(deleting.isCompleted)
            vm.enqueueAgentRequest("late during delete", targetSessionId = CHILD, subAgentInstructionId = "late")
            runCurrent()
            assertEquals(originalQueue, vm.queuedRequests.value)
        } finally {
            releaseDeletion.complete(Unit)
        }
        deletion.join()
        vm.selectSession(CHILD)
        runCurrent()
        assertTrue(vm.queuedRequests.value.isEmpty())

        vm.enqueueAgentRequest("late after delete", targetSessionId = CHILD, subAgentInstructionId = "later")
        assertNull(fixture.jobs[CHILD])
        runCurrent()
        assertTrue(vm.queuedRequests.value.isEmpty())
        coVerify(exactly = 2) { fixture.sessions.getSessionById(CHILD) }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun queuedChildSlashTextNeverResolvesAsParentCommandButOrdinarySlashStillDoes() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val oldJob = Job()
        fixture.jobs[CHILD] = oldJob
        val childCommand = fixture.installCommand("/compress")
        val drainCommand = fixture.installCommand("/drain")
        val childInvocation = slot<SlashCommandContext>()
        val drainInvocation = slot<SlashCommandContext>()
        every { childCommand.execute(capture(childInvocation), "") } answers {
            firstArg<SlashCommandContext>().showUsage()
        }
        every { drainCommand.execute(capture(drainInvocation), "") } answers {
            firstArg<SlashCommandContext>().showUsage()
        }
        // 验证之后的执行故意在会话查询处返回，隔离命令分流而不启动真实工作流。
        coEvery { fixture.sessions.getSessionById(CHILD) } returns fixture.sessionRows.getValue(CHILD) andThen
            fixture.sessionRows.getValue(CHILD) andThen null
        vm.selectSession(PARENT)
        assertTrue(vm.sendSubAgentInstruction(PARENT, CHILD, "/compress").sent)
        oldJob.complete()
        val drain = vm.executeAgentRequestStream("/drain", targetSessionId = CHILD)
        drain.join()
        runCurrent()

        assertEquals(PARENT, vm.currentSessionId.value)
        verify(exactly = 1) { drainCommand.execute(drainInvocation.captured, "") }
        coVerify(exactly = 1) {
            fixture.persistence.persist(CHILD, MessageRole.ASSISTANT, match { it.startsWith("| ") }, id = any(), isContextExcluded = true)
        }
        coVerify(exactly = 0) {
            fixture.persistence.persist(PARENT, MessageRole.ASSISTANT, any(), id = any(), isContextExcluded = true)
        }
        verify(exactly = 0) { fixture.commands.resolve("/compress") }
        verify(exactly = 0) { childCommand.execute(any(), any()) }
        coVerify(exactly = 3) { fixture.sessions.getSessionById(CHILD) }
        assertNull(fixture.jobs[CHILD])

        val ordinary = vm.executeAgentRequestStream("/compress", targetSessionId = PARENT)
        ordinary.join()
        runCurrent()
        verify(exactly = 1) { childCommand.execute(childInvocation.captured, "") }
        coVerify(exactly = 1) {
            fixture.persistence.persist(PARENT, MessageRole.ASSISTANT, match { it.startsWith("| ") }, id = any(), isContextExcluded = true)
        }
        coVerify(exactly = 1) {
            fixture.persistence.persist(CHILD, MessageRole.ASSISTANT, match { it.startsWith("| ") }, id = any(), isContextExcluded = true)
        }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun stopWaitsForNonCancellableAuditBeforeStartingQueuedRun() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val historyStarted = CompletableDeferred<Unit>()
        val auditStarted = CompletableDeferred<Unit>()
        val releaseAudit = CompletableDeferred<Unit>()
        coEvery { fixture.persistence.buildHistory(CHILD, any()) } coAnswers {
            historyStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { fixture.journal.record(match { it.type == SubAgentEventType.STOPPED }, any()) } coAnswers {
            auditStarted.complete(Unit)
            releaseAudit.await()
            val finish = secondArg<(suspend () -> Boolean)?>()
            assertTrue(finish?.invoke() == true)
            mockk<SubAgentInteractionEntity>(relaxed = true)
        }
        vm.selectSession(CHILD)
        val oldJob = vm.executeAgentRequestStream("first run", targetSessionId = CHILD)
        historyStarted.await()
        fixture.events.release(CHILD)
        vm.stopAgentSession(CHILD)
        runCurrent()
        try {
            assertTrue(auditStarted.isCompleted)
            assertFalse(oldJob.isActive)
            assertFalse(oldJob.isCompleted)
            vm.enqueueAgentRequest("next run", targetSessionId = CHILD, subAgentInstructionId = "next")
            runCurrent()
            assertEquals("next", vm.queuedRequests.value.single().subAgentInstructionId)
            assertSame(oldJob, fixture.jobs[CHILD])
            coVerify(exactly = 1) { fixture.persistence.buildHistory(CHILD, any()) }
        } finally {
            releaseAudit.complete(Unit)
        }
        oldJob.join()
        runCurrent()

        val nextJob = fixture.jobs.getValue(CHILD)
        assertNotSame(oldJob, nextJob)
        assertTrue(nextJob.isActive)
        assertFalse(CHILD in fixture.stopping)
        assertTrue(vm.queuedRequests.value.isEmpty())
        coVerify(exactly = 2) { fixture.persistence.buildHistory(CHILD, any()) }
        runCurrent()
        assertSame(nextJob, fixture.jobs[CHILD])
    }

    @Test
    fun oldStopAuditCannotClearReplacementJobOrConsumeItsQueue() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val historyStarted = CompletableDeferred<Unit>()
        val auditStarted = CompletableDeferred<Unit>()
        val releaseAudit = CompletableDeferred<Unit>()
        coEvery { fixture.persistence.buildHistory(CHILD, any()) } coAnswers {
            historyStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { fixture.journal.record(match { it.type == SubAgentEventType.STOPPED }, any()) } coAnswers {
            auditStarted.complete(Unit)
            releaseAudit.await()
            val finish = secondArg<(suspend () -> Boolean)?>()
            assertTrue(finish?.invoke() == true)
            mockk<SubAgentInteractionEntity>(relaxed = true)
        }
        vm.selectSession(CHILD)
        val oldJob = vm.executeAgentRequestStream("first run", targetSessionId = CHILD)
        historyStarted.await()
        fixture.events.release(CHILD)
        vm.stopAgentSession(CHILD)
        runCurrent()
        try {
            assertTrue(auditStarted.isCompleted)
            vm.enqueueAgentRequest("pending run", targetSessionId = CHILD, subAgentInstructionId = "pending")
            val replacement = vm.executeAgentRequestStream("replacement run", targetSessionId = CHILD)
            runCurrent()
            assertTrue(replacement.isActive)
            assertSame(replacement, fixture.jobs[CHILD])
            releaseAudit.complete(Unit)
            oldJob.join()
            runCurrent()

            assertSame(replacement, fixture.jobs[CHILD])
            assertTrue(replacement.isActive)
            assertSame(AgentUIState.Streaming, vm.agentStates.value[CHILD])
            assertEquals("pending", vm.queuedRequests.value.single().subAgentInstructionId)
            assertFalse(CHILD in fixture.stopping)
            coVerify(exactly = 2) { fixture.persistence.buildHistory(CHILD, any()) }
        } finally {
            releaseAudit.complete(Unit)
        }
    }

    @Test
    fun rejectedPlanKeepsQueuedRequestAndBackgroundNotificationPending() = runTest(dispatcher) {
        val vm = fixture.viewModel
        val workflowStarted = CompletableDeferred<Unit>()
        val reject = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            workflowStarted.complete(Unit)
            reject.await()
            emit(AgentEvent.PlanRejected)
        }
        vm.selectSession(PARENT)
        val run = vm.executeAgentRequestStream("make a plan", targetSessionId = PARENT, persistUserMessage = false)
        workflowStarted.await()
        vm.enqueueAgentRequest("queued before rejection", targetSessionId = PARENT)
        val notificationSeq = fixture.notifications.enqueue(PARENT, notification())
        runCurrent()
        val queued = vm.queuedRequests.value.single()
        reject.complete(Unit)
        run.join()
        runCurrent()

        assertEquals(listOf(queued), vm.queuedRequests.value)
        assertEquals(listOf(notificationSeq), fixture.notifications.peek(PARENT).map { it.seq })
        assertSame(AgentUIState.Idle, vm.agentStates.value[PARENT])
        assertFalse(PARENT in vm.completedSessions.value)
        assertNull(fixture.jobs[PARENT])
        verify(exactly = 1) { fixture.workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun stopAllFinishesOldCleanupBeforeAcceptingNextExplicitRequest() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        coEvery { fixture.recoveryRepository.holdSession(any(), "USER_STOPPED") } answers {
            order += "hold:${firstArg<String>()}"
            true
        }
        coEvery { fixture.recoveryRepository.clearExplicitHold(any()) } answers {
            order += "clear:${firstArg<String>()}"
            true
        }
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val input = firstArg<String>()
            flow {
                if (input == "old") {
                    started.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable) { releaseCleanup.await() } }
                } else {
                    order += "next"
                    nextStarted.complete(Unit)
                    emit(AgentEvent.Completed)
                }
            }
        }
        val old = fixture.viewModel.executeAgentRequestStream("old", targetSessionId = PARENT)
        started.await()
        fixture.viewModel.stopAllAgents()
        val next = fixture.viewModel.executeAgentRequestStream("next", targetSessionId = PARENT)
        runCurrent()
        assertFalse(nextStarted.isCompleted)
        releaseCleanup.complete(Unit)
        old.join()
        next.join()
        assertTrue(nextStarted.isCompleted)
        assertTrue(order.indexOf("hold:$PARENT") < order.lastIndexOf("clear:$PARENT"))
        assertTrue(order.lastIndexOf("clear:$PARENT") < order.indexOf("next"))
    }

    private fun notification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = "terminal",
        title = "Finished command",
        outcome = NotificationOutcome.COMPLETED
    )
}
