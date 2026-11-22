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
    fun historyPageIsNotCommittedUntilTheAnchorLayoutMatches() {
        val owner = Any()
        val first = ChatItemsBuildRequest(source.copy(limit = 30), Any(), owner)
        val next = ChatItemsBuildRequest(source.copy(limit = 60), Any(), owner)
        val oldPage = ChatItemsProjection(first, "old")
        val newPage = ChatItemsProjection(next, "new")
        val state = ChatHistoryPageState<String>()
        val anchorRow = AgentUIMessage("visible-row", MessageRole.USER, "visible-row")
        val oldRows = SubAgentGroupProjection(
            listOf(ChatRenderItem(anchorRow, "visible-row", "user")), emptyList(), emptyList(), emptySet(), emptySet(),
        )
        val newRows = SubAgentGroupProjection(
            (0 until 162).map { index ->
                val message = AgentUIMessage("older-$index", MessageRole.USER, "older-$index")
                ChatRenderItem(message, message.id, "user")
            } + oldRows.bodyItems,
            emptyList(), emptyList(), emptySet(), emptySet(),
        )

        state.offer(oldPage)
        state.reportLayout(first, itemCount = 2, expectedItemCount = 2,
            firstVisible = ChatHistoryAnchor("visible-row", 0, 11), rows = oldRows)
        assertTrue(state.isLayoutCommitted(first, 2))

        state.offer(newPage)
        state.requireAnchor("visible-row", index = 162)
        state.offer(ChatItemsProjection(next, "same-request-frame"))
        assertFalse(chatHistoryLayoutReadyForPaging(state, next, itemCount = 164, emptyProjection = false))
        assertTrue(chatHistoryLayoutReadyForPaging(state, next, itemCount = 164, emptyProjection = true))
        state.reportLayout(next, itemCount = 164, expectedItemCount = 164,
            firstVisible = ChatHistoryAnchor("older-0", 0, 11), rows = newRows)
        assertFalse(state.isLayoutCommitted(next, 164))
        state.reportLayout(next, itemCount = 164, expectedItemCount = 164,
            firstVisible = ChatHistoryAnchor("visible-row", 2, 11), rows = newRows)
        assertFalse(state.isLayoutCommitted(next, 164))

        state.reportLayout(next, itemCount = 164, expectedItemCount = 164,
            firstVisible = ChatHistoryAnchor("visible-row", 162, 11), rows = newRows)
        assertTrue(state.isLayoutCommitted(next, 164))
        assertTrue(chatHistoryLayoutReadyForPaging(state, next, itemCount = 164, emptyProjection = false))
        assertFalse(state.isLayoutCommitted(next, 163))

        val searchState = ChatHistoryPageState<String>()
        searchState.offer(oldPage)
        searchState.reportLayout(first, 2, 2, ChatHistoryAnchor("visible-row", 0, 11), oldRows)
        searchState.offer(newPage)
        searchState.requireAnchor("visible-row", 162)
        searchState.clearRequiredAnchor()
        searchState.reportLayout(next, 164, 164, ChatHistoryAnchor("older-0", 0, 11), newRows)
        assertTrue(searchState.isLayoutCommitted(next, 164))
    }

    @Test
    fun historyAnchorIsOnlyRequiredOutsideComposeNearestKeyWindow() {
        assertFalse(requiresChatHistoryKeyAnchor(2, 129))
        assertTrue(requiresChatHistoryKeyAnchor(2, 130))
        assertFalse(requiresChatHistoryKeyAnchor(29, 129))
        assertTrue(requiresChatHistoryKeyAnchor(29, 130))
        assertFalse(requiresChatHistoryKeyAnchor(30, 159))
        assertTrue(requiresChatHistoryKeyAnchor(30, 160))
        assertFalse(requiresChatHistoryKeyAnchor(20, 19))
    }

    @Test
    fun emptyFilteredProjectionCanContinuePagingWithoutAnIndexNearTheTop() {
        val state = source.copy(hasMore = true, limit = 60)
        assertFalse(shouldLoadMoreChatHistory(state, firstVisibleIndex = 5, emptyProjection = false))
        assertTrue(shouldLoadMoreChatHistory(state, firstVisibleIndex = 5, emptyProjection = true))
        assertFalse(shouldLoadMoreChatHistory(state.copy(isLoadingMore = true), 0, emptyProjection = true))
        assertFalse(shouldLoadMoreChatHistory(state.copy(hasMore = false), 0, emptyProjection = true))
        assertFalse(shouldLoadMoreChatHistory(null, 0, emptyProjection = true))
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
