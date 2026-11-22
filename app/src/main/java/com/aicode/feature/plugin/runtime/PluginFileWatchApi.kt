package com.aicode.feature.plugin.runtime

import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.WatchFilter
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import java.io.File
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PluginFileWatchTarget(val path: String, val hostDirectory: File?, val execution: WorkspaceExecutionContext?, val checkCurrent: () -> Unit)

data class PluginFileWatchEvent(val sourceContext: PluginInvocationContext, val pageId: String, val subscriptionId: String, val batch: FileChangeBatch,
    internal val handle: String, internal val generation: Long, val errorCode: String? = null)

@Singleton
class PluginFileWatchApi @Inject constructor(
    private val gateway: Provider<PluginHostGateway>,
    private val files: PluginFileBridge,
    private val hub: FileChangeHub
) {
    private class Subscription(val context: PluginInvocationContext, val pageId: String, val id: String,
        val target: PluginFileWatchTarget, val recursive: Boolean) {
        val owner = PluginResourceOwner.of(context)
        val handle = UUID.randomUUID().toString()
        var generation = 0L
        var collector: Job? = null
        var observer: Job? = null
        var removed = false
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscriptions = linkedMapOf<Pair<String, String>, Subscription>()
    private val visibility = hashMapOf<String, Boolean>()
    private val mutableEvents = MutableSharedFlow<PluginFileWatchEvent>(extraBufferCapacity = 0)
    val events: Flow<PluginFileWatchEvent> = mutableEvents.asSharedFlow()

    suspend fun watch(context: PluginInvocationContext, pageId: String, params: JsonObject): JsonObject {
        if (pageId.isBlank() || pageId.length > 128 || params.keys.any { it !in setOf("subscriptionId", "path", "recursive") }) invalid()
        val id = string(params, "subscriptionId", 128)
        val path = string(params, "path", 4096)
        val recursive = params["recursive"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: invalid() } ?: false
        context.checkIntegrity()
        val target = files.watchTarget(context, path)
        val arguments = buildJsonObject { put("path", target.path); put("operation", "watch"); put("recursive", recursive) }
        gateway.get().authorize(context, "list", arguments, PluginApprovalOperation.READ_FILES, arguments.toString())
        withContext(Dispatchers.IO + (target.execution ?: kotlin.coroutines.EmptyCoroutineContext)) { target.checkCurrent() }
        val key = pageId to id
        val entry = Subscription(context, pageId, id, target, recursive)
        synchronized(lock) {
            val existing = subscriptions[key]
            if (existing != null && existing.owner != entry.owner) throw PluginHostException("WATCH_NOT_FOUND", "Subscription belongs to another source")
            if (subscriptions.size >= 64 || subscriptions.values.count { it.owner == entry.owner } >= 16) throw PluginHostException("WATCH_LIMIT", "Plugin watch limit reached")
            existing?.let(::removeLocked)
            subscriptions[key] = entry
            entry.observer = scope.launch(start = CoroutineStart.LAZY) {
                context.changes.collect { if (runCatching { context.checkCurrent() }.isFailure) synchronized(lock) { removeLocked(entry) } }
            }
            if (visibility[pageId] != false) startLocked(entry)
        }
        entry.observer!!.start()
        return buildJsonObject { put("subscriptionId", id) }
    }

    suspend fun unwatch(context: PluginInvocationContext, pageId: String, subscriptionId: String): JsonObject {
        context.checkIntegrity()
        synchronized(lock) {
            val entry = subscriptions[pageId to subscriptionId]
            if (entry != null && entry.owner != PluginResourceOwner.of(context)) throw PluginHostException("WATCH_NOT_FOUND", "No subscription belongs to this source")
            entry?.let(::removeLocked)
        }
        return buildJsonObject { put("ok", true) }
    }

    fun isCurrent(event: PluginFileWatchEvent): Boolean = synchronized(lock) {
        val entry = subscriptions[event.pageId to event.subscriptionId]
        entry != null && !entry.removed && entry.handle == event.handle && entry.generation == event.generation && visibility[event.pageId] != false &&
            runCatching { event.sourceContext.checkCurrent() }.isSuccess
    }

    fun setPageVisible(pageId: String, visible: Boolean) = synchronized(lock) {
        visibility[pageId] = visible
        subscriptions.values.filter { it.pageId == pageId }.forEach { entry ->
            if (visible) startLocked(entry) else { entry.generation++; entry.collector?.cancel(); entry.collector = null }
        }
    }

    fun closePage(pageId: String) = synchronized(lock) {
        subscriptions.values.filter { it.pageId == pageId }.toList().forEach(::removeLocked)
        visibility.remove(pageId)
        Unit
    }

    private fun startLocked(entry: Subscription) {
        if (entry.removed || entry.collector?.isActive == true) return
        val generation = ++entry.generation
        entry.collector = scope.launch {
            val execution = entry.target.execution
            try {
            withContext(execution ?: kotlin.coroutines.EmptyCoroutineContext) {
                entry.target.checkCurrent()
                val batches = entry.target.hostDirectory?.let { hub.watchHostDir(it, entry.target.path, recursive = entry.recursive, filter = WatchFilter(followGitignore = false)) }
                    ?: hub.watchRemoteDir(requireNotNull(execution), entry.target.path, entry.recursive, WatchFilter(followGitignore = false))
                try {
                    batches.collect { batch ->
                        entry.target.checkCurrent()
                        val current = synchronized(lock) { !entry.removed && entry.generation == generation && visibility[entry.pageId] != false }
                        if (current) mutableEvents.emit(PluginFileWatchEvent(entry.context, entry.pageId, entry.id, batch, entry.handle, generation))
                    }
                } finally {
                    val job = currentCoroutineContext()[Job]
                    synchronized(lock) { if (entry.collector === job) entry.collector = null }
                }
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val current = synchronized(lock) { !entry.removed && entry.generation == generation }
                if (current) mutableEvents.emit(PluginFileWatchEvent(entry.context, entry.pageId, entry.id,
                    FileChangeBatch(emptyList(), truncated = true), entry.handle, generation, "WATCH_UNAVAILABLE"))
            }
        }
    }

    private fun removeLocked(entry: Subscription) {
        entry.removed = true
        entry.generation++
        entry.collector?.cancel()
        entry.observer?.cancel()
        subscriptions.remove(entry.pageId to entry.id, entry)
    }

    private fun string(params: JsonObject, key: String, max: Int): String = (params[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= max && '\u0000' !in it.content }?.content ?: invalid()
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid page watch parameters")
}
