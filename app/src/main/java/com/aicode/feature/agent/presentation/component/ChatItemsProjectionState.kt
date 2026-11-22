package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.aicode.feature.agent.presentation.ChatMessagesState
import com.aicode.feature.agent.presentation.preview.UiWorkEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ChatItemsBuildRequest(
    val source: ChatMessagesState,
    val inputVersion: Any,
    val ownership: Any,
    val knownChildIds: Set<String> = emptySet(),
) {
    private fun sameOwner(current: ChatItemsBuildRequest): Boolean = source.loaded && current.source.loaded &&
        source.sessionId == current.source.sessionId && ownership === current.ownership

    fun canRetainFor(current: ChatItemsBuildRequest?): Boolean = current != null && sameOwner(current) &&
        current.knownChildIds.containsAll(knownChildIds)

    fun canPublishTo(current: ChatItemsBuildRequest?): Boolean = current != null && sameOwner(current) &&
        knownChildIds == current.knownChildIds && inputVersion === current.inputVersion &&
        source.messages === current.source.messages

    fun expandsHistoryFrom(previous: ChatItemsBuildRequest): Boolean =
        source.limit > previous.source.limit && previous.canRetainFor(this)
}

internal class ChatItemsProjection<T>(val request: ChatItemsBuildRequest, val value: T) {
    fun retainedFor(current: ChatItemsBuildRequest?): T? = value.takeIf { request.canRetainFor(current) }
}

@Composable
internal fun <T> rememberChatProjection(
    request: ChatItemsBuildRequest?,
    buildReady: Boolean = true,
    calculate: suspend () -> T,
): ChatItemsProjection<T>? {
    var result by remember { mutableStateOf<ChatItemsProjection<T>?>(null) }
    val currentRequest by rememberUpdatedState(request)
    UiWorkEffect(request, buildReady) {
        if (request == null || !buildReady) return@UiWorkEffect
        val built = withContext(Dispatchers.Default) { calculate() }
        currentCoroutineContext().ensureActive()
        if (request.canPublishTo(currentRequest)) result = ChatItemsProjection(request, built)
    }
    val previous = result
    val retained = previous?.takeIf { it.request.canRetainFor(request) }
    if (previous != null && retained == null) {
        SideEffect { if (result === previous) result = null }
    }
    return retained
}

// Compose Foundation 1.10.2 builds the key map from a 30-item sliding window plus 100 extra items.
private const val CHAT_HISTORY_KEY_SLIDING_WINDOW = 30
private const val CHAT_HISTORY_KEY_EXTRA_ITEMS = 100

internal fun requiresChatHistoryKeyAnchor(oldIndex: Int, newIndex: Int): Boolean {
    val nearestRangeEnd = oldIndex / CHAT_HISTORY_KEY_SLIDING_WINDOW * CHAT_HISTORY_KEY_SLIDING_WINDOW +
        CHAT_HISTORY_KEY_SLIDING_WINDOW + CHAT_HISTORY_KEY_EXTRA_ITEMS
    return newIndex >= nearestRangeEnd
}

internal fun shouldLoadMoreChatHistory(
    messages: ChatMessagesState?,
    firstVisibleIndex: Int,
    emptyProjection: Boolean,
): Boolean = messages != null && messages.hasMore && !messages.isLoadingMore &&
    (emptyProjection || firstVisibleIndex <= 3)

internal fun <T> chatHistoryLayoutReadyForPaging(
    page: ChatHistoryPageState<T>,
    request: ChatItemsBuildRequest?,
    itemCount: Int,
    emptyProjection: Boolean,
): Boolean = emptyProjection || page.isLayoutCommitted(request, itemCount)

internal data class ChatHistoryAnchor(val key: Any, val index: Int, val offset: Int)

internal data class ChatHistoryPagePresentation<T>(
    val visible: ChatItemsProjection<T>?,
    val state: ChatHistoryPageState<T>,
)

internal class ChatHistoryPageState<T> {
    var committed: ChatItemsProjection<T>? by mutableStateOf(null)
        private set
    var deferredRequest: ChatItemsBuildRequest? by mutableStateOf(null)
        private set
    var committedItemCount by mutableStateOf(0)
        private set
    var committedRows by mutableStateOf<SubAgentGroupProjection?>(null)
        private set
    var anchor: ChatHistoryAnchor? = null
        private set
    private var offered: ChatItemsProjection<T>? = null
    private var requiredAnchor by mutableStateOf<Pair<Any, Int>?>(null)
    private var requestedAnchorFor by mutableStateOf<ChatItemsBuildRequest?>(null)
    val anchorConfirmation: Pair<Any, Int>? get() = requiredAnchor

