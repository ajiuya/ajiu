package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
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
    fun settledChunkKeysOnlyAppendUntilTheWholeMessageIsShown() {
        val lead = "```txt\n" + "0".repeat(2050) + "\n```\n\n"
        val code = "```kotlin\nval number = 1\n```"
        val full = lead + code + "\n\nxyz🚀  \r\n"
        val saved = assistant("a", full, 2)
        val items = flattenChatProcesses(buildChatItems(listOf(user, saved))).filter { it.message.id == saved.id }
        assertTrue(items.size >= 2)
        var previous = emptyMap<String, String>()
        var end = 0
        while (true) {
            val prefix = full.substring(0, end)
            val shown = applyTypewriterContentSlices(items, mapOf(saved.id to prefix))
            assertEquals(items.take(shown.size).map { it.key }, shown.map { it.key })
            assertEquals(prefix, shown.joinToString("") { it.slice!! })
            assertEquals(listOf(shown.first()), shown.filter { it.isChunkHeader })
            assertEquals(listOf(shown.last()), shown.filter { it.isChunkFooter })
            val now = shown.associate { it.key to it.slice!! }
            previous.forEach { (key, raw) -> assertTrue("$key at $end", now.getValue(key).startsWith(raw)) }
            previous = now
            if (end == full.length) {
                assertEquals(items, shown)
                break
            }
            end += Character.charCount(full.codePointAt(end))
        }
    }

    @Test
    fun anotherCharacterAtThePackingLimitNeverMovesAnEarlierParagraph() {
        val lead = "```txt\n" + "0".repeat(2050) + "\n```\n\n"
        val a = "```\n" + "A".repeat(992) + "\n```"
        val base = "$lead$a\n\n"
        val full = base + "b".repeat(400)
        val saved = assistant("a", full, 2)
        val plan = flattenChatProcesses(buildChatItems(listOf(user, saved))).filter { it.message.id == saved.id }
        val before = applyTypewriterContentSlices(plan, mapOf("a" to base + "b".repeat(196)))
        val after = applyTypewriterContentSlices(plan, mapOf("a" to base + "b".repeat(197)))
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(before.dropLast(1), after.dropLast(1))
        assertEquals(before.last().slice + "b", after.last().slice)
        assertTrue(after.last().slice!!.all { it == 'b' })
    }

    @Test
    fun completedCodeFenceSurvivesWhileTheLastParagraphChangesMarkdownType() {
        val lead = "~~~text\n" + "0".repeat(2050) + "\n~~~\n\n"
        val code = "```kotlin\nval number = 1\n```"
        val base = "$lead$code\n\n标题"
        val saved = assistant("a", "$base\n---x", 2)
        val items = flattenChatProcesses(buildChatItems(listOf(user, saved)))
        val parser = MarkdownParser(GFMFlavourDescriptor())
        var previous = ""
        for ((suffix, lastType) in listOf("" to MarkdownElementTypes.PARAGRAPH,
            "\n---" to MarkdownElementTypes.SETEXT_2, "\n---x" to MarkdownElementTypes.PARAGRAPH)) {
            val chunk = applyTypewriterContentSlices(items, mapOf("a" to base + suffix)).single { it.key == "a#chunk1" }.slice!!
            assertTrue(chunk.startsWith(previous))
            val nodes = parser.buildMarkdownTreeFromString(chunk).children
            val fence = nodes.single { it.type == MarkdownElementTypes.CODE_FENCE }
            assertEquals(code, chunk.substring(fence.startOffset, fence.endOffset).trimEnd('\r', '\n'))
            assertTrue(nodes.any { it.type == lastType })
            previous = chunk
        }
    }

    @Test
    fun playbackChangesOnlyBodyRowsEvenWhenOtherRowsUseTheSameCarrier() {
        val saved = assistant("a", "正文末尾", 2)
        val items = flattenChatProcesses(buildChatItems(listOf(user, saved)))
        val body = items.single { it.message.id == saved.id }
        val group = body.copy(key = "group", contentType = SUB_AGENT_GROUP_CONTENT_TYPE)
        val reasoning = body.copy(key = "reason", contentType = "reasoning")
        val shown = applyTypewriterContentSlices(listOf(group, reasoning, body), mapOf("a" to "正文"))
        assertEquals(listOf(group.key, reasoning.key, body.key), shown.map { it.key })
        assertSame(group, shown[0])
        assertSame(reasoning, shown[1])
        assertEquals("正文", shown.last().slice)
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
    fun retainedPlanDoesNotBorrowFinalTextWhenTheSavedMessageWasRewritten() {
        val common = "same prefix "
        val saved = assistant("a", common + "saved text", 2)
        val rows = listOf(
            ChatRenderItem(saved, "a#chunk0", "assistant-chunk", slice = common, sliceStart = 0,
                isChunkHeader = true, isChunkFooter = false),
            ChatRenderItem(saved, "a#chunk1", "assistant-chunk", slice = saved.content.drop(common.length),
                sliceStart = common.length, isChunkHeader = false, isChunkFooter = true),
        )
        val playing = common + "original stream"
        val plan = TypewriterContentPlan(rows, playing)
        val prefix = playing.take(common.length + 4)
        val frame = TypewriterText(prefix, false, playing)
        assertEquals(1, plan.visibleCount(frame))
        assertEquals(prefix, plan.render(rows[0], frame)!!.slice)
        assertEquals(null, plan.render(rows[1], frame))
        assertEquals(saved.content, rows.joinToString("") { it.slice!! })
    }

    @Test
    fun finalPlanDoesNotUseSavedBytesWhileThePlayerStillCarriesItsPreviousSource() {
        val saved = assistant("a", "abc X" + "saved".repeat(5), 2)
        val rows = listOf(
            ChatRenderItem(saved, "a#chunk0", "assistant-chunk", slice = saved.content.take(5),
                sliceStart = 0, isChunkHeader = true, isChunkFooter = false),
            ChatRenderItem(saved, "a#chunk1", "assistant-chunk", slice = saved.content.drop(5),
                sliceStart = 5, isChunkHeader = false, isChunkFooter = true),
        )
        val plan = TypewriterContentPlan(rows, saved.content)
        val oldFrame = TypewriterText("abc  ", true)
        assertTrue(saved.content.startsWith(oldFrame.text.trimEnd()))
        assertEquals(1, plan.visibleCount(oldFrame))
        assertEquals(oldFrame.text, plan.render(rows[0], oldFrame)!!.slice)
        assertEquals(null, plan.render(rows[1], oldFrame))
        val finalFrame = TypewriterText(saved.content, true)
        assertEquals(2, plan.visibleCount(finalFrame))
        assertEquals(rows, rows.map { plan.render(it, finalFrame) })
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
