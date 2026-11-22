package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.ChatMessagesState
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.*
import org.junit.Test

class ChatItemsProjectionStateTest {
    private val messages = listOf(AgentUIMessage("id", MessageRole.ASSISTANT, "full wire and visible text"))
    private val source = ChatMessagesState(sessionId = "session", messages = messages, loaded = true)

    @Test
    fun publicationRequiresExactInputVersionSessionOwnershipAndMessageSnapshot() {
        val version = Any()
        val owner = Any()
        val request = ChatItemsBuildRequest(source, version, owner)
        assertTrue(request.canPublishTo(ChatItemsBuildRequest(source, version, owner)))
        assertFalse(request.canPublishTo(null))
        assertFalse(request.canPublishTo(ChatItemsBuildRequest(source, Any(), owner)))
        assertFalse(request.canPublishTo(ChatItemsBuildRequest(source, version, Any())))
        assertFalse(request.canPublishTo(ChatItemsBuildRequest(source.copy(sessionId = "other"), version, owner)))
        assertFalse(request.canPublishTo(ChatItemsBuildRequest(source.copy(loaded = false), version, owner)))
        assertFalse(request.canPublishTo(ChatItemsBuildRequest(source.copy(messages = messages.toMutableList()), version, owner)))
    }

    @Test
    fun sameSessionRefreshRetainsPreviousProjectionButOwnershipRevocationCannotDisplayIt() {
        val owner = Any()
        val first = ChatItemsBuildRequest(source, Any(), owner)
        val displayed = listOf("all public rows", "unlimited child rows")
        val result = ChatItemsProjection(first, displayed)
        val refreshed = ChatItemsBuildRequest(source.copy(messages = messages + messages.single().copy(id = "new")), Any(), owner)
        assertFalse(first.canPublishTo(refreshed))
        assertSame(displayed, result.retainedFor(refreshed))
        assertNull(result.retainedFor(ChatItemsBuildRequest(source, Any(), Any())))
        assertNull(result.retainedFor(ChatItemsBuildRequest(source.copy(sessionId = "new"), Any(), owner)))
        assertNull(result.retainedFor(null))
    }

    @Test
    fun childAdditionRetainsRowsButPublicationAndChildRemovalUseExactOwnership() {
        val owner = Any()
        val version = Any()
        val first = ChatItemsBuildRequest(source, version, owner, setOf("a"))
        val rows = listOf("tool", "chat-notification:notice", "child-a")
        val displayed = ChatItemsProjection(first, rows)
        val added = ChatItemsBuildRequest(source, version, owner, setOf("a", "b"))
        val replaced = ChatItemsBuildRequest(source, version, owner, setOf("b"))

        assertSame(rows, displayed.retainedFor(added))
        assertFalse(first.canPublishTo(added))
        assertTrue(added.canPublishTo(ChatItemsBuildRequest(source, version, owner, setOf("b", "a"))))
        assertNull(displayed.retainedFor(replaced))
        assertNull(displayed.retainedFor(ChatItemsBuildRequest(source, version, owner, emptySet())))
        assertNull(displayed.retainedFor(ChatItemsBuildRequest(source, version, Any(), added.knownChildIds)))
        assertNull(displayed.retainedFor(ChatItemsBuildRequest(source.copy(sessionId = "other"), version, owner, added.knownChildIds)))
    }

    @Test
    fun settledPlaybackStaysInTailUntilItsActualPublishedProjectionOwnsTheMessage() {
        val owner = Any()
        val first = ChatItemsBuildRequest(source, Any(), owner)
        val delivered = source.copy(messages = messages + messages.single().copy(id = "landed"))
        val next = ChatItemsBuildRequest(delivered, Any(), owner)
        val oldIds = source.messages.mapTo(hashSetOf()) { it.id }
        val nextIds = delivered.messages.mapTo(hashSetOf()) { it.id }
        val oldDisplay = "old metadata" to oldIds
        val retained = ChatItemsProjection(first, oldDisplay).retainedFor(next)!!
        val entry = ChatTypewriterEntry("stream", messageId = "landed", text = "complete visible text")
        assertSame(oldDisplay, retained)
        assertFalse(chatPlaybackHandedOff(entry, retained.second))
        assertFalse(entry.messageId in retained.second)
        val current = ChatItemsProjection(next, "new metadata" to nextIds).retainedFor(next)!!
        assertEquals("new metadata", current.first)
        assertTrue(chatPlaybackHandedOff(entry, current.second))
        assertFalse(chatPlaybackHandedOff(entry.copy(messageId = null), current.second))
        assertNull(ChatItemsProjection(first, oldDisplay).retainedFor(ChatItemsBuildRequest(delivered, Any(), Any())))
    }
}