    fun recordAnchor(value: ChatHistoryAnchor) {
        anchor = value
    }

    fun requireAnchor(key: Any, index: Int) {
        requiredAnchor = key to index
    }

    fun clearRequiredAnchor() {
        requiredAnchor = null
        requestedAnchorFor = null
        deferredRequest = null
    }

    fun reportLayout(
        request: ChatItemsBuildRequest?,
        itemCount: Int,
        expectedItemCount: Int,
        firstVisible: ChatHistoryAnchor?,
        rows: SubAgentGroupProjection,
    ) {
        if (itemCount != expectedItemCount || itemCount > 0 && firstVisible == null) return
        val page = offered ?: return
        if (page.request !== request) return
        if (firstVisible != null && rows.lazyKeyAt(firstVisible.index) != firstVisible.key) return
        val target = requiredAnchor.takeIf { committed?.request !== request }
        if (target != null && (firstVisible?.key != target.first || firstVisible.index != target.second)) return
        committed = page
        committedItemCount = itemCount
        committedRows = rows
        requiredAnchor = null
        requestedAnchorFor = null
    }

    fun hasCommittedLayout(request: ChatItemsBuildRequest?, itemCount: Int): Boolean =
        request != null && committed?.request === request && committedItemCount == itemCount

    fun isLayoutCommitted(request: ChatItemsBuildRequest?, itemCount: Int): Boolean =
        hasCommittedLayout(request, itemCount) && requiredAnchor == null

    fun defer(request: ChatItemsBuildRequest?) {
        deferredRequest = request
    }

    fun hasRequestedAnchor(request: ChatItemsBuildRequest?): Boolean = request != null && requestedAnchorFor === request

    fun deferLargeHistoryPage(
        state: LazyListState,
        request: ChatItemsBuildRequest?,
        rows: SubAgentGroupProjection?,
        keepPosition: Boolean,
        cancelPendingScroll: Boolean = false,
    ): Boolean {
        val previous = committed ?: return false
        val oldRows = committedRows ?: return false
        val currentLayout = state.layoutInfo
        if (currentLayout.totalItemsCount != committedItemCount) return false
        val savedAnchor = anchor
        val first = savedAnchor?.let { saved ->
            currentLayout.visibleItemsInfo.firstOrNull { it.index == saved.index && it.key == saved.key }
        } ?: currentLayout.visibleItemsInfo.firstOrNull() ?: return false
        val currentIndex = first.index
        if (oldRows.lazyKeyAt(currentIndex) != first.key) return false
        val currentOffset = anchor?.takeIf { it.key == first.key && it.index == currentIndex }?.offset
            ?: state.firstVisibleItemScrollOffset
        val oldIndex = oldRows.lazyIndexOfKey(first.key)
        val newIndex = rows?.lazyIndexOfKey(first.key) ?: return false
        if (!keepPosition || oldIndex != currentIndex || !requiresChatHistoryKeyAnchor(oldIndex, newIndex) ||
            request?.expandsHistoryFrom(previous.request) != true
        ) return false
        val anchorWasRequested = hasRequestedAnchor(request)
        if (anchorWasRequested && state.isScrollInProgress && !cancelPendingScroll) return false
        deferredRequest = request
        anchor = ChatHistoryAnchor(first.key, currentIndex, currentOffset)
        if (anchorWasRequested) {
            requestedAnchorFor = null
            requiredAnchor = null
            state.requestScrollToItem(currentIndex, currentOffset)
        }
        return true
    }

    fun offer(page: ChatItemsProjection<T>?) {
        if (offered?.request !== page?.request) {
            requiredAnchor = null
            requestedAnchorFor = null
        }
        offered = page
    }

    fun markAnchorRequested(request: ChatItemsBuildRequest?) {
        requestedAnchorFor = request
    }
}

