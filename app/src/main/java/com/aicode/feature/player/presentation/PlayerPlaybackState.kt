package com.aicode.feature.player.presentation

internal data class PlayerPlaybackState(
    val title: String = "", val playing: Boolean = false, val position: Double = 0.0, val duration: Double = 0.0,
    val hasVideo: Boolean = false, val readyState: Int = 0, val videoWidth: Int = 0, val videoHeight: Int = 0,
    val background: Boolean = false
) {
    val canPictureInPicture get() = hasVideo && readyState >= 2
    fun pipSignature(owner: String) = PlayerPipSignature(owner, playing, videoWidth, videoHeight)
    val metadataSignature get() = title to (duration * 1000).toLong()
}
internal data class PlayerPipSignature(val owner: String, val playing: Boolean, val width: Int, val height: Int)
