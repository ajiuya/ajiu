package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.SubAgentExecutionPage
import com.aicode.feature.agent.presentation.SubAgentInstructionFailure
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import com.aicode.feature.agent.presentation.SubAgentInteractionPage
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * `task` 工具的专属卡片宿主：操作用 CompositionLocal 下发，避免参数一路穿过
 * 消息列表、分组与气泡三层组合。
 */
data class SubAgentCardHost(
    val states: Map<String, SubAgentCardState> = emptyMap(),
    val onOpen: (String) -> Unit = {},
    val onStop: (String) -> Unit = {},
    val summaries: Map<String, SubAgentInteractionSummary> = emptyMap(),
    val conversation: (String) -> Flow<SubAgentInteractionPage> = {
        flowOf(SubAgentInteractionPage(loaded = true))
    },
    val execution: (String) -> Flow<SubAgentExecutionPage> = {
        flowOf(SubAgentExecutionPage(loaded = true))
    },
    // Several expanded cards may hold a lease for the same child.
    val onExpanded: (String, Boolean) -> Unit = { _, _ -> },
    val onLoadMore: (String) -> Unit = {},
    val onLoadMoreExecution: (String) -> Unit = {},
    val onSend: suspend (String, String) -> SubAgentInstructionResult = { _, _ ->
        SubAgentInstructionResult(sent = false, failure = SubAgentInstructionFailure.SESSION_MISSING)
    },
    val onOpenConfirm: (String) -> Unit = {},
    val onContentInteraction: () -> Unit = {},
)

val LocalSubAgentCardHost = staticCompositionLocalOf<SubAgentCardHost?> { null }

/**
 * 从 `task(create)` 的工具参数与结果里解析出的派发信息（支持单个与批量派发）。
 */
internal data class ParsedSubAgentDispatch(
    val items: List<ParsedSubAgentDispatchItem>,
    val globalWarning: String? = null
) {
    // 兼容单项快捷访问
    val subSessionId: String? get() = items.firstOrNull()?.subSessionId
    val agentName: String? get() = items.firstOrNull()?.agentName
    val description: String get() = items.firstOrNull()?.description.orEmpty()
    val tier: String? get() = items.firstOrNull()?.tier
    val model: String? get() = items.firstOrNull()?.model
    val warning: String? get() = items.firstOrNull()?.warning ?: globalWarning
}

internal data class ParsedSubAgentDispatchItem(
    val subSessionId: String?,
    val agentName: String?,
    val description: String,
    val tier: String?,
    val model: String?,
    val warning: String?,
    val claimable: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true }

private fun parseDispatchObject(raw: String): JsonObject? {
    if (raw.length > SUB_AGENT_METADATA_MAX_CHARS) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (char in raw) {
        if (inString) when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '"' -> inString = false
        } else when (char) {
            '"' -> inString = true
            '{', '[' -> if (++depth > 256) return null
            '}', ']' -> depth--
        }
    }
    return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private fun JsonObject.dispatchString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun validDispatchArgs(args: JsonObject): Boolean {
    val fields = listOf("action", "id", "description", "prompt", "agent", "tier", "message")
    if (fields.any { key -> args[key]?.let { it != JsonNull && (it !is JsonPrimitive || !it.isString) } == true }) return false
    if ("action" in args && args["action"] == JsonNull) return false
    if ("tasks" !in args) return true
    val tasks = args["tasks"] as? JsonArray ?: return false
    return tasks.size <= SUB_AGENT_METADATA_MAX_ITEMS && tasks.all { task ->
        task is JsonObject && fields.all { key ->
            val value = task[key]
            value == null || value == JsonNull || value is JsonPrimitive && value.isString
        }
    }
}

