package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupExpansionTest {
    private fun user(id: String) = AgentUIMessage(id, MessageRole.USER, "hi")
    private fun tool(id: String) = AgentUIMessage(id, MessageRole.TOOL, "done")
    private fun assistant(id: String) = AgentUIMessage(id, MessageRole.ASSISTANT, "answer")

    private fun items(
        messages: List<AgentUIMessage>,
        turnOverrides: Map<String, Boolean> = emptyMap(),
        activeTurnKey: String? = null,
    ) = buildChatItems(messages, turnOverrides, activeTurnKey)

    private fun List<ChatRenderItem>.header() = first { it.turnHeader != null }

    @Test
    fun runningToolsAreIndependentRowsWithoutProcessHeader() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0"
        ))
        assertEquals(listOf("u0", "t1", "t2"), rendered.map { it.key })
        assertTrue(rendered.none { it.turnHeader != null })
    }

    @Test
    fun singleToolUsesTheSameListLayout() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0"
        ))
        assertEquals(listOf("u0", "t1"), rendered.map { it.key })
    }

    @Test
    fun middleAndFinalBodyDoNotCollapseRunningTools() {
        val messages = listOf(user("u0"), tool("t1"), assistant("a1"), tool("t2"), assistant("a2"))
        val rendered = flattenChatProcesses(items(messages, activeTurnKey = "turn:u0"))
        assertEquals(messages.map { it.id }, rendered.map { it.key })
        assertTrue(rendered.none { it.turnHeader != null })
    }

    @Test
    fun toolCompletionAloneDoesNotCollapseTheRunningTurn() {
        val pending = tool("t1").copy(content = "[running] command")
        val before = flattenChatProcesses(items(listOf(user("u0"), pending), activeTurnKey = "turn:u0"))
        val after = flattenChatProcesses(items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0"))
        assertEquals(before.map { it.key }, after.map { it.key })
        assertTrue(after.none { it.turnHeader != null })
    }

    @Test
    fun runningTurnIgnoresOldWholeTurnCollapsePreference() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1")),
            turnOverrides = mapOf("turn:u0" to false), activeTurnKey = "turn:u0"
        ))
        assertEquals(listOf("u0", "t1"), rendered.map { it.key })
    }

    @Test
    fun finishedTurnHasOneSummaryAndKeepsFinalAnswerVisible() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1"), tool("t2"), assistant("a1"))
        ))
        assertEquals(listOf("u0", "turn:u0#header", "a1"), rendered.map { it.key })
        assertFalse(rendered.header().turnHeader!!.expanded)
        assertFalse(rendered.header().turnHeader!!.running)
        assertFalse(rendered.last().reasoningVisible)
    }

    @Test
    fun finishedTurnWithoutAnswerStillCollapsesAllTools() {
        val rendered = flattenChatProcesses(items(listOf(user("u0"), tool("t1"), tool("t2"))))
        assertEquals(listOf("u0", "turn:u0#header"), rendered.map { it.key })
    }

    @Test
    fun finishedSummaryCanBeManuallyExpandedToIndependentRows() {
        val rendered = flattenChatProcesses(items(
            listOf(user("u0"), tool("t1"), tool("t2"), assistant("a1")),
            turnOverrides = mapOf("turn:u0" to true)
        ))
        assertEquals(listOf("u0", "turn:u0#header", "t1", "t2", "a1"), rendered.map { it.key })
        assertTrue(rendered.header().turnHeader!!.expanded)
    }

    @Test
    fun manualHistoricalExpansionSurvivesRebuild() {
        val messages = listOf(user("u0"), tool("t1"), tool("t2"))
        val overrides = mapOf("turn:u0" to true)
        assertEquals(items(messages, overrides), items(messages, overrides))
        assertTrue(items(messages, overrides).header().turnHeader!!.expanded)
    }

    @Test
    fun finishingResetsOnlyTheTurnThatWasRunning() {
        assertEquals("turn:u0", finishedToolTurn("turn:u0", null))
        assertEquals("turn:u0", finishedToolTurn("turn:u0", "turn:u1"))
        assertEquals(null, finishedToolTurn("turn:u0", "turn:u0"))
        assertEquals(null, finishedToolTurn(null, "turn:u1"))
        assertEquals(null, finishedToolTurn(null, null))
    }

    @Test
    fun queuedTurnKeepsThePreviousTurnCollapsed() {
        val messages = listOf(user("u0"), tool("t1"), assistant("a1"), user("u1"), tool("t2"))
        val finished = finishedToolTurn("turn:u0", "turn:u1")!!
        val overrides = mapOf("turn:u0" to true) + (finished to false)
        val rendered = flattenChatProcesses(items(messages, overrides, "turn:u1"))
        assertEquals(listOf("u0", "turn:u0#header", "a1", "u1", "t2"), rendered.map { it.key })
    }

    @Test
    fun historicalExpansionDoesNotChangeRunningTurn() {
        val messages = listOf(user("u0"), tool("t1"), user("u1"), tool("t2"))
        val rendered = flattenChatProcesses(items(messages, mapOf("turn:u0" to true), "turn:u1"))
        assertEquals(listOf("u0", "turn:u0#header", "t1", "u1", "t2"), rendered.map { it.key })
    }

    @Test
    fun processGrowthPreservesExistingLazyItemKeys() {
        fun render(count: Int) = flattenChatProcesses(items(
            listOf(user("u0")) + (1..count).map { tool("t$it") }, activeTurnKey = "turn:u0"
        ))
        val before = render(8)
        val after = render(9)
        assertEquals(before.map { it.key }, after.take(before.size).map { it.key })
    }

    @Test
    fun longToolProcessUsesIndependentLazyItems() {
        val messages = listOf(user("u0")) + (1..150).map { tool("t$it") }
        val running = flattenChatProcesses(items(messages, activeTurnKey = "turn:u0"))
        assertEquals(messages.map { it.id }, running.map { it.key })
        assertEquals(2, flattenChatProcesses(items(messages)).size)
        val expanded = flattenChatProcesses(items(messages, mapOf("turn:u0" to true)))
        assertEquals(152, expanded.size)
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
    }

    @Test
    fun compactionMarkerDoesNotStartNewTurn() {
        val marker = user("marker").copy(isCompactionMarker = true)
        val summary = assistant("summary").copy(isContextSummary = true)
        val rendered = items(listOf(user("u0"), tool("t1"), marker, summary, assistant("answer")))
        assertEquals(1, rendered.count { it.turnHeader != null })
        assertEquals("turn:u0", rendered.header().turnHeader!!.key)
        assertEquals(listOf("t1", "marker", "summary"), rendered.header().turnProcess.map { it.key })
    }

    @Test
    fun backgroundNotificationBelongsToTheSameProcess() {
        val notice = user("notice").copy(isBackgroundNotification = true)
        val rendered = items(listOf(user("u0"), tool("t1"), notice, assistant("answer")))
        assertTrue(rendered.header().turnProcess.any { it.key == "notice" })
        assertFalse(flattenChatProcesses(rendered).any { it.key == "notice" })
        assertTrue(rendered.any { it.key == "answer" })
    }

    @Test
    fun trailingCompactionSummaryDoesNotReplaceFinalAnswer() {
        val summary = assistant("summary").copy(isContextSummary = true)
        val rendered = items(listOf(user("u0"), tool("t1"), assistant("answer"), summary))
        assertTrue(rendered.any { it.key == "answer" })
        assertTrue(rendered.header().turnProcess.any { it.key == "summary" })
    }

    @Test
    fun toolAttachmentsRemainVisibleAfterProcessCollapses() {
        val attachment = AgentAttachment("report.pdf", "~/report.pdf", "/tmp/report.pdf", "application/pdf", 1_024, false)
        val messages = listOf(user("u0"), tool("t1"), tool("send").copy(attachments = listOf(attachment)), tool("t2"), assistant("answer"))
        val rendered = flattenChatProcesses(items(messages))
        assertTrue(rendered.any { it.key == "send#attachments" })
        assertTrue(rendered.any { it.key == "answer" })
        assertFalse(rendered.any { it.key == "t1" || it.key == "t2" || it.key == "send" })
    }

    @Test
    fun runningAttachmentToolKeepsItsOriginalPosition() {
        val attachment = AgentAttachment("report.pdf", "~/report.pdf", "/tmp/report.pdf", "application/pdf", 1_024, false)
        val messages = listOf(user("u0"), tool("send").copy(attachments = listOf(attachment)), assistant("middle"), tool("t2"))
        val rendered = flattenChatProcesses(items(messages, activeTurnKey = "turn:u0"))
        assertEquals(listOf("u0", "send", "send#attachments", "middle", "t2"), rendered.map { it.key })
        assertTrue(rendered.first { it.key == "send" }.message.attachments.isEmpty())
        assertEquals(listOf(attachment), rendered.first { it.key == "send#attachments" }.message.attachments)
    }

    @Test
    fun attachmentOnlyToolStillHasACompletedSummaryAndReopensItsCapsule() {
        val attachment = AgentAttachment("report.pdf", "~/report.pdf", "/tmp/report.pdf", "application/pdf", 1_024, false)
        val messages = listOf(user("u0"), tool("send").copy(attachments = listOf(attachment)), assistant("answer"))
        val collapsed = flattenChatProcesses(items(messages))
        assertEquals(listOf("u0", "turn:u0#header", "send#attachments", "answer"), collapsed.map { it.key })
        val expanded = flattenChatProcesses(items(messages, mapOf("turn:u0" to true)))
        assertEquals(1, expanded.count { it.key == "send" })
        assertEquals(1, expanded.count { it.key == "send#attachments" })
    }

    @Test
    fun reopeningProcessKeepsAttachmentsInOriginalOrderWithoutDuplicates() {
        val attachment = AgentAttachment("report.pdf", "~/report.pdf", "/tmp/report.pdf", "application/pdf", 1_024, false)
        val messages = listOf(user("u0"), tool("send").copy(attachments = listOf(attachment)), assistant("middle"), tool("t2"), assistant("answer"))
        val rendered = flattenChatProcesses(items(messages, mapOf("turn:u0" to true)))
        assertEquals(listOf("u0", "turn:u0#header", "send", "send#attachments", "middle", "t2", "answer"), rendered.map { it.key })
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
    }

    @Test
    fun pureImageAssistantAnswerRemainsVisibleAfterCompletion() {
        val image = AgentAttachment("image.png", "~/image.png", "/tmp/image.png", "image/png", 1_024, true)
        val answer = assistant("image").copy(content = "", attachments = listOf(image), reasoning = "thinking")
        val rendered = flattenChatProcesses(items(listOf(user("u0"), tool("t1"), answer)))
        assertEquals(listOf("u0", "turn:u0#header", "image"), rendered.map { it.key })
        assertEquals(listOf(image), rendered.last().message.attachments)
    }

    @Test
    fun intermediateAssistantAttachmentRemainsVisible() {
        val image = AgentAttachment("image.png", "~/image.png", "/tmp/image.png", "image/png", 1_024, true)
        val messages = listOf(user("u0"), assistant("image").copy(content = "", attachments = listOf(image)), tool("t1"), assistant("answer"))
        val rendered = flattenChatProcesses(items(messages))
        assertTrue(rendered.any { it.key == "image" })
        assertTrue(rendered.any { it.key == "answer" })
        assertFalse(rendered.any { it.key == "t1" })
    }

    @Test
    fun truncatedWindowCollapsesLeadingHalfTurn() {
        val rendered = items(listOf(assistant("a1"), tool("t1"), tool("t2"), user("u0"), assistant("answer")))
        val header = rendered.header()
        assertEquals("turn:head:t2", header.turnHeader!!.key)
        assertFalse(header.turnHeader!!.expanded)
        assertEquals(listOf("a1", "t1", "t2"), header.turnProcess.map { it.key })
    }

    @Test
    fun truncatedWindowKeepsTrailingAnswerVisible() {
        val rendered = items(listOf(tool("t1"), tool("t2"), assistant("a9"), user("u0"), assistant("answer")))
        assertEquals(listOf("t1", "t2"), rendered.header().turnProcess.map { it.key })
        assertTrue(rendered.any { it.key == "a9" })
        assertFalse(rendered.first { it.key == "a9" }.reasoningVisible)
    }

    @Test
    fun prependingOlderMessagesPreservesLeadingExpansion() {
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"), user("u0"))
        val overrides = mapOf("turn:head:t2" to true)
        val before = items(tail, overrides)
        val after = items(listOf(assistant("older"), tool("old-tool")) + tail, overrides)
        assertEquals(before.header().turnHeader!!.key, after.header().turnHeader!!.key)
        assertTrue(after.header().turnHeader!!.expanded)
    }

    @Test
    fun halfTurnExpansionIsIsolatedBySession() {
        val messages = listOf(assistant("a1"), tool("t1"), tool("t2"))
        val overrides = mapOf("turn:head:session:s1" to true)
        assertTrue(buildChatItems(messages, overrides, sessionId = "s1").header().turnHeader!!.expanded)
        assertFalse(buildChatItems(messages, overrides, sessionId = "s2").header().turnHeader!!.expanded)
    }

    @Test
    fun halfTurnExpansionSurvivesToolUpdatesAndWindowShift() {
        val overrides = mapOf("turn:head:session:s1" to true)
        for (window in listOf(
            listOf(assistant("a1"), tool("t1")),
            listOf(tool("t1"), tool("t2")),
            listOf(tool("t2"), tool("t3")),
        )) {
            assertTrue(buildChatItems(window, overrides, sessionId = "s1").header().turnHeader!!.expanded)
        }
    }

    @Test
    fun loadingTurnStartPreservesHeaderAndExpansion() {
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"))
        val previous = reconcileTurnKeys(tail, emptyMap(), "s1")
        val key = previous.getValue("a1")
        val full = listOf(user("u0")) + tail
        val next = reconcileTurnKeys(full, previous, "s1")
        val rendered = buildChatItems(full, mapOf(key to true), sessionId = "s1", turnKeys = next)
        assertEquals(key, rendered.header().turnHeader!!.key)
        assertTrue(rendered.header().turnHeader!!.expanded)
        assertEquals(previous.getValue("t1"), next.getValue("t1"))
    }

    @Test
    fun slidingWindowPreservesRunningTurnIdentity() {
        val full = listOf(user("u0"), assistant("a1"), tool("t1"))
        val previous = reconcileTurnKeys(full, emptyMap(), "s1")
        val tail = full.drop(1) + tool("t2")
        val keys = reconcileTurnKeys(tail, previous, "s1")
        val rendered = flattenChatProcesses(buildChatItems(tail, activeTurnKey = "turn:u0", sessionId = "s1", turnKeys = keys))
        assertEquals("turn:u0", keys.getValue("t2"))
        assertEquals(tail.map { it.id }, rendered.map { it.key })
    }

    @Test
    fun prependingOtherTurnsKeepsTheirIdentitiesSeparate() {
        val tail = listOf(assistant("a1"), tool("t1"), user("u2"), tool("t2"))
        val previous = reconcileTurnKeys(tail, emptyMap(), "s1")
        val full = listOf(user("u0"), tool("t0"), user("u1")) + tail
        val keys = reconcileTurnKeys(full, previous, "s1")
        assertEquals("turn:u0", keys.getValue("u0"))
        assertEquals(previous.getValue("a1"), keys.getValue("u1"))
        assertEquals("turn:u2", keys.getValue("u2"))
        val rendered = flattenChatProcesses(buildChatItems(full, turnKeys = keys))
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
    }

    @Test
    fun reloadingEvictedTurnPreservesItsHeaderAndExpansion() {
        val history = mutableMapOf<String, String>()
        val tail = listOf(assistant("a1"), tool("t1"))
        history.putAll(reconcileTurnKeys(tail, history, "s1"))
        val key = history.getValue("a1")
        val full = listOf(user("u1")) + tail
        history.putAll(reconcileTurnKeys(full, history, "s1"))
        val newer = listOf(user("u2"), tool("t2"))
        history.putAll(reconcileTurnKeys(newer, history, "s1"))
        val keys = reconcileTurnKeys(full + newer, history, "s1")
        val rendered = flattenChatProcesses(buildChatItems(full + newer, mapOf(key to true), turnKeys = keys))
        assertEquals(key, keys.getValue("u1"))
        assertTrue(rendered.first { it.turnHeader?.key == key }.turnHeader!!.expanded)
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
    }

    @Test
    fun searchTargetsAnswerInsteadOfItsReasoning() {
        val answer = assistant("a1").copy(reasoning = "thinking")
        val rendered = flattenChatProcesses(items(listOf(user("u0"), answer), mapOf("turn:u0" to true)))
        assertEquals("a1", rendered[findChatMessageIndex(rendered, "a1")].key)
    }

    @Test
    fun searchTargetsFirstLongAnswerChunk() {
        val answer = assistant("a1").copy(
            content = (1..100).joinToString("\n\n") { "paragraph $it " + "content ".repeat(20) }, reasoning = "thinking"
        )
        val rendered = flattenChatProcesses(items(listOf(user("u0"), answer), mapOf("turn:u0" to true)))
        assertEquals("a1#chunk0", rendered[findChatMessageIndex(rendered, "a1")].key)
        assertEquals(-1, findChatMessageIndex(rendered, "missing"))
    }

    @Test
    fun plainTextFinishedTurnHasNoProcessHeader() {
        val rendered = items(listOf(user("u0"), assistant("a1")))
        assertTrue(rendered.none { it.turnHeader != null })
        assertTrue(rendered.any { it.key == "a1" })
    }

    @Test
    fun finalReasoningBelongsToCollapsedProcess() {
        val rendered = items(listOf(user("u0"), assistant("a1").copy(reasoning = "thinking")))
        assertTrue(rendered.header().turnProcess.any { it.key == "a1#reasoning" })
        assertFalse(rendered.first { it.key == "a1" }.reasoningVisible)
    }

    @Test
    fun halfTurnKeyIsInheritedOnlyOnceWhenTheTurnGainsItsUserMessage() {
        // 补齐轮首时沿用半轮 key 是有意的：展开选择与滚动锚点靠它稳定。
        // 但同一个 key 只能归一个轮，后续的轮必须退回自己的消息级 key。
        val halfTurn = listOf(assistant("a1"), tool("t1"))
        val previous = reconcileTurnKeys(halfTurn, emptyMap(), "s1")
        assertEquals(setOf("turn:head:session:s1"), previous.values.toSet())

        val full = listOf(user("u0")) + halfTurn
        val keys = reconcileTurnKeys(full, previous, "s1")
        assertEquals("turn:head:session:s1", keys.getValue("u0"))
        assertEquals("turn:head:session:s1", keys.getValue("a1"))
        assertEquals("turn:head:session:s1", keys.getValue("t1"))

        val withNextTurn = full + listOf(user("u1"), assistant("a2"), tool("t2"))
        val next = reconcileTurnKeys(withNextTurn, keys, "s1")
        assertEquals("turn:head:session:s1", next.getValue("u0"))
        assertEquals("turn:u1", next.getValue("u1"))
    }

    @Test
    fun twoTurnsNeverShareTheSameHeaderKey() {
        val previous = mapOf(
            "a1" to "turn:head:session:s1", "t1" to "turn:head:session:s1",
            "a2" to "turn:head:session:s1", "t2" to "turn:head:session:s1",
        )
        val messages = listOf(
            user("u0"), assistant("a1"), tool("t1"),
            user("u1"), assistant("a2"), tool("t2"),
        )
        val keys = reconcileTurnKeys(messages, previous, "s1")
        val rendered = flattenChatProcesses(buildChatItems(messages, sessionId = "s1", turnKeys = keys))
        val itemKeys = rendered.map { it.key }
        assertEquals(itemKeys.size, itemKeys.toSet().size)
        assertEquals("turn:head:session:s1", keys.getValue("u0"))
        assertEquals("turn:u1", keys.getValue("u1"))
    }

    @Test
    fun halfTurnKeyStillCarriesAcrossWindowsWithoutUserMessage() {
        // 窗口两端都还没补齐轮首时，会话级 key 必须继续沿用：展开选择与滚动锚点靠它保持稳定。
        val first = reconcileTurnKeys(listOf(assistant("a1"), tool("t1")), emptyMap(), "s1")
        val next = reconcileTurnKeys(listOf(assistant("a2"), assistant("a1"), tool("t1")), first, "s1")
        assertEquals(setOf("turn:head:session:s1"), next.values.toSet())
    }
}
