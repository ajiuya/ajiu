package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.feature.filepreview.domain.PreviewRequest
import com.aicode.feature.filepreview.presentation.FilePreviewHost
import com.aicode.feature.filepreview.presentation.PreviewUriHandler
import com.aicode.feature.filepreview.presentation.rememberFilePreviewState
import com.aicode.feature.filepreview.presentation.attachmentUsesPlayer
import com.aicode.feature.player.presentation.PlayerActivity
import com.aicode.feature.player.domain.PlayerMediaTypes
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppMotion
import com.aicode.core.ui.ImageViewerHost
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.readableContentMaxWidth
import com.aicode.core.ui.rememberImageViewerState
import com.aicode.core.ui.rememberViewerDecodeSpec
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.agent.presentation.WorkflowStatus
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository.Companion.DEFAULT_TYPEWRITER_CHARS_PER_SECOND
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import com.aicode.feature.agent.presentation.preview.ToolPreviewHost
import com.aicode.feature.agent.presentation.preview.ToolPreviewHostState
import com.aicode.feature.agent.presentation.preview.ToolPreviewWorkVisibility
import com.aicode.feature.agent.presentation.preview.UiWorkEffect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.aicode.feature.agent.presentation.preview.rememberToolPreviewHostState
import android.os.SystemClock
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

internal fun shouldShowToolPreparation(
    preparingTool: String?,
    isBusy: Boolean,
    isCompacting: Boolean,
    awaitingUserInput: Boolean,
): Boolean = preparingTool != null && isBusy && !isCompacting && !awaitingUserInput

/** 悬浮层（横幅/面板/输入框）与最后一条消息的间距。 */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** 内容底部驱动校准的容差（px）：最后内容底部超出安全区超过该值才向下校准，避免亚像素抖动。 */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** 用同一次测量的留白与末项坐标校准；逐字增长不能反复重置列表的首项锚点。 */
internal suspend fun LazyListState.followChatBottom(tolerancePx: Int = AUTO_SCROLL_TOLERANCE_PX) {
    if (!canScrollForward) return
    var layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return
    if (lastVisible.index < layout.totalItemsCount - 1) {
        // 新增的末项尚未测到高度：先将其起点放到安全区下沿，再按实际高度补齐。
        val anchor = (layout.viewportEndOffset - layout.afterContentPadding)
            .coerceIn(0, (layout.viewportEndOffset - 1).coerceAtLeast(0))
        scrollToItem(layout.totalItemsCount - 1, -anchor)
        layout = layoutInfo
    }
    val tail = layout.visibleItemsInfo.lastOrNull() ?: return
    if (tail.index != layout.totalItemsCount - 1) return
    val overflow = tail.offset.toLong() + tail.size - (layout.viewportEndOffset - layout.afterContentPadding)
    if (overflow > tolerancePx && canScrollForward) scrollBy(overflow.toFloat())
}

/** 滚动到底部按钮直径（dp）。 */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** 连续新消息的入场错开间隔（ms）与总上限：一次插入很多卡片时不能让最后一张等好几秒。 */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** 消息未就绪时延迟多久才显示加载提示（ms）：本地读库很快，立即显示反而闪。 */
private const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * 长消息拆块渲染：
 *
 * 超长助手正文（长文档复述）如果塞成单条 LazyColumn item，撑到几屏深时手势命中区
 * 会在列表可见区之外，深处表格的横滑/长按复制/点击全部失效（原版深处交互失效的根因）。
 * 限高+内滚的单窗口方案又会让窗口与列表世界之间出现「接不上、独立一块」的断接。
 *
 * 拆块的思路是：分裂成多条「有界高度」的列表 item，每条都是普通气泡（思考只在首块、
 * 操作行只在末块、相邻块零间距无缝衔接），列表单一滚动轴——世界上下完全接通、深处
 * 交互（表 8+ 横滑/复制/点击）因每条 item 高度有界而全部恢复。
 *
 * 若正文长度不超过阈值，不拆块，与普通消息完全一致。
 *
 * internal（而非 private）是为了让 [buildChatItems] 的用例能断言渲染项，见 ToolGroupExpansionTest。
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    /** 分块首字符在持久化原文中的 UTF-16 偏移。 */
    val sliceStart: Int = 0,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /** 整轮过程状态；运行中的轮在展平时只保留过程项。 */
    val turnHeader: TurnHeaderState? = null,
    /** 轮头携带的过程项，随后转换为独立列表项。 */
    val turnProcess: List<ChatRenderItem> = emptyList(),
    /** 是否渲染消息自带的思考块；结果块把思考抽为过程项后传 false。 */
    val reasoningVisible: Boolean = true,
    val chatTurnKey: String? = null,
    val subAgentCapsule: SubAgentTimelineCapsule? = null,
    val subAgentCarrierMessageIds: Set<String> = emptySet(),
)

private data class ChatMetadataProjection(
    val items: List<ChatRenderItem>,
    val turnKeys: Map<String, String>,
    val collapsedGroups: SubAgentGroupProjection,
    val statusRanks: Map<String, Int>,
    val messageIds: Set<String>,
    val taskDurations: Map<String, Long>,
    val turnUsages: Map<String, TurnUsage>,
    val actionableMessageIds: Set<String>,
    val toolsByTurn: Map<String?, List<AgentUIMessage>>,
)

private data class ChatGroupDisplay(
    val metadata: ChatMetadataProjection,
    val projection: SubAgentGroupProjection,
)

/** 整轮折叠头 item 的 contentType。 */
private const val TURN_HEADER_CONTENT_TYPE = "turn-header"

/** 整轮展开时，被抽为独立过程项的「轮末助手思考」的 contentType。 */
private const val REASONING_CONTENT_TYPE = "reasoning"
private const val TOOL_ATTACHMENTS_CONTENT_TYPE = "tool-attachments"

/**
 * 回滚冲突提示里最多列出几个文件路径。Toast 空间有限，超出部分折叠成「另有 N 个」，
 * 完整清单仍可从日志查看。
 */
private const val MAX_REWIND_CONFLICT_LINES = 3

/** 轮首用户消息 id → 整轮折叠的持久化 key（与 ViewModel.turnExpansionOverrides 同口径）。 */
internal fun turnKeyOf(firstMessageId: String): String = "turn:$firstMessageId"

/**
 * 被窗口截断、找不到轮首的那一截过程（见 [buildChatItems]）的折叠头 key。
 *
 * 取块尾消息 id 而非块首：窗口继续向前补加载时新消息插在块首，块尾不变、key 保持稳定。
 */
internal fun leadingHeadKey(lastMessage: AgentUIMessage): String = "turn:head:${lastMessage.id}"

/**
 * 整轮过程状态；仅结束后渲染折叠头。
 *
 * [endMessageId] 是轮末助手消息 id，据此查本轮耗时（见 [computeTaskDurations]，口径一致）。
 */
internal data class TurnHeaderState(
    val key: String,
    val endMessageId: String?,
    /** 该轮是否仍在执行（末轮且 agent 忙）。 */
    val running: Boolean,
    /** 展开态（已含用户手动覆盖的结果）。 */
    val expanded: Boolean,
)

/**
 * 该助手消息是否会渲染出「气泡下方的元信息行」——即能不能承接那排「复制 / 更多」按钮。
 *
 * 压缩标记、上下文摘要、压缩失败、后台通知条在 [AgentMessageItem] 里各有专用渲染分支并提前
 * return，不产生这一行；纯思考无正文的助手消息同理（没有正文可复制）。把「最新一条」的按钮
 * 挂到它们身上，整段会话就一个按钮都不剩。
 */
private fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

internal fun actionableChatMessageIds(messages: List<AgentUIMessage>, isBusy: Boolean): Set<String> {
    val (leading, turns) = splitChatTurns(messages)
    val ids = leading.filter { it.rendersActionRow() }.mapTo(hashSetOf()) { it.id }
    for (index in turns.indices) {
        if (index == turns.lastIndex && isBusy) continue
        val turn = turns[index]
        val result = turn.messages.lastOrNull { it.isResultCandidate() && it.rendersActionRow() }
            ?: turn.messages.lastOrNull { it.rendersActionRow() }
        result?.let { ids += it.id }
    }
    return ids
}

/**
 * 单条消息（非工具分组）在一次渲染中占据的 item：
 * 超长助手正文拆成多条有界 chunk，其余消息 1:1。
 */
private fun messageRenderItems(message: AgentUIMessage): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // 只拆出一块（如无空行的超长单段）：等同普通消息。
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    var offset = 0
    return slices.mapIndexed { idx, slice ->
        val start = offset
        offset += slice.length
        ChatRenderItem(
            message = message,
            key = "${message.id}#chunk$idx",
            contentType = "assistant-chunk",
            slice = slice,
            sliceStart = start,
            isChunkHeader = idx == 0,
            isChunkFooter = idx == slices.lastIndex,
        )
    }
}

internal fun findChatMessageIndex(items: List<ChatRenderItem>, messageId: String): Int =
    items.indexOfFirst { it.key == messageId }.takeIf { it >= 0 }
        ?: items.indexOfFirst { it.message.id == messageId && it.contentType != REASONING_CONTENT_TYPE }

internal fun insertSubAgentTurnItems(
    items: List<ChatRenderItem>,
    capsules: List<SubAgentTurnCapsule>,
    messages: List<AgentUIMessage>
): List<ChatRenderItem> {
    if (capsules.isEmpty()) return items
    val messagesById = messages.associateBy { it.id }
    val byTurn = capsules.groupBy { it.turnKey }
    val lastIndexByTurn = items.withIndex().filter { it.value.chatTurnKey != null }
        .associate { it.value.chatTurnKey to it.index }
    return buildList {
        items.forEachIndexed { index, item ->
            add(item)
            if (lastIndexByTurn[item.chatTurnKey] == index) {
                byTurn[item.chatTurnKey].orEmpty().forEach { turn ->
                    val message = messagesById[turn.capsule.anchorMessageId] ?: return@forEach
                    add(ChatRenderItem(
                        message = message, key = turn.key, contentType = SUB_AGENT_CAPSULE_CONTENT_TYPE,
                        chatTurnKey = turn.turnKey, subAgentCapsule = turn.capsule,
                        subAgentCarrierMessageIds = turn.carrierMessageIds
                    ))
                }
            }
        }
    }
}

internal fun reconcileTurnKeys(
    messages: List<AgentUIMessage>,
    previousKeys: Map<String, String>,
    sessionId: String?,
): Map<String, String> = buildMap {
    val (leading, turns) = splitChatTurns(messages)
    if (leading.isNotEmpty()) {
        val key = leading.asReversed().firstNotNullOfOrNull { previousKeys[it.id] }
            ?: if (turns.isEmpty() && sessionId != null) {
                "turn:head:session:$sessionId"
            } else {
                leadingHeadKey(leading.last())
            }
        leading.forEach { put(it.id, key) }
    }
    for (turn in turns) {
        // 分页补齐轮首时，沿用已显示的半轮标识，保留展开选择与列表锚点。
        val key = previousKeys[turn.userMessage.id]
            ?: turn.messages.firstNotNullOfOrNull { previousKeys[it.id] }
            ?: turn.key
        put(turn.userMessage.id, key)
        turn.messages.forEach { put(it.id, key) }
    }
}

