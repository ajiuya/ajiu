package com.aicode.feature.plugin.runtime

import android.app.Application
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionDecision
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.forInvocation
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginOperationGrantRepository
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PluginHostIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val modes = ExecutionModeHolder()
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("work", "/work", WorkspaceType.INTERNAL)))
    private var execution = WorkspaceExecutionContext(modes.currentMode(), "work", mockk(),
        WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE, File("/work"), File("/fixture/rootfs"), File("/fixture/global"), "/root"), null) {}
    private val commands = mockk<CommandEngine>()
    private val registry = ToolRegistry()
    private val approvals = PluginApprovalCoordinator()
    private val rules = AtomicReference(emptyList<PermissionRule>())
    private val grants = mockk<SessionPermissionGrantRepository>()
    private val chatApprovals = ToolPermissionManager(grants)
    private val factory = PluginFakeProcessFactory()
    private val empty = JsonObject(emptyMap())
    private val payload = buildJsonObject { put("value", "sample") }
    private val name = PluginAgentTool.toolName("sample.plugin", "report")
    private val session = AgentContext(null, null, "/work", null, sessionId = "chat-A")
    private lateinit var manager: PluginManager
    private lateinit var capture: PluginCapture
    private lateinit var gateway: PluginHostGateway
    private lateinit var runtime: PluginRuntimeManager
    private lateinit var publication: PluginToolPublication

    @Before fun setup() = runBlocking<Unit> {
        manager = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "state/registry.json"))
        val manifest = PluginCoreFixtures.manifest(contributions = """"tools":[{"id":"report","title":"Report","inputSchema":"tool.json","handler":"report"}],"uiEntries":[{"id":"action","slot":"drawer.footer","title":"Action","handler":"action"}]""",
            runtime = """{"type":"python","entry":"module.py"}""")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("module.py" to byteArrayOf(), "tool.json" to
            """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""".toByteArray())))
        capture = PluginCoreFixtures.activate(manager)
        val workspaces = mockk<WorkspaceRepository>()
        every { workspaces.selection } returns selection
        val contexts = mockk<WorkspaceExecutionContextFactory>()
        coEvery { contexts.freeze(any(), any(), any()) } answers {
            val owner = secondArg<com.aicode.feature.agent.domain.container.runtime.RuntimeOwner?>()
            val check = thirdArg<(() -> Unit)?>() ?: {}
            check()
            WorkspaceExecutionContext(modes.currentMode(), "work", mockk(), execution.localMapping, null,
                runtimeOwner = owner, checkSource = check).also { execution = it }
        }
        val settings = mockk<ContainerSettingsRepository>()
        every { settings.activeProfileSelectionRevisions } returns MutableStateFlow(0L)
        every { settings.activeProfileSelectionRevision } returns 0L
        every { settings.profileConfigurationRevision } returns 0L
        every { settings.profileConfigurationRevisions } returns MutableStateFlow(0L)
        val ssh = mockk<RemoteSshConnection>()
        every { ssh.identityState } returns MutableStateFlow<RemoteConnectionIdentity?>(null)
        val folders = mockk<LocalFolderGrantRepository>()
        every { folders.snapshots } returns MutableStateFlow(LocalFolderSnapshot.EMPTY)
        every { folders.snapshot() } returns LocalFolderSnapshot.EMPTY
        coEvery { folders.awaitReady() } returns Unit
        val repo = mockk<PermissionRulesRepository>()
        coEvery { repo.loadEffectiveForCurrentProject() } answers { rules.get() }
        val policy = ToolPermissionPolicyEngine(repo, grants, app, mockk(), mockk())
        gateway = PluginHostGateway(app, manager, workspaces, contexts, modes, settings, ssh, folders,
            PluginFileBridge(FileBackendRouting()), commands, policy, registry, approvals,
            PluginCapabilityRepository(File(temporary.root, "capabilities.enc"), manager, { it }, { it }),
            PluginOperationGrantRepository(File(temporary.root, "operations.enc"), { "AICODEENC1:$it" }, { it.removePrefix("AICODEENC1:") }), emptySet())
        runtime = PluginRuntimeManager(manager, factory)
        publication = PluginToolPublication(app, manager, registry, gateway, runtime)
        every { commands.notReadyHint() } returns null
        coEvery { commands.runCommandSyncWithExit("pwd", "/work", 3_000) } coAnswers {
            assertSame(execution, WorkspaceExecutionContext.current())
            CommandResult("/work", 0)
        }
    }

    @After fun close() = runBlocking<Unit> {
        if (::runtime.isInitialized) { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }

    private fun tool() = PluginAgentTool(app, manager.tools().single(), gateway, runtime)
    private suspend fun error(block: suspend () -> Any?): String = try { block(); throw AssertionError("Expected denial") }
        catch (failure: PluginHostException) { failure.code }
    private suspend fun visibleApproval() = withTimeout(5000) { approvals.pending.filterNotNull().first() }
    private suspend fun chatApproval() = withTimeout(5000) { chatApprovals.pendingRequest.filterNotNull().first() }
    private val shellArgs = buildJsonObject { put("command", "pwd"); put("timeout", 3) }

    @Test fun unknownAndApprovalMethodsAreNeverRoutable() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        for (method in listOf("host.tools.resolve", "chat.resolvePlanApproval", "host.shizuku.exec", "root", "anything")) {
            val expected = if (method in setOf("host.tools.resolve", "chat.resolvePlanApproval")) "SYSTEM_PROTECTED" else "METHOD_NOT_SUPPORTED"
            assertEquals(expected, error { gateway.call(source, method, empty) })
        }
        assertNull(approvals.pending.value)
    }

    @Test fun callerCannotSelectAnotherSessionOrNestedPrivilegedTool() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        for (tool in listOf("Root", "Shizuku", "submitPlan", "task", "terminal", name)) {
            assertEquals("TOOL_NOT_ALLOWED", error { gateway.call(source, "tools.execute", buildJsonObject { put("name", tool) }) })
        }
        assertEquals("PARAM_INVALID", error { gateway.call(source, "host.tools.execute", buildJsonObject { put("sessionId", "other"); put("name", "Bash") }) })
        assertNull(approvals.pending.value)
    }

    @Test fun bothAgentRestrictionsAndProjectDenyAreEvaluatedBeforeApproval() = runBlocking<Unit> {
        val limited = session.copy(agentDefinition = AgentDefinition("reader", "Reader", allowedTools = listOf(name), prompt = "Read"))
        val source = gateway.captureContext(capture, session.sessionId, session.projectRoot, limited)
        assertEquals("TOOL_NOT_ALLOWED", error { gateway.call(source, "bash", shellArgs) })
        rules.set(listOf(PermissionRule.fromCompact("Bash(pwd)", PermissionDecision.DENY)))
        assertEquals("SYSTEM_DENIED", error { gateway.call(gateway.captureContext(capture), "host.bash", shellArgs) })
        assertNull(approvals.pending.value)
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun commandRequiresNativeOnceDespitePolicyAllowAndUsesFrozenBackend() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        val result = async { gateway.call(source, "host.bash", shellArgs) }
        val request = visibleApproval()
        assertEquals(capture.id, request.pluginId)
        assertTrue(request.details.contains("/work")); assertTrue(request.details.contains("pwd"))
        approvals.resolve("wrong-ticket", true)
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
        approvals.resolve(request.id, true)
        val value = result.await().jsonObject
        assertEquals(JsonPrimitive(0), value["exitCode"])
        assertEquals(JsonPrimitive("/work"), value["output"])
        coVerify(exactly = 1) { commands.runCommandSyncWithExit("pwd", "/work", 3000) }
    }

    @Test fun sessionFileApprovalIncludesActualTextBinaryAndAppendContents() = runBlocking<Unit> {
        val source = gateway.captureContext(capture, "chat-A")
        for (method in listOf("fs.write", "fs.append", "fs.writeBinary")) {
            val path = "${method.substringAfter('.')}.txt"
            val file = File(manager.dataDirectory(capture.id), path).apply { writeText("start") }
            fun params(content: String) = buildJsonObject {
                put("path", "data://$path")
                if (method == "fs.writeBinary") put("base64", java.util.Base64.getEncoder().encodeToString(content.toByteArray()))
                else put("content", content)
            }
            val first = async { gateway.call(source, method, params("alpha")) }
            val request = visibleApproval()
            assertFalse(request.details.contains("alpha"))
            approvals.resolve(request.id, PluginApprovalChoice.ALWAYS_SESSION)
            first.await()
            withTimeout(5000) { gateway.call(source, method, params("alpha")) }
            val before = file.readBytes()
            val changed = async { error { gateway.call(source, method, params("bravo")) } }
            approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
            assertEquals("USER_REJECTED", changed.await())
            assertArrayEquals(before, file.readBytes())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun clipboardSessionApprovalNeverMatchesDifferentTextWithTheSameLength() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val api = PluginSystemHostApi(app, javax.inject.Provider { gateway }, mockk(), mockk(), mockk())
            val source = gateway.captureContext(capture, "chat-A")
            fun params(text: String) = buildJsonObject { put("text", text) }
            val first = async { api.call(source, "clipboard.write", params("alpha")) }
            val request = visibleApproval()
            assertFalse(request.details.contains("alpha"))
            approvals.resolve(request.id, PluginApprovalChoice.ALWAYS_SESSION)
            first.await()
            withTimeout(5000) { api.call(source, "clipboard.write", params("alpha")) }
            val changed = async { error { api.call(source, "clipboard.write", params("bravo")) } }
            approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
            assertEquals("USER_REJECTED", changed.await())
            assertEquals("alpha", app.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun httpSessionApprovalIncludesBodyAndHeaderValuesWithoutDisplayingThem() = runBlocking<Unit> {
        val http = spyk(PluginHttpService(okhttp3.OkHttpClient()))
        coEvery { http.execute(any(), any()) } returns buildJsonObject { put("status", 200) }
        val api = PluginSystemHostApi(app, javax.inject.Provider { gateway }, mockk(), http, mockk())
        val source = gateway.captureContext(capture, "chat-A")
        fun params(body: String = "alpha", token: String = "token-a") = buildJsonObject {
            put("url", "https://example.com/test"); put("method", "POST"); put("body", body)
            put("headers", buildJsonObject { put("Authorization", token) })
        }
        val first = async { api.call(source, "http.request", params()) }
        val request = visibleApproval()
        assertFalse(request.details.contains("alpha")); assertFalse(request.details.contains("token-a"))
        approvals.resolve(request.id, PluginApprovalChoice.ALWAYS_SESSION)
        first.await()
        withTimeout(5000) { api.call(source, "http.request", params()) }
        for (changedParams in listOf(params(body = "bravo"), params(token = "token-b"))) {
            val changed = async { error { api.call(source, "http.request", changedParams) } }
            approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
            assertEquals("USER_REJECTED", changed.await())
        }
        coVerify(exactly = 2) { http.execute(any(), any()) }
    }

    @Test fun nativeSessionChoiceReusesSameChatSourceButNeverAnotherChat() = runBlocking<Unit> {
        val source = gateway.captureContext(capture, "chat-A")
        val first = async { gateway.call(source, "bash", shellArgs) }
        val approval = visibleApproval()
        assertTrue(approval.canAllowSession)
        assertFalse(approval.pluginRunSession)
        approvals.resolve(approval.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertEquals(JsonPrimitive(0), first.await().jsonObject["exitCode"])
        val same = withTimeout(5000) { gateway.call(gateway.captureContext(capture, "chat-A"), "bash", shellArgs) }
        assertEquals(JsonPrimitive(0), same.jsonObject["exitCode"])
        assertNull(approvals.pending.value)
        val other = async { error { gateway.call(gateway.captureContext(capture, "chat-B"), "bash", shellArgs) } }
        approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
        assertEquals("USER_REJECTED", other.await())
        coVerify(exactly = 2) { commands.runCommandSyncWithExit("pwd", "/work", 3000) }
    }

    @Test fun nativeRunChoiceIsSharedByChildrenButNotByAnotherInvocation() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        val first = async { gateway.call(source, "bash", shellArgs) }
        val approval = visibleApproval()
        assertTrue(approval.canAllowSession)
        assertTrue(approval.pluginRunSession)
        approvals.resolve(approval.id, PluginApprovalChoice.ALWAYS_SESSION)
        first.await()
        withTimeout(5000) { gateway.call(source.child(), "bash", shellArgs) }
        assertNull(approvals.pending.value)
        val nextRun = async { error { gateway.call(gateway.captureContext(capture), "bash", shellArgs) } }
        approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
        assertEquals("USER_REJECTED", nextRun.await())
        coVerify(exactly = 2) { commands.runCommandSyncWithExit("pwd", "/work", 3000) }
    }

    @Test fun rememberedPluginSessionStillChecksDenyAndChangedSource() = runBlocking<Unit> {
        val source = gateway.captureContext(capture, "chat-A")
        val first = async { gateway.call(source, "bash", shellArgs) }
        approvals.resolve(visibleApproval().id, PluginApprovalChoice.ALWAYS_SESSION)
        first.await()
        rules.set(listOf(PermissionRule.fromCompact("Bash(pwd)", PermissionDecision.DENY)))
        assertEquals("SYSTEM_DENIED", error { gateway.call(source, "bash", shellArgs) })
        assertNull(approvals.pending.value)
        rules.set(emptyList())
        selection.value = WorkspaceRepository.Selection(Workspace("work", "/work", WorkspaceType.INTERNAL))
        val changed = async { error { gateway.call(gateway.captureContext(capture, "chat-A"), "bash", shellArgs) } }
        approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
        assertEquals("USER_REJECTED", changed.await())
        coVerify(exactly = 1) { commands.runCommandSyncWithExit("pwd", "/work", 3000) }
    }

    @Test fun denyArrivingBeforeSessionChoicePreventsRememberingIt() = runBlocking<Unit> {
        val source = gateway.captureContext(capture, "chat-A")
        val first = async { error { gateway.call(source, "bash", shellArgs) } }
        val request = visibleApproval()
        rules.set(listOf(PermissionRule.fromCompact("Bash", PermissionDecision.DENY)))
        approvals.resolve(request.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertEquals("SYSTEM_DENIED", first.await())
        rules.set(emptyList())
        val retry = async { error { gateway.call(source, "bash", shellArgs) } }
        approvals.resolve(visibleApproval().id, PluginApprovalChoice.REJECT)
        assertEquals("USER_REJECTED", retry.await())
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun nativePermissionApiHasSessionChoiceWithoutResolvingQuestionsOrPlans() = runBlocking<Unit> {
        val api = PluginNativePermissionApi(javax.inject.Provider { gateway }, approvals)
        val source = gateway.captureContext(capture, "chat-A")
        fun params(kind: String, sessionId: String = "chat-A") = buildJsonObject {
            put("requestId", "plugin-request"); put("sessionId", sessionId)
            put("title", "Permission"); put("description", "Exact operation")
            put("operation", buildJsonObject { put("kind", kind); put("target", "sample") })
        }
        val first = async { api.call(source, "permissions.request", params("sampleOperation")) }
        val approval = visibleApproval()
        assertTrue(approval.canAllowSession)
        approvals.resolve(approval.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertEquals(JsonPrimitive("allow"), first.await().jsonObject["decision"])
        assertEquals(JsonPrimitive("allow"), withTimeout(5000) {
            api.call(source, "permissions.request", params("sampleOperation"))
        }.jsonObject["decision"])
        assertNull(approvals.pending.value)
        for (kind in listOf("Root", "Shizuku", "submitPlan", "planMode", "askUserQuestion")) {
            assertEquals("SYSTEM_PROTECTED", error { api.call(source, "permissions.request", params(kind)) })
        }
        assertEquals("CONTEXT_CHANGED", error { api.call(source, "permissions.request", params("sampleOperation", "chat-B")) })
    }

    @Test fun ruleChangedWhileApprovalVisibleDeniesEvenAfterAllowClick() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        val result = async { error { gateway.call(source, "bash", shellArgs) } }
        val request = visibleApproval()
        rules.set(listOf(PermissionRule.fromCompact("Bash", PermissionDecision.DENY)))
        approvals.resolve(request.id, true)
        assertEquals("SYSTEM_DENIED", result.await())
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun workspaceRoundTripRevokesQueuedApprovalAndCannotReuseIt() = runBlocking<Unit> {
        val source = gateway.captureContext(capture)
        val result = async { try { gateway.call(source, "bash", shellArgs); false } catch (_: CancellationException) { true } }
        val request = visibleApproval()
        selection.value = WorkspaceRepository.Selection(null)
        selection.value = WorkspaceRepository.Selection(Workspace("work", "/work", WorkspaceType.INTERNAL))
        approvals.resolve(request.id, true)
        assertTrue(withTimeout(5000) { result.await() })
        assertNull(approvals.pending.value)
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun onlyExplicitBridgeToolsListedAndWebToolRejectExecutesNothing() = runBlocking<Unit> {
        var calls = 0
        val web = object : AgentTool() {
            override val name = "webfetch"; override val description = "fetch"
            override val parameters = emptyMap<String, ToolParameter>()
            override suspend fun execute(args: Map<String, JsonElement>): ToolResult { calls++; return ToolResult.Success(empty) }
        }
        registry.register("webfetch", web); registry.register(name, tool())
        val source = gateway.captureContext(capture)
        val listed = gateway.call(source, "tools.list", buildJsonObject { put("includeSchema", true) }).jsonObject.getValue("tools").jsonArray
        assertEquals(JsonPrimitive("webfetch"), listed.single().jsonObject["name"])
        assertNotNull(listed.single().jsonObject["inputSchema"])
        val result = async { error { gateway.call(source, "tools.execute", buildJsonObject { put("name", "webfetch") }) } }
        approvals.resolve(visibleApproval().id, false)
        assertEquals("USER_REJECTED", result.await()); assertEquals(0, calls)
    }

    @Test fun aiToolRequiresMatchingContextAndArgumentsBeforeProcessCreation() = runBlocking<Unit> {
        val tool = tool()
        assertEquals("CONTEXT_REQUIRED", (tool.execute(payload) as ToolResult.Error).code)
        assertEquals("PARAM_INVALID", (tool.executeWithContext(empty, session) as ToolResult.Error).code)
        assertEquals("CONTEXT_CHANGED", (tool.executeWithContext(payload, session.copy(projectRoot = "/another")) as ToolResult.Error).code)
        rules.set(listOf(PermissionRule.fromCompact(name, PermissionDecision.DENY)))
        assertEquals("SYSTEM_DENIED", (tool.executeWithContext(payload, session) as ToolResult.Error).code)
        assertTrue(factory.launches.isEmpty()); assertNull(chatApprovals.pendingRequest.value)
    }

    @Test fun modelToolApprovalIsOneShotAndCannotUseSessionGrant() = runBlocking<Unit> {
        val tool = tool()
        val call = async { tool.executeWithContext(payload, session) }
        val request = visibleApproval()
        assertTrue(request.details.contains(requireNotNull(session.sessionId)))
        assertTrue(request.canAlwaysAllow)
        chatApprovals.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
        assertFalse(call.isCompleted)
        approvals.resolve(request.id, PluginApprovalChoice.REJECT)
        assertEquals("USER_REJECTED", (call.await() as ToolResult.Error).code)
        assertTrue(factory.launches.isEmpty())
    }

    @Test fun modelInvocationAndReverseCommandUseDistinctNativeDecisions() = runBlocking<Unit> {
        val call = async { tool().executeWithContext(payload, session) }
        val permission = visibleApproval()
        assertTrue(factory.launches.isEmpty()); approvals.resolve(permission.id, PluginApprovalChoice.ONCE)
        val process = withTimeout(5000) { factory.started.receive() }
        val rpc = process.message()
        assertEquals(JsonPrimitive("tool.invoke"), rpc["method"])
        assertEquals(payload, rpc.getValue("params").jsonObject["args"])
        process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "child"); put("method", "host.bash"); put("params", shellArgs) })
        val nested = visibleApproval(); assertEquals(PluginApprovalOperation.EXECUTE_COMMAND, nested.operation)
        approvals.resolve(nested.id, false)
        assertEquals(JsonPrimitive("USER_REJECTED"), process.message().getValue("error").jsonObject["code"])
        process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", rpc.getValue("id")); put("result", "ok") })
        assertEquals(JsonPrimitive("ok"), (call.await() as ToolResult.Success).data)
        coVerify(exactly = 0) { commands.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun pageCanOnlyDispatchActionsDeclaredByItsOwnInstallation() = runBlocking<Unit> {
        val actions = PluginActions(gateway, runtime)
        val origin = gateway.captureContext(capture)
        assertEquals("PARAM_INVALID", error { actions.call(origin, "plugin.invoke", buildJsonObject { put("handler", "../report") }) })
        assertEquals("CONTEXT_CHANGED", error { actions.invoke(requireNotNull(manager.capture(capture.id)), "action", empty, origin) })
        assertTrue(factory.launches.isEmpty())
        val result = async { actions.invoke(capture, "action", empty, origin) }
        val process = withTimeout(5000) { factory.started.receive() }; val rpc = process.message()
        assertEquals(JsonPrimitive("ui.invoke"), rpc["method"])
        process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", rpc.getValue("id")); put("result", "ok") })
        assertEquals(JsonPrimitive("ok"), result.await())
    }

    @Test fun configurationPageCanCallItsOwnRuntimeHandlerWithoutAnActionEntry() = runBlocking<Unit> {
        val actions = PluginActions(gateway, runtime)
        val page = gateway.captureContext(capture)
        val call = async { actions.call(page, "plugin.invoke", buildJsonObject {
            put("handler", "configuration.read"); put("args", empty)
        }) }
        val process = withTimeout(5000) { factory.started.receive() }
        val message = process.message()
        assertEquals(JsonPrimitive("ui.invoke"), message["method"])
        assertEquals(JsonPrimitive("configuration.read"), message.getValue("params").jsonObject["handler"])
        process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("result", "saved") })
        assertEquals(JsonPrimitive("saved"), call.await())
        page.checkCurrent()
        page.close()
    }

    @Test fun runtimeOwnerIsBoundToPluginSourceRatherThanRandomInvocationOrChat() = runBlocking<Unit> {
        val first = gateway.captureContext(capture, session.sessionId, session.projectRoot, session)
        val second = gateway.captureContext(capture, session.sessionId, session.projectRoot, session)
        assertNotEquals(first.invocationId, second.invocationId)
        assertEquals(first.runtimeOwner, second.runtimeOwner)
        assertEquals(com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN, first.runtimeOwner?.kind)
        val authority = first.runtimeContext()
        assertSame(first.sourceIdentity, authority.sourceIdentity)
        assertNotSame(first.sourceIdentity, authority.authorityIdentity)
        val guarded = authority.guarded(first::checkCurrent).withBackgroundAuthority(Any()).withIdleCallbacks()
        assertEquals(first.runtimeOwner, guarded.runtimeOwner)
        assertSame(first.sourceIdentity, guarded.sourceIdentity)
    }

    @Test fun retainingAndChildContextsPreserveSourceOwnershipWithoutSharingInvocationLifetime() = runBlocking<Unit> {
        val original = gateway.captureContext(capture)
        val retained = original.retainSource()
        val child = original.child()
        assertNotEquals(original.invocationId, retained.invocationId)
        assertSame(original.sourceIdentity, retained.sourceIdentity)
        assertSame(original.runtimeContext().authorityIdentity, retained.runtimeContext().authorityIdentity)
        assertEquals(original.runtimeOwner, child.runtimeContext().runtimeOwner)
        original.close()
        try { child.checkCurrent(); fail("Expected parent invocation closure") } catch (_: CancellationException) { }
        retained.checkCurrent()
        manager.disable(capture.id)
        assertTrue(runCatching { retained.checkCurrent() }.exceptionOrNull() is com.aicode.feature.plugin.domain.PluginCaptureException)
    }

    @Test fun contentAndActivationChangesReplaceRuntimeOwnerAndInvalidateOldSource() = runBlocking<Unit> {
        val original = gateway.captureContext(capture)
        val oldOwner = original.runtimeOwner
        val manifest = PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"module.py"}""", version = "2.0.0")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("module.py" to "value = 2".toByteArray())))
        val updated = PluginCoreFixtures.activate(manager)
        val replacement = gateway.captureContext(updated)
        assertNotEquals(capture.contentHash, updated.contentHash)
        assertNotEquals(oldOwner, replacement.runtimeOwner)
        assertTrue(runCatching { original.checkCurrent() }.exceptionOrNull() is com.aicode.feature.plugin.domain.PluginCaptureException)
        replacement.checkCurrent()
    }

    @Test fun runtimeContextCannotBeCreatedWithoutAnExplicitOwnerAndSource() = runBlocking<Unit> {
        val root = manager.dataDirectory(capture.id)
        val missingOwner = PluginInvocationContext(capture, null, "", null, root, kotlinx.coroutines.flow.emptyFlow(), sourceIdentity = "source") {}
        assertEquals("CONTEXT_REQUIRED", error { missingOwner.runtimeContext() })
        val missingSource = PluginInvocationContext(capture, null, "", null, root, kotlinx.coroutines.flow.emptyFlow(),
            runtimeOwner = com.aicode.feature.agent.domain.container.runtime.RuntimeOwner(
                com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN, "missing-source")) {}
        assertEquals("CONTEXT_REQUIRED", error { missingSource.runtimeContext() })
    }

    @Test fun workspaceLessBackgroundCannotAcquireAChrootRuntimeThroughDefaultProfile() = runBlocking<Unit> {
        var starts = 0
        val processFactory = object : PluginRuntimeProcessFactory {
            override suspend fun captureProfile() = ContainerProfile.BUILTIN_ALPINE.copy(
                localRuntime = com.aicode.feature.agent.domain.container.LocalContainerRuntime.CHROOT)
            override fun containerPath(file: File) = file.path
            override suspend fun start(launch: PluginLocalLaunch): Process { starts++; throw AssertionError("Unexpected launch") }
        }
        val ownedRuntime = PluginRuntimeManager(manager, processFactory)
        val background = gateway.captureBackgroundContext(capture)
        try {
            try {
                ownedRuntime.invoke(capture, requireNotNull(capture.content.runtime), "report", empty,
                    background.runtimeContext(), { _, _ -> empty })
                fail("Expected missing workspace runtime capture")
            } catch (failure: PluginProcessException) { assertEquals("PLUGIN_CONTEXT_REQUIRED", failure.code) }
            assertEquals(0, starts)
        } finally { background.close(); ownedRuntime.close(); withTimeout(5_000) { ownedRuntime.awaitClosed() } }
    }

    @Test fun registeredToolIsBoundToOfferedInstanceAcrossDisableAndReenable() = runBlocking<Unit> {
        coroutineScope {
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { publication.observe() }
            suspend fun until(test: () -> Boolean) = withTimeout(5000) { while (!test()) yield() }
            try {
                until { registry.hasTool(name) }
                val old = requireNotNull(registry.getTool(name)); assertSame(old, registry.forInvocation(name, listOf(old)))
                manager.disable(capture.id); until { !registry.hasTool(name) }
                PluginCoreFixtures.activate(manager); until { registry.hasTool(name) }
                val current = requireNotNull(registry.getTool(name))
                assertNotSame(old, current); assertNull(registry.forInvocation(name, listOf(old)))
                assertFalse(registry.registerIfVacant(old)); registry.unregisterOwned(old)
                assertSame(current, registry.forInvocation(name, listOf(current)))
                assertEquals("STALE_CAPTURE", (old.executeWithContext(payload, session) as ToolResult.Error).code)
            } finally { observer.cancelAndJoin() }
        }
        assertFalse(registry.hasTool(name))
    }
}
