package com.aicode.feature.player.presentation

import org.junit.Assert.*
import org.junit.Test

class PlayerSurfaceSignatureTest {
    @Test fun positionAndBufferUpdatesKeepSurfaceSignaturesStable() {
        val state = PlayerPlaybackState(title = "Track", playing = true, position = 0.125,
            duration = 100.25, hasVideo = true, readyState = 2, videoWidth = 1920, videoHeight = 1080)
        for (i in 1..2000) {
            val next = state.copy(position = i / 10.0, readyState = 4, background = true)
            assertEquals(state.pipSignature("owner"), next.pipSignature("owner"))
            assertEquals(state.metadataSignature, next.metadataSignature)
        }
        assertNotEquals(state.pipSignature("owner"), state.copy(playing = false).pipSignature("owner"))
        assertNotEquals(state.pipSignature("owner"), state.copy(videoWidth = 720).pipSignature("owner"))
        assertNotEquals(state.pipSignature("owner"), state.pipSignature("new-owner"))
        assertNotEquals(state.metadataSignature, state.copy(title = "next").metadataSignature)
        assertNotEquals(state.metadataSignature, state.copy(duration = 100.251).metadataSignature)
    }

    @Test fun pictureInPictureRequiresDecodedVideoReadiness() {
        val state = PlayerPlaybackState(hasVideo = true, readyState = 2)
        assertTrue(state.canPictureInPicture)
        assertTrue(state.copy(readyState = 4).canPictureInPicture)
        assertFalse(state.copy(readyState = 1).canPictureInPicture)
        assertFalse(state.copy(hasVideo = false).canPictureInPicture)
    }
}
