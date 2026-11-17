package com.aicode.feature.player.domain

import org.junit.Assert.*
import org.junit.Test

class PlayerMediaTypesTest {
    @Test fun supportedPathsIgnoreQueryFragmentAndExtensionCase() {
        listOf("/tmp/clip.MP4", "~/workspace/track.FLAC", "https://cdn.example/show.m3u8?sig=x#part", "video.mpd#period").forEach {
            assertTrue(it, PlayerMediaTypes.supports(it))
        }
        listOf("notes.txt", "image.png", "https://example.org/watch.html", "file").forEach {
            assertFalse(it, PlayerMediaTypes.supports(it))
        }
    }

    @Test fun mimeTypesSupportExtensionlessAudioVideoAndManifests() {
        listOf("audio/mpeg", "VIDEO/MP4", "application/vnd.apple.mpegurl", "application/x-mpegurl; charset=utf-8", "application/dash+xml").forEach {
            assertTrue(it, PlayerMediaTypes.supports("https://cdn.example/download?id=1", it))
        }
        assertFalse(PlayerMediaTypes.supports("file", "text/plain"))
        assertFalse(PlayerMediaTypes.supports("file", "application/octet-stream"))
    }

    @Test fun audioKindUsesExtensionOrMimeType() {
        listOf("track.MP3", "sound.flac?token=x", "voice.opus#start", "song.m4a").forEach {
            assertEquals(it, "audio", PlayerMediaTypes.kind(it))
        }
        assertEquals("audio", PlayerMediaTypes.kind("https://cdn.example/download", "Audio/ogg"))
        assertEquals("video", PlayerMediaTypes.kind("clip.webm", "video/webm"))
        assertEquals("video", PlayerMediaTypes.kind("stream.m3u8"))
    }

    @Test fun localRequestNormalizesLocationAndUsesParentRoot() {
        val request = PlayerMediaTypes.request("file:///tmp/media/a%20b.mp3", "Track")
        assertEquals("embed", request.mode)
        assertEquals("/tmp/media/a b.mp3", request.src)
        assertEquals("/tmp/media", request.root)
        assertEquals(listOf(PlayerItem("Track", request.src, "audio")), request.items)
        assertEquals("~/workspace/videos", PlayerMediaTypes.request("videos/a.mp4", "Clip").root)
        assertEquals("/", PlayerMediaTypes.request("/a.mp4", "Clip").root)
    }

    @Test fun remoteRequestUsesMimeKindAndWorkspaceRoot() {
        val request = PlayerMediaTypes.request("https://cdn.example/download?id=7", "Song", "audio/mpeg")
        assertEquals("~/workspace", request.root)
        assertEquals("https://cdn.example/download?id=7", request.src)
        assertEquals(listOf(PlayerItem("Song", request.src, "audio")), request.items)
    }

    @Test fun launchValidationRejectsInvalidPathsRootsAndWebpages() {
        listOf("../secret.mp4", "~/workspace/../secret.mp4", "~/secret.mp4", "content://provider/1", "file://remote/tmp/a.mp4",
            "javascript:alert(1)", "https://127.0.0.1/a.mp4", "https://example.org/watch.html", "a\\b.mp4", "").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                PlayerRequestCodec.validate(PlayerMediaTypes.request(path, "Track"))
            }
        }
    }
}
