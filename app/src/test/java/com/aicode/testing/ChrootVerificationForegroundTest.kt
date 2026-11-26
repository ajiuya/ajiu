package com.aicode.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ChrootVerificationForegroundTest {
    @Test fun unreadyGateRejectsWithoutLeakingAnUnboundedMessage() {
        val gate = ChrootVerificationForeground()
        val failure = assertThrows(VerificationAssertion::class.java) { gate.requireUsable() }
        assertEquals("verification_foreground_required", failure.marker)
        assertEquals(null, failure.message)
        assertFalse(gate.snapshot().established)
        assertFalse(gate.snapshot().lost)
    }

    @Test fun closingAnUnarmedPreparationGateNeverEstablishesOrRestartsIt() {
        val gate = ChrootVerificationForeground()
        var stops = 0
        gate.attachStopCallback { stops++ }
        gate.beginClosing()
        gate.detachStopCallback()
        gate.markClosed()
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        assertTrue(gate.snapshot().closed)
        assertFalse(gate.snapshot().established)
        assertFalse(gate.snapshot().lost)
        assertFalse(gate.snapshot().usable)
        assertEquals(0, stops)
    }

    @Test fun startedResumedAndFocusedAreAllRequiredBeforeArming() {
        val gate = ChrootVerificationForeground()
        gate.onStarted()
        gate.onWindowFocusChanged(true)
        assertFalse(gate.snapshot().usable)
        gate.onResumed()
        gate.requireUsable()
        assertTrue(gate.snapshot().established)
        val other = ChrootVerificationForeground()
        other.onResumed()
        other.onWindowFocusChanged(true)
        assertFalse(other.snapshot().usable)
        other.onStarted()
        other.requireUsable()
    }

    @Test fun initialFalseFocusPauseStopAndDestroyDoNotLatchLoss() {
        val gate = ChrootVerificationForeground()
        var stops = 0
        gate.attachStopCallback { stops++ }
        gate.onWindowFocusChanged(false)
        gate.onStarted()
        gate.onPaused()
        gate.onStopped()
        gate.onDestroyed()
        assertFalse(gate.snapshot().established)
        assertFalse(gate.snapshot().lost)
        assertEquals(0, stops)
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        gate.requireUsable()
    }

    @Test fun pauseStopDestroyAndFocusLossLatchAndCannotBeRearmed() {
        val losses: List<(ChrootVerificationForeground) -> Unit> = listOf(
            ChrootVerificationForeground::onPaused,
            ChrootVerificationForeground::onStopped,
            ChrootVerificationForeground::onDestroyed,
            { it.onWindowFocusChanged(false) }
        )
        for (lose in losses) {
            val gate = readyGate()
            lose(gate)
            assertTrue(gate.snapshot().lost)
            gate.onStarted()
            gate.onResumed()
            gate.onWindowFocusChanged(true)
            assertTrue(gate.snapshot().established)
            assertFalse(gate.snapshot().usable)
            assertThrows(VerificationAssertion::class.java) { gate.requireUsable() }
        }
    }

    @Test fun closingDisablesPermissionWithoutReportingExpectedTeardownAsLoss() {
        val gate = readyGate()
        var stops = 0
        gate.attachStopCallback { stops++ }
        gate.beginClosing()
        gate.onWindowFocusChanged(false)
        gate.onPaused()
        gate.onStopped()
        gate.onDestroyed()
        gate.markClosed()
        assertTrue(gate.snapshot().closing)
        assertTrue(gate.snapshot().closed)
        assertFalse(gate.snapshot().lost)
        assertFalse(gate.snapshot().usable)
        assertEquals(0, stops)
    }

    @Test fun failedStartupCannotBeEstablishedByClosingOrLateResume() {
        val gate = ChrootVerificationForeground()
        gate.beginClosing()
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        gate.markClosed()
        assertFalse(gate.snapshot().established)
        assertFalse(gate.snapshot().lost)
        assertFalse(gate.snapshot().usable)
        assertTrue(gate.snapshot().closed)
    }

    @Test fun stopCallbackRunsAtMostOnceEvenAfterResumeAndCallbackReplacement() {
        val gate = readyGate()
        var stops = 0
        gate.attachStopCallback { stops++ }
        gate.onWindowFocusChanged(false)
        gate.onPaused()
        gate.onStopped()
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        gate.onWindowFocusChanged(false)
        gate.detachStopCallback()
        gate.attachStopCallback { stops++ }
        assertEquals(1, stops)
    }

    @Test fun alreadyLostGateDeliversOnePendingStopWhenFixtureAttaches() {
        val gate = readyGate()
        gate.onPaused()
        var stops = 0
        gate.attachStopCallback { stops++ }
        gate.onStopped()
        gate.detachStopCallback()
        gate.attachStopCallback { stops++ }
        assertEquals(1, stops)
        assertFalse(gate.snapshot().usable)
    }

    @Test fun stopCallbackRunsOutsideTheStateLock() {
        val gate = readyGate()
        val detached = CountDownLatch(1)
        var worker: Thread? = null
        gate.attachStopCallback {
            worker = thread(isDaemon = true) {
                gate.detachStopCallback()
                detached.countDown()
            }
            assertTrue(detached.await(1L, TimeUnit.SECONDS))
        }
        try {
            gate.onPaused()
        } finally {
            worker?.join(1_000L)
        }
        assertEquals(0L, detached.count)
    }

    @Test fun stopCallbackFailurePropagatesWithoutRestoringPermissionOrRetryingStop() {
        val gate = readyGate()
        val failure = IllegalStateException("private-stop-failure")
        var stops = 0
        gate.attachStopCallback { stops++; throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { gate.onPaused() })
        gate.onStopped()
        gate.onResumed()
        assertEquals(1, stops)
        assertTrue(gate.snapshot().lost)
        assertFalse(gate.snapshot().usable)
    }

    @Test fun injectedSourceCheckRejectsTheNextLaunchAfterFocusLoss() {
        val gate = readyGate()
        val sourceCheck: () -> Unit = gate::checkCurrent
        var authorizations = 0
        var launches = 0
        val launch = {
            sourceCheck()
            authorizations++
            sourceCheck()
            launches++
        }
        launch()
        gate.onWindowFocusChanged(false)
        gate.onWindowFocusChanged(true)
        assertThrows(CancellationException::class.java) { launch() }
        assertEquals(1, authorizations)
        assertEquals(1, launches)
    }

    @Test fun currentCheckRejectsUnreadyAndLatchedLossWithAnExceptionInsteadOfAnError() {
        val gate = ChrootVerificationForeground()
        val unready = assertThrows(Exception::class.java) { gate.checkCurrent() }
        assertEquals(CancellationException::class.java, unready.javaClass)
        assertFalse(AssertionError::class.java.isInstance(unready))
        assertEquals("verification_foreground_required", unready.message)
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        gate.checkCurrent()
        gate.onWindowFocusChanged(false)
        gate.onWindowFocusChanged(true)
        val lost = assertThrows(Exception::class.java) { gate.checkCurrent() }
        assertEquals(CancellationException::class.java, lost.javaClass)
        assertFalse(AssertionError::class.java.isInstance(lost))
        assertEquals("verification_foreground_required", lost.message)
        assertTrue(gate.snapshot().lost)
    }

    private fun readyGate() = ChrootVerificationForeground().apply {
        onStarted()
        onResumed()
        onWindowFocusChanged(true)
        requireUsable()
    }
}