private fun isSubAgentDispatchClaimable(item: JsonObject?, transportStatus: String?): Boolean {
    if (item == null || transportStatus?.lowercase(Locale.ROOT) !in setOf("success", "partial")) return false
    val id = item["id"] as? JsonPrimitive ?: return false
    if (!id.isString || id.contentOrNull.isNullOrBlank()) return false
    fun recognizedField(name: String, allowed: Set<String>): Boolean {
        val field = item[name] ?: return true
        val value = (field as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.trim()?.lowercase(Locale.ROOT) ?: return false
        return value in allowed
    }
    return recognizedField("status", setOf("success", "completed", "running", "created")) &&
        recognizedField("state", setOf("running", "completed", "queued", "created", "awaiting_confirmation")) &&
        (item["error"] == null || item["error"] == JsonNull)
}

/**
 * 解析 `task(create)`：支持单个与批量任务派发的双重解析。
 *
 * 结果里 `data` 既可能是对象也可能是被序列化成字符串的对象；
 * 解析不出 id 时卡片仍按参数渲染，只是不能跳转。
 */
internal fun parseSubAgentDispatch(argsJson: String?, content: String?): ParsedSubAgentDispatch? {
    if ((argsJson?.length ?: 0) > SUB_AGENT_METADATA_MAX_CHARS ||
        (content?.length ?: 0) > SUB_AGENT_METADATA_MAX_CHARS) return null
    val args = argsJson?.let(::parseDispatchObject)?.takeIf(::validDispatchArgs) ?: return null
    val action = if ("action" in args) args.dispatchString("action")?.trim()?.lowercase(Locale.ROOT) else "create"
    if (action != "create") return null
    val result = content?.let { parseDispatchObject(it.withoutToolStatusPrefix()) }
    val status = result?.dispatchString("status")
    if (status == "error") return null
    val data = when (val element = result?.get("data")) {
        is JsonObject -> element
        is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull?.let(::parseDispatchObject)
        else -> null
    }
    if (data?.containsKey("subagents") == true && data["subagents"] !is JsonArray) return null
    if (((data?.get("subagents") as? JsonArray)?.size ?: 0) > SUB_AGENT_METADATA_MAX_ITEMS) return null

    // 1. 尝试从 result 的 subagents 数组解析批量结果
    val resultSubagents = (data?.get("subagents") as? JsonArray)?.map { el ->
        val obj = el as? JsonObject
        if (obj == null) return@map ParsedSubAgentDispatchItem(
            subSessionId = null, agentName = null, description = el.toString(),
            tier = null, model = null, warning = null, claimable = false,
        )
        ParsedSubAgentDispatchItem(
            subSessionId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            agentName = obj.dispatchString("agent")?.trim()?.takeIf { it.isNotBlank() },
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
            warning = (obj["warning"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: if (!isSubAgentDispatchClaimable(obj, status)) {
                    (obj["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: (obj["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                } else null,
            claimable = isSubAgentDispatchClaimable(obj, status),
        )
    }

    // 2. 尝试从 args 的 tasks 数组解析任务入参
    val argTasks = (args?.get("tasks") as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        val desc = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prompt?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        ParsedSubAgentDispatchItem(
            subSessionId = null,
            agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = desc,
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = null,
            warning = null,
            claimable = false,
        )
    }

    val items: List<ParsedSubAgentDispatchItem> = when {
        !resultSubagents.isNullOrEmpty() -> {
            resultSubagents.mapIndexed { idx, item ->
                val resultItem = (data?.get("subagents") as? JsonArray)?.getOrNull(idx) as? JsonObject
                val fallbackName = if (!argTasks.isNullOrEmpty()) argTasks.getOrNull(idx)?.agentName
                    else if (resultSubagents.size == 1) args.dispatchString("agent") else null
                item.copy(
                    description = item.description.ifBlank { argTasks?.getOrNull(idx)?.description.orEmpty() },
                    agentName = item.agentName ?: fallbackName?.takeIf {
                        item.claimable && resultItem?.containsKey("agent") == false
                    },
                )
            }
        }
        !argTasks.isNullOrEmpty() -> argTasks
        else -> {
            // 单个子代理处理
            val agentName = if (data?.containsKey("agent") == true) data.dispatchString("agent")
                else args.dispatchString("agent")?.takeIf { it.isNotBlank() }
            val description = (args?.get("description") as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: (args?.get("prompt") as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.lineSequence()?.firstOrNull()?.trim().orEmpty()

            val singleItem = ParsedSubAgentDispatchItem(
                subSessionId = (data?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                agentName = agentName,
                description = description,
                tier = (data?.get("tier") as? JsonPrimitive)?.contentOrNull,
                model = (data?.get("model") as? JsonPrimitive)?.contentOrNull,
                warning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                claimable = isSubAgentDispatchClaimable(data, status),
            )
            listOf(singleItem)
        }
    }

    if (items.isEmpty()) return null

    return ParsedSubAgentDispatch(
        items = items,
        globalWarning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    )
}

internal fun unrepresentedSubAgentDispatchItems(
    items: List<ParsedSubAgentDispatchItem>,
    representedIds: Set<String>,
): List<ParsedSubAgentDispatchItem> = items.filterNot {
    it.claimable && it.subSessionId != null && it.subSessionId in representedIds
}

internal fun subAgentDispatchWarnings(dispatch: ParsedSubAgentDispatch): List<String> =
    (listOfNotNull(dispatch.globalWarning) + dispatch.items.mapNotNull {
        it.warning?.takeIf { _ -> it.subSessionId != null && it.claimable }
    }).filter { it.isNotBlank() }.distinct()

@Composable
internal fun SubAgentDispatchCard(dispatch: ParsedSubAgentDispatch) {
    val host = LocalSubAgentCardHost.current
    val items = unrepresentedSubAgentDispatchItems(dispatch.items, LocalRepresentedSubAgentIds.current)
    val warnings = subAgentDispatchWarnings(dispatch)
    if (items.isEmpty() && warnings.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (items.isNotEmpty() && items.all { it.subSessionId == null }) SubAgentDispatchSummary(items.size)
        warnings.forEach { SubAgentWarningBanner(it) }
        items.forEachIndexed { index, item ->
            val sessionId = item.subSessionId
            if (sessionId != null && item.claimable && host?.summaries?.get(sessionId)?.subSessionId == sessionId) {
                SubAgentInteractionCapsule(
                    sessionId, title = item.description, fallbackAgentName = item.agentName,
                )
            } else {
                SubAgentDispatchItemCard(index + 1, item, liveState = null, onOpen = { host?.onOpen?.invoke(it) })
            }
        }
    }
}

/** 顶部 Summary：图标 + 标题 + 数量统计 + 派发状态。 */
@Composable
private fun SubAgentDispatchSummary(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Cpu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.subagent_dispatch_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.subagent_dispatch_count, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        SubAgentDispatchedChip()
    }
}

/**
 * 单个子代理任务卡片项：序号（01, 02...）+ 身份/模型 + 描述 + 跳转箭头。
 *
 * [liveState] 来自 ViewModel 的实时状态（按子会话 id 索引）：本进程内派发的子代理会随
 * 生命周期刷新状态药丸并展示结果摘要；为 null（历史消息回放、进程重启后）时退回静态
 * 「已派发」展示，不会渲染假的「运行中」。
 */
@Composable
private fun SubAgentDispatchItemCard(
    index: Int,
    item: ParsedSubAgentDispatchItem,
    liveState: SubAgentCardState?,
    onOpen: (String) -> Unit
) {
    val host = LocalSubAgentCardHost.current
    val hasSession = item.claimable && item.subSessionId?.let { host?.summaries?.get(it)?.sessionExists } == true

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (hasSession) {
                        Modifier.clickable { onOpen(item.subSessionId!!) }
                    } else Modifier
                )
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // 序号：01, 02...（对标网络搜索）
                Text(
                    text = index.toString().padStart(2, '0'),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.agentName ?: stringResource(R.string.subagent_card_default_agent),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        modelBadge(item.tier, item.model)?.let { badge ->
                            Spacer(Modifier.width(Spacing.xs))
                            Surface(
                                shape = RoundedCornerShape(Radius.xs),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        SubAgentStatusChip(liveState?.status)
                        if (hasSession) {
                            Spacer(Modifier.width(Spacing.xs))
                            Icon(
                                imageVector = FeatherIcons.ChevronRight,
                                contentDescription = stringResource(R.string.subagent_card_open),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 完成后的结果摘要（与完成通知同一份）：多数场景无需再进子会话即可看到结论
                    liveState?.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = excerpt,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    liveState?.changedFiles?.takeIf { it.isNotEmpty() }?.let { files ->
                        Text(
                            text = stringResource(R.string.subagent_card_changed_files, files.size),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    item.warning?.let { warning ->
                        Spacer(Modifier.height(Spacing.xs / 2))
                        SubAgentWarningBanner(warning)
                    }
                }
            }
        }
    }
}

/** 警告/回退提示条。 */
@Composable
private fun SubAgentWarningBanner(warning: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xs),
        color = MaterialTheme.semanticColors.warningContainer.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.semanticColors.warning.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.warning,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.onWarningContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 发出时的状态药丸：指示子代理任务已成功派发。 */
@Composable
private fun SubAgentDispatchedChip() {
    SubAgentStatusPill(
        label = stringResource(R.string.subagent_interaction_dispatch_unconfirmed),
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 单项实时状态药丸：随子代理生命周期刷新（运行中/已完成/失败/已停止）。
 * [status] 为 null——历史消息回放、进程重启后拿不到实时状态——退回静态「已派发」，
 * 不渲染可能失真的「运行中」。
 */
@Composable
private fun SubAgentStatusChip(status: SubAgentCardStatus?) {
    when (status) {
        null -> SubAgentDispatchedChip()
        SubAgentCardStatus.RUNNING -> SubAgentStatusPill(
            label = stringResource(R.string.subagent_card_status_running),
            color = MaterialTheme.colorScheme.primary
        )
        SubAgentCardStatus.COMPLETED -> SubAgentStatusPill(
            label = stringResource(R.string.subagent_card_status_completed),
            color = MaterialTheme.semanticColors.success
        )
        SubAgentCardStatus.FAILED -> SubAgentStatusPill(
            label = stringResource(R.string.subagent_card_status_failed),
            color = MaterialTheme.colorScheme.error
        )
        SubAgentCardStatus.STOPPED -> SubAgentStatusPill(
            label = stringResource(R.string.subagent_card_status_stopped),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 状态药丸通用形制：色点 + 文本，[color] 决定整体色调。 */
@Composable
private fun SubAgentStatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 档位 + 模型：档位是「轻量/均衡/强力」，模型是实际落地的 model id；inherit 时不显示模型。 */
@Composable
private fun modelBadge(tier: String?, model: String?): String? {
    val tierStr = tierLabel(tier)
    val modelStr = model?.takeIf { it.isNotBlank() && it != "inherit" }
    return when {
        tierStr != null && modelStr != null -> "$tierStr · $modelStr"
        tierStr != null -> tierStr
        modelStr != null -> modelStr
        else -> null
    }
}

@Composable
private fun tierLabel(tier: String?): String? = when (tier?.lowercase()) {
    "light" -> stringResource(R.string.settings_subagent_tier_light)
    "standard" -> stringResource(R.string.settings_subagent_tier_standard)
    "strong" -> stringResource(R.string.settings_subagent_tier_strong)
    else -> null
}
