package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.pageEnter
import com.aicode.core.ui.pageExit
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.presentation.MemoryUiState
import com.aicode.feature.settings.presentation.MemoryViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySettingsRoute(
    viewModel: MemoryViewModel,
    showBack: Boolean,
    onNavigateBack: () -> Unit,
    onOpenProjectProgress: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    BackHandler(enabled = state.selectedEntry != null) { viewModel.closeDetail() }
    val pageStates = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = state.selectedEntry,
        contentKey = { it?.id ?: "memory-list" },
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState != null
            ContentTransform(pageEnter(forward), pageExit(forward), sizeTransform = null)
        },
        label = "memory-detail"
    ) { selected ->
        pageStates.SaveableStateProvider(selected?.id ?: "memory-list") {
            Scaffold(
                containerColor = settingsPageBackground(),
                topBar = {
                    TopAppBar(
                        title = { Text(selected?.name ?: stringResource(R.string.memory_management_title)) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground()),
                        navigationIcon = {
                            if (showBack || selected != null) {
                                IconButton(onClick = {
                                    if (selected != null) viewModel.closeDetail() else onNavigateBack()
                                }) {
                                    Icon(FeatherIcons.ArrowLeft, stringResource(R.string.common_back))
                                }
                            }
                        },
                        actions = {
                            if (selected == null) {
                                IconButton(onClick = viewModel::refresh, enabled = !state.loading && !state.targetChanged) {
                                    Icon(FeatherIcons.RefreshCw, stringResource(R.string.memory_management_refresh))
                                }
                                IconButton(onClick = viewModel::startCreate, enabled = !state.targetChanged && !state.loading) {
                                    Icon(FeatherIcons.Plus, stringResource(R.string.memory_management_add))
                                }
                            } else {
                                IconButton(
                                    onClick = { state.document?.entry?.let(viewModel::requestDelete) },
                                    enabled = state.document != null && !state.deleting && !state.targetChanged
                                ) { Icon(FeatherIcons.Trash2, stringResource(R.string.common_delete)) }
                            }
                        }
                    )
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (state.targetChanged) {
                        Text(
                            stringResource(R.string.memory_management_target_changed),
                            modifier = Modifier.padding(Spacing.lg),
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = viewModel::open) { Text(stringResource(R.string.memory_management_reload)) }
                    } else {
                        if (state.error != null && !state.creating) {
                            Text(
                                stringResource(memoryManagementErrorRes(state.error!!)),
                                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (selected == null) {
                            onOpenProjectProgress?.let { open ->
                                SettingsGroup {
                                    SettingsRow(title = stringResource(R.string.project_progress_title), onClick = open)
                                }
                            }
                            MemorySection(state, viewModel::openDetail, viewModel::requestDelete)
                        } else if (state.detailLoading) {
                            MemoryLoading()
                        } else {
                            state.document?.takeIf { it.entry.id == selected.id }?.let { MemoryDetailSection(it) }
                        }
                    }
                }
            }
        }
    }
    if (state.creating && !state.targetChanged) {
        NewMemorySheet(
            hasWorkspace = state.hasWorkspace,
            saving = state.saving,
            error = state.error,
            onSave = viewModel::create,
            onDismiss = viewModel::dismissCreate
        )
    }
    state.pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.memory_management_delete_title)) },
            text = {
                Text(stringResource(
                    R.string.memory_management_delete_message, entry.name,
                    stringResource(memoryScopeLabelRes(entry.scope))
                ))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete, enabled = !state.deleting) {
                    if (state.deleting) CircularProgressIndicator(modifier = Modifier.size(Spacing.lg))
                    else Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
internal fun MemorySection(
    state: MemoryUiState,
    onOpenDetail: (MemoryEntry) -> Unit,
    onDelete: (MemoryEntry) -> Unit
) {
    if (state.loading && state.entries.isEmpty()) {
        MemoryLoading()
        return
    }
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        MemoryScope.entries.sortedBy { if (it == MemoryScope.PROJECT) 0 else 1 }.forEach { scope ->
            val entries = state.entries.filter { it.scope == scope }
            val expanded = if (scope == MemoryScope.PROJECT) projectExpanded else globalExpanded
            item(key = "${scope.name}-header") {
                CollapsibleGroupHeader(
                    text = if (scope == MemoryScope.PROJECT && state.projectName != null) {
                        stringResource(R.string.memory_management_project_named, state.projectName)
                    } else stringResource(memoryScopeLabelRes(scope)),
                    expanded = expanded,
                    onToggle = {
                        if (scope == MemoryScope.PROJECT) projectExpanded = !projectExpanded else globalExpanded = !globalExpanded
                    }
                )
            }
            if (expanded) {
                if (entries.isEmpty()) {
                    item(key = "${scope.name}-empty") {
                        SettingsGroup {
                            Text(
                                stringResource(if (scope == MemoryScope.PROJECT && !state.hasWorkspace) {
                                    R.string.memory_management_no_workspace
                                } else R.string.memory_management_empty),
                                modifier = Modifier.padding(Spacing.lg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        LazyGroupItem(index, entries.lastIndex) {
                            SwipeToDeleteRow(onDelete = { onDelete(entry) }, onClick = { onOpenDetail(entry) }) {
                                Row(
                                    Modifier.fillMaxWidth().padding(Spacing.lg),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (entry.description.isNotBlank()) {
                                            Text(entry.description, style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (entry.preview.isNotBlank()) {
                                            Text(entry.preview, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.semanticColors.subtleText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Icon(FeatherIcons.ChevronRight, null, tint = MaterialTheme.semanticColors.subtleText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

internal fun memoryScopeLabelRes(scope: MemoryScope): Int = when (scope) {
    MemoryScope.GLOBAL -> R.string.memory_management_global
    MemoryScope.PROJECT -> R.string.memory_management_project
}

internal fun memoryManagementErrorRes(error: MemoryManagementError): Int = when (error) {
    MemoryManagementError.INVALID_NAME -> R.string.memory_management_invalid_name
    MemoryManagementError.NAME_CONFLICT -> R.string.memory_management_name_conflict
    MemoryManagementError.NO_WORKSPACE -> R.string.memory_management_no_workspace
    MemoryManagementError.NOT_FOUND -> R.string.memory_management_not_found
    MemoryManagementError.CHANGED -> R.string.memory_management_changed
    MemoryManagementError.UNSAFE_PATH -> R.string.memory_management_unsafe_path
    MemoryManagementError.IO_FAILED -> R.string.memory_management_io_failed
}
