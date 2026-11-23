package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryProject
import com.aicode.feature.settings.presentation.ProjectProgressEntryAction
import com.aicode.feature.settings.presentation.ProjectProgressError
import com.aicode.feature.settings.presentation.ProjectProgressFilter
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import com.aicode.feature.settings.presentation.ProjectProgressViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.RefreshCw
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectProgressSettingsRoute(
    viewModel: ProjectProgressViewModel,
    sessionId: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    tabs: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val goBack: () -> Unit = {
        if (!state.busy) {
            when {
                state.confirmation != null -> viewModel.dismissConfirmation()
                state.form != null -> viewModel.dismissForm()
                state.selectedEntryId != null -> viewModel.closeEntry()
                state.selectedProjectId != null -> viewModel.closeProject()
                else -> onBack()
            }
        }
    }
    BackHandler(enabled = showBack || state.selectedEntryId != null || state.selectedProjectId != null, onBack = goBack)
    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.project_progress_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground()),
                navigationIcon = {
                    if (showBack || state.selectedEntryId != null || state.selectedProjectId != null) {
                        IconButton(onClick = goBack, enabled = !state.busy) {
                            Icon(FeatherIcons.ArrowLeft, stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy && !state.targetChanged && state.workspaceRoot != null) {
                        Icon(FeatherIcons.RefreshCw, stringResource(R.string.project_progress_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.selectedEntryId == null) tabs()
            if (state.targetChanged) {
                Text(stringResource(R.string.project_progress_target_changed), modifier = Modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.open(sessionId) }) { Text(stringResource(R.string.project_progress_reload)) }
            } else {
                if (state.error != null && state.form == null) {
                    Text(stringResource(projectProgressErrorRes(state.error!!)), modifier = Modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.error)
                    if (state.error == ProjectProgressError.ENVIRONMENT_UNAVAILABLE) {
                        TextButton(onClick = { viewModel.open(sessionId) }) { Text(stringResource(R.string.project_progress_reload)) }
                    }
                }
                when {
                    state.selectedEntryId != null && state.detailLoading -> ProjectProgressLoading()
                    state.selectedEntryId != null -> state.detail?.let { entry ->
                        ProjectProgressDetail(entry, !state.busy, viewModel::requestEntryAction)
                    }
                    else -> ProjectProgressSection(
                        state = state,
                        onSelectProject = viewModel::selectProject,
                        onCloseProject = viewModel::closeProject,
                        onOpenEntry = viewModel::openEntry,
                        onRegister = viewModel::startRegister,
                        onBind = viewModel::bindSelected,
                        onUnbind = viewModel::unbind,
                        onAutoProgress = viewModel::setAutoProgressEnabled,
                        onHandoff = viewModel::startHandoff,
                        onArchive = viewModel::showArchive,
                        onLoadMore = viewModel::loadMore,
                        onFilter = viewModel::setFilter
                    )
                }
            }
        }
    }
    if (state.form != null && !state.targetChanged) {
        ProjectProgressFormSheet(state, viewModel::register, viewModel::saveHandoff, viewModel::dismissForm)
    }
    state.confirmation?.takeIf { !state.targetChanged }?.let { pending ->
        val revoke = pending.action == ProjectProgressEntryAction.REVOKE
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(if (revoke) R.string.project_progress_revoke_title else R.string.project_progress_archive_title)) },
            text = {
                Text(stringResource(if (revoke) R.string.project_progress_revoke_confirmation else R.string.project_progress_archive_confirmation, projectProgressEntryTitle(pending.entry)))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEntryAction, enabled = !state.busy) {
                    Text(stringResource(if (state.busy) R.string.project_progress_saving else if (revoke) R.string.project_progress_revoke else R.string.project_progress_archive))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
internal fun ProjectProgressSection(
    state: ProjectProgressUiState,
    onSelectProject: (ProjectMemoryProject) -> Unit,
    onCloseProject: () -> Unit,
    onOpenEntry: (ProjectMemoryEntry) -> Unit,
    onRegister: () -> Unit,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
    onAutoProgress: (Boolean) -> Unit,
    onHandoff: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    onFilter: (ProjectProgressFilter) -> Unit
) {
    if (state.loading && state.projects.isEmpty()) {
        ProjectProgressLoading()
        return
    }
    if (state.workspaceRoot == null) {
        Text(stringResource(R.string.project_progress_no_workspace), modifier = Modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val browseEnabled = !state.busy && state.projectListReady
    val enabled = browseEnabled && !state.loading && state.associations != null
    val selected = state.selectedProject
    val currentWorkspaceProject = state.currentWorkspaceProject
    val otherProjects = if (selected == null) state.projects.filterNot { it.id == currentWorkspaceProject?.id } else emptyList()
    var managementExpanded by rememberSaveable(state.workspaceRoot, state.sessionId, selected?.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (selected == null) {
            item(key = "workspace") {
                SettingsGroup(Modifier.testTag("project-progress-overview")) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(stringResource(R.string.project_progress_current_workspace),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentWorkspaceProject?.name ?: state.workspaceName.orEmpty(),
                            style = MaterialTheme.typography.titleMedium)
                        Text(state.workspaceRoot, modifier = Modifier.testTag("project-progress-workspace-path"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.project_progress_environment_value,
                                projectProgressEnvironment(state.environmentMode),
                                stringResource(when {
                                    !state.projectListReady -> R.string.project_progress_registration_unknown
                                    currentWorkspaceProject != null -> R.string.project_progress_registered
                                    else -> R.string.project_progress_not_registered
                                })
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentWorkspaceProject != null) {
                            Text(projectProgressCurrentBinding(state), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.projectListReady) {
                            Button(
                                onClick = {
                                    if (currentWorkspaceProject != null) {
                                        if (browseEnabled) onSelectProject(currentWorkspaceProject)
                                    } else if (enabled) {
                                        onRegister()
                                    }
                                },
                                enabled = if (currentWorkspaceProject != null) browseEnabled else enabled,
                                modifier = Modifier.fillMaxWidth().testTag(
                                    if (currentWorkspaceProject != null) "project-progress-open-current" else "project-progress-register"
                                )
                            ) {
                                Text(stringResource(
                                    if (currentWorkspaceProject != null) R.string.project_progress_view
                                    else R.string.project_progress_register_short))
                            }
                        }
                    }
                }
            }
            if (otherProjects.isNotEmpty()) {
                item(key = "other-projects-heading") {
                    ProjectProgressGroupHeader(stringResource(R.string.project_progress_other_projects))
                }
                itemsIndexed(otherProjects, key = { _, project -> "project-${project.id}" }) { index, project ->
                    LazyGroupItem(index, otherProjects.lastIndex, modifier = Modifier.testTag("project-progress-project-${project.id}")) {
                        SettingsRow(title = project.name, subtitle = project.identity.projectRoot,
                            enabled = browseEnabled, onClick = { onSelectProject(project) })
                    }
                }
            }
        } else {
            item(key = "project-overview") {
                SettingsGroup(Modifier.testTag("project-progress-overview")) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(selected.name, style = MaterialTheme.typography.titleMedium)
                        Text(selected.identity.projectRoot, modifier = Modifier.testTag("project-progress-workspace-path"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.project_progress_environment_value,
                                projectProgressEnvironment(state.environmentMode),
                                stringResource(if (selected.autoProgressEnabled)
                                    R.string.project_progress_auto_enabled else R.string.project_progress_auto_disabled)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(projectProgressCurrentBinding(state), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { if (!state.busy) onCloseProject() }, enabled = !state.busy,
                            modifier = Modifier.testTag("project-progress-all-projects")) {
                            Text(stringResource(R.string.project_progress_all_projects))
                        }
                    }
                }
            }
            item(key = "latest-handoff") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ProjectProgressGroupHeader(stringResource(R.string.project_progress_latest_handoff))
                    TextButton(onClick = { if (enabled) onHandoff() }, enabled = enabled) {
                        Text(stringResource(R.string.project_progress_write_handoff))
                    }
                }
                when {
                    state.handoffLoading -> ProjectProgressInlineLoading()
                    state.latestHandoff != null -> ProjectProgressHandoffCard(state.latestHandoff, browseEnabled) { onOpenEntry(state.latestHandoff) }
                    else -> ProjectProgressHint(stringResource(R.string.project_progress_no_handoff))
                }
            }
            if (state.capacity?.nearCapacity == true) {
                item(key = "capacity-warning") {
                    Text(stringResource(R.string.project_progress_capacity_warning), modifier = Modifier.padding(Spacing.sm),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.onWarningContainer)
                }
            }
            item(key = "history-heading") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ProjectProgressGroupHeader(stringResource(if (state.showingArchive) R.string.project_progress_archive_history else R.string.project_progress_recent))
                    TextButton(onClick = { if (browseEnabled) onArchive(!state.showingArchive) }, enabled = browseEnabled) {
                        Text(stringResource(if (state.showingArchive) R.string.project_progress_view_recent else R.string.project_progress_view_archive))
                    }
                }
                ProjectProgressFilterBar(state.filter, browseEnabled, onFilter)
            }
            if (state.entries.isEmpty() && !state.historyLoading) {
                item(key = "history-empty") { ProjectProgressHint(stringResource(R.string.project_progress_empty_history)) }
            }
            itemsIndexed(state.entries, key = { _, entry -> "entry-${entry.id}" }) { index, entry ->
                LazyGroupItem(index, state.entries.lastIndex) {
                    ProjectProgressEntryRow(entry, browseEnabled) { onOpenEntry(entry) }
                }
            }
            item(key = "history-more") {
                if (state.historyLoading) {
                    ProjectProgressInlineLoading()
                } else if (state.hasMore) {
                    TextButton(onClick = { if (browseEnabled) onLoadMore() }, enabled = browseEnabled) {
                        Text(stringResource(R.string.project_progress_load_more))
                    }
                }
            }
            item(key = "history-notice") {
                Text(stringResource(R.string.project_progress_read_only_history), modifier = Modifier.padding(Spacing.sm),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.subtleText)
            }
        }
        item(key = "management") {
            ProjectProgressManagement(state, managementExpanded, { managementExpanded = !managementExpanded },
                enabled, onBind, onUnbind, onAutoProgress, onHandoff)
        }
    }
}

