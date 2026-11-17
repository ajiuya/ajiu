package com.aicode.feature.player.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.GZIPOutputStream

class PlayerRelayTest {
    private fun upstream(handler: (Request) -> Response): PlayerUpstream = PlayerUpstream(
        OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .addInterceptor { handler(it.request()) }.build(),
        object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8")) }
    )

    private fun response(request: Request, code: Int, body: ByteArray = "body".toByteArray(), headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(code)
            .body(body.toResponseBody()).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    private fun relay(upstream: PlayerUpstream, url: String = "https://cdn.example/movie.mp4", method: String = "GET", headers: Map<String, String> = emptyMap()): ByteArray {
        val output = ByteArrayOutputStream()
        PlayerResources().use { resources ->
            PlayerRelay(upstream).forward(PlayerHttpRequest(method, "/T/relay", emptyMap(), headers), PlayerHttpResponse(output, method == "HEAD"),
                resources, "/T/", DashMappings("/T/"), url, "https://watch.example/show")
        }
        return output.toByteArray()
    }

    @Test fun upstreamStatusesAreNotTurnedIntoSuccessfulMedia() {
        for (code in listOf(206, 304, 401, 403, 404, 416, 429, 500, 503)) {
            val raw = relay(upstream { response(it, code, headers = mapOf("Content-Range" to "bytes */10")) }).toString(Charsets.UTF_8)
            assertTrue(raw, raw.startsWith("HTTP/1.1 $code "))
            assertTrue(raw.contains("Content-Range: bytes */10", true))
            if (code == 304) assertTrue(raw.endsWith("\r\n\r\n"))
        }
    }

    @Test fun redirectPlaylistUsesFinalUrlAndOriginalRefOnEveryHop() {
        val requests = mutableListOf<Request>()
        val raw = relay(upstream { request ->
            requests += request
            if (request.url.encodedPath == "/first.m3u8") response(request, 302, headers = mapOf("Location" to "https://cdn.example/new/master.m3u8"))
            else response(request, 200, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg.ts\n".toByteArray(), mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
        }, "https://source.example/first.m3u8").toString(Charsets.UTF_8)
        assertEquals(2, requests.size)
        assertEquals("https://cdn.example/new/master.m3u8", requests.last().url.toString())
        assertTrue(raw.contains("X-Ajiu-Manifest-Rewritten: hls"))
        assertTrue(requests.all { it.header("Referer") == "https://watch.example/show" })
        assertTrue(raw.contains(relayUrl("/T/", "https://cdn.example/new/seg.ts", "https://watch.example/show")))
        assertTrue(raw.contains(relayUrl("/T/", "https://cdn.example/new/key.bin", "https://watch.example/show")))
    }

    @Test fun redirectToMetadataIsRejectedBeforeSecondCall() {
        var requests = 0
        val upstream = upstream {
            requests++
            response(it, 302, headers = mapOf("Location" to "http://169.254.169.254/latest/meta-data/"))
        }
        assertEquals(403, assertThrows(PlayerHttpException::class.java) { relay(upstream) }.status)
        assertEquals(1, requests)
    }

    @Test fun rangeAndEncodingPassThroughWithoutFullBuffering() {
        val bytes = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("range entity".toByteArray()) } }.toByteArray()
        var seen: Request? = null
        val output = relay(upstream {
            seen = it
            response(it, 206, bytes, mapOf("Content-Type" to "video/mp4", "Content-Encoding" to "gzip",
                "Content-Length" to bytes.size.toString(), "Content-Range" to "bytes 2-4/10", "Accept-Ranges" to "bytes"))
        }, headers = mapOf("range" to "bytes=2-4", "accept-encoding" to "gzip"))
        assertEquals("bytes=2-4", seen!!.header("Range"))
        assertEquals("gzip", seen!!.header("Accept-Encoding"))
        val boundary = output.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertArrayEquals(bytes, output.copyOfRange(boundary, output.size))
        assertTrue(output.toString(Charsets.ISO_8859_1).contains("content-encoding: gzip", true))
    }

    @Test fun gzipManifestIsDecodedRewrittenAndLengthRecalculated() {
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("#EXTM3U\nseg.ts\n".toByteArray()) } }.toByteArray()
        val output = relay(upstream { response(it, 200, compressed, mapOf("Content-Type" to "application/x-mpegURL", "Content-Encoding" to "gzip", "Content-Length" to compressed.size.toString())) }, "https://cdn.example/m.m3u8")
        val text = output.toString(Charsets.UTF_8)
        assertFalse(text.contains("Content-Encoding", true))
        assertTrue(text.contains("/T/relay?u="))
        val body = text.substringAfter("\r\n\r\n").toByteArray()
        assertTrue(text.contains("Content-Length: ${body.size}"))
    }

    @Test fun rewrittenDashCarriesMarkerAndExposesSubstitutableTemplate() {
        val xml = """<MPD><Period><AdaptationSet><Representation><SegmentTemplate media="s-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet></Period></MPD>"""
        val text = relay(upstream { response(it, 200, xml.toByteArray(), mapOf("Content-Type" to "application/dash+xml")) }, "https://cdn.example/a.mpd").toString(Charsets.UTF_8)
        assertTrue(text.contains("X-Ajiu-Manifest-Rewritten: dash"))
        assertTrue(text.contains("/T/dash/"))
        assertTrue(text.contains("${'$'}Number${'$'}"))
        assertFalse(text.contains("media=\"https://"))
    }

    @Test fun relayDisablesTotalCallTimeoutWhileApiRetainsItsBudget() {
        val budgets = mutableListOf<Long>()
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).addInterceptor {
                budgets += it.call().timeout().timeoutNanos()
                response(it.request(), 200)
            }.build()
        val upstream = PlayerUpstream(client, object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
        })
        relay(upstream)
        PlayerResources().use { resources -> upstream.open(resources, "https://api.example/catalog").use { } }
        assertEquals(listOf(0L, java.util.concurrent.TimeUnit.SECONDS.toNanos(120)), budgets)
        assertEquals(15000, client.connectTimeoutMillis)
        assertEquals(30000, client.readTimeoutMillis)
    }

    @Test fun headPreservesStatusHeadersAndOmitsBody() {
        var method = ""
        val text = relay(upstream { method = it.method; response(it, 206, headers = mapOf("Content-Length" to "123", "Content-Range" to "bytes 0-122/500")) }, method = "HEAD").toString(Charsets.UTF_8)
        assertEquals("HEAD", method)
        assertTrue(text.startsWith("HTTP/1.1 206"))
        assertTrue(text.contains("Content-Length: 123", true))
        assertTrue(text.endsWith("\r\n\r\n"))
    }
}
