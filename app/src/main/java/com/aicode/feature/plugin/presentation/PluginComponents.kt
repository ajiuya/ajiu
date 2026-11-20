package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginIcon
import com.aicode.feature.plugin.domain.PluginJson
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginRiskSummary
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.PluginTrustPreview
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.domain.capabilityPurpose
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Info
import compose.icons.feathericons.List
import compose.icons.feathericons.Search
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Tool
import kotlinx.serialization.json.JsonObject

@Composable
internal fun PluginListContent(
    snapshot: PluginSnapshot,
    languageTag: String,
    busy: Boolean,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEnable: (String) -> Unit = {},
    onDisable: (String) -> Unit = {},
    onOpenPage: (String, PluginPage) -> Unit = { _, _ -> }
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val search = query.trim()
    val plugins = snapshot.plugins.filter { plugin ->
        val matchesStatus = when (filter) {
            1 -> plugin.isActive
            2 -> !plugin.isActive && plugin.status != PluginStatus.INVALID
            3 -> plugin.status == PluginStatus.INVALID || plugin.errors.isNotEmpty()
            else -> true
        }
        matchesStatus && (search.isEmpty() || listOfNotNull(
            plugin.record.id,
            plugin.manifest?.name?.resolve(languageTag),
            plugin.manifest?.description?.resolve(languageTag),
            plugin.manifest?.publisher
        ).any { it.contains(search, ignoreCase = true) })
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        state = listState,
        contentPadding = PaddingValues(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item(key = "toolbar") {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.plugin_center_search_installed),
                leadingIcon = { Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(Spacing.lg)) },
                modifier = Modifier.fillMaxWidth().testTag("plugin-installed-search")
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                listOf(
                    "all" to R.string.plugin_center_filter_all,
                    "enabled" to R.string.plugin_center_filter_enabled,
                    "disabled" to R.string.plugin_center_filter_disabled,
                    "invalid" to R.string.plugin_center_filter_invalid
                ).forEachIndexed { index, (key, label) ->
                    FilterChip(
                        selected = filter == index,
                        onClick = { filter = index },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("plugin-filter-$key")
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.plugin_visual_installed_count, snapshot.plugins.size),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) CircularProgressIndicator(Modifier.size(Spacing.lg))
                Text(
                    stringResource(R.string.plugin_visual_enabled_count, snapshot.plugins.count { it.isActive }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
            if (snapshot.errors.isNotEmpty()) PluginProblems(snapshot.errors.map { it.toProblem() })
        }
        if (plugins.isEmpty()) item(key = "empty") {
            SettingsGroup {
                SettingsRow(title = stringResource(
                    if (snapshot.plugins.isEmpty()) R.string.plugin_center_no_installed else R.string.plugin_center_no_results
                ))
                if (snapshot.plugins.isEmpty()) {
                    TextButton(
                        onClick = onImport,
                        enabled = !busy,
                        modifier = Modifier.padding(horizontal = Spacing.sm)
                    ) { Text(stringResource(R.string.plugin_center_install_from_zip)) }
                }
            }
        }
        items(plugins, key = { it.record.id }) { plugin ->
            val name = plugin.manifest?.name?.resolve(languageTag) ?: plugin.record.id
            val enableDescription = stringResource(R.string.plugin_center_enable_contentdescription, name)
            val configuration = plugin.content.configPage
            val statusColor = when {
                plugin.status == PluginStatus.INVALID -> MaterialTheme.colorScheme.error
                plugin.isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.semanticColors.subtleText
            }
            SettingsGroup(Modifier.border(0.5.dp, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.lg))) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("plugin-header-${plugin.record.id}")
                        .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PluginVisualHeader(
                        name = name,
                        version = plugin.record.version,
                        icon = plugin.content.icon,
                        modifier = Modifier.weight(1f).testTag("plugin-row-${plugin.record.id}")
                            .clickable { onSelect(plugin.record.id) },
                        nameModifier = Modifier.testTag("plugin-name-${plugin.record.id}"),
                        iconModifier = Modifier.testTag("plugin-icon-${plugin.record.id}")
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(Spacing.xs).background(statusColor, CircleShape))
                            Text(
                                stringResource(when {
                                    plugin.status == PluginStatus.INVALID -> R.string.plugin_invalid
                                    plugin.isActive -> R.string.plugin_enabled
                                    else -> R.string.plugin_disabled
                                }),
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    AppSwitch(
                        checked = plugin.isActive,
                        onCheckedChange = { enabled -> if (enabled) onEnable(plugin.record.id) else onDisable(plugin.record.id) },
                        enabled = !busy && plugin.status != PluginStatus.INVALID,
                        modifier = Modifier.testTag("plugin-switch-${plugin.record.id}")
                            .semantics { contentDescription = enableDescription }
                    )
                }
                val description = plugin.manifest?.description?.resolve(languageTag).orEmpty()
                if (description.isNotBlank() || !plugin.isActive && configuration != null) {
                    Text(
                        description.ifBlank { stringResource(R.string.plugin_center_enable_first) },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    )
                }
                if (plugin.errors.isNotEmpty()) PluginProblems(plugin.errors.map { it.toProblem() }, Modifier.padding(horizontal = Spacing.lg))
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("plugin-footer-${plugin.record.id}")
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (!busy && plugin.isActive && configuration != null) onOpenPage(plugin.record.id, configuration) },
                        enabled = !busy && plugin.isActive && configuration != null,
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.sm),
                        modifier = Modifier.testTag("plugin-configure-${plugin.record.id}")
                    ) {
                        Icon(FeatherIcons.Sliders, contentDescription = null, modifier = Modifier.size(Spacing.lg))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.plugin_center_configure))
                    }
                    TextButton(
                        onClick = { onSelect(plugin.record.id) },
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.sm),
                        modifier = Modifier.testTag("plugin-detail-${plugin.record.id}")
                    ) {
                        Text(stringResource(R.string.plugin_center_details), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.semanticColors.subtleText, modifier = Modifier.size(Spacing.lg))
                    }
                }
            }
        }
        if (plugins.isNotEmpty() && search.isEmpty() && filter == 0) item(key = "all-shown") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(Modifier.width(Spacing.xxl), color = MaterialTheme.semanticColors.subtleBorder)
                Text(stringResource(R.string.plugin_visual_all_shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
                HorizontalDivider(Modifier.width(Spacing.xxl), color = MaterialTheme.semanticColors.subtleBorder)
            }
        }
    }
}

