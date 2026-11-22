package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Play
import compose.icons.feathericons.X

private const val MAX_VISIBLE_PENDING_COUNT = 99

@Composable
internal fun SessionRecoveryBubble(
    pendingCount: Int,
    canContinue: Boolean,
    canAbandon: Boolean,
    confirming: Boolean,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pendingCount <= 0) return

    val colors = MaterialTheme.colorScheme
    val continueLabel = stringResource(
        if (confirming) R.string.session_recovery_confirm_continue else R.string.session_recovery_continue
    )
    val shortConfirmLabel = if (confirming) stringResource(R.string.session_recovery_bubble_confirm) else null
    val count = when {
        pendingCount > MAX_VISIBLE_PENDING_COUNT -> stringResource(
            R.string.session_recovery_bubble_count_overflow, MAX_VISIBLE_PENDING_COUNT
        )
        pendingCount > 1 -> stringResource(R.string.session_recovery_bubble_count, pendingCount)
        else -> null
    }
    val countDescription = if (count != null) {
        stringResource(R.string.session_recovery_pending_count, pendingCount)
    } else null
    val abandonLabel = stringResource(R.string.session_recovery_abandon)
    val labelLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val height = maxOf(30.dp, with(LocalDensity.current) { labelLineHeight.toDp() })

    Row(
        modifier = modifier
            .width(104.dp)
            .height(height)
            .testTag("session_recovery_bubble")
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecoveryBubbleAction(
            label = continueLabel,
            shortLabel = shortConfirmLabel,
            icon = if (confirming) FeatherIcons.Check else FeatherIcons.Play,
            color = colors.primary,
            enabled = canContinue,
            onClick = onContinue,
            count = count,
            countDescription = countDescription,
            modifier = Modifier.weight(if (count != null) 1.15f else 1f)
                .fillMaxHeight().testTag("session_recovery_continue")
        )
        Spacer(Modifier.width(1.dp).height(Spacing.lg).background(colors.outline))
        RecoveryBubbleAction(
            label = abandonLabel,
            icon = FeatherIcons.X,
            color = colors.error,
            enabled = canAbandon,
            onClick = onAbandon,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("session_recovery_abandon")
        )
    }
}

@Composable
private fun RecoveryBubbleAction(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    shortLabel: String? = null,
    count: String? = null,
    countDescription: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
    val labelColor = if (enabled) color else colors.onSurface.copy(alpha = 0.38f)
    val gap = Spacing.xs / 2

    BoxWithConstraints(
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button) { if (enabled) onClick() }
            .semantics {
                contentDescription = label
                if (countDescription != null) stateDescription = countDescription
            }
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        val textMeasurer = rememberTextMeasurer()
        val gapPx = with(LocalDensity.current) { gap.roundToPx() }
        val iconWidth = with(LocalDensity.current) { Spacing.md.roundToPx() }
        val countSize = count?.let { textMeasurer.measure(AnnotatedString(it), style, maxLines = 1).size }
        val countWidth = if (countSize != null) countSize.width + gapPx else 0
        fun fits(text: String): Boolean {
            val textSize = textMeasurer.measure(AnnotatedString(text), style, maxLines = 1).size
            return textSize.width + countWidth <= constraints.maxWidth &&
                textSize.height <= constraints.maxHeight
        }
        val visibleLabel = when {
            fits(label) -> label
            shortLabel != null && fits(shortLabel) -> shortLabel
            else -> null
        }
        val showCount = countSize != null && countSize.height <= constraints.maxHeight &&
            (visibleLabel != null || iconWidth + countWidth <= constraints.maxWidth)

        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (visibleLabel != null) {
                Text(visibleLabel, style = style, color = labelColor, maxLines = 1)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(Spacing.md), tint = labelColor)
            }
            if (showCount && count != null) {
                Text(
                    text = count,
                    style = style,
                    color = if (enabled) colors.onSurfaceVariant else labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
