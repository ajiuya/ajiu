package com.aicode.feature.player.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlayerRequestCodecTest {
    private val url = "https://upload.wikimedia.org/example.webm"
    private fun decode(request: PlayerRequest) = PlayerRequestCodec.decode(PlayerRequestCodec.json.encodeToJsonElement(request).toString())
    private fun invalid(request: PlayerRequest) { assertNull(decode(request)) }

    @Test fun defaultsAndUnknownFields() {
        assertEquals(PlayerRequest(), PlayerRequestCodec.decode("{\"future\":true}"))
        val encoded = PlayerRequestCodec.json.encodeToJsonElement(PlayerRequest()).jsonObject
        assertEquals(JsonPrimitive("index"), encoded["mode"])
        assertEquals(JsonPrimitive("~/workspace"), encoded["root"])
        assertEquals(10, encoded.size)
        assertEquals(JsonArray(emptyList()), encoded["videoSources"])
        assertEquals(JsonPrimitive(""), encoded["videoId"])
    }

    @Test fun roundTripIncludesAllFieldsAndKinds() {
        val request = PlayerRequest("series", url, "标题", listOf(PlayerItem("episode", url), PlayerItem("song", "audio.mp3", "audio")),
            "https://catalog.example.org/api", "完整剧集", "~/workspace/videos", "https://commons.wikimedia.org/")
        assertEquals(request.copy(items = request.items.map { if (it.kind == "audio") it.copy(url = "~/workspace/audio.mp3") else it }), decode(request))
    }

    @Test fun configuredVideoIdentityAndSearchFiltersRoundTripWithoutEpisodePayloads() {
        val detail = PlayerRequest(mode = "series", title = "exact work", query = "same title",
            videoSources = listOf("custom_source"), videoId = "987654")
        assertEquals(detail, decode(detail))
        val search = PlayerRequest(query = "keyword", videoSources = listOf("ffzy", "lzi"))
        assertEquals(search, decode(search))
        invalid(detail.copy(videoSources = emptyList()))
        invalid(detail.copy(videoSources = listOf("ffzy", "lzi")))
        invalid(detail.copy(videoSources = listOf("../source")))
        invalid(detail.copy(videoId = "bad\nidentity"))
        assertEquals(PlayerRequest(), PlayerRequestCodec.decode("{}"))
    }

    @Test fun modesAreStrict() {
        listOf("", "video", "stream", "webpage", "EMBED").forEach { invalid(PlayerRequest(mode = it, src = url)) }
        invalid(PlayerRequest(mode = "embed"))
        invalid(PlayerRequest(mode = "series"))
        invalid(PlayerRequest(mode = "embed", src = url, items = listOf(PlayerItem("1", url), PlayerItem("2", url))))
        assertNotNull(decode(PlayerRequest(mode = "series", api = "https://catalog.example.org/api", query = "q")))
    }

    @Test fun supportedLocalLocationsAreNormalized() {
        val samples = mapOf(
            "file:///tmp/a%20b.mp4" to "/tmp/a b.mp4",
            "/tmp/a b.mp4" to "/tmp/a b.mp4",
            "~/workspace/a.mp4" to "~/workspace/a.mp4",
            "videos/a.mp4" to "~/workspace/videos/a.mp4",
            "./a.mp4" to "~/workspace/a.mp4"
        )
        samples.forEach { (src, expected) -> assertEquals(src, expected, decode(PlayerRequest(src = src))!!.src) }
    }

    @Test fun schemesAndForeignFileAuthoritiesAreRejected() {
        listOf("javascript:alert(1)", "data:video/mp4;base64,abc", "content://provider/1", "ftp://example.org/a.mp4",
            "file://remote/tmp/a.mp4", "file:relative", "file:///tmp/a.mp4?q=1", "minis://workspace/a.mp4",
            "minis://attachments/a.mp3", "minis://workspace/%252e%252e/a", "//example.org/a.mp4",
            "~other/a.mp4", "~/secret.mp4", "../a.mp4", "a\\b.mp4", "file:///tmp/%00.mp4", "file:///tmp/a%0Amp4").forEach {
            invalid(PlayerRequest(src = it))
        }
    }

    @Test fun publicHttpValidationRejectsLocalAndCredentials() {
        listOf("http://localhost/a.mp4", "http://a.local/a.mp4", "http://metadata.google.internal/a.mp4",
            "http://127.0.0.1/a.mp4", "http://127.1/a.mp4", "http://2130706433/a.mp4", "http://10.0.0.1/a.mp4",
            "http://169.254.169.254/a.mp4", "http://100.64.1.1/a.mp4", "http://192.168.1.1/a.mp4",
            "http://[::1]/a.mp4", "http://[::ffff:127.0.0.1]/a.mp4", "http://[fc00::1]/a.mp4",
            "https://user:secret@example.org/a.mp4", "http:example.org/a", "https://example.org/a b", "https://example.org/\na").forEach {
            invalid(PlayerRequest(src = it))
        }
        assertNotNull(decode(PlayerRequest(src = "https://8.8.8.8/a.mp4")))
        assertNotNull(decode(PlayerRequest(src = "https://[2606:4700:4700::1111]/a.mp4")))
    }

    @Test fun apiRefererAndRootValidatedIndependently() {
        invalid(PlayerRequest(api = "file:///tmp/api"))
        invalid(PlayerRequest(referer = "javascript:alert(1)"))
        listOf("https://example.org/", "content://files/root", "~/workspace/../secret", "../videos", "", " /tmp/videos").forEach {
            invalid(PlayerRequest(root = it))
        }
        invalid(PlayerRequest(items = listOf(PlayerItem("x", "javascript:alert(1)"))))
        assertEquals("/tmp/videos", decode(PlayerRequest(root = "file:///tmp/videos"))!!.root)
    }

    @Test fun webpagesAndInvalidKindsRejected() {
        listOf("https://example.org/watch.html", "https://example.org/page.htm?x=1", "https://youtu.be/123",
            "https://archive.org/details/item", "https://commons.wikimedia.org/wiki/File:A.webm").forEach { invalid(PlayerRequest(src = it)) }
        listOf("webpage", "stream", "image", "VIDEO").forEach { invalid(PlayerRequest(items = listOf(PlayerItem("x", url, it)))) }
    }

    @Test fun itemAndStringLimitsAreInclusive() {
        val valid = PlayerRequest(title = "x".repeat(512), items = List(2000) { PlayerItem("x", url) })
        assertEquals(2000, PlayerRequestCodec.validate(valid).items.size)
        invalid(valid.copy(items = valid.items + PlayerItem("extra", url)))
        invalid(PlayerRequest(title = "x".repeat(513)))
        invalid(PlayerRequest(query = "x".repeat(513)))
        invalid(PlayerRequest(items = listOf(PlayerItem("x".repeat(513), url))))
        invalid(PlayerRequest(title = "a\nb"))
        val base = "https://example.org/"
        assertNotNull(decode(PlayerRequest(src = base + "x".repeat(8192 - base.length))))
        invalid(PlayerRequest(src = base + "x".repeat(8193 - base.length)))
    }

    @Test fun aggregateEncodedPayloadIsBoundedForCardsAndDecode() {
        val request = PlayerRequest(items = List(2000) { PlayerItem("x".repeat(512), url) })
        assertThrows(IllegalArgumentException::class.java) { PlayerRequestCodec.validate(request) }
        assertNull(decode(request))
        assertEquals(512 * 1024, PlayerRequestCodec.MAX_ENCODED_BYTES)
    }

    @Test fun rawByteLimitAndNestingGuard() {
        val base = "{\"title\":\"x\",\"ignored\":\""
        val raw = base + "x".repeat(PlayerRequestCodec.MAX_RAW_BYTES - base.length - 2) + "\"}"
        assertEquals(PlayerRequestCodec.MAX_RAW_BYTES, raw.toByteArray().size)
        assertNotNull(PlayerRequestCodec.decode(raw))
        assertNull(PlayerRequestCodec.decode(raw + " "))
        assertNull(PlayerRequestCodec.decode("{\"ignored\":\"" + "中".repeat(800000) + "\"}"))
        assertNull(PlayerRequestCodec.decode("{\"ignored\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"))
        assertNotNull(PlayerRequestCodec.decode("{\"title\":\"[\\\"{\"}"))
    }

    @Test fun malformedJsonAndTypeConfusionReturnNull() {
        listOf("null", "[]", "{", "{\"items\":{}}", "{\"src\":42}", "{\"items\":[{\"url\":\"$url\"}]}",
            "{\"mode\":null}", "{\"items\":[null]}").forEach { assertNull(it, PlayerRequestCodec.decode(it)) }
    }
}
