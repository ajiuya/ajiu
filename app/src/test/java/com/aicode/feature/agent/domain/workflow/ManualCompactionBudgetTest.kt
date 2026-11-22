package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManualCompactionBudgetTest {
    private val sessionId = "session"
    private val registry = mockk<ToolRegistry>()
    private val providers = mockk<AIProviderRepository>()
    private val prompts = mockk<SystemPromptProvider>()
    private val compactor = mockk<ContextCompactor>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val sessions = mockk<SessionUseCase>()
    private val persistence = mockk<MessagePersistenceUseCase>()
    private val keyRotator = mockk<ProviderKeyRotator>()
    private val promptContext = slot<AgentContext>()
    private val history = listOf(
        AgentMessage.UserMessage(id = "user-1", content = "Inspect the current implementation"),
        AgentMessage.AssistantMessage(id = "assistant-1", content = "Located the workflow"),
        AgentMessage.UserMessage(id = "user-2", content = "Check manual compaction wiring"),
        AgentMessage.AssistantMessage(id = "assistant-2", content = "Keep the fixed request overhead")
    )
    private val context = AgentContext(
        currentFile = "/project/src/Manual.kt",
        selectedCode = "val retained = true",
        projectRoot = "/project/active-workspace",
        language = "kotlin",
        history = listOf(AgentMessage.UserMessage(id = "stale", content = "Old UI history")),
        sessionId = sessionId,
        inputMessageId = "manual-request",
        lastInputTokens = 42_000,
        goal = "Preserve the active session snapshot",
        goalStatus = GoalStatus.ACTIVE,
        reasoningEffort = "high",
        agentDefinition = AgentDefinition(
            name = "reviewer",
            description = "Review the current workspace",
            allowedTools = listOf("readFile", "search"),
            prompt = "Review only the selected code and report findings"
        ),
        rootApprovalId = "root-approval"
    )
    private val tools = listOf(BudgetTool("readFile"), BudgetTool("search"))
    private val systemPrompt = """
        Main system instructions: preserve the complete request context.

        Workspace rules: inspect the selected project before making changes.

        Memory and skills: retain all configured fixed prompt fragments.
    """.trimIndent()
    private val mainConfig = AIProviderConfig(
        id = "main-provider", name = "main", type = ProviderType.OPENAI, apiKey = "test-key",
        baseUrl = "https://example.invalid", defaultModel = "main-model", models = listOf("main-model")
    )
    private val summaryConfig = AIProviderConfig(
        id = "summary-provider", name = "summary", type = ProviderType.OPENAI, apiKey = "test-key",
        baseUrl = "https://example.invalid", defaultModel = "summary-default",
        models = listOf("summary-default", "summary-model")
    )
    private val workflow = StatefulAgentWorkflow(
        toolRegistry = registry,
        aiProviderRepository = providers,
        providerFactory = com.aicode.feature.agent.domain.provider.ProviderInstanceFactory(
            mockk(), mockk(), mockk(), metadata, mockk(relaxed = true), keyRotator
        ),
        promptProvider = prompts,
        permissionManager = mockk(),
        policyEngine = mockk(),
        contextCompactor = compactor,
        toolOutputStore = mockk(),
        modelMetadataService = metadata,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = defaultSettings,
        generalSettingsRepository = mockk(relaxed = true),
        sessionUseCase = sessions,
        messagePersistenceUseCase = persistence,
        checkpointManager = mockk(),
        llmCallRecordDao = mockk(),
        agentNotificationCenter = mockk(),
        eventInjector = mockk(),
        fileAccess = mockk(),
        appContext = mockk(),
        rootCommandManager = mockk(),
        shizukuManager = mockk(),
        recoveryRepository = mockk(relaxed = true),
        workspaceExecutionContextFactory = mockk(),
        pluginHooks = mockk(relaxed = true)
    )

    @Before
    fun prepare() {
        mockkConstructor(OpenAIAdapter::class)
        coEvery { anyConstructed<OpenAIAdapter>().complete(any(), any(), any(), any()) } throws
            AssertionError("Manual compaction wiring must not make a model request")
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } throws
            AssertionError("Manual compaction wiring must not start a model stream")
        coEvery { sessions.getSessionById(sessionId) } returns null
        coEvery { defaultSettings.getDefaultProviderId() } returns mainConfig.id
        coEvery { defaultSettings.getDefaultModel() } returns mainConfig.effectiveModel
        coEvery { providers.getProviderById(mainConfig.id) } returns mainConfig
        coEvery { providers.getProviderById(summaryConfig.id) } returns summaryConfig
        every { keyRotator.activeKey(any(), any()) } returns "test-key"
        coEvery { metadata.resolve(mainConfig.id, ProviderType.OPENAI, "main-model") } returns
            ModelMetadata("main-model", contextTokens = 64_000, inputTokens = 48_000, outputTokens = 4_096, supportsTools = true)
        coEvery { metadata.resolve(summaryConfig.id, ProviderType.OPENAI, "summary-model") } returns
            ModelMetadata("summary-model", contextTokens = 256_000, outputTokens = 16_384, supportsTools = true)
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery { persistence.buildHistory(sessionId, "__manual_compress__") } returns history
        every { prompts.build(capture(promptContext)) } returns systemPrompt
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CompactionResult(firstArg<List<AgentMessage>>(), true) }
    }

    @After
    fun cleanup() {
        try {
            coVerify(exactly = 0) { anyConstructed<OpenAIAdapter>().complete(any(), any(), any(), any()) }
            verify(exactly = 0) { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(OpenAIAdapter::class)
        }
    }

    private class BudgetTool(override val name: String) : AgentTool() {
        override val description = "Inspect files in the active workspace"
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "Workspace path to inspect")
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            error("Manual compaction must not execute tools")
    }

    @Test
    fun manualCompactionPassesCompleteFixedSystemAndEffectiveToolsToCompactor() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val onEvent: suspend (AgentEvent) -> Unit = { events += it }
        val started = AgentEvent.CompactionStarted(42_000, "manual-attempt")
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            arg<suspend (AgentEvent) -> Unit>(8).invoke(started)
            CompactionResult(firstArg<List<AgentMessage>>(), true)
        }

        assertTrue(workflow.compactSession(sessionId, context, tools, onEvent))

        coVerify(exactly = 1) {
            compactor.compactIfNeeded(
                messages = history, aiProvider = any(), sessionId = sessionId, force = true,
                windowProvider = any(), systemPrompt = systemPrompt, tools = tools,
                currentInputTokens = 0, onEvent = onEvent
            )
        }
        coVerify(exactly = 1) { persistence.buildHistory(sessionId, "__manual_compress__") }
        verify(exactly = 1) { prompts.build(any()) }
        verify { registry wasNot Called }
        assertEquals(listOf(started), events)
    }

    @Test
    fun promptBuildKeepsEditorWorkspaceGoalAndAgentDefinitionWithPersistedHistory() = runBlocking {
        assertTrue(workflow.compactSession(sessionId, context, tools))

        val snapshot = promptContext.captured
        assertEquals(context.copy(history = history), snapshot)
        assertNotSame(context, snapshot)
        assertEquals(context.currentFile, snapshot.currentFile)
        assertEquals(context.selectedCode, snapshot.selectedCode)
        assertEquals(context.language, snapshot.language)
        assertEquals(context.projectRoot, snapshot.projectRoot)
        assertEquals(context.goal, snapshot.goal)
        assertEquals(GoalStatus.ACTIVE, snapshot.goalStatus)
        assertSame(context.agentDefinition, snapshot.agentDefinition)
        assertSame(history, snapshot.history)
        assertEquals("stale", (context.history.single() as AgentMessage.UserMessage).id)
        verify(exactly = 1) { prompts.build(any()) }
    }

    @Test
    fun dedicatedSummaryModelDoesNotReplaceTheMainWindowProvider() = runBlocking {
        coEvery { compactionSettings.getCompactionProviderId() } returns summaryConfig.id
        coEvery { compactionSettings.getCompactionModel() } returns "summary-model"
        val summaryProvider = slot<AIProvider>()
        val windowProvider = slot<AIProvider>()

        assertTrue(workflow.compactSession(sessionId, context, tools))

        coVerify(exactly = 1) {
            compactor.compactIfNeeded(
                messages = history, aiProvider = capture(summaryProvider), sessionId = sessionId, force = true,
                windowProvider = capture(windowProvider), systemPrompt = systemPrompt, tools = tools,
                currentInputTokens = 0, onEvent = any()
            )
        }
        assertNotSame(windowProvider.captured, summaryProvider.captured)
        assertEquals(summaryConfig.id, summaryProvider.captured.providerId)
        assertEquals("summary-model", summaryProvider.captured.model)
        assertEquals(16_384, summaryProvider.captured.maxOutputTokens)
        assertEquals(mainConfig.id, windowProvider.captured.providerId)
        assertEquals("main-model", windowProvider.captured.model)
        assertEquals(4_096, windowProvider.captured.maxOutputTokens)
        coVerify(exactly = 1) { metadata.resolve(mainConfig.id, ProviderType.OPENAI, "main-model") }
        coVerify(exactly = 1) { metadata.resolve(summaryConfig.id, ProviderType.OPENAI, "summary-model") }
    }

    @Test
    fun unconfiguredSummaryModelUsesTheMainProviderForSummaryAndWindow() = runBlocking {
        val summaryProvider = slot<AIProvider>()
        val windowProvider = slot<AIProvider>()

        assertTrue(workflow.compactSession(sessionId, context, tools))

        coVerify(exactly = 1) {
            compactor.compactIfNeeded(
                messages = history, aiProvider = capture(summaryProvider), sessionId = sessionId, force = true,
                windowProvider = capture(windowProvider), systemPrompt = systemPrompt, tools = tools,
                currentInputTokens = 0, onEvent = any()
            )
        }
        assertSame(windowProvider.captured, summaryProvider.captured)
        assertEquals(mainConfig.id, summaryProvider.captured.providerId)
        assertEquals("main-model", summaryProvider.captured.model)
        coVerify(exactly = 1) { metadata.resolve(mainConfig.id, ProviderType.OPENAI, "main-model") }
        coVerify(exactly = 0) { compactionSettings.getCompactionModel() }
        coVerify(exactly = 0) { providers.getProviderById(summaryConfig.id) }
    }

    @Test
    fun historyOfAtMostTwoMessagesSkipsPromptSummarySelectionAndCompactor() = runBlocking {
        for (size in 0..2) {
            coEvery { persistence.buildHistory(sessionId, "__manual_compress__") } returns history.take(size)

            assertFalse("history size $size", workflow.compactSession(sessionId, context, tools))
        }

        coVerify(exactly = 3) { persistence.buildHistory(sessionId, "__manual_compress__") }
        coVerify(exactly = 0) {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { prompts.build(any()) }
        coVerify(exactly = 0) { compactionSettings.getCompactionProviderId() }
        coVerify(exactly = 0) { compactionSettings.getCompactionModel() }
    }

    @Test
    fun manualCompactionReturnsTheCompactorDeclinedResult() = runBlocking {
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CompactionResult(history, false)

        assertFalse(workflow.compactSession(sessionId, context, tools))

        coVerify(exactly = 1) {
            compactor.compactIfNeeded(
                messages = history, aiProvider = any(), sessionId = sessionId, force = true,
                windowProvider = any(), systemPrompt = systemPrompt, tools = tools,
                currentInputTokens = 0, onEvent = any()
            )
        }
    }

    @Test
    fun mismatchedContextSessionIsRejectedBeforeResolvingProvidersOrHistory() = runBlocking {
        val failure = runCatching {
            workflow.compactSession(sessionId, context.copy(sessionId = "other-session"), tools)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        coVerify(exactly = 0) { sessions.getSessionById(any()) }
        coVerify(exactly = 0) { defaultSettings.getDefaultProviderId() }
        coVerify(exactly = 0) { providers.getProviderById(any()) }
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { prompts.build(any()) }
    }
}
