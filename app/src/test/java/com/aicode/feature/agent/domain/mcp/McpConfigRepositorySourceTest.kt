package com.aicode.feature.agent.domain.mcp

import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpConfigRepositorySourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var selection: MutableStateFlow<WorkspaceRepository.Selection>

    private fun repository(): McpConfigRepository {
        val global = temporary.newFolder("global")
        val project = temporary.newFolder("project")
        val workspace = mockk<WorkspaceRepository>()
        val selected = Workspace("project", project.absolutePath, WorkspaceType.INTERNAL)
        every { workspace.currentPath() } returns project.absolutePath
        every { workspace.current } returns MutableStateFlow(selected)
        selection = MutableStateFlow(WorkspaceRepository.Selection(selected))
        every { workspace.selection } returns selection
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns global
        val roots = mockk<ProjectAicodeRoot>()
        every { roots.forPath(any()) } answers { File(firstArg<String>(), ".aicode") }
        val hub = mockk<FileChangeHub>()
        every { hub.watchAicode() } returns emptyFlow()
        every { hub.watchWorkspace(any()) } returns emptyFlow()
        return McpConfigRepository(installer, workspace, roots, hub)
    }

    @Test fun identicalServerConfigurationKeepsItsGlobalOrProjectSource() = runTest {
        val repository = repository()
        val server = McpServerConfig("shared", command = "node", args = listOf("server.js"))
        repository.setGlobalServers(listOf(server))
        val global = repository.getEffectiveEntries().single()
        assertEquals(McpScope.GLOBAL, global.scope)
        repository.checkEntryCurrent(global)
        repository.setProjectServers(listOf(server))
        val project = repository.getEffectiveEntries().single()
        assertEquals(McpScope.PROJECT, project.scope)
        assertNotEquals(global.sourceIdentity, project.sourceIdentity)
        try { repository.checkEntryCurrent(global); fail("Expected override source revocation") } catch (_: CancellationException) { }
        repository.checkEntryCurrent(project)
    }

    @Test fun contentRoundTripCannotRestoreAnOldConfigurationCapture() = runTest {
        val repository = repository()
        val first = McpServerConfig("local", command = "first")
        repository.setGlobalServers(listOf(first))
        val old = repository.getEffectiveEntries().single()
        repository.setGlobalServers(listOf(first.copy(command = "second")))
        repository.setGlobalServers(listOf(first))
        val current = repository.getEffectiveEntries().single()
        assertEquals(old.server, current.server)
        assertNotEquals(old.configurationRevision, current.configurationRevision)
        try { repository.checkEntryCurrent(old); fail("Expected stale generation") } catch (_: CancellationException) { }
        repository.checkEntryCurrent(current)
    }

    @Test fun globalEntryAlsoDependsOnTheProjectOverlayRevision() = runTest {
        val repository = repository()
        repository.setGlobalServers(listOf(McpServerConfig("global", url = "https://example.invalid/mcp")))
        val old = repository.getEffectiveEntries().single()
        repository.setProjectServers(listOf(McpServerConfig("other", command = "node")))
        val current = repository.getEffectiveEntries().first { it.server.name == "global" }
        assertEquals(old.configurationRevision, current.configurationRevision)
        assertNotEquals(old.overlayRevision, current.overlayRevision)
        try { repository.checkEntryCurrent(old); fail("Expected overlay generation check") } catch (_: CancellationException) { }
        repository.checkEntryCurrent(current)
    }

    @Test fun globalHttpEntryWithoutASelectedWorkspaceKeepsAnExplicitNullWorkspaceSource() = runTest {
        val repository = repository()
        repository.setGlobalServers(listOf(McpServerConfig("global", url = "https://example.invalid/mcp")))
        selection.value = WorkspaceRepository.Selection(null)
        val entry = repository.getEffectiveEntries().single()
        assertEquals(McpScope.GLOBAL, entry.scope)
        assertNull(entry.workspacePath)
        assertEquals(selection.value.id, entry.workspaceSelectionId)
        repository.checkEntryCurrent(entry)
        selection.value = WorkspaceRepository.Selection(null)
        try { repository.checkEntryCurrent(entry); fail("Expected selection generation rejection") } catch (_: CancellationException) { }
    }

    @Test fun bothGlobalAndProjectEntriesFreezeTheExactWorkspaceSelectionGeneration() = runTest {
        val repository = repository()
        repository.setGlobalServers(listOf(McpServerConfig("global", command = "global")))
        repository.setProjectServers(listOf(McpServerConfig("project", command = "project")))
        val original = repository.getEffectiveEntries()
        val first = selection.value
        assertTrue(original.all { it.workspaceSelectionId == first.id && it.workspacePath == first.workspace?.path })
        selection.value = WorkspaceRepository.Selection(Workspace("other", temporary.newFolder("other").absolutePath, WorkspaceType.INTERNAL))
        for (entry in original) {
            try { repository.checkEntryCurrent(entry); fail("Expected workspace selection rejection") } catch (_: CancellationException) { }
        }
        selection.value = WorkspaceRepository.Selection(first.workspace)
        for (entry in original) {
            try { repository.checkEntryCurrent(entry); fail("Expected A-B-A selection rejection") } catch (_: CancellationException) { }
        }
        val current = repository.getEffectiveEntries()
        assertTrue(current.all { it.workspaceSelectionId == selection.value.id && it.workspaceSelectionId != first.id })
        current.forEach(repository::checkEntryCurrent)
        try { repository.checkEntryCurrent(current.first().copy(workspaceSelectionId = "")); fail("Expected missing source identity rejection") }
        catch (_: CancellationException) { }
    }
}
