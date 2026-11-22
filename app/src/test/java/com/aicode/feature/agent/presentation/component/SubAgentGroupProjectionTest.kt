package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentGroupProjectionTest {
    private val parent = "parent"
    private val firstTurn = "turn:user-1"
    private val secondTurn = "turn:user-2"

    private fun ordinary(id: String, turn: String = firstTurn, role: MessageRole = MessageRole.ASSISTANT) = ChatRenderItem(
        message = AgentUIMessage(id, role, "body $id", timestamp = 1L),
        key = id,
        contentType = role.name,
        chatTurnKey = turn,
    )

    private fun child(
        id: String,
        turn: String = firstTurn,
        status: String? = null,
        revision: String = id,
        carriers: Set<String> = emptySet(),
    ) = ChatRenderItem(
        message = AgentUIMessage("call-$revision", MessageRole.TOOL, "original $revision", timestamp = 2L),
        key = "$turn:child:$id",
        contentType = SUB_AGENT_CAPSULE_CONTENT_TYPE,
        chatTurnKey = turn,
        subAgentCapsule = SubAgentTimelineCapsule(id, "call-$revision", "task $revision", status),
        subAgentCarrierMessageIds = carriers,
    )

    private fun lazyItems(projection: SubAgentGroupProjection): List<ChatRenderItem?> =
        projection.bodyItems + listOf<ChatRenderItem?>(null) + projection.footerItems

    @Test
    fun noChildrenPreservesOriginalInputAndDoesNotCreateEmptyGroups() {
        listOf(emptyList(), listOf(ordinary("first"), ordinary("last"))).forEach { raw ->
            val projection = projectSubAgentGroup(raw, parent, activeTurnKey = firstTurn)
            assertSame(raw, projection.bodyItems)
            assertSame(raw, projection.items)
            assertTrue(projection.footerItems.isEmpty())
            assertTrue(projection.groups.isEmpty())
            assertTrue(projection.representedChildIds.isEmpty())
            assertEquals(raw.size + 1, projection.expectedLazyItemCount)
        }
    }

    @Test
    fun eachRealTurnHasAnIndependentGroupClosedByDefaultAndNoFiveCardLimit() {
        val oldChildren = (1..7).map { child("old-$it", status = "running") }
        val currentChildren = (1..12).map { child("new-$it", secondTurn, "queued") }
        val raw = listOf(ordinary("user-1", role = MessageRole.USER)) + oldChildren + ordinary("answer-1") +
            ordinary("user-2", secondTurn, MessageRole.USER) + currentChildren + ordinary("answer-2", secondTurn)
        val projection = projectSubAgentGroup(raw, parent, activeTurnKey = secondTurn)
        assertEquals(2, projection.groups.size)
        assertEquals(listOf(7, 12), projection.groups.map { it.capsules.size })
        assertEquals(19, projection.representedChildIds.size)
        assertEquals(19, projection.capsules.size)
        assertTrue(projection.items.none { it.subAgentCapsule != null })
        assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
        val firstGroup = projection.groups[0]
        val secondGroup = projection.groups[1]
        assertTrue(projection.bodyItems.indexOfFirst { it.key == firstGroup.key } > projection.bodyItems.indexOfFirst { it.key == "answer-1" })
        assertTrue(projection.bodyItems.indexOfFirst { it.key == firstGroup.key } < projection.bodyItems.indexOfFirst { it.key == "user-2" })
        assertEquals(secondGroup.key, projection.footerItems.single().key)
        val lazySequence = lazyItems(projection)
        assertEquals(null, lazySequence[projection.bodyItems.size])
        assertEquals(secondGroup.key, lazySequence[projection.bodyItems.size + 1]?.key)
        assertEquals(7, summarizeSubAgentGroup(firstGroup.capsules).workingCount)
        assertEquals(12, summarizeSubAgentGroup(secondGroup.capsules).workingCount)
    }

    @Test
    fun expandedGroupRemainsOneOriginalListItemBesideItsSiblings() {
        val before = ordinary("before")
        val chunk = ordinary("chunk").copy(contentType = "assistant-chunk", slice = "unchanged", isChunkHeader = false)
        val after = ordinary("after")
        val children = (1..8).map { child("child-$it") }
        val projection = projectSubAgentGroup(listOf(before) + children + chunk + after, parent, activeTurnKey = firstTurn)
        assertEquals(listOf(before, chunk, after), projection.bodyItems)
        assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
        assertEquals(8, projection.groups.single().capsules.size)
        assertEquals(projection.items.size, projection.items.map { it.key }.toSet().size)
        assertEquals(projection.items.size + 1, projection.expectedLazyItemCount)
        assertSame(after, projection.bodyItems.last())
        assertSame(children.last().message, projection.footerItems.first().message)
        assertTrue(projection.items.none { it.subAgentCapsule != null })
    }

    @Test
    fun oldWorkingChildRemainsInOldTurnAndOnlyCurrentTurnIsASeparateFooter() {
        val old = child("old", status = "running")
        val current = child("current", secondTurn, "completed")
        val nextUser = ordinary("user-2", secondTurn, MessageRole.USER)
        val raw = listOf(ordinary("user-1", role = MessageRole.USER), old, ordinary("old-result"), nextUser, current, ordinary("current-result", secondTurn))
        val projection = projectSubAgentGroup(raw, parent, activeTurnKey = secondTurn)
        assertEquals(listOf("old"), projection.groups[0].capsules.map { it.subSessionId })
        assertTrue(projection.bodyItems.none { it.subAgentCapsule != null })
        assertTrue(projection.bodyItems.indexOfFirst { it.key == projection.groups[0].key } < projection.bodyItems.indexOf(nextUser))
        assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
        assertEquals(listOf("current"), projection.groups[1].capsules.map { it.subSessionId })
        val closed = projectSubAgentGroup(raw, parent)
        assertTrue(closed.footerItems.isEmpty())
        assertEquals(projection.groups.map { it.key }, closed.groups.map { it.key })
        val following = projectSubAgentGroup(raw + ordinary("user-3", "turn:user-3", MessageRole.USER), parent, activeTurnKey = "turn:user-3")
        assertTrue(following.footerItems.isEmpty())
        assertEquals(2, following.groups.size)
    }

    @Test
    fun sameChildAcrossTurnsHasIndependentExpansionAndParentTurnScopedStableKeys() {
        val first = child("same", revision = "first")
        val second = child("same", secondTurn, revision = "second")
        val raw = listOf(first, second)
        val initial = projectSubAgentGroup(raw, parent, activeTurnKey = secondTurn)
        val firstGroup = initial.groups[0].key
        val secondGroup = initial.groups[1].key
        assertNotEquals(firstGroup, secondGroup)
        assertNotEquals(first.key, second.key)
        assertEquals(setOf("same"), initial.representedChildIds)
        val opened = projectSubAgentGroup(raw, parent, activeTurnKey = secondTurn)
        assertEquals(initial.items.map { it.key }, opened.items.map { it.key })
        assertTrue(opened.items.none { it.subAgentCapsule != null })
        assertEquals(initial.groups.map { it.key }, opened.groups.map { it.key })
        val otherParent = projectSubAgentGroup(raw, "another-parent")
        assertTrue(otherParent.groups.none { it.key in initial.groups.map(SubAgentTurnGroup::key) })
        val refreshed = projectSubAgentGroup(listOf(child("older"), first.copy(subAgentCapsule = first.subAgentCapsule!!.copy(title = "new preview")), second), parent)
        assertEquals(initial.groups.map { it.key }, refreshed.groups.map { it.key })
    }

    @Test
    fun latestSameTurnChildVersionWinsButAllCarriersRemainNavigable() {
        val older = child("a", revision = "old", status = "running", carriers = setOf("notice-old"))
        val other = child("b", status = "running")
        val latest = child("a", revision = "new", status = "running", carriers = setOf("notice-new"))
        val projection = projectSubAgentGroup(listOf(older, other, latest), parent, activeTurnKey = firstTurn)
        assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
        assertEquals(setOf("call-old", "call-b", "call-new", "notice-old", "notice-new"), projection.carrierMessageIds)
        assertEquals(2, projection.capsules.size)
        assertEquals(1, findSubAgentGroupMessageIndex(projection, "call-old"))
        assertEquals(1, findSubAgentGroupMessageIndex(projection, "call-new"))
        assertSame(projection.footerItems.single(), lazyItems(projection)[1])
        assertTrue(projection.items.none { it.subAgentCapsule != null })
        assertFalse(projection.items.any { it === older })
    }

    @Test
    fun statusPartitionIsCompletedThenExceptionalThenWorkingAndStableWithinRank() {
        val children = listOf(
            child("running", status = "running"), child("failed", status = "failed"), child("done-a", status = "completed"),
            child("queued", status = "queued"), child("stopped", status = "interrupted"), child("done-b", status = "completed"),
            child("deleted", status = "deleted"), child("history", status = "unknown"), child("waiting", status = "awaiting_confirmation"),
        )
        val projection = projectSubAgentGroup(children, parent, activeTurnKey = firstTurn)
        assertEquals(listOf("done-a", "done-b", "failed", "stopped", "deleted", "history", "running", "queued", "waiting"),
            projection.capsules.map { it.subSessionId })
        assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
        assertTrue(projection.items.none { it.subAgentCapsule != null })
        assertEquals(SubAgentGroupSummary(workingCount = 3, completedCount = 2, awaitingConfirmationCount = 1,
            failedCount = 1, interruptedCount = 1, deletedCount = 1, historyCount = 1), summarizeSubAgentGroup(projection.capsules))
    }

    @Test
    fun previewContentTokenAndTimerChangesDoNotChangeRanksOrRelativeOrder() {
        val raw = listOf(child("a"), child("b"), child("c"))
        val summaries = mapOf(
            "a" to SubAgentInteractionSummary("a", status = SubAgentInteractionStatus.RUNNING),
            "b" to SubAgentInteractionSummary("b", status = SubAgentInteractionStatus.COMPLETED),
            "c" to SubAgentInteractionSummary("c", status = SubAgentInteractionStatus.FAILED),
        )
        val ranks = subAgentGroupStatusRanks(raw, summaries)
        assertEquals(listOf(2, 0, 1), raw.map { ranks[it.key] })
        val projection = projectSubAgentGroup(raw, parent, statusRanks = ranks)
        assertEquals(listOf("b", "c", "a"), projection.capsules.map { it.subSessionId })
        val updated = summaries.mapValues { (_, value) -> value.copy(latestPreview = "more text", startedAtMs = 10L, finishedAtMs = 900L) }
        val updatedRaw = raw.map { it.copy(message = it.message.copy(content = "longer content", toolDurationMs = 800L, inputTokens = 400)) }
        assertEquals(ranks, subAgentGroupStatusRanks(updatedRaw, updated))
        val after = projectSubAgentGroup(updatedRaw, parent, statusRanks = ranks)
        assertEquals(projection.items.map { it.key }, after.items.map { it.key })
        val statusChanged = updated + ("a" to updated.getValue("a").copy(status = SubAgentInteractionStatus.COMPLETED))
        val final = projectSubAgentGroup(updatedRaw, parent, statusRanks = subAgentGroupStatusRanks(updatedRaw, statusChanged))
        assertEquals(listOf("a", "b", "c"), final.capsules.map { it.subSessionId })
        assertEquals(projection.groups.map { it.key }, final.groups.map { it.key })
    }

    @Test
    fun navigationUsesRealItemsThenOwnCarrierGroupAndAccountsForExactlyOneActiveSlot() {
        val first = child("a", carriers = setOf("notice-a"))
        val second = child("b", secondTurn, carriers = setOf("notice-b"))
        val user = ordinary("user-2", secondTurn, MessageRole.USER)
        val result = ordinary("result-2", secondTurn)
        val raw = listOf(ordinary("result-1"), first, user, second, result)
        run {
            val projection = projectSubAgentGroup(raw, parent, activeTurnKey = secondTurn)
            val actual = lazyItems(projection)
            listOf("notice-a", "notice-b").forEach { id ->
                val item = actual[findSubAgentGroupMessageIndex(projection, id)]!!
                assertEquals(SUB_AGENT_GROUP_CONTENT_TYPE, item.contentType)
                assertEquals(if (id == "notice-a") firstTurn else secondTurn, item.chatTurnKey)
            }
            assertSame(user, actual[findSubAgentGroupMessageIndex(projection, user.message.id)])
            assertSame(result, actual[findSubAgentGroupMessageIndex(projection, result.message.id)])
            listOf(first, second).forEach { child ->
                val item = actual[findSubAgentGroupMessageIndex(projection, child.message.id)]!!
                assertEquals(SUB_AGENT_GROUP_CONTENT_TYPE, item.contentType)
            }
            assertEquals(-1, findSubAgentGroupMessageIndex(projection, "missing"))
            assertEquals(projection.expectedLazyItemCount, actual.size)
        }
    }

    @Test
    fun unscopedActiveChildCannotClaimFallbackOrCreateAnUnverifiedTailGroup() {
        val orphan = child("active").copy(chatTurnKey = null)
        val ordinary = ordinary("raw-dispatch")
        val projection = projectSubAgentGroup(listOf(ordinary, orphan), parent, activeTurnKey = secondTurn)
        assertEquals(listOf(ordinary), projection.bodyItems)
        assertTrue(projection.groups.isEmpty())
        assertTrue(projection.representedChildIds.isEmpty())
        assertTrue(projection.carrierMessageIds.isEmpty())
        assertTrue(projection.footerItems.isEmpty())
    }

    @Test
    fun summaryCountsUseOnlyRepresentedOwnedIdentityAndAwaitingIsPartOfWorking() {
        val capsules = SubAgentInteractionStatus.entries.map { status -> SubAgentTimelineCapsule(status.name, null, fallbackStatus = status.name) }
        val counts = summarizeSubAgentGroup(capsules)
        assertEquals(8, counts.totalCount)
        assertEquals(3, counts.workingCount)
        assertEquals(1, counts.awaitingConfirmationCount)
        assertEquals(1, counts.completedCount)
        assertEquals(1, counts.failedCount)
        assertEquals(1, counts.interruptedCount)
        assertEquals(1, counts.deletedCount)
        assertEquals(1, counts.historyCount)
        val foreign = mapOf("owned" to SubAgentInteractionSummary("foreign", status = SubAgentInteractionStatus.COMPLETED))
        val legacy = mapOf("owned" to SubAgentCardState("foreign", status = SubAgentCardStatus.COMPLETED))
        val owned = SubAgentTimelineCapsule("owned", null, fallbackStatus = "running")
        assertEquals(SubAgentGroupSummary(workingCount = 1), summarizeSubAgentGroup(listOf(owned), foreign, legacy))
        assertEquals(SubAgentGroupSummary(), summarizeSubAgentGroup(emptyList(), foreign, legacy))
        val duplicates = listOf(owned, owned.copy(fallbackStatus = "completed"))
        assertEquals(SubAgentGroupSummary(completedCount = 1), summarizeSubAgentGroup(duplicates))
    }
}
