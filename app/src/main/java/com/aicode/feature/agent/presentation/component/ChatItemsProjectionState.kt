package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
