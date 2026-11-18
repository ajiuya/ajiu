package com.aicode.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.Image
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicode.R
import com.aicode.core.ui.AppMotion

internal val DiffAddBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffAddBg

internal val DiffAddText: Color
    @Composable get() = MaterialTheme.semanticColors.diffAdd

internal val DiffRemoveBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemoveBg

internal val DiffRemoveText: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemove

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/** 工具「指令 / 结果」展开窗口的最大高度：超出后在窗口内滚动，不再无限撑高。 */
private val ToolSectionMaxHeight = 240.dp

/** 工具窗口底部渐隐遮罩高度（仅当内容还可继续下滚时显示）。 */
private val ToolSectionFadeHeight = 24.dp

/** 展开态由宿主持有，避免列表回收或切页后丢失手动查看的详情。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    durationMs: Long? = null,
    onOpenPreview: (() -> Unit)? = null,
) {
    val streaming = liveOutput != null
    val running = message.isToolRunning(liveOutput)
    // produceState 的 key 只重启任务，不重置 value；组合 key 同时隔离旧输入的结果。
    val metadata by key(message.id, message.content, message.toolName, message.isError, running) {
        produceState<ToolMessageMetadata?>(initialValue = null) {
            value = withContext(Dispatchers.Default) {
                ToolMessageMetadata(
                    edit = if (!running && !message.isError &&
                        (message.toolName == "editFile" || message.toolName == "writeFile")
                    ) parseEditDiff(message.content) else null,
                    todo = if (message.toolName == "todo" && !running && !message.isError) {
                        parseTodoResult(message.content)
                    } else null,
                    webSearch = if (message.toolName == "websearch" && !running && !message.isError) {
                        parseWebSearchResult(message.content)
                    } else null,
                    // task 的卡片连「派发中」也要显示（此时结果还没到，只有参数），故不受 running 限制。
                    subAgent = if (message.toolName == "task" && !message.isError) {
                        parseSubAgentDispatch(message.toolArgs, message.content)
                    } else null,
                    notifications = if (!running) parseToolNotifications(message.content) else emptyList(),
                    hasResult = !running && hasToolResult(message.content)
                )
            }
        }
    }
    val hasArguments by key(message.id, message.toolArgs) {
        produceState<Boolean?>(initialValue = null) {
            value = withContext(Dispatchers.Default) { hasToolArgs(message.toolArgs) }
        }
    }
    val edit = metadata?.edit
    val todoData = metadata?.todo
    val actionTitle = message.toolActionTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(toolActionPlaceholderRes(message.toolName))
    val representedIds = LocalRepresentedSubAgentIds.current
    val notifications = metadata?.notifications.orEmpty().filterNot { it.subSessionId in representedIds }

    val hasLiveOutput = !liveOutput.isNullOrBlank()
    val expandable = running || metadata == null || hasArguments == null ||
        metadata?.hasResult == true || hasArguments == true ||
        metadata?.subAgent != null || notifications.isNotEmpty()
    val effectiveExpanded = expandedOverride == true

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight)
                .clip(RoundedCornerShape(Radius.pill))
                .background(chatMutedSurfaceColor())
                .border(ChatStyle.hairline, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.pill))
                .then(
                    if (expandable) Modifier.combinedClickable(
                        onClick = {
                            onExpandedChange?.invoke(!effectiveExpanded)
                            onToggle?.invoke()
                        },
                        onLongClick = onOpenPreview
                    ) else Modifier
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            ToolStatusIcon(running = running, isError = message.isError, toolName = message.toolName)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = actionTitle,
                modifier = Modifier.weight(1f, fill = false),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = durationMs?.let(::formatToolDuration)
                    ?: if (running) stringResource(R.string.chat_tool_waiting_execution)
                    else stringResource(R.string.chat_tool_group_duration_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            if (expandable) {
                Spacer(Modifier.width(Spacing.xs))
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
        }
        if (effectiveExpanded) {
            Text(
                text = "${stringResource(R.string.common_tool)} · ${message.toolName.orEmpty()}",
                modifier = Modifier.padding(top = Spacing.sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (streaming) {
            // 展开态与落库卡片同构：先「指令」（工具参数），再「结果」（实时输出尾部）；
            // 折叠态只保留胶囊标题。
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = AppMotion.contentEnter(animateSize = false),
                exit = AppMotion.contentExit(animateSize = false),
            ) {
                val preparedArgs by key(message.id, message.toolArgs, running) {
                    produceState<String?>(initialValue = null) {
                        value = withContext(Dispatchers.Default) { formatToolArgs(message.toolArgs) }
                    }
                }
                val argsFull = preparedArgs
                Column {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (hasLiveOutput) {
                        val truncated = remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = truncated, live = true)
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = AppMotion.contentEnter(animateSize = message.content.length + message.toolArgs.orEmpty().length <= AppMotion.expandingContentMaxChars),
                exit = AppMotion.contentExit(animateSize = message.content.length + message.toolArgs.orEmpty().length <= AppMotion.expandingContentMaxChars),
            ) {
                // 详情任务跟随 AnimatedVisibility 的内容生命周期，收起时保留至退出动画结束。
                val details by key(
                    message.id, message.content, message.toolArgs, message.toolName,
                    message.isError, running
                ) {
                    produceState<ToolMessageDetails?>(initialValue = null, key1 = metadata) {
                        val prepared = metadata ?: return@produceState
                        value = withContext(Dispatchers.Default) {
                            val webSearch = prepared.webSearch
                            val plain = prepared.edit == null &&
                                prepared.todo?.items.isNullOrEmpty() && webSearch == null
                            ToolMessageDetails(
                                args = if (plain) formatToolArgs(message.toolArgs) else null,
                                result = if (plain && !running) formatToolResult(message.content) else null,
                                webSearch = webSearch,
                                diffCopyText = prepared.edit?.hunks?.joinToString("\n") { it.diff }
                            )
                        }
                    }
                }
                val argsFull = details?.args
                val resultText = details?.result
                val webSearchData = details?.webSearch
                Column(
                    modifier = Modifier.pointerInput(message.id) {
                        detectDoubleTapToCollapse {
                            onExpandedChange?.invoke(false)
                            onToggle?.invoke()
                        }
                    }
                ) {
                if (todoData != null && todoData.items.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    TodoCard(items = todoData.items)
                } else if (webSearchData != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    WebSearchResultCard(result = webSearchData)
                } else if (edit != null && details != null) {
                    // 差异卡：头部给路径与「复制」，页脚给增删统计（DSH 那块白底描边卡）
                    Spacer(Modifier.height(Spacing.xs))
                    ChatCodeCard(
                        title = edit.path.ifBlank { null },
                        copyText = details?.diffCopyText.orEmpty(),
                        footer = stringResource(
                            R.string.tool_changed_files_summary,
                            edit.added,
                            edit.removed,
                            if (edit.path.isBlank()) 0 else 1
                        )
                    ) {
                        Column {
                            edit.hunks.forEach { h -> DiffView(diff = h.diff, startLine = h.startLine) }
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                } else {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (!resultText.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = resultText)
                    }
                }
                }
            }
        }
        if (effectiveExpanded) {
            metadata?.subAgent?.let { dispatch ->
                Spacer(Modifier.height(Spacing.xs))
                SubAgentDispatchCard(dispatch = dispatch)
            }
            if (notifications.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                notifications.forEach { ToolNotificationRow(it) }
            }
        }
        // 文件列表：工具结束后常显在消息底部，一行一个文件，点击用系统 app 打开。
        if (!running && message.attachments.isNotEmpty()) {
            val opener = LocalAttachmentOpener.current
            MessageAttachmentList(
                attachments = message.attachments,
                onClick = { opener.open(it) }
            )
        }
    }
}

private data class ToolMessageMetadata(
    val edit: EditDiff?,
    val todo: ParsedTodoResult?,
    val webSearch: ParsedWebSearchResult?,
    val subAgent: ParsedSubAgentDispatch?,
    val notifications: List<ToolNotificationInfo>,
    val hasResult: Boolean
)

private data class ToolMessageDetails(
    val args: String?,
    val result: String?,
    val webSearch: ParsedWebSearchResult?,
    val diffCopyText: String?
)

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false, val subSessionId: String? = null)

/**
 * 从工具结果 transport JSON 顶层的 `notifications` 字段提取搭车通知（后台任务/子代理完成）。
 * 该字段由 [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow] 在 AI 忙碌时注入。
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    val identities = parseSubAgentNotificationMetadata(raw, toolResult = true).associateBy { it.ordinal }
    return array.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapIndexedNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message",
            subSessionId = identities[index]?.subSessionId
        )
    }
}

/** 工具卡片底部的搭车通知提示条：状态点 + 摘要。 */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/**
 * 双击折叠检测：detectTapGestures 的 awaitFirstDown 默认 requireUnconsumed=true，
 * 而展开区内容包在 SelectionContainer / clickable 里会消费 down，导致首击被忽略、
 * 双击永远不触发。这里首击不要求未消费，只做「快速连点两次」判定。
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
    val viewConfig = viewConfiguration
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        // 两次点击期间若发生明显位移（用户在滑动），放弃双击判定
        if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
            return@awaitEachGesture
        }
        if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
            return@awaitEachGesture
        }
        if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        onDoubleTap()
    }
}

/** 等待指定指针抬起；期间若位移超过 slop（发生滑动），返回 false。 */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - downPosition).getDistance() > slop) return false
        if (!change.pressed) return true
    }
}

