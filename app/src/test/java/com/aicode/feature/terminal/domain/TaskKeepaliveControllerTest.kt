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
        TaskKeepaliveController.detach(service)
        TaskKeepaliveController.release(context, owner)
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
        assertFalse(TaskKeepaliveController.isProtected())
        TaskKeepaliveController.release(context, owner)
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
        assertEquals(1, context.starts)
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
