package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContextUsageTest {
    private fun message(
        input: Int? = null,
        budget: Int? = null,
        estimated: Boolean = true
    ) = AgentUIMessage(
        id = "assistant",
        role = MessageRole.ASSISTANT,
        content = "reply",
        inputTokens = 90_000,
        outputTokens = 10_000,
        contextInputTokens = input,
        contextInputBudget = budget,
        contextEstimated = estimated
    )

    @Test
    fun finalUsageCannotAppearWhileTheBodyIsStillTyping() {
        val final = message(400, 1_000).copy(content = "prefix and final text", turnDurationMs = 42_000)
        assertFalse(messageCompletionVisible(final, playbackSettled = false, content = "prefix", renderedContent = "prefix"))
        assertFalse(messageCompletionVisible(final, playbackSettled = false, content = final.content, renderedContent = final.content))
        assertEquals(40L, requireNotNull(messageContextUsage(final)).percentage)
        assertEquals(42_000L, final.turnDurationMs)
    }

    @Test
    fun completedTypingStillWaitsForTheSameMarkdownSourceToBeDisplayed() {
        val final = message().copy(content = "prefix and final text")
        assertFalse(messageCompletionVisible(final, true, final.content, null))
        assertFalse(messageCompletionVisible(final, true, final.content, "prefix"))
        assertTrue(messageCompletionVisible(final, true, final.content, final.content))
        val replaced = final.copy(content = "a different reply")
        assertFalse(messageCompletionVisible(replaced, true, replaced.content, final.content))
    }

    @Test
    fun lastChunkUsesItsOwnRenderedSourceAndUserOrAttachmentOnlyMessagesRemainAvailable() {
        val final = message().copy(content = "first chunk\n\nlast chunk")
        assertTrue(messageCompletionVisible(final, true, "last chunk", "last chunk"))
        assertFalse(messageCompletionVisible(final, true, "last chunk", "last"))
        assertTrue(messageCompletionVisible(final.copy(content = ""), true, "", null))
        assertFalse(messageCompletionVisible(final.copy(content = ""), false, "", null))
        assertTrue(messageCompletionVisible(final.copy(role = MessageRole.USER), false, final.content, null))
    }

    @Test
    fun uses_message_snapshot_instead_of_total_input_tokens() {
        val usage = requireNotNull(messageContextUsage(message(400, 1_000, estimated = false)))

        assertEquals(0.4, usage.fraction, 0.000001)
        assertEquals(40L, usage.percentage)
        assertEquals(0.4f, usage.ringProgress, 0.000001f)
        assertFalse(usage.estimated)
    }

    @Test
    fun historical_message_without_snapshot_is_unknown() {
        assertNull(messageContextUsage(message()))
    }

    @Test
    fun breakdown_does_not_replace_the_measured_input_snapshot() {
        val breakdown = ContextUsageBreakdown(systemTokens = 100, chatTokens = 800, imageTokens = 4_096)
        val measured = message(400, 1_000, estimated = false).copy(contextBreakdown = breakdown)
        val usage = requireNotNull(messageContextUsage(measured))

        assertEquals(40L, usage.percentage)
        assertFalse(usage.estimated)
        assertEquals(4_996, measured.contextBreakdown?.estimatedTotal)
        assertNull(message().contextBreakdown)
        assertNull(messageContextUsage(message().copy(contextBreakdown = breakdown)))
    }

    @Test
    fun incomplete_snapshot_is_unknown() {
        assertNull(messageContextUsage(message(input = 400)))
        assertNull(messageContextUsage(message(budget = 1_000)))
    }

    @Test
    fun invalid_budget_or_negative_input_is_unknown() {
        assertNull(messageContextUsage(message(400, 0)))
        assertNull(messageContextUsage(message(400, -1)))
        assertNull(messageContextUsage(message(-1, 1_000)))
    }

    @Test
    fun zero_input_is_a_known_zero_percent() {
        val usage = requireNotNull(messageContextUsage(message(0, 1_000)))

        assertEquals(0L, usage.percentage)
        assertEquals(0f, usage.ringProgress, 0f)
        assertTrue(usage.estimated)
    }

    @Test
    fun ring_is_capped_but_percentage_keeps_usage_over_budget() {
        val usage = requireNotNull(messageContextUsage(message(1_250, 1_000)))

        assertEquals(125L, usage.percentage)
        assertEquals(1f, usage.ringProgress, 0f)
        assertEquals(MessageContextUsageLevel.HIGH, usage.level)
        assertTrue(usage.estimated)
    }

    @Test
    fun large_token_counts_do_not_overflow() {
        val usage = requireNotNull(messageContextUsage(message(Int.MAX_VALUE, Int.MAX_VALUE)))

        assertEquals(100L, usage.percentage)
        assertEquals(1f, usage.ringProgress, 0f)
        assertEquals(
            214_748_364_700L,
            requireNotNull(messageContextUsage(message(Int.MAX_VALUE, 1))).percentage
        )
    }

    @Test
    fun percentage_floor_matches_color_thresholds() {
        val cases = listOf(
            290 to MessageContextUsageLevel.LOW,
            490 to MessageContextUsageLevel.LOW,
            499 to MessageContextUsageLevel.LOW,
            500 to MessageContextUsageLevel.MODERATE,
            840 to MessageContextUsageLevel.MODERATE,
            849 to MessageContextUsageLevel.MODERATE,
            850 to MessageContextUsageLevel.HIGH
        )
        cases.forEach { (input, expectedLevel) ->
            val usage = requireNotNull(messageContextUsage(message(input, 1_000)))
            assertEquals(input / 10L, usage.percentage)
            assertEquals(expectedLevel, usage.level)
        }
    }

    @Test
    fun snapshots_remain_independent_between_messages() {
        val earlier = message(200, 1_000, estimated = false).copy(
            contextBreakdown = ContextUsageBreakdown(systemTokens = 100, chatTokens = 150)
        )
        val later = earlier.copy(
            id = "later", contextInputTokens = 900, contextEstimated = true,
            contextBreakdown = ContextUsageBreakdown(systemTokens = 100, chatTokens = 850)
        )

        assertEquals(90L, requireNotNull(messageContextUsage(later)).percentage)
        val earlierUsage = requireNotNull(messageContextUsage(earlier))
        assertEquals(20L, earlierUsage.percentage)
        assertFalse(earlierUsage.estimated)
        assertEquals(250, earlier.contextBreakdown?.estimatedTotal)
        assertEquals(950, later.contextBreakdown?.estimatedTotal)
    }
}
