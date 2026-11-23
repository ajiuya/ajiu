package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.settings.presentation.ProjectProgressEntryAction
import com.aicode.feature.settings.presentation.ProjectProgressError
import com.aicode.feature.settings.presentation.ProjectProgressForm
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectProgressFormSheet(
    state: ProjectProgressUiState,
    onRegister: (String) -> Unit,
    onHandoff: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val form = state.form ?: return
    val saving by rememberUpdatedState(state.busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !saving })
    AdaptiveModalBottomSheet(onDismissRequest = { if (!state.busy) onDismiss() }, sheetState = sheetState, sheetGesturesEnabled = !state.busy) {
        ProjectProgressEditorForm(form, state.busy, state.error, onRegister, onHandoff,
            registrationReady = state.projectListReady)
    }
}

@Composable
internal fun ProjectProgressEditorForm(
    form: ProjectProgressForm,
    saving: Boolean,
    error: ProjectProgressError?,
    onRegister: (String) -> Unit,
    onHandoff: (String, String) -> Unit,
    registrationReady: Boolean = true
) {
    var name by rememberSaveable(form) { mutableStateOf("") }
    var body by rememberSaveable(form) { mutableStateOf("") }
    val registering = form == ProjectProgressForm.REGISTER
    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(if (registering) R.string.project_progress_register else R.string.project_progress_handoff), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (registering) R.string.project_progress_register_form_hint else R.string.project_progress_handoff_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextField(
            value = name, onValueChange = { name = it },
            label = stringResource(if (registering) R.string.project_progress_name else R.string.project_progress_summary),
            enabled = !saving, isError = error == ProjectProgressError.INVALID_INPUT,
            colors = dialogTextFieldColors()
        )
        if (!registering) {
            AppTextField(
                value = body, onValueChange = { body = it }, label = stringResource(R.string.project_progress_body),
                singleLine = false, minLines = 6, enabled = !saving,
                isError = error == ProjectProgressError.INVALID_INPUT, colors = dialogTextFieldColors()
            )
        }
        error?.let { Text(stringResource(projectProgressErrorRes(it)), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { if (registering) onRegister(name) else onHandoff(name, body) },
            enabled = !saving && (!registering || registrationReady) && name.isNotBlank() &&
                name.length <= (if (registering) 200 else 512) &&
                (registering || (body.isNotBlank() && body.length <= 8192)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (saving) R.string.project_progress_saving else R.string.project_progress_save))
        }
    }
}

@Composable
internal fun ProjectProgressDetail(entry: ProjectMemoryEntry, enabled: Boolean, onAction: (ProjectProgressEntryAction) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(stringResource(R.string.project_progress_entry))
        SettingsGroup {
            SettingsRow(title = projectProgressEntryTitle(entry), subtitle = projectProgressEntryMetadata(entry))
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.project_progress_created_at), subtitle = timeFormat.format(Date(entry.createdAt)))
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.project_progress_revision), subtitle = entry.revision.toString())
            TextButton(onClick = { clipboard.setText(AnnotatedString(entry.body)) }) { Text(stringResource(R.string.project_progress_copy_body)) }
        }
        SettingsGroupHeader(stringResource(R.string.project_progress_body))
        SettingsGroup {
            SelectionContainer {
                Text(entry.body, modifier = Modifier.fillMaxWidth().padding(Spacing.lg), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.project_progress_read_only_history), modifier = Modifier.padding(Spacing.sm),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!entry.revoked || !entry.archived) {
            SettingsGroup {
                if (!entry.revoked) {
                    SettingsRow(title = stringResource(R.string.project_progress_revoke), enabled = enabled,
                        onClick = { onAction(ProjectProgressEntryAction.REVOKE) })
                }
                if (!entry.archived) {
                    if (!entry.revoked) SettingsDivider()
                    SettingsRow(title = stringResource(R.string.project_progress_archive), enabled = enabled,
                        onClick = { onAction(ProjectProgressEntryAction.ARCHIVE) })
                }
            }
        }
    }
}
