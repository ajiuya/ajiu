package com.aicode.feature.plugin.domain

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

object PluginManifestParser {
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_PATH_LENGTH = 200
    const val MAX_PATH_DEPTH = 8
    private val PLUGIN_ID = Regex("[a-z][a-z0-9-]{0,31}\\.[a-z][a-z0-9-]{0,31}")
    private val LOCAL_ID = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val HANDLER = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
    private val ICON = Regex("[a-z][a-z0-9-]{0,31}")
    private val LOCALE = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")
    private val SUPPORTED = setOf("skills", "agents", "tools", "uiEntries", "workspaceTools", "configPage",
        "hooks", "mcp", "providers", "sessionSources", "shizuku", "overlay", "backup", "modelAccess")
    private val UNSUPPORTED = SUPPORTED + setOf("review", "transform")

    fun isValidPluginId(id: String): Boolean = PLUGIN_ID.matches(id)
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH || path.startsWith('/') ||
            path.contains('\\') || path.contains(':') || path.any { it.isISOControl() }) return false
        val segments = path.split('/')
        return segments.size <= MAX_PATH_DEPTH && segments.none { it.isEmpty() || it == "." || it == ".." }
    }
    fun isIconFilePath(value: String): Boolean = value.endsWith(".png", true) || value.endsWith(".webp", true)

    fun parse(text: String, hostApi: SemVer = PluginApi.VERSION): ManifestParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            return ManifestParseResult.Failure(listOf(PluginError(PluginErrorCode.MANIFEST_TOO_LARGE)))
        }
        val root = try { PluginJson.parse(text) as? JsonObject } catch (_: Exception) { null }
            ?: return ManifestParseResult.Failure(listOf(PluginError(PluginErrorCode.MANIFEST_NOT_JSON)))
        return try { ManifestParseResult.Success(Reader(hostApi).read(root)) }
        catch (e: Invalid) { ManifestParseResult.Failure(listOf(e.error)) }
    }
    private class Invalid(val error: PluginError) : Exception(null, null, false, false)
    private class Reader(val hostApi: SemVer) {
        fun fail(path: String, code: PluginErrorCode = PluginErrorCode.FIELD_INVALID): Nothing = throw Invalid(PluginError(code, path))
        fun keys(obj: JsonObject, allowed: Set<String>, path: String) {
            obj.keys.firstOrNull { it !in allowed }?.let {
                fail("$path$it", if (it in UNSUPPORTED || path == "contributes.") PluginErrorCode.UNSUPPORTED_CONTRIBUTION else PluginErrorCode.UNSUPPORTED_FIELD)
            }
        }
        fun obj(value: JsonElement?, path: String): JsonObject = value as? JsonObject
            ?: fail(path, if (value == null) PluginErrorCode.FIELD_MISSING else PluginErrorCode.FIELD_INVALID)
        fun text(value: JsonElement?, path: String, limit: Int = 200, multiline: Boolean = false): String {
            if (value == null) fail(path, PluginErrorCode.FIELD_MISSING)
            val primitive = value as? JsonPrimitive
            val valueText = primitive?.takeIf { it.isString }?.content ?: fail(path)
            if (valueText.isBlank() || valueText.length > limit ||
                valueText.any { it.isISOControl() && !(multiline && it == '\n') }) fail(path)
            return valueText
        }
        fun bool(value: JsonElement?, path: String): Boolean = when (value) {
            null -> false
            else -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail(path)
        }
        fun localized(value: JsonElement?, path: String, limit: Int = 80, multiline: Boolean = false): LocalizedText {
            if (value is JsonPrimitive) return LocalizedText.single(text(value, path, limit, multiline))
            val map = obj(value, path)
            if (map.isEmpty() || map.size > 16) fail(path)
            val result = LinkedHashMap<String, String>()
            map.forEach { (tag, item) ->
                val normal = LocalizedText.normalize(tag)
                if ((normal != "default" && !LOCALE.matches(normal)) || normal in result) fail(path)
                result[normal] = text(item, "$path.$tag", limit, multiline)
            }
            return LocalizedText(result)
        }
        fun file(value: JsonElement?, path: String, extensions: List<String>? = null): String {
            val name = text(value, path)
            if (!isSafeRelativePath(name) || extensions?.none { name.endsWith(it, true) } == true) fail(path)
            return name
        }
        fun icon(value: JsonElement?, path: String, default: String): String = if (value == null) default
            else text(value, path, 32).also { if (!ICON.matches(it)) fail(path) }
        fun <T> list(value: JsonElement?, path: String, read: (JsonObject, String) -> T): List<T> {
            if (value == null) return emptyList()
            val array = value as? JsonArray ?: fail(path)
            if (array.size > 64) fail(path)
            val seen = HashSet<String>()
            return pluginImmutableList(array.mapIndexed { index, entry ->
                val at = "$path[$index]"
                val item = obj(entry, at)
                val id = text(item["id"], "$at.id", 64)
                if (!LOCAL_ID.matches(id)) fail("$at.id")
                if (!seen.add(id.lowercase(Locale.ROOT))) fail("$at.id", PluginErrorCode.DUPLICATE_ID)
                read(item, at)
            })
        }
        fun read(root: JsonObject): PluginManifest {
            keys(root, setOf("\$schema", "manifestVersion", "id", "publisher", "name", "version", "description", "license", "repository", "engines", "runtime", "contributes", "configPage", "icon", "permissions"), "")
            root["\$schema"]?.let { text(it, "\$schema", 2048) }
            val mv = (root["manifestVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: fail("manifestVersion", if (root["manifestVersion"] == null) PluginErrorCode.FIELD_MISSING else PluginErrorCode.FIELD_INVALID)
            if (mv != 1) fail("manifestVersion", PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION)
            val id = text(root["id"], "id", 65)
            if (!isValidPluginId(id)) fail("id")
            val publisher = text(root["publisher"], "publisher", 32)
            if (publisher != id.substringBefore('.')) fail("publisher")
            val version = SemVer.parse(text(root["version"], "version", 128)) ?: fail("version")
            val name = localized(root["name"], "name")
            val description = localized(root["description"], "description", 600, true)
            val license = text(root["license"], "license", 100)
            val repository = text(root["repository"], "repository", 300)
            val uri = runCatching { URI(repository) }.getOrNull() ?: fail("repository")
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.path.orEmpty().trim('/').isEmpty()) fail("repository")
            val engines = obj(root["engines"], "engines")
            keys(engines, setOf("pluginApi"), "engines.")
            val api = text(engines["pluginApi"], "engines.pluginApi", 256)
            val range = SemVerRange.parse(api) ?: fail("engines.pluginApi")
            if (!range.matches(hostApi)) fail("engines.pluginApi", PluginErrorCode.API_INCOMPATIBLE)
            val runtime = root["runtime"]?.let { runtime(obj(it, "runtime")) }
            val contributes = obj(root["contributes"], "contributes")
            keys(contributes, SUPPORTED, "contributes.")
            fun refs(key: String) = list(contributes[key], "contributes.$key") { o, p ->
                keys(o, setOf("id", "file"), "$p.")
                PluginContributionRef(text(o["id"], "$p.id"), file(o["file"], "$p.file", listOf(".md")))
            }
            val skills = refs("skills")
            val agents = refs("agents")
            val tools = list(contributes["tools"], "contributes.tools") { o, p ->
                keys(o, setOf("id", "title", "description", "inputSchema", "handler"), "$p.")
                val handler = text(o["handler"], "$p.handler", 64).also { if (!HANDLER.matches(it)) fail("$p.handler") }
                PluginToolRef(text(o["id"], "$p.id"), localized(o["title"], "$p.title"),
                    o["description"]?.let { localized(it, "$p.description", 600) } ?: LocalizedText.single(""),
                    file(o["inputSchema"], "$p.inputSchema", listOf(".json")), handler)
            }
            val uiEntries = list(contributes["uiEntries"], "contributes.uiEntries") { o, p ->
                keys(o, setOf("id", "slot", "title", "page", "handler", "confirm", "icon"), "$p.")
                val slot = text(o["slot"], "$p.slot", 64)
                if (slot !in PluginUiSlots.ALL) fail("$p.slot", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
                val page = o["page"]?.let { file(it, "$p.page", listOf(".html", ".htm")) }
                val handler = o["handler"]?.let { text(it, "$p.handler", 64).also { h -> if (!HANDLER.matches(h)) fail("$p.handler") } }
                if (slot in PluginUiSlots.DATA_ONLY) {
                    if (page != null || handler != null || o["confirm"] != null) fail(p)
                } else if ((page == null) == (handler == null) || (slot in PluginUiSlots.ACTION_ONLY && page != null) ||
                    (PluginUiSlots.isSettingsSlot(slot) && handler != null)) fail(p)
                PluginUiEntryRef(text(o["id"], "$p.id"), slot, localized(o["title"], "$p.title"), page,
                    handler, bool(o["confirm"], "$p.confirm"), icon(o["icon"], "$p.icon", "book"))
            }
            if (uiEntries.groupingBy { it.slot }.eachCount().values.any { it > PluginUiSlots.MAX_ENTRIES_PER_PLUGIN }) fail("contributes.uiEntries")
            val workspaceTools = list(contributes["workspaceTools"], "contributes.workspaceTools") { o, p ->
                keys(o, setOf("id", "title", "description", "icon", "category", "pageFile"), "$p.")
                PluginWorkspaceToolRef(text(o["id"], "$p.id"), localized(o["title"], "$p.title"),
                    o["description"]?.let { localized(it, "$p.description", 600) } ?: LocalizedText.single(""),
                    icon(o["icon"], "$p.icon", "play"), o["category"]?.let { text(it, "$p.category", 32) } ?: "general",
                    file(o["pageFile"], "$p.pageFile", listOf(".html", ".htm")))
            }
            if (contributes["configPage"] != null && root["configPage"] != null) fail("configPage", PluginErrorCode.DUPLICATE_ID)
            val configPage = (contributes["configPage"] ?: root["configPage"])?.let { file(it, "configPage", listOf(".html", ".htm")) }
            val pageIds = uiEntries.map { it.id } + workspaceTools.map { it.id } + if (configPage != null) listOf("config") else emptyList()
            if (pageIds.map { it.lowercase(Locale.ROOT) }.distinct().size != pageIds.size) fail("pages", PluginErrorCode.DUPLICATE_ID)
            val icon = root["icon"]?.let {
                val raw = text(it, "icon")
                if (isIconFilePath(raw)) file(it, "icon") else icon(it, "icon", "puzzle")
            }
            val hooks = list(contributes["hooks"], "contributes.hooks") { o, p ->
                keys(o, setOf("id", "event", "handler", "mode"), "$p.")
                val event = text(o["event"], "$p.event", 64)
                if (event !in PluginHookEvents.ALL) fail("$p.event", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
                val mode = o["mode"]?.let { text(it, "$p.mode", 16) } ?: PluginHookModes.OBSERVE
                if (mode !in PluginHookModes.ALL) fail("$p.mode", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
                val expected = if (event in PluginHookEvents.TRANSFORM) PluginHookModes.TRANSFORM else PluginHookModes.OBSERVE
                if (mode != expected) fail("$p.mode")
                PluginHookRef(text(o["id"], "$p.id"), event, handler(o["handler"], "$p.handler"), mode)
            }
            val mcp = list(contributes["mcp"], "contributes.mcp") { o, p ->
                keys(o, setOf("id", "file"), "$p.")
                PluginMcpRef(text(o["id"], "$p.id"), file(o["file"], "$p.file", listOf(".json")))
            }
            val providers = list(contributes["providers"], "contributes.providers") { o, p ->
                keys(o, setOf("id", "name", "description", "format", "baseUrl"), "$p.")
                val format = text(o["format"], "$p.format", 32)
                if (format !in PluginProviderFormats.ALL) fail("$p.format", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
                val url = text(o["baseUrl"], "$p.baseUrl", 2048)
                val address = runCatching { URI(url) }.getOrNull() ?: fail("$p.baseUrl")
                if (address.scheme !in setOf("http", "https") || address.host.isNullOrBlank() || address.userInfo != null ||
                    address.rawQuery != null || address.rawFragment != null) fail("$p.baseUrl")
                PluginProviderRef(text(o["id"], "$p.id"), localized(o["name"], "$p.name"),
                    o["description"]?.let { localized(it, "$p.description", 600) } ?: LocalizedText.single(""), format, url.trimEnd('/'))
            }
            val sessionSources = list(contributes["sessionSources"], "contributes.sessionSources") { o, p ->
                keys(o, setOf("id", "title", "handlers"), "$p.")
                val h = obj(o["handlers"], "$p.handlers")
                keys(h, setOf("listSessions", "readSession", "sendMessage", "interruptSession", "createSession", "refresh"), "$p.handlers.")
                fun optional(key: String) = h[key]?.let { handler(it, "$p.handlers.$key") }
                PluginSessionSourceRef(text(o["id"], "$p.id"), localized(o["title"], "$p.title"),
                    PluginSessionSourceHandlersRef(handler(h["listSessions"], "$p.handlers.listSessions"),
                        handler(h["readSession"], "$p.handlers.readSession"), optional("sendMessage"),
                        optional("interruptSession"), optional("createSession"), optional("refresh")))
            }
            fun purpose(key: String): String? = contributes[key]?.let { raw ->
                val declaration = obj(raw, "contributes.$key")
                keys(declaration, setOf("purpose"), "contributes.$key.")
                text(declaration["purpose"], "contributes.$key.purpose")
            }
            val shizuku = purpose("shizuku")?.let(::PluginShizukuRef)
            val backup = purpose("backup")?.let(::PluginBackupRef)
            val modelAccess = purpose("modelAccess")?.let(::PluginModelAccessRef)
            val overlay = contributes["overlay"]?.let { raw ->
                val o = obj(raw, "contributes.overlay")
                keys(o, setOf("purpose", "surfaces"), "contributes.overlay.")
                val surfaces = list(o["surfaces"], "contributes.overlay.surfaces") { s, p ->
                    keys(s, setOf("id", "page", "width", "height", "draggable", "snap"), "$p.")
                    fun edge(key: String, default: Int, maximum: Int): Int {
                        val value = s[key] ?: return default
                        return ((value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail("$p.$key"))
                            .also { if (it !in 48..maximum) fail("$p.$key") }
                    }
                    val snap = s["snap"]?.let { text(it, "$p.snap", 16) } ?: PluginOverlaySnap.NONE
                    if (snap !in PluginOverlaySnap.ALL) fail("$p.snap")
                    PluginOverlaySurfaceRef(text(s["id"], "$p.id"), file(s["page"], "$p.page", listOf(".html", ".htm")),
                        edge("width", 240, 720), edge("height", 320, 1200),
                        s["draggable"]?.let { bool(it, "$p.draggable") } ?: true, snap)
                }
                if (surfaces.isEmpty() || surfaces.size > 3) fail("contributes.overlay.surfaces")
                PluginOverlayRef(text(o["purpose"], "contributes.overlay.purpose"), surfaces)
            }
            if (hooks.any { it.event in PluginHookEvents.BACKUP } && backup == null) fail("contributes.backup", PluginErrorCode.FIELD_MISSING)
            if (uiEntries.any { it.slot in PluginUiSlots.DATA_ONLY } &&
                (backup == null || hooks.none { it.event == PluginHookEvents.BACKUP_EXPORT_BEFORE })) fail("contributes.backup", PluginErrorCode.FIELD_MISSING)
            val permissions = root["permissions"]?.let { raw ->
                val declarations = obj(raw, "permissions")
                val result = LinkedHashMap<PluginCapability, String>()
                declarations.forEach { (key, value) ->
                    val capability = when (key) {
                        "modelAccess" -> PluginCapability.MODEL_ACCESS
                        "shizuku" -> PluginCapability.SHIZUKU
                        "overlay" -> PluginCapability.OVERLAY
                        "backup" -> PluginCapability.BACKUP
                        "httpTransform" -> PluginCapability.HTTP_TRANSFORM
                        "autostart" -> PluginCapability.AUTOSTART
                        else -> fail("permissions.$key", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
                    }
                    val declaration = obj(value, "permissions.$key")
                    keys(declaration, setOf("purpose"), "permissions.$key.")
                    result[capability] = text(declaration["purpose"], "permissions.$key.purpose")
                }
                java.util.Collections.unmodifiableMap(result)
            } ?: emptyMap()
            if ((tools.isNotEmpty() || hooks.isNotEmpty() || sessionSources.isNotEmpty() || uiEntries.any { it.handler != null }) && runtime == null) fail("runtime", PluginErrorCode.FIELD_MISSING)
            val manifest = PluginManifest(mv, id, name, version, publisher, repository, description, license, api,
                skills, agents, workspaceTools, uiEntries, runtime, tools, configPage, icon,
                hooks, mcp, providers, sessionSources, shizuku, overlay, backup, modelAccess, permissions)
            if (manifest.contributionCount == 0) fail("contributes")
            return manifest
        }
        fun handler(value: JsonElement?, path: String): String = text(value, path, 64).also { if (!HANDLER.matches(it)) fail(path) }
        fun runtime(o: JsonObject): PluginRuntimeRef {
            keys(o, setOf("type", "entry", "command", "dependencies", "resident"), "runtime.")
            val type = text(o["type"], "runtime.type", 32)
            if (type !in PluginRuntimeTypes.ALL) fail("runtime.type", PluginErrorCode.UNSUPPORTED_CONTRIBUTION)
            val entry = file(o["entry"], "runtime.entry", if (type == "python") listOf(".py") else listOf(".js", ".mjs", ".cjs"))
            val command = o["command"]?.let { raw ->
                val array = raw as? JsonArray ?: fail("runtime.command")
                if (array.isEmpty() || array.size > 16) fail("runtime.command")
                pluginImmutableList(array.map { text(it, "runtime.command", 200).also { part ->
                    if (part.startsWith('/') || part.contains('\\') || part.contains("..")) fail("runtime.command")
                } })
            }
            val dependencies = o["dependencies"]?.let { raw ->
                val dep = obj(raw, "runtime.dependencies")
                keys(dep, setOf("tool", "file"), "runtime.dependencies.")
                val tool = text(dep["tool"], "runtime.dependencies.tool", 8)
                if (tool !in setOf("npm", "pip") || (type == "python") != (tool == "pip")) fail("runtime.dependencies.tool")
                PluginDependencyRef(tool, file(dep["file"], "runtime.dependencies.file", if (tool == "npm") listOf(".json") else listOf(".txt")))
            }
            return PluginRuntimeRef(type, entry, command, dependencies, bool(o["resident"], "runtime.resident"))
        }
    }
}
