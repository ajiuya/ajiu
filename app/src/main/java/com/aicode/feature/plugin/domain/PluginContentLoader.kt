package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpConfigParser
import com.aicode.feature.agent.domain.skill.SkillParser
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.plugin.data.PluginFiles
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object PluginContentLoader {
    const val MAX_CONTENT_BYTES = 512 * 1024
    const val MAX_SCHEMA_BYTES = 256 * 1024

    fun load(dir: File, manifest: PluginManifest): ContentLoadResult = try {
        fun read(path: String, maximum: Int = MAX_CONTENT_BYTES) = PluginFiles.readRegularText(dir, path, maximum)
        fun checked(path: String, maximum: Int = MAX_CONTENT_BYTES): File {
            PluginFiles.readRegular(dir, path, maximum)
            return PluginFiles.checkedFile(dir, path)
        }
        fun oneLine(value: String) = value.replace(Regex("\\s+"), " ").trim()
        fun markdown(path: String): String {
            val text = read(path)
            validateFrontmatter(text)
            return text
        }
        val skills = manifest.skills.map { ref ->
            val parsed = SkillParser.parseText(markdown(ref.file), ref.id)
            require(parsed.instructions.isNotBlank())
            PluginSkill(ref.id, parsed.copy(name = pluginContributionName(manifest.id, ref.id),
                description = oneLine(parsed.description), requiredTools = pluginImmutableList(parsed.requiredTools), dirPath = null))
        }
        val agents = manifest.agents.map { ref ->
            val parsed = requireNotNull(AgentDefinitionParser.parseText(markdown(ref.file), ref.id))
            require(parsed.providerId == null && parsed.model == null)
            PluginAgent(ref.id, parsed.copy(name = pluginContributionName(manifest.id, ref.id),
                description = oneLine(parsed.description), allowedTools = pluginImmutableList(parsed.allowedTools),
                disallowedTools = pluginImmutableList(parsed.disallowedTools),
                inject = java.util.Collections.unmodifiableSet(parsed.inject.toSet()), filePath = null))
        }
        val runtime = manifest.runtime?.let { ref ->
            val entry = checked(ref.entry, 4 * 1024 * 1024)
            val dependencies = ref.dependencies?.let { dep ->
                val file = checked(dep.file, 4 * 1024 * 1024)
                val bytes = PluginFiles.readRegular(dir, dep.file, 4 * 1024 * 1024)
                if (dep.tool == "npm") require(PluginJson.parse(PluginFiles.utf8(bytes)) is JsonObject)
                PluginDependency(dep.tool, file, PluginFiles.sha256(bytes))
            }
            PluginRuntime(manifest.id, ref.type, dir, entry,
                pluginImmutableList(ref.command ?: listOf(if (ref.type == PluginRuntimeTypes.PYTHON) "python3" else "node", ref.entry)),
                dependencies, ref.resident)
        }
        if (manifest.hooks.isNotEmpty() || manifest.sessionSources.isNotEmpty()) requireNotNull(runtime)
        val hooks = manifest.hooks.map { ref ->
            require(ref.mode in PluginHookModes.ALL && ref.event in PluginHookEvents.ALL)
            require(ref.mode == if (ref.event in PluginHookEvents.TRANSFORM) PluginHookModes.TRANSFORM else PluginHookModes.OBSERVE)
            PluginHook(manifest.id, ref.id, ref.event, ref.handler, requireNotNull(runtime), ref.mode)
        }
        val mcp = manifest.mcp.flatMap { ref -> loadMcp(manifest.id, ref, read(ref.file, MAX_SCHEMA_BYTES)) }
        require(mcp.map { it.config.name.lowercase(Locale.ROOT) }.distinct().size == mcp.size)
        val providers = manifest.providers.map { ref ->
            PluginProviderDefinition(manifest.id, ref.id, ref.name, ref.description, ref.format, ref.baseUrl, manifest.version.toString())
        }
        val sessionSources = manifest.sessionSources.map { ref ->
            PluginSessionSourceDefinition(manifest.id, ref.id, ref.title, ref.handlers, manifest.version.toString())
        }
        val overlay = manifest.overlay?.let { ref ->
            PluginOverlayDefinition(manifest.id, ref.purpose, pluginImmutableList(ref.surfaces.map { surface ->
                checked(surface.page)
                PluginOverlaySurface(manifest.id, surface.id, surface.page, dir, manifest.version.toString(),
                    surface.width, surface.height, surface.draggable, surface.snap)
            }))
        }
        val tools = manifest.tools.map { ref ->
            val schema = PluginJson.parse(read(ref.inputSchema, MAX_SCHEMA_BYTES)) as? JsonObject
                ?: error("schema object")
            require(PluginToolSchema.validate(schema)) { "unsupported schema" }
            PluginTool(manifest.id, ref.id, ref.title, ref.description, schema, ref.handler, requireNotNull(runtime))
        }
        fun page(id: String, title: LocalizedText, slot: String?, file: String, icon: String): PluginPage {
            checked(file)
            return PluginPage(manifest.id, id, title, slot, file, icon, dir, manifest.version.toString())
        }
        val uiEntries = manifest.uiEntries.map { ref ->
            PluginUiEntry(manifest.id, ref.id, ref.title, ref.slot, ref.icon,
                ref.page?.let { page(ref.id, ref.title, ref.slot, it, ref.icon) }, ref.handler, ref.confirm)
        }
        val workspaceTools = manifest.workspaceTools.map { ref ->
            checked(ref.pageFile)
            PluginWorkspaceTool(manifest.id, ref.id, ref.title, ref.description, ref.icon, ref.category,
                ref.pageFile, dir, manifest.version.toString())
        }
        val configPage = manifest.configPage?.let { page("config", manifest.name, null, it, "settings") }
        val icon = manifest.icon?.let {
            if (PluginManifestParser.isIconFilePath(it)) PluginIcon(file = checked(it, 256 * 1024)) else PluginIcon(name = it)
        }
        ContentLoadResult.Ok(PluginContent(icon, pluginImmutableList(skills), pluginImmutableList(agents),
            pluginImmutableList(workspaceTools), pluginImmutableList(uiEntries), runtime, pluginImmutableList(tools), configPage,
            pluginImmutableList(hooks), pluginImmutableList(mcp), pluginImmutableList(providers), pluginImmutableList(sessionSources),
            manifest.shizuku?.let { PluginShizukuDefinition(manifest.id, it.purpose) }, overlay,
            manifest.backup?.let { PluginBackupDefinition(manifest.id, it.purpose) },
            manifest.modelAccess?.let { PluginModelAccessDefinition(manifest.id, it.purpose) }))
    } catch (e: Exception) {
        ContentLoadResult.Failed(listOf(PluginError(PluginErrorCode.CONTENT_INVALID, e.javaClass.simpleName)))
    }

    private fun loadMcp(pluginId: String, ref: PluginMcpRef, text: String): List<PluginMcpServer> {
        val root = PluginJson.parse(text) as? JsonObject ?: error("mcp root")
        require(root.keys == setOf("mcpServers"))
        val servers = root["mcpServers"] as? JsonObject ?: error("mcp servers")
        require(servers.isNotEmpty() && servers.size <= 64)
        fun string(value: kotlinx.serialization.json.JsonElement?, maximum: Int = 2048): String {
            val result = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("mcp text")
            require(result.isNotBlank() && result.length <= maximum && result.none { it.isISOControl() })
            return result
        }
        fun strings(value: kotlinx.serialization.json.JsonElement?) {
            if (value == null) return
            val list = value as? JsonArray ?: error("mcp list")
            require(list.size <= 128)
            list.forEach { string(it) }
        }
        fun mapping(value: kotlinx.serialization.json.JsonElement?) {
            if (value == null) return
            val map = value as? JsonObject ?: error("mcp map")
            require(map.size <= 128)
            map.forEach { (key, item) ->
                require(key.isNotBlank() && key.length <= 200 && key.none { it.isISOControl() })
                string(item, 8192)
            }
        }
        return servers.map { (name, value) ->
            require(name.length <= 64 && McpServerConfig.isValidName(name))
            val o = value as? JsonObject ?: error("mcp server")
            require(o.keys.all { it in setOf("url", "headers", "command", "args", "env", "enabled", "disabledTools", "auth", "type") })
            require((o["command"] == null) != (o["url"] == null))
            o["command"]?.let { string(it, 200) }
            o["url"]?.let {
                val uri = URI(string(it))
                require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null)
            }
            o["type"]?.let { require(string(it, 16) in setOf("stdio", "http", "sse", "streamable-http")) }
            strings(o["args"])
            strings(o["disabledTools"])
            mapping(o["env"])
            mapping(o["headers"])
            val identityHash = PluginFiles.sha256("$pluginId\u0000${ref.id}\u0000$name".toByteArray(Charsets.UTF_8)).take(12)
            val prefix = "plugin__${pluginId.replace('.', '_')}__${ref.id}__$name"
            val namespaced = "${prefix.take(48)}__$identityHash"
            Json.decodeFromJsonElement(McpServerConfig.serializer(), JsonObject(o.filterKeys { it != "type" } + ("name" to JsonPrimitive(namespaced))))
            val config = requireNotNull(McpConfigParser.parse(text, "PluginContent").firstOrNull { it.name == name }).copy(name = namespaced)
            require(config.auth?.let { !config.isStdio && it.type == "oauth" } != false)
            PluginMcpServer(pluginId, ref.id, name, config.copy(
                args = pluginImmutableList(config.args),
                headers = java.util.Collections.unmodifiableMap(LinkedHashMap(config.headers)),
                env = java.util.Collections.unmodifiableMap(LinkedHashMap(config.env)),
                disabledTools = java.util.Collections.unmodifiableSet(config.disabledTools.toSet()),
                auth = config.auth?.let { auth -> auth.copy(scopes = auth.scopes?.let(::pluginImmutableList)) }))
        }
    }

    private fun validateFrontmatter(text: String) {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return
        val end = normalized.indexOf("\n---", 3)
        require(end >= 0) { "frontmatter boundary" }
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 0
            isAllowDuplicateKeys = false
            nestingDepthLimit = 20
            codePointLimit = MAX_CONTENT_BYTES
        }
        val value = Yaml(SafeConstructor(options)).load<Any>(normalized.substring(4, end))
        require(value == null || value is Map<*, *>) { "frontmatter mapping" }
        val map = value as? Map<*, *> ?: return
        require(map.keys.all { it is String }) { "frontmatter keys" }
        require("mode" !in map) { "deprecated mode" }
    }
}
