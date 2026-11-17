package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.domain.recovery.RecoveryResumeSnapshot
import com.aicode.feature.agent.domain.recovery.RecoveryStage
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.recovery.RecoveryToolCall
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecoveryDaoTest {
    private val fixture = RecoveryDaoFixture()
    private val dao = fixture.dao
    private val call = ToolCall("call", "Bash", mapOf("command" to JsonPrimitive("printf original")))

    @Test
    fun enqueueKeepsStableInputIdAndMonotonicOrderWithoutCreatingMessages() = runTest {
        val first = fixture.enqueue()
        val second = fixture.enqueue("second")
        val repeated = dao.enqueueRequest(first.copy(inputMessageId = "different-default-id"))

        assertEquals(first, repeated)
        assertEquals("input_request", repeated?.inputMessageId)
        assertEquals(listOf(1L, 2L), listOf(first.queueOrder, second.queueOrder))
        assertTrue(fixture.messages.isEmpty())
    }

    @Test
    fun claimHonorsEnvironmentQueueOrderAndUniqueSessionRun() = runTest {
        fixture.enqueue()
        fixture.enqueue("second")
        assertNull(dao.claimRequest("request", "another-environment", false, "wrong", "owner", 2_000))
        assertNull(dao.claimRequest("second", "local", false, "out-of-order", "owner", 2_000))
        val run = fixture.claim()
        assertEquals(run.id, fixture.requests.getValue("request").activeRunId)
        assertNull(dao.claimRequest("request", "local", true, "duplicate", "owner", 2_000))
        assertNull(dao.claimRequest("second", "local", true, "parallel", "owner", 2_000))
        assertEquals(1, fixture.runs.size)
        assertNull(run.resumeSnapshotJson)
    }

    @Test
    fun confirmedClaimFreezesTypedSnapshotBeforeChangingActiveRunOrPersistingResumeInput() = runTest {
        val old = ready()
        assertTrue(dao.commitRecoveryInput(old.id, "owner", input()))
        val step = checkNotNull(dao.beginRecoveryStep(old.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(old.id, "owner", step, "Uncommitted visible tail", 4_000, 500, 32_768))
        assertTrue(dao.declareRecoveryTools(old.id, "owner", step, listOf(RecoveryToolCall("half", "Bash")), 4_000))
        dao.initializeRecovery("restarted-owner", 5_000)

        val resumed = fixture.claim(runId = "resumed", confirmed = true, owner = "restarted-owner")

        val expected = RecoveryResumeSnapshot(RecoveryStage.TEXT, "Uncommitted visible tail", old.id,
            step.step, step.revision, inputMessageExists = true)
        assertEquals(expected, snapshot(resumed))
        assertEquals(resumed, fixture.runs.getValue(fixture.requests.getValue("request").activeRunId!!))
        assertEquals(listOf(input()), fixture.messages.values.toList())
        assertEquals(setOf("stage", "draftText", "sourceRunId", "sourceStep", "inputMessageExists"),
            Json.parseToJsonElement(checkNotNull(resumed.resumeSnapshotJson)).let { (it as kotlinx.serialization.json.JsonObject).keys })
        val frozen = resumed.resumeSnapshotJson
        fixture.drafts[old.id] = fixture.drafts.getValue(old.id).copy(text = "Later change")
        assertEquals(frozen, dao.runRow(resumed.id)?.resumeSnapshotJson)
        assertFalse(dao.saveRecoveryDraft(old.id, "owner", step, "Old-owner callback", 6_000, 0, 32_768))
        assertFalse(dao.commitRecoveryAssistant(old.id, "owner", step, assistant(), 6_000))
    }

    @Test
    fun claimClassifiesNotStartedAndAlreadyPersistedInputWithoutInventingDraft() = runTest {
        val old = ready()
        assertTrue(dao.finishRecoveryRun(old.id, "owner", "INTERRUPTED", null, 3_000))
        val withoutInput = fixture.claim(runId = "without-input", confirmed = true)
        assertEquals(RecoveryResumeSnapshot(RecoveryStage.NOT_STARTED, "", old.id), snapshot(withoutInput))
        assertTrue(dao.commitRecoveryInput(withoutInput.id, "owner", input()))
        assertTrue(dao.finishRecoveryRun(withoutInput.id, "owner", "INTERRUPTED", null, 4_000))

        val withInput = fixture.claim(runId = "with-input", confirmed = true)

        assertEquals(RecoveryStage.TEXT, snapshot(withInput).stage)
        assertTrue(snapshot(withInput).inputMessageExists)
        assertEquals("", snapshot(withInput).draftText)
        assertEquals(withoutInput.id, snapshot(withInput).sourceRunId)
    }

    @Test
    fun claimedSnapshotTracksUnexecutedAndFinishedLedgerAcrossRepeatedInterruptions() = runTest {
        val old = ready()
        val step = checkNotNull(dao.beginRecoveryStep(old.id, "owner", 3_000))
        assertTrue(dao.commitRecoveryAssistant(old.id, "owner", step, assistant(calls = listOf(call)), 4_000))
        assertTrue(dao.finishRecoveryRun(old.id, "owner", "INTERRUPTED", null, 5_000))
        val unexecuted = fixture.claim(runId = "unexecuted", confirmed = true)
        assertEquals(RecoveryStage.TOOLS_NOT_EXECUTED, snapshot(unexecuted).stage)
        assertTrue(dao.commitRecoveryToolResult(unexecuted.id, "owner", toolResult().copy(isError = true), 7_000))
        assertTrue(dao.finishRecoveryRun(unexecuted.id, "owner", "INTERRUPTED", null, 8_000))

        val completed = fixture.claim(runId = "completed", confirmed = true)

        assertEquals(RecoveryStage.TOOLS_COMPLETED, snapshot(completed).stage)
        assertEquals("FINISHED", fixture.tools.getValue(old.id to call.id).status)
        assertEquals(old.id, fixture.tools.getValue(old.id to call.id).runId)
        assertEquals(RecoveryStage.TOOLS_NOT_EXECUTED, snapshot(unexecuted).stage)
    }

    @Test
    fun firstAssistantCommitConsumesInheritedDraftWithoutResurrectingOldRunDrafts() = runTest {
        val old = ready()
        val oldStep = checkNotNull(dao.beginRecoveryStep(old.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(old.id, "owner", oldStep, "Old visible draft", 4_000, 500, 32_768))
        assertTrue(dao.finishRecoveryRun(old.id, "owner", "INTERRUPTED", null, 5_000))
        val resumed = fixture.claim(runId = "resumed", confirmed = true)
        assertEquals("Old visible draft", snapshot(resumed).draftText)
        val resumedStep = checkNotNull(dao.beginRecoveryStep(resumed.id, "owner", 6_000))
        assertFalse(dao.commitRecoveryAssistant(resumed.id, "wrong-owner", resumedStep, assistant(), 7_000))
        assertEquals("Old visible draft", snapshot(fixture.runs.getValue(resumed.id)).draftText)
        assertTrue(dao.commitRecoveryAssistant(resumed.id, "owner", resumedStep, assistant(), 7_000))
        assertEquals("", snapshot(fixture.runs.getValue(resumed.id)).draftText)
        assertEquals("Old visible draft", fixture.drafts.getValue(old.id).text)
        assertTrue(dao.finishRecoveryRun(resumed.id, "owner", "INTERRUPTED", null, 8_000))

        val third = fixture.claim(runId = "third", confirmed = true)

        assertEquals(RecoveryStage.TEXT, snapshot(third).stage)
        assertEquals("", snapshot(third).draftText)
        assertEquals(resumed.id, snapshot(third).sourceRunId)
    }

    @Test
    fun startupInterruptsOldRunAndMarksOnlyStartedToolsUnknownWithoutDispatch() = runTest {
        fixture.enqueue()
        fixture.enqueue("queued")
        val run = fixture.claim()
        val step = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        val calls = listOf(call, ToolCall("waiting", "readFile", emptyMap()))
        assertTrue(dao.commitRecoveryAssistant(run.id, "owner", step, assistant(calls = calls), 4_000))
        assertTrue(dao.markRecoveryTool(run.id, "owner", "call", true, 5_000))
        assertTrue(dao.markRecoveryTool(run.id, "owner", "waiting", false, 5_000))

        dao.initializeRecovery("new-process", 6_000)

        assertEquals("INTERRUPTED", fixture.runs.getValue(run.id).status)
        assertEquals("INTERRUPTED", fixture.requests.getValue("request").status)
        assertEquals("AWAITING_CONFIRMATION", fixture.requests.getValue("queued").status)
        assertEquals("UNKNOWN", fixture.tools.getValue(run.id to "call").status)
        assertEquals("WAITING_APPROVAL", fixture.tools.getValue(run.id to "waiting").status)
        assertNull(dao.claimRequest("request", "local", false, "automatic", "new-process", 7_000))
        assertNull(dao.claimRequest("queued", "local", false, "automatic-queued", "new-process", 7_000))
        assertNull(dao.ownedRunning(run.id, "owner"))
        assertEquals(1, fixture.runs.size)
        assertEquals(1, fixture.messages.size)
    }

    @Test
    fun assistantCommitDeclaresFullCallsStoresMessageAndClearsDraftTogether() = runTest {
        val run = ready()
        val step = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(run.id, "owner", step, "Visible body", 4_000, 500, 32_768))
        fixture.writes.clear()
        val message = assistant(calls = listOf(call)).copy(reasoning = "private-only", signature = "signature-only",
            thinkingBlocksJson = "[{\"type\":\"thinking\"}]")

        assertTrue(dao.commitRecoveryAssistant(run.id, "owner", step, message, 5_000))

        assertEquals(message, fixture.messages.getValue(message.id))
        val ledger = fixture.tools.getValue(run.id to call.id)
        assertEquals("DECLARED", ledger.status)
        assertEquals(message.id, ledger.assistantMessageId)
        assertFalse(ledger.toString().contains("printf original"))
        assertFalse(ledger.toString().contains("private-only"))
        assertFalse(ledger.toString().contains("signature-only"))
        assertNull(fixture.drafts[run.id])
        assertEquals(step.step, fixture.runs.getValue(run.id).committedStep)
        assertEquals(listOf("declare:call", "message:assistant", "tool:call:DECLARED", "run:run:RUNNING", "clearDraft:run"), fixture.writes)
    }

    @Test
    fun malformedOrConflictingToolDeclarationDoesNotCommitAssistantOrClearDraft() = runTest {
        val run = ready()
        val step = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(run.id, "owner", step, "Visible", 4_000, 500, 32_768))
        assertFalse(dao.commitRecoveryAssistant(run.id, "owner", step, assistant().copy(toolCallsJson = "[{"), 5_000))
        assertTrue(dao.declareRecoveryTools(run.id, "owner", step, listOf(RecoveryToolCall("different", "readFile")), 5_000))
        assertFalse(dao.commitRecoveryAssistant(run.id, "owner", step, assistant(calls = listOf(call)), 6_000))
        assertTrue(fixture.messages.isEmpty())
        assertEquals("Visible", fixture.drafts.getValue(run.id).text)
    }

    @Test
    fun restartAdvancesRevisionAndRejectsOldDraftAndOldAssistantCompletion() = runTest {
        val run = ready()
        val old = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(run.id, "owner", old, "Old stream", 4_000, 500, 32_768))
        assertTrue(dao.declareRecoveryTools(run.id, "owner", old, listOf(RecoveryToolCall("call", "Bash")), 4_000))
        val current = checkNotNull(dao.restartRecoveryStream(run.id, "owner", old, 5_000))
        assertEquals(old.revision + 1, current.revision)
        assertTrue(fixture.tools.isEmpty())
        assertNull(fixture.drafts[run.id])
        assertFalse(dao.saveRecoveryDraft(run.id, "owner", old, "Late old stream", 6_000, 500, 32_768))
        assertFalse(dao.commitRecoveryAssistant(run.id, "owner", old, assistant(), 6_000))
        assertTrue(dao.commitRecoveryAssistant(run.id, "owner", current, assistant(), 7_000))
        assertNull(dao.restartRecoveryStream(run.id, "owner", current, 8_000))
    }

    @Test
    fun draftWritesAreTimeThrottledAndByteBounded() = runTest {
        val run = ready()
        val step = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.saveRecoveryDraft(run.id, "owner", step, "First", 4_000, 500, 6))
        assertFalse(dao.saveRecoveryDraft(run.id, "owner", step, "Next", 4_100, 500, 6))
        assertFalse(dao.saveRecoveryDraft(run.id, "owner", step, "三字节", 4_600, 500, 6))
        assertTrue(dao.saveRecoveryDraft(run.id, "owner", step, "Next", 4_600, 500, 6))
        assertEquals("Next", fixture.drafts.getValue(run.id).text)
    }

    @Test
    fun toolCannotStartBeforeAssistantCommitOrStartTwice() = runTest {
        val run = ready()
        val step = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.declareRecoveryTools(run.id, "owner", step, listOf(RecoveryToolCall(call.id, call.name)), 3_000))
        assertFalse(dao.markRecoveryTool(run.id, "owner", call.id, true, 4_000))
        assertTrue(dao.commitRecoveryAssistant(run.id, "owner", step, assistant(calls = listOf(call)), 5_000))
        assertTrue(dao.markRecoveryTool(run.id, "owner", call.id, false, 6_000))
        assertTrue(dao.markRecoveryTool(run.id, "owner", call.id, true, 7_000))
        assertEquals("STARTED", fixture.tools.getValue(run.id to call.id).status)
        assertFalse(dao.markRecoveryTool(run.id, "owner", call.id, true, 8_000))
        assertFalse(dao.markRecoveryTool(run.id, "owner", call.id, false, 8_000))
        assertNull(dao.beginRecoveryStep(run.id, "owner", 8_000))
        assertFalse(dao.finishRecoveryRun(run.id, "owner", "COMPLETED", null, 8_000))
    }

    @Test
    fun finalToolResultPreservesOriginalMetadataTimingAndMessageOrder() = runTest {
        val run = runningTool()
        val pending = toolResult().copy(content = "[pending]", timestamp = 7, toolActionTitle = "Original title",
            toolStartedAtMs = 100, toolDurationMs = 12)
        fixture.messages[pending.id] = pending
        val result = toolResult().copy(timestamp = 999, toolActionTitle = "New title", toolStartedAtMs = 200, toolDurationMs = 300)

        assertTrue(dao.commitRecoveryToolResult(run.id, "owner", result, 7_000))

        assertEquals(result.copy(timestamp = 7, toolActionTitle = "Original title", toolStartedAtMs = 100, toolDurationMs = 12),
            fixture.messages.getValue(pending.id))
        assertEquals("FINISHED", fixture.tools.getValue(run.id to call.id).status)
        assertEquals(pending.id, fixture.tools.getValue(run.id to call.id).resultMessageId)
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", result.copy(content = "Late overwrite"), 8_000))
        assertTrue(dao.finishRecoveryRun(run.id, "owner", "COMPLETED", null, 9_000))
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", result, 10_000))
    }

    @Test
    fun confirmedResumeUsesNewRunAndCanOnlyCloseOldUnknownWithErrorNotExecuteIt() = runTest {
        val old = runningTool()
        dao.initializeRecovery("new-owner", 7_000)
        val resumed = fixture.claim(runId = "resumed", confirmed = true, owner = "new-owner")
        assertNotEquals(old.id, resumed.id)
        assertEquals(RecoveryStage.TOOLS_UNKNOWN, snapshot(resumed).stage)
        assertEquals(old.id, snapshot(resumed).sourceRunId)
        assertEquals("input_request", fixture.requests.getValue("request").inputMessageId)
        assertNull(dao.beginRecoveryStep(resumed.id, "new-owner", 8_000))
        assertFalse(dao.markRecoveryTool(resumed.id, "new-owner", call.id, true, 8_000))
        assertFalse(dao.commitRecoveryToolResult(old.id, "owner", toolResult(), 8_000))
        assertFalse(dao.commitRecoveryToolResult(resumed.id, "new-owner", toolResult(), 8_000))
        assertTrue(dao.commitRecoveryToolResult(resumed.id, "new-owner", toolResult().copy(isError = true, content = "Verify external effects"), 9_000))
        assertEquals("FINISHED", fixture.tools.getValue(old.id to call.id).status)
        assertEquals(RecoveryStep(1, 0), dao.beginRecoveryStep(resumed.id, "new-owner", 10_000))
    }

    @Test
    fun holdCannotBeBypassedByConfirmedClaimAndClearDoesNotResumeAutomatically() = runTest {
        val run = runningTool()
        fixture.enqueue("queued")
        assertTrue(dao.holdRecoverySession("session", "plan_rejected", 7_000))
        assertNull(dao.ownedRunning(run.id, "owner"))
        assertEquals("UNKNOWN", fixture.tools.getValue(run.id to call.id).status)
        assertEquals("AWAITING_CONFIRMATION", fixture.requests.getValue("queued").status)
        assertNull(dao.claimRequest("request", "local", true, "blocked", "owner", 8_000))
        assertNull(dao.claimRequest("queued", "local", true, "blocked-queue", "owner", 8_000))
        assertTrue(dao.clearRecoveryHold("session"))
        assertEquals("INTERRUPTED", fixture.requests.getValue("request").status)
        assertNull(dao.claimRequest("request", "local", false, "still-blocked", "owner", 9_000))
        assertEquals(1, fixture.runs.size)
    }

    @Test
    fun invalidationPreventsLateWritesEvenAfterRestoreRecreatesOriginalGeneration() = runTest {
        val run = runningTool()
        assertTrue(dao.invalidateRecoverySession("session", "restore", true, 7_000))
        assertEquals(1L, fixture.sessions.getValue("session").executionGeneration)
        assertEquals(setOf("session"), fixture.retiredNotificationSessions)
        fixture.sessions["session"] = RecoverySessionGate("session", 0, null)
        fixture.messages.clear()
        fixture.writes.clear()

        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 8_000))
        assertFalse(dao.commitRecoveryInput(run.id, "owner", input()))
        assertFalse(dao.commitRecoveryAssistant(run.id, "owner", RecoveryStep(1, 0), assistant(), 8_000))
        assertNull(dao.freezeRecoveryInputSnapshot(run.id, "owner", "{}"))
        assertNull(dao.claimRequest("request", "local", true, "revived", "owner", 8_000))
        assertTrue(fixture.messages.isEmpty())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun allExecutionWritesRejectDeletedSessionChangedGenerationAndOwnerFence() = runTest {
        val run = runningTool()
        assertFalse(dao.commitRecoveryToolResult(run.id, "wrong-owner", toolResult(), 7_000))
        fixture.sessions["session"] = RecoverySessionGate("session", 1, null)
        assertFalse(dao.markRecoveryTool(run.id, "owner", call.id, true, 7_000))
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 7_000))
        fixture.sessions.remove("session")
        fixture.writes.clear()
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 8_000))
        assertNull(dao.beginRecoveryStep(run.id, "owner", 8_000))
        assertFalse(dao.incrementRecoveryBudget(run.id, "owner", 1, 1, 8_000))
        assertFalse(dao.finishRecoveryRun(run.id, "owner", "COMPLETED", null, 8_000))
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun continuationBudgetSurvivesResumeAndOnlyCountsTokensAndThreeContinuations() = runTest {
        val run = ready()
        assertTrue(dao.incrementRecoveryBudget(run.id, "owner", 32_000, 200_000, 3_000))
        assertTrue(dao.finishRecoveryRun(run.id, "owner", "INTERRUPTED", null, 4_000))
        val next = fixture.claim(runId = "resumed", confirmed = true)
        assertTrue(dao.incrementRecoveryBudget(next.id, "owner", 32_000, 200_000, 5_000))
        assertFalse(dao.incrementRecoveryBudget(next.id, "owner", 1, 1, 6_000))
        assertTrue(dao.incrementRecoveryBudget(next.id, "owner", 0, 0, 7_000))
        assertFalse(dao.incrementRecoveryBudget(next.id, "owner", 0, 0, 8_000))
        val request = fixture.requests.getValue("request")
        assertEquals(3, request.continuationCount)
        assertEquals(64_000L, request.continuationOutputTokens)
        assertEquals(400_000L, request.continuationChars)
    }

    @Test
    fun stableInputAndFrozenSnapshotAreNotRewrittenOnResume() = runTest {
        val run = ready()
        assertTrue(dao.commitRecoveryInput(run.id, "owner", input()))
        assertEquals("{\"version\":1}", dao.freezeRecoveryInputSnapshot(run.id, "owner", "{\"version\":1}"))
        assertTrue(dao.finishRecoveryRun(run.id, "owner", "INTERRUPTED", null, 3_000))
        val next = fixture.claim(runId = "resumed", confirmed = true)
        assertTrue(dao.commitRecoveryInput(next.id, "owner", input().copy(content = "Changed input", timestamp = 999)))
        assertEquals("{\"version\":1}", dao.freezeRecoveryInputSnapshot(next.id, "owner", "{\"version\":2}"))
        assertEquals(input().copy(modelInputSnapshotJson = "{\"version\":1}"), fixture.messages.getValue(input().id))
        assertEquals(1, fixture.messages.size)
    }

    @Test
    fun abandoningStartedRequestFencesCallbacksAndPreservesUnknownAudit() = runTest {
        val run = runningTool()
        assertTrue(dao.abandonRecoveryRequest(run.requestId, 7_000))
        assertEquals("ABANDONED", fixture.requests.getValue(run.requestId).status)
        assertEquals("UNKNOWN", fixture.tools.getValue(run.id to call.id).status)
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 8_000))
        assertNull(dao.claimRequest(run.requestId, "local", true, "revived", "owner", 8_000))
    }

    @Test
    fun queueEditingAndReorderingAreExactAndCannotMutateRunningOrHeldRequests() = runTest {
        fixture.enqueue()
        fixture.enqueue("second")
        fixture.enqueue("third")
        fixture.claim()
        val editedPayload = Json.encodeToString(com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload("Edited", "~/workspace"))
        assertFalse(dao.updateQueuedRequest("request", editedPayload, 3_000))
        assertTrue(dao.updateQueuedRequest("second", editedPayload, 3_000))
        assertEquals(editedPayload, fixture.requests.getValue("second").payloadJson)
        assertFalse(dao.reorderQueuedRequests("session", listOf("third"), 4_000))
        assertFalse(dao.reorderQueuedRequests("session", listOf("third", "third"), 4_000))
        assertTrue(dao.reorderQueuedRequests("session", listOf("third", "second"), 4_000))
        assertEquals(1L, fixture.requests.getValue("request").queueOrder)
        assertEquals(2L, fixture.requests.getValue("third").queueOrder)
        assertEquals(3L, fixture.requests.getValue("second").queueOrder)
        assertTrue(dao.holdRecoverySession("session", "plan_rejected", 5_000))
        assertFalse(dao.updateQueuedRequest("second", editedPayload, 6_000))
        assertFalse(dao.reorderQueuedRequests("session", listOf("third", "second"), 6_000))
        assertTrue(dao.clearRecoveryHold("session"))
        fixture.enqueue("new-explicit-input")
        assertTrue(dao.claimRequest("new-explicit-input", "local", false, "new-run", "owner", 7_000) != null)
    }

    @Test
    fun resumeInputHasItsOwnFrozenSnapshotAndCannotBeUsedOnFirstRun() = runTest {
        val old = ready()
        assertFalse(dao.commitRecoveryInput(old.id, "owner", input().copy(id = "resume_${old.id}")))
        assertTrue(dao.commitRecoveryInput(old.id, "owner", input()))
        assertEquals("original", dao.freezeRecoveryInputSnapshot(old.id, "owner", "original"))
        assertTrue(dao.finishRecoveryRun(old.id, "owner", "INTERRUPTED", null, 3_000))
        val next = fixture.claim(runId = "resumed", confirmed = true)
        val resumedInput = input().copy(id = "resume_${next.id}", content = "Continue interrupted task", timestamp = 4_000)
        assertTrue(dao.commitRecoveryInput(next.id, "owner", resumedInput))
        assertEquals("resumed", dao.freezeRecoveryInputSnapshot(next.id, "owner", "resumed", resumedInput.id))
        assertNull(dao.freezeRecoveryInputSnapshot(next.id, "owner", "wrong", "resume_${old.id}"))
        assertEquals("original", fixture.messages.getValue(input().id).modelInputSnapshotJson)
        assertEquals("resumed", fixture.messages.getValue(resumedInput.id).modelInputSnapshotJson)
        assertEquals(input().id, fixture.messages.getValue(resumedInput.id).continuationOfMessageId)
    }

    @Test
    fun placeholderAndTimingUseSameFenceAndCannotOverwriteFinalResultOrResurrectDeletedRun() = runTest {
        val run = runningTool()
        val pending = toolResult().copy(content = "[pending]", toolActionTitle = "Original title")
        assertTrue(dao.commitRecoveryToolPlaceholder(run.id, "owner", pending))
        assertEquals("STARTED", fixture.tools.getValue(run.id to call.id).status)
        assertTrue(dao.updateRecoveryToolTiming(run.id, "owner", call.id, 10, null))
        assertTrue(dao.updateRecoveryToolTiming(run.id, "owner", call.id, 99, 12))
        assertTrue(dao.updateRecoveryTurnDuration(run.id, "owner", "assistant", 50))
        assertTrue(dao.updateRecoveryTurnDuration(run.id, "owner", "assistant", 99))
        assertEquals(10L, fixture.messages.getValue(pending.id).toolStartedAtMs)
        assertEquals(12L, fixture.messages.getValue(pending.id).toolDurationMs)
        assertEquals(50L, fixture.messages.getValue("assistant").turnDurationMs)
        assertTrue(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 7_000))
        assertFalse(dao.commitRecoveryToolPlaceholder(run.id, "owner", pending))
        assertEquals("Original result", fixture.messages.getValue(pending.id).content)
        assertTrue(dao.deleteRecoverySession("session", "session_deleted"))
        assertEquals(setOf("session"), fixture.retiredNotificationSessions)
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.runs.isEmpty())
        assertTrue(fixture.tools.isEmpty())
        assertTrue(fixture.drafts.isEmpty())
        assertEquals(1L, fixture.sessions.getValue("session").executionGeneration)
        assertFalse(dao.commitRecoveryToolPlaceholder(run.id, "owner", pending))
        assertFalse(dao.updateRecoveryToolTiming(run.id, "owner", call.id, 100, 100))
        assertFalse(dao.updateRecoveryTurnDuration(run.id, "owner", "assistant", 100))
    }

    @Test
    fun supplementalContinuationIsInsertOnlyAndCannotReplaceSnapshotOrCompactionRows() = runTest {
        val run = ready()
        assertTrue(dao.commitRecoveryInput(run.id, "owner", input()))
        assertEquals("frozen", dao.freezeRecoveryInputSnapshot(run.id, "owner", "frozen"))
        val continuation = input().copy(id = "continuation", content = "Continue output")
        assertTrue(dao.commitRecoverySupplementalInput(run.id, "owner", continuation))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", continuation.copy(content = "Overwrite")))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", input().copy(content = "Overwrite original")))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", continuation.copy(id = "marker", isCompactionMarker = true)))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", continuation.copy(id = "summary", isContextSummary = true)))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", continuation.copy(id = "snapshot", modelInputSnapshotJson = "new")))
        assertEquals("frozen", fixture.messages.getValue(input().id).modelInputSnapshotJson)
        assertEquals("input_request", fixture.requests.getValue("request").inputMessageId)
        assertTrue(dao.finishRecoveryRun(run.id, "owner", "INTERRUPTED", null, 3_000))
        assertFalse(dao.commitRecoverySupplementalInput(run.id, "owner", continuation.copy(id = "late")))
    }

    @Test
    fun inputAndToolResultAcknowledgeNotificationsOnlyAfterFencedPersistence() = runTest {
        val run = ready()
        fixture.writes.clear()
        assertFalse(dao.commitRecoveryInput(run.id, "wrong-owner", input(), listOf(1)))
        assertTrue(fixture.deliveredNotificationSeqs.isEmpty())
        assertTrue(dao.commitRecoveryInput(run.id, "owner", input(), listOf(1)))
        assertEquals(listOf("message:input_request", "notifications:1"), fixture.writes)
        assertTrue(dao.commitRecoveryInput(run.id, "owner", input(), listOf(2)))
        val marker = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        assertTrue(dao.commitRecoveryAssistant(run.id, "owner", marker, assistant(calls = listOf(call)), 4_000))
        assertTrue(dao.markRecoveryTool(run.id, "owner", call.id, true, 5_000))
        fixture.writes.clear()
        assertTrue(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 6_000, listOf(3)))
        assertEquals(listOf("message:tool_call", "tool:call:FINISHED", "clearDraft:run", "notifications:3"), fixture.writes)
        assertTrue(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 7_000, listOf(4)))
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult().copy(content = "conflicting result"), 8_000, listOf(5)))
        assertEquals(setOf(1L, 2L, 3L, 4L), fixture.deliveredNotificationSeqs)
        assertTrue(dao.finishRecoveryRun(run.id, "owner", "INTERRUPTED", null, 9_000))
        assertFalse(dao.commitRecoveryInput(run.id, "owner", input(), listOf(6)))
        assertFalse(dao.commitRecoveryToolResult(run.id, "owner", toolResult(), 10_000, listOf(7)))
        assertEquals(setOf(1L, 2L, 3L, 4L), fixture.deliveredNotificationSeqs)
    }

    private fun snapshot(run: RecoveryRunEntity): RecoveryResumeSnapshot =
        Json.decodeFromString(checkNotNull(run.resumeSnapshotJson))

    private suspend fun ready(): RecoveryRunEntity {
        fixture.enqueue()
        return fixture.claim()
    }

    private suspend fun runningTool(): RecoveryRunEntity {
        val run = ready()
        val marker = checkNotNull(dao.beginRecoveryStep(run.id, "owner", 3_000))
        check(dao.commitRecoveryAssistant(run.id, "owner", marker, assistant(calls = listOf(call)), 4_000))
        check(dao.markRecoveryTool(run.id, "owner", call.id, true, 5_000))
        return run
    }

    private fun assistant(calls: List<ToolCall> = emptyList()) = AgentMessageEntity(
        "assistant", "session", "ASSISTANT", "Visible response", 10,
        toolCallsJson = calls.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
    )

    private fun toolResult() = AgentMessageEntity("tool_call", "session", "TOOL", "Original result", 20,
        toolCallId = call.id, toolName = call.name, toolArgs = "{\"command\":\"printf original\"}")

    private fun input() = AgentMessageEntity("input_request", "session", "USER", "Original input", 1)
}
