package com.aicode.feature.player.data

import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoCatalogRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun spec(id: String) = VideoCatalogSource(id, id.uppercase(), "https://catalog.example/$id?token=keep", 3)
    private fun response(request: Request, code: Int = 200, payload: ByteArray = "{}".toByteArray(), headers: Map<String, String> = emptyMap()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(payload.toResponseBody()).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
    private fun upstream(handler: (Request) -> Response) = PlayerUpstream(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
        .addInterceptor { handler(it.request()) }.build(), object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
    })
    private fun payload(entries: List<JsonObject>, total: Int = entries.size, pages: Int = 1) = buildJsonObject {
        put("code", 1); put("total", total); put("pagecount", pages); put("list", JsonArray(entries))
    }.toString().toByteArray()
    private fun entry(id: String, name: String, episodes: String = "One${'$'}https://cdn.example/a.mp4") = buildJsonObject {
        put("vod_id", id); put("vod_name", name); put("vod_play_from", "direct"); put("vod_play_url", episodes)
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun privateConfigurationControlsSourcesTimingAndNetworkStatus() {
        File(temporary.root, "sources.json").writeText("""{"deadline":0.2,"grace":0.05,"sources":[{"id":"custom-source","name":"Custom","api":"https://catalog.example/api","timeout":4,"search":false,"enabled":true,"referer":"https://watch.example/"}]}""")
        val repo = VideoCatalogRepository({ temporary.root }, upstream { error("Configuration must not query an upstream") })
        val sources = repo.sources()
        assertEquals(0.2, sources.getValue("deadline").jsonPrimitive.double, 0.001)
        assertEquals(0.05, sources.getValue("grace").jsonPrimitive.double, 0.001)
        val source = sources.getValue("sources").jsonArray.single().jsonObject
        assertEquals("custom-source", source.text("id"))
        assertEquals("Custom", source.text("name"))
        assertEquals("https://watch.example/", source.text("referer"))
        assertFalse(source.getValue("search").jsonPrimitive.boolean)
        assertEquals(4, source.getValue("timeout").jsonPrimitive.int)
        assertEquals("network", repo.status().text("mode"))
        assertEquals(sources["sources"], repo.status()["sources"])
    }

    @Test fun onlineSearchAggregatesSourcesRetainsErrorsAndFollowsRedirects() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val repo = VideoCatalogRepository({ temporary.root }, upstream { request ->
            requests += request
            when (request.url.encodedPath) {
                "/first" -> response(request, 302, headers = mapOf("Location" to "https://catalog.example/final"))
                "/final", "/second" -> response(request, payload = payload(listOf(entry("11", "中文剧集"))))
                "/failed" -> response(request, 503)
                else -> error("Unexpected path ${request.url}")
            }
        }, sourceOverrides = listOf(spec("first"), spec("failed"), spec("second")))
        PlayerResources().use { resources ->
            val result = repo.search("中文剧集", resources = resources)
            assertEquals("network", result.text("mode"))
            val items = result.getValue("items").jsonArray
            assertEquals(2, items.size)
            assertEquals(setOf("first", "second"), items.map { it.jsonObject.text("source") }.toSet())
            assertTrue(items.all { "eps" !in it.jsonObject && "url" !in it.jsonObject && it.jsonObject.getValue("metadataOnly").jsonPrimitive.boolean })
            val error = result.getValue("errors").jsonArray.single().jsonObject
            assertEquals("failed", error.text("source"))
            assertEquals("http_503", error.text("code"))
            assertEquals(3, result.getValue("sourceResults").jsonArray.size)
        }
        assertEquals(4, requests.size)
        assertTrue(requests.filter { it.url.encodedPath != "/final" }.all { it.url.queryParameter("wd") == "中文剧集" && it.url.queryParameter("ac") == "detail" })
    }

    @Test fun globalPaginationRetainsSurplusItemsAcrossSourcesAndCachesTailPages() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val repo = VideoCatalogRepository({ temporary.root }, upstream { request ->
            requests += request
            val first = request.url.encodedPath == "/first"
            val ids = if (first) (1..5).map { "f$it" } else (1..2).map { "s$it" }
            val page = request.url.queryParameter("pg")!!.toInt()
            val size = if (first) 3 else 2
            response(request, payload = payload(ids.drop((page - 1) * size).take(size).map { entry(it, "Page $it") }, ids.size, (ids.size + size - 1) / size))
        }, sourceOverrides = listOf(spec("first"), spec("second")))
        PlayerResources().use { resources ->
            val identities = mutableListOf<Pair<String, String>>()
            for (page in 1..4) {
                val result = repo.search("Page", page = page, limit = 2, resources = resources)
                assertEquals(7L, result.getValue("total").jsonPrimitive.long)
                assertEquals(page < 4, result.getValue("hasMore").jsonPrimitive.boolean)
                identities += result.getValue("items").jsonArray.map { it.jsonObject.text("source") to it.jsonObject.text("id") }
            }
            assertEquals(7, identities.size)
            assertEquals(7, identities.distinct().size)
            assertEquals((1..5).map { "first" to "f$it" }.toSet() + (1..2).map { "second" to "s$it" }.toSet(), identities.toSet())
            assertTrue(repo.search("Page", page = 5, limit = 2, resources = resources).getValue("items").jsonArray.isEmpty())
            val repeated = repo.search("Page", limit = 2, resources = resources).getValue("items").jsonArray
            assertEquals(identities.take(2), repeated.map { it.jsonObject.text("source") to it.jsonObject.text("id") })
        }
        assertEquals(3, requests.size)
    }

    @Test fun detailUsesExactSourceIdAndPreservesAllLinesAndTwoThousandEpisodes() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val episodes = (1..2000).joinToString("#") { "Episode $it${'$'}https://cdn.example/$it.m3u8" }
        val work = buildJsonObject {
            put("vod_id", "42"); put("vod_name", "Target"); put("vod_play_from", "mp4${'$'}${'$'}${'$'}m3u8")
            put("vod_play_url", "One${'$'}https://cdn.example/one.mp4${'$'}${'$'}${'$'}$episodes")
            put("vod_play_referer", "https://watch.example/show")
        }
        val repo = VideoCatalogRepository({ temporary.root }, upstream { request ->
            requests += request
            response(request, payload = payload(listOf(work)))
        }, sourceOverrides = listOf(spec("fixture")))
        PlayerResources().use { resources ->
            val result = repo.detail("fixture", "42", resources)
            assertEquals("fixture", result.text("source"))
            assertEquals("42", result.text("id"))
            assertEquals("Target", result.text("title"))
            assertEquals(2, result.getValue("lines").jsonArray.size)
            assertEquals(1, result.getValue("defaultLine").jsonPrimitive.int)
            assertEquals(2000, result.getValue("eps").jsonArray.size)
            assertEquals("https://cdn.example/2000.m3u8", result.getValue("eps").jsonArray.last().jsonObject.text("u"))
            assertEquals("https://watch.example/show", result.text("referer"))
        }
        val request = requests.single()
        assertEquals("42", request.url.queryParameter("ids"))
        assertNull(request.url.queryParameter("wd"))
        assertNull(request.url.queryParameter("pg"))
        assertEquals("keep", request.url.queryParameter("token"))
    }

    @Test fun detailNeverSubstitutesAnotherWorkWithTheSameTitle() {
        val repo = VideoCatalogRepository({ temporary.root }, upstream { response(it, payload = payload(listOf(entry("different", "Target")))) },
            sourceOverrides = listOf(spec("fixture")))
        PlayerResources().use { resources ->
            val result = repo.detail("fixture", "42", resources)
            assertEquals("42", result.text("id"))
            assertTrue(result.getValue("eps").jsonArray.isEmpty())
            assertEquals("id_not_found", result.getValue("errors").jsonArray.single().jsonObject.text("code"))
        }
    }

    @Test fun apiSeriesSelectsExactTitleAndDirectM3u8LineWithoutDroppingApiParameters() {
        var requested: Request? = null
        val target = buildJsonObject {
            put("vod_id", "2"); put("vod_name", "Target"); put("vod_play_from", "mp4${'$'}${'$'}${'$'}m3u8")
            put("vod_play_url", "one${'$'}https://cdn.example/a.mp4${'$'}${'$'}${'$'}episode1${'$'}https://cdn.example/a.m3u8#episode2${'$'}https://cdn.example/b.m3u8")
        }
        val repo = VideoCatalogRepository({ temporary.root }, upstream {
            requested = it
            response(it, payload = payload(listOf(entry("1", "Other"), target)))
        }, sourceOverrides = emptyList())
        PlayerResources().use { resources ->
            val result = repo.apiSeries("https://catalog.example/api.php?token=keep", "Target", "https://watch.example/", resources)
            assertEquals("Target", result.text("title"))
            assertEquals("2", result.text("id"))
            assertEquals(2, result.getValue("eps").jsonArray.size)
            assertEquals("https://cdn.example/a.m3u8", result.getValue("eps").jsonArray[0].jsonObject.text("u"))
        }
        assertEquals("keep", requested!!.url.queryParameter("token"))
        assertEquals("detail", requested!!.url.queryParameter("ac"))
        assertEquals("Target", requested!!.url.queryParameter("wd"))
        assertEquals("https://watch.example/", requested!!.header("Referer"))
    }

    @Test fun charsetFallbackAndBomPreserveChineseTitles() {
        for ((charset, bom) in listOf("GBK" to false, "GB18030" to false, "UTF-8" to true, "UTF-16LE" to true)) {
            val raw = payload(listOf(entry("1", "中文剧集"))).toString(Charsets.UTF_8).toByteArray(java.nio.charset.Charset.forName(charset))
            val prefix = if (!bom) byteArrayOf() else if (charset == "UTF-8") byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) else byteArrayOf(0xff.toByte(), 0xfe.toByte())
            val repo = VideoCatalogRepository({ temporary.root }, upstream { response(it, payload = prefix + raw) }, sourceOverrides = listOf(spec("fixture")))
            PlayerResources().use { resources -> assertEquals(charset, "中文剧集", repo.detail("fixture", "1", resources).text("title")) }
        }
    }

    @Test fun invalidQueriesIdsSourcesAndClosedResourcesNeverContactAnUpstream() {
        var calls = 0
        val repo = VideoCatalogRepository({ temporary.root }, upstream { calls++; error("Unexpected request") }, sourceOverrides = listOf(spec("fixture")))
        PlayerResources().use { resources ->
            listOf("", " ", "x".repeat(513), "a\nb").forEach { query -> assertThrows(IllegalArgumentException::class.java) { repo.search(query, resources = resources) } }
            listOf("", " ", "a,b", "x".repeat(129), "bad\nidentity").forEach { id -> assertThrows(IllegalArgumentException::class.java) { repo.detail("fixture", id, resources) } }
            assertThrows(IllegalArgumentException::class.java) { repo.search("valid", listOf("unknown"), resources = resources) }
            assertThrows(IllegalArgumentException::class.java) { repo.search("valid", page = 0, resources = resources) }
            assertThrows(IllegalArgumentException::class.java) { repo.search("valid", limit = 201, resources = resources) }
            resources.close()
            assertThrows(IOException::class.java) { repo.search("valid", resources = resources) }
        }
        assertEquals(0, calls)
    }

    @Test fun disabledKeywordSourcesReportFailureWithoutNetworkCalls() {
        var calls = 0
        val repo = VideoCatalogRepository({ temporary.root }, upstream { calls++; error("Unexpected request") },
            sourceOverrides = listOf(spec("fixture").copy(search = false)))
        PlayerResources().use { resources ->
            val result = repo.search("Target", resources = resources)
            assertTrue(result.getValue("items").jsonArray.isEmpty())
            assertEquals("keyword_search_disabled", result.getValue("errors").jsonArray.single().jsonObject.text("code"))
        }
        assertEquals(0, calls)
    }

    @Test fun closingParentResourcesCancelsOwnedUpstreamCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).addInterceptor {
            call = it.call()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(it.request(), payload = payload(listOf(entry("1", "Target"))))
        }.build()
        val repo = VideoCatalogRepository({ temporary.root }, PlayerUpstream(client, object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
        }), sourceOverrides = listOf(spec("fixture")))
        val resources = PlayerResources()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<JsonObject> { repo.search("Target", resources = resources) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            resources.close()
            assertTrue(call!!.isCanceled())
            assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
        } finally { resources.close(); release.countDown(); pool.shutdownNow() }
    }
}
