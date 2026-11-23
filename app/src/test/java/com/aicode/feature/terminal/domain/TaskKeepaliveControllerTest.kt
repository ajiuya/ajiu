package com.aicode.feature.terminal.domain

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import io.mockk.mockk
import io.mockk.verify
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TaskKeepaliveControllerTest {
    private lateinit var context: StartingContext
    private val owner = "controller-test:owner"
    private val service = mockk<TerminalKeepaliveService>(relaxed = true)

    @Before
    fun setUp() {
        context = StartingContext(RuntimeEnvironment.getApplication())
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onAppVisible(context)
    }

    @After
    fun tearDown() {
        TaskKeepaliveController.release(context, owner)
        TaskKeepaliveController.release(context, "$owner:input")
        TaskKeepaliveController.release(context, "$owner:other")
        TaskKeepaliveController.detach(service)
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onTaskRemoved(context)
        TaskKeepaliveController.onAppVisible(context)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun releaseAndDisableNeverStartAService() {
        repeat(3) { TaskKeepaliveController.release(context, owner) }
        TaskKeepaliveController.setPersistent(context, false)
        assertEquals(0, context.starts)
        assertFalse(TaskKeepaliveController.isProtected())
    }

    @Test
    fun rejectedForegroundStartIsNotProtectionAndReleaseDoesNotRetry() {
        context.reject = true
        assertFalse(TaskKeepaliveController.acquire(context, owner, cpu = true))
        assertEquals(1, context.starts)
        assertFalse(TaskKeepaliveController.snapshot().startPending)
        assertFalse(TaskKeepaliveController.isProtected())
        TaskKeepaliveController.release(context, owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(1, context.starts)
    }

    @Test
    fun acceptedStartDoesNotReturnProtectedUntilServiceConfirms() {
        assertFalse(TaskKeepaliveController.acquire(context, owner, cpu = true))
        assertTrue(TaskKeepaliveController.snapshot().startPending)
        assertFalse(TaskKeepaliveController.isProtected())
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        assertEquals(1, context.starts)
        TaskKeepaliveController.attach(service)
        assertTrue(TaskKeepaliveController.acquire(context, owner, cpu = true))
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("FGS rejected"))
        assertFalse(TaskKeepaliveController.isProtected())
    }

    @Test
    fun missingForegroundConfirmationIsBoundedAndUnavailable() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(TaskKeepaliveController.snapshot().startPending)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertFalse(TaskKeepaliveController.isProtected())
        TaskKeepaliveController.release(context, owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(1, context.starts)
    }

    @Test
    fun taskRemovalSuppressesLateAcquiresUntilForegroundResumes() {
        TaskKeepaliveController.onTaskRemoved(context)
        assertFalse(TaskKeepaliveController.acquire(context, owner, cpu = true))
        assertEquals(0, context.starts)
        assertFalse(TaskKeepaliveController.snapshot().cpuRequired)
        TaskKeepaliveController.onAppVisible(context)
        assertEquals(1, context.starts)
        assertTrue(TaskKeepaliveController.snapshot().cpuRequired)
        assertFalse(TaskKeepaliveController.isProtected())
    }

    @Test
    fun staleWorkerReadCannotOverrideUserDisable() {
        val revision = TaskKeepaliveController.persistentRevision()
        TaskKeepaliveController.setPersistent(context, false)
        assertFalse(TaskKeepaliveController.restorePersistent(context, revision))
        assertFalse(TerminalKeepaliveService.enablePersistent(context))
        assertEquals(0, context.starts)
    }

    @Test
    fun renewalReplacesOldDeadlineAndCloseCancelsNewDeadline() {
        TaskKeepaliveController.acquireForInput(context, owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30))
        TaskKeepaliveController.acquireForInput(context, owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30))
        assertTrue(TaskKeepaliveController.snapshot().cpuRequired)
        TaskKeepaliveController.release(context, owner)
        val startsAfterClose = context.starts
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(60))
        assertFalse(TaskKeepaliveController.snapshot().serviceRequired)
        assertEquals(startsAfterClose, context.starts)
    }

    @Test
    fun inputLeaseExpiresAfterSixtyMinutes() {
        TaskKeepaliveController.acquireForInput(context, owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(60))
        assertFalse(TaskKeepaliveController.snapshot().cpuRequired)
        assertFalse(TaskKeepaliveController.snapshot().serviceRequired)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(4, context.starts)
    }

    @Test
    fun equivalentLeaseChangesAndInputRenewalsDoNotReconcileAgain() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        TaskKeepaliveController.attach(service)
        repeat(30) {
            TaskKeepaliveController.acquire(context, owner, cpu = true)
            TaskKeepaliveController.acquireForInput(context, "$owner:input")
        }
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { service.reconcileTasks() }
        TaskKeepaliveController.release(context, "$owner:input")
        verify(exactly = 0) { service.reconcileTasks() }
    }

    @Test
    fun mainThreadTaskStartAndEndReconcileImmediatelyWithoutWaitingForTheHandler() {
        TaskKeepaliveController.attach(service)
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        verify(exactly = 1) { service.reconcileTasks() }
        TaskKeepaliveController.release(context, owner)
        verify(exactly = 2) { service.reconcileTasks() }
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 2) { service.reconcileTasks() }
    }

    @Test
    fun offThreadReconcileIsCoalescedAndDetachedServiceNeverReceivesTheQueuedWork() {
        TaskKeepaliveController.attach(service)
        thread {
            repeat(30) { TaskKeepaliveController.acquire(context, owner, cpu = true) }
        }.join()
        verify(exactly = 0) { service.reconcileTasks() }
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { service.reconcileTasks() }
        thread { TaskKeepaliveController.release(context, owner) }.join()
        TaskKeepaliveController.detach(service)
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { service.reconcileTasks() }
    }

    @Test
    fun readonlyStateReportsConfirmedProtectionAndTaskRemovalWithoutRequestingStarts() {
        assertFalse(TaskKeepaliveController.states is kotlinx.coroutines.flow.MutableStateFlow<*>)
        assertEquals(TaskKeepaliveController.snapshot(), TaskKeepaliveController.states.value)
        TaskKeepaliveController.acquire(context, owner, cpu = true, network = true)
        assertFalse(TaskKeepaliveController.states.value.protected)
        TaskKeepaliveController.attach(service)
        assertTrue(TaskKeepaliveController.states.value.protected)
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("Foreground unavailable"))
        assertFalse(TaskKeepaliveController.states.value.protected)
        TaskKeepaliveController.release(context, owner)
        val starts = context.starts
        TaskKeepaliveController.onTaskRemoved(context)
        assertTrue(TaskKeepaliveController.states.value.suspended)
        assertFalse(TaskKeepaliveController.states.value.protected)
        assertEquals(starts, context.starts)
        assertEquals(TaskKeepaliveController.snapshot(), TaskKeepaliveController.states.value)
        TaskKeepaliveController.onAppVisible(context)
        assertFalse(TaskKeepaliveController.states.value.suspended)
        assertEquals(starts, context.starts)
    }

    @Test
    fun rejectedStartsUseThreeBackoffsAndRepeatedAcquiresOrWorkersCannotBypassTheBudget() {
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(0, TaskKeepaliveController.states.value.recoveryAttempts)
        repeat(30) {
            TaskKeepaliveController.acquire(context, owner, cpu = true)
            TaskKeepaliveController.setPersistent(context, true)
            TaskKeepaliveController.retryPersistent(context)
            TaskKeepaliveController.restorePersistent(context, TaskKeepaliveController.persistentRevision())
        }
        assertEquals(1, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(1, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2, context.starts)
        assertEquals(1, TaskKeepaliveController.states.value.recoveryAttempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(2, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(3, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_999))
        assertEquals(3, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(4, context.starts)
        assertEquals(3, TaskKeepaliveController.states.value.recoveryAttempts)
        assertTrue(TaskKeepaliveController.states.value.recoveryExhausted)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        repeat(30) {
            TaskKeepaliveController.acquire(context, owner, cpu = true)
            TaskKeepaliveController.retryPersistent(context)
            TaskKeepaliveController.restorePersistent(context, TaskKeepaliveController.persistentRevision())
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(4, context.starts)
        assertFalse(TaskKeepaliveController.isProtected())
    }

    @Test
    fun everyUnconfirmedRecoveryTimesOutAndStopsAfterThreeAttempts() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        listOf(1L, 3L, 10L).forEachIndexed { index, delay ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertFalse(TaskKeepaliveController.states.value.startPending)
            assertTrue(TaskKeepaliveController.states.value.recoveryPending)
            assertEquals(index + 1, context.starts)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(delay))
            assertTrue(TaskKeepaliveController.states.value.startPending)
            assertEquals(index + 2, context.starts)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(TaskKeepaliveController.states.value.recoveryExhausted)
        assertFalse(TaskKeepaliveController.states.value.startPending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1))
        assertEquals(4, context.starts)
    }

    @Test
    fun attachAndDetachDoNotResetRecoveryAttemptsAndOldServiceCallbacksAreIgnored() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        TaskKeepaliveController.attach(service)
        repeat(3) { index ->
            TaskKeepaliveController.detach(service)
            assertTrue(TaskKeepaliveController.states.value.recoveryPending)
            repeat(20) { TaskKeepaliveController.acquire(context, owner, cpu = true) }
            assertEquals(index + 1, context.starts)
            val delay = listOf(1L, 3L, 10L)[index]
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(delay))
            TaskKeepaliveController.attach(service)
            assertTrue(TaskKeepaliveController.isProtected())
            assertEquals(index + 1, TaskKeepaliveController.states.value.recoveryAttempts)
            assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        }
        TaskKeepaliveController.detach(service)
        assertTrue(TaskKeepaliveController.states.value.recoveryExhausted)
        repeat(30) { TaskKeepaliveController.acquire(context, owner, cpu = true) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(4, context.starts)
        val replacement = mockk<TerminalKeepaliveService>(relaxed = true)
        TaskKeepaliveController.onAppVisible(context)
        try {
            TaskKeepaliveController.attach(replacement)
            TaskKeepaliveController.detach(service)
            TaskKeepaliveController.foregroundFailed(service, IllegalStateException("old instance"))
            assertTrue(TaskKeepaliveController.isProtected())
            assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        } finally { TaskKeepaliveController.detach(replacement) }
    }

    @Test
    fun foregroundFailureRecoversWithoutRequiringAnotherAcquireAndKeepsTheBudget() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        TaskKeepaliveController.attach(service)
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("FGS rejected"))
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, context.starts)
        TaskKeepaliveController.attach(service)
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("FGS rejected again"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(3, context.starts)
        assertEquals(2, TaskKeepaliveController.states.value.recoveryAttempts)
    }

    @Test
    fun lateForegroundFailureDuringTimeoutBackoffReplacesStaleRecoveryWithoutResettingBudget() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        val recoveryField = TaskKeepaliveController.javaClass.getDeclaredField("recovery").apply { isAccessible = true }
        val staleRecovery = recoveryField.get(TaskKeepaliveController) as Runnable
        val sequence = TaskKeepaliveController.currentStartSequence()
        context.reject = true
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("late creation failed"), sequence)
        staleRecovery.run()
        assertEquals(1, context.starts)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(0, TaskKeepaliveController.states.value.recoveryAttempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(1, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2, context.starts)
        assertEquals(1, TaskKeepaliveController.states.value.recoveryAttempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(3, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(4, context.starts)
        assertEquals(3, TaskKeepaliveController.states.value.recoveryAttempts)
        assertTrue(TaskKeepaliveController.states.value.recoveryExhausted)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
    }

    @Test
    fun confirmationCancelsItsTimeoutAndStaleTimeoutCannotFailANewerStart() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val firstSequence = TaskKeepaliveController.currentStartSequence()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        TaskKeepaliveController.attach(service, firstSequence)
        TaskKeepaliveController.detach(service)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val secondSequence = TaskKeepaliveController.currentStartSequence()
        assertFalse(TaskKeepaliveController.attach(service, firstSequence))
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("late failure"), firstSequence)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(TaskKeepaliveController.states.value.startPending)
        assertEquals(2, context.starts)
        assertTrue(TaskKeepaliveController.attach(service, secondSequence))
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("old same-instance failure"), firstSequence)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertTrue(TaskKeepaliveController.isProtected())
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
    }

    @Test
    fun lastReleaseInvalidatesPendingConfirmationAndOnlyANewDemandResetsTheBudget() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val oldSequence = TaskKeepaliveController.currentStartSequence()
        TaskKeepaliveController.release(context, owner)
        assertFalse(TaskKeepaliveController.states.value.startPending)
        assertFalse(TaskKeepaliveController.attach(service, oldSequence))
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14))
        assertEquals(5, context.starts)
        assertTrue(TaskKeepaliveController.states.value.recoveryExhausted)
        TaskKeepaliveController.release(context, owner)
        assertEquals(0, TaskKeepaliveController.states.value.recoveryAttempts)
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        assertEquals(6, context.starts)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertFalse(TaskKeepaliveController.states.value.recoveryExhausted)
    }

    @Test
    fun suspendCancelsRecoveryAndLateCallbacksUntilAnExplicitForegroundReset() {
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val failedSequence = TaskKeepaliveController.currentStartSequence()
        TaskKeepaliveController.onTaskRemoved(context)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertFalse(TaskKeepaliveController.attach(service, failedSequence))
        TaskKeepaliveController.foregroundFailed(service, IllegalStateException("late failure"), failedSequence)
        repeat(30) { TaskKeepaliveController.acquire(context, owner, cpu = true) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(1, context.starts)
        assertEquals(1, TaskKeepaliveController.states.value.ownerCount)
        TaskKeepaliveController.onAppVisible(context)
        assertEquals(2, context.starts)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(0, TaskKeepaliveController.states.value.recoveryAttempts)
    }

    @Test
    fun canceledTimeoutAndRecoveryCallbacksCannotMutateANewerDemand() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val timeoutField = TaskKeepaliveController.javaClass.getDeclaredField("startTimeout").apply { isAccessible = true }
        val oldTimeout = timeoutField.get(TaskKeepaliveController) as Runnable
        TaskKeepaliveController.release(context, owner)
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val recoveryField = TaskKeepaliveController.javaClass.getDeclaredField("recovery").apply { isAccessible = true }
        val oldRecovery = recoveryField.get(TaskKeepaliveController) as Runnable
        TaskKeepaliveController.onTaskRemoved(context)
        context.reject = false
        TaskKeepaliveController.onAppVisible(context)
        val current = TaskKeepaliveController.states.value
        oldTimeout.run()
        oldRecovery.run()
        assertEquals(current, TaskKeepaliveController.states.value)
        assertEquals(3, context.starts)
        assertTrue(TaskKeepaliveController.states.value.startPending)
    }

    @Test
    fun awaitingProtectionWaitsThroughBackoffAndOnlyReturnsTrueAfterAttach() = runTest {
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) { TaskKeepaliveController.awaitProtection() }
        assertFalse(confirmation.isCompleted)
        context.reject = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(confirmation.isCompleted)
        TaskKeepaliveController.attach(service)
        assertTrue(confirmation.await())
        assertEquals(2, context.starts)
    }

    @Test
    fun finishingOneOwnerDoesNotCancelAnotherOwnersRecoveryOrResetItsBudget() {
        context.reject = true
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        TaskKeepaliveController.acquire(context, "$owner:other", cpu = false, network = true)
        TaskKeepaliveController.release(context, owner)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(1, TaskKeepaliveController.states.value.ownerCount)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, context.starts)
        assertEquals(1, TaskKeepaliveController.states.value.recoveryAttempts)
        TaskKeepaliveController.release(context, "$owner:other")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(2, context.starts)
    }

    @Test
    fun idlePersistentOrDisplayDemandDoesNotScheduleActiveRecovery() {
        context.reject = true
        TaskKeepaliveController.setPersistent(context, true)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(1, context.starts)
        TaskKeepaliveController.acquire(context, owner, cpu = false, network = false)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(2, context.starts)
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
        TaskKeepaliveController.acquire(context, owner, cpu = false, network = false)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(4, context.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(4, context.starts)
    }

    private class StartingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        var reject = false

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            if (reject) throw IllegalStateException("Background start rejected")
            return ComponentName(packageName, TerminalKeepaliveService::class.java.name)
        }

        override fun startService(service: Intent): ComponentName? =
            throw AssertionError("Only a foreground-service start may be requested")
    }
}
