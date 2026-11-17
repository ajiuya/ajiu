package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentChatIntegrationTest {
    private val parent = "parent"

    private fun user(id: String) = AgentUIMessage(id, MessageRole.USER, "request $id", timestamp = 1L)
    private fun answer(id: String) = AgentUIMessage(id, MessageRole.ASSISTANT, "answer $id", timestamp = 3L)
    private fun dispatch(id: String, child: String, send: Boolean = false) = AgentUIMessage(
        id, MessageRole.TOOL,
        """{"status":"success","data":{"id":"$child","state":"running"}}""",
        timestamp = 2L,
        toolName = "task",
        toolArgs = if (send) """{"action":"send","id":"$child","message":"continue"}"""
            else """{"prompt":"work","description":"task $child"}""",
    )

    private fun projection(
        messages: List<AgentUIMessage>,
        children: Set<String>,
        activeTurnKey: String? = null,
        expanded: Boolean = false,
        statuses: Map<String, SubAgentInteractionSummary> = emptyMap(),
        previousTurnKeys: Map<String, String> = emptyMap(),
    ): SubAgentGroupProjection {
        val keys = reconcileTurnKeys(messages, previousTurnKeys, parent)
        val items = flattenChatProcesses(buildChatItems(messages, activeTurnKey = activeTurnKey, sessionId = parent, turnKeys = keys))
        val timeline = projectSubAgentTimeline(messages, children, parent)
        val cards = projectSubAgentTurnCapsules(timeline, keys, parent)
        val inserted = insertSubAgentTurnItems(items, cards, messages)
        return projectSubAgentGroup(inserted, parent, expanded, activeTurnKey,
            statusRanks = subAgentGroupStatusRanks(inserted, statuses))
    }

    @Test
    fun runningTurnKeepsIndependentGroupEvenWhenPersistentProcessBranchIsSkipped() {
        val messages = listOf(user("u"), dispatch("d", "child"), answer("a"))
        val result = projection(messages, setOf("child"), turnKeyOf("u"))
        assertEquals(1, result.groups.size)
        assertEquals("child", result.groups.single().capsules.single().subSessionId)
        assertEquals(SUB_AGENT_GROUP_CONTENT_TYPE, result.footerItems.single().contentType)
        assertTrue(result.bodyItems.none { it.contentType == SUB_AGENT_GROUP_CONTENT_TYPE })
        assertFalse(result.items.any { it.subAgentCapsule != null })
        assertEquals(result.items.size + 1, result.expectedLazyItemCount)
        val opened = projection(messages, setOf("child"), turnKeyOf("u"), expanded = true)
        assertEquals("child", opened.footerItems.last().subAgentCapsule?.subSessionId)
        assertTrue(findSubAgentGroupMessageIndex(opened, "d") in 0 until opened.expectedLazyItemCount)
    }

    @Test
    fun completedGroupsStayBeforeNextUserEvenWhenWholeProcessIsCollapsed() {
        val messages = listOf(user("u1"), dispatch("d1", "old"), answer("a1"), user("u2"), dispatch("d2", "new"), answer("a2"))
        val result = projection(messages, setOf("old", "new"), turnKeyOf("u2"))
        val group = result.groups.first()
        val groupIndex = result.bodyItems.indexOfFirst { it.key == group.key }
        assertTrue(groupIndex > result.bodyItems.indexOfLast { it.message.id == "a1" })
        assertTrue(groupIndex < result.bodyItems.indexOfFirst { it.message.id == "u2" })
        assertEquals(listOf("old"), group.capsules.map { it.subSessionId })
        assertEquals(listOf("new"), result.groups.last().capsules.map { it.subSessionId })
        val stopped = projection(messages, setOf("old", "new"))
        assertTrue(stopped.footerItems.isEmpty())
        assertEquals(result.groups.map { it.key }, stopped.groups.map { it.key })
    }

    @Test
    fun actualNumberIsUnlimitedAndCompletedChildrenPrecedeWorkingChildren() {
        val messages = listOf(user("u")) + (1..8).map { dispatch("d$it", "c$it") } + answer("a")
        val summaries = (1..8).associate { id -> "c$id" to SubAgentInteractionSummary("c$id",
            status = if (id % 2 == 0) SubAgentInteractionStatus.COMPLETED else SubAgentInteractionStatus.RUNNING) }
        val result = projection(messages, summaries.keys, expanded = true, statuses = summaries)
        assertEquals(8, result.groups.single().childItems.size)
        assertEquals(listOf("c2", "c4", "c6", "c8", "c1", "c3", "c5", "c7"),
            result.groups.single().capsules.map { it.subSessionId })
        assertEquals(8, result.items.count { it.subAgentCapsule != null })
    }

    @Test
    fun repeatedChildDeduplicatesWithinTurnButRealLaterSendGetsItsOwnStableCard() {
        val messages = listOf(user("u1"), dispatch("d1", "same"), dispatch("d2", "same", true), answer("a1"),
            user("u2"), dispatch("d3", "same", true), answer("a2"))
        val result = projection(messages, setOf("same"), expanded = true)
        assertEquals(2, result.groups.size)
        val cards = result.items.filter { it.subAgentCapsule != null }
        assertEquals(listOf("d2", "d3"), cards.map { it.subAgentCapsule?.anchorMessageId })
        assertNotEquals(cards.first().key, cards.last().key)
        assertEquals(setOf("d1", "d2"), cards.first().subAgentCarrierMessageIds)
        assertEquals(setOf("same"), result.representedChildIds)
    }

    @Test
    fun notificationOnlyNextTurnDoesNotMoveOldWorkingChildOrCreateAnotherGroup() {
        val notice = AgentUIMessage("n", MessageRole.USER,
            BACKGROUND_NOTIFICATION_PREFIX + "<subagent-notification><subagent-id>old</subagent-id><status>completed</status></subagent-notification>",
            timestamp = 5L, isBackgroundNotification = true)
        val messages = listOf(user("u1"), dispatch("d1", "old"), answer("a1"), user("u2"), notice, answer("a2"))
        val result = projection(messages, setOf("old"), turnKeyOf("u2"), expanded = true)
        assertEquals(1, result.groups.size)
        assertEquals(turnKeyOf("u1"), result.groups.single().turnKey)
        assertTrue(result.footerItems.isEmpty())
        assertTrue("n" in result.carrierMessageIds)
        assertEquals(setOf(turnKeyOf("u1"), turnKeyOf("u2")), reconcileTurnKeys(messages, emptyMap(), parent).values.toSet())
    }

    @Test
    fun prependingUserToLeadingPagePreservesGroupAndChildIdentity() {
        val tail = listOf(dispatch("d", "child"), answer("a"))
        val oldKeys = reconcileTurnKeys(tail, emptyMap(), parent)
        val old = projection(tail, setOf("child"), expanded = true, previousTurnKeys = oldKeys)
        val full = projection(listOf(user("u")) + tail, setOf("child"), expanded = true, previousTurnKeys = oldKeys)
        assertEquals(old.groups.single().key, full.groups.single().key)
        assertEquals(old.groups.single().childItems.single().key, full.groups.single().childItems.single().key)
    }

    @Test
    fun foreignChildAndNoticeWithoutLoadedRealCallCannotClaimOrInventCards() {
        val messages = listOf(user("u"), dispatch("d", "foreign"), answer("a"))
        val result = projection(messages, setOf("owned"), turnKeyOf("u"))
        assertTrue(result.groups.isEmpty())
        assertTrue(result.representedChildIds.isEmpty())
        assertTrue(result.bodyItems.any { it.message.id == "d" })
        assertTrue(insertSubAgentTurnItems(emptyList(), emptyList(), messages).isEmpty())
    }
}
