package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import com.aicode.feature.plugin.domain.session.PluginSessionGroup
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class PluginSessionRegistry @Inject constructor() {
    internal data class Source(val context: PluginInvocationContext, val definition: PluginSessionSourceDefinition)
    private class State(val owner: PluginRuntimeOwner, val definition: PluginSessionSourceDefinition, val name: String) {
        var sessions: List<PluginSessionSummary> = emptyList()
        var loading = true
        var error: String? = null
        var watcher: Job? = null
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = linkedMapOf<String, State>()
    private val _groups = MutableStateFlow<List<PluginSessionGroup>>(emptyList())
    private val _thinking = MutableStateFlow<Map<String, String>>(emptyMap())
    val groups: StateFlow<List<PluginSessionGroup>> = _groups.asStateFlow()
    val thinking: StateFlow<Map<String, String>> = _thinking.asStateFlow()

    internal fun syncSources(current: List<Source>) = synchronized(lock) {
        val keys = current.mapTo(hashSetOf()) { it.definition.key }
        sources.keys.filterNot { it in keys }.toList().forEach(::removeSource)
        current.forEach { ensureSource(it.context, it.definition) }
        publish()
    }

    internal fun ensureSource(context: PluginInvocationContext, definition: PluginSessionSourceDefinition) = synchronized(lock) {
        context.checkCurrent()
        val existing = sources[definition.key]
        if (existing != null && runCatching { existing.owner.requireCaller(context) }.isSuccess) return@synchronized
        removeSource(definition.key)
        val name = context.capture.manifest.name.resolve(Locale.getDefault().toLanguageTag())
        val state = State(PluginRuntimeOwner(context), definition, name)
        sources[definition.key] = state
        state.watcher = scope.launch {
            context.changes.collect {
                if (runCatching { state.owner.checkCurrent() }.isFailure) synchronized(lock) {
                    if (sources[definition.key] === state) { removeSource(definition.key); publish() }
                }
            }
        }
        publish()
    }

    internal fun setSessions(context: PluginInvocationContext, sourceKey: String, sessions: List<PluginSessionSummary>) = synchronized(lock) {
        val state = requireState(context, sourceKey)
        require(sessions.all { it.sourceKey == sourceKey && it.pluginId == context.capture.id })
        state.sessions = sessions.toList(); state.loading = false; state.error = null
        val ids = sessions.mapTo(hashSetOf()) { it.id }
        _thinking.value = _thinking.value.filterKeys { !it.startsWith("$sourceKey|") || it in ids }
        publish()
    }

    internal fun setLoading(context: PluginInvocationContext, sourceKey: String, loading: Boolean) = synchronized(lock) {
        requireState(context, sourceKey).loading = loading
        publish()
    }

    internal fun setError(context: PluginInvocationContext, sourceKey: String, error: String?) = synchronized(lock) {
        val state = requireState(context, sourceKey)
        state.loading = false; state.error = error
        publish()
    }

    internal fun setThinking(context: PluginInvocationContext, sourceKey: String, sessionId: String, text: String?) = synchronized(lock) {
        requireSession(context, sourceKey, sessionId)
        val id = "$sourceKey|$sessionId"
        _thinking.value = if (text.isNullOrBlank()) _thinking.value - id else _thinking.value + (id to text)
    }

    internal fun requireSession(context: PluginInvocationContext, sourceKey: String, id: String): PluginSessionSummary = synchronized(lock) {
        requireState(context, sourceKey).sessions.firstOrNull { it.sessionId == id }
            ?: throw PluginHostException("SESSION_NOT_FOUND", "External session is not registered for this source")
    }

    internal fun checkSource(context: PluginInvocationContext, sourceKey: String) = synchronized(lock) { requireState(context, sourceKey); Unit }

    fun pruneRevoked() = synchronized(lock) {
        sources.filterValues { runCatching { it.owner.checkCurrent() }.isFailure }.keys.toList().forEach(::removeSource)
        publish()
    }

    private fun requireState(context: PluginInvocationContext, key: String): State {
        val state = sources[key] ?: throw PluginHostException("SESSION_SOURCE_NOT_FOUND", "Session source is not registered")
        if (state.owner.background && !context.background) state.owner.requireCapture(context) else state.owner.requireCaller(context)
        return state
    }

    private fun removeSource(key: String) {
        sources.remove(key)?.watcher?.cancel()
        _thinking.value = _thinking.value.filterKeys { !it.startsWith("$key|") }
    }

    private fun publish() {
        _groups.value = sources.values.groupBy { it.owner.capture.id }.map { (id, values) ->
            PluginSessionGroup(id, values.first().name,
                values.flatMap { it.sessions }.sortedWith(compareByDescending<PluginSessionSummary> { it.updatedAt ?: 0L }.thenBy { it.title }),
                values.any { it.loading }, values.firstNotNullOfOrNull { it.error })
        }
    }
}
