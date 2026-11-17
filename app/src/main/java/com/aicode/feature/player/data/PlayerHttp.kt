package com.aicode.feature.player.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

internal class PlayerHttpException(val status: Int, message: String) : RuntimeException(message)
internal fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
internal fun decodeQuery(value: String): String = try { URLDecoder.decode(value, "UTF-8") }
    catch (_: IllegalArgumentException) { throw PlayerHttpException(400, "Invalid encoding") }
internal fun sessionToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun sessionRoute(rawPath: String, token: String): String? {
    val prefix = "/$token/"
    if (!rawPath.startsWith(prefix)) return null
    val route = rawPath.removePrefix(prefix)
    if (route.isEmpty() || '\\' in route || route.split('/').any { segment ->
        val decoded = runCatching { decodeQuery(segment.replace("+", "%2B")) }.getOrNull()
        decoded == null || decoded in setOf(".", "..", "") ||
            (!route.startsWith("dash/") && ('/' in decoded || '\\' in decoded)) || decoded.any { it.code < 32 }
    }) return null
    return route
}

internal data class ByteRange(val start: Long, val end: Long) { val length: Long get() = end - start + 1 }
internal fun parseRange(header: String?, size: Long): ByteRange? {
    if (header == null) return null
    fun invalid(): Nothing = throw PlayerHttpException(416, "Range not satisfiable")
    if (size <= 0 || !header.startsWith("bytes=")) invalid()
    val value = header.removePrefix("bytes=").trim()
    if (!Regex("[0-9]*-[0-9]*").matches(value)) invalid()
    val (left, right) = value.split('-', limit = 2)
    if (left.isEmpty()) {
        val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
        return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
    }
    val start = left.toLongOrNull() ?: invalid()
    if (start >= size) invalid()
    val end = if (right.isEmpty()) size - 1 else right.toLongOrNull() ?: invalid()
    if (end < start) invalid()
    return ByteRange(start, end.coerceAtMost(size - 1))
}

internal fun requireSessionOrigin(request: PlayerHttpRequest, origin: String) {
    if (request.headers["host"] != origin.removePrefix("http://") || request.headers["origin"]?.let { it != origin } == true)
        throw PlayerHttpException(403, "Origin mismatch")
}

internal data class PlayerHttpRequest(val method: String, val rawPath: String, val query: Map<String, String>,
    val headers: Map<String, String>, val rawQuery: String = "") {
    companion object {
        fun read(input: InputStream): PlayerHttpRequest {
            var remaining = 32768
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    if (--remaining < 0) throw PlayerHttpException(431, "Headers too large")
                    val b = input.read()
                    if (b < 0) throw PlayerHttpException(400, "Incomplete request")
                    if (b == 10) break
                    bytes.write(b)
                }
                return bytes.toString("ISO-8859-1").removeSuffix("\r")
            }
            val parts = line().split(' ')
            if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) throw PlayerHttpException(400, "Invalid request")
            val target = parts[1]
            if (!target.startsWith('/') || target.startsWith("//") || '#' in target) throw PlayerHttpException(400, "Origin-form target required")
            val headers = linkedMapOf<String, String>()
            while (true) {
                val row = line()
                if (row.isEmpty()) break
                val colon = row.indexOf(':')
                if (colon <= 0 || row.first().isWhitespace()) throw PlayerHttpException(400, "Invalid header")
                val name = row.substring(0, colon).lowercase()
                if (!Regex("[a-z0-9!#$%&'*+.^_`|~-]+").matches(name) || name in headers) throw PlayerHttpException(400, "Duplicate or invalid header")
                val value = row.substring(colon + 1).trim()
                if (value.any { it.code < 32 && it != '\t' }) throw PlayerHttpException(400, "Invalid header")
                headers[name] = value
            }
            val query = linkedMapOf<String, String>()
            val opaqueDash = target.substringBefore('?').split('/').getOrNull(2) == "dash"
            (if (opaqueDash) emptyList() else target.substringAfter('?', "").split('&')).filter { it.isNotEmpty() }.forEach {
                val key = decodeQuery(it.substringBefore('='))
                if (key in query) throw PlayerHttpException(400, "Duplicate parameter")
                query[key] = decodeQuery(it.substringAfter('=', ""))
            }
            return PlayerHttpRequest(parts[0], target.substringBefore('?'), query, headers, target.substringAfter('?', ""))
        }
    }
}

internal class PlayerHttpResponse(private val output: OutputStream, val head: Boolean, private val progress: () -> Unit = {}) {
    var started = false
        private set
    fun headers(status: Int, headers: Map<String, String> = emptyMap()) {
        check(!started)
        started = true
        val reason = when (status) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 502 -> "Bad Gateway"; else -> "Response" }
        val block = buildString {
            append("HTTP/1.1 $status $reason\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n")
            headers.forEach { (key, value) -> require('\r' !in value && '\n' !in value); append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(block.toByteArray(Charsets.ISO_8859_1)); progress()
    }
    fun bytes(status: Int, bytes: ByteArray, type: String, extraHeaders: Map<String, String> = emptyMap()) {
        headers(status, extraHeaders + mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
        if (!head && status !in setOf(204, 304)) stream(bytes.inputStream())
    }
    fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") = bytes(status, text.toByteArray(), type)
    fun track(input: InputStream): InputStream = PlayerProgressInputStream(input, progress)
    fun stream(input: InputStream, length: Long = Long.MAX_VALUE) {
        if (head) return
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            if (count == 0) continue
            progress(); output.write(buffer, 0, count); progress(); remaining -= count
        }
    }
}

internal fun mediaType(path: String): String = when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
    "html" -> "text/html; charset=utf-8"; "js" -> "application/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
    "json" -> "application/json; charset=utf-8"; "m3u", "m3u8" -> "application/vnd.apple.mpegurl"; "mpd" -> "application/dash+xml"
    "mp4", "m4v" -> "video/mp4"; "m4s" -> "video/iso.segment"; "webm" -> "video/webm"; "ts", "m2ts" -> "video/mp2t"; "3gp", "3gpp" -> "video/3gpp"
    "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
    "ogg", "oga", "opus" -> "audio/ogg"; "weba" -> "audio/webm"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream"
}
