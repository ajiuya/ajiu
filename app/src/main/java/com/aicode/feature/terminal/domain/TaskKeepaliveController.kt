package com.aicode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object TaskKeepaliveController {
    private const val TAG = "TaskKeepalive"
    private const val PREFS = "task_keepalive"
    private const val TASK_REMOVED = "task_removed"
    private const val INPUT_LEASE_MS = 60 * 60 * 1000L
    private const val START_TIMEOUT_MS = 10_000L
    internal const val EXTRA_START_SEQUENCE = "com.aicode.keepalive.START_SEQUENCE"
    private val RECOVERY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 10_000L)
    private val registry = KeepaliveLeaseRegistry(SystemClock::elapsedRealtime)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val inputExpirations = mutableMapOf<String, Runnable>()
    private val status = MutableStateFlow(registry.snapshot())
    internal val states = status.asStateFlow()
    private var service: TerminalKeepaliveService? = null
    private var initialized = false
    private var startSequence = 0L
    private var startTimeout: Runnable? = null
    private var recovery: Runnable? = null
    private var recoveryContext: Context? = null
    private var activeDemand = false
    private var attemptedForDemand = false
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
        recoveryContext = context.applicationContext
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
        resetRecovery()
        if (service == null) invalidateStart()
        reconcile(context, allowStart = true)
    }

    @Synchronized
    internal fun onTaskRemoved(context: Context) {
        initialize(context)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TASK_REMOVED, true).commit()
        if (!saved) FileLogger.w(TAG, "Could not persist user task-removal state")
        registry.setSuspended(true)
        registry.markUnavailable()
        resetRecovery()
        activeDemand = false
        invalidateStart()
        publish()
    }

    @Synchronized
    internal fun currentStartSequence(): Long = startSequence

    @Synchronized
    internal fun attach(instance: TerminalKeepaliveService, sequence: Long? = null): Boolean {
        val snapshot = registry.snapshot()
        if (sequence != null && (sequence != startSequence || !snapshot.serviceRequired)) return false
        service = instance
        cancelStartTimeout()
        cancelRecovery()
        registry.markForeground()
        registry.setRecoveryState(snapshot.recoveryAttempts, pending = false)
        if (snapshot.activeRequired) attemptedForDemand = true
        lastReconciled = registry.snapshot()
        publish()
        return true
    }

    @Synchronized
    internal fun detach(instance: TerminalKeepaliveService) {
        if (service !== instance) return
        service = null
        mainHandler.removeCallbacks(reconcileService)
        reconcilePending = false
        lastReconciled = null
        invalidateStart()
        updateRecoveryDemand()
        scheduleRecovery()
        publish()
    }

    @Synchronized
    internal fun foregroundFailed(instance: TerminalKeepaliveService, error: Throwable, sequence: Long? = null) {
        if (service != null && service !== instance) return
        if (sequence != null && sequence != startSequence) return
        service = null
        mainHandler.removeCallbacks(reconcileService)
        reconcilePending = false
        lastReconciled = null
        invalidateStart()
        updateRecoveryDemand()
        scheduleRecovery()
        publish()
        FileLogger.e(TAG, "Foreground protection unavailable; errorType=${error.javaClass.simpleName}")
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
            status.first {
                it.protected || !it.serviceRequired || (!it.startPending && !it.recoveryPending && !it.foreground)
            }.protected
        }
        if (confirmed == null) FileLogger.w(TAG, "Foreground confirmation timed out")
        return confirmed == true
    }

    @Synchronized
    private fun reconcile(context: Context, allowStart: Boolean): Boolean {
        updateRecoveryDemand()
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
            if (!snapshot.activeRequired || !attemptedForDemand) {
                if (snapshot.activeRequired) attemptedForDemand = true
                requestStart(context.applicationContext)
            } else {
                scheduleRecovery()
            }
        }
        publish()
        return registry.snapshot().protected
    }

    private fun requestStart(context: Context) {
        registry.markStartRequested()
        val sequence = ++startSequence
        val timeout = Runnable {
            synchronized(this) {
                if (startSequence == sequence && registry.snapshot().startPending) {
                    invalidateStart()
                    updateRecoveryDemand()
                    scheduleRecovery()
                    publish()
                    FileLogger.w(TAG, "Service did not confirm foreground startup")
                }
            }
        }
        startTimeout = timeout
        mainHandler.postDelayed(timeout, START_TIMEOUT_MS)
        publish()
        try {
            val intent = Intent(context, TerminalKeepaliveService::class.java).putExtra(EXTRA_START_SEQUENCE, sequence)
            val component = context.startForegroundService(intent)
            checkNotNull(component) { "Keepalive service was not resolved" }
        } catch (error: Exception) {
            if (startSequence == sequence && registry.snapshot().startPending) {
                invalidateStart()
                scheduleRecovery()
                FileLogger.e(TAG, "Service start rejected; errorType=${error.javaClass.simpleName}")
            }
        }
    }

    private fun updateRecoveryDemand() {
        val snapshot = registry.snapshot()
        if (activeDemand != snapshot.activeRequired) {
            resetRecovery()
            if (activeDemand && service == null) invalidateStart()
            activeDemand = snapshot.activeRequired
        }
        if (snapshot.activeRequired && (service != null || snapshot.startPending)) attemptedForDemand = true
        if (!snapshot.serviceRequired && service == null) invalidateStart()
    }

    private fun resetRecovery() {
        cancelRecovery()
        attemptedForDemand = false
        registry.setRecoveryState(attempts = 0, pending = false)
    }

    private fun cancelRecovery() {
        recovery?.let { mainHandler.removeCallbacks(it) }
        recovery = null
        val snapshot = registry.snapshot()
        registry.setRecoveryState(snapshot.recoveryAttempts, pending = false, exhausted = snapshot.recoveryExhausted)
    }

    private fun cancelStartTimeout() {
        startTimeout?.let { mainHandler.removeCallbacks(it) }
        startTimeout = null
    }

    private fun invalidateStart() {
        cancelStartTimeout()
        cancelRecovery()
        startSequence++
        registry.markUnavailable()
    }

    private fun scheduleRecovery() {
        val snapshot = registry.snapshot()
        if (!snapshot.activeRequired || service != null || snapshot.startPending || recovery != null) return
        val context = recoveryContext ?: return
        val attempts = snapshot.recoveryAttempts
        if (attempts >= RECOVERY_DELAYS_MS.size) {
            if (!snapshot.recoveryExhausted) FileLogger.w(TAG, "Active protection recovery exhausted; attempts=$attempts")
            registry.setRecoveryState(attempts, pending = false, exhausted = true)
            return
        }
        val sequence = startSequence
        lateinit var retry: Runnable
        retry = Runnable {
            synchronized(this) {
                if (recovery === retry && startSequence == sequence) {
                    recovery = null
                    updateRecoveryDemand()
                    val current = registry.snapshot()
                    if (current.activeRequired && service == null && !current.startPending) {
                        registry.setRecoveryState(attempts + 1, pending = false)
                        requestStart(context)
                    } else {
                        registry.setRecoveryState(current.recoveryAttempts, pending = false, exhausted = current.recoveryExhausted)
                    }
                    publish()
                }
            }
        }
        recovery = retry
        registry.setRecoveryState(attempts, pending = true)
        mainHandler.postDelayed(retry, RECOVERY_DELAYS_MS[attempts])
        FileLogger.i(TAG, "Active protection recovery scheduled; attempt=${attempts + 1} delayMs=${RECOVERY_DELAYS_MS[attempts]}")
    }

    private fun needsReconcile(snapshot: KeepaliveLeaseRegistry.Snapshot): Boolean = lastReconciled?.let {
        it.serviceRequired != snapshot.serviceRequired || it.cpuRequired != snapshot.cpuRequired ||
            it.wifiRequired != snapshot.wifiRequired || it.persistent != snapshot.persistent
    } ?: true

    private fun publish() {
        val previous = status.value
        val snapshot = registry.snapshot()
        status.value = snapshot
        if (snapshot.activeRequired && (!previous.activeRequired || previous.protected != snapshot.protected)) {
            val state = "owners=${snapshot.ownerCount} pending=${snapshot.startPending} recovery=${snapshot.recoveryPending} attempts=${snapshot.recoveryAttempts}"
            if (snapshot.protected) FileLogger.i(TAG, "Active foreground protection confirmed; $state")
            else FileLogger.w(TAG, "Active foreground protection missing; $state")
        }
    }
}
