package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.plugin.data.index.PluginIndexDefaults
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginIcon
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import com.aicode.feature.plugin.domain.PluginNativeCapabilityChallenge
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.capabilityPurpose
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Key
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Power
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Shuffle
import compose.icons.feathericons.Smartphone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginManagementTabs(
    native: NativePluginViewModel,
    store: PluginStoreViewModel,
    languageTag: String,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenPage: (String, PluginPage) -> Unit = { _, _ -> }
) {
    val snapshot by native.snapshot.collectAsStateWithLifecycle()
    val nativeState by native.state.collectAsStateWithLifecycle()
    val state by store.state.collectAsStateWithLifecycle()
    val grants by native.capabilityGrants.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var mineRevision by rememberSaveable { mutableStateOf(0) }
    var showWarnings by rememberSaveable { mutableStateOf(false) }
    val tabStates = rememberSaveableStateHolder()
    val nativeBusy = nativeState.busy || nativeState.preview != null || nativeState.trust != null ||
        nativeState.capability != null || nativeState.dependency != null || nativeState.action != null
    val busy = nativeBusy || state.downloading != null || state.preview != null
    val titles = listOf(R.string.plugin_center_mine, R.string.plugin_center_discover)

    LaunchedEffect(nativeState.installedId) {
        if (nativeState.installedId != null) {
            tabStates.removeState("mine:$mineRevision")
            mineRevision++
            tab = 0
            native.consumeInstalled()
        }
    }
    LaunchedEffect(state.installedId) {
        if (state.installedId != null) {
            tabStates.removeState("mine:$mineRevision")
            mineRevision++
            tab = 0
            store.consumeInstalled()
        }
    }
    val download: (PluginStoreItem) -> Unit = { item ->
        val current = native.state.value
        if (!current.busy && current.preview == null && current.trust == null &&
            current.capability == null && current.dependency == null && current.action == null) {
            store.download(item)
        }
    }
    Column(Modifier.fillMaxSize().testTag("plugin-center")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(top = Spacing.sm)
                .heightIn(min = 48.dp).testTag("plugin-center-segments")
                .clip(RoundedCornerShape(Radius.mdLarge)).background(MaterialTheme.semanticColors.capsuleSurface)
                .selectableGroup().padding(Spacing.xs)
        ) {
            titles.forEachIndexed { index, title ->
                val selected = tab == index
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.sm))
                        .background(if (selected) MaterialTheme.semanticColors.cardSurface else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { tab = index })
                        .testTag(if (index == 0) "plugin-center-mine" else "plugin-center-discover")
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(title), style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        state.notice?.let {
            Text(stringResource(it), modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tabStates.SaveableStateProvider(if (tab == 0) "mine:$mineRevision" else "discover") {
            when (tab) {
                0 -> Column(Modifier.weight(1f)) {
                    PluginListContent(
                        snapshot, languageTag, busy,
                        onImport = { native.showManagement(PluginManagementPanel.IMPORT) },
                        onRefresh = native::refresh,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                        onEnable = native::prepareTrust,
                        onDisable = native::disable,
                        onOpenPage = onOpenPage
                    )
                    if (snapshot.plugins.isEmpty()) {
                        TextButton(onClick = { tab = 1 }, modifier = Modifier.align(Alignment.CenterHorizontally)
                            .testTag("plugin-discover-action")) {
                            Text(stringResource(R.string.plugin_center_discover_action))
                        }
                    }
                }
                1 -> PluginStoreContent(
                    state, languageTag, store::refresh, download, store::cancelDownload,
                    onManage = { id -> tab = 0; onSelect(id) }, nativeBusy = nativeBusy
                )
            }
        }
    }
    nativeState.managementPanel?.let { panel ->
        key(panel) {
            AdaptiveModalBottomSheet(
                onDismissRequest = native::dismissManagement,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                        .imePadding().testTag("plugin-management-${panel.name.lowercase(java.util.Locale.ROOT)}")
                ) {
                    Text(
                        stringResource(when (panel) {
                            PluginManagementPanel.IMPORT -> R.string.plugin_center_add
                            PluginManagementPanel.MANAGE -> R.string.plugin_center_manage
                            PluginManagementPanel.SOURCES -> R.string.plugin_center_sources
                            PluginManagementPanel.PERMISSIONS -> R.string.plugin_center_permissions
                            PluginManagementPanel.DEVELOPMENT -> R.string.plugin_development_import
                        }),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    )
                    when (panel) {
                        PluginManagementPanel.IMPORT -> Column(Modifier.padding(horizontal = Spacing.lg)
                            .padding(bottom = Spacing.xl)) {
                            SettingsGroup {
                                SettingsRow(title = stringResource(R.string.plugin_import_zip), enabled = !busy,
                                    onClick = { native.dismissManagement(); onImport() },
                                    modifier = Modifier.testTag("plugin-import-zip"))
                                SettingsDivider()
                                SettingsRow(title = stringResource(R.string.plugin_development_import), enabled = !busy,
                                    onClick = { native.showManagement(PluginManagementPanel.DEVELOPMENT) })
                            }
                        }
                        PluginManagementPanel.MANAGE -> Column(Modifier.verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)) {
                            SettingsGroup {
                                SettingsRow(title = stringResource(R.string.plugin_center_sources),
                                    onClick = { native.showManagement(PluginManagementPanel.SOURCES) }, enabled = !busy)
                                SettingsDivider()
                                SettingsRow(title = stringResource(R.string.plugin_center_permissions),
                                    onClick = { native.showManagement(PluginManagementPanel.PERMISSIONS) }, enabled = !busy)
                                SettingsDivider()
                                SettingsRow(title = stringResource(R.string.plugin_refresh), enabled = !busy && (tab == 0 || !state.loading),
                                    onClick = { native.dismissManagement(); if (tab == 0) native.refresh() else store.refresh() },
                                    modifier = Modifier.testTag("plugin-management-refresh"))
                                SettingsDivider()
                                SettingsRow(title = stringResource(R.string.plugin_center_details),
                                    onClick = { native.dismissManagement(); showWarnings = true })
                            }
                        }
                        PluginManagementPanel.SOURCES -> PluginSourceContent(state, store::saveSources, busy)
                        PluginManagementPanel.PERMISSIONS -> LazyColumn(
                            Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            item {
                                PluginProblems(nativeState.problems)
                                Text(stringResource(R.string.plugin_capability_intro),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (snapshot.plugins.isEmpty()) item { Text(stringResource(R.string.plugin_center_no_installed)) }
                            items(snapshot.plugins, key = { it.record.id }) { plugin ->
                                SettingsGroupHeader(plugin.manifest?.name?.resolve(languageTag) ?: plugin.record.id)
                                if (PluginCapability.entries.any { plugin.manifest?.capabilityPurpose(it) != null }) {
                                    PluginCapabilityRows(plugin, grants.keys, busy, native::isCapabilityGranted,
                                        prepare = { id, capability -> native.dismissManagement(); native.prepareCapability(id, capability) },
                                        revoke = native::revokeCapability)
                                }
                                SettingsGroup {
                                    SettingsRow(title = stringResource(R.string.plugin_operation_grants_clear),
                                        onClick = { native.clearOperationGrants(plugin.record.id) }, enabled = !busy,
                                        modifier = Modifier.testTag("plugin-operation-grants-clear-${plugin.record.id}"))
                                }
                            }
                        }
                        PluginManagementPanel.DEVELOPMENT -> {
                            var path by rememberSaveable { mutableStateOf("") }
                            val trimmed = path.trim()
                            val validPath = trimmed.startsWith('/') && trimmed.length <= 200 && trimmed.none { it.isISOControl() }
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
                                .padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Text(stringResource(R.string.plugin_development_hint), style = MaterialTheme.typography.bodySmall)
                                AppTextField(
                                    path, { path = it }, label = stringResource(R.string.plugin_development_path),
                                    enabled = !busy, colors = dialogTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth().testTag("plugin-development-path")
                                )
                                Button(onClick = { native.dismissManagement(); native.previewDirectory(trimmed) },
                                    enabled = validPath && !busy, modifier = Modifier.fillMaxWidth().testTag("plugin-development-preview")) {
                                    Text(stringResource(R.string.plugin_store_preview))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showWarnings) PluginSourceWarningSheet { showWarnings = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginStoreContent(
    state: PluginStoreUiState,
    languageTag: String,
    onRefresh: () -> Unit,
    onDownload: (PluginStoreItem) -> Unit,
    onCancel: () -> Unit,
    onManage: (String) -> Unit = {},
    nativeBusy: Boolean = false
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var introductionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showWarnings by rememberSaveable { mutableStateOf(false) }
    val filteredItems = remember(state.items, query, tag, languageTag) {
        state.items.filter { item ->
            (tag == null || tag in item.plugin.tags) && (query.isBlank() || listOf(
                item.plugin.id, item.plugin.author, item.plugin.name.resolve(languageTag),
                item.plugin.description.resolve(languageTag)
            ).any { it.contains(query.trim(), ignoreCase = true) })
        }
    }
    val categories = listOf(null to R.string.plugin_center_filter_all, "tool" to R.string.plugin_store_tools,
        "ui" to R.string.plugin_store_ui, "integration" to R.string.plugin_store_integrations)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Spacing.lg).testTag("plugin-store-list"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item(key = "search") {
            AppTextField(
                query, { query = it }, placeholder = stringResource(R.string.plugin_store_search),
                leadingIcon = { Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).testTag("plugin-store-search")
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(categories, key = { it.first ?: "all" }) { (value, label) ->
                    FilterChip(selected = tag == value, onClick = { tag = value },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("plugin-store-filter-${value ?: "all"}"))
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                    .clickable { showWarnings = true }.testTag("plugin-source-note")
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(FeatherIcons.Shield, contentDescription = null, tint = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.plugin_center_source_note), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.loading) CircularProgressIndicator()
            PluginProblems(listOfNotNull(state.problem))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs).padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.plugin_visual_community_plugins),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.plugin_visual_plugin_count, filteredItems.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
            }
        }
        items(filteredItems, key = { it.key }) { item ->
            SettingsGroup(modifier = Modifier.testTag("plugin-store-item-${item.key}")
                .clickable { introductionKey = item.key }) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Row(
                        Modifier.fillMaxWidth().testTag("plugin-store-header-${item.key}"),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PluginVisualIcon(
                            PluginIcon(name = when {
                                "tool" in item.plugin.tags -> "tool"
                                "ui" in item.plugin.tags -> "monitor"
                                "integration" in item.plugin.tags -> "layers"
                                else -> "box"
                            }),
                            modifier = Modifier.size(48.dp).testTag("plugin-store-icon-${item.key}")
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(item.plugin.name.resolve(languageTag),
                                modifier = Modifier.testTag("plugin-store-name-${item.key}"),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.plugin.version ?: stringResource(R.string.plugin_store_latest),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.downloading != item.key) {
                            PluginStoreAction(item, state, nativeBusy, onDownload, onManage)
                        }
                    }
                    Text(item.plugin.description.resolve(languageTag), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        categories.firstOrNull { (value, _) -> value != null && value in item.plugin.tags }?.let { (_, label) ->
                            Text(stringResource(label), modifier = Modifier.clip(RoundedCornerShape(Radius.xs))
                                .background(MaterialTheme.semanticColors.mutedSurface)
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
                        }
                        if (item.installedVersion != null) Text(stringResource(R.string.plugin_center_installed),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
                        Text(
                            listOf(item.plugin.author, if (item.source.builtIn) stringResource(R.string.plugin_source_community) else item.source.name)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.downloading == item.key) {
                        val installing = state.previewKey == item.key && state.preview == null
                        FlowRow(
                            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp)
                                    .testTag("plugin-store-loading-${item.key}"), strokeWidth = 2.dp)
                                Text(stringResource(if (installing) R.string.plugin_center_installing else R.string.plugin_center_downloading),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (!installing) TextButton(onClick = onCancel, modifier = Modifier.testTag("plugin-store-cancel-${item.key}")) {
                                Text(stringResource(R.string.plugin_store_cancel_download))
                            }
                        }
                    }
                    if (item.stale) Text(stringResource(R.string.plugin_store_stale),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.warning)
                }
                state.itemProblems[item.key]?.let { problem ->
                    var showDiagnostics by rememberSaveable(item.key, problem) { mutableStateOf(false) }
                    val diagnosticsLabel = stringResource(R.string.plugin_visual_diagnostics)
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.semanticColors.warningContainer.copy(alpha = 0.24f))
                            .clickable(onClickLabel = diagnosticsLabel) { showDiagnostics = !showDiagnostics }
                            .testTag("plugin-store-problem-${item.key}")
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(FeatherIcons.AlertCircle, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.semanticColors.onWarningContainer)
                        Text(stringResource(when (problem.code) {
                            "RELEASE_NOT_FOUND" -> R.string.plugin_visual_release_not_found
                            "DOWNLOAD_FAILED" -> R.string.plugin_visual_download_failed
                            else -> problem.messageRes
                        }), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.semanticColors.onWarningContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ExpandableChevronIcon(expanded = showDiagnostics, size = 14.dp,
                            tint = MaterialTheme.semanticColors.onWarningContainer)
                        TextButton(onClick = { onDownload(item) },
                            enabled = item.compatible && !nativeBusy && state.downloading == null && state.preview == null,
                            contentPadding = PaddingValues(horizontal = Spacing.xs),
                            modifier = Modifier.testTag("plugin-store-retry-${item.key}")) {
                            Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.plugin_center_retry), modifier = Modifier.padding(start = Spacing.xs),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (showDiagnostics) {
                        PluginProblems(listOf(problem), Modifier.padding(horizontal = Spacing.lg)
                            .testTag("plugin-store-diagnostics-${item.key}"))
                    }
                }
            }
        }
        if (!state.loading && filteredItems.isEmpty()) item(key = "empty") {
            Text(stringResource(R.string.plugin_store_empty), modifier = Modifier.padding(vertical = Spacing.lg))
        }
        item(key = "refresh") {
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.md), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onRefresh, enabled = !state.loading && !nativeBusy && state.downloading == null,
                    modifier = Modifier.testTag("plugin-store-refresh")) {
                    Text(stringResource(R.string.plugin_refresh), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText)
                }
            }
        }
    }
    state.items.firstOrNull { it.key == introductionKey }?.let { item ->
        PluginStoreIntroductionSheet(
            item, state, languageTag, nativeBusy,
            onDownload = { introductionKey = null; onDownload(it) },
            onManage = { introductionKey = null; onManage(it) },
            onDismiss = { introductionKey = null }
        )
    }
    if (showWarnings) PluginSourceWarningSheet { showWarnings = false }
}

@Composable
private fun PluginStoreAction(
    item: PluginStoreItem,
    state: PluginStoreUiState,
    nativeBusy: Boolean,
    onDownload: (PluginStoreItem) -> Unit,
    onManage: (String) -> Unit,
    previewAction: Boolean = false
) {
    val manage = item.installedVersion != null && !item.updateAvailable && !(previewAction && item.plugin.version == null)
    val enabled = !nativeBusy && state.downloading == null && state.preview == null && (manage || item.compatible)
    val onClick = { if (manage) onManage(item.plugin.id) else onDownload(item) }
    val modifier = Modifier.heightIn(min = 32.dp)
        .testTag("plugin-store-${if (previewAction) "preview" else if (manage) "manage" else "get"}-${item.key}")
    val label = stringResource(when {
        manage -> R.string.plugin_center_manage
        !item.compatible -> R.string.plugin_store_incompatible
        previewAction -> R.string.plugin_center_get_preview
        item.updateAvailable -> R.string.plugin_store_update
        else -> R.string.plugin_center_get
    })
    if (manage) {
        FilledTonalButton(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = RoundedCornerShape(Radius.sm),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = RoundedCornerShape(Radius.sm),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginStoreIntroductionSheet(
    item: PluginStoreItem,
    state: PluginStoreUiState,
    languageTag: String,
    nativeBusy: Boolean,
    onDownload: (PluginStoreItem) -> Unit,
    onManage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)
            .testTag("plugin-store-introduction"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.plugin_center_introduction), style = MaterialTheme.typography.titleLarge)
            Text(item.plugin.name.resolve(languageTag), style = MaterialTheme.typography.titleMedium)
            Text(item.plugin.description.resolve(languageTag), style = MaterialTheme.typography.bodyMedium)
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.plugin_id), subtitle = item.plugin.id)
                SettingsRow(title = stringResource(R.string.plugin_version),
                    subtitle = item.plugin.version ?: stringResource(R.string.plugin_store_latest))
                SettingsRow(title = stringResource(R.string.plugin_publisher), subtitle = item.plugin.author)
                item.plugin.license?.let { SettingsRow(title = stringResource(R.string.plugin_license), subtitle = it) }
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.plugin_center_sources),
                    subtitle = if (item.source.builtIn) stringResource(R.string.plugin_source_community) else item.source.name)
                SettingsRow(title = stringResource(R.string.plugin_source_index_url), subtitle = item.source.indexUrl)
                SettingsRow(title = stringResource(R.string.plugin_repository), subtitle = item.plugin.repository)
            }
            Text(stringResource(R.string.plugin_metadata_warning), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.plugin_center_store_hint), style = MaterialTheme.typography.bodySmall)
            if (!item.compatible) Text(stringResource(R.string.plugin_store_incompatible), color = MaterialTheme.semanticColors.warning)
            if (item.stale) Text(stringResource(R.string.plugin_store_stale), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.warning)
            PluginStoreAction(item, state, nativeBusy, onDownload, onManage, previewAction = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSourceWarningSheet(onDismiss: () -> Unit) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.plugin_center_details), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.plugin_source_community_warning), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.plugin_process_warning), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.plugin_capability_intro), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    }
}

