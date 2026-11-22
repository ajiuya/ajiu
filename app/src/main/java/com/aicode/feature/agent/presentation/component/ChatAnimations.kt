package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aicode.feature.agent.presentation.preview.LocalUiWorkVisible

internal val LocalChatAnimationsEnabled = compositionLocalOf { true }

@Composable
internal fun chatAnimationsEnabled(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return LocalUiWorkVisible.current && LocalChatAnimationsEnabled.current && state.isAtLeast(Lifecycle.State.RESUMED)
}

@Composable
internal fun rememberChatInfiniteFloat(
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String,
    enabled: Boolean = true,
    pausedValue: Float = initialValue
): State<Float> {
    if (!enabled || !chatAnimationsEnabled()) return rememberUpdatedState(pausedValue)
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = "$label-value"
    )
}
