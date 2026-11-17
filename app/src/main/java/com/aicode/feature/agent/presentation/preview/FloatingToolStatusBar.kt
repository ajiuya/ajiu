package com.aicode.feature.agent.presentation.preview

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.ToolPreviewPalette
import com.aicode.feature.agent.presentation.component.toolActionPlaceholderRes
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight

@Composable
internal fun FloatingToolStatusBar(
    target: ToolPreviewTarget,
    display: ToolPreviewDisplay?,
    bitmap: Bitmap?,
    targets: List<ToolPreviewTarget>,
    index: Int,
    state: ToolPreviewHostState,
    thumbnailEnabled: Boolean,
    presentation: ToolPreviewPresentation,
    onSetThumbnailEnabled: (Boolean) -> Unit,
) {
    var barMenu by remember { mutableStateOf(false) }
    var thumbMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ToolPreviewMetrics.barRadius)
    Box(Modifier.fillMaxWidth().height(if (thumbnailEnabled) ToolPreviewMetrics.thumbnailHeight else ToolPreviewMetrics.barHeight)) {
        Layout(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ToolPreviewMetrics.barHeight)
                .shadow(ToolPreviewMetrics.barShadow, shape, ambientColor = ToolPreviewPalette.shadowAmbient, spotColor = ToolPreviewPalette.shadowSpot)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), shape)
                .border(ToolPreviewMetrics.barBorder, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                .padding(start = ToolPreviewMetrics.barStartPadding(thumbnailEnabled), end = ToolPreviewMetrics.barEndPadding),
            content = {
                PreviewStageIcon(target.stage)
                Box {
                    Text(
                        previewActionTitle(target, presentation),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            role = Role.Button,
                            onClick = { state.openDetails(target.id) }, onLongClick = { barMenu = true },
                            onClickLabel = stringResource(R.string.tool_preview_open_detail),
                            onLongClickLabel = stringResource(R.string.tool_preview_options),
                        ),
                        fontSize = ToolPreviewMetrics.titleSize, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    DropdownMenu(expanded = barMenu, onDismissRequest = { barMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (thumbnailEnabled) R.string.tool_preview_disable else R.string.tool_preview_enable)) },
                            trailingIcon = { if (thumbnailEnabled) Icon(FeatherIcons.Check, null, Modifier.size(Spacing.lg)) },
                            onClick = { barMenu = false; onSetThumbnailEnabled(!thumbnailEnabled) },
                        )
                    }
                }
                Box { if (targets.size > 1) ToolPreviewNavigation(targets, index, state::select) }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val status = measurables[0].measure(loose)
            val navigation = measurables[2].measure(loose)
            val gap = ToolPreviewMetrics.statusGap.roundToPx()
            val titleStart = status.width + gap
            val titleEnd = constraints.maxWidth - navigation.width - if (navigation.width > 0) gap else 0
            val titleWidth = (titleEnd - titleStart).coerceAtLeast(0)
            val title = measurables[1].measure(loose.copy(maxWidth = titleWidth))
            layout(constraints.maxWidth, constraints.maxHeight) {
                status.placeRelative(0, (constraints.maxHeight - status.height) / 2)
                title.placeRelative(titleStart + (titleWidth - title.width) / 2, (constraints.maxHeight - title.height) / 2)
                navigation.placeRelative(constraints.maxWidth - navigation.width, (constraints.maxHeight - navigation.height) / 2)
            }
        }
        if (thumbnailEnabled) {
            Box(Modifier.align(Alignment.TopStart).padding(start = ToolPreviewMetrics.thumbnailInset)) {
                ToolPreviewThumbnail(target, display, bitmap,
                    onClick = { state.openDetails(target.id) }, onLongClick = { thumbMenu = true })
                DropdownMenu(expanded = thumbMenu, onDismissRequest = { thumbMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tool_preview_disable), color = MaterialTheme.colorScheme.error) },
                        onClick = { thumbMenu = false; onSetThumbnailEnabled(false) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolPreviewNavigation(
    targets: List<ToolPreviewTarget>,
    index: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    detail: Boolean = false,
) {
    if (targets.size <= 1 || index !in targets.indices) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (detail) Arrangement.SpaceBetween else Arrangement.Center) {
        PreviewPageArrow(previous = true, enabled = index > 0, detail = detail,
            onClick = { targets.getOrNull(index - 1)?.let { onSelect(it.id) } },
            onLongClick = { onSelect(targets.first().id) })
        Text(stringResource(if (detail) R.string.tool_preview_detail_page else R.string.tool_preview_page, index + 1, targets.size),
            fontSize = if (detail) ToolPreviewMetrics.detailPageSize else ToolPreviewMetrics.pageSize,
            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PreviewPageArrow(previous = false, enabled = index < targets.lastIndex, detail = detail,
            onClick = { targets.getOrNull(index + 1)?.let { onSelect(it.id) } },
            onLongClick = { onSelect(targets.last().id) })
    }
}

@Composable
private fun PreviewPageArrow(previous: Boolean, enabled: Boolean, detail: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Icon(
        if (previous) FeatherIcons.ChevronLeft else FeatherIcons.ChevronRight,
        stringResource(if (previous) R.string.tool_preview_previous else R.string.tool_preview_next),
        modifier = Modifier.size(if (detail) ToolPreviewMetrics.detailArrowSize else ToolPreviewMetrics.arrowSize)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = stringResource(if (previous) R.string.tool_preview_first else R.string.tool_preview_last)),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.25f),
    )
}

@Composable
internal fun previewActionTitle(target: ToolPreviewTarget, presentation: ToolPreviewPresentation): String =
    presentation.actionResource?.let { stringResource(it) }
        ?: presentation.actionTitle ?: stringResource(toolActionPlaceholderRes(target.name))

@Composable
internal fun previewStageLabel(stage: ToolPreviewStage): String = stringResource(when (stage) {
    ToolPreviewStage.PREPARING -> R.string.tool_preview_preparing
    ToolPreviewStage.RUNNING -> R.string.tool_preview_running
    ToolPreviewStage.COMPLETED -> R.string.tool_preview_completed
    ToolPreviewStage.FAILED -> R.string.tool_preview_failed
})

@Composable
internal fun PreviewStageIcon(stage: ToolPreviewStage) {
    if (stage == ToolPreviewStage.RUNNING || stage == ToolPreviewStage.PREPARING) {
        CircularProgressIndicator(Modifier.size(ToolPreviewMetrics.statusSize), strokeWidth = ToolPreviewMetrics.progressStroke)
    } else {
        Icon(if (stage == ToolPreviewStage.FAILED) FeatherIcons.AlertCircle else FeatherIcons.Check,
            contentDescription = previewStageLabel(stage), modifier = Modifier.size(ToolPreviewMetrics.statusSize),
            tint = if (stage == ToolPreviewStage.FAILED) MaterialTheme.colorScheme.error else ToolPreviewPalette.success)
    }
}