@Composable
private fun PluginIdentity(name: String, version: String, description: String, icon: PluginIcon? = null) {
    SettingsGroup {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PluginVisualHeader(name, version, icon, iconSize = 56.dp, titleStyle = MaterialTheme.typography.titleLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PluginExpandableSection(
    title: String,
    stateKey: String,
    summary: String? = null,
    icon: ImageVector = FeatherIcons.List,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    SettingsGroup {
        PluginVisualExpandableRow(title, stateKey, icon, modifier, summary, content)
    }
}

@Composable
private fun pluginFeatureSummary(manifest: PluginManifest): String {
    val features = buildList {
        if (manifest.skills.isNotEmpty()) add(stringResource(R.string.plugin_skill_item, manifest.skills.size))
        if (manifest.agents.isNotEmpty()) add(stringResource(R.string.plugin_agent_item, manifest.agents.size))
        if (manifest.tools.isNotEmpty()) add(stringResource(R.string.plugin_tool_item, manifest.tools.size))
        if (manifest.workspaceTools.isNotEmpty()) add(stringResource(R.string.plugin_workspace_item, manifest.workspaceTools.size))
        val pageCount = manifest.uiEntries.count { it.page != null }
        val actionCount = manifest.uiEntries.count { it.handler != null }
        if (pageCount > 0) add(stringResource(R.string.plugin_page_item, pageCount))
        if (actionCount > 0) add(stringResource(R.string.plugin_action_item, actionCount))
        if (manifest.hooks.isNotEmpty()) add(stringResource(R.string.plugin_contribution_hook))
        if (manifest.mcp.isNotEmpty()) add(stringResource(R.string.plugin_contribution_mcp))
        if (manifest.providers.isNotEmpty()) add(stringResource(R.string.plugin_contribution_provider))
        if (manifest.sessionSources.isNotEmpty()) add(stringResource(R.string.plugin_contribution_sessions))
        if (manifest.configPage != null) add(stringResource(R.string.plugin_configuration))
        manifest.runtime?.let { add(it.type) }
        PluginCapability.entries.filter { manifest.capabilityPurpose(it) != null }.forEach { add(stringResource(it.labelRes())) }
    }
    return features.take(3).joinToString(" · ").ifEmpty { stringResource(R.string.plugin_contributions_empty) }
}

internal fun PluginStatus.labelRes(): Int = when (this) {
    PluginStatus.DISABLED -> R.string.plugin_disabled
    PluginStatus.ENABLED -> R.string.plugin_enabled
    PluginStatus.INVALID -> R.string.plugin_invalid
}

@Composable
internal fun PluginMetadata(manifest: PluginManifest, languageTag: String) {
    SettingsGroup {
        SettingsRow(title = manifest.name.resolve(languageTag), subtitle = manifest.description.resolve(languageTag))
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.plugin_id), subtitle = manifest.id)
        SettingsRow(title = stringResource(R.string.plugin_version), subtitle = manifest.version.toString())
        SettingsRow(title = stringResource(R.string.plugin_publisher), subtitle = manifest.publisher)
        SettingsRow(title = stringResource(R.string.plugin_repository), subtitle = manifest.repository)
        SettingsRow(title = stringResource(R.string.plugin_license), subtitle = manifest.license)
    }
    Text(
        stringResource(R.string.plugin_metadata_warning),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = Spacing.sm)
    )
}

