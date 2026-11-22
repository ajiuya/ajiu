package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpServerConfig

object PluginHookModes {
    const val OBSERVE = "observe"
    const val TRANSFORM = "transform"
    const val REVIEW = "review"
    val ALL = setOf(OBSERVE, TRANSFORM)
}

object PluginHookEvents {
    const val APP_STARTED = "app.started"
    const val AGENT_RUN_STARTED = "agent.run.started"
    const val AGENT_RUN_FINISHED = "agent.run.finished"
    const val AGENT_REQUEST_STARTED = "agent.request.started"
    const val AGENT_REQUEST_FINISHED = "agent.request.finished"
    const val AGENT_TOOL_STARTED = "agent.tool.started"
    const val AGENT_TOOL_FINISHED = "agent.tool.finished"
    const val AGENT_COMPACTION = "agent.compaction"
    const val AGENT_TOOL_BEFORE = "agent.tool.before"
    const val AGENT_TOOL_AFTER = "agent.tool.after"
    const val AGENT_HTTP_BEFORE = "agent.http.before"
    const val AGENT_PERMISSION_REVIEW = "agent.permission.review"
    const val PLUGIN_INSTALLED = "plugin.installed"
    const val PLUGIN_UNINSTALLED = "plugin.uninstalled"
    const val PLUGIN_ENABLED = "plugin.enabled"
    const val PLUGIN_DISABLED = "plugin.disabled"
    const val BACKUP_EXPORT_BEFORE = "backup.export.before"
    const val BACKUP_IMPORT_FINISHED = "backup.import.finished"
    val BACKUP = setOf(BACKUP_EXPORT_BEFORE, BACKUP_IMPORT_FINISHED)
    val TRANSFORM = setOf(AGENT_TOOL_BEFORE, AGENT_TOOL_AFTER, AGENT_HTTP_BEFORE)
    val SYSTEM_PROTECTED = setOf(AGENT_PERMISSION_REVIEW)
    val ALL = setOf(APP_STARTED, AGENT_RUN_STARTED, AGENT_RUN_FINISHED, AGENT_REQUEST_STARTED,
        AGENT_REQUEST_FINISHED, AGENT_TOOL_STARTED, AGENT_TOOL_FINISHED, AGENT_COMPACTION,
        AGENT_TOOL_BEFORE, AGENT_TOOL_AFTER, AGENT_HTTP_BEFORE, AGENT_PERMISSION_REVIEW, PLUGIN_INSTALLED,
        PLUGIN_UNINSTALLED, PLUGIN_ENABLED, PLUGIN_DISABLED, BACKUP_EXPORT_BEFORE, BACKUP_IMPORT_FINISHED)
}

data class PluginHookRef(val id: String, val event: String, val handler: String, val mode: String = PluginHookModes.OBSERVE)
data class PluginMcpRef(val id: String, val file: String)
object PluginProviderFormats {
    const val OPENAI = "openai"
    val ALL = setOf(OPENAI)
}
data class PluginProviderRef(val id: String, val name: LocalizedText, val description: LocalizedText, val format: String, val baseUrl: String)
data class PluginSessionSourceHandlersRef(
    val listSessions: String,
    val readSession: String,
    val sendMessage: String? = null,
    val interruptSession: String? = null,
    val createSession: String? = null,
    val refresh: String? = null
)
data class PluginSessionSourceRef(val id: String, val title: LocalizedText, val handlers: PluginSessionSourceHandlersRef)
data class PluginShizukuRef(val purpose: String)
data class PluginBackupRef(val purpose: String)
data class PluginModelAccessRef(val purpose: String)
data class PluginOverlaySurfaceRef(
    val id: String,
    val page: String,
    val width: Int = 240,
    val height: Int = 320,
    val draggable: Boolean = true,
    val snap: String = PluginOverlaySnap.NONE
)
object PluginOverlaySnap {
    const val NONE = "none"
    const val X = "x"
    const val Y = "y"
    const val BOTH = "both"
    val ALL = setOf(NONE, X, Y, BOTH)
}
data class PluginOverlayRef(val purpose: String, val surfaces: List<PluginOverlaySurfaceRef> = emptyList())

data class PluginHook(
    val pluginId: String,
    val localId: String,
    val event: String,
    val handler: String,
    val runtime: PluginRuntime,
    val mode: String = PluginHookModes.OBSERVE
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginMcpServer(val pluginId: String, val localId: String, val originalName: String, val config: McpServerConfig) {
    val key: String get() = "$pluginId:$localId:$originalName"
}
data class PluginProviderDefinition(
    val pluginId: String,
    val localId: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val format: String,
    val baseUrl: String,
    val pluginVersion: String
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginSessionSourceDefinition(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val handlers: PluginSessionSourceHandlersRef,
    val pluginVersion: String
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginShizukuDefinition(val pluginId: String, val purpose: String)
data class PluginBackupDefinition(val pluginId: String, val purpose: String)
data class PluginModelAccessDefinition(val pluginId: String, val purpose: String)
data class PluginOverlaySurface(
    val pluginId: String,
    val localId: String,
    val pageFile: String,
    val installDir: java.io.File,
    val pluginVersion: String,
    val width: Int,
    val height: Int,
    val draggable: Boolean,
    val snap: String = PluginOverlaySnap.NONE
) { val key: String get() = pluginContributionName(pluginId, localId) }
data class PluginOverlayDefinition(val pluginId: String, val purpose: String, val surfaces: List<PluginOverlaySurface>)
