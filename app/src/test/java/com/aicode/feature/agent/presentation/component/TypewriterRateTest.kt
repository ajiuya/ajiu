package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypewriterRateTest {
    @Test
    fun defaultIsThirtyCodePointsPerSecond() {
        assertEquals(30f, typewriterRate(100f), 0f)
        assertEquals(30f, typewriterDrainRate(100f), 0f)
        assertEquals(30.0, advanceTypewriter(0.0, 10_000, 1_000_000_000L), 0.000001)
    }

    @Test
    fun everySettingCapsStreamingAndDrainRegardlessOfBacklog() {
        for (limit in 10..120 step 5) {
            for (lag in listOf(0.01f, 12f, 240f, 10_000f)) {
                assertEquals(limit.toFloat(), typewriterRate(lag, limit), 0f)
                assertEquals(limit.toFloat(), typewriterDrainRate(lag, limit), 0f)
            }
        }
    }

    @Test
    fun noBacklogDoesNotSpendTimeOrAdvancePastUpstream() {
        assertEquals(0f, typewriterRate(0f), 0f)
        assertEquals(0f, typewriterDrainRate(-5f), 0f)
        assertEquals(2.0, advanceTypewriter(0.0, 2, 1_000_000_000L), 0f.toDouble())
        assertEquals(2.0, advanceTypewriter(2.0, 2, 10_000_000_000L), 0f.toDouble())
        assertEquals(2.3, advanceTypewriter(2.0, 100, 10_000_000L), 0.000001)
    }

    @Test
    fun frequentDeltasPreserveElapsedAndFractionalProgress() {
        var shown = 0.0
        repeat(1_000) { tick ->
            shown = advanceTypewriter(shown, (tick + 1) * 10, 1_000_000L)
        }
        assertEquals(30.0, shown, 0.000001)
    }

    @Test
    fun changingRatePreservesFractionalProgress() {
        var shown = advanceTypewriter(0.0, 1_000, 37_000_000L, 30)
        assertEquals(1.11, shown, 0.000001)
        shown = advanceTypewriter(shown, 1_000, 500_000_000L, 10)
        assertEquals(6.11, shown, 0.000001)
        shown = advanceTypewriter(shown, 1_000, 500_000_000L, 120)
        assertEquals(66.11, shown, 0.000001)
    }

    @Test
    fun streamEndHasNoDeadlineOrBacklogAcceleration() {
        val afterFormerDeadline = advanceTypewriter(0.0, 300, 600_000_000L)
        assertEquals(18.0, afterFormerDeadline, 0.000001)
        val afterTwoSeconds = advanceTypewriter(afterFormerDeadline, 300, 1_400_000_000L)
        assertEquals(60.0, afterTwoSeconds, 0.000001)
        assertFalse(afterTwoSeconds >= 300)
        assertEquals(300.0, advanceTypewriter(afterTwoSeconds, 300, 8_000_000_000L), 0.000001)
    }

    @Test
    fun slowUpstreamDoesNotCauseBurstAllowance() {
        var shown = 0.0
        repeat(100) { tick ->
            shown = advanceTypewriter(shown, tick / 20 + 1, 10_000_000L)
        }
        val beforeBurst = shown
        shown = advanceTypewriter(shown, 10_000, 24_000_000L)
        assertTrue(shown - beforeBurst <= 30 * 0.024 + 0.000001)
    }

    @Test
    fun settingsAreClampedAtInputBoundary() {
        assertEquals(10f, typewriterRate(1_000f, 1), 0f)
        assertEquals(120f, typewriterDrainRate(1_000f, 999), 0f)
        assertEquals(5.0, advanceTypewriter(5.0, 100, -1L), 0.0)
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
