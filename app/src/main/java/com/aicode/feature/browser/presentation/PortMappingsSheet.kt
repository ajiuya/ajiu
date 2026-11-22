package com.aicode.feature.browser.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.container.PortMapping
import com.aicode.feature.agent.domain.container.PortMappingState
import com.aicode.feature.agent.domain.container.PortMappingStatus
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortMappingsSheet(
    state: PortMappingState,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: (String) -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.semanticColors.pageBackground
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_done))
                }
            }
            PortMappingsContent(
                state = state,
                onAdd = onAdd,
                onOpen = onOpen,
                onCopy = onCopy,
                onClose = onClose
            )
        }
    }
}

@Composable
internal fun PortMappingsContent(
    state: PortMappingState,
    onAdd: (String, String) -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: (String) -> Unit
) {
    var remotePort by rememberSaveable { mutableStateOf("") }
    var localPort by rememberSaveable { mutableStateOf("") }
    val remoteValid = parsePortMappingPort(remotePort) != null
    val localValid = localPort.isEmpty() || parsePortMappingPort(localPort) != null
    val limitReached = state.mappings.count { it.status != PortMappingStatus.FAILED } >= 4

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
            .padding(bottom = Spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.port_mapping_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(Spacing.sm))
        if (!state.remoteMode) {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.port_mapping_error_local_mode))
            }
            return@Column
        }

        Text(
            text = stringResource(R.string.port_mapping_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        if (!state.available) {
            Text(
                text = stringResource(R.string.port_mapping_error_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.warning
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        SettingsGroup {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppTextField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    modifier = Modifier.fillMaxWidth().testTag("port-mapping-remote-port"),
                    label = stringResource(R.string.port_mapping_remote_port),
                    placeholder = stringResource(R.string.port_mapping_port_range),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = state.available,
                    isError = remotePort.isNotEmpty() && !remoteValid,
                    supportingText = if (remotePort.isNotEmpty() && !remoteValid) {
                        { Text(stringResource(R.string.port_mapping_error_invalid_port)) }
                    } else null
                )
                AppTextField(
                    value = localPort,
                    onValueChange = { localPort = it },
                    modifier = Modifier.fillMaxWidth().testTag("port-mapping-local-port"),
                    label = stringResource(R.string.port_mapping_local_port),
                    placeholder = stringResource(R.string.port_mapping_local_auto),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = state.available,
                    isError = !localValid,
                    supportingText = if (!localValid) {
                        { Text(stringResource(R.string.port_mapping_error_invalid_port)) }
                    } else null
                )
                if (limitReached) {
                    Text(
                        text = stringResource(R.string.port_mapping_error_limit_reached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.warning
                    )
                }
                Button(
                    onClick = { onAdd(remotePort, localPort) },
                    enabled = state.available && remoteValid && localValid && !limitReached,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.port_mapping_add))
                }
            }
        }

        SettingsGroupHeader(stringResource(R.string.port_mapping_list_title))
        if (state.mappings.isEmpty()) {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.port_mapping_empty))
            }
        } else {
            state.mappings.forEach { mapping ->
                key(mapping.id) {
                    PortMappingRow(
                        mapping = mapping,
                        available = state.available,
                        onOpen = { onOpen(mapping.id) },
                        onCopy = { onCopy(mapping.id) },
                        onClose = { onClose(mapping.id) }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }
}

@Composable
private fun PortMappingRow(
    mapping: PortMapping,
    available: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit
) {
    SettingsGroup {
        SettingsRow(
            title = if (mapping.status == PortMappingStatus.ACTIVE && mapping.localPort != null) {
                stringResource(R.string.port_mapping_route, mapping.remotePort, mapping.localPort)
            } else {
                stringResource(R.string.port_mapping_remote_endpoint, mapping.remotePort)
            },
            subtitle = stringResource(when (mapping.status) {
                PortMappingStatus.CONNECTING -> R.string.port_mapping_status_connecting
                PortMappingStatus.ACTIVE -> R.string.port_mapping_status_active
                PortMappingStatus.FAILED -> R.string.port_mapping_status_failed
            })
        )
        if (mapping.status == PortMappingStatus.FAILED) {
            mapping.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }
        }
        if (mapping.status == PortMappingStatus.ACTIVE && !mapping.backgroundProtected) {
            Text(
                text = stringResource(R.string.port_mapping_background_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.warning,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }
        SettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.End
        ) {
            if (mapping.status == PortMappingStatus.ACTIVE) {
                TextButton(onClick = onOpen, enabled = available) {
                    Text(stringResource(R.string.port_mapping_open))
                }
                TextButton(onClick = onCopy, enabled = available) {
                    Text(stringResource(R.string.port_mapping_copy))
                }
            }
            TextButton(onClick = onClose) {
                Text(stringResource(
                    if (mapping.status == PortMappingStatus.CONNECTING) R.string.port_mapping_cancel
                    else R.string.port_mapping_close
                ))
            }
        }
    }
}
