package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewMemorySheet(
    hasWorkspace: Boolean,
    saving: Boolean,
    error: MemoryManagementError?,
    onSave: (String, String, String, MemoryScope) -> Unit,
    onDismiss: () -> Unit,
    initialScope: MemoryScope = MemoryScope.GLOBAL
) {
    val sheetState = rememberNewMemorySheetState(saving)
    AdaptiveModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !saving
    ) {
        NewMemoryForm(hasWorkspace, saving, error, onSave, initialScope)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberNewMemorySheetState(saving: Boolean): SheetState {
    val currentSaving by rememberUpdatedState(saving)
    return rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !currentSaving }
    )
}

@Composable
internal fun NewMemoryForm(
    hasWorkspace: Boolean,
    saving: Boolean,
    error: MemoryManagementError?,
    onSave: (String, String, String, MemoryScope) -> Unit,
    initialScope: MemoryScope = MemoryScope.GLOBAL
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(if (hasWorkspace) initialScope else MemoryScope.GLOBAL) }
    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.memory_management_add), style = MaterialTheme.typography.titleLarge)
        SettingsGroup {
            MemoryScope.entries.forEachIndexed { index, option ->
                if (index > 0) SettingsDivider()
                val enabled = !saving && (option != MemoryScope.PROJECT || hasWorkspace)
                SettingsRow(
                    title = stringResource(memoryScopeLabelRes(option)),
                    subtitle = if (option == MemoryScope.PROJECT && !hasWorkspace) {
                        stringResource(R.string.memory_management_no_workspace)
                    } else null,
                    enabled = enabled,
                    onClick = { scope = option },
                    trailing = {
                        RadioButton(selected = scope == option, onClick = { scope = option }, enabled = enabled)
                    }
                )
            }
        }
        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.memory_management_name),
            supportingText = { Text(stringResource(R.string.memory_management_name_hint)) },
            enabled = !saving,
            isError = error == MemoryManagementError.INVALID_NAME || error == MemoryManagementError.NAME_CONFLICT,
            colors = dialogTextFieldColors()
        )
        AppTextField(
            value = description,
            onValueChange = { description = it },
            label = stringResource(R.string.memory_management_description),
            enabled = !saving,
            colors = dialogTextFieldColors()
        )
        AppTextField(
            value = body,
            onValueChange = { body = it },
            label = stringResource(R.string.memory_management_body),
            singleLine = false,
            minLines = 6,
            enabled = !saving,
            colors = dialogTextFieldColors()
        )
        error?.let {
            Text(stringResource(memoryManagementErrorRes(it)), color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onSave(name, description, body, scope) },
            enabled = !saving && name.isNotBlank() && body.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (saving) R.string.memory_management_creating else R.string.memory_management_create))
        }
    }
}
