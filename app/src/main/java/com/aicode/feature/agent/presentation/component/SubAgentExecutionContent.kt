package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.SubAgentExecutionPage
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun SubAgentExecutionContent(subSessionId: String, host: SubAgentCardHost?) {
    var expanded by rememberSaveable(subSessionId) { mutableStateOf(false) }
    val canRead = host?.summaries?.get(subSessionId)?.let { it.subSessionId == subSessionId && it.sessionExists } == true
    val toggle = { host?.onContentInteraction?.invoke(); expanded = !expanded }
    val page by rememberSubAgentPage(
        subSessionId = subSessionId,
        expanded = expanded,
        hostAvailable = canRead,
        initial = SubAgentExecutionPage(),
        source = { host?.execution?.invoke(subSessionId) ?: flowOf(SubAgentExecutionPage(loaded = true)) },
        onFailure = { previous, failure -> previous.copy(loaded = true, error = failure.message.orEmpty()) },
    )
    val toggleLabel = stringResource(if (expanded) R.string.subagent_interaction_collapse_execution else R.string.subagent_interaction_expand_execution)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = toggle)
            .semantics { stateDescription = toggleLabel }.heightIn(min = Spacing.xxl).padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.subagent_interaction_execution), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                null, modifier = Modifier.size(Spacing.lg), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            if (canRead && !page.loaded && page.error == null) {
                Text(stringResource(R.string.subagent_interaction_loading_execution), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page.error?.let { SubAgentPageError(it) }
            if (page.streamingText.isNotBlank()) {
                Text(stringResource(R.string.subagent_interaction_streaming), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.semanticColors.info)
                SubAgentInteractionMarkdown("$subSessionId:stream", page.streamingText)
            }
            SubAgentExecutionRecords(subSessionId, page, host?.takeIf { canRead })
        }
    }
}

private data class SubAgentExecutionRecord(val message: AgentUIMessage, val running: RunningToolOutput? = null)

@Composable
private fun SubAgentExecutionRecords(subSessionId: String, page: SubAgentExecutionPage, host: SubAgentCardHost?) {
    val records = remember(page.messages, page.runningTools) {
        val running = page.runningTools.associateBy { it.messageId }
        val ids = page.messages.mapTo(HashSet()) { it.id }
        buildList {
            page.messages.filterNot {
                it.isCompactionMarker || it.isContextSummary || it.isBackgroundNotification ||
                    (it.role == MessageRole.ASSISTANT && it.content.isBlank() && it.attachments.isEmpty())
            }.forEach { add(SubAgentExecutionRecord(it.copy(reasoning = null), running[it.id])) }
            page.runningTools.filterNot { it.messageId in ids }.forEach {
                add(SubAgentExecutionRecord(AgentUIMessage(
                    id = it.messageId, role = MessageRole.TOOL, content = "", timestamp = 0L,
                    toolName = it.toolName, toolArgs = it.toolArgs,
                ), it))
            }
        }
    }
    var fromLatest by rememberSaveable(subSessionId) { mutableIntStateOf(0) }
    val window = subAgentRecordWindow(records, fromLatest)
    if (page.loaded && records.isEmpty() && page.streamingText.isBlank() && page.error == null) {
        Text(stringResource(R.string.subagent_interaction_empty_execution), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (page.hasMore) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            TextButton(onClick = {
                host?.onContentInteraction?.invoke()
                fromLatest = window.pageCount
                host?.onLoadMoreExecution?.invoke(subSessionId)
            }, enabled = host != null, modifier = Modifier.heightIn(min = Spacing.xxl),
                contentPadding = PaddingValues(Spacing.xs)) {
                Text(stringResource(R.string.subagent_interaction_load_earlier_execution), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    SubAgentRecordNavigation(window,
        onOlder = { fromLatest = window.pageFromLatest + 1 }, onNewer = { fromLatest = window.pageFromLatest - 1 })
    window.records.forEach { record -> key(record.message.id) { SubAgentExecutionRecordContent(subSessionId, record) } }
}

@Composable
private fun SubAgentExecutionRecordContent(subSessionId: String, record: SubAgentExecutionRecord) {
    val message = record.message
    val onInteraction = LocalSubAgentCardHost.current?.onContentInteraction ?: {}
    var toolExpanded by rememberSaveable(subSessionId, message.id) { mutableStateOf(false) }
    if (message.role == MessageRole.TOOL) {
        if (record.running == null && canRenderSubAgentToolBody(message)) {
            ToolMessageBody(message = message, expandedOverride = toolExpanded,
                onExpandedChange = { onInteraction(); toolExpanded = it }, durationMs = message.toolDurationMs)
        } else {
            // Nested task/notification results use raw details, never the dispatch renderer.
            SubAgentRawExecutionTool(record, toolExpanded) { onInteraction(); toolExpanded = !toolExpanded }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (message.role == MessageRole.ASSISTANT) R.string.subagent_interaction_source_child
                    else R.string.subagent_interaction_source_instruction), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SubAgentRecordTimestamp(message.timestamp)
            }
            if (message.content.isNotBlank()) SubAgentInteractionMarkdown("$subSessionId:execution:${message.id}", message.content)
        }
    }
    if (message.attachments.isNotEmpty() &&
        (message.role != MessageRole.TOOL || record.running != null || !canRenderSubAgentToolBody(message))) {
        val opener = LocalAttachmentOpener.current
        MessageAttachmentList(message.attachments, onClick = { opener.open(it) })
    }
}

@Composable
private fun SubAgentRawExecutionTool(record: SubAgentExecutionRecord, expanded: Boolean, toggle: () -> Unit) {
    val message = record.message
    val tool = message.toolActionTitle?.takeIf { it.isNotBlank() } ?: message.toolName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.subagent_interaction_unknown_tool)
    val toggleLabel = stringResource(if (expanded) R.string.subagent_interaction_collapse else R.string.subagent_interaction_expand)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = ChatStyle.toolRowMinHeight)
                .clip(RoundedCornerShape(Radius.pill)).background(chatMutedSurfaceColor())
                .border(ChatStyle.hairline, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.pill))
                .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = toggle)
                .semantics { stateDescription = toggleLabel }
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        ) {
            ToolStatusIcon(running = record.running != null, isError = message.isError, toolName = message.toolName)
            Text(tool, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message.toolDurationMs?.let(::formatToolDuration)
                ?: stringResource(R.string.chat_tool_group_duration_unknown), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                null, modifier = Modifier.size(Spacing.lg), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            SubAgentRecordTimestamp(message.timestamp)
            val args = record.running?.toolArgs?.takeIf { it.isNotBlank() } ?: message.toolArgs
            if (!args.isNullOrBlank()) ToolSection(stringResource(R.string.subagent_interaction_tool_instruction), args)
            val output = record.running?.text ?: message.content
            if (output.isNotBlank()) ToolSection(stringResource(R.string.subagent_interaction_tool_output), output, live = record.running != null)
        }
    }
}
