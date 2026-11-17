package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮任务总耗时的划分（[computeTaskDurations]）与展示格式（[formatTaskDuration]）。
 */
class TaskDurationTest {

    private fun user(id: String, ts: Long, marker: Boolean = false) = AgentUIMessage(
        id = id,
        role = MessageRole.USER,
        content = "hi",
        timestamp = ts,
        isCompactionMarker = marker
    )

    private fun assistant(id: String, ts: Long, summary: Boolean = false) = AgentUIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "ok",
        timestamp = ts,
        isContextSummary = summary,
        turnDurationMs = mapOf("a1" to if (ts == 4_000L) 3_000L else 5_000L, "a2" to if (ts == 16_000L) 5_000L else 8_000L)[id]
    )

    private fun tool(id: String, ts: Long) = AgentUIMessage(
        id = id,
        role = MessageRole.TOOL,
        content = "result",
        timestamp = ts,
        toolName = "Bash"
    )

    @Test
    fun finished_turn_measures_from_user_message_to_last_assistant() {
        val messages = listOf(user("u1", 1_000), assistant("a1", 6_000))
        val durations = computeTaskDurations(messages, lastTurnFinished = true)
        assertEquals(5_000L, durations["a1"])
    }

    @Test
    fun still_streaming_last_message_has_no_duration() {
        val messages = listOf(user("u1", 1_000), assistant("a1", 6_000))
        assertTrue(computeTaskDurations(messages, lastTurnFinished = false).isEmpty())
    }

    @Test
    fun only_the_last_assistant_of_a_tool_loop_gets_the_total() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000),
            tool("t1", 3_000),
            assistant("a2", 9_000)
        )
        val durations = computeTaskDurations(messages, lastTurnFinished = true)
        assertNull(durations["a1"])
        assertEquals(8_000L, durations["a2"])
    }

    @Test
    fun each_turn_counted_from_its_own_user_message() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 4_000),
            user("u2", 11_000),
            assistant("a2", 16_000)
        )
        val durations = computeTaskDurations(messages, lastTurnFinished = true)
        assertEquals(3_000L, durations["a1"])
        assertEquals(5_000L, durations["a2"])
    }

    @Test
    fun compaction_marker_and_summary_do_not_split_the_turn() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000),
            user("marker", 3_000, marker = true),
            assistant("summary", 3_001, summary = true),
            assistant("a2", 9_000)
        )
        val durations = computeTaskDurations(messages, lastTurnFinished = true)
        assertNull(durations["summary"])
        assertEquals(8_000L, durations["a2"])
    }

    @Test
    fun turn_ending_on_a_tool_message_gets_no_duration() {
        val messages = listOf(user("u1", 1_000), assistant("a1", 2_000), tool("t1", 3_000))
        assertTrue(computeTaskDurations(messages, lastTurnFinished = true).isEmpty())
    }

    @Test
    fun assistant_without_a_preceding_user_message_is_skipped() {
        // 分页只加载到助手消息时，找不到本轮起点，不显示耗时
        val messages = listOf(assistant("a1", 2_000).copy(turnDurationMs = null))
        assertTrue(computeTaskDurations(messages, lastTurnFinished = true).isEmpty())
    }

    @Test
    fun oldTimestampDifferenceIsNotUsedAsMeasuredTime() {
        val messages = listOf(user("u1", 1_000), assistant("a1", 6_000).copy(turnDurationMs = null))
        assertTrue(computeTaskDurations(messages, true).isEmpty())
    }

    @Test
    fun backgroundNoticeDoesNotResetTheUserTurn() {
        val notice = user("notice", 4_000).copy(isBackgroundNotification = true)
        val messages = listOf(user("u1", 1_000), assistant("a1", 3_000), notice, assistant("a2", 9_000))
        assertEquals(mapOf("a2" to 8_000L), computeTaskDurations(messages, true))
    }

    @Test
    fun measuredDurationSurvivesPagedOutUserMessage() {
        val messages = listOf(tool("t1", 1_000), assistant("a1", 2_000).copy(turnDurationMs = 12_345))
        assertEquals(mapOf("a1" to 12_345L), computeTaskDurations(messages, true))
    }

    @Test
    fun format_seconds_minutes_hours() {
        assertEquals("1s", formatTaskDuration(200))
        assertEquals("5s", formatTaskDuration(4_600))
        assertEquals("59s", formatTaskDuration(59_400))
        assertEquals("1:00", formatTaskDuration(59_600))
        assertEquals("2:05", formatTaskDuration(125_000))
        assertEquals("1:02:05", formatTaskDuration(3_725_000))
    }
}
