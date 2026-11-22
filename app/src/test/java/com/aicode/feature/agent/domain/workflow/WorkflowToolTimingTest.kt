package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentRecoveryDao
import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryStatus
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
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
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
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
    private val compactor = mockk<ContextCompactor>()
    private val outputStore = mockk<ToolOutputStore>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val notifications = AgentNotificationCenter()
    private val injector = mockk<AgentEventInjector>()
    private val modelRequests = mutableListOf<List<AgentMessage>>()
    private val context = AgentContext(null, null, "/project", null, sessionId = "session")
    private val hooks = mockk<com.aicode.feature.plugin.runtime.PluginHookDispatcher>(relaxed = true)
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
        llmCallRecordDao = mockk(relaxed = true),
        agentNotificationCenter = notifications,
        eventInjector = injector,
        fileAccess = mockk(),
        appContext = mockk { every { getString(any()) } returns "Deferred until plan approval" },
        rootCommandManager = mockk(),
        shizukuManager = mockk(),
        recoveryRepository = recovery,
        pluginHooks = hooks,
        workspaceExecutionContextFactory = mockk {
            coEvery { freeze(any()) } returns WorkspaceExecutionContext(
                ExecutionMode.LOCAL_PROOT, "timing-test", mockk(), null, null
            ) {}
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
        coEvery { policy.evaluate(any(), any(), any()) } returns
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

    private class GatedTool(override val name: String) : AgentTool() {
        override val description = "test tool"
        override val parameters = emptyMap<String, ToolParameter>()
        val entered = CompletableDeferred<Map<String, JsonElement>>()
        val release = CompletableDeferred<Unit>()
        val permissionArguments = mutableListOf<Map<String, JsonElement>>()

        override fun buildPermissionRequest(
            callId: String,
            args: Map<String, JsonElement>,
            argsPreview: String
        ): PendingToolPermission {
            permissionArguments += args.toMap()
            return super.buildPermissionRequest(callId, args, argsPreview)
        }

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            entered.complete(args.toMap())
            release.await()
            return ToolResult.Success(JsonPrimitive(name))
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
            coEvery { policy.evaluate(tool.name, any(), context.sessionId) } returns
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
            coVerify(exactly = 2) { policy.evaluate(tool.name, args - "tool_title", context.sessionId) }
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
            coEvery { policy.evaluate(rejected.name, any(), any()) } returns
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
            coEvery { policy.evaluate(tool.name, any(), any()) } returns
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
        coEvery { policy.evaluate(tool.name, any(), any()) } returns
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
        coEvery { policy.evaluate("rejected", any(), any()) } returns
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
            coVerify(exactly = 0) { policy.evaluate("unasked", any(), any()) }
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
            coVerify(exactly = 0) { policy.evaluate(plan.name, any(), any()) }
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
            coEvery { policy.evaluate(tool.name, any(), any()) } returns
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
            coEvery { policy.evaluate(rejected.name, any(), any()) } returns
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
            coVerify(exactly = 0) { policy.evaluate(unasked.name, any(), any()) }
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
            coEvery { policy.evaluate(tool.name, any(), any()) } answers {
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
            coEvery { policy.evaluate(tool.name, forbidden, any()) } returns
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
            coEvery { policy.evaluate(tool.name, any(), any()) } answers {
                checks++
                ToolPermissionPolicyEngine.EvalResult(if (checks == 1) ToolPermissionPolicyEngine.Verdict.ALLOW else ToolPermissionPolicyEngine.Verdict.DENY, "New policy denied")
            }
            responses(AIResponse("", listOf(ToolCall("revoked", tool.name, emptyMap()))), AIResponse("done"))
            val events = mutableListOf<AgentEvent>()
            collect(listOf(tool), events)
            assertEquals(2, checks)
            assertFalse(tool.entered.isCompleted)
            assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result.contains("SYSTEM_DENIED"))
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