/** 状态同时提供读屏语义，颜色之外仍能区分执行中、成功与失败。 */
@Composable
internal fun ToolStatusIcon(running: Boolean, isError: Boolean, toolName: String?) {
    val state = toolExecutionState(running, isError)
    val tint = when (state) {
        ToolExecutionState.RUNNING -> MaterialTheme.colorScheme.primary
        ToolExecutionState.FAILED -> MaterialTheme.colorScheme.error
        ToolExecutionState.SUCCEEDED -> MaterialTheme.semanticColors.success
    }
    val pulseAlpha = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-status-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
            label = "tool-status-pulse-alpha"
        ).value
    } else {
        1f
    }
    val statusLabel = stringResource(
        when (state) {
            ToolExecutionState.RUNNING -> R.string.chat_tool_running
            ToolExecutionState.FAILED -> R.string.chat_tool_failed
            ToolExecutionState.SUCCEEDED -> R.string.chat_tool_succeeded
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = toolIcon(toolName),
            contentDescription = statusLabel,
            tint = tint,
            modifier = Modifier
                // 与思考行同一格宽：两行的图标中心与后续文字左边缘才对得齐
                .size(ChatStyle.rowIconSize)
                .graphicsLayer { alpha = pulseAlpha }
        )
    }
}

/** 工具名字形映射：键与 [com.aicode.feature.agent.domain.tool.ToolRegistry] 注册名一致（比对时忽略大小写）。 */
private fun toolIcon(toolName: String?): ImageVector = when (toolName?.lowercase()) {
    "editfile", "writefile", "generateimage" -> FeatherIcons.Edit3
    "readfile", "list", "sendfile" -> FeatherIcons.FileText
    "viewimage" -> FeatherIcons.Image
    "search", "websearch", "webfetch" -> FeatherIcons.Search
    "browser" -> FeatherIcons.Globe
    "bash", "terminal", "root", "shizuku" -> FeatherIcons.Terminal
    "askuserquestion" -> FeatherIcons.HelpCircle
    "submitplan" -> FeatherIcons.FileText
    "todo" -> FeatherIcons.Check
    "task" -> FeatherIcons.Cpu
    "memory" -> FeatherIcons.Database
    else -> FeatherIcons.Tool
}

