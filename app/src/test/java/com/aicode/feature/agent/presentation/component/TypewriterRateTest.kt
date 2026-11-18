package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypewriterRateTest {
    @Test
    fun streamingTakesTheGreaterOfTheFollowAndCatchupRates() {
        assertEquals(190f, typewriterRate(lag = 4f, arrivalRate = 200f, charsPerSecond = 350), 0.001f)
        assertEquals(100f, typewriterRate(lag = 12f, arrivalRate = 20f, charsPerSecond = 350), 0.001f)
        assertEquals(30f, typewriterRate(lag = 1f, arrivalRate = 0f, charsPerSecond = 350), 0.001f)
    }

    @Test
    fun everyIntegerSettingCapsStreamingAndDrainRegardlessOfBacklog() {
        for (limit in 30..350) {
            for (lag in listOf(0.01f, 12f, 240f, 10_000f)) {
                val rate = typewriterRate(lag, 200f, limit)
                val drainRate = typewriterDrainRate(lag, limit)
                assertTrue(rate in 30f..limit.toFloat())
                assertTrue(drainRate in 30f..limit.toFloat())
                if (lag >= 240f) {
                    assertEquals(limit.toFloat(), rate, 0f)
                    assertEquals(limit.toFloat(), drainRate, 0f)
                }
            }
        }
    }

    @Test
    fun noBacklogDoesNotSpendTimeOrAdvancePastUpstream() {
        assertEquals(0f, typewriterRate(0f, 100f, 350), 0f)
        assertEquals(0f, typewriterDrainRate(-5f, 350), 0f)
        assertEquals(2.0, advanceTypewriter(0.0, 2, 1_000_000_000L, 350f), 0.0)
        assertEquals(2.0, advanceTypewriter(2.0, 2, 10_000_000_000L, 0f), 0.0)
        assertEquals(2.3, advanceTypewriter(2.0, 100, 10_000_000L, 30f), 0.000001)
    }

    @Test
    fun frequentDeltasPreserveElapsedAndFractionalProgress() {
        var shown = 0.0
        val arrivals = TypewriterArrivalRate()
        arrivals.rateAt(0L, 0)
        repeat(1_000) { tick ->
            val available = (tick + 1) * 10
            val observed = arrivals.rateAt((tick + 1) * 1_000_000L, available)
            val rate = typewriterRate((available - shown).toFloat(), observed, 350)
            shown = advanceTypewriter(shown, available, 1_000_000L, rate)
        }
        assertEquals(350.0, shown, 0.000001)
    }

    @Test
    fun changingRatePreservesFractionalProgress() {
        var shown = advanceTypewriter(0.0, 1_000, 37_000_000L, 30f)
        assertEquals(1.11, shown, 0.000001)
        shown = advanceTypewriter(shown, 1_000, 500_000_000L, 75f)
        assertEquals(38.61, shown, 0.000001)
        shown = advanceTypewriter(shown, 1_000, 500_000_000L, 350f)
        assertEquals(213.61, shown, 0.000001)
    }

    @Test
    fun shortDrainUsesTheTargetDurationUnlessTheSelectedLimitIsLower() {
        assertEquals(60f, typewriterDrainRate(12f, 350), 0.001f)
        assertEquals(30f, typewriterDrainRate(12f, 30), 0.001f)
        assertEquals(12.0, advanceTypewriter(0.0, 12, 200_000_000L, typewriterDrainRate(12f, 350)), 0.000001)
    }

    @Test
    fun longDrainCannotSpendMoreThanTheSelectedLimitEvenPastTheRc2Deadline() {
        val rate = typewriterDrainRate(300f, 350)
        val afterFormerDeadline = advanceTypewriter(0.0, 300, 600_000_000L, rate)
        assertEquals(210.0, afterFormerDeadline, 0.000001)
        assertEquals(300.0, advanceTypewriter(afterFormerDeadline, 300, 300_000_000L, rate), 0.000001)
    }

    @Test
    fun arrivalEstimateIgnoresRecompositionsAndExpiresStaleSamples() {
        val arrivals = TypewriterArrivalRate()
        assertEquals(0f, arrivals.rateAt(0L, 100), 0f)
        assertEquals(0f, arrivals.rateAt(50_000_000L, 100), 0f)
        assertEquals(200f, arrivals.rateAt(100_000_000L, 120), 0.001f)
        assertEquals(100f, arrivals.rateAt(200_000_000L, 120), 0.001f)
        assertEquals(50f, arrivals.rateAt(500_000_000L, 125), 0.001f)
        assertEquals(50f, arrivals.rateAt(501_000_000L, 125), 0.001f)
        assertEquals(10f, arrivals.rateAt(600_000_000L, 125), 0.001f)
        assertEquals(0f, arrivals.rateAt(1_001_000_000L, 125), 0f)
        assertEquals(4f, arrivals.rateAt(1_050_000_000L, 127), 0.001f)
        assertEquals(6f, arrivals.rateAt(1_050_000_000L, 128), 0.001f)
        arrivals.clear()
        assertEquals(0f, arrivals.rateAt(1_075_000_000L, 1), 0f)
        assertEquals(80f, arrivals.rateAt(1_100_000_000L, 3), 0.001f)
    }

    @Test
    fun settingsAreClampedAtInputBoundary() {
        assertEquals(30f, typewriterRate(1_000f, 1_000f, 1), 0f)
        assertEquals(350f, typewriterDrainRate(1_000f, 999), 0f)
        assertEquals(5.0, advanceTypewriter(5.0, 100, -1L, 350f), 0.0)
    }

    @Test
    fun cursorAdvancesThroughLongPrefixesWithoutRecreatingUnchangedFrames() {
        val prefix = "中🚀文𝄞".repeat(10_000)
        val cursor = TypewriterCursor(prefix)
        assertFalse(cursor.update(prefix + "尾巴"))
        assertEquals(40_002, cursor.availableCodePoints)
        val frame = cursor.render(40_001)
        assertEquals(prefix + "尾", frame)
        repeat(20) {
            assertSame(frame, cursor.render(40_001))
        }
        assertEquals(prefix + "尾巴", cursor.render(40_002))
    }

    @Test
    fun cursorCountsSplitSurrogatesOnlyWhenComplete() {
        val cursor = TypewriterCursor("")
        assertFalse(cursor.update("甲\uD83D"))
        assertEquals(1, cursor.availableCodePoints)
        assertEquals("甲", cursor.render(1))
        assertFalse(cursor.update("甲🚀乙\uD834"))
        assertEquals(3, cursor.availableCodePoints)
        assertEquals("甲🚀乙", cursor.render(3))
        assertFalse(cursor.update("甲🚀乙𝄞"))
        assertEquals(4, cursor.availableCodePoints)
        assertEquals("甲🚀乙𝄞", cursor.render(4))
    }

    @Test
    fun cursorResetsOnReplacementAndPreservesMatchingAppends() {
        val cursor = TypewriterCursor("old ")
        assertFalse(cursor.update("old text 🚀"))
        assertEquals("old te", cursor.render(6))
        assertTrue(cursor.update("new 𝄞 text"))
        assertEquals("", cursor.render(0))
        assertEquals("new 𝄞", cursor.render(5))
        assertFalse(cursor.update("new 𝄞 text again"))
        assertEquals("new 𝄞", cursor.render(5))
        assertEquals("new 𝄞 text again", cursor.render(cursor.availableCodePoints))
    }

    @Test
    fun unicodeProgressCountsCodePointsAndKeepsSurrogatesWhole() {
        val text = "甲🚀乙𝄞尾"
        assertEquals(5, typewriterCodePointCount(text))
        assertEquals("甲🚀", truncateToCodePoints(text, 2))
        assertEquals("甲🚀乙𝄞", truncateToCodePoints(text, 4))
        assertEquals(text, truncateToCodePoints(text, 5))
        assertEquals("", truncateToCodePoints(text, 0))
        assertEquals("甲", truncateToCodePoints("甲\uD83D", 2))
        assertEquals(1, typewriterCodePointCount("甲\uD83D"))
        assertEquals("甲🚀", truncateToCodePoints("甲🚀", 2))
    }
}
