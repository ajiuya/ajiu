package com.aicode.feature.terminal.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@HiltWorker
class KeepaliveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val keepaliveSettings: KeepaliveSettingsRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        try {
            if (TaskKeepaliveController.isSuspended(applicationContext)) return Result.success()
            val revision = TaskKeepaliveController.persistentRevision()
            if (!keepaliveSettings.isEnabled()) return Result.success()
            val restored = TaskKeepaliveController.restorePersistent(applicationContext, revision)
            if (!restored && !TaskKeepaliveController.snapshot().persistent) return Result.success()
            if (TaskKeepaliveController.awaitProtection()) return Result.success()
            if (TaskKeepaliveController.isSuspended(applicationContext) || !TaskKeepaliveController.snapshot().persistent) {
                return Result.success()
            }
            FileLogger.w(TAG, "Foreground service unavailable, attempt=$runAttemptCount")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "Keepalive check failed, attempt=$runAttemptCount", error)
        }
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "KeepaliveWorker"
        private const val UNIQUE_NAME = "keepalive-check"
        private const val PERIOD_MINUTES = 15L
        internal const val MAX_RETRIES = 2

        fun schedule(context: Context) {
            if (TaskKeepaliveController.isSuspended(context)) return
            val request = PeriodicWorkRequestBuilder<KeepaliveWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
