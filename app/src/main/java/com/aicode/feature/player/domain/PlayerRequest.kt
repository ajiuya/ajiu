package com.aicode.feature.player.domain

import kotlinx.serialization.Serializable

@Serializable
data class PlayerRequest(
    val mode: String = "index",
    val src: String = "",
    val title: String = "",
    val items: List<PlayerItem> = emptyList(),
    val api: String = "",
    val query: String = "",
    val root: String = "~/workspace",
    val referer: String = "",
    val videoSources: List<String> = emptyList(),
    val videoId: String = ""
)

@Serializable
data class PlayerItem(val name: String, val url: String, val kind: String = "video")
