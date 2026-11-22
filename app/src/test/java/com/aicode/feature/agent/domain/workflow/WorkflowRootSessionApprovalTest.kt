package com.aicode.feature.agent.domain.workflow

import android.content.Context
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.root.RootCommandResult
import com.aicode.feature.agent.domain.shizuku.ShizukuCommandResult
import com.aicode.feature.agent.domain.shizuku.ShizukuIdentity
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.runtime.PluginBeforeTool
import com.aicode.feature.plugin.runtime.PluginHookDispatcher
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WorkflowRootSessionApprovalTest {
    private data class Permit(
        val id: String,
        val tool: String,
        val session: String,
        val command: String,
        val root: RootAccessState? = null,
        val shizuku: ShizukuIdentity? = null
    )

    private data class Invocation(val tool: String, val command: String, val session: String, val permitId: String?)

    private val registry = ToolRegistry()
    private val providers = mockk<AIProviderRepository>()
    private val prompts = mockk<SystemPromptProvider>()
    private val grants = mockk<SessionPermissionGrantRepository>()
    private val permissions = ToolPermissionManager(grants)
    private val rules = mockk<PermissionRulesRepository>()
    private val app = mockk<Context>()
    private val rootManager = mockk<RootCommandManager>()
    private val shizukuManager = mockk<ShizukuManager>()
    private val rootState = MutableStateFlow(RootAccessState(true, 42, "root-authority"))
    private val shizukuIdentity = MutableStateFlow<ShizukuIdentity?>(ShizukuIdentity(0, "root-connection"))
    private val compactor = mockk<ContextCompactor>()
    private val outputStore = mockk<ToolOutputStore>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val hooks = mockk<PluginHookDispatcher>(relaxed = true)
    private val allowed = ConcurrentHashMap.newKeySet<Pair<String?, String>>()
    private val livePermits = ConcurrentHashMap<String, Permit>()
    private val issued = CopyOnWriteArrayList<Permit>()
    private val executed = CopyOnWriteArrayList<Permit>()
    private val rejected = CopyOnWriteArrayList<Invocation>()
    private val revoked = CopyOnWriteArrayList<String>()
    private val seenRequests = CopyOnWriteArrayList<PendingToolPermission>()
    private val modelRequests = CopyOnWriteArrayList<List<AgentMessage>>()
    private val pendingRequests = Channel<PendingToolPermission>(Channel.UNLIMITED)
    private val nonce = AtomicInteger()
    private var beforeFreeze: suspend () -> Unit = {}
    private lateinit var workflow: StatefulAgentWorkflow
    private val rootTool = CommandTool("Root")
    private val shizukuTool = CommandTool("Shizuku")
    private val tools get() = listOf(rootTool, shizukuTool)

    @Before
    fun prepare() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        mockkObject(FileLogger)
        every { FileLogger.memoryCheckpoint(any(), any(), any(), any()) } returns Unit
        mockkConstructor(OpenAIAdapter::class)
        every { prompts.build(any()) } returns "system"
        every { app.getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { app.getString(any(), *anyVararg()) } answers { "resource:${firstArg<Int>()}" }
        coEvery { rules.loadEffectiveForCurrentProject() } returns emptyList<PermissionRule>()
        coEvery { grants.isAllowed(any(), any()) } answers { (firstArg<String?>() to secondArg<String>()) in allowed }
        coEvery { grants.allow(any(), any()) } answers {
            allowed.add(firstArg<String?>() to secondArg<String>())
            Unit
        }
        every { rootManager.accessState } returns rootState
        coEvery { rootManager.accessSnapshot() } answers { rootState.value }
        every { shizukuManager.identity } returns shizukuIdentity
        every { shizukuManager.accessSnapshot() } answers { shizukuIdentity.value }
        coEvery { rootManager.authorize(any(), any(), any(), any()) } answers {
            val state = rootState.value
            if (!state.enabled || state.generation != arg<Long>(2) || state.authorityId != arg<String>(3)) null
            else issue("Root", firstArg(), secondArg(), root = state)
        }
        every { shizukuManager.authorize(any(), any(), any()) } answers {
            val identity = shizukuIdentity.value
            if (identity == null || identity != thirdArg<ShizukuIdentity>()) null
            else issue("Shizuku", firstArg(), secondArg(), shizuku = identity)
        }
        every { rootManager.revokePermit(any()) } answers { revoke(firstArg()) }
        every { shizukuManager.revokePermit(any()) } answers { revoke(firstArg()) }
        coEvery { rootManager.execute(any(), any(), any(), any()) } answers {
            RootCommandResult("root", if (consume("Root", firstArg(), arg(2), arg(3))) 0 else RootCommandManager.EXIT_APPROVAL_REQUIRED)
        }
        coEvery { shizukuManager.runApprovedCommand(any(), any(), any(), any()) } answers {
            ShizukuCommandResult("shizuku", if (consume("Shizuku", firstArg(), arg(2), arg(3))) 0 else -1)
        }
        coEvery { defaultSettings.getDefaultProviderId() } returns "provider"
        coEvery { defaultSettings.getDefaultModel() } returns "model"
        coEvery { providers.getProviderById("provider") } returns AIProviderConfig(
            id = "provider", name = "test", type = ProviderType.OPENAI, apiKey = "test-key",
            baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model")
        )
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("model", contextTokens = 100_000)
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery { compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompactionResult(firstArg<List<AgentMessage>>(), false)
        }
        every { outputStore.process(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        coEvery { hooks.beforeTool(any(), any(), any()) } answers { PluginBeforeTool(thirdArg()) }
        coEvery { hooks.afterTool(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        tools.forEach { registry.register(it.name, it) }
        workflow = StatefulAgentWorkflow(
            toolRegistry = registry,
            aiProviderRepository = providers,
            providerFactory = ProviderInstanceFactory(
                mockk(), mockk(), mockk(), metadata, mockk(relaxed = true),
                mockk { every { activeKey(any(), any()) } returns "test-key" }
            ),
            promptProvider = prompts,
            permissionManager = permissions,
            policyEngine = ToolPermissionPolicyEngine(rules, grants, app, rootManager, shizukuManager),
            contextCompactor = compactor,
            toolOutputStore = outputStore,
            modelMetadataService = metadata,
            compactionModelSettingsRepository = compactionSettings,
            titleModelSettingsRepository = mockk(),
            defaultModelSettingsRepository = defaultSettings,
            generalSettingsRepository = mockk(relaxed = true),
            sessionUseCase = mockk { coEvery { getSessionById(any()) } returns null },
            messagePersistenceUseCase = mockk(),
            checkpointManager = mockk(relaxed = true),
            llmCallRecordDao = mockk(relaxed = true),
            agentNotificationCenter = AgentNotificationCenter(),
            eventInjector = mockk<AgentEventInjector>(),
            fileAccess = mockk(),
            appContext = app,
            rootCommandManager = rootManager,
            shizukuManager = shizukuManager,
            recoveryRepository = mockk(relaxed = true),
            workspaceExecutionContextFactory = mockk {
                coEvery { freeze(any()) } coAnswers {
                    beforeFreeze()
                    WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "approval-test", mockk(), null, null) {}
                }
            },
            pluginHooks = hooks
        )
    }

    @After
    fun cleanup() {
        pendingRequests.close()
        unmockkConstructor(OpenAIAdapter::class)
        unmockkObject(FileLogger)
        unmockkStatic(SystemClock::class)
    }

    private inner class CommandTool(override val name: String) : AgentTool() {
        override val description = "test command tool"
        override val parameters = mapOf("command" to ToolParameter("command", ParameterType.STRING, "command"))
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = error("Execution requires AgentContext")
        override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
            val command = (args.getValue("command") as JsonPrimitive).content
            val exitCode = if (name == "Root") {
                assertNull(context.shizukuApprovalId)
                rootManager.execute(command, 30_000, context.sessionId.orEmpty(), context.rootApprovalId).exitCode
            } else {
                assertNull(context.rootApprovalId)
                shizukuManager.runApprovedCommand(command, 30_000, context.sessionId.orEmpty(), context.shizukuApprovalId).exitCode
            }
            return if (exitCode == 0) ToolResult.Success(JsonPrimitive(command))
                else ToolResult.Error("Permit rejected", "PERMIT_REJECTED")
        }
    }

    private fun issue(tool: String, session: String, command: String, root: RootAccessState? = null, shizuku: ShizukuIdentity? = null): String {
        val permit = Permit("permit-${nonce.incrementAndGet()}", tool, session, command, root, shizuku)
        livePermits[permit.id] = permit
        issued += permit
        return permit.id
    }

    private fun revoke(id: String) {
        livePermits.remove(id)
        revoked += id
    }

    private fun consume(tool: String, command: String, session: String, id: String?): Boolean {
        val permit = id?.let(livePermits::remove)
        val valid = permit != null && permit.tool == tool && permit.command == command && permit.session == session &&
            (if (tool == "Root") permit.root == rootState.value else permit.shizuku == shizukuIdentity.value)
        if (valid) executed += requireNotNull(permit) else rejected += Invocation(tool, command, session, id)
        return valid
    }

    private fun call(tool: String, index: Int, command: String = "id", elevate: Boolean = false) = ToolCall(
        "$tool-$index", tool, buildMap {
            put("command", JsonPrimitive(command))
            if (elevate) put("elevate", JsonPrimitive(true))
        }
    )

    private fun responses(vararg values: AIResponse) {
        val pending = ArrayDeque(values.toList())
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            modelRequests += secondArg<List<AgentMessage>>().toList()
            flowOf<AIStreamChunk>(AIStreamChunk.Final(pending.removeFirst()))
        }
    }

    private suspend fun collect(session: String = "session"): List<AgentEvent> = coroutineScope {
        val requests = launch(start = CoroutineStart.UNDISPATCHED) {
            permissions.pendingRequest.filterNotNull().collect { request ->
                if (request.sessionId == session && seenRequests.none { it.id == request.id }) {
                    seenRequests += request
                    pendingRequests.send(request)
                }
            }
        }
        try {
            val events = mutableListOf<AgentEvent>()
            workflow.executeEvents("test", AgentContext(null, null, "/project", null, sessionId = session), tools).collect { event ->
                events += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                    is AgentEvent.Failed -> error(event.error)
                    else -> Unit
                }
            }
            events
        } finally {
            requests.cancelAndJoin()
        }
    }

    private suspend fun awaitRequest(): PendingToolPermission = withTimeout(5_000) { pendingRequests.receive() }

    private suspend fun assertAlwaysUsesFreshPermits(tool: String, expectedGrant: String) = coroutineScope {
        responses(AIResponse("first", listOf(call(tool, 1))), AIResponse("second", listOf(call(tool, 2, "id -u"))), AIResponse("done"))
        val run = async { collect() }
        try {
            val request = awaitRequest()
            assertEquals(tool, request.toolName)
            assertEquals(expectedGrant, request.sessionGrant)
            assertEquals("session", request.sessionId)
            permissions.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
            val events = run.await()
            assertEquals(1, seenRequests.size)
            assertEquals(setOf("session" to expectedGrant), allowed)
            assertEquals(listOf("id", "id -u"), issued.map { it.command })
            assertEquals(2, issued.map { it.id }.toSet().size)
            assertTrue(issued.all { it.session == "session" && it.tool == tool })
            assertEquals(issued.toList(), executed.toList())
            assertTrue(rejected.isEmpty())
            assertTrue(livePermits.isEmpty())
            assertTrue(revoked.containsAll(issued.map { it.id }))
            assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { !it.isError })
            assertTrue(events.last() is AgentEvent.Completed)
        } finally {
            run.cancelAndJoin()
        }
    }

    @Test
    fun rootAlways_mintsFreshPermitForEachCommandWithoutAnotherPrompt() = runBlocking {
        withTimeout(10_000) { assertAlwaysUsesFreshPermits("Root", "tool:Root:root-authority:42") }
    }

    @Test
    fun rootShizukuAlways_mintsFreshPermitForEachCommandWithoutAnotherPrompt() = runBlocking {
        withTimeout(10_000) { assertAlwaysUsesFreshPermits("Shizuku", "tool:Shizuku:0:root-connection") }
    }

    @Test
    fun shellShizukuAlways_mintsFreshPermitForEachCommandWithoutAnotherPrompt() = runBlocking {
        withTimeout(10_000) {
            shizukuIdentity.value = ShizukuIdentity(2000, "shell-connection")
            assertAlwaysUsesFreshPermits("Shizuku", "tool:Shizuku:2000:shell-connection")
        }
    }

    @Test
    fun rootOnce_asksAgainForTheNextCommandAndIssuesDistinctPermits() = runBlocking {
        withTimeout(10_000) {
            responses(AIResponse("first", listOf(call("Root", 1))), AIResponse("second", listOf(call("Root", 2, "id -u"))), AIResponse("done"))
            val run = async { collect() }
            try {
                val first = awaitRequest()
                permissions.resolve(first.id, PermissionChoice.ONCE)
                val second = awaitRequest()
                assertTrue(first.id != second.id)
                assertEquals("tool:Root:root-authority:42", second.sessionGrant)
                permissions.resolve(second.id, PermissionChoice.ONCE)
                val events = run.await()
                assertEquals(2, seenRequests.size)
                assertTrue(allowed.isEmpty())
                assertEquals(2, issued.map { it.id }.toSet().size)
                assertEquals(issued.toList(), executed.toList())
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { !it.isError })
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun rejectingEitherChannel_doesNotIssueAnyExecutionPermit() = runBlocking {
        withTimeout(10_000) {
            for (tool in listOf("Root", "Shizuku")) {
                responses(AIResponse("proposal", listOf(call(tool, 1))))
                val run = async { collect() }
                try {
                    val request = awaitRequest()
                    permissions.resolve(request.id, PermissionChoice.REJECT)
                    val events = run.await()
                    assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result.contains("USER_REJECTED"))
                    assertTrue(issued.isEmpty())
                    assertTrue(executed.isEmpty())
                    assertTrue(rejected.isEmpty())
                    assertTrue(allowed.isEmpty())
                } finally { run.cancelAndJoin() }
            }
        }
    }

    @Test
    fun cancellationWhileWaiting_clearsNativeRequestWithoutIssuingPermit() = runBlocking {
        withTimeout(10_000) {
            for (tool in listOf("Root", "Shizuku")) {
                responses(AIResponse("proposal", listOf(call(tool, 1))))
                val run = async { collect() }
                awaitRequest()
                run.cancelAndJoin()
                assertNull(permissions.pendingForSession("session"))
                assertTrue(permissions.awaitingSessionIds.value.isEmpty())
                assertTrue(issued.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(allowed.isEmpty())
            }
        }
    }

    @Test
    fun rootDisableWhileWaiting_rejectsRequestAndLateAlwaysCannotExecute() = runBlocking {
        withTimeout(10_000) {
            responses(AIResponse("proposal", listOf(call("Root", 1))))
            val run = async { collect() }
            try {
                val request = awaitRequest()
                rootState.value = RootAccessState(false, 43, "root-authority")
                val events = run.await()
                permissions.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                assertNull(permissions.pendingForSession("session"))
                assertTrue(issued.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(allowed.isEmpty())
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun rootAuthorityChangeWhileWaiting_rejectsOldRequest() = runBlocking {
        withTimeout(10_000) {
            responses(AIResponse("proposal", listOf(call("Root", 1))))
            val run = async { collect() }
            try {
                awaitRequest()
                rootState.value = RootAccessState(true, 42, "new-authority")
                val events = run.await()
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                assertTrue(issued.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(allowed.isEmpty())
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun shizukuConnectionChangeWhileWaiting_rejectsOldRequestAndLateAlways() = runBlocking {
        withTimeout(10_000) {
            responses(AIResponse("proposal", listOf(call("Shizuku", 1))))
            val run = async { collect() }
            try {
                val request = awaitRequest()
                shizukuIdentity.value = ShizukuIdentity(0, "new-connection")
                val events = run.await()
                permissions.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                assertNull(permissions.pendingForSession("session"))
                assertTrue(issued.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(allowed.isEmpty())
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun batchRejection_revokesAlreadyApprovedPermitsForBothChannels() = runBlocking {
        withTimeout(10_000) {
            responses(AIResponse("batch", listOf(call("Root", 1), call("Shizuku", 2), call("Root", 3, "id -u"))))
            val run = async { collect() }
            try {
                permissions.resolve(awaitRequest().id, PermissionChoice.ONCE)
                permissions.resolve(awaitRequest().id, PermissionChoice.ONCE)
                permissions.resolve(awaitRequest().id, PermissionChoice.REJECT)
                val events = run.await()
                assertEquals(setOf("Root", "Shizuku"), issued.map { it.tool }.toSet())
                assertEquals(2, issued.size)
                assertEquals(issued.map { it.id }.toSet(), revoked.toSet())
                assertTrue(livePermits.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(rejected.isEmpty())
                assertEquals(3, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
                assertTrue(events.none { it is AgentEvent.ToolExecutionStarted })
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun cancellationAfterIssuingPermits_revokesBothBeforeAnyExecution() = runBlocking {
        withTimeout(10_000) {
            val frozen = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            beforeFreeze = { frozen.complete(Unit); release.await() }
            responses(AIResponse("batch", listOf(call("Root", 1), call("Shizuku", 2))))
            val run = async { collect() }
            try {
                permissions.resolve(awaitRequest().id, PermissionChoice.ONCE)
                permissions.resolve(awaitRequest().id, PermissionChoice.ONCE)
                frozen.await()
                assertEquals(2, livePermits.size)
                run.cancelAndJoin()
                assertEquals(issued.map { it.id }.toSet(), revoked.toSet())
                assertTrue(livePermits.isEmpty())
                assertTrue(executed.isEmpty())
                assertTrue(rejected.isEmpty())
            } finally { release.complete(Unit); run.cancelAndJoin() }
        }
    }

    @Test
    fun changedIdentityAfterApproval_cannotExecuteQueuedPermit() = runBlocking {
        withTimeout(10_000) {
            for (tool in listOf("Root", "Shizuku")) {
                val frozen = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                beforeFreeze = { frozen.complete(Unit); release.await() }
                responses(AIResponse("proposal", listOf(call(tool, 1))), AIResponse("done"))
                val run = async { collect() }
                try {
                    permissions.resolve(awaitRequest().id, PermissionChoice.ONCE)
                    frozen.await()
                    val permit = issued.last()
                    assertEquals(tool, permit.tool)
                    if (tool == "Root") rootState.value = RootAccessState(true, 44, "root-authority")
                    else shizukuIdentity.value = ShizukuIdentity(0, "new-connection")
                    release.complete(Unit)
                    val events = run.await()
                    assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                    assertTrue(executed.isEmpty())
                    assertTrue(revoked.contains(permit.id))
                    assertTrue(livePermits.isEmpty())
                    assertEquals(permit.id, rejected.last().permitId)
                } finally { release.complete(Unit); run.cancelAndJoin() }
            }
        }
    }

    @Test
    fun pluginMutatedFinalCommands_areApprovedAndBoundToEachChannelsPermit() = runBlocking {
        withTimeout(10_000) {
            val originals = listOf(call("Root", 1), call("Shizuku", 2))
            coEvery { hooks.beforeTool(any(), any(), any()) } answers {
                val toolCall = secondArg<ToolCall>()
                PluginBeforeTool(JsonObject(mapOf("command" to JsonPrimitive(if (toolCall.name == "Root") "id -u" else "id -g"))))
            }
            responses(AIResponse("proposal", originals), AIResponse("done"))
            val run = async { collect() }
            try {
                val rootRequest = awaitRequest()
                assertEquals(JsonObject(mapOf("command" to JsonPrimitive("id -u"))), Json.parseToJsonElement(rootRequest.argsPreview))
                permissions.resolve(rootRequest.id, PermissionChoice.ONCE)
                val shizukuRequest = awaitRequest()
                assertEquals(JsonObject(mapOf("command" to JsonPrimitive("id -g"))), Json.parseToJsonElement(shizukuRequest.argsPreview))
                permissions.resolve(shizukuRequest.id, PermissionChoice.ONCE)
                val events = run.await()
                assertEquals(listOf("id -u", "id -g"), issued.map { it.command })
                assertEquals(issued.toSet(), executed.toSet())
                assertTrue(rejected.isEmpty())
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { !it.isError })
                assertEquals(originals, modelRequests.last().filterIsInstance<AgentMessage.AssistantMessage>().single().toolCalls)
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun dangerousElevation_rejectsForgedAlwaysChoiceWithoutIssuingPermit() = runBlocking {
        withTimeout(10_000) {
            for (tool in listOf("Root", "Shizuku")) {
                responses(AIResponse("dangerous", listOf(call(tool, 1, "rm -rf /data", elevate = true))), AIResponse("done"))
                val run = async { collect() }
                try {
                    val request = awaitRequest()
                    assertNull(request.sessionGrant)
                    permissions.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
                    val events = run.await()
                    val result = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
                    assertTrue(result.isError)
                    assertTrue(result.result.contains("INVALID_APPROVAL_CHOICE"))
                    assertTrue(issued.isEmpty())
                    assertTrue(executed.isEmpty())
                    assertTrue(allowed.isEmpty())
                } finally { run.cancelAndJoin() }
            }
        }
    }

    @Test
    fun alwaysGrantCannotTransferBetweenWorkflowSessions() = runBlocking {
        withTimeout(10_000) {
            allowed += "first-session" to "tool:Root:root-authority:42"
            allowed += "first-session" to "tool:Shizuku:0:root-connection"
            responses(AIResponse("root", listOf(call("Root", 1))), AIResponse("shizuku", listOf(call("Shizuku", 2))))
            val run = async { collect("second-session") }
            try {
                val rootRequest = awaitRequest()
                assertEquals("second-session", rootRequest.sessionId)
                assertTrue(issued.isEmpty())
                permissions.resolve(rootRequest.id, PermissionChoice.ONCE)
                val shizukuRequest = awaitRequest()
                assertEquals("second-session", shizukuRequest.sessionId)
                assertEquals("tool:Shizuku:0:root-connection", shizukuRequest.sessionGrant)
                permissions.resolve(shizukuRequest.id, PermissionChoice.REJECT)
                run.await()
                assertEquals(1, issued.size)
                assertEquals("second-session", issued.single().session)
                assertEquals(issued.toList(), executed.toList())
                assertFalse(allowed.any { it.first == "second-session" })
            } finally { run.cancelAndJoin() }
        }
    }

    @Test
    fun dangerousElevationStillAsksOnceAfterBothOrdinaryAlwaysGrants() = runBlocking {
        withTimeout(10_000) {
            responses(
                AIResponse("root", listOf(call("Root", 1))),
                AIResponse("shizuku", listOf(call("Shizuku", 2))),
                AIResponse("root dangerous", listOf(call("Root", 3, "rm -rf /data", elevate = true))),
                AIResponse("shizuku dangerous", listOf(call("Shizuku", 4, "rm -rf /system", elevate = true))),
                AIResponse("done")
            )
            val run = async { collect() }
            try {
                repeat(2) { permissions.resolve(awaitRequest().id, PermissionChoice.ALWAYS_SESSION) }
                repeat(2) {
                    val request = awaitRequest()
                    assertNull(request.sessionGrant)
                    permissions.resolve(request.id, PermissionChoice.ONCE)
                }
                val events = run.await()
                assertEquals(setOf("session" to "tool:Root:root-authority:42", "session" to "tool:Shizuku:0:root-connection"), allowed)
                assertEquals(4, seenRequests.size)
                assertEquals(4, issued.size)
                assertEquals(4, executed.size)
                assertEquals(4, issued.map { it.id }.toSet().size)
                assertTrue(rejected.isEmpty())
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { !it.isError })
            } finally { run.cancelAndJoin() }
        }
    }
}
