package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.appTextFieldColors
import com.aicode.feature.agent.presentation.SubAgentInstructionFailure
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import com.aicode.feature.agent.presentation.SubAgentInteractionPage
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Send
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubAgentInteractionCapsule(
    subSessionId: String,
    title: String = "",
    fallbackStatus: String? = null,
    modifier: Modifier = Modifier,
    fallbackAgentName: String? = null,
) {
    val host = LocalSubAgentCardHost.current
    val ownedSummary = host?.summaries?.get(subSessionId)?.takeIf { it.subSessionId == subSessionId }
    val summary = resolveSubAgentSummary(subSessionId, title, ownedSummary,
        host?.states?.get(subSessionId)?.takeIf { ownedSummary != null }, fallbackStatus, fallbackAgentName)
    val canRead = ownedSummary != null
    val canOperate = canRead && summary.sessionExists && summary.status != SubAgentInteractionStatus.DELETED
    val expansion = rememberSubAgentExpansion(subSessionId)
    LaunchedEffect(summary.status) { expansion.observe(summary.status) }
    val page by rememberSubAgentPage(
        subSessionId = subSessionId,
        expanded = expansion.expanded,
        hostAvailable = canRead,
        initial = SubAgentInteractionPage(),
        source = { host?.conversation?.invoke(subSessionId) ?: flowOf(SubAgentInteractionPage(loaded = true)) },
        onFailure = { previous, failure -> previous.copy(loaded = true, error = failure.message.orEmpty()) },
        onActive = { active -> host?.onExpanded?.invoke(subSessionId, active) },
    )
    // The draft and delivery job outlive the folded body, and are scoped to the child identity.
    val instruction = rememberSubAgentInstructionState(subSessionId)
    var showTechnical by rememberSaveable(subSessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val send: () -> Unit = {
        val sender = host?.onSend?.takeIf { canOperate }
        scope.launch {
            instruction.send { submitted ->
                sender?.invoke(subSessionId, submitted) ?: SubAgentInstructionResult(
                    sent = false, failure = SubAgentInstructionFailure.NOT_OWNED,
                )
            }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
    ) {
        Column {
            SubAgentCapsuleHeader(
                summary = summary,
                expanded = expansion.expanded,
                toggle = { host?.onContentInteraction?.invoke(); expansion.toggle() },
                onOpen = if (canOperate) host?.let { { it.onOpen(subSessionId) } } else null,
            )
            if (summary.status == SubAgentInteractionStatus.AWAITING_CONFIRMATION) {
                SubAgentConfirmationNotice(
                    enabled = canOperate,
                    onOpen = { host?.onOpenConfirm?.invoke(subSessionId) },
                    modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
                )
            }
            // No exit animation: folding must immediately dispose the full-text composition.
            if (expansion.expanded) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Column(
                        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
                        if (!canRead) SubAgentInteractionNotice(stringResource(R.string.subagent_interaction_unavailable))
                        if (!summary.sessionExists || summary.status == SubAgentInteractionStatus.DELETED) {
                            SubAgentInteractionNotice(stringResource(R.string.subagent_interaction_deleted_notice))
                        }
                        SubAgentConversationContent(subSessionId, page, canRead, subAgentRoleLabel(summary)) {
                            host?.onLoadMore?.invoke(subSessionId)
                        }
                        SubAgentExecutionContent(subSessionId, host?.takeIf { canOperate })
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            FilledTonalButton(
                                onClick = { host?.onOpen?.invoke(subSessionId) }, enabled = canOperate,
                                modifier = Modifier.heightIn(min = Spacing.xxl),
                                shape = RoundedCornerShape(Radius.sm),
                                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                            ) {
                                Text(stringResource(R.string.subagent_interaction_open_session), style = MaterialTheme.typography.labelSmall)
                            }
                            if (summary.status.isActiveExecution() || summary.status == SubAgentInteractionStatus.QUEUED) {
                                OutlinedButton(
                                    onClick = { host?.onStop?.invoke(subSessionId) }, enabled = canOperate,
                                    modifier = Modifier.heightIn(min = Spacing.xxl),
                                    shape = RoundedCornerShape(Radius.sm),
                                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text(stringResource(R.string.subagent_interaction_stop), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(
                                onClick = { host?.onContentInteraction?.invoke(); showTechnical = !showTechnical },
                                modifier = Modifier.heightIn(min = Spacing.xxl),
                                contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs),
                            ) {
                                Text(stringResource(if (showTechnical) R.string.subagent_interaction_hide_technical
                                    else R.string.subagent_interaction_show_technical), style = MaterialTheme.typography.labelSmall)
                                Icon(if (showTechnical) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                                    null, modifier = Modifier.size(Spacing.md))
                            }
                        }
                        if (showTechnical) SubAgentTechnicalDetails(summary)
                        SubAgentInstructionField(
                            draft = instruction.value.draft,
                            onDraftChange = instruction::edit,
                            agentLabel = subAgentRoleLabel(summary),
                            enabled = canOperate,
                            sending = instruction.value.sending,
                            failure = instruction.value.failure,
                            sentNotice = instruction.value.sentNotice,
                            onSend = send,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubAgentCapsuleHeader(
    summary: SubAgentInteractionSummary,
    expanded: Boolean,
    toggle: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val expandLabel = stringResource(if (expanded) R.string.subagent_interaction_collapse else R.string.subagent_interaction_expand)
    val openLabel = stringResource(R.string.subagent_interaction_open_session)
    val stateLabel = stringResource(if (expanded) R.string.subagent_interaction_expanded else R.string.subagent_interaction_collapsed)
    val status = subAgentStatusLabel(summary.status)
    val color = subAgentStatusColor(summary.status)
    val task = subAgentVisibleTask(summary)
    Column(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            role = Role.Button,
            onClickLabel = expandLabel,
            onLongClickLabel = if (onOpen != null) openLabel else null,
            onLongClick = onOpen,
            onClick = toggle,
        ).semantics { stateDescription = "$stateLabel · $status" }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(subAgentRoleLabel(summary), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(shape = RoundedCornerShape(Radius.pill), color = color.copy(alpha = 0.1f)) {
                Row(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Box(Modifier.size(Spacing.xs).clip(CircleShape).background(color))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
            Icon(
                if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg),
            )
        }
        if (task.isNotBlank()) {
            Text(task, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        SubAgentCountsAndElapsed(summary)
        val preview = summary.currentToolTitle?.takeIf { summary.status.isActiveExecution() && it.isNotBlank() }?.let {
            stringResource(R.string.subagent_interaction_current_tool, it)
        } ?: summary.latestPreview.takeIf { !expanded && it.isNotBlank() && it != task }
        preview?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = if (summary.status == SubAgentInteractionStatus.COMPLETED || summary.status == SubAgentInteractionStatus.FAILED) color
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubAgentCountsAndElapsed(summary: SubAgentInteractionSummary) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.subagent_interaction_counts, summary.exchangeCount, summary.toolCallCount),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
        SubAgentElapsedLabel(summary)
    }
}

@Composable
private fun SubAgentElapsedLabel(summary: SubAgentInteractionSummary) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val elapsed = rememberSubAgentElapsedMs(summary, lifecycle.isAtLeast(Lifecycle.State.STARTED))
    elapsed?.let(::formatToolDuration)?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText, maxLines = 1)
    }
}

@Composable
private fun SubAgentTechnicalDetails(summary: SubAgentInteractionSummary) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.subagent_interaction_session_id, summary.subSessionId), style = MaterialTheme.typography.labelSmall)
            summary.agentName?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.subagent_interaction_agent, it), style = MaterialTheme.typography.bodySmall)
            }
            if (summary.title.isNotBlank() && subAgentVisibleTask(summary) != summary.title) {
                Text(stringResource(R.string.subagent_interaction_task, summary.title), style = MaterialTheme.typography.bodySmall)
            }
            Text(summary.model?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.subagent_interaction_model, it) }
                ?: stringResource(R.string.subagent_interaction_model_unknown), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SubAgentConfirmationNotice(enabled: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.subagent_interaction_confirmation_hint),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.warning,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen, enabled = enabled,
                contentPadding = PaddingValues(Spacing.xs), modifier = Modifier.heightIn(min = Spacing.xxl)) {
                Text(stringResource(R.string.subagent_interaction_handle_confirmation_short), style = MaterialTheme.typography.labelSmall)
                Icon(FeatherIcons.ExternalLink, null, modifier = Modifier.padding(start = Spacing.xs).size(Spacing.md))
            }
        }
    }
}

