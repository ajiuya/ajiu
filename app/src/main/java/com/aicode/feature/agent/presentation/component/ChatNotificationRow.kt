package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.ChatNotificationTimelineEvent
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import com.aicode.feature.agent.domain.notification.AgentMessageAction
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Send
import compose.icons.feathericons.Tool

internal enum class ChatNotificationTone {
    TASK_SUCCESS,
    TASK_FAILURE,
    AGENT,
    COMMAND,
    USER,
}

internal data class ChatNotificationRowModel(
    val source: String,
    val title: String,
    val detail: String,
    val time: String? = null,
    val tone: ChatNotificationTone,
    val icon: ImageVector,
    val isBackgroundTask: Boolean = false,
    val backgroundTaskExitCode: String? = null,
)

internal fun subAgentInstructionNotification(
    event: SubAgentInteractionMetadata,
    summary: SubAgentInteractionSummary?,
): ChatRenderItem {
    val target = summary?.agentName?.takeIf { it.isNotBlank() }
        ?: event.agentName?.takeIf { it.isNotBlank() }
        ?: event.title?.takeIf { it.isNotBlank() }
        ?: "子代理"
    val correction = event.origin != "USER"
    val displayTarget = target.removeSuffix("代理")
    val model = ChatNotificationRowModel(
        source = "主代理",
        title = if (correction) "给${displayTarget}代理发送纠偏" else "给${displayTarget}代理添加指令",
        detail = event.preview,
        time = formatClockTime(event.timestamp),
        tone = ChatNotificationTone.COMMAND,
        icon = if (correction) FeatherIcons.Send else FeatherIcons.Edit3,
    )
    return ChatRenderItem(
        message = AgentUIMessage(
            id = "notification:${event.id}",
            role = com.aicode.feature.agent.presentation.MessageRole.ASSISTANT,
            content = "",
            timestamp = event.timestamp,
        ),
        key = "chat-notification:${event.id}",
        contentType = CHAT_NOTIFICATION_CONTENT_TYPE,
        notification = model,
    )
}

