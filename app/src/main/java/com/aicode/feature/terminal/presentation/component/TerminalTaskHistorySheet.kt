package com.aicode.feature.terminal.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.terminal.domain.MAX_TERMINAL_OUTPUT_CHARS
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import com.aicode.feature.terminal.presentation.TerminalHistoryUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.X
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TerminalHistoryAction(isRemote: Boolean, onOpen: () -> Unit) {
    if (!isRemote) {
        IconButton(onClick = onOpen, modifier = Modifier.testTag("terminal-history-open")) {
            Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.terminal_history_title))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalTaskHistorySheet(
    state: TerminalHistoryUiState,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.semanticColors.pageBackground
    ) {
        BackHandler(enabled = state.selectedRecord != null, onBack = onBack)
        TerminalTaskHistoryContent(
            state = state,
            onSelect = onSelect,
            onBack = onBack,
            onDismiss = onDismiss,
            modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
        )
    }
}

@Composable
internal fun TerminalTaskHistoryContent(
    state: TerminalHistoryUiState,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = state.selectedRecord
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("terminal-history-back")) {
                    Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                }
            }
            Text(
                text = stringResource(R.string.terminal_history_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("terminal-history-close")) {
                Icon(FeatherIcons.X, contentDescription = stringResource(R.string.common_close))
            }
        }
        if (selected == null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("terminal-history-list"),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item(key = "boundary") { HistoryNotice(R.string.terminal_history_boundary) }
                if (state.loading) {
                    item(key = "loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.terminal_history_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.unavailable) {
                    item(key = "unavailable") { HistoryNotice(R.string.terminal_history_unavailable) }
                }
                if (state.storageError) {
                    item(key = "error") { HistoryNotice(R.string.terminal_history_storage_error) }
                }
                if (!state.loading && !state.unavailable && !state.storageError && state.records.isEmpty()) {
                    item(key = "empty") { HistoryNotice(R.string.terminal_history_empty) }
                }
                itemsIndexed(state.records, key = { _, record -> record.historyId }) { index, record ->
                    LazyGroupItem(index = index, lastIndex = state.records.lastIndex) {
                        SettingsRow(
                            title = record.title,
                            subtitle = "${historyStatus(record)}\n${historyTimes(record)}",
                            onClick = { onSelect(record.historyId) },
                            modifier = Modifier.testTag("terminal-history-record-${record.historyId}")
                        )
                        if (record.historyError != null) HistoryNotice(R.string.terminal_history_storage_error)
                    }
                }
            }
        } else {
            key(selected.historyId) {
                val chunks = remember(selected.output) { terminalHistoryOutputChunks(selected.output) }
                SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().testTag("terminal-history-detail"),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        item(key = "metadata") {
                            SettingsGroup {
                                SettingsRow(title = selected.title, subtitle = historyStatus(selected))
                                SettingsDivider()
                                SettingsRow(title = historyTimes(selected))
                                SettingsDivider()
                                SettingsRow(title = stringResource(R.string.terminal_history_id, selected.historyId))
                            }
                        }
                        item(key = "boundary") { HistoryNotice(R.string.terminal_history_boundary) }
                        item(key = "snapshot") { HistoryNotice(R.string.terminal_history_snapshot_note) }
                        if (state.storageError || selected.historyError != null) {
                            item(key = "error") { HistoryNotice(R.string.terminal_history_storage_error) }
                        }
                        if (chunks.isEmpty()) {
                            item(key = "output-empty") { HistoryNotice(R.string.terminal_history_output_empty) }
                        }
                        itemsIndexed(chunks, key = { index, _ -> "output-$index" }) { index, chunk ->
                            Text(
                                text = chunk,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)
                                    .testTag("terminal-history-output-$index")
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun terminalHistoryOutputChunks(output: String): List<String> {
    fun boundary(end: Int): Int =
        if (end in 1 until output.length && output[end - 1].isHighSurrogate() && output[end].isLowSurrogate()) end - 1
        else end

    val limit = boundary(minOf(output.length, MAX_TERMINAL_OUTPUT_CHARS + 64))
    return buildList {
        var start = 0
        while (start < limit) {
            val end = boundary(minOf(start + 2048, limit))
            add(output.substring(start, end))
            start = end
        }
    }
}

@Composable
private fun HistoryNotice(resource: Int) {
    Text(
        text = stringResource(resource),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
    )
}

@Composable
private fun historyStatus(record: TerminalTaskRecord): String = when (record.status) {
    TerminalTaskStatus.RUNNING -> stringResource(R.string.terminal_history_running)
    TerminalTaskStatus.FINISHED -> record.exitCode?.let { stringResource(R.string.terminal_history_finished, it) }
        ?: stringResource(R.string.terminal_history_unknown)
    TerminalTaskStatus.UNKNOWN -> stringResource(R.string.terminal_history_unknown)
}

@Composable
private fun historyTimes(record: TerminalTaskRecord): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    return stringResource(
        R.string.terminal_history_times,
        formatter.format(Date(record.startedAt)),
        formatter.format(Date(record.updatedAt))
    )
}
