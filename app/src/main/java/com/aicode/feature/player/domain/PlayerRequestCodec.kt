package com.aicode.feature.player.domain

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PlayerRequestCodec {
    const val MAX_RAW_BYTES = 2 * 1024 * 1024
    const val MAX_ENCODED_BYTES = 512 * 1024
    const val MAX_ITEMS = 2000
    const val MAX_URL_LENGTH = 8192
    const val MAX_TITLE_LENGTH = 512
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(raw: String): PlayerRequest? {
        if (raw.length > MAX_RAW_BYTES || raw.toByteArray().size > MAX_RAW_BYTES || !boundedDepth(raw)) return null
        return try { validate(json.decodeFromString<PlayerRequest>(raw)) }
        catch (_: SerializationException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    fun validate(request: PlayerRequest): PlayerRequest {
        require(request.mode in setOf("embed", "index", "series")) { "Invalid player mode" }
        text(request.title, MAX_TITLE_LENGTH)
        text(request.query, MAX_TITLE_LENGTH)
        text(request.videoId, 256)
        require(request.videoSources.size <= 64 && request.videoSources.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        require(request.videoId.isEmpty() || request.videoSources.size == 1)
        require(request.items.size <= MAX_ITEMS)
        val items = request.items.map {
            text(it.name, MAX_TITLE_LENGTH)
            require(it.kind in setOf("audio", "video"))
            it.copy(url = mediaLocation(it.url))
        }
        val src = request.src.takeIf(String::isNotEmpty)?.let(::mediaLocation).orEmpty()
        val api = request.api.takeIf(String::isNotEmpty)?.let { httpUrl(it).toString() }.orEmpty()
        val referer = request.referer.takeIf(String::isNotEmpty)?.let { httpUrl(it).toString() }.orEmpty()
        val root = location(request.root)
        require(!root.startsWith("http://") && !root.startsWith("https://"))
        if (request.mode == "embed") require(src.isNotEmpty() && items.size <= 1)
        if (request.mode == "series") require(items.isNotEmpty() || src.isNotEmpty() ||
            (api.isNotEmpty() && request.query.isNotBlank()) || (request.videoId.isNotBlank() && request.videoSources.size == 1))
        return request.copy(src = src, items = items, api = api, referer = referer, root = root).also {
            require(json.encodeToString(it).toByteArray().size <= MAX_ENCODED_BYTES)
        }
    }

    internal fun httpUrl(raw: String): HttpUrl {
        text(raw, MAX_URL_LENGTH)
        require(raw.isNotBlank() && raw == raw.trim() && '\\' !in raw)
        val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("Invalid URL") }
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.rawAuthority != null)
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("HTTP(S) URL required")
        require(url.username.isEmpty() && url.password.isEmpty() && uri.rawUserInfo == null)
        val host = url.host.lowercase().trimEnd('.')
        require(host.contains('.') || ':' in host)
        require(host != "localhost" && listOf(".localhost", ".local", ".internal", ".test", ".invalid").none(host::endsWith))
        if (':' in host || host.all { it.isDigit() || it == '.' }) require(publicAddress(InetAddress.getByName(host)))
        require(url.toString().length <= MAX_URL_LENGTH)
        return url
    }

    internal fun location(raw: String): String {
        text(raw, MAX_URL_LENGTH)
        require(raw.isNotBlank() && raw == raw.trim() && '\\' !in raw && !raw.startsWith("//"))
        if (raw.startsWith("http:", true) || raw.startsWith("https:", true)) return httpUrl(raw).toString()
        val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(raw)?.value?.dropLast(1)?.lowercase()
        val path = when (scheme) {
            null -> raw
            "file" -> {
                val uri = URI(raw)
                require(uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && !uri.isOpaque)
                require(uri.path?.startsWith('/') == true)
                uri.path
            }
            else -> throw IllegalArgumentException("Unsupported media scheme")
        }
        text(path, MAX_URL_LENGTH)
        require(!path.startsWith("//") && '\\' !in path && path.split('/').none { it == ".." })
        require(!path.startsWith('~') || path == "~/workspace" || path.startsWith("~/workspace/"))
        return if (path.startsWith('/') || path.startsWith("~/workspace")) path else "~/workspace/${path.removePrefix("./")}"
    }

    private fun mediaLocation(raw: String): String = location(raw).also { value ->
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val url = value.toHttpUrlOrNull()!!
            val path = url.encodedPath.lowercase()
            require(!path.endsWith(".html") && !path.endsWith(".htm") &&
                url.host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "vimeo.com", "www.vimeo.com") &&
                !(url.host == "archive.org" && path.startsWith("/details/")) &&
                !(url.host == "commons.wikimedia.org" && path.startsWith("/wiki/"))) { "Webpage requires browser" }
        }
    }

    private fun text(value: String, max: Int) = require(value.length <= max && value.none { it.code < 32 || it.code == 127 })

    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) || (a == 172 && c in 16..31) ||
                (a == 192 && (c == 168 || c == 0 || (c == 88 && b[2] == 99))) ||
                (a == 198 && (c in 18..19 || (c == 51 && b[2] == 100))) || (a == 203 && c == 0 && b[2] == 113))
        }
        return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 1 && ((b[2] == 0x0d && b[3] == 0xb8) || b[2] == 0)) && !(b[0] == 0x20 && b[1] == 2)
    }

    internal fun boundedDepth(raw: String): Boolean {
        var depth = 0; var quoted = false; var escaped = false
        for (ch in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }
}
