package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSpeedTrackerTest {
    @Test
    fun emptyStream_hasNoSpeedSample() {
        val tracker = GenerationSpeedTracker()
        assertNull(tracker.sample(250))
        assertNull(tracker.finish(0, 1_000))
        assertNull(tracker.finish(100, null))
    }

    @Test
    fun updatesUseQuarterSecondIntervalAfterAFullObservationSecond() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(96), 1_000)
        assertNull(tracker.sample(1_250))
        assertNull(tracker.sample(1_500))
        assertNull(tracker.sample(1_750))
        tracker.append("aaaa", 1_750)
        val speed = tracker.sample(2_000)!!
        assertEquals(250L, GenerationSpeedTracker.UPDATE_INTERVAL_MS)
        assertEquals(1.0, speed.tokensPerSecond, 0.001)
        assertTrue(speed.estimated)
    }

    @Test
    fun asciiChunkBoundaries_doNotInflateTokenEstimate() {
        val tracker = GenerationSpeedTracker()
        repeat(4) { tracker.append("a", it * 250L) }
        assertEquals(0.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
        assertEquals(1.0, tracker.finish(0, 1_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun reasoningAndBodyShareTheSameCounter() {
        val tracker = GenerationSpeedTracker()
        tracker.append("思考", 0)
        tracker.append("正文", 750)
        assertEquals(2.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun noNewContentForASecond_reportsZeroInsteadOfOldSpeed() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(96), 0)
        tracker.append("aaaa", 750)
        assertEquals(1.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
        tracker.sample(1_250)
        tracker.sample(1_500)
        assertEquals(0.0, tracker.sample(1_750)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun finalProviderUsage_calibratesAverageAndClearsEstimatedFlag() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(100), 5_000)
        val final = tracker.finish(200, 2_000)!!
        assertEquals(100.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
    }

    @Test
    fun missingProviderUsage_keepsEstimatedFinalAverage() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(100), 9_000)
        val final = tracker.finish(0, 10_000)!!
        assertEquals(2.5, final.tokensPerSecond, 0.001)
        assertTrue(final.estimated)
    }

    @Test
    fun sameTimestampDoesNotExposeAnUnobservedLiveOrFinalRate() {
        val tracker = GenerationSpeedTracker()
        tracker.append("汉", 100)
        assertNull(tracker.sample(100))
        assertNull(tracker.finish(1, 0))
        assertNull(tracker.finish(1, 249))
        assertEquals(4.0, tracker.finish(1, 250)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun reset_discardsPreviousAttemptAndWaitingTime() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(1_000), 0)
        tracker.sample(250)
        tracker.reset()
        assertNull(tracker.sample(2_000))
        tracker.append("汉", 3_000)
        assertNull(tracker.sample(3_250))
        tracker.append("字", 3_750)
        assertEquals(1.0, tracker.sample(4_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun independentTrackers_doNotShareSessionSpeed() {
        val first = GenerationSpeedTracker()
        val second = GenerationSpeedTracker()
        first.append("a".repeat(96), 0)
        assertNull(second.sample(250))
        second.append("汉", 0)
        first.append("aaaa", 750)
        second.append("字", 750)
        assertEquals(1.0, first.sample(1_000)!!.tokensPerSecond, 0.001)
        assertEquals(1.0, second.sample(1_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun aBufferedFirstPacketIsNotMultipliedByAQuarterSecondDenominator() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(4_000), 10_000)
        assertNull(tracker.sample(10_250))
        assertNull(tracker.sample(10_500))
        assertNull(tracker.sample(10_750))
        assertEquals(0.0, tracker.sample(11_000)!!.tokensPerSecond, 0.001)
        val final = tracker.finish(200, 12_000)!!
        assertEquals(200.0 / 12.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
    }

    @Test
    fun aMidStreamBurstIsAveragedOverMultipleSecondsAndSmoothedWithoutClippingTokens() {
        val tracker = GenerationSpeedTracker()
        var previous = 0.0
        for (tick in 0..20) {
            val now = tick * 250L
            tracker.append("a".repeat(40), now)
            tracker.sample(now)?.let { previous = it.tokensPerSecond }
        }
        tracker.append("a".repeat(1_000), 5_250)
        val speed = tracker.sample(5_250)!!
        val unsmoothedWindowRate = 360.0 / 3.0
        assertTrue(speed.tokensPerSecond > previous)
        assertTrue(speed.tokensPerSecond < unsmoothedWindowRate)
        assertTrue(speed.estimated)
        assertEquals(460.0 / 5.5, tracker.finish(0, 5_500)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun steadyGenerationConvergesToTheSameRateWithoutAnArtificialSpeedLimit() {
        val tracker = GenerationSpeedTracker()
        var latest = 0.0
        for (tick in 0..80) {
            val now = tick * 250L
            tracker.append("a".repeat(1_000), now)
            tracker.sample(now)?.let { latest = it.tokensPerSecond }
        }
        assertEquals(1_000.0, latest, 0.01)
    }

    @Test
    fun repeatedSampleAtTheSameTimeCannotChangeTheSmoothingOrGrowTheWindow() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(96), 0)
        tracker.append("aaaa", 750)
        val initial = tracker.sample(1_000)!!
        tracker.append("a".repeat(400), 1_000)
        repeat(100) { assertEquals(initial, tracker.sample(1_000)) }
        val next = tracker.sample(1_250)!!
        assertTrue(next.tokensPerSecond > initial.tokensPerSecond)
        assertTrue(next.tokensPerSecond < 100.0)
    }

    @Test
    fun burstAfterAnIdlePeriodRampsUpInsteadOfImmediatelyShowingTheWholeBurstRate() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(400), 0)
        assertEquals(0.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
        tracker.append("a".repeat(400), 1_250)
        val resumed = tracker.sample(1_250)!!
        assertTrue(resumed.tokensPerSecond > 0.0)
        assertTrue(resumed.tokensPerSecond < 100.0 / 1.25)
    }

    @Test
    fun finalProviderAverageDoesNotInheritTheLiveSmoothing() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(100), 0)
        tracker.append("a".repeat(100), 750)
        tracker.sample(1_000)
        tracker.append("a".repeat(1_000), 1_250)
        val live = tracker.sample(1_250)!!
        val final = tracker.finish(120, 2_000)!!
        assertEquals(60.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
        assertTrue(live.estimated)
    }

    @Test
    fun bufferedFirstPacketCannotBeReattributedToTheNextSlowChunks() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(40_000), 0)
        tracker.append("aaaa", 750)
        assertEquals(1.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun simultaneousTextAndReasoningPacketsUseOneInitialBaseline() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(4_000), 1_000)
        tracker.append("思考".repeat(1_000), 1_000)
        tracker.append("正文", 1_750)
        assertEquals(2.0, tracker.sample(2_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun cumulativeToolArgumentsAreDifferencedByIdAndCombinedWithVisibleText() {
        val tracker = GenerationSpeedTracker()
        tracker.begin(0)
        tracker.appendToolArguments("one", 4, 0, 250)
        tracker.appendToolArguments("two", 4, 0, 500)
        tracker.appendToolArguments("one", 8, 2, 750)
        tracker.appendToolArguments("one", 8, 2, 750)
        tracker.append("body", 750)
        assertEquals(6.0, tracker.sample(1_000)!!.tokensPerSecond, 0.001)
        assertEquals(6.0, tracker.finish(0, 1_000)!!.tokensPerSecond, 0.001)
        val calibrated = tracker.finish(24, 1_000)!!
        assertEquals(24.0, calibrated.tokensPerSecond, 0.001)
        assertFalse(calibrated.estimated)
    }

    @Test
    fun toolArgumentReplacementsDoNotDoubleCountAndUnchangedSnapshotsDoNotRefreshIdleTime() {
        val tracker = GenerationSpeedTracker()
        tracker.begin(0)
        tracker.appendToolArguments("one", 400, 0, 250)
        tracker.appendToolArguments("one", 4, 2, 500)
        tracker.appendToolArguments("one", 4, 2, 1_500)
        assertEquals(0.0, tracker.sample(1_500)!!.tokensPerSecond, 0.001)
        assertEquals(2.0, tracker.finish(0, 1_500)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun resetDiscardsToolIdsAndFinalResponsesWithoutTimingDoNotInventIntervals() {
        val tracker = GenerationSpeedTracker()
        tracker.begin(0)
        tracker.appendToolArguments("same-id", 4_000, 0, 750)
        tracker.reset()
        assertNull(tracker.finish(1_000, null))
        assertNull(tracker.finish(0, 5_000))
        tracker.begin(5_000)
        tracker.appendToolArguments("same-id", 4, 0, 5_750)
        assertEquals(1.0, tracker.sample(6_000)!!.tokensPerSecond, 0.001)
    }

    @Test
    fun finalAverageIncludesTimeBeforeTheFirstVisibleContent() {
        val tracker = GenerationSpeedTracker()
        tracker.append("buffered content", 6_778)
        val final = tracker.finish(290, 10_000)!!
        assertEquals(29.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
    }

    @Test
    fun usageOnlyResponseUsesTheMeasuredRequestDuration() {
        val tracker = GenerationSpeedTracker()
        val final = tracker.finish(290, 10_000)!!
        assertEquals(29.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
        assertNull(tracker.sample(10_000))
    }

    @Test
    fun missingRequestDurationDoesNotTurnALiveEstimateIntoAFinalAverage() {
        val tracker = GenerationSpeedTracker()
        tracker.append("a".repeat(400), 0)
        tracker.append("a".repeat(400), 750)
        val live = tracker.sample(1_000)!!
        assertTrue(live.tokensPerSecond > 0)
        assertTrue(live.estimated)
        assertNull(tracker.finish(290, null))
    }

    @Test
    fun shortResponseStillHasItsOriginalFinalUsageEvenWithoutALiveSample() {
        val tracker = GenerationSpeedTracker()
        tracker.append("body", 1_000)
        assertNull(tracker.sample(1_250))
        val final = tracker.finish(40, 500)!!
        assertEquals(80.0, final.tokensPerSecond, 0.001)
        assertFalse(final.estimated)
    }
}
