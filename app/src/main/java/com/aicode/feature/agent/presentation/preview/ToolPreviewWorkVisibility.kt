package com.aicode.feature.agent.presentation.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

internal val LocalUiWorkVisible = staticCompositionLocalOf { true }

@Composable
internal fun ToolPreviewWorkVisibility(visible: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiWorkVisible provides (LocalUiWorkVisible.current && visible), content = content)
}

internal suspend fun Lifecycle.repeatVisibleUiWork(visible: Boolean, block: suspend CoroutineScope.() -> Unit) {
    if (visible) repeatOnLifecycle(Lifecycle.State.STARTED, block)
}

@Composable
internal fun UiWorkEffect(vararg keys: Any?, visible: Boolean = LocalUiWorkVisible.current, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest = rememberUpdatedState(block)
    LaunchedEffect(lifecycle, visible, *keys) {
        lifecycle.repeatVisibleUiWork(visible) { latest.value(this) }
    }
}