/**
 * 工具执行中的场景文案：把工具名归成用户一眼能懂的「正在做什么」，供统一忙碌指示器
 * （[AgentBusyIndicator] / [RippleText]）使用。
 *
 * 键与 [toolIcon] 一致（工具注册名，忽略大小写）。归类原则是「用户视角」而不是「实现视角」：
 * 读文件与列目录都算「正在读取文件」，联网搜索与抓网页都算「正在联网搜索」。工具名在执行前
 * 就已到达（`AgentEvent.ToolCallStarted`），所以文案能跟着实际动作走，而不必停在「正在思考」。
 * MCP 动态注册工具、自定义工具等未归类的，一律回落 [R.string.chat_status_calling_tool]。
 */
@StringRes
internal fun toolRunningLabelRes(toolName: String?): Int = when (toolName?.lowercase()) {
    "editfile", "writefile" -> R.string.chat_status_editing_file
    "readfile", "list", "sendfile" -> R.string.chat_status_reading_file
    "viewimage" -> R.string.chat_status_viewing_image
    "search", "websearch", "webfetch" -> R.string.chat_status_searching_web
    "browser" -> R.string.chat_status_browsing_web
    "bash", "terminal", "root", "shizuku" -> R.string.chat_status_running_command
    "askuserquestion" -> R.string.chat_status_asking_user
    "submitplan" -> R.string.plan_approval_waiting
    "generateimage" -> R.string.chat_status_generating_image
    "todo" -> R.string.chat_status_updating_todo
    "task" -> R.string.chat_status_starting_subagent
    "memory" -> R.string.chat_status_reading_memory
    else -> R.string.chat_status_calling_tool
}

