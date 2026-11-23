package com.aicode.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Sliders

@Composable
internal fun MobileBuildSettingsGroup(
    settings: MobileBuildSettings,
    saving: Boolean,
    @StringRes error: Int?,
    optionsSavedVersion: Long,
    onSetEnabled: (Boolean) -> Unit,
    onSaveOptions: (MobileBuildSettings) -> Unit,
    onClearError: () -> Unit = {}
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var savedVersionAtOpen by remember { mutableLongStateOf(optionsSavedVersion) }

    LaunchedEffect(showOptions, optionsSavedVersion) {
        if (showOptions && optionsSavedVersion > savedVersionAtOpen) showOptions = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SettingsGroupHeader(text = stringResource(R.string.mobile_build_title))
        SettingsGroup {
            val enabledTitle = stringResource(R.string.mobile_build_enable)
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = enabledTitle,
                subtitle = stringResource(R.string.mobile_build_enable_hint),
                trailing = {
                    AppSwitch(
                        checked = settings.enabled,
                        onCheckedChange = onSetEnabled,
                        enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = enabledTitle }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(R.string.mobile_build_options),
                subtitle = stringResource(
                    R.string.mobile_build_summary,
                    mobileBuildHeapLabel(settings.gradleHeapMb),
                    stringResource(if (settings.daemonEnabled) R.string.mobile_build_on else R.string.mobile_build_off),
                    stringResource(if (settings.parallelEnabled) R.string.mobile_build_on else R.string.mobile_build_off),
                    settings.maxWorkers,
                    mobileBuildHeapLabel(settings.testHeapMb)
                ),
                enabled = !saving,
                onClick = {
                    onClearError()
                    savedVersionAtOpen = optionsSavedVersion
                    showOptions = true
                }
            )
        }
        Text(
            text = stringResource(R.string.mobile_build_scope_hint),
            modifier = Modifier.padding(horizontal = Spacing.md),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!showOptions) {
            error?.let {
                Text(
                    text = stringResource(it),
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showOptions) {
        MobileBuildOptionsSheet(
            settings = settings,
            saving = saving,
            error = error,
            onSave = onSaveOptions,
            onDismiss = {
                if (!saving) {
                    showOptions = false
                    onClearError()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileBuildOptionsSheet(
    settings: MobileBuildSettings,
    saving: Boolean,
    @StringRes error: Int?,
    onSave: (MobileBuildSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val isSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !isSaving }
    )
    AdaptiveModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        sheetState = sheetState,
        sheetGesturesEnabled = !saving
    ) {
        MobileBuildOptionsForm(settings, saving, error, onSave, onDismiss)
    }
}

@Composable
internal fun MobileBuildOptionsForm(
    settings: MobileBuildSettings,
    saving: Boolean,
    @StringRes error: Int?,
    onSave: (MobileBuildSettings) -> Unit,
    onCancel: () -> Unit
) {
    var gradleHeapMb by rememberSaveable { mutableIntStateOf(settings.gradleHeapMb) }
    var daemonEnabled by rememberSaveable { mutableStateOf(settings.daemonEnabled) }
    var parallelEnabled by rememberSaveable { mutableStateOf(settings.parallelEnabled) }
    var maxWorkers by rememberSaveable { mutableIntStateOf(settings.maxWorkers) }
    var testHeapMb by rememberSaveable { mutableIntStateOf(settings.testHeapMb) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.mobile_build_options_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.mobile_build_options_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsGroup {
            MobileBuildOptionChoices(
                title = stringResource(R.string.mobile_build_gradle_heap),
                hint = stringResource(R.string.mobile_build_gradle_heap_hint),
                options = MobileBuildSettings.GRADLE_HEAP_OPTIONS,
                selected = gradleHeapMb,
                enabled = !saving,
                label = { mobileBuildHeapLabel(it) },
                onSelect = { gradleHeapMb = it }
            )
            SettingsDivider()
            val daemonTitle = stringResource(R.string.mobile_build_daemon)
            SettingsRow(
                title = daemonTitle,
                subtitle = stringResource(R.string.mobile_build_daemon_hint),
                trailing = {
                    AppSwitch(
                        checked = daemonEnabled,
                        onCheckedChange = { daemonEnabled = it },
                        enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = daemonTitle }
                    )
                }
            )
            SettingsDivider()
            val parallelTitle = stringResource(R.string.mobile_build_parallel)
            SettingsRow(
                title = parallelTitle,
                subtitle = stringResource(R.string.mobile_build_parallel_hint),
                trailing = {
                    AppSwitch(
                        checked = parallelEnabled,
                        onCheckedChange = { parallelEnabled = it },
                        enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = parallelTitle }
                    )
                }
            )
            SettingsDivider()
            MobileBuildOptionChoices(
                title = stringResource(R.string.mobile_build_workers),
                hint = stringResource(R.string.mobile_build_workers_hint),
                options = MobileBuildSettings.WORKER_OPTIONS,
                selected = maxWorkers,
                enabled = !saving,
                label = { stringResource(R.string.mobile_build_worker_option, it) },
                onSelect = { maxWorkers = it }
            )
            SettingsDivider()
            MobileBuildOptionChoices(
                title = stringResource(R.string.mobile_build_test_heap),
                hint = stringResource(R.string.mobile_build_test_heap_hint),
                options = MobileBuildSettings.TEST_HEAP_OPTIONS,
                selected = testHeapMb,
                enabled = !saving,
                label = { mobileBuildHeapLabel(it) },
                onSelect = { testHeapMb = it }
            )
        }
        Text(
            text = stringResource(R.string.mobile_build_scope_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = {
                if (!saving) {
                    onSave(
                        settings.copy(
                            gradleHeapMb = gradleHeapMb,
                            daemonEnabled = daemonEnabled,
                            parallelEnabled = parallelEnabled,
                            maxWorkers = maxWorkers,
                            testHeapMb = testHeapMb
                        )
                    )
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (saving) R.string.mobile_build_saving else R.string.common_save))
        }
        TextButton(
            onClick = { if (!saving) onCancel() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileBuildOptionChoices(
    title: String,
    hint: String,
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    label: @Composable (Int) -> String,
    onSelect: (Int) -> Unit
) {
    SettingsRow(title = title, subtitle = hint)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { option ->
            val optionLabel = label(option)
            val optionDescription = stringResource(R.string.mobile_build_option_description, title, optionLabel)
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(optionLabel) },
                modifier = Modifier.semantics { contentDescription = optionDescription }
            )
        }
    }
}

@Composable
private fun mobileBuildHeapLabel(heapMb: Int): String = when (heapMb) {
    512 -> stringResource(R.string.mobile_build_heap_512_mb)
    768 -> stringResource(R.string.mobile_build_heap_768_mb)
    1024 -> stringResource(R.string.mobile_build_heap_1_gb)
    1536 -> stringResource(R.string.mobile_build_heap_1_5_gb)
    2048 -> stringResource(R.string.mobile_build_heap_2_gb)
    2560 -> stringResource(R.string.mobile_build_heap_2_5_gb)
    3072 -> stringResource(R.string.mobile_build_heap_3_gb)
    else -> stringResource(R.string.mobile_build_heap_mb, heapMb)
}