@Composable
internal fun ProjectProgressManagement(
    state: ProjectProgressUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
    onAutoProgress: (Boolean) -> Unit,
    onHandoff: () -> Unit
) {
    val actionsEnabled = enabled && !state.busy
    val selected = state.selectedProject
    val binding = projectProgressCurrentBinding(state)
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth()
                    .testTag("project-settings-toggle")
                    .clickable(enabled = !state.busy, role = Role.Button) {
                        if (!state.busy) onToggle()
                    }
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.project_progress_management), style = MaterialTheme.typography.bodyLarge)
                    if (!expanded) {
                        Text(
                            state.capacity?.let { capacity ->
                                stringResource(
                                    R.string.project_settings_capacity_summary,
                                    capacity.activeEntries,
                                    capacity.maxActiveEntries,
                                    projectProgressFormatBytes(capacity.usedBytes, locale),
                                    projectProgressFormatBytes(capacity.maxBytes, locale)
                                )
                            } ?: stringResource(R.string.project_progress_capacity_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                }
                ExpandableChevronIcon(expanded = expanded, tint = MaterialTheme.semanticColors.subtleText)
            }
        }
        if (expanded) {
            ProjectProgressGroupHeader(stringResource(R.string.project_settings_binding_title))
            SettingsGroup(Modifier.testTag("project-settings-binding")) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.project_progress_binding),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ProjectProgressLabel(
                            stringResource(
                                if (state.sessionId == null) R.string.project_settings_binding_scope_workspace
                                else R.string.project_settings_binding_scope_session
                            ),
                            modifier = Modifier.testTag("project-settings-binding-scope")
                        )
                    }
                    Text(
                        binding,
                        modifier = Modifier.testTag("project-settings-binding-status"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(
                            if (state.sessionId == null) R.string.project_settings_binding_scope_hint_workspace
                            else R.string.project_settings_binding_scope_hint_session
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selected != null) {
                        if (!state.canBindSelected) {
                            Text(
                                stringResource(R.string.project_progress_outside_workspace),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.onWarningContainer
                            )
                        }
                        Button(
                            onClick = { if (actionsEnabled && state.canBindSelected) onBind() },
                            enabled = actionsEnabled && state.canBindSelected,
                            modifier = Modifier.fillMaxWidth().testTag("project-settings-bind")
                        ) {
                            Text(stringResource(R.string.project_progress_bind))
                        }
                    }
                }
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.project_progress_unbind),
                    subtitle = stringResource(R.string.project_progress_unbind_hint),
                    enabled = actionsEnabled,
                    onClick = { if (actionsEnabled) onUnbind() },
                    modifier = Modifier.testTag("project-settings-unbind")
                )
            }
            if (selected != null) {
                ProjectProgressGroupHeader(stringResource(R.string.project_settings_record_options))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.project_progress_auto_capture),
                        subtitle = stringResource(R.string.project_progress_auto_capture_hint),
                        trailing = {
                            AppSwitch(
                                checked = selected.autoProgressEnabled,
                                onCheckedChange = { if (actionsEnabled) onAutoProgress(it) },
                                enabled = actionsEnabled,
                                modifier = Modifier.testTag("project-settings-auto-progress")
                            )
                        }
                    )
                }
                Button(onClick = { if (actionsEnabled) onHandoff() }, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.project_progress_handoff))
                }
            }
            ProjectProgressEnvironmentCard(state)
            ProjectProgressCapacityCard(state.capacity, enabled = !state.busy)
        }
    }
}

