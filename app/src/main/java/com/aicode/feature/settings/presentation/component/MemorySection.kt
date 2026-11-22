package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
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
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe
import compose.icons.feathericons.List
import compose.icons.feathericons.Search
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw

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
    var selectedScope by rememberSaveable { mutableStateOf<MemoryScope?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = selectedScope ?: if (state.hasWorkspace) MemoryScope.PROJECT else MemoryScope.GLOBAL
    LaunchedEffect(state.hasWorkspace, state.loading) {
        if (!state.loading && !state.hasWorkspace && selectedScope == MemoryScope.PROJECT) {
            selectedScope = MemoryScope.GLOBAL
        }
    }
    LaunchedEffect(state.createdScope) {
        state.createdScope?.let {
            selectedScope = it
            query = ""
            viewModel.consumeCreatedScope()
        }
    }
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
                        title = {
                            Text(selected?.name ?: stringResource(R.string.memory_management_title),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
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
                                    Icon(FeatherIcons.RefreshCw, stringResource(R.string.memory_management_refresh),
                                        tint = MaterialTheme.semanticColors.subtleText)
                                }
                                IconButton(onClick = viewModel::startCreate, enabled = !state.targetChanged && !state.loading) {
                                    Icon(FeatherIcons.Plus, stringResource(R.string.memory_management_add),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
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
                        TextButton(onClick = {
                            selectedScope = null
                            query = ""
                            viewModel.open()
                        }) { Text(stringResource(R.string.memory_management_reload)) }
                    } else {
                        if (state.error != null && !state.creating) {
                            Text(
                                stringResource(memoryManagementErrorRes(state.error!!)),
                                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (selected == null) {
                            MemorySection(
                                state, viewModel::openDetail, viewModel::requestDelete,
                                scope = scope, query = query,
                                onScopeChange = { selectedScope = it }, onQueryChange = { query = it },
                                onOpenProjectProgress = onOpenProjectProgress
                            )
                        } else if (state.detailLoading) {
                            MemoryLoading()
                        } else {
                            state.document?.takeIf { it.entry.id == selected.id }?.let {
                                MemoryDetailSection(it, projectName = state.projectName,
                                    busy = state.deleting || state.saving || state.targetChanged,
                                    onDelete = viewModel::requestDelete)
                            }
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
            onDismiss = viewModel::dismissCreate,
            initialScope = scope
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
    onDelete: (MemoryEntry) -> Unit,
    scope: MemoryScope,
    query: String,
    onScopeChange: (MemoryScope) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenProjectProgress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val search = query.trim()
    val entries = state.entries.filter { entry ->
        entry.scope == scope && (search.isEmpty() || listOf(entry.name, entry.description, entry.preview)
            .any { it.contains(search, ignoreCase = true) })
    }
    val listState = rememberLazyListState()
    if (state.loading && state.entries.isEmpty()) {
        MemoryLoading()
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("memory-list"),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xl)
    ) {
        item(key = "scope-search") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("memory-scope-segments")
                    .clip(RoundedCornerShape(Radius.mdLarge)).background(MaterialTheme.semanticColors.capsuleSurface)
                    .selectableGroup().padding(Spacing.xs)
            ) {
                MemoryScope.entries.forEach { option ->
                    val selected = scope == option
                    val enabled = option == MemoryScope.GLOBAL || state.hasWorkspace
                    Row(
                        Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.sm))
                            .background(if (selected) MaterialTheme.semanticColors.cardSurface else Color.Transparent)
                            .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                                onClick = { onScopeChange(option) })
                            .testTag(if (option == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project")
                            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText
                        Icon(if (option == MemoryScope.GLOBAL) FeatherIcons.Globe else FeatherIcons.Folder,
                            contentDescription = null, tint = color, modifier = Modifier.size(Spacing.lg))
                        Text(stringResource(if (option == MemoryScope.GLOBAL) R.string.memory_a_global else R.string.memory_a_current_project),
                            style = MaterialTheme.typography.titleSmall, color = color,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            AppTextField(
                value = query, onValueChange = onQueryChange,
                placeholder = stringResource(R.string.memory_a_search),
                leadingIcon = { Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(Spacing.lg)) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).testTag("memory-search")
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("memory-range-summary")
                    .padding(horizontal = Spacing.xs, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (scope == MemoryScope.PROJECT && !state.projectName.isNullOrBlank()) {
                        stringResource(R.string.memory_management_project_named, state.projectName)
                    } else stringResource(if (scope == MemoryScope.PROJECT) R.string.memory_a_current_project else R.string.memory_a_global),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (state.loading) CircularProgressIndicator(Modifier.size(Spacing.lg), strokeWidth = 2.dp)
                else Text(stringResource(R.string.memory_a_count, entries.size),
                    modifier = Modifier.testTag("memory-count"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText)
            }
            if (!state.hasWorkspace) {
                Text(stringResource(R.string.memory_management_no_workspace),
                    modifier = Modifier.padding(bottom = Spacing.md), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.loading && entries.isEmpty()) item(key = "empty") {
            SettingsGroup {
                Text(stringResource(if (search.isNotEmpty()) R.string.memory_a_no_results else R.string.memory_management_empty),
                    modifier = Modifier.padding(Spacing.lg), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            LazyGroupItem(index, entries.lastIndex, dividerStartInset = Spacing.lg + Spacing.xl + Spacing.md) {
                SwipeToDeleteRow(
                    modifier = Modifier.testTag("memory-row-${entry.id}"),
                    onDelete = { onDelete(entry) }, onClick = { onOpenDetail(entry) },
                    deleteEnabled = !state.deleting && !state.saving && !state.targetChanged
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Spacing.xxl * 3 + Spacing.lg).padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Box(Modifier.width(Spacing.xl).align(Alignment.Top), contentAlignment = Alignment.TopCenter) {
                            Icon(FeatherIcons.FileText, contentDescription = null,
                                modifier = Modifier.size(20.dp).testTag("memory-icon-${entry.id}"),
                                tint = MaterialTheme.semanticColors.subtleText)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(entry.name, modifier = Modifier.testTag("memory-name-${entry.id}"),
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry.description.isNotBlank()) {
                                Text(entry.description, modifier = Modifier.testTag("memory-description-${entry.id}"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.preview.isNotBlank()) {
                                Text(entry.preview, modifier = Modifier.testTag("memory-preview-${entry.id}"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.subtleText,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Icon(FeatherIcons.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(Spacing.lg), tint = MaterialTheme.semanticColors.subtleText)
                    }
                }
            }
        }
        onOpenProjectProgress?.let { open ->
            item(key = "project-progress") {
                SettingsGroup(Modifier.padding(top = Spacing.lg).testTag("memory-project-progress")) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(Spacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(Spacing.xxl).clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.semanticColors.mutedSurface), contentAlignment = Alignment.Center) {
                            Icon(FeatherIcons.List, contentDescription = null, modifier = Modifier.size(Spacing.lg),
                                tint = MaterialTheme.semanticColors.subtleText)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(stringResource(R.string.memory_a_progress_title), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.memory_a_progress_hint), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.semanticColors.subtleText)
                        }
                        Icon(FeatherIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(Spacing.lg),
                            tint = MaterialTheme.semanticColors.subtleText)
                    }
                }
            }
        }
        if (!state.loading && entries.isNotEmpty()) item(key = "range-end") {
            Text(stringResource(R.string.memory_a_range_end),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl).testTag("memory-range-end"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
