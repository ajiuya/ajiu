package com.aicode.feature.agent.domain.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDraftWindowTest {
    @Test
    fun firstDraftIsImmediateAndTheOriginalBoundaryRemainsEligible() {
        val window = RecoveryDraftWindow()
        val step = RecoveryStep(1, 0)
        assertTrue(window.isDue(step, 1_000))
        window.saved(step, 1_000)
        assertFalse(window.isDue(step, 1_499))
        assertTrue(window.isDue(step, 1_500))
    }

    @Test
    fun retriesNewStepsAndClockResetNeverInheritAnOldSuppressionWindow() {
        val window = RecoveryDraftWindow()
        window.saved(RecoveryStep(1, 0), 1_000)
        assertTrue(window.isDue(RecoveryStep(1, 1), 1_001))
        assertTrue(window.isDue(RecoveryStep(2, 0), 1_001))
        assertTrue(window.isDue(RecoveryStep(1, 0), 999))
    }

    @Test
    fun rejectedOrThrottledAttemptDoesNotPostponeTheNextEligibleSave() {
        val window = RecoveryDraftWindow()
        val step = RecoveryStep(1, 0)
        assertTrue(window.isDue(step, 1_000))
        assertTrue(window.isDue(step, 1_001))
        window.saved(step, 1_001)
        assertFalse(window.isDue(step, 1_500))
        assertTrue(window.isDue(step, 1_501))
    }
}
