package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.domain.PluginUiEntryKind
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.settings.presentation.component.SettingsRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class PluginSlotSelection(val contribution: PluginContribution<PluginUiEntry>, val context: PluginInvocationContext, val args: JsonObject)

@Singleton
class PluginSlotHost @Inject constructor(
    internal val manager: PluginManager,
    internal val gateway: PluginHostGateway,
    internal val actions: PluginActions
)

val LocalPluginSlotHost = staticCompositionLocalOf<PluginSlotHost?> { null }

internal class PluginSlotViewModel(private val manager: PluginManager, private val gateway: PluginHostGateway, private val actions: PluginActions) : ViewModel() {
    val snapshot = manager.snapshot
    private val mutableSelection = MutableStateFlow<PluginSlotSelection?>(null)
    internal val selection = mutableSelection.asStateFlow()
    private val mutableProblem = MutableStateFlow<PluginProblem?>(null)
    internal val problem = mutableProblem.asStateFlow()
    private val mutableResult = MutableStateFlow<String?>(null)
    internal val result = mutableResult.asStateFlow()
    init { viewModelScope.launch { manager.ensureLoaded() } }
    internal fun openCurrent(id: String, uiEntry: PluginUiEntry, args: JsonObject) {
        val capture = manager.capture(id) ?: return
        if (capture.content.uiEntries.none { it == uiEntry }) return
        val entry = PluginContribution(capture, uiEntry)
        viewModelScope.launch {
            try {
                val context = gateway.captureContext(entry.capture, sessionId = (args["sessionId"] as? JsonPrimitive)?.content)
                mutableSelection.value = PluginSlotSelection(entry, context, args)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProblem.value = PluginProblem("CONTEXT_CHANGED", R.string.plugin_error_context) }
        }
    }
    internal fun close() { mutableSelection.value = null; mutableProblem.value = null; mutableResult.value = null }
    internal fun run(selection: PluginSlotSelection, args: JsonObject) {
        if (mutableSelection.value !== selection) return
        viewModelScope.launch {
            try {
                selection.context.checkIntegrity()
                val result = actions.invoke(selection.contribution.capture, requireNotNull(selection.contribution.value.handler), JsonObject(selection.args + args), selection.context)
                mutableResult.value = result.toString().take(65536)
                mutableSelection.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProblem.value = PluginProblem("ACTION_FAILED", R.string.plugin_error_runtime); mutableSelection.value = null }
        }
    }
    internal suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement = actions.call(context, method, params)
}

@Composable
fun PluginSlotContent(slot: String, args: JsonObject = JsonObject(emptyMap()), modifier: Modifier = Modifier, enabled: Boolean = true) {
    val host = LocalPluginSlotHost.current ?: return
    val instanceId = rememberSaveable(slot) { java.util.UUID.randomUUID().toString() }
    val factory = remember(host) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                modelClass.cast(PluginSlotViewModel(host.manager, host.gateway, host.actions))!!
        }
    }
    val viewModel: PluginSlotViewModel = viewModel(key = "plugin-slot-$slot-$instanceId", factory = factory)
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val entries = remember(snapshot, slot) { snapshot.plugins.filter { it.isActive }.flatMap { installed ->
        installed.content.uiEntries.filter { it.slot == slot && it.kind != PluginUiEntryKind.DATA }.map { installed.record.id to it }
    } }
    Column(modifier) {
        entries.forEach { (id, entry) ->
            SettingsRow(title = entry.title.resolve(language), subtitle = id, enabled = enabled, onClick = {
                viewModel.openCurrent(id, entry, args)
            })
        }
        PluginProblems(listOfNotNull(problem), Modifier.padding(horizontal = Spacing.lg))
    }
    selection?.let { selected ->
        val entry = selected.contribution.value
        val page = entry.page
        if (page != null) NativePluginPage(selected.contribution.capture, page, language, { selected.context }, viewModel::call, viewModel::close)
        else PluginActionDialog(entry, language, false, { viewModel.run(selected, it) }, viewModel::close)
    }
    result?.let { text -> AlertDialog(onDismissRequest = viewModel::close, title = { Text(stringResource(R.string.plugin_action_result)) }, text = { Text(text) }, confirmButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.plugin_close)) } }) }
}
