package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.runtime.PluginApprovalCoordinator
import com.aicode.feature.plugin.runtime.PluginApprovalChoice
import com.aicode.feature.plugin.runtime.PluginApprovalOperation
import com.aicode.feature.plugin.runtime.PluginHostApproval
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PluginApprovalViewModel @Inject constructor(private val coordinator: PluginApprovalCoordinator) : ViewModel() {
    val pending = coordinator.pending
    fun resolve(id: String, choice: PluginApprovalChoice) = coordinator.resolve(id, choice)
}

@Composable
fun PluginApprovalHost(viewModel: PluginApprovalViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    pending?.let { request ->
        DisposableEffect(request.id) {
            onDispose { viewModel.resolve(request.id, PluginApprovalChoice.REJECT) }
        }
        PluginApprovalDialog(request, viewModel::resolve)
    }
}

internal fun PluginApprovalOperation.labelRes(): Int = when (this) {
    PluginApprovalOperation.EXECUTE_TOOL -> R.string.plugin_approval_execute_tool
    PluginApprovalOperation.EXECUTE_COMMAND -> R.string.plugin_approval_execute_command
    PluginApprovalOperation.READ_FILES -> R.string.plugin_approval_read_files
    PluginApprovalOperation.WRITE_FILES -> R.string.plugin_approval_write_files
    PluginApprovalOperation.DELETE_FILES -> R.string.plugin_approval_delete_files
    PluginApprovalOperation.PREPARE_DEPENDENCIES -> R.string.plugin_approval_prepare_dependencies
}

@Composable
internal fun PluginApprovalDialog(request: PluginHostApproval, onDecision: (String, PluginApprovalChoice) -> Unit) {
    var decided by remember(request.id) { mutableStateOf(false) }
    fun decide(choice: PluginApprovalChoice) {
        if (decided) return
        decided = true
        onDecision(request.id, choice)
    }
    AlertDialog(
        onDismissRequest = { decide(PluginApprovalChoice.REJECT) },
        title = { Text(stringResource(R.string.plugin_approval_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.plugin_approval_identity, request.pluginName, request.pluginId))
                Text(stringResource(request.operation.labelRes()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = Spacing.sm))
                Text(request.details)
                if (request.canAlwaysAllow) {
                    Text(stringResource(R.string.plugin_approval_scope), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md))
                    Text(request.scopeSummary)
                }
                Text(stringResource(R.string.plugin_approval_warning), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.md))
            }
        },
        confirmButton = {
            Column {
                Button(onClick = { decide(PluginApprovalChoice.ONCE) }, enabled = !decided) { Text(stringResource(R.string.plugin_approval_allow_once)) }
                if (request.canAlwaysAllow) TextButton(onClick = { decide(PluginApprovalChoice.ALWAYS) }, enabled = !decided) { Text(stringResource(R.string.plugin_approval_always)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { decide(PluginApprovalChoice.REJECT) }, enabled = !decided) { Text(stringResource(R.string.plugin_approval_deny)) }
        }
    )
}
