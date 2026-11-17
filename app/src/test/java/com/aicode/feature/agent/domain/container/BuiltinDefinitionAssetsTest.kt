package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class BuiltinDefinitionAssetsTest {
    private fun bundledAgents() = listOf(File("src/main/assets/agents"), File("app/src/main/assets/agents")).first { it.isDirectory }
    private fun definitions() = bundledAgents().listFiles()!!.associate { file ->
        val provider = mockk<FileAccessProvider>()
        every { provider.readFile(file.path) } answers { file.readText() }
        file.nameWithoutExtension to requireNotNull(AgentDefinitionParser.parse(provider, file.path))
    }

    @Test fun exactlyFiveChineseDefaultNamesMatchTheirFilenamesAndParseWithoutModelBindings() {
        val definitions = definitions()
        assertEquals(setOf("探索员", "编码员", "审核员", "测试员", "架构师"), definitions.keys)
        definitions.forEach { (filename, definition) ->
            assertEquals(filename, definition.name)
            assertTrue(definition.description.isNotBlank())
            assertTrue(definition.prompt.isNotBlank())
            assertNull(definition.providerId)
            assertNull(definition.model)
            assertNull(definition.reasoningEffort)
            val text = File(bundledAgents(), "$filename.md").readText()
            assertFalse(Regex("(?m)^(provider|model|tier|mode):").containsMatchIn(text))
            assertFalse(text.contains("/root/.aicode"))
            assertTrue(definition.prompt.contains("DENY"))
            assertTrue(definition.prompt.contains("submitPlan"))
        }
        BuiltinDefinitionPaths.validate(bundledAgents().listFiles()!!.associate { it.name to it.readBytes() }, emptyMap())
    }

    @Test fun explorationReviewArchitectureOnlyHaveReadAndCommunicationTools() {
        val allowed = setOf("readFile", "list", "search", "websearch", "webfetch", "loadSkill", "viewImage", "messageParent")
        val universe = allowed + setOf("writeFile", "editFile", "Bash", "terminal", "Root", "Shizuku", "submitPlan", "task", "browser", "memory", "manageMcp", "generateImage", "mcp__server__write")
        val definitions = definitions()
        for (name in listOf("探索员", "审核员", "架构师")) {
            val definition = definitions.getValue(name)
            assertEquals(allowed, definition.allowedTools.toSet())
            assertEquals(allowed, definition.filterToolNames(universe.toList()).toSet())
            assertEquals("文件读取 是 · 文件修改 否 · 执行命令 否", definition.capabilitySummary())
        }
    }

    @Test fun codingAndTestingOnlyAddScopedFileWritesCommandsAndCannotNestOrElevateDirectly() {
        val allowed = setOf("readFile", "list", "search", "websearch", "webfetch", "loadSkill", "viewImage", "messageParent", "writeFile", "editFile", "Bash", "terminal")
        val definitions = definitions()
        for (name in listOf("编码员", "测试员")) {
            val definition = definitions.getValue(name)
            assertEquals(allowed, definition.allowedTools.toSet())
            assertEquals("文件读取 是 · 文件修改 是 · 执行命令 是", definition.capabilitySummary())
            val dangerous = listOf("task", "Root", "Shizuku", "submitPlan", "browser", "manageMcp", "mcp__server__write")
            assertTrue(definition.filterToolNames(dangerous).isEmpty())
            assertTrue(definition.filterToolNames(allowed.toList()).containsAll(allowed))
        }
    }

    private fun manifest(files: Map<String, ByteArray>) = BuiltinDefinitionAssets.skillManifest(
        listAssets = { directory ->
            val prefix = "$directory/"
            files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        },
        readAsset = { files[it] ?: throw IOException("missing asset") }
    )

    @Test fun recursivelyDiscoversEverySkillAndCompanionByteWithoutExecutingScripts() {
        val files = linkedMapOf(
            "skills/dev-god/SKILL.md" to "---\nname: dev-god\n---\nbody".toByteArray(),
            "skills/dev-god/source/deep/source.md" to ByteArray(80000) { (it % 251).toByte() },
            "skills/dev-god/docs/guide.md" to "docs".toByteArray(),
            "skills/dev-god/scripts/x.py" to "raise RuntimeError('do not run')".toByteArray(),
            "skills/new-skill/SKILL.md" to "---\nname: new-skill\n---\nbody".toByteArray()
        )
        val manifest = manifest(files)
        assertEquals(setOf("dev-god", "new-skill"), manifest.keys)
        assertEquals(setOf("SKILL.md", "source/deep/source.md", "docs/guide.md", "scripts/x.py"), manifest.getValue("dev-god").keys)
        files.forEach { (path, bytes) ->
            val relative = path.removePrefix("skills/")
            assertArrayEquals(bytes, manifest.getValue(relative.substringBefore('/')).getValue(relative.substringAfter('/')))
        }
    }

    @Test fun invalidOrUnreadableAssetTreeNeverReturnsAPartialPackage() {
        assertThrows(IllegalArgumentException::class.java) { manifest(mapOf("skills/dev-god/docs/a.md" to byteArrayOf())) }
        assertThrows(IllegalArgumentException::class.java) { manifest(mapOf("skills/dev-god/SKILL.md" to byteArrayOf(), "skills/dev-god/../a" to byteArrayOf())) }
        assertThrows(IOException::class.java) {
            BuiltinDefinitionAssets.skillManifest(
                listAssets = { when (it) {
                    "skills" -> listOf("dev-god")
                    "skills/dev-god" -> listOf("SKILL.md", "script.py")
                    else -> emptyList()
                } },
                readAsset = { if (it.endsWith("script.py")) throw IOException("asset read failed") else "body".toByteArray() }
            )
        }
    }
}
