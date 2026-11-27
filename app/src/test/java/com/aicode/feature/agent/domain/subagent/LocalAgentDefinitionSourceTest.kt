package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.skill.LocalSkillDefinitionFixture
import io.mockk.Called
import io.mockk.every
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentDefinitionSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(root: File) {
        val common = LocalSkillDefinitionFixture(root)
        val source = LocalAgentDefinitionSource(common.context, common.workspace, common.mode,
            common.settings, common.remote, common.remoteCache)
        val config = AgentDefinitionConfigRepository(common.installer, common.projectRoot, common.hub, common.backend, source)
        val repository = AgentDefinitionRepository(GlobalDirectoryAgentSource(common.backend), ProjectDirectoryAgentSource(common.backend),
            config, common.backend, common.plugins, source)
        init { every { common.plugins.agents() } returns emptyList() }
        fun definition(name: String, body: String = "body", scope: AgentDefinitionScope = AgentDefinitionScope.GLOBAL,
            filename: String = "$name.md"): File {
            val root = if (scope == AgentDefinitionScope.GLOBAL) File(common.files, "aicode/agents")
                else File(common.current.value!!.path, ".aicode/agents")
            return File(root, filename).apply {
                parentFile!!.mkdirs()
                writeText("---\nname: $name\ndescription: fixture\ntools: [readFile]\n---\n$body")
            }
        }
        fun assertNoExecution() { verify { common.backend wasNot Called; common.factory wasNot Called } }
    }

    @Test fun uncreatedChrootListsAllFiveRolesWithoutFreezingOrAccessingTheGuest() = runBlocking {
        val f = Fixture(temporary.newFolder())
        val names = setOf("探索员", "编码员", "审核员", "测试员", "架构师")
        names.forEach { f.definition(it) }
        val snapshot = f.repository.readDefinitionSnapshot(f.common.factory, forceRefresh = true)
        assertEquals(names, snapshot.entries.map { it.definition.name }.toSet())
        assertTrue(snapshot.disabledNames.isEmpty())
        assertTrue(snapshot.entries.all { it.definition.filePath!!.startsWith("~/.aicode/agents/") })
        f.assertNoExecution()
    }

    @Test fun projectOverrideAndDisabledRolesAgreeBetweenSettingsAndDispatch() = runBlocking {
        val f = Fixture(temporary.newFolder())
        f.definition("编码员", "global")
        f.definition("编码员", "project", AgentDefinitionScope.PROJECT)
        f.definition("审核员")
        f.repository.setDisabled("审核员", true, AgentDefinitionScope.GLOBAL)
        val snapshot = f.repository.readDefinitionSnapshot(f.common.factory)
        assertEquals(2, snapshot.entries.size)
        assertEquals(setOf("审核员"), snapshot.disabledNames)
        assertEquals("project", f.repository.find("编码员")!!.prompt)
        assertNull(f.repository.find("审核员"))
        assertEquals("审核员", f.repository.findIncludingDisabled("审核员")!!.name)
        f.repository.setDisabled("审核员", false, AgentDefinitionScope.GLOBAL)
        assertEquals(2, f.repository.listEnabled().size)
        f.assertNoExecution()
    }

    @Test fun editingKeepsTheOriginalFilenameAndOnlyDeletesTheRequestedScope() {
        val f = Fixture(temporary.newFolder())
        val global = f.definition("编码员", "global", filename = "custom-file.md")
        val project = f.definition("编码员", "project", AgentDefinitionScope.PROJECT)
        val before = f.repository.sourceIdentity()
        assertNull(f.repository.save(AgentDefinitionForm(name = "编码员", description = "", prompt = "updated"), AgentDefinitionScope.GLOBAL, "编码员"))
        assertTrue(global.readText().contains("updated"))
        assertFalse(File(global.parentFile, "编码员.md").exists())
        assertNotEquals(before, f.repository.sourceIdentity())
        assertTrue(f.repository.delete("编码员", AgentDefinitionScope.GLOBAL))
        assertFalse(global.exists())
        assertTrue(project.exists())
        assertEquals("project", f.repository.find("编码员")!!.prompt)
        f.assertNoExecution()
    }

    @Test fun newDefinitionCanBeRenamedAndConflictingNamesDoNotOverwriteIt() {
        val f = Fixture(temporary.newFolder())
        assertNull(f.repository.save(AgentDefinitionForm(name = "新代理", description = "", prompt = "new body"), AgentDefinitionScope.PROJECT))
        assertNull(f.repository.save(AgentDefinitionForm(name = "重命名", description = "", prompt = "renamed body"), AgentDefinitionScope.PROJECT, "新代理"))
        assertNull(f.repository.find("新代理"))
        assertEquals("renamed body", f.repository.find("重命名")!!.prompt)
        assertEquals(AgentSaveError.NAME_CONFLICT,
            f.repository.save(AgentDefinitionForm(name = "重命名", description = "", prompt = "overwrite"), AgentDefinitionScope.PROJECT))
        assertEquals("renamed body", f.repository.find("重命名")!!.prompt)
        f.assertNoExecution()
    }

    @Test fun corruptDisabledConfigurationIsAnErrorRatherThanReenablingEveryRole() {
        val f = Fixture(temporary.newFolder())
        f.definition("审核员")
        File(f.common.files, "aicode/agents.json").writeText("{broken")
        assertThrows(Exception::class.java) { f.repository.listEnabled() }
        f.assertNoExecution()
    }

    @Test fun workspaceAbaInvalidatesOldCapturesAndNeverWritesToTheNewWorkspace() = runBlocking {
        val f = Fixture(temporary.newFolder())
        val capture = f.source.capture()!!
        f.common.select(f.common.second)
        f.common.select(f.common.first)
        val failure = runCatching { f.source.setDisabled(capture, "编码员", true, AgentDefinitionScope.PROJECT) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(File(f.common.first, ".aicode/agents.json").exists())
        assertFalse(File(f.common.second, ".aicode/agents.json").exists())
        f.assertNoExecution()
    }

    @Test fun scopeSymlinkCannotReadOrOverwriteDefinitionsOutsideTheSelectedRoots() {
        val f = Fixture(temporary.newFolder())
        val outside = temporary.newFolder("outside")
        File(outside, "agents").mkdirs()
        val sentinel = File(outside, "agents/secret.md").apply { writeText("---\nname: secret\n---\noutside") }
        Files.createSymbolicLink(File(f.common.first, ".aicode").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { f.repository.listAll() }
        assertEquals("---\nname: secret\n---\noutside", sentinel.readText())
        f.assertNoExecution()
    }
}
