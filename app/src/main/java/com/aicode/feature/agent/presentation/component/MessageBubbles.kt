package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ContentWidth
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Database
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 工具卡片入场时长（ms）与上浮起点：略微下移再淡入到位，只走 draw 层不影响布局。 */
private const val MESSAGE_ENTRY_ANIM_MS = 260
private val MESSAGE_ENTRY_RISE = 10.dp

/** 一轮任务的划分结果：轮起点 + 轮内所有助手消息（1 轮 n 步）+ 轮末那条。 */
private data class AgentTurn(
    val startMillis: Long,
    val assistantMessages: List<AgentUIMessage>,
    val endMessage: AgentUIMessage
)

/**
 * 一轮任务内的 token 合计：轮内**每一步**（每次 LLM 调用）的输入/输出/缓存命中求和。
 * 与 [computeTaskDurations] 同挂在轮末那条助手消息下，见 [computeTurnUsage]。
 */
internal data class TurnUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * 把消息按「用户消息 → 下一个用户消息之前」切成轮。
 *
 * 轮末判定：其后第一条消息是用户消息，或它就是列表末条且本轮已结束（[lastTurnFinished]，
 * 由 agent 是否空闲给出）。仍在生成中的末轮不计入——耗时与用量都要等本轮收工才成立。
 *
 * 上下文压缩插入的锚点/摘要落在轮内（压缩发生在请求前），若参与划分会把轮起点算到压缩
 * 时刻上，故先剔除。
 */
private fun splitTurns(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean,
    allowMeasuredLeading: Boolean = false
): List<AgentTurn> {
    var inputOwner: String? = null
    val turnMessages = messages.filter {
        !it.isCompactionMarker && !it.isContextSummary && !it.isCompactionFailure && !it.isBackgroundNotification
    }.filter { message ->
        if (message.role != MessageRole.USER) true
        else {
            val continuation = message.continuationOfMessageId
            val continuesCurrent = continuation != null && continuation == inputOwner
            inputOwner = continuation ?: message.id
            !continuesCurrent
        }
    }
    if (turnMessages.isEmpty()) return emptyList()
    val turns = mutableListOf<AgentTurn>()
    var turnStart: Long? = null
    var assistants = mutableListOf<AgentUIMessage>()
    turnMessages.forEachIndexed { index, message ->
        when (message.role) {
            MessageRole.USER -> {
                turnStart = message.timestamp
                assistants = mutableListOf()
            }
            MessageRole.ASSISTANT -> {
                val start = turnStart ?: message.timestamp.takeIf { allowMeasuredLeading && message.turnDurationMs != null } ?: return@forEachIndexed
                assistants += message
                val isTurnEnd = if (index == turnMessages.lastIndex) {
                    lastTurnFinished
                } else {
                    turnMessages[index + 1].role == MessageRole.USER
                }
                if (isTurnEnd) {
                    turns += AgentTurn(start, assistants.toList(), message)
                    assistants = mutableListOf()
                }
            }
            MessageRole.TOOL -> Unit
        }
    }
    return turns
}

/**
 * 本轮单调时钟实测总耗时；旧数据缺少快照时不从排序时间推算。
 */
