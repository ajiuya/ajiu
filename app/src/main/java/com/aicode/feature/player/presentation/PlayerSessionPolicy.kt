package com.aicode.feature.player.presentation

import java.net.URI
import java.util.Locale

internal class PlayerSessionPolicy(sessionUrl: String, sessionOrigin: String) {
    private val origin = URI(sessionOrigin)
    private val entry = URI(sessionUrl)
    private val token: String
    val originRule: String
    val entryUrl = sessionUrl
    init {
        require(origin.scheme in setOf("http", "https") && origin.host?.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "[::1]", "::1"))
        require(origin.rawUserInfo == null && origin.port > 0 && origin.rawQuery == null && origin.rawFragment == null)
        require(origin.rawPath.isNullOrEmpty() || origin.rawPath == "/")
        originRule = "${origin.scheme}://${origin.rawAuthority}"
        token = entry.rawPath.orEmpty().split('/').getOrNull(1).orEmpty()
        require(token.matches(Regex("[A-Za-z0-9_-]+")) && allowsPage(sessionUrl))
    }
    fun sameOrigin(value: String) = runCatching {
        val candidate = URI(value)
        candidate.scheme == origin.scheme && candidate.host.equals(origin.host, true) && candidate.port == origin.port && candidate.rawUserInfo == null
    }.getOrDefault(false)
    fun allowsRequest(value: String) = runCatching {
        val candidate = URI(value)
        val raw = candidate.rawPath.orEmpty()
        val lower = raw.lowercase(Locale.ROOT)
        sameOrigin(value) && raw.startsWith("/$token/") && !lower.contains("%2f") && !lower.contains("%5c") &&
            !candidate.path.contains('\\') && candidate.path.split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)
    fun allowsPage(value: String) = allowsRequest(value) && runCatching {
        URI(value).rawPath in setOf("/$token/index.html", "/$token/embed.html", "/$token/series.html")
    }.getOrDefault(false)
    fun fullPageBase() = "$originRule/$token/index.html"
    companion object {
        private val mediaExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "ts", "m2ts", "m3u", "m3u8", "mpd", "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "weba", "vtt", "srt")
        fun isExternalPage(value: String) = runCatching {
            val uri = URI(value)
            uri.scheme in setOf("https", "http", "mailto", "tel") && uri.rawUserInfo == null && uri.path.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT) !in mediaExtensions
        }.getOrDefault(false)
    }
}
