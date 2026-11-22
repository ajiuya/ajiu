package com.aicode.feature.plugin.runtime

import android.app.Application
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
class PluginModelServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: PluginConversationFixture
    private lateinit var service: PluginModelService
    private lateinit var context: PluginInvocationContext
    private val providers = mockk<AIProviderRepository>()
    private val gateway = mockk<PluginHostGateway>()
    private val paidApprovals = AtomicInteger()
    private var allow = true
    private var stream: Flow<AIStreamChunk> = flowOf(AIStreamChunk.TextDelta("hello"), AIStreamChunk.Final(AIResponse("hello")))
    private val config = AIProviderConfig(id = "provider", name = "Provider", type = ProviderType.OPENAI,
        apiKey = "private-key", baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model"))

    @Before fun setup() = runBlocking<Unit> {
        fixture = PluginConversationFixture(temporary.root)
        fixture.install()
        context = fixture.context()
        coEvery { providers.getProviderById("provider") } returns config
        coEvery { gateway.requireCapability(any(), PluginCapability.MODEL_ACCESS) } coAnswers {
            val caller = firstArg<PluginInvocationContext>()
            fixture.capabilities.ensureLoaded()
            if (!fixture.capabilities.checkGranted(caller.capture, PluginCapability.MODEL_ACCESS)) throw PluginHostException("CAPABILITY_REQUIRED", "Native grant required")
        }
        coEvery { gateway.authorize(any(), "model.start", any(), PluginApprovalOperation.EXECUTE_TOOL, any(), any()) } coAnswers {
            firstArg<PluginInvocationContext>().checkCurrent()
            paidApprovals.incrementAndGet()
            if (!allow) throw PluginHostException("USER_REJECTED", "Rejected")
        }
        mockkConstructor(OpenAIAdapter::class)
        every { anyConstructed<OpenAIAdapter>().completeStream(any(), any(), any(), any()) } answers { stream }
        service = PluginModelService(providers, fixture.factory, fixture.metadata, mockk(), ToolRegistry(), fixture.capabilities,
            Provider { gateway }, CoroutineScope(SupervisorJob() + Dispatchers.Default), System::currentTimeMillis)
    }

    @After fun close() { if (::service.isInitialized) service.close(); unmockkConstructor(OpenAIAdapter::class) }

    private fun createArgs() = buildJsonObject { put("providerId", "provider"); put("model", "model") }
    private suspend fun handle(): String {
        fixture.grantModel()
        return service.create(context, createArgs()).jsonObject.getValue("handle").jsonObject.getValue("id").jsonPrimitive.content
    }
    private fun startArgs(handle: String) = buildJsonObject {
        put("handleId", handle); put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "Hello") })))
    }
    private suspend fun start(handle: String) = service.start(context, startArgs(handle)).jsonObject.getValue("streamId").jsonPrimitive.content
    private suspend fun read(id: String, cursor: Int = 0) = service.read(context, buildJsonObject {
        put("streamId", id); put("cursor", cursor); put("waitMs", 2000)
    }).jsonObject
    private suspend fun terminal(id: String): JsonObject {
        var cursor = 0
        return withTimeout(5000) {
            while (true) {
                val result = read(id, cursor)
                cursor = result.getValue("cursor").jsonPrimitive.content.toInt()
                if (result.getValue("done").jsonPrimitive.content == "true") return@withTimeout result
            }
            @Suppress("UNREACHABLE_CODE") JsonObject(emptyMap())
        }
    }
    private suspend fun error(block: suspend () -> Any?): String = try { block(); throw AssertionError("Expected denial") }
        catch (failure: PluginHostException) { failure.code }

    @Test fun modelDeclarationIsNotANativeGrant() = runBlocking<Unit> {
        assertEquals("CAPABILITY_REQUIRED", error { service.create(context, createArgs()) })
        assertEquals(0, paidApprovals.get())
    }

    @Test fun eachPaidStartRequiresNativeApprovalAndNeverReturnsCredentials() = runBlocking<Unit> {
        val handle = handle()
        val first = terminal(start(handle))
        assertEquals("success", first.getValue("status").jsonPrimitive.content)
        assertEquals("hello", first.getValue("final").jsonObject.getValue("content").jsonPrimitive.content)
        allow = false
        assertEquals("USER_REJECTED", error { start(handle) })
        assertEquals(2, paidApprovals.get())
        assertFalse(first.toString().contains(config.apiKey))
    }

    @Test fun overBudgetRequestIsRejectedBeforePaidApproval() = runBlocking<Unit> {
        val handle = handle()
        val args = buildJsonObject {
            put("handleId", handle); put("messages", JsonArray(List(4) {
                buildJsonObject { put("role", "user"); put("content", "x".repeat(120_000)) }
            }))
        }
        assertEquals("CONTEXT_TOO_LARGE", error { service.start(context, args) })
        assertEquals(0, paidApprovals.get())
    }

    @Test fun handleCannotCrossCallerSessionOrWorkspace() = runBlocking<Unit> {
        val handle = handle()
        assertEquals("RESOURCE_NOT_FOUND", error { service.start(fixture.context(session = "chat-B"), startArgs(handle)) })
        assertEquals("RESOURCE_NOT_FOUND", error { service.start(fixture.context(workspace = "/other"), startArgs(handle)) })
        assertEquals(0, paidApprovals.get())
    }

    @Test fun sameSourceNewInvocationCanPollButCursorCannotReplayDeliveredChunks() = runBlocking<Unit> {
        val handle = handle()
        val id = start(handle)
        val result = service.read(fixture.context(), buildJsonObject { put("streamId", id); put("waitMs", 2000) }).jsonObject
        assertTrue(result.getValue("events").jsonArray.isNotEmpty())
        assertEquals("CURSOR_INVALID", error { read(id, 0) })
    }

    @Test fun terminalProviderErrorClearsTheHandleAndIsNotReportedAsSuccess() = runBlocking<Unit> {
        stream = flowOf(AIStreamChunk.Final(AIResponse("partial", stopReason = "refusal")))
        val handle = handle()
        val result = terminal(start(handle))
        assertEquals("error", result.getValue("status").jsonPrimitive.content)
        assertEquals("MODEL_TERMINAL_ERROR", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("HANDLE_NOT_FOUND", error { start(handle) })
    }

    @Test fun missingFinalIsAnErrorAndDoesNotFabricateCompletion() = runBlocking<Unit> {
        stream = flowOf(AIStreamChunk.TextDelta("partial"))
        val handle = handle()
        val result = terminal(start(handle))
        assertEquals("MODEL_INCOMPLETE", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun cancellationStopsTheProviderAndPreservesCancelledTerminalStatus() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        stream = flow { try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } }
        val handle = handle()
        val id = start(handle)
        withTimeout(5000) { started.await() }
        service.cancel(context, buildJsonObject { put("streamId", id) })
        withTimeout(5000) { cancelled.await() }
        assertEquals("cancelled", terminal(id).getValue("status").jsonPrimitive.content)
    }

    @Test fun unreadBufferOverflowCancelsAndReleasesHandle() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        stream = flow {
            try { repeat(2048) { emit(AIStreamChunk.TextDelta("x".repeat(16 * 1024))) } }
            finally { cancelled.complete(Unit) }
        }
        val handle = handle()
        val id = start(handle)
        withTimeout(5000) { cancelled.await() }
        assertEquals("STREAM_BUFFER_OVERFLOW", terminal(id).getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("HANDLE_NOT_FOUND", error { start(handle) })
    }

    @Test fun disableEnableCannotRevivePreviousHandleOrGrant() = runBlocking<Unit> {
        val handle = handle()
        fixture.manager.disable(fixture.capture.id)
        fixture.capture = PluginCoreFixtures.activate(fixture.manager)
        context = fixture.context()
        assertEquals("CAPABILITY_REQUIRED", error { start(handle) })
        fixture.grantModel()
        assertEquals("HANDLE_NOT_FOUND", error { start(handle) })
    }

    @Test fun closeRaceDoesNotLeaveProviderStreamRunning() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        stream = flow { try { entered.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) } }
        val handle = handle()
        start(handle)
        withTimeout(5000) { entered.await() }
        coroutineScope { List(8) { async(Dispatchers.Default) { runCatching { service.close(context, buildJsonObject { put("handleId", handle) }) } } }.awaitAll() }
        withTimeout(5000) { stopped.await() }
        assertEquals("HANDLE_NOT_FOUND", error { start(handle) })
    }

    @Test fun revokedSourceRejectsLateReadInsteadOfMovingToAnotherWorkspace() = runBlocking<Unit> {
        val id = start(handle())
        fixture.current = false
        fixture.changes.emit(Unit)
        try { read(id); fail("Expected stale source cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
