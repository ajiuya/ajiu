package com.aicode.feature.plugin.presentation

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.theme.Radius
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
internal fun PluginProjectToolsDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().testTag("plugin-project-tools-dismiss").clickable(
                interactionSource = null,
                indication = null,
                onClick = onClose
            ))
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                val maximumHeight = maxHeight * 0.8f
                Surface(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                        .heightIn(min = minOf(438.dp, maximumHeight), max = maximumHeight)
                        .testTag("plugin-project-tools-dialog"),
                    shape = RoundedCornerShape(Radius.xl),
                    tonalElevation = Spacing.xs,
                    shadowElevation = Spacing.md
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun PluginProjectTools(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val viewModel: PluginProjectToolsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    PluginProjectToolsContent(state.second, state.third, language, viewModel::pin, onOpen, onClose)
}

@Composable
internal fun PluginProjectToolsContent(
    tools: List<PluginWorkspaceTool>,
    pinned: Set<String>,
    language: String,
    onPin: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit
) {
    var all by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.plugin_project_tools),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onClose, modifier = Modifier.testTag("plugin-project-tools-close")) {
                Text(stringResource(R.string.plugin_close))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("plugin-project-tools-list"),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "project-tools-filter") {
                TextButton(onClick = { all = !all }, modifier = Modifier.testTag("plugin-project-tools-filter")) {
                    Text(stringResource(if (all) R.string.plugin_project_tools else R.string.plugin_project_tools_all))
                }
            }
            if (tools.isEmpty()) item(key = "project-tools-empty") {
                Text(stringResource(R.string.plugin_project_tools_empty))
            }
            items(tools.filter { all || it.key in pinned }, key = { it.key }) { tool ->
                SettingsGroup {
                    SettingsRow(title = tool.title.resolve(language), subtitle = tool.description.resolve(language), onClick = { onOpen(tool.key) })
                    if (all) SettingsRow(title = stringResource(R.string.plugin_project_tools_pin), trailing = { AppSwitch(tool.key in pinned, { onPin(tool.key, it) }) })
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
