package com.aicode.feature.plugin.presentation

import com.aicode.feature.plugin.runtime.PluginFileWatchApi
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PluginPageFileSubscriptions(
    private val api: PluginFileWatchApi,
    private val context: PluginInvocationContext,
    scope: CoroutineScope,
    private val deliver: (String, JsonElement) -> Unit
) : AutoCloseable {
    private val pageId = UUID.randomUUID().toString()
    private var closed = false
    private val collector: Job = scope.launch {
        api.events.collect { event ->
            if (closed || event.pageId != pageId || event.sourceContext !== context) return@collect
            try {
                withContext(Dispatchers.IO) { context.checkIntegrity() }
                if (closed || !api.isCurrent(event)) return@collect
                deliver("fs.changed", buildJsonObject {
                    put("subscriptionId", event.subscriptionId)
                    put("truncated", event.batch.truncated)
                    put("changes", JsonArray(event.batch.changes.map { change -> buildJsonObject {
                        put("path", change.containerPath); put("kind", change.kind.name.lowercase())
                    } }))
                })
            } catch (_: Exception) { close() }
        }
    }
    suspend fun call(method: String, params: JsonObject): JsonElement {
        if (closed) throw PluginHostException("CONTEXT_CHANGED", "Page was closed")
        return when (method) {
            "fs.watch" -> api.watch(context, pageId, params)
            "fs.unwatch" -> api.unwatch(context, pageId, (params["subscriptionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw PluginHostException("PARAM_INVALID", "subscriptionId is required"))
            else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Unsupported file watch method")
        }
    }
    fun setVisible(visible: Boolean) { if (!closed) api.setPageVisible(pageId, visible) }
    override fun close() { if (closed) return; closed = true; collector.cancel(); api.closePage(pageId) }
}
