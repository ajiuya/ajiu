package com.aicode.feature.agent.presentation.preview

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing

/** RC2 geometry is shared by the overlay and list reserve; changing one in isolation overlaps rows. */
internal object ToolPreviewMetrics {
    val barHeight = 38.dp
    val thumbnailWidth = 100.dp
    val thumbnailHeight = 65.dp
    val thumbnailInset = 10.dp
    val overhang = thumbnailHeight - barHeight
    val bottomPadding = 6.dp
    val rowGap = Spacing.sm
    val barRadius = Radius.md
    val barBorder = 0.5.dp
    val barShadow = Spacing.sm
    val barEndPadding = 10.dp
    val statusSize = 14.dp
    val statusGap = 6.dp
    val progressStroke = 1.5.dp
    val arrowSize = 18.dp
    val detailArrowSize = 20.dp
    val titleSize = 13.sp
    val pageSize = 11.sp
    val detailTitleSize = 14.sp
    val detailPageSize = 13.sp
    val detailBodySize = 12.5.sp
    val detailLineHeight = 18.sp
    val detailHeightFraction = 0.9f
    val detailImageMaxHeight = 360.dp
    val detailPagerInset = 18.dp
    val terminalVerticalPadding = 10.dp
    val thumbnailHorizontalPadding = 6.dp
    val thumbnailVerticalPadding = Spacing.xs
    val hudVerticalPadding = 1.dp
    val hudFontSize = 5.sp
    val thumbnailStatusFontSize = 6.sp
    val thumbnailStatusLineHeight = 7.sp
    val thumbnailCommandFontSize = 7.sp
    val thumbnailCommandLineHeight = 8.sp
    val thumbnailBodyFontSize = 5.5.sp
    val thumbnailBodyLineHeight = 6.5.sp
    val thumbnailDiffFontSize = 5.sp
    val thumbnailDiffLineHeight = 6.sp
    val thumbnailFileNameFontSize = 6.5.sp
    fun barStartPadding(enabled: Boolean) = if (enabled) thumbnailWidth + thumbnailInset + Spacing.sm else Spacing.md
    fun visibleHeight(enabled: Boolean) = bottomPadding + if (enabled) thumbnailHeight else barHeight
    fun listBottomReserve(enabled: Boolean) = visibleHeight(enabled) + rowGap
}
