package com.aicode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object TaskKeepaliveController {
    private const val TAG = "TaskKeepalive"
    private const val PREFS = "task_keepalive"
    private const val TASK_REMOVED = "task_removed"
    private const val INPUT_LEASE_MS = 60 * 60 * 1000L
    private const val START_TIMEOUT_MS = 10_000L
    private val registry = KeepaliveLeaseRegistry(SystemClock::elapsedRealtime)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val inputExpirations = mutableMapOf<String, Runnable>()
    private val status = MutableStateFlow(registry.snapshot())
    private var service: TerminalKeepaliveService? = null
    private var initialized = false
    private var startSequence = 0L
    private var lastReconciled: KeepaliveLeaseRegistry.Snapshot? = null
    private var reconcilePending = false
    private val reconcileService = Runnable {
        synchronized(this) {
            reconcilePending = false
            val running = service ?: return@synchronized
            val snapshot = registry.snapshot()
            if (needsReconcile(snapshot)) {
                lastReconciled = snapshot
                running.reconcileTasks()
            }
        }
    }

    @Synchronized
    private fun initialize(context: Context) {
        if (initialized) return
        registry.setSuspended(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(TASK_REMOVED, false))
        initialized = true
        publish()
    }

    /** 返回值只确认已进入前台；启动中可通过 [awaitProtection] 等待确认。 */
    @Synchronized
    fun acquire(context: Context, owner: String, cpu: Boolean, network: Boolean = cpu): Boolean {
        initialize(context)
        inputExpirations.remove(owner)?.let { mainHandler.removeCallbacks(it) }
        registry.acquire(owner, cpu, network)
        return reconcile(context, allowStart = true)
    }

    @Synchronized
    fun acquireForInput(context: Context, owner: String): Boolean {
        initialize(context)
        val appContext = context.applicationContext
        val expiresAt = registry.acquireTimed(owner, INPUT_LEASE_MS)
        inputExpirations.remove(owner)?.let { mainHandler.removeCallbacks(it) }
        lateinit var expire: Runnable
        expire = Runnable {
            synchronized(this) {
                if (inputExpirations[owner] === expire) {
                    inputExpirations.remove(owner)
                    registry.expire(owner, expiresAt)
                    reconcile(appContext, allowStart = false)
                }
            }
        }
        inputExpirations[owner] = expire
        mainHandler.postDelayed(expire, INPUT_LEASE_MS)
        return reconcile(context, allowStart = true)
    }

    @Synchronized
    fun release(context: Context, owner: String) {
        initialize(context)
        inputExpirations.remove(owner)?.let { mainHandler.removeCallbacks(it) }
        registry.release(owner)
        reconcile(context, allowStart = false)
    }

    @Synchronized
    fun setPersistent(context: Context, enabled: Boolean): Boolean {
        initialize(context)
        registry.setPersistent(enabled)
        return reconcile(context, allowStart = enabled)
    }

    @Synchronized
    fun persistentRevision(): Long = registry.persistentRevision()

    @Synchronized
    internal fun restorePersistent(context: Context, expectedRevision: Long): Boolean {
        initialize(context)
        if (!registry.restorePersistent(expectedRevision)) return false
        reconcile(context, allowStart = true)
        return true
    }

    @Synchronized
    fun retryPersistent(context: Context): Boolean {
        initialize(context)
        if (!registry.snapshot().persistent) return false
        return reconcile(context, allowStart = true)
    }

    @Synchronized
    fun onAppVisible(context: Context) {
        initialize(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TASK_REMOVED, false).apply()
        registry.setSuspended(false)
        reconcile(context, allowStart = true)
    }

    @Synchronized
    internal fun onTaskRemoved(context: Context) {
        initialize(context)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TASK_REMOVED, true).commit()
        if (!saved) FileLogger.w(TAG, "Could not persist user task-removal state")
        registry.setSuspended(true)
        registry.markUnavailable()
        startSequence++
        publish()
    }

    @Synchronized
    internal fun attach(instance: TerminalKeepaliveService) {
        service = instance
        registry.markForeground()
        lastReconciled = registry.snapshot()
        publish()
    }

    @Synchronized
    internal fun detach(instance: TerminalKeepaliveService) {
        if (service !== instance) return
        service = null
        mainHandler.removeCallbacks(reconcileService)
        reconcilePending = false
        lastReconciled = null
        registry.markUnavailable()
        publish()
    }

    @Synchronized
    internal fun foregroundFailed(instance: TerminalKeepaliveService, error: Throwable) {
        if (service != null && service !== instance) return
        service = null
        mainHandler.removeCallbacks(reconcileService)
        reconcilePending = false
        lastReconciled = null
        registry.markUnavailable()
        publish()
        FileLogger.e(TAG, "Foreground protection unavailable", error)
    }

    @Synchronized
    internal fun snapshot(): KeepaliveLeaseRegistry.Snapshot = registry.snapshot()

    @Synchronized
    fun isProtected(): Boolean = registry.snapshot().protected

    @Synchronized
    fun isSuspended(context: Context): Boolean {
        initialize(context)
        return registry.snapshot().suspended
    }

    suspend fun awaitProtection(timeoutMillis: Long = 5_000): Boolean {
        val confirmed = withTimeoutOrNull(timeoutMillis) {
            status.first { it.protected || !it.serviceRequired || (!it.startPending && !it.foreground) }.protected
        }
        if (confirmed == null) FileLogger.w(TAG, "Foreground confirmation timed out")
        return confirmed == true
    }

    @Synchronized
    private fun reconcile(context: Context, allowStart: Boolean): Boolean {
        val snapshot = registry.snapshot()
        val running = service
        if (running != null) {
            if (needsReconcile(snapshot)) {
                if (Looper.myLooper() == mainHandler.looper) {
                    mainHandler.removeCallbacks(reconcileService)
                    reconcilePending = false
                    lastReconciled = snapshot
                    running.reconcileTasks()
                } else if (!reconcilePending) {
                    reconcilePending = true
                    mainHandler.post(reconcileService)
                }
            }
        } else if (allowStart && snapshot.serviceRequired && !snapshot.startPending) {
            registry.markStartRequested()
            val sequence = ++startSequence
            mainHandler.postDelayed({
                synchronized(this) {
                    if (startSequence == sequence && registry.snapshot().startPending) {
                        registry.markUnavailable()
                        publish()
                        FileLogger.w(TAG, "Service did not confirm foreground startup")
                    }
                }
            }, START_TIMEOUT_MS)
            publish()
            try {
                val appContext = context.applicationContext
                val component = appContext.startForegroundService(Intent(appContext, TerminalKeepaliveService::class.java))
                checkNotNull(component) { "Keepalive service was not resolved" }
            } catch (error: Exception) {
                registry.markUnavailable()
                FileLogger.e(TAG, "Service start rejected; tasks are not protected", error)
            }
        }
        publish()
        return registry.snapshot().protected
    }

    private fun needsReconcile(snapshot: KeepaliveLeaseRegistry.Snapshot): Boolean = lastReconciled?.let {
        it.serviceRequired != snapshot.serviceRequired || it.cpuRequired != snapshot.cpuRequired ||
            it.wifiRequired != snapshot.wifiRequired || it.persistent != snapshot.persistent
    } ?: true

    private fun publish() {
        status.value = registry.snapshot()
    }
}
