package com.aicode.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 全局统一的精致紧凑型开关组件：
 * - 紧凑尺寸（42.dp × 24.dp），告别 Material 3 默认 Switch 的笨重厚重感；
 * - 纯白微阴影圆形滑块 + 丝滑平移动画；
 * - 浅色模式为品牌蓝 / 柔和浅灰轨道；深色模式为亮蓝 / 暗灰蓝轨道。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // 轨道颜色
    val targetTrackColor = when {
        checked -> MaterialTheme.colorScheme.primary
        isLight -> Color(0xFFE2E8F0)
        else -> Color(0xFF334155)
    }
    val animatedTrackColor = animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = AppMotion.tween(AppMotion.colorMillis),
        label = "switchTrackColor"
    )

    // 滑块颜色
    val thumbColor = if (isLight) Color.White else Color(0xFFF8FAFC)

    // 滑块位移（轨道宽 42dp，内边距 2dp，滑块 20dp，位移范围 2dp -> 20dp）
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = AppMotion.tween(AppMotion.colorMillis),
        label = "switchThumbOffset"
    )

    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = onCheckedChange
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(toggleModifier)
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind { drawRect(animatedTrackColor.value) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.value.roundToPx(), 0) }
                .size(20.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(thumbColor, CircleShape)
        )
    }
}
