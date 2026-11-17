package com.aicode.feature.player.domain

object PlayerMediaTypes {
    private val audio = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "wma", "ape", "alac", "aiff", "weba")
    private val video = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts", "m2ts", "avi", "flv", "ogv", "3gp", "3gpp", "rmvb", "m3u", "m3u8", "mpd")
    private fun extension(path: String) = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    fun supports(path: String, mimeType: String = ""): Boolean = extension(path) in audio || extension(path) in video ||
        mimeType.startsWith("audio/", true) || mimeType.startsWith("video/", true) ||
        mimeType.substringBefore(';').lowercase() in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml")
    fun kind(path: String, mimeType: String = ""): String = if (extension(path) in audio || mimeType.startsWith("audio/", true)) "audio" else "video"
    fun request(path: String, title: String, mimeType: String = ""): PlayerRequest {
        val normalized = runCatching { PlayerRequestCodec.location(path) }.getOrDefault(path)
        val root = if (normalized.startsWith("http://") || normalized.startsWith("https://")) "~/workspace"
            else normalized.substringBeforeLast('/', "~/workspace").ifEmpty { "/" }
        return PlayerRequest(mode = "embed", src = normalized, title = title,
            items = listOf(PlayerItem(title, normalized, kind(path, mimeType))), root = root)
    }
}
