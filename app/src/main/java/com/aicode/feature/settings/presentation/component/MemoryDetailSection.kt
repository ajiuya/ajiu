package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.presentation.component.MarkdownContent

@Composable
internal fun MemoryDetailSection(document: MemoryDocument) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.memory_management_scope),
                subtitle = stringResource(memoryScopeLabelRes(document.entry.scope))
            )
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.memory_management_file), subtitle = document.entry.fileName)
            TextButton(onClick = { clipboard.setText(AnnotatedString(document.content)) }) {
                Text(stringResource(R.string.memory_management_copy_body))
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SettingsGroupHeader(stringResource(R.string.memory_management_description))
                SettingsGroup {
                    Text(
                        document.entry.description.ifBlank { stringResource(R.string.memory_management_no_description) },
                        modifier = Modifier.padding(Spacing.lg),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                SettingsGroupHeader(stringResource(R.string.memory_management_body))
                SettingsGroup {
                    MarkdownContent(
                        text = document.content,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg)
                    )
                }
            }
        }
    }
}