@Composable
internal fun PluginContributions(manifest: PluginManifest, languageTag: String) {
    SettingsGroupHeader(stringResource(R.string.plugin_contributions))
    SettingsGroup {
        if (manifest.contributionCount == 0) SettingsRow(title = stringResource(R.string.plugin_contributions_empty))
        manifest.skills.forEach { SettingsRow(title = stringResource(R.string.plugin_skill_item, it.id), subtitle = it.file) }
        manifest.agents.forEach { SettingsRow(title = stringResource(R.string.plugin_agent_item, it.id), subtitle = it.file) }
        manifest.tools.forEach { SettingsRow(title = stringResource(R.string.plugin_tool_item, it.title.resolve(languageTag)), subtitle = it.description.resolve(languageTag)) }
        manifest.workspaceTools.forEach { SettingsRow(title = stringResource(R.string.plugin_workspace_item, it.title.resolve(languageTag)), subtitle = it.pageFile) }
        manifest.uiEntries.forEach { entry ->
            SettingsRow(
                title = stringResource(if (entry.page != null) R.string.plugin_page_item else R.string.plugin_action_item, entry.title.resolve(languageTag)),
                subtitle = stringResource(R.string.plugin_entry_slot, entry.slot)
            )
        }
        manifest.hooks.forEach { SettingsRow(title = stringResource(R.string.plugin_contribution_hook), subtitle = "${it.event} · ${it.mode}") }
        manifest.mcp.forEach { SettingsRow(title = stringResource(R.string.plugin_contribution_mcp), subtitle = it.id) }
        manifest.providers.forEach { SettingsRow(title = stringResource(R.string.plugin_contribution_provider), subtitle = it.name.resolve(languageTag)) }
        manifest.sessionSources.forEach { SettingsRow(title = stringResource(R.string.plugin_contribution_sessions), subtitle = it.title.resolve(languageTag)) }
        manifest.configPage?.let { SettingsRow(title = stringResource(R.string.plugin_configuration), subtitle = it) }
        manifest.runtime?.let { runtime ->
            SettingsRow(title = stringResource(R.string.plugin_runtime), subtitle = stringResource(R.string.plugin_runtime_value, runtime.type, runtime.entry))
            runtime.command?.let { SettingsRow(title = stringResource(R.string.plugin_runtime_command), subtitle = it.joinToString(" ")) }
            runtime.dependencies?.let { SettingsRow(title = stringResource(R.string.plugin_dependencies), subtitle = stringResource(R.string.plugin_dependency_value, it.tool, it.file)) }
        }
    }
}