@Composable
internal fun <T> rememberChatHistoryPage(
    state: LazyListState,
    result: ChatItemsProjection<T>?,
    keepPosition: () -> Boolean,
    scrolling: () -> Boolean,
    indexOfKey: (T, Any) -> Int,
): ChatHistoryPagePresentation<T> {
    val displayed = remember(state) { ChatHistoryPageState<T>() }
    val previous = displayed.committed
    val anchor = displayed.anchor
    val oldIndex = if (previous != null && anchor != null) displayed.committedRows?.lazyIndexOfKey(anchor.key) ?: -1 else -1
    val newIndex = if (result != null && anchor != null) indexOfKey(result.value, anchor.key) else -1
    val exceedsKeyWindow = oldIndex >= 0 && oldIndex == anchor?.index && requiresChatHistoryKeyAnchor(oldIndex, newIndex)
    val largeHistoryPage = previous != null && result?.request?.expandsHistoryFrom(previous.request) == true &&
        keepPosition() && exceedsKeyWindow
    val deferred = displayed.deferredRequest === result?.request
    val anchorRequestPending = displayed.hasRequestedAnchor(result?.request)
    val holdForScroll = largeHistoryPage && (deferred || !anchorRequestPending && scrolling())
    val shown = if (holdForScroll) previous else result
    LaunchedEffect(displayed) {
        snapshotFlow { scrolling() to displayed.deferredRequest }.collect { (isScrolling, deferredRequest) ->
            if (!isScrolling && deferredRequest != null) displayed.defer(null)
        }
    }
    SideEffect {
        if (largeHistoryPage && scrolling() && !anchorRequestPending) displayed.defer(result?.request)
        displayed.offer(shown)
        if (shown === result && !largeHistoryPage) displayed.clearRequiredAnchor()
        if (shown === result && largeHistoryPage && newIndex >= 0) {
            anchor?.let { displayed.requireAnchor(it.key, newIndex) }
        }
    }
    LaunchedEffect(state, displayed) {
        snapshotFlow {
            val page = displayed.committed
            val layout = state.layoutInfo
            val index = state.firstVisibleItemIndex
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (page != null && displayed.hasCommittedLayout(page.request, layout.totalItemsCount) &&
                layout.totalItemsCount == displayed.committedItemCount
            ) item?.let { ChatHistoryAnchor(it.key, index, state.firstVisibleItemScrollOffset) } else null
        }.collect { it?.let(displayed::recordAnchor) }
    }
    return ChatHistoryPagePresentation(shown, displayed)
}

@Composable
internal fun <T> ObserveChatHistoryLayout(
    state: LazyListState,
    page: ChatHistoryPageState<T>,
    request: ChatItemsBuildRequest?,
    rows: SubAgentGroupProjection,
) {
    LaunchedEffect(state, page, request, rows) {
        snapshotFlow {
            val layout = state.layoutInfo
            val index = state.firstVisibleItemIndex
            val first = layout.visibleItemsInfo.firstOrNull { it.index == index }
            Triple(
                layout.totalItemsCount,
                first?.let { ChatHistoryAnchor(it.key, index, state.firstVisibleItemScrollOffset) },
                page.anchorConfirmation,
            )
        }.collect { (itemCount, first, _) ->
            page.reportLayout(request, itemCount, rows.expectedLazyItemCount, first, rows)
        }
    }
}

@Composable
internal fun <T> PreserveChatHistoryAnchor(
    state: LazyListState,
    page: ChatHistoryPageState<T>,
    request: ChatItemsBuildRequest?,
    rows: SubAgentGroupProjection,
    keepPosition: () -> Boolean,
    interactionActive: () -> Boolean,
) {
    val previous = page.committed
    val previousItems = page.committedRows
    SideEffect {
        if (previous == null || previousItems == null || request?.expandsHistoryFrom(previous.request) != true ||
            !keepPosition()
        ) return@SideEffect
        val layout = state.layoutInfo
        val anchor = page.anchor ?: return@SideEffect
        if (layout.totalItemsCount != page.committedItemCount) {
            if (interactionActive() || state.isScrollInProgress) {
                page.defer(request)
                return@SideEffect
            }
            val index = rows.lazyIndexOfKey(anchor.key)
            if (index >= 0) {
                page.requireAnchor(anchor.key, index)
                state.requestScrollToItem(index, anchor.offset)
                page.markAnchorRequested(request)
            }
            return@SideEffect
        }
        val firstIndex = state.firstVisibleItemIndex
        val first = layout.visibleItemsInfo.firstOrNull { it.index == firstIndex } ?: return@SideEffect
        if (first.key != anchor.key || first.key != previousItems.lazyKeyAt(firstIndex)) return@SideEffect
        val index = rows.lazyIndexOfKey(first.key)
        if (!requiresChatHistoryKeyAnchor(firstIndex, index)) return@SideEffect
        if (page.hasRequestedAnchor(request)) return@SideEffect
        if (interactionActive() || state.isScrollInProgress) {
            page.defer(request)
            return@SideEffect
        }

        page.requireAnchor(first.key, index)
        state.requestScrollToItem(index, state.firstVisibleItemScrollOffset)
        page.markAnchorRequested(request)
    }
}
