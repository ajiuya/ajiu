package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
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
) {
    fun sameOwner(current: ChatItemsBuildRequest?): Boolean = current != null && source.loaded && current.source.loaded &&
        source.sessionId == current.source.sessionId && ownership === current.ownership

    fun canPublishTo(current: ChatItemsBuildRequest?): Boolean = current != null && sameOwner(current) &&
        inputVersion === current.inputVersion && source.messages === current.source.messages
}

internal class ChatItemsProjection<T>(val request: ChatItemsBuildRequest, val value: T) {
    fun retainedFor(current: ChatItemsBuildRequest?): T? = value.takeIf { request.sameOwner(current) }
}

@Composable
internal fun <T> rememberChatProjection(
    request: ChatItemsBuildRequest?,
    calculate: suspend () -> T,
): ChatItemsProjection<T>? {
    var result by remember { mutableStateOf<ChatItemsProjection<T>?>(null) }
    val currentRequest by rememberUpdatedState(request)
    UiWorkEffect(request) {
        if (request == null) return@UiWorkEffect
        val built = withContext(Dispatchers.Default) { calculate() }
        currentCoroutineContext().ensureActive()
        if (request.canPublishTo(currentRequest)) result = ChatItemsProjection(request, built)
    }
    return result?.takeIf { it.request.sameOwner(request) }
}
