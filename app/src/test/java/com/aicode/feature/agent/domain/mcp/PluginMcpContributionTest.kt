package com.aicode.feature.agent.domain.mcp

import android.app.Application
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthManager
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
class PluginMcpContributionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var plugins: PluginManager
    private lateinit var manager: McpManager
    private val tools = ToolRegistry()
    private var connects = 0
    private var closes = 0
    private val config = mockk<McpConfigRepository>()
    private val oauth = mockk<McpOAuthManager>()
    private val descriptor = McpToolDescriptor("report", "Report", JsonObject(emptyMap()))

    @Before fun setup() = runBlocking<Unit> {
        plugins = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "registry/state.json"))
        val manifest = PluginCoreFixtures.manifest(contributions = """"mcp":[{"id":"tools","file":"mcp.json"}]""")
        val text = """{"mcpServers":{"shared":{"url":"https://example.invalid/mcp"}}}"""
        PluginCoreFixtures.install(plugins, PluginCoreFixtures.archive(manifest, listOf("mcp.json" to text.toByteArray())))
        PluginCoreFixtures.activate(plugins)
        mockkConstructor(McpClient::class)
        coEvery { anyConstructed<McpClient>().connect() } coAnswers { connects++; 1 }
        every { anyConstructed<McpClient>().tools } returns listOf(descriptor)
        every { anyConstructed<McpClient>().close() } answers { closes++; Unit }
        val selected = com.aicode.feature.workspace.data.repository.WorkspaceRepository.Selection(null)
        val userConfig = McpServerConfig("user", url = "https://example.invalid/user")
        coEvery { config.getEffectiveServers() } returns listOf(userConfig)
        coEvery { config.getEffectiveEntries() } returns listOf(McpServerEntry(userConfig, McpScope.GLOBAL, 1L, "test-global", workspaceSelectionId = selected.id))
        every { config.checkEntryCurrent(any()) } returns Unit
        every { oauth.cancel(any()) } returns Unit
        val workspaces = mockk<com.aicode.feature.workspace.data.repository.WorkspaceRepository>()
        every { workspaces.selection } returns MutableStateFlow(selected)
        manager = McpManager(config, tools, mockk(), mockk(), workspaces, mockk(), oauth, plugins, mockk())
    }

    @After fun cleanup() { unmockkConstructor(McpClient::class) }

    @Test fun pluginSyncUsesNamespacedToolsAndDoesNotReconnectUserClients() = runBlocking<Unit> {
        manager.reload()
        val user = tools.getTool(McpTool.buildNamespacedName("user", "report"))
        assertNotNull(user)
        manager.syncPluginServers()
        assertEquals(2, connects)
        val server = plugins.mcpServers().single().value.config.name
        assertTrue(server.startsWith("plugin__"))
        val pluginTool = tools.getTool(McpTool.buildNamespacedName(server, "report"))
        assertNotNull(pluginTool)
        assertNotSame(user, pluginTool)
        manager.syncPluginServers()
        assertEquals(2, connects)
        assertSame(user, tools.getTool(McpTool.buildNamespacedName("user", "report")))
        assertEquals(0, closes)
    }

    @Test fun disableImmediatelyClosesOnlyOwnedClientAndNeverRevivesOldToolOnEnable() = runBlocking<Unit> {
        manager.reload()
        manager.syncPluginServers()
        val server = plugins.mcpServers().single().value.config.name
        val name = McpTool.buildNamespacedName(server, "report")
        val old = tools.getTool(name)
        val user = tools.getTool(McpTool.buildNamespacedName("user", "report"))
        plugins.disable("sample.plugin")
        assertNull(tools.getTool(name))
        assertSame(user, tools.getTool(McpTool.buildNamespacedName("user", "report")))
        assertEquals(1, closes)
        PluginCoreFixtures.activate(plugins)
        manager.syncPluginServers()
        assertEquals(3, connects)
        assertNotSame(old, tools.getTool(name))
    }

    @Test fun pluginOAuthUsesDeclaredConfigurationAndCannotFallBackToUserNamespace() = runBlocking<Unit> {
        val config = plugins.mcpServers().single().value.config
        val authorized = mutableListOf<String>()
        coEvery { oauth.authorize(any()) } coAnswers { authorized += firstArg<McpServerConfig>().name; mockk() }
        assertTrue(manager.authorize(config.name).isSuccess)
        assertEquals(listOf(config.name), authorized)
        plugins.disable("sample.plugin")
        assertTrue(manager.authorize(config.name).isFailure)
        assertEquals(1, authorized.size)
    }

    @Test fun sharedParserRetainsUserAuthAndDisabledToolsAndPluginNamespacesStaySeparate() {
        val parsed = McpConfigParser.parse("""{"mcpServers":{"server":{"url":"https://example.invalid","headers":{"X-Name":"value"},"disabledTools":["hidden"],"auth":{"type":"oauth","scopes":["read"]}},"local":{"command":"node","args":["server.mjs"],"env":{"A":"B"}}}}""")
        assertEquals(listOf("server", "local"), parsed.map { it.name })
        assertEquals(setOf("hidden"), parsed.first().disabledTools)
        assertEquals(listOf("read"), parsed.first().auth?.scopes)
        assertEquals("B", parsed.last().env["A"])
        assertNotEquals(McpTool.buildNamespacedName("server", "tool"), McpTool.buildNamespacedName("plugin__sample__tools__server", "tool"))
    }
}