@Composable
private fun ProjectProgressEnvironmentCard(state: ProjectProgressUiState) {
    var detailsExpanded by rememberSaveable(state.workspaceRoot, state.environmentMode) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val path = state.workspaceRoot.orEmpty()
    ProjectProgressGroupHeader(stringResource(R.string.project_progress_environment))
    SettingsGroup(Modifier.testTag("project-settings-environment")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ProjectProgressLabel(projectProgressEnvironment(state.environmentMode))
                    state.workspaceName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.testTag("project-settings-environment-details")
                ) {
                    Text(stringResource(R.string.project_settings_environment_details))
                    ExpandableChevronIcon(expanded = detailsExpanded, tint = MaterialTheme.semanticColors.subtleText)
                }
            }
            if (detailsExpanded) {
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.project_settings_path),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { if (!state.busy) clipboard.setText(AnnotatedString(path)) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("project-settings-copy-path")
                    ) {
                        Text(stringResource(R.string.project_settings_copy_path))
                    }
                }
                SelectionContainer {
                    Text(
                        path,
                        modifier = Modifier.fillMaxWidth().testTag("project-settings-path"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    path,
                    modifier = Modifier.fillMaxWidth().testTag("project-settings-path"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
            }
        }
    }
}

@Composable
internal fun ProjectProgressFilterBar(filter: ProjectProgressFilter, enabled: Boolean, onFilter: (ProjectProgressFilter) -> Unit) {
    Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.semanticColors.mutedSurface) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ProjectProgressFilter.entries.forEach { option ->
                Surface(
                    modifier = Modifier.weight(1f).selectable(selected = filter == option, enabled = enabled, role = Role.Tab, onClick = { if (enabled) onFilter(option) }),
                    shape = RoundedCornerShape(Radius.sm), color = if (filter == option) MaterialTheme.semanticColors.cardSurface else Color.Transparent
                ) {
                    Text(stringResource(when (option) {
                        ProjectProgressFilter.ALL -> R.string.project_progress_filter_all
                        ProjectProgressFilter.AUTO_PROGRESS -> R.string.project_progress_auto_kind
                        ProjectProgressFilter.HANDOFF -> R.string.project_progress_handoff_kind
                    }), modifier = Modifier.padding(Spacing.sm), style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center, color = if (filter == option) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ProjectProgressHandoffCard(entry: ProjectMemoryEntry, enabled: Boolean, onOpen: () -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ProjectProgressEntryHeading(entry)
            Text(projectProgressEntryTitle(entry), style = MaterialTheme.typography.bodyLarge)
            Text(projectProgressEntryStatus(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.subtleText)
            Text(entry.body, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.project_progress_open_handoff), enabled = enabled, onClick = onOpen)
    }
}

@Composable
private fun ProjectProgressEntryRow(entry: ProjectMemoryEntry, enabled: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = { if (enabled) onOpen() }).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        ProjectProgressEntryHeading(entry)
        Text(projectProgressEntryTitle(entry), style = MaterialTheme.typography.bodyLarge)
        Text(projectProgressEntryStatus(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.subtleText)
    }
}

@Composable
private fun ProjectProgressEntryHeading(entry: ProjectMemoryEntry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ProjectProgressLabel(projectProgressEntryKind(entry))
        Spacer(Modifier.weight(1f))
        Text(projectProgressEntryTime(entry), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
    }
}

@Composable
private fun ProjectProgressLabel(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(Radius.xs), color = MaterialTheme.semanticColors.infoContainer) {
        Text(text, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.onInfoContainer)
    }
}

