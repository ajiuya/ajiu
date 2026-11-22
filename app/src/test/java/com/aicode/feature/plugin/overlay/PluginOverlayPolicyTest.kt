package com.aicode.feature.plugin.overlay

import com.aicode.feature.plugin.domain.PluginOverlaySnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOverlayPolicyTest {
    @Test fun foregroundAndBackgroundPoliciesFollowActualAppVisibility() {
        assertTrue(PluginOverlayPolicy.visible("foreground", true, true, true))
        assertFalse(PluginOverlayPolicy.visible("foreground", false, true, true))
        assertTrue(PluginOverlayPolicy.visible("background", false, true, true))
        assertFalse(PluginOverlayPolicy.visible("background", true, true, true))
    }
    @Test fun screenOffAlwaysHidesEvenAlwaysVisibleOverlays() {
        for (policy in PluginOverlayPolicy.policies) for (foreground in listOf(true, false)) assertFalse(PluginOverlayPolicy.visible(policy, foreground, false, true))
    }
    @Test fun explicitHiddenAndUnknownPoliciesNeverBecomeVisible() {
        for (policy in PluginOverlayPolicy.policies) assertFalse(PluginOverlayPolicy.visible(policy, true, true, false))
        assertFalse(PluginOverlayPolicy.visible("future", true, true, true))
    }
    @Test fun positionsStayInsideScreenAndOversizedWindowsAnchorAtZero() {
        assertEquals(0 to 400, PluginOverlayPolicy.clamp(-20, 1000, 200, 400, 600, 800))
        assertEquals(0 to 0, PluginOverlayPolicy.clamp(50, 60, 1000, 1000, 600, 800))
    }
    @Test fun edgeSnapOnlyTriggersNearAnAllowedAxis() {
        assertEquals(0 to 100, PluginOverlayPolicy.snap(20, 100, 100, 100, 600, 800, PluginOverlaySnap.X, 40))
        assertEquals(20 to 100, PluginOverlayPolicy.snap(20, 100, 100, 100, 600, 800, PluginOverlaySnap.Y, 40))
        assertEquals(100 to 100, PluginOverlayPolicy.snap(100, 100, 100, 100, 600, 800, PluginOverlaySnap.BOTH, 40))
    }
    @Test fun nearestEdgeWinsWhenBothAxesAreAllowed() {
        assertEquals(30 to 0, PluginOverlayPolicy.snap(30, 15, 100, 100, 600, 800, PluginOverlaySnap.BOTH, 40))
        assertEquals(500 to 400, PluginOverlayPolicy.snap(490, 400, 100, 100, 600, 800, PluginOverlaySnap.X, 40))
    }
    @Test fun disabledSnappingPreservesTheReleasedPosition() {
        assertEquals(1 to 2, PluginOverlayPolicy.snap(1, 2, 100, 100, 600, 800, PluginOverlaySnap.NONE, 40))
    }
}
