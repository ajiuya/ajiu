package com.aicode.feature.player.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class PlayerHttpTest {
    @Test fun rangesIncludeSuffixOpenEndedAndClamp() {
        assertNull(parseRange(null, 10))
        assertEquals(ByteRange(0, 0), parseRange("bytes=0-0", 10))
        assertEquals(ByteRange(7, 9), parseRange("bytes=-3", 10))
        assertEquals(ByteRange(0, 9), parseRange("bytes=-999", 10))
        assertEquals(ByteRange(4, 9), parseRange("bytes=4-", 10))
        assertEquals(ByteRange(4, 9), parseRange("bytes=4-999", 10))
        assertEquals(ByteRange(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1), parseRange("bytes=-2", Long.MAX_VALUE))
    }

    @Test fun invalidAndUnsatisfiableRangesAre416() {
        for (range in listOf("bytes=10-", "bytes=-0", "bytes=9-4", "bytes=-", "bytes=0-1,3-4", "bytes=999999999999999999999-", "items=0-1")) {
            assertEquals(range, 416, assertThrows(PlayerHttpException::class.java) { parseRange(range, 10) }.status)
        }
        assertEquals(416, assertThrows(PlayerHttpException::class.java) { parseRange("bytes=0-0", 0) }.status)
    }

    @Test fun tokensAreCapabilitiesNotPathPrefixes() {
        val token = sessionToken()
        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, sessionToken())
        assertEquals("embed.html", sessionRoute("/$token/embed.html", token))
        assertEquals("relay", sessionRoute("/$token/relay", token))
        for (path in listOf("/embed.html", "/player/$token/embed.html", "/${token}x/relay", "/$token/../config.js", "/$token/%2e%2e/config.js", "/$token/a%2fb", "/$token//config.js", "/%${token.take(2) + token.drop(2)}/relay")) {
            assertNull(path, sessionRoute(path, token))
        }
        assertEquals("dash/id/chunk-7.m4s", sessionRoute("/$token/dash/id/chunk-7.m4s", token))
    }

    @Test fun parserPreservesRawDashQueryAndRejectsDuplicateInputs() {
        val parsed = PlayerHttpRequest.read(ByteArrayInputStream("GET /token/dash/id/a.m4s?t=10&sig=a%2Bb HTTP/1.1\r\nHost: 127.0.0.1:1234\r\nRange: bytes=2-4\r\n\r\n".toByteArray()))
        assertEquals("t=10&sig=a%2Bb", parsed.rawQuery)
        assertTrue(parsed.query.isEmpty())
        val duplicateDash = PlayerHttpRequest.read(ByteArrayInputStream("GET /token/dash/id/a%2Fb.m4s?x=1&x=2&sig=a%2Bb HTTP/1.1\r\n\r\n".toByteArray()))
        assertEquals("x=1&x=2&sig=a%2Bb", duplicateDash.rawQuery)
        assertEquals("bytes=2-4", parsed.headers["range"])
        assertThrows(PlayerHttpException::class.java) {
            PlayerHttpRequest.read(ByteArrayInputStream("GET /t/relay?u=a&u=b HTTP/1.1\r\n\r\n".toByteArray()))
        }
    }

    @Test fun headSendsHeadersOnlyAnd416CarriesContentRange() {
        val output = ByteArrayOutputStream()
        PlayerHttpResponse(output, true).text(200, "hello")
        assertTrue(output.toString().contains("Content-Length: 5\r\n"))
        assertTrue(output.toString().endsWith("\r\n\r\n"))
        output.reset()
        PlayerHttpResponse(output, false).headers(416, mapOf("Content-Range" to "bytes */10", "Content-Length" to "0"))
        assertTrue(output.toString().startsWith("HTTP/1.1 416"))
        assertTrue(output.toString().contains("Content-Range: bytes */10"))
    }

    @Test fun addressPolicyBlocksPrivateMetadataAndMappedAddresses() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.100.100.200", "0.0.0.0", "224.1.2.3", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "2001:db8::1")) {
            assertFalse(ip, publicAddress(InetAddress.getByName(ip)))
        }
        assertTrue(publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "https://localhost/x", "http://metadata.google.internal/x", "https://a:b@example.com/x")) {
            assertThrows(PlayerHttpException::class.java) { remoteUrl(url) }
        }
    }

    @Test fun resourceCloseIsIdempotentAndRevokesLateRegistration() {
        val resources = PlayerResources()
        var closes = 0
        resources.add(AutoCloseable { closes++ })
        val client = okhttp3.OkHttpClient()
        val call = client.newCall(okhttp3.Request.Builder().url("https://example.com/").build())
        resources.add(call)
        resources.close()
        assertTrue(call.isCanceled())
        resources.close()
        assertEquals(1, closes)
        assertThrows(java.io.IOException::class.java) { resources.checkOpen() }
        assertThrows(java.io.IOException::class.java) { resources.add(AutoCloseable { closes++ }) }
        assertEquals(2, closes)
        val lateCall = client.newCall(okhttp3.Request.Builder().url("https://example.com/").build())
        assertThrows(java.io.IOException::class.java) { resources.add(lateCall) }
        assertTrue(lateCall.isCanceled())
        var sessionCloses = 0
        val session = PlayerSession("http://127.0.0.1:1/token/index.html", "http://127.0.0.1:1") { sessionCloses++ }
        session.close()
        session.close()
        assertEquals(1, sessionCloses)
    }

    @Test fun originPolicyRequiresExactLoopbackHostAndOptionalMatchingOrigin() {
        val origin = "http://127.0.0.1:1234"
        fun request(host: String, from: String? = null) = PlayerHttpRequest("GET", "/token/index.html", emptyMap(),
            buildMap { put("host", host); if (from != null) put("origin", from) })
        requireSessionOrigin(request("127.0.0.1:1234"), origin)
        requireSessionOrigin(request("127.0.0.1:1234", origin), origin)
        listOf(request("localhost:1234"), request("127.0.0.1:5678"), request("127.0.0.1:1234", "https://example.org")).forEach {
            assertEquals(403, assertThrows(PlayerHttpException::class.java) { requireSessionOrigin(it, origin) }.status)
        }
    }
}
