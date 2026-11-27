package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RetryState
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PluginRemoteRunState(
    val running: Boolean = false,
    val assistant: AgentUIMessage? = null,
    val settled: List<AgentUIMessage> = emptyList(),
    val tools: Map<String, RunningToolOutput> = emptyMap(),
    val retry: RetryState? = null,
    val error: String? = null,
    val renderedIds: Set<String> = emptySet()
)

@Singleton
class PluginRemoteRunManager @Inject constructor(
    private val providers: AIProviderRepository,
    private val factory: ProviderInstanceFactory,
    private val registry: PluginSessionRegistry,
    private val gateway: Provider<PluginHostGateway>
) : AutoCloseable {
    private class Run(val owner: PluginRuntimeOwner, val token: String, var touchedAt: Long) { var job: Job? = null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val owners = linkedMapOf<String, Run>()
    private val hasOwners = MutableStateFlow(false)
    private val mutableStates = MutableStateFlow<Map<String, PluginRemoteRunState>>(emptyMap())
    internal val states: StateFlow<Map<String, PluginRemoteRunState>> = mutableStates.asStateFlow()

    init {
        scope.launch {
            hasOwners.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) {
                    delay(1_000)
                    synchronized(lock) {
                        owners.filterValues { runCatching { it.owner.checkCurrent() }.isFailure ||
                            System.currentTimeMillis() - it.touchedAt > 5 * 60_000 }.keys.toList().forEach { id ->
                            owners.remove(id)?.job?.cancel()
                            mutableStates.value -= id
                        }
                        hasOwners.value = owners.isNotEmpty()
                    }
                }
            }
        }
    }

    suspend fun start(context: PluginInvocationContext, session: PluginSessionSummary, prompt: String?, onFinished: suspend () -> Unit = {}) {
        context.checkIntegrity()
        val registered = registry.requireSession(context, session.sourceKey, session.sessionId)
        if (registered != session) throw PluginHostException("CONTEXT_CHANGED", "External session projection changed")
        if (prompt != null && (prompt.isBlank() || prompt.length > 32_000)) PluginConversationJson.invalid("Remote prompt is empty or too long")
        val local = session.providerId ?: throw PluginHostException("REMOTE_PROVIDER_REQUIRED", "External session has no provider")
        val providerId = if (PluginProviderIds.isPluginId(local)) {
            if (PluginProviderIds.pluginIdOf(local) != context.capture.id) throw PluginHostException("PROVIDER_NOT_FOUND", "External provider is not owned by this plugin")
            local
        } else PluginProviderIds.idOf(context.capture.id, local)
        if (context.capture.content.providers.none { it.localId == PluginProviderIds.localIdOf(providerId) }) {
            throw PluginHostException("PROVIDER_NOT_FOUND", "External provider is not declared")
        }
        val config = providers.getProviderById(providerId)?.takeIf { it.isEnabled && it.hasUsableApiKey }
            ?: throw PluginHostException("PROVIDER_NOT_FOUND", "External provider is not available")
        val model = session.model?.takeIf { it.isNotBlank() } ?: config.effectiveModel
        val args = buildJsonObject { put("sourceKey", session.sourceKey); put("sessionId", session.sessionId); put("providerId", providerId)
            put("model", model); put("subscribeOnly", prompt == null); prompt?.let { put("text", it) } }
        val providerIdentity = com.aicode.feature.plugin.data.PluginFiles.sha256(
            com.google.gson.Gson().toJson(config).toByteArray(Charsets.UTF_8))
        gateway.get().authorizeWithSessionIdentity(context, "sessionSource.remoteRun", args, PluginApprovalOperation.EXECUTE_TOOL,
            "Connect to external provider\n$args", JsonPrimitive(providerIdentity))
        registry.requireSession(context, session.sourceKey, session.sessionId)
        if (providers.getProviderById(providerId) != config) throw PluginHostException("CONTEXT_CHANGED", "External provider changed during approval")
        val provider = factory.create(config.copy(selectedModel = model), session.id)
        provider.customHeaders = provider.customHeaders + mapOf(
            "X-AiCode-Session-Id" to session.id,
            "X-AiCode-Operation-Id" to UUID.randomUUID().toString(),
            "X-AiCode-Subscribe-Only" to (prompt == null).toString()
        )
        provider.useResponseApi = false
        val run = Run(PluginRuntimeOwner(context), UUID.randomUUID().toString(), System.currentTimeMillis())
        synchronized(lock) {
            context.checkCurrent()
            if (owners[session.id]?.job?.isActive == true) throw PluginHostException("REMOTE_RUN_BUSY", "External session is already running")
            if (owners.size >= 32 && session.id !in owners) throw PluginHostException("REMOTE_RUN_LIMIT", "Too many external runs")
            owners[session.id] = run
            hasOwners.value = true
            mutableStates.value += session.id to PluginRemoteRunState(running = true)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    context.whileCurrent {
                        var final = false
                        provider.completeStream("", prompt?.let { listOf(AgentMessage.UserMessage(content = it)) }.orEmpty(), emptyList())
                            .takeWhile { chunk ->
                                run.owner.checkCurrent()
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> append(session.id, run, chunk.text, false)
                                    is AIStreamChunk.ReasoningDelta -> append(session.id, run, chunk.text, true)
                                    is AIStreamChunk.Retrying -> update(session.id, run) { it.copy(assistant = null, retry = RetryState(chunk.attempt, chunk.maxRetries, chunk.error)) }
                                    is AIStreamChunk.Final -> {
                                        if (chunk.response.isAborted) throw PluginHostException("REMOTE_TERMINAL_ERROR", "External run terminated")
                                        final = true
                                        if (chunk.response.toolCalls.isNotEmpty()) throw PluginHostException("REMOTE_TOOL_CALLS_NOT_SUPPORTED", "External run cannot execute local tools")
                                    }
                                    is AIStreamChunk.ToolCallDeclared, is AIStreamChunk.ToolCallArgumentsDelta -> throw PluginHostException("REMOTE_TOOL_CALLS_NOT_SUPPORTED", "External run cannot execute local tools")
                                    is AIStreamChunk.KeySwitched -> Unit
                                }
                                chunk !is AIStreamChunk.Final
                            }.collect { }
                        if (!final) throw PluginHostException("REMOTE_INCOMPLETE", "External stream ended without a final response")
                        settle(session.id, run)
                        run.owner.checkCurrent()
                        onFinished()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { update(session.id, run) { it.copy(error = (error as? PluginHostException)?.code ?: "REMOTE_RUN_FAILED") } }
                finally {
                    synchronized(lock) { if (owners[session.id] === run) run.job = null }
                    update(session.id, run) { it.copy(running = false, retry = null, tools = emptyMap()) }
                }
            }
            run.job = job
            job.start()
        }
    }

    fun stop(context: PluginInvocationContext, sessionId: String) {
        synchronized(lock) {
            val run = owners[sessionId] ?: return
            run.owner.requireCaller(context)
            run.job?.cancel()
        }
    }

    internal fun updateTool(context: PluginInvocationContext, sessionId: String, callId: String, tool: RunningToolOutput?) {
        val run = synchronized(lock) { owners[sessionId] } ?: return
        run.owner.requireCaller(context)
        update(sessionId, run) {
            if (it.tools.size >= 64 && callId !in it.tools) throw PluginHostException("REMOTE_TOOL_LIMIT", "Too many external tools")
            it.copy(tools = if (tool == null) it.tools - callId else it.tools + (callId to tool))
        }
    }

    internal fun retainMessages(context: PluginInvocationContext, sessionId: String, renderedIds: Set<String>) {
        if (renderedIds.size > 500) PluginConversationJson.invalid("Too many rendered IDs")
        val run = synchronized(lock) { owners[sessionId] } ?: return
        run.owner.requireCaller(context)
        update(sessionId, run) { it.copy(settled = it.settled.filterNot { message -> message.id in renderedIds }, renderedIds = renderedIds.toSet()) }
    }

    private fun append(id: String, run: Run, text: String, reasoning: Boolean) {
        if (text.isEmpty()) return
        update(id, run) { state ->
            val current = state.assistant ?: AgentUIMessage(id = "remote-${run.token}", role = MessageRole.ASSISTANT, content = "")
            if (current.content.length + current.reasoning.orEmpty().length + text.length > 512 * 1024) throw PluginHostException("REMOTE_BUFFER_OVERFLOW", "External stream exceeds its budget")
            state.copy(retry = null, assistant = if (reasoning) current.copy(reasoning = current.reasoning.orEmpty() + text) else current.copy(content = current.content + text))
        }
    }

    private fun settle(id: String, run: Run) = update(id, run) { state ->
        val assistant = state.assistant ?: return@update state
        val message = assistant.copy(reasoning = assistant.reasoning?.takeIf { it.isNotBlank() })
        state.copy(assistant = null, settled = if (message.id in state.renderedIds) state.settled else (state.settled + message).takeLast(100))
    }

    private fun update(id: String, run: Run, transform: (PluginRemoteRunState) -> PluginRemoteRunState) = synchronized(lock) {
        if (owners[id] !== run) return@synchronized
        run.owner.checkCurrent()
        run.touchedAt = System.currentTimeMillis()
        mutableStates.value += id to transform(mutableStates.value[id] ?: PluginRemoteRunState())
    }

    override fun close() {
        synchronized(lock) { owners.values.forEach { it.job?.cancel() }; owners.clear(); hasOwners.value = false; mutableStates.value = emptyMap() }
        scope.cancel()
    }
}
