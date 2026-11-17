package com.aicode.feature.agent.presentation

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubAgentJournalFixture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInstructionGatewayTest {
    @Before
    fun silenceLogging() {
        mockkObject(FileLogger)
        every { FileLogger.e(any(), any(), any()) } just Runs
    }

    @After
    fun restoreLogging() {
        unmockkObject(FileLogger)
    }

    @Test
    fun fullUserInstructionIsDurableBeforeQueueingWithoutNotificationLaneOrExecutionClaim() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        val order = mutableListOf<String>()
        fixture.interactions.beforeInsert = { order += "journal" }
        val input = "  full user instruction\n".repeat(2000) + "end  "
        var queuedId: String? = null
        val result = gateway.send("parent", "child", input) { child, body, workspace, instructionId ->
            order += "queue"
            assertEquals("child", child)
            assertEquals(input.trim(), body)
            assertEquals("/work", workspace)
            queuedId = instructionId
        }
        assertTrue(result.sent)
        assertNull(result.failure)
        assertEquals(listOf("journal", "queue"), order)
        val stored = fixture.interactions.getAllOnce().single()
        assertEquals(queuedId, stored.id)
        assertEquals(input.trim(), stored.content)
        assertEquals("MESSAGE_FROM_PARENT", stored.kind)
        assertEquals("USER", stored.origin)
        assertNull(stored.instructionId)
        assertNull(stored.sourceMessageId)
        assertTrue(received.isEmpty())
        assertEquals(0, fixture.bus.activeCount)
        coVerify(exactly = 0) { fixture.sessionUseCase.upsertSession(any()) }
        coVerify(exactly = 0) { fixture.sessionUseCase.updateProviderModel(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.sessionUseCase.updateReasoningEffort(any(), any()) }
    }

    @Test
    fun deliberateRepeatedUserInputHasDistinctInstructionIds() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val queuedIds = mutableListOf<String>()
        repeat(2) {
            assertTrue(gateway.send("parent", "child", "same input") { _, _, _, id -> queuedIds += id }.sent)
        }
        val rows = fixture.interactions.getAllOnce()
        assertEquals(2, rows.size)
        assertEquals(2, queuedIds.toSet().size)
        assertEquals(queuedIds, rows.map { it.id })
        assertTrue(rows.all { it.content == "same input" && it.origin == "USER" })
    }

    @Test
    fun bothActualParentAndWorkspaceMustMatchBeforeAcceptance() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(parentId = "other-parent")
        assertEquals(SubAgentInstructionFailure.NOT_OWNED,
            gateway.send("parent", "child", "go") { _, _, _, _ -> fail("Wrong parent") }.failure)
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(parentId = "parent", workspacePath = "/other")
        assertEquals(SubAgentInstructionFailure.NOT_OWNED,
            gateway.send("parent", "child", "go") { _, _, _, _ -> fail("Wrong workspace") }.failure)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun missingParentChildAndEmptyDraftFailWithoutQueueing() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        assertEquals(SubAgentInstructionFailure.EMPTY,
            gateway.send("parent", "child", " \n ") { _, _, _, _ -> fail() }.failure)
        assertEquals(SubAgentInstructionFailure.SESSION_MISSING,
            gateway.send(null, "child", "go") { _, _, _, _ -> fail() }.failure)
        assertEquals(SubAgentInstructionFailure.SESSION_MISSING,
            gateway.send("missing", "child", "go") { _, _, _, _ -> fail() }.failure)
        fixture.sessionRows.remove("child")
        assertEquals(SubAgentInstructionFailure.SESSION_MISSING,
            gateway.send("parent", "child", "go") { _, _, _, _ -> fail() }.failure)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun journalFailureCannotQueueAnUnrecordedRequestOrReportSent() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.interactions.insertFailure = IllegalStateException("disk full")
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val draft = "retain draft"
        val result = gateway.send("parent", "child", draft) { _, _, _, _ -> fail("Journal failed") }
        assertFalse(result.sent)
        assertEquals(SubAgentInstructionFailure.SEND_FAILED, result.failure)
        assertEquals("retain draft", draft)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun enqueueFailureKeepsAcceptedAuditButDoesNotClaimExecutionOrClearDraft() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val draft = "keep this input"
        val result = gateway.send("parent", "child", draft) { _, _, _, _ ->
            throw IllegalStateException("queue unavailable")
        }
        assertFalse(result.sent)
        assertEquals(SubAgentInstructionFailure.SEND_FAILED, result.failure)
        val stored = fixture.interactions.getAllOnce().single()
        assertEquals(draft, stored.content)
        assertEquals("MESSAGE_FROM_PARENT", stored.kind)
        assertEquals("USER", stored.origin)
        assertEquals(0, fixture.bus.activeCount)
    }

    @Test
    fun cancellationBeforeEnteringSendIsNotSwallowedEvenForEmptyInput() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        var cancelled = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            try {
                gateway.send("parent", "child", "") { _, _, _, _ -> fail() }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        caller.join()
        assertTrue(cancelled)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
        coVerify(exactly = 0) { fixture.sessionUseCase.getSessionById(any()) }
    }

    @Test
    fun cancellationDuringValidationDoesNotAcceptOrQueue() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val validating = CompletableDeferred<Unit>()
        coEvery { fixture.sessionUseCase.getSessionById("child") } coAnswers {
            validating.complete(Unit)
            awaitCancellation()
        }
        var cancellationPropagated = false
        val caller = launch {
            try {
                gateway.send("parent", "child", "go") { _, _, _, _ -> fail("Cancelled before acceptance") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }
        }
        validating.await()
        caller.cancel()
        caller.join()
        assertTrue(cancellationPropagated)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun cancellationAfterLookupButBeforeAcceptanceDoesNotEnterNonCancellableRegion() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        coEvery { fixture.sessionUseCase.getSessionById("parent") } coAnswers {
            currentCoroutineContext().cancel()
            fixture.sessionRows["parent"]
        }
        var cancellationPropagated = false
        val caller = launch {
            try {
                gateway.send("parent", "child", "go") { _, _, _, _ -> fail("Not accepted") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }
        }
        caller.join()
        assertTrue(cancellationPropagated)
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun leavingCompositionAfterAcceptanceStillRecordsAndQueuesExactlyOnce() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        val recording = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.interactions.beforeInsert = {
            recording.complete(Unit)
            release.await()
        }
        var queued = 0
        val caller = launch {
            gateway.send("parent", "child", "go") { _, _, _, _ -> queued++ }
        }
        recording.await()
        caller.cancel()
        release.complete(Unit)
        caller.join()
        assertEquals(1, queued)
        assertEquals("USER", fixture.interactions.getAllOnce().single().origin)
        assertEquals(0, fixture.bus.activeCount)
    }

    @Test
    fun reopeningAnAcceptedButUnstartedInstructionDoesNotReplayIt() = runTest {
        val fixture = SubAgentJournalFixture()
        val gateway = SubAgentInstructionGateway(fixture.sessionUseCase, fixture.bus)
        var enqueued = 0
        val result = gateway.send("parent", "child", "pending request") { _, _, _, _ -> enqueued++ }
        assertTrue(result.sent)
        val coldBus = SubAgentEventBus(fixture.journal)
        val restoredEvents = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coldBus.events.collect { restoredEvents += it } }
        val stored = fixture.interactions.getAllOnce().single()
        assertEquals(SubAgentEventType.MESSAGE_FROM_PARENT.name, stored.kind)
        assertEquals("USER", stored.origin)
        assertEquals(1, enqueued)
        assertTrue(restoredEvents.isEmpty())
        assertEquals(0, coldBus.activeCount)
    }
}
