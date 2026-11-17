package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextUsageSheet(
    message: AgentUIMessage,
    onDismissRequest: () -> Unit,
    onCompactContext: (() -> Unit)? = null,
    contextCompactionEnabled: Boolean = true
) {
    val missing = stringResource(R.string.chat_context_breakdown_missing)
    val inputTotal = message.contextInputTokens?.takeIf { it >= 0 }?.let {
        "${if (message.contextEstimated) "≈" else ""}${formatTokenCount(it.toLong())}"
    } ?: missing
    val inputBudget = message.contextInputBudget?.takeIf { it > 0 }?.let {
        formatTokenCount(it.toLong())
    } ?: missing
    val breakdown = message.contextBreakdown
    val rows = listOf(
        R.string.chat_context_system to breakdown?.systemTokens,
        R.string.chat_context_tool_definitions to breakdown?.toolDefinitionTokens,
        R.string.chat_context_chat_history to breakdown?.chatTokens,
        R.string.chat_context_tool_results to breakdown?.toolResultTokens,
        R.string.chat_context_images to breakdown?.imageTokens
    )

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.imePadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.chat_context_details),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.chat_context_input_total, inputTotal))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.chat_context_input_budget, inputBudget))
            }
            SettingsGroup {
                rows.forEachIndexed { index, (label, tokens) ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        title = stringResource(label),
                        trailing = {
                            Text(
                                text = tokens?.let { "≈${formatTokenCount(it.toLong())}" } ?: missing,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }
            Text(
                text = stringResource(R.string.chat_context_breakdown_estimated_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.chat_context_compact_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    onDismissRequest()
                    onCompactContext?.invoke()
                },
                enabled = contextCompactionEnabled && onCompactContext != null,
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.chat_context_compact_now))
            }
            if (!contextCompactionEnabled) {
                Text(
                    text = stringResource(R.string.chat_context_compact_busy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
