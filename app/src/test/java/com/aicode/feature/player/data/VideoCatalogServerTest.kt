package com.aicode.feature.player.data

import com.aicode.feature.player.domain.PlayerRequest
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoCatalogServerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val detail = buildJsonObject {
        put("source", "fixture"); put("id", "42"); put("title", "Target")
        put("lines", JsonArray(listOf(buildJsonObject {
            put("id", "all"); put("name", "Direct"); put("eps", JsonArray(listOf(buildJsonObject {
                put("n", "One"); put("u", "https://cdn.example/one.mp4"); put("kind", "video")
            })))
        })))
    }

    @Test fun allCatalogEndpointsRequireSessionCapabilityExactOriginAndGet(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        every { fixture.backend.sources() } returns buildJsonObject { put("sources", JsonArray(emptyList())) }
        every { fixture.backend.status() } returns buildJsonObject { put("mode", "network") }
        every { fixture.backend.search("关键词", listOf("first", "custom-source"), 2, 60, any()) } returns buildJsonObject {
            put("items", JsonArray(emptyList())); put("errors", JsonArray(emptyList()))
        }
        every { fixture.backend.detail("custom-source", "42", any()) } returns detail
        fixture.server().createSession(PlayerRequest()).use { session ->
            val prefix = playerSessionPrefix(session)
            listOf("sources", "status", "search?q=${encodeQuery("关键词")}&sources=first,custom-source&page=2", "detail?source=custom-source&id=42").forEach { endpoint ->
                val path = prefix + "api/video/" + endpoint
                val response = playerHttp(session, path, headers = mapOf("Origin" to session.origin))
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("Content-Type: application/json"))
                assertTrue(response.contains("Cache-Control: no-store"))
                assertTrue(playerHttp(session, path, "HEAD").startsWith("HTTP/1.1 405"))
                assertTrue(playerHttp(session, path, "POST").startsWith("HTTP/1.1 405"))
                assertTrue(playerHttp(session, path, headers = mapOf("Origin" to "https://foreign.example")).startsWith("HTTP/1.1 403"))
                assertTrue(playerHttp(session, path, headers = mapOf("Host" to "localhost:1")).startsWith("HTTP/1.1 403"))
                assertTrue(playerHttp(session, path.replace(prefix, "/wrong/")).startsWith("HTTP/1.1 404"))
            }
            assertTrue(playerHttp(session, prefix + "api/video/unknown").startsWith("HTTP/1.1 404"))
        }
        verify(exactly = 1) { fixture.backend.search("关键词", listOf("first", "custom-source"), 2, 60, any()) }
        verify(exactly = 1) { fixture.backend.detail("custom-source", "42", any()) }
    }

    @Test fun invalidParametersAndOversizedResponsesFailBeforeWritingSuccess(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        every { fixture.backend.search(any(), any(), any(), any(), any()) } returns buildJsonObject { put("items", JsonArray(emptyList())) }
        every { fixture.backend.detail(any(), any(), any()) } throws IllegalArgumentException("Unknown configured source")
        every { fixture.backend.status() } returns buildJsonObject { put("large", "x".repeat(4 * 1024 * 1024)) }
        fixture.server().createSession(PlayerRequest()).use { session ->
            val prefix = playerSessionPrefix(session) + "api/video/"
            listOf("search", "search?q=", "search?q=x&page=0", "search?q=x&page=1000001", "search?q=x&limit=201", "search?q=x&limit=-1", "search?q=x&q=y", "search?q=${"x".repeat(513)}", "search?q=x&sources=bad%2Fsource",
                "detail?source=fixture", "detail?source=bad%2Fsource&id=1", "detail?source=fixture&id=${"1".repeat(129)}", "detail?source=unknown&id=1").forEach { route ->
                assertTrue(route, playerHttp(session, prefix + route).startsWith("HTTP/1.1 400"))
            }
            assertTrue(playerHttp(session, prefix + "search?q=${"x".repeat(512)}").startsWith("HTTP/1.1 200"))
            assertTrue(playerHttp(session, prefix + "search?q=x", headers = mapOf("Content-Length" to "1")).startsWith("HTTP/1.1 400"))
            val oversized = playerHttp(session, prefix + "status")
            assertTrue(oversized.startsWith("HTTP/1.1 502"))
            assertTrue(oversized.length < 1024)
        }
        verify(exactly = 1) { fixture.backend.search(any(), any(), any(), any(), any()) }
    }

    @Test fun exactVideoRequestUsesOnlyConfiguredSourceAndId(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        every { fixture.backend.detail("fixture", "42", any()) } returns detail
        fixture.server().createSession(PlayerRequest(mode = "series", query = "same title", videoSources = listOf("fixture"), videoId = "42")).use { session ->
            val prefix = playerSessionPrefix(session)
            val response = playerHttp(session, prefix + "api/catalog")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            val data = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
            assertEquals(detail, data)
            assertFalse(data.toString().contains(prefix))
        }
        verify(exactly = 1) { fixture.backend.detail("fixture", "42", any()) }
        verify(exactly = 0) { fixture.backend.search(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.backend.apiSeries(any(), any(), any(), any()) }
    }

    @Test fun blockedSearchLeavesControlsResponsiveAndPeerAbortClosesItsResources(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val fixture = PlayerServerFixture(temporary.root)
        every { fixture.backend.search(any(), any(), any(), any(), any()) } answers {
            val resources = arg<PlayerResources>(4)
            resources.add(AutoCloseable { cancelled.countDown() })
            entered.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS))
            resources.checkOpen()
            buildJsonObject {}
        }
        fixture.server().createSession(PlayerRequest()).use { session ->
            val uri = URI(session.url)
            val prefix = playerSessionPrefix(session)
            Socket(uri.host, uri.port).use { socket ->
                socket.getOutputStream().write("GET ${prefix}api/video/search?q=slow HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n".toByteArray())
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val config = playerHttp(session, prefix + "config.js")
                assertTrue(config.startsWith("HTTP/1.1 200"))
                assertTrue(playerHttp(session, prefix + "relay?u=bad").startsWith("HTTP/1.1 400"))
                val epoch = Regex("window\\.__AJIU_STATE_EPOCH__=([0-9]+);").find(config)!!.groupValues[1].toLong()
                val body = playerStatePayload("ajiu_player_v3:${fixture.namespace}:control", "42", epoch)
                assertTrue(playerHttp(session, prefix + "api/state", "POST", mapOf("Content-Length" to body.size.toString()), body).startsWith("HTTP/1.1 204"))
                socket.close()
                assertTrue("Peer EOF must close the active catalog request", cancelled.await(3, TimeUnit.SECONDS))
            }
        }
    }
}
