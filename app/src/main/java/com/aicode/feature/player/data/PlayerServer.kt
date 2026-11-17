package com.aicode.feature.player.data

import android.content.Context
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.player.domain.PlayerRequest
import com.aicode.feature.player.domain.PlayerRequestCodec
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val identityResolver: ProjectMemoryIdentityResolver,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val backend: VideoCatalogRepository,
    private val copyBudget: PlayerCopyBudget
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val upstream = PlayerUpstream()
    private fun pool(name: String, core: Int, max: Int, queue: Int) = ThreadPoolExecutor(core, max, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(queue), { task -> Thread(task, name).apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val workers = pool("player-media", 4, 8, 32)
    private val headers = pool("player-headers", 2, 4, 32)
    private val controls = pool("player-controls", 2, 4, 32)
    private val discovery = pool("player-library", 1, 2, 16)
    private val videoDiscovery = pool("player-catalog", 1, 2, 16)
    private val deadlines = ScheduledThreadPoolExecutor(1) { Thread(it, "player-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val sessions = mutableSetOf<PlayerSession>()
    private val states = mutableMapOf<String, PlayerStateStore>()

    suspend fun createSession(request: PlayerRequest): PlayerSession {
        var pending: PlayerSession? = null
        try { return withContext(Dispatchers.IO) {
        val normalized = PlayerRequestCodec.validate(request)
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val resources = PlayerResources()
        val listener = ServerSocket()
        var library: PlayerLibrary? = null
        var registered: PlayerSession? = null
        try {
            val binding = try { PlayerWorkspaceBinding.capture(workspaceRepository, identityResolver, executionFactory, owner) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { currentCoroutineContext().ensureActive(); null }
                catch (_: IllegalStateException) { null }
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
            val origin = "http://127.0.0.1:${listener.localPort}"
            val token = sessionToken()
            val prefix = "/$token/"
            if (binding != null) library = PlayerLibrary(binding, normalized.root, prefix, resources,
                createCopy = { File.createTempFile("ajiu_player_", ".copy", context.cacheDir) }, budget = copyBudget)
            val namespace = binding?.namespace ?: stateNamespaceHash("network")
            val state = synchronized(states) { states.getOrPut(namespace) {
                PlayerStateStore(context.getSharedPreferences(PlayerStateStore.preferencesName(namespace), Context.MODE_PRIVATE), json, namespace)
            } }
            val mappings = DashMappings(prefix)
            val localMappings = DashMappings(prefix, local = true, remoteMappings = mappings)
            lateinit var session: PlayerSession
            session = PlayerSession("$origin$prefix${normalized.mode}.html", origin) {
                owner.cancel()
                runCatching { listener.close() }
                resources.close(); library?.close(); mappings.clear(); localMappings.clear()
                synchronized(sessions) { sessions.remove(session) }
            }
            val data = SessionData(normalized, token, origin, prefix, resources, library, binding, mappings, localMappings, state)
            synchronized(sessions) { check(sessions.size < 8) { "Player session limit reached" }; sessions += session }
            registered = session
            pending = session
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                workspaceRepository.current.collect {
                    if (binding != null) try { binding.checkCurrent() } catch (_: Exception) { session.close() }
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                identityResolver.environmentChanges.collect {
                    if (binding != null) try { binding.checkCurrent() } catch (_: Exception) { session.close() }
                }
            }
            Thread({ accept(listener, data, session) }, "player-listener").apply { isDaemon = true }.start()
            currentCoroutineContext().ensureActive()
            session
        } catch (failure: Throwable) {
            registered?.close(); owner.cancel(); resources.close(); library?.close(); runCatching { listener.close() }
            throw failure
        }
        } } catch (failure: Throwable) { pending?.close(); throw failure }
    }
    private fun accept(listener: ServerSocket, data: SessionData, session: PlayerSession) {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept().apply { soTimeout = 15000 }
                val connection = PlayerResources()
                try {
                    connection.add(socket); data.resources.add(connection)
                    val activity = PlayerConnectionActivity()
                    val deadline = deadlines.scheduleWithFixedDelay({ if (activity.timedOut()) connection.close() }, 15, 1, TimeUnit.SECONDS)
                    val cleanup = { deadline.cancel(false); connection.close(); data.resources.remove(connection) }
                    try { headers.execute {
                        var dispatched = false
                        try {
                            val input = PlayerProgressInputStream(socket.getInputStream(), activity::progress).buffered()
                            val request = PlayerHttpRequest.read(input)
                            activity.headersComplete()
                            val route = sessionRoute(request.rawPath, data.token)
                            val executor = when {
                                route == "local" || route == "relay" || route?.startsWith("dash/") == true || route?.startsWith("files/") == true -> workers
                                route?.startsWith("api/video/") == true || route == "api/catalog" -> videoDiscovery
                                route == "api/library" -> discovery
                                else -> controls
                            }
                            executor.execute { try { handle(socket, data, connection, activity, request, input) } finally { cleanup() } }
                            dispatched = true
                        } catch (_: RejectedExecutionException) { }
                        catch (e: Exception) { error(socket, null, e) }
                        finally { if (!dispatched) cleanup() }
                    } } catch (_: RejectedExecutionException) { cleanup() }
                } catch (_: IOException) { connection.close(); data.resources.remove(connection) }
            }
        } catch (_: IOException) { }
        finally { session.close() }
    }
    private fun handle(socket: Socket, data: SessionData, connection: PlayerResources, activity: PlayerConnectionActivity,
        request: PlayerHttpRequest, input: InputStream) {
        var output: PlayerHttpResponse? = null
        var watch: ScheduledFuture<*>? = null
        try {
            val response = PlayerHttpResponse(socket.getOutputStream(), request.method == "HEAD", activity::progress)
            output = response
            requireSessionOrigin(request, data.origin)
            val route = sessionRoute(request.rawPath, data.token) ?: throw PlayerHttpException(404, "Unknown session route")
            connection.checkOpen(); data.binding?.checkCurrent()
            if (route == "api/state") {
                if (request.method != "POST") { response.headers(405, mapOf("Allow" to "POST", "Content-Length" to "0")); return }
                if (request.headers["transfer-encoding"] != null) throw PlayerHttpException(400, "Content-Length required")
                val length = request.headers["content-length"]?.toIntOrNull()?.takeIf { it >= 0 } ?: throw PlayerHttpException(411, "Content-Length required")
                if (length > PlayerStateStore.MAX_BODY) throw PlayerHttpException(413, "State request too large")
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset); if (count < 0) throw PlayerHttpException(400, "Incomplete state"); offset += count }
                data.state.update(bytes); response.headers(204); return
            }
            if (request.method !in setOf("GET", "HEAD")) { response.headers(405, mapOf("Allow" to "GET, HEAD", "Content-Length" to "0")); return }
            if (request.headers["transfer-encoding"] != null || request.headers["content-length"]?.let { it != "0" } == true) throw PlayerHttpException(400, "GET body rejected")
            if (request.method == "GET" && (route == "local" || route.startsWith("files/") || route.startsWith("api/"))) {
                socket.soTimeout = 1
                watch = deadlines.scheduleWithFixedDelay({
                    try { input.read(); connection.close() } catch (_: SocketTimeoutException) { } catch (_: IOException) { connection.close() }
                }, 250, 250, TimeUnit.MILLISECONDS)
                val peerWatch = watch
                connection.add(AutoCloseable { peerWatch.cancel(false) })
            }
            when {
                route == "config.js" -> response.text(200, "window.__AJIU_CONFIG__=${json.encodeToString(data.request)};\n${data.state.bootstrapScript()}window.__AJIU_L10N__=${PlayerTranslations.json(context)};", JS)
                route == "api/library" -> response.text(200, requireLibrary(data).json(request.query["root"] ?: data.request.root).toString(), JSON)
                route == "api/catalog" -> {
                    val result = catalog(data, connection, request.query).toString().toByteArray()
                    if (result.size > 4 * 1024 * 1024) throw PlayerHttpException(502, "Catalog response too large")
                    connection.checkOpen(); response.bytes(200, result, JSON)
                }
                route.startsWith("api/video/") -> video(request, response, route, connection)
                route == "local" -> local(request, response, data, connection, activity, request.query["path"] ?: throw PlayerHttpException(400, "Missing path"))
                route.startsWith("files/") -> {
                    val segments = route.removePrefix("files/").split('/').map { decodeQuery(it.replace("+", "%2B")) }
                    local(request, response, data, connection, activity, localManifestPath(segments))
                }
                route == "relay" -> relay(request, response, data, connection, request.query["u"] ?: throw PlayerHttpException(400, "Missing URL"), request.query["ref"] ?: data.request.referer)
                route.startsWith("dash/") -> { val (url, ref) = data.mappings.resolve(route, request.rawQuery); relay(request, response, data, connection, url, ref) }
                else -> asset(route, response)
            }
        } catch (e: Exception) { error(socket, output, e) }
        finally { watch?.cancel(false) }
    }
    private fun requireLibrary(data: SessionData) = data.library ?: throw PlayerHttpException(503, "Workspace files unavailable for this session")
    private fun catalog(data: SessionData, connection: PlayerResources, query: Map<String, String>): JsonObject {
        val request = data.request
        if (query.keys.any { it in setOf("api", "query", "referer") }) {
            fun parameter(key: String, fallback: String, max: Int): String = (query[key] ?: fallback).also {
                if (it.length > max || it.any { ch -> ch.code < 32 || ch.code == 127 }) throw PlayerHttpException(400, "Invalid catalog parameter")
            }
            val api = parameter("api", request.api, 8192)
            val keyword = parameter("query", request.query, 512)
            val referer = parameter("referer", request.referer, 8192)
            remoteUrl(api)
            if (referer.isNotBlank()) remoteUrl(referer)
            return backend.apiSeries(api, keyword, referer, connection)
        }
        if (request.videoId.isNotBlank()) return backend.detail(request.videoSources.single(), request.videoId, connection)
        if (request.items.isNotEmpty() || request.api.isBlank()) return buildJsonObject {
            put("title", request.title)
            put("eps", JsonArray(request.items.map { buildJsonObject { put("n", it.name); put("u", it.url); put("kind", it.kind) } }))
        }
        return backend.apiSeries(request.api, request.query, request.referer, connection)
    }
    private fun video(request: PlayerHttpRequest, response: PlayerHttpResponse, route: String, connection: PlayerResources) {
        if (request.method != "GET") { response.headers(405, mapOf("Allow" to "GET", "Content-Length" to "0")); return }
        fun text(key: String, max: Int, required: Boolean = false): String = request.query[key].orEmpty().trim().also {
            if (it.length > max || it.any { ch -> ch.code < 32 } || required && it.isEmpty()) throw PlayerHttpException(400, "Invalid catalog parameter")
        }
        fun number(key: String, default: Int, max: Int) = request.query[key]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..max } ?: throw PlayerHttpException(400, "Invalid catalog number") } ?: default
        val result = when (route) {
            "api/video/sources" -> backend.sources()
            "api/video/status" -> backend.status()
            "api/video/search" -> {
                val sources = text("sources", 4096).split(',').filter { it.isNotBlank() }.distinct()
                if (sources.any { !SOURCE.matches(it) }) throw PlayerHttpException(400, "Invalid source")
                backend.search(text("q", 512, true), sources, number("page", 1, 1_000_000), number("limit", 60, 200), connection)
            }
            "api/video/detail" -> { val source = text("source", 64, true); if (!SOURCE.matches(source)) throw PlayerHttpException(400, "Invalid source"); backend.detail(source, text("id", 128, true), connection) }
            else -> throw PlayerHttpException(404, "Unknown catalog route")
        }
        connection.checkOpen()
        val bytes = result.toString().toByteArray()
        if (bytes.size > 4 * 1024 * 1024) throw PlayerHttpException(502, "Catalog response too large")
        response.bytes(200, bytes, JSON)
    }
    private fun asset(route: String, response: PlayerHttpResponse) {
        if (route !in ASSETS) throw PlayerHttpException(404, "Unknown asset")
        context.assets.open("player/$route").use { response.headers(200, mapOf("Content-Type" to mediaType(route))); response.stream(it) }
    }
    private fun local(request: PlayerHttpRequest, response: PlayerHttpResponse, data: SessionData,
        connection: PlayerResources, activity: PlayerConnectionActivity, rawPath: String) {
        val library = requireLibrary(data)
        val path = library.checkedPath(rawPath)
        var size = 0L
        var range: ByteRange? = null
        val manifest = path.substringAfterLast('.').lowercase() in setOf("m3u", "m3u8", "mpd")
        val opened = try { library.openValidated(path, metadataOnly = response.head, connection = connection, progress = activity::progress) {
            size = it
            range = parseRange(request.headers["range"].takeUnless { response.head || "if-range" in request.headers || manifest }, size)
            if (manifest && size > 4 * 1024 * 1024) throw PlayerHttpException(413, "Local manifest too large")
        } } catch (e: PlayerHttpException) {
            if (e.status != 416) throw e
            response.headers(416, mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0", "Accept-Ranges" to "bytes")); return
        }
        connection.add(opened)
        try {
            val selected = range
            if (response.head) { response.headers(200, mapOf("Content-Type" to mediaType(path), "Content-Length" to size.toString(), "Accept-Ranges" to "bytes")); return }
            val file = opened.descriptor!!
            library.checkCurrent(); connection.checkOpen()
            if (file.length() != size) throw PlayerHttpException(503, "File changed")
            file.seek(selected?.start ?: 0)
            val input = object : InputStream() {
                private val single = ByteArray(1)
                override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    library.checkCurrent(); connection.checkOpen()
                    val count = file.read(b, off, len)
                    library.checkCurrent(); connection.checkOpen()
                    return count
                }
            }
            if (manifest) {
                val bytes = input.readLimited()
                val base = localManifestBase(path)
                val rewritten = if (path.endsWith(".mpd", true)) rewriteDash(bytes, base, data.localMappings, data.request.referer)
                    else rewriteHls(bytes.toString(Charsets.UTF_8), base, data.prefix, data.request.referer, local = true).toByteArray()
                response.bytes(200, rewritten, mediaType(path), mapOf("X-Ajiu-Manifest-Rewritten" to if (path.endsWith(".mpd", true)) "dash" else "hls")); return
            }
            val fields = linkedMapOf("Content-Type" to mediaType(path), "Accept-Ranges" to "bytes", "Content-Length" to (selected?.length ?: size).toString())
            selected?.let { fields["Content-Range"] = "bytes ${it.start}-${it.end}/$size" }
            response.headers(if (selected == null) 200 else 206, fields); response.stream(input, selected?.length ?: size)
        } finally { opened.close(); connection.remove(opened) }
    }
    private fun relay(request: PlayerHttpRequest, response: PlayerHttpResponse, data: SessionData, connection: PlayerResources, url: String, referer: String) {
        PlayerRelay(upstream).forward(request, response, connection, data.prefix, data.mappings, url, referer.ifBlank { remoteUrl(url).resolve("/")!!.toString() })
    }
    private fun error(socket: Socket, output: PlayerHttpResponse?, error: Exception) {
        runCatching {
            val response = output ?: PlayerHttpResponse(socket.getOutputStream(), false)
            if (response.started) return@runCatching
            val status = when (error) { is PlayerHttpException -> error.status; is FileNotFoundException, is NoSuchFileException -> 404; is SocketTimeoutException -> 504; is IllegalArgumentException -> 400; else -> 502 }
            response.text(status, "Player HTTP $status")
        }
    }
    private data class SessionData(val request: PlayerRequest, val token: String, val origin: String, val prefix: String,
        val resources: PlayerResources, val library: PlayerLibrary?, val binding: PlayerWorkspaceBinding?, val mappings: DashMappings,
        val localMappings: DashMappings, val state: PlayerStateStore)
    private companion object {
        const val JS = "application/javascript; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        val SOURCE = Regex("[A-Za-z0-9_-]{1,64}")
        val ASSETS = setOf("index.html", "embed.html", "series.html", "app.js", "bridge.js", "net-media.js", "player.css", "vendor/hls-1.5.15.min.js", "vendor/dash-4.7.4.all.min.js")
    }
}
class PlayerSession internal constructor(val url: String, val origin: String, private val onClose: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val listeners = mutableListOf<() -> Unit>()
    fun onClosed(listener: () -> Unit) {
        val notify = synchronized(listeners) { if (closed.get()) true else { listeners += listener; false } }
        if (notify) listener()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { onClose() } finally {
            val snapshot = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
            snapshot.forEach { runCatching(it) }
        }
    }
}
