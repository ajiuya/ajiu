package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentRecoveryDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryStatus
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.ExecutionSourceChangedException
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WorkflowToolTimingTest {
    private val elapsed = AtomicLong(1_000L)
    private val recoveryClock = AtomicLong(1_000L)
    private val recoveryLedger = RecoveryDaoFixture()
    private val draftSaves = mutableListOf<Boolean>()
    private val recoveryStepAttempts = mutableListOf<String>()
    private val recoveryDao = object : AgentRecoveryDao by recoveryLedger.dao {
        override suspend fun beginRecoveryStep(runId: String, ownerFence: String, now: Long): RecoveryStep? {
            recoveryStepAttempts += runId
            return recoveryLedger.dao.beginRecoveryStep(runId, ownerFence, now)
        }

        override suspend fun saveRecoveryDraft(
            runId: String,
            ownerFence: String,
            marker: RecoveryStep,
            text: String,
            now: Long,
            minimumIntervalMs: Long,
            maximumBytes: Int
        ): Boolean = recoveryLedger.dao.saveRecoveryDraft(
            runId, ownerFence, marker, text, recoveryClock.get(), minimumIntervalMs, maximumBytes
        ).also { draftSaves += it }
    }
    private val recovery = RecoveryRepository(recoveryDao)
    private val recoveryPersistence = MessagePersistenceUseCase(mockk(relaxed = true), mockk<AgentDatabase>(relaxed = true), recovery)
    private val registry = mockk<ToolRegistry>()
    private val providers = mockk<AIProviderRepository>()
    private val openAIApi = mockk<OpenAIApi>()
    private val prompts = mockk<SystemPromptProvider>()
    private val permissions = mockk<ToolPermissionManager>()
    private val policy = mockk<ToolPermissionPolicyEngine>()
    private val rootManager = mockk<RootCommandManager>()
    private val shizukuManager = mockk<ShizukuManager>()
    private val rootState = MutableStateFlow(RootAccessState(true, 42, "root-authority"))
    private val compactor = mockk<ContextCompactor>()
    private val outputStore = mockk<ToolOutputStore>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val notifications = AgentNotificationCenter()
    private val injector = mockk<AgentEventInjector>()
    private val modelRequests = mutableListOf<List<AgentMessage>>()
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val context = AgentContext(null, null, "/project", null, sessionId = "session")
    private val hooks = mockk<com.aicode.feature.plugin.runtime.PluginHookDispatcher>(relaxed = true)
    private var beforeFreeze: suspend () -> Unit = {}
    private var permissionProfileSnapshot: ContainerProfileSnapshot? = null
    private val capturedExecutions = java.util.concurrent.CopyOnWriteArrayList<WorkspaceExecutionContext>()
    private val workflow = StatefulAgentWorkflow(
        toolRegistry = registry,
        aiProviderRepository = providers,
        providerFactory = com.aicode.feature.agent.domain.provider.ProviderInstanceFactory(
            openAIApi, mockk(), mockk(), metadata, mockk(relaxed = true),
            mockk { every { activeKey(any(), any()) } returns "test-key" }
        ),
        promptProvider = prompts,
        permissionManager = permissions,
        policyEngine = policy,
        contextCompactor = compactor,
        toolOutputStore = outputStore,
        modelMetadataService = metadata,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = defaultSettings,
        generalSettingsRepository = mockk(relaxed = true),
        sessionUseCase = mockk { coEvery { getSessionById(any()) } returns null },
        messagePersistenceUseCase = recoveryPersistence,
        checkpointManager = mockk(relaxed = true),
        llmCallRecordDao = records,
        agentNotificationCenter = notifications,
        eventInjector = injector,
        fileAccess = mockk(),
        appContext = mockk { every { getString(any()) } returns "Deferred until plan approval" },
        rootCommandManager = rootManager,
        shizukuManager = shizukuManager,
        recoveryRepository = recovery,
        pluginHooks = hooks,
        workspaceExecutionContextFactory = mockk {
            coEvery { freeze(any(), any()) } coAnswers {
                beforeFreeze()
                WorkspaceExecutionContext(
                    ExecutionMode.LOCAL_PROOT, "timing-test", mockk(), null, null,
                    profileSnapshot = permissionProfileSnapshot, checkSource = {}
                )
                    .also { capturedExecutions += it }
            }
        }
    )

    @Before
    fun prepare() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsed.get() }
        mockkObject(FileLogger)
        every { FileLogger.memoryCheckpoint(any(), any(), any(), any()) } returns Unit
        mockkConstructor(OpenAIAdapter::class)
        every { prompts.build(any()) } returns "system"
        every { rootManager.accessState } returns rootState
        coEvery { permissions.confirmHostWriteSessionGrant(any(), any()) } returns Unit
        coEvery { defaultSettings.getDefaultProviderId() } returns "provider"
        coEvery { defaultSettings.getDefaultModel() } returns "model"
        coEvery { providers.getProviderById("provider") } returns AIProviderConfig(
            id = "provider", name = "test", type = ProviderType.OPENAI, apiKey = "test-key",
            baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model")
        )
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("model", contextTokens = 100_000)
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CompactionResult(firstArg<List<AgentMessage>>(), false) }
        coEvery { policy.evaluate(any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW)
        every { outputStore.process(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        coEvery { hooks.beforeTool(any(), any(), any()) } answers { com.aicode.feature.plugin.runtime.PluginBeforeTool(thirdArg()) }
        coEvery { hooks.afterTool(any(), any(), any()) } answers { thirdArg<ToolResult>() }
    }

    @After
    fun cleanup() {
        unmockkConstructor(OpenAIAdapter::class)
        unmockkObject(FileLogger)
        unmockkStatic(SystemClock::class)
    }

    private fun responses(vararg responses: AIResponse) {
        val pending = ArrayDeque(responses.toList())
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            modelRequests += secondArg<List<AgentMessage>>().toList()
            flowOf<AIStreamChunk>(AIStreamChunk.Final(pending.removeFirst()))
        }
    }

    private fun register(vararg tools: GatedTool) {
        tools.forEach { tool -> every { registry.getTool(tool.name) } returns tool }
    }

    private class GatedTool(
        override val name: String,
        private val onCancellation: suspend () -> Unit = {}
    ) : AgentTool() {
        override val description = "test tool"
        override val parameters = emptyMap<String, ToolParameter>()
        val entered = CompletableDeferred<Map<String, JsonElement>>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<CancellationException>()
        val permissionArguments = mutableListOf<Map<String, JsonElement>>()
        val executionContexts = java.util.concurrent.CopyOnWriteArrayList<AgentContext>()

        override fun buildPermissionRequest(
            callId: String,
            args: Map<String, JsonElement>,
            argsPreview: String
        ): PendingToolPermission {
            permissionArguments += args.toMap()
            return super.buildPermissionRequest(callId, args, argsPreview)
        }

        override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
            executionContexts += context
            return execute(args)
        }

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            entered.complete(args.toMap())
            return try {
                release.await()
                ToolResult.Success(JsonPrimitive(name))
            } catch (failure: CancellationException) {
                cancelled.complete(failure)
                withContext(NonCancellable) { onCancellation() }
                throw failure
            }
        }
    }

    private suspend fun collect(
        tools: List<AgentTool>,
        events: MutableList<AgentEvent>,
        acknowledge: (AgentEvent.ToolCallFinished) -> Boolean = { true }
    ) {
        workflow.executeEvents("test", context, tools).collect { event ->
            events += event
            when (event) {
                is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                is AgentEvent.ToolCallFinished -> if (acknowledge(event)) {
                    notifications.ack("session", event.notificationSeqs)
                    event.persisted?.complete(Unit)
                }
                else -> Unit
            }
        }
    }

    // Exercise permit delivery/revocation using in-memory tools; no native execution is involved.
    private fun mockSessionPermits() {
        coEvery { policy.evaluate(any(), any(), any(), any()) } answers {
            ToolPermissionPolicyEngine.EvalResult(
                ToolPermissionPolicyEngine.Verdict.ALLOW,
                rootGeneration = 42L,
                rootAuthorityId = "root-authority",
                permissionSource = permissionSource(requireNotNull(thirdArg<String?>()))
            )
        }
        coEvery { rootManager.authorize(any(), any(), 42L, "root-authority") } answers {
            "permit-${firstArg<String>()}"
        }
        every { rootManager.revokePermit(any()) } returns Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coroutineSplitRegression_recollectingSameFlowResetsStateApprovalsBudgetAndProvider() = runTest(timeout = 10.seconds) {
        val tool = GatedTool("recollected-tool")
        register(tool)
        tool.release.complete(Unit)
        mockSessionPermits()
        coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } returns
            ToolPermissionPolicyEngine.EvalResult(
                ToolPermissionPolicyEngine.Verdict.ASK,
                rootGeneration = 42L,
                rootAuthorityId = "root-authority"
            )
        val approvals = mutableListOf<PendingToolPermission>()
        coEvery { permissions.awaitApproval(any()) } answers {
            approvals += firstArg<PendingToolPermission>()
            PermissionChoice.ONCE
        }
        val issuedPermits = listOf("recollection-permit-1", "recollection-permit-2")
        val authorizations = AtomicInteger()
        coEvery { rootManager.authorize("session", "recollection-command", 42L, "root-authority") } answers {
            issuedPermits[authorizations.getAndIncrement()]
        }
        val usedProviders = mutableListOf<AIProvider>()
        coEvery { compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            usedProviders += secondArg<AIProvider>()
            CompactionResult(firstArg<List<AgentMessage>>(), false)
        }
        val call = ToolCall("repeated-call", tool.name, mapOf("command" to JsonPrimitive("recollection-command")))
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            val messages = secondArg<List<AgentMessage>>().toList()
            modelRequests += messages
            assertEquals(listOf(tool), thirdArg<List<AgentTool>>())
            val response = when (val previousReplies = messages.filterIsInstance<AgentMessage.AssistantMessage>().size) {
                0 -> AIResponse("proposal", listOf(call))
                // Each collection uses all three continuations, within the cumulative output budget.
                1, 2, 3 -> AIResponse("part-$previousReplies", stopReason = "length", outputTokens = 20_000)
                4 -> AIResponse("done")
                else -> error("Unexpected replay: $messages")
            }
            flowOf<AIStreamChunk>(AIStreamChunk.Final(response))
        }
        val coldFlow = workflow.executeEvents("recollect", context.copy(inputMessageId = "recollection-input"), listOf(tool))
        val collections = mutableListOf<List<AgentEvent>>()
        assertTrue(modelRequests.isEmpty())
        coVerify(exactly = 0) { providers.getProviderById(any()) }

        repeat(2) { index ->
            val events = mutableListOf<AgentEvent>()
            coldFlow.collect { event ->
                events += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                    else -> Unit
                }
            }
            collections += events
            assertTrue(events.none { it is AgentEvent.Failed })
            assertEquals(listOf("proposal", "part-1", "part-2", "part-3", "done"),
                events.filterIsInstance<AgentEvent.AssistantText>().map { it.content })
            assertEquals(listOf(call.id), events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.id })
            assertEquals(listOf(call.id), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.id })
            val result = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
            assertEquals(call.id, result.id)
            assertFalse(result.isError)
            assertEquals(events.filterIsInstance<AgentEvent.AssistantText>().last().messageId,
                events.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
            assertEquals(1, events.count { it is AgentEvent.Completed })
            assertTrue(events.last() is AgentEvent.Completed)
            assertEquals((index + 1) * 5, modelRequests.size)
            val initialRequest = modelRequests[index * 5]
            assertEquals(listOf("recollection-input"), initialRequest.filterIsInstance<AgentMessage.UserMessage>().map { it.id })
            assertTrue(initialRequest.none { it is AgentMessage.AssistantMessage || it is AgentMessage.ToolResultMessage })
            assertEquals(listOf(call.id), modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
            verify(exactly = 1) { rootManager.revokePermit(issuedPermits[index]) }
        }

        assertEquals(10, usedProviders.size)
        assertTrue(usedProviders.take(5).all { it === usedProviders.first() })
        assertTrue(usedProviders.drop(5).all { it === usedProviders[5] })
        assertTrue("Recollection must create its own mutable provider", usedProviders.first() !== usedProviders[5])
        val firstIds = collections.first().filterIsInstance<AgentEvent.AssistantText>().map { it.messageId }.toSet()
        assertTrue(collections.last().filterIsInstance<AgentEvent.AssistantText>().none { it.messageId in firstIds })
        assertEquals(2, approvals.size)
        assertEquals(2, approvals.map { it.id }.toSet().size)
        assertTrue(approvals.all { it.sessionId == "session" && it.id != call.id })
        assertEquals(issuedPermits, tool.executionContexts.map { it.rootApprovalId })
        coVerify(exactly = 2) { permissions.awaitApproval(any()) }
        coVerify(exactly = 2) { rootManager.authorize("session", "recollection-command", 42L, "root-authority") }
        issuedPermits.forEach { permit -> verify(exactly = 1) { rootManager.revokePermit(permit) } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coroutineSplitRegression_interleavedSessionsKeepResponsesToolAcksAndLastPersistedIdsSeparate() = runTest(timeout = 10.seconds) {
        val a = GatedTool("tool-a")
        val b = GatedTool("tool-b")
        register(a, b)
        mockSessionPermits()
        val tools = mapOf("split-a" to a, "split-b" to b)
        val requests = tools.mapValues { mutableListOf<List<AgentMessage>>() }
        val streamEntered = tools.mapValues { CompletableDeferred<Unit>() }
        val releaseStream = tools.mapValues { CompletableDeferred<Unit>() }
        val bFinalEntered = CompletableDeferred<Unit>()
        val releaseBFinal = CompletableDeferred<Unit>()
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            val messages = secondArg<List<AgentMessage>>().toList()
            val session = messages.filterIsInstance<AgentMessage.UserMessage>().single().id.removePrefix("input-")
            val tool = tools.getValue(session)
            requests.getValue(session) += messages
            assertEquals(listOf(tool), thirdArg<List<AgentTool>>())
            val results = messages.filterIsInstance<AgentMessage.ToolResultMessage>()
            flow {
                if (results.isEmpty()) {
                    if (session == "split-b") streamEntered.getValue("split-a").await()
                    emit(AIStreamChunk.TextDelta("proposal-$session"))
                    emit(AIStreamChunk.ReasoningDelta("reason-$session"))
                    streamEntered.getValue(session).complete(Unit)
                    releaseStream.getValue(session).await()
                    emit(AIStreamChunk.Final(AIResponse("proposal-$session", listOf(
                        ToolCall("shared-call", tool.name, mapOf("command" to JsonPrimitive(session)))
                    ))))
                } else {
                    assertEquals("shared-call", results.single().id)
                    assertEquals(tool.name, results.single().toolName)
                    assertEquals(ToolResult.Success(JsonPrimitive(tool.name)).toTransportString(), results.single().result)
                    if (session == "split-b") {
                        bFinalEntered.complete(Unit)
                        releaseBFinal.await()
                    }
                    // Empty B final must leave B's own persisted proposal as its turn's message ID.
                    emit(AIStreamChunk.Final(AIResponse(if (session == "split-a") "answer-a" else "")))
                }
            }
        }
        val eventsA = mutableListOf<AgentEvent>()
        val eventsB = mutableListOf<AgentEvent>()
        val resultsA = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val resultsB = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val finalA = CompletableDeferred<AgentEvent.AssistantText>()
        val jobA = async {
            workflow.executeEvents("request-a", context.copy(sessionId = "split-a", inputMessageId = "input-split-a"), listOf(a)).collect { event ->
                eventsA += event
                when (event) {
                    is AgentEvent.AssistantText -> if (event.content == "answer-a") finalA.complete(event) else event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> resultsA.send(event)
                    else -> Unit
                }
            }
        }
        val jobB = async {
            workflow.executeEvents("request-b", context.copy(sessionId = "split-b", inputMessageId = "input-split-b"), listOf(b)).collect { event ->
                eventsB += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> resultsB.send(event)
                    else -> Unit
                }
            }
        }
        try {
            streamEntered.values.forEach { it.await() }
            releaseStream.getValue("split-a").complete(Unit)
            a.entered.await()
            releaseStream.getValue("split-b").complete(Unit)
            b.entered.await()
            assertEquals("split-a", a.executionContexts.single().sessionId)
            assertEquals("split-b", b.executionContexts.single().sessionId)
            assertEquals("permit-split-a", a.executionContexts.single().rootApprovalId)
            assertEquals("permit-split-b", b.executionContexts.single().rootApprovalId)
            b.release.complete(Unit)
            val resultB = resultsB.receive()
            a.release.complete(Unit)
            val resultA = resultsA.receive()
            assertEquals("tool-a", resultA.toolName)
            assertEquals("tool-b", resultB.toolName)
            assertFalse(resultA.isError)
            assertFalse(resultB.isError)
            val ackA = requireNotNull(resultA.persisted)
            val ackB = requireNotNull(resultB.persisted)
            assertTrue(ackA !== ackB)
            assertFalse(ackA.isCompleted)
            assertFalse(ackB.isCompleted)
            ackA.complete(Unit)
            val answerA = finalA.await()
            runCurrent()
            assertFalse(ackB.isCompleted)
            assertEquals(1, requests.getValue("split-b").size)
            assertFalse(bFinalEntered.isCompleted)
            assertTrue(jobB.isActive)
            ackB.complete(Unit)
            bFinalEntered.await()
            val answerAck = requireNotNull(answerA.persisted)
            assertFalse(answerAck.isCompleted)
            assertTrue(eventsA.none { it is AgentEvent.TurnFinished || it is AgentEvent.Completed })
            answerAck.complete(Unit)
            jobA.await()
            verify(exactly = 1) { rootManager.revokePermit("permit-split-a") }
            verify(exactly = 0) { rootManager.revokePermit("permit-split-b") }
            assertTrue(jobB.isActive)
            releaseBFinal.complete(Unit)
            jobB.await()

            assertEquals(listOf("proposal-split-a", "answer-a"), eventsA.filterIsInstance<AgentEvent.AssistantText>().map { it.content })
            assertEquals(listOf("proposal-split-b"), eventsB.filterIsInstance<AgentEvent.AssistantText>().map { it.content })
            for ((session, events) in listOf("split-a" to eventsA, "split-b" to eventsB)) {
                val assistants = events.filterIsInstance<AgentEvent.AssistantText>()
                assertEquals("reason-$session", assistants.first().reasoning)
                assertEquals("proposal-$session", events.filterIsInstance<AgentEvent.AssistantDelta>().last().accumulated)
                assertEquals(assistants.last().messageId, events.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
                assertEquals(listOf("shared-call"), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.id })
                assertEquals(listOf("shared-call"), events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })
                assertTrue(events.none { it is AgentEvent.Failed })
                assertEquals(1, events.count { it is AgentEvent.Completed })
                assertTrue(events.last() is AgentEvent.Completed)
                val replay = requests.getValue(session)
                assertEquals(2, replay.size)
                assertEquals(listOf("proposal-$session"), replay.last().filterIsInstance<AgentMessage.AssistantMessage>().map { it.content })
                verify(exactly = 1) { rootManager.revokePermit("permit-$session") }
            }
            assertTrue(eventsA.filterIsInstance<AgentEvent.TurnFinished>().single().messageId !=
                eventsB.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
        } finally {
            withContext(NonCancellable) {
                jobA.cancel()
                jobB.cancel()
                releaseStream.values.forEach { it.complete(Unit) }
                releaseBFinal.complete(Unit)
                a.release.complete(Unit)
                b.release.complete(Unit)
                (eventsA + eventsB).forEach { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                        is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                        else -> Unit
                    }
                }
                jobA.cancelAndJoin()
                jobB.cancelAndJoin()
                resultsA.close()
                resultsB.close()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coroutineSplitRegression_cancellingOneStreamRecordsCancellationAndRevokesOnlyItsPermit() = runTest(timeout = 10.seconds) {
        val a = GatedTool("cancelled-tool")
        val b = GatedTool("surviving-tool")
        register(a, b)
        mockSessionPermits()
        val statistics = java.util.concurrent.CopyOnWriteArrayList<LlmCallRecordEntity>()
        coEvery { records.insert(any()) } answers { statistics += firstArg<LlmCallRecordEntity>(); Unit }
        val tools = mapOf("cancel-a" to a, "survive-b" to b)
        val requests = tools.mapValues { mutableListOf<List<AgentMessage>>() }
        val cancelledStreamEntered = CompletableDeferred<Unit>()
        val releaseCancelledStream = CompletableDeferred<Unit>()
        val cancelledStreamClosed = CompletableDeferred<Unit>()
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            val messages = secondArg<List<AgentMessage>>().toList()
            val session = messages.filterIsInstance<AgentMessage.UserMessage>().single().id.removePrefix("input-")
            val tool = tools.getValue(session)
            requests.getValue(session) += messages
            assertEquals(listOf(tool), thirdArg<List<AgentTool>>())
            val results = messages.filterIsInstance<AgentMessage.ToolResultMessage>()
            flow {
                if (results.isEmpty()) {
                    emit(AIStreamChunk.Final(AIResponse("proposal-$session", listOf(
                        ToolCall("shared-call", tool.name, mapOf("command" to JsonPrimitive(session)))
                    ), inputTokens = 11, outputTokens = 7)))
                } else {
                    assertEquals("shared-call", results.single().id)
                    assertEquals(tool.name, results.single().toolName)
                    if (session == "cancel-a") {
                        try {
                            emit(AIStreamChunk.TextDelta("partial-a"))
                            cancelledStreamEntered.complete(Unit)
                            releaseCancelledStream.await()
                            emit(AIStreamChunk.Final(AIResponse("must not finish")))
                        } finally {
                            cancelledStreamClosed.complete(Unit)
                        }
                    } else {
                        emit(AIStreamChunk.Final(AIResponse("answer-b", inputTokens = 33, outputTokens = 9)))
                    }
                }
            }
        }
        val eventsA = mutableListOf<AgentEvent>()
        val eventsB = mutableListOf<AgentEvent>()
        val resultsA = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val resultsB = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val jobA = async {
            workflow.executeEvents("request-a", context.copy(sessionId = "cancel-a", inputMessageId = "input-cancel-a"), listOf(a)).collect { event ->
                eventsA += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> resultsA.send(event)
                    else -> Unit
                }
            }
        }
        val jobB = async {
            workflow.executeEvents("request-b", context.copy(sessionId = "survive-b", inputMessageId = "input-survive-b"), listOf(b)).collect { event ->
                eventsB += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> resultsB.send(event)
                    else -> Unit
                }
            }
        }
        try {
            a.entered.await()
            b.entered.await()
            assertEquals("permit-cancel-a", a.executionContexts.single().rootApprovalId)
            assertEquals("permit-survive-b", b.executionContexts.single().rootApprovalId)
            b.release.complete(Unit)
            val resultB = resultsB.receive()
            a.release.complete(Unit)
            val resultA = resultsA.receive()
            assertFalse(resultA.isError)
            assertFalse(resultB.isError)
            requireNotNull(resultA.persisted).complete(Unit)
            cancelledStreamEntered.await()
            jobA.cancelAndJoin()
            runCurrent()
            assertTrue(jobA.isCancelled)
            assertTrue(cancelledStreamClosed.isCompleted)
            assertFalse(releaseCancelledStream.isCompleted)
            assertTrue(eventsA.none { it is AgentEvent.Completed || it is AgentEvent.TurnFinished || it is AgentEvent.Failed })
            assertEquals(listOf("proposal-cancel-a"), eventsA.filterIsInstance<AgentEvent.AssistantText>().map { it.content })
            verify(exactly = 1) { rootManager.revokePermit("permit-cancel-a") }
            verify(exactly = 0) { rootManager.revokePermit("permit-survive-b") }
            val survivingAck = requireNotNull(resultB.persisted)
            assertFalse(survivingAck.isCompleted)
            assertFalse(survivingAck.isCancelled)
            assertTrue(jobB.isActive)
            assertEquals(1, requests.getValue("survive-b").size)
            assertEquals(listOf("success", "cancelled"), statistics.filter { it.sessionId == "cancel-a" }.map { it.status })
            assertEquals(listOf("success"), statistics.filter { it.sessionId == "survive-b" }.map { it.status })

            survivingAck.complete(Unit)
            jobB.await()
            assertEquals(listOf("proposal-survive-b", "answer-b"), eventsB.filterIsInstance<AgentEvent.AssistantText>().map { it.content })
            assertEquals(eventsB.filterIsInstance<AgentEvent.AssistantText>().last().messageId,
                eventsB.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
            assertEquals(listOf("shared-call"), eventsB.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })
            assertTrue(eventsB.none { it is AgentEvent.Failed })
            assertEquals(1, eventsB.count { it is AgentEvent.Completed })
            assertTrue(eventsB.last() is AgentEvent.Completed)
            assertEquals(2, requests.getValue("cancel-a").size)
            assertEquals(2, requests.getValue("survive-b").size)
            assertEquals(listOf("success", "success"), statistics.filter { it.sessionId == "survive-b" }.map { it.status })
            assertEquals(listOf(7, 0), statistics.filter { it.sessionId == "cancel-a" }.map { it.outputTokens })
            assertEquals(listOf(7, 9), statistics.filter { it.sessionId == "survive-b" }.map { it.outputTokens })
            assertEquals(4, statistics.size)
            assertTrue(statistics.all { it.kind == "chat" && it.providerId == "provider" && it.model == "model" })
            verify(exactly = 1) { rootManager.revokePermit("permit-cancel-a") }
            verify(exactly = 1) { rootManager.revokePermit("permit-survive-b") }
        } finally {
            withContext(NonCancellable) {
                jobA.cancel()
                jobB.cancel()
                releaseCancelledStream.complete(Unit)
                a.release.complete(Unit)
                b.release.complete(Unit)
                (eventsA + eventsB).forEach { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                        is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                        else -> Unit
                    }
                }
                jobA.cancelAndJoin()
                jobB.cancelAndJoin()
                resultsA.close()
                resultsB.close()
            }
        }
    }

    @Test
    fun sourceAuthorizationDuringFreeze_finishesOnceWithoutExecutionOrAnotherModelCall() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("source-dependent")
            register(tool)
            beforeFreeze = { withContext(Dispatchers.IO) { throw LocalRuntimeAuthorizationRequired() } }
            responses(
                AIResponse("Proposed tool", listOf(ToolCall("source-id", tool.name, emptyMap()))),
                AIResponse("must not be requested")
            )
            val events = mutableListOf<AgentEvent>()

            val failure = runCatching { collect(listOf(tool), events) }.exceptionOrNull()

            assertNull("Source authorization must finish the Flow instead of escaping it", failure)
            assertEquals("LOCAL_RUNTIME_AUTHORIZATION_REQUIRED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
            assertEquals(1, events.count { it is AgentEvent.Completed })
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(events.indexOfFirst { it is AgentEvent.Failed } < events.indexOfFirst { it is AgentEvent.Completed })
            assertEquals(1, events.filterIsInstance<AgentEvent.TurnFinished>().size)
            assertEquals(1, events.filterIsInstance<AgentEvent.AssistantText>().size)
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
            assertFalse(tool.entered.isCompleted)
            assertEquals(1, modelRequests.size)
            verify(exactly = 1) { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sourceAuthorizationInParallel_waitsForCancelledSiblingCleanupAndKeepsCompletedResult() = runTest(timeout = 10.seconds) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val completed = GatedTool("completed")
        val running = GatedTool("running") {
            cleanupStarted.complete(Unit)
            allowCleanup.await()
            cleanupFinished.complete(Unit)
        }
        val refused = GatedTool("refused")
        val tools = listOf(completed, running, refused)
        register(*tools.toTypedArray())
        val freezeCalls = AtomicInteger()
        val refusedFreeze = CompletableDeferred<Unit>()
        val rejectSource = CompletableDeferred<Unit>()
        beforeFreeze = {
            if (freezeCalls.incrementAndGet() == 3) {
                withContext(Dispatchers.IO) {
                    refusedFreeze.complete(Unit)
                    rejectSource.await()
                    throw LocalRuntimeAuthorizationRequired()
                }
            }
        }
        val calls = tools.map { ToolCall("${it.name}-id", it.name, emptyMap()) }
        responses(AIResponse("Parallel proposal", calls), AIResponse("must not be requested"))
        val events = mutableListOf<AgentEvent>()
        val completedResult = CompletableDeferred<AgentEvent.ToolCallFinished>()
        val failedAfterCleanup = CompletableDeferred<Boolean>()
        val job = async {
            runCatching {
                workflow.executeEvents("test", context, tools).collect { event ->
                    events += event
                    when (event) {
                        is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                        is AgentEvent.ToolCallFinished -> {
                            if (event.id == "completed-id") completedResult.complete(event)
                            event.persisted?.complete(Unit)
                        }
                        is AgentEvent.Failed -> failedAfterCleanup.complete(cleanupFinished.isCompleted)
                        else -> Unit
                    }
                }
            }
        }
        try {
            completed.entered.await()
            running.entered.await()
            refusedFreeze.await()
            completed.release.complete(Unit)
            val successfulResult = completedResult.await()
            assertFalse(successfulResult.isError)
            assertEquals(ToolResult.Success(JsonPrimitive(completed.name)).toTransportString(), successfulResult.result)
            assertFalse(refused.entered.isCompleted)
            rejectSource.complete(Unit)
            cleanupStarted.await()
            runCurrent()
            assertTrue(running.cancelled.isCompleted)
            assertFalse(running.release.isCompleted)
            assertFalse(cleanupFinished.isCompleted)
            assertFalse(job.isCompleted)
            assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.Completed })
            assertEquals(listOf(successfulResult), events.filterIsInstance<AgentEvent.ToolCallFinished>())
            assertEquals(1, modelRequests.size)
            allowCleanup.complete(Unit)
            val outcome = job.await()
            assertTrue(cleanupFinished.isCompleted)
            assertNull("Source authorization must be handled after sibling cleanup", outcome.exceptionOrNull())
            assertEquals("LOCAL_RUNTIME_AUTHORIZATION_REQUIRED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
            assertTrue(failedAfterCleanup.await())
            assertEquals(1, events.count { it is AgentEvent.Completed })
            assertTrue(events.last() is AgentEvent.Completed)
            assertEquals(listOf(successfulResult), events.filterIsInstance<AgentEvent.ToolCallFinished>())
            assertEquals(setOf("completed-id", "running-id"), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.id }.toSet())
            assertFalse(refused.entered.isCompleted)
            assertEquals(1, modelRequests.size)
        } finally {
            rejectSource.complete(Unit)
            allowCleanup.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun sourceAuthorizationBoundary_preservesCancellationFromIoFreeze() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("cancelled-source")
            register(tool)
            val cancelled = CancellationException("Synthetic source capture cancellation")
            beforeFreeze = { withContext(Dispatchers.IO) { throw cancelled } }
            responses(AIResponse("Proposal", listOf(ToolCall("cancelled-source-id", tool.name, emptyMap()))))
            val events = mutableListOf<AgentEvent>()

            val failure = runCatching { collect(listOf(tool), events) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(cancelled.message, failure?.message)
            assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
            assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.Completed || it is AgentEvent.TurnFinished })
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
            assertFalse(tool.entered.isCompleted)
            assertEquals(1, modelRequests.size)
        }
    }

    @Test
    fun sourceAuthorizationBoundary_doesNotClassifyOrdinaryIoFailureByItsMessage() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("unavailable-source")
            register(tool)
            val unavailable = IOException(LocalRuntimeAuthorizationRequired().message)
            beforeFreeze = { withContext(Dispatchers.IO) { throw unavailable } }
            responses(AIResponse("Proposal", listOf(ToolCall("unavailable-source-id", tool.name, emptyMap()))))
            val events = mutableListOf<AgentEvent>()

            val failure = runCatching { collect(listOf(tool), events) }.exceptionOrNull()

            assertEquals(IOException::class.java, failure?.javaClass)
            assertEquals(unavailable.message, failure?.message)
            assertTrue(generateSequence(failure) { it.cause }.any { it === unavailable })
            assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.Completed || it is AgentEvent.TurnFinished })
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
            assertFalse(tool.entered.isCompleted)
            assertEquals(1, modelRequests.size)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parameterOnlyGenerationUpdatesSpeedBeyondThePreviewLimitAndCalibratesAtFinish() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.ToolCallDeclared("writeFile"))
            delay(250)
            emit(AIStreamChunk.ToolCallArgumentsDelta("call", "writeFile", "preview", 400_000, 0))
            delay(500)
            emit(AIStreamChunk.ToolCallArgumentsDelta("call", "writeFile", "preview", 400_004, 0))
            delay(750)
            emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 24), requestDurationMillis = 1_500))
        }
        val events = mutableListOf<AgentEvent>()
        collect(emptyList(), events)
        assertTrue(events.none { it is AgentEvent.Failed })
        val speeds = events.filterIsInstance<AgentEvent.GenerationSpeed>()
        assertTrue(speeds.any { it.estimated && it.tokensPerSecond > 0.0 })
        assertEquals(16.0, speeds.last().tokensPerSecond, 0.001)
        assertFalse(speeds.last().estimated)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolCallArgumentsPreview>().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryAndKeySwitchDiscardCumulativeArgumentCountersAndPreviewDeduplication() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        for (restart in listOf(
            AIStreamChunk.Retrying(1, 3, com.aicode.feature.agent.domain.provider.RetryErrorInfo(
                com.aicode.feature.agent.domain.provider.RetryErrorKind.TIMEOUT)),
            AIStreamChunk.KeySwitched(2, 3)
        )) {
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
                emit(AIStreamChunk.ToolCallDeclared("writeFile"))
                delay(250)
                emit(AIStreamChunk.ToolCallArgumentsDelta("same-id", "writeFile", "same-preview", 40_000, 0))
                delay(250)
                emit(restart)
                delay(5_000)
                emit(AIStreamChunk.ToolCallDeclared("writeFile"))
                delay(250)
                emit(AIStreamChunk.ToolCallArgumentsDelta("same-id", "writeFile", "same-preview", 4, 0))
                delay(750)
                emit(AIStreamChunk.Final(AIResponse("done"), requestDurationMillis = 1_000))
            }
            val events = mutableListOf<AgentEvent>()
            collect(emptyList(), events)
            assertTrue(events.none { it is AgentEvent.Failed })
            assertEquals(2, events.filterIsInstance<AgentEvent.ToolCallArgumentsPreview>().size)
            val finalSpeed = events.filterIsInstance<AgentEvent.GenerationSpeed>().last()
            assertEquals(1.0, finalSpeed.tokensPerSecond, 0.001)
            assertTrue(finalSpeed.estimated)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalOnlyAndSingleInstantResponsesDoNotCreateAnArtificialQuarterSecondRate() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        for (textDelta in listOf(false, true)) {
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
                if (textDelta) emit(AIStreamChunk.TextDelta("whole buffered response"))
                emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 40_000), requestDurationMillis = 0))
            }
            val events = mutableListOf<AgentEvent>()
            collect(emptyList(), events)
            assertTrue(events.none { it is AgentEvent.GenerationSpeed || it is AgentEvent.Failed })
            assertTrue(events.any { it is AgentEvent.Completed })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalAverageIncludesTheWaitBeforeVisibleOutput() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
            delay(6_778)
            emit(AIStreamChunk.TextDelta("buffered content"))
            delay(3_222)
            emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 290), requestDurationMillis = 10_000))
        }
        val events = mutableListOf<AgentEvent>()
        collect(emptyList(), events)
        assertTrue(events.none { it is AgentEvent.Failed })
        val finalSpeed = events.filterIsInstance<AgentEvent.GenerationSpeed>().last()
        assertEquals(29.0, finalSpeed.tokensPerSecond, 0.001)
        assertFalse(finalSpeed.estimated)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalAverageUsesTheFrozenDurationBeforeStreamCleanup() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
            delay(9_000)
            emit(AIStreamChunk.TextDelta("body"))
            delay(1_000)
            emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 290), requestDurationMillis = 10_000))
            delay(5_000)
        }
        val events = mutableListOf<AgentEvent>()
        collect(emptyList(), events)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertTrue(testScheduler.currentTime >= 15_000)
        val finalSpeed = events.filterIsInstance<AgentEvent.GenerationSpeed>().last()
        assertEquals(29.0, finalSpeed.tokensPerSecond, 0.001)
        assertFalse(finalSpeed.estimated)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalOnlyResponseWithMeasuredRequestTimeHasAnAverage() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
            delay(10_000)
            emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 290), requestDurationMillis = 10_000))
        }
        val events = mutableListOf<AgentEvent>()
        collect(emptyList(), events)
        assertTrue(events.none { it is AgentEvent.Failed })
        val finalSpeed = events.filterIsInstance<AgentEvent.GenerationSpeed>().single()
        assertEquals(29.0, finalSpeed.tokensPerSecond, 0.001)
        assertFalse(finalSpeed.estimated)
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalResponseWithoutRequestTimingPreservesTheLiveEstimate() = runTest {
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.TextDelta("a".repeat(400)))
            delay(750)
            emit(AIStreamChunk.TextDelta("aaaa"))
            delay(500)
            emit(AIStreamChunk.Final(AIResponse("done", outputTokens = 290)))
        }
        val events = mutableListOf<AgentEvent>()
        collect(emptyList(), events)
        assertTrue(events.none { it is AgentEvent.Failed })
        val speeds = events.filterIsInstance<AgentEvent.GenerationSpeed>()
        assertTrue(speeds.isNotEmpty())
        assertTrue(speeds.all { it.estimated })
        assertTrue(speeds.last().tokensPerSecond > 0)
    }

    @Test
    fun automaticCompactionContinuesTheLatestRequestInsteadOfAskingForAProgressReport() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("nextStep")
            tool.release.complete(Unit)
            register(tool)
            val marker = AgentMessage.UserMessage(
                id = "compaction-marker",
                content = CONTEXT_COMPACTION_MARKER,
                isCompactionMarker = true
            )
            val summary = AgentMessage.AssistantMessage(
                id = "compaction-summary",
                content = "The requested implementation is not finished yet.",
                isContextSummary = true
            )
            var compactedOnce = false
            coEvery {
                compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                if (compactedOnce) CompactionResult(firstArg<List<AgentMessage>>(), compacted = false)
                else {
                    compactedOnce = true
                    CompactionResult(listOf(marker, summary) + firstArg<List<AgentMessage>>(), compacted = true)
                }
            }
            responses(
                AIResponse("I will continue with the next step.", listOf(ToolCall("next-step", tool.name, emptyMap()))),
                AIResponse("The task is complete.")
            )
            val events = mutableListOf<AgentEvent>()

            collect(listOf(tool), events)

            assertEquals(2, modelRequests.size)
            val firstRequest = modelRequests.first()
            val markerSent = firstRequest.filterIsInstance<AgentMessage.UserMessage>().single { it.isCompactionMarker }
            assertEquals(CONTEXT_COMPACTION_MARKER, markerSent.content)
            assertTrue(markerSent.content.contains("Continue the latest user request"))
            assertTrue(firstRequest.any { it is AgentMessage.AssistantMessage && it.isContextSummary })
            assertEquals(listOf("next-step"), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.id })
            assertTrue(events.last() is AgentEvent.Completed)
        }
    }

    @Test
    fun parallelToolsFinishIndividuallyBeforeAwaitAllAndModelResultsKeepCallOrder() = runBlocking {
        withTimeout(10_000) {
            val slow = GatedTool("slow")
            val fast = GatedTool("fast")
            register(slow, fast)
            val calls = listOf(
                ToolCall("slow-id", slow.name, mapOf("tool_title" to JsonPrimitive("Slow action"))),
                ToolCall("fast-id", fast.name, mapOf("tool_title" to JsonPrimitive("Fast action")))
            )
            responses(AIResponse("", calls), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            val finishedEvents = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
            val job = launch {
                collect(listOf(slow, fast), events) { event ->
                    finishedEvents.trySend(event)
                    false
                }
            }
            slow.entered.await()
            fast.entered.await()
            elapsed.set(1_125L)
            fast.release.complete(Unit)
            val fastFinished = finishedEvents.receive()

            assertEquals("fast-id", fastFinished.id)
            assertEquals(125L, fastFinished.durationMs)
            assertEquals("Fast action", fastFinished.actionTitle)
            assertFalse(slow.release.isCompleted)
            assertEquals(1, modelRequests.size)
            assertEquals(listOf("fast-id"), events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })

            elapsed.set(1_800L)
            slow.release.complete(Unit)
            val slowFinished = finishedEvents.receive()
            assertEquals("slow-id", slowFinished.id)
            assertEquals(800L, slowFinished.durationMs)
            assertNotNull(fastFinished.persisted)
            assertNotNull(slowFinished.persisted)
            assertFalse(job.isCompleted)

            elapsed.set(6_800L)
            fastFinished.persisted!!.complete(Unit)
            assertFalse(job.isCompleted)
            assertEquals(1, modelRequests.size)
            slowFinished.persisted!!.complete(Unit)
            job.join()

            val started = events.filterIsInstance<AgentEvent.ToolExecutionStarted>().associateBy { it.id }
            val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>()
            assertEquals(listOf("fast-id", "slow-id"), finished.map { it.id })
            assertEquals(listOf(125L, 800L), finished.map { it.durationMs })
            finished.forEach { event ->
                assertFalse(event.isError)
                assertEquals(started.getValue(event.id).startedAtMs, event.startedAtMs)
                assertEquals(1_000L, started.getValue(event.id).startedElapsedMs)
            }
            assertEquals(listOf("Slow action", "Fast action"), events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.actionTitle })
            assertEquals(listOf("slow-id", "fast-id"), modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
            assertEquals(events.filterIsInstance<AgentEvent.AssistantText>().last().messageId,
                events.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
            assertEquals(5_800L, events.filterIsInstance<AgentEvent.TurnFinished>().single().durationMs)
            assertTrue(events.last() is AgentEvent.Completed)
            verify(exactly = 0) { injector.inject(any(), any()) }
        }
    }

    @Test
    fun samePathStartsTimerOnlyAfterLockAndDoesNotWaitForPreviousPersistence() = runBlocking {
        withTimeout(10_000) {
            val first = GatedTool("first")
            val second = GatedTool("second")
            register(first, second)
            val args = mapOf("path" to JsonPrimitive("/shared.kt"))
            val calls = listOf(ToolCall("first-id", first.name, args), ToolCall("second-id", second.name, args))
            responses(AIResponse("", calls), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            val finishes = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
            val job = launch {
                collect(listOf(first, second), events) { event -> finishes.trySend(event); false }
            }
            first.entered.await()
            assertFalse(second.entered.isCompleted)
            elapsed.set(3_000L)
            first.release.complete(Unit)
            val firstFinished = finishes.receive()
            second.entered.await()
            assertEquals(2_000L, firstFinished.durationMs)
            assertFalse(firstFinished.persisted!!.isCompleted)

            elapsed.set(3_040L)
            second.release.complete(Unit)
            val secondFinished = finishes.receive()
            assertEquals(40L, secondFinished.durationMs)
            assertEquals(listOf(1_000L, 3_000L), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.startedElapsedMs })
            elapsed.set(9_000L)
            firstFinished.persisted.complete(Unit)
            secondFinished.persisted!!.complete(Unit)
            job.join()

            assertEquals(listOf(2_000L, 40L), events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.durationMs })
            assertEquals(listOf("first-id", "second-id"), modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
        }
    }

    @Test
    fun approvalWaitDoesNotStartExecutionClockOrRewriteCommandArguments() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            val command = "printf '%s\\n' '" + "complete command ".repeat(45) + "'"
            val args = linkedMapOf("tool_title" to JsonPrimitive("Inspect output"), "command" to JsonPrimitive(command))
            val call = ToolCall("command-id", tool.name, args)
            val original = JsonObject(args).toString()
            val approvalRequested = CompletableDeferred<PendingToolPermission>()
            val decision = CompletableDeferred<PermissionChoice>()
            coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK)
            coEvery { permissions.awaitApproval(any()) } coAnswers {
                approvalRequested.complete(firstArg())
                decision.await()
            }
            responses(AIResponse("Proposal body", listOf(call)), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(listOf(tool), events) }
            val request = approvalRequested.await()
            elapsed.set(11_000L)
            assertFalse(tool.entered.isCompleted)
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallStarted })
            assertEquals(args - "tool_title", tool.permissionArguments.single())
            assertEquals(JsonObject(args - "tool_title"), Json.parseToJsonElement(request.argsPreview))
            assertEquals("session", request.sessionId)
            assertTrue(request.id != call.id)

            decision.complete(PermissionChoice.ONCE)
            assertEquals(args - "tool_title", tool.entered.await())
            elapsed.set(11_060L)
            tool.release.complete(Unit)
            job.join()

            val started = events.filterIsInstance<AgentEvent.ToolExecutionStarted>().single()
            val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
            assertEquals(11_000L, started.startedElapsedMs)
            assertEquals(started.startedAtMs, finished.startedAtMs)
            assertEquals(60L, finished.durationMs)
            assertEquals("Inspect output", finished.actionTitle)
            assertEquals(JsonObject(args - "tool_title"), Json.parseToJsonElement(finished.argsPreview!!))
            assertEquals("Inspect output", events.filterIsInstance<AgentEvent.ToolCallStarted>().single().actionTitle)
            assertEquals(command, (tool.entered.await().getValue("command") as JsonPrimitive).content)
            assertEquals(original, JsonObject(call.arguments).toString())
            assertEquals("Proposal body", events.filterIsInstance<AgentEvent.AssistantText>().first().content)
            assertEquals(listOf(call), events.filterIsInstance<AgentEvent.AssistantText>().first().toolCalls)
            assertEquals(listOf(call), modelRequests.last().filterIsInstance<AgentMessage.AssistantMessage>().single().toolCalls)
            coVerify(exactly = 1) { policy.evaluate(tool.name, args - "tool_title", context.sessionId, null) }
            coVerify(exactly = 1) {
                policy.evaluate(tool.name, args - "tool_title", context.sessionId, match { it === capturedExecutions.single() })
            }
        }
    }

    @Test
    fun hostWriteSessionRegression_writeAskRetainsEvaluatedSessionGrantInPendingRequest() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            tool.release.complete(Unit)
            val args = mapOf("command" to JsonPrimitive("printf updated > /system/etc/hosts"))
            val grant = "host-write-session-v1:workflow-test-scope"
            val evaluation = ToolPermissionPolicyEngine.EvalResult(
                verdict = ToolPermissionPolicyEngine.Verdict.ASK,
                sessionGrant = grant,
                permissionSource = ToolPermissionPolicyEngine.PermissionSource(
                    ToolPermissionPolicyEngine.PermissionNamespace.HOST
                ),
                hostSystemTargets = setOf("WRITE:/system/etc/hosts")
            )
            val approvalRequested = CompletableDeferred<PendingToolPermission>()
            val decision = CompletableDeferred<PermissionChoice>()
            coEvery { policy.evaluate(tool.name, args, context.sessionId, any()) } returns evaluation
            coEvery { permissions.awaitApproval(any()) } coAnswers {
                approvalRequested.complete(firstArg())
                decision.await()
            }
            responses(
                AIResponse("Host write proposal", listOf(ToolCall("host-write-id", tool.name, args))),
                AIResponse("done")
            )
            val events = mutableListOf<AgentEvent>()
            val job = async { collect(listOf(tool), events) }
            try {
                val request = approvalRequested.await()
                assertEquals("session", request.sessionId)
                assertEquals(tool.name, request.toolName)
                assertEquals(JsonObject(args), Json.parseToJsonElement(request.argsPreview))
                assertFalse(tool.entered.isCompleted)
                assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
                // A real ASK carrying WRITE evidence must not lose the session choice in the workflow.
                assertEquals(
                    "Non-empty host write targets must not hide the evaluated session approval choice",
                    grant,
                    request.sessionGrant
                )

                // Isolate request presentation from ALWAYS_SESSION validation and grant persistence.
                decision.complete(PermissionChoice.ONCE)
                job.await()
                assertEquals(args, tool.entered.await())
                assertFalse(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                assertTrue(events.none { it is AgentEvent.Failed })
                assertTrue(events.last() is AgentEvent.Completed)
                assertEquals(2, modelRequests.size)
                coVerify(exactly = 1) { permissions.awaitApproval(any()) }
                coVerify(exactly = 1) { policy.evaluate(tool.name, args, context.sessionId, null) }
                coVerify(exactly = 1) {
                    policy.evaluate(tool.name, args, context.sessionId, match { it === capturedExecutions.single() })
                }
            } finally {
                decision.complete(PermissionChoice.REJECT)
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun notificationJoinsFirstCompletedResultOnceAndAcksOnlyAfterItsPersistence() = runBlocking {
        withTimeout(10_000) {
            val slow = GatedTool("slow")
            val fast = GatedTool("fast")
            register(slow, fast)
            val calls = listOf(
                ToolCall("slow-id", slow.name, mapOf("command" to JsonPrimitive("slow"))),
                ToolCall("fast-id", fast.name, linkedMapOf(
                    "tool_title" to JsonPrimitive("Fast action"),
                    "command" to JsonPrimitive("full argument ".repeat(60))
                ))
            )
            val seq = notifications.enqueue("session", PendingNotification(
                AgentNotificationKind.BACKGROUND_TASK, "term", "Finished task", NotificationOutcome.COMPLETED
            ))
            val injected = """{"status":"success","data":"fast","notifications":["ready"]}"""
            every { injector.inject(any(), any()) } returns injected
            responses(AIResponse("", calls), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            val finishes = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
            val job = launch {
                collect(listOf(slow, fast), events) { event -> finishes.trySend(event); false }
            }
            slow.entered.await()
            fast.entered.await()
            elapsed.set(1_025L)
            fast.release.complete(Unit)
            val fastFinished = finishes.receive()
            assertEquals("fast-id", fastFinished.id)
            assertEquals(injected, fastFinished.result)
            assertEquals("Fast action", fastFinished.actionTitle)
            assertEquals(JsonObject(calls.last().arguments - "tool_title"), Json.parseToJsonElement(fastFinished.argsPreview!!))
            assertNotNull(fastFinished.startedAtMs)
            assertEquals(25L, fastFinished.durationMs)
            assertEquals(listOf(seq), notifications.peek("session").map { it.seq })
            verify(exactly = 1) {
                injector.inject(ToolResult.Success(JsonPrimitive("fast")).toTransportString(), match { it.map { item -> item.seq } == listOf(seq) })
            }

            elapsed.set(1_800L)
            slow.release.complete(Unit)
            val slowFinished = finishes.receive()
            assertEquals("slow-id", slowFinished.id)
            assertEquals(800L, slowFinished.durationMs)
            assertEquals(ToolResult.Success(JsonPrimitive("slow")).toTransportString(), slowFinished.result)
            assertEquals(listOf(seq), notifications.peek("session").map { it.seq })
            assertFalse(fastFinished.persisted!!.isCompleted)
            assertFalse(job.isCompleted)
            assertEquals(1, modelRequests.size)
            verify(exactly = 1) { injector.inject(any(), any()) }
            slowFinished.persisted!!.complete(Unit)
            assertEquals(listOf(seq), notifications.peek("session").map { it.seq })
            assertEquals(1, modelRequests.size)
            elapsed.set(9_000L)
            assertEquals(listOf(seq), fastFinished.notificationSeqs)
            assertTrue(slowFinished.notificationSeqs.isEmpty())
            notifications.ack("session", fastFinished.notificationSeqs)
            fastFinished.persisted.complete(Unit)
            job.join()

            assertTrue(notifications.peek("session").isEmpty())
            assertEquals(listOf("fast-id", "slow-id"), events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })
            val replayed = modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>()
            assertEquals(listOf("slow-id", "fast-id"), replayed.map { it.id })
            assertEquals(slowFinished.result, replayed.first().result)
            assertEquals(injected, replayed.last().result)
        }
    }

    @Test
    fun cancellationWhileRunningDoesNotPublishSuccessOrReuseTimingInNextTurn() = runBlocking {
        withTimeout(10_000) {
            val first = GatedTool("tool")
            register(first)
            val calls = listOf(ToolCall("same-id", first.name, emptyMap()))
            responses(AIResponse("", calls), AIResponse("", calls), AIResponse("done"))
            val cancelledEvents = mutableListOf<AgentEvent>()
            val job = launch { collect(listOf(first), cancelledEvents) }
            first.entered.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertTrue(cancelledEvents.none { it is AgentEvent.ToolCallFinished || it is AgentEvent.TurnFinished || it is AgentEvent.Completed })
            elapsed.set(11_000L)
            val second = GatedTool("tool")
            register(second)
            val events = mutableListOf<AgentEvent>()
            val next = launch { collect(listOf(second), events) }
            second.entered.await()
            elapsed.set(11_060L)
            second.release.complete(Unit)
            next.join()

            val started = events.filterIsInstance<AgentEvent.ToolExecutionStarted>().single()
            val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
            assertEquals(11_000L, started.startedElapsedMs)
            assertEquals(60L, finished.durationMs)
            assertEquals(started.startedAtMs, finished.startedAtMs)
            assertFalse(finished.isError)
            assertTrue(events.last() is AgentEvent.Completed)
        }
    }

    @Test
    fun rejectingLaterApprovalLeavesApprovedPlaceholdersUntimedAndExecutesNothing() = runBlocking {
        withTimeout(10_000) {
            val approved = GatedTool("approved")
            val rejected = GatedTool("rejected")
            register(approved, rejected)
            coEvery { policy.evaluate(rejected.name, any(), any(), any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK)
            coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.REJECT
            val calls = listOf(ToolCall("approved-id", approved.name, emptyMap()), ToolCall("rejected-id", rejected.name, emptyMap()))
            responses(AIResponse("", calls))
            val events = mutableListOf<AgentEvent>()

            collect(listOf(approved, rejected), events)

            assertEquals(listOf("approved-id"), events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.id })
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted })
            assertFalse(approved.entered.isCompleted)
            assertFalse(rejected.entered.isCompleted)
            val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>()
            assertEquals(listOf("rejected-id", "approved-id"), finished.map { it.id })
            finished.forEach {
                assertTrue(it.isError)
                assertNull(it.startedAtMs)
                assertNull(it.durationMs)
            }
            assertEquals(1, modelRequests.size)
            coVerify(exactly = 1) { permissions.awaitApproval(any()) }
        }
    }

    @Test
    fun firstLlmFailureDoesNotAssignTurnDurationToHistoricalAssistant() = runBlocking {
        withTimeout(10_000) {
            val historicalAssistant = AgentMessage.AssistantMessage(id = "history-assistant", content = "Previous answer")
            val requestContext = context.copy(history = listOf(
                AgentMessage.UserMessage(content = "Previous request"), historicalAssistant
            ))
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow<AIStreamChunk> {
                elapsed.set(1_800L)
                throw IllegalStateException("Provider unavailable")
            }
            val events = mutableListOf<AgentEvent>()

            workflow.executeEvents("test", requestContext, emptyList()).collect { event ->
                events += event
                if (event is AgentEvent.AssistantText) event.persisted?.complete(Unit)
            }

            assertTrue(events.none { it is AgentEvent.AssistantText })
            val finished = events.filterIsInstance<AgentEvent.TurnFinished>().single()
            assertNull(finished.messageId)
            assertEquals(800L, finished.durationMs)
            assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
            verify(exactly = 1) { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) }
        }
    }

    @Test
    fun partialLlmFailureAssignsTurnDurationToCurrentPartialRatherThanHistoricalAssistant() = runBlocking {
        withTimeout(10_000) {
            val historicalAssistant = AgentMessage.AssistantMessage(id = "history-assistant", content = "Previous answer")
            val requestContext = context.copy(history = listOf(
                AgentMessage.UserMessage(content = "Previous request"), historicalAssistant
            ))
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
                elapsed.set(1_125L)
                emit(AIStreamChunk.TextDelta("Current partial answer"))
                elapsed.set(1_800L)
                throw IllegalStateException("Stream interrupted")
            }
            val events = mutableListOf<AgentEvent>()

            workflow.executeEvents("test", requestContext, emptyList()).collect { event ->
                events += event
                if (event is AgentEvent.AssistantText) event.persisted?.complete(Unit)
            }

            val partial = events.filterIsInstance<AgentEvent.AssistantText>().single()
            assertEquals("Current partial answer", partial.content)
            assertTrue(partial.messageId != historicalAssistant.id)
            val finished = events.filterIsInstance<AgentEvent.TurnFinished>().single()
            assertEquals(partial.messageId, finished.messageId)
            assertEquals(800L, finished.durationMs)
            assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
            verify(exactly = 1) { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) }
        }
    }

    @Test
    fun recoveryDraftThrottleKeepsCurrentRunAliveAndCommitsAllVisibleDeltas() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("draft-throttle")
            val runId = requireNotNull(snapshot.recoveryRunId)
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
                modelRequests += secondArg<List<AgentMessage>>().toList()
                flow {
                    recoveryClock.set(1_000L)
                    elapsed.set(1_000L)
                    emit(AIStreamChunk.TextDelta("First"))
                    assertEquals(listOf(true), draftSaves)
                    assertEquals("First", recoveryLedger.drafts.getValue(runId).text)
                    recoveryClock.set(1_100L)
                    elapsed.set(1_100L)
                    emit(AIStreamChunk.TextDelta(" second"))
                    assertEquals(listOf(true), draftSaves)
                    assertEquals("First", recoveryLedger.drafts.getValue(runId).text)
                    assertTrue(recovery.isCurrent(runId))
                    recoveryClock.set(1_500L)
                    elapsed.set(1_500L)
                    emit(AIStreamChunk.TextDelta(" third"))
                    assertEquals(listOf(true, true), draftSaves)
                    assertEquals("First second third", recoveryLedger.drafts.getValue(runId).text)
                    emit(AIStreamChunk.Final(AIResponse("First second third")))
                }
            }

            val events = collectRecovery(snapshot)

            assertEquals(listOf(true, true), draftSaves)
            assertEquals("First second third", events.filterIsInstance<AgentEvent.AssistantDelta>().last().accumulated)
            val assistant = events.filterIsInstance<AgentEvent.AssistantText>().single()
            assertEquals("First second third", recoveryLedger.messages.getValue(assistant.messageId).content)
            assertEquals(RecoveryStep(1, 0), assistant.recoveryStep)
            assertFalse(recoveryLedger.drafts.containsKey(runId))
            assertEquals(1, modelRequests.size)
            assertTrue(events.none { it is AgentEvent.Failed })
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
            assertEquals("COMPLETED", recoveryLedger.runs.getValue(runId).status)
        }
    }

    @Test
    fun preexistingDraftThrottleDoesNotCancelOrAdvanceTheFrontendWindow() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("preexisting-draft")
            val runId = requireNotNull(snapshot.recoveryRunId)
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
                modelRequests += secondArg<List<AgentMessage>>().toList()
                flow {
                    recoveryClock.set(1_000L)
                    elapsed.set(1_100L)
                    val current = recoveryLedger.runs.getValue(runId)
                    assertTrue(recovery.saveDraft(runId, RecoveryStep(current.step, current.revision), "Previous", force = true))
                    recoveryClock.set(1_100L)
                    emit(AIStreamChunk.TextDelta("First"))
                    assertEquals(listOf(true, false), draftSaves)
                    assertEquals("Previous", recoveryLedger.drafts.getValue(runId).text)
                    assertTrue(recovery.isCurrent(runId))
                    recoveryClock.set(1_500L)
                    elapsed.set(1_500L)
                    emit(AIStreamChunk.TextDelta(" second"))
                    assertEquals(listOf(true, false, true), draftSaves)
                    assertEquals("First second", recoveryLedger.drafts.getValue(runId).text)
                    emit(AIStreamChunk.Final(AIResponse("First second")))
                }
            }
            val events = collectRecovery(snapshot)
            assertTrue(events.none { it is AgentEvent.Failed })
            assertEquals("First second", events.filterIsInstance<AgentEvent.AssistantText>().single().content)
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
        }
    }

    @Test
    fun skippedDraftWriteStillChecksTheCurrentRunFenceOnEveryDelta() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("draft-owner")
            val runId = requireNotNull(snapshot.recoveryRunId)
            every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } returns flow {
                recoveryClock.set(1_000L)
                elapsed.set(1_000L)
                emit(AIStreamChunk.TextDelta("First"))
                assertTrue(recovery.isCurrent(runId))
                assertTrue(recovery.invalidateSession("session", "backup_restore"))
                recoveryClock.set(1_100L)
                elapsed.set(1_100L)
                emit(AIStreamChunk.TextDelta(" late"))
                error("An invalidated run must not continue consuming the stream")
            }
            val events = mutableListOf<AgentEvent>()
            val failure = runCatching {
                workflow.executeEvents("test", snapshot, emptyList()).collect { event -> events += event }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(events.none { it is AgentEvent.AssistantText || it is AgentEvent.Completed })
            assertFalse(recovery.isCurrent(runId))
            assertTrue(recoveryLedger.messages.values.none { it.role == "ASSISTANT" })
        }
    }

    @Test
    fun recoveryEmptyFinalCommitsItsStepBeforeRunCanFinish() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("empty-final")
            val runId = requireNotNull(snapshot.recoveryRunId)
            responses(AIResponse(""))

            val events = collectRecovery(snapshot)

            val assistant = events.filterIsInstance<AgentEvent.AssistantText>().single()
            assertEquals("", assistant.content)
            assertEquals(RecoveryStep(1, 0), assistant.recoveryStep)
            assertTrue(assistant.persisted!!.isCompleted)
            val stored = recoveryLedger.messages.getValue(assistant.messageId)
            assertEquals(MessageRole.ASSISTANT.name, stored.role)
            assertEquals("", stored.content)
            assertEquals(1, recoveryLedger.runs.getValue(runId).step)
            assertEquals(1, recoveryLedger.runs.getValue(runId).committedStep)
            assertEquals(assistant.messageId, events.filterIsInstance<AgentEvent.TurnFinished>().single().messageId)
            assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.ToolExecutionStarted })
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
            assertEquals("COMPLETED", recoveryLedger.requests.values.single().status)
        }
    }

    @Test
    fun recoveryContextOverflowCompactsAndRestartsSameUncommittedStepWithNewRevision() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("overflow")
            val runId = requireNotNull(snapshot.recoveryRunId)
            val beforeCompaction = mutableListOf<RecoveryStep>()
            coEvery {
                compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                if (arg<Boolean>(3)) {
                    val current = recoveryLedger.runs.getValue(runId)
                    assertEquals(0, current.committedStep)
                    beforeCompaction += RecoveryStep(current.step, current.revision)
                    CompactionResult(firstArg<List<AgentMessage>>(), true)
                } else CompactionResult(firstArg<List<AgentMessage>>(), false)
            }
            responses(AIResponse("", stopReason = "model_context_window_exceeded"), AIResponse("Recovered response"))

            val events = collectRecovery(snapshot)

            assertEquals(listOf(RecoveryStep(1, 0)), beforeCompaction)
            assertEquals(2, modelRequests.size)
            assertEquals(modelRequests.first(), modelRequests.last())
            assertEquals(1, events.filterIsInstance<AgentEvent.AssistantText>().size)
            val assistant = events.filterIsInstance<AgentEvent.AssistantText>().single()
            assertEquals(RecoveryStep(1, 1), assistant.recoveryStep)
            assertEquals("Recovered response", recoveryLedger.messages.getValue(assistant.messageId).content)
            val savedRun = recoveryLedger.runs.getValue(runId)
            assertEquals(1, savedRun.step)
            assertEquals(1L, savedRun.revision)
            assertEquals(1, savedRun.committedStep)
            assertEquals(1, recoveryLedger.messages.values.count { it.role == MessageRole.ASSISTANT.name })
            assertTrue(events.none { it is AgentEvent.Failed })
            assertTrue(events.last() is AgentEvent.Completed)
            coVerify(exactly = 1) {
                compactor.compactIfNeeded(any(), any(), any(), true, any(), any(), any(), any(), any())
            }
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recoverySystemDenialsWaitForPersistenceBeforeTheNextModelStep() = runTest(timeout = 10.seconds) {
        val snapshot = startRecovery("system-denials")
        val runId = requireNotNull(snapshot.recoveryRunId)
        val tools = listOf(GatedTool("Bash"), GatedTool("terminal"))
        register(*tools.toTypedArray())
        tools.forEach { tool ->
            coEvery { policy.evaluate(tool.name, any(), any(), any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, "Blocked by safety policy")
        }
        val calls = tools.map { ToolCall("${it.name}-id", it.name, emptyMap()) }
        responses(AIResponse("Inspect environment", calls), AIResponse("The commands were not executed."))
        val pending = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val persist = Channel<Unit>(Channel.UNLIMITED)
        val job = async {
            collectRecovery(snapshot, tools) { event ->
                pending.send(event)
                persist.receive()
            }
        }
        try {
            calls.forEach { call ->
                val event = select {
                    pending.onReceive { it }
                    job.onAwait { error("Workflow ended before the denial result was persisted") }
                }
                runCurrent()
                assertTrue(job.isActive)
                assertEquals(call.id, event.id)
                assertEquals(1, modelRequests.size)
                assertEquals(1, recoveryLedger.runs.getValue(runId).step)
                assertEquals(listOf(runId), recoveryStepAttempts)
                assertEquals("WAITING_APPROVAL", recoveryLedger.tools.getValue(runId to call.id).status)
                assertFalse(recoveryLedger.messages.containsKey("tool_${call.id}"))
                assertTrue(tools.none { it.entered.isCompleted })
                assertNull(event.startedAtMs)
                assertNull(event.durationMs)
                assertEquals("SYSTEM_DENIED", Json.parseToJsonElement(event.result).jsonObject.getValue("code").jsonPrimitive.content)
                persist.send(Unit)
            }
            val events = job.await()
            assertEquals(2, modelRequests.size)
            assertEquals(listOf(runId, runId), recoveryStepAttempts)
            val results = modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>()
            assertEquals(calls.map { it.id }, results.map { it.id })
            assertTrue(results.all { Json.parseToJsonElement(it.result).jsonObject.getValue("code").jsonPrimitive.content == "SYSTEM_DENIED" })
            assertTrue(recoveryLedger.tools.values.all { it.status == "FINISHED" })
            assertEquals("The commands were not executed.", events.filterIsInstance<AgentEvent.AssistantText>().last().content)
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.Failed })
            assertTrue(events.last() is AgentEvent.Completed)
            coVerify(exactly = 0) { permissions.awaitApproval(any()) }
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
            assertEquals("COMPLETED", recoveryLedger.requests.values.single().status)
        } finally {
            job.cancelAndJoin()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingWhileDeniedResultAwaitsPersistenceDoesNotStartAnotherModelCall() = runTest(timeout = 10.seconds) {
        val tool = GatedTool("Bash")
        register(tool)
        coEvery { policy.evaluate(tool.name, any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, "Blocked by safety policy")
        responses(AIResponse("", listOf(ToolCall("denied-id", tool.name, emptyMap()))), AIResponse("done"))
        val events = mutableListOf<AgentEvent>()
        val pending = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val job = launch {
            collect(listOf(tool), events) { event -> pending.trySend(event); false }
        }
        try {
            val event = pending.receive()
            assertNotNull(event.persisted)
            assertFalse(event.persisted!!.isCompleted)
            runCurrent()
            assertTrue(job.isActive)
            assertEquals(1, modelRequests.size)
            job.cancelAndJoin()
            event.persisted.complete(Unit)
            runCurrent()
            assertEquals(1, modelRequests.size)
            assertFalse(tool.entered.isCompleted)
            assertTrue(events.none { it is AgentEvent.Completed || it is AgentEvent.ToolExecutionStarted })
        } finally {
            job.cancelAndJoin()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun userRejectionWaitsForEachCancelledResultBeforeCompletingTheTurn() = runTest(timeout = 10.seconds) {
        val tools = listOf(GatedTool("approved"), GatedTool("rejected"), GatedTool("unasked"))
        register(*tools.toTypedArray())
        coEvery { policy.evaluate("rejected", any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK)
        coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.REJECT
        responses(AIResponse("Proposal", tools.map { ToolCall("${it.name}-id", it.name, emptyMap()) }))
        val events = mutableListOf<AgentEvent>()
        val pending = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
        val job = launch {
            collect(tools, events) { event -> pending.trySend(event); false }
        }
        try {
            listOf("rejected-id", "approved-id", "unasked-id").forEachIndexed { index, id ->
                val event = pending.receive()
                assertNotNull(event.persisted)
                runCurrent()
                assertEquals(id, event.id)
                assertEquals(index + 1, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
                assertTrue(job.isActive)
                assertTrue(events.none { it is AgentEvent.Completed || it is AgentEvent.ToolExecutionStarted })
                assertTrue(tools.none { it.entered.isCompleted })
                assertEquals(1, modelRequests.size)
                assertEquals("USER_REJECTED", Json.parseToJsonElement(event.result).jsonObject.getValue("code").jsonPrimitive.content)
                event.persisted!!.complete(Unit)
            }
            job.join()
            assertTrue(events.last() is AgentEvent.Completed)
            assertEquals(1, modelRequests.size)
            coVerify(exactly = 0) { policy.evaluate("unasked", any(), any(), any()) }
        } finally {
            job.cancelAndJoin()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun planBatchWaitsForDeferredResultPersistenceBeforeEnteringThePlanTool() = runTest(timeout = 10.seconds) {
        val snapshot = startRecovery("plan-deferred")
        val runId = requireNotNull(snapshot.recoveryRunId)
        val plan = GatedTool(com.aicode.feature.agent.domain.tool.plan.SubmitPlanTool.NAME)
        val command = GatedTool("Bash")
        register(plan, command)
        val calls = listOf(ToolCall("command-id", command.name, emptyMap()), ToolCall("plan-id", plan.name, emptyMap()))
        responses(AIResponse("Plan", calls), AIResponse("Approved plan received."))
        val pending = CompletableDeferred<AgentEvent.ToolCallFinished>()
        val persist = CompletableDeferred<Unit>()
        val job = async {
            collectRecovery(snapshot, listOf(plan, command)) { event ->
                if (event.id == "command-id") {
                    pending.complete(event)
                    persist.await()
                }
            }
        }
        try {
            val event = pending.await()
            assertNotNull(event.persisted)
            runCurrent()
            assertFalse(plan.entered.isCompleted)
            assertFalse(command.entered.isCompleted)
            assertEquals(listOf(runId), recoveryStepAttempts)
            assertEquals("DECLARED", recoveryLedger.tools.getValue(runId to "command-id").status)
            assertEquals("DECLARED", recoveryLedger.tools.getValue(runId to "plan-id").status)
            coVerify(exactly = 0) { policy.evaluate(plan.name, any(), any(), any()) }
            assertEquals("PLAN_BATCH_DEFERRED", Json.parseToJsonElement(event.result).jsonObject.getValue("code").jsonPrimitive.content)
            persist.complete(Unit)
            plan.entered.await()
            assertEquals("FINISHED", recoveryLedger.tools.getValue(runId to "command-id").status)
            assertEquals(1, modelRequests.size)
            plan.release.complete(Unit)
            val events = job.await()
            assertFalse(command.entered.isCompleted)
            assertEquals(listOf("plan-id"), events.filterIsInstance<AgentEvent.ToolExecutionStarted>().map { it.id })
            assertEquals(calls.map { it.id }, modelRequests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
            assertEquals(listOf(runId, runId), recoveryStepAttempts)
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun deniedResultPersistenceFailureDoesNotContinueOrReportCompletion() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("denied-write-failure")
            val runId = requireNotNull(snapshot.recoveryRunId)
            val tool = GatedTool("Bash")
            register(tool)
            coEvery { policy.evaluate(tool.name, any(), any(), any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, "Blocked by safety policy")
            responses(AIResponse("Inspect", listOf(ToolCall("denied-id", tool.name, emptyMap()))), AIResponse("done"))
            val failedWrite = IllegalStateException("Storage unavailable")
            val events = mutableListOf<AgentEvent>()
            val failure = runCatching {
                collectRecovery(snapshot, listOf(tool), events) { throw failedWrite }
            }.exceptionOrNull()
            assertEquals(IllegalStateException::class.java, failure?.javaClass)
            assertEquals(failedWrite.message, failure?.message)
            assertTrue(generateSequence(failure) { it.cause }.any { it === failedWrite })
            assertTrue(events.none { it is AgentEvent.Completed || it is AgentEvent.TurnFinished || it is AgentEvent.ToolExecutionStarted })
            assertEquals(1, modelRequests.size)
            assertEquals(listOf(runId), recoveryStepAttempts)
            assertFalse(tool.entered.isCompleted)
            assertEquals("WAITING_APPROVAL", recoveryLedger.tools.getValue(runId to "denied-id").status)
            assertFalse(recoveryLedger.messages.containsKey("tool_denied-id"))
            assertFalse(recovery.finish(runId, RecoveryStatus.COMPLETED))
        }
    }

    @Test
    fun recoveryUserBatchRejectionFinishesApprovedRejectedAndUnaskedLedgerCallsWithoutExecution() = runBlocking {
        withTimeout(10_000) {
            val snapshot = startRecovery("batch-rejection")
            val runId = requireNotNull(snapshot.recoveryRunId)
            val approved = GatedTool("approved")
            val rejected = GatedTool("rejected")
            val unasked = GatedTool("unasked")
            register(approved, rejected, unasked)
            coEvery { policy.evaluate(rejected.name, any(), any(), any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK)
            coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.REJECT
            val calls = listOf(approved, rejected, unasked).map { ToolCall("${it.name}-id", it.name, emptyMap()) }
            responses(AIResponse("Proposed batch", calls))

            val events = collectRecovery(snapshot, listOf(approved, rejected, unasked))

            val assistant = events.filterIsInstance<AgentEvent.AssistantText>().single()
            assertEquals(calls, assistant.toolCalls)
            assertEquals(listOf("approved-id"), events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.id })
            assertEquals(listOf("rejected-id", "approved-id", "unasked-id"),
                events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })
            assertEquals(calls.map { it.id }.toSet(), recoveryLedger.tools.values.map { it.toolCallId }.toSet())
            calls.forEach { call ->
                val tool = recoveryLedger.tools.getValue(runId to call.id)
                assertEquals("FINISHED", tool.status)
                assertEquals(assistant.messageId, tool.assistantMessageId)
                assertEquals("tool_${call.id}", tool.resultMessageId)
                val result = recoveryLedger.messages.getValue("tool_${call.id}")
                assertTrue(result.isError)
                assertEquals(call.id, result.toolCallId)
                val transport = Json.parseToJsonElement(result.content).jsonObject
                assertEquals("error", transport.getValue("status").jsonPrimitive.content)
                assertEquals("USER_REJECTED", transport.getValue("code").jsonPrimitive.content)
                assertNull(result.toolStartedAtMs)
                assertNull(result.toolDurationMs)
            }
            assertFalse(approved.entered.isCompleted)
            assertFalse(rejected.entered.isCompleted)
            assertFalse(unasked.entered.isCompleted)
            assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.Failed })
            assertEquals(1, modelRequests.size)
            coVerify(exactly = 1) { permissions.awaitApproval(any()) }
            coVerify(exactly = 0) { policy.evaluate(unasked.name, any(), any(), any()) }
            assertTrue(events.last() is AgentEvent.Completed)
            assertTrue(recovery.finish(runId, RecoveryStatus.COMPLETED))
        }
    }

    @Test fun hookArgumentsAreApprovedAndExecutedExactlyWhileAiWireKeepsOriginalArguments() = runBlocking<Unit> {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            tool.release.complete(Unit)
            val original = mapOf("command" to JsonPrimitive("echo original"))
            val finalArgs = JsonObject(mapOf("command" to JsonPrimitive("pwd")))
            coEvery { hooks.beforeTool(any(), any(), any()) } returns com.aicode.feature.plugin.runtime.PluginBeforeTool(finalArgs)
            val evaluated = mutableListOf<Map<String, JsonElement>>()
            coEvery { policy.evaluate(tool.name, any(), any(), any()) } answers {
                evaluated += secondArg<Map<String, JsonElement>>().toMap()
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK)
            }
            coEvery { permissions.awaitApproval(any()) } returns com.aicode.feature.agent.domain.permission.PermissionChoice.ONCE
            responses(AIResponse("", listOf(ToolCall("mutated", tool.name, original))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            assertEquals(finalArgs, tool.entered.await())
            assertTrue(evaluated.size >= 2)
            assertTrue(evaluated.all { it == finalArgs })
            assertEquals(listOf(finalArgs), tool.permissionArguments)
            assertEquals(original, modelRequests.last().filterIsInstance<AgentMessage.AssistantMessage>().single().toolCalls.single().arguments)
            assertEquals(finalArgs.toString(), events.filterIsInstance<AgentEvent.ToolCallFinished>().single().argsPreview)
            coVerify(exactly = 1) { permissions.awaitApproval(any()) }
        }
    }

    @Test fun hookMutationStillRunsFinalDenyPolicyBeforeNativeApprovalAndCannotExecute() = runBlocking<Unit> {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            val forbidden = JsonObject(mapOf("command" to JsonPrimitive("blocked-final-command")))
            coEvery { hooks.beforeTool(any(), any(), any()) } returns com.aicode.feature.plugin.runtime.PluginBeforeTool(forbidden)
            coEvery { policy.evaluate(tool.name, forbidden, any(), any()) } returns
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, "Denied final arguments")
            responses(AIResponse("", listOf(ToolCall("blocked", tool.name, mapOf("command" to JsonPrimitive("safe"))))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
            assertTrue(finished.result.contains("SYSTEM_DENIED"))
            assertEquals(forbidden.toString(), finished.argsPreview)
            assertFalse(tool.entered.isCompleted)
            coVerify(exactly = 0) { permissions.awaitApproval(any()) }
        }
    }

    @Test fun policyRevocationAfterApprovalIsCheckedAgainImmediatelyBeforeExecution() = runBlocking<Unit> {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            var checks = 0
            coEvery { policy.evaluate(tool.name, any(), any(), any()) } answers {
                checks++
                ToolPermissionPolicyEngine.EvalResult(
                    if (checks == 1) ToolPermissionPolicyEngine.Verdict.ALLOW else ToolPermissionPolicyEngine.Verdict.DENY,
                    "New policy denied", channelUnavailable = false
                )
            }
            responses(AIResponse("", listOf(ToolCall("revoked", tool.name, emptyMap()))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            assertEquals(2, checks)
            assertFalse(tool.entered.isCompleted)
            assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result.contains("SYSTEM_DENIED"))
            assertTrue(events.none { it is AgentEvent.Failed })
            assertEquals(2, modelRequests.size)
        }
    }

    private fun permissionSource(profile: String) = ToolPermissionPolicyEngine.PermissionSource(
        ToolPermissionPolicyEngine.PermissionNamespace.PROOT_GUEST,
        mode = ExecutionMode.LOCAL_PROOT, modeSelectionRevision = 3,
        selectedProfileId = profile, profileSelectionRevision = 4, profileConfigurationRevision = 5,
        activeProfileId = profile
    )

    private suspend fun assertProofChangeAborts(
        initial: ToolPermissionPolicyEngine.EvalResult,
        actual: ToolPermissionPolicyEngine.EvalResult,
        choice: PermissionChoice = PermissionChoice.ONCE
    ) {
        val tool = GatedTool("proof-dependent")
        register(tool)
        val args = mapOf("command" to JsonPrimitive("cat /system/etc/hosts"))
        val checkedCaptures = mutableListOf<WorkspaceExecutionContext?>()
        coEvery { policy.evaluate(tool.name, args, context.sessionId, any()) } answers {
            val execution = arg<WorkspaceExecutionContext?>(3)
            checkedCaptures += execution
            if (execution == null) initial else {
                assertTrue(capturedExecutions.any { it === execution })
                assertTrue(WorkspaceExecutionContext.current() === execution)
                actual
            }
        }
        coEvery { permissions.awaitApproval(any()) } returns choice
        responses(AIResponse("Proposal", listOf(ToolCall("proof-id", tool.name, args))), AIResponse("must not be requested"))
        val events = mutableListOf<AgentEvent>()
        collect(listOf(tool), events)
        val immediateConfirmation = initial.verdict == ToolPermissionPolicyEngine.Verdict.ASK &&
            choice == PermissionChoice.ALWAYS_SESSION
        assertEquals(if (immediateConfirmation) 3 else 2, checkedCaptures.size)
        assertNull(checkedCaptures.first())
        if (immediateConfirmation) assertNull(checkedCaptures[1])
        assertTrue(checkedCaptures.last() === capturedExecutions.single())
        assertFalse(tool.entered.isCompleted)
        assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
        assertEquals("EXECUTION_SOURCE_CHANGED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
        assertEquals(1, events.count { it is AgentEvent.TurnFinished })
        assertEquals(1, events.count { it is AgentEvent.Completed })
        assertTrue(events.last() is AgentEvent.Completed)
        assertEquals(1, modelRequests.size)
        coVerify(exactly = if (immediateConfirmation) 1 else 0) {
            permissions.confirmHostWriteSessionGrant(any(), any())
        }
        if (immediateConfirmation) coVerify(exactly = 1) {
            permissions.confirmHostWriteSessionGrant(context.sessionId, initial.sessionGrant!!)
        }
        coVerify(exactly = if (initial.verdict == ToolPermissionPolicyEngine.Verdict.ASK) 1 else 0) {
            permissions.awaitApproval(any())
        }
    }

    @Test
    fun channelUnavailableDenialStopsBeforeOrdinaryDeniedResultOrAnotherModelCall() = runBlocking {
        withTimeout(10_000) {
            val source = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST)
            assertProofChangeAborts(
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, permissionSource = source),
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY,
                    permissionSource = source.copy(), channelUnavailable = true)
            )
        }
    }

    @Test
    fun actualExecutionSourceChangeCannotReuseAnAllowDecision() = runBlocking {
        withTimeout(10_000) {
            assertProofChangeAborts(
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, permissionSource = permissionSource("old")),
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, permissionSource = permissionSource("new"))
            )
        }
    }

    @Test
    fun actualExecutionNewAskCannotReuseAnAllowDecisionOrOpenAnotherPrompt() = runBlocking {
        withTimeout(10_000) {
            val source = permissionSource("same")
            assertProofChangeAborts(
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, permissionSource = source),
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, permissionSource = source.copy())
            )
        }
    }

    @Test
    fun changedHostTargetsCannotReuseThisCallsOnceApproval() = runBlocking {
        withTimeout(10_000) {
            val source = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST)
            assertProofChangeAborts(
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK,
                    permissionSource = source, hostSystemTargets = setOf("READ:/system/etc/hosts")),
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK,
                    permissionSource = source.copy(), hostSystemTargets = setOf("READ:/system/etc/passwd"))
            )
        }
    }

    @Test
    fun approvalProofCopiesTargetsBeforeMutableEvaluatorStateCanChange() = runBlocking {
        withTimeout(10_000) {
            val targets = linkedSetOf("READ:/system/etc/hosts")
            val source = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST)
            beforeFreeze = { targets.clear(); targets += "READ:/system/etc/passwd" }
            assertProofChangeAborts(
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, permissionSource = source, hostSystemTargets = targets),
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, permissionSource = source.copy(), hostSystemTargets = targets)
            )
        }
    }

    private suspend fun assertInvalidHostWriteSessionChoice(evaluatedGrant: String?, session: String?) {
        val tool = GatedTool("Bash")
        register(tool)
        tool.release.complete(Unit)
        val snapshot = context.copy(sessionId = session)
        val requests = mutableListOf<PendingToolPermission>()
        coEvery { policy.evaluate(tool.name, any(), session, any()) } returns ToolPermissionPolicyEngine.EvalResult(
            verdict = ToolPermissionPolicyEngine.Verdict.ASK,
            sessionGrant = evaluatedGrant,
            permissionSource = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST),
            hostSystemTargets = setOf("WRITE:/system/etc/hosts")
        )
        coEvery { permissions.awaitApproval(any()) } answers {
            requests += firstArg<PendingToolPermission>()
            PermissionChoice.ALWAYS_SESSION
        }
        responses(AIResponse("write proposal", listOf(ToolCall("invalid-session-choice", tool.name, emptyMap()))), AIResponse("done"))
        val events = mutableListOf<AgentEvent>()
        workflow.executeEvents("test", snapshot, listOf(tool)).collect { event ->
            events += event
            when (event) {
                is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                else -> Unit
            }
        }
        assertNull(requests.single().sessionGrant)
        assertFalse(tool.entered.isCompleted)
        assertTrue(events.none { it is AgentEvent.ToolExecutionStarted })
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(finished.isError)
        assertTrue(finished.result.contains("INVALID_APPROVAL_CHOICE"))
        assertTrue(events.last() is AgentEvent.Completed)
        coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
        coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
        verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_fakeAlwaysWithoutEvaluatedGrantIsStillInvalid() = runBlocking {
        withTimeout(10_000) { assertInvalidHostWriteSessionChoice(null, "session") }
    }

    @Test
    fun hostWriteSessionRegression_fakeAlwaysWithBlankScopeIsStillInvalid() = runBlocking {
        withTimeout(10_000) {
            for (grant in listOf("", "  ", "host-write-session-v1:", "host-write-session-v1:  ")) {
                assertInvalidHostWriteSessionChoice(grant, "session")
            }
        }
    }

    @Test
    fun hostWriteSessionRegression_fakeAlwaysWithBlankSessionIsStillInvalid() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            tool.release.complete(Unit)
            for (session in listOf("", "  ")) {
                val events = mutableListOf<AgentEvent>()
                val failure = runCatching {
                    workflow.executeEvents("test", context.copy(sessionId = session), listOf(tool)).collect { event ->
                        events += event
                        when (event) {
                            is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                            is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                            else -> Unit
                        }
                    }
                }.exceptionOrNull()
                // A non-null blank session cannot construct RuntimeOwner, before policy or UI.
                assertTrue("Blank sessions must fail closed at the runtime-owner boundary", failure is IllegalArgumentException)
                assertTrue(events.isEmpty())
                assertFalse(tool.entered.isCompleted)
            }
            assertTrue(capturedExecutions.isEmpty())
            assertTrue(modelRequests.isEmpty())
            coVerify(exactly = 0) { policy.evaluate(any(), any(), any(), any()) }
            coVerify(exactly = 0) { permissions.awaitApproval(any()) }
            coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
            coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
            verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
            verify(exactly = 0) { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) }

            // Null has no RuntimeOwner: keep the original missing-session fake-ALWAYS control.
            assertInvalidHostWriteSessionChoice("host-write-session-v1:valid-scope", null)
        }
    }

    @Test
    fun hostWriteSessionRegression_sourceChangedWhileApprovalPendingCannotSaveOrExecuteAlwaysChoice() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            val initial = chrootHostWriteEvaluation()
            var source = initial.permissionSource
            coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } answers {
                initial.copy(permissionSource = source)
            }
            val requested = CompletableDeferred<PendingToolPermission>()
            val decision = CompletableDeferred<PermissionChoice>()
            coEvery { permissions.awaitApproval(any()) } coAnswers {
                requested.complete(firstArg())
                decision.await()
            }
            responses(AIResponse("CHROOT write", listOf(ToolCall("pending-source-change", tool.name, emptyMap()))), AIResponse("must not be requested"))
            val events = mutableListOf<AgentEvent>()
            val job = async { collect(listOf(tool), events) }
            try {
                val request = requested.await()
                assertEquals(initial.sessionGrant, request.sessionGrant)
                source = source!!.copy(workspaceSelectionId = "changed-while-pending")
                decision.complete(PermissionChoice.ALWAYS_SESSION)
                job.await()
                assertFalse(tool.entered.isCompleted)
                assertTrue(events.none { it is AgentEvent.ToolExecutionStarted || it is AgentEvent.ToolCallFinished })
                assertEquals("EXECUTION_SOURCE_CHANGED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
                assertEquals(1, modelRequests.size)
                assertTrue(events.last() is AgentEvent.Completed)
                coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
            } finally {
                decision.complete(PermissionChoice.REJECT)
                job.cancelAndJoin()
            }
        }
    }

    private suspend fun assertImmediateHostWriteRecheckRejects(current: ToolPermissionPolicyEngine.EvalResult) {
        val tool = GatedTool("Bash")
        register(tool)
        val reviewed = chrootHostWriteEvaluation()
        val checks = AtomicInteger()
        coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } answers {
            assertNull(arg<WorkspaceExecutionContext?>(3))
            if (checks.incrementAndGet() == 1) reviewed else current
        }
        coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.ALWAYS_SESSION
        responses(AIResponse("CHROOT write", listOf(ToolCall("immediate-recheck", tool.name, emptyMap()))), AIResponse("denial handled"))
        val events = mutableListOf<AgentEvent>()
        collect(listOf(tool), events)
        assertEquals(2, checks.get())
        assertFalse(tool.entered.isCompleted)
        assertTrue(capturedExecutions.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolCallStarted || it is AgentEvent.ToolExecutionStarted })
        if (current.verdict == ToolPermissionPolicyEngine.Verdict.DENY && !current.channelUnavailable) {
            assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result.contains("SYSTEM_DENIED"))
        } else {
            assertTrue(events.none { it is AgentEvent.ToolCallFinished })
            assertEquals("EXECUTION_SOURCE_CHANGED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
            assertEquals(1, modelRequests.size)
        }
        assertTrue(events.last() is AgentEvent.Completed)
        coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
        coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
        verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_immediateExplicitDenyCannotSaveWideGrant() = runBlocking {
        withTimeout(10_000) {
            assertImmediateHostWriteRecheckRejects(chrootHostWriteEvaluation().copy(verdict = ToolPermissionPolicyEngine.Verdict.DENY))
        }
    }

    @Test
    fun hostWriteSessionRegression_immediateUnknownEvidenceChangeCannotSaveWideGrantOrContinueModel() = runBlocking {
        withTimeout(10_000) {
            assertImmediateHostWriteRecheckRejects(chrootHostWriteEvaluation().copy(hostSystemUncertainties = setOf("dynamic-command")))
        }
    }

    @Test
    fun hostWriteSessionRegression_immediateMissingScopeCannotSaveWideGrantOrContinueModel() = runBlocking {
        withTimeout(10_000) { assertImmediateHostWriteRecheckRejects(chrootHostWriteEvaluation().copy(sessionGrant = null)) }
    }

    @Test
    fun hostWriteSessionRegression_immediateChannelUnavailableCannotSaveWideGrantOrContinueModel() = runBlocking {
        withTimeout(10_000) {
            assertImmediateHostWriteRecheckRejects(chrootHostWriteEvaluation().copy(
                verdict = ToolPermissionPolicyEngine.Verdict.DENY, channelUnavailable = true
            ))
        }
    }

    @Test
    fun hostWriteSessionRegression_unknownEvidenceChangeAbortsBeforeToolStartedOrAnotherModelCall() = runBlocking {
        withTimeout(10_000) {
            val initial = ToolPermissionPolicyEngine.EvalResult(
                verdict = ToolPermissionPolicyEngine.Verdict.ASK,
                sessionGrant = "host-write-session-v1:unknown-scope",
                permissionSource = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST),
                hostSystemUncertainties = setOf("unknown-script")
            )
            assertProofChangeAborts(
                initial,
                initial.copy(hostSystemUncertainties = setOf("dynamic-command")),
                choice = PermissionChoice.ALWAYS_SESSION
            )
        }
    }

    @Test
    fun hostWriteSessionRegression_unknownEvidenceIsFrozenBeforeEvaluatorMutatesItsSet() = runBlocking {
        withTimeout(10_000) {
            val uncertainties = linkedSetOf("unknown-script")
            val initial = ToolPermissionPolicyEngine.EvalResult(
                verdict = ToolPermissionPolicyEngine.Verdict.ASK,
                sessionGrant = "host-write-session-v1:mutable-unknown-scope",
                permissionSource = ToolPermissionPolicyEngine.PermissionSource(ToolPermissionPolicyEngine.PermissionNamespace.HOST),
                hostSystemUncertainties = uncertainties
            )
            beforeFreeze = { uncertainties.clear(); uncertainties += "dynamic-command" }
            assertProofChangeAborts(initial, initial.copy(), choice = PermissionChoice.ALWAYS_SESSION)
        }
    }

    private fun chrootHostWriteEvaluation() = ToolPermissionPolicyEngine.EvalResult(
        verdict = ToolPermissionPolicyEngine.Verdict.ASK,
        sessionGrant = "host-write-session-v1:chroot-scope",
        permissionSource = permissionSource("chroot-profile").copy(namespace = ToolPermissionPolicyEngine.PermissionNamespace.CHROOT_GUEST),
        hostSystemUncertainties = setOf("host-linked-opaque-script"),
        hostWriteRootGeneration = 42,
        hostWriteRootAuthorityId = "root-authority"
    )

    @Test
    fun hostWriteSessionRegression_chrootRootGenerationChangeAbortsBeforeToolStartedOrAnotherModelCall() = runBlocking {
        withTimeout(10_000) {
            val initial = chrootHostWriteEvaluation()
            assertProofChangeAborts(initial, initial.copy(hostWriteRootGeneration = 43), choice = PermissionChoice.ALWAYS_SESSION)
            coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
        }
    }

    @Test
    fun hostWriteSessionRegression_chrootRootAuthorityChangeAbortsBeforeToolStartedOrAnotherModelCall() = runBlocking {
        withTimeout(10_000) {
            val initial = chrootHostWriteEvaluation()
            assertProofChangeAborts(initial, initial.copy(hostWriteRootAuthorityId = "replacement-authority"), choice = PermissionChoice.ALWAYS_SESSION)
            coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
        }
    }

    @Test
    fun hostWriteSessionRegression_changedActualSessionSourceCannotReuseOldScopeOrContinueModel() = runBlocking {
        withTimeout(10_000) {
            val initial = chrootHostWriteEvaluation()
            assertProofChangeAborts(
                initial,
                initial.copy(permissionSource = initial.permissionSource!!.copy(workspaceSelectionId = "replacement-selection")),
                choice = PermissionChoice.ALWAYS_SESSION
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun hostWriteSessionRegression_chrootPendingRootChangesRejectAndCancelRevocationGuard() = runTest(timeout = 10.seconds) {
        val changes = listOf(
            RootAccessState(false, 43, "root-authority"),
            RootAccessState(true, 43, "root-authority"),
            RootAccessState(true, 42, "replacement-authority")
        )
        val resolved = mutableListOf<String>()
        for ((index, changed) in changes.withIndex()) {
            rootState.value = RootAccessState(true, 42, "root-authority")
            val tool = GatedTool("Bash")
            register(tool)
            coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } returns chrootHostWriteEvaluation()
            val requested = CompletableDeferred<PendingToolPermission>()
            val decision = CompletableDeferred<PermissionChoice>()
            coEvery { permissions.awaitApproval(any()) } coAnswers {
                requested.complete(firstArg())
                decision.await()
            }
            every { permissions.resolve(any(), any()) } answers {
                resolved += firstArg<String>()
                decision.complete(secondArg())
                Unit
            }
            responses(AIResponse("host-linked CHROOT command", listOf(ToolCall("chroot-pending-$index", tool.name, emptyMap()))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            val job = async { collect(listOf(tool), events) }
            try {
                val request = requested.await()
                assertEquals(chrootHostWriteEvaluation().sessionGrant, request.sessionGrant)
                assertFalse(tool.entered.isCompleted)
                rootState.value = changed
                job.await()
                assertEquals(request.id, resolved.last())
                assertFalse("A late ALWAYS_SESSION must not replace revocation", decision.complete(PermissionChoice.ALWAYS_SESSION))
                assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result.contains("USER_REJECTED"))
                assertTrue(events.none { it is AgentEvent.ToolExecutionStarted })
                assertFalse(tool.entered.isCompleted)
                val countAfterCompletion = resolved.size
                rootState.value = RootAccessState(false, 100 + index.toLong(), "later-authority")
                runCurrent()
                assertEquals("The finally block must cancel and join the guard", countAfterCompletion, resolved.size)
            } finally { job.cancelAndJoin() }
        }
        coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
        coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
        verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_chrootAlwaysSavesAfterImmediateMatchingProofWithoutRootCommandPermit() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            tool.release.complete(Unit)
            val evaluation = chrootHostWriteEvaluation()
            val checks = AtomicInteger()
            coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } answers {
                checks.incrementAndGet()
                evaluation
            }
            coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.ALWAYS_SESSION
            val validated = AtomicInteger()
            coEvery { permissions.confirmHostWriteSessionGrant("session", evaluation.sessionGrant!!) } answers {
                assertFalse(tool.entered.isCompleted)
                assertEquals("Initial review and immediate same-argument recheck must precede storage", 2, checks.get())
                assertTrue(capturedExecutions.isEmpty())
                assertNull(WorkspaceExecutionContext.current())
                validated.incrementAndGet()
                Unit
            }
            responses(AIResponse("CHROOT proposal", listOf(ToolCall("chroot-always", tool.name, emptyMap()))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            assertEquals(1, validated.get())
            assertEquals("The final WEC-bound proof must still run after immediate approval", 3, checks.get())
            assertEquals(1, capturedExecutions.size)
            assertTrue(tool.entered.isCompleted)
            assertFalse(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
            assertTrue(events.none { it is AgentEvent.Failed })
            assertTrue(events.last() is AgentEvent.Completed)
            coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
            verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun chrootGuestBuildsScriptsAndInteractionExecuteWithRealPolicyWithoutHostApproval() = runBlocking {
        withTimeout(10_000) {
            val profile = ContainerProfile.BUILTIN_ALPINE.copy(
                id = "chroot-policy", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT,
                chrootInstance = ChrootInstance("instance", 1, 2, 3)
            )
            val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 1, 1, Any())
            permissionProfileSnapshot = snapshot
            val settings = mockk<ContainerSettingsRepository> {
                coEvery { captureProfileSnapshot() } returns snapshot
                every { checkProfileSnapshot(any()) } returns Unit
            }
            val rules = mockk<PermissionRulesRepository> { coEvery { loadEffectiveForCurrentProject() } returns emptyList() }
            val grants = mockk<SessionPermissionGrantRepository>()
            val engine = ToolPermissionPolicyEngine(
                rules, grants, mockk(), rootManager, shizukuManager, ExecutionModeHolder(), settings
            )
            rootState.value = RootAccessState(false, 43, "")
            val results = mutableListOf<ToolPermissionPolicyEngine.EvalResult>()
            coEvery { policy.evaluate(any(), any(), any(), any()) } coAnswers {
                engine.evaluate(firstArg(), secondArg(), thirdArg(), arg(3)).also { results += it }
            }
            val calls = listOf(
                "Bash" to mapOf("command" to JsonPrimitive("npm install")),
                "Bash" to mapOf("command" to JsonPrimitive("sh /root/opaque.sh")),
                "terminal" to mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("python3 build.py")),
                "terminal" to mapOf("action" to JsonPrimitive("key"), "key" to JsonPrimitive("enter"))
            )
            for ((index, call) in calls.withIndex()) {
                val (name, args) = call
                val tool = GatedTool(name)
                register(tool)
                tool.release.complete(Unit)
                responses(AIResponse("guest operation", listOf(ToolCall("guest-$index", name, args))), AIResponse("done"))
                val events = mutableListOf<AgentEvent>()
                collect(listOf(tool), events)
                assertTrue(name, tool.entered.isCompleted)
                assertFalse(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
                assertTrue(events.none { it is AgentEvent.Failed })
                assertTrue(events.last() is AgentEvent.Completed)
            }
            assertEquals(calls.size * 2, results.size)
            results.chunked(2).forEach { assertEquals(it.first(), it.last()) }
            results.forEach {
                assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, it.verdict)
                assertEquals(ToolPermissionPolicyEngine.PermissionNamespace.CHROOT_GUEST, it.permissionSource?.namespace)
                assertTrue(it.hostSystemUncertainties.isEmpty())
                assertTrue(it.ordinaryGuestUncertainties.isNotEmpty())
                assertNull(it.hostWriteRootGeneration)
                assertNull(it.rootGeneration)
            }
            coVerify(exactly = 0) { permissions.awaitApproval(any()) }
            coVerify(exactly = 0) { permissions.confirmHostWriteSessionGrant(any(), any()) }
            coVerify(exactly = 0) { grants.isAllowed(any(), any()) }
            coVerify(exactly = 0) { rootManager.accessSnapshot() }
            coVerify(exactly = 0) { rootManager.authorize(any(), any(), any(), any()) }
            verify(exactly = 0) { shizukuManager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun equalSourceValuesAndThisCallsApprovalMayExecuteWithoutAnotherPrompt() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("same-source")
            register(tool)
            tool.release.complete(Unit)
            val source = permissionSource("same")
            coEvery { policy.evaluate(tool.name, any(), context.sessionId, any()) } answers {
                ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK,
                    permissionSource = source.copy(), hostSystemTargets = setOf("READ:/proc/version"))
            }
            coEvery { permissions.awaitApproval(any()) } returns PermissionChoice.ONCE
            responses(AIResponse("Proposal", listOf(ToolCall("same-id", tool.name, emptyMap()))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            assertTrue(tool.entered.isCompleted)
            assertFalse(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().isError)
            assertTrue(events.none { it is AgentEvent.Failed })
            assertEquals(2, modelRequests.size)
            coVerify(exactly = 1) { permissions.awaitApproval(any()) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sourceProofChangeInParallelWaitsForSiblingCleanupAndKeepsAlreadyPersistedResult() = runTest(timeout = 10.seconds) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val completed = GatedTool("proof-completed")
        val running = GatedTool("proof-running") {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
            cleanupFinished.complete(Unit)
        }
        val refused = GatedTool("proof-refused")
        val tools = listOf(completed, running, refused)
        register(*tools.toTypedArray())
        val releaseReview = CompletableDeferred<Unit>()
        val completedResult = CompletableDeferred<AgentEvent.ToolCallFinished>()
        val source = permissionSource("reviewed")
        coEvery { policy.evaluate(refused.name, any(), context.sessionId, any()) } coAnswers {
            val actual = arg<WorkspaceExecutionContext?>(3)
            if (actual != null) releaseReview.await()
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW,
                permissionSource = if (actual == null) source else source.copy(selectedProfileId = "changed"))
        }
        responses(AIResponse("Parallel proof proposal", tools.map { ToolCall("${it.name}-id", it.name, emptyMap()) }), AIResponse("must not be requested"))
        val events = mutableListOf<AgentEvent>()
        val job = async {
            workflow.executeEvents("test", context, tools).collect { event ->
                events += event
                when (event) {
                    is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                    is AgentEvent.ToolCallFinished -> {
                        event.persisted?.complete(Unit)
                        completedResult.complete(event)
                    }
                    else -> Unit
                }
            }
        }
        try {
            completed.entered.await()
            running.entered.await()
            completed.release.complete(Unit)
            val persisted = completedResult.await()
            assertFalse(persisted.isError)
            releaseReview.complete(Unit)
            cleanupStarted.await()
            runCurrent()
            assertTrue(running.cancelled.isCompleted)
            assertFalse(cleanupFinished.isCompleted)
            assertFalse(job.isCompleted)
            assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.Completed })
            releaseCleanup.complete(Unit)
            job.await()
            assertTrue(cleanupFinished.isCompleted)
            assertFalse(refused.entered.isCompleted)
            assertEquals(listOf(persisted), events.filterIsInstance<AgentEvent.ToolCallFinished>())
            assertEquals("EXECUTION_SOURCE_CHANGED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
            assertEquals(1, events.count { it is AgentEvent.Completed })
            assertTrue(events.last() is AgentEvent.Completed)
            assertEquals(1, modelRequests.size)
        } finally {
            releaseReview.complete(Unit)
            releaseCleanup.complete(Unit)
            job.cancelAndJoin()
        }
    }

    private open class SourceChangedTool(override val name: String) : AgentTool() {
        override val description = "source mismatch fixture"
        override val parameters = emptyMap<String, ToolParameter>()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = throw ExecutionSourceChangedException()
    }

    private class StreamingSourceChangedTool(name: String) : SourceChangedTool(name), StreamingAgentTool {
        override fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent> = flow {
            throw ExecutionSourceChangedException()
        }
    }

    @Test
    fun sourceChangedFromSyncAndStreamingToolsCannotBeSwallowedAsOrdinaryToolFailures() = runBlocking {
        withTimeout(10_000) {
            for (tool in listOf(SourceChangedTool("sync-source"), StreamingSourceChangedTool("stream-source"))) {
                every { registry.getTool(tool.name) } returns tool
                responses(AIResponse("Proposal", listOf(ToolCall("${tool.name}-id", tool.name, emptyMap()))), AIResponse("must not be requested"))
                val previousRequests = modelRequests.size
                val events = mutableListOf<AgentEvent>()
                collect(listOf(tool), events)
                assertEquals("EXECUTION_SOURCE_CHANGED", events.filterIsInstance<AgentEvent.Failed>().single().reasonCode)
                assertTrue(events.none { it is AgentEvent.ToolCallFinished })
                assertEquals(1, events.count { it is AgentEvent.Completed })
                assertEquals(previousRequests + 1, modelRequests.size)
            }
        }
    }

    @Test fun pluginDeniedRecoveryToolWaitsForPersistedAckAndThenCompletesTheOriginalBatch() = runBlocking<Unit> {
        withTimeout(10_000) {
            val tool = GatedTool("Bash")
            register(tool)
            val snapshot = startRecovery("hook-denied")
            val runId = requireNotNull(snapshot.recoveryRunId)
            coEvery { hooks.beforeTool(any(), any(), any()) } returns com.aicode.feature.plugin.runtime.PluginBeforeTool(JsonObject(emptyMap()), true, "Denied by plugin")
            responses(AIResponse("", listOf(ToolCall("hook-denied-call", tool.name, emptyMap()))), AIResponse("Handled denial"))
            val resultReady = CompletableDeferred<AgentEvent.ToolCallFinished>()
            val release = CompletableDeferred<Unit>()
            val job = async { collectRecovery(snapshot, listOf(tool)) { event -> resultReady.complete(event); release.await() } }
            try {
                val finished = resultReady.await()
                assertTrue(finished.result.contains("PLUGIN_DENIED"))
                assertFalse(finished.persisted!!.isCompleted)
                assertEquals(1, modelRequests.size)
                assertEquals("WAITING_APPROVAL", recoveryLedger.tools.getValue(runId to "hook-denied-call").status)
                assertFalse(tool.entered.isCompleted)
                release.complete(Unit)
                val events = job.await()
                assertEquals("FINISHED", recoveryLedger.tools.getValue(runId to "hook-denied-call").status)
                assertEquals(2, modelRequests.size)
                assertTrue(events.last() is AgentEvent.Completed)
                coVerify(exactly = 0) { permissions.awaitApproval(any()) }
            } finally { job.cancelAndJoin() }
        }
    }

    private suspend fun startRecovery(requestId: String): AgentContext {
        val request = recovery.enqueue("session", RecoveryRequestPayload("test", "/project"), "local",
            requestId = requestId, inputMessageId = "input-$requestId")
        val run = checkNotNull(recovery.claim(request.id, request.environment))
        recoveryPersistence.persist("session", MessageRole.USER, "test", id = request.inputMessageId, recoveryRunId = run.id)
        return context.copy(inputMessageId = request.inputMessageId, recoveryRunId = run.id, persistInputSnapshot = true)
    }

    private suspend fun collectRecovery(
        snapshot: AgentContext,
        tools: List<AgentTool> = emptyList(),
        events: MutableList<AgentEvent> = mutableListOf(),
        beforeToolResult: suspend (AgentEvent.ToolCallFinished) -> Unit = {}
    ): List<AgentEvent> {
        workflow.executeEvents("test", snapshot, tools).collect { event ->
            events += event
            when (event) {
                is AgentEvent.AssistantText -> {
                    recoveryPersistence.persist("session", MessageRole.ASSISTANT, event.content,
                        id = event.messageId, toolCalls = event.toolCalls, recoveryRunId = snapshot.recoveryRunId,
                        recoveryStep = event.recoveryStep)
                    event.persisted?.complete(Unit)
                }
                is AgentEvent.ToolCallStarted -> recoveryPersistence.persist("session", MessageRole.TOOL, SessionUseCase.PENDING_TOOL_MARKER,
                    id = "tool_${event.id}", toolCallId = event.id, toolName = event.toolName, toolArgs = event.argsPreview,
                    toolActionTitle = event.actionTitle, recoveryRunId = snapshot.recoveryRunId, recoveryPlaceholder = true)
                is AgentEvent.ToolCallFinished -> {
                    beforeToolResult(event)
                    recoveryPersistence.persist("session", MessageRole.TOOL, event.result,
                        id = "tool_${event.id}", toolCallId = event.id, toolName = event.toolName, toolArgs = event.argsPreview,
                        isError = event.isError, toolStartedAtMs = event.startedAtMs, toolDurationMs = event.durationMs,
                        recoveryRunId = snapshot.recoveryRunId)
                    event.persisted?.complete(Unit)
                }
                else -> Unit
            }
        }
        return events
    }

    @Test
    fun realAdapterReplaysOriginalToolTitleInRequestBodyWhileExecutionReceivesOnlyCommand() = runBlocking {
        withTimeout(10_000) {
            unmockkConstructor(OpenAIAdapter::class)
            val tool = GatedTool("Bash")
            register(tool)
            tool.release.complete(Unit)
            val command = "printf '%s\\n' 'original command'"
            val arguments = linkedMapOf("tool_title" to JsonPrimitive("Inspect command"), "command" to JsonPrimitive(command))
            val original = JsonObject(arguments).toString()
            val quotedArguments = Gson().toJson(original)
            val requests = mutableListOf<ChatCompletionRequest>()
            val payloads = ArrayDeque(listOf(
                """{"choices":[{"delta":{"content":"Proposal body","tool_calls":[{"index":0,"id":"wire-id","function":{"name":"Bash","arguments":$quotedArguments}}]},"finish_reason":"tool_calls"}]}""",
                """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}"""
            ))
            every { openAIApi.streamChatCompletion(any(), any(), any(), any()) } answers {
                requests += arg<ChatCompletionRequest>(3)
                val body = "data: ${payloads.removeFirst()}\n\ndata: [DONE]\n\n"
                val call = mockk<Call<ResponseBody>>()
                every { call.enqueue(any()) } answers {
                    firstArg<Callback<ResponseBody>>().onResponse(call, Response.success(body.toResponseBody(null)))
                }
                every { call.cancel() } returns Unit
                every { call.isCanceled } returns false
                call
            }
            val events = mutableListOf<AgentEvent>()

            collect(listOf(tool), events)

            assertEquals(2, requests.size)
            val replayed = requests.last().messages.single { it.role == "assistant" }
            assertEquals("Proposal body", replayed.content)
            assertEquals(original, replayed.tool_calls!!.single().function.arguments)
            assertEquals(arguments - "tool_title", tool.entered.await())
            assertEquals(command, (tool.entered.await().getValue("command") as JsonPrimitive).content)
            assertEquals(arguments, events.filterIsInstance<AgentEvent.AssistantText>().first().toolCalls.single().arguments)
            val requestBody = Gson().toJsonTree(requests.last()).asJsonObject
            val assistantBody = requestBody.getAsJsonArray("messages").single { it.asJsonObject.get("role").asString == "assistant" }.asJsonObject
            assertEquals(original, assistantBody.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").get("arguments").asString)
            assertEquals("Inspect command", events.filterIsInstance<AgentEvent.ToolCallFinished>().single().actionTitle)
            assertTrue(events.last() is AgentEvent.Completed)
        }
    }
}
