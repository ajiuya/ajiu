package com.aicode.feature.player.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionPolicyTest {
    private val origin = "http://127.0.0.1:12345"
    private val policy = PlayerSessionPolicy("$origin/test_TOKEN-123/embed.html", origin)

    @Test fun sessionPagesAndResourcesKeepCapability() {
        listOf("index.html", "embed.html", "series.html").forEach {
            assertTrue(policy.allowsPage("$origin/test_TOKEN-123/$it?src=https%3A%2F%2Fmedia.example%2Fv.mp4"))
        }
        listOf("app.js", "vendor/hls-1.5.15.min.js", "relay?u=https%3A%2F%2Fexample.org", "api/catalog", "local?path=x.mp4", "files/tilde/workspace/a.ts").forEach {
            assertTrue(policy.allowsRequest("$origin/test_TOKEN-123/$it"))
        }
        assertEquals("$origin/test_TOKEN-123/index.html", policy.fullPageBase())
    }

    @Test fun anotherTokenPortHostOrSchemeIsRejected() {
        listOf(
            "$origin/other/index.html", "$origin/test_TOKEN-1234/index.html", "$origin/test_TOKEN-123",
            "http://127.0.0.1:12346/test_TOKEN-123/index.html", "https://127.0.0.1:12345/test_TOKEN-123/index.html",
            "http://localhost:12345/test_TOKEN-123/index.html", "http://evil.example:12345/test_TOKEN-123/index.html",
            "http://user@127.0.0.1:12345/test_TOKEN-123/index.html", "file:///test_TOKEN-123/index.html",
            "content://provider/test_TOKEN-123/index.html", "javascript:alert(1)", "data:text/html,hello"
        ).forEach { assertFalse(it, policy.allowsRequest(it)) }
    }

    @Test fun encodedTraversalAndSeparatorsAreRejected() {
        listOf("../index.html", "%2e%2e/index.html", ".%2e/index.html", "x/../../index.html", "x%2f../index.html", "x%5c../index.html", "./index.html").forEach {
            assertFalse(it, policy.allowsRequest("$origin/test_TOKEN-123/$it"))
        }
    }

    @Test fun relayAndAssetsAreNotTrustedTopLevelBridgePages() {
        listOf("relay?u=page.html", "vendor/index.htmlx", "config.js", "dash/id/index.html", "vendor/index.html", "relay/index.html").forEach {
            assertFalse(it, policy.allowsPage("$origin/test_TOKEN-123/$it"))
        }
        assertTrue(policy.sameOrigin(origin))
        assertFalse(policy.sameOrigin("http://127.0.0.1:54321"))
    }

    @Test fun mediaIsNotHandedToAnExternalPlayer() {
        listOf("mp4", "MP4", "m3u8", "mpd", "mp3", "flac", "m4a", "webm", "ts", "vtt").forEach {
            assertFalse(it, PlayerSessionPolicy.isExternalPage("https://example.org/media.$it?token=secret"))
        }
        assertTrue(PlayerSessionPolicy.isExternalPage("https://example.org/help"))
        assertTrue(PlayerSessionPolicy.isExternalPage("mailto:user@example.org"))
        assertFalse(PlayerSessionPolicy.isExternalPage("intent://media#Intent;scheme=https;end"))
        assertFalse(PlayerSessionPolicy.isExternalPage("file:///sdcard/movie.mp4"))
    }

    @Test fun remoteOriginIsNotAcceptedAsASession() {
        assertTrue(runCatching { PlayerSessionPolicy("https://example.org:443/token/index.html", "https://example.org:443") }.isFailure)
        assertTrue(runCatching { PlayerSessionPolicy("$origin/token/index.html", "http://127.0.0.1") }.isFailure)
        assertTrue(runCatching { PlayerSessionPolicy("$origin/token/embed.html", "$origin/another/") }.isFailure)
    }
}
