package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.R
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.domain.command.CompressCommandHandler
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.context.CompactionProgress
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.agent.presentation.component.RewindOption
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class CompactionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private lateinit var context: Context
    private val automaticRuns = Channel<AutomaticRun>(Channel.UNLIMITED)
    private val manualRuns = Channel<ManualRun>(Channel.UNLIMITED)
    private val persisted = mutableListOf<PersistedMessage>()
    private var compressPersistenceGate: CompletableDeferred<Unit>? = null
    private var compressPersistEntered = CompletableDeferred<CommandInvocation>()
    private val vm get() = fixture.viewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        context = ApplicationProvider.getApplicationContext()
        fixture = SubAgentViewModelQueueFixture(context)
        fixture.sessionRows[OTHER] = fixture.sessionRows.getValue(PARENT).copy(id = OTHER, title = "Other")
        coEvery { fixture.sessions.getMostRecentSessionOfWorkspace(any()) } answers { fixture.sessionRows[PARENT] }
        coEvery { fixture.sessions.getFirstSessionOfWorkspace(any()) } answers {
            fixture.sessionRows.values.firstOrNull { it.workspacePath == firstArg<String>() && it.parentId == null }
        }
        coEvery { fixture.sessions.isSessionEmpty(any()) } returns true
        every { fixture.agentDefinitions.findIncludingDisabled(any()) } returns null
        installBackend()
        installPersistence()
        vm.setWorkspace(WORKSPACE)
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
    fun automaticProgressIsMonotonicAndOnlyCompletedFinishedClearsItsUsage() = runTest(dispatcher, timeout = 15.seconds) {
        seedUsage(OTHER, OTHER_USAGE)
        val run = startAutomatic(PARENT)
        run.send(USAGE)
        assertHidden(PARENT)
        run.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        assertCompacting(PARENT, 0)
        run.send(progress(37))
        assertCompacting(PARENT, 37)
        run.send(progress(12))
        assertCompacting(PARENT, 37)
        run.send(progress(90))
        assertCompacting(PARENT, 90)
        run.send(progress(95, phase = CompactionPhase.SAVING))
        assertCompacting(PARENT, 95)
        run.send(progress(100, phase = CompactionPhase.COMPLETED))
        assertCompacting(PARENT, 100)
        assertEquals(mapOf(PARENT to USAGE, OTHER to OTHER_USAGE), vm.contextUsages.value)

        run.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertHidden(PARENT)
        assertEquals(mapOf(OTHER to OTHER_USAGE), vm.contextUsages.value)
        cancel(run.owner)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun manualProgressStartsAtZeroBeforeFirstEventAndHidesAfterCompletedFinished() = runTest(dispatcher, timeout = 15.seconds) {
        seedUsage(PARENT, USAGE)
        seedUsage(OTHER, OTHER_USAGE)
        vm.selectSession(PARENT)
        val call = startManual(PARENT)
        assertCompacting(PARENT, 0)
        assertSame(AgentUIState.Idle, vm.agentStates.value[PARENT])
        call.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        assertCompacting(PARENT, 0)
        call.send(progress(37))
        assertCompacting(PARENT, 37)
        call.send(progress(90))
        assertCompacting(PARENT, 90)
        call.send(progress(95, phase = CompactionPhase.SAVING))
        assertCompacting(PARENT, 95)
        call.send(progress(100, phase = CompactionPhase.COMPLETED))
        assertCompacting(PARENT, 100)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        call.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertHidden(PARENT)
        assertEquals(mapOf(OTHER to OTHER_USAGE), vm.contextUsages.value)
        finish(call, changed = true)
        assertHidden(PARENT)
        assertFalse(vm.isRunning)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun automaticAndManualSessionsKeepIndependentProgressAcrossImmediateSelectionChanges() = runTest(dispatcher, timeout = 15.seconds) {
        val automatic = startAutomatic(PARENT)
        automatic.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        automatic.send(progress(37))
        val manual = startManual(OTHER)
        manual.send(AgentEvent.CompactionStarted(12_000, "other-attempt"))
        manual.send(progress(90, "other-attempt"))
        assertCompacting(PARENT, 37)

        vm.selectSession(OTHER)
        assertCompacting(OTHER, 90)
        automatic.send(progress(95, phase = CompactionPhase.SAVING))
        assertCompacting(OTHER, 90)
        vm.selectSession(PARENT)
        assertCompacting(PARENT, 95)
        manual.send(progress(100, "other-attempt", CompactionPhase.COMPLETED))
        manual.send(AgentEvent.CompactionFinished("other-attempt"))
        assertCompacting(PARENT, 95)
        vm.selectSession(OTHER)
        assertHidden(OTHER)
        vm.selectSession(CHILD)
        assertHidden(CHILD)
        vm.selectSession(PARENT)
        assertCompacting(PARENT, 95)
        finish(manual, changed = true)
        cancel(automatic.owner)
    }

    @Test
    fun bothEventPathsRejectWrongAttemptsResetNewAttemptsAndFreezeEveryTerminalPhase() = runTest(dispatcher, timeout = 15.seconds) {
        for (manual in listOf(false, true)) {
            seedUsage(PARENT, USAGE)
            val automatic = if (manual) null else startAutomatic(PARENT)
            val call = if (manual) startManual(PARENT) else null
            val send: suspend (AgentEvent) -> Unit = if (call != null) call::send else automatic!!::send
            send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
            send(progress(37))
            send(progress(90, "wrong-attempt"))
            send(AgentEvent.CompactionFinished("wrong-attempt"))
            send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", "wrong-attempt"))
            assertCompacting(PARENT, 37)
            assertEquals(USAGE, vm.contextUsages.value[PARENT])
            assertTrue(persisted.isEmpty())

            send(AgentEvent.CompactionStarted(18_000, "new-attempt"))
            assertCompacting(PARENT, 0)
            send(progress(37, "new-attempt"))
            send(progress(100, phase = CompactionPhase.COMPLETED))
            send(AgentEvent.CompactionFinished(ATTEMPT))
            send(AgentEvent.CompactionFailed("compaction_fakecode", ATTEMPT))
            assertCompacting(PARENT, 37)

            for (phase in listOf(CompactionPhase.UNCHANGED, CompactionPhase.FAILED, CompactionPhase.CANCELLED, CompactionPhase.COMPLETED)) {
                val attempt = "terminal-$phase"
                send(AgentEvent.CompactionStarted(18_000, attempt))
                assertCompacting(PARENT, 0)
                send(progress(37, attempt))
                val terminalPercent = if (phase == CompactionPhase.COMPLETED || phase == CompactionPhase.UNCHANGED) 100 else 37
                send(progress(terminalPercent, attempt, phase))
                send(progress(95, attempt, CompactionPhase.SAVING))
                send(progress(100, attempt, CompactionPhase.COMPLETED))
                assertCompacting(PARENT, terminalPercent)
                assertEquals(USAGE, vm.contextUsages.value[PARENT])
                send(AgentEvent.CompactionFinished(attempt))
                assertHidden(PARENT)
                if (phase == CompactionPhase.COMPLETED) assertFalse(PARENT in vm.contextUsages.value)
                else assertEquals(USAGE, vm.contextUsages.value[PARENT])
                send(progress(100, attempt, CompactionPhase.COMPLETED))
                send(AgentEvent.CompactionFinished(attempt))
                send(AgentEvent.CompactionFailed("compaction_fakecode", attempt))
                assertHidden(PARENT)
            }
            if (call != null) finish(call, changed = true) else cancel(automatic!!.owner)
            assertTrue(persisted.isEmpty())
        }
    }

    @Test
    fun oldAutomaticJobCannotClearReplacementWithCompactionOrWorkflowTerminalEventsOrFinally() = runTest(dispatcher, timeout = 15.seconds) {
        val old = startAutomatic(PARENT)
        old.send(USAGE)
        old.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        old.send(progress(37))
        val replacement = startAutomatic(PARENT)
        replacement.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        replacement.send(progress(90))
        assertNotSame(old.owner, replacement.owner)

        old.send(AgentEvent.CompactionStarted(18_000, "old-retry"))
        old.send(progress(100, phase = CompactionPhase.COMPLETED))
        old.send(AgentEvent.CompactionFinished(ATTEMPT))
        old.send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", ATTEMPT))
        old.send(AgentEvent.Failed("obsolete workflow"))
        old.send(AgentEvent.Completed)
        assertCompacting(PARENT, 90)
        old.events.close()
        old.owner.join()
        assertCompacting(PARENT, 90)
        assertSame(AgentUIState.Streaming, vm.agentStates.value[PARENT])
        assertSame(replacement.owner, fixture.jobs[PARENT])
        assertTrue(replacement.owner.isActive)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        assertTrue(persisted.isEmpty())
        cancel(replacement.owner)
    }

    @Test
    fun oldManualCallbackAndFinallyCannotClearNewAutomaticOwnerEvenWithSameAttemptId() = runTest(dispatcher, timeout = 15.seconds) {
        seedUsage(PARENT, USAGE)
        val old = startManual(PARENT)
        old.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        old.send(progress(37))
        val replacement = startAutomatic(PARENT)
        replacement.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        replacement.send(progress(90))

        old.send(AgentEvent.CompactionStarted(18_000, "obsolete"))
        old.send(progress(100, phase = CompactionPhase.COMPLETED))
        old.send(AgentEvent.CompactionFinished(ATTEMPT))
        old.send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", ATTEMPT))
        assertCompacting(PARENT, 90)
        finish(old, changed = false)
        old.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertCompacting(PARENT, 90)
        assertSame(replacement.owner, fixture.jobs[PARENT])
        assertTrue(replacement.owner.isActive)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        assertTrue(persisted.isEmpty())
        cancel(replacement.owner)
    }

    @Test
    fun stoppingCancellationAndStopAllLeaveNeitherStickyBusyNorStalePercentages() = runTest(dispatcher, timeout = 15.seconds) {
        val manual = startManual(PARENT)
        manual.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        manual.send(progress(37))
        fixture.stopping.add(PARENT)
        try {
            manual.send(AgentEvent.CompactionStarted(18_000, "stopping-attempt"))
            manual.send(progress(100, phase = CompactionPhase.COMPLETED))
            manual.send(AgentEvent.CompactionFinished(ATTEMPT))
            manual.send(AgentEvent.CompactionFailed("compaction_fakecode", ATTEMPT))
            vm.compactSession(PARENT)
            assertCompacting(PARENT, 37)
            assertSame(manual.owner, fixture.jobs[PARENT])
        } finally {
            fixture.stopping.remove(PARENT)
        }
        vm.stopAgentSession(PARENT)
        manual.owner.join()
        assertHidden(PARENT)
        assertFalse(vm.isRunning)
        fixture.jobs[PARENT] = manual.owner
        manual.send(AgentEvent.CompactionStarted(18_000, "inactive-owner"))
        manual.send(progress(100, "inactive-owner", CompactionPhase.COMPLETED))
        assertHidden(PARENT)
        fixture.jobs.remove(PARENT)

        val cancelled = startAutomatic(PARENT)
        cancelled.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        cancelled.send(progress(90))
        cancel(cancelled.owner)
        assertHidden(PARENT)
        val automatic = startAutomatic(PARENT)
        automatic.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        automatic.send(progress(95, phase = CompactionPhase.SAVING))
        val other = startManual(OTHER)
        other.send(AgentEvent.CompactionStarted(12_000, "other-attempt"))
        other.send(progress(37, "other-attempt"))
        vm.stopAllAgents()
        automatic.owner.join()
        other.owner.join()
        other.send(AgentEvent.CompactionStarted(12_000, "late-after-stop-all"))
        other.send(AgentEvent.CompactionFailed("compaction_fakecode", "other-attempt"))
        assertHidden(PARENT)
        vm.selectSession(OTHER)
        assertHidden(OTHER)
        assertFalse(vm.isRunning)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue("Unexpected stop records: $persisted", persisted.isEmpty())
    }

    @Test
    fun deletionRejectsCallbacksBeforeAndAfterRuntimeCleanupWithoutAffectingOtherSession() = runTest(dispatcher, timeout = 15.seconds) {
        val deleting = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.sessions.deleteSession(PARENT) } coAnswers {
            deleting.complete(Unit)
            releaseDeletion.await()
            fixture.sessionRows.remove(PARENT)
            listOf(PARENT)
        }
        val target = startManual(PARENT)
        target.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        target.send(progress(37))
        val other = startManual(OTHER)
        other.send(AgentEvent.CompactionStarted(12_000, "other-attempt"))
        other.send(progress(90, "other-attempt"))
        val deletion = vm.deleteSession(PARENT)
        deleting.await()
        try {
            target.send(AgentEvent.CompactionStarted(18_000, "deleted-attempt"))
            target.send(progress(100, phase = CompactionPhase.COMPLETED))
            target.send(AgentEvent.CompactionFinished(ATTEMPT))
            target.send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", ATTEMPT))
            assertCompacting(PARENT, 37)
            vm.compactSession(PARENT)
            assertSame(target.owner, fixture.jobs[PARENT])
        } finally {
            releaseDeletion.complete(Unit)
        }
        deletion.join()
        target.owner.join()
        vm.selectSession(PARENT)
        assertHidden(PARENT)
        target.send(AgentEvent.CompactionStarted(18_000, "late-after-delete"))
        target.send(progress(100, "late-after-delete", CompactionPhase.COMPLETED))
        target.send(AgentEvent.CompactionFinished("late-after-delete"))
        vm.compactSession(PARENT)
        assertHidden(PARENT)
        assertNull(fixture.jobs[PARENT])
        vm.selectSession(OTHER)
        assertCompacting(OTHER, 90)
        assertSame(other.owner, fixture.jobs[OTHER])
        assertTrue(persisted.isEmpty())
        cancel(other.owner)
    }

    @Test
    fun rewindCancelsOnlyItsSessionAndClearsItsCompactionAndUsageBeforeLateCallbacks() = runTest(dispatcher, timeout = 15.seconds) {
        seedUsage(PARENT, USAGE)
        val target = startManual(PARENT)
        target.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        target.send(progress(95, phase = CompactionPhase.SAVING))
        val other = startManual(OTHER)
        other.send(AgentEvent.CompactionStarted(12_000, "other-attempt"))
        other.send(progress(37, "other-attempt"))
        coEvery { fixture.messages.getMessageById("rewind-target") } returns AgentMessageEntity(
            id = "rewind-target", sessionId = PARENT, role = MessageRole.USER.name,
            content = "original prompt", timestamp = 42L
        )
        coEvery { fixture.dependencies.checkpointDao.getCheckpointBySessionAndMessage(PARENT, "rewind-target") } returns CheckpointEntity(
            id = "rewind-checkpoint", sessionId = PARENT, userMessageId = "rewind-target",
            promptSnippet = "original prompt", createdAt = 42L
        )
        val rewindOwner = RuntimeOwner(RuntimeOwnerKind.AGENT, PARENT)
        coEvery { fixture.executionFactory.freeze(any(), rewindOwner, any()) } throws LocalRuntimeAuthorizationRequired()
        var filledPrompt: String? = null
        vm.executeRewindOption("rewind-target", RewindOption.RESTORE_CONVERSATION) { prompt, attachments ->
            filledPrompt = prompt
            assertTrue(attachments.isEmpty())
        }.join()
        assertTrue(target.owner.isCancelled)
        assertHidden(PARENT)
        assertFalse(PARENT in vm.contextUsages.value)
        assertEquals("original prompt", filledPrompt)
        coVerify(exactly = 1) { fixture.persistence.rewindConversation(PARENT, 42L) }
        coVerify(exactly = 0) { fixture.executionFactory.freeze(any(), rewindOwner, any()) }
        coVerify(exactly = 0) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(any(), any()) }
        target.send(AgentEvent.CompactionStarted(18_000, "late-after-rewind"))
        target.send(progress(100, "late-after-rewind", CompactionPhase.COMPLETED))
        assertHidden(PARENT)
        vm.selectSession(OTHER)
        assertCompacting(OTHER, 37)
        assertTrue(other.owner.isActive)
        cancel(other.owner)
    }

    @Test
    fun duplicateManualRequestsAreGuardedBeforeStartDuringCompactionAndUntilWorkflowReturns() = runTest(dispatcher, timeout = 15.seconds) {
        vm.compactSession(PARENT)
        val owner = fixture.jobs.getValue(PARENT)
        vm.compactSession(PARENT)
        assertSame(owner, fixture.jobs[PARENT])
        val call = manualRuns.receive()
        assertSame(owner, call.owner)
        call.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        call.send(progress(37))
        vm.compactSession(PARENT)
        assertSame(owner, fixture.jobs[PARENT])
        call.send(progress(100, phase = CompactionPhase.COMPLETED))
        call.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertHidden(PARENT)
        assertTrue(owner.isActive)
        vm.compactSession(PARENT)
        dispatcher.scheduler.runCurrent()
        assertSame(owner, fixture.jobs[PARENT])
        coVerify(exactly = 1) { fixture.workflow.compactSession(any(), any(), any(), any()) }
        finish(call, changed = true)

        val automatic = startAutomatic(PARENT)
        vm.compactSession(PARENT)
        dispatcher.scheduler.runCurrent()
        assertSame(automatic.owner, fixture.jobs[PARENT])
        coVerify(exactly = 1) { fixture.workflow.compactSession(any(), any(), any(), any()) }
        cancel(automatic.owner)
        fixture.sessionRows[PARENT] = fixture.sessionRows.getValue(PARENT).copy(workspacePath = "/different-workspace")
        vm.compactSession(PARENT)
        fixture.jobs.getValue(PARENT).join()
        assertHidden(PARENT)
        vm.compactSession("missing-session")
        fixture.jobs.getValue("missing-session").join()
        vm.selectSession("missing-session")
        assertHidden("missing-session")
        coVerify(exactly = 1) { fixture.workflow.compactSession(any(), any(), any(), any()) }
    }

    @Test
    fun failuresAndExceptionsKeepUsageAndOnlyPersistSanitizedMessagesWithoutFalseSuccess() = runTest(dispatcher, timeout = 15.seconds) {
        val automatic = startAutomatic(PARENT)
        automatic.send(USAGE)
        automatic.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        automatic.send(progress(37))
        automatic.send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", ATTEMPT))
        automatic.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertHidden(PARENT)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        cancel(automatic.owner)
        assertEquals(listOf(safeFailure()), persisted)
        persisted.clear()

        val manual = startManual(PARENT)
        manual.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        manual.send(progress(37))
        manual.send(AgentEvent.CompactionFailed("compaction_fakecode: $FAKE_SECRET", ATTEMPT))
        manual.send(AgentEvent.CompactionFinished(ATTEMPT))
        finish(manual, changed = false)
        assertHidden(PARENT)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        assertEquals(listOf(safeFailure()), persisted)
        persisted.clear()

        val throwing = startManual(PARENT)
        throwing.result.completeExceptionally(IllegalStateException("Authorization: Bearer $FAKE_SECRET"))
        throwing.owner.join()
        assertHidden(PARENT)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        val feedback = persisted.single()
        assertEquals(PARENT, feedback.sessionId)
        assertEquals(MessageRole.ASSISTANT, feedback.role)
        assertEquals(context.getString(R.string.agent_compaction_failed, context.getString(R.string.compaction_error_incomplete)), feedback.content)
        assertTrue(feedback.requireSession)
        assertFalse(feedback.content.contains(FAKE_SECRET))
        assertFalse(vm.agentStates.value[PARENT] is AgentUIState.Result)
        assertFalse(PARENT in vm.completedSessions.value)
        assertFalse(vm.isRunning)
    }

    @Test
    fun compressionFailuresDistinguishTotalFirstContentIdleAndMissingTerminal() = runTest(dispatcher, timeout = 15.seconds) {
        val cases = listOf(
            "compaction_total_timeout" to R.string.compaction_error_total_timeout,
            "compaction_first_chunk_timeout" to R.string.compaction_error_first_content_timeout,
            "compaction_idle_timeout" to R.string.compaction_error_idle_timeout,
            "compaction_no_final" to R.string.compaction_error_missing_terminal,
            "compaction_missing_stop" to R.string.compaction_error_missing_terminal,
            "compaction_upstream_interrupted" to R.string.compaction_error_upstream_interrupted
        )
        for ((code, resource) in cases) {
            val manual = startManual(PARENT)
            manual.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
            manual.send(AgentEvent.CompactionFailed(code, ATTEMPT))
            manual.send(AgentEvent.CompactionFinished(ATTEMPT))
            finish(manual, changed = false)
            assertEquals(listOf(safeFailure().copy(content = context.getString(resource))), persisted)
            assertFalse(PARENT in vm.completedSessions.value)
            assertHidden(PARENT)
            persisted.clear()
        }
    }

    @Test
    fun automaticUpstreamInterruptionKeepsUsageAndReportsNoCompressionSuccess() = runTest(dispatcher, timeout = 15.seconds) {
        val automatic = startAutomatic(PARENT)
        automatic.send(USAGE)
        automatic.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        automatic.send(progress(37))
        automatic.send(AgentEvent.CompactionFailed("compaction_upstream_interrupted", ATTEMPT))
        automatic.send(AgentEvent.CompactionFinished(ATTEMPT))
        assertHidden(PARENT)
        assertEquals(USAGE, vm.contextUsages.value[PARENT])
        assertEquals(listOf(safeFailure().copy(content = context.getString(R.string.compaction_error_upstream_interrupted))), persisted)
        assertFalse(PARENT in vm.completedSessions.value)
        cancel(automatic.owner)
    }

    @Test
    fun unchangedAndIncompleteFinishedKeepUsageAndNeverPretendToHaveCompacted() = runTest(dispatcher, timeout = 15.seconds) {
        seedUsage(PARENT, USAGE)
        for (unchanged in listOf(true, false)) {
            val call = startManual(PARENT)
            call.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
            call.send(progress(37))
            if (unchanged) {
                call.send(progress(100, phase = CompactionPhase.UNCHANGED))
                call.send(progress(100, phase = CompactionPhase.COMPLETED))
                assertCompacting(PARENT, 100)
            }
            call.send(AgentEvent.CompactionFinished(ATTEMPT))
            assertHidden(PARENT)
            assertEquals(USAGE, vm.contextUsages.value[PARENT])
            finish(call, changed = false)
            assertEquals(listOf(PersistedMessage(PARENT, MessageRole.ASSISTANT, context.getString(R.string.agent_context_no_compaction), requireSession = true)), persisted)
            assertFalse(vm.agentStates.value[PARENT] is AgentUIState.Result)
            assertFalse(PARENT in vm.completedSessions.value)
            persisted.clear()
        }
    }

    @Test
    fun compressKeepsCapturedSessionAndEditorWhenPersistenceSuspendsAcrossSessionSwitch() = runTest(dispatcher, timeout = 15.seconds) {
        installCompressCommand()
        compressPersistenceGate = CompletableDeferred()
        val command = vm.executeAgentRequestStream(
            "/compress", currentFile = "/work/src/Original.kt", selectedCode = "original selection", targetSessionId = PARENT
        )
        val invocation = compressPersistEntered.await()
        assertEquals(PARENT, invocation.sessionId)
        vm.selectSession(OTHER)
        assertHidden(OTHER)
        compressPersistenceGate!!.complete(Unit)
        val call = manualRuns.receive()
        command.join()
        invocation.owner.join()
        assertEquals(PARENT, call.sessionId)
        assertEquals(PARENT, call.context.sessionId)
        assertEquals("/work/src/Original.kt", call.context.currentFile)
        assertEquals("original selection", call.context.selectedCode)
        assertEquals("kotlin", call.context.language)
        assertEquals(WORKSPACE, call.context.projectRoot)
        assertHidden(OTHER)
        call.send(AgentEvent.CompactionStarted(18_000, ATTEMPT))
        call.send(progress(37))
        assertHidden(OTHER)
        vm.selectSession(PARENT)
        assertCompacting(PARENT, 37)
        assertEquals(listOf(PARENT), persisted.filter { it.role == MessageRole.USER }.map { it.sessionId })
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.workflow.compactSession(OTHER, any(), any(), any()) }
        finish(call, changed = true)
    }

    @Test
    fun suspendedCompressCannotRestartAfterStopAllOrSessionDeletion() = runTest(dispatcher, timeout = 15.seconds) {
        installCompressCommand()
        for (delete in listOf(false, true)) {
            vm.selectSession(PARENT)
            compressPersistEntered = CompletableDeferred()
            compressPersistenceGate = CompletableDeferred()
            val command = vm.executeAgentRequestStream("/compress", targetSessionId = PARENT)
            val invocation = compressPersistEntered.await()
            if (delete) vm.deleteSession(PARENT).join() else vm.stopAllAgents()
            compressPersistenceGate!!.complete(Unit)
            command.join()
            invocation.owner.join()
            vm.selectSession(PARENT)
            assertHidden(PARENT)
            assertFalse(vm.isRunning)
            assertTrue(fixture.jobs.values.none { it.isActive })
            assertTrue(manualRuns.tryReceive().isFailure)
            coVerify(exactly = 0) { fixture.workflow.compactSession(any(), any(), any(), any()) }
        }
    }

    @Test
    fun manualContextAndEffectiveToolsAreFrozenAndMatchAutomaticDefinitionAndFallbackRules() = runTest(dispatcher, timeout = 15.seconds) {
        val names = listOf("readFile", "writeFile", "task", "messageParent", "goalStatus", "mcp__docs__read", "mcp__docs__write")
        val tools = names.map { toolName -> mockk<AgentTool> { every { name } returns toolName } }
        every { fixture.tools.getAvailableTools() } returns tools
        val definition = AgentDefinition(
            name = "snapshot-agent", description = "Compaction snapshot fixture",
            allowedTools = listOf("readFile", "writeFile", "task", "goalStatus", "mcp__docs__*"),
            disallowedTools = listOf("writeFile", "mcp__docs__write"), prompt = "Definition prompt snapshot"
        )
        val original = fixture.sessionRows.getValue(CHILD).copy(
            subagentType = definition.name, goal = "frozen goal", goalStatus = GoalStatus.ACTIVE.name,
            reasoningEffort = ReasoningEffort.HIGH.name
        )
        fixture.sessionRows[CHILD] = original
        every { fixture.agentDefinitions.findIncludingDisabled(definition.name) } returns definition
        vm.selectSession(OTHER)
        val manual = startManual(CHILD, "/work/src/Frozen.kt", "frozen selection")
        assertHidden(OTHER)
        assertSnapshot(manual.context, CHILD, definition)
        assertEquals(listOf("readFile", "messageParent", "goalStatus", "mcp__docs__read"), manual.tools.map { it.name })
        fixture.sessionRows[CHILD] = original.copy(goal = "replacement goal", goalStatus = GoalStatus.PAUSED.name)
        every { fixture.agentDefinitions.findIncludingDisabled(definition.name) } returns definition.copy(prompt = "Replacement prompt")
        every { fixture.tools.getAvailableTools() } returns emptyList()
        vm.setWorkspace("/different-workspace")
        dispatcher.scheduler.runCurrent()
        assertSnapshot(manual.context, CHILD, definition)
        assertEquals(listOf("readFile", "messageParent", "goalStatus", "mcp__docs__read"), manual.tools.map { it.name })
        finish(manual, changed = true)

        fixture.sessionRows[CHILD] = original
        every { fixture.agentDefinitions.findIncludingDisabled(definition.name) } returns definition
        every { fixture.tools.getAvailableTools() } returns tools
        vm.setWorkspace(WORKSPACE)
        dispatcher.scheduler.runCurrent()
        val automatic = startAutomatic(CHILD, "/work/src/Frozen.kt", "frozen selection")
        assertSnapshot(automatic.context, CHILD, definition)
        assertEquals(manual.tools, automatic.tools)
        cancel(automatic.owner)
        verify(exactly = 2) { fixture.agentDefinitions.findIncludingDisabled(definition.name) }

        for ((sid, active) in listOf(PARENT to false, CHILD to false, PARENT to true)) {
            fixture.sessionRows[sid] = fixture.sessionRows.getValue(sid).copy(
                subagentType = null, goalStatus = if (active) GoalStatus.ACTIVE.name else GoalStatus.PAUSED.name
            )
            val expected = names.filterNot {
                it == (if (sid == CHILD) "task" else "messageParent") || (!active && it == "goalStatus")
            }
            val fallbackManual = startManual(sid)
            assertNull(fallbackManual.context.agentDefinition)
            assertEquals(expected, fallbackManual.tools.map { it.name })
            finish(fallbackManual, changed = true)
            val fallbackAutomatic = startAutomatic(sid)
            assertNull(fallbackAutomatic.context.agentDefinition)
            assertEquals(fallbackManual.tools, fallbackAutomatic.tools)
            cancel(fallbackAutomatic.owner)
        }
    }

    private fun installBackend() {
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val snapshot = secondArg<AgentContext>()
            val run = AutomaticRun(snapshot, thirdArg(), fixture.jobs.getValue(requireNotNull(snapshot.sessionId)))
            flow {
                automaticRuns.send(run)
                for (delivery in run.events) {
                    emit(delivery.event)
                    delivery.consumed.complete(Unit)
                }
            }
        }
        coEvery { fixture.workflow.compactSession(any(), any(), any(), any()) } coAnswers {
            val sid = firstArg<String>()
            val run = ManualRun(sid, secondArg(), thirdArg(), arg(3), fixture.jobs.getValue(sid))
            manualRuns.send(run)
            run.result.await()
        }
    }

    private fun installPersistence() {
        coEvery {
            fixture.persistence.persist(
                sessionId = any(), role = any(), content = any(), id = any(), toolCalls = any(),
                toolCallId = any(), toolName = any(), toolArgs = any(), isError = any(), reasoning = any(),
                signature = any(), thinkingBlocksJson = any(), attachments = any(), inputTokens = any(),
                outputTokens = any(), cachedInputTokens = any(), isCompacted = any(), isContextExcluded = any(),
                contextInputTokens = any(), contextInputBudget = any(), contextEstimated = any(),
                contextBreakdown = any(), toolActionTitle = any(), toolStartedAtMs = any(),
                toolDurationMs = any(), turnDurationMs = any(), requireSession = any(),
                recoveryRunId = any(), recoveryStep = any(), recoveryPlaceholder = any(), recoverySupplemental = any()
            )
        } coAnswers {
            val sid = firstArg<String>()
            val role = secondArg<MessageRole>()
            val content = thirdArg<String>()
            if (role == MessageRole.USER && content == "/compress") {
                compressPersistEntered.complete(CommandInvocation(sid, requireNotNull(currentCoroutineContext()[Job])))
                compressPersistenceGate?.await()
            }
            persisted += PersistedMessage(sid, role, content, arg(6), arg(8), arg(26))
        }
    }

    private fun installCompressCommand() {
        every { fixture.commands.resolve("/compress") } returns SlashCommandRegistry.ResolvedCommand.Action(CompressCommandHandler(), "")
    }

    private suspend fun startAutomatic(sid: String, currentFile: String? = null, selectedCode: String? = null): AutomaticRun {
        val owner = vm.executeAgentRequestStream(
            "exercise compaction", currentFile = currentFile, selectedCode = selectedCode,
            projectRoot = WORKSPACE, targetSessionId = sid, persistUserMessage = false
        )
        val run = automaticRuns.receive()
        assertEquals(sid, run.context.sessionId)
        assertSame(owner, run.owner)
        assertSame(owner, fixture.jobs[sid])
        return run
    }

    private suspend fun startManual(sid: String, currentFile: String? = null, selectedCode: String? = null): ManualRun {
        vm.compactSession(sid, currentFile, selectedCode)
        val run = manualRuns.receive()
        assertEquals(sid, run.sessionId)
        assertSame(run.owner, fixture.jobs[sid])
        return run
    }

    private suspend fun seedUsage(sid: String, usage: AgentEvent.ContextUsage) {
        val run = startAutomatic(sid)
        run.send(usage)
        vm.contextUsages.first { it[sid] == usage }
        cancel(run.owner)
        assertEquals(usage, vm.contextUsages.value[sid])
    }

    private suspend fun finish(call: ManualRun, changed: Boolean) {
        call.result.complete(changed)
        call.owner.join()
        dispatcher.scheduler.runCurrent()
    }

    private suspend fun cancel(owner: Job) {
        owner.cancelAndJoin()
        dispatcher.scheduler.runCurrent()
    }

    private fun assertCompacting(sid: String, percent: Int) {
        dispatcher.scheduler.runCurrent()
        assertEquals(sid, vm.currentSessionId.value)
        assertEquals(sid to percent, vm.compactionPercent.value)
        assertTrue(vm.isCompacting.value)
    }

    private fun assertHidden(sid: String) {
        dispatcher.scheduler.runCurrent()
        assertEquals(sid, vm.currentSessionId.value)
        assertEquals(sid to 0, vm.compactionPercent.value)
        assertFalse(vm.isCompacting.value)
    }

    private fun assertSnapshot(snapshot: AgentContext, sid: String, definition: AgentDefinition) {
        assertEquals(sid, snapshot.sessionId)
        assertEquals("/work/src/Frozen.kt", snapshot.currentFile)
        assertEquals("frozen selection", snapshot.selectedCode)
        assertEquals("kotlin", snapshot.language)
        assertEquals(WORKSPACE, snapshot.projectRoot)
        assertEquals("frozen goal", snapshot.goal)
        assertEquals(GoalStatus.ACTIVE, snapshot.goalStatus)
        assertEquals("high", snapshot.reasoningEffort)
        assertSame(definition, snapshot.agentDefinition)
        assertEquals("Definition prompt snapshot", snapshot.agentDefinition?.prompt)
    }

    private fun safeFailure() = PersistedMessage(
        PARENT, MessageRole.TOOL, context.getString(R.string.compaction_error_incomplete),
        toolName = COMPACTION_FAILURE_TOOL_NAME, isError = true, requireSession = true
    )

    private fun progress(percent: Int, attempt: String = ATTEMPT, phase: CompactionPhase = CompactionPhase.SUMMARIZING) =
        AgentEvent.CompactionProgress(CompactionProgress(phase = phase, percent = percent), attempt)

    private data class PersistedMessage(
        val sessionId: String,
        val role: MessageRole,
        val content: String,
        val toolName: String? = null,
        val isError: Boolean = false,
        val requireSession: Boolean = false
    )

    private data class CommandInvocation(val sessionId: String, val owner: Job)
    private data class Delivery(val event: AgentEvent, val consumed: CompletableDeferred<Unit>)

    private class AutomaticRun(val context: AgentContext, val tools: List<AgentTool>, val owner: Job) {
        val events = Channel<Delivery>(Channel.UNLIMITED)

        suspend fun send(event: AgentEvent) {
            val consumed = CompletableDeferred<Unit>()
            events.send(Delivery(event, consumed))
            consumed.await()
        }
    }

    private class ManualRun(
        val sessionId: String,
        val context: AgentContext,
        val tools: List<AgentTool>,
        private val onEvent: suspend (AgentEvent) -> Unit,
        val owner: Job
    ) {
        val result = CompletableDeferred<Boolean>()

        suspend fun send(event: AgentEvent) = onEvent(event)
    }

    companion object {
        private const val WORKSPACE = "/work"
        private const val OTHER = "other"
        private const val ATTEMPT = "compaction-attempt"
        private const val FAKE_SECRET = "FAKE_COMPACTION_SECRET_9471"
        private val USAGE = AgentEvent.ContextUsage(18_000, 20_000, estimated = false)
        private val OTHER_USAGE = AgentEvent.ContextUsage(12_000, 20_000, estimated = true)
    }
}
