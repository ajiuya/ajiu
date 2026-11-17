package com.aicode.feature.agent.presentation.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.ToolPreviewPalette
import java.util.Locale

@Composable
internal fun ToolPreviewThumbnail(
    target: ToolPreviewTarget,
    display: ToolPreviewDisplay?,
    bitmap: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(ToolPreviewMetrics.thumbnailWidth, ToolPreviewMetrics.thumbnailHeight)
        .clip(RoundedCornerShape(Radius.sm)).background(ToolPreviewPalette.thumbnailBackground)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick,
            onClickLabel = stringResource(R.string.tool_preview_open_detail),
            onLongClickLabel = stringResource(R.string.tool_preview_disable))) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.tool_preview_image),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.padding(horizontal = ToolPreviewMetrics.thumbnailHorizontalPadding, vertical = ToolPreviewMetrics.thumbnailVerticalPadding)) {
            MiniText(previewStageLabel(target.stage),
                if (target.stage == ToolPreviewStage.FAILED) ToolPreviewPalette.diffRemove else ToolPreviewPalette.thumbnailStatus,
                bold = true, lines = 1, fontSize = ToolPreviewMetrics.thumbnailStatusFontSize, lineHeight = ToolPreviewMetrics.thumbnailStatusLineHeight)
            if (display != null) {
                when {
                    display.bodyHidden -> MiniText(stringResource(R.string.tool_preview_sensitive), ToolPreviewPalette.thumbnailText)
                    display.diff.removed.isNotEmpty() || display.diff.added.isNotEmpty() -> {
                        if (display.diff.removed.isNotEmpty()) MiniText(
                            previewTailLines(display.diff.removed, 3, 512).lineSequence().joinToString("\n") { "- $it" },
                            ToolPreviewPalette.diffRemove, lines = 3, fontSize = ToolPreviewMetrics.thumbnailDiffFontSize,
                            lineHeight = ToolPreviewMetrics.thumbnailDiffLineHeight)
                        if (display.diff.added.isNotEmpty()) MiniText(
                            previewTailLines(display.diff.added, 4, 512).lineSequence().joinToString("\n") { "+ $it" },
                            ToolPreviewPalette.diffAdd, lines = 4, fontSize = ToolPreviewMetrics.thumbnailDiffFontSize,
                            lineHeight = ToolPreviewMetrics.thumbnailDiffLineHeight)
                    }
                    target.kind == ToolPreviewKind.COMMAND -> {
                        if (display.command.isNotBlank()) MiniText("$ ${display.command.lineSequence().firstOrNull(String::isNotBlank).orEmpty()}",
                            ToolPreviewPalette.thumbnailCommand, bold = true, lines = 1,
                            fontSize = ToolPreviewMetrics.thumbnailCommandFontSize, lineHeight = ToolPreviewMetrics.thumbnailCommandLineHeight)
                        MiniText(previewTailLines(display.body, 8).ifBlank { stringResource(R.string.tool_preview_no_output) },
                            if (target.stage == ToolPreviewStage.FAILED) ToolPreviewPalette.diffRemove else ToolPreviewPalette.commandTail)
                    }
                    else -> {
                        if (target.kind == ToolPreviewKind.FILE || target.kind == ToolPreviewKind.TEXT) {
                            display.parameters.firstOrNull { it.name in setOf("path", "file", "filePath") }?.value?.let {
                                MiniText(it.substringAfterLast('/'), ToolPreviewPalette.thumbnailFileName, bold = true, lines = 1,
                                    fontSize = ToolPreviewMetrics.thumbnailFileNameFontSize, lineHeight = ToolPreviewMetrics.thumbnailCommandLineHeight)
                            }
                        }
                        MiniText(previewTailLines(display.body, 8).ifBlank { stringResource(R.string.tool_preview_no_output) },
                            if (target.stage == ToolPreviewStage.FAILED) ToolPreviewPalette.diffRemove else ToolPreviewPalette.thumbnailText)
                    }
                }
            }
        }
        if (showResourceHud(target, LocalUiWorkVisible.current)) ResourceHud(Modifier.align(Alignment.BottomStart).fillMaxWidth())
    }
}

@Composable
private fun ResourceHud(modifier: Modifier = Modifier) {
    val usage = rememberSystemResourceUsage(active = true)
    val unknown = stringResource(R.string.tool_preview_unknown_resource)
    val cpu = usage.cpuPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: unknown
    val pss = usage.pssBytes?.let { String.format(Locale.ROOT, "%.1fG", it / 1_073_741_824.0) } ?: unknown
    val description = stringResource(R.string.tool_preview_hud_app_description, cpu, pss)
    Row(modifier.background(ToolPreviewPalette.hudBackground)
        .semantics { contentDescription = description }
        .padding(horizontal = Spacing.xs, vertical = ToolPreviewMetrics.hudVerticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tool_preview_hud_cpu, cpu), fontSize = ToolPreviewMetrics.hudFontSize,
            fontFamily = FontFamily.Monospace, color = ToolPreviewPalette.hudText, maxLines = 1)
        Text(stringResource(R.string.tool_preview_hud_pss, pss), fontSize = ToolPreviewMetrics.hudFontSize,
            fontFamily = FontFamily.Monospace, color = ToolPreviewPalette.hudText, maxLines = 1)
    }
}

@Composable
private fun MiniText(
    text: String,
    color: Color,
    bold: Boolean = false,
    lines: Int = 8,
    fontSize: TextUnit = ToolPreviewMetrics.thumbnailBodyFontSize,
    lineHeight: TextUnit = ToolPreviewMetrics.thumbnailBodyLineHeight,
) {
    Text(text, color = color, fontSize = fontSize, lineHeight = lineHeight, fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}
