package com.aicode.feature.plugin.presentation

import androidx.compose.ui.geometry.Rect
import com.aicode.feature.plugin.runtime.PluginHostException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPageOverlayStateTest {
    @Test fun modalRectangleIsMappedFromCssPageCoordinatesToNativeWindow() {
        val state = PluginPageOverlayState()
        state.setBounds("page", Rect(10f, 20f, 210f, 320f))
        assertTrue(state.update("page", buildJsonObject {
            put("visible", true)
            put("rect", buildJsonObject { put("left", 5); put("top", 6); put("width", 100); put("height", 90) })
        }))
        assertEquals(listOf(Rect(15f, 26f, 115f, 116f)), state.rects.value)
    }
    @Test fun modalWithoutRectangleCoversOnlyItsNativePage() {
        val state = PluginPageOverlayState()
        state.setBounds("page", Rect(10f, 20f, 210f, 320f))
        state.update("page", buildJsonObject { put("visible", true) })
        assertEquals(listOf(Rect(10f, 20f, 210f, 320f)), state.rects.value)
    }
    @Test fun closeAndExplicitFalseRemoveOnlyTheOwnedPageHole() {
        val state = PluginPageOverlayState()
        state.setBounds("one", Rect(0f, 0f, 100f, 100f))
        state.setBounds("two", Rect(100f, 0f, 200f, 100f))
        state.update("one", buildJsonObject { put("visible", true) })
        state.update("two", buildJsonObject { put("visible", true) })
        state.close("one")
        assertEquals(listOf(Rect(100f, 0f, 200f, 100f)), state.rects.value)
        assertFalse(state.update("two", buildJsonObject { put("visible", false) }))
        assertTrue(state.rects.value.isEmpty())
    }
    @Test fun invalidRectanglesFailInsteadOfChangingGlobalBackground() {
        val state = PluginPageOverlayState()
        assertThrows(PluginHostException::class.java) { state.update("page", buildJsonObject {
            put("visible", true); put("rect", buildJsonObject { put("left", -1); put("top", 0); put("width", 10); put("height", 10) })
        }) }
        assertTrue(state.rects.value.isEmpty())
    }
}
