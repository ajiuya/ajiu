package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object McpConfigParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, logTag: String = "McpConfig"): List<McpServerConfig> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrElse {
            FileLogger.w(logTag, "MCP configuration could not be parsed")
            return emptyList()
        } ?: return emptyList()
        val servers = root["mcpServers"] as? JsonObject ?: return emptyList()
        return servers.mapNotNull { (name, value) ->
            val row = value as? JsonObject ?: return@mapNotNull null
            fun string(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
            fun strings(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            fun mapping(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonObject)?.mapNotNull { (key, item) ->
                (item as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }?.toMap().orEmpty()
            val enabled = (row["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val disabled = strings(row["disabledTools"]).toSet()
            val command = string("command")
            val url = string("url")
            when {
                !command.isNullOrBlank() -> McpServerConfig(name, command = command, args = strings(row["args"]),
                    env = mapping(row["env"]), enabled = enabled, disabledTools = disabled)
                !url.isNullOrBlank() -> {
                    val auth = (row["auth"] as? JsonObject)?.let { auth -> McpAuthConfig(
                        type = (auth["type"] as? JsonPrimitive)?.contentOrNull ?: McpAuthConfig.TYPE_OAUTH,
                        clientId = (auth["clientId"] as? JsonPrimitive)?.contentOrNull,
                        authServer = (auth["authServer"] as? JsonPrimitive)?.contentOrNull,
                        scopes = (auth["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ) }
                    McpServerConfig(name, url = url, headers = mapping(row["headers"]), enabled = enabled, disabledTools = disabled, auth = auth)
                }
                else -> null
            }
        }
    }
}
