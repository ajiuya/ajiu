package com.aicode.feature.agent.domain.memory

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Singleton
class ProjectProgressTerminalTracker @Inject constructor() {
    private data class Key(val sessionId: String, val tabId: String, val mode: String, val generation: String?)
    private val owners = linkedMapOf<Key, ProjectProgressCollector>()
    private val earlyResults = linkedMapOf<Key, Int>()
    private val completed = linkedSetOf<Key>()
    private val active = mutableMapOf<String, MutableSet<ProjectProgressCollector>>()
    private val deletedSessions = mutableSetOf<String>()

    @Synchronized fun register(sessionId: String, collector: ProjectProgressCollector) {
        require(sessionId.isNotBlank())
        if (sessionId in deletedSessions) collector.block()
        else active.getOrPut(sessionId) { mutableSetOf() }.add(collector)
    }

    @Synchronized fun release(sessionId: String, collector: ProjectProgressCollector) {
        active[sessionId]?.let { values -> if (values.apply { remove(collector) }.isEmpty()) active.remove(sessionId) }
    }

    @Synchronized fun clearSession(sessionId: String) {
        deletedSessions.add(sessionId)
        val collectors = active.remove(sessionId).orEmpty() + owners.filterKeys { it.sessionId == sessionId }.values
        collectors.toSet().forEach { it.block() }
        owners.keys.removeAll { it.sessionId == sessionId }
        earlyResults.keys.removeAll { it.sessionId == sessionId }
        completed.removeAll { it.sessionId == sessionId }
    }

    @Synchronized fun started(sessionId: String, collector: ProjectProgressCollector, args: String?, result: String, isError: Boolean) {
        if (isError || sessionId in deletedSessions || collector !in active[sessionId].orEmpty()) return
        val input = parse(args) ?: return
        if ((input["action"] as? JsonPrimitive)?.content != "start") return
        val metadata = parse(result) ?: return
        if ((metadata["status"] as? JsonPrimitive)?.content != "success") return
        val tabId = ((metadata["data"] as? JsonObject)?.get("tab_id") as? JsonPrimitive)?.content ?: return
        val key = key(sessionId, tabId, collector.executionMode, collector.channelGeneration) ?: return
        if (key in owners || key in completed) return
        check(owners.size < 128) { "Background tracking capacity reached; results need manual verification" }
        if (!collector.backgroundStarted(tabId)) return
        val early = earlyResults.remove(key)
        if (early != null) {
            rememberCompleted(key)
            collector.backgroundFinished(tabId, early)
        } else owners[key] = collector
    }

    @Synchronized fun finished(
        sourceSessionId: String?,
        tabId: String,
        exitCode: Int,
        sourceMode: String? = null,
        channelGeneration: String? = null
    ) {
        val session = sourceSessionId?.takeIf { it.isNotBlank() && it !in deletedSessions } ?: return
        val key = key(session, tabId, sourceMode, channelGeneration) ?: return
        if (key in completed) return
        val owner = owners.remove(key)
        if (owner != null) {
            rememberCompleted(key)
            owner.backgroundFinished(tabId, exitCode)
        } else if (active[session].orEmpty().any { it.executionMode == key.mode && it.channelGeneration == key.generation }) {
            earlyResults.putIfAbsent(key, exitCode)
            while (earlyResults.size > 128) earlyResults.remove(earlyResults.keys.first())
        }
    }

    private fun rememberCompleted(key: Key) {
        completed.add(key)
        while (completed.size > 256) completed.remove(completed.first())
    }

    private fun key(session: String, tabId: String, mode: String?, generation: String?): Key? {
        if (!Regex("[A-Za-z0-9_-]{1,100}").matches(tabId)) return null
        val normalized = mode?.lowercase(Locale.ROOT)?.takeIf { it in setOf("local_proot", "remote_ssh") } ?: return null
        if (normalized == "remote_ssh" && generation.isNullOrBlank()) return null
        if (normalized == "local_proot" && generation != null) return null
        return Key(session, tabId, normalized, generation)
    }

    private fun parse(text: String?): JsonObject? = if (text == null || text.length > 65536) null
        else runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
