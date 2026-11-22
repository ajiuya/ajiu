package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginHookDispatcherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val agent = AgentContext(null, null, "/workspace", null, sessionId = "session")
    private val empty = JsonObject(emptyMap())
    @Before fun setup() { mockkObject(FileLogger); every { FileLogger.i(any(), any()) } returns Unit; every { FileLogger.w(any(), any<String>()) } returns Unit }
    @After fun cleanup() { unmockkObject(FileLogger) }
    private inner class Fixture {
        val plugins = PluginManager(File(temporary.newFolder(), "plugins"), File(temporary.newFolder(), "registry.json"))
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        val caps = PluginCapabilityRepository(File(temporary.newFolder(), "caps.enc"), plugins, { it }, { it })
        val gateway = mockk<PluginHostGateway>()
        val dispatcher = PluginHookDispatcher(plugins, caps, runtime, Provider { gateway })
        val contexts = mutableMapOf<String, PluginInvocationContext>()
        init {
            coEvery { gateway.captureContext(any(), any(), any(), any()) } answers { invocation(firstArg()) }
            coEvery { gateway.captureBackgroundContext(any(), any()) } answers { invocation(firstArg()) }
        }
        fun invocation(capture: PluginCapture) = contexts.getOrPut("${capture.id}:${capture.activationEpoch}") {
            PluginInvocationContext(capture, null, "/workspace", "session", plugins.dataDirectory(capture.id), flowOf(Unit)) {}
        }
        suspend fun install(events: List<Pair<String, String>>): PluginCapture {
            val hooks = events.mapIndexed { index, (event, mode) -> """{"id":"h$index","event":"$event","mode":"$mode","handler":"h$index"}""" }.joinToString(",")
            val extra = if (events.any { it.first in PluginHookEvents.BACKUP }) ",\"backup\":{\"purpose\":\"Export own data\"}" else ""
            val manifest = PluginCoreFixtures.manifest("\"hooks\":[$hooks]$extra", """{"type":"javascript","entry":"main.js","resident":true}""")
            PluginCoreFixtures.install(plugins, PluginCoreFixtures.archive(manifest, listOf("main.js" to "// static fixture".toByteArray())))
            return PluginCoreFixtures.activate(plugins)
        }
        fun answer(process: PluginFakeProcess, message: JsonObject, result: JsonElement) = process.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", requireNotNull(message["id"])); put("result", result)
        })
        suspend fun close() { dispatcher.stop(); runtime.close(); withTimeout(5000) { runtime.awaitClosed() } }
    }
    @Test fun writableBeforeRunsThroughRealRuntimeAndNdjsonTransport() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.install(listOf(PluginHookEvents.AGENT_TOOL_BEFORE to PluginHookModes.TRANSFORM))
            val result = async { f.dispatcher.beforeTool(agent, ToolCall("call", "Bash", emptyMap()), empty) }
            val process = withTimeout(5000) { f.factory.started.receive() }
            val message = process.message()
            assertEquals(JsonPrimitive("hook.invoke"), message["method"])
            val params = message["params"] as JsonObject
            val payload = params["args"] as JsonObject
            assertEquals(JsonPrimitive("agent.tool.before"), payload["event"])
            assertEquals(JsonPrimitive("Bash"), payload["toolName"])
            f.answer(process, message, buildJsonObject { put("arguments", buildJsonObject { put("command", "pwd") }) })
            assertEquals(JsonPrimitive("pwd"), result.await().arguments["command"])
        } finally { f.close() }
    }
    @Test fun delegationUsesSdkStartNextProtocolBoundToFinalArgsAndCannotReplay() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.install(listOf(PluginHookEvents.AGENT_TOOL_BEFORE to PluginHookModes.TRANSFORM))
            val original = ToolCall("call", "Bash", mapOf("command" to JsonPrimitive("old")))
            val before = async { f.dispatcher.beforeTool(agent, original, JsonObject(original.arguments)) }
            val process = withTimeout(5000) { f.factory.started.receive() }
            f.answer(process, process.message(), buildJsonObject {
                put("arguments", buildJsonObject { put("command", "pwd") }); put("delegate", buildJsonObject { put("handler", "delegate") })
            })
            val mutation = before.await()
            val delegation = requireNotNull(mutation.delegation)
            val finalCall = original.copy(arguments = mutation.arguments)
            val progress = mutableListOf<String>()
            val execution = async { f.dispatcher.executeDelegated(delegation, finalCall, agent.inputMessageId) { progress += it } }
            val start = process.message()
            val args = (start["params"] as JsonObject)["args"] as JsonObject
            assertEquals(JsonPrimitive("tool.invoke"), start["method"])
            assertEquals(JsonPrimitive("start"), args["operation"])
            assertEquals(mutation.arguments, args["arguments"])
            assertEquals(JsonPrimitive(agent.inputMessageId), args["runId"])
            val executionId = requireNotNull(args["executionId"])
            f.answer(process, start, buildJsonObject { put("executionId", executionId); put("type", "progress"); put("chunk", "working") })
            val next = process.message()
            assertEquals(JsonPrimitive("next"), ((next["params"] as JsonObject)["args"] as JsonObject)["operation"])
            f.answer(process, next, buildJsonObject { put("executionId", executionId); put("type", "completed"); put("data", "done") })
            assertEquals(ToolResult.Success(JsonPrimitive("done")), execution.await())
            assertEquals(listOf("working"), progress)
            assertEquals("PLUGIN_DELEGATE_MISMATCH", (f.dispatcher.executeDelegated(delegation, finalCall, agent.inputMessageId) as ToolResult.Error).code)
        } finally { f.close() }
    }
    @Test fun afterOnlyChangesSuccessDataAndPreservesOriginalImagesAndFailure() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.install(listOf(PluginHookEvents.AGENT_TOOL_AFTER to PluginHookModes.TRANSFORM))
            val image = com.aicode.feature.agent.domain.model.AgentImage("image/png", "AA==")
            val original = ToolResult.Success(JsonPrimitive("old"), listOf(image))
            val after = async { f.dispatcher.afterTool(agent, ToolCall("call", "readFile", emptyMap()), original) }
            val process = withTimeout(5000) { f.factory.started.receive() }
            val message = process.message()
            val payload = (message["params"] as JsonObject)["args"] as JsonObject
            assertEquals(JsonPrimitive("success"), payload["status"])
            f.answer(process, message, buildJsonObject { put("data", "new") })
            val changed = after.await() as ToolResult.Success
            assertEquals(JsonPrimitive("new"), changed.data)
            assertEquals(listOf(image), changed.images)
            val failure = ToolResult.Error("real failure", "REAL_FAILURE")
            assertSame(failure, f.dispatcher.afterTool(agent, ToolCall("call2", "readFile", emptyMap()), failure))
        } finally { f.close() }
    }
    @Test fun protectedToolsCannotBeTransformedAndShizukuCanTransformButNotDelegate() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.install(listOf(PluginHookEvents.AGENT_TOOL_BEFORE to PluginHookModes.TRANSFORM))
            for (tool in listOf("Root", "submitPlan", "askUserQuestion")) {
                val result = f.dispatcher.beforeTool(agent, ToolCall("call", tool, emptyMap()), empty)
                assertEquals(empty, result.arguments)
                assertNull(result.delegation)
            }
            assertTrue(f.factory.processes.isEmpty())
            val shizuku = async { f.dispatcher.beforeTool(agent, ToolCall("call", "Shizuku", emptyMap()), empty) }
            val process = withTimeout(5000) { f.factory.started.receive() }
            f.answer(process, process.message(), buildJsonObject { put("arguments", buildJsonObject { put("command", "id") }); put("delegate", buildJsonObject { put("handler", "run") }) })
            val mutation = shizuku.await()
            assertEquals(JsonPrimitive("id"), mutation.arguments["command"])
            assertNull(mutation.delegation)
        } finally { f.close() }
    }
    @Test fun revokedHookResponseCannotMutateFinalArguments() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.install(listOf(PluginHookEvents.AGENT_TOOL_BEFORE to PluginHookModes.TRANSFORM))
            val before = async { f.dispatcher.beforeTool(agent, ToolCall("call", "Bash", emptyMap()), empty) }
            val process = withTimeout(5000) { f.factory.started.receive() }
            process.message()
            f.plugins.disable(capture.id)
            assertEquals(empty, before.await().arguments)
        } finally { f.close() }
    }
    @Test fun autostartRunsOnlyAfterNativeCapabilityGrantAndUsesExplicitBackgroundCapability() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.install(listOf(PluginHookEvents.APP_STARTED to PluginHookModes.OBSERVE))
            f.dispatcher.start()
            f.caps.ensureLoaded()
            assertTrue(f.factory.processes.isEmpty())
            val challenge = requireNotNull(f.caps.prepareGrant(capture, PluginCapability.AUTOSTART))
            assertEquals(PluginOperationResult.Success, f.caps.confirmGrant(challenge.nativeToken))
            val process = withTimeout(5000) { f.factory.started.receive() }
            val message = process.message()
            assertEquals(JsonPrimitive("app.started"), (((message["params"] as JsonObject)["args"] as JsonObject)["event"]))
            io.mockk.coVerify { f.gateway.captureBackgroundContext(any(), PluginCapability.AUTOSTART) }
            f.answer(process, message, empty)
        } finally { f.close() }
    }
}
