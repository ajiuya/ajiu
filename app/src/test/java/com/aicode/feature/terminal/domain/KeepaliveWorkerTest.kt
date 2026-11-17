package com.aicode.feature.terminal.domain

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class KeepaliveWorkerTest {
    private lateinit var context: RejectingContext
    private val settings = mockk<KeepaliveSettingsRepository>()

    @Before
    fun setUp() {
        context = RejectingContext(RuntimeEnvironment.getApplication())
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onAppVisible(context)
    }

    @After
    fun tearDown() {
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onTaskRemoved(context)
        TaskKeepaliveController.onAppVisible(context)
    }

    private fun worker(attempt: Int = 0): KeepaliveWorker {
        val parameters = mockk<WorkerParameters>(relaxed = true)
        every { parameters.runAttemptCount } returns attempt
        return KeepaliveWorker(context, parameters, settings)
    }

    @Test
    fun rejectedForegroundStartRetriesAtMostTwiceAndNeverReportsSuccess() = runTest {
        coEvery { settings.isEnabled() } returns true
        assertEquals(ListenableWorker.Result.retry(), worker(0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(2).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(20).doWork())
        assertFalse(TaskKeepaliveController.isProtected())
        assertEquals(4, context.starts)
    }

    @Test
    fun disabledSettingNeverStartsService() = runTest {
        coEvery { settings.isEnabled() } returns false
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, context.starts)
    }

    @Test
    fun taskRemovalStopsChecksWithoutEvenReadingSetting() = runTest {
        TaskKeepaliveController.onTaskRemoved(context)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        coVerify(exactly = 0) { settings.isEnabled() }
        assertEquals(0, context.starts)
    }

    @Test
    fun latePersistentReadCannotUndoUserDisable() = runTest {
        coEvery { settings.isEnabled() } answers {
            TaskKeepaliveController.setPersistent(context, false)
            true
        }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, context.starts)
        assertFalse(TaskKeepaliveController.snapshot().persistent)
    }

    @Test
    fun settingReadFailureAlsoHasBoundedRetries() = runTest {
        coEvery { settings.isEnabled() } throws IllegalStateException("Storage unavailable")
        assertEquals(ListenableWorker.Result.retry(), worker(0).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(2).doWork())
        assertEquals(0, context.starts)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoRetry() = runTest {
        coEvery { settings.isEnabled() } throws CancellationException("Cancelled")
        worker().doWork()
        Unit
    }

    private class RejectingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            throw IllegalStateException("Background foreground-service start rejected")
        }
        override fun startService(service: Intent): ComponentName? =
            throw AssertionError("A regular background service must not be started")
    }
}
