package com.aicode.feature.plugin.data.index

import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginJson
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.SemVer
import java.io.IOException
import java.net.URI

class PluginIndexException(message: String, cause: Throwable? = null) : IOException(message, cause)
class NoMatchingReleaseException(message: String) : IOException(message)

internal object PluginIndexParser {
    private val authorPattern = Regex("^[a-z][a-z0-9-]*$")
    private val languagePattern = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    fun parse(bytes: ByteArray): PluginIndexDocument {
        if (bytes.size > MAX_INDEX_BYTES) throw PluginIndexException("index byte limit")
        val index = try {
            val root = PluginJson.parse(bytes.toString(Charsets.UTF_8), maxDepth = 16)
            PluginIndexJson.decodeFromJsonElement(PluginIndexDocument.serializer(), root)
        } catch (failure: Exception) { throw PluginIndexException("invalid index", failure) }
        if (index.schemaVersion != 1) throw PluginIndexException("unsupported index schema")
        if (index.plugins.size > 5000) throw PluginIndexException("index entry limit")
        val ids = hashSetOf<String>()
        index.plugins.forEach { plugin ->
            if (!PluginManifestParser.isValidPluginId(plugin.id) || !ids.add(plugin.id)) throw PluginIndexException("invalid or duplicate plugin id")
            validateLocalized(plugin.name, 120, false)
            validateLocalized(plugin.description, 2000, true)
            if (!authorPattern.matches(plugin.author)) throw PluginIndexException("invalid author")
            if (PluginRepositoryAddress.parse(plugin.repository) == null) throw PluginIndexException("invalid repository")
            plugin.version?.let { if (it.startsWith('v') || SemVer.parse(it) == null) throw PluginIndexException("invalid version") }
            plugin.minAppVersion?.let { if (SemVer.parse(it) == null) throw PluginIndexException("invalid minimum version") }
            if (plugin.tags.size > 4 || plugin.tags.distinct().size != plugin.tags.size || plugin.tags.any { it !in PluginTags.ALL }) throw PluginIndexException("invalid tags")
            plugin.logo?.let(::requireHttps)
            plugin.homepage?.let(::requireHttps)
            if ((plugin.license?.length ?: 0) > 200) throw PluginIndexException("license limit")
        }
        return index
    }

    private fun validateLocalized(text: LocalizedText, max: Int, newlines: Boolean) {
        if (text.values.size > 16) throw PluginIndexException("locale limit")
        text.values.forEach { (tag, value) ->
            if (tag != LocalizedText.DEFAULT_KEY && !languagePattern.matches(tag)) throw PluginIndexException("invalid locale")
            if (value.isEmpty() || value.length > max || value.any { it.isISOControl() && !(newlines && it == '\n') }) throw PluginIndexException("invalid text")
        }
    }
    private fun requireHttps(value: String) { if (!PluginIndexAddress.isHttps(value)) throw PluginIndexException("HTTPS required") }
    const val MAX_INDEX_BYTES = 2 * 1024 * 1024
}

internal object PluginRepositoryAddress {
    fun parse(value: String): Pair<String, String>? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val segments = uri.rawPath.trim('/').split('/')
        val allowed = Regex("[A-Za-z0-9_.-]+")
        if (segments.size != 2 || segments.any { !allowed.matches(it) || it in setOf(".", "..") }) return null
        val repo = segments[1].removeSuffix(".git")
        return if (repo.isBlank()) null else segments[0] to repo
    }
}

internal object PluginIndexAddress {
    fun isHttps(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
            value.none { it <= ' ' || it == '\\' } && uri.port in setOf(-1, 443)
    }
    fun normalize(raw: String): String {
        val value = raw.trim()
        require(isHttps(value) && URI(value).rawQuery == null) { "HTTPS index URL required" }
        val uri = URI(value).normalize()
        return URI("https", null, uri.host.lowercase(), -1, uri.path.ifEmpty { "/" }, uri.query, null).toASCIIString()
    }
}
