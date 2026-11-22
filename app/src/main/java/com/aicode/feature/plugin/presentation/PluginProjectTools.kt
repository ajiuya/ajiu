package com.aicode.feature.plugin.presentation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Singleton
class PluginProjectToolsRepository @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("plugin_project_tools", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0L)
    val changes = revision
    fun selected(workspace: String): Set<String> = preferences.getStringSet(workspace, emptySet()).orEmpty().toSet()
    fun set(workspace: String, key: String, selected: Boolean) {
        if (workspace.isBlank()) return
        val next = selected(workspace).toMutableSet().apply { if (selected) add(key) else remove(key) }
        preferences.edit().putStringSet(workspace, next).apply()
        revision.value++
    }
}

@HiltViewModel
class PluginProjectToolsViewModel @Inject constructor(
    private val manager: PluginManager,
    private val workspaces: WorkspaceRepository,
    private val repository: PluginProjectToolsRepository,
    internal val gateway: PluginHostGateway,
    internal val actions: PluginActions
) : ViewModel() {
    internal val state = combine(manager.snapshot, workspaces.selection, repository.changes) { snapshot, selection, _ ->
        val path = selection.workspace?.path.orEmpty()
        Triple(path, snapshot.plugins.filter { it.isActive }.flatMap { it.content.workspaceTools }, repository.selected(path))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple("", emptyList<PluginWorkspaceTool>(), emptySet()))
    init { viewModelScope.launch { manager.ensureLoaded() } }
    internal fun pin(key: String, selected: Boolean) { repository.set(state.value.first, key, selected) }
    internal fun capture(id: String) = manager.capture(id)
}

@Composable
fun PluginProjectTools(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val viewModel: PluginProjectToolsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var all by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.plugin_project_tools))
            TextButton(onClick = onClose) { Text(stringResource(R.string.plugin_close)) }
        }
        TextButton(onClick = { all = !all }) { Text(stringResource(if (all) R.string.plugin_project_tools else R.string.plugin_project_tools_all)) }
        val tools = state.second.filter { all || it.key in state.third }
        if (state.second.isEmpty()) Text(stringResource(R.string.plugin_project_tools_empty))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(tools, key = { it.key }) { tool ->
                SettingsGroup {
                    SettingsRow(title = tool.title.resolve(language), subtitle = tool.description.resolve(language), onClick = { onOpen(tool.key) })
                    if (all) SettingsRow(title = stringResource(R.string.plugin_project_tools_pin), trailing = { AppSwitch(tool.key in state.third, { viewModel.pin(tool.key, it) }) })
                }
            }
        }
    }
}

@Composable
fun PluginWorkspacePage(toolKey: String, onClose: () -> Unit) {
    val viewModel: PluginProjectToolsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val tool = state.second.firstOrNull { it.key == toolKey }
    val capture = remember(tool, state) { tool?.let { viewModel.capture(it.pluginId) } }
    if (tool == null || capture == null) Text(stringResource(R.string.plugin_page_reopen), modifier = Modifier.padding(Spacing.lg))
    else {
        val page = PluginPage(tool.pluginId, tool.localId, tool.title, null, tool.pageFile, tool.icon, tool.installDir, tool.pluginVersion)
        NativePluginPage(capture, page, language, { viewModel.gateway.captureContext(it) }, viewModel.actions::call, onClose, embedded = true)
    }
}
