package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginLifecycleEvent
import com.aicode.feature.plugin.domain.PluginManager
import java.util.UUID
import java.security.MessageDigest
import com.aicode.feature.plugin.domain.PluginCapture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class PluginToolDelegation(
    val contribution: PluginContribution<PluginHook>,
    val context: PluginInvocationContext,
    val handler: String,
    val runId: String,
    val callId: String,
    val toolName: String,
    val argumentsHash: String,
    val executionId: String = UUID.randomUUID().toString(),
    val consumed: AtomicBoolean = AtomicBoolean()
)
internal data class PluginBeforeTool(val arguments: JsonObject, val denied: Boolean = false, val reason: String? = null, val delegation: PluginToolDelegation? = null)

@Singleton
class PluginHookDispatcher @Inject constructor(
    private val plugins: PluginManager,
    private val capabilities: PluginCapabilityRepository,
    private val runtime: PluginRuntimeManager,
    private val gateway: Provider<PluginHostGateway>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val events = Channel<suspend () -> Unit>(64)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        plugins.registerLifecycleSink { lifecycle(it) }
        scope.launch { for (event in events) try { event() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            FileLogger.w("PluginHooks", "Plugin observer failed: ${failure.javaClass.simpleName}")
        } }
        scope.launch {
            plugins.ensureLoaded(); capabilities.ensureLoaded()
            var delivered = emptySet<String>()
            kotlinx.coroutines.flow.combine(plugins.snapshot, capabilities.grants) { _, _ -> Unit }.collect {
                val hooks = plugins.hooks(PluginHookEvents.APP_STARTED)
                val active = hooks.filter { capabilities.checkGranted(it.capture, PluginCapability.AUTOSTART) }
                val identities = active.associateBy { "${it.value.key}:${it.capture.installRevision}:${it.capture.activationEpoch}" }
                for ((identity, hook) in identities) if (identity !in delivered) {
                    enqueue { invoke(hook, gateway.get().captureBackgroundContext(hook.capture, PluginCapability.AUTOSTART), buildJsonObject {
                        put("event", PluginHookEvents.APP_STARTED); put("launchId", UUID.randomUUID().toString()); put("startedAt", System.currentTimeMillis())
                    }) }
                }
                delivered = identities.keys
            }
        }
    }

    internal fun stop() { events.close(); scope.cancel() }

    private suspend fun lifecycle(event: PluginLifecycleEvent) {
        val capture = event.capture
        val hooks = capture.content.hooks.filter { it.event == event.event && it.mode == PluginHookModes.OBSERVE }
        try {
            if (hooks.isEmpty()) return
            val context = gateway.get().captureBackgroundContext(capture)
            for (hook in hooks) invoke(PluginContribution(capture, hook), context, buildJsonObject {
                put("event", event.event); put("pluginId", capture.id); put("version", capture.manifest.version.toString())
            })
        } finally { if (capture.cleanup) runtime.closeCapture(capture) }
    }

    internal fun observe(event: String, context: AgentContext, details: JsonObject = JsonObject(emptyMap())) {
        val hooks = plugins.hooks(event).filter { it.value.mode == PluginHookModes.OBSERVE }
        for (hook in hooks) enqueue {
            val invocation = gateway.get().captureContext(hook.capture, context.sessionId, context.projectRoot, context)
            invoke(hook, invocation, payload(event, context, details))
        }
    }

    internal suspend fun beforeTool(context: AgentContext, call: ToolCall, original: JsonObject): PluginBeforeTool {
        if (call.name in PROTECTED_TOOLS) return PluginBeforeTool(original)
        var arguments = original
        val accepted = mutableListOf<PluginInvocationContext>()
        var delegated: Pair<PluginContribution<PluginHook>, Pair<PluginInvocationContext, String>>? = null
        val deadline = System.nanoTime() + 3_000_000_000L
        for (hook in plugins.hooks(PluginHookEvents.AGENT_TOOL_BEFORE).filter { it.value.mode == PluginHookModes.TRANSFORM }) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            val invocation = foreground(hook, context) ?: continue
            val result = invoke(hook, invocation, payload(PluginHookEvents.AGENT_TOOL_BEFORE, context, buildJsonObject {
                put("callId", call.id); put("toolName", call.name); put("arguments", arguments)
            }), timeoutMs = remaining.coerceAtMost(1500)) ?: continue
            val change = PluginHookTransform.parseBefore(result) ?: continue
            if (change.denied) return PluginBeforeTool(arguments, true, change.denyReason ?: "Plugin rejected this tool call")
            change.arguments?.let { arguments = it }
            accepted += invocation
            if (delegated == null && call.name !in NO_DELEGATION) change.delegate?.let { delegated = hook to (invocation to it) }
        }
        if (accepted.any { runCatching { it.checkIntegrity() }.isFailure }) return PluginBeforeTool(original)
        val delegation = delegated?.let { (hook, target) -> PluginToolDelegation(hook, target.first, target.second,
            context.recoveryRunId ?: context.inputMessageId, call.id, call.name, argumentsHash(arguments)) }
        return PluginBeforeTool(arguments, delegation = delegation)
    }

    internal suspend fun executeDelegated(
        delegation: PluginToolDelegation,
        call: ToolCall,
        runId: String,
        onProgress: suspend (String) -> Unit = {}
    ): ToolResult {
        if (call.name in NO_DELEGATION) return ToolResult.Error("This tool cannot be delegated", "SYSTEM_PROTECTED")
        if (delegation.runId != runId || delegation.callId != call.id || delegation.toolName != call.name ||
            delegation.argumentsHash != argumentsHash(JsonObject(call.arguments)) || !delegation.consumed.compareAndSet(false, true)) {
            return ToolResult.Error("Tool delegate identity changed", "PLUGIN_DELEGATE_MISMATCH")
        }
        val context = delegation.context
        suspend fun call(operation: String): JsonObject = context.whileCurrent {
            runtime.invoke(context.capture, delegation.contribution.value.runtime, delegation.handler, buildJsonObject {
                put("operation", operation); put("executionId", delegation.executionId); put("runId", runId)
                put("callId", call.id); put("toolName", call.name)
                if (operation == "start") put("arguments", JsonObject(call.arguments))
            }, context.runtimeContext(), { method, params -> gateway.get().call(context, method, params) },
                method = "tool.invoke", timeoutMs = if (operation == "cancel") 1500 else 35_000)
                as? JsonObject ?: throw PluginProcessException("PLUGIN_DELEGATE_PROTOCOL", "Invalid delegate response")
        }
        var completed = false
        try {
            return withTimeoutOrNull(30 * 60_000L) {
                var response = call("start")
                var result: ToolResult? = null
                while (result == null) {
                    if (response["executionId"] != JsonPrimitive(delegation.executionId)) {
                        throw PluginProcessException("PLUGIN_DELEGATE_PROTOCOL", "Mismatched delegate execution")
                    }
                    when ((response["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "pending" -> delay(100)
                        "progress" -> {
                            val chunk = (response["chunk"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 64 * 1024 }?.content
                                ?: throw PluginProcessException("PLUGIN_DELEGATE_PROTOCOL", "Invalid delegate progress")
                            onProgress(chunk)
                        }
                        "completed" -> { completed = true; result = ToolResult.Success(response["data"] ?: JsonNull) }
                        "error" -> { completed = true; result = ToolResult.Error(
                            (response["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(500) ?: "Delegate failed", "PLUGIN_DELEGATE_FAILED") }
                        else -> throw PluginProcessException("PLUGIN_DELEGATE_PROTOCOL", "Invalid delegate event")
                    }
                    if (result == null) response = call("next")
                }
                result
            } ?: ToolResult.Error("Tool delegate timed out", "PLUGIN_DELEGATE_TIMEOUT")
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { return ToolResult.Error("Tool delegate failed", (failure as? PluginProcessException)?.code ?: "PLUGIN_DELEGATE_FAILED") }
        finally {
            if (!completed) withContext(NonCancellable) { withTimeoutOrNull(1500) { runCatching { call("cancel") } } }
        }
    }

    internal suspend fun afterTool(context: AgentContext, call: ToolCall, result: ToolResult): ToolResult {
        if (call.name in PROTECTED_TOOLS || result !is ToolResult.Success) return result
        var data = result.data
        val accepted = mutableListOf<PluginInvocationContext>()
        val deadline = System.nanoTime() + 3_000_000_000L
        for (hook in plugins.hooks(PluginHookEvents.AGENT_TOOL_AFTER).filter { it.value.mode == PluginHookModes.TRANSFORM }) {
            val invocation = foreground(hook, context) ?: continue
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            val transformed = invoke(hook, invocation, payload(PluginHookEvents.AGENT_TOOL_AFTER, context, buildJsonObject {
                put("callId", call.id); put("toolName", call.name); put("status", "success"); put("arguments", JsonObject(call.arguments)); put("data", data)
            }), timeoutMs = remaining.coerceAtMost(1500)) ?: continue
            PluginHookTransform.parseAfter(transformed)?.let { data = it; accepted += invocation }
        }
        if (accepted.any { runCatching { it.checkIntegrity() }.isFailure }) return result
        return result.copy(data = data)
    }

    internal fun hasHttpHooks(): Boolean = plugins.hooks(PluginHookEvents.AGENT_HTTP_BEFORE).any {
        it.value.mode == PluginHookModes.TRANSFORM && capabilities.checkGranted(it.capture, PluginCapability.HTTP_TRANSFORM)
    }

    internal suspend fun beforeHttpRequest(url: String, method: String, headers: Map<String, String>, body: String): HttpRequestHookResult {
        var currentUrl = url
        var currentHeaders = headers
        var currentBody = body
        var changed = false
        val sources = mutableListOf<PluginInvocationContext>()
        val result = withTimeoutOrNull(2000) {
            for (hook in plugins.hooks(PluginHookEvents.AGENT_HTTP_BEFORE).filter { it.value.mode == PluginHookModes.TRANSFORM }) {
                if (!capabilities.checkGranted(hook.capture, PluginCapability.HTTP_TRANSFORM)) continue
                val invocation = gateway.get().captureBackgroundContext(hook.capture, PluginCapability.HTTP_TRANSFORM)
                val returned = invoke(hook, invocation, buildJsonObject {
                    put("event", PluginHookEvents.AGENT_HTTP_BEFORE); put("url", currentUrl); put("method", method)
                    put("headers", JsonObject(PluginHttpHeaders.redact(currentHeaders).mapValues { JsonPrimitive(it.value) })); put("body", currentBody)
                }, secretHeaders = headers) ?: continue
                if (!capabilities.checkGranted(hook.capture, PluginCapability.HTTP_TRANSFORM)) throw CancellationException("HTTP hook permission revoked")
                val transform = PluginHookTransform.parseHttp(returned) ?: continue
                val nextUrl = transform.url ?: currentUrl
                val parsed = nextUrl.toHttpUrlOrNull()
                if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) continue
                currentHeaders = PluginHttpHeaders.merge(url, currentHeaders, transform.headers.orEmpty(), nextUrl)
                currentUrl = nextUrl; transform.body?.let { currentBody = it }; changed = true
                sources += invocation
            }
            if (changed) HttpRequestHookResult(currentUrl, currentHeaders, currentBody) {
                sources.forEach { source ->
                    source.checkIntegrity()
                    if (!capabilities.checkGranted(source.capture, PluginCapability.HTTP_TRANSFORM)) throw CancellationException("HTTP hook permission revoked")
                }
            } else HttpRequestHookResult()
        }
        return result ?: HttpRequestHookResult()
    }

    private suspend fun invoke(hook: PluginContribution<PluginHook>, context: PluginInvocationContext, payload: JsonObject,
        timeoutMs: Long = 1500, secretHeaders: Map<String, String> = emptyMap()): JsonElement? {
        return try {
            context.whileCurrent {
                withTimeoutOrNull(timeoutMs) { runtime.invoke(context.capture, hook.value.runtime, hook.value.handler, payload,
                    context.runtimeContext(), { method, params -> gateway.get().call(context, method, params) }, method = "hook.invoke", timeoutMs = timeoutMs) }
            }?.also { returned ->
                PluginHookTransform.logs(returned).forEach { line -> FileLogger.i("plugin:${hook.capture.id}/${hook.value.localId}", PluginHttpHeaders.redactText(line, secretHeaders)) }
            }
        } catch (failure: CancellationException) { currentCoroutineContext().ensureActive(); null }
        catch (failure: Exception) { FileLogger.w("PluginHooks", "Plugin hook failed: ${failure.javaClass.simpleName}"); null }
    }

    private suspend fun foreground(hook: PluginContribution<PluginHook>, context: AgentContext): PluginInvocationContext? = try {
        gateway.get().captureContext(hook.capture, context.sessionId, context.projectRoot, context)
    } catch (_: Exception) { currentCoroutineContext().ensureActive(); null }

    private fun enqueue(event: suspend () -> Unit) {
        if (events.trySend(event).isFailure) FileLogger.w("PluginHooks", "Plugin observer queue is full")
    }
    private fun payload(event: String, context: AgentContext, details: JsonObject) = buildJsonObject {
        put("event", event); put("runId", context.recoveryRunId ?: context.inputMessageId)
        context.sessionId?.let { put("sessionId", it) }; put("scope", if (context.agentDefinition == null) "main" else "subagent")
        context.agentDefinition?.let { put("agentName", it.name) }; details.forEach { (key, value) -> put(key, value) }
    }
    private fun argumentsHash(arguments: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(arguments.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        val PROTECTED_TOOLS = setOf("Root", "submitPlan", "askUserQuestion")
        val NO_DELEGATION = PROTECTED_TOOLS + "Shizuku"
    }
}
