package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryContinuationProjectionTest {
    @Test
    fun adjacentContinuationsKeepOriginalTurnAndLatestUsage() {
        val messages = listOf(user("original"), assistant("partial", 2), continuation("resume-1"),
            assistant("middle", 3), continuation("resume-2"), assistant("final", 5))
        val keys = reconcileTurnKeys(messages, emptyMap(), "session")
        assertEquals(setOf("turn:original"), keys.values.toSet())
        assertEquals(10, computeTurnUsage(messages, true).getValue("final").inputTokens)
        assertFalse(buildChatItems(messages).any { it.key == "resume-1" && it.turnHeader == null })
    }

    @Test
    fun interveningRealUserTurnKeepsExplicitContinuationBoundary() {
        val messages = listOf(user("original"), assistant("first", 2), user("another"), assistant("second", 3),
            continuation("resume-1"), assistant("final", 5))
        val keys = reconcileTurnKeys(messages, emptyMap(), "session")
        assertEquals("turn:original", keys["first"])
        assertEquals("turn:another", keys["second"])
        assertEquals("turn:resume-1", keys["final"])
        assertEquals(3, computeTurnUsage(messages, true).size)
        assertTrue(buildChatItems(messages).any { it.message.id == "resume-1" && it.turnHeader == null })
    }

    @Test
    fun incompleteDraftStaysVisibleWithContinuationMetadataWhenProcessIsCollapsed() {
        val resume = continuation("resume-1").copy(continuationDraft = "incomplete visible text")
        val items = buildChatItems(listOf(user("original"), assistant("partial", 2), resume, assistant("final", 5)))
        val draft = items.single { it.message.id == "resume-1" && it.turnHeader == null }
        assertEquals("incomplete visible text", draft.message.continuationDraft)
        assertEquals(MessageRole.USER, draft.message.role)
        assertEquals("original", draft.message.continuationOfMessageId)
    }

    private fun user(id: String) = AgentUIMessage(id, MessageRole.USER, id, timestamp = 1)
    private fun continuation(id: String) = user(id).copy(continuationOfMessageId = "original")
    private fun assistant(id: String, input: Int) = AgentUIMessage(id, MessageRole.ASSISTANT, id,
        timestamp = 2, inputTokens = input, outputTokens = 1)
}
