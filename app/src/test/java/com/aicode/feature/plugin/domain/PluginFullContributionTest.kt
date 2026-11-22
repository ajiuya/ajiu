package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.data.PluginFiles
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginFullContributionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun load(text: String = PluginFullContributionFixtures.manifest(), files: Map<String, String> = PluginFullContributionFixtures.files()): ContentLoadResult {
        val root = temporary.newFolder()
        PluginCoreFixtures.tree(root, text, files)
        return PluginContentLoader.load(root, PluginCoreFixtures.manifestValue(text))
    }
    @Test fun fullUpstreamPackageShapeParsesAndLoadsAllContributionsWithoutExecution() = runBlocking<Unit> {
        val manifest = PluginCoreFixtures.manifestValue(PluginFullContributionFixtures.manifest())
        val content = (load() as ContentLoadResult.Ok).content
        assertEquals(10, manifest.hooks.size)
        assertEquals(manifest.hooks.map { it.event }, content.hooks.map { it.event })
        assertEquals("sample.plugin:hello", content.skills.single().skill.name)
        assertEquals("sample.plugin:worker", content.agents.single().definition.name)
        assertEquals("sample.plugin:task", content.tools.single().key)
        assertEquals("sample.plugin:work", content.workspaceTools.single().key)
        assertEquals("sample.plugin:model", content.providers.single().key)
        assertEquals("http://127.0.0.1:8123/v1", content.providers.single().baseUrl)
        assertEquals("openai", content.providers.single().format)
        assertEquals("refresh", content.sessionSources.single().handlers.refresh)
        assertEquals("send", content.sessionSources.single().handlers.sendMessage)
        assertEquals("interrupt", content.sessionSources.single().handlers.interruptSession)
        assertEquals("create", content.sessionSources.single().handlers.createSession)
        assertEquals("x", content.overlay!!.surfaces.single().snap)
        assertEquals(PluginUiEntryKind.DATA, content.uiEntries.first { it.slot == PluginUiSlots.BACKUP_DATA }.kind)
        assertEquals(PluginCapability.entries.toSet(), PluginCapability.entries.filter { manifest.capabilityPurpose(it) != null }.toSet())
        assertNotNull(content.shizuku)
        assertNotNull(content.backup)
        assertNotNull(content.modelAccess)
        assertEquals(listOf("node", "--no-warnings", "runtime/main.mjs"), content.runtime!!.command)
        assertTrue(content.runtime.resident)
        assertEquals(PluginFiles.sha256(PluginFullContributionFixtures.files().getValue("package.json").toByteArray()), content.runtime.dependencies!!.sha256)
        assertEquals(setOf("config", "work"), content.pages.map { it.localId }.toSet())
    }
    @Test fun mcpConfigsAreNamespacedAndCannotSelectHostNames() = runBlocking<Unit> {
        val mcp = (load() as ContentLoadResult.Ok).content.mcp
        assertEquals(setOf("remote", "local"), mcp.map { it.originalName }.toSet())
        assertTrue(mcp.all { it.config.name.startsWith("plugin__sample_plugin__connections__") })
        assertTrue(mcp.all { it.key == "sample.plugin:connections:${it.originalName}" })
        assertEquals("yes", mcp.first { it.originalName == "local" }.config.env["FIXTURE"])
        assertEquals("value", mcp.first { it.originalName == "remote" }.config.headers["X-Fixture"])
        val files = PluginFullContributionFixtures.files().toMutableMap()
        files["mcp.json"] = """{"mcpServers":{"remote":{"name":"builtin","url":"https://example.com/mcp"}}}"""
        assertTrue(load(files = files) is ContentLoadResult.Failed)
    }
    @Test fun mcpMalformedDuplicateOrWrongTypedFieldsFailClosed() = runBlocking<Unit> {
        for (text in listOf("{broken", """{"mcpServers":{}}""", """{"mcpServers":{"a":{"command":"node","args":[1]}}}""",
            """{"mcpServers":{"a":{"url":"file:///secret"}}}""", """{"mcpServers":{"a":{"url":"https://example.com","command":"node"}}}""",
            """{"mcpServers":{"a":{"url":"https://example.com"},"a":{"url":"https://example.com"}}}""")) {
            assertTrue(text, load(files = PluginFullContributionFixtures.files() + ("mcp.json" to text)) is ContentLoadResult.Failed)
        }
    }
    @Test fun mcpSanitizedNamespaceCollisionsCannotOverrideAnotherContribution() = runBlocking<Unit> {
        val manifest = PluginCoreFixtures.manifest(""""mcp":[{"id":"a","file":"a.json"},{"id":"a__b","file":"b.json"}]""")
        val files = mapOf("a.json" to """{"mcpServers":{"b__c":{"command":"node"}}}""",
            "b.json" to """{"mcpServers":{"c":{"command":"node"}}}""")
        val first = (load(manifest, files) as ContentLoadResult.Ok).content.mcp.map { it.config.name }
        val second = (load(manifest, files) as ContentLoadResult.Ok).content.mcp.map { it.config.name }
        assertEquals(2, first.distinct().size)
        assertEquals(first, second)
        assertTrue(first.all { it.startsWith("plugin__sample_plugin__") && it.length <= 62 })
    }
    @Test fun allHookEventsKeepTheirTypedModeAndReviewCannotDecide() = runBlocking<Unit> {
        for (event in PluginHookEvents.ALL) {
            val mode = if (event in PluginHookEvents.TRANSFORM) "transform" else "observe"
            val backup = if (event in PluginHookEvents.BACKUP) ",\"backup\":{\"purpose\":\"Export own data\"}" else ""
            val text = PluginCoreFixtures.manifest(""""hooks":[{"id":"test","event":"$event","handler":"handle","mode":"$mode"}]$backup""", PluginFullContributionFixtures.runtime)
            val parsed = PluginCoreFixtures.manifestValue(text)
            assertEquals(mode, parsed.hooks.single().mode)
            assertTrue((load(text) as ContentLoadResult.Ok).content.hooks.single().runtime.resident)
        }
        for (event in PluginHookEvents.ALL) {
            val text = PluginCoreFixtures.manifest(""""hooks":[{"id":"test","event":"$event","handler":"handle","mode":"review"}]""", PluginFullContributionFixtures.runtime)
            assertTrue(event, PluginManifestParser.parse(text) is ManifestParseResult.Failure)
        }
        val observed = PluginCoreFixtures.manifest(""""hooks":[{"id":"notice","event":"agent.permission.review","handler":"notice"}]""", PluginFullContributionFixtures.runtime)
        assertEquals(PluginHookModes.OBSERVE, PluginCoreFixtures.manifestValue(observed).hooks.single().mode)
    }
    @Test fun capabilityDeclarationsArePurposeOnlyAndNeverRuntimeApprovalMethods() = runBlocking<Unit> {
        val text = PluginCoreFixtures.replaceField(PluginFullContributionFixtures.manifest(), "permissions",
            PluginJson.parse("""{"httpTransform":{"purpose":"Rewrite a specific request"},"autostart":{"purpose":"Initialize own service"}}"""))
        val manifest = PluginCoreFixtures.manifestValue(text)
        assertEquals("Rewrite a specific request", manifest.capabilityPurpose(PluginCapability.HTTP_TRANSFORM))
        assertEquals("Initialize own service", manifest.capabilityPurpose(PluginCapability.AUTOSTART))
        for (replacement in listOf("", "x".repeat(201), "line\nline")) {
            val root = PluginJson.parse(PluginFullContributionFixtures.manifest()) as kotlinx.serialization.json.JsonObject
            val contributes = root["contributes"] as kotlinx.serialization.json.JsonObject
            val changed = kotlinx.serialization.json.JsonObject(contributes.toMutableMap().apply {
                put("shizuku", kotlinx.serialization.json.buildJsonObject { put("purpose", kotlinx.serialization.json.JsonPrimitive(replacement)) })
            })
            assertTrue(PluginManifestParser.parse(PluginCoreFixtures.replaceField(root.toString(), "contributes", changed)) is ManifestParseResult.Failure)
        }
    }
    @Test fun missingMcpAndOverlayPagesOrSymlinkResourcesAreRejected() = runBlocking<Unit> {
        for (path in listOf("mcp.json", "pages/overlay.html")) {
            val root = temporary.newFolder()
            PluginCoreFixtures.tree(root, PluginFullContributionFixtures.manifest(), PluginFullContributionFixtures.files())
            Files.delete(File(root, path).toPath())
            assertTrue(PluginContentLoader.load(root, PluginCoreFixtures.manifestValue(PluginFullContributionFixtures.manifest())) is ContentLoadResult.Failed)
            val outside = temporary.newFile().apply { writeText("outside") }
            Files.createSymbolicLink(File(root, path).toPath(), outside.toPath())
            assertTrue(PluginContentLoader.load(root, PluginCoreFixtures.manifestValue(PluginFullContributionFixtures.manifest())) is ContentLoadResult.Failed)
            assertEquals("outside", outside.readText())
        }
    }
    @Test fun utf8ByteLimitsApplyToMcpAndPageResources() = runBlocking<Unit> {
        val files = PluginFullContributionFixtures.files()
        val large = "界".repeat(PluginContentLoader.MAX_SCHEMA_BYTES / 2)
        assertTrue(large.length < PluginContentLoader.MAX_SCHEMA_BYTES)
        assertTrue(large.toByteArray().size > PluginContentLoader.MAX_SCHEMA_BYTES)
        assertTrue(load(files = files + ("mcp.json" to large)) is ContentLoadResult.Failed)
        val page = "界".repeat(PluginContentLoader.MAX_CONTENT_BYTES / 2)
        assertTrue(page.length < PluginContentLoader.MAX_CONTENT_BYTES)
        assertTrue(load(files = files + ("pages/overlay.html" to page)) is ContentLoadResult.Failed)
    }
    @Test fun overlayGeometryPurposesHandlersAndProviderUrlsAreValidated() = runBlocking<Unit> {
        for ((from, to) in listOf("\"snap\":\"x\"" to "\"snap\":\"bad\"", "\"width\":240" to "\"width\":47")) {
            val original = PluginFullContributionFixtures.manifest()
            assertTrue(PluginManifestParser.parse(original.replace(from, to)) is ManifestParseResult.Failure)
        }
        for (url in listOf("file:///secret", "https://user:secret@example.com/v1", "https://example.com/v1?key=value", "https://example.com/#part")) {
            assertTrue(PluginManifestParser.parse(PluginFullContributionFixtures.manifest().replace("http://127.0.0.1:8123/v1/", url)) is ManifestParseResult.Failure)
        }
        assertTrue(PluginManifestParser.parse(PluginFullContributionFixtures.manifest().replace("\"readSession\":\"read\"", "\"readSession\":42")) is ManifestParseResult.Failure)
    }
}