@Composable
private fun PluginRisks(risks: PluginRiskSummary, processWarning: Boolean = true) {
    if (risks.nativeProcess && processWarning) Text(stringResource(R.string.plugin_process_warning), modifier = Modifier.padding(vertical = Spacing.sm))
    if (risks.htmlPages) Text(stringResource(R.string.plugin_page_security_hint), modifier = Modifier.padding(vertical = Spacing.sm))
    if (risks.dependencies) Text(stringResource(R.string.plugin_dependencies_warning), modifier = Modifier.padding(vertical = Spacing.sm))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginPreviewDialog(
    preview: PluginPreview,
    languageTag: String,
    busy: Boolean,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy }
    )
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    AdaptiveModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !busy
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            Text(
                stringResource(R.string.plugin_preview_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                PluginIdentity(
                    preview.manifest.name.resolve(languageTag),
                    preview.manifest.version.toString(),
                    preview.manifest.description.resolve(languageTag),
                    preview.content.icon
                )
                Text(stringResource(R.string.plugin_center_review_warning), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.semanticColors.warning)
                Text(stringResource(R.string.plugin_install_disabled), style = MaterialTheme.typography.bodyMedium)
                preview.installedVersion?.let {
                    Text(stringResource(R.string.plugin_replacement_warning, it, preview.manifest.version.toString()), color = MaterialTheme.colorScheme.error)
                }
                PluginRisks(preview.risks)
                SettingsGroupHeader(stringResource(R.string.plugin_center_review_source))
                PluginOriginMetadata(preview.source)
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.plugin_publisher), subtitle = preview.manifest.publisher)
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.plugin_repository), subtitle = preview.manifest.repository)
                }
                Text(stringResource(R.string.plugin_metadata_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.plugin_source_change_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.warning)
                PluginExpandableSection(
                    title = stringResource(R.string.plugin_center_features),
                    stateKey = "${preview.token}:features",
                    summary = pluginFeatureSummary(preview.manifest),
                    modifier = Modifier.testTag("plugin-preview-features")
                ) { PluginContributions(preview.manifest, languageTag) }
                PluginExpandableSection(
                    title = stringResource(R.string.plugin_center_technical),
                    stateKey = "${preview.token}:technical",
                    summary = "${preview.manifest.id} · ${preview.manifest.license}",
                    icon = FeatherIcons.Code,
                    modifier = Modifier.testTag("plugin-preview-technical")
                ) { PluginMetadata(preview.manifest, languageTag) }
                PluginExpandableSection(
                    title = stringResource(R.string.plugin_center_digest),
                    stateKey = "${preview.token}:digest",
                    summary = preview.contentHash.take(12) + "…",
                    icon = FeatherIcons.Info,
                    modifier = Modifier.testTag("plugin-preview-digest")
                ) {
                    Text(stringResource(R.string.plugin_content_digest, preview.contentHash), style = MaterialTheme.typography.bodySmall)
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (busy) CircularProgressIndicator(Modifier.weight(1f, fill = false))
                TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text(stringResource(R.string.plugin_cancel)) }
                Button(onClick = { if (!busy) onInstall(preview.token) }, enabled = !busy) {
                    Text(stringResource(if (preview.installedVersion == null) R.string.plugin_install else R.string.plugin_replace))
                }
            }
        }
    }
}

@Composable
internal fun PluginTrustDialog(
    trust: PluginTrustPreview,
    languageTag: String,
    busy: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var acknowledged by remember(trust.token, trust.installRevision, trust.contentHash) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_trust_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(trust.manifest?.name?.resolve(languageTag) ?: trust.id, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.plugin_process_warning), modifier = Modifier.padding(vertical = Spacing.md))
                Text(stringResource(R.string.plugin_trust_revision_warning))
                trust.risks?.let { PluginRisks(it, processWarning = false) }
                Text(stringResource(R.string.plugin_metadata_warning), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.plugin_content_digest, trust.contentHash), style = MaterialTheme.typography.bodySmall)
                PluginProblems(trust.errors.map { it.toProblem() })
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.plugin_trust_acknowledge),
                        trailing = { AppSwitch(acknowledged, { acknowledged = it }, enabled = !busy, modifier = Modifier.testTag("plugin-trust-acknowledge")) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(trust.id, trust.token) }, enabled = acknowledged && !busy && trust.errors.isEmpty() && trust.manifest != null) {
                Text(stringResource(R.string.plugin_trust_enable))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_cancel)) } }
    )
}

