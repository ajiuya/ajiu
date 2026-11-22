package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCapsuleEntranceTest {
    private val sessionId = "parent-session"
    private val turnKey = "turn:user-1"

    private fun item(id: String, role: MessageRole = MessageRole.USER) = ChatRenderItem(
        message = AgentUIMessage(id, role, "body $id", timestamp = 1L),
        key = id,
        contentType = role.name,
        chatTurnKey = turnKey,
    )

    private fun child(id: String, carrierId: String) = ChatRenderItem(
        message = AgentUIMessage(carrierId, MessageRole.TOOL, "task $id", timestamp = 2L),
        key = "$turnKey:child:$id",
        contentType = SUB_AGENT_CAPSULE_CONTENT_TYPE,
        chatTurnKey = turnKey,
        subAgentCapsule = SubAgentTimelineCapsule(id, carrierId, "task $id", "running"),
        subAgentCarrierMessageIds = setOf(carrierId),
    )

    @Test
    fun initialSessionLoadRegistersExistingCapsulesWithoutAnimatingThem() {
        val projection = projectSubAgentGroup(
            listOf(item("user-1"), item("old-tool", MessageRole.TOOL)),
            sessionId,
            activeTurnKey = turnKey,
        )
        val tracker = ChatCapsuleEntranceTracker()

        assertTrue(tracker.update(sessionId, ready = false, allowAnimation = true, messageIds = listOf("user-1"), projection = projection).isEmpty())
        assertTrue(
            tracker.update(
                sessionId, ready = true, allowAnimation = true,
                messageIds = listOf("user-1", "old-tool"), projection = projection,
            ).isEmpty()
        )
    }

    @Test
    fun onlyNewToolMessagesAppendedWhileFollowingAreAnimatedOnce() {
        val tracker = ChatCapsuleEntranceTracker()
        val initial = listOf(item("user-1"))
        val initialProjection = projectSubAgentGroup(initial, sessionId, activeTurnKey = turnKey)
        tracker.update(sessionId, true, true, listOf("user-1"), initialProjection)

        val updated = initial + item("new-tool", MessageRole.TOOL)
        val updatedProjection = projectSubAgentGroup(updated, sessionId, activeTurnKey = turnKey)
        assertEquals(setOf("new-tool"), tracker.update(sessionId, true, true, listOf("user-1", "new-tool"), updatedProjection))
        assertTrue(tracker.update(sessionId, true, true, listOf("user-1", "new-tool"), updatedProjection).isEmpty())
    }

    @Test
    fun historyPageAndNonFollowingAppendDoNotAnimate() {
        val tracker = ChatCapsuleEntranceTracker()
        val initial = listOf(item("user-1"), item("old-tool", MessageRole.TOOL))
        tracker.update(
            sessionId, true, true, listOf("user-1", "old-tool"),
            projectSubAgentGroup(initial, sessionId, activeTurnKey = turnKey),
        )

        val history = listOf(item("older-tool", MessageRole.TOOL), item("user-1"), item("old-tool", MessageRole.TOOL))
        assertTrue(
            tracker.update(
                sessionId, true, true, listOf("older-tool", "user-1", "old-tool"),
                projectSubAgentGroup(history, sessionId, activeTurnKey = turnKey),
            ).isEmpty()
        )

        val appended = history + item("new-tool", MessageRole.TOOL)
        assertTrue(
            tracker.update(
                sessionId, true, false, listOf("older-tool", "user-1", "old-tool", "new-tool"),
                projectSubAgentGroup(appended, sessionId, activeTurnKey = turnKey),
            ).isEmpty()
        )
    }

    @Test
    fun childGroupUsesStableKeyAndDoesNotReplayWhenItMovesFromFooterToBody() {
        val tracker = ChatCapsuleEntranceTracker()
        val initial = listOf(item("user-1"))
        tracker.update(
            sessionId, true, true, listOf("user-1"),
            projectSubAgentGroup(initial, sessionId, activeTurnKey = turnKey),
        )

        val carrierId = "task-call-1"
        val withChild = initial + child("child-1", carrierId)
        val footer = projectSubAgentGroup(withChild, sessionId, activeTurnKey = turnKey)
        val groupKey = footer.footerItems.single().key
        assertEquals(setOf(groupKey), tracker.update(sessionId, true, true, listOf("user-1", carrierId), footer))

        val body = projectSubAgentGroup(withChild, sessionId, activeTurnKey = null)
        assertEquals(groupKey, body.bodyItems.last().key)
        assertTrue(tracker.update(sessionId, true, true, listOf("user-1", carrierId), body).isEmpty())
    }

    @Test
    fun videoCurveClampsEndpointsAndKeepsOvershootSubtle() {
        assertEquals(0f, capsuleEntranceEase(-1f), 0f)
        assertEquals(1f, capsuleEntranceEase(1f), 0f)
        assertEquals(0f, capsuleEntranceBack(0f), 0.000001f)
        assertEquals(1f, capsuleEntranceBack(1f), 0.000001f)
        assertTrue(capsuleEntranceBack(0.6f) > 1f)
        assertTrue(capsuleEntranceBack(0.6f) < 1.1f)
        assertEquals(420, CAPSULE_ENTRANCE_DURATION_MS)
    }
}