/**
 * 消息列表 → LazyColumn item 列表。
 *
 * 整轮任务折叠与长文拆块；过程里的工具始终各占一个列表项。
 *
 * 先按「用户消息 → 下一个用户消息之前」切成轮（见 [splitChatTurns]）：每轮依次产出「用户消息 →
 * 轮头（携带过程项，仅当该轮有过程或正在运行）→ 常显项与结果」。过程项先归入轮头，展开时转换为
 * 独立列表项；附件入口常显（见 [isPersistentInTurn]），后台通知与压缩卡片属过程性提示、
 * 随过程折叠。
 * 结果 = 轮内最后一条有可见正文的普通助手消息（其思考抽为过程项）。
 *
 * 轮首之前剩下的散消息同样按「一轮过程」处理：消息按条数截断加载，窗口边界常切在某一轮中间，
 * 这一段缺的是轮首用户消息而不是折叠头——原样常显会在历史最前面摊开一整轮过程。它们补一个不带
 * 耗时的折叠头（key 见 [leadingHeadKey]），默认收起。
 *
 * 运行轮保持列表展开，结束后默认收起；历史过程可通过 [turnOverrides] 手动展开。
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    turnOverrides: Map<String, Boolean> = emptyMap(),
    activeTurnKey: String? = null,
    sessionId: String? = null,
    turnKeys: Map<String, String> = emptyMap(),
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    val (leading, turns) = splitChatTurns(messages)
    if (leading.isNotEmpty()) {
        // 没有轮首时，分页窗口两端都可能随工具输出移动，只能用所属会话保持展开标识稳定。
        val headKey = turnKeys[leading.last().id] ?: if (turns.isEmpty() && sessionId != null) {
            "turn:head:session:$sessionId"
        } else {
            leadingHeadKey(leading.last())
        }
        // 这一截通常结尾就是上一轮的最终回复（窗口从某一轮中间切开）：它照旧常显，
        // 只有它的思考与更早的过程步骤折进折叠头，与完整轮次的呈现一致。
        val running = headKey == activeTurnKey
        val resultId = if (running) null else leading.last().takeIf { it.isResultCandidate() }?.id
        val expanded = running || turnOverrides[headKey] == true
        val process = buildTurnProcessItems(leading, resultId)
        if (process.isNotEmpty()) {
            items += ChatRenderItem(
                message = leading.last(),
                key = "$headKey#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = headKey,
                    endMessageId = resultId,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
        if (!running) items += buildTurnPersistentItems(leading, resultId, expanded)
    }
    for (turn in turns) {
        items += messageRenderItems(turn.userMessage)
        val endAssistant = turn.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.isSystemCardInTurn()
        }
        val stableTurnKey = turnKeys[turn.userMessage.id] ?: turn.key
        val running = stableTurnKey == activeTurnKey
        val resultId = if (running) null else turn.messages.lastOrNull { it.isResultCandidate() }?.id
        // 过程与常显项分开构建，展开时由主列表逐项组合。
        val process = buildTurnProcessItems(turn.messages, resultId)
        val expanded = running || turnOverrides[stableTurnKey] == true
        if (process.isNotEmpty() || running) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "$stableTurnKey#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = stableTurnKey,
                    endMessageId = endAssistant?.id,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
        if (!running) items += buildTurnPersistentItems(turn.messages, resultId, expanded)
    }
    val resolvedKeys = if (turnKeys.isEmpty()) reconcileTurnKeys(messages, emptyMap(), sessionId) else turnKeys
    return items.map { item ->
        val turnKey = item.turnHeader?.key ?: resolvedKeys[item.message.id]
        item.copy(chatTurnKey = turnKey, turnProcess = item.turnProcess.map { it.copy(chatTurnKey = turnKey) })
    }
}

internal fun flattenChatProcesses(items: List<ChatRenderItem>): List<ChatRenderItem> = buildList {
    for (item in items) {
        if (item.turnHeader == null) {
            add(item)
            continue
        }
        if (!item.turnHeader.running) add(item.copy(turnProcess = emptyList()))
        if (item.turnHeader.expanded) addAll(item.turnProcess)
    }
}

/** 一轮任务：轮首用户消息 + 其后的全部消息（助手 / 工具 / 压缩卡片等）。 */
private class ChatTurn(
    val key: String,
    val userMessage: AgentUIMessage,
    val messages: List<AgentUIMessage>,
)

/**
 * 按「用户消息」切成轮。返回 (轮首之前的散消息, 轮列表)。
 *
 * 后台通知虽可能是 USER 角色，但它是系统注入的提示条而非用户输入，不作为轮起点（归入当前轮内容）。
 */
private fun splitChatTurns(messages: List<AgentUIMessage>): Pair<List<AgentUIMessage>, List<ChatTurn>> {
    val leading = ArrayList<AgentUIMessage>()
    val turns = ArrayList<ChatTurn>()
    var user: AgentUIMessage? = null
    var body: MutableList<AgentUIMessage>? = null
    for (message in messages) {
        val continuation = message.continuationOfMessageId
        val continuesCurrent = continuation != null &&
            (continuation == user?.id || continuation == user?.continuationOfMessageId)
        if (message.role == MessageRole.USER && !message.isBackgroundNotification && !message.isCompactionMarker && !continuesCurrent) {
            user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
            user = message
            body = ArrayList()
        } else {
            if (body == null) leading += message else body.add(message)
        }
    }
    user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
    return leading to turns
}

/**
 * 该消息在整轮折叠里是否「常显」——不参与过程折叠，无论轮展开与否都显示。
 *
 * 附件和模型直出的图片就是结果本身，随过程折叠会把产出物藏起来。后台通知与压缩卡片是过程性提示，
 * 随过程一起折叠（见 [isSystemCardInTurn]）。
 */
private fun AgentUIMessage.isPersistentInTurn(): Boolean =
    !continuationDraft.isNullOrBlank() || (role != MessageRole.USER && attachments.isNotEmpty())

/**
 * 系统注入的卡片型消息（后台通知条 / 压缩标记·摘要·失败）：折进轮过程，且不得被选为
 * 轮「结果」或轮末耗时锚点——压缩摘要是 ASSISTANT 角色，不排除会顶掉真正的回复正文。
 */
private fun AgentUIMessage.isSystemCardInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification || continuationOfMessageId != null

/** 能否作为一轮的「结果」：最后一条有可见正文的普通助手消息（系统卡片不算，见 [isSystemCardInTurn]）。 */
private fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isSystemCardInTurn() &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * 轮内「过程」项保持原序：思考、工具、中间助手正文、后台通知条与压缩卡片。
 * 挂在轮头 item 内一起展开。
 *
 * 结果消息的思考归过程；工具胶囊参与折叠，附件入口单独常显。
 */
private fun buildTurnProcessItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>(messages.size)
    for (message in messages) {
        when {
            message.role == MessageRole.TOOL && message.attachments.isNotEmpty() -> {
                out += messageRenderItems(message.copy(attachments = emptyList()))
                out += ChatRenderItem(
                    message = message,
                    key = "${message.id}#attachments",
                    contentType = TOOL_ATTACHMENTS_CONTENT_TYPE,
                )
            }
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    out += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
            }
            else -> out += messageRenderItems(message)
        }
    }
    return out
}

/** 轮内常显的最终回复与附件入口。 */
private fun buildTurnPersistentItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    processExpanded: Boolean,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>()
    for (message in messages) {
        if (message.id == resultId) {
            messageRenderItems(message).forEach { out += it.copy(reasoningVisible = false) }
        } else if (!processExpanded && message.role == MessageRole.TOOL && message.attachments.isNotEmpty()) {
            out += ChatRenderItem(
                message = message,
                key = "${message.id}#attachments",
                contentType = TOOL_ATTACHMENTS_CONTENT_TYPE,
            )
        } else if (!processExpanded && message.isPersistentInTurn()) {
            out += messageRenderItems(message)
        }
    }
    return out
}

/** 超过该长度（字符）的助手正文拆成多条有界 chunk。 */
private const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** 段落、表格按字符预算打包；超预算时只在行间拆分，代码围栏始终保持完整。 */
private const val CHUNK_BUDGET_CHARS = 1_200