@Composable
internal fun PluginDetailContent(
    plugin: InstalledPlugin,
    languageTag: String,
    busy: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onReplace: () -> Unit,
    onOpenPage: (String, PluginPage) -> Unit,
    onInvokeAction: (String, PluginUiEntry) -> Unit,
    onPrepareDependencies: (String) -> Unit,
    modifier: Modifier = Modifier,
    capabilityContent: @Composable () -> Unit = {},
    onClearOperationGrants: (() -> Unit)? = null
) {
    val configPage = plugin.content.configPage
    val otherPages = plugin.content.pages.filterNot { it.key == configPage?.key }
    val stateKey = "${plugin.record.id}:${plugin.record.installRevision}"
    val name = plugin.manifest?.name?.resolve(languageTag) ?: plugin.record.id
    val enableDescription = stringResource(R.string.plugin_center_enable_contentdescription, name)
    var safetyExpanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup(Modifier.testTag("plugin-detail-identity-card")
            .border(0.5.dp, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.lg))) {
            Column(
                Modifier.fillMaxWidth().testTag("plugin-detail-identity").padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                PluginVisualHeader(
                    name = name,
                    version = plugin.record.version,
                    icon = plugin.content.icon,
                    iconSize = 56.dp,
                    titleStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(shape = RoundedCornerShape(Radius.xs), color = MaterialTheme.semanticColors.mutedSurface) {
                        Text(
                            stringResource(R.string.plugin_center_installed),
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                plugin.manifest?.description?.resolve(languageTag)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().testTag("plugin-detail-enable-row")
                    .clickable(enabled = !busy && plugin.status != PluginStatus.INVALID) {
                        if (plugin.isActive) onDisable(plugin.record.id) else onEnable(plugin.record.id)
                    }.padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.plugin_visual_enable_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(when {
                            plugin.status == PluginStatus.INVALID -> R.string.plugin_invalid
                            plugin.isActive -> R.string.plugin_enabled
                            else -> R.string.plugin_disabled
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (plugin.status == PluginStatus.INVALID) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppSwitch(
                    checked = plugin.isActive,
                    onCheckedChange = { enabled -> if (enabled) onEnable(plugin.record.id) else onDisable(plugin.record.id) },
                    enabled = !busy && plugin.status != PluginStatus.INVALID,
                    modifier = Modifier.testTag("plugin-detail-switch-${plugin.record.id}")
                        .semantics { contentDescription = enableDescription }
                )
            }
            if (configPage != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(
                        onClick = { if (!busy && plugin.isActive) onOpenPage(plugin.record.id, configPage) },
                        enabled = !busy && plugin.isActive,
                        shape = RoundedCornerShape(Radius.mdLarge),
                        modifier = Modifier.fillMaxWidth().testTag("plugin-page-${configPage.localId}")
                    ) {
                        Icon(FeatherIcons.Sliders, contentDescription = null, modifier = Modifier.size(Spacing.lg))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.plugin_visual_open_configuration))
                    }
                    if (!plugin.isActive) {
                        Text(stringResource(R.string.plugin_center_enable_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                SettingsRow(title = stringResource(R.string.plugin_center_no_configuration))
            }
        }
        if (plugin.errors.isNotEmpty()) PluginProblems(plugin.errors.map { it.toProblem() })
        SettingsGroupHeader(stringResource(R.string.plugin_center_permissions))
        capabilityContent()
        Row(
            modifier = Modifier.fillMaxWidth().testTag("plugin-detail-safety").clickable { safetyExpanded = !safetyExpanded }
                .padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(FeatherIcons.Shield, contentDescription = null, tint = MaterialTheme.semanticColors.warning, modifier = Modifier.size(Spacing.md))
            Text(
                stringResource(R.string.plugin_visual_runtime_notice),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.plugin_visual_safety_details), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            ExpandableChevronIcon(expanded = safetyExpanded, size = Spacing.md, tint = MaterialTheme.semanticColors.subtleText)
        }
        if (safetyExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.plugin_process_warning), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.plugin_local_only), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (plugin.content.runtime?.dependencies != null) {
            SettingsGroupHeader(stringResource(R.string.plugin_center_prepare))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.plugin_prepare_dependencies),
                    subtitle = stringResource(if (plugin.record.dependenciesReady) R.string.plugin_dependencies_ready else R.string.plugin_dependencies_needed),
                    onClick = { onPrepareDependencies(plugin.record.id) }, enabled = !busy && plugin.isActive
                )
            }
            Text(stringResource(R.string.plugin_dependencies_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.warning)
        }
        if (otherPages.isNotEmpty() || plugin.content.actions.isNotEmpty()) {
            SettingsGroupHeader(stringResource(R.string.plugin_center_more_pages))
            Text(stringResource(R.string.plugin_detail_entries_hint), style = MaterialTheme.typography.bodySmall)
            if (!plugin.isActive) Text(stringResource(R.string.plugin_center_enable_first), style = MaterialTheme.typography.bodySmall)
            SettingsGroup {
                otherPages.forEach { page ->
                    SettingsRow(
                        title = page.title.resolve(languageTag), subtitle = stringResource(R.string.plugin_open_page),
                        onClick = { onOpenPage(plugin.record.id, page) }, enabled = !busy && plugin.isActive,
                        modifier = Modifier.testTag("plugin-page-${page.localId}")
                    )
                }
                plugin.content.actions.forEach { action ->
                    SettingsRow(
                        title = action.title.resolve(languageTag), subtitle = stringResource(R.string.plugin_run_action),
                        onClick = { onInvokeAction(plugin.record.id, action) }, enabled = !busy && plugin.isActive,
                        modifier = Modifier.testTag("plugin-action-${action.localId}")
                    )
                }
            }
        }
        SettingsGroup(Modifier.testTag("plugin-detail-sections")
            .border(0.5.dp, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.lg))) {
            PluginVisualExpandableRow(
                title = stringResource(R.string.plugin_center_features),
                stateKey = "$stateKey:features",
                icon = FeatherIcons.List,
                modifier = Modifier.testTag("plugin-detail-features")
            ) {
                plugin.manifest?.let { PluginContributions(it, languageTag) }
                    ?: SettingsRow(title = stringResource(R.string.plugin_contributions_empty))
            }
            SettingsDivider()
            PluginVisualExpandableRow(
                title = stringResource(R.string.plugin_center_technical),
                stateKey = "$stateKey:technical",
                icon = FeatherIcons.Code,
                modifier = Modifier.testTag("plugin-detail-technical")
            ) {
                plugin.manifest?.let { PluginMetadata(it, languageTag) }
                PluginOriginMetadata(plugin.record.source)
                Text(
                    stringResource(R.string.plugin_content_digest, plugin.record.contentHash),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            SettingsDivider()
            PluginVisualExpandableRow(
                title = stringResource(R.string.plugin_center_maintenance),
                stateKey = "$stateKey:maintenance",
                icon = FeatherIcons.Tool,
                modifier = Modifier.testTag("plugin-detail-maintenance")
            ) {
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.plugin_replace_zip), onClick = onReplace, enabled = !busy)
                    onClearOperationGrants?.let { clear ->
                        SettingsDivider()
                        SettingsRow(title = stringResource(R.string.plugin_operation_grants_clear), onClick = clear, enabled = !busy)
                    }
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.plugin_uninstall), onClick = { onUninstall(plugin.record.id) }, enabled = !busy)
                }
            }
        }
    }
}

