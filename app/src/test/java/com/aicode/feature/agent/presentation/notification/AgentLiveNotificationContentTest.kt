package com.aicode.feature.agent.presentation.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLiveNotificationContentTest {
    @Test
    fun runningClockUsesElapsedAnchorAndSystemChronometer() {
        val snapshot = AgentLiveNotificationState.Snapshot(startedAtElapsedMs = 10_000)
        val clock = AgentLiveNotificationContent.clock(snapshot, 80_000, 1_000_000)
        assertEquals(930_000L, clock.whenMs)
        assertTrue(clock.usesChronometer)
        assertEquals("1:10", clock.shortText)
    }

    @Test
    fun completedClockFreezesDurationAndStopsChronometer() {
        val snapshot = AgentLiveNotificationState.Snapshot(
            completed = true, startedAtElapsedMs = 1_000, finishedAtElapsedMs = 62_000
        )
        val first = AgentLiveNotificationContent.clock(snapshot, 70_000, 1_000_000)
        val later = AgentLiveNotificationContent.clock(snapshot, 80_000, 1_010_000)
        assertFalse(first.usesChronometer)
        assertEquals("1:01", first.shortText)
        assertEquals(first, later)
    }

    @Test
    fun missingStartDoesNotInventElapsedDuration() {
        val clock = AgentLiveNotificationContent.clock(AgentLiveNotificationState.Snapshot(), 123, 456)
        assertFalse(clock.usesChronometer)
        assertEquals("", clock.shortText)
    }

    @Test
    fun timerHandlesHoursAndNegativeDurations() {
        assertEquals("0:00", AgentLiveNotificationContent.timerText(-1))
        assertEquals("59:59", AgentLiveNotificationContent.timerText(3_599_999))
        assertEquals("1:00:00", AgentLiveNotificationContent.timerText(3_600_000))
    }

    @Test
    fun unknownLabelsIncludingRawCommandsAndOutputsBecomeGenericActions() {
        val actions = mapOf("Running command" to "Command", "Thinking" to "Think")
        assertEquals("Command", AgentLiveNotificationContent.safeAction("Running command", actions, "Running"))
        for (unsafe in listOf("curl --header Secret:token", "tool output\nprivate", "Thinking\npassword", "")) {
            assertEquals("Running", AgentLiveNotificationContent.safeAction(unsafe, actions, "Running"))
        }
    }

    @Test
    fun notificationBodyNeverUsesTaskTitleOrSessionIdentifier() {
        val snapshot = AgentLiveNotificationState.Snapshot(
            enabled = true,
            sessions = listOf(AgentLiveNotificationState.Session("private-uuid", "secret command/output", "Reading"))
        )
        val text = AgentLiveNotificationContent.body(snapshot, "Running", "Agent %1\$d · %2\$s")
        assertEquals("Agent 1 · Reading", text)
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("private-uuid"))
    }

    @Test
    fun actionChipPrefersConcreteActionOverThinkingAndNeverAClock() {
        val snapshot = AgentLiveNotificationState.Snapshot(
            sessions = listOf(
                AgentLiveNotificationState.Session("a", "", "Think"),
                AgentLiveNotificationState.Session("b", "", "Command")
            )
        )
        assertEquals("Command", shortText(snapshot))
        assertEquals("Ended", shortText(snapshot.copy(completed = true, completionVerified = false)))
        assertEquals("Done", shortText(snapshot.copy(completed = true, completionVerified = true)))
        assertEquals("Failed", shortText(snapshot.copy(completed = true, failed = true, completionVerified = false)))
    }

    @Test
    fun expandedBodyIsBoundedEvenWithManyActiveSessions() {
        val snapshot = AgentLiveNotificationState.Snapshot(
            sessions = (1..100).map { AgentLiveNotificationState.Session("$it", "", "Command") }
        )
        assertEquals(10, AgentLiveNotificationContent.body(snapshot, "Running", "Agent %1\$d · %2\$s").lines().size)
    }

    private fun shortText(snapshot: AgentLiveNotificationState.Snapshot) = AgentLiveNotificationContent.shortText(
        snapshot, "Running", "Done", "Failed", "Think", "Ended"
    )
}
