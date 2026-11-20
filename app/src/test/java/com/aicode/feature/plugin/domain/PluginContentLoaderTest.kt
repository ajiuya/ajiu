package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.data.PluginFiles
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginContentLoaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun content(text: String = PluginCoreFixtures.manifest(), files: Map<String, String> = emptyMap()): ContentLoadResult {
        val root = temporary.newFolder()
        PluginCoreFixtures.tree(root, text, files)
        return PluginContentLoader.load(root, PluginCoreFixtures.manifestValue(text))
    }
    @Test fun skillsUseRealParserButCannotOverrideNamesOrExposeEditableSource() {
        val text = "---\nname: builtin\ndescription: |\n  first\n  second\nrequired_tools: [Bash]\n---\nInstructions."
        val result = content(files = mapOf("skills/hello.md" to text)) as ContentLoadResult.Ok
        val skill = result.content.skills.single().skill
        assertEquals("sample.plugin:hello", skill.name)
        assertEquals("first second", skill.description)
        assertEquals(listOf("Bash"), skill.requiredTools)
        assertNull(skill.dirPath)
    }
    @Test fun agentsUseRealParserAndHostNamespaceAndNoEditableFile() {
        val manifest = PluginCoreFixtures.manifest(""""agents":[{"id":"worker","file":"agents/worker.md"}]""")
        val result = content(manifest, mapOf("agents/worker.md" to "---\nname: Explore\ntools: [readFile]\n---\nInspect code.")) as ContentLoadResult.Ok
        val definition = result.content.agents.single().definition
        assertEquals("sample.plugin:worker", definition.name)
        assertEquals(listOf("readFile"), definition.allowedTools)
        assertNull(definition.filePath)
        assertNull(definition.providerId)
        assertNull(definition.model)
    }
    @Test fun boundProviderModelAndDeprecatedModeAreRejected() {
        val manifest = PluginCoreFixtures.manifest(""""agents":[{"id":"worker","file":"a.md"}]""")
        for (frontmatter in listOf("provider: paid", "model: paid", "mode: auto")) {
            assertTrue(frontmatter, content(manifest, mapOf("a.md" to "---\n$frontmatter\n---\nRun.")) is ContentLoadResult.Failed)
        }
    }
    @Test fun malformedAndDuplicateYamlCannotBypassParserFallback() {
        for (text in listOf("---\n[a,b]\n---\nbody", "---\nname: a\nname: b\n---\nbody", "---\nname: [\n---\nbody")) {
            assertTrue(content(files = mapOf("skills/hello.md" to text)) is ContentLoadResult.Failed)
        }
    }
    @Test fun emptyContributionBodyIsRejected() {
        assertTrue(content(files = mapOf("skills/hello.md" to "---\nname: a\n---\n")) is ContentLoadResult.Failed)
    }
    @Test fun runtimeAndDependenciesAreStaticAndHashedWithoutExecuting() {
        val manifest = PluginCoreFixtures.manifest(runtime = """{"type":"javascript","entry":"main.js","dependencies":{"tool":"npm","file":"package.json"}}""")
        val files = mapOf("main.js" to "throw new Error('must not execute')", "package.json" to """{"scripts":{"install":"touch should-not-exist"}}""")
        val result = content(manifest, files) as ContentLoadResult.Ok
        assertEquals(listOf("node", "main.js"), result.content.runtime?.command)
        assertEquals(PluginFiles.sha256(files.getValue("package.json").toByteArray()), result.content.runtime?.dependencies?.sha256)
        assertFalse(File(result.content.runtime?.installDir, "should-not-exist").exists())
    }
    @Test fun missingRuntimeAndSchemaFilesAreRejected() {
        val manifest = PluginCoreFixtures.manifest(""""tools":[{"id":"task","title":"Task","inputSchema":"schema.json","handler":"task"}]""",
            runtime = """{"type":"python","entry":"main.py"}""")
        assertTrue(content(manifest) is ContentLoadResult.Failed)
        assertTrue(content(manifest, mapOf("main.py" to "print('never executed')")) is ContentLoadResult.Failed)
    }
    @Test fun schemasRejectUnknownRefsDeepRecursionAndInvalidRequired() {
        fun valid(text: String) = PluginToolSchema.validate(PluginJson.parse(text) as JsonObject)
        assertFalse(valid("""{"type":"object","${'$'}ref":"#/loop"}"""))
        assertFalse(valid("""{"type":"object","properties":{"x":{"type":"string"}},"required":["y"]}"""))
        assertFalse(valid("""{"type":"object","properties":{"x":{"type":"unsupported"}}}"""))
        val deep = (1..10).fold("""{"type":"string"}""") { inner, _ -> """{"type":"array","items":$inner}""" }
        assertFalse(valid("""{"type":"object","properties":{"x":$deep}}"""))
    }
    @Test fun schemasSupportFiniteBasicObjectsArraysEnumsAndBounds() {
        val schema = PluginJson.parse("""{"type":"object","properties":{
            "title":{"type":"string","minLength":1,"maxLength":40},
            "count":{"type":"integer","minimum":0,"maximum":10},
            "mode":{"type":"string","enum":["safe","fast"]},
            "items":{"type":"array","items":{"type":"boolean"},"maxItems":8}
        },"required":["title"],"additionalProperties":false}""") as JsonObject
        assertTrue(PluginToolSchema.validate(schema))
    }
    @Test fun schemaLimitsRejectLargePropertiesAndWrongTypedEnums() {
        val properties = (1..129).joinToString(",") { "\"p$it\":{\"type\":\"string\"}" }
        assertFalse(PluginToolSchema.validate(PluginJson.parse("""{"type":"object","properties":{$properties}}""") as JsonObject))
        assertFalse(PluginToolSchema.validate(PluginJson.parse("""{"type":"object","properties":{"x":{"type":"boolean","enum":["true"]}}}""") as JsonObject))
    }
    @Test fun configWorkspaceAndSlotPagesAreAllStaticHtmlContributions() {
        val manifest = PluginCoreFixtures.manifest(""""configPage":"config.html","workspaceTools":[{"id":"work","title":"Work","pageFile":"work.html"}],"uiEntries":[{"id":"settings","slot":"settings.general","title":"Settings","page":"settings.html"}]""")
        val result = content(manifest, mapOf("config.html" to "<html>config</html>", "work.html" to "<html>work</html>", "settings.html" to "<html>settings</html>")) as ContentLoadResult.Ok
        assertEquals(setOf("config", "work", "settings"), result.content.pages.map { it.localId }.toSet())
    }
    @Test fun symlinkResourceIsNeverFollowed() {
        val root = temporary.newFolder()
        PluginCoreFixtures.tree(root)
        val skill = File(root, "skills/hello.md")
        assertTrue(skill.delete())
        val outside = temporary.newFile("outside.md").apply { writeText("Outside") }
        Files.createSymbolicLink(skill.toPath(), outside.toPath())
        assertTrue(PluginContentLoader.load(root, PluginCoreFixtures.manifestValue(PluginCoreFixtures.manifest())) is ContentLoadResult.Failed)
        assertEquals("Outside", outside.readText())
    }
}
