package com.aicode.feature.agent.domain.workflow

import android.content.Context
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
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
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class ToolPreviewWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val registry = mockk<ToolRegistry>()
    private val providers = mockk<AIProviderRepository>()
    private val prompts = mockk<SystemPromptProvider>()
    private val permissions = mockk<ToolPermissionManager>()
    private val policy = mockk<ToolPermissionPolicyEngine>()
    private val compactor = mockk<ContextCompactor>()
    private val outputStore = mockk<ToolOutputStore>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val files = mockk<FileAccessProvider>()
    private val app = mockk<Context>()
    private val requests = mutableListOf<List<AgentMessage>>()
    private lateinit var workflow: StatefulAgentWorkflow

    @Before
    fun prepare() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        mockkObject(FileLogger)
        every { FileLogger.memoryCheckpoint(any(), any(), any(), any()) } returns Unit
        mockkConstructor(OpenAIAdapter::class)
        every { prompts.build(any()) } returns "system"
        every { app.cacheDir } returns temporary.root
        every { app.getString(any()) } returns "Deferred until plan approval"
        coEvery { defaultSettings.getDefaultProviderId() } returns "provider"
        coEvery { defaultSettings.getDefaultModel() } returns "model"
        coEvery { providers.getProviderById("provider") } returns AIProviderConfig(
            id = "provider", name = "test", type = ProviderType.OPENAI, apiKey = "test-key",
            baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model")
        )
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("model", contextTokens = 100_000, supportsVision = true)
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery { compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompactionResult(firstArg<List<AgentMessage>>(), false)
        }
        coEvery { policy.evaluate(any(), any(), any(), any()) } returns ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW)
        every { outputStore.process(any(), any(), any()) } answers { thirdArg<ToolResult>() }
        val hooks = mockk<com.aicode.feature.plugin.runtime.PluginHookDispatcher>(relaxed = true)
        coEvery { hooks.beforeTool(any(), any(), any()) } answers { com.aicode.feature.plugin.runtime.PluginBeforeTool(thirdArg()) }
        coEvery { hooks.afterTool(any(), any(), any()) } answers { thirdArg() }
        workflow = StatefulAgentWorkflow(
            toolRegistry = registry, aiProviderRepository = providers,
            providerFactory = com.aicode.feature.agent.domain.provider.ProviderInstanceFactory(
                mockk(), mockk(), mockk(), metadata, mockk(relaxed = true),
                mockk { every { activeKey(any(), any()) } returns "test-key" }
            ),
            promptProvider = prompts, permissionManager = permissions, policyEngine = policy, contextCompactor = compactor,
            toolOutputStore = outputStore, modelMetadataService = metadata, compactionModelSettingsRepository = compactionSettings,
            titleModelSettingsRepository = mockk(), defaultModelSettingsRepository = defaultSettings,
            generalSettingsRepository = mockk(relaxed = true),
            sessionUseCase = mockk { coEvery { getSessionById(any()) } returns null }, messagePersistenceUseCase = mockk(),
            checkpointManager = mockk(relaxed = true), llmCallRecordDao = mockk(relaxed = true),
            agentNotificationCenter = AgentNotificationCenter(), eventInjector = mockk<AgentEventInjector>(),
            fileAccess = files, appContext = app, rootCommandManager = mockk(),
            shizukuManager = mockk(),
            recoveryRepository = mockk(relaxed = true),
            pluginHooks = hooks,
            workspaceExecutionContextFactory = mockk {
                coEvery { freeze(any(), any()) } returns WorkspaceExecutionContext(
                    ExecutionMode.LOCAL_PROOT, "preview-test", files, null, null
                ) {}
            }
        )
    }

    @After
    fun cleanup() {
        unmockkConstructor(OpenAIAdapter::class)
        unmockkObject(FileLogger)
        unmockkStatic(SystemClock::class)
    }

    private val image = AgentImage("image/png", Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)), "/original/tool-image.png")

    private open class ImageTool(override val name: String, protected val result: ToolResult) : AgentTool() {
        override val description = "test returned image"
        override val parameters = emptyMap<String, ToolParameter>()
        var executions = 0
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult { executions++; return result }
    }

    private class StreamImageTool(name: String, result: ToolResult) : ImageTool(name, result), StreamingAgentTool {
        override fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent> {
            executions++
            return flowOf(ToolStreamEvent.Completed(result))
        }
    }

    private suspend fun execute(vararg tools: ImageTool): List<AgentEvent.ToolCallFinished> {
        tools.forEach { tool -> every { registry.getTool(tool.name) } returns tool }
        val calls = tools.mapIndexed { index, tool -> ToolCall("call-$index", tool.name, emptyMap()) }
        val responses = ArrayDeque(listOf(AIResponse("", calls), AIResponse("done")))
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers {
            requests += secondArg<List<AgentMessage>>().toList()
            flowOf<AIStreamChunk>(AIStreamChunk.Final(responses.removeFirst()))
        }
        val events = mutableListOf<AgentEvent.ToolCallFinished>()
        workflow.executeEvents("test", AgentContext(null, null, "/project", null, sessionId = "parent"), tools.toList()).collect { event ->
            when (event) {
                is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                is AgentEvent.ToolCallFinished -> { events += event; event.persisted?.complete(Unit) }
                is AgentEvent.Failed -> error(event.error)
                else -> Unit
            }
        }
        return events
    }

    @Test
    fun syncAndStreamImagesGetUiAttachmentsWithoutReplayingCachePathsToModelOrRemote() = runBlocking {
        withTimeout(10_000) {
            val result = ToolResult.Success(JsonPrimitive("already returned"), listOf(image))
            val sync = ImageTool("readFile", result)
            val stream = StreamImageTool("browser", result)
            val finished = execute(sync, stream)
            assertEquals(2, finished.size)
            finished.forEach { event ->
                assertFalse(event.isError)
                val attachment = event.attachments.single()
                assertTrue(ToolPreviewImageCache.contains(File(temporary.root, ToolPreviewImageCache.DIRECTORY), attachment.localPath))
                assertFalse(event.result.contains(ToolPreviewImageCache.DIRECTORY))
                assertEquals(result.toTransportString(), event.result)
            }
            assertEquals(2, finished.flatMap { it.attachments }.map { it.localPath }.toSet().size)
            assertEquals(1, sync.executions)
            assertEquals(1, stream.executions)
            assertEquals(listOf(listOf(image), listOf(image)), requests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.images })
            verify(exactly = 0) { files.readBytes(any()) }
            verify(exactly = 0) { files.openRead(any()) }
            verify(exactly = 0) { files.openRead(any(), any()) }
            verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
        }
    }

    @Test
    fun bothToolBranchesUseTheSameCanonicalTrustedAppCacheParent() = runBlocking {
        withTimeout(10_000) {
            val trusted = temporary.newFolder("actual-cache")
            val alias = File(temporary.root, "cache-alias")
            Files.createSymbolicLink(alias.toPath(), trusted.toPath())
            every { app.cacheDir } returns alias
            val result = ToolResult.Success(JsonPrimitive("success"), listOf(image))
            val finished = execute(ImageTool("viewImage", result), StreamImageTool("browser", result))
            assertEquals(2, finished.size)
            val root = File(trusted, ToolPreviewImageCache.DIRECTORY)
            assertTrue(finished.all { ToolPreviewImageCache.contains(root, it.attachments.single().localPath) })
        }
    }

    @Test
    fun cacheFailureDoesNotTurnEitherSuccessfulToolBranchIntoFailure() = runBlocking {
        withTimeout(10_000) {
            every { app.cacheDir } returns temporary.newFile("blocked-cache")
            val result = ToolResult.Success(JsonPrimitive("success"), listOf(image))
            val finished = execute(ImageTool("viewImage", result), StreamImageTool("browser", result))
            assertEquals(2, finished.size)
            assertTrue(finished.none { it.isError })
            assertTrue(finished.all { it.attachments.isEmpty() })
            assertEquals(listOf(listOf(image), listOf(image)), requests.last().filterIsInstance<AgentMessage.ToolResultMessage>().map { it.images })
        }
    }

    @Test
    fun existingExportAttachmentsAreDeduplicatedAndNotCopiedIntoPrivateCache() = runBlocking {
        withTimeout(10_000) {
            val file = """{"path":"~/workspace/generated.png","local_path":"/local/generated.png","name":"generated.png","mime_type":"image/png","size_bytes":8,"is_image":true}"""
            val data = Json.parseToJsonElement("""{"files":[$file,$file]}""")
            val finished = execute(ImageTool("generateImage", ToolResult.Success(data, listOf(image)))).single()
            assertEquals(1, finished.attachments.size)
            assertEquals("/local/generated.png", finished.attachments.single().localPath)
            assertFalse(File(temporary.root, ToolPreviewImageCache.DIRECTORY).exists())
            assertFalse(finished.result.contains("local_path"))
        }
    }
}
