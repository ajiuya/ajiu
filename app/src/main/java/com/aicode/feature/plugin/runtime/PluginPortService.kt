package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.PluginPortMappingOwner
import com.aicode.feature.agent.domain.container.PortMapping
import com.aicode.feature.agent.domain.container.PortMappingStatus
import com.aicode.feature.agent.domain.container.SshPortMappingManager
import com.aicode.feature.settings.data.repository.ExecutionMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginPortService @Inject constructor(private val mappings: SshPortMappingManager) {
    private class Entry(val context: PluginInvocationContext, val owner: PluginResourceOwner, val port: Int) {
        val id = UUID.randomUUID().toString()
        val lease = PluginPortMappingOwner(owner.pluginId, id)
        var mappingId: String? = null
        var closing = false
        var observer: Job? = null
        var cleanup: Job? = null
        val created = CompletableDeferred<Unit>()
        val revoked = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    suspend fun forward(context: PluginInvocationContext, port: Int, localPort: Int?): JsonObject {
        context.checkIntegrity()
        val execution = context.execution ?: throw PluginHostException("CONTEXT_REQUIRED", "A ready captured workspace is required")
        if (execution.mode == ExecutionMode.LOCAL_PROOT && localPort != null && localPort != port) {
            throw PluginHostException("LOCAL_PORT_UNSUPPORTED", "Local mode exposes the existing loopback port directly")
        }
        val entry = Entry(context, PluginResourceOwner.of(context), port)
        synchronized(lock) {
            if (entries.size >= 4) throw PluginHostException("LIMIT_REACHED", "Plugin port resources are active or closing")
            entries[entry.id] = entry
        }
        try {
            context.checkIntegrity()
            synchronized(lock) {
                entry.observer = scope.launch(start = CoroutineStart.LAZY) {
                    context.changes.collect {
                        if (runCatching { context.checkCurrent() }.isFailure) retire(entry)
                    }
                }
            }
            entry.observer!!.start()
            if (execution.mode == ExecutionMode.REMOTE_SSH) {
                val identity = execution.remoteSource?.identity ?: throw PluginHostException("CONTEXT_REQUIRED", "Captured SSH source is required")
                val mappingId = mappings.addOwned(port, localPort, entry.lease, identity, context::checkCurrent)
                synchronized(lock) { entry.mappingId = mappingId }
                if (entry.closing) throw CancellationException("Plugin port closed during creation")
                kotlinx.coroutines.flow.combine(mappings.state, entry.revoked) { state, revoked ->
                    revoked || state.mappings.none { it.id == mappingId && it.status == PortMappingStatus.CONNECTING }
                }.first { it }
                context.checkCurrent()
                val mapping = mappings.ownedMapping(entry.lease, mappingId)
                    ?: throw PluginHostException("CONNECTION_CHANGED", "SSH port mapping source changed")
                if (mapping.status != PortMappingStatus.ACTIVE) throw PluginHostException(mapping.error?.name ?: "CONNECTION_FAILED", "SSH port mapping could not become active")
            }
            context.checkIntegrity()
            if (entry.closing) throw CancellationException("Plugin port closed during creation")
            return info(entry)
        } catch (failure: Throwable) {
            retire(entry)
            throw failure
        } finally {
            entry.created.complete(Unit)
        }
    }

    suspend fun close(context: PluginInvocationContext, id: String): Boolean {
        val entry = owned(context, id)
        retire(entry).join()
        return true
    }

    fun list(context: PluginInvocationContext): JsonArray {
        context.checkIntegrity()
        val owner = PluginResourceOwner.of(context)
        val mine = synchronized(lock) { entries.values.filter { it.owner == owner && !it.closing } }
        return JsonArray(mine.map(::info)).also { context.checkCurrent() }
    }

    private fun info(entry: Entry): JsonObject {
        entry.context.checkCurrent()
        val mapping: PortMapping? = entry.mappingId?.let { mappings.ownedMapping(entry.lease, it) }
        if (entry.mappingId != null && (mapping == null || mapping.status != PortMappingStatus.ACTIVE)) {
            throw PluginHostException("CONNECTION_CHANGED", "The owned port mapping is no longer active")
        }
        val local = mapping?.localPort ?: entry.port
        return buildJsonObject {
            put("id", entry.id); put("localPort", local); put("remoteHost", "127.0.0.1"); put("remotePort", entry.port)
            put("url", "http://127.0.0.1:$local"); put("direct", entry.mappingId == null)
        }
    }

    private fun owned(context: PluginInvocationContext, id: String): Entry {
        context.checkIntegrity()
        return synchronized(lock) { entries[id]?.takeIf { !it.closing && it.owner == PluginResourceOwner.of(context) } }
            ?: throw PluginHostException("PORT_NOT_FOUND", "No port belongs to this plugin and source")
    }

    private fun retire(entry: Entry): Job = synchronized(lock) {
        entry.cleanup?.let { return@synchronized it }
        entry.closing = true
        entry.revoked.value = true
        scope.launch {
            withContext(NonCancellable) {
                entry.created.await()
                entry.observer?.cancel()
                entry.mappingId?.let { mappings.closeOwned(entry.lease, it) }
                synchronized(lock) { entries.remove(entry.id, entry) }
            }
        }.also { entry.cleanup = it }
    }
}
