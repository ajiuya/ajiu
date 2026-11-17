package com.aicode.feature.player.data

import android.content.Context
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

@Singleton
class VideoCatalogRepository internal constructor(
    private val configRoot: () -> File,
    private val upstream: PlayerUpstream = PlayerUpstream(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val sourceOverrides: List<VideoCatalogSource>? = null,
    private val deadlineMillis: Long = 8_000,
    private val graceMillis: Long = 1_500,
    private val defaults: List<VideoCatalogSource> = DEFAULT_SOURCES,
    private val lineName: (Int) -> String = { "Line $it" }
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        configRoot = { File(context.filesDir, "player/catalog").apply { if (!isDirectory && !mkdirs()) throw IOException("Catalog configuration directory unavailable") } },
        defaults = DEFAULT_SOURCES.mapIndexed { i, source -> source.copy(name = context.getString(SOURCE_NAMES[i])) },
        lineName = { context.getString(R.string.player_line_number, it) }
    )
    private val cacheLock = Any()
    private val plans = linkedMapOf<String, NetworkPlan>()
    internal fun sources(): JsonObject {
        val root = configRoot()
        return buildJsonObject {
            put("sources", JsonArray(configuredSources(root).map { it.toJson() }))
            put("deadline", timing(root).first / 1000.0); put("grace", timing(root).second / 1000.0)
        }
    }
    internal fun status(): JsonObject = buildJsonObject {
        put("mode", "network"); put("sources", sources()["sources"]!!)
    }
    internal fun search(query: String, sources: List<String> = emptyList(), page: Int = 1, limit: Int = 60,
        resources: PlayerResources): JsonObject {
        val keyword = validQuery(query)
        require(page in 1..1_000_000 && limit in 1..200)
        resources.checkOpen()
        val root = configRoot()
        return networkSearch(root, keyword, selectSources(configuredSources(root), sources), page, limit, resources)
    }
    internal fun detail(source: String, id: String, resources: PlayerResources): JsonObject {
        validId(id); resources.checkOpen()
        val root = configRoot()
        val target = selectSources(configuredSources(root), listOf(source)).single()
        val timing = timing(root)
        val result = race(listOf(target), resources, { it.entries.isNotEmpty() }, timing.first, timing.second) { spec, child -> request(spec, child, id = id) }.single()
        val exact = result.entries.firstOrNull { it.text("vod_id") == id }
        if (exact != null) return series(result.source, exact, result.apiUrl, result.finalUrl, resources)
        return buildJsonObject {
            put("source", target.id); put("sourceName", target.name); put("id", id); put("title", "")
            put("api", target.api); put("apiUrl", result.apiUrl); put("referer", "")
            put("lines", JsonArray(emptyList())); put("defaultLine", 0); put("eps", JsonArray(emptyList()))
            put("errors", JsonArray(listOf(result.error ?: error(target.id, "Requested source/id was not returned", "id_not_found"))))
        }
    }
    internal fun autoSeries(query: String, resources: PlayerResources, sources: List<String> = emptyList()): JsonObject {
        val keyword = validQuery(query)
        resources.checkOpen()
        val root = configRoot()
        val selected = selectSources(configuredSources(root), sources)
        val timing = timing(root)
        val results = race(selected, resources, { result -> result.entries.any { playable(it, resources) } }, timing.first, timing.second) { spec, child -> keywordResult(spec, keyword, 1, child) }
        val all = results.flatMap { result -> result.entries.map { series(result.source, it, result.apiUrl, result.finalUrl, resources) } }
            .filter { (it["eps"] as JsonArray).isNotEmpty() }
        val exact = all.filter { it.text("title").trim() == keyword }
        val ranked = exact.ifEmpty { all }.sortedByDescending { (it["eps"] as JsonArray).size }
        return buildJsonObject {
            ranked.firstOrNull()?.forEach { (key, value) -> put(key, value) }
            if (ranked.isEmpty()) { put("id", ""); put("title", keyword); put("eps", JsonArray(emptyList())); put("lines", JsonArray(emptyList())); put("defaultLine", 0) }
            put("query", keyword); put("others", JsonArray(ranked.drop(1)))
            put("errors", JsonArray(results.mapNotNull { it.error })); put("sourceResults", JsonArray(results.map { it.toJson() }))
        }
    }
    internal fun apiSeries(api: String, query: String, referer: String, resources: PlayerResources): JsonObject {
        val keyword = validQuery(query)
        val source = VideoCatalogSource("custom", "", remoteUrl(api).toString(), 8, referer = referer)
        val result = race(listOf(source), resources, { it.entries.isNotEmpty() }, deadlineMillis, graceMillis) { spec, child -> keywordResult(spec, keyword, 1, child) }.single()
        val exact = result.entries.filter { it.text("vod_name").trim() == keyword }
        val selected = exact.ifEmpty { result.entries }.filter { playable(it, resources) }.maxByOrNull { it.text("vod_play_url").length }
        return if (selected != null) series(source, selected, result.apiUrl, result.finalUrl, resources) else buildJsonObject {
            put("title", keyword); put("eps", JsonArray(emptyList())); put("lines", JsonArray(emptyList()))
            put("errors", JsonArray(listOfNotNull(result.error)))
        }
    }
    private fun networkSearch(root: File, query: String, sources: List<VideoCatalogSource>, page: Int, limit: Int,
        resources: PlayerResources): JsonObject {
        val timing = timing(root)
        val start = System.nanoTime()
        var firstUsableAt: Long? = null
        val key = query + "\u0000" + sources.joinToString("|") { it.toJson().toString() }
        val snapshot = synchronized(cacheLock) { plans[key]?.takeIf { elapsed(it.created) < 60_000 } } ?: run {
            val initial = race(sources, resources, { result -> (result.entries.isNotEmpty() && page == 1).also { if (it && firstUsableAt == null) firstUsableAt = elapsed(start) } }, timing.first, timing.second) { spec, child -> keywordResult(spec, query, 1, child) }.map(::metadataResult)
            NetworkPlan(System.nanoTime(), initial).also { plan -> synchronized(cacheLock) {
                if (plan.roundBytes.values.sum() <= 2 * 1024 * 1024 && initial.sumOf { it.entries.size } <= 4096) {
                    plans[key] = plan; trimCache()
                }
            } }
        }
        val offset = (page.toLong() - 1) * limit
        val total = snapshot.total
        val errors = snapshot.initial.mapNotNull { it.error }.toMutableList()
        val observed = linkedMapOf<String, SourceResult>()
        snapshot.initial.forEach { observed[it.source.id] = it }
        if (offset >= total) return searchJson(query, page, limit, total, false, emptyList(), errors, observed.values.toList())
        var low = 1; var high = snapshot.maxRounds
        while (low < high) { val middle = low + (high - low) / 2; if (snapshot.beforeRound(middle + 1) > offset) high = middle else low = middle + 1 }
        var round = low
        val end = minOf(total, offset + limit)
        val items = mutableListOf<JsonObject>()
        var usableAt = firstUsableAt
        while (round <= snapshot.maxRounds && snapshot.beforeRound(round) < end) {
            resources.checkOpen()
            val before = snapshot.beforeRound(round)
            val cached = synchronized(cacheLock) { snapshot.rounds[round] }
            val spent = elapsed(start)
            val remaining = minOf(timing.first - spent, usableAt?.let { timing.second - (spent - it) } ?: Long.MAX_VALUE)
            if (cached == null && remaining <= 0) { errors += error("catalog", "Catalog pagination budget exhausted", "deadline_exceeded"); break }
            val results = cached ?: run {
                val active = snapshot.initial.filter { it.error == null && round <= it.pageCount && it.total > 0 }.map { it.source }
                val fetched = race(active, resources, { result -> result.entries.isNotEmpty().also { if (it && usableAt == null) usableAt = elapsed(start) } }, remaining, timing.second) { spec, child -> keywordResult(spec, query, round, child) }.map(::metadataResult)
                val bytes = fetched.sumOf { result -> result.entries.sumOf { it.toString().toByteArray().size.toLong() } }
                if (fetched.all { it.error == null } && bytes <= 2 * 1024 * 1024 && fetched.sumOf { it.entries.size } <= 4096) synchronized(cacheLock) {
                    while (snapshot.rounds.size >= 16) { val oldest = snapshot.rounds.keys.firstOrNull { it != 1 } ?: break; snapshot.rounds.remove(oldest); snapshot.roundBytes.remove(oldest) }
                    snapshot.rounds[round] = fetched; snapshot.roundBytes[round] = bytes; trimCache()
                }
                fetched
            }
            results.forEach { observed[it.source.id] = it }; errors += results.mapNotNull { it.error }
            var position = before
            snapshot.initial.filter { it.error == null }.forEach { initial ->
                val slots = minOf(initial.pageSize.toLong(), (initial.total - (round.toLong() - 1) * initial.pageSize).coerceAtLeast(0))
                val result = results.firstOrNull { it.source.id == initial.source.id }
                if (result != null && result.error == null && position < end && position + slots > offset) {
                    val from = (offset - position).coerceAtLeast(0).toInt()
                    val through = minOf(slots, end - position).coerceAtLeast(0).toInt()
                    items += result.entries.map { metadata(result.source, it, result.apiUrl) }.distinctBy { it.text("id") }
                        .sortedWith(compareBy<JsonObject> { rank(it.text("name"), query) }.thenBy { it.text("name").length }.thenBy { it.text("name") }.thenBy { it.text("id") }).drop(from).take((through - from).coerceAtLeast(0))
                }
                position += slots
            }
            round++
        }
        return searchJson(query, page, limit, total, end < total, items, errors.distinctBy { it.toString() }, observed.values.toList())
    }
    private fun metadataResult(result: SourceResult) = result.copy(entries = result.entries.map { JsonObject(it.filterKeys { key -> key in METADATA_FIELDS }) })
    private class NetworkPlan(val created: Long, val initial: List<SourceResult>) {
        val total = initial.filter { it.error == null }.sumOf { it.total }
        val maxRounds = initial.filter { it.error == null }.maxOfOrNull { it.pageCount } ?: 1
        val rounds = linkedMapOf(1 to initial)
        val roundBytes = linkedMapOf(1 to initial.sumOf { result -> result.entries.sumOf { it.toString().toByteArray().size.toLong() } })
        fun beforeRound(round: Int) = initial.filter { it.error == null }.sumOf { minOf(it.total, (round.toLong() - 1) * it.pageSize) }
    }
    private fun trimCache() {
        while (plans.size > 8 || plans.values.sumOf { it.roundBytes.values.sum() } > 8 * 1024 * 1024L) {
            val oldest = plans.entries.firstOrNull() ?: break
            plans.remove(oldest.key)
        }
    }
    private fun keywordResult(source: VideoCatalogSource, query: String, page: Int, child: PlayerResources) =
        if (!source.search) SourceResult(source, error = error(source.id, "Keyword search disabled", "keyword_search_disabled")) else request(source, child, keyword = query, page = page)
    private fun request(source: VideoCatalogSource, child: PlayerResources, id: String = "", keyword: String = "", page: Int = 1): SourceResult {
        if (!source.enabled) return SourceResult(source, error = error(source.id, "Source disabled", "source_disabled"))
        val start = System.nanoTime()
        val url = remoteUrl(source.api).newBuilder().setQueryParameter("ac", "detail").apply {
            removeAllQueryParameters("ids"); removeAllQueryParameters("wd"); removeAllQueryParameters("pg")
            if (id.isNotEmpty()) setQueryParameter("ids", id) else { setQueryParameter("wd", keyword); setQueryParameter("pg", page.toString()) }
        }.build().toString()
        return try {
            upstream.open(child, url, referer = source.referer).use { exchange ->
                val response = exchange.response
                if (!response.isSuccessful) throw PlayerHttpException(response.code, "Catalog upstream HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty catalog response")
                val bytes = decodedBody(response.header("Content-Encoding"), body.byteStream()).use { it.readLimited(16 * 1024 * 1024) }
                child.checkOpen()
                val data = decodeCatalog(bytes, body.contentType()?.charset())
                val code = (data["code"] as? JsonPrimitive)?.content
                if (code != null && code !in setOf("1", "200")) throw IOException("Catalog API rejected request")
                val entries = (data["list"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { it.text("vod_id").isNotBlank() } ?: throw IOException("Catalog list required")
                val total = maxOf((data["total"] as? JsonPrimitive)?.longOrNull ?: entries.size.toLong(), entries.size.toLong())
                val pageSize = entries.size.takeIf { it > 0 } ?: (data["limit"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
                val pageCount = maxOf(1, (data["pagecount"] as? JsonPrimitive)?.intOrNull ?: ((total + pageSize - 1) / pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                SourceResult(source, entries, total, pageCount, url, response.request.url.toString(), elapsed(start), pageSize = pageSize)
            }
        } catch (e: Exception) { SourceResult(source, apiUrl = url, elapsedMs = elapsed(start), error = error(source.id, "Source query failed", if (e is PlayerHttpException) "http_${e.status}" else "upstream_error")) }
    }
    private fun decodeCatalog(bytes: ByteArray, declared: Charset?): JsonObject {
        val bom = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> null
        }
        for (charset in (listOfNotNull(bom, declared) + listOf(Charsets.UTF_8, Charset.forName("GBK"), Charset.forName("GB18030"), Charsets.ISO_8859_1)).distinct()) {
            val parsed = runCatching {
                val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                json.parseToJsonElement(text) as? JsonObject
            }.getOrNull()
            if (parsed != null) return parsed
        }
        throw IOException("Invalid catalog encoding or JSON")
    }
    private fun race(sources: List<VideoCatalogSource>, parent: PlayerResources, usable: (SourceResult) -> Boolean,
        budgetMillis: Long, resultGraceMillis: Long, fetch: (VideoCatalogSource, PlayerResources) -> SourceResult): List<SourceResult> {
        if (sources.isEmpty()) return emptyList()
        parent.checkOpen()
        val executor = Executors.newFixedThreadPool(minOf(sources.size, 8)) { Thread(it, "player-catalog").apply { isDaemon = true } }
        val executorResource = AutoCloseable { executor.shutdownNow() }
        val children = linkedMapOf<String, PlayerResources>()
        val futures = linkedMapOf<Future<SourceResult>, VideoCatalogSource>()
        val completion = ExecutorCompletionService<SourceResult>(executor)
        val results = linkedMapOf<String, SourceResult>()
        val started = System.nanoTime()
        var gotAt: Long? = null
        try {
            sources.forEach { source -> val child = PlayerResources(); children[source.id] = child; parent.add(child); futures[completion.submit(Callable { fetch(source, child) })] = source }
            parent.add(executorResource)
            while (results.size < sources.size) {
                parent.checkOpen()
                val spent = elapsed(started)
                if (spent >= budgetMillis || gotAt?.let { spent - it >= resultGraceMillis } == true) break
                futures.forEach { (future, source) -> if (source.id !in results && spent >= source.timeout * 1000L && !future.isDone) {
                    children.getValue(source.id).close(); future.cancel(true)
                    results[source.id] = SourceResult(source, elapsedMs = spent, error = error(source.id, "Source timeout", "source_timeout"))
                } }
                val future = completion.poll(minOf(50L, (budgetMillis - spent).coerceAtLeast(1), gotAt?.let { (resultGraceMillis - (spent - it)).coerceAtLeast(1) } ?: 50L), TimeUnit.MILLISECONDS) ?: continue
                val source = futures.getValue(future)
                if (source.id in results) continue
                val result = try { future.get() } catch (_: Exception) { SourceResult(source, error = error(source.id, "Source cancelled", "source_cancelled")) }
                results[source.id] = result
                if (gotAt == null && usable(result)) gotAt = elapsed(started)
            }
            parent.checkOpen()
            sources.filter { it.id !in results }.forEach {
                val code = if (elapsed(started) >= budgetMillis) "deadline_exceeded" else "grace_expired"
                results[it.id] = SourceResult(it, error = error(it.id, "Catalog time budget exhausted", code))
            }
            return sources.map { results.getValue(it.id) }
        } finally {
            children.values.forEach { it.close(); parent.remove(it) }; futures.keys.forEach { it.cancel(true) }
            executor.shutdownNow(); parent.remove(executorResource)
        }
    }
    private fun metadata(source: VideoCatalogSource, entry: JsonObject, apiUrl: String): JsonObject = buildJsonObject {
        put("id", entry.text("vod_id")); put("source", source.id); put("sourceName", source.name)
        put("name", entry.text("vod_name")); put("title", entry.text("vod_name"))
        mapOf("remark" to "vod_remarks", "type" to "type_name", "year" to "vod_year", "mtime" to "vod_time", "playfrom" to "vod_play_from").forEach { (key, field) -> put(key, entry.text(field)) }
        put("api", source.api); put("apiUrl", apiUrl); put("metadataOnly", true)
    }
    private fun playable(entry: JsonObject, resources: PlayerResources) = entry.text("vod_play_url").splitToSequence("$$$").flatMap { it.splitToSequence('#') }.any {
        resources.checkOpen(); runCatching { remoteUrl(it.substringAfter('$', it).trim()) }.isSuccess
    }
    private fun series(source: VideoCatalogSource, entry: JsonObject, apiUrl: String, finalUrl: String,
        resources: PlayerResources): JsonObject {
        val froms = entry.text("vod_play_from").split("$$$")
        val lines = entry.text("vod_play_url").split("$$$").mapIndexed { index, line ->
            resources.checkOpen()
            val eps = line.split('#').filter { it.isNotBlank() }.mapIndexedNotNull { episodeIndex, raw ->
                resources.checkOpen()
                val separator = raw.indexOf('$')
                val name = if (separator >= 0) raw.substring(0, separator).trim() else "${episodeIndex + 1}"
                val url = (if (separator >= 0) raw.substring(separator + 1) else raw).trim()
                val valid = runCatching { remoteUrl(url).toString() }.getOrNull() ?: return@mapIndexedNotNull null
                buildJsonObject { put("n", name.ifEmpty { "${episodeIndex + 1}" }); put("u", valid); put("kind", "video") }
            }
            buildJsonObject {
                put("id", index.toString()); put("name", froms.getOrNull(index).orEmpty().ifBlank { lineName(index + 1) }); put("eps", JsonArray(eps))
                put("m3u8", froms.getOrNull(index).orEmpty().contains("m3u8", true) || eps.any { it.text("u").contains(".m3u8", true) })
            }
        }.filter { (it["eps"] as JsonArray).isNotEmpty() }
        val preferred = lines.indices.maxWithOrNull(compareBy<Int> { (lines[it]["m3u8"] as JsonPrimitive).boolean }.thenBy { (lines[it]["eps"] as JsonArray).size }) ?: 0
        val referer = sequenceOf("vod_play_referer", "vod_referer", "referer").map { entry.text(it) }.firstOrNull { it.isNotBlank() }
            ?.let { runCatching { remoteUrl(it).toString() }.getOrDefault("") }.orEmpty().ifBlank { source.referer }
        return buildJsonObject {
            metadata(source, entry, apiUrl).forEach { (key, value) -> put(key, value) }; put("metadataOnly", false)
            mapOf("area" to "vod_area", "pic" to "vod_pic", "actor" to "vod_actor", "director" to "vod_director", "content" to "vod_content", "lang" to "vod_lang").forEach { (key, field) -> put(key, entry.text(field)) }
            put("finalApiUrl", finalUrl); put("referer", referer); put("lines", JsonArray(lines)); put("defaultLine", preferred)
            put("eps", lines.getOrNull(preferred)?.get("eps") ?: JsonArray(emptyList())); put("errors", JsonArray(emptyList()))
        }
    }
    private fun config(root: File): JsonElement? {
        if (sourceOverrides != null) return null
        val file = File(root, "sources.json")
        if (!file.isFile || file.length() > 256 * 1024) return null
        return json.parseToJsonElement(file.readText())
    }
    private fun timing(root: File): Pair<Long, Long> {
        val value = config(root) as? JsonObject
        val deadline = (value?.get("deadline") as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong().coerceIn(100, 60_000) } ?: deadlineMillis
        val grace = (value?.get("grace") as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong().coerceIn(0, 30_000) } ?: graceMillis
        return deadline to grace
    }
    private fun configuredSources(root: File): List<VideoCatalogSource> {
        sourceOverrides?.let { return it }
        val value = config(root) ?: return defaults
        val objects = (value as? JsonObject)?.get("sources") as? JsonArray ?: value as? JsonArray ?: return defaults
        val configured = objects.map {
            val custom = it as? JsonObject ?: throw IllegalArgumentException("Source object required")
            val id = custom.text("id").ifBlank { custom.text("key") }
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
            val fallback = defaults.firstOrNull { it.id == id }
            val api = custom.text("api").ifBlank { fallback?.api.orEmpty() }
            remoteUrl(api)
            VideoCatalogSource(id, custom.text("name").ifBlank { fallback?.name ?: id }, api,
                ((custom["timeout"] as? JsonPrimitive)?.intOrNull ?: fallback?.timeout ?: 6).coerceIn(1, 30),
                (custom["enabled"] as? JsonPrimitive)?.booleanOrNull ?: fallback?.enabled ?: true,
                (custom["search"] as? JsonPrimitive)?.booleanOrNull ?: fallback?.search ?: true,
                custom.text("referer").ifBlank { fallback?.referer.orEmpty() }.also { ref -> if (ref.isNotBlank()) remoteUrl(ref) })
        }
        require(configured.size <= 64 && configured.map { it.id }.distinct().size == configured.size)
        return configured
    }
    private fun selectSources(configured: List<VideoCatalogSource>, requested: List<String>) =
        if (requested.isEmpty()) configured.filter { it.enabled } else requested.distinct().map { key -> configured.firstOrNull { it.id == key } ?: throw IllegalArgumentException("Unknown source id") }
    private fun searchJson(query: String, page: Int, limit: Int, total: Long, hasMore: Boolean, items: List<JsonObject>, errors: List<JsonObject>, results: List<SourceResult>) = buildJsonObject {
        put("query", query); put("page", page); put("limit", limit); put("total", total); put("hasMore", hasMore); put("mode", "network")
        put("items", JsonArray(items)); put("errors", JsonArray(errors)); put("sourceResults", JsonArray(results.map { it.toJson() }))
        put("partial", errors.isNotEmpty() && items.size.toLong() < minOf(limit.toLong(), (total - (page.toLong() - 1) * limit).coerceAtLeast(0)))
    }
    private data class SourceResult(val source: VideoCatalogSource, val entries: List<JsonObject> = emptyList(),
        val total: Long = 0, val pageCount: Int = 1, val apiUrl: String = "", val finalUrl: String = "", val elapsedMs: Long = 0,
        val error: JsonObject? = null, val pageSize: Int = 1) {
        fun toJson() = buildJsonObject {
            put("source", source.id); put("sourceName", source.name); put("ok", error == null); put("total", total); put("count", entries.size); put("elapsedMs", elapsedMs); put("apiUrl", apiUrl); error?.let { put("error", it) }
        }
    }
    companion object {
        internal val DEFAULT_SOURCES = listOf(
            VideoCatalogSource("ffzy", "FFZY", "https://cj.ffzyapi.com/api.php/provide/vod/", 6),
            VideoCatalogSource("dytt", "DYTT", "https://caiji.dyttzyapi.com/api.php/provide/vod", 6),
            VideoCatalogSource("lzi", "LZI", "https://cj.lziapi.com/api.php/provide/vod", 6),
            VideoCatalogSource("mtzy", "MTZY", "https://caiji.maotaizy.cc/api.php/provide/vod", 4, search = false))
        private val SOURCE_NAMES = listOf(R.string.player_source_ffzy, R.string.player_source_dytt, R.string.player_source_lzi, R.string.player_source_mtzy)
        private val METADATA_FIELDS = setOf("vod_id", "vod_name", "vod_remarks", "type_name", "vod_year", "vod_time", "vod_play_from")
        private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        private fun rank(name: String, query: String) = if (name == query) 0 else if (name.startsWith(query, true)) 1 else 2
        private fun validQuery(query: String): String { require(query.isNotBlank() && query.length <= 512 && query.none { it.code < 32 || it.code == 127 }); return query.trim() }
        private fun validId(id: String) { require(id.isNotBlank() && id == id.trim() && id.length <= 128 && ',' !in id && id.none { it.code < 32 || it.code == 127 }) }
        private fun error(source: String, message: String, code: String = "catalog_error") = buildJsonObject { put("source", source); put("message", message); put("code", code) }
    }
}
internal data class VideoCatalogSource(val id: String, val name: String, val api: String, val timeout: Int,
    val enabled: Boolean = true, val search: Boolean = true, val referer: String = "") {
    fun toJson() = buildJsonObject { put("id", id); put("key", id); put("name", name); put("api", api); put("timeout", timeout); put("enabled", enabled); put("search", search); put("referer", referer) }
}
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