@Composable
private fun PluginSourceContent(state: PluginStoreUiState, onSave: (String, String) -> Unit, busy: Boolean) {
    val urls = state.sources.filterNot { it.builtIn }.joinToString("\n") { it.indexUrl }
    var custom by rememberSaveable(urls) { mutableStateOf(urls) }
    var mirror by rememberSaveable(state.mirror) { mutableStateOf(state.mirror) }
    val savedValues = custom.trim() == urls.trim() && mirror.trim() == state.mirror.trim()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
        .padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.plugin_source_community), subtitle = PluginIndexDefaults.COMMUNITY_URL)
        }
        Text(stringResource(R.string.plugin_source_community_warning), style = MaterialTheme.typography.bodySmall)
        AppTextField(
            custom, { custom = it }, label = stringResource(R.string.plugin_source_custom), singleLine = false,
            minLines = 3, maxLines = 6, enabled = !busy, isError = state.sourceProblem != null,
            colors = dialogTextFieldColors(), modifier = Modifier.fillMaxWidth().testTag("plugin-source-urls")
        )
        Text(stringResource(R.string.plugin_source_hint), style = MaterialTheme.typography.bodySmall)
        AppTextField(
            mirror, { mirror = it }, label = stringResource(R.string.plugin_source_mirror), enabled = !busy,
            isError = state.sourceProblem != null, colors = dialogTextFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("plugin-source-mirror")
        )
        Text(stringResource(R.string.plugin_source_mirror_warning), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.warning)
        PluginProblems(listOfNotNull(state.sourceProblem))
        if (state.sourceSaved && savedValues) Text(stringResource(R.string.plugin_center_source_saved),
            color = MaterialTheme.semanticColors.success, modifier = Modifier.testTag("plugin-source-saved"))
        Button(onClick = { onSave(custom, mirror) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("plugin-source-save")) { Text(stringResource(R.string.plugin_source_save)) }
    }
}

