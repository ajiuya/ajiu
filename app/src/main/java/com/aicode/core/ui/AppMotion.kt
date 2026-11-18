package com.aicode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween as animationTween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

internal object AppMotion {
    const val pressMillis = 80
    const val releaseMillis = 160
    const val colorMillis = 160
    const val expandMillis = 180
    const val collapseMillis = 140
    const val pageMillis = 220
    const val pressedScale = 0.98f
    const val expandingContentMaxChars = 2_048
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> tween(durationMillis: Int = releaseMillis): TweenSpec<T> =
        animationTween(durationMillis = durationMillis, easing = easing)

    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 700f)

    fun contentEnter(animateSize: Boolean): EnterTransition = fadeIn(tween(expandMillis)) +
        if (animateSize) expandVertically(tween(expandMillis), expandFrom = Alignment.Top) else EnterTransition.None

    fun contentExit(animateSize: Boolean): ExitTransition = fadeOut(tween(collapseMillis)) +
        if (animateSize) shrinkVertically(tween(collapseMillis), shrinkTowards = Alignment.Top) else ExitTransition.None
}

@Composable
internal fun Modifier.appPressFeedback(source: InteractionSource, enabled: Boolean = true): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (enabled && pressed) AppMotion.pressedScale else 1f,
        animationSpec = AppMotion.tween(if (pressed) AppMotion.pressMillis else AppMotion.releaseMillis),
        label = "app-press"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
