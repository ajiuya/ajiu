package com.aicode.feature.plugin.runtime

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PluginHttpHeaders {
    const val PLACEHOLDER = "{{Token}}"
    private val sensitive = setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "x-goog-api-key", "cookie", "cookie2", "set-cookie", "set-cookie2")
    private fun secrets(headers: Map<String, String>): Set<String> = headers.filterKeys { it.lowercase(Locale.ROOT) in sensitive }.values
        .flatMap { value -> listOf(value.trim(), value.substringAfter(' ', "").trim()) +
            value.split(';').map { it.substringAfter('=', "").trim() } }.filter { it.isNotEmpty() && it != PLACEHOLDER }.toSet()
    fun redactText(text: String, headers: Map<String, String> = emptyMap()): String {
        var safe = text
        secrets(headers).sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[redacted]") }
        return safe.replace(Regex("(?i)(authorization|bearer|api[_-]?key|token|password|cookie)\\s*[:= ]+[^\\s,;]+"), "$1=[redacted]")
            .filterNot { it.isISOControl() && it != '\t' }.take(500)
    }
    fun redact(headers: Map<String, String>): Map<String, String> {
        val keys = secrets(headers)
        return headers.mapValues { (name, value) -> if (name.lowercase(Locale.ROOT) in sensitive || keys.any { it in value }) PLACEHOLDER else value }
    }
    fun merge(originalUrl: String, originalHeaders: Map<String, String>, returnedHeaders: Map<String, String>, finalUrl: String): Map<String, String> {
        val first = originalUrl.toHttpUrlOrNull()
        val last = finalUrl.toHttpUrlOrNull()
        val same = first != null && last != null && first.scheme == last.scheme && first.host == last.host && first.port == last.port
        val keys = secrets(originalHeaders)
        val result = originalHeaders.filter { (name, value) -> same || name.lowercase(Locale.ROOT) !in sensitive && keys.none { it in value } }.toMutableMap()
        returnedHeaders.forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) !in sensitive && value != PLACEHOLDER && keys.none { it in value }) {
                result.keys.firstOrNull { it.equals(name, true) }?.let(result::remove)
                result[name] = value
            }
        }
        return result
    }
}
