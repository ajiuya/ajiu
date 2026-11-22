package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import com.aicode.feature.plugin.domain.session.PluginSessionGroup
import com.aicode.feature.plugin.domain.session.PluginSessionListResult
import com.aicode.feature.plugin.domain.session.PluginSessionPage
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginSessionSourceService @Inject constructor(
    private val plugins: PluginManager,
    private val runtime: PluginRuntimeManager,
    private val registry: PluginSessionRegistry,
    private val capabilities: PluginCapabilityRepository,
    private val gateway: Provider<PluginHostGateway>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    val sessionGroups: StateFlow<List<PluginSessionGroup>> get() = registry.groups

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            plugins.ensureLoaded()
            plugins.snapshot.collectLatest { syncDeclaredSources() }
        }
    }

    fun sourceCount(): Int = plugins.sessionSources().size

    suspend fun syncDeclaredSources() {
        plugins.ensureLoaded()
        val sources = plugins.sessionSources().map { contribution ->
            PluginSessionRegistry.Source(backgroundContext(contribution.capture), contribution.value)
        }
        registry.syncSources(sources)
    }

    suspend fun requestRefresh() = coroutineScope {
        syncDeclaredSources()
        plugins.sessionSources().map { contribution -> async {
            val context = backgroundContext(contribution.capture)
            val definition = contribution.value
            try {
                registry.checkSource(context, definition.key)
                registry.setLoading(context, definition.key, true)
                val handler = definition.handlers.refresh
                if (handler != null) {
                    invoke(context, definition, handler, buildJsonObject { put("sourceId", definition.localId) }, 20_000)
                } else {
                    val response = invoke(context, definition, definition.handlers.listSessions,
                        buildJsonObject { put("sourceId", definition.localId); put("offset", 0); put("limit", 200) })
                    val root = PluginConversationJson.objectValue(response, "listSessions response")
                    registry.setSessions(context, definition.key, PluginSessionCodec.sessions(context, definition, root["sessions"]))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (runCatching { context.checkCurrent() }.isSuccess) registry.setError(context, definition.key, "SESSION_REFRESH_FAILED")
                FileLogger.w("PluginSessionSource", "Session refresh failed: ${definition.key} (${error.javaClass.simpleName})")
            }
        } }.awaitAll()
        Unit
    }

    suspend fun listSessions(context: PluginInvocationContext): PluginSessionListResult {
        context.checkIntegrity()
        val publisher = backgroundContext(context.capture)
        val sessions = context.capture.content.sessionSources.flatMap { definition ->
            registry.ensureSource(publisher, definition)
            val response = invoke(context, definition, definition.handlers.listSessions,
                buildJsonObject { put("sourceId", definition.localId); put("offset", 0); put("limit", 200) })
            val root = PluginConversationJson.objectValue(response, "listSessions response")
            PluginSessionCodec.sessions(publisher, definition, root["sessions"]).also { registry.setSessions(publisher, definition.key, it) }
        }
        return PluginSessionListResult(sessions)
    }

    suspend fun readSession(context: PluginInvocationContext, sourceKey: String, sessionId: String,
        cursor: JsonElement? = null, maxMessages: Int = 50): PluginSessionPage {
        val source = requireSource(context, sourceKey, sessionId)
        if (cursor != null && cursor.toString().length > 4096) PluginConversationJson.invalid("Cursor is too large")
        val response = invoke(context, source, source.handlers.readSession, buildJsonObject {
            put("sourceId", source.localId); put("sessionId", sessionId); cursor?.let { put("cursor", it) }; put("maxMessages", maxMessages.coerceIn(1, 100))
        })
        registry.requireSession(context, sourceKey, sessionId)
        return PluginSessionCodec.page(response, sessionId)
    }

    suspend fun createSession(context: PluginInvocationContext, sourceKey: String, args: JsonObject): JsonElement {
        val source = requireDefinition(context, sourceKey)
        registry.checkSource(context, source.key)
        val handler = source.handlers.createSession ?: throw PluginHostException("SESSION_CREATE_UNSUPPORTED", "Session source cannot create sessions")
        gateway.get().authorize(context, "sessionSource.createSession", args, PluginApprovalOperation.EXECUTE_TOOL, "Create external session\nsource=$sourceKey\n$args")
        return invoke(context, source, handler, JsonObject(args + ("sourceId" to kotlinx.serialization.json.JsonPrimitive(source.localId))))
    }

    suspend fun sendMessage(context: PluginInvocationContext, sourceKey: String, sessionId: String, message: String): JsonElement {
        val source = requireSource(context, sourceKey, sessionId)
        if (message.isBlank() || message.length > 32_000) PluginConversationJson.invalid("External session message is empty or too long")
        val handler = source.handlers.sendMessage ?: throw PluginHostException("SESSION_SEND_UNSUPPORTED", "Session source cannot send messages")
        val args = buildJsonObject { put("sourceId", source.localId); put("sessionId", sessionId); put("message", message); put("operationId", UUID.randomUUID().toString()) }
        gateway.get().authorize(context, "sessionSource.sendMessage", args, PluginApprovalOperation.EXECUTE_TOOL, "Send external session message\nsource=$sourceKey\n$args")
        requireSource(context, sourceKey, sessionId)
        return invoke(context, source, handler, args)
    }

    suspend fun interruptSession(context: PluginInvocationContext, sourceKey: String, sessionId: String) {
        val source = requireSource(context, sourceKey, sessionId)
        val handler = source.handlers.interruptSession ?: throw PluginHostException("SESSION_INTERRUPT_UNSUPPORTED", "Session source cannot interrupt sessions")
        val args = buildJsonObject { put("sourceId", source.localId); put("sessionId", sessionId) }
        gateway.get().authorize(context, "sessionSource.interruptSession", args, PluginApprovalOperation.EXECUTE_TOOL, "Interrupt external session\nsource=$sourceKey\n$args")
        requireSource(context, sourceKey, sessionId)
        invoke(context, source, handler, args)
    }

    suspend fun followSession(context: PluginInvocationContext, sourceKey: String, sessionId: String) {
        val source = requireSource(context, sourceKey, sessionId)
        invoke(context, source, "sessionSource.follow", buildJsonObject { put("sourceId", source.localId); put("sessionId", sessionId) })
    }

    suspend fun unfollowSession(context: PluginInvocationContext, sourceKey: String, sessionId: String) {
        val source = requireSource(context, sourceKey, sessionId)
        invoke(context, source, "sessionSource.unfollow", buildJsonObject { put("sourceId", source.localId); put("sessionId", sessionId) })
    }

    private fun requireDefinition(context: PluginInvocationContext, key: String): PluginSessionSourceDefinition {
        context.checkCurrent()
        return context.capture.content.sessionSources.firstOrNull { it.key == key }
            ?: throw PluginHostException("SESSION_SOURCE_NOT_FOUND", "Session source does not belong to this plugin")
    }

    private fun requireSource(context: PluginInvocationContext, key: String, id: String): PluginSessionSourceDefinition {
        val source = requireDefinition(context, key)
        registry.requireSession(context, key, id)
        return source
    }

    private suspend fun invoke(context: PluginInvocationContext, source: PluginSessionSourceDefinition, handler: String,
        args: JsonObject, timeoutMs: Long = 60_000): JsonElement = context.whileCurrent {
        val process = context.capture.content.runtime ?: throw PluginHostException("PLUGIN_RUNTIME_UNAVAILABLE", "Session source runtime is unavailable")
        if (context.capture.content.sessionSources.none { it == source }) throw PluginHostException("CONTEXT_CHANGED", "Session source changed")
        val publisher = if (context.background) context else backgroundContext(context.capture)
        runtime.invoke(context.capture, process, handler, args, publisher.runtimeContext(),
            { method, params -> gateway.get().call(publisher, method, params) }, method = "ui.invoke", timeoutMs = timeoutMs)
    }

    private suspend fun backgroundContext(capture: com.aicode.feature.plugin.domain.PluginCapture): PluginInvocationContext {
        capabilities.ensureLoaded()
        return gateway.get().captureBackgroundContext(capture,
            PluginCapability.AUTOSTART.takeIf { capabilities.checkGranted(capture, it) })
    }
}
