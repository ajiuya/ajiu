package com.aicode.feature.plugin.overlay

import com.aicode.feature.plugin.domain.PluginOverlaySnap
import kotlin.math.abs

internal object PluginOverlayPolicy {
    val policies = setOf("always", "foreground", "background")
    fun visible(policy: String, foreground: Boolean, screenOn: Boolean, requested: Boolean): Boolean =
        screenOn && requested && when (policy) { "foreground" -> foreground; "background" -> !foreground; "always" -> true; else -> false }
    fun clamp(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)) to y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
    fun snap(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int, mode: String, trigger: Int): Pair<Int, Int> {
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        val candidates = mutableListOf<Pair<Int, Pair<Int, Int>>>()
        if (mode == PluginOverlaySnap.X || mode == PluginOverlaySnap.BOTH) {
            candidates += abs(x) to (0 to y); candidates += abs(maxX - x) to (maxX to y)
        }
        if (mode == PluginOverlaySnap.Y || mode == PluginOverlaySnap.BOTH) {
            candidates += abs(y) to (x to 0); candidates += abs(maxY - y) to (x to maxY)
        }
        return candidates.filter { it.first <= trigger }.minByOrNull { it.first }?.second ?: (x to y)
    }
}