/**
 * 工具是否仍在执行：实时输出中，或结果仍是 pending 占位。
 * [AIChatPanel] 的计时与失败统计复用相同判定。
 */
internal fun AgentUIMessage.isToolRunning(liveOutput: String?): Boolean =
    liveOutput != null ||
        content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

internal enum class ToolExecutionState { RUNNING, SUCCEEDED, FAILED }

internal fun toolExecutionState(running: Boolean, isError: Boolean): ToolExecutionState = when {
    running -> ToolExecutionState.RUNNING
    isError -> ToolExecutionState.FAILED
    else -> ToolExecutionState.SUCCEEDED
}

internal fun formatToolDuration(millis: Long): String {
    val elapsed = millis.coerceAtLeast(0L)
    if (elapsed < 100) return "<0.1s"
    if (elapsed >= 60_000) return formatTaskDuration(elapsed)
    val tenths = elapsed / 100
    return "${tenths / 10}.${tenths % 10}s"
}

internal data class ToolGroupSummary(
    val count: Int,
    val running: Boolean,
    val durationMs: Long?,
    val failureCount: Int,
)

internal fun toolElapsedMs(message: AgentUIMessage, startElapsedMs: Long?, nowElapsedMs: Long): Long? =
    message.toolDurationMs ?: startElapsedMs?.takeIf { message.isToolRunning(null) }?.let {
        (nowElapsedMs - it).coerceAtLeast(0L)
    }

internal fun toolGroupSummary(
    messages: List<AgentUIMessage>,
    turnDurationMs: Long?,
    runningIds: Set<String> = emptySet(),
): ToolGroupSummary {
    val running = messages.any { it.id in runningIds || it.isToolRunning(null) }
    return ToolGroupSummary(
        count = messages.size,
        running = running,
        durationMs = turnDurationMs,
        failureCount = messages.count { it.isError && it.id !in runningIds && !it.isToolRunning(null) },
    )
}

@Composable
internal fun toolGroupSummaryText(summary: ToolGroupSummary): String = stringResource(
    if (summary.running) R.string.chat_tool_group_summary_running else R.string.chat_tool_group_summary,
    summary.count,
    summary.durationMs?.let(::formatTaskDuration) ?: stringResource(R.string.chat_tool_group_duration_unknown),
    summary.failureCount,
)

/** 展开区的一段带小标题的内容块（如「指令」「结果」）：弱底等宽小面板，超出限高后在窗口内滚动。 */
@Composable
internal fun ToolSection(label: String, content: String, live: Boolean = false) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(2.dp))

    val scrollState = rememberScrollState()
    var followLive by remember { mutableStateOf(true) }
    var displayedContent by remember { mutableStateOf(content) }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLive = false
        }
    }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (!scrolling && !canScrollForward) followLive = true
            }
    }
    LaunchedEffect(content, live, followLive, scrollState.isScrollInProgress) {
        if (!live || (followLive && !scrollState.isScrollInProgress)) displayedContent = content
    }
    val fadeColor = MaterialTheme.colorScheme.background
    Box {
        ChatMonoPanel(
            modifier = Modifier
                .heightIn(max = ToolSectionMaxHeight)
                .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = if (live) displayedContent else content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        // 底部渐隐：只有还能继续往下滚时才盖一层，提示“下面还有内容”
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(ToolSectionFadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
            )
        }
    }
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 彩色行级差异视图
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
    val lines = remember(diff) { diff.split("\n") }
    val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(diff) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
    val removeCount = lines.count { it.startsWith("-") }
    val addCount = lines.count { it.startsWith("+") }
    val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
    val gutterChars = maxOf(2, maxLineNo.toString().length)
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 横向滚动容器在外层、文本选择在内层：滚动手势优先，
        // 避免 SelectionContainer 偶发抢占左右滑动（选择模式激活后拖动被选词消费）。
        // 底色与圆角由外层 ChatCodeCard 提供，这里只负责行渲染与横滑。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    var oldLineNo = startLine
                    var newLineNo = startLine
                    visibleLines.forEach { line ->
                        val marker = line.firstOrNull()
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val lineNo = when (marker) {
                            '-' -> oldLineNo++
                            '+' -> newLineNo++
                            else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                        }
                        val gutter = lineNo.toString().padStart(gutterChars)
                        val styled = buildAnnotatedString {
                            withStyle(SpanStyle(color = gutterColor)) {
                                append(gutter)
                                append("  ")
                            }
                            withStyle(SpanStyle(color = fg)) {
                                append(line.ifEmpty { " " })
                            }
                        }
                        Text(
                            text = styled,
                            style = mono,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        if (collapsible) {
            DiffExpandToggle(
                expanded = expanded,
                hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/** 长差异的页脚切换 */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 16.dp
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.tool_expand_remaining, hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** editFile 单处编辑的差异片段。 */
internal data class EditHunk(val startLine: Int, val diff: String)

/** editFile 结果中解析出的结构化差异 */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>
)

/**
 * 从持久化的 TOOL 内容中解析 editFile / writeFile 的结构化差异
 */
internal fun parseEditDiff(content: String): EditDiff? {
    val dataObj = extractToolDataObject(content)
    if (dataObj != null) {
        return parseEditDiffObject(dataObj)
    }

    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
    }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

    val hunks = obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d)
    } ?: run {
        val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
        listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
    }
    if (hunks.isEmpty()) return null
    return EditDiff(path = path, added = added, removed = removed, hunks = hunks)
}

