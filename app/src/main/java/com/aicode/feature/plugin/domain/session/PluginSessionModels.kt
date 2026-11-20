package com.aicode.feature.plugin.domain.session

import kotlinx.serialization.json.JsonElement

data class PluginSessionSummary(
    val id: String,
    val sessionId: String,
    val sourceKey: String,
    val sourceTitle: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val title: String,
    val status: String,
    val running: Boolean,
    val updatedAt: Long?,
    val group: String,
    val hostName: String,
    val workspaceTitle: String,
    val capabilities: List<String> = emptyList(),
    val providerId: String? = null,
    val model: String? = null,
    val executionMode: String? = null
)

sealed interface PluginMessageBlock {
    data class Text(val text: String) : PluginMessageBlock
    data class Reasoning(val text: String) : PluginMessageBlock
    data class Tool(val callId: String?, val name: String, val status: String, val input: String?, val output: String?) : PluginMessageBlock
    data class Attachment(val kind: String?, val name: String?, val mimeType: String?, val path: String?) : PluginMessageBlock
    data class Notice(val text: String) : PluginMessageBlock
}

data class PluginSessionMessage(val id: String, val role: String, val createdAt: Long?, val status: String, val blocks: List<PluginMessageBlock>)
data class PluginSessionPage(val sessionId: String, val title: String, val status: String, val messages: List<PluginSessionMessage>,
    val hasMore: Boolean, val nextCursor: JsonElement?)
data class PluginSessionListResult(val sessions: List<PluginSessionSummary>, val errors: List<String> = emptyList())
data class PluginSessionGroup(val pluginId: String, val pluginName: String, val sessions: List<PluginSessionSummary>,
    val loading: Boolean = false, val error: String? = null)
