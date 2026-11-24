package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.agent.presentation.preview.PreparingToolPreviewSnapshot
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ToolPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private val vm get() = fixture.viewModel
    private val streams = listOf(PARENT, OTHER).associateWith { Channel<Delivery>(Channel.UNLIMITED) }
    private val pendingPersists = Channel<PendingPersist>(Channel.UNLIMITED)
    private val runningSnapshots = mutableListOf<Pair<String?, List<RunningToolOutput>>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.sessionRows[OTHER] = fixture.sessionRows.getValue(PARENT).copy(id = OTHER, title = "Other")
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val sessionId = requireNotNull(secondArg<AgentContext>().sessionId)
            flow {
                for (delivery in streams.getValue(sessionId)) {
                    emit(delivery.event)
                    delivery.consumed.complete(Unit)
                }
            }
        }
        coEvery {
            fixture.persistence.persist(
                sessionId = any(), role = MessageRole.TOOL, content = any(), id = any(),
                toolCallId = any(), toolName = any(), toolArgs = any(), isError = false,
                toolActionTitle = any(), recoveryRunId = any(), recoveryPlaceholder = true
            )
        } coAnswers {
            val pending = PendingPersist(firstArg(), arg(3), arg(5), arg(6), arg(7))
            pendingPersists.send(pending)
            pending.release.await()
            pending.persisted.complete(Unit)
            Unit
        }
        vm.setWorkspace("/work")
        dispatcher.scheduler.runCurrent()
        vm.selectSession(PARENT)
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
    fun delayedPersistKeepsDraftUntilSameIdRunsAndFinishRemovesIt() = runTest(dispatcher) {
        collectPreviews()
        val callId = "write-1"
        val draft = PreparingToolPreviewSnapshot("tool_$callId", "writeFile", "draft arguments")
        val sibling = PreparingToolPreviewSnapshot("tool_read-2", "readFile", "other arguments")
        val args = """{"path":"/work/result.txt","content":"ready"}"""
        val run = vm.executeAgentRequestStream("write", targetSessionId = PARENT)
        deliver(PARENT, AgentEvent.ToolCallArgumentsPreview(callId, draft.name, draft.accumulated)).await()
        deliver(PARENT, AgentEvent.ToolCallArgumentsPreview("read-2", sibling.name, sibling.accumulated)).await()
        awaitPreviews(PARENT, listOf(draft, sibling))

        deliver(PARENT, AgentEvent.ToolCallStarted(callId, draft.name, args)).await()
        val pending = pendingPersists.receive()
        runCurrent()
        assertEquals(PARENT, pending.sessionId)
        assertEquals(draft.id, pending.messageId)
        assertEquals(callId, pending.callId)
        assertEquals(draft.name, pending.toolName)
        assertEquals(args, pending.toolArgs)
        assertFalse(pending.persisted.isCompleted)
        assertPreviews(PARENT, listOf(draft, sibling))

        pending.release.complete(Unit)
        pending.persisted.await()
        assertTrue(pending.persisted.isCompleted)
        awaitPreviews(PARENT, listOf(sibling), listOf(RunningToolOutput(draft.id, "", draft.name, args)))

        val finished = CompletableDeferred<Unit>()
        deliver(PARENT, AgentEvent.ToolCallFinished(callId, draft.name, "written", false, persisted = finished)).await()
        finished.await()
        assertTrue(finished.isCompleted)
        awaitPreviews(PARENT, listOf(sibling))
        deliver(PARENT, AgentEvent.Completed).await()
        streams.getValue(PARENT).close()
        run.join()
        runCurrent()
        assertPreviews(PARENT)
    }

    @Test
    fun sameCallIdStaysSessionScopedAndDeletedDraftCannotReturn() = runTest(dispatcher) {
        collectPreviews()
        val callId = "shared-call"
        val parentDraft = PreparingToolPreviewSnapshot("tool_$callId", "writeFile", "parent arguments")
        val otherDraft = PreparingToolPreviewSnapshot("tool_$callId", "readFile", "other arguments")
        val parentRun = vm.executeAgentRequestStream("parent", targetSessionId = PARENT)
        val otherRun = vm.executeAgentRequestStream("other", targetSessionId = OTHER)
        deliver(PARENT, AgentEvent.ToolCallArgumentsPreview(callId, parentDraft.name, parentDraft.accumulated)).await()
        deliver(OTHER, AgentEvent.ToolCallArgumentsPreview(callId, otherDraft.name, otherDraft.accumulated)).await()
        awaitPreviews(PARENT, listOf(parentDraft))
        vm.selectSession(OTHER)
        awaitPreviews(OTHER, listOf(otherDraft))

        deliver(PARENT, AgentEvent.ToolCallStarted(callId, parentDraft.name, parentDraft.accumulated)).await()
        val parentPersist = pendingPersists.receive()
        deliver(OTHER, AgentEvent.ToolCallStarted(callId, otherDraft.name, otherDraft.accumulated)).await()
        val otherPersist = pendingPersists.receive()
        runCurrent()
        assertEquals(PARENT, parentPersist.sessionId)
        assertEquals(OTHER, otherPersist.sessionId)
        assertFalse(parentPersist.persisted.isCompleted)
        assertFalse(otherPersist.persisted.isCompleted)
        assertPreviews(OTHER, listOf(otherDraft))
        vm.selectSession(PARENT)
        awaitPreviews(PARENT, listOf(parentDraft))

        parentPersist.release.complete(Unit)
        parentPersist.persisted.await()
        val parentRunning = RunningToolOutput(parentDraft.id, "", parentDraft.name, parentDraft.accumulated)
        awaitPreviews(PARENT, running = listOf(parentRunning))
        vm.selectSession(OTHER)
        awaitPreviews(OTHER, listOf(otherDraft))
        assertFalse(otherPersist.persisted.isCompleted)

        vm.selectSession(PARENT)
        awaitPreviews(PARENT, running = listOf(parentRunning))
        vm.deleteSession(OTHER).join()
        otherRun.join()
        otherPersist.release.complete(Unit)
        runCurrent()
        assertTrue(otherRun.isCancelled)
        assertFalse(fixture.sessionRows.containsKey(OTHER))
        assertFalse(otherPersist.persisted.isCompleted)
        assertPreviews(PARENT, running = listOf(parentRunning))
        vm.selectSession(OTHER)
        awaitPreviews(OTHER)
        vm.selectSession(PARENT)
        awaitPreviews(PARENT, running = listOf(parentRunning))

        val finished = CompletableDeferred<Unit>()
        deliver(PARENT, AgentEvent.ToolCallFinished(callId, parentDraft.name, "written", false, persisted = finished)).await()
        finished.await()
        awaitPreviews(PARENT)
        deliver(PARENT, AgentEvent.Completed).await()
        streams.getValue(PARENT).close()
        parentRun.join()
        runCurrent()
        assertPreviews(PARENT)
    }

    @Test
    fun failedPersistClearsDraftWithoutPublishingRunning() = runTest(dispatcher) {
        collectPreviews()
        val callId = "failed-write"
        val draft = PreparingToolPreviewSnapshot("tool_$callId", "writeFile", "arguments")
        val run = vm.executeAgentRequestStream("fail", targetSessionId = PARENT)
        deliver(PARENT, AgentEvent.ToolCallArgumentsPreview(callId, draft.name, draft.accumulated)).await()
        awaitPreviews(PARENT, listOf(draft))
        deliver(PARENT, AgentEvent.ToolCallStarted(callId, draft.name, draft.accumulated)).await()
        val pending = pendingPersists.receive()
        runCurrent()
        assertPreviews(PARENT, listOf(draft))
        assertFalse(pending.persisted.isCompleted)

        pending.release.completeExceptionally(IOException("placeholder write failed"))
        run.join()
        runCurrent()
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        assertFalse(pending.persisted.isCompleted)
        assertPreviews(PARENT)
        assertTrue(runningSnapshots.all { it.second.isEmpty() })
    }

    @Test
    fun cancellationDuringPersistClearsDraftAndLateReleaseCannotReviveIt() = runTest(dispatcher) {
        collectPreviews()
        val callId = "cancelled-write"
        val draft = PreparingToolPreviewSnapshot("tool_$callId", "writeFile", "arguments")
        val run = vm.executeAgentRequestStream("cancel", targetSessionId = PARENT)
        deliver(PARENT, AgentEvent.ToolCallArgumentsPreview(callId, draft.name, draft.accumulated)).await()
        awaitPreviews(PARENT, listOf(draft))
        deliver(PARENT, AgentEvent.ToolCallStarted(callId, draft.name, draft.accumulated)).await()
        val pending = pendingPersists.receive()
        runCurrent()
        assertFalse(pending.persisted.isCompleted)
        assertPreviews(PARENT, listOf(draft))

        vm.stopAgentSession(PARENT)
        run.join()
        runCurrent()
        assertTrue(run.isCancelled)
        assertPreviews(PARENT)
        pending.release.complete(Unit)
        runCurrent()
        assertFalse(pending.persisted.isCompleted)
        assertPreviews(PARENT)
        assertTrue(runningSnapshots.all { it.second.isEmpty() })
    }

    private suspend fun TestScope.collectPreviews() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.preparingToolArguments.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.runningToolPreviewSnapshot.collect { runningSnapshots += it }
        }
        awaitPreviews(PARENT)
        assertTrue(runningSnapshots.isNotEmpty())
    }

    private suspend fun awaitPreviews(
        sessionId: String,
        preparing: List<PreparingToolPreviewSnapshot> = emptyList(),
        running: List<RunningToolOutput> = emptyList()
    ) {
        combine(vm.preparingToolArguments, vm.runningToolPreviewSnapshot) { actualPreparing, actualRunning ->
            actualPreparing == (sessionId to preparing) && actualRunning == (sessionId to running)
        }.first { it }
        assertPreviews(sessionId, preparing, running)
    }

    private fun assertPreviews(
        sessionId: String,
        preparing: List<PreparingToolPreviewSnapshot> = emptyList(),
        running: List<RunningToolOutput> = emptyList()
    ) {
        assertEquals(sessionId to preparing, vm.preparingToolArguments.value)
        assertEquals(sessionId to running, vm.runningToolPreviewSnapshot.value)
    }

    private suspend fun deliver(sessionId: String, event: AgentEvent): CompletableDeferred<Unit> {
        val delivery = Delivery(event)
        streams.getValue(sessionId).send(delivery)
        return delivery.consumed
    }

    private data class Delivery(
        val event: AgentEvent,
        val consumed: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private data class PendingPersist(
        val sessionId: String,
        val messageId: String,
        val callId: String?,
        val toolName: String?,
        val toolArgs: String?,
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        val persisted: CompletableDeferred<Unit> = CompletableDeferred()
    )

    companion object {
        private const val OTHER = "other-preview-session"
    }
}
