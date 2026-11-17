package com.aicode.feature.player.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerServiceOwnershipTest {
    @Test fun lateOldNotificationCannotReplaceOrStopNewBackgroundOwner() {
        assertEquals(PlayerServiceStartDecision.ACCEPT, playerServiceStartDecision("A", "A", true, false))
        assertEquals(PlayerServiceStartDecision.ACCEPT, playerServiceStartDecision("B", "B", true, true))
        assertEquals(PlayerServiceStartDecision.IGNORE_STALE, playerServiceStartDecision("A", "B", true, true))
        assertEquals(PlayerServiceStartDecision.IGNORE_STALE, playerServiceStartDecision(null, "B", true, true))
    }

    @Test fun orphanedForegroundStartMustBeStopped() {
        assertEquals(PlayerServiceStartDecision.STOP_ORPHAN, playerServiceStartDecision("A", null, false, false))
        assertEquals(PlayerServiceStartDecision.STOP_ORPHAN, playerServiceStartDecision("A", null, false, true))
        assertEquals(PlayerServiceStartDecision.STOP_ORPHAN, playerServiceStartDecision("A", "A", false, true))
    }

    @Test fun staleIntentDoesNotStartNewBackgroundSession() {
        assertEquals(PlayerServiceStartDecision.STOP_ORPHAN, playerServiceStartDecision("A", "B", true, false))
        assertEquals(PlayerServiceStartDecision.ACCEPT, playerServiceStartDecision("B", "B", true, false))
    }
}
