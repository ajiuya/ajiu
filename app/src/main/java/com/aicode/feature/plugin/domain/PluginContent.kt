package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import java.io.File
import kotlinx.serialization.json.JsonObject

fun pluginContributionName(pluginId: String, localId: String): String = "$pluginId:$localId"

internal fun <T> pluginImmutableList(values: Collection<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())

data class PluginSkill(val localId: String, val skill: Skill)
data class PluginAgent(val localId: String, val definition: AgentDefinition)
data class PluginDependency(val tool: String, val file: File, val sha256: String)
data class PluginRuntime(
    val pluginId: String,
    val type: String,
    val installDir: File,
    val entryFile: File,
    val command: List<String>,
    val dependencies: PluginDependency? = null,
    val resident: Boolean = false
)
data class PluginTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: JsonObject,
    val handler: String,
    val runtime: PluginRuntime
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginPage(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val slot: String?,
    val pageFile: String,
    val icon: String,
    val installDir: File,
    val pluginVersion: String
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginUiEntry(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val slot: String,
    val icon: String,
    val page: PluginPage? = null,
    val handler: String? = null,
    val confirm: Boolean = false
) {
    val key: String get() = pluginContributionName(pluginId, localId)
    val kind: PluginUiEntryKind get() = when {
        slot == PluginUiSlots.BACKUP_DATA -> PluginUiEntryKind.DATA
        page != null -> PluginUiEntryKind.PAGE
        else -> PluginUiEntryKind.ACTION
    }
}
data class PluginWorkspaceTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String,
    val category: String,
    val pageFile: String,
    val installDir: File,
    val pluginVersion: String
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginIcon(val name: String? = null, val file: File? = null)
data class PluginContent(
    val icon: PluginIcon? = null,
    val skills: List<PluginSkill> = emptyList(),
    val agents: List<PluginAgent> = emptyList(),
    val workspaceTools: List<PluginWorkspaceTool> = emptyList(),
    val uiEntries: List<PluginUiEntry> = emptyList(),
    val runtime: PluginRuntime? = null,
    val tools: List<PluginTool> = emptyList(),
    val configPage: PluginPage? = null,
    val hooks: List<PluginHook> = emptyList(),
    val mcp: List<PluginMcpServer> = emptyList(),
    val providers: List<PluginProviderDefinition> = emptyList(),
    val sessionSources: List<PluginSessionSourceDefinition> = emptyList(),
    val shizuku: PluginShizukuDefinition? = null,
    val overlay: PluginOverlayDefinition? = null,
    val backup: PluginBackupDefinition? = null,
    val modelAccess: PluginModelAccessDefinition? = null
) {
    val pages: List<PluginPage> get() = uiEntries.mapNotNull { it.page } +
        workspaceTools.map {
            PluginPage(it.pluginId, it.localId, it.title, null, it.pageFile, it.icon, it.installDir, it.pluginVersion)
        } + listOfNotNull(configPage)
    val actions: List<PluginUiEntry> get() = uiEntries.filter { it.kind == PluginUiEntryKind.ACTION }
}
sealed interface ContentLoadResult {
    data class Ok(val content: PluginContent) : ContentLoadResult
    data class Failed(val errors: List<PluginError>) : ContentLoadResult
}
