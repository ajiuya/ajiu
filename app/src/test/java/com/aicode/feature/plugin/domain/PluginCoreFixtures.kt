package com.aicode.feature.plugin.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object PluginCoreFixtures {
    fun manifest(
        contributions: String = "\"skills\":[{\"id\":\"hello\",\"file\":\"skills/hello.md\"}]",
        runtime: String? = null,
        id: String = "sample.plugin",
        version: String = "1.0.0"
    ): String = """{
        "manifestVersion":1,"id":"$id","publisher":"${id.substringBefore('.')}",
        "name":{"default":"Sample","zh-CN":"Sample ZH"},"version":"$version",
        "description":"Sample plugin","license":"MIT","repository":"https://example.com/sample/plugin",
        "engines":{"pluginApi":"^1.0.0"},"contributes":{$contributions}
        ${runtime?.let { ",\"runtime\":$it" }.orEmpty()}
    }""".trimIndent()

    fun replaceField(text: String, key: String, value: JsonElement): String {
        val root = PluginJson.parse(text) as JsonObject
        return JsonObject(root.toMutableMap().apply { put(key, value) }).toString()
    }
    fun manifestValue(text: String): PluginManifest = (PluginManifestParser.parse(text) as ManifestParseResult.Success).manifest
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
    }
    fun archive(text: String = manifest(), extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray = zip(
        listOf("plugin.json" to text.toByteArray(), "skills/hello.md" to "---\nname: forged\ndescription: useful\n---\nDo good work.".toByteArray()) + extra
    )
    fun tree(root: File, text: String = manifest(), files: Map<String, String> = emptyMap()) {
        root.mkdirs()
        File(root, "plugin.json").writeText(text)
        File(root, "skills/hello.md").apply { parentFile.mkdirs(); writeText("---\nname: internal\n---\nWork safely.") }
        files.forEach { (path, content) -> File(root, path).apply { parentFile.mkdirs(); writeText(content) } }
    }
    suspend fun install(manager: PluginManager, bytes: ByteArray = archive()): InstalledPlugin {
        val preview = (manager.preview(bytes.inputStream()) as PluginPreviewResult.Success).preview
        return (manager.install(preview.token) as PluginInstallResult.Success).plugin
    }
    suspend fun activate(manager: PluginManager, id: String = "sample.plugin"): PluginCapture {
        val trust = manager.prepareTrust(id)
        check(trust.errors.isEmpty())
        check(manager.enable(id, trust.token) == PluginOperationResult.Success)
        return requireNotNull(manager.capture(id))
    }
}
