package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInteractionJournalTest {
    private fun event(type: SubAgentEventType, body: String = "", id: String = type.name) =
        SubAgentEvent("child", "parent", type, body, eventId = id, timestamp = 1234)

    private fun assistant(id: String, content: String, time: Long = 50) =
        AgentMessageEntity(id, "child", "ASSISTANT", content, time)

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Invalid relationship, source or identity must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun recordsUnabridgedBodiesAndDistinctSendsWithIdenticalText() = runTest {
        val fixture = SubAgentJournalFixture()
        val body = "complete interaction\n".repeat(2000) + "END"
        val events = listOf(
            event(SubAgentEventType.SPAWNED, body),
            event(SubAgentEventType.MESSAGE_FROM_PARENT, body, "send-1"),
            event(SubAgentEventType.MESSAGE_FROM_PARENT, body, "send-2"),
            event(SubAgentEventType.MESSAGE_FROM_SUB, body),
            event(SubAgentEventType.STOPPED, body).copy(origin = "PARENT"),
            event(SubAgentEventType.DELETED, body).copy(origin = "USER"),
            event(SubAgentEventType.FAILED, body)
        )
        events.forEach { fixture.journal.record(it) }
        val rows = fixture.interactions.getAllOnce()
        assertEquals(7, rows.size)
        assertTrue(rows.all { it.content == body && it.timestamp == 1234L })
        assertTrue(rows.all { it.title == "Child" && it.model == "parent-model" })
        assertEquals("PARENT", rows.single { it.id == "send-1" }.origin)
        assertEquals("SUB", rows.single { it.kind == "MESSAGE_FROM_SUB" }.origin)
        assertEquals("PARENT", rows.single { it.kind == "STOPPED" }.origin)
        assertEquals("USER", rows.single { it.kind == "DELETED" }.origin)
        assertEquals("SUB", rows.single { it.kind == "FAILED" }.origin)
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun completionUsesExactRealAssistantBodyInsteadOfNotificationDigest() = runTest {
        val fixture = SubAgentJournalFixture()
        val full = "complete output\n".repeat(2000) + "last line"
        fixture.messageRows["final"] = assistant("final", full)
        fixture.messageRows["later"] = assistant("later", "not this completion", 100)
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(model = "child-model")
        val recorded = fixture.journal.record(event(SubAgentEventType.COMPLETED, "short digest")
            .copy(sourceMessageId = "final"))
        assertEquals(full, recorded.content)
        assertEquals("final", recorded.sourceMessageId)
        assertEquals("child-model", recorded.model)
        coVerify(exactly = 1) { fixture.messages.getMessageById("final") }
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun completionWithoutCurrentSourceNeverBorrowsPreviousAssistantBody() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.messageRows["previous-turn"] = assistant("previous-turn", "previous result")
        val empty = fixture.journal.record(event(SubAgentEventType.COMPLETED, id = "empty-turn"))
        val explicit = fixture.journal.record(event(SubAgentEventType.COMPLETED, "explicit body", "explicit"))
        assertEquals("", empty.content)
        assertNull(empty.sourceMessageId)
        assertEquals("explicit body", explicit.content)
        assertNull(explicit.sourceMessageId)
        coVerify(exactly = 0) { fixture.messages.getMessageById(any()) }
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun completionRejectsMissingForeignAndSyntheticSourcesWithoutWriting() = runTest {
        val fixture = SubAgentJournalFixture()
        val invalid = listOf(
            assistant("foreign", "foreign body").copy(sessionId = "other-child"),
            assistant("reasoning", "").copy(reasoning = "thinking only"),
            assistant("tool", "tool body").copy(role = "TOOL"),
            assistant("user", "user body").copy(role = "USER"),
            assistant("summary", "summary").copy(isContextSummary = true),
            assistant("marker", "marker").copy(isCompactionMarker = true),
            assistant("excluded", "excluded").copy(isContextExcluded = true)
        )
        invalid.forEach { fixture.messageRows[it.id] = it }
        for (id in invalid.map { it.id } + "missing") {
            rejected {
                fixture.journal.record(event(SubAgentEventType.COMPLETED, id = "bad-$id")
                    .copy(sourceMessageId = id))
            }
        }
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun rejectsMissingUnrelatedAndSelfParentSessionsBeforeWriting() = runTest {
        val fixture = SubAgentJournalFixture()
        val original = event(SubAgentEventType.MESSAGE_FROM_PARENT)
        for (invalid in listOf(
            original.copy(parentSessionId = "missing"),
            original.copy(subSessionId = "missing"),
            original.copy(subSessionId = "other-child"),
            original.copy(subSessionId = "parent"),
            original.copy(eventId = " ")
        )) {
            rejected { fixture.journal.record(invalid) }
        }
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun retryKeepsOriginalEntryEvenAfterChildAndSourceDeletion() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.messageRows["final"] = assistant("final", "original full body")
        val event = event(SubAgentEventType.COMPLETED).copy(sourceMessageId = "final")
        val recorded = fixture.journal.record(event)
        fixture.sessionRows.remove("child")
        fixture.messageRows.clear()
        val retried = fixture.journal.record(event.copy(detail = "not an overwrite", timestamp = 9999, origin = "USER"))
        assertEquals(recorded, retried)
        assertEquals(listOf(recorded), fixture.interactions.getAllOnce())
        rejected { fixture.journal.record(event.copy(parentSessionId = "other-parent")) }
        rejected { fixture.journal.record(event.copy(subSessionId = "other-child")) }
        rejected { fixture.journal.record(event.copy(type = SubAgentEventType.STOPPED)) }
    }

    @Test
    fun concurrentInsertWinnerIsReturnedWithoutOverwritingItsBody() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.interactions.beforeInsert = { candidate ->
            fixture.interactions.beforeInsert = {}
            fixture.interactions.insertIgnore(candidate.copy(content = "first writer", timestamp = 1))
        }
        val stored = fixture.journal.record(event(SubAgentEventType.MESSAGE_FROM_PARENT, "second writer"))
        assertEquals("first writer", stored.content)
        assertEquals(1L, stored.timestamp)
        assertEquals(listOf(stored), fixture.interactions.getAllOnce())
    }

    @Test
    fun concurrentInsertWithConflictingIdentityIsRejected() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.interactions.beforeInsert = { candidate ->
            fixture.interactions.beforeInsert = {}
            fixture.interactions.insertIgnore(candidate.copy(subSessionId = "other-child"))
        }
        rejected { fixture.journal.record(event(SubAgentEventType.MESSAGE_FROM_PARENT, "body")) }
        assertEquals("other-child", fixture.interactions.getAllOnce().single().subSessionId)
    }

    @Test
    fun startedOnlyConsumesAcceptedUserInstructionForThisExactParentAndChild() = runTest {
        val fixture = SubAgentJournalFixture()
        val accepted = event(SubAgentEventType.MESSAGE_FROM_PARENT, "user input", "accepted").copy(origin = "USER")
        fixture.journal.record(accepted)
        fixture.journal.record(accepted.copy(eventId = "parent-agent", origin = "PARENT"))
        fixture.journal.record(accepted.copy(eventId = "wrong-kind", type = SubAgentEventType.MESSAGE_FROM_SUB))
        fixture.journal.record(accepted.copy(eventId = "foreign", parentSessionId = "other-parent", subSessionId = "other-child"))
        fixture.sessionRows["peer"] = fixture.sessionRows.getValue("child").copy(id = "peer")
        fixture.journal.record(accepted.copy(eventId = "peer-user", subSessionId = "peer"))
        val started = event(SubAgentEventType.STARTED).copy(instructionId = accepted.eventId, origin = "USER")
        for (id in listOf("missing", "", "parent-agent", "wrong-kind", "foreign", "peer-user")) {
            rejected { fixture.journal.record(started.copy(eventId = "bad-$id", instructionId = id)) }
        }
        rejected { fixture.journal.record(started.copy(eventId = "source", sourceMessageId = "assistant")) }
        rejected { fixture.journal.record(started.copy(eventId = "not-started", type = SubAgentEventType.COMPLETED)) }
        val stored = fixture.journal.record(started)
        assertEquals("STARTED", stored.kind)
        assertEquals(accepted.eventId, stored.instructionId)
        assertEquals("USER", stored.origin)
        assertNull(stored.sourceMessageId)
        fixture.sessionRows.remove("child")
        assertEquals(stored, fixture.journal.record(started.copy(timestamp = 9999)))
        rejected { fixture.journal.record(started.copy(instructionId = "peer-user")) }
    }

    @Test
    fun cancelledRequiresAcceptedUserInstructionForSameParentAndChild() = runTest {
        val fixture = SubAgentJournalFixture()
        val accepted = event(SubAgentEventType.MESSAGE_FROM_PARENT, "user input", "accepted").copy(origin = "USER")
        fixture.journal.record(accepted)
        fixture.journal.record(accepted.copy(eventId = "parent-agent", origin = "PARENT"))
        fixture.journal.record(accepted.copy(eventId = "wrong-kind", type = SubAgentEventType.MESSAGE_FROM_SUB))
        fixture.journal.record(accepted.copy(eventId = "foreign", parentSessionId = "other-parent", subSessionId = "other-child"))
        fixture.sessionRows["peer"] = fixture.sessionRows.getValue("child").copy(id = "peer")
        fixture.journal.record(accepted.copy(eventId = "peer-user", subSessionId = "peer"))
        val cancelled = event(SubAgentEventType.INSTRUCTION_CANCELLED, "Removed from queue")
            .copy(instructionId = accepted.eventId)
        for ((index, id) in listOf(null, "", " ", "missing", "parent-agent", "wrong-kind", "foreign", "peer-user").withIndex()) {
            rejected { fixture.journal.record(cancelled.copy(eventId = "bad-$index", instructionId = id)) }
        }
        rejected { fixture.journal.record(cancelled.copy(eventId = "assistant-source", sourceMessageId = "assistant")) }
        rejected { fixture.journal.record(cancelled.copy(eventId = "wrong-event-kind", type = SubAgentEventType.STOPPED)) }
        val stored = fixture.journal.record(cancelled)
        assertEquals("INSTRUCTION_CANCELLED", stored.kind)
        assertEquals(accepted.eventId, stored.instructionId)
        assertEquals("SYSTEM", stored.origin)
        assertEquals("Removed from queue", stored.content)
        assertNull(stored.sourceMessageId)
        assertFalse(fixture.interactions.getAllOnce().any { it.id.startsWith("bad-") })
        coVerify(exactly = 0) { fixture.messages.getMessageById(any()) }
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun cancellationRetryAfterChildDeletionPreservesAuditWithoutInventingStarted() = runTest {
        val fixture = SubAgentJournalFixture()
        val accepted = event(SubAgentEventType.MESSAGE_FROM_PARENT, "same input", "accepted-1").copy(origin = "USER")
        fixture.journal.record(accepted)
        fixture.journal.record(accepted.copy(eventId = "accepted-2"))
        val cancelled = event(SubAgentEventType.INSTRUCTION_CANCELLED, "Queue cleared", "cancelled")
            .copy(instructionId = accepted.eventId)
        val stored = fixture.journal.record(cancelled)
        fixture.sessionRows.remove("child")
        assertEquals(stored, fixture.journal.record(cancelled.copy(detail = "not an overwrite", timestamp = 9999)))
        rejected { fixture.journal.record(cancelled.copy(instructionId = "accepted-2")) }
        rejected { fixture.journal.record(cancelled.copy(type = SubAgentEventType.STARTED)) }
        val rows = fixture.interactions.getAllOnce()
        assertEquals(3, rows.size)
        assertFalse(rows.any { it.kind == "STARTED" })
        assertEquals(listOf("accepted-1"), rows.filter { it.kind == "INSTRUCTION_CANCELLED" }.map { it.instructionId })
    }

    @Test
    fun cancellationIsDurableButNeverDispatchedByEitherEventBusEntrypoint() = runTest {
        val fixture = SubAgentJournalFixture()
        val accepted = event(SubAgentEventType.MESSAGE_FROM_PARENT, "queued input", "accepted").copy(origin = "USER")
        fixture.bus.recordForDisplay(accepted)
        assertTrue(fixture.bus.tryReserveSlots(2))
        assertTrue(fixture.bus.tryMarkStarted("child"))
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        val cancelled = event(SubAgentEventType.INSTRUCTION_CANCELLED, "Queue cleared", "cancelled")
            .copy(instructionId = accepted.eventId)
        fixture.bus.emit(cancelled)
        val second = fixture.bus.recordForDisplay(cancelled.copy(eventId = "cancelled-display"))
        assertEquals("INSTRUCTION_CANCELLED", fixture.interactions.getById(cancelled.eventId)!!.kind)
        assertEquals("SYSTEM", second!!.origin)
        assertEquals(accepted.eventId, second.instructionId)
        assertTrue(received.isEmpty())
        assertEquals(setOf("child"), fixture.bus.activeSubSessionIds.value)
        assertEquals(3, fixture.bus.activeCount)
        fixture.bus.releaseSlots(2)
        assertEquals(1, fixture.bus.activeCount)
    }

    @Test
    fun emitPersistsBeforeDeliveryAndPreservesNotificationDetailAndSynchronousSlots() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.messageRows["final"] = assistant("final", "full output\n".repeat(2000))
        val received = mutableListOf<SubAgentEvent>()
        val recorded = mutableListOf<SubAgentInteractionEntity?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.bus.events.collect {
                recorded += fixture.interactions.getById(it.eventId)
                received += it
            }
        }
        assertTrue(fixture.bus.tryReserveSlots(1))
        val spawn = event(SubAgentEventType.SPAWNED, "task")
        val completion = event(SubAgentEventType.COMPLETED).copy(sourceMessageId = "final")
        fixture.bus.emit(spawn)
        assertEquals(setOf("child"), fixture.bus.activeSubSessionIds.value)
        assertEquals(1, fixture.bus.activeCount)
        fixture.bus.emit(completion)
        assertEquals(listOf(spawn, completion).map { it.copy(generation = 0, parentGeneration = 0) }, received)
        assertTrue(recorded.all { it != null })
        assertTrue(recorded.last()!!.content.length > 20_000)
        assertEquals("", received.last().detail)
        assertEquals(0, fixture.bus.activeCount)
    }

    @Test
    fun failedWriteNeitherDispatchesNorConsumesReservedSpawnSlot() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.interactions.insertFailure = IllegalStateException("disk full")
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        assertTrue(fixture.bus.tryReserveSlots(1))
        try {
            fixture.bus.emit(event(SubAgentEventType.SPAWNED, "task"))
            fail("Write failure must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("disk full", error.message)
        }
        assertTrue(received.isEmpty())
        assertTrue(fixture.bus.activeSubSessionIds.value.isEmpty())
        assertEquals(1, fixture.bus.activeCount)
        fixture.bus.releaseSlots(1)
        fixture.bus.markStarted("child")
        try {
            fixture.bus.emit(event(SubAgentEventType.STOPPED))
            fail("Failed terminal write must not release activity")
        } catch (_: IllegalStateException) {
        }
        assertEquals(setOf("child"), fixture.bus.activeSubSessionIds.value)
        assertTrue(received.isEmpty())
    }

    @Test
    fun displayOnlyEventsAndStartedNeverDispatchOrChangeActivity() = runTest {
        val fixture = SubAgentJournalFixture()
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        fixture.bus.markStarted("child")
        fixture.bus.recordForDisplay(event(SubAgentEventType.STOPPED, "full stop reason").copy(origin = "USER"))
        fixture.bus.emit(event(SubAgentEventType.STARTED))
        assertTrue(received.isEmpty())
        assertEquals(setOf("child"), fixture.bus.activeSubSessionIds.value)
        assertEquals(setOf("STOPPED", "STARTED"), fixture.interactions.getAllOnce().map { it.kind }.toSet())
        assertTrue(fixture.bus.release("child"))
        assertEquals(0, fixture.bus.activeCount)
        assertFalse(fixture.bus.release("child"))
    }

    @Test
    fun acceptingAndStartingRemainDistinctAndReopeningDoesNotReplayOrRestoreActivity() = runTest {
        val fixture = SubAgentJournalFixture()
        val accepted = event(SubAgentEventType.MESSAGE_FROM_PARENT, "same input", "accepted-1")
            .copy(origin = "USER", timestamp = 10)
        fixture.bus.recordForDisplay(accepted)
        fixture.bus.recordForDisplay(accepted.copy(eventId = "accepted-2", timestamp = 11))
        fixture.bus.emit(event(SubAgentEventType.COMPLETED, id = "previous-completion").copy(timestamp = 20))
        val started = event(SubAgentEventType.STARTED, id = "actual-start")
            .copy(instructionId = accepted.eventId, timestamp = 30, origin = "USER")
        fixture.bus.recordForDisplay(started)
        fixture.bus.markStarted("child")

        val coldBus = SubAgentEventBus(SubAgentInteractionJournal(fixture.interactions, fixture.messages, fixture.sessions))
        val replayed = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coldBus.events.collect { replayed += it } }
        val rows = fixture.interactions.getAllOnce()
        val acceptedIds = rows.filter { it.kind == "MESSAGE_FROM_PARENT" && it.origin == "USER" }.map { it.id }.toSet()
        val startedIds = rows.filter { it.kind == "STARTED" }.mapNotNull { it.instructionId }.toSet()
        assertEquals(setOf("accepted-2"), acceptedIds - startedIds)
        assertTrue(replayed.isEmpty())
        assertTrue(coldBus.activeSubSessionIds.value.isEmpty())
        assertEquals(0, coldBus.activeCount)
        assertEquals(rows, fixture.interactions.getAllOnce())
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun terminalNotificationIsDurableWithoutFlowCollectorAndOnlyContainsPublicRunResult() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(subagentType = "编码员")
        fixture.messageRows["answer"] = assistant("answer", "public answer").copy(
            reasoning = "PRIVATE_THINKING", signature = "PRIVATE_SIGNATURE", thinkingBlocksJson = "PRIVATE_BLOCKS",
            toolCallsJson = "PRIVATE_TOOL_PROTOCOL"
        )
        fixture.messageRows["write"] = AgentMessageEntity("write", "child", "TOOL", "public output", 10,
            toolName = "writeFile", toolArgs = """{"path":"/work/result.kt","content":"PRIVATE_PARAMETER"}""")
        fixture.messageRows["previous-write"] = fixture.messageRows.getValue("write").copy(
            id = "previous-write", toolArgs = """{"path":"/work/old.kt"}"""
        )
        val completed = event(SubAgentEventType.COMPLETED, id = "done").copy(
            sourceMessageId = "answer", runMessageIds = setOf("write", "answer")
        )
        fixture.bus.emit(completed)
        fixture.bus.emit(completed.copy(detail = "retry must preserve first payload"))
        val pending = fixture.notifications.peek("parent").single()
        assertEquals(completed.eventId, pending.eventId)
        assertEquals("编码员", pending.title)
        assertEquals("public answer", pending.resultExcerpt)
        assertEquals(listOf("/work/result.kt"), pending.changedFiles)
        val payload = fixture.notificationDao.rows.single().payloadJson
        assertFalse(payload.contains("PRIVATE_"))
        assertFalse(payload.contains("old.kt"))
        val reopened = AgentNotificationCenter(fixture.notificationDao)
        assertTrue(reopened.peek("parent").isEmpty())
        assertEquals(pending, reopened.pendingForConfirmation("parent").single())
        assertEquals("编码员", fixture.interactions.getMetadataByParentOnce("parent").single().agentName)
    }

    @Test
    fun stoppedAndAgentMessagesPersistBeforeFlowWhileUserInstructionRemainsAuditOnly() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.bus.emit(event(SubAgentEventType.SPAWNED).copy(timestamp = 1))
        fixture.bus.emit(event(SubAgentEventType.STOPPED, "user stopped", "stopped").copy(timestamp = 2))
        assertEquals(0, fixture.bus.activeCount)
        assertEquals("stopped", fixture.notifications.peek("parent").single().eventId)
        fixture.bus.emit(event(SubAgentEventType.MESSAGE_FROM_PARENT, "follow up", "parent-send"))
        assertEquals("follow up", fixture.notifications.peek("child").single().message)
        fixture.bus.recordForDisplay(event(SubAgentEventType.MESSAGE_FROM_PARENT, "card input", "card")
            .copy(origin = "USER"))
        assertEquals(listOf("parent-send"), fixture.notifications.peek("child").map { it.eventId })
    }

    @Test
    fun coldSpawnWithoutRunGetsStableInterruptedAuditAndConfirmationWithoutReplay() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(subagentType = "审核员")
        val spawn = event(SubAgentEventType.SPAWNED, "original task", "spawn").copy(timestamp = 10)
        fixture.bus.emit(spawn)
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(subagentType = "new definition")
        val coldCenter = AgentNotificationCenter(fixture.notificationDao)
        val coldJournal = SubAgentInteractionJournal(fixture.interactions, fixture.messages, fixture.sessions, coldCenter)
        val coldBus = SubAgentEventBus(coldJournal)
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coldBus.events.collect { received += it } }
        val recovered = coldJournal.recoverInterrupted("Process interrupted")
        assertEquals("interrupted:spawn", recovered.single().id)
        assertEquals("INTERRUPTED", recovered.single().kind)
        assertEquals("审核员", recovered.single().agentName)
        assertEquals("original task", fixture.interactions.getById("spawn")!!.content)
        assertTrue(coldCenter.peek("parent").isEmpty())
        val notification = coldCenter.pendingForConfirmation("parent").single()
        assertEquals("interrupted:spawn", notification.eventId)
        assertEquals("审核员", notification.title)
        assertTrue(coldJournal.recoverInterrupted("repeat").isEmpty())
        assertEquals(notification, coldCenter.pendingForConfirmation("parent").single())
        assertTrue(received.isEmpty())
        assertEquals(0, coldBus.activeCount)
    }

    @Test
    fun recoveryLedgerResolvesStartedInstructionWithoutReplayingAcceptedInput() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.bus.recordForDisplay(event(SubAgentEventType.MESSAGE_FROM_PARENT, "accepted body", "instruction")
            .copy(origin = "USER", timestamp = 5))
        fixture.bus.recordForDisplay(event(SubAgentEventType.STARTED, id = "start").copy(
            timestamp = 10, instructionId = "instruction", runId = "run"
        ))
        fixture.interactions.recoveryRuns += recoveryRun("run", "INTERRUPTED")
        val recovered = fixture.journal.recoverInterrupted()
        assertEquals("run", recovered.single().runId)
        assertEquals("INTERRUPTED", recovered.single().kind)
        val start = fixture.interactions.getById("start")!!
        assertEquals("instruction", start.instructionId)
        assertEquals("accepted body", fixture.interactions.getById("instruction")!!.content)
        assertTrue(fixture.notifications.peek("child").isEmpty())
        assertTrue(fixture.notifications.peek("parent").isEmpty())
        assertEquals(1, fixture.notifications.pendingForConfirmation("parent").size)
        coVerify(exactly = 0) { fixture.messages.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun coldRecoveryDoesNotInterruptCurrentCompletedOrImportedReadOnlyExecutions() = runTest {
        for (status in listOf("RUNNING", "COMPLETED")) {
            val fixture = SubAgentJournalFixture()
            fixture.journal.record(event(SubAgentEventType.STARTED, id = "started").copy(timestamp = 10, runId = "run"))
            fixture.interactions.recoveryRuns += recoveryRun("run", status)
            assertTrue(fixture.journal.recoverInterrupted().isEmpty())
            assertTrue(fixture.notifications.pendingRecipientIds().isEmpty())
        }
        val fixture = SubAgentJournalFixture()
        fixture.interactions.insertIgnore(SubAgentInteractionEntity("imported", "parent", "child", "STARTED", "historical", 10))
        assertTrue(fixture.journal.recoverInterrupted().isEmpty())
        fixture.interactions.recoveryRuns += recoveryRun("old-run", "INTERRUPTED")
        assertEquals("interrupted:imported", fixture.journal.recoverInterrupted().single().id)
    }

    @Test
    fun terminalInheritsStartFenceAndNameInsteadOfDeliveringIntoRenamedParentGeneration() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(subagentType = "架构师")
        fixture.journal.record(event(SubAgentEventType.STARTED).copy(runId = "run", timestamp = 10))
        fixture.sessionRows["parent"] = fixture.sessionRows.getValue("parent").copy(executionGeneration = 1)
        fixture.notificationDao.generations["parent"] = 1
        fixture.sessionRows["child"] = fixture.sessionRows.getValue("child").copy(subagentType = "later name")
        val terminal = fixture.journal.record(event(SubAgentEventType.COMPLETED, "result").copy(runId = "run", timestamp = 20))
        assertEquals(0L, terminal.parentGeneration)
        assertEquals("架构师", terminal.agentName)
        assertTrue(fixture.notifications.pendingRecipientIds().isEmpty())
    }

    @Test
    fun finishCallbackIsAwaitedOnceAndLostOwnershipDoesNotWriteOrDispatch() = runTest {
        val fixture = SubAgentJournalFixture()
        var finishes = 0
        val completed = event(SubAgentEventType.COMPLETED, "public result", "completed")
        fixture.bus.emit(completed) { finishes++; true }
        fixture.bus.emit(completed) { finishes++; false }
        assertEquals(1, finishes)
        assertEquals(1, fixture.notifications.peek("parent").size)
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        try {
            fixture.bus.emit(event(SubAgentEventType.FAILED, "stale", "rejected")) { false }
            fail("Lost owner must abort the transaction")
        } catch (_: CancellationException) {
        }
        assertNull(fixture.interactions.getById("rejected"))
        assertEquals(1, fixture.notificationDao.rows.size)
        assertTrue(received.isEmpty())
    }

    @Test
    fun staleSpawnReturnsReservationAndDoesNotBroadcastOrOccupySlot() = runTest {
        val fixture = SubAgentJournalFixture()
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        assertTrue(fixture.bus.tryReserveSlots(1))
        fixture.sessionRows["parent"] = fixture.sessionRows.getValue("parent").copy(executionGeneration = 1)
        fixture.notificationDao.generations["parent"] = 1
        fixture.bus.emit(event(SubAgentEventType.SPAWNED).copy(generation = 0, parentGeneration = 0))
        assertEquals(0, fixture.bus.activeCount)
        assertTrue(received.isEmpty())
        assertTrue(fixture.bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING))
    }

    @Test
    fun stoppedEventBindsOriginalRunAndCannotControlLaterExecution() = runTest {
        val fixture = SubAgentJournalFixture()
        fixture.journal.record(event(SubAgentEventType.STARTED).copy(runId = "first", timestamp = 10))
        val stopped = fixture.journal.record(event(SubAgentEventType.STOPPED).copy(timestamp = 20))
        assertEquals("first", stopped.runId)
        assertTrue(fixture.journal.isCurrent(stopped))
        fixture.journal.record(event(SubAgentEventType.STARTED).copy(eventId = "later-start", runId = "second", timestamp = 30))
        assertFalse(fixture.journal.isCurrent(stopped))
    }

    private fun recoveryRun(id: String, status: String) = RecoveryRunEntity(
        id, "request", "child", 0, "old-process", status, 1, 0, 1, 1, 20,
        if (status == "RUNNING") null else 20
    )
}
