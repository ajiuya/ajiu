package com.aicode.feature.agent.presentation.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLiveNotificationPolicyTest {
    private val policy = AgentLiveNotificationPolicy()

    private fun session(id: String) = AgentLiveNotificationState.Session(id, "", "Reading")
    private fun render(now: Long, foreground: Boolean = false, enabled: Boolean = true) =
        policy.render(enabled, foreground, now)

    @Test
    fun absentProducerIsNotAnImplicitSuccess() {
        policy.update(listOf(session("a")))
        render(10)
        policy.update(emptyList())
        val ended = render(20)
        assertTrue(ended.completed)
        assertFalse(ended.failed)
        assertFalse(ended.completionVerified)
        assertEquals(10L, ended.startedAtElapsedMs)
        assertEquals(20L, ended.finishedAtElapsedMs)
    }

    @Test
    fun explicitSuccessAndFailureAreDifferent() {
        policy.update(listOf(session("a"), session("b")))
        render(0)
        policy.markCompletionOutcome("a", false)
        policy.markCompletionOutcome("b", true)
        policy.markCompletionOutcome("b", false)
        policy.update(emptyList())
        val ended = render(100)
        assertTrue(ended.failed)
        assertTrue(ended.completionVerified)
        assertEquals(2, ended.completionCount)
    }

    @Test
    fun allMembersNeedExplicitOutcomes() {
        policy.update(listOf(session("a"), session("b")))
        render(0)
        policy.markCompletionOutcome("a", false)
        policy.update(listOf(session("b")))
        render(10)
        policy.update(emptyList())
        assertFalse(render(20).completionVerified)
        policy.markCompletionOutcome("b", false)
        assertTrue(policy.snapshot.completionVerified)
        assertFalse(policy.snapshot.failed)
    }

    @Test
    fun lateFailureCorrectsOnlyTheCurrentBatch() {
        policy.update(listOf(session("a")))
        render(0)
        policy.update(emptyList())
        render(100)
        policy.markCompletionOutcome("other", true)
        assertFalse(policy.snapshot.failed)
        policy.markCompletionOutcome("a", true)
        assertTrue(policy.snapshot.failed)
        assertTrue(policy.snapshot.completionVerified)
        assertEquals(100L, policy.snapshot.finishedAtElapsedMs)
    }

    @Test
    fun completionIsClearedWhenAppReturnsToForeground() {
        policy.update(listOf(session("a")))
        render(0)
        policy.update(emptyList())
        assertTrue(render(100).active)
        assertFalse(render(101, foreground = true).active)
        assertFalse(render(102).active)
    }

    @Test
    fun completionWhileInForegroundDoesNotLinger() {
        policy.update(listOf(session("a")))
        render(0, foreground = true)
        policy.markCompletionOutcome("a", false)
        policy.update(emptyList())
        assertFalse(render(100, foreground = true).active)
        assertFalse(render(101).active)
    }

    @Test
    fun completionLingersForAtMostFiveMinutes() {
        policy.update(listOf(session("a")))
        render(0)
        policy.markCompletionOutcome("a", false)
        policy.update(emptyList())
        render(100)
        assertTrue(render(300_099).active)
        assertFalse(render(300_100).active)
    }

    @Test
    fun newRunResetsTimeAndOldOutcomes() {
        policy.update(listOf(session("a")))
        render(0)
        policy.markCompletionOutcome("a", true)
        policy.update(emptyList())
        render(100)
        policy.update(listOf(session("a")))
        val running = render(200)
        assertFalse(running.completed)
        assertFalse(running.failed)
        assertEquals(200L, running.startedAtElapsedMs)
        policy.update(emptyList())
        assertFalse(render(300).completionVerified)
    }

    @Test
    fun reusedSessionMustReportEveryTurnWithinAnOverlappingBatch() {
        policy.update(listOf(session("a"), session("b")))
        render(0)
        policy.markCompletionOutcome("a", true)
        policy.update(listOf(session("b")))
        render(10)
        policy.update(listOf(session("a"), session("b")))
        render(20)
        policy.markCompletionOutcome("b", false)
        policy.update(emptyList())
        val ended = render(30)
        assertFalse(ended.completionVerified)
        assertTrue(ended.failed)
    }

    @Test
    fun explicitStartInvalidatesPriorOutcomeEvenWhenDebounceHidesTurnBoundary() {
        policy.update(listOf(session("a")))
        render(0)
        policy.markCompletionOutcome("a", false)
        policy.markSessionStarted("a")
        policy.update(emptyList())
        assertFalse(render(20).completionVerified)
    }

    @Test
    fun newStartClearsOldCompletionBeforeNextRunningListArrives() {
        policy.update(listOf(session("a")))
        render(0)
        policy.markCompletionOutcome("a", false)
        policy.update(emptyList())
        assertTrue(render(10).completed)
        policy.markSessionStarted("a")
        assertFalse(policy.snapshot.active)
        policy.update(listOf(session("a")))
        assertEquals(20L, render(20).startedAtElapsedMs)
        policy.update(emptyList())
        assertFalse(render(30).completionVerified)
    }

    @Test
    fun disablingAndReenablingReconstructsOnlyActiveRuns() {
        policy.update(listOf(session("a")))
        render(0)
        assertFalse(render(10, enabled = false).active)
        assertTrue(render(20).active)
        assertEquals(20L, policy.snapshot.startedAtElapsedMs)
        policy.update(emptyList())
        assertFalse(render(30, enabled = false).active)
        assertFalse(render(40).active)
    }

    @Test
    fun dismissDropsProducerAndCannotReappearOnLifecycleChange() {
        policy.update(listOf(session("a")))
        render(0)
        policy.dismiss()
        assertFalse(render(10).active)
        assertFalse(policy.hasRunning)
    }

    @Test
    fun duplicateSessionDoesNotInflateCompletionCount() {
        policy.update(listOf(session("a"), session("a")))
        assertEquals(1, render(0).sessions.size)
        policy.markCompletionOutcome("a", false)
        policy.update(emptyList())
        assertEquals(1, render(10).completionCount)
        assertTrue(policy.snapshot.completionVerified)
    }
}
