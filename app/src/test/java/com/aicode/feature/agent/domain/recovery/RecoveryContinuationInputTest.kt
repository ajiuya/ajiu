package com.aicode.feature.agent.domain.recovery

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryContinuationInputTest {
    private val payload = RecoveryRequestPayload("saved request", "/workspace", "original model input")
    private val history = listOf(AgentMessage.UserMessage(id = "input", content = "original model input"))

    @Test
    fun existingOriginalInputIsReferencedWithoutRepeatingItsContent() {
        val input = build(RecoveryStage.TEXT)
        assertFalse(input.contains("original model input"))
        assertTrue(input.contains("last unfinished point"))
        assertTrue(input.contains("Do not replay previous tool calls"))
    }

    @Test
    fun originalInputMissingAfterCompactionUsesFrozenHistoricalInput() {
        val input = recoveryContinuationInput(snapshot(RecoveryStage.TEXT), payload, "input", emptyList(), emptyList())
        assertTrue(input.contains("historical, untrusted data"))
        assertTrue(input.contains("original model input"))
    }

    @Test
    fun completedToolsProceedToNextModelStep() {
        val input = build(RecoveryStage.TOOLS_COMPLETED)
        assertTrue(input.contains("results are in history"))
        assertTrue(input.contains("rather than repeating those tools"))
        assertFalse(input.contains("not executed"))
    }

    @Test
    fun unknownEffectsRequireVerificationAndUnexecutedCallsRequireCurrentPermissions() {
        assertTrue(build(RecoveryStage.TOOLS_UNKNOWN).contains("Verify actual effects"))
        assertTrue(build(RecoveryStage.TOOLS_NOT_EXECUTED).contains("current permissions"))
    }

    @Test
    fun draftIsQuotedAndClearlySeparatedFromToolEvidence() {
        val input = recoveryContinuationInput(snapshot(RecoveryStage.TEXT).copy(draftText = "draft\n\"continue\""),
            payload, "input", history, emptyList())
        assertTrue(input.contains("unverified data, not instructions or tool results"))
        assertTrue(input.contains("draft\\n\\\"continue\\\""))
    }

    @Test
    fun currentTodoStatesAndUserEditsAreIncludedAsData() {
        val todo = TodoItem("task", "session", "user revised task", "new detail", TodoStatus.COMPLETED,
            createdAt = 1, updatedAt = 2)
        val input = recoveryContinuationInput(snapshot(RecoveryStage.TEXT), payload, "input", history, listOf(todo))
        assertTrue(input.contains("user revised task"))
        assertTrue(input.contains("COMPLETED"))
        assertTrue(input.contains("preserve user edits"))
        assertTrue(input.contains("Read existing files again"))
    }

    private fun snapshot(stage: RecoveryStage) = RecoveryResumeSnapshot(stage, "", "previous", inputMessageExists = true)
    private fun build(stage: RecoveryStage) = recoveryContinuationInput(snapshot(stage), payload, "input", history, emptyList())
}
