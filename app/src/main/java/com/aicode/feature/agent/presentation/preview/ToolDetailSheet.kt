package com.aicode.feature.agent.presentation.preview

import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.ToolPreviewPalette
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.isCompactWidth
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import com.aicode.feature.agent.presentation.component.LocalSubAgentCardHost
import com.aicode.feature.agent.presentation.component.TaskOperationContent
import com.aicode.feature.agent.presentation.component.TaskOperationIdentity
import com.aicode.feature.agent.presentation.component.TaskOperationPresentation
import com.aicode.feature.agent.presentation.component.projectTaskOperation
import com.aicode.feature.agent.presentation.component.taskOperationCopyText
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailSheet(
    target: ToolPreviewTarget,
    targets: List<ToolPreviewTarget>,
    index: Int,
    state: ToolPreviewHostState,
    browserManager: BrowserManager?,
    cache: ToolPreviewBitmapCache,
    readFileBytes: ((String) -> ByteArray?)?,
    resolveLocalImage: ((String) -> File?)?,
    onOpenBrowser: (() -> Unit)?,
    safety: ToolPreviewSafety = rememberToolPreviewSafety(target),
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialPresentation: ToolPreviewPresentation? = null,
) {
    var touching by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden) dismissing = true
        true
    })
    val compact = isCompactWidth()
    val scrollState = key(target.sourceSessionId, target.id, target.name, safety.bodyHidden) { rememberLazyListState() }
    val updateContent by remember(sheetState, compact, scrollState) {
        derivedStateOf {
            !touching && !settling && !dismissing && !scrollState.isScrollInProgress && (!compact || !sheetState.isAnimationRunning &&
                sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded)
        }
    }
    LaunchedEffect(touching, settling, compact) {
        if (!touching && settling) {
            // Let nested scrolling deliver the release before considering the sheet settled.
            withFrameNanos { }
            snapshotFlow {
                !compact || !sheetState.isAnimationRunning &&
                    sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded
            }.first { it }
            dismissing = false
            settling = false
        }
    }
    val presentation = rememberToolPreviewPresentation(target, enabled = updateContent, dispatcher = workDispatcher, safety = safety,
        initialPresentation = initialPresentation, prepareInitialWhilePaused = !touching && !settling && !dismissing)
    val display = presentation.display
    val stage = display?.stage ?: target.stage
    val active = stage == ToolPreviewStage.PREPARING || stage == ToolPreviewStage.RUNNING
    val host = LocalSubAgentCardHost.current
    val isTask = target.name.equals("task", true) && stage != ToolPreviewStage.PREPARING
    val taskInput by rememberUpdatedState(Triple(display, host?.summaries.orEmpty(), stage))
    val taskPresentation = key(target.sourceSessionId, target.id, target.name, safety.bodyHidden, isTask) {
        val presentation by produceState<TaskOperationPresentation?>(null, updateContent, workDispatcher) {
            if (!updateContent || !isTask) return@produceState
            snapshotFlow { taskInput }.collectLatest { input ->
                val (next, summaries, nextStage) = input
                if (next != null) {
                    val projected = withContext(workDispatcher) {
                        projectTaskOperation(next.taskArgs, next.taskResult, nextStage == ToolPreviewStage.RUNNING, taskDetailIdentitiesOf(summaries))
                    }
                    currentCoroutineContext().ensureActive()
                    if (taskInput === input) value = projected
                }
            }
        }
        presentation
    }
    val bitmap = rememberToolPreviewBitmap(target, browserManager, cache, enabled = true,
        resolveLocalImage = resolveLocalImage, readFileBytes = readFileBytes, maxEdge = TOOL_DETAIL_MAX_EDGE_PX,
        safety = safety, samplingPaused = !updateContent)
    val notExecuted = stringResource(R.string.tool_preview_not_executed)
    val sensitiveBody = stringResource(R.string.tool_preview_sensitive)
    val noOutput = stringResource(R.string.tool_preview_no_output)
    val body = remember(display, notExecuted, sensitiveBody, noOutput) {
        display?.let { toolDetailBodyOf(it, notExecuted, sensitiveBody, noOutput) }.orEmpty()
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val copyText = remember(context, locale, isTask, taskPresentation, active, display, body) {
        if (isTask && taskPresentation != null) taskOperationCopyText(context, taskPresentation, active)
        else display?.let { toolPreviewCopyText(it.command, body) }.orEmpty()
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val latestCopyText by rememberUpdatedState(copyText)
    var copied by remember(target.sourceSessionId, target.id, safety.bodyHidden) { mutableStateOf(false) }
    var copyFailed by remember(target.sourceSessionId, target.id, safety.bodyHidden) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_600); copied = false } }
    val copyAll: () -> Unit = {
        scope.launch {
            try {
                val text = latestCopyText.takeIf(String::isNotBlank)
                if (text != null) {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("tool", text)))
                    copied = true; copyFailed = false
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { copyFailed = true }
        }
    }
    val copyLabel = stringResource(R.string.tool_preview_copy_all)
    AdaptiveModalBottomSheet(onDismissRequest = state::closeDetails, sheetState = sheetState, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * ToolPreviewMetrics.detailHeightFraction).dp)
            .imePadding().background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        settling = true
                        touching = false
                    }
                }
            }) {
            key(target.sourceSessionId, target.id, target.name, safety.bodyHidden) {
                DetailHeader(target, presentation, state::closeDetails, copyText.isNotBlank(), copied, copyFailed, copyAll)
            }
            if (!isTask || stage == ToolPreviewStage.FAILED) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ToolPreviewMetrics.detailPagerInset, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    PreviewStageIcon(stage)
                    Spacer(Modifier.width(ToolPreviewMetrics.statusGap))
                    Text(previewStageLabel(stage), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(ToolPreviewMetrics.statusGap))
            key(target.sourceSessionId, target.id, target.name, safety.bodyHidden) {
                SelectionContainer(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Spacing.md).padding(bottom = Spacing.sm)
                    .appendTextContextMenuComponents {
                        if (copyText.isNotBlank()) item(key = "tool_preview_copy_all", label = copyLabel) {
                            copyAll()
                            close()
                        }
                    }) {
                    LazyColumn(Modifier.fillMaxWidth()
                        .then(if (isTask) Modifier else Modifier.clip(RoundedCornerShape(Radius.md)).background(ToolPreviewPalette.terminalBackground)),
                        state = scrollState) {
                        if (bitmap != null) item(key = "image") {
                            Image(bitmap.asImageBitmap(), stringResource(R.string.tool_preview_image),
                                Modifier.fillMaxWidth().heightIn(max = ToolPreviewMetrics.detailImageMaxHeight).clip(RoundedCornerShape(Radius.mdLarge)),
                                contentScale = ContentScale.Fit)
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        if (target.name.equals("browser", true) && stage != ToolPreviewStage.PREPARING && onOpenBrowser != null) item(key = "browser") {
                            TextButton(onClick = onOpenBrowser) { Text(stringResource(R.string.tool_preview_open_browser), color = ToolPreviewPalette.terminalCommand) }
                        }
                        if (display == null) item(key = "loading") {
                            Text(stringResource(R.string.tool_preview_loading), Modifier.padding(Spacing.md),
                                color = if (isTask) MaterialTheme.colorScheme.onSurfaceVariant else ToolPreviewPalette.placeholder)
                        } else if (isTask) item(key = "task") {
                            TaskOperationContent(taskPresentation, display.taskArgs, display.taskResult, stage == ToolPreviewStage.RUNNING)
                        } else {
                            item(key = "top") { Spacer(Modifier.height(ToolPreviewMetrics.terminalVerticalPadding)) }
                            items(display.terminalCommand.size, key = { "command:$it" }) { index ->
                                TerminalTextBlock(display.terminalCommand[index], ToolPreviewPalette.terminalCommand, FontWeight.SemiBold)
                            }
                            if (display.command.isNotEmpty() && body.isNotBlank()) item(key = "command_gap") { Spacer(Modifier.height(ToolPreviewMetrics.statusGap)) }
                            if (stage == ToolPreviewStage.PREPARING) item(key = "not_executed") {
                                TerminalTextBlock(ToolDetailTextBlock(AnnotatedString(notExecuted)), ToolPreviewPalette.placeholder)
                            }
                            if (display.bodyHidden) item(key = "sensitive") {
                                TerminalTextBlock(ToolDetailTextBlock(AnnotatedString(sensitiveBody)), ToolPreviewPalette.placeholder)
                            }
                            if (stage != ToolPreviewStage.PREPARING && !display.bodyHidden && display.body.isBlank() && display.command.isEmpty()) item(key = "no_output") {
                                TerminalTextBlock(ToolDetailTextBlock(AnnotatedString(noOutput)), ToolPreviewPalette.placeholder)
                            }
                            items(display.terminalBody.size, key = { "body:$it" }) { index ->
                                TerminalTextBlock(display.terminalBody[index],
                                    if (stage == ToolPreviewStage.FAILED) ToolPreviewPalette.terminalError else ToolPreviewPalette.terminalOutput)
                            }
                            item(key = "bottom") { Spacer(Modifier.height(ToolPreviewMetrics.terminalVerticalPadding)) }
                        }
                    }
                }
            }
            if (targets.size > 1) ToolPreviewNavigation(targets, index, state::selectDetail,
                Modifier.fillMaxWidth().padding(horizontal = ToolPreviewMetrics.detailPagerInset, vertical = Spacing.sm), detail = true)
            else Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun DetailHeader(target: ToolPreviewTarget, presentation: ToolPreviewPresentation, onDismiss: () -> Unit,
    copyEnabled: Boolean, copied: Boolean, copyFailed: Boolean, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Text(previewActionTitle(target, presentation), Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl + Spacing.sm),
            fontSize = ToolPreviewMetrics.detailTitleSize, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.align(Alignment.CenterStart).size(Spacing.xxl).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(ToolPreviewMetrics.barBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Icon(FeatherIcons.X, stringResource(R.string.tool_preview_close), Modifier.size(Spacing.lg))
        }
        Box(Modifier.align(Alignment.CenterEnd).size(Spacing.xxl).clip(CircleShape).clickable(enabled = copyEnabled, onClick = onCopy),
            contentAlignment = Alignment.Center) {
            Icon(if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                stringResource(if (copied) R.string.tool_preview_copied else R.string.tool_preview_copy_all), Modifier.size(Spacing.lg),
                tint = if (copied) ToolPreviewPalette.success else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (copyFailed) Text(stringResource(R.string.tool_preview_copy_failed), Modifier.padding(horizontal = Spacing.md),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun TerminalTextBlock(block: ToolDetailTextBlock, color: Color, weight: FontWeight = FontWeight.Normal) {
    Text(block.text, Modifier.fillMaxWidth().padding(horizontal = Spacing.md), softWrap = true,
        fontSize = ToolPreviewMetrics.detailBodySize, lineHeight = ToolPreviewMetrics.detailLineHeight,
        fontFamily = FontFamily.Monospace, fontWeight = weight, color = color)
}

internal data class ToolDetailTextBlock(val text: AnnotatedString, val newlineAfter: Boolean = false)

internal fun toolDetailTextBlocks(text: AnnotatedString): List<ToolDetailTextBlock> = buildList {
    var start = 0
    while (start < text.length) {
        var end = start
        var lines = 0
        while (end < text.length && end - start < 1_024) {
            if (text[end++] == '\n' && ++lines == 16) break
        }
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val hasNextLine = text[end - 1] == '\n' && end < text.length
        add(ToolDetailTextBlock(text.subSequence(start, if (hasNextLine) end - 1 else end), hasNextLine))
        start = end
    }
}

internal fun toolPreviewDiffBody(
    body: String,
    display: ToolPreviewDisplay,
    removeColor: Color = ToolPreviewPalette.diffRemove,
    addColor: Color = ToolPreviewPalette.diffAdd,
): AnnotatedString = buildAnnotatedString {
    append(body)
    if (display.bodyHidden || display.stage == ToolPreviewStage.FAILED) return@buildAnnotatedString
    val diffText = if (display.resultHunks.isNotEmpty()) display.result else listOf(
        display.diff.removed.takeIf(String::isNotEmpty)?.lineSequence()?.joinToString("\n") { "- $it" }.orEmpty(),
        display.diff.added.takeIf(String::isNotEmpty)?.lineSequence()?.joinToString("\n") { "+ $it" }.orEmpty(),
    ).filter(String::isNotEmpty).joinToString("\n")
    if (diffText.isEmpty() || !body.endsWith(diffText)) return@buildAnnotatedString
    var offset = body.length - diffText.length
    for (line in diffText.lineSequence()) {
        val color = when (line.firstOrNull()) { '-' -> removeColor; '+' -> addColor; else -> null }
        if (color != null && line.isNotEmpty()) addStyle(SpanStyle(color = color), offset, offset + line.length)
        offset += line.length + 1
    }
}

internal fun taskDetailIdentitiesOf(summaries: Map<String, SubAgentInteractionSummary>): Map<String, TaskOperationIdentity> =
    summaries.filter { (id, summary) -> id == summary.subSessionId }.mapValues { (_, summary) ->
        TaskOperationIdentity(name = summary.agentName?.let(::safeToolPreviewText), task = safeToolPreviewText(summary.title))
    }

