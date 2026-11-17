package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.agent.domain.recovery.RecoveryStage

// request/draft are public visible text; unknownTools contains names only, never reasoning or arguments.
data class SessionRecoveryItemUi(
    val requestId: String,
    val request: String,
    val draft: String,
    val unknownTools: List<String>,
    val reason: String?,
    val environmentMatches: Boolean,
    val pendingCount: Int = 1,
    val stage: RecoveryStage = RecoveryStage.NOT_STARTED
)

@Composable
fun SessionRecoveryCard(
    items: List<SessionRecoveryItemUi>,
    busy: Boolean,
    onContinue: (String) -> Unit,
    onAbandon: (String) -> Unit,
    onInspect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val first = items.firstOrNull() ?: return
    key(first.requestId) {
        var selectedId by remember { mutableStateOf<String?>(null) }
        var mode by remember { mutableStateOf(SessionRecoveryReview.DETAILS) }
        // Lock synchronously before dispatch; the host's busy update may arrive on the next frame.
        var submitted by remember(busy) { mutableStateOf(false) }
        val locked = busy || submitted
        val pendingCount = items.sumOf { it.pendingCount }
        fun open(review: SessionRecoveryReview) {
            selectedId = first.requestId
            mode = review
        }
        SettingsGroup(
            modifier = modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                .testTag("session_recovery_card")
        ) {
            SettingsRow(
                title = stringResource(R.string.session_recovery_interrupted),
                subtitle = stringResource(recoveryStageLabel(first.stage))
            )
            Column(
                modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (pendingCount > 1) {
                    Text(stringResource(R.string.session_recovery_pending_count, pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = first.request.ifBlank { stringResource(R.string.session_recovery_request_missing) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.session_recovery_draft_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.warning
                )
                Text(
                    text = first.draft.ifBlank { stringResource(R.string.session_recovery_draft_missing) },
                    modifier = Modifier.testTag("session_recovery_draft_preview"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (first.unknownTools.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.session_recovery_unknown_summary, first.unknownTools.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!first.environmentMatches) SessionRecoveryEnvironmentNotice()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        open(SessionRecoveryReview.DETAILS)
                        onInspect?.invoke(first.requestId)
                    }, modifier = Modifier.testTag("session_recovery_inspect")) {
                        Text(stringResource(R.string.session_recovery_inspect))
                    }
                    OutlinedButton(
                        onClick = { if (!locked && first.environmentMatches) open(SessionRecoveryReview.CONTINUE) },
                        enabled = !locked && first.environmentMatches,
                        shape = RoundedCornerShape(Radius.mdLarge),
                        modifier = Modifier.testTag("session_recovery_continue")
                    ) {
                        Text(stringResource(R.string.session_recovery_continue))
                    }
                    TextButton(
                        onClick = { if (!locked) open(SessionRecoveryReview.ABANDON) },
                        enabled = !locked,
                        modifier = Modifier.testTag("session_recovery_abandon"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.session_recovery_abandon))
                    }
                }
            }
        }
        val selectedIndex = items.indexOfFirst { it.requestId == selectedId }
        val selected = items.getOrNull(selectedIndex)
        if (selected != null) {
            key(selected.requestId) {
                SessionRecoverySheet(
                    item = selected,
                    index = selectedIndex,
                    itemCount = items.size,
                    mode = mode,
                    locked = locked,
                    onModeChange = { mode = it },
                    onSelect = { index ->
                        selectedId = items[index].requestId
                        mode = SessionRecoveryReview.DETAILS
                    },
                    onDismiss = { selectedId = null },
                    onContinue = {
                        if (!busy && !submitted && selected.environmentMatches) {
                            submitted = true
                            selectedId = null
                            onContinue(selected.requestId)
                        }
                    },
                    onAbandon = {
                        if (!busy && !submitted) {
                            submitted = true
                            selectedId = null
                            onAbandon(selected.requestId)
                        }
                    }
                )
            }
        }
    }
}

internal fun recoveryStageLabel(stage: RecoveryStage): Int = when (stage) {
    RecoveryStage.NOT_STARTED -> R.string.session_recovery_stage_not_started
    RecoveryStage.TEXT -> R.string.session_recovery_stage_text
    RecoveryStage.TOOLS_NOT_EXECUTED -> R.string.session_recovery_stage_tools_pending
    RecoveryStage.TOOLS_UNKNOWN -> R.string.session_recovery_stage_tools_unknown
    RecoveryStage.TOOLS_COMPLETED -> R.string.session_recovery_stage_tools_completed
}

private enum class SessionRecoveryReview { DETAILS, CONTINUE, ABANDON }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRecoverySheet(
    item: SessionRecoveryItemUi,
    index: Int,
    itemCount: Int,
    mode: SessionRecoveryReview,
    locked: Boolean,
    onModeChange: (SessionRecoveryReview) -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit
) {
    var unknownResultsChecked by remember(item, mode) { mutableStateOf(false) }
    val canContinue = !locked && item.environmentMatches &&
        (item.unknownTools.isEmpty() || unknownResultsChecked)
    val clipboard = LocalClipboardManager.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val optInLabel = stringResource(R.string.session_recovery_unknown_opt_in)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.85f)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .testTag("session_recovery_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(when (mode) {
                    SessionRecoveryReview.DETAILS -> R.string.session_recovery_details
                    SessionRecoveryReview.CONTINUE -> R.string.session_recovery_review_title
                    SessionRecoveryReview.ABANDON -> R.string.session_recovery_abandon_title
                }),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (itemCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (!locked && index > 0) onSelect(index - 1) },
                        enabled = !locked && index > 0,
                        modifier = Modifier.testTag("session_recovery_previous")
                    ) { Text(stringResource(R.string.session_recovery_previous)) }
                    Text(
                        text = stringResource(R.string.session_recovery_page, index + 1, itemCount),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(
                        onClick = { if (!locked && index + 1 < itemCount) onSelect(index + 1) },
                        enabled = !locked && index + 1 < itemCount,
                        modifier = Modifier.testTag("session_recovery_next")
                    ) { Text(stringResource(R.string.session_recovery_next)) }
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (locked) SessionRecoveryNotice(stringResource(R.string.session_recovery_busy))
                if (!item.environmentMatches) SessionRecoveryEnvironmentNotice()
                item.reason?.let { reason ->
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.session_recovery_reason),
                            subtitle = stringResource(sessionRecoveryReasonLabel(reason))
                        )
                    }
                }
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.session_recovery_request))
                    SettingsDivider()
                    SelectionContainer {
                        Text(
                            text = item.request.ifBlank { stringResource(R.string.session_recovery_request_missing) },
                            modifier = Modifier.padding(Spacing.lg),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.session_recovery_draft_label))
                    SettingsDivider()
                    SelectionContainer {
                        Text(
                            text = item.draft.ifBlank { stringResource(R.string.session_recovery_draft_missing) },
                            modifier = Modifier.padding(Spacing.lg).testTag("session_recovery_draft_body"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(item.draft)) },
                        enabled = item.draft.isNotBlank(),
                        modifier = Modifier.testTag("session_recovery_copy_draft")
                    ) { Text(stringResource(R.string.session_recovery_copy_draft)) }
                }
                if (item.unknownTools.isNotEmpty()) {
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.session_recovery_unknown_tools),
                            subtitle = item.unknownTools.joinToString("\n")
                        )
                        SettingsDivider()
                        Text(
                            text = stringResource(R.string.session_recovery_unknown_hint),
                            modifier = Modifier.padding(Spacing.lg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.warning
                        )
                        if (mode == SessionRecoveryReview.CONTINUE) {
                            SettingsDivider()
                            SettingsRow(
                                title = optInLabel,
                                trailing = {
                                    AppSwitch(
                                        checked = unknownResultsChecked,
                                        onCheckedChange = {
                                            if (!locked && item.environmentMatches) unknownResultsChecked = it
                                        },
                                        enabled = !locked && item.environmentMatches,
                                        modifier = Modifier.padding(start = Spacing.sm)
                                            .testTag("session_recovery_unknown_opt_in")
                                            .semantics { contentDescription = optInLabel }
                                    )
                                }
                            )
                        }
                    }
                }
                if (mode == SessionRecoveryReview.CONTINUE) {
                    SessionRecoveryNotice(stringResource(R.string.session_recovery_review_hint))
                } else if (mode == SessionRecoveryReview.ABANDON) {
                    SessionRecoveryNotice(stringResource(R.string.session_recovery_abandon_hint))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (mode) {
                    SessionRecoveryReview.DETAILS -> {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.session_recovery_close)) }
                        OutlinedButton(
                            onClick = { if (!locked) onModeChange(SessionRecoveryReview.ABANDON) },
                            enabled = !locked,
                            shape = RoundedCornerShape(Radius.mdLarge),
                            modifier = Modifier.weight(1f).testTag("session_recovery_sheet_abandon")
                        ) { Text(stringResource(R.string.session_recovery_abandon)) }
                        Button(
                            onClick = {
                                if (!locked && item.environmentMatches) onModeChange(SessionRecoveryReview.CONTINUE)
                            },
                            enabled = !locked && item.environmentMatches,
                            shape = RoundedCornerShape(Radius.mdLarge),
                            modifier = Modifier.weight(1f).testTag("session_recovery_sheet_continue")
                        ) { Text(stringResource(R.string.session_recovery_continue)) }
                    }
                    SessionRecoveryReview.CONTINUE -> {
                        TextButton(
                            onClick = { onModeChange(SessionRecoveryReview.DETAILS) },
                            modifier = Modifier.weight(1f).testTag("session_recovery_back")
                        ) { Text(stringResource(R.string.session_recovery_back)) }
                        Button(
                            onClick = {
                                if (!locked && item.environmentMatches &&
                                    (item.unknownTools.isEmpty() || unknownResultsChecked)
                                ) onContinue()
                            },
                            enabled = canContinue,
                            shape = RoundedCornerShape(Radius.mdLarge),
                            modifier = Modifier.weight(1f).testTag("session_recovery_confirm_continue")
                        ) { Text(stringResource(R.string.session_recovery_confirm_continue)) }
                    }
                    SessionRecoveryReview.ABANDON -> {
                        TextButton(
                            onClick = { onModeChange(SessionRecoveryReview.DETAILS) },
                            modifier = Modifier.weight(1f).testTag("session_recovery_back")
                        ) { Text(stringResource(R.string.session_recovery_back)) }
                        Button(
                            onClick = { if (!locked) onAbandon() },
                            enabled = !locked,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(Radius.mdLarge),
                            modifier = Modifier.weight(1f).testTag("session_recovery_confirm_abandon")
                        ) { Text(stringResource(R.string.session_recovery_confirm_abandon)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRecoveryEnvironmentNotice() {
    SessionRecoveryNotice(stringResource(R.string.session_recovery_environment_mismatch))
}

@Composable
private fun SessionRecoveryNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.warning
    )
}

private fun sessionRecoveryReasonLabel(reason: String): Int = when (reason) {
    "process_interrupted" -> R.string.session_recovery_reason_process
    "stream_interrupted" -> R.string.session_recovery_reason_stream
    "environment_mismatch" -> R.string.session_recovery_environment_mismatch
    "unknown_tool_result" -> R.string.session_recovery_unknown_hint
    else -> R.string.session_recovery_reason_interrupted
}
