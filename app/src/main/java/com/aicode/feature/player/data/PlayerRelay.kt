package com.aicode.feature.player.data

import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal class PlayerRelay(private val upstream: PlayerUpstream) {
    fun forward(request: PlayerHttpRequest, output: PlayerHttpResponse, resources: PlayerResources,
        prefix: String, mappings: DashMappings, url: String, referer: String) {
        val forwarded = request.headers.filterKeys { it in setOf("range", "if-range", "if-none-match", "if-modified-since", "accept", "accept-encoding") }
        upstream.open(resources, url, request.method, referer, forwarded, streaming = true).use { exchange ->
            val response = exchange.response
            val body = response.body
            val type = response.header("Content-Type").orEmpty().lowercase()
            val path = response.request.url.encodedPath.lowercase()
            val hls = type.contains("mpegurl") || path.endsWith(".m3u8")
            val dash = type.contains("dash+xml") || path.endsWith(".mpd")
            if (response.code == 200 && !output.head && (hls || dash) && body != null) {
                val bytes = decodedBody(response.header("Content-Encoding"), output.track(body.byteStream())).use { it.readLimited() }
                val result = if (hls) rewriteHls(bytes.toString(Charsets.UTF_8), response.request.url, prefix, referer).toByteArray()
                    else rewriteDash(bytes, response.request.url, mappings, referer)
                output.bytes(200, result, if (hls) "application/vnd.apple.mpegurl; charset=utf-8" else "application/dash+xml; charset=utf-8",
                    mapOf("X-Ajiu-Manifest-Rewritten" to if (hls) "hls" else "dash"))
            } else {
                output.headers(response.code, relayResponseHeaders(response.headers.toMultimap().mapValues { it.value.joinToString(", ") }))
                if (body != null && response.code !in setOf(204, 304)) output.stream(body.byteStream())
            }
        }
    }
}
internal fun relayResponseHeaders(headers: Map<String, String>): Map<String, String> {
    val allowed = setOf("content-type", "content-length", "content-range", "accept-ranges", "content-encoding", "etag", "last-modified", "expires", "vary", "retry-after", "content-disposition")
    return headers.filterKeys { it.lowercase() in allowed }
}
internal fun decodedBody(encoding: String?, input: InputStream): InputStream = when (encoding?.lowercase()?.trim()) {
    null, "", "identity" -> input
    "gzip", "x-gzip" -> GZIPInputStream(input)
    "deflate" -> InflaterInputStream(input)
    else -> throw PlayerHttpException(502, "Unsupported document encoding")
}