internal fun computeTaskDurations(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, Long> = splitTurns(messages, lastTurnFinished, allowMeasuredLeading = true)
    .mapNotNull { turn -> turn.endMessage.turnDurationMs?.takeIf { it >= 0L }?.let { turn.endMessage.id to it } }
    .toMap()

/**
 * 每轮任务的 token 合计：轮内**所有步骤**的输入/输出/缓存命中求和，返回「消息 id → [TurnUsage]」，
 * 同样只有轮末的那条助手消息才有条目。
 *
 * 口径是「这一轮总共花了多少」，不是「这一步花了多少」：一轮里可能调了 n 次模型（工具循环），
 * 逐步显示会把一次任务的开销拆成碎片，中间步骤的数字对用户也没有意义。
 */
internal fun computeTurnUsage(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, TurnUsage> = splitTurns(messages, lastTurnFinished).associate { turn ->
    turn.endMessage.id to TurnUsage(
        inputTokens = turn.assistantMessages.sumOf { it.inputTokens },
        outputTokens = turn.assistantMessages.sumOf { it.outputTokens },
        cachedInputTokens = turn.assistantMessages.sumOf { it.cachedInputTokens }
    )
}

/** 任务耗时格式化：不足 1 分钟显示 `12s`，不足 1 小时显示 `2:05`，更长显示 `1:02:05`。 */
internal fun formatTaskDuration(millis: Long): String {
    val totalSeconds = ((millis + 500) / 1000).coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return when {
        hours > 0 -> "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
        minutes > 0 -> "$minutes:$paddedSeconds"
        else -> "${seconds}s"
    }
}

/**
 * 单条消息的缓存命中率：命中缓存的输入 / 总输入（inputTokens 含缓存命中部分），与设置页 Token 统计同口径。
 * 无输入统计或本次未命中缓存时返回 null（不占位，避免把「渠道不报缓存数据」误示为 0% 命中）。
 */
internal fun formatCacheHitRate(inputTokens: Int, cachedInputTokens: Int): String? {
    if (inputTokens <= 0 || cachedInputTokens <= 0) return null
    val rate = (cachedInputTokens * 100.0 / inputTokens).coerceAtMost(100.0)
    return "${rate.roundToInt()}%"
}

internal enum class MessageContextUsageLevel { LOW, MODERATE, HIGH }

internal data class MessageContextUsage(
    val fraction: Double,
    val percentage: Long,
    val estimated: Boolean
) {
    val ringProgress: Float get() = fraction.coerceIn(0.0, 1.0).toFloat()
    val level: MessageContextUsageLevel
        get() = when {
            fraction < 0.5 -> MessageContextUsageLevel.LOW
            fraction < 0.85 -> MessageContextUsageLevel.MODERATE
            else -> MessageContextUsageLevel.HIGH
        }
}

internal fun messageContextUsage(message: AgentUIMessage): MessageContextUsage? {
    val inputTokens = message.contextInputTokens ?: return null
    val inputBudget = message.contextInputBudget ?: return null
    if (inputTokens < 0 || inputBudget <= 0) return null
    return MessageContextUsage(
        fraction = inputTokens.toDouble() / inputBudget,
        percentage = inputTokens.toLong() * 100 / inputBudget,
        estimated = message.contextEstimated
    )
}

internal fun messageCompletionVisible(
    message: AgentUIMessage,
    playbackSettled: Boolean,
    content: String,
    renderedContent: String?,
): Boolean = message.role != MessageRole.ASSISTANT ||
    (playbackSettled && (!message.content.hasVisibleContent() || renderedContent == content))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    /** 是否为已结束轮次的最终回复（挂「复制 / 更多」）。用户消息不受此限：每条都常驻
     *  这一排按钮，随时能复制或回退自己发的话。时间戳与用量、耗时属于信息，不受它控制。 */
    showActions: Boolean = false,
    playbackSettled: Boolean = true,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onRewindClick: ((String) -> Unit)? = null,
    onMoreClick: ((AgentUIMessage) -> Unit)? = null,
    onToolToggle: (() -> Unit)? = null,
    /** 工具行展开态的持久化覆盖（null = 尚未手动开关过，按内容类型取默认）；见 [ToolMessageBody]。 */
    toolExpandedOverride: Boolean? = null,
    /** 工具行手动展开/收起时回传新状态，由上层持久化。 */
    onToolExpandedChange: ((Boolean) -> Unit)? = null,
    reasoningExpandedOverride: Boolean? = null,
    onReasoningExpandedChange: ((Boolean) -> Unit)? = null,
    /** 本轮任务总耗时（ms）：仅轮末助手消息非空，见 [computeTaskDurations]。 */
    taskDurationMs: Long? = null,
    /** 本轮任务的 token 合计（1 轮 n 步求和）：仅轮末助手消息非空，见 [computeTurnUsage]。 */
    turnUsage: TurnUsage? = null,
    /** 新消息入场动画延迟（ms）：null 表示历史消息直接显示；非 null 时首次组合延迟后淡入展开。 */
    entryDelayMs: Long? = null,
    /** 长消息分块渲染：非 null 时正文 MarkdownContent 只渲染该片段。
     *  分块之间气泡无缝衔接（首块带思考、末块带操作行与底部圆角），复制按钮仍复制整条 message.content。 */
    contentSlice: String? = null,
    /** 是否为分块的首块（渲染思考块、顶部圆角）；非分块消息恒为 true。 */
    isChunkHeader: Boolean = true,
    /** 是否渲染本条消息自带的思考块。默认 true；整轮折叠把思考抽出为独立过程项时传 false，避免重复。 */
    reasoningVisible: Boolean = true,
    /** 思考过程耗时（ms）：由上层记录的思考实际耗时。 */
    reasoningDurationMs: Long? = null,
    /** 是否为分块的末块（渲染操作行、底部圆角、与下一条列表 item 的间距）；非分块消息恒为 true。 */
    isChunkFooter: Boolean = true,
    onCompactContext: (() -> Unit)? = null,
    contextCompactionEnabled: Boolean = true,
    toolDurationMs: Long? = null,
    onToolPreview: (() -> Unit)? = null,
) {
    if (message.isCompactionMarker) {
        // 压缩内部锚点不再渲染分隔线：摘要卡片已提供压缩反馈，避免与卡片重复。
        return
    }

    if (message.isContextSummary) {
        CompactionSummaryCard(message, markdownCache)
        return
    }

    if (message.isCompactionFailure) {
        CompactionFailureCard(message)
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }
    if (message.continuationOfMessageId != null) {
        var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(stringResource(R.string.session_recovery_continuation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.continuationDraft?.takeIf { it.isNotBlank() }?.let { draft ->
                Text(stringResource(R.string.session_recovery_draft_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.warning)
                SelectionContainer {
                    Text(draft, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(if (expanded) R.string.common_collapse_action else R.string.common_expand),
                    modifier = Modifier.clickable { expanded = !expanded }.padding(Spacing.sm),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }

    val hasReasoning = message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    // 模型直出的图片走附件落库，纯图消息没有正文与思考，同样要展示（不能提前 return）。
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning && !hasAttachments) return

    val isUser = message.role == MessageRole.USER
    val displayedContent = contentSlice ?: message.content
    var renderedContent by remember(message.id) { mutableStateOf<String?>(null) }
    val completionVisible = messageCompletionVisible(message, playbackSettled, displayedContent, renderedContent)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 用户气泡随文字撑开，最大撑到与 AI 气泡同宽（消息列宽 - 列表两侧 padding）。
    // 大屏下消息列已限宽居中，气泡上限跟着收窄，不能再拿整个屏宽算。
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    var copied by remember { mutableStateOf(false) }
    var contextDetailsVisible by remember(message.id) { mutableStateOf(false) }
    if (contextDetailsVisible) {
        ContextUsageSheet(
            message = message,
            onDismissRequest = { contextDetailsVisible = false },
            onCompactContext = onCompactContext,
            contextCompactionEnabled = contextCompactionEnabled
        )
    }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // 工具卡片入场：只对本次浏览期间新追加进来的消息播（entryDelayMs 非空），
    // 历史、切页返回、item 回收重挂载都直接显示（谁该入场由 MessageEntryScheduler 定）。
    // 入场只改 alpha 与 translationY（draw 阶段生效），卡片高度从插入那一帧就到位：
    // 用 AnimatedVisibility 的话未入场的卡片完全不占位，每张卡片入场都让列表高度跳一次，
    // 与贴底跟随的 scrollToItem 叠加就是一连串抖动——而那正是这个动画本来要消除的。
    // 现在列表高度只在消息插入时变一次，之后动画全程不碰布局。
    var entered by rememberSaveable(message.id) { mutableStateOf(entryDelayMs == null) }
    LaunchedEffect(message.id) {
        if (entered) return@LaunchedEffect
        val delayMs = entryDelayMs ?: return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = MESSAGE_ENTRY_ANIM_MS, easing = LinearOutSlowInEasing),
        label = "tool-entry"
    )

    // 超长助手消息由 AIChatPanel 拆成多条有界 item（拆块）渲染，这里不再做任何限高内滚；
    // 每条分块都视为普通消息的一段：正文按块渲染、思考只在首块、操作行只在末块。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // LazyColumn 不再统一 spacedBy：末块（或非分块消息）自带与下一条 item 的间距，
            // 相邻分块之间零间距无缝衔接，整段长回复在外观上仍是连续的一整段。
            // 扁平文档流下正文之间没有气泡边框兜底，紧凑排布分清「轮次」。
            .padding(bottom = if (isChunkFooter) Spacing.sm else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning && isChunkHeader && reasoningVisible) {
            // 思考默认收起：折叠行只占一行（显示思考的第一行），要看全文手动点开
            ReasoningBubble(
                text = message.reasoning.orEmpty(),
                cache = markdownCache,
                durationMs = reasoningDurationMs,
                expandedOverride = reasoningExpandedOverride,
                onExpandedChange = onReasoningExpandedChange
            )
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // 助手消息左对齐，用户消息右对齐
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.role == MessageRole.TOOL) {
                            // 入场动画只改 draw 层，避免高度变化干扰贴底。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = entryProgress
                                        translationY = (1f - entryProgress) * MESSAGE_ENTRY_RISE.toPx()
                                    }
                            ) {
                                ToolMessageBody(
                                    message = message,
                                    liveOutput = liveOutput,
                                    expandedOverride = toolExpandedOverride,
                                    onExpandedChange = onToolExpandedChange,
                                    onToggle = onToolToggle,
                                    durationMs = toolDurationMs,
                                    onOpenPreview = onToolPreview
                                )
                            }
                        } else if (isUser) {
                            UserMessageBubble(
                                text = message.content,
                                maxWidth = maxUserBubbleWidth
                            )
                        } else {
                            AssistantMessageBubble(
                                text = displayedContent,
                                cache = markdownCache,
                                isChunkHeader = isChunkHeader,
                                isChunkFooter = isChunkFooter,
                                onRendered = { renderedContent = it },
                            )
                        }
                    }
                }
                if ((isUser || message.role == MessageRole.ASSISTANT) && hasAttachments) {
                    MessageAttachmentList(attachments = message.attachments)
                }
                // 气泡下方的元信息行（工具消息不显示）：用户消息每条都常驻「时间 + 复制/回退/更多」，
                // 助手消息只挂已结束轮次的最终回复。
                // 排列固定为「统计（用量/缓存/耗时）→ 复制 → 上下文 → 更多选项」：上下文读取本条消息的快照。
                //
                // 用量与耗时按**本轮合计**（1 轮 n 步的所有调用求和）挂在轮末那条助手消息上：
                // 逐步显示会把一次任务的开销拆成碎片，中间步骤的数字对用户也没有意义。
                val tokenStats = turnUsage
                    ?.takeIf { it.inputTokens > 0 || it.outputTokens > 0 }
                    ?.let {
                        val inStr = formatTokenCount(it.inputTokens.toLong())
                        val outStr = formatTokenCount(it.outputTokens.toLong())
                        "↑$inStr ↓$outStr"
                    }
                val cacheHitRate = turnUsage?.let {
                    formatCacheHitRate(it.inputTokens, it.cachedInputTokens)
                }
                val durationText = taskDurationMs?.let { formatTaskDuration(it) }
                val contextUsageVisible = message.role == MessageRole.ASSISTANT &&
                    (turnUsage != null || taskDurationMs != null || showActions)
                val hasMeta = isUser || tokenStats != null || cacheHitRate != null || durationText != null || contextUsageVisible
                val actionsVisible = isUser || showActions
                if ((hasContent || hasAttachments) && message.role != MessageRole.TOOL && isChunkFooter &&
                    completionVisible && (actionsVisible || hasMeta)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            Spacing.sm,
                            if (isUser) Alignment.End else Alignment.Start
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        val copyButton: @Composable () -> Unit = {
                            MessageActionIconButton(
                                icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                                contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                                tint = iconTint,
                                onClick = {
                                    copyScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("message", message.content))
                                        )
                                        copied = true
                                    }
                                }
                            )
                        }
                        if (isUser) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChatMetaText(text = formatClockTime(message.timestamp))
                                if (actionsVisible) {
                                    Spacer(Modifier.width(Spacing.xs))
                                    if (hasContent) copyButton()
                                    if (onRewindClick != null) {
                                        MessageActionIconButton(
                                            icon = FeatherIcons.RotateCcw,
                                            contentDescription = stringResource(R.string.checkpoint_rewind_title),
                                            tint = iconTint,
                                            onClick = { onRewindClick(message.id) }
                                        )
                                    }
                                    if (onMoreClick != null) {
                                        Spacer(Modifier.width(Spacing.xs))
                                        MessageActionIconButton(
                                            icon = FeatherIcons.MoreHorizontal,
                                            contentDescription = stringResource(R.string.chat_more_options),
                                            tint = iconTint,
                                            onClick = { onMoreClick(message) }
                                        )
                                    }
                                }
                            }
                        }
                        if (tokenStats != null) {
                            ChatMetaText(text = tokenStats, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                        if (cacheHitRate != null) {
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    FeatherIcons.Database,
                                    contentDescription = stringResource(R.string.chat_cache_hit_rate, cacheHitRate),
                                    tint = iconTint,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                ChatMetaText(text = cacheHitRate)
                            }
                        }
                        if (durationText != null) {
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    FeatherIcons.Clock,
                                    contentDescription = stringResource(R.string.chat_task_duration, durationText),
                                    tint = iconTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                ChatMetaText(text = durationText)
                            }
                        }
                        if (!isUser && (actionsVisible || contextUsageVisible)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                if (actionsVisible && hasContent) copyButton()
                                if (contextUsageVisible) {
                                    MessageContextUsageIndicator(
                                        message = message,
                                        onClick = { contextDetailsVisible = true }
                                    )
                                }
                                if (actionsVisible && onMoreClick != null) {
                                    MessageActionIconButton(
                                        icon = FeatherIcons.MoreHorizontal,
                                        contentDescription = stringResource(R.string.chat_more_options),
                                        tint = iconTint,
                                        onClick = { onMoreClick(message) }
                                    )
                                }
                            }
                        }
                    }
                    // 复制成功 1.5s 后恢复图标
                    if (copied) {
                        LaunchedEffect(copied) {
                            delay(1500)
                            copied = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageContextUsageIndicator(
    message: AgentUIMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val usage = messageContextUsage(message)
    val description = when {
        usage == null -> stringResource(R.string.chat_context_usage_unknown)
        usage.estimated -> stringResource(R.string.chat_context_usage_estimated, usage.percentage)
        else -> stringResource(R.string.chat_context_usage, usage.percentage)
    }
    val tint = when (usage?.level) {
        MessageContextUsageLevel.LOW -> MaterialTheme.semanticColors.success
        MessageContextUsageLevel.MODERATE -> MaterialTheme.semanticColors.warning
        MessageContextUsageLevel.HIGH -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ChatStyle.panelCorner))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.chat_context_details),
                onClick = onClick
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Canvas(modifier = Modifier.size(ChatStyle.rowIconSize)) {
            val strokeWidth = 2.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            drawCircle(
                color = trackColor,
                radius = diameter / 2f,
                style = Stroke(width = strokeWidth)
            )
            if (usage != null) {
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 360f * usage.ringProgress,
                    useCenter = false,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = if (usage == null) "—%" else "${if (usage.estimated) "≈" else ""}${usage.percentage}%",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}


@Composable
internal fun TurnCollapseHeader(
    running: Boolean,
    durationMs: Long?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs)
            .heightIn(min = Spacing.xl * 2)
            .clip(RoundedCornerShape(Radius.pill))
            .background(chatMutedSurfaceColor())
            .border(ChatStyle.hairline, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.pill))
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (summary != null) {
            Icon(
                imageVector = FeatherIcons.Tool,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ChatStyle.rowIconSize)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            text = summary ?: when {
                running -> stringResource(R.string.chat_turn_running)
                durationMs != null -> stringResource(R.string.chat_turn_completed, formatTaskDuration(durationMs))
                // 拿不到耗时（被窗口截断的半轮、消息缺时间戳）时只说「已完成」，不留空档
                else -> stringResource(R.string.chat_turn_completed_plain)
            },
            modifier = Modifier.weight(1f, fill = false),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Spacing.xs))
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(24.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
    }
}

