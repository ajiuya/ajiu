package com.aicode.feature.agent.presentation.component

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.preview.safeToolPreviewText

@StringRes
internal fun taskOperationActionResource(action: TaskOperationAction): Int = when (action) {
    TaskOperationAction.CREATE -> R.string.task_operation_create
    TaskOperationAction.SEND -> R.string.task_operation_send
    TaskOperationAction.READ -> R.string.task_operation_read
    TaskOperationAction.STOP -> R.string.task_operation_stop
    TaskOperationAction.DELETE -> R.string.task_operation_delete
    TaskOperationAction.LIST -> R.string.task_operation_list
    TaskOperationAction.UNKNOWN -> R.string.task_operation_unknown
}

@StringRes
internal fun taskOperationStatusResource(status: TaskOperationStatus): Int = when (status) {
    TaskOperationStatus.RUNNING -> R.string.task_operation_running
    TaskOperationStatus.COMPLETED -> R.string.task_operation_completed
    TaskOperationStatus.QUEUED -> R.string.task_operation_queued
    TaskOperationStatus.DELIVERED -> R.string.task_operation_delivered
    TaskOperationStatus.STOPPING -> R.string.task_operation_stopping
    TaskOperationStatus.STOPPED -> R.string.task_operation_stopped
    TaskOperationStatus.DELETED -> R.string.task_operation_deleted
    TaskOperationStatus.FAILED -> R.string.task_operation_failed
    TaskOperationStatus.INTERRUPTED -> R.string.task_operation_interrupted
    TaskOperationStatus.AWAITING_CONFIRMATION -> R.string.task_operation_awaiting_confirmation
    TaskOperationStatus.RECORDED -> R.string.task_operation_recorded
    TaskOperationStatus.UNKNOWN -> R.string.task_operation_status_unknown
}

@Composable
internal fun TaskOperationContent(
    presentation: TaskOperationPresentation?,
    argsJson: String?,
    rawResult: String?,
    running: Boolean,
) {
    var showTechnical by remember(argsJson) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (presentation?.argumentsUnavailable == true) {
            Text(stringResource(R.string.task_operation_history_instruction_missing), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (presentation == null) {
            Text(stringResource(if (running) R.string.task_operation_preparing else R.string.task_operation_unparsed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val action = stringResource(taskOperationActionResource(presentation.action))
            presentation.items.forEachIndexed { index, item -> key(index, item.id) {
                val name = item.name?.takeIf(String::isNotBlank) ?: stringResource(taskOperationFallbackNameResource(presentation.action, item))
                Text(stringResource(R.string.task_operation_route, action, name), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                if (item.task.isNotBlank()) Text(stringResource(R.string.task_operation_task_title, item.task),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.instruction?.takeIf(String::isNotBlank)?.let {
                    TaskOperationText(stringResource(if (presentation.action == TaskOperationAction.SEND)
                        R.string.task_operation_supplement else R.string.task_operation_assignment), it,
                        contentKey = "task:${item.id}:$index:instruction")
                }
                item.status?.let { status ->
                    Text(stringResource(taskOperationStatusResource(status)), style = MaterialTheme.typography.labelLarge,
                        color = if (status == TaskOperationStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status == TaskOperationStatus.DELIVERED) Text(stringResource(R.string.task_operation_delivered_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.output?.takeIf(String::isNotBlank)?.let {
                    TaskOperationText(stringResource(R.string.task_operation_latest_output), it,
                        contentKey = "task:${item.id}:$index:output")
                }
                item.warning?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.warning)
                }
                item.error?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } }
            if (presentation.items.isEmpty() && presentation.action == TaskOperationAction.LIST && presentation.hasResult) {
                Text(stringResource(R.string.task_operation_empty), style = MaterialTheme.typography.bodySmall)
            }
            if (presentation.partial) Text(stringResource(R.string.task_operation_partial), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.warning)
            presentation.message?.takeIf { it.isNotBlank() && it != presentation.error }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            presentation.error?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = { showTechnical = !showTechnical }) {
            Text(stringResource(if (showTechnical) R.string.task_operation_hide_technical else R.string.task_operation_show_technical))
        }
        if (showTechnical) {
            argsJson?.takeIf(String::isNotBlank)?.let {
                TaskOperationText(stringResource(R.string.task_operation_safe_args), safeToolPreviewText(it), monospace = true)
            }
            rawResult?.takeIf(String::isNotBlank)?.let {
                TaskOperationText(stringResource(R.string.task_operation_safe_result), safeToolPreviewText(it), monospace = true)
            }
        }
    }
}

@Composable
private fun TaskOperationText(label: String, text: String, contentKey: String = label, monospace: Boolean = false) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (monospace) SelectionContainer {
        Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    } else SubAgentInteractionMarkdown(contentKey, text, Modifier.fillMaxWidth())
}

@StringRes
private fun taskOperationFallbackNameResource(action: TaskOperationAction, item: TaskOperationItem): Int =
    if (action == TaskOperationAction.CREATE || item.task.isNotBlank()) R.string.task_operation_generic_agent else R.string.task_operation_unnamed

internal fun taskOperationCopyText(context: Context, presentation: TaskOperationPresentation?, running: Boolean): String = buildList {
    if (presentation == null) {
        add(context.getString(if (running) R.string.task_operation_preparing else R.string.task_operation_unparsed))
        return@buildList
    }
    if (presentation.argumentsUnavailable) add(context.getString(R.string.task_operation_history_instruction_missing))
    val action = context.getString(taskOperationActionResource(presentation.action))
    presentation.items.forEach { item ->
        val name = item.name?.takeIf(String::isNotBlank) ?: context.getString(taskOperationFallbackNameResource(presentation.action, item))
        add(context.getString(R.string.task_operation_route, action, name))
        if (item.task.isNotBlank()) add(context.getString(R.string.task_operation_task_title, item.task))
        item.instruction?.takeIf(String::isNotBlank)?.let {
            add(context.getString(if (presentation.action == TaskOperationAction.SEND) R.string.task_operation_supplement else R.string.task_operation_assignment))
            add(it)
        }
        item.status?.let {
            add(context.getString(taskOperationStatusResource(it)))
            if (it == TaskOperationStatus.DELIVERED) add(context.getString(R.string.task_operation_delivered_hint))
        }
        item.output?.takeIf(String::isNotBlank)?.let { add(context.getString(R.string.task_operation_latest_output)); add(it) }
        item.warning?.takeIf(String::isNotBlank)?.let(::add)
        item.error?.takeIf(String::isNotBlank)?.let(::add)
    }
    if (presentation.items.isEmpty() && presentation.action == TaskOperationAction.LIST && presentation.hasResult) add(context.getString(R.string.task_operation_empty))
    if (presentation.partial) add(context.getString(R.string.task_operation_partial))
    presentation.message?.takeIf { it.isNotBlank() && it != presentation.error }?.let(::add)
    presentation.error?.takeIf(String::isNotBlank)?.let(::add)
}.joinToString("\n\n")
