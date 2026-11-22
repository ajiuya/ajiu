package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

internal val ReasoningWindowMaxHeight = 300.dp

@OptIn(ExperimentalFoundationApi::class)
private val ReasoningCacheWindow = LazyLayoutCacheWindow(
    ahead = ReasoningWindowMaxHeight,
    behind = ReasoningWindowMaxHeight / 2
)

@OptIn(ExperimentalFoundationApi::class)
internal fun newReasoningScrollState(): LazyListState = LazyListState(ReasoningCacheWindow, 0, 0)

internal class ReasoningScrollStateStore(private val maxEntries: Int = 32) {
    private data class Key(val sessionId: String, val messageId: String)
    private val states = LinkedHashMap<Key, LazyListState>(16, 0.75f, true)

    val size: Int get() = states.size

    fun get(sessionId: String, messageId: String): LazyListState {
        val key = Key(sessionId, messageId)
        states[key]?.let { return it }
        return newReasoningScrollState().also {
            states[key] = it
            trim()
        }
    }

    fun retain(sessionId: String, messageId: String, state: LazyListState) {
        states[Key(sessionId, messageId)] = state
        trim()
    }

    fun moveIfPresent(sessionId: String, fromMessageId: String, toMessageId: String) {
        states.remove(Key(sessionId, fromMessageId))?.let { retain(sessionId, toMessageId, it) }
    }

    fun remove(sessionId: String, messageId: String) {
        states.remove(Key(sessionId, messageId))
    }

    fun removeSession(sessionId: String) {
        states.keys.removeAll { it.sessionId == sessionId }
    }

    private fun trim() {
        while (states.size > maxEntries) {
            val oldest = states.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}

@Composable
internal fun rememberReasoningScrollState(
    store: ReasoningScrollStateStore,
    sessionId: String?,
    messageId: String
): LazyListState = remember(store, sessionId, messageId) {
    // 正在组合的窗口继续持有原状态，即使它已被最近浏览缓存淘汰。
    if (sessionId == null) newReasoningScrollState() else store.get(sessionId, messageId)
}

@Composable
internal fun rememberPlaybackReasoningScrollState(
    store: ReasoningScrollStateStore,
    sessionId: String?,
    playbackKey: String,
    messageId: String?
): LazyListState {
    val state = remember(store, sessionId, playbackKey) {
        // 临时播放编号会在重挂宿主时复用，不能作为进程级历史身份。
        if (sessionId == null || messageId == null) newReasoningScrollState() else store.get(sessionId, messageId)
    }
    SideEffect {
        if (sessionId != null && messageId != null) store.retain(sessionId, messageId, state)
    }
    return state
}
