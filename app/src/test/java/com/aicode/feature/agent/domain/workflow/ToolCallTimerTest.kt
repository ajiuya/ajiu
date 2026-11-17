package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallTimerTest {
    @Test
    fun wallClockRollbackDoesNotChangeElapsedDurationOrCapturedStart() {
        var wall = 1_700_000_000_000L
        var elapsed = 4_000L
        val timer = ToolCallTimer({ wall }, { elapsed })
        val started = timer.start()

        wall -= 60_000L
        elapsed += 125L
        val finished = timer.finish(started)

        assertEquals(1_700_000_000_000L, finished.startedAtMs)
        assertEquals(125L, finished.durationMs)
        assertEquals(4_000L, started.startedElapsedMs)
    }

    @Test
    fun wallClockAndElapsedRealtimeUseTheirOwnTimeBases() {
        var wallReads = 0
        var elapsed = 12_345L
        val timer = ToolCallTimer({ wallReads++; 1_800_000_000_000L }, { elapsed })
        val started = timer.start()

        elapsed = 12_387L
        assertEquals(ToolCallTimer.Started(1_800_000_000_000L, 12_345L), started)
        assertEquals(ToolCallTimer.Finished(1_800_000_000_000L, 42L), timer.finish(started))
        assertEquals(1, wallReads)
    }

    @Test
    fun elapsedClockRollbackIsClampedToZero() {
        var elapsed = 1_000L
        val timer = ToolCallTimer({ 50_000L }, { elapsed })
        val started = timer.start()

        elapsed = 900L

        assertEquals(ToolCallTimer.Finished(50_000L, 0L), timer.finish(started))
    }

    @Test
    fun twoIndependentInstancesDoNotShareStartsOrDurations() {
        var slowElapsed = 1_000L
        var fastElapsed = 90_000L
        val slow = ToolCallTimer({ 10_000L }, { slowElapsed })
        val fast = ToolCallTimer({ 20_000L }, { fastElapsed })
        val slowStart = slow.start()
        val fastStart = fast.start()

        fastElapsed += 25L
        val fastFinished = fast.finish(fastStart)
        slowElapsed += 800L
        val slowFinished = slow.finish(slowStart)

        assertEquals(ToolCallTimer.Finished(20_000L, 25L), fastFinished)
        assertEquals(ToolCallTimer.Finished(10_000L, 800L), slowFinished)
        assertEquals(25L, fastFinished.durationMs)
    }

    @Test
    fun overlappingStartsOnOneTimerKeepIndependentSnapshots() {
        var wall = 10_000L
        var elapsed = 1_000L
        val timer = ToolCallTimer({ wall }, { elapsed })
        val first = timer.start()
        wall += 200L
        elapsed += 200L
        val second = timer.start()

        elapsed += 50L
        val secondFinished = timer.finish(second)
        elapsed += 550L
        val firstFinished = timer.finish(first)

        assertEquals(ToolCallTimer.Finished(10_200L, 50L), secondFinished)
        assertEquals(ToolCallTimer.Finished(10_000L, 800L), firstFinished)
        assertEquals(50L, secondFinished.durationMs)
    }
}
