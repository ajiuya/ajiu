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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.runtime.PluginDependencyRequest

@Composable
internal fun PluginDependencyDialog(
    request: PluginDependencyRequest,
    pluginName: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var confirmed by remember(request.requestId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_dependencies_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.plugin_approval_identity, pluginName, request.pluginId))
                Text(request.tool, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = Spacing.sm))
                Text(stringResource(R.string.plugin_dependencies_warning))
                Text(stringResource(R.string.plugin_content_digest, request.inputDigest), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { if (!confirmed) { confirmed = true; onConfirm(request.requestId) } }, enabled = !busy && !confirmed) {
                Text(stringResource(R.string.plugin_dependencies_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.plugin_cancel)) } }
    )
}
