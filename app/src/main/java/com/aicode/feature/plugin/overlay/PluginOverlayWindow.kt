package com.aicode.feature.plugin.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.aicode.R
import com.aicode.feature.plugin.presentation.PluginPageSession
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PluginOverlayWindow(
    context: Context,
    val pluginId: String,
    val overlayId: String,
    private val manager: WindowManager,
    private val params: WindowManager.LayoutParams,
    val session: PluginPageSession,
    private val draggable: Boolean,
    private val snap: String,
    initialPolicy: String,
    private var foreground: Boolean,
    private var screenOn: Boolean,
    private val onPosition: (Int, Int) -> Unit,
    private val onClose: () -> Unit,
    private val onVisibility: (Boolean) -> Unit,
    private val onDispose: () -> Unit
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val handle = View(context)
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var policy = initialPolicy
    private var requested = true
    private var active = false
    private var moved = false
    private var longPressed = false
    private var startX = 0
    private var startY = 0
    private var downX = 0f
    private var downY = 0f
    private var animator: ValueAnimator? = null
    private var dragRegion = RectF(0f, 0f, params.width / density, params.height / density)
    private val longPress = Runnable {
        if (active && !moved) { longPressed = true; postMessage(buildJsonObject { put("type", "longPress") }); showMenu() }
    }
    init {
        addView(session.webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(handle, LayoutParams(params.width, params.height).apply { gravity = Gravity.TOP or Gravity.START })
        handle.visibility = if (draggable) VISIBLE else GONE
        handle.setOnTouchListener { _, event -> touch(event) }
        applyVisibility()
    }
    fun describe(): JsonObject = buildJsonObject {
        put("overlayId", overlayId); put("pluginId", pluginId); put("width", params.width / density); put("height", params.height / density)
        put("x", params.x); put("y", params.y); put("density", density); put("draggable", draggable); put("snap", snap)
        put("visibilityPolicy", policy); put("foreground", foreground)
    }
    fun visibilityPolicy(): String = policy
    fun setVisibilityPolicy(value: String) { require(value in PluginOverlayPolicy.policies); policy = value; applyVisibility() }
    fun setForeground(value: Boolean) { foreground = value; applyVisibility(); postMessage(buildJsonObject { put("type", "appState"); put("foreground", value) }) }
    fun setScreenOn(value: Boolean) { screenOn = value; applyVisibility() }
    fun setVisible(value: Boolean) { requested = value; applyVisibility() }
    private fun applyVisibility() {
        val visible = PluginOverlayPolicy.visible(policy, foreground, screenOn, requested)
        visibility = if (visible) VISIBLE else GONE
        onVisibility(visible)
        if (!visible) { handler.removeCallbacks(longPress); animator?.cancel(); active = false }
        if (visible) session.webView.onResume() else session.webView.onPause()
    }
    fun setDragRegion(rect: RectF) {
        if (!draggable) return
        dragRegion = rect
        val left = (rect.left * density).roundToInt().coerceIn(0, params.width)
        val top = (rect.top * density).roundToInt().coerceIn(0, params.height)
        handle.layoutParams = LayoutParams((rect.width() * density).roundToInt().coerceIn(0, params.width - left),
            (rect.height() * density).roundToInt().coerceIn(0, params.height - top)).apply { leftMargin = left; topMargin = top }
    }
    fun moveBy(dx: Float, dy: Float) { animator?.cancel(); params.x += (dx * density).roundToInt(); params.y += (dy * density).roundToInt(); clamp(); update(); onPosition(params.x, params.y) }
    fun resize(width: Int, height: Int, animate: Boolean = false, x: Float? = null, y: Float? = null) {
        require(width in 48..720 && height in 48..1200)
        animator?.cancel()
        val beforeWidth = params.width
        val beforeHeight = params.height
        val targetWidth = (width * density).roundToInt()
        val targetHeight = (height * density).roundToInt()
        fun apply(fraction: Float) {
            params.width = (beforeWidth + (targetWidth - beforeWidth) * fraction).roundToInt()
            params.height = (beforeHeight + (targetHeight - beforeHeight) * fraction).roundToInt()
            if (x != null) params.x = (x * density).roundToInt()
            if (y != null) params.y = (y * density).roundToInt()
            clamp(); update()
        }
        if (animate && visibility == VISIBLE) animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 220; addUpdateListener { apply(it.animatedValue as Float) }; start() }
        else apply(1f)
        setDragRegion(dragRegion)
        onPosition(params.x, params.y)
        postMessage(buildJsonObject { put("type", "resize"); put("width", width); put("height", height) })
    }
    fun postMessage(payload: JsonElement) { session.postOverlayMessage(payload) }
    fun dispose() { animator?.cancel(); handler.removeCallbacksAndMessages(null); handle.setOnTouchListener(null); onDispose(); session.close() }
    private fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel(); active = true; moved = false; longPressed = false
                startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (!moved && hypot(dx, dy) > slop) { moved = true; handler.removeCallbacks(longPress); postMessage(buildJsonObject { put("type", "dragStart") }) }
                if (moved) { params.x = startX + dx.roundToInt(); params.y = startY + dy.roundToInt(); clamp(); update() }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!active) return true
                active = false
                if (moved) {
                    val metrics = resources.displayMetrics
                    val (x, y) = PluginOverlayPolicy.snap(params.x, params.y, params.width, params.height, metrics.widthPixels, metrics.heightPixels, snap, (40 * density).roundToInt())
                    params.x = x; params.y = y; update(); onPosition(x, y)
                    postMessage(buildJsonObject { put("type", "dragEnd"); put("x", x / density); put("y", y / density) })
                    if (x == 0 || y == 0 || x == metrics.widthPixels - params.width || y == metrics.heightPixels - params.height) postMessage(buildJsonObject { put("type", "snap"); put("x", x / density); put("y", y / density) })
                } else if (!longPressed) postMessage(buildJsonObject { put("type", "click") })
            }
            MotionEvent.ACTION_CANCEL -> { active = false; handler.removeCallbacks(longPress) }
        }
        return true
    }
    private fun showMenu() {
        PopupMenu(context, handle).apply {
            val options = listOf(R.string.plugin_overlay_policy_always to "always", R.string.plugin_overlay_policy_foreground to "foreground", R.string.plugin_overlay_policy_background to "background")
            options.forEachIndexed { index, (label, _) -> menu.add(0, index, index, context.getString(label)) }
            menu.add(0, options.size, options.size, context.getString(R.string.plugin_overlay_close))
            setOnMenuItemClickListener { item -> if (item.itemId == options.size) onClose() else setVisibilityPolicy(options[item.itemId].second); true }
            show()
        }
    }
    private fun clamp() { val metrics = resources.displayMetrics; val point = PluginOverlayPolicy.clamp(params.x, params.y, params.width, params.height, metrics.widthPixels, metrics.heightPixels); params.x = point.first; params.y = point.second }
    private fun update() { manager.updateViewLayout(this, params) }
}
