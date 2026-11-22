package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ToolBeforeHookResult(val denied: Boolean = false, val denyReason: String? = null,
    val arguments: JsonObject? = null, val delegate: String? = null)
internal data class HttpRequestHookResult(val url: String? = null, val headers: Map<String, String>? = null,
    val body: String? = null, val checkCurrent: () -> Unit = {})

internal object PluginHookTransform {
    const val MAX_JSON_CHARS = 256 * 1024
    const val MAX_HTTP_BODY_BYTES = 4 * 1024 * 1024
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val RESERVED_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-connection", "expect", "upgrade", "trailer", "te")

    fun logs(value: JsonElement): List<String> = ((value as? JsonObject)?.get("logs") as? JsonArray).orEmpty()
        .take(50).mapNotNull { (it as? JsonPrimitive)?.takeIf { text -> text.isString }?.content?.take(500) }
    private fun validLogs(value: JsonObject): Boolean = value["logs"]?.let { raw ->
        raw is JsonArray && raw.size <= 50 && raw.all { it is JsonPrimitive && it.isString && it.content.length <= 500 }
    } ?: true

    fun parseBefore(value: JsonElement): ToolBeforeHookResult? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys.any { it !in setOf("decision", "reason", "arguments", "delegate", "logs") } || !validLogs(obj)) return null
        val decision = obj["decision"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        if (decision != null && decision !in setOf("allow", "deny")) return null
        val reason = obj["reason"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 500 }?.content ?: return null }
        val args = obj["arguments"]?.let { it as? JsonObject ?: return null }
        if (args != null && args.toString().length > MAX_JSON_CHARS) return null
        val delegate = obj["delegate"]?.let {
            val definition = it as? JsonObject ?: return null
            if (definition.keys != setOf("handler")) return null
            (definition["handler"] as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")) }?.content ?: return null
        }
        return ToolBeforeHookResult(decision == "deny", reason, args, delegate)
    }

    fun parseAfter(value: JsonElement): JsonElement? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys.any { it !in setOf("data", "logs") } || !validLogs(obj)) return null
        return obj["data"]?.takeIf { it.toString().length <= MAX_JSON_CHARS }
    }

    fun parseHttp(value: JsonElement): HttpRequestHookResult? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys.any { it !in setOf("url", "headers", "body", "logs") } || !validLogs(obj)) return null
        fun string(name: String): String? = obj[name]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (obj.keys.any { it !in setOf("headers", "logs") && obj[it] !is JsonPrimitive }) return null
        val url = string("url")
        if ("url" in obj && url == null || url != null && url.length > 4096) return null
        val body = string("body")
        if ("body" in obj && body == null) return null
        if (body != null && (body.toByteArray(Charsets.UTF_8).size > MAX_HTTP_BODY_BYTES ||
                runCatching { PluginJson.parse(body, maxDepth = 128) }.getOrNull() !is JsonObject)) return null
        val headers = obj["headers"]?.let { raw ->
            val map = raw as? JsonObject ?: return null
            if (map.size > 64) return null
            map.mapValues { (key, v) ->
                if (!HEADER_NAME.matches(key) || key.lowercase(java.util.Locale.ROOT) in RESERVED_HEADERS) return null
                (v as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 8192 && p.content.none(Char::isISOControl) }?.content ?: return null
            }
        }
        if (headers != null && headers.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != headers.size) return null
        return HttpRequestHookResult(url, headers, body)
    }
}
