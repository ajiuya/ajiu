package com.aicode.feature.agent.domain.mcp

import android.app.Application
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class McpManagerRuntimeSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = ChrootInstance("instance", 1, 2, 3)
    private val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "phone", localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = instance)
    private val entries = MutableStateFlow(listOf(McpServerEntry(McpServerConfig("server", command = "node"), McpScope.GLOBAL, 1L, "global")))
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("work", "/work", WorkspaceType.INTERNAL)))
    private val owners = mutableListOf<RuntimeOwner>()
    private val sourceChecks = mutableListOf<() -> Unit>()
    private var authorized = true
    private var connects = 0
    private var beforeEntriesReturn: () -> Unit = {}
    private val workspaces = mockk<WorkspaceRepository>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val engine = mockk<LinuxContainerEngine>()
    private val commands = mockk<LocalRuntimeCommands>()
    private lateinit var snapshot: ContainerProfileSnapshot
    private val capturePaths = mutableListOf<String>()
    private val localDirectories = mutableListOf<Pair<String, String>>()
    private lateinit var manager: McpManager

    @Before fun setup() {
        mockkConstructor(McpClient::class)
        coEvery { anyConstructed<McpClient>().connect() } answers { connects++; 1 }
        every { anyConstructed<McpClient>().tools } returns emptyList()
        every { anyConstructed<McpClient>().close() } returns Unit
        val config = mockk<McpConfigRepository>()
        entries.value = entries.value.map { it.copy(workspacePath = selection.value.workspace?.path, workspaceSelectionId = selection.value.id) }
        coEvery { config.getEffectiveEntries() } answers {
            val captured = entries.value
            beforeEntriesReturn()
            captured
        }
        every { config.effectiveEntriesFlow } returns entries
        every { config.serialize(any()) } answers { firstArg<List<McpServerConfig>>().toString() }
        every { config.checkEntryCurrent(any()) } answers {
            if (firstArg<McpServerEntry>() !in entries.value) throw CancellationException("Config source changed")
        }
        every { workspaces.selection } returns selection
        every { workspaces.localMcpWorkspace(any()) } answers {
            val source = firstArg<String>()
            val directory = File(temporary.root, "phone-mcp/$source").apply { mkdirs() }.absolutePath
            localDirectories += source to directory
            directory
        }
        snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 2L, 3L, Any())
        coEvery { settings.captureProfileSnapshot() } answers { snapshot }
        every { settings.checkProfileSnapshot(any()) } answers {
            if (firstArg<ContainerProfileSnapshot>() !== snapshot) throw CancellationException("Profile changed")
        }
        every { settings.activeProfileSelectionRevisions } returns MutableStateFlow(2L)
        every { settings.profileConfigurationRevisions } returns MutableStateFlow(3L)
        every { engine.notReadyHintFor(any()) } returns null
        coEvery { commands.capture(any(), any(), any(), any(), any(), any()) } answers {
            val owner = arg<RuntimeOwner>(3)
            val check = arg<() -> Unit>(5)
            check()
            owners += owner
            sourceChecks += check
            capturePaths += arg<String>(2)
            if (!authorized) throw LocalRuntimeAuthorizationRequired()
            LocalRuntimeCapture(owner, firstArg<ContainerProfile>().id, arg<Long>(1), instance, arg<String>(2), "/root", "digest", Any(), check)
        }
        val plugins = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "registry/state.json"))
        manager = McpManager(config, ToolRegistry(), mockk(), engine, workspaces, settings, mockk(), plugins, commands)
    }

    @After fun cleanup() {
        selection.value = WorkspaceRepository.Selection(null)
        unmockkConstructor(McpClient::class)
    }

    @Test fun authorizationWaitRetryKeepsServerOwnerButScopeChangesReplaceIt() = runBlocking<Unit> {
        authorized = false
        manager.reloadServer("server")
        authorized = true
        manager.reloadServer("server")
        assertEquals(owners[0], owners[1])
        assertEquals(RuntimeOwnerKind.MCP, owners[0].kind)
        entries.value = listOf(entries.value.single().copy(scope = McpScope.PROJECT, sourceIdentity = "project", configurationRevision = 2L))
        manager.reloadServer("server")
        assertNotEquals(owners[1], owners[2])
        try { sourceChecks[0](); fail("Expected obsolete scope source") } catch (_: CancellationException) { }
        sourceChecks[2]()
    }

    @Test fun connectingInsideAChatStillCapturesAnIndependentMcpOwner() = runBlocking<Unit> {
        val chat = RuntimeOwner(RuntimeOwnerKind.AGENT, "chat")
        val execution = WorkspaceExecutionContext(com.aicode.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT,
            "chat", mockk(), null, null, runtimeOwner = chat) {}
        withContext(execution) { manager.reloadServer("server") }
        assertEquals(RuntimeOwnerKind.MCP, owners.single().kind)
        assertNotEquals(chat, owners.single())
    }

    @Test fun workspaceRoundTripRevokesOldConnectionSourceEvenAtSamePath() = runBlocking<Unit> {
        manager.reloadServer("server")
        val old = owners.single()
        selection.value = WorkspaceRepository.Selection(Workspace("other", "/other", WorkspaceType.INTERNAL))
        selection.value = WorkspaceRepository.Selection(Workspace("work", "/work", WorkspaceType.INTERNAL))
        try { sourceChecks.single()(); fail("Expected old connection source") } catch (_: CancellationException) { }
        entries.value = entries.value.map { it.copy(workspacePath = selection.value.workspace?.path, workspaceSelectionId = selection.value.id) }
        manager.reloadServer("server")
        assertNotEquals(old, owners.last())
        sourceChecks.last()()
    }

    private fun remote(default: ContainerProfile = profile) {
        val selected = ContainerProfile.BUILTIN_ALPINE.copy(id = "remote", mode = com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH,
            rootfsSource = com.aicode.feature.agent.domain.container.RootfsSource.RemoteSsh("server-channel", "/remote/project"))
        snapshot = ContainerProfileSnapshot(selected.id, default.id, listOf(selected, default), 2L, 3L, Any())
    }

    @Test fun sshDefaultChrootUsesAnIndependentPhoneServiceDirectoryAcrossAuthorizationRetries() = runBlocking<Unit> {
        remote()
        val remotePath = File(temporary.root, "remote-project-with-same-local-name").apply { mkdirs() }.absolutePath
        File(remotePath, "phone-secret").writeText("not an MCP workspace")
        selection.value = WorkspaceRepository.Selection(Workspace("remote", remotePath, WorkspaceType.INTERNAL))
        entries.value = entries.value.map { it.copy(workspacePath = remotePath, workspaceSelectionId = selection.value.id) }
        authorized = false
        manager.reloadServer("server")
        manager.reloadServer("server")
        assertEquals(owners[0], owners[1])
        assertEquals(capturePaths[0], capturePaths[1])
        assertNotEquals(remotePath, capturePaths.first())
        assertTrue(capturePaths.all { it.startsWith(File(temporary.root, "phone-mcp").absolutePath + "/") && it != remotePath })
        assertEquals(1, localDirectories.map { it.first }.distinct().size)
        assertTrue(File(capturePaths.first()).list()!!.isEmpty())
        assertEquals("not an MCP workspace", File(remotePath, "phone-secret").readText())
        val old = owners.first()
        selection.value = WorkspaceRepository.Selection(Workspace("other", "/remote/other", WorkspaceType.INTERNAL))
        selection.value = WorkspaceRepository.Selection(Workspace("remote", remotePath, WorkspaceType.INTERNAL))
        try { sourceChecks.first()(); fail("Expected remote selection generation revocation") } catch (_: CancellationException) { }
        entries.value = entries.value.map { it.copy(workspaceSelectionId = selection.value.id) }
        manager.reloadServer("server")
        assertNotEquals(old, owners.last())
        assertEquals(capturePaths.first(), capturePaths.last())
    }

    @Test fun sshDefaultProotRetainsExistingBehaviorAndHttpNeverCreatesALocalServiceWorkspace() = runBlocking<Unit> {
        remote(ContainerProfile.BUILTIN_ALPINE)
        manager.reloadServer("server")
        assertEquals(1, connects)
        assertTrue(capturePaths.isEmpty())
        verify(exactly = 1) { engine.notReadyHintFor(ContainerProfile.BUILTIN_ALPINE) }
        verify(exactly = 0) { workspaces.localMcpWorkspace(any()) }
        entries.value = entries.value.map { it.copy(server = McpServerConfig("server", url = "https://example.invalid/mcp")) }
        remote()
        manager.reloadServer("server")
        assertEquals(2, connects)
        assertTrue(capturePaths.isEmpty())
        verify(exactly = 0) { workspaces.localMcpWorkspace(any()) }
    }

    @Test fun entryReturnedForAnotherSelectionCannotCreateAChrootRequestOrConnectHttp() = runBlocking<Unit> {
        for (http in listOf(false, true)) {
            val captured = WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL))
            selection.value = captured
            entries.value = entries.value.map { it.copy(server = if (http) McpServerConfig("server", url = "https://example.invalid/mcp")
                else McpServerConfig("server", command = "node"), workspacePath = "/work/A", workspaceSelectionId = captured.id) }
            beforeEntriesReturn = { selection.value = WorkspaceRepository.Selection(Workspace("B", "/work/B", WorkspaceType.INTERNAL)) }
            try { manager.reloadServer("server"); fail("Expected frozen entry selection rejection") } catch (_: CancellationException) { }
            beforeEntriesReturn = {
                selection.value = WorkspaceRepository.Selection(Workspace("B", "/work/B", WorkspaceType.INTERNAL))
                selection.value = WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL))
            }
            try { manager.reloadServer("server"); fail("Expected A-B-A selection rejection") } catch (_: CancellationException) { }
        }
        assertEquals(0, connects)
        coVerify(exactly = 0) { commands.capture(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { commands.startStdio(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { workspaces.localMcpWorkspace(any()) }
    }

    @Test fun noSelectedWorkspaceAllowsGlobalHttpButCannotCreateAChrootStdioRequest() = runBlocking<Unit> {
        selection.value = WorkspaceRepository.Selection(null)
        entries.value = entries.value.map { it.copy(workspacePath = null, workspaceSelectionId = selection.value.id) }
        manager.reloadServer("server")
        assertEquals(0, connects)
        entries.value = entries.value.map { it.copy(server = McpServerConfig("server", url = "https://example.invalid/mcp")) }
        manager.reloadServer("server")
        assertEquals(1, connects)
        coVerify(exactly = 0) { commands.capture(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { workspaces.localMcpWorkspace(any()) }
    }

    @Test fun aManuallyConstructedEntryWithoutASelectionIdNeverActsAsAWildcard() = runBlocking<Unit> {
        entries.value = entries.value.map { it.copy(workspaceSelectionId = "") }
        try { manager.reloadServer("server"); fail("Expected missing selection identity rejection") } catch (_: CancellationException) { }
        assertEquals(0, connects)
        coVerify(exactly = 0) { commands.capture(any(), any(), any(), any(), any(), any()) }
    }
}
