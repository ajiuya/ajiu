package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.feature.agent.domain.skill.*
import com.aicode.feature.agent.domain.subagent.*
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteAgentConfigRepositorySourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val native = TestFileAccessProvider()
    private var source = "endpoint-a:profile-a:uuid-a"
    private var fail = false
    private var afterSnapshot: () -> Unit = {}
    private val requests = mutableListOf<Triple<String, AgentConfigKind, Boolean>>()
    private val provider = object : FileAccessProvider by native {
        override fun agentConfigSourceIdentity() = source
        private fun mapped(path: String): String = File(temporary.root, path.removePrefix("~/")).path
        override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot {
            requests += Triple(root, kind, forceRefresh)
            if (fail) throw IOException("remote offline")
            val directory = File(mapped(root))
            val candidates = if (kind == AgentConfigKind.AGENTS) directory.listFiles().orEmpty().filter { it.isFile && it.extension == "md" }
                else directory.walkTopDown().filter { it.isFile && (it.name.equals("SKILL.md", true) || it.name.equals("CLAUDE.md", true)) }
                    .groupBy { it.parentFile }.values.map { list -> list.sortedBy { if (it.name.equals("SKILL.md", true)) 0 else 1 }.first() }
            val files = candidates.associate { root + "/" + it.relativeTo(directory).invariantSeparatorsPath to it.readText() }
            afterSnapshot()
            return AgentConfigSnapshot(source, "revision", files)
        }
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) = native.writeFile(mapped(path), content, overwrite, encoding)
        override fun readFile(path: String) = native.readFile(mapped(path))
        override fun isFile(path: String) = native.isFile(mapped(path))
        override fun isDirectory(path: String) = native.isDirectory(mapped(path))
        override fun listFiles(path: String) = native.listFiles(mapped(path))
        override fun mkdirs(path: String) = native.mkdirs(mapped(path))
        override fun delete(path: String) = native.delete(mapped(path))
        override fun deleteRecursively(path: String) = native.deleteRecursively(mapped(path))
    }
    private val pluginSource = mockk<PluginDefinitionSource>().apply {
        every { revision } returns 0
        every { skills() } returns emptyList()
        every { agents() } returns emptyList()
    }
    private fun write(path: String, text: String) = provider.writeFile(path, text)
    private fun skillRepository(disabled: Set<String> = emptySet()): SkillRepository {
        val config = mockk<SkillConfigRepository>()
        every { config.disabledNames() } returns disabled
        return SkillRepository(GlobalDirectorySkillSource(provider), ProjectDirectorySkillSource(provider), config, provider, pluginSource)
    }
    private fun agentRepository(disabled: Set<String> = emptySet()): AgentDefinitionRepository {
        val config = mockk<AgentDefinitionConfigRepository>()
        every { config.disabledNames() } returns disabled
        return AgentDefinitionRepository(GlobalDirectoryAgentSource(provider), ProjectDirectoryAgentSource(provider), config, provider, pluginSource)
    }

    @Test fun skillSourcesKeepProjectPrioritySkillMdPreferenceFrontmatterAndCompleteInstructions() {
        write("~/.aicode/skills/actual-global/CLAUDE.md", "---\nname: shared\n---\nglobal")
        write("~/workspace/.aicode/skills/actual-project/CLAUDE.md", "---\nname: wrong\n---\nlegacy")
        write("~/workspace/.aicode/skills/actual-project/SKILL.md", "---\nname: shared\ndescription: project\nrequired_tools: [readFile]\n---\n" + "full instruction\n".repeat(1000))
        val repository = skillRepository()
        val listed = repository.listAllSkills().single()
        assertEquals(SkillScope.PROJECT, listed.scope)
        assertEquals("~/workspace/.aicode/skills/actual-project", listed.skill.dirPath)
        assertEquals(listOf("readFile"), listed.skill.requiredTools)
        requests.clear()
        assertEquals("full instruction\n".repeat(1000).trim(), repository.loadInstructions("SHARED"))
        assertTrue(requests.all { it.third })
        assertEquals(1, requests.size)
        assertTrue(skillRepository(setOf("shared")).listSkills().isEmpty())
    }

    @Test fun globalSkillSaveAndDeleteFindTheirOwnScopeEvenWhenProjectShadowsIt() {
        write("~/.aicode/skills/actual-global/CLAUDE.md", "---\nname: shared\n---\nglobal")
        write("~/workspace/.aicode/skills/actual-project/SKILL.md", "---\nname: shared\n---\nproject")
        val repository = skillRepository()
        assertNull(repository.save(SkillForm(name = "shared", description = "updated", instructions = "new global"), SkillScope.GLOBAL, "shared"))
        assertTrue(provider.readFile("~/.aicode/skills/actual-global/CLAUDE.md").contains("new global"))
        assertFalse(provider.isDirectory("~/.aicode/skills/shared"))
        assertEquals("project", repository.loadInstructions("shared"))
        assertTrue(repository.deleteSkill("shared", SkillScope.GLOBAL))
        assertTrue(provider.isFile("~/workspace/.aicode/skills/actual-project/SKILL.md"))
        assertFalse(provider.isDirectory("~/.aicode/skills/actual-global"))
    }

    @Test fun sourceChangesDuringSkillOrAgentScanCannotReturnMergedDefinitions() {
        write("~/.aicode/skills/one/SKILL.md", "body")
        afterSnapshot = { source = "endpoint-b:profile-b:uuid-b" }
        assertThrows(CancellationException::class.java) { skillRepository().listAllSkills() }
        source = "endpoint-a:profile-a:uuid-a"
        assertThrows(CancellationException::class.java) { agentRepository().listAll() }
    }

    @Test fun loadAndFindDoNotTreatRemoteFailureAsEmptyOrUsePreviouslyListedBodies() {
        write("~/.aicode/skills/one/SKILL.md", "---\nname: one\n---\ncomplete")
        write("~/.aicode/agents/one.md", "---\nname: one\n---\ncomplete")
        val skills = skillRepository()
        val agents = agentRepository()
        assertEquals(1, skills.listAllSkills().size)
        assertEquals(1, agents.listAll().size)
        fail = true
        assertThrows(IOException::class.java) { skills.loadInstructions("one") }
        assertThrows(IOException::class.java) { agents.find("one") }
    }

    @Test fun agentFindHonorsProjectPriorityDisabledNamesAndCompleteRealPaths() {
        write("~/.aicode/agents/global.md", "---\nname: shared\n---\nglobal")
        write("~/workspace/.aicode/agents/real-project.md", "---\nname: shared\ntools: [readFile]\n---\nproject body")
        val repository = agentRepository()
        assertEquals("project body", repository.find("shared")!!.prompt)
        assertEquals("~/workspace/.aicode/agents/real-project.md", repository.find("shared")!!.filePath)
        assertTrue(requests.all { it.third })
        assertNull(agentRepository(setOf("shared")).find("shared"))
        assertEquals("project body", agentRepository(setOf("shared")).findIncludingDisabled("shared")!!.prompt)
    }

    @Test fun globalAgentSaveAndDeleteUseUnmergedGlobalSource() {
        write("~/.aicode/agents/real-global.md", "---\nname: shared\n---\nglobal")
        write("~/workspace/.aicode/agents/real-project.md", "---\nname: shared\n---\nproject")
        val repository = agentRepository()
        val before = repository.definitionsVersion
        assertNull(repository.save(AgentDefinitionForm(name = "shared", description = "", prompt = "changed"), AgentDefinitionScope.GLOBAL, "shared"))
        assertTrue(provider.readFile("~/.aicode/agents/real-global.md").contains("changed"))
        assertFalse(provider.isFile("~/.aicode/agents/shared.md"))
        assertTrue(repository.delete("shared", AgentDefinitionScope.GLOBAL))
        assertEquals(before + 2, repository.definitionsVersion)
        assertTrue(provider.isFile("~/workspace/.aicode/agents/real-project.md"))
        assertEquals("project", repository.find("shared")!!.prompt)
    }
}