/**
 * 后台任务完成通知的轻量提示条：不作为普通用户气泡展示，仅以紧凑横条形式告知用户
 * 哪个后台命令结束了、成功与否。从通知文本里提取 <status>/<summary> 字段。
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val notices = parseSubAgentNotificationMetadata(message.content)
    val remaining = unrepresentedSubAgentNotifications(notices, LocalRepresentedSubAgentIds.current)
    if (remaining.isEmpty()) return
    val content = remaining.joinToString("\n") { it.raw }
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val dotColor = when {
        // status 为 message 的是代理间消息（非失败），用主色；其余非 completed 视为失败。
        statuses.any { it != "completed" && it != "message" } -> MaterialTheme.colorScheme.error
        statuses.any { it == "message" } -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.success
    }
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            // 摘要原文由 domain 层生成（同一份还要喂给 AI），这里不按中文标记切字符串取任务名——
            // 换文案或切到英文界面这段解析就废了。多条时只报数量与失败数。
            val failedCount = statuses.count { it != "completed" }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.scaledForChat(LocalChatTextScale.current),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 上下文压缩成功行：默认折叠为「圆点 + 上下文已压缩 + 箭头」，点击展开查看摘要全文。
 * 扁平行 + 弱底面板，与工具行同构，只用圆点颜色区分事件类型。
 */
@Composable
private fun CompactionSummaryCard(message: AgentUIMessage, markdownCache: MarkdownRenderCache?) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ChatStyle.panelCorner))
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_context_compressed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && message.content.hasVisibleContent()) {
                Spacer(Modifier.height(Spacing.xs))
                MarkdownContent(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    cache = markdownCache
                )
            }
        }
    }
}

/**
 * 上下文压缩失败行：默认折叠为「圆点 + 压缩失败 + 原因首行 + 箭头」，点击展开查看完整原因。
 * 与工具行同构的扁平行，用 error 色圆点区分。
 */
@Composable
private fun CompactionFailureCard(message: AgentUIMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reason = message.content.ifBlank { stringResource(R.string.chat_compaction_failed) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ChatStyle.panelCorner))
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_compaction_failed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!expanded) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = reason.replace("\n", " ").trim(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.scaledForChat(LocalChatTextScale.current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                ChatMonoPanel {
                    Text(
                        text = reason,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            .scaledForChat(LocalChatTextScale.current)
                    )
                }
            }
        }
    }
}
