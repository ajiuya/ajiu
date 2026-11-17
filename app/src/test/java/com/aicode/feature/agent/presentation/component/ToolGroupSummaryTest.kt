package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.*
import org.junit.Test

class ToolGroupSummaryTest {
    private fun tool(id: String, pending: Boolean = false, error: Boolean = false, duration: Long? = null) =
        AgentUIMessage(id, MessageRole.TOOL, if (pending) "[running] executing" else "done", isError = error, toolDurationMs = duration)

    @Test
    fun summaryUsesMeasuredTurnTimeRatherThanAddingParallelTools() {
        val result = toolGroupSummary(listOf(tool("a", duration = 100), tool("b", duration = 3_000, error = true)), 4_500)
        assertEquals(2, result.count)
        assertEquals(4_500L, result.durationMs)
        assertEquals(1, result.failureCount)
        assertFalse(result.running)
    }

    @Test
    fun oldRowsWithoutMeasuredTurnTimeStayUnknown() {
        assertNull(toolGroupSummary(listOf(tool("a"), tool("b")), null).durationMs)
    }

    @Test
    fun pendingAndLiveOutputAreNeverCountedAsFailed() {
        val result = toolGroupSummary(listOf(tool("a", pending = true, error = true), tool("b", error = true)), null, setOf("b"))
        assertTrue(result.running)
        assertEquals(0, result.failureCount)
    }

    @Test
    fun realElapsedIsMonotonicAndCompletedValueWins() {
        assertEquals(250L, toolElapsedMs(tool("a", pending = true), 1_000, 1_250))
        assertEquals(125L, toolElapsedMs(tool("a", duration = 125), 1_000, 9_000))
        assertEquals(0L, toolElapsedMs(tool("a", pending = true), 1_000, 900))
        assertNull(toolElapsedMs(tool("a", pending = true), null, 9_000))
        assertNull(toolElapsedMs(tool("a"), 1_000, 9_000))
    }

    @Test
    fun smallDurationsAreNotDisplayedAsZero() {
        assertEquals("<0.1s", formatToolDuration(0))
        assertEquals("<0.1s", formatToolDuration(99))
        assertEquals("0.1s", formatToolDuration(100))
        assertEquals("2.1s", formatToolDuration(2_199))
        assertEquals("1:00", formatToolDuration(60_000))
    }

    @Test
    fun executionStateOnlySucceedsAfterCompletion() {
        assertEquals(ToolExecutionState.RUNNING, toolExecutionState(true, false))
        assertEquals(ToolExecutionState.RUNNING, toolExecutionState(true, true))
        assertEquals(ToolExecutionState.FAILED, toolExecutionState(false, true))
        assertEquals(ToolExecutionState.SUCCEEDED, toolExecutionState(false, false))
    }
}