/** 每个分块都是原文的连续区间：空行、CRLF、Markdown 硬换行和正在输入的行尾均不改写。 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val starts = IntArray(lines.size + 1)
    for (i in lines.indices) {
        var next = starts[i] + lines[i].length
        if (next < text.length && text[next] == '\r') next++
        if (next < text.length && text[next] == '\n') next++
        starts[i + 1] = next
    }
    val chunks = ArrayList<String>()
    var chunkStart = 0
    var hasBlock = false
    fun pack(first: Int, end: Int) {
        if (hasBlock && starts[end] - chunkStart > CHUNK_BUDGET_CHARS) {
            chunks.add(text.substring(chunkStart, starts[first]))
            chunkStart = starts[first]
        }
        hasBlock = true
    }
    var i = 0
    while (i < lines.size) {
        val first = i++
        val line = lines[first].trimStart()
        if (line.isEmpty()) continue
        if (line.startsWith("```") || line.startsWith("~~~")) {
            val marker = line.first()
            val width = line.indexOfFirst { it != marker }.let { if (it == -1) line.length else it }
            while (i < lines.size) {
                val closing = lines[i++].trim()
                if (closing.length >= width && closing.all { it == marker }) break
            }
            pack(first, i)
        } else {
            val table = line.startsWith('|')
            while (i < lines.size) {
                val next = lines[i].trimStart()
                if (next.isEmpty() || next.startsWith('|') != table || next.startsWith("```") || next.startsWith("~~~")) break
                i++
            }
            if (starts[i] - starts[first] <= CHUNK_BUDGET_CHARS) pack(first, i)
            else for (row in first until i) pack(row, row + 1)
        }
    }
    chunks.add(text.substring(chunkStart))
    return chunks
}

internal fun finishedToolTurn(previousTurnKey: String?, activeTurnKey: String?): String? =
    previousTurnKey?.takeIf { it != activeTurnKey }

internal data class ChatTypewriterEntry(
    val key: String,
    val messageId: String? = null,
    val text: String = "",
    val reasoning: String = "",
    val initialText: String = "",
    val initialReasoning: String = "",
    val afterTimestamp: Long = Long.MIN_VALUE,
)

internal fun chatPlaybackHandedOff(entry: ChatTypewriterEntry, displayedMessageIds: Set<String>): Boolean =
    entry.messageId != null && entry.messageId in displayedMessageIds

internal data class ChatTypewriterState(
    val initialized: Boolean = false,
    val seenMessageIds: Set<String> = emptySet(),
    val entries: List<ChatTypewriterEntry> = emptyList(),
    val streamKey: String? = null,
    val nextKey: Int = 0,
    val messageSnapshot: List<AgentUIMessage>? = null,
)

internal fun reconcileChatTypewriters(
    previous: ChatTypewriterState,
    messages: List<AgentUIMessage>,
    streamingText: String?,
    streamingReasoning: String?,
    discardPending: Boolean = false,
): ChatTypewriterState {
    val entries = previous.entries.toMutableList()
    var streamKey = previous.streamKey
    var nextKey = previous.nextKey
    if (discardPending) {
        entries.removeAll { it.messageId == null }
        streamKey = null
    }
    val newMessages = if (messages === previous.messageSnapshot) emptyList() else
        messages.filter { it.id !in previous.seenMessageIds }
    val candidates = newMessages.filter {
        it.role == MessageRole.ASSISTANT && !it.isSystemCardInTurn()
    }
    fun bindPending() {
        for (message in candidates) {
            if (entries.any { it.messageId == message.id }) continue
            val index = entries.indexOfFirst { entry ->
                entry.messageId == null && message.timestamp >= entry.afterTimestamp &&
                    (entry.text.hasVisibleContent() || entry.reasoning.hasVisibleContent()) &&
                    (entry.text.isEmpty() || message.content.startsWith(entry.text.trimEnd())) &&
                    (entry.reasoning.isEmpty() || message.reasoning.orEmpty().startsWith(entry.reasoning.trimEnd()))
            }
            if (index >= 0) {
                entries[index] = entries[index].copy(
                    messageId = message.id, text = message.content, reasoning = message.reasoning.orEmpty()
                )
            }
        }
    }
    // 先把旧调用交给其落库消息，再接收下一次调用，不能拿新回复的前缀覆盖旧消息的进度。
    bindPending()
    val hasStream = !streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()
    if (!hasStream || discardPending) {
        streamKey = null
    } else {
        val index = entries.indexOfFirst { it.key == streamKey }
        val current = entries.getOrNull(index)
        val continues = current != null && if (current.messageId != null) {
            (streamingText == null || current.text == streamingText) &&
                (streamingReasoning == null || current.reasoning == streamingReasoning)
        } else {
            (streamingText == null || streamingText.startsWith(current.text)) &&
                (streamingReasoning == null || streamingReasoning.startsWith(current.reasoning))
        }
        if (continues) {
            if (current.messageId == null) {
                entries[index] = current.copy(
                    text = streamingText ?: current.text,
                    reasoning = streamingReasoning ?: current.reasoning,
                )
            }
        } else {
            val text = streamingText.orEmpty()
            val reasoning = streamingReasoning.orEmpty()
            streamKey = "stream:${nextKey++}"
            entries += ChatTypewriterEntry(
                key = streamKey,
                text = text,
                reasoning = reasoning,
                initialText = if (previous.initialized) "" else text,
                initialReasoning = if (previous.initialized) "" else reasoning,
                afterTimestamp = messages.lastOrNull()?.timestamp ?: Long.MIN_VALUE,
            )
        }
    }
    bindPending()
    val previousTailTimestamp = if (candidates.isEmpty()) Long.MIN_VALUE else
        messages.lastOrNull { it.id in previous.seenMessageIds }?.timestamp ?: Long.MIN_VALUE
    for (message in candidates) {
        if (entries.any { it.messageId == message.id }) continue
        val appended = message.timestamp >= previousTailTimestamp
        if (!previous.initialized || !appended) continue
        entries += ChatTypewriterEntry(
            key = "message:${message.id}",
            messageId = message.id,
            text = message.content,
            reasoning = message.reasoning.orEmpty(),
        )
    }
    return ChatTypewriterState(
        initialized = true,
        seenMessageIds = if (newMessages.isEmpty()) previous.seenMessageIds else
            previous.seenMessageIds + newMessages.map { it.id },
        entries = entries,
        streamKey = streamKey,
        nextKey = nextKey,
        messageSnapshot = messages,
    )
}

private data class ChatTypewriterPlayback(
    val entry: ChatTypewriterEntry,
    val body: State<TypewriterText>,
    val reasoning: State<TypewriterText>,
) {
    val settled: State<Boolean> = derivedStateOf(structuralEqualityPolicy()) {
        body.value.let { it.settled && it.source == entry.text } &&
            reasoning.value.let { it.settled && it.source == entry.reasoning }
    }
}

internal class TypewriterContentPlan(items: List<ChatRenderItem>, target: String) {
    private val rows = items.filter {
        it.contentType == MessageRole.ASSISTANT.name || it.contentType == "assistant-chunk"
    }
    private val rowIndex = rows.mapIndexed { index, row -> row.key to index }.toMap()
    private val starts = rows.map { it.sliceStart }
    private val source = rows.firstOrNull()?.message?.content.orEmpty()
    private var observedSource = target
    private var commonPrefix = source.commonPrefixWith(target).length

    private fun compatiblePrefix(frame: TypewriterText): Int {
        if (observedSource !== frame.source) {
            observedSource = frame.source
            commonPrefix = source.commonPrefixWith(frame.source).length
        }
        return commonPrefix
    }

    fun visibleCount(frame: TypewriterText): Int {
        if (rows.isEmpty()) return 0
        val shownLength = frame.text.length
        if (shownLength > compatiblePrefix(frame)) return 1
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] < shownLength) low = mid + 1 else high = mid
        }
        return low.coerceAtLeast(1)
    }

    fun includes(item: ChatRenderItem, count: Int): Boolean = rowIndex[item.key]?.let { it < count } ?: true

    fun render(item: ChatRenderItem, frame: TypewriterText): ChatRenderItem? {
        if (item.key !in rowIndex) return item
        val shown = frame.text
        if (shown.length > compatiblePrefix(frame)) {
            return if (item.isChunkHeader) item.copy(slice = shown, isChunkFooter = true) else null
        }
        val chunk = item.slice ?: return item.copy(slice = shown)
        val count = shown.length - item.sliceStart
        if (count <= 0 && !item.isChunkHeader) return null
        val footer = count <= chunk.length
        return if (count >= chunk.length && footer == item.isChunkFooter) item
        else item.copy(slice = chunk.take(count.coerceAtLeast(0)), isChunkFooter = footer)
    }
}

@Composable
internal fun rememberTypewriterItemProjection(
    items: List<ChatRenderItem>,
    playback: Map<String, Pair<TypewriterContentPlan, State<TypewriterText>>>,
): State<List<ChatRenderItem>> {
    val counts = playback.mapValues { (_, value) ->
        val (plan, text) = value
        remember(plan, text) { derivedStateOf(structuralEqualityPolicy()) { plan.visibleCount(text.value) } }
    }
    return remember(items, counts) {
        derivedStateOf(structuralEqualityPolicy()) {
            val visible = counts.mapValues { it.value.value }
            if (playback.isEmpty()) items else items.filter { item ->
                playback[item.message.id]?.first?.includes(item, visible.getValue(item.message.id)) ?: true
            }
        }
    }
}

internal fun applyTypewriterContentSlices(
    items: List<ChatRenderItem>,
    contentByMessageId: Map<String, String>,
): List<ChatRenderItem> {
    if (contentByMessageId.isEmpty()) return items
    val plans = items.filter { it.message.id in contentByMessageId }.groupBy { it.message.id }
        .mapValues { (id, rows) -> TypewriterContentPlan(rows, contentByMessageId.getValue(id)) }
    return items.mapNotNull { item ->
        val shown = contentByMessageId[item.message.id]
        if (shown == null) item else plans.getValue(item.message.id).render(item, TypewriterText(shown, false))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** 大屏右栏当前开的是终端 / Git 时，顶栏对应图标高亮。 */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    onboardingStep: OnboardingStep? = null,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onDismissModelSheetInOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    ToolPreviewWorkVisibility(visible) {
    val uiWorkActive = chatUiWorkActive()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val pendingScroll by viewModel.pendingScrollMessage.collectAsStateWithLifecycle()
    // 子代理卡片：状态与动作经 CompositionLocal 下发给 task 工具行，避免逐层透传。
    val subAgentCards by viewModel.subAgentCards.collectAsStateWithLifecycle()

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val subAgentSummarySnapshot by viewModel.subAgentInteractionSummaries.collectAsStateWithLifecycle()
    val subAgentSummaries = subAgentSummarySnapshot.takeIf { it.first == currentSessionId }?.second.orEmpty()
    val subAgentGroupStateHolder = rememberSaveableStateHolder()
    val subAgentChildStateHolder = rememberSubAgentGroupChildStateHolder(currentSessionId)
    val toolPreviewState = rememberToolPreviewHostState(currentSessionId)
    val toolExecutionStarts by viewModel.toolExecutionStarts.collectAsStateWithLifecycle()
    val userBubbleStyle = settingsViewModel?.userChatBubbleStyle?.collectAsStateWithLifecycle()?.value ?: ChatBubbleStyle.ORIGINAL
    val assistantBubbleStyle = settingsViewModel?.assistantChatBubbleStyle?.collectAsStateWithLifecycle()?.value ?: ChatBubbleStyle.DOCUMENT
    // 工具卡片入场调度：只排本次浏览期间新追加到尾部的 TOOL 消息，逐个错开淡入。
    // 换会话时调度器重建，新会话的存量消息不入场。
    val entryScheduler = remember(currentSessionId) { MessageEntryScheduler() }
    val messageEntryDelays = remember(messages, entryScheduler) { entryScheduler.schedule(messages) }
    val currentSessionState by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val currentSession = currentSessionState
    val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_session_btn)
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val contextUsages by viewModel.contextUsages.collectAsStateWithLifecycle()
    val generationSpeed by viewModel.generationSpeed.collectAsStateWithLifecycle()
    val speedTier by viewModel.speedTier.collectAsStateWithLifecycle()
    val recoverySnapshot by viewModel.recoveryItems.collectAsStateWithLifecycle()
    val recoveryBusy by viewModel.recovering.collectAsStateWithLifecycle()
    val recoveryItems = recoverySnapshot.takeIf { it.first == currentSessionId }?.second.orEmpty()
    val typewriterCharsPerSecond = settingsViewModel?.typewriterCharsPerSecond?.collectAsStateWithLifecycle()?.value
        ?: DEFAULT_TYPEWRITER_CHARS_PER_SECOND
    val chatTextScale = settingsViewModel?.chatTextScale?.collectAsStateWithLifecycle()?.value
        ?: ThemeSettingsRepository.DEFAULT_CHAT_TEXT_SCALE
    val chatBackgroundImagePath = settingsViewModel?.chatBackgroundImagePath?.collectAsStateWithLifecycle()?.value
    val chatBackgroundAlpha = settingsViewModel?.chatBackgroundAlpha?.collectAsStateWithLifecycle()?.value
        ?: BackgroundSettingsRepository.DEFAULT_CHAT_ALPHA
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val compactionProgressSnapshot by viewModel.compactionPercent.collectAsStateWithLifecycle()
    val compactionPercent = compactionProgressSnapshot.takeIf { it.first == currentSessionId }?.second ?: 0
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val keySwitchState by viewModel.keySwitchState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val reasoningTiming by viewModel.reasoningTiming.collectAsStateWithLifecycle()
    val preparingTool by viewModel.preparingTool.collectAsStateWithLifecycle()
    // 等待模型或工具执行时的状态文案：
    // 1. 若当前有正在执行的工具（runningTool）或模型正在准备的工具（preparingTool，工具名先到参数还在流式），
    //    直接说清在做什么（「正在读取文件」、「正在编辑文件」等，见 toolRunningLabelRes）；
    // 2. 否则（如等待模型首字吐出、纯思考间隙）兜底显示「正在思考」。
    val activeToolName by remember {
        derivedStateOf { runningTool.lastOrNull()?.toolName?.takeIf { it.isNotBlank() } ?: preparingTool }
    }
    val thinkingLabel = activeToolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val pendingPlanApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
    val pendingPlanApprovalSessionTitle by viewModel.pendingPlanApprovalSessionTitle.collectAsStateWithLifecycle()
    val awaitingUserInput = pendingPermission != null || pendingQuestion != null || pendingPlanApproval != null
    val currentTodoItems by viewModel.currentSessionTodoItems.collectAsStateWithLifecycle()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()
    val targetRewindMessageId by viewModel.targetRewindMessageId.collectAsStateWithLifecycle()
    val rewindConflicts by viewModel.rewindConflicts.collectAsStateWithLifecycle()
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val defaultProviderId = settingsViewModel?.defaultModelProviderId?.collectAsStateWithLifecycle()?.value ?: ""
    val defaultModelName = settingsViewModel?.defaultModel?.collectAsStateWithLifecycle()?.value ?: ""
    // 未绑定会话回退：新会话默认模型（主页空会话中选择后记忆），未设置则为 null（UI 显示默认图标/顶栏模型名留空）。
    val defaultFallbackProvider = providers
        .find { it.id == defaultProviderId }
        ?.takeIf { it.hasUsableApiKey }
        ?.let { if (defaultModelName.isNotBlank() && defaultModelName in it.models) it.copy(selectedModel = defaultModelName) else it }
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // 与 workflow.resolveProviderConfig 保持一致：绑定 provider 须启用且已填 apiKey，否则回退默认模型
            providers.find { it.id == boundProviderId }
                ?.takeIf { it.hasUsableApiKey }
                // 绑定模型已被移出列表时绑定失效，回退默认模型（与 workflow.resolveProviderConfig 一致）
                ?.takeIf { boundModel.isNullOrBlank() || boundModel in it.models }
                ?.let {
                    if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
                } ?: defaultFallbackProvider
        } else {
            defaultFallbackProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentGoal by viewModel.currentSessionGoal.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()
    val fileMentionState by viewModel.fileMentionState.collectAsStateWithLifecycle()

    val inputText by viewModel.inputDraft.collectAsStateWithLifecycle()
    // 输入 "/" 打开命令菜单时重扫技能，反映磁盘上技能的增删改。
    LaunchedEffect(inputText) {
        if (inputText == "/") viewModel.refreshSlashCommands()
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    var uploadingCount by remember { mutableStateOf(0) }
    var messageForMenu by remember { mutableStateOf<AgentUIMessage?>(null) }
    var editingMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    var fullScreenInputVisible by remember { mutableStateOf(false) }
    var queuedEditing by remember { mutableStateOf<QueuedRequest?>(null) }
    val listState = rememberLazyListState()
    // 消息未就绪时的加载提示：本地读库通常几十毫秒，立刻显示反而闪一下，等一小会儿还没就绪才提示。
    var showMessagesLoading by remember(currentSessionId) { mutableStateOf(false) }
    // 贴底滚动留白：首帧测量前用兜底值（约输入框 + 间距），实测悬浮层高度后改为动态值，
    // 横幅/面板/输入框任何形态下最后一条消息都停在悬浮层上方不被遮挡。
    val inputBarBottomReserveDp = 156.dp
    var floatingLayerHeightPx by remember { mutableStateOf(0) }
    val inputBarReservePx = with(LocalDensity.current) {
        (if (floatingLayerHeightPx > 0) floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()
        else inputBarBottomReserveDp.toPx()).toInt()
    }
    val markdownCache = remember(currentSessionId) { MarkdownRenderCache() }
    val toolOverrideSnapshot by remember(viewModel) { derivedStateOf { viewModel.toolExpansionOverrides.toMap() } }
    val turnOverrideSnapshot by remember(viewModel) { derivedStateOf { viewModel.turnExpansionOverrides.toMap() } }
    val reasoningOverrideSnapshot by remember(viewModel) { derivedStateOf { viewModel.reasoningExpansionOverrides.toMap() } }
    // 正在执行的工具 id 集合：只让集合内容参与 remember key，避免实时输出逐字刷新导致整表重建。
    val runningToolIds by remember { derivedStateOf { runningTool.mapTo(HashSet()) { it.messageId } } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 回滚代码时若有文件因「快照后被改过」被跳过，必须告知用户——静默跳过等于把数据丢失
    // 换成了无声的失败。展示后立即清空状态，避免重组时重复弹。
    LaunchedEffect(rewindConflicts) {
        val skipped = rewindConflicts
        if (skipped.isEmpty()) return@LaunchedEffect
        val shown = skipped.take(MAX_REWIND_CONFLICT_LINES)
        val more = skipped.size - shown.size
        val listText = shown.joinToString("\n") + if (more > 0) "\n…另有 $more 个" else ""
        val message = if (skipped.size == 1) {
            context.getString(R.string.checkpoint_skipped_single, listText)
        } else {
            context.getString(R.string.checkpoint_skipped_multiple, skipped.size, listText)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.clearRewindConflicts()
    }

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    val turnKeyHistory = remember(currentSessionId) {
        currentSessionId?.let { viewModel.turnMessageKeys.getOrPut(it) { mutableMapOf() } }
            ?: mutableMapOf()
    }
    var stableTurnKeys by remember(currentSessionId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val activeTurnKey = remember(isBusy, messagesReady, messages, stableTurnKeys, currentSessionId) {
        if (isBusy && messagesReady) {
            val user = messages.asReversed().firstOrNull { it.role == MessageRole.USER && !it.isBackgroundNotification && !it.isCompactionMarker }
            if (user != null) stableTurnKeys[user.id]
                ?: reconcileTurnKeys(messages, emptyMap(), currentSessionId)[user.id]
                ?: turnKeyOf(user.id)
            else messages.lastOrNull()?.let { stableTurnKeys[it.id] ?: "turn:head:session:$currentSessionId" }
        } else null
    }
    var previousActiveTurnKey by remember(currentSessionId) { mutableStateOf<String?>(null) }
    val finishedTurnKey = if (messagesReady) finishedToolTurn(previousActiveTurnKey, activeTurnKey) else null
    val effectiveTurnOverrides = if (finishedTurnKey != null) {
        turnOverrideSnapshot + (finishedTurnKey to false)
    } else turnOverrideSnapshot
    SideEffect {
        if (messagesReady) previousActiveTurnKey = activeTurnKey
        finishedTurnKey?.let { viewModel.setTurnExpanded(it, false) }
    }
    var toolClockMs by remember(currentSessionId) { mutableStateOf(SystemClock.elapsedRealtime()) }
    val anyToolRunning = runningToolIds.isNotEmpty() || remember(messages) {
        messages.any { it.role == MessageRole.TOOL && it.isToolRunning(null) }
    }
    UiWorkEffect(currentSessionId, anyToolRunning) {
        while (anyToolRunning) {
            toolClockMs = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val knownChildIds = remember(subAgentSummaries.keys) { subAgentSummaries.keys.toSet() }
    val projectionOwner = remember(currentSessionId, knownChildIds) { Any() }
    val timelineCache = remember(projectionOwner) { SubAgentTimelineCache() }
    val chatItemsKey = remember(messages, effectiveTurnOverrides, activeTurnKey, subAgentSummaries, subAgentCards, isBusy) { Any() }
    val chatRequest = remember(messagesState, chatItemsKey, projectionOwner, messagesReady) {
        if (messagesReady) ChatItemsBuildRequest(messagesState, chatItemsKey, projectionOwner) else null
    }
    val metadataResult = rememberChatProjection(chatRequest) {
        val context = currentCoroutineContext()
        messages.forEach { message ->
            context.ensureActive()
            if (message.isBackgroundNotification || message.role == MessageRole.TOOL) {
                parseSubAgentNotificationMetadata(message.content, !message.isBackgroundNotification)
            }
        }
        val resolvedKeys = reconcileTurnKeys(messages, synchronized(turnKeyHistory) { turnKeyHistory.toMap() }, currentSessionId)
        val timeline = timelineCache.project(messages, knownChildIds, currentSessionId, checkpoint = { context.ensureActive() })
        val capsules = projectSubAgentTurnCapsules(timeline, resolvedKeys, currentSessionId)
        val base = flattenChatProcesses(buildChatItems(messages, effectiveTurnOverrides, activeTurnKey, currentSessionId, resolvedKeys))
        val inserted = insertSubAgentTurnItems(base, capsules, messages)
        val ranks = subAgentGroupStatusRanks(inserted, subAgentSummaries, subAgentCards)
        context.ensureActive()
        ChatMetadataProjection(
            items = inserted,
            turnKeys = resolvedKeys,
            collapsedGroups = projectSubAgentGroup(inserted, currentSessionId, activeTurnKey = activeTurnKey, statusRanks = ranks),
            statusRanks = ranks,
            messageIds = messages.mapTo(hashSetOf()) { it.id },
            taskDurations = computeTaskDurations(messages, lastTurnFinished = !isBusy),
            turnUsages = computeTurnUsage(messages, lastTurnFinished = !isBusy),
            actionableMessageIds = actionableChatMessageIds(messages, isBusy),
            toolsByTurn = messages.filter { it.role == MessageRole.TOOL && !it.isSystemCardInTurn() }.groupBy { resolvedKeys[it.id] },
        )
    }
    val metadata = metadataResult?.value
    SideEffect {
        if (metadataResult?.request?.canPublishTo(chatRequest) == true && metadata != null) {
            synchronized(turnKeyHistory) { turnKeyHistory.putAll(metadata.turnKeys) }
            stableTurnKeys = metadata.turnKeys
        }
    }
    val reasoningDurations = viewModel.reasoningDurations
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = activeProvider?.let { modelMetadata[modelMetadataKey(it.id, activeModel)] }
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerDashboards by (settingsViewModel?.providerDashboards?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentDashboardState = activeProvider?.let { providerDashboards[it.id] } ?: ProviderDashboardState.Idle

    // 键盘弹出时收起面板，避免输入框被挤压
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // 余额/待办面板展开状态：展开时叠加面板联动折叠，避免输入框被双重顶开
    var dashboardExpanded by rememberSaveable { mutableStateOf(false) }
    var todoExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderDashboardBar 仅在有面板脚本的 provider 下渲染（见 ChatInputBar）。无该栏时
    // dashboardExpanded 可能残留 true：面板曾展开后随 provider 切换/脚本移除而卸载，LaunchedEffect
    // 上报链路中断无法复位，直接拿它做 forceCollapse 会把授权/询问/计划面板永久压成收起态。
    // 故叠加可见性，只在面板当前可见且展开时才折叠叠加面板。
    val dashboardBarVisible = activeProvider?.dashboardScriptPath?.isNotBlank() == true
    val dashboardCollapseActive = (dashboardExpanded && dashboardBarVisible) || (todoExpanded && currentTodoItems.isNotEmpty())

    fun buildDashboardContext(
        lastInput: Int = 0,
        lastOutput: Int = 0,
        lastCached: Int = 0,
        refreshReason: String = ""
    ): DashboardContext {
        val curSessionId = currentSessionId.orEmpty()
        val agentStateStr = when (agentState) {
            is AgentUIState.Idle -> "idle"
            is AgentUIState.Loading -> "loading"
            is AgentUIState.Streaming -> "streaming"
            is AgentUIState.Result -> "result"
            is AgentUIState.Error -> "error"
        }
        // 未显式传最近 token（切会话/任务完成/手动刷新等场景）时，从最后一条含 token 的助手消息兜底：
        // 否则面板的「最近输入/输出/缓存」会显示 0。llm 事件触发的刷新仍用事件带的实时值（更及时）。
        val lastTokenMsg = messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && (it.inputTokens > 0 || it.outputTokens > 0)
        }
        val effLastInput = if (lastInput > 0) lastInput else (lastTokenMsg?.inputTokens ?: 0)
        val effLastOutput = if (lastOutput > 0) lastOutput else (lastTokenMsg?.outputTokens ?: 0)
        val effLastCached = if (lastCached > 0) lastCached else (lastTokenMsg?.cachedInputTokens ?: 0)
        return DashboardContext(
            model = activeModel,
            // 面板脚本在容器内执行，工作区路径必须是容器视角（~/workspace）；宿主真实路径在容器内不存在
            workspacePath = WorkspacePathMapper.CONTAINER_ROOT,
            workspaceName = if (projectRoot.isNotBlank()) File(projectRoot).name else "",
            sessionId = curSessionId,
            lastInputTokens = effLastInput,
            lastOutputTokens = effLastOutput,
            lastCachedTokens = effLastCached,
            totalInputTokens = currentSession?.totalInputTokens ?: 0,
            totalOutputTokens = currentSession?.totalOutputTokens ?: 0,
            modelContextTokens = activeModelMetadata?.contextTokens ?: 0,
            modelMaxInputTokens = activeModelMetadata?.inputTokens ?: 0,
            modelMaxOutputTokens = activeModelMetadata?.outputTokens ?: 0,
            modelInputCostUsdPerM = activeModelMetadata?.inputCostUsdPerM ?: 0.0,
            modelOutputCostUsdPerM = activeModelMetadata?.outputCostUsdPerM ?: 0.0,
            modelCacheReadCostUsdPerM = activeModelMetadata?.cacheReadCostUsdPerM ?: 0.0,
            modelSupportsTools = activeModelMetadata?.supportsTools ?: false,
            modelSupportsVision = activeModelMetadata?.supportsVision ?: false,
            modelSupportsReasoning = activeModelMetadata?.supportsReasoning ?: false,
            messageCount = messages.size,
            agentState = agentStateStr,
            reasoningEffort = reasoningEffort.apiValue,
            refreshReason = refreshReason
        )
    }

    // 首次进入、切换提供商、切换脚本路径或切换会话时拉取一次面板。
    // 会话切换时等 currentSession / messages 都落到新会话（sessionReady）再刷新：
    // 否则 buildDashboardContext 读到的是切换瞬间的旧会话快照，面板会显示旧数据或空 token。
    val sessionReady = currentSession?.id == currentSessionId && messagesReady
    LaunchedEffect(activeProvider?.id, activeProvider?.dashboardScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
    }

    // 每次单次 LLM 请求返回时，立即带上最新 Token 与上下文实时刷新面板
    // 注意：LaunchedEffect(Unit) 只启动一次，collect 闭包会捕获首次组合时的变量快照，
    // 必须用 rememberUpdatedState 取最新值，否则会话累计 Token/消息数等永远停留在旧值。
    val latestBuildDashboardContext by rememberUpdatedState(
        { lastInput: Int, lastOutput: Int, lastCached: Int, refreshReason: String ->
            buildDashboardContext(lastInput, lastOutput, lastCached, refreshReason)
        }
    )
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    LaunchedEffect(Unit) {
        viewModel.llmCallEvents.collect { callEvent ->
            val provider = latestActiveProvider ?: return@collect
            if (provider.dashboardScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    // AI 一轮任务完成后刷新面板：让脚本拿到最终 agentState（result/idle/error），
    // 否则面板状态会一直停在「生成中」。busy→非busy 每轮只发生一次（Result 后再置 Idle 属
    // 非busy→非busy，不会重复触发），故这里触发的刷新恰好落在最终状态上。
    var lastAgentStateForPanel by remember { mutableStateOf<AgentUIState?>(null) }
    LaunchedEffect(agentState) {
        val prev = lastAgentStateForPanel
        lastAgentStateForPanel = agentState
        val wasBusy = prev is AgentUIState.Loading || prev is AgentUIState.Streaming
        val nowDone = agentState !is AgentUIState.Loading && agentState !is AgentUIState.Streaming
        if (wasBusy && nowDone) {
            val provider = latestActiveProvider ?: return@LaunchedEffect
            if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.id, provider.type, listOf(activeModel))
        }
    }

    // 主页模型选择面板复用 ModelSelectionSheet，能力 Tag 依赖全量模型元数据；
    // 仅 resolve 当前激活模型会导致未选过的模型无 Tag（设置页进入时已全量加载，主页补齐）。
    // providers 异步加载，首次组合时为空，需等非空后再解析（providers 后续变化也会触发，成本低有缓存）。
    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            settingsViewModel?.loadAllModelMetadata()
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            uploadingCount = selected.size
            try {
                selected.forEach { uri ->
                    runCatching {
                        copyUriToWorkspace(context, uri, viewModel.fileAccess, includeImageData = images)
                    }.onSuccess { uploaded ->
                        pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                        successCount += 1
                    }.onFailure { error ->
                        failures += (error.message ?: uploadFallbackError(context))
                    }
                }
            } finally {
                uploadingCount = 0
            }
            // 结果提示：全失败展示首个错误；有文件被上限截断或上传失败时用 partial 文案；全成功用 success 文案。
            val skipped = uris.size - selected.size
            when {
                successCount == 0 && failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
                skipped > 0 || failures.isNotEmpty() ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // 拍照：输出到 cache 临时文件（FileProvider 授权 uri），拍完按图片附件处理。
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.chat_camera_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    var typewriterState by remember(currentSessionId) { mutableStateOf(ChatTypewriterState()) }
    var revealedTypewriterKeys by remember(currentSessionId) { mutableStateOf(emptySet<String>()) }
    val cancelPlayback = agentState is AgentUIState.Error ||
        (agentState as? AgentUIState.Result)?.status == WorkflowStatus.CANCELLED
    val playbackState = remember(typewriterState, messages, messagesReady, streamingText, streamingReasoning, retryState, keySwitchState, isCompacting) {
        if (messagesReady) reconcileChatTypewriters(
            previous = typewriterState,
            messages = messages,
            streamingText = streamingText,
            streamingReasoning = streamingReasoning,
            discardPending = retryState != null || keySwitchState != null || isCompacting,
        ) else typewriterState
    }
    val playbacks = playbackState.entries.map { entry ->
        key(currentSessionId, entry.key) {
            val active = entry.key == playbackState.streamKey
            ChatTypewriterPlayback(
                entry = entry,
                body = rememberTypewriterStreamingText(
                    text = entry.text,
                    active = active && streamingText != null,
                    sessionKey = currentSessionId,
                    charsPerSecond = typewriterCharsPerSecond,
                    initialText = entry.initialText,
                    revealImmediately = entry.key in revealedTypewriterKeys || cancelPlayback,
                    workActive = uiWorkActive,
                ),
                reasoning = rememberTypewriterStreamingText(
                    text = entry.reasoning,
                    active = active && streamingReasoning != null && streamingText == null,
                    sessionKey = currentSessionId,
                    charsPerSecond = typewriterCharsPerSecond,
                    initialText = entry.initialReasoning,
                    revealImmediately = entry.key in revealedTypewriterKeys || cancelPlayback,
                    workActive = uiWorkActive,
                ),
            )
        }
    }
    val playbackByMessageId = playbacks.filter { it.entry.messageId != null }
        .associateBy { it.entry.messageId!! }
    val collapsedSubAgentProjection = metadata?.collapsedGroups
        ?: SubAgentGroupProjection(emptyList(), emptyList(), emptyList(), emptySet(), emptySet())
    val subAgentGroupExpansion = collapsedSubAgentProjection.groups.associate { group ->
        group.key to key(group.key) {
            rememberRetainedSubAgentGroupExpansion(currentSessionId, group.key, subAgentGroupStateHolder)
        }
    }
    val subAgentExpansionSnapshot = subAgentGroupExpansion.mapValues { it.value.expanded }
    val groupVersion = remember(metadataResult, subAgentExpansionSnapshot) { Any() }
    val groupRequest = remember(chatRequest, groupVersion) {
        chatRequest?.let { ChatItemsBuildRequest(it.source, groupVersion, it.ownership) }
    }
    val expandedGroups = rememberChatProjection(groupRequest) {
        metadata?.let {
            ChatGroupDisplay(it, projectSubAgentGroup(it.items, currentSessionId, activeTurnKey = activeTurnKey,
                expansionOverrides = subAgentExpansionSnapshot, statusRanks = it.statusRanks))
        }
    }
    val published = expandedGroups?.value
    val displayedMetadata = published?.metadata ?: metadata
    val grouped = published?.projection ?: collapsedSubAgentProjection
    val taskDurations = displayedMetadata?.taskDurations.orEmpty()
    val turnUsages = displayedMetadata?.turnUsages.orEmpty()
    val actionableMessageIds = displayedMetadata?.actionableMessageIds.orEmpty()
    val displayedMessageIds = displayedMetadata?.messageIds.orEmpty()
    val completedPlaybacks by remember(playbacks, displayedMessageIds, playbackState.streamKey) {
        derivedStateOf(structuralEqualityPolicy()) {
            playbacks.filter {
                it.settled.value && chatPlaybackHandedOff(it.entry, displayedMessageIds) && it.entry.key != playbackState.streamKey
            }.mapTo(mutableSetOf()) { it.entry.key }
        }
    }
    val retainedPlaybackState = playbackState.copy(entries = playbackState.entries.filterNot { it.key in completedPlaybacks })
    SideEffect { typewriterState = retainedPlaybackState }
    val displayReady = messagesReady && (messages.isEmpty() || displayedMetadata != null)
    UiWorkEffect(currentSessionId, displayReady) {
        if (displayReady) {
            showMessagesLoading = false
            return@UiWorkEffect
        }
        delay(MESSAGES_LOADING_HINT_DELAY_MS)
        showMessagesLoading = true
    }
    val playbackContent = remember(grouped, playbackByMessageId) {
        grouped.items.filter { it.message.id in playbackByMessageId }.groupBy { it.message.id }
            .mapValues { (id, rows) ->
                val playback = playbackByMessageId.getValue(id)
                TypewriterContentPlan(rows, playback.entry.text) to playback.body
            }
    }
    val bodyItems by rememberTypewriterItemProjection(grouped.bodyItems, playbackContent)
    val footerItems by rememberTypewriterItemProjection(grouped.footerItems, playbackContent)
    val subAgentProjection = remember(grouped, bodyItems, footerItems) {
        grouped.copy(bodyItems = bodyItems, footerItems = footerItems)
    }
    val subAgentGroupsByKey = remember(subAgentProjection.groups) { subAgentProjection.groups.associateBy { it.key } }
    val chatItems = subAgentProjection.items
    // 异步列表尚未接到落库消息时仍留在尾巴；一旦原消息接管，这里不再渲染第二份。
    val tailPlaybacks = playbacks.filter {
        !chatPlaybackHandedOff(it.entry, displayedMessageIds)
    }
    val showStreaming = tailPlaybacks.any { it.entry.text.hasVisibleContent() }
    val showReasoning = tailPlaybacks.any { it.entry.reasoning.hasVisibleContent() }

    // 自动滚动跟随
    // 两个状态都必须 saveable：窄窗打开编辑器 / 终端 / Git / 设置都是全屏路由，聊天页整棵组合
    // 被 dispose。用普通 remember 的话返回时 positionedSession 归 null（当成换了会话重新贴底）、
    // followBottom 归 true（校准循环把恢复出的位置拉回底部），浏览历史的位置就丢了。
    var positionedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var userScrollGesture by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var nestedFling by remember { mutableStateOf(false) }
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollGesture = true
                    followBottom = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                nestedFling = true
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                nestedFling = false
                return Velocity.Zero
            }
        }
    }

    val isAtBottom by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // 列表还没测量（首帧、或消息未就绪期间 LazyColumn 未挂载）时 layoutInfo 为空、
            // canScrollForward 恒 false。此时不能判「在底部」——从别的页面返回的首帧会因此
            // 恢复跟随，把 listState 刚恢复出的滚动位置拉回底部。
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            // 到底 = 最后内容底部停在安全区（悬浮层上沿）附近，而非视口底：
            // 判定更严格，避免用户拖走一小段后仍被判「在底部」而恢复跟随、立即被拉回。
            val safeBottom = layout.viewportEndOffset - layout.afterContentPadding
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= safeBottom + AUTO_SCROLL_TOLERANCE_PX
        }
    }

    LaunchedEffect(pointerDown, userScrollGesture, nestedFling, listState.isScrollInProgress) {
        if (!pointerDown && userScrollGesture && !nestedFling && !listState.isScrollInProgress) {
            delay(150)
            userScrollGesture = false
            followBottom = isAtBottom
        }
    }

    // 回底按钮的显示门槛：只用「不在底部」会让流式增长的那一两帧（校准循环还没把视口拉回）
    // 也算离底，按钮跟着闪。要求离底超过半个视口，用户真的翻上去看历史时才出现。
    // messagesReady / messages.isEmpty() 与 totalItemsCount 是同一枚硬币的两面：layoutInfo 是
    // 「最后一次布局 pass」的产物，LazyColumn 卸载（空会话 WelcomeState、加载占位）后不会自动
    // 清空——旧会话翻历史后切到空会话，残留布局会让按钮悬在新会话上。totalItemsCount 再拦截
    // 「新列表尚未按当前消息重测」（layout 开始前 layoutInfo 仍是旧会话的）那一帧。
    val isFarFromBottom by remember(listState, messagesReady, messages.size, chatItems.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != chatItems.size + 1) return@derivedStateOf false
            if (lastVisible.index < layout.totalItemsCount - 1) return@derivedStateOf true
            val safeBottom = layout.viewportEndOffset - layout.afterContentPadding
            (lastVisible.offset + lastVisible.size) - safeBottom > layout.viewportEndOffset / 2
        }
    }

    // 用户开始拖拽：停止跟随。松手时若已到底则恢复跟随。
    // 额外：流式输出时内容持续增长，用户可能松手后又被「顶」离底部——
    // 用 snapshotFlow { isAtBottom } 持续监测，只要滑到底部就恢复跟随，
    // 满足「流式中滚到底部自动继续跟随」。
    val latestIsAtBottom = rememberUpdatedState(isAtBottom)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 松手后延迟判定是否恢复跟随：等惯性滚动稳定，
                    // 避免「松手在底部但惯性上滑」被立即拉回。
                    scope.launch {
                        delay(150)
                        if (!userScrollGesture) followBottom = latestIsAtBottom.value
                    }
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { latestIsAtBottom.value && !userScrollGesture }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    val snapToBottom: suspend () -> Unit = { listState.followChatBottom(tolerancePx = 0) }

    // 正文接收与 Markdown 实际长高可能不在同一帧；只用已测量布局驱动一个跟随者。
    UiWorkEffect(listState, currentSessionId, displayReady) {
        if (!displayReady) return@UiWorkEffect
        snapshotFlow {
            if (followBottom && !userScrollGesture && positionedSession == currentSessionId) listState.layoutInfo else null
        }.collectLatest { layout ->
            if (layout != null && followBottom && !userScrollGesture) listState.followChatBottom()
        }
    }

    val sendMessage: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val attachments = pendingAttachments
            val modelSupportsVision = activeModelMetadata?.supportsVision == true
            val promptAttachments = if (modelSupportsVision) attachments.filterNot { it.isImage } else attachments
            val modelRequest = appendAttachmentsToRequest(context, text, promptAttachments)
            val images = if (modelSupportsVision) attachments.toAgentImages() else emptyList()
            // 统一走队列：AI 忙时入队（等本轮结束后自动发送下一条），空闲时直接发送。
            // 斜杠命令在 ViewModel 内（agent workflow 之前）分流执行，无需在此区分。
            viewModel.enqueueAgentRequest(
                request = text,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments.toAgentAttachments()
            )
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom()
            }
        }
    }

    // 切换会话 / 打开搜索命中：优先定位到目标消息，否则贴底并恢复跟随。
    UiWorkEffect(currentSessionId, messagesReady, pendingScroll, chatItems) {
        if (!messagesReady || metadataResult?.request?.canPublishTo(chatRequest) != true) return@UiWorkEffect
        val pending = pendingScroll
        if (pending != null && pending.first == currentSessionId) {
            val index = findSubAgentGroupMessageIndex(subAgentProjection, pending.second)
            if (index >= 0) {
                // 等一帧让 LazyColumn 按新会话完成重组，再瞬移到目标消息。
                withFrameNanos { }
                followBottom = false
                listState.scrollToItem(index)
                positionedSession = currentSessionId
                viewModel.consumePendingScroll()
            } else if (!messagesState.hasMore && expandedGroups?.request?.canPublishTo(groupRequest) == true) {
                // 全部消息已加载仍找不到（消息可能已被删除）：放弃，避免卡在待定位态。
                viewModel.consumePendingScroll()
            }
            return@UiWorkEffect
        }
        if (positionedSession != currentSessionId) {
            // 等一帧让 LazyColumn 按新会话完成重组，再滚到锚点（maxScroll）。
            withFrameNanos { }
            snapToBottom()
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // 展开/收起一条 item（工具卡片、工具分组头）之后**不做任何主动滚动**：就地展开、就地收起，
    // 视口一动不动；只暂停贴底跟随（用户滚回底部时由 isAtBottom 监测自动恢复）。
    //
    // 从前这里会按「让 item 底部露出安全区」重定位视口，还带一条「item 完全不可见就 scrollToItem」
    // 的兜底；卡片比一屏高时前者算出的目标被夹到 0，后者干脆把 item 顶到视口顶——两条路径都会让
    // 被点的那一行整条跳到顶部（概率性出现，取决于点的那一刻布局稳定到哪一帧）。这个跳动比
    // 「展开后底部被输入框挡一点」难接受得多，整段重定位逻辑去掉。
    val onToolItemToggled: () -> Unit = { followBottom = false }

    // 顶部快到头时加载更多历史。绝不能把 firstVisibleItemIndex 作为组合期读取的 effect key：
    // fling 中 index 每跨一个 item 就变一次，会让整个 AIChatPanel 大函数随之重组、连带全部可见
    // item 重组，是快速滚动掉帧（进而 fling 补偿跳变、抽搐）的主要放大器。改为在协程内用
    // snapshotFlow 观察 index，组合体完全不读它。
    // 触发信号里带上分页上限：消息按条数分页，一轮长过程折叠后整页补载可能一个可见项都不增加，
    // index 不再变化；只监听 index 会卡在这里，要用户上下晃动才能再触发一页。
    // 上限纳入信号后，每页补载完成都会重新判断，仍在顶部就连续补载。
    val latestMessagesState = rememberUpdatedState(messagesState)
    UiWorkEffect(listState, messagesReady) {
        if (!messagesReady) return@UiWorkEffect
        snapshotFlow {
            val ms = latestMessagesState.value
            Triple(listState.firstVisibleItemIndex <= 3 && ms.hasMore && !ms.isLoadingMore, ms.sessionId, ms.limit)
        }
            .distinctUntilChanged()
            .collect { (shouldLoad, _, _) ->
                if (shouldLoad) viewModel.loadMoreMessages()
            }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH

    val markdownImageTransformer = remember(viewModel.fileAccess, projectRoot, executionMode, connectionState) {
        MarkdownImageTransformer(viewModel.fileAccess)
    }
    val imageViewerState = rememberImageViewerState()
    val filePreviewState = rememberFilePreviewState(currentSessionId)
    LaunchedEffect(filePreviewState, projectRoot, executionMode, connectionState) { filePreviewState.close() }
    val defaultUriHandler = LocalUriHandler.current
    val previewUriHandler = remember(defaultUriHandler, filePreviewState) {
        PreviewUriHandler(defaultUriHandler) { path -> filePreviewState.open(PreviewRequest(path)) }
    }
    val viewerDecodeSpec = rememberViewerDecodeSpec()
    val chatImageLoad = remember(viewModel.fileAccess, viewerDecodeSpec, projectRoot, executionMode, connectionState) {
        chatImageLoader(viewModel.fileAccess, viewerDecodeSpec.maxEdge, viewerDecodeSpec.maxPixels)
    }

    val attachmentOpener = remember(viewModel.fileAccess, context, filePreviewState, isRemote) {
        AttachmentOpener { attachment ->
            val mediaPath = attachment.containerPath.ifBlank { attachment.localPath }.ifBlank { attachment.fileName }
            if (attachment.containerPath.isNotBlank() && attachmentUsesPlayer(mediaPath, attachment.mimeType)) {
                PlayerActivity.open(context, PlayerMediaTypes.request(attachment.containerPath, attachment.fileName, attachment.mimeType))
            } else {
                val cached = attachment.localPath.takeIf { isRemote || it.startsWith(context.cacheDir.absolutePath + File.separator) }.orEmpty()
                filePreviewState.open(PreviewRequest(attachment.containerPath, attachment.fileName, attachment.mimeType, cached,
                    recordedLocalPath = attachment.localPath.takeIf { cached.isEmpty() }.orEmpty()))
            }
        }
    }

    // 过程始终按独立列表项组合，追加工具时不切换布局结构。
    @Composable
    fun RenderChatItem(sourceItem: ChatRenderItem) {
        val content = playbackContent[sourceItem.message.id]
        val item = if (content == null) sourceItem else {
            val rendered by remember(sourceItem, content) {
                derivedStateOf(structuralEqualityPolicy()) { content.first.render(sourceItem, content.second.value) }
            }
            rendered ?: return
        }
        val header = item.turnHeader
        when {
            item.contentType == SUB_AGENT_GROUP_CONTENT_TYPE -> {
                subAgentGroupsByKey[item.key]?.let { group ->
                    SubAgentGroupCapsule(
                        capsules = group.capsules,
                        expanded = subAgentGroupExpansion[group.key]?.expanded == true,
                        onToggle = {
                            onToolItemToggled()
                            subAgentGroupExpansion[group.key]?.toggle()
                        }
                    )
                }
            }
            item.subAgentCapsule != null -> {
                val capsule = item.subAgentCapsule
                subAgentChildStateHolder.SaveableStateProvider(item.key) {
                    SubAgentInteractionCapsule(capsule.subSessionId, capsule.title, capsule.fallbackStatus,
                        fallbackAgentName = capsule.fallbackAgentName)
                }
            }
            header != null -> {
                val tools = displayedMetadata?.toolsByTurn?.get(header.key).orEmpty()
                val summary = toolGroupSummary(tools, header.endMessageId?.let { taskDurations[it] }, runningToolIds)
                TurnCollapseHeader(
                    running = header.running,
                    durationMs = header.endMessageId?.let { taskDurations[it] },
                    summary = summary.takeIf { it.count > 0 }?.let { toolGroupSummaryText(it) },
                    expanded = header.expanded,
                    onToggle = {
                        viewModel.setTurnExpanded(header.key, !header.expanded)
                        onToolItemToggled()
                    }
                )
            }
            item.contentType == TOOL_ATTACHMENTS_CONTENT_TYPE -> {
                val opener = LocalAttachmentOpener.current
                MessageAttachmentList(
                    attachments = item.message.attachments,
                    onClick = { opener.open(it) }
                )
            }
            item.contentType == REASONING_CONTENT_TYPE -> {
                // 助手的思考抽为过程项：展开态交给宿主持久化，划出视口/切页返回仍保持。
                ReasoningBubble(
                    text = playbackByMessageId[item.message.id]?.reasoning?.value?.text ?: item.message.reasoning.orEmpty(),
                    cache = markdownCache,
                    preRendered = item.message.id in playbackByMessageId,
                    durationMs = reasoningDurations[item.message.id],
                    expandedOverride = reasoningOverrideSnapshot[item.message.id],
                    onExpandedChange = {
                        viewModel.setReasoningExpanded(item.message.id, it)
                        onToolItemToggled()
                    }
                )
            }
            else -> {
                val message = item.message
                val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                val playback = playbackByMessageId[message.id]?.takeUnless { it.settled.value }
                if (playback != null && item.isChunkHeader && item.reasoningVisible && playback.entry.reasoning.isNotEmpty()) {
                    ReasoningBubble(
                        text = playback.reasoning.value.text,
                        cache = markdownCache,
                        preRendered = true,
                        durationMs = reasoningDurations[message.id],
                        expandedOverride = reasoningOverrideSnapshot[message.id],
                        onExpandedChange = {
                            viewModel.setReasoningExpanded(message.id, it)
                            onToolItemToggled()
                        },
                    )
                }
                AgentMessageItem(
                    message = message,
                    showActions = message.id in actionableMessageIds && playback == null,
                    playbackSettled = playback == null,
                    liveOutput = live,
                    markdownCache = markdownCache,
                    contentSlice = item.slice,
                    isChunkHeader = item.isChunkHeader,
                    isChunkFooter = item.isChunkFooter,
                    reasoningVisible = item.reasoningVisible && playback == null,
                    onRewindClick = { viewModel.openRewindMenu(it) },
                    onMoreClick = { messageForMenu = it },
                    onCompactContext = currentSessionId?.let { sid -> { viewModel.compactSession(sid, currentFile, selectedCode) } },
                    contextCompactionEnabled = !isBusy && !isCompacting && !awaitingUserInput,
                    toolExpandedOverride = toolOverrideSnapshot[message.id] ?: false,
                    toolDurationMs = if (message.role == MessageRole.TOOL) {
                        toolElapsedMs(message, toolExecutionStarts[message.id], toolClockMs)
                    } else null,
                    onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(message.id, isExpanded) },
                    onToolToggle = onToolItemToggled,
                    onToolPreview = { toolPreviewState.openDetails(message.id) },
                    reasoningExpandedOverride = reasoningOverrideSnapshot[message.id],
                    onReasoningExpandedChange = {
                        viewModel.setReasoningExpanded(message.id, it)
                        onToolItemToggled()
                    },
                    reasoningDurationMs = reasoningDurations[message.id],
                    taskDurationMs = taskDurations[message.id],
                    turnUsage = turnUsages[message.id],
                    entryDelayMs = messageEntryDelays[message.id]
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalUriHandler provides previewUriHandler,
        LocalChatTextScale provides chatTextScale,
        LocalUserChatBubbleStyle provides userBubbleStyle,
        LocalAssistantChatBubbleStyle provides assistantBubbleStyle,
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState,
        LocalAttachmentOpener provides attachmentOpener,
        LocalRepresentedSubAgentIds provides subAgentProjection.representedChildIds,
        LocalSubAgentCardHost provides SubAgentCardHost(
            states = subAgentCards,
            summaries = subAgentSummaries,
            onOpen = { id -> currentSessionId?.let { viewModel.openSubAgentSession(it, id) } },
            onStop = { id -> currentSessionId?.let { viewModel.stopOwnedSubAgent(it, id) } },
            conversation = { id -> currentSessionId?.let { viewModel.subAgentConversation(it, id) }
                ?: kotlinx.coroutines.flow.flowOf(com.aicode.feature.agent.presentation.SubAgentInteractionPage(loaded = true)) },
            execution = { id -> currentSessionId?.let { viewModel.subAgentExecution(it, id) }
                ?: kotlinx.coroutines.flow.flowOf(com.aicode.feature.agent.presentation.SubAgentExecutionPage(loaded = true)) },
            onLoadMore = viewModel::loadMoreSubAgentInteractions,
            onLoadMoreExecution = viewModel::loadMoreSubAgentExecution,
            onSend = { id, text -> currentSessionId?.let { viewModel.sendSubAgentInstruction(it, id, text) }
                ?: com.aicode.feature.agent.presentation.SubAgentInstructionResult(false,
                    com.aicode.feature.agent.presentation.SubAgentInstructionFailure.SESSION_MISSING) },
            onOpenConfirm = { id -> currentSessionId?.let { viewModel.openSubAgentSession(it, id) } },
            onContentInteraction = onToolItemToggled
        )
    ) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatHeader(
                sessionTitle = sessionTitle,
                modelName = activeProvider?.effectiveModel,
                providerName = activeProvider?.name,
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    onOpenDrawer()
                },
                onNewChat = { viewModel.newSession() },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                onNavigateToBrowser = onNavigateToBrowser,
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive,
                browserActive = browserActive,
                chosenSpeedTier = speedTier,
                onSpeedTierChange = viewModel::setSpeedTier,
                providerConfig = activeProvider,
                onOpenMcp = onOpenMcp
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        ChatBackgroundImage(chatBackgroundImagePath, chatBackgroundAlpha, Modifier.fillMaxSize())
        // 大屏正文列限宽居中：铺满整个平板宽度会让一行文字过长、气泡横跨全屏，读起来很累。
        // 窄窗下 readableContentMaxWidth() 返回 Dp.Unspecified，widthIn 不产生任何约束。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = readableContentMaxWidth())
                .fillMaxSize()
        ) {
            // 内容层：消息列表延伸到屏幕底部，输入框悬浮其上，滚动时卡片可滑入输入框后面
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (!displayReady) {
                    // 远程模式连接未就绪时显示连接状态占位，避免空白或旧工作区记录闪烁
                    if (isRemote && connectionState != null && connectionState != com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    } else if (showMessagesLoading) {
                        // 本地模式读库偏慢时的占位：以前这里什么都不画，切会话会先闪一下空白。
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(
                        bottomReserve = with(LocalDensity.current) { inputBarReservePx.toDp() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(userScrollConnection)
                            .pointerInput(listState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    pointerDown = true
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        pointerDown = false
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.md,
                            bottom = with(LocalDensity.current) { inputBarReservePx.toDp() }
                        )
                    ) {
                        itemsIndexed(subAgentProjection.bodyItems, key = { _, item -> item.key }, contentType = { _, item -> item.contentType }) { _, item ->
                            RenderChatItem(item)
                        }
                        // 忙碌状态指示器：在没有正文/思考流式输出、未在压缩、未被权限弹窗或询问挂起时展示。
                        // 工具正在执行或模型正在准备工具时，文案会由 thinkingLabel 动态呈现为对应场景（如「正在读取文件」）；
                        // 既无流式又无具体工具时兜底显示「正在思考」。
                        val showToolPreparation = shouldShowToolPreparation(
                            preparingTool = preparingTool,
                            isBusy = isBusy,
                            isCompacting = isCompacting,
                            awaitingUserInput = awaitingUserInput,
                        )
                        val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && !awaitingUserInput
                        val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val showKeySwitched = keySwitchState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val tailKind = when {
                            showStreaming -> TailKind.STREAMING
                            isCompacting -> TailKind.COMPACTING
                            showKeySwitched -> TailKind.KEY_SWITCHED
                            showRetrying -> TailKind.RETRYING
                            showThinking -> TailKind.THINKING
                            else -> TailKind.NONE
                        }
                        // 尾巴 item：思考气泡与状态尾巴合并进同一个永久挂载的 item，二者都不按状态增删。
                        // 思考开始/结束或流式开始/结束若让 totalItemsCount 突增突减，LazyColumn 会把
                        // firstVisibleItemIndex 向下 clamp → 视口上跳（旧症状2根因）。item 数量恒为 1，
                        // anchor 不会被 clamp：showReasoning 时渲染思考气泡（内部自带折叠），否则为空；
                        // tailKind 为 NONE 时尾巴为空 Box（0 高度）。流结束落库后跟随 effect 会把新消息贴底。
                        item(key = "__active__", contentType = "tail") {
                            Column {
                                tailPlaybacks.forEach { playback ->
                                    key(playback.entry.key) {
                                        if (playback.entry.reasoning.hasVisibleContent()) {
                                            val reasoningLive = playback.entry.key == playbackState.streamKey &&
                                                reasoningTiming != null && reasoningTiming?.second == null &&
                                                !showStreaming && preparingTool == null
                                            val expansionKey = playback.entry.messageId ?: currentSessionId?.let {
                                                viewModel.streamingReasoningKey(it)
                                            }
                                            ReasoningBubble(
                                                text = playback.reasoning.value.text,
                                                cache = markdownCache,
                                                showTimer = reasoningLive,
                                                durationMs = playback.entry.messageId?.let { reasoningDurations[it] }
                                                    ?: reasoningTiming?.let { (start, end) -> end?.minus(start) },
                                                timerStartMs = reasoningTiming?.first,
                                                preRendered = true,
                                                sessionKey = currentSessionId,
                                                live = reasoningLive,
                                                expandedOverride = expansionKey?.let { reasoningOverrideSnapshot[it] },
                                                onExpandedChange = { expanded ->
                                                    expansionKey?.let { viewModel.setReasoningExpanded(it, expanded) }
                                                    onToolItemToggled()
                                                },
                                            )
                                        }
                                        if (playback.entry.text.hasVisibleContent()) {
                                            StreamingBubble(text = playback.body.value.text, cache = markdownCache)
                                        }
                                    }
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble(label = thinkingLabel)
                                    TailKind.STREAMING -> Unit
                                    TailKind.COMPACTING -> CompactionProgressBubble(compactionPercent)
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.KEY_SWITCHED -> {
                                        val ks = keySwitchState
                                        if (ks != null) KeySwitchedBubble(ks.newIndex, ks.total) else Box(Modifier)
                                    }
                                    TailKind.NONE -> Box(Modifier)
                                }
                                // 工具参数仍在生成时，保留的思考或正文不能遮掉进度反馈。
                                if (showToolPreparation && (tailKind == TailKind.NONE || tailKind == TailKind.STREAMING)) {
                                    ThinkingBubble(label = thinkingLabel)
                                }
                            }
                        }
                        itemsIndexed(subAgentProjection.footerItems, key = { _, item -> item.key }, contentType = { _, item -> item.contentType }) { _, item ->
                            RenderChatItem(item)
                        }
                    }
                }
            }
            } // 内容层结束

            // 悬浮层：错误气泡 / 面板 / 输入框（蒙版在 ChatInputBar 内部，跟随键盘上移）
            val floatingPanelAlpha by animateFloatAsState(
                targetValue = if (listState.isScrollInProgress) 0.4f else 1f,
                animationSpec = AppMotion.tween(AppMotion.colorMillis),
                label = "floating-panel-alpha"
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 实测悬浮层实际高度作为滚动留白，横幅/面板/输入框任何形态都不遮挡最后一条
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = floatingPanelAlpha }) {
                StatusBanner(state = agentState)
            }

            ChatToolPreview(
                viewModel = viewModel,
                sessionId = currentSessionId,
                state = toolPreviewState,
                visible = visible && messageForMenu == null && editingMessage == null && targetRewindMessageId == null &&
                    imageViewerState.request == null && filePreviewState.request == null && !fullScreenInputVisible,
                onOpenBrowser = onNavigateToBrowser,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )

            // 退场动画期间 uploadingCount 已归零，直接读会淡出一个「正在上传 0 个文件」，
            // 与 StatusBanner 同样用非空记忆值兜住退场。
            val lastUploadingCount = rememberLastNonNull(uploadingCount.takeIf { it > 0 })
            AnimatedVisibility(
                visible = uploadingCount > 0,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastUploadingCount?.let { UploadingBanner(count = it) }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val planForPanel = rememberLastNonNull(pendingPlanApproval)
            AnimatedVisibility(
                visible = pendingPlanApproval != null,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { request ->
                    PlanApprovalPanel(
                        request = request,
                        onApprove = { viewModel.resolvePlanApproval(request.id, approved = true) },
                        onReject = { feedback ->
                            viewModel.resolvePlanApproval(request.id, approved = false, feedback = feedback)
                        },
                        sessionTitle = pendingPlanApprovalSessionTitle.ifBlank { request.sessionId },
                        enabled = pendingPlanApproval?.id == request.id
                    )
                }
            }

            if (recoveryItems.isNotEmpty()) {
                SessionRecoveryCard(
                    items = recoveryItems,
                    busy = isBusy || isCompacting || recoveryBusy,
                    onContinue = viewModel::continueRecovery,
                    onAbandon = viewModel::abandonRecovery,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }
            ChatInputBar(
                onFullScreenInputChanged = { fullScreenInputVisible = it },
                value = inputText,
                onValueChange = viewModel::updateInputDraft,
                onSend = sendMessage,
                enterToSend = settingsViewModel?.enterToSend?.collectAsStateWithLifecycle()?.value ?: false,
                onStop = {
                    revealedTypewriterKeys = playbacks.mapTo(mutableSetOf()) { it.entry.key }
                    viewModel.stopAgent()
                },
                isBusy = isBusy || isCompacting,
                workspaceViewModel = workspaceViewModel,
                hasRunningSessions = { viewModel.hasRunningSessionsInCurrentWorkspace() },
                onSwitchWorkspaceConfirmed = { viewModel.stopAllAndCloseTerminal() },
                activeProvider = activeProvider,
                providers = providers,
                modelMetadata = modelMetadata,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                currentGoal = currentGoal,
                onSetGoalPaused = { viewModel.setGoalPaused(it) },
                onClearGoal = { viewModel.clearGoal() },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                generationSpeed = generationSpeed,
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = ::takePhoto,
                slashCommands = slashCommands,
                fileMentionState = fileMentionState,
                onFileMentionOpen = viewModel::requestFileMention,
                onFileMentionDismiss = viewModel::dismissFileMention,
                matchFileMentions = viewModel::matchFileMentions,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                onMoveQueued = { from, to -> viewModel.moveQueuedRequest(from, to) },
                onEditQueued = { queuedEditing = it },
                onInterjectQueued = { viewModel.interjectQueuedRequest(it) },
                dashboardState = currentDashboardState,
                todoItems = currentTodoItems,
                sessionId = currentSessionId.orEmpty(),
                onTodoExpandedChange = { todoExpanded = it },
                forceCollapseDashboard = awaitingUserInput || imeVisible,
                onDashboardExpandedChange = { dashboardExpanded = it },
                onRefreshDashboard = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                onRefreshDashboardByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                tokenEstimated = contextUsages[currentSessionId]?.estimated ?: (sessionLastInputTokens <= 0 && currentSessionId != null),
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.let {
                        com.aicode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget(it)
                    } ?: 0
                    val usage = contextUsages[currentSessionId]?.takeIf { sessionLastInputTokens > 0 || isBusy }
                    if (contextLimit > 0) {
                        (usage?.inputTokens ?: sessionLastInputTokens).toFloat() / contextLimit
                    } else 0f
                },
                isScrolling = listState.isScrollInProgress,
                forceOpenModelSheet = onboardingStep == OnboardingStep.SIMULATE_CHOOSE_MODEL,
                onSelectModelInOnboarding = onSelectModelInOnboarding,
                onModelSheetDismiss = onDismissModelSheetInOnboarding,
                // 主页模型弹层的「测试」按钮：直接复用设置页的连通性测试链路，状态由弹层自己持有。
                onRunModelTest = settingsViewModel?.let { svm -> { p, m -> svm.runModelTest(p, m) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .onboardingTarget(OnboardingStep.SEND_MESSAGE)
            )
            } // 悬浮层结束

            // 悬浮授权弹窗：独立于底栏排版流，悬浮于输入框上方，避免撑大底栏高度导致消息列表被顶上去。
            // 用户上下滑动消息列表时跟随透明弱化，不遮挡阅读视线。
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            var permissionPanelHeightPx by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = pendingPermission != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { floatingLayerHeightPx.toDp() } + Spacing.xs)
                    .onGloballyPositioned { permissionPanelHeightPx = if (pendingPermission != null) it.size.height else 0 },
                enter = fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 3 } +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) +
                       slideOutVertically(tween(160)) { it / 3 } +
                       scaleOut(targetScale = 0.96f, animationSpec = tween(160))
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = dashboardCollapseActive,
                        isScrolling = listState.isScrollInProgress
                    )
                }
            }

            // 滚动到底部按钮：悬浮在输入框右上角上方（避让底栏及可能悬浮的授权弹窗），离底超过半屏时
            // 显示，不看滚动方向——往上翻历史后停住恰恰是最需要一键回底的时刻；滚动时跟随输入框淡出。
            val permissionOffsetPx = with(LocalDensity.current) {
                if (pendingPermission != null) permissionPanelHeightPx + Spacing.xs.toPx() else 0f
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + permissionOffsetPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
                    .graphicsLayer { alpha = if (listState.isScrollInProgress) 0.4f else 1f },
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ScrollToBottomButton(
                    onClick = {
                        followBottom = true
                        scope.launch { snapToBottom() }
                    }
                )
            }

            targetRewindMessageId?.let { targetId ->
                val targetMsg = messages.find { it.id == targetId }
                RewindOptionsBottomSheet(
                    promptSnippet = targetMsg?.content ?: "",
                    onOptionSelected = { option ->
                        viewModel.executeRewindOption(targetId, option) { text, attachments ->
                            viewModel.updateInputDraft(text)
                            pendingAttachments = attachments.map { it.toPendingAttachment() }
                        }
                    },
                    onDismissRequest = { viewModel.dismissRewindMenu() }
                )
            }

            messageForMenu?.let { message ->
                val clipboard = LocalClipboard.current
                val copyScope = rememberCoroutineScope()
                MessageActionsBottomSheet(
                    message = message,
                    onDismiss = { messageForMenu = null },
                    onEditClick = { editingMessage = message },
                    onCopyClick = {
                        copyScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content)))
                        }
                    },
                    onForkClick = { viewModel.forkFromMessage(message.id) },
                    onDeleteClick = { viewModel.deleteMessage(message.id) }
                )
            }

            editingMessage?.let { message ->
                EditMessageDialog(
                    initialText = message.content,
                    onDismiss = { editingMessage = null },
                    onConfirm = { newContent ->
                        viewModel.updateMessageContent(message.id, newContent)
                        editingMessage = null
                    }
                )
            }

            queuedEditing?.let { req ->
                EditMessageDialog(
                    initialText = req.request,
                    onDismiss = { queuedEditing = null },
                    onConfirm = { newContent ->
                        viewModel.updateQueuedRequest(req.id, newContent)
                        queuedEditing = null
                    }
                )
            }
        }
        }
    }

        // Dialog 是独立 window、不占父布局尺寸，挂在 Scaffold 之后即可覆盖整屏 ——
        // 平板双栏下不会只盖住聊天列，也不会被 MainActivity 画在最上层的全局背景水印压住。
        ImageViewerHost(state = imageViewerState, load = chatImageLoad)
        FilePreviewHost(state = filePreviewState, onEdit = { path -> defaultUriHandler.openUri("$path:1") })
    }
    }
}

