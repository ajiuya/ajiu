package com.aicode.feature.plugin.presentation

import androidx.compose.ui.geometry.Rect
import com.aicode.feature.plugin.runtime.PluginHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

@Singleton
class PluginPageOverlayState @Inject constructor() {
    private val mutableRects = MutableStateFlow<List<Rect>>(emptyList())
    val rects = mutableRects.asStateFlow()
    private val bounds = linkedMapOf<String, Rect>()
    private val overlays = linkedMapOf<String, Rect?>()
    fun setBounds(pageId: String, value: Rect) { bounds[pageId] = value; publish() }
    fun update(pageId: String, params: JsonObject): Boolean {
        val visible = (params["visible"] as? JsonPrimitive)?.booleanOrNull ?: invalid()
        if (!visible) overlays.remove(pageId)
        else {
            val raw = params["rect"] as? JsonObject
            overlays[pageId] = raw?.let {
                fun number(key: String): Float = (it[key] as? JsonPrimitive)?.floatOrNull?.takeIf { value -> value.isFinite() && value in 0f..10000f } ?: invalid()
                val left = number("left"); val top = number("top")
                Rect(left, top, left + number("width"), top + number("height"))
            }
        }
        publish()
        return visible
    }
    fun close(pageId: String) { bounds.remove(pageId); overlays.remove(pageId); publish() }
    private fun publish() {
        mutableRects.value = overlays.mapNotNull { (id, rect) ->
            val parent = bounds[id] ?: return@mapNotNull null
            rect?.translate(parent.topLeft)?.intersect(parent) ?: parent
        }
    }
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid page overlay rectangle")
}