@Composable
private fun SubAgentInstructionField(
    draft: String,
    onDraftChange: (String) -> Unit,
    agentLabel: String,
    enabled: Boolean,
    sending: Boolean,
    failure: SubAgentInstructionFailure?,
    sentNotice: Boolean,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AppTextField(
            value = draft, onValueChange = onDraftChange, modifier = Modifier.fillMaxWidth(),
            placeholderComposable = {
                Text(stringResource(R.string.subagent_interaction_instruction_hint_named, agentLabel),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            enabled = enabled && !sending, textStyle = MaterialTheme.typography.bodySmall,
            singleLine = false, maxLines = 4, shape = RoundedCornerShape(Radius.sm),
            colors = appTextFieldColors(), isError = failure != null,
            trailingIcon = {
                IconButton(onClick = onSend, enabled = enabled && !sending && draft.isNotBlank(),
                    modifier = Modifier.size(Spacing.xxl)) {
                    Icon(FeatherIcons.Send, stringResource(R.string.subagent_interaction_send), modifier = Modifier.size(Spacing.lg))
                }
            },
        )
        if (draft.isNotBlank()) {
            Text(stringResource(R.string.subagent_interaction_instruction_notice), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            sending -> Text(stringResource(R.string.subagent_interaction_sending), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            failure != null -> Text(stringResource(when (failure) {
                SubAgentInstructionFailure.EMPTY -> R.string.subagent_interaction_send_empty
                SubAgentInstructionFailure.SESSION_MISSING -> R.string.subagent_interaction_send_missing
                SubAgentInstructionFailure.NOT_OWNED -> R.string.subagent_interaction_send_not_owned
                SubAgentInstructionFailure.SEND_FAILED -> R.string.subagent_interaction_send_failed
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            sentNotice -> Text(stringResource(R.string.subagent_interaction_sent), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.success)
        }
    }
}

@Composable
internal fun subAgentRoleLabel(summary: SubAgentInteractionSummary): String {
    val identity = subAgentDisplayIdentity(summary)
    return when (identity.kind) {
        SubAgentIdentityKind.NAMED -> identity.name
        SubAgentIdentityKind.GENERIC -> stringResource(R.string.subagent_interaction_role_generic)
        SubAgentIdentityKind.UNKNOWN -> stringResource(R.string.subagent_interaction_role_unknown)
    }
}

@Composable
internal fun subAgentStatusLabel(status: SubAgentInteractionStatus): String = stringResource(when (status) {
    SubAgentInteractionStatus.QUEUED -> R.string.subagent_interaction_status_queued
    SubAgentInteractionStatus.RUNNING -> R.string.subagent_interaction_status_running
    SubAgentInteractionStatus.AWAITING_CONFIRMATION -> R.string.subagent_interaction_status_confirmation
    SubAgentInteractionStatus.COMPLETED -> R.string.subagent_interaction_status_completed
    SubAgentInteractionStatus.FAILED -> R.string.subagent_interaction_status_failed
    SubAgentInteractionStatus.INTERRUPTED -> R.string.subagent_interaction_status_interrupted
    SubAgentInteractionStatus.DELETED -> R.string.subagent_interaction_status_deleted
    SubAgentInteractionStatus.HISTORY -> R.string.subagent_interaction_status_history
})

@Composable
internal fun subAgentStatusColor(status: SubAgentInteractionStatus): Color = when (status) {
    SubAgentInteractionStatus.QUEUED, SubAgentInteractionStatus.RUNNING -> MaterialTheme.semanticColors.info
    SubAgentInteractionStatus.AWAITING_CONFIRMATION -> MaterialTheme.semanticColors.warning
    SubAgentInteractionStatus.COMPLETED -> MaterialTheme.semanticColors.success
    SubAgentInteractionStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