internal fun projectChatNotificationItems(
    items: List<ChatRenderItem>,
    messages: List<AgentUIMessage>,
    timeline: List<ChatNotificationTimelineEvent>,
    interactions: List<SubAgentInteractionMetadata>,
    summaries: Map<String, SubAgentInteractionSummary>,
): List<ChatRenderItem> {
    val parsedNotices = messages.asSequence()
        .filter { it.isBackgroundNotification || it.role == com.aicode.feature.agent.presentation.MessageRole.TOOL }
        .flatMap { message ->
            parseSubAgentNotificationMetadata(message.content, toolResult = message.role == com.aicode.feature.agent.presentation.MessageRole.TOOL)
                .asSequence().map { notice -> message to notice }
        }
        .toList()
    val mixedBackgroundNotices = parsedNotices.groupBy { it.first.id }
        .filter { (_, entries) ->
            entries.first().first.isBackgroundNotification && entries.any { it.second.kind != null } && entries.any { it.second.kind == null }
        }
    val mixedBackgroundMessageIds = mixedBackgroundNotices.keys
    val mixedBackgroundEventIds = mixedBackgroundNotices.values.flatten()
        .filter { it.second.kind != null }
        .mapNotNullTo(hashSetOf()) { (_, notice) -> notice.eventId.takeIf(String::isNotBlank) }
    val mixedBackgroundSignatures = mixedBackgroundNotices.values.flatten()
        .filter { it.second.kind != null }
        .mapTo(hashSetOf()) { (_, notice) -> notificationSignature(notice) }
    val effectiveTimeline = timeline.filterNot { event ->
        val notice = event.entry.notification
        notice.eventId in mixedBackgroundEventIds ||
            (notice.eventId.isBlank() && notificationSignature(notice) in mixedBackgroundSignatures)
    }
    val sourceMessageIds = timeline.mapNotNullTo(hashSetOf()) { it.sourceMessageId }
    val replaceableBackgroundMessageIds = parsedNotices.groupBy { it.first.id }
        .filter { (_, entries) -> entries.first().first.isBackgroundNotification && entries.all { it.second.kind != null } }
        .keys
    val loadedNotices = parsedNotices.filter { (_, notice) -> notice.kind != null }
    val loadedMessageIds = messages.mapTo(hashSetOf()) { it.id }
    fun isReplaced(item: ChatRenderItem): Boolean =
        item.key.startsWith("chat-notification:") || item.message.id in sourceMessageIds ||
            item.message.id in replaceableBackgroundMessageIds

    val turnKeyByMessageId = buildMap {
        items.forEach { item ->
            val turnKey = item.chatTurnKey ?: item.turnHeader?.key
            if (turnKey != null) put(item.message.id, turnKey)
            item.turnProcess.forEach { processItem ->
                processItem.chatTurnKey?.let { put(processItem.message.id, it) }
            }
        }
    }
    val retained = items.mapNotNull { item ->
        if (item.turnHeader != null) item.copy(turnProcess = item.turnProcess.filterNot(::isReplaced))
        else item.takeUnless(::isReplaced)
    }.toMutableList()
    val carrierMessageIdByEventId = loadedNotices.associate { (message, notice) ->
        notice.eventId.ifBlank { "legacy:${message.id}:${notice.ordinal}" } to message.id
    }
    val rows = linkedMapOf<String, Pair<ChatRenderItem, String?>>()
    val signatures = hashSetOf<String>()
    effectiveTimeline.forEach { event ->
        val notice = event.entry.notification
        val sourceMessageId = event.sourceMessageId ?: carrierMessageIdByEventId[notice.eventId]
        rows[notice.eventId] = notificationTimelineItem(notice, event.entry.createdAt) to sourceMessageId
        signatures += notificationSignature(notice)
    }
    val compatibilityRows = loadedNotices.asSequence()
        .filterNot { (message, notice) ->
            message.id in mixedBackgroundMessageIds || notice.eventId in rows || notificationSignature(notice) in signatures
        }
        .onEach { (_, notice) -> signatures += notificationSignature(notice) }
        .map { (message, notice) ->
            val id = notice.eventId.ifBlank { "legacy:${message.id}:${notice.ordinal}" }
            id to (notificationMetadataTimelineItem(notice, message.timestamp) to message.id)
        }
        .toList()
    compatibilityRows.forEach { (id, row) -> rows.putIfAbsent(id, row) }
    interactions.asSequence()
        .filter { it.kind == "MESSAGE_FROM_PARENT" }
        .sortedWith(compareBy<SubAgentInteractionMetadata> { it.timestamp }.thenBy { it.id })
        .forEach { event ->
            rows.putIfAbsent(event.id, subAgentInstructionNotification(event, summaries[event.subSessionId]) to event.sourceMessageId)
        }
    val earliestTimestamp = messages.minOfOrNull { it.timestamp }
    val orderedItems = items.flatMap { item -> listOf(item) + item.turnProcess }
    val sortedRows = rows.values.sortedWith(compareBy<Pair<ChatRenderItem, String?>> { it.first.message.timestamp }.thenBy { it.first.key })
    for ((row, sourceMessageId) in sortedRows) {
        val sourceTurnKey = sourceMessageId?.let(turnKeyByMessageId::get)
        val timestampItem = orderedItems.lastOrNull { it.chatTurnKey != null && it.message.timestamp <= row.message.timestamp }
        val targetTurnKey = sourceTurnKey ?: timestampItem?.chatTurnKey
        if (targetTurnKey == null &&
            (sourceMessageId == null || sourceMessageId !in loadedMessageIds) &&
            earliestTimestamp != null && row.message.timestamp < earliestTimestamp
        ) {
            continue
        }
        val turnHeaderIndex = targetTurnKey?.let { key -> retained.indexOfFirst { it.turnHeader?.key == key } } ?: -1
        if (turnHeaderIndex >= 0) {
            val header = retained[turnHeaderIndex]
            val process = header.turnProcess.toMutableList()
            val processIndex = process.indexOfLast { it.message.timestamp <= row.message.timestamp }
            process.add(processIndex + 1, row.copy(chatTurnKey = targetTurnKey))
            retained[turnHeaderIndex] = header.copy(turnProcess = process)
        } else {
            val positioned = row.copy(chatTurnKey = targetTurnKey)
            val index = retained.indexOfLast {
                it.turnHeader == null && it.subAgentCapsule == null && it.message.timestamp <= row.message.timestamp
            }
            retained.add(index + 1, positioned)
        }
    }
    return retained
}