@Composable
private fun ChatToolPreview(
    viewModel: AIAgentViewModel,
    sessionId: String?,
    state: ToolPreviewHostState,
    visible: Boolean,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.toolPreviewMessages.collectAsStateWithLifecycle()
    val preparing by viewModel.preparingToolArguments.collectAsStateWithLifecycle()
    val running by viewModel.runningToolPreviewSnapshot.collectAsStateWithLifecycle()
    val reader by viewModel.toolPreviewFileReader.collectAsStateWithLifecycle()
    ToolPreviewHost(
        sessionId = sessionId,
        messages = messages.takeIf { it.first == sessionId }?.second.orEmpty(),
        running = running.takeIf { it.first == sessionId }?.second.orEmpty(),
        preparing = preparing.takeIf { it.first == sessionId }?.second.orEmpty(),
        browserManager = viewModel.browserManager,
        state = state,
        visible = visible,
        onOpenBrowser = onOpenBrowser,
        readFileBytes = reader.takeIf { it.first == sessionId }?.second,
        modifier = modifier,
    )
}

/**
 * 记住最后一个非空值，供 [AnimatedVisibility] 的退出动画继续渲染旧内容。
 *
 * 用普通对象而不是 [androidx.compose.runtime.MutableState] 持有：这里只需要跨重组留住上一个值，
 * 不需要它自己触发重组（源值变化本身就会重组读取点），进快照系统反而会多引发一次无效重组。
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * 工具卡片入场动画的调度器：决定哪些消息该播入场、以及各自错开多久。
 *
 * 判据是「本调度器存续期间新追加到尾部」，而不是「timestamp 距今 N 秒内」——
 * 后者在切页返回时会把仍在时间窗内的那批消息再判成新消息，动画重播一遍。
 * 首次调用时列表里已有的消息一律记为存量；向上翻页加载进来的历史比已见最大时间戳更旧，
 * 同样不入场。会话切换时整个调度器重建，新会话的存量消息也不入场。
 */
private class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** 返回「消息 id → 入场延迟（ms）」；不在表内的消息直接显示。 */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}

/** 滚动到底部按钮：圆形，悬浮在输入框右上角，不在底部时显示。 */
@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(SCROLL_TO_BOTTOM_BTN_SIZE.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = FeatherIcons.ArrowDown,
                contentDescription = stringResource(R.string.common_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

