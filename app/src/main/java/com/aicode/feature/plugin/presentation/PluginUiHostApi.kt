package com.aicode.feature.plugin.presentation

import com.aicode.feature.plugin.overlay.AppForegroundMonitor
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import com.aicode.feature.plugin.runtime.PluginHostApi
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PluginTip(val tipId: String, val pluginId: String, val title: String, val text: String, val actionLabel: String?, val actionHandler: String?, val invocation: PluginInvocationContext)

@Singleton
class PluginTipManager @Inject constructor() {
    private val mutableTips = MutableStateFlow<List<PluginTip>>(emptyList())
    private val lock = Any()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val watchers = linkedMapOf<String, kotlinx.coroutines.Job>()
    internal val tips = mutableTips.asStateFlow()
    internal fun show(invocation: PluginInvocationContext, params: JsonObject): JsonElement {
        invocation.checkCurrent()
        val text = (params["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val title = (params["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (text.isBlank() || text.length > 500 || title.length > 80) throw PluginHostException("PARAM_INVALID", "Invalid tip text or title")
        val action = params["action"]?.let { it as? JsonObject ?: throw PluginHostException("PARAM_INVALID", "Invalid tip action") }
        val label = (action?.get("label") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val handler = (action?.get("handler") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (action != null && (label.isNullOrBlank() || label.length > 24 || handler == null || !Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$").matches(handler))) throw PluginHostException("PARAM_INVALID", "Invalid tip action")
        if (handler != null && invocation.capture.content.runtime == null) throw PluginHostException("RUNTIME_UNAVAILABLE", "Tip action requires a runtime")
        val tipId = java.util.UUID.randomUUID().toString()
        synchronized(lock) {
            val pending = mutableTips.value.filterNot { it.pluginId == invocation.capture.id }
            if (pending.size >= 3) throw PluginHostException("TIP_QUEUE_FULL", "Too many pending tips")
            val owned = invocation.retainSource()
            mutableTips.value.filter { it.pluginId == invocation.capture.id }.forEach { it.invocation.close(); watchers.remove(it.tipId)?.cancel() }
            mutableTips.value = pending + PluginTip(tipId, invocation.capture.id, title, text, label, handler, owned)
            watchers[tipId] = scope.launch {
                owned.changes.collect { if (runCatching { owned.checkCurrent() }.isFailure) dismissTip(tipId) }
            }
        }
        return buildJsonObject { put("tipId", tipId); put("queued", true) }
    }
    internal fun dismissTip(tipId: String) = synchronized(lock) {
        val tip = mutableTips.value.firstOrNull { it.tipId == tipId } ?: return@synchronized
        mutableTips.value = mutableTips.value.filterNot { it.tipId == tipId }
        watchers.remove(tipId)?.cancel()
        tip.invocation.close()
    }
}

@Singleton
class PluginUiHostApi @Inject constructor(private val overlays: PluginOverlayManager, private val foreground: AppForegroundMonitor, private val tips: PluginTipManager) : PluginHostApi {
    override val methods = setOf("app.isForeground", "tip", "overlay.create", "overlay.close", "overlay.update", "overlay.postMessage")
    override suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement {
        context.checkIntegrity()
        return when (method) {
            "app.isForeground" -> JsonPrimitive(foreground.current())
            "tip" -> tips.show(context, params)
            "overlay.create" -> overlays.create(context, params)
            "overlay.close" -> overlays.close(context, params)
            "overlay.update" -> overlays.update(context, params)
            "overlay.postMessage" -> overlays.postMessage(context, params)
            else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Unsupported plugin UI method")
        }
    }
}
