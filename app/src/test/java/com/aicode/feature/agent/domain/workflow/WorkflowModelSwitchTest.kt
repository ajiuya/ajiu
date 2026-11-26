package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.plugin.runtime.PluginBeforeTool
import com.aicode.feature.plugin.runtime.PluginHookDispatcher
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowModelSwitchTest {
    private data class Selection(val providerId: String, val model: String)

    private data class CreatedProvider(val selection: Selection, val provider: AIProvider)

    private data class ModelRequest(
        val provider: AIProvider,
        val selection: Selection,
        val systemPrompt: String,
        val messages: List<AgentMessage>,
        val tools: List<AgentTool>,
        val reasoningEffort: String?
    )

    private data class CompactionRequest(
        val messages: List<AgentMessage>,
        val provider: AIProvider,
        val windowProvider: AIProvider?,
        val inputTokens: Int
    )

    private val registry = mockk<ToolRegistry>()
    private val providers = mockk<AIProviderRepository>()
    private val factory = mockk<ProviderInstanceFactory>()
    private val prompts = mockk<SystemPromptProvider>()
    private val policy = mockk<ToolPermissionPolicyEngine>()
    private val compactor = mockk<ContextCompactor>()
    private val outputStore = mockk<ToolOutputStore>()
    private val metadataService = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val sessions = mockk<SessionUseCase>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>()
    private val notifications = AgentNotificationCenter()
    private val injector = mockk<AgentEventInjector>()
    private val hooks = mockk<PluginHookDispatcher>(relaxed = true)
    private val configurations = mutableMapOf<String, AIProviderConfig>()
    private val modelMetadata = mutableMapOf<Triple<String, ProviderType, String>, ModelMetadata>()
    private val created = mutableListOf<CreatedProvider>()
    private val requests = mutableListOf<ModelRequest>()
    private val compactions = mutableListOf<CompactionRequest>()
    private val statistics = mutableListOf<LlmCallRecordEntity>()
    private var selection = Selection("provider-a", "model-a")
    private var response: (ModelRequest) -> Flow<AIStreamChunk> = { error("Unexpected model request: ${it.selection}") }
    private var beforeCompaction: suspend (CompactionRequest) -> Unit = {}
    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/project",
        language = null,
        sessionId = "session",
        inputMessageId = "input"
    )
    private val workflow = StatefulAgentWorkflow(
        toolRegistry = registry,
        aiProviderRepository = providers,
        providerFactory = factory,
        promptProvider = prompts,
        permissionManager = mockk(),
        policyEngine = policy,
        contextCompactor = compactor,
        toolOutputStore = outputStore,
        modelMetadataService = metadataService,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = defaultSettings,
        generalSettingsRepository = mockk(relaxed = true),
        sessionUseCase = sessions,
        messagePersistenceUseCase = persistence,
        checkpointManager = mockk(relaxed = true),
        llmCallRecordDao = records,
        agentNotificationCenter = notifications,
        eventInjector = injector,
        fileAccess = mockk(),
        appContext = mockk { every { getString(any()) } returns "Deferred until plan approval" },
        rootCommandManager = mockk(),
        shizukuManager = mockk(),
        recoveryRepository = mockk(relaxed = true),
        pluginHooks = hooks,
        workspaceExecutionContextFactory = mockk {
            coEvery { freeze(any(), any()) } returns WorkspaceExecutionContext(
                ExecutionMode.LOCAL_PROOT, "model-switch-test", mockk(), null, null
            ) {}
        }
    )

    @Before
    fun prepare() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        mockkObject(FileLogger)
        every { FileLogger.memoryCheckpoint(any(), any(), any(), any()) } returns Unit
        every { prompts.build(any()) } returns "system"
        every { compactor.discardCheckpoint(any()) } returns Unit
        coEvery { sessions.getSessionById("session") } answers {
            ChatSessionEntity(
                id = "session", title = "test", createdAt = 1L, updatedAt = 1L,
                providerId = selection.providerId, model = selection.model
            )
        }
        coEvery { providers.getProviderById(any()) } answers { configurations[firstArg<String>()] }
        coEvery { defaultSettings.getDefaultProviderId() } returns ""
        coEvery { defaultSettings.getDefaultModel() } returns ""
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery { metadataService.resolve(any(), any(), any()) } answers {
            modelMetadata.getValue(Triple(firstArg<String>(), secondArg<ProviderType>(), thirdArg<String>()))
        }
        coEvery { factory.create(any(), any()) } answers {
            val config = firstArg<AIProviderConfig>()
            assertEquals("session", secondArg<String?>())
            newProvider(config)
        }
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val request = CompactionRequest(
                firstArg<List<AgentMessage>>().toList(), secondArg(), arg(4), arg(7)
            )
            compactions += request
            beforeCompaction(request)
            CompactionResult(request.messages, compacted = false)
        }
        coEvery { policy.evaluate(any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW)
        every { outputStore.process(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        coEvery { hooks.beforeTool(any(), any(), any()) } answers { PluginBeforeTool(thirdArg()) }
        coEvery { hooks.afterTool(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        coEvery { records.insert(any()) } answers { statistics += firstArg<LlmCallRecordEntity>() }
        addModel("provider-a", "model-a", inputBudget = 80_000)
    }

    @After
    fun cleanup() {
        unmockkObject(FileLogger)
        unmockkStatic(SystemClock::class)
    }

    private fun addModel(
        providerId: String,
        model: String,
        type: ProviderType = ProviderType.OPENAI,
        contextTokens: Int = 100_000,
        inputBudget: Int = 40_000,
        supportsVision: Boolean = false
    ): ModelMetadata {
        val previous = configurations[providerId]
        require(previous == null || previous.type == type)
        configurations[providerId] = previous?.copy(models = (previous.models + model).distinct())
            ?: AIProviderConfig(
                id = providerId, name = providerId, type = type, apiKey = "test-key",
                baseUrl = "https://example.invalid", defaultModel = model, models = listOf(model)
            )
        return ModelMetadata(
            id = model,
            providerId = providerId,
            contextTokens = contextTokens,
            inputTokens = inputBudget,
            outputTokens = 256,
            supportsTools = true,
            supportsVision = supportsVision
        ).also {
            assertEquals(inputBudget, ModelContextPolicy.effectiveInputBudget(it))
            modelMetadata[Triple(providerId, type, model)] = it
        }
    }

    private fun newProvider(config: AIProviderConfig): AIProvider {
        val provider = mockk<AIProvider>(relaxed = true)
        var providerId = config.id
        var model = config.effectiveModel
        var capability: ReasoningCapability? = modelMetadata.getValue(Triple(config.id, config.type, model)).toReasoningCapability()
        every { provider.providerId } answers { providerId }
        every { provider.providerId = any() } answers { providerId = firstArg() }
        every { provider.model } answers { model }
        every { provider.model = any() } answers { model = firstArg() }
        every { provider.reasoningCapability } answers { capability }
        every { provider.reasoningCapability = any() } answers { capability = firstArg() }
        every { provider.useResponseApi } returns config.useResponseApi
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            val request = ModelRequest(
                provider = provider,
                selection = Selection(provider.providerId, provider.model),
                systemPrompt = firstArg(),
                messages = secondArg<List<AgentMessage>>().toList(),
                tools = thirdArg<List<AgentTool>>().toList(),
                reasoningEffort = arg(3)
            )
            requests += request
            response(request)
        }
        created += CreatedProvider(Selection(config.id, config.effectiveModel), provider)
        return provider
    }

    private fun select(providerId: String, model: String) {
        require(model in configurations.getValue(providerId).models)
        selection = Selection(providerId, model)
    }

    private fun responses(vararg values: AIResponse) {
        val pending = ArrayDeque(values.toList())
        response = { flowOf<AIStreamChunk>(AIStreamChunk.Final(pending.removeFirst())) }
    }

    private class GatedTool(
        override val name: String,
        private val payload: String = name
    ) : AgentTool() {
        override val description = "test tool"
        override val parameters = emptyMap<String, ToolParameter>()
        val entered = CompletableDeferred<Map<String, JsonElement>>()
        val release = CompletableDeferred<Unit>()
        val result: ToolResult get() = ToolResult.Success(JsonPrimitive(payload))

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            entered.complete(args.toMap())
            release.await()
            return result
        }
    }

    private fun register(vararg tools: GatedTool) {
        tools.forEach { tool -> every { registry.getTool(tool.name) } returns tool }
    }

    private suspend fun acknowledge(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
            is AgentEvent.ToolCallFinished -> {
                notifications.ack("session", event.notificationSeqs)
                event.persisted?.complete(Unit)
            }
            else -> Unit
        }
    }

    private suspend fun collect(
        events: MutableList<AgentEvent>,
        tools: List<AgentTool> = emptyList(),
        requestContext: AgentContext = context,
        acknowledgeEvent: suspend (AgentEvent) -> Boolean = { true }
    ) {
        workflow.executeEvents("test", requestContext, tools).collect { event ->
            events += event
            if (acknowledgeEvent(event)) acknowledge(event)
        }
    }

    private fun assertCompleted(events: List<AgentEvent>) {
        assertTrue(events.filterIsInstance<AgentEvent.Failed>().toString(), events.none { it is AgentEvent.Failed })
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    private fun assertFailed(events: List<AgentEvent>, reasonCode: String) {
        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(reasonCode, failure.reasonCode)
        assertEquals(listOf(failure, AgentEvent.Completed), events.takeLast(2))
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
    }

    private fun assertStatistics(vararg expected: Selection) {
        assertEquals(expected.toList(), statistics.map { Selection(it.providerId.orEmpty(), it.model.orEmpty()) })
        assertTrue(statistics.all { it.kind == "chat" && it.status == "success" && it.sessionId == "session" })
        assertEquals(requests.map { it.selection }, statistics.map { Selection(it.providerId.orEmpty(), it.model.orEmpty()) })
    }

    private fun assertRequestBudgets(events: List<AgentEvent>, vararg expected: Int) {
        val usages = events.filterIsInstance<AgentEvent.ContextUsage>()
        assertEquals(expected.flatMap { listOf(it, it) }, usages.map { it.inputBudget })
        val assistantUsages = events.filterIsInstance<AgentEvent.AssistantText>().map { it.contextUsage }
        assertTrue(assistantUsages.all { it != null })
        assertEquals(expected.toList(), assistantUsages.map { it!!.inputBudget })
    }

    private fun assertToolPairs(request: ModelRequest, calls: List<ToolCall>, tools: List<GatedTool>) {
        val assistant = request.messages.filterIsInstance<AgentMessage.AssistantMessage>().single { it.toolCalls == calls }
        val following = request.messages.drop(request.messages.indexOf(assistant) + 1)
        val results = following.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(calls.map { it.id }, results.map { it.id })
        assertEquals(calls.map { it.name }, results.map { it.toolName })
        assertEquals(tools.map { it.result.toTransportString() }, results.map { it.result })
        assertEquals(calls.size, results.size)
    }

    @Test
    fun sameProviderModelSwitchWaitsForEveryParallelToolAndPersistenceAck() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-a", "model-b", inputBudget = 24_000)
            val slow = GatedTool("slow")
            val fast = GatedTool("fast")
            register(slow, fast)
            val calls = listOf(
                ToolCall("slow-id", slow.name, emptyMap()),
                ToolCall("fast-id", fast.name, emptyMap())
            )
            responses(
                AIResponse(
                    "Run both tools", calls, signature = "model-a-signature",
                    thinkingBlocksJson = "[{\"signature\":\"model-a-signature\"}]", inputTokens = 101, outputTokens = 12
                ),
                AIResponse("done", inputTokens = 202, outputTokens = 23)
            )
            val events = mutableListOf<AgentEvent>()
            val finishes = Channel<AgentEvent.ToolCallFinished>(Channel.UNLIMITED)
            val job = launch {
                collect(events, listOf(slow, fast)) { event ->
                    if (event is AgentEvent.ToolCallFinished) {
                        finishes.send(event)
                        false
                    } else true
                }
            }
            slow.entered.await()
            fast.entered.await()
            select("provider-a", "model-b")
            fast.release.complete(Unit)
            val fastFinished = finishes.receive()
            assertEquals("fast-id", fastFinished.id)
            acknowledge(fastFinished)
            yield()
            assertFalse(slow.release.isCompleted)
            assertEquals(listOf(Selection("provider-a", "model-a")), requests.map { it.selection })
            assertEquals(1, created.size)

            slow.release.complete(Unit)
            val slowFinished = finishes.receive()
            assertEquals("slow-id", slowFinished.id)
            assertNotNull(slowFinished.persisted)
            yield()
            assertFalse(job.isCompleted)
            assertEquals(1, requests.size)
            assertEquals(1, created.size)
            acknowledge(slowFinished)
            job.join()

            assertCompleted(events)
            assertEquals(listOf("model-a", "model-b"), requests.map { it.selection.model })
            assertEquals(listOf("provider-a", "provider-a"), requests.map { it.selection.providerId })
            assertEquals(2, created.size)
            assertSame(created[0].provider, requests[0].provider)
            assertSame(created[1].provider, requests[1].provider)
            assertFalse(requests[0].provider === requests[1].provider)
            assertToolPairs(requests[1], calls, listOf(slow, fast))
            val replayedAssistant = requests[1].messages.filterIsInstance<AgentMessage.AssistantMessage>().single()
            assertEquals("Run both tools", replayedAssistant.content)
            assertTrue(replayedAssistant.signature.isEmpty() && replayedAssistant.thinkingBlocksJson.isEmpty())
            assertEquals("model-a-signature", events.filterIsInstance<AgentEvent.AssistantText>().first().signature)
            assertEquals(listOf("fast-id", "slow-id"), events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.id })
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-a", "model-b"))
            assertEquals(listOf(101, 202), statistics.map { it.inputTokens })
            assertEquals(listOf(12, 23), statistics.map { it.outputTokens })
            assertRequestBudgets(events, 80_000, 24_000)
            assertSame(requests[1].provider, compactions[1].windowProvider)
            verify(exactly = 1) { compactor.discardCheckpoint("session") }
        }
    }

    @Test
    fun providerSwitchDuringStreamKeepsRetryOnOriginalInstanceAndRemovesOnlyOldNativeMetadata() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-b", "vision-b", ProviderType.GEMINI, inputBudget = 32_000, supportsVision = true)
            val image = AgentImage("image/png", "aW1hZ2U=", "/fixture/image.png")
            val historicalCall = ToolCall("historical-call", "read", emptyMap())
            val historicalAssistant = AgentMessage.AssistantMessage(
                id = "historical-assistant", content = "historical body", toolCalls = listOf(historicalCall),
                reasoning = "historical reasoning", signature = "historical-signature",
                thinkingBlocksJson = "[{\"type\":\"thinking\",\"signature\":\"historical-signature\"}]",
                images = listOf(image)
            )
            val history = listOf(
                AgentMessage.UserMessage(id = "historical-user", content = "look at this", images = listOf(image)),
                historicalAssistant,
                AgentMessage.ToolResultMessage(id = historicalCall.id, toolName = "read", result = "historical result", images = listOf(image))
            )
            val originalHistory = history.toList()
            val requestContext = context.copy(history = history, inputImages = listOf(image))
            val streamEntered = CompletableDeferred<Unit>()
            val retry = CompletableDeferred<Unit>()
            val retryObserved = CompletableDeferred<Unit>()
            val finishStream = CompletableDeferred<Unit>()
            val tool = GatedTool("next")
            tool.release.complete(Unit)
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            response = { request ->
                if (requests.size == 1) flow {
                    streamEntered.complete(Unit)
                    retry.await()
                    emit(AIStreamChunk.Retrying(1, 3, RetryErrorInfo(RetryErrorKind.TIMEOUT)))
                    retryObserved.complete(Unit)
                    finishStream.await()
                    assertEquals(Selection("provider-a", "model-a"), request.selection)
                    assertEquals("provider-a", request.provider.providerId)
                    assertEquals("model-a", request.provider.model)
                    emit(AIStreamChunk.TextDelta("original provider body"))
                    emit(AIStreamChunk.Final(AIResponse(
                        "original provider body", listOf(call), reasoning = "current reasoning",
                        signature = "stream-signature", thinkingBlocksJson = "[{\"signature\":\"stream-signature\"}]",
                        inputTokens = 111, outputTokens = 21
                    )))
                } else flowOf(AIStreamChunk.Final(AIResponse("vision complete", inputTokens = 222, outputTokens = 22)))
            }
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events, listOf(tool), requestContext) }
            streamEntered.await()
            select("provider-b", "vision-b")
            retry.complete(Unit)
            retryObserved.await()
            assertEquals(1, requests.size)
            assertEquals(1, created.size)
            assertEquals("model-a", created.single().provider.model)
            finishStream.complete(Unit)
            job.join()

            assertCompleted(events)
            assertEquals(listOf(Selection("provider-a", "model-a"), Selection("provider-b", "vision-b")), requests.map { it.selection })
            assertSame(created[0].provider, requests[0].provider)
            assertSame(created[1].provider, requests[1].provider)
            assertEquals(2, created.size)
            assertEquals(1, events.filterIsInstance<AgentEvent.Retrying>().size)
            assertEquals(1, statistics.first().retryCount)
            assertEquals(0, statistics.last().retryCount)
            val originalUsers = requests[0].messages.filterIsInstance<AgentMessage.UserMessage>()
            val switchedUsers = requests[1].messages.filterIsInstance<AgentMessage.UserMessage>()
            assertTrue(originalUsers.all { it.images.isEmpty() })
            assertEquals(listOf(listOf(image), listOf(image)), switchedUsers.map { it.images })
            val originalAssistant = requests[0].messages.filterIsInstance<AgentMessage.AssistantMessage>().single()
            assertEquals("historical-signature", originalAssistant.signature)
            assertTrue(originalAssistant.images.isEmpty())
            assertTrue(originalAssistant.thinkingBlocksJson.isEmpty())
            assertTrue(requests[0].messages.filterIsInstance<AgentMessage.ToolResultMessage>().single().images.isEmpty())
            val switchedAssistants = requests[1].messages.filterIsInstance<AgentMessage.AssistantMessage>()
            assertEquals(listOf("historical body", "original provider body"), switchedAssistants.map { it.content })
            assertEquals(listOf("historical reasoning", "current reasoning"), switchedAssistants.map { it.reasoning })
            assertTrue(switchedAssistants.all { it.signature.isEmpty() && it.thinkingBlocksJson.isEmpty() })
            assertEquals(listOf(listOf(image), emptyList<AgentImage>()), switchedAssistants.map { it.images })
            assertEquals(listOf(listOf(historicalCall), listOf(call)), switchedAssistants.map { it.toolCalls })
            val results = requests[1].messages.filterIsInstance<AgentMessage.ToolResultMessage>()
            assertEquals(listOf("historical-call", "next-id"), results.map { it.id })
            assertEquals(listOf("historical result", tool.result.toTransportString()), results.map { it.result })
            assertEquals(listOf(listOf(image), emptyList<AgentImage>()), results.map { it.images })
            assertEquals(originalHistory, history)
            assertEquals(originalHistory, requestContext.history)
            assertEquals(listOf(image), requestContext.inputImages)
            assertEquals("historical-signature", historicalAssistant.signature)
            assertEquals(listOf(image), historicalAssistant.images)
            assertFalse(historicalAssistant.thinkingBlocksJson.isEmpty())
            assertEquals("stream-signature", events.filterIsInstance<AgentEvent.AssistantText>().first().signature)
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-b", "vision-b"))
            assertEquals(listOf(111, 222), statistics.map { it.inputTokens })
            assertRequestBudgets(events, 80_000, 32_000)
            coVerify(exactly = 1) { metadataService.resolve("provider-b", ProviderType.GEMINI, "vision-b") }
        }
    }

    @Test
    fun selectionChangedAfterPreparationCannotMixNewVisionOrBudgetIntoOldRequest() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-b", "vision-b", inputBudget = 16_000, supportsVision = true)
            val image = AgentImage("image/png", "aW1hZ2U=", "/fixture/image.png")
            val tool = GatedTool("next")
            tool.release.complete(Unit)
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            beforeCompaction = {
                if (compactions.size == 1) select("provider-b", "vision-b")
            }
            responses(AIResponse("use tool", listOf(call)), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()

            collect(events, listOf(tool), context.copy(inputImages = listOf(image)))

            assertCompleted(events)
            assertEquals(listOf("model-a", "vision-b"), requests.map { it.selection.model })
            assertTrue(requests[0].messages.filterIsInstance<AgentMessage.UserMessage>().single().images.isEmpty())
            assertEquals(listOf(image), requests[1].messages.filterIsInstance<AgentMessage.UserMessage>().single().images)
            assertSame(requests[0].provider, compactions[0].windowProvider)
            assertSame(requests[1].provider, compactions[1].windowProvider)
            assertRequestBudgets(events, 80_000, 16_000)
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-b", "vision-b"))
            assertToolPairs(requests[1], listOf(call), listOf(tool))
        }
    }

    @Test
    fun unchangedProviderAndModelReuseOneInstanceEvenWhenUnrelatedConfigurationChanges() = runBlocking {
        withTimeout(10_000) {
            val tool = GatedTool("next")
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            responses(
                AIResponse("first body", listOf(call), signature = "same-model-signature", thinkingBlocksJson = "[{\"signature\":\"same-model-signature\"}]", inputTokens = 101),
                AIResponse("done", inputTokens = 102)
            )
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events, listOf(tool)) }
            tool.entered.await()
            configurations["provider-a"] = configurations.getValue("provider-a").copy(name = "renamed", apiKey = "replacement-test-key")
            tool.release.complete(Unit)
            job.join()

            assertCompleted(events)
            assertEquals(2, requests.size)
            assertEquals(1, created.size)
            assertSame(requests[0].provider, requests[1].provider)
            assertEquals(listOf("model-a", "model-a"), requests.map { it.selection.model })
            val replayed = requests[1].messages.filterIsInstance<AgentMessage.AssistantMessage>().single()
            assertEquals("same-model-signature", replayed.signature)
            assertEquals("[{\"signature\":\"same-model-signature\"}]", replayed.thinkingBlocksJson)
            assertToolPairs(requests[1], listOf(call), listOf(tool))
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-a", "model-a"))
            assertRequestBudgets(events, 80_000, 80_000)
            coVerify(exactly = 1) { factory.create(any(), "session") }
        }
    }

    @Test
    fun selectionChangedWhileFinalResponseStreamsDoesNotStartAnExtraRequestAfterCompletion() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-b", "model-b", inputBudget = 12_000)
            val streamEntered = CompletableDeferred<Unit>()
            val finishStream = CompletableDeferred<Unit>()
            response = { flow {
                streamEntered.complete(Unit)
                finishStream.await()
                emit(AIStreamChunk.Final(AIResponse("task complete", inputTokens = 123, outputTokens = 45)))
            } }
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events) }
            streamEntered.await()
            select("provider-b", "model-b")
            finishStream.complete(Unit)
            job.join()

            assertCompleted(events)
            assertEquals(listOf(Selection("provider-a", "model-a")), requests.map { it.selection })
            assertEquals(1, created.size)
            assertStatistics(Selection("provider-a", "model-a"))
            assertEquals(123, statistics.single().inputTokens)
            assertEquals(45, statistics.single().outputTokens)
            assertRequestBudgets(events, 80_000)
            coVerify(exactly = 1) { factory.create(any(), "session") }
            coVerify(exactly = 0) { metadataService.resolve("provider-b", any(), "model-b") }
        }
    }

    @Test
    fun switchingToSmallWindowClearsLowUsageBaselineAndBlocksBeforeSendingAnOversizedRequest() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-a", "small", contextTokens = 2_048, inputBudget = 512)
            val tool = GatedTool("next")
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            val requestContext = context.copy(history = listOf(AgentMessage.UserMessage(id = "large-history", content = "x".repeat(8_000))))
            responses(AIResponse("next step", listOf(call), inputTokens = 1, outputTokens = 3))
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events, listOf(tool), requestContext) }
            tool.entered.await()
            select("provider-a", "small")
            tool.release.complete(Unit)
            job.join()

            assertFailed(events, "input_budget_exceeded")
            assertEquals(listOf(Selection("provider-a", "model-a")), requests.map { it.selection })
            assertEquals(listOf("model-a", "small"), created.map { it.selection.model })
            assertEquals(2, compactions.size)
            val blocked = events.filterIsInstance<AgentEvent.ContextUsage>().last()
            val fullEstimate = ContextTokenEstimator.estimate("system", compactions.last().messages, listOf(tool))
            val stalePrediction = ContextTokenEstimator.calibrated(fullEstimate, ContextTokenEstimator.estimate("system", requests.single().messages, listOf(tool)), 1)
            assertTrue("Fixture must distinguish a stale low baseline", stalePrediction < 512)
            assertEquals(fullEstimate, blocked.inputTokens)
            assertEquals(512, blocked.inputBudget)
            assertTrue(blocked.estimated)
            assertTrue(blocked.inputTokens >= blocked.inputBudget)
            assertEquals(fullEstimate, compactions.last().inputTokens)
            assertSame(created.last().provider, compactions.last().windowProvider)
            assertStatistics(Selection("provider-a", "model-a"))
            assertEquals(1, statistics.single().inputTokens)
            assertEquals(listOf(80_000, 80_000, 512), events.filterIsInstance<AgentEvent.ContextUsage>().map { it.inputBudget })
        }
    }

    @Test
    fun switchingModelsClearsHighUsageBaselineSoAFittingSmallWindowRequestCanProceed() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-a", "small", contextTokens = 4_096, inputBudget = 1_000)
            val tool = GatedTool("next")
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            responses(AIResponse("next step", listOf(call), inputTokens = 50_000), AIResponse("done", inputTokens = 70))
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events, listOf(tool)) }
            tool.entered.await()
            select("provider-a", "small")
            tool.release.complete(Unit)
            job.join()

            assertCompleted(events)
            assertEquals(listOf("model-a", "small"), requests.map { it.selection.model })
            val expected = ContextTokenEstimator.estimate("system", requests[1].messages, listOf(tool))
            val beforeSecondRequest = events.filterIsInstance<AgentEvent.ContextUsage>()[2]
            assertTrue("Fixture must fit the smaller window without calibration", expected < 1_000)
            assertEquals(expected, beforeSecondRequest.inputTokens)
            assertEquals(expected, compactions[1].inputTokens)
            assertTrue(beforeSecondRequest.estimated)
            assertEquals(1_000, beforeSecondRequest.inputBudget)
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-a", "small"))
            assertEquals(listOf(50_000, 70), statistics.map { it.inputTokens })
            assertRequestBudgets(events, 80_000, 1_000)
            assertToolPairs(requests[1], listOf(call), listOf(tool))
        }
    }

    @Test
    fun multipleSelectionsDuringOneToolBatchCreateOnlyTheLastSelectedModel() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-a", "model-b", inputBudget = 24_000)
            addModel("provider-c", "model-c", inputBudget = 20_000)
            addModel("provider-c", "model-d", inputBudget = 18_000)
            val tool = GatedTool("next")
            register(tool)
            val call = ToolCall("next-id", tool.name, emptyMap())
            responses(AIResponse("next step", listOf(call), inputTokens = 10), AIResponse("done", inputTokens = 20))
            val events = mutableListOf<AgentEvent>()
            val job = launch { collect(events, listOf(tool)) }
            tool.entered.await()
            select("provider-a", "model-b")
            select("provider-c", "model-c")
            select("provider-c", "model-d")
            assertEquals(1, created.size)
            tool.release.complete(Unit)
            job.join()

            assertCompleted(events)
            val expected = listOf(Selection("provider-a", "model-a"), Selection("provider-c", "model-d"))
            assertEquals(expected, requests.map { it.selection })
            assertEquals(expected, created.map { it.selection })
            assertStatistics(*expected.toTypedArray())
            assertRequestBudgets(events, 80_000, 18_000)
            assertToolPairs(requests[1], listOf(call), listOf(tool))
        }
    }

    @Test
    fun switchingAfterEachTruncationDoesNotResetTheTaskContinuationCount() = runBlocking {
        withTimeout(10_000) {
            val models = listOf("model-a", "model-b", "model-c", "model-d", "model-e")
            models.drop(1).forEach { addModel("provider-a", it, inputBudget = 40_000) }
            response = {
                flowOf(AIStreamChunk.Final(AIResponse("part-${requests.size}", stopReason = "length", inputTokens = 100 + requests.size, outputTokens = 20)))
            }
            var finishedResponses = 0
            val events = mutableListOf<AgentEvent>()

            collect(events) { event ->
                if (event is AgentEvent.AssistantText) {
                    finishedResponses++
                    if (finishedResponses < models.size) select("provider-a", models[finishedResponses])
                }
                true
            }

            assertFailed(events, "continuation_budget_exceeded")
            val expected = models.take(4).map { Selection("provider-a", it) }
            assertEquals(expected, requests.map { it.selection })
            assertEquals(expected, created.map { it.selection })
            assertStatistics(*expected.toTypedArray())
            assertEquals(listOf(101, 102, 103, 104), statistics.map { it.inputTokens })
            assertTrue(statistics.all { it.stopReason == "length" && it.outputTokens == 20 })
            assertEquals(listOf(0, 1, 2, 3), requests.map { request ->
                request.messages.filterIsInstance<AgentMessage.UserMessage>().count { it.content == "Your response was truncated. Continue from where it stopped." }
            })
            assertEquals(listOf("part-1", "part-2", "part-3"), requests.last().messages.filterIsInstance<AgentMessage.AssistantMessage>().map { it.content })
            assertRequestBudgets(events, 80_000, 40_000, 40_000, 40_000)
            coVerify(exactly = 3) { persistence.invalidateHistory("session") }
        }
    }

    @Test
    fun switchingModelsDoesNotResetTheTaskCumulativeContinuationOutputBudget() = runBlocking {
        withTimeout(10_000) {
            addModel("provider-a", "model-b", inputBudget = 40_000)
            addModel("provider-a", "model-c", inputBudget = 30_000)
            response = {
                flowOf(AIStreamChunk.Final(AIResponse("part-${requests.size}", stopReason = "length", inputTokens = 100 + requests.size, outputTokens = 40_000)))
            }
            val events = mutableListOf<AgentEvent>()
            var finishedResponses = 0

            collect(events) { event ->
                if (event is AgentEvent.AssistantText) {
                    finishedResponses++
                    select("provider-a", if (finishedResponses == 1) "model-b" else "model-c")
                }
                true
            }

            assertFailed(events, "continuation_budget_exceeded")
            assertEquals(listOf("model-a", "model-b"), requests.map { it.selection.model })
            assertEquals(listOf("model-a", "model-b"), created.map { it.selection.model })
            assertStatistics(Selection("provider-a", "model-a"), Selection("provider-a", "model-b"))
            assertEquals(80_000, statistics.sumOf { it.outputTokens })
            assertEquals(listOf(101, 102), statistics.map { it.inputTokens })
            assertEquals(1, requests.last().messages.filterIsInstance<AgentMessage.UserMessage>().count { it.content == "Your response was truncated. Continue from where it stopped." })
            assertRequestBudgets(events, 80_000, 40_000)
            coVerify(exactly = 1) { persistence.invalidateHistory("session") }
        }
    }
}
