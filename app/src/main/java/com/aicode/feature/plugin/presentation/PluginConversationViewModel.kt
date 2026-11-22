package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import com.aicode.feature.plugin.domain.session.PluginSessionPage
import com.aicode.feature.plugin.domain.session.PluginSessionMessage
import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.runtime.PluginChatUiRequests
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.plugin.runtime.PluginRemoteRunManager
import com.aicode.feature.plugin.runtime.PluginSessionCodec
import com.aicode.feature.plugin.runtime.PluginSessionEventBus
import com.aicode.feature.plugin.runtime.PluginSessionRegistry
import com.aicode.feature.plugin.runtime.PluginSessionSourceService
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class PluginConversationState(val selected: PluginSessionSummary? = null, val page: PluginSessionPage? = null, val draft: String = "", val loading: Boolean = false, val problem: PluginProblem? = null, val tools: Map<String, PluginMessageBlock.Tool> = emptyMap())

@HiltViewModel
class PluginConversationViewModel @Inject constructor(
    private val manager: PluginManager,
    private val gateway: PluginHostGateway,
    private val service: PluginSessionSourceService,
    private val registry: PluginSessionRegistry,
    private val eventBus: PluginSessionEventBus,
    private val remote: PluginRemoteRunManager,
    private val workspaces: WorkspaceRepository,
    val requests: PluginChatUiRequests
) : ViewModel() {
    val groups = registry.groups
    val thinking = registry.thinking
    internal val runStates = remote.states
    private val mutableState = MutableStateFlow(PluginConversationState())
    internal val state = mutableState.asStateFlow()
    private var selectedContext: PluginInvocationContext? = null
    private var selectedPublisher: PluginInvocationContext? = null
    private var selectionRevision = 0L
    private var loadJob: Job? = null
    init {
        service.start()
        viewModelScope.launch { registry.groups.collect { groups ->
            val selected = state.value.selected ?: return@collect
            if (groups.flatMap { it.sessions }.none { it.id == selected.id }) close()
        } }
        viewModelScope.launch { workspaces.selection.collect { close() } }
        viewModelScope.launch(Dispatchers.IO) {
            eventBus.events.collect { event ->
                val selected = state.value.selected ?: return@collect
                val context = selectedContext ?: return@collect
                val publisher = selectedPublisher ?: return@collect
                val eventRevision = selectionRevision
                if (event.sessionId != selected.id || event.sourceIdentity != publisher.sourceIdentity || event.pluginId != context.capture.id || event.installRevision != context.capture.installRevision || event.contentHash != context.capture.contentHash || event.activationEpoch != context.capture.activationEpoch) return@collect
                try {
                    event.checkCurrent(); context.checkCurrent(); publisher.checkCurrent()
                    if (eventRevision != selectionRevision || selectedContext !== context) return@collect
                    val root = event.payload
                    when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
                        "snapshot" -> mutableState.update { current -> if (current.selected?.id == selected.id) current.copy(page = PluginSessionCodec.page(root["page"]!!, selected.sessionId), tools = emptyMap()) else current }
                        "message" -> {
                            val message = PluginSessionCodec.message(root["message"]!!)
                            mutableState.update { current -> if (current.selected?.id != selected.id) current else current.copy(page = current.page?.copy(messages = (current.page.messages.filterNot { it.id == message.id } + message).takeLast(500))) }
                        }
                        "tool-start", "tool-output", "tool-end" -> {
                            val id = (root["callId"] as JsonPrimitive).content
                            val name = (root["name"] as? JsonPrimitive)?.contentOrNull ?: "tool"
                            val output = (root["output"] as? JsonPrimitive)?.contentOrNull ?: root["output"]?.toString()
                            mutableState.update { current -> if (current.selected?.id != selected.id) current else {
                                val previous = current.tools[id]
                                val tool = PluginMessageBlock.Tool(id, previous?.name ?: name, if ((root["type"] as JsonPrimitive).content == "tool-end") "done" else "running", root["input"]?.toString() ?: previous?.input, (previous?.output.orEmpty() + output.orEmpty()).takeLast(128 * 1024))
                                current.copy(tools = (current.tools + (id to tool)).entries.toList().takeLast(64).associate { it.toPair() })
                            } }
                        }
                        "turn-end" -> refreshSelected()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { close() }
            }
        }
    }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) { try { service.requestRefresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail() } } }
    fun select(session: PluginSessionSummary) {
        close()
        val revision = ++selectionRevision
        loadJob?.cancel()
        mutableState.value = PluginConversationState(selected = session, loading = true)
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val capture = manager.capture(session.pluginId) ?: error("plugin unavailable")
                val context = gateway.captureContext(capture)
                val publisher = gateway.captureBackgroundContext(capture)
                val page = service.readSession(context, session.sourceKey, session.sessionId)
                context.checkIntegrity()
                if (selectionRevision != revision) { context.close(); publisher.close(); return@launch }
                selectedContext = context
                selectedPublisher = publisher
                mutableState.update { it.copy(page = page, loading = false) }
                service.followSession(context, session.sourceKey, session.sessionId)
                if (session.executionMode == "remote" && session.providerId != null) remote.start(context, session, null, ::refreshSelectedNow)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (selectionRevision == revision) fail() }
        }
    }
    fun close() {
        selectionRevision++
        loadJob?.cancel()
        val selected = state.value.selected
        val context = selectedContext
        selectedPublisher?.close()
        selectedPublisher = null
        selectedContext = null
        mutableState.value = PluginConversationState()
        if (context != null && selected != null) viewModelScope.launch(Dispatchers.IO) {
            try { runCatching { service.unfollowSession(context, selected.sourceKey, selected.sessionId) } }
            finally { context.close() }
        }
    }
    fun updateDraft(text: String) { mutableState.update { it.copy(draft = text.take(32000)) } }
    fun send() {
        val selected = state.value.selected ?: return
        val context = selectedContext ?: return
        val text = state.value.draft.trim().takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (selected.executionMode == "remote" && selected.providerId != null) remote.start(context, selected, text, ::refreshSelectedNow)
                else service.sendMessage(context, selected.sourceKey, selected.sessionId, text)
                context.checkCurrent()
                if (state.value.selected?.id == selected.id) mutableState.update { it.copy(draft = "") }
                refreshSelectedNow()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
    }
    fun stop() {
        val selected = state.value.selected ?: return
        val context = selectedContext ?: return
        viewModelScope.launch(Dispatchers.IO) { try { remote.stop(context, selected.id); service.interruptSession(context, selected.sourceKey, selected.sessionId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail() } }
    }
    fun loadMore() {
        val selected = state.value.selected ?: return
        val context = selectedContext ?: return
        val page = state.value.page?.takeIf { it.hasMore } ?: return
        val revision = selectionRevision
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val older = service.readSession(context, selected.sourceKey, selected.sessionId, page.nextCursor)
                context.checkIntegrity()
                if (revision == selectionRevision) mutableState.update { it.copy(page = older.copy(messages = (older.messages + page.messages).distinctBy { message -> message.id }.takeLast(500))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
    }
    private fun refreshSelected() { viewModelScope.launch(Dispatchers.IO) { refreshSelectedNow() } }
    private suspend fun refreshSelectedNow() {
        val selected = state.value.selected ?: return
        val context = selectedContext ?: return
        val revision = selectionRevision
        val page = service.readSession(context, selected.sourceKey, selected.sessionId)
        context.checkIntegrity()
        if (selectionRevision == revision) mutableState.update { it.copy(page = page, loading = false) }
    }
    override fun onCleared() { loadJob?.cancel(); selectedContext?.close(); selectedPublisher?.close(); selectedContext = null; selectedPublisher = null; super.onCleared() }
    private fun fail() { mutableState.update { it.copy(loading = false, problem = PluginProblem("SESSION_SOURCE_FAILED", R.string.plugin_error_runtime)) } }
}