internal fun notificationTimelineItem(notification: PendingNotification, timestamp: Long): ChatRenderItem {
    val kind = when (notification.kind) {
        AgentNotificationKind.BACKGROUND_TASK -> "background_task"
        AgentNotificationKind.SUBAGENT -> "subagent"
        AgentNotificationKind.AGENT_MESSAGE -> "agent_message"
        AgentNotificationKind.USER_MESSAGE -> "user_message"
    }
    val status = when {
        notification.kind in setOf(AgentNotificationKind.AGENT_MESSAGE, AgentNotificationKind.USER_MESSAGE) -> "message"
        notification.outcome == NotificationOutcome.COMPLETED -> "completed"
        notification.outcome == NotificationOutcome.FAILED -> "failed"
        else -> "stopped"
    }
    val summary = when (notification.kind) {
        AgentNotificationKind.BACKGROUND_TASK -> "后台任务「${notification.title}」已结束"
        AgentNotificationKind.SUBAGENT -> when (notification.outcome) {
            NotificationOutcome.COMPLETED -> "子代理「${notification.title}」已执行完成"
            NotificationOutcome.FAILED -> "子代理「${notification.title}」已执行失败"
            NotificationOutcome.STOPPED -> "子代理「${notification.title}」已停止"
        }
        AgentNotificationKind.AGENT_MESSAGE -> when (notification.action) {
            AgentMessageAction.ADD_INSTRUCTION -> "给${notification.targetTitle ?: notification.title}代理添加指令"
            AgentMessageAction.CORRECTION -> "给${notification.targetTitle ?: notification.title}代理发送纠偏"
            AgentMessageAction.MESSAGE -> if (notification.fromParent) "主代理发来一条消息"
                else "${notification.title.ifBlank { "子代理" }}发来一条消息"
        }
        AgentNotificationKind.USER_MESSAGE -> "用户补充了一条消息"
    }
    val metadata = SubAgentNotificationMetadata(
        ordinal = 0,
        eventId = notification.eventId,
        sourceId = notification.sourceId,
        kind = kind,
        title = notification.title,
        summary = summary,
        status = status,
        body = notification.message ?: notification.resultExcerpt.orEmpty(),
        sourceTitle = notification.title,
        action = notification.action,
        targetTitle = notification.targetTitle.orEmpty(),
        fromParent = notification.fromParent,
        taskTitle = notification.title,
        command = notification.command.orEmpty(),
        exitCode = notification.exitCode?.toString().orEmpty(),
        detail = notification.detail.orEmpty(),
        tailOutput = notification.tailOutput.orEmpty(),
        raw = "",
    )
    return notificationMetadataTimelineItem(metadata, timestamp)
}

private fun notificationMetadataTimelineItem(notice: SubAgentNotificationMetadata, timestamp: Long): ChatRenderItem {
    val id = notice.eventId.ifBlank { "legacy:$timestamp:${notice.ordinal}:${notice.sourceId}" }
    return ChatRenderItem(
        message = AgentUIMessage(
            id = "notification:$id",
            role = com.aicode.feature.agent.presentation.MessageRole.ASSISTANT,
            content = "",
            timestamp = timestamp,
        ),
        key = "chat-notification:$id",
        contentType = CHAT_NOTIFICATION_CONTENT_TYPE,
        notification = notificationRowModel(notice, formatClockTime(timestamp)),
    )
}

private fun notificationSignature(notification: PendingNotification): String = listOf(
    notification.kind.name,
    if (notification.kind == AgentNotificationKind.USER_MESSAGE) "" else notification.sourceId,
    notification.outcome.name,
    notification.title,
    notification.command.orEmpty(),
    notification.exitCode?.toString().orEmpty(),
    notification.message ?: notification.resultExcerpt.orEmpty(),
    notification.detail.orEmpty(),
    notification.tailOutput.orEmpty(),
    notification.action.name,
    notification.targetTitle.orEmpty(),
    notification.fromParent.toString(),
).joinToString("\u0000")

private fun notificationSignature(notice: SubAgentNotificationMetadata): String = listOf(
    when (notice.kind) {
        "background_task" -> AgentNotificationKind.BACKGROUND_TASK.name
        "subagent" -> AgentNotificationKind.SUBAGENT.name
        "agent_message" -> AgentNotificationKind.AGENT_MESSAGE.name
        "user_message" -> AgentNotificationKind.USER_MESSAGE.name
        else -> ""
    },
    if (notice.kind == "user_message") "" else notice.sourceId,
    when (notice.status) {
        "completed" -> NotificationOutcome.COMPLETED.name
        "failed" -> NotificationOutcome.FAILED.name
        "stopped" -> NotificationOutcome.STOPPED.name
        else -> "COMPLETED"
    },
    notice.taskTitle.ifBlank { notice.title },
    notice.command,
    notice.exitCode,
    notice.body,
    notice.detail,
    notice.tailOutput,
    notice.action.name,
    notice.targetTitle,
    notice.fromParent.toString(),
).joinToString("\u0000")

