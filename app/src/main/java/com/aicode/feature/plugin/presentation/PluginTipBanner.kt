package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.plugin.runtime.PluginProcessException
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.settings.presentation.component.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@HiltViewModel
class PluginTipsViewModel @Inject constructor(private val tips: PluginTipManager, private val runtime: PluginRuntimeManager, private val gateway: PluginHostGateway) : ViewModel() {
    internal val entries = tips.tips
    internal fun dismiss(id: String) { tips.dismissTip(id) }
    internal fun action(tip: PluginTip) {
        val handler = tip.actionHandler ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var invocation: PluginInvocationContext? = null
            try {
                val child = tip.invocation.child()
                invocation = child
                child.checkIntegrity()
                val process = child.capture.content.runtime ?: return@launch
                runtime.invoke(child.capture, process, handler, buildJsonObject { put("tipId", tip.tipId); put("action", "primary") }, child.runtimeContext(), { method, params -> gateway.call(child, method, params) }, method = "ui.invoke")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = when (failure) {
                    is PluginHostException -> failure.code
                    is PluginProcessException -> failure.code
                    else -> "PLUGIN_ACTION_FAILED"
                }.filterNot { it.isISOControl() }.take(80)
                FileLogger.w("PluginTip", "Tip action failed: $code")
            } finally { invocation?.close(); tips.dismissTip(tip.tipId) }
        }
    }
}

@Composable
fun PluginTipBanners() {
    val viewModel: PluginTipsViewModel = hiltViewModel()
    val tips by viewModel.entries.collectAsStateWithLifecycle()
    Column {
        tips.forEach { tip ->
            LaunchedEffect(tip) { tip.invocation.changes.collect { if (runCatching { tip.invocation.checkCurrent() }.isFailure) viewModel.dismiss(tip.tipId) } }
            SettingsGroup { Column(Modifier.padding(Spacing.md)) {
                Text(tip.pluginId, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                if (tip.title.isNotBlank()) Text(tip.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Text(tip.text)
                Row {
                    if (tip.actionLabel != null && tip.actionHandler != null) TextButton(onClick = { viewModel.action(tip) }) { Text(tip.actionLabel) }
                    TextButton(onClick = { viewModel.dismiss(tip.tipId) }) { Text(stringResource(R.string.plugin_tip_dismiss)) }
                }
            } }
        }
    }
}
