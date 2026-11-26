package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.player.domain.PlayerItem
import com.aicode.feature.player.domain.PlayerRequest
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class PlayerServerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun networkSessionsUseDistinctCapabilitiesWithoutAcquiringWorkspaceFiles(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        val server = fixture.server()
        server.createSession(PlayerRequest(mode = "embed", src = "https://cdn.example/a.mp4", title = "Track")).use { first ->
            server.createSession(PlayerRequest()).use { second ->
                assertTrue(first.url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/[0-9a-f]{64}/embed\\.html")))
                assertNotEquals(first.origin, second.origin)
                val prefix = playerSessionPrefix(first)
                val config = playerHttp(first, prefix + "config.js")
                assertTrue(config.startsWith("HTTP/1.1 200"))
                assertTrue(config.contains("window.__AJIU_CONFIG__="))
                assertTrue(config.contains("\"title\":\"Track\""))
                assertTrue(config.contains("window.__AJIU_STATE_NAMESPACE__=\"${fixture.namespace}\""))
                assertTrue(playerHttp(first, "/wrong/config.js").startsWith("HTTP/1.1 404"))
                assertTrue(playerHttp(first, prefix + "config.js", headers = mapOf("Origin" to "https://foreign.example")).startsWith("HTTP/1.1 403"))
                assertTrue(playerHttp(first, prefix + "config.js", headers = mapOf("Host" to "localhost:1")).startsWith("HTTP/1.1 403"))
                assertTrue(playerHttp(first, prefix + "api/library").startsWith("HTTP/1.1 503"))
            }
        }
        coVerify(exactly = 0) { fixture.executionFactory.freeze(any()) }
    }

    @Test fun httpMediaAndNetworkCatalogRequestsNeverCaptureAnUnauthorizedActiveChroot(): Unit = runBlocking {
        val f = DeniedChrootWorkspace()
        val catalog = buildJsonObject { put("eps", JsonArray(emptyList())) }
        every { f.base.backend.apiSeries(any(), any(), any(), any()) } returns catalog
        every { f.base.backend.detail("fixture", "42", any()) } returns catalog
        val assets = mockk<android.content.res.AssetManager>()
        every { f.base.context.assets } returns assets
        every { assets.open(any()) } answers { "network player asset".byteInputStream() }
        val requests = listOf(
            PlayerRequest(mode = "embed", src = "HTTPS://cdn.example/a.mp4"),
            PlayerRequest(mode = "series", items = listOf(PlayerItem("One", "https://cdn.example/one.mp4"),
                PlayerItem("Two", "http://cdn.example/two.mp4"))),
            PlayerRequest(mode = "series", api = "https://catalog.example/api", query = "title"),
            PlayerRequest(mode = "series", videoSources = listOf("fixture"), videoId = "42"),
        )
        val server = f.server()
        for (request in requests) {
            server.createSession(request, f.owner).use { session ->
                val prefix = playerSessionPrefix(session)
                assertTrue(playerHttp(session, URI(session.url).path).startsWith("HTTP/1.1 200"))
                val config = playerHttp(session, prefix + "config.js")
                assertTrue(config.startsWith("HTTP/1.1 200"))
                assertTrue(config.contains("window.__AJIU_STATE_NAMESPACE__=\"${stateNamespaceHash("network")}\""))
                assertTrue(playerHttp(session, prefix + "api/catalog").startsWith("HTTP/1.1 200"))
                assertTrue(playerHttp(session, prefix + "api/library").startsWith("HTTP/1.1 503"))
                assertTrue(playerHttp(session, prefix + "local?path=" + encodeQuery("~/workspace/a.mp4")).startsWith("HTTP/1.1 503"))
                assertTrue(playerHttp(session, prefix + "files/tilde/workspace/a.mp4").startsWith("HTTP/1.1 503"))
            }
        }
        coVerify(exactly = 0) { f.factory.freezeForPaths(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.runtime.capture(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.base.identityResolver.capture(any(), any(), any()) }
        verify(exactly = 0) { f.runtime.fileAccess(any()) }
        verify(exactly = 0) { f.local.captureWorkspaceSource() }
    }

    @Test fun explicitLocalAndMixedMediaCannotCreateANetworkSessionAfterChrootAuthorizationFailure(): Unit = runBlocking {
        val f = DeniedChrootWorkspace()
        val server = f.server()
        val requests = listOf(
            PlayerRequest(mode = "embed", src = "~/workspace/a.mp4"),
            PlayerRequest(mode = "series", items = listOf(PlayerItem("Local", "~/workspace/a.mp4"))),
            PlayerRequest(mode = "series", src = "https://cdn.example/a.mp4",
                items = listOf(PlayerItem("Local", "~/workspace/a.mp4"))),
            PlayerRequest(mode = "series", src = "~/workspace/a.mp4",
                items = listOf(PlayerItem("Network", "https://cdn.example/a.mp4"))),
            PlayerRequest(mode = "series", api = "https://catalog.example/api", query = "title",
                items = listOf(PlayerItem("Local", "~/workspace/a.mp4"))),
        )
        for (request in requests) {
            assertPropagated(f.failure, runCatching { server.createSession(request, f.owner) }.exceptionOrNull())
            assertTrue(activeSessions(server).isEmpty())
        }
        coVerify(exactly = requests.size) { f.runtime.capture(any(), any(), any(), f.owner, any(), any()) }
        verify(exactly = 0) { f.runtime.fileAccess(any()) }
        verify(exactly = 0) { f.base.context.getSharedPreferences(any(), any()) }
        assertTrue(temporary.root.listFiles().isNullOrEmpty())
    }

    @Test fun emptyIndexKeepsNetworkUiWhenOnlyItsOptionalFileCapabilityThrowsIOException(): Unit = runBlocking {
        val f = DeniedChrootWorkspace()
        val server = f.server()
        for (failure in listOf(LocalRuntimeAuthorizationRequired(), IOException("Workspace unavailable"))) {
            f.failure = failure
            server.createSession(PlayerRequest(), f.owner).use { session ->
                val prefix = playerSessionPrefix(session)
                assertTrue(playerHttp(session, prefix + "config.js").startsWith("HTTP/1.1 200"))
                assertTrue(playerHttp(session, prefix + "api/library").startsWith("HTTP/1.1 503"))
            }
            assertTrue(activeSessions(server).isEmpty())
        }
        coVerify(exactly = 2) { f.runtime.capture(any(), any(), any(), f.owner, any(), any()) }
        verify(exactly = 0) { f.runtime.fileAccess(any()) }
    }

    @Test fun optionalIndexDoesNotSwallowCancellationSecurityOrAssertionFailures(): Unit = runBlocking {
        val f = DeniedChrootWorkspace()
        val server = f.server()
        for (failure in listOf(CancellationException("Source changed"), SecurityException("Denied"), AssertionError("Invariant"))) {
            f.failure = failure
            assertPropagated(failure, runCatching { server.createSession(PlayerRequest(), f.owner) }.exceptionOrNull())
            assertTrue(activeSessions(server).isEmpty())
        }
        verify(exactly = 0) { f.base.context.getSharedPreferences(any(), any()) }
        verify(exactly = 0) { f.runtime.fileAccess(any()) }
    }

    @Test fun networkOnlyCreationStillChecksItsUiSourceAndClosesARejectedSession(): Unit = runBlocking {
        val f = DeniedChrootWorkspace()
        val server = f.server()
        for (rejectAt in listOf(1, 2, 4)) {
            var checks = 0
            var closed = false
            val failure = CancellationException("UI source closed")
            val result = runCatching {
                server.createSession(PlayerRequest(mode = "embed", src = "https://cdn.example/a.mp4"), f.owner) {
                    checks++
                    if (checks == rejectAt) {
                        activeSessions(server).singleOrNull()?.onClosed { closed = true }
                        throw failure
                    }
                }
            }
            assertPropagated(failure, result.exceptionOrNull())
            assertEquals(rejectAt, checks)
            assertTrue(activeSessions(server).isEmpty())
            if (rejectAt == 4) assertTrue(closed)
        }
        coVerify(exactly = 0) { f.factory.freezeForPaths(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.runtime.capture(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun requestItemsAreReturnedByCatalogWithoutQueryingConfiguredSites(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        val item = PlayerItem("Song", "https://cdn.example/song.mp3", "audio")
        fixture.server().createSession(PlayerRequest(mode = "series", title = "Queue", items = listOf(item))).use { session ->
            val response = playerHttp(session, playerSessionPrefix(session) + "api/catalog")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            val catalog = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
            assertEquals("Queue", catalog.getValue("title").jsonPrimitive.content)
            val episode = catalog.getValue("eps").jsonArray.single().jsonObject
            assertEquals(item.url, episode.getValue("u").jsonPrimitive.content)
            assertEquals("audio", episode.getValue("kind").jsonPrimitive.content)
        }
        verify(exactly = 0) { fixture.backend.search(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.backend.detail(any(), any(), any()) }
        verify(exactly = 0) { fixture.backend.apiSeries(any(), any(), any(), any()) }
    }

    @Test fun localRangeSuffix416AndHeadUseFrozenStreamingRoute(): Unit = runBlocking {
        var opens = 0
        val access = playerFileAccess(entries = { listOf(FileEntry("a.mp4", false, 10, 1)) }, stream = { opens++; "0123456789".byteInputStream() })
        val fixture = PlayerServerFixture(temporary.root, access)
        fixture.server().createSession(PlayerRequest()).use { session ->
            val prefix = playerSessionPrefix(session)
            assertTrue(playerHttp(session, prefix + "config.js").contains("window.__AJIU_STATE_NAMESPACE__=\"${fixture.namespace}\""))
            val route = prefix + "local?path=" + encodeQuery("~/workspace/a.mp4")
            val head = playerHttp(session, route, "HEAD")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("Content-Length: 10"))
            assertEquals("", head.substringAfter("\r\n\r\n"))
            assertEquals(0, opens)
            val invalid = playerHttp(session, route, headers = mapOf("Range" to "bytes=10-"))
            assertTrue(invalid.startsWith("HTTP/1.1 416"))
            assertTrue(invalid.contains("Content-Range: bytes */10"))
            assertEquals(0, opens)
            val partial = playerHttp(session, route, headers = mapOf("Range" to "bytes=2-4"))
            assertTrue(partial.startsWith("HTTP/1.1 206"))
            assertEquals("234", partial.substringAfter("\r\n\r\n"))
            assertTrue(partial.contains("Content-Range: bytes 2-4/10"))
            assertEquals("789", playerHttp(session, route, headers = mapOf("Range" to "bytes=-3")).substringAfter("\r\n\r\n"))
            assertEquals("0123456789", playerHttp(session, route).substringAfter("\r\n\r\n"))
            assertEquals(1, opens)
            assertTrue(playerHttp(session, prefix + "local?path=" + encodeQuery("~/workspace/../secret")).startsWith("HTTP/1.1 403"))
        }
        assertTrue(temporary.root.listFiles().orEmpty().none { it.extension == "copy" })
    }

    @Test fun localHlsRelativeChildrenKeysAndSegmentsStayUnderSessionRoot(): Unit = runBlocking {
        val contents = mapOf(
            "~/workspace/media/master.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild/list.m3u8\n",
            "~/workspace/media/child/list.m3u8" to "#EXTM3U\n../segment.ts\n",
            "~/workspace/media/key.bin" to "keybytes",
            "~/workspace/media/segment.ts" to "segment bytes"
        )
        val tree = mapOf("~/workspace" to listOf("media"), "~/workspace/media" to listOf("master.m3u8", "key.bin", "segment.ts", "child"),
            "~/workspace/media/child" to listOf("list.m3u8"))
        val access = playerFileAccess(entries = { directory -> tree[directory].orEmpty().map { name ->
            val path = "$directory/$name"
            FileEntry(name, path !in contents, contents[path]?.toByteArray()?.size?.toLong() ?: 0, 1)
        } }, stream = { contents.getValue(it).byteInputStream() })
        val fixture = PlayerServerFixture(temporary.root, access)
        fixture.server().createSession(PlayerRequest(root = "~/workspace/media")).use { session ->
            val prefix = playerSessionPrefix(session)
            val master = playerHttp(session, prefix + "local?path=" + encodeQuery("~/workspace/media/master.m3u8"))
            assertTrue(master.startsWith("HTTP/1.1 200"))
            val child = prefix + "files/tilde/workspace/media/child/list.m3u8"
            val key = prefix + "files/tilde/workspace/media/key.bin"
            assertTrue(master.contains(child))
            assertTrue(master.contains("URI=\"$key\""))
            assertEquals("keybytes", playerHttp(session, key).substringAfter("\r\n\r\n"))
            val playlist = playerHttp(session, child)
            val segment = prefix + "files/tilde/workspace/media/segment.ts"
            assertTrue(playlist.contains(segment))
            assertEquals("segment bytes", playerHttp(session, segment).substringAfter("\r\n\r\n"))
            assertTrue(playerHttp(session, prefix + "files/tilde/workspace/secret.ts").startsWith("HTTP/1.1 403"))
            assertTrue(playerHttp(session, prefix + "files/tilde/workspace/media%2F..%2Fsecret.ts").startsWith("HTTP/1.1 404"))
        }
    }

    @Test fun localDashFormattedTemplateReachesTheSubstitutedFileRoute(): Unit = runBlocking {
        val xml = """<MPD><Period><AdaptationSet><Representation><SegmentTemplate media="chunk-${'$'}Number%05d${'$'}.m4s" initialization="init.mp4"/></Representation></AdaptationSet></Period></MPD>"""
        val contents = mapOf("main.mpd" to xml, "chunk-00007.m4s" to "chunk bytes", "init.mp4" to "init bytes")
        val access = playerFileAccess(entries = { contents.map { (name, value) -> FileEntry(name, false, value.toByteArray().size.toLong(), 1) } },
            stream = { contents.getValue(it.substringAfterLast('/')).byteInputStream() })
        val fixture = PlayerServerFixture(temporary.root, access)
        fixture.server().createSession(PlayerRequest()).use { session ->
            val prefix = playerSessionPrefix(session)
            val response = playerHttp(session, prefix + "local?path=" + encodeQuery("~/workspace/main.mpd"))
            assertTrue(response.startsWith("HTTP/1.1 200"))
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(response.substringAfter("\r\n\r\n").byteInputStream())
            val template = doc.getElementsByTagName("SegmentTemplate").item(0) as Element
            val media = template.getAttribute("media")
            assertTrue(media.contains("${'$'}Number%05d${'$'}"))
            assertEquals("chunk bytes", playerHttp(session, media.replace("${'$'}Number%05d${'$'}", "00007")).substringAfter("\r\n\r\n"))
            assertEquals("init bytes", playerHttp(session, template.getAttribute("initialization")).substringAfter("\r\n\r\n"))
        }
    }

    @Test fun localDashRemoteBaseUsesTheSameMappingAsTheLiveRelayRoute(): Unit = runBlocking {
        val xml = """<MPD><BaseURL>https://cdn.example/media/</BaseURL><Period><AdaptationSet><Representation><SegmentTemplate media="chunk-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet></Period></MPD>"""
        val access = playerFileAccess(entries = { listOf(FileEntry("main.mpd", false, xml.toByteArray().size.toLong(), 1)) }, stream = { xml.byteInputStream() })
        val fixture = PlayerServerFixture(temporary.root, access)
        mockkConstructor(PlayerUpstream::class)
        try {
            val requests = mutableListOf<Pair<String, String>>()
            every { anyConstructed<PlayerUpstream>().open(any(), any(), any(), any(), any(), any()) } answers {
                val resources = arg<PlayerResources>(0)
                val url = arg<String>(1)
                val referer = arg<String>(3)
                requests += url to referer
                val request = okhttp3.Request.Builder().url(url).build()
                val body = "remote chunk".toByteArray()
                val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                    .header("Content-Type", "video/iso.segment").header("Content-Length", body.size.toString())
                    .body(body.toResponseBody()).build()
                val call = mockk<okhttp3.Call>(relaxed = true)
                resources.add(call)
                PlayerExchange(response, call, resources)
            }
            fixture.server().createSession(PlayerRequest(referer = "https://watch.example/")).use { session ->
                val prefix = playerSessionPrefix(session)
                val response = playerHttp(session, prefix + "local?path=" + encodeQuery("~/workspace/main.mpd"))
                assertTrue(response.startsWith("HTTP/1.1 200"))
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(response.substringAfter("\r\n\r\n").byteInputStream())
                val media = (doc.getElementsByTagName("SegmentTemplate").item(0) as Element).getAttribute("media").replace("${'$'}Number${'$'}", "7")
                assertTrue(media.startsWith(prefix + "dash/"))
                val segment = playerHttp(session, media)
                assertTrue(segment.startsWith("HTTP/1.1 200"))
                assertEquals("remote chunk", segment.substringAfter("\r\n\r\n"))
                assertEquals(listOf("https://cdn.example/media/chunk-7.m4s" to "https://watch.example/"), requests)
            }
        } finally { unmockkConstructor(PlayerUpstream::class) }
    }

    @Test fun workspaceChangeClosesAnActiveCopyAndRevokesItsListener(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val channelClosed = AtomicBoolean()
        val access = playerFileAccess(entries = { listOf(FileEntry("a.mp4", false, 10, 1)) }, stream = {
            object : InputStream() {
                override fun read(): Int = error("Expected buffered read")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    entered.countDown()
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw IOException("Channel closed")
                }
                override fun close() { if (channelClosed.compareAndSet(false, true)) closed.countDown() }
            }
        })
        val fixture = PlayerServerFixture(temporary.root, access)
        fixture.server().createSession(PlayerRequest()).use { session ->
            val uri = URI(session.url)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET ${playerSessionPrefix(session)}local?path=${encodeQuery("~/workspace/a.mp4")} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                fixture.current.value = fixture.current.value!!.copy(name = "other-workspace")
                assertTrue("Workspace invalidation must close the blocked source", closed.await(2, TimeUnit.SECONDS))
                val response = runCatching { socket.getInputStream().readBytes() }
                assertTrue(response.isFailure || !response.getOrThrow().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
                assertTrue(channelClosed.get())
                assertThrows(IOException::class.java) { Socket(uri.host, uri.port).close() }
            }
        }
    }

    @Test fun namespacedRevisionStatePersistsAcrossOriginsAndSupportsDeletion(): Unit = runBlocking {
        val fixture = PlayerServerFixture(temporary.root)
        val server = fixture.server()
        server.createSession(PlayerRequest()).use { first ->
            val firstPrefix = playerSessionPrefix(first)
            val config = playerHttp(first, firstPrefix + "config.js").substringAfter("\r\n\r\n")
            val epoch = Regex("window\\.__AJIU_STATE_EPOCH__=([0-9]+);").find(config)!!.groupValues[1].toLong()
            val key = "ajiu_player_v3:${fixture.namespace}:position"
            val body = playerStatePayload(key, "12.5", epoch)
            assertTrue(playerHttp(first, firstPrefix + "api/state", "POST", mapOf("Content-Length" to body.size.toString()), body).startsWith("HTTP/1.1 204"))
            val foreign = playerStatePayload("ajiu_player_v3:${"d".repeat(64)}:position", "foreign", epoch)
            assertTrue(playerHttp(first, firstPrefix + "api/state", "POST", mapOf("Content-Length" to foreign.size.toString()), foreign).startsWith("HTTP/1.1 400"))
            server.createSession(PlayerRequest()).use { second ->
                assertNotEquals(first.origin, second.origin)
                first.close()
                val prefix = playerSessionPrefix(second)
                val reopened = playerHttp(second, prefix + "config.js")
                assertTrue(reopened.contains("window.__AJIU_SAVED_STATE__={\"$key\":\"12.5\"}"))
                val deletion = playerStatePayload(key, null, epoch, 2)
                assertTrue(playerHttp(second, prefix + "api/state", "POST", mapOf("Content-Length" to deletion.size.toString()), deletion).startsWith("HTTP/1.1 204"))
                assertTrue(playerHttp(second, prefix + "config.js").contains("window.__AJIU_SAVED_STATE__={};"))
                assertTrue(playerHttp(second, prefix + "api/state").startsWith("HTTP/1.1 405"))
            }
        }
    }

    @Test fun cancelledCallerBeforeIoResultDeliveryClosesTheUnclaimedSession() {
        val fixture = PlayerServerFixture(temporary.root)
        val server = fixture.server()
        val pendingTasks: BlockingQueue<Runnable> = LinkedBlockingQueue()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pendingTasks.add(block) }
        }
        val owner = Job()
        val creating = CoroutineScope(owner + dispatcher).async { server.createSession(PlayerRequest()) }
        val field = PlayerServer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(server) as MutableSet<PlayerSession>
        var unclaimed: PlayerSession? = null
        try {
            pendingTasks.poll(2, TimeUnit.SECONDS)!!.run()
            val delivery = pendingTasks.poll(5, TimeUnit.SECONDS)
            assertNotNull("I/O result must be queued for the original dispatcher", delivery)
            unclaimed = synchronized(sessions) { sessions.single() }
            val uri = URI(unclaimed.url)
            creating.cancel()
            delivery!!.run()
            runBlocking { creating.join() }
            assertTrue(creating.isCancelled)
            assertTrue(synchronized(sessions) { sessions.isEmpty() })
            assertThrows(IOException::class.java) { Socket(uri.host, uri.port).close() }
        } finally {
            creating.cancel()
            while (true) (pendingTasks.poll() ?: break).run()
            synchronized(sessions) { sessions.toList() }.forEach { it.close() }
            unclaimed?.close()
            owner.cancel()
        }
    }

    private fun assertPropagated(expected: Throwable, actual: Throwable?) {
        assertNotNull(actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue("The thrown exception must remain in the recovered cause chain",
            generateSequence(actual) { it.cause }.take(16).any { it === expected })
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeSessions(server: PlayerServer): Set<PlayerSession> {
        val sessions = PlayerServer::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(server) as MutableSet<PlayerSession>
        return synchronized(sessions) { sessions.toSet() }
    }

    private inner class DeniedChrootWorkspace {
        private val access = mockk<FileAccessProvider> {
            every { cacheIdentity() } returns "active-chroot-fixture"
        }
        val base = PlayerServerFixture(temporary.root, access)
        val local = mockk<LocalFileAccess>()
        private val remote = mockk<RemoteSftpFileAccess>()
        private val settings = mockk<ContainerSettingsRepository>()
        private val grants = mockk<LocalFolderGrantRepository>()
        val runtime = mockk<LocalRuntimeCommands>()
        val owner = RuntimeOwner(RuntimeOwnerKind.FILE, "player-server-ui-fixture")
        var failure: Throwable = LocalRuntimeAuthorizationRequired()
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "chroot-player", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 1, 2, 3)
        )
        private val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 0, 3, Any())
        val factory = spyk(WorkspaceExecutionContextFactory(access, ExecutionModeHolder(), local, remote, settings, grants, runtime))

        init {
            base.current.value = requireNotNull(base.current.value).copy(type = WorkspaceType.INTERNAL)
            coEvery { base.identityResolver.capture("/work/project", any(), any()) } returns ProjectMemoryCapture(
                ProjectMemoryIdentity("local_proot", projectRoot = "/work/project", profileId = profile.id)
            ) {}
            coEvery { grants.awaitReady() } returns Unit
            every { grants.snapshot() } returns LocalFolderSnapshot.EMPTY
            coEvery { settings.captureProfileSnapshot() } returns snapshot
            every { settings.checkProfileSnapshot(snapshot) } returns Unit
            every { local.captureWorkspaceSource() } returns WorkspacePathMapper.WorkspaceSource(File("/work/project"), "/work/project")
            every { local.checkWorkspaceSource(any()) } returns Unit
            coEvery { runtime.capture(any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<() -> Unit>(5).invoke()
                throw failure
            }
        }

        fun server() = PlayerServer(base.context, base.workspaceRepository, base.identityResolver, factory, base.backend, PlayerCopyBudget())
    }

    @Test fun closeRevokesListenerAndIncompleteClientConnections(): Unit = runBlocking {
        val session = PlayerServerFixture(temporary.root).server().createSession(PlayerRequest())
        val uri = URI(session.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())
            session.close()
            session.close()
            val read = runCatching { socket.getInputStream().read() }
            assertTrue(read.isFailure || read.getOrNull() == -1)
        }
        assertThrows(IOException::class.java) { Socket(uri.host, uri.port).close() }
    }
}
