package com.aicode.feature.plugin.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginOverlayPositionRepository
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.presentation.PluginPageSession
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginFileWatchApi
import com.aicode.feature.plugin.presentation.PluginPageFileSubscriptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

@Singleton
class PluginOverlayManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val plugins: PluginManager,
    private val capabilities: PluginCapabilityRepository,
    private val positions: PluginOverlayPositionRepository,
    private val foreground: AppForegroundMonitor,
    private val gateway: Provider<PluginHostGateway>,
    private val actions: Provider<PluginActions>,
    private val runtime: Provider<PluginRuntimeManager>,
    private val watches: PluginFileWatchApi
) {
    private val windows = linkedMapOf<String, PluginOverlayWindow>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val createMutex = Mutex()
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    init {
        scope.launch { foreground.isForeground.collect { value -> windows.values.toList().forEach { it.setForeground(value) } } }
        scope.launch { plugins.snapshot.collect { closeStale() } }
        scope.launch { capabilities.grants.collect { closeStale() } }
    }
    fun hasOverlays(): Boolean = windows.isNotEmpty()
    fun isAppForeground(): Boolean = foreground.current()
    fun onServiceStarted() = Unit
    fun onServiceStopped() { closeAll() }
    fun onScreenStateChanged(value: Boolean) { scope.launch { screenOn = value; windows.values.forEach { it.setScreenOn(value) } } }

    suspend fun create(invocation: PluginInvocationContext, params: JsonObject): JsonObject = createMutex.withLock {
        invocation.checkIntegrity()
        capabilities.ensureLoaded()
        gateway.get().requireCapability(invocation, PluginCapability.OVERLAY)
        if (!Settings.canDrawOverlays(context)) throw PluginHostException("OVERLAY_SYSTEM_PERMISSION_MISSING", "System overlay permission is required")
        val declaration = invocation.capture.content.overlay ?: throw PluginHostException("OVERLAY_NOT_DECLARED", "Overlay was not declared")
        val surfaceId = string(params, "surface", false)
        val surface = (if (surfaceId == null) declaration.surfaces.firstOrNull() else declaration.surfaces.firstOrNull { it.localId == surfaceId })
            ?: throw PluginHostException("SURFACE_NOT_FOUND", "Overlay surface was not declared")
        val saved = positions.get(invocation.capture.id, surface.localId)
        val captured = gateway.get().captureBackgroundContext(invocation.capture)
        val width = integer(params, "width", surface.width, 48..720)
        val height = integer(params, "height", surface.height, 48..1200)
        val policy = string(params, "visibilityPolicy", false) ?: "always"
        if (policy !in PluginOverlayPolicy.policies) throw PluginHostException("PARAM_INVALID", "Invalid overlay visibility policy")
        withContext(Dispatchers.Main.immediate) {
            invocation.checkCurrent()
            windows[surface.key]?.let { return@withContext it.describe() }
            if (windows.size >= 8 || windows.values.count { it.pluginId == invocation.capture.id } >= 3) throw PluginHostException("OVERLAY_LIMIT", "Overlay limit reached")
            val density = context.resources.displayMetrics.density
            val page = PluginPage(surface.pluginId, surface.localId, LocalizedText.single(surface.localId), null, surface.pageFile, "circle", surface.installDir, surface.pluginVersion)
            val layout = WindowManager.LayoutParams((width * density).toInt(), (height * density).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = integer(params, "x", saved?.x ?: 0, 0..Int.MAX_VALUE)
                y = integer(params, "y", saved?.y ?: 0, 0..Int.MAX_VALUE)
                val point = PluginOverlayPolicy.clamp(x, y, this.width, this.height, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
                x = point.first; y = point.second
            }
            lateinit var window: PluginOverlayWindow
            var subscriptions: PluginPageFileSubscriptions? = null
            val session = PluginPageSession(context, invocation.capture, page, false, {
                captured.checkCurrent()
                if (!capabilities.checkGranted(captured.capture, PluginCapability.OVERLAY)) throw PluginHostException("CAPABILITY_REQUIRED", "Overlay capability was revoked")
            }, { method, args ->
                if (method == "fs.watch" || method == "fs.unwatch") subscriptions?.call(method, args)
                    ?: throw PluginHostException("CONTEXT_CHANGED", "Overlay page closed")
                else pageCall(captured, surface.key, method, args)
            }, { closeById(surface.key) })
            subscriptions = PluginPageFileSubscriptions(watches, captured, scope, session::postEvent)
            window = PluginOverlayWindow(context, surface.pluginId, surface.key, windowManager, layout, session, surface.draggable, surface.snap, policy, foreground.current(), screenOn,
                { x, y -> scope.launch(Dispatchers.IO) { positions.save(surface.pluginId, surface.localId, x, y) } }, { closeById(surface.key) },
                { subscriptions?.setVisible(it) }, { subscriptions?.close(); subscriptions = null; captured.close() })
            try {
                ContextCompat.startForegroundService(context, Intent(context, PluginOverlayService::class.java))
                captured.checkCurrent()
                windowManager.addView(window, layout)
                windows[surface.key] = window
                session.start()
                if (windows[surface.key] !== window) throw PluginHostException("PAGE_UNSUPPORTED", "Secure overlay page could not start")
                window.describe()
            } catch (failure: Exception) {
                windows.remove(surface.key)
                runCatching { windowManager.removeViewImmediate(window) }
                window.dispose()
                if (windows.isEmpty()) context.stopService(Intent(context, PluginOverlayService::class.java))
                throw PluginHostException("OVERLAY_SERVICE_FAILED", "Could not create overlay")
            }
        }
    }

    suspend fun close(invocation: PluginInvocationContext, params: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        val window = owned(invocation, params)
        closeById(window.overlayId)
        buildJsonObject { put("ok", true) }
    }
    suspend fun update(invocation: PluginInvocationContext, params: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        val window = owned(invocation, params)
        params["visible"]?.let { window.setVisible((it as? JsonPrimitive)?.booleanOrNull ?: invalid()) }
        params["visibilityPolicy"]?.let { val value = (it as? JsonPrimitive)?.contentOrNull ?: invalid(); if (value !in PluginOverlayPolicy.policies) invalid(); window.setVisibilityPolicy(value) }
        if ("dx" in params || "dy" in params) window.moveBy(number(params, "dx", 0f), number(params, "dy", 0f))
        if ("width" in params || "height" in params) {
            val current = window.describe()
            window.resize(integer(params, "width", (current["width"] as JsonPrimitive).floatOrNull!!.toInt(), 48..720), integer(params, "height", (current["height"] as JsonPrimitive).floatOrNull!!.toInt(), 48..1200))
        }
        window.describe()
    }
    suspend fun postMessage(invocation: PluginInvocationContext, params: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        val window = owned(invocation, params)
        window.postMessage(params["payload"] ?: JsonNull)
        buildJsonObject { put("ok", true) }
    }
    fun closePlugin(pluginId: String) { scope.launch { windows.values.filter { it.pluginId == pluginId }.map { it.overlayId }.forEach(::closeById) } }
    fun closeAll() { scope.launch { windows.keys.toList().forEach(::closeById) } }
    private fun closeStale() {
        windows.values.filter { window -> plugins.capture(window.pluginId)?.let { capabilities.checkGranted(it, PluginCapability.OVERLAY) } != true }.map { it.overlayId }.forEach(::closeById)
    }
    private fun closeById(id: String) {
        val window = windows.remove(id) ?: return
        runCatching { windowManager.removeViewImmediate(window) }
        window.dispose()
        if (windows.isEmpty()) context.stopService(Intent(context, PluginOverlayService::class.java))
    }
    private fun owned(invocation: PluginInvocationContext, params: JsonObject): PluginOverlayWindow {
        invocation.checkCurrent()
        if (!capabilities.checkGranted(invocation.capture, PluginCapability.OVERLAY)) throw PluginHostException("CAPABILITY_REQUIRED", "Overlay capability is required")
        val id = string(params, "overlayId", false) ?: string(params, "surface", false)?.let { "${invocation.capture.id}:$it" } ?: invalid()
        val window = windows[id] ?: throw PluginHostException("OVERLAY_NOT_FOUND", "Overlay not found")
        if (window.pluginId != invocation.capture.id) throw PluginHostException("OVERLAY_DENIED", "Overlay belongs to another plugin")
        return window
    }
    private suspend fun pageCall(invocation: PluginInvocationContext, id: String, method: String, params: JsonObject): JsonElement {
        val local = setOf("overlay.self", "overlay.setDragRegion", "overlay.moveBy", "overlay.resize", "overlay.setVisibilityPolicy", "overlay.close", "ui.close", "ui.overlay", "overlay.postMessage")
        if (method !in local) return actions.get().call(invocation, method, params)
        invocation.checkIntegrity()
        return withContext(Dispatchers.Main.immediate) {
            val window = owned(invocation, buildJsonObject { put("overlayId", id) })
            when (method) {
                "overlay.self" -> window.describe()
                "overlay.setDragRegion" -> { window.setDragRegion(RectF(number(params, "left", 0f), number(params, "top", 0f), number(params, "left", 0f) + number(params, "width", 0f), number(params, "top", 0f) + number(params, "height", 0f))); JsonNull }
                "overlay.moveBy" -> { window.moveBy(number(params, "dx", 0f), number(params, "dy", 0f)); JsonNull }
                "overlay.resize" -> { window.resize(integer(params, "width", 240, 48..720), integer(params, "height", 320, 48..1200), (params["animate"] as? JsonPrimitive)?.booleanOrNull == true, (params["x"] as? JsonPrimitive)?.floatOrNull, (params["y"] as? JsonPrimitive)?.floatOrNull); JsonNull }
                "overlay.setVisibilityPolicy" -> { val policy = string(params, "policy", true)!!; if (policy !in PluginOverlayPolicy.policies) invalid(); window.setVisibilityPolicy(policy); JsonNull }
                "overlay.close", "ui.close" -> { closeById(id); JsonNull }
                "ui.overlay" -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Overlay surfaces do not host page modals")
                else -> {
                    val payload = params["payload"] ?: JsonNull
                    scope.launch(Dispatchers.IO) {
                        val process = invocation.capture.content.runtime ?: return@launch
                        runCatching { runtime.get().invoke(invocation.capture, process, "overlay.message", buildJsonObject { put("overlayId", id); put("payload", payload) }, invocation.runtimeContext(), { name, arguments -> gateway.get().call(invocation, name, arguments) }, method = "ui.invoke") }
                    }
                    JsonNull
                }
            }
        }
    }
    private fun string(params: JsonObject, key: String, required: Boolean): String? {
        val raw = params[key] ?: return if (required) invalid() else null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
    }
    private fun integer(params: JsonObject, key: String, default: Int, range: IntRange): Int =
        if (key !in params) default else (params[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range } ?: invalid()
    private fun number(params: JsonObject, key: String, default: Float): Float =
        if (key !in params) default else (params[key] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 10000 } ?: invalid()
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid overlay parameters")
}