@Composable
internal fun PluginUninstallDialog(plugin: InstalledPlugin, languageTag: String, busy: Boolean, onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteData by remember(plugin.record.id, plugin.record.installRevision) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_uninstall_title)) },
        text = {
            Column {
                Text(plugin.manifest?.name?.resolve(languageTag) ?: plugin.record.id)
                Text(stringResource(R.string.plugin_uninstall_warning))
                SettingsRow(title = stringResource(R.string.plugin_keep_data), trailing = {
                    AppSwitch(checked = !deleteData, onCheckedChange = { deleteData = !it }, enabled = !busy, modifier = Modifier.testTag("plugin-keep-data"))
                })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(plugin.record.id, deleteData) }, enabled = !busy) { Text(stringResource(R.string.plugin_uninstall)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_cancel)) } }
    )
}

@Composable
internal fun PluginActionDialog(action: PluginUiEntry, languageTag: String, busy: Boolean, onInvoke: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var input by remember(action.key) { mutableStateOf("{}") }
    val args = remember(input) { runCatching { PluginJson.parse(input, maxDepth = 32) as? JsonObject }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title.resolve(languageTag)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.plugin_action_warning))
                AppTextField(
                    value = input, onValueChange = { if (it.length <= 64 * 1024) input = it },
                    label = stringResource(R.string.plugin_action_arguments),
                    singleLine = false, minLines = 3, maxLines = 8, isError = args == null,
                    colors = dialogTextFieldColors(), enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("plugin-action-arguments")
                )
                if (args == null) Text(stringResource(R.string.plugin_action_invalid_arguments), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { args?.let(onInvoke) }, enabled = !busy && args != null) { Text(stringResource(R.string.plugin_run_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_cancel)) } }
    )
}
