package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypewriterHandoffTest {
    private val user = AgentUIMessage("u", MessageRole.USER, "request", timestamp = 1)

    private fun assistant(id: String, text: String, time: Long, reasoning: String? = null) =
        AgentUIMessage(id, MessageRole.ASSISTANT, text, timestamp = time, reasoning = reasoning)

    @Test
    fun landingAssistantAndToolTogetherTransfersSamePlaybackKey() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        state = reconcileChatTypewriters(state, listOf(user), "回答前缀", "思考")
        val key = state.entries.single().key
        state = reconcileChatTypewriters(state, listOf(user), null, null)
        assertEquals("回答前缀", state.entries.single().text)
        val saved = assistant("a", "回答前缀以及最后文字🚀", 2, "思考结尾")
        val tool = AgentUIMessage("t", MessageRole.TOOL, "done", timestamp = 3)
        state = reconcileChatTypewriters(state, listOf(user, saved, tool), null, null)
        val entry = state.entries.single()
        assertEquals(key, entry.key)
        assertEquals("a", entry.messageId)
        assertEquals(saved.content, entry.text)
        assertEquals(saved.reasoning, entry.reasoning)
        assertEquals("", entry.initialText)
    }

    @Test
    fun newCallCannotOverwritePreviousLandedAssistant() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        state = reconcileChatTypewriters(state, listOf(user), "共同前缀：第一条", null)
        val firstKey = state.streamKey
        val first = assistant("a1", "共同前缀：第一条完整尾巴", 2)
        val tool = AgentUIMessage("t", MessageRole.TOOL, "done", timestamp = 3)
        state = reconcileChatTypewriters(state, listOf(user, first, tool), "共同前缀：第二条", null)
        assertEquals(first.content, state.entries.single { it.messageId == "a1" }.text)
        assertEquals(firstKey, state.entries.single { it.messageId == "a1" }.key)
        assertFalse(firstKey == state.streamKey)
        val secondKey = state.streamKey
        val second = assistant("a2", "共同前缀：第二条完整尾巴", 4)
        state = reconcileChatTypewriters(state, listOf(user, first, tool, second), null, null)
        assertEquals(2, state.entries.size)
        assertEquals(secondKey, state.entries.single { it.messageId == "a2" }.key)
        assertEquals(first.content, state.entries.single { it.messageId == "a1" }.text)
    }

    @Test
    fun newStreamAfterBatchedToolsDoesNotBindToEarlierMatchingPrefix() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        val first = assistant("a1", "共同开头但属于上一调用", 2)
        val tool = AgentUIMessage("t", MessageRole.TOOL, "done", timestamp = 3)
        state = reconcileChatTypewriters(state, listOf(user, first, tool), "共同开头", null)
        val current = state.entries.single { it.key == state.streamKey }
        assertEquals(null, current.messageId)
        assertEquals("共同开头", current.text)
        assertEquals(first.content, state.entries.single { it.messageId == "a1" }.text)
        val second = assistant("a2", "共同开头但属于下一调用", 4)
        state = reconcileChatTypewriters(state, listOf(user, first, tool, second), null, null)
        assertEquals(current.key, state.entries.single { it.messageId == "a2" }.key)
    }

    @Test
    fun identicalCallsAfterNullGapKeepIndependentMessageIds() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        val first = assistant("a1", "一样的回复", 2)
        state = reconcileChatTypewriters(state, listOf(user), first.content, null)
        state = reconcileChatTypewriters(state, listOf(user, first), null, null)
        state = reconcileChatTypewriters(state, listOf(user, first), first.content, null)
        val second = assistant("a2", first.content, 3)
        state = reconcileChatTypewriters(state, listOf(user, first, second), null, null)
        assertEquals(setOf("a1", "a2"), state.entries.map { it.messageId }.toSet())
        assertEquals(2, state.entries.map { it.key }.toSet().size)
    }

    @Test
    fun landingBeforeNullDoesNotDuplicateTail() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        val saved = assistant("a", "正文", 2)
        state = reconcileChatTypewriters(state, listOf(user), saved.content, null)
        state = reconcileChatTypewriters(state, listOf(user, saved), saved.content, null)
        state = reconcileChatTypewriters(state, listOf(user, saved), saved.content, null)
        assertEquals(1, state.entries.size)
        assertEquals("a", state.entries.single().messageId)
        assertEquals(state.entries.single().key, state.streamKey)
    }

    @Test
    fun historyAndSessionSwitchDoNotReplayExistingMessages() {
        val saved = assistant("a", "历史文字", 2)
        val history = reconcileChatTypewriters(ChatTypewriterState(), listOf(user, saved), null, null)
        assertTrue(history.entries.isEmpty())
        val activeSession = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), "已在后台收到🚀", "历史思考")
        assertEquals("已在后台收到🚀", activeSession.entries.single().initialText)
        assertEquals("历史思考", activeSession.entries.single().initialReasoning)
        val older = assistant("older", "更早历史", 0)
        val paged = reconcileChatTypewriters(history, listOf(older, user, saved), null, null)
        assertTrue(paged.entries.isEmpty())
    }

    @Test
    fun completeResponseWithoutObservedDeltasStillTypes() {
        val initial = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        val saved = assistant("a", "完整响应也需逐字显示", 2)
        val state = reconcileChatTypewriters(initial, listOf(user, saved), null, null)
        assertEquals(saved.content, state.entries.single().text)
        assertEquals("", state.entries.single().initialText)
    }

    @Test
    fun retryDiscardsOnlyUnpersistedPlayback() {
        var state = reconcileChatTypewriters(ChatTypewriterState(), listOf(user), null, null)
        val saved = assistant("a", "落库内容", 2)
        state = reconcileChatTypewriters(state, listOf(user, saved), null, null)
        state = reconcileChatTypewriters(state, listOf(user, saved), "重试前输出", null)
        state = reconcileChatTypewriters(state, listOf(user, saved), null, null, discardPending = true)
        assertEquals(listOf("a"), state.entries.map { it.messageId })
        assertEquals(null, state.streamKey)
    }

    @Test
    fun landedMessageRendersPrefixExactlyOnceWithoutMutatingData() {
        val saved = assistant("a", "首段\n\n最后文字🚀", 2, "真实思考")
        val items = flattenChatProcesses(buildChatItems(listOf(user, saved)))
        val rendered = applyTypewriterContentSlices(items, mapOf("a" to "首段"))
        val body = rendered.filter { it.message.id == "a" && it.contentType != "reasoning" && it.turnHeader == null }
        assertEquals(1, body.size)
        assertEquals("首段", body.single().slice)
        assertEquals(saved, body.single().message)
        assertEquals(saved.content, applyTypewriterContentSlices(items, emptyMap()).last().message.content)
    }

    @Test
    fun streamDeltasDoNotTraverseAnUnchangedMessageHistory() {
        val history = List(1_000) { user.copy(id = "u$it", timestamp = it.toLong()) }
        var reads = 0
        val snapshot = object : AbstractList<AgentUIMessage>() {
            override val size: Int get() = history.size
            override fun get(index: Int): AgentUIMessage {
                reads++
                return history[index]
            }
        }
        var state = reconcileChatTypewriters(ChatTypewriterState(), snapshot, null, null)
        state = reconcileChatTypewriters(state, snapshot, "body", null)
        val seen = state.seenMessageIds
        val streamKey = state.streamKey
        reads = 0
        var text = "body"
        repeat(100) {
            text += "追加🚀"
            state = reconcileChatTypewriters(state, snapshot, text, null)
        }
        assertEquals(0, reads)
        assertSame(seen, state.seenMessageIds)
        assertSame(snapshot, state.messageSnapshot)
        assertEquals(streamKey, state.streamKey)
        assertEquals(text, state.entries.single().text)
    }

    @Test
    fun longMessageDoesNotLeakUnplayedChunks() {
        val saved = assistant("a", (1..80).joinToString("\n\n") { "段落$it " + "文字".repeat(60) }, 2)
        val items = flattenChatProcesses(buildChatItems(listOf(user, saved)))
        assertTrue(items.count { it.message.id == "a" } > 2)
        val rendered = applyTypewriterContentSlices(items, mapOf("a" to "段落1 文字🚀"))
        assertEquals(listOf("段落1 文字🚀"), rendered.filter { it.message.id == "a" }.map { it.slice })
        assertTrue(rendered.filter { it.message.id == "a" }.all { it.message == saved })
    }
}