internal fun PluginCapability.labelRes(): Int = when (this) {
    PluginCapability.MODEL_ACCESS -> R.string.plugin_capability_model
    PluginCapability.BACKUP -> R.string.plugin_capability_backup
    PluginCapability.OVERLAY -> R.string.plugin_capability_overlay
    PluginCapability.SHIZUKU -> R.string.plugin_capability_shizuku
    PluginCapability.AUTOSTART -> R.string.plugin_capability_autostart
    PluginCapability.HTTP_TRANSFORM -> R.string.plugin_capability_http_transform
}

private fun PluginCapability.icon(): ImageVector = when (this) {
    PluginCapability.MODEL_ACCESS -> FeatherIcons.Cpu
    PluginCapability.BACKUP -> FeatherIcons.Archive
    PluginCapability.OVERLAY -> FeatherIcons.Layers
    PluginCapability.SHIZUKU -> FeatherIcons.Key
    PluginCapability.AUTOSTART -> FeatherIcons.Power
    PluginCapability.HTTP_TRANSFORM -> FeatherIcons.Shuffle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginCapabilityRows(plugin: InstalledPlugin, grantKeys: Set<String>, busy: Boolean, isGranted: (String, PluginCapability) -> Boolean, prepare: (String, PluginCapability) -> Unit, revoke: (String, PluginCapability) -> Unit) {
    val declared = PluginCapability.entries.filter { plugin.manifest?.capabilityPurpose(it) != null }
    val context = androidx.compose.ui.platform.LocalContext.current
    val editable = !busy && plugin.isActive
    var permissionDetail by remember(plugin.record.id, plugin.record.installRevision) { mutableStateOf<PluginCapability?>(null) }
    SettingsGroup {
        if (declared.isEmpty()) SettingsRow(title = stringResource(R.string.plugin_capability_not_declared))
        declared.forEachIndexed { index, capability ->
            if (index > 0) SettingsDivider()
            val granted = "${plugin.record.id}:${capability.name}" in grantKeys && isGranted(plugin.record.id, capability)
            SettingsRow(
                icon = capability.icon(), title = stringResource(capability.labelRes()),
                subtitle = plugin.manifest?.capabilityPurpose(capability),
                onClick = {
                    if (granted) permissionDetail = capability else prepare(plugin.record.id, capability)
                }, enabled = editable,
                modifier = Modifier.testTag("plugin-capability-${capability.name}")
                    .semantics { if (!editable) disabled() },
                trailing = {
                    Text(stringResource(if (granted) R.string.plugin_visual_granted else R.string.plugin_visual_not_granted),
                        modifier = Modifier.padding(start = Spacing.sm).testTag("plugin-capability-status-${capability.name}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText)
                }
            )
        }
    }
    if (PluginCapability.OVERLAY in declared) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smartphone, title = stringResource(R.string.plugin_overlay_system_settings),
                subtitle = stringResource(R.string.plugin_overlay_system_hint),
                onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.testTag("plugin-capability-system-overlay"),
                trailing = {
                    Text(stringResource(R.string.plugin_visual_go_settings), modifier = Modifier.padding(start = Spacing.sm),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            )
        }
    }
    if (!plugin.isActive && declared.isNotEmpty()) Text(stringResource(R.string.plugin_capability_enable_first), style = MaterialTheme.typography.bodySmall)
    permissionDetail?.takeIf { it in declared }?.let { capability ->
        val granted = "${plugin.record.id}:${capability.name}" in grantKeys && isGranted(plugin.record.id, capability)
        AdaptiveModalBottomSheet(
            onDismissRequest = { permissionDetail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)
                    .testTag("plugin-capability-detail-${capability.name}"),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(stringResource(R.string.plugin_visual_permission_detail), style = MaterialTheme.typography.titleLarge)
                SettingsGroup {
                    SettingsRow(icon = capability.icon(), title = stringResource(capability.labelRes()),
                        subtitle = plugin.manifest?.capabilityPurpose(capability))
                }
                Text(stringResource(R.string.plugin_capability_warning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { revoke(plugin.record.id, capability); permissionDetail = null },
                    enabled = editable && granted,
                    modifier = Modifier.fillMaxWidth().testTag("plugin-capability-revoke-${capability.name}")
                ) {
                    Text(stringResource(R.string.plugin_visual_revoke))
                }
                TextButton(onClick = { permissionDetail = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
internal fun PluginOriginMetadata(source: PluginInstallOrigin) {
    val label = when (source.kind) {
        PluginInstallOriginKind.LOCAL -> stringResource(R.string.plugin_source_local)
        PluginInstallOriginKind.DEVELOPMENT -> stringResource(R.string.plugin_development_import)
        PluginInstallOriginKind.INDEX -> if (source.sourceId == PluginIndexDefaults.COMMUNITY_ID) stringResource(R.string.plugin_source_community) else source.sourceName ?: source.sourceId.orEmpty()
    }
    SettingsGroup {
        SettingsRow(title = stringResource(R.string.plugin_tab_sources), subtitle = label)
        source.indexUrl?.let { SettingsRow(title = stringResource(R.string.plugin_source_index_url), subtitle = it) }
        source.repository?.let { SettingsRow(title = stringResource(R.string.plugin_repository), subtitle = it) }
    }
}

@Composable
internal fun PluginCapabilityDialog(challenge: PluginNativeCapabilityChallenge, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var acknowledged by remember(challenge.nativeToken) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.plugin_capability_title, stringResource(challenge.capability.labelRes()))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(challenge.pluginId, style = MaterialTheme.typography.titleMedium)
            Text(challenge.purpose, modifier = Modifier.padding(vertical = Spacing.md))
            PluginOriginMetadata(challenge.source)
            Text(stringResource(R.string.plugin_capability_warning), modifier = Modifier.padding(vertical = Spacing.md))
            if (challenge.capability == PluginCapability.MODEL_ACCESS) Text(stringResource(R.string.plugin_capability_model_cost), color = MaterialTheme.semanticColors.warning)
            if (challenge.capability == PluginCapability.BACKUP) Text(stringResource(R.string.plugin_capability_backup_sensitive), color = MaterialTheme.semanticColors.warning)
            if (challenge.capability == PluginCapability.SHIZUKU) Text(stringResource(R.string.plugin_capability_shizuku_uid), color = MaterialTheme.semanticColors.warning)
            SettingsGroup { SettingsRow(title = stringResource(R.string.plugin_capability_acknowledge), trailing = { AppSwitch(acknowledged, { acknowledged = it }, enabled = !busy, modifier = Modifier.testTag("plugin-capability-acknowledge")) }) }
        }
    }, confirmButton = { Button(onClick = { onConfirm(challenge.nativeToken) }, enabled = acknowledged && !busy) { Text(stringResource(R.string.plugin_capability_confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_cancel)) } })
}
