package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.domain.session.PluginSessionGroup
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow

@Composable
fun PluginSessionGroups(groups: List<PluginSessionGroup>, onSelect: (PluginSessionSummary) -> Unit, onRefresh: () -> Unit) {
    if (groups.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        item("refresh") { TextButton(onClick = onRefresh) { Text(stringResource(R.string.plugin_external_sessions)) } }
        groups.forEach { group ->
            item("group:${group.pluginId}") {
                Text(group.pluginName, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.lg))
                if (group.loading) CircularProgressIndicator()
                group.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Spacing.lg)) }
            }
            items(group.sessions, key = { it.id }) { session ->
                SettingsRow(title = session.title, subtitle = "${session.group} · ${session.status}", onClick = { onSelect(session) })
            }
        }
    }
}

@Composable
fun PluginConversationPanel(viewModel: PluginConversationViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()
    val runs by viewModel.runStates.collectAsStateWithLifecycle()
    val selected = state.selected ?: return
    val run = runs[selected.id]
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(selected.title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::close) { Text(stringResource(R.string.plugin_close)) }
        }
        PluginProblems(listOfNotNull(state.problem), Modifier.padding(horizontal = Spacing.lg))
        if (state.loading) CircularProgressIndicator()
        thinking[selected.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.lg)) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (state.page?.hasMore == true) item { TextButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.plugin_session_load_more)) } }
            items(state.page?.messages.orEmpty(), key = { it.id }) { message ->
                SettingsGroup { Column(Modifier.padding(Spacing.md)) {
                    Text(message.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message.blocks.forEach { PluginMessageContent(it) }
                } }
            }
            items(state.tools.values.toList(), key = { it.callId ?: it.name }) { PluginMessageContent(it) }
            items(run?.settled.orEmpty(), key = { it.id }) { message ->
                message.reasoning?.takeIf { it.isNotBlank() }?.let { MarkdownContent(text = it, color = MaterialTheme.colorScheme.onSurface) }
                MarkdownContent(text = message.content, color = MaterialTheme.colorScheme.onSurface)
            }
            run?.assistant?.let { assistant -> item {
                assistant.reasoning?.takeIf { it.isNotBlank() }?.let { MarkdownContent(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (assistant.content.isNotBlank()) MarkdownContent(text = assistant.content, color = MaterialTheme.colorScheme.onSurface)
            } }
            run?.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        }
        AppTextField(state.draft, viewModel::updateDraft, label = stringResource(R.string.plugin_session_message), singleLine = false, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = viewModel::stop) { Text(stringResource(R.string.plugin_session_stop)) }
            TextButton(onClick = viewModel::send, enabled = state.draft.isNotBlank()) { Text(stringResource(R.string.plugin_session_send)) }
        }
    }
}

@Composable
private fun PluginMessageContent(block: PluginMessageBlock) {
    when (block) {
        is PluginMessageBlock.Text -> MarkdownContent(text = block.text, color = MaterialTheme.colorScheme.onSurface)
        is PluginMessageBlock.Reasoning -> MarkdownContent(text = block.text, color = MaterialTheme.colorScheme.onSurface)
        is PluginMessageBlock.Notice -> Text(block.text, style = MaterialTheme.typography.bodySmall)
        is PluginMessageBlock.Attachment -> Text(block.name ?: block.path.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        is PluginMessageBlock.Tool -> SettingsGroup { Column(Modifier.padding(Spacing.md)) {
            Text("${block.name} · ${block.status}", style = MaterialTheme.typography.labelLarge)
            block.input?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            block.output?.let { MarkdownContent(text = it, color = MaterialTheme.colorScheme.onSurface) }
        } }
    }
}