@Composable
private fun projectProgressCurrentBinding(state: ProjectProgressUiState): String =
    state.associations?.let { projectProgressBindingText(it, state.sessionId != null) }
        ?: stringResource(R.string.project_progress_binding_unknown)

@Composable
private fun projectProgressEnvironment(mode: String?): String = stringResource(when (mode) {
    "local_proot" -> R.string.project_progress_local_environment
    "remote_ssh" -> R.string.project_progress_remote_environment
    else -> R.string.project_progress_environment_unknown
})

@Composable
private fun ProjectProgressInlineLoading() {
    Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ProjectProgressGroupHeader(text: String) {
    SettingsGroupHeader(text, topPadding = Spacing.sm, bottomPadding = Spacing.xs)
}

@Composable
private fun ProjectProgressHint(text: String) {
    SettingsGroup { Text(text, modifier = Modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ProjectProgressLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun projectProgressBindingText(associations: ProjectMemoryAssociations, hasSession: Boolean): String {
    val present = if (hasSession) associations.sessionBindingPresent == true else associations.workspaceBindingPresent
    val project = if (hasSession) associations.sessionProject else associations.workspaceProject
    return when {
        project != null -> stringResource(R.string.project_progress_bound_project, project.name)
        present -> stringResource(R.string.project_progress_explicit_none)
        else -> stringResource(R.string.project_progress_not_bound)
    }
}

@Composable
internal fun projectProgressEntryTitle(entry: ProjectMemoryEntry): String =
    if (entry.kind == "turn" && (entry.summary == "Project activity · ${entry.status} · unverified" ||
            (entry.status == "interrupted" && entry.summary == "Project activity · interrupted · needs_verification"))) {
        stringResource(R.string.project_progress_activity_title)
    } else entry.summary

@Composable
internal fun projectProgressEntryMetadata(entry: ProjectMemoryEntry): String {
    val flags = listOf(projectProgressEntryKind(entry), projectProgressEntryStatus(entry))
        .joinToString(stringResource(R.string.project_progress_metadata_separator))
    return stringResource(R.string.project_progress_entry_metadata, flags, projectProgressEntryTime(entry))
}

@Composable
private fun projectProgressEntryKind(entry: ProjectMemoryEntry): String = stringResource(when (entry.kind) {
    "turn" -> R.string.project_progress_auto_kind
    "handoff" -> R.string.project_progress_handoff_kind
    else -> R.string.project_progress_note_kind
})

@Composable
private fun projectProgressEntryStatus(entry: ProjectMemoryEntry): String {
    val status = stringResource(when (entry.status) {
        "completed" -> R.string.project_progress_status_completed
        "failed" -> R.string.project_progress_status_failed
        "interrupted" -> R.string.project_progress_status_interrupted
        "background_pending" -> R.string.project_progress_status_background_pending
        "recorded" -> R.string.project_progress_status_recorded
        else -> R.string.project_progress_status_unknown
    })
    return buildList {
        add(status)
        add(stringResource(R.string.project_progress_unverified))
        if (entry.revoked) add(stringResource(R.string.project_progress_revoked))
        if (entry.archived) add(stringResource(R.string.project_progress_archived))
        if (entry.userEdited) add(stringResource(R.string.project_progress_user_edited))
        if (entry.truncated) add(stringResource(R.string.project_progress_truncated))
    }.joinToString(stringResource(R.string.project_progress_metadata_separator))
}

private fun projectProgressEntryTime(entry: ProjectMemoryEntry): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.updatedAt))

internal fun projectProgressErrorRes(error: ProjectProgressError): Int = when (error) {
    ProjectProgressError.ENVIRONMENT_UNAVAILABLE -> R.string.project_progress_environment_unavailable
    ProjectProgressError.INVALID_INPUT -> R.string.project_progress_invalid_input
    ProjectProgressError.READ_FAILED -> R.string.project_progress_read_failed
    ProjectProgressError.SAVE_FAILED -> R.string.project_progress_save_failed
    ProjectProgressError.NOT_FOUND -> R.string.project_progress_not_found
    ProjectProgressError.REGISTERED_BINDING_FAILED -> R.string.project_progress_registered_binding_failed
}