/**
 * 把落库的原始工具结果清洗成可读文本
 */
internal fun formatToolResult(raw: String): String {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
            "success", "partial" -> formatToolData(obj["data"]) ?: s
            else -> s
        }
    }

    when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx >= 0) {
                var body = s.substring(msgIdx + "message=".length)
                val codeIdx = body.lastIndexOf(", code=")
                body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
                return body.trim()
            }
        }
        s.startsWith("Success(data=") -> {
            val inner = s.removePrefix("Success(data=").removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
        s.startsWith("Partial(data=") -> {
            var inner = s.removePrefix("Partial(data=")
            val msgIdx = inner.lastIndexOf(", message=")
            inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
    }
    return s
}

private fun parseToolTransport(raw: String): JsonObject? {
    return runCatching {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        if (obj["status"] != null) obj else null
    }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
    return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
    return when (data) {
        is JsonPrimitive -> data.contentOrNull ?: data.toString()
        is JsonObject -> {
            val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
            val mainStr = (main as? JsonPrimitive)?.contentOrNull
            mainStr ?: data.entries.joinToString("\n") { (k, v) ->
                val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                "$k: $vv"
            }
        }
        null -> null
        else -> data.toString()
    }
}

internal fun String.withoutToolStatusPrefix(): String = trim()
    .removePrefix(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
    .removePrefix(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    .removePrefix(SessionUseCase.PENDING_TOOL_MARKER)
    .trim()

/**
 * 把 `data=` 里的 JsonElement 文本渲染成可读结果
 */
internal fun formatJsonData(jsonStr: String): String? = runCatching {
    when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
    }
}.getOrNull()

private fun hasToolResult(raw: String): Boolean {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> (obj["message"]?.jsonPrimitive?.contentOrNull ?: s).isNotBlank()
            "success", "partial" -> hasToolData(obj["data"]) ?: s.isNotBlank()
            else -> s.isNotBlank()
        }
    }
    val inner = when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx < 0) return s.isNotBlank()
            val body = s.substring(msgIdx + "message=".length)
            val codeIdx = body.lastIndexOf(", code=")
            return (if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")).isNotBlank()
        }
        s.startsWith("Success(data=") -> s.removePrefix("Success(data=").removeSuffix(")")
        s.startsWith("Partial(data=") -> {
            val body = s.removePrefix("Partial(data=")
            val msgIdx = body.lastIndexOf(", message=")
            if (msgIdx >= 0) body.substring(0, msgIdx) else body.removeSuffix(")")
        }
        else -> return s.isNotBlank()
    }
    return runCatching {
        when (val data = Json.parseToJsonElement(inner.trim())) {
            is JsonPrimitive, is JsonObject -> hasToolData(data) ?: inner.isNotBlank()
            else -> inner.isNotBlank()
        }
    }.getOrElse { inner.isNotBlank() }
}

private fun hasToolData(data: JsonElement?): Boolean? = when (data) {
    is JsonPrimitive -> data.contentOrNull?.isNotBlank() ?: true
    is JsonObject -> {
        val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
        (main as? JsonPrimitive)?.contentOrNull?.isNotBlank() ?: data.isNotEmpty()
    }
    null -> null
    else -> true
}

private fun hasToolArgs(argsJson: String?): Boolean {
    if (argsJson.isNullOrBlank()) return false
    return runCatching { Json.parseToJsonElement(argsJson).jsonObject.isNotEmpty() }.getOrDefault(true)
}

/** 把传入参数 JSON 列成 `key: value` 多行 */
internal fun formatToolArgs(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        if (obj.isEmpty()) return null
        obj.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
        }
    }.getOrNull() ?: argsJson.trim()
}