internal fun notificationRowModel(
    notice: SubAgentNotificationMetadata,
    time: String? = null,
): ChatNotificationRowModel {
    val sourceTitle = notice.sourceTitle.ifBlank { notice.title }
    return when (notice.kind) {
        "background_task" -> {
            val exit = notice.exitCode.takeIf { it.isNotBlank() }
            ChatNotificationRowModel(
                source = "",
                title = notice.taskTitle.ifBlank { notice.title },
                detail = "",
                time = time,
                tone = if (notice.status == "completed") ChatNotificationTone.TASK_SUCCESS
                else ChatNotificationTone.TASK_FAILURE,
                icon = if (notice.status == "completed") FeatherIcons.Check else FeatherIcons.Tool,
                isBackgroundTask = true,
                backgroundTaskExitCode = exit,
            )
        }
        "subagent" -> ChatNotificationRowModel(
            source = notice.title.ifBlank { notice.sourceTitle.ifBlank { "子代理" } },
            title = notice.summary.ifBlank {
                when (notice.status) {
                    "completed" -> "子代理已执行完成"
                    "failed" -> "子代理已执行失败"
                    else -> "子代理任务已结束"
                }
            },
            detail = notice.body.ifBlank { notice.detail },
            time = time,
            tone = if (notice.status == "completed") ChatNotificationTone.AGENT else ChatNotificationTone.TASK_FAILURE,
            icon = if (notice.status == "completed") FeatherIcons.MessageSquare else FeatherIcons.Tool,
        )
        "agent_message" -> {
            val target = (notice.targetTitle.ifBlank { notice.title.ifBlank { "子代理" } }).removeSuffix("代理")
            val commandTitle = when (notice.action) {
                AgentMessageAction.ADD_INSTRUCTION -> "给${target}代理添加指令"
                AgentMessageAction.CORRECTION -> "给${target}代理发送纠偏"
                AgentMessageAction.MESSAGE -> if (notice.fromParent) "主代理发来一条消息"
                else "${sourceTitle.ifBlank { "子代理" }}发来一条消息"
            }
            ChatNotificationRowModel(
                source = if (notice.action == AgentMessageAction.MESSAGE && !notice.fromParent) {
                    sourceTitle.ifBlank { "子代理" }
                } else "主代理",
                title = commandTitle,
                detail = notice.body,
                time = time,
                tone = if (notice.action == AgentMessageAction.MESSAGE) ChatNotificationTone.AGENT
                else ChatNotificationTone.COMMAND,
                icon = when (notice.action) {
                    AgentMessageAction.ADD_INSTRUCTION -> FeatherIcons.Edit3
                    AgentMessageAction.CORRECTION -> FeatherIcons.Send
                    AgentMessageAction.MESSAGE -> FeatherIcons.MessageSquare
                },
            )
        }
        "user_message" -> ChatNotificationRowModel(
            source = "用户",
            title = "用户补充了一条消息",
            detail = notice.body,
            time = time,
            tone = ChatNotificationTone.USER,
            icon = FeatherIcons.MessageSquare,
        )
        else -> ChatNotificationRowModel(
            source = sourceTitle.ifBlank { "系统事件" },
            title = notice.summary.ifBlank { "应用事件通知" },
            detail = notice.body.ifBlank { notice.detail },
            time = time,
            tone = ChatNotificationTone.AGENT,
            icon = FeatherIcons.Tool,
        )
    }
}

@Composable
internal fun ChatNotificationRow(
    model: ChatNotificationRowModel,
    modifier: Modifier = Modifier,
) {
    val palette = notificationPalette(model.tone)
    val title = if (model.isBackgroundTask) {
        val taskTitle = model.title.ifBlank { stringResource(R.string.chat_notification_background_task_default_name) }
        model.backgroundTaskExitCode?.let {
            stringResource(R.string.chat_notification_background_task_finished_with_exit, taskTitle, it)
        } ?: stringResource(R.string.chat_notification_background_task_finished, taskTitle)
    } else model.title
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.container)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(palette.accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier
                            .size(23.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = model.icon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = palette.accent,
                        )
                    }
                    if (model.source.isNotBlank()) {
                        Text(
                            text = model.source,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                model.time?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            model.detail.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(start = 30.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class NotificationPalette(val accent: Color, val container: Color)

@Composable
private fun notificationPalette(tone: ChatNotificationTone): NotificationPalette = when (tone) {
    ChatNotificationTone.TASK_SUCCESS -> NotificationPalette(
        MaterialTheme.semanticColors.success,
        MaterialTheme.semanticColors.successContainer.copy(alpha = 0.78f),
    )
    ChatNotificationTone.TASK_FAILURE -> NotificationPalette(
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f),
    )
    ChatNotificationTone.AGENT -> NotificationPalette(
        MaterialTheme.semanticColors.info,
        MaterialTheme.semanticColors.infoContainer.copy(alpha = 0.78f),
    )
    ChatNotificationTone.COMMAND -> NotificationPalette(
        MaterialTheme.semanticColors.warning,
        MaterialTheme.semanticColors.warningContainer.copy(alpha = 0.78f),
    )
    ChatNotificationTone.USER -> NotificationPalette(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
    )
}
