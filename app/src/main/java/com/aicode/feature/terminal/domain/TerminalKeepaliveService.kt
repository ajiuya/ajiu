package com.aicode.feature.terminal.domain

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationContent
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var liveJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false
    private var stopping = false
    private var currentChannelId: String? = null
    private var cpuRenewAt = 0L

    private val renewCpuLock = Runnable {
        if (!stopping && foreground) reconcileTasks()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        if (ensureForeground()) {
            TaskKeepaliveController.attach(this)
            reconcileTasks()
            startLiveObserver()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new start can arrive after stopSelf but before destruction of this same instance.
        if (stopping && TaskKeepaliveController.snapshot().serviceRequired) {
            stopping = false
            if (ensureForeground()) {
                TaskKeepaliveController.attach(this)
                startLiveObserver()
            }
        }
        if (!stopping) reconcileTasks()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        TaskKeepaliveController.onTaskRemoved(this)
        runCatching { KeepaliveWorker.cancel(this) }
            .onFailure { FileLogger.w(TAG, "Cancel keepalive worker failed", it) }
        stopProtection()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        cancelTaskListNotification()
        releaseCpuLock()
        releaseWifiLock()
        foreground = false
        currentChannelId = null
        cpuRenewAt = 0L
        TaskKeepaliveController.detach(this)
        super.onDestroy()
    }

    internal fun reconcileTasks() {
        if (stopping) return
        val snapshot = TaskKeepaliveController.snapshot()
        if (!snapshot.serviceRequired) {
            stopProtection()
            return
        }
        if (!ensureForeground()) return
        if (snapshot.cpuRequired) {
            if (wakeLock?.isHeld != true || SystemClock.elapsedRealtime() >= cpuRenewAt) {
                acquireCpuLock()
                cpuRenewAt = SystemClock.elapsedRealtime() + CPU_RENEW_MS
                handler.removeCallbacks(renewCpuLock)
                handler.postDelayed(renewCpuLock, CPU_RENEW_MS)
            }
        } else {
            handler.removeCallbacks(renewCpuLock)
            cpuRenewAt = 0L
            releaseCpuLock()
        }
        if (snapshot.wifiRequired) acquireWifiLock() else releaseWifiLock()
    }

    private fun ensureForeground(): Boolean {
        val state = AgentLiveNotificationState.state.value
        val channelId = if (state.active) AGENT_CHANNEL_ID else CHANNEL_ID
        if (!foreground || currentChannelId != channelId) {
            try {
                postForegroundNotification(state) { startForeground(NOTIFICATION_ID, it) }
                currentChannelId = channelId
                foreground = true
            } catch (error: Exception) {
                foreground = false
                TaskKeepaliveController.foregroundFailed(this, error)
                stopProtection()
                return false
            }
        } else {
            runCatching {
                postForegroundNotification(state) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, it)
                }
            }.onFailure { FileLogger.w(TAG, "Update foreground notification failed", it) }
        }
        updateTaskListNotification(state)
        return true
    }

    private inline fun postForegroundNotification(
        state: AgentLiveNotificationState.Snapshot,
        post: (Notification) -> Unit
    ) {
        val notification = buildForegroundNotification(state)
        try {
            post(notification)
        } catch (error: Exception) {
            if (!notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)) throw error
            FileLogger.w(TAG, "Post native live update failed; retrying ordinary notification", error)
            post(buildAgentNotification(state, allowPromoted = false))
        }
    }

    private fun startLiveObserver() {
        if (liveJob?.isActive == true || stopping) return
        liveJob = serviceScope.launch {
            combine(AgentLiveNotificationState.state, KeepaliveNotificationState.state) { live, _ -> live }
                .collect { if (foreground && !stopping) ensureForeground() }
        }
    }

    private fun buildForegroundNotification(state: AgentLiveNotificationState.Snapshot): Notification =
        if (state.active) buildAgentNotification(state) else buildTerminalNotification()

    private fun publicNotification(channelId: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_keepalive_task)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_task_public_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun taskLabels() = KeepaliveNotificationContent.Labels(
        summary = getString(R.string.keepalive_task_summary),
        terminalRow = getString(R.string.keepalive_task_terminal_row),
        agentRow = getString(R.string.keepalive_task_agent_row),
        local = getString(R.string.keepalive_task_local),
        ssh = getString(R.string.keepalive_task_ssh),
        running = getString(R.string.keepalive_task_command_running),
        idle = getString(R.string.keepalive_task_terminal_idle)
    )

    private fun buildTerminalNotification(): Notification {
        val state = KeepaliveNotificationState.state.value
        val labels = taskLabels()
        val persistent = TaskKeepaliveController.snapshot().persistent
        val text = if (state.terminalCount == 0 && state.agents.isEmpty() && persistent) {
            getString(R.string.notification_keepalive_enabled)
        } else KeepaliveNotificationContent.summary(state, labels)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keepalive_task)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(KeepaliveNotificationContent.expanded(state, labels)))
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(CHANNEL_ID))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateTaskListNotification(live: AgentLiveNotificationState.Snapshot) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (KeepaliveNotificationContent.companionRequired(live, KeepaliveNotificationState.state.value)) {
                manager.notify(TASK_LIST_NOTIFICATION_ID, buildTerminalNotification())
            } else manager.cancel(TASK_LIST_NOTIFICATION_ID)
        }.onFailure { FileLogger.w(TAG, "Update task-list notification failed", it) }
    }

    private fun cancelTaskListNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(TASK_LIST_NOTIFICATION_ID) }
            .onFailure { FileLogger.w(TAG, "Cancel task-list notification failed", it) }
    }

    private fun buildAgentNotification(
        state: AgentLiveNotificationState.Snapshot,
        allowPromoted: Boolean = true
    ): Notification {
        val clock = AgentLiveNotificationContent.clock(state, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        val title = when {
            state.completed && state.failed -> getString(R.string.keepalive_task_failed_title)
            state.completed && !state.completionVerified -> getString(R.string.keepalive_task_ended_title)
            state.completed -> getString(R.string.keepalive_task_completed_title)
            else -> AgentLiveNotificationContent.title(
                state, getString(R.string.keepalive_task_agent_running), getString(R.string.notification_agent_live_title_multi)
            )
        }
        val body = when {
            state.completed && state.failed -> getString(R.string.keepalive_task_failed_body)
            state.completed && !state.completionVerified -> getString(R.string.keepalive_task_ended_body)
            state.completed -> getString(R.string.keepalive_task_completed_body)
            else -> AgentLiveNotificationContent.body(
                state, getString(R.string.keepalive_task_agent_running), getString(R.string.keepalive_task_agent_row)
            )
        }
        val icon = when {
            state.completed && state.failed -> R.drawable.ic_keepalive_task_failed
            state.completed && state.completionVerified -> R.drawable.ic_keepalive_task_completed
            else -> R.drawable.ic_keepalive_task
        }
        val shortText = AgentLiveNotificationContent.shortText(
            state,
            runningFallback = getString(R.string.keepalive_task_action_running),
            completedText = getString(R.string.keepalive_task_completed_title),
            failedText = getString(R.string.keepalive_task_failed_title),
            thinkingText = getString(R.string.keepalive_task_action_thinking),
            endedText = getString(R.string.keepalive_task_ended_title)
        )
        if (allowPromoted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && promotedNotificationsAvailable()) {
            runCatching { buildPromotedAgentNotification(state, title, body, shortText, icon, clock) }
                .onSuccess { return it }
                .onFailure { FileLogger.w(TAG, "Native live update unavailable; using ordinary notification", it) }
        }
        return NotificationCompat.Builder(this, AGENT_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(AGENT_CHANNEL_ID))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(true)
            .setWhen(clock.whenMs)
            .setUsesChronometer(clock.usesChronometer)
            .setChronometerCountDown(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun promotedNotificationsAvailable(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildPromotedAgentNotification(
        state: AgentLiveNotificationState.Snapshot,
        title: String,
        body: String,
        shortText: String,
        icon: Int,
        clock: AgentLiveNotificationContent.Clock
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setStyledByProgress(false)
            .setProgressTrackerIcon(null)
            .setProgressIndeterminate(!state.completed)
            .setProgress(if (state.completed && state.completionVerified && !state.failed) 100 else 0)
        return Notification.Builder(this, AGENT_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(progressStyle)
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(AGENT_CHANNEL_ID))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setWhen(clock.whenMs)
            .setUsesChronometer(false)
            .setColorized(false)
            .setShortCriticalText(shortText)
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopProtection() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        liveJob?.cancel()
        liveJob = null
        cancelTaskListNotification()
        releaseCpuLock()
        releaseWifiLock()
        foreground = false
        currentChannelId = null
        cpuRenewAt = 0L
        TaskKeepaliveController.detach(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireCpuLock() {
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:ActiveTasks")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(CPU_TIMEOUT_MS)
        }.onFailure { FileLogger.e(TAG, "CPU wake lock unavailable", it) }
    }

    private fun releaseCpuLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            .onFailure { FileLogger.w(TAG, "CPU wake lock release failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        runCatching {
            val manager = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val lock = wifiLock ?: manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:ActiveTasks")
                .apply { setReferenceCounted(false) }
                .also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        }.onFailure { FileLogger.w(TAG, "Wi-Fi lock unavailable", it) }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
            .onFailure { FileLogger.w(TAG, "Wi-Fi lock release failed", it) }
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val TASK_LIST_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "terminal_service"
        private const val AGENT_CHANNEL_ID = "agent_live"
        private const val CPU_TIMEOUT_MS = 10 * 60 * 1000L
        private const val CPU_RENEW_MS = 5 * 60 * 1000L
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        fun enablePersistent(context: Context): Boolean = TaskKeepaliveController.retryPersistent(context)

        fun disablePersistent(context: Context) {
            TaskKeepaliveController.setPersistent(context, false)
        }

        fun isRunning(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = TaskKeepaliveController.isProtected()
    }
}
