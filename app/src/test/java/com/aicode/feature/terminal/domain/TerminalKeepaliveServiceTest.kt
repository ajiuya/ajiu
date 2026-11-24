package com.aicode.feature.terminal.domain

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowWifiManager

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TerminalKeepaliveServiceTest {
    private lateinit var context: Context
    private var controller: ServiceController<TerminalKeepaliveService>? = null
    private val owner = "service-test:owner"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val notifications = context.getSystemService(NotificationManager::class.java)
        listOf("terminal_service", "agent_live").forEach {
            notifications.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_LOW))
        }
        AgentLiveNotificationState.clear()
        KeepaliveNotificationState.updateAgents(emptyList())
        TerminalNotificationSource.entries.forEach { KeepaliveNotificationState.updateTerminals(it, emptyList()) }
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onAppVisible(context)
    }

    @After
    fun tearDown() {
        TaskKeepaliveController.release(context, owner)
        TaskKeepaliveController.release(context, "service-test:display")
        TaskKeepaliveController.release(context, "service-test:other")
        TaskKeepaliveController.setPersistent(context, false)
        controller?.destroy()
        AgentLiveNotificationState.clear()
        TaskKeepaliveController.onTaskRemoved(context)
        TaskKeepaliveController.onAppVisible(context)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(cpu: Boolean, network: Boolean = cpu): TerminalKeepaliveService {
        TaskKeepaliveController.acquire(context, owner, cpu, network)
        return Robolectric.buildService(TerminalKeepaliveService::class.java).also { controller = it }.create().get()
    }

    @Test
    fun displayOnlyLeaseDoesNotTakePowerLocks() {
        ShadowPowerManager.clearWakeLocks()
        start(cpu = false, network = false)
        assertTrue(TaskKeepaliveController.isProtected())
        assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
    }

    @Test
    fun activeTaskLocksAreCentralizedAndReleasedIndependentlyOfDisplayLease() {
        val service = start(cpu = true)
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        assertEquals(1, wifi.activeLockCount)
        TaskKeepaliveController.acquire(context, "service-test:display", cpu = false, network = false)
        TaskKeepaliveController.release(context, owner)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(TaskKeepaliveController.isProtected())
        assertFalse(wake.isHeld)
        assertEquals(0, wifi.activeLockCount)
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun cpuLockRenewsBeyondItsBoundedTimeoutAndDestroyReleasesBothLocks() {
        start(cpu = true)
        val wake = ShadowPowerManager.getLatestWakeLock()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
        assertTrue(wake.isHeld)
        controller!!.destroy()
        controller = null
        assertFalse(wake.isHeld)
        assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        assertFalse(TaskKeepaliveController.isProtected())
        assertTrue(TaskKeepaliveController.states.value.recoveryPending)
    }

    @Test
    fun taskRemovalSuspendsProtectionAndLateLeasesCannotRestartIt() {
        val service = start(cpu = true)
        val wake = ShadowPowerManager.getLatestWakeLock()
        service.onTaskRemoved(null)
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        assertTrue(TaskKeepaliveController.isSuspended(context))
        assertFalse(TaskKeepaliveController.isProtected())
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertFalse(wake.isHeld)
        assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
    }

    @Test
    fun fallbackUsesChronometerButPublicVersionContainsNoTaskDetails() {
        AgentLiveNotificationState.publish(
            AgentLiveNotificationState.Snapshot(
                enabled = true,
                sessions = listOf(AgentLiveNotificationState.Session("private-id", "raw command/output", "Command")),
                startedAtElapsedMs = 0
            )
        )
        val service = start(cpu = false)
        val notification = shadowOf(service).lastForegroundNotification
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = notification.publicVersion
        assertNotNull(public)
        assertEquals(Notification.VISIBILITY_PUBLIC, public.visibility)
        val contents = listOf(notification.extras, public.extras).joinToString()
        assertFalse(contents.contains("raw command/output"))
        assertFalse(contents.contains("private-id"))
        assertFalse(public.extras.toString().contains("Command"))
    }

    @Test
    fun sameChannelUpdatesNotifyWithoutRestartingForegroundAndChannelChangesDoRestartIt() {
        val service = start(cpu = true)
        val initial = shadowOf(service).lastForegroundNotification
        repeat(3) { service.reconcileTasks() }
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(initial, shadowOf(service).lastForegroundNotification)
        AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
            enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Command"))
        ))
        shadowOf(Looper.getMainLooper()).idle()
        val agent = shadowOf(service).lastForegroundNotification
        assertNotSame(initial, agent)
        assertEquals("agent_live", agent.channelId)
        AgentLiveNotificationState.publish(AgentLiveNotificationState.state.value.copy(
            sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Next action"))
        ))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(agent, shadowOf(service).lastForegroundNotification)
        val updated = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(1)
        assertTrue(updated.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Next action"))
        AgentLiveNotificationState.clear()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(agent, shadowOf(service).lastForegroundNotification)
        assertEquals("terminal_service", shadowOf(service).lastForegroundNotification.channelId)
    }

    @Test
    fun repeatedReconcileAndLiveRendersDoNotPostponeCpuLeaseRenewal() {
        val service = start(cpu = true)
        val renewal = service.javaClass.getDeclaredField("cpuRenewAt").apply { isAccessible = true }
        val firstDeadline = renewal.getLong(service)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        repeat(30) {
            service.reconcileTasks()
            AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
                enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Action $it"))
            ))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(firstDeadline, renewal.getLong(service))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertTrue(renewal.getLong(service) > firstDeadline)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        TaskKeepaliveController.release(context, owner)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
    }

    @Test
    fun aNewStartBeforeDestructionReusesTheSameServiceAndRestoresLocksAndObserver() {
        val service = start(cpu = true)
        TaskKeepaliveController.release(context, owner)
        assertFalse(TaskKeepaliveController.isProtected())
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        service.onStartCommand(null, 0, 2)
        assertTrue(TaskKeepaliveController.isProtected())
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
            enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Restored"))
        ))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("agent_live", shadowOf(service).lastForegroundNotification.channelId)
    }

    @Test
    fun foregroundChannelSwitchFailureDoesNotLeaveProtectionOrPowerLocksReportedActive() {
        val service = start(cpu = true)
        val wake = ShadowPowerManager.getLatestWakeLock()
        mockkObject(service)
        try {
            every { service.startForeground(any(), any<Notification>()) } throws IllegalStateException("FGS rejected")
            AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
                enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Action"))
            ))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(TaskKeepaliveController.isProtected())
            assertFalse(wake.isHeld)
            assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(service) }
    }

    @Test
    fun firstForegroundFailureDoesNotConfirmProtectionOrAcquirePowerLocks() {
        ShadowPowerManager.clearWakeLocks()
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val built = Robolectric.buildService(TerminalKeepaliveService::class.java)
        controller = built
        val service = built.get()
        mockkObject(service)
        try {
            every { service.startForeground(any(), any<Notification>()) } throws IllegalStateException("FGS rejected")
            built.create()
            verify(exactly = 1) { service.startForeground(1, any<Notification>()) }
            assertFalse(TaskKeepaliveController.isProtected())
            assertFalse(TaskKeepaliveController.snapshot().startPending)
            assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
            assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
            assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(service) }
    }

    @Test
    fun failedCreationIgnoresItsOldStartCommandAndOnlyTheBackoffRequestCanRestartIt() {
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        val originalSequence = TaskKeepaliveController.currentStartSequence()
        val built = Robolectric.buildService(TerminalKeepaliveService::class.java)
        controller = built
        val service = built.get()
        mockkObject(service)
        try {
            every { service.startForeground(any(), any<Notification>()) } throws IllegalStateException("FGS rejected")
            built.create()
            val oldStart = Intent().putExtra(TaskKeepaliveController.EXTRA_START_SEQUENCE, originalSequence)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(oldStart, 0, 1))
            verify(exactly = 1) { service.startForeground(1, any<Notification>()) }
            assertTrue(TaskKeepaliveController.states.value.recoveryPending)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, TaskKeepaliveController.states.value.recoveryAttempts)
            assertTrue(TaskKeepaliveController.states.value.startPending)
        } finally { unmockkObject(service) }
        val retry = Intent().putExtra(TaskKeepaliveController.EXTRA_START_SEQUENCE, TaskKeepaliveController.currentStartSequence())
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(retry, 0, 2))
        assertTrue(TaskKeepaliveController.isProtected())
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
        assertEquals(1, TaskKeepaliveController.states.value.recoveryAttempts)
    }

    @Test
    fun ordinarySameChannelPostFailureKeepsEstablishedProtectionAndPowerLocks() {
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val initial = shadowOf(service).lastForegroundNotification
        val wake = ShadowPowerManager.getLatestWakeLock()
        val notifications = service.getSystemService(NotificationManager::class.java)
        mockkObject(notifications)
        try {
            every { notifications.notify(1, any<Notification>()) } throws IllegalStateException("Notify rejected")
            service.reconcileTasks()
            verify(exactly = 1) { notifications.notify(1, any<Notification>()) }
            assertTrue(TaskKeepaliveController.isProtected())
            assertTrue(wake.isHeld)
            assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
            assertSame(initial, shadowOf(service).lastForegroundNotification)
        } finally { unmockkObject(notifications) }
        service.reconcileTasks()
        assertTrue(TaskKeepaliveController.isProtected())
        assertEquals("terminal_service", shadowOf(notifications).getNotification(1).channelId)
    }

    @Test
    fun liveObserverSurvivesOrdinaryPostFailureAndPublishesTheNextAction() {
        AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
            enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "", "Initial"))
        ))
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val initial = shadowOf(service).lastForegroundNotification
        val notifications = service.getSystemService(NotificationManager::class.java)
        mockkObject(notifications)
        try {
            every { notifications.notify(1, any<Notification>()) } throws IllegalStateException("Notify rejected")
            AgentLiveNotificationState.publish(AgentLiveNotificationState.state.value.copy(
                sessions = listOf(AgentLiveNotificationState.Session("session", "", "Rejected"))
            ))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(TaskKeepaliveController.isProtected())
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(notifications) }
        AgentLiveNotificationState.publish(AgentLiveNotificationState.state.value.copy(
            sessions = listOf(AgentLiveNotificationState.Session("session", "", "Recovered"))
        ))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(initial, shadowOf(service).lastForegroundNotification)
        val recovered = shadowOf(notifications).getNotification(1)
        assertTrue(recovered.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Recovered"))
        assertTrue(TaskKeepaliveController.isProtected())
    }

    @Test
    fun optionalPromotedChannelPostRetriesOrdinaryBeforeReportingForegroundFailure() {
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val promoted = Notification.Builder(service, "agent_live")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()
        mockkObject(service, recordPrivateCalls = true)
        try {
            every { service["buildForegroundNotification"](any<AgentLiveNotificationState.Snapshot>()) } returns promoted
            every {
                service.startForeground(1, match<Notification> { it.extras.getBoolean("android.requestPromotedOngoing") })
            } throws IllegalArgumentException("Promoted post rejected")
            AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
                enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "private", "Action")),
                startedAtElapsedMs = 0
            ))
            shadowOf(Looper.getMainLooper()).idle()
            verify(exactly = 2) { service.startForeground(1, any<Notification>()) }
            val fallback = shadowOf(service).lastForegroundNotification
            assertEquals("agent_live", fallback.channelId)
            assertFalse(fallback.extras.getBoolean("android.requestPromotedOngoing"))
            assertTrue(fallback.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(Notification.VISIBILITY_PRIVATE, fallback.visibility)
            assertNotNull(fallback.publicVersion)
            assertTrue(TaskKeepaliveController.isProtected())
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(service) }
    }

    @Test
    fun optionalPromotedSameChannelPostRetriesOrdinaryWithoutRestartingForeground() {
        AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
            enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "", "Initial")),
            startedAtElapsedMs = 0
        ))
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val initial = shadowOf(service).lastForegroundNotification
        val promoted = Notification.Builder(service, "agent_live")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()
        val notifications = service.getSystemService(NotificationManager::class.java)
        mockkObject(service, recordPrivateCalls = true)
        mockkObject(notifications)
        try {
            every { service["buildForegroundNotification"](any<AgentLiveNotificationState.Snapshot>()) } returns promoted
            every {
                notifications.notify(1, match<Notification> { it.extras.getBoolean("android.requestPromotedOngoing") })
            } throws IllegalArgumentException("Promoted post rejected")
            service.reconcileTasks()
            verify(exactly = 2) { notifications.notify(1, any<Notification>()) }
            verify(exactly = 0) { service.startForeground(any(), any<Notification>()) }
            val fallback = shadowOf(notifications).getNotification(1)
            assertFalse(fallback.extras.getBoolean("android.requestPromotedOngoing"))
            assertTrue(fallback.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertSame(initial, shadowOf(service).lastForegroundNotification)
            assertEquals(Notification.VISIBILITY_PRIVATE, fallback.visibility)
            assertNotNull(fallback.publicVersion)
            assertTrue(TaskKeepaliveController.isProtected())
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(notifications, service) }
    }

    @Test
    fun rejectedPromotedAndOrdinaryUpdatesKeepEstablishedForegroundProtection() {
        AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
            enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "", "Initial"))
        ))
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val promoted = Notification.Builder(service, "agent_live")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()
        val notifications = service.getSystemService(NotificationManager::class.java)
        mockkObject(service, recordPrivateCalls = true)
        mockkObject(notifications)
        try {
            every { service["buildForegroundNotification"](any<AgentLiveNotificationState.Snapshot>()) } returns promoted
            every { notifications.notify(1, any<Notification>()) } throws IllegalStateException("All posts rejected")
            service.reconcileTasks()
            verify(exactly = 2) { notifications.notify(1, any<Notification>()) }
            assertTrue(TaskKeepaliveController.isProtected())
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(notifications, service) }
    }

    @Test
    fun rejectedPromotedAndOrdinaryForegroundStartsReleaseProtectionAndPowerLocks() {
        val service = start(cpu = true)
        shadowOf(Looper.getMainLooper()).idle()
        val wake = ShadowPowerManager.getLatestWakeLock()
        val promoted = Notification.Builder(service, "agent_live")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()
        mockkObject(service, recordPrivateCalls = true)
        try {
            every { service["buildForegroundNotification"](any<AgentLiveNotificationState.Snapshot>()) } returns promoted
            every { service.startForeground(1, any<Notification>()) } throws IllegalStateException("All FGS posts rejected")
            AgentLiveNotificationState.publish(AgentLiveNotificationState.Snapshot(
                enabled = true, sessions = listOf(AgentLiveNotificationState.Session("session", "", "Action"))
            ))
            shadowOf(Looper.getMainLooper()).idle()
            verify(exactly = 2) { service.startForeground(1, any<Notification>()) }
            assertFalse(TaskKeepaliveController.isProtected())
            assertFalse(wake.isHeld)
            assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        } finally { unmockkObject(service) }
    }

    @Test
    fun anotherActiveOwnerKeepsBothLocksUntilTheLastTaskFinishes() {
        start(cpu = true)
        val wake = ShadowPowerManager.getLatestWakeLock()
        TaskKeepaliveController.acquire(context, "service-test:other", cpu = true)
        TaskKeepaliveController.release(context, owner)
        assertTrue(TaskKeepaliveController.isProtected())
        assertTrue(wake.isHeld)
        assertEquals(1, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        TaskKeepaliveController.release(context, "service-test:other")
        assertFalse(TaskKeepaliveController.isProtected())
        assertFalse(wake.isHeld)
        assertEquals(0, shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        assertFalse(TaskKeepaliveController.states.value.recoveryPending)
    }

    @Test
    fun lockLifecycleLogsOnlyActualAcquisitionRenewalAndReleaseWithoutTaskDetails() {
        mockkObject(FileLogger)
        try {
            every { FileLogger.i(any(), any()) } just Runs
            val service = start(cpu = true)
            repeat(30) { service.reconcileTasks() }
            verify(exactly = 1) { FileLogger.i(any(), match { it.startsWith("CPU wake lock acquired; held=true") }) }
            verify(exactly = 1) { FileLogger.i(any(), "Wi-Fi lock acquired; held=true") }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
            verify(exactly = 1) { FileLogger.i(any(), match { it.startsWith("CPU wake lock renewed; held=true") }) }
            TaskKeepaliveController.release(context, owner)
            verify(exactly = 1) { FileLogger.i(any(), "CPU wake lock release requested; held=false") }
            verify(exactly = 1) { FileLogger.i(any(), "Wi-Fi lock release requested; held=false") }
            verify(exactly = 0) { FileLogger.i(any(), match { it.contains(owner) }) }
        } finally { unmockkObject(FileLogger) }
    }

    @Test
    fun failedLockAcquisitionNeverReportsHeldAndDoesNotSetASuccessfulRenewalDeadline() {
        val service = start(cpu = false, network = false)
        val power = service.getSystemService(PowerManager::class.java)
        val wifi = context.getSystemService(WifiManager::class.java)
        val cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:cpu").apply { setReferenceCounted(false) }
        val networkLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "test:wifi")
            .apply { setReferenceCounted(false) }
        val cpuShadow = shadowOf(cpuLock)
        val networkShadow = Shadow.extract<ShadowWifiManager.ShadowWifiLock>(networkLock)
        service.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }.set(service, cpuLock)
        service.javaClass.getDeclaredField("wifiLock").apply { isAccessible = true }.set(service, networkLock)
        mockkObject(cpuShadow, networkShadow, FileLogger, recordPrivateCalls = true)
        try {
            every { cpuShadow["acquire"](any<Long>()) } throws SecurityException("private task details")
            every { networkShadow["acquire"]() } throws SecurityException("private task details")
            every { FileLogger.i(any(), any()) } just Runs
            every { FileLogger.e(any(), any(), any()) } just Runs
            every { FileLogger.w(any(), any(), any()) } just Runs
            TaskKeepaliveController.acquire(context, owner, cpu = true)
            assertFalse(cpuLock.isHeld)
            assertFalse(networkLock.isHeld)
            assertTrue(TaskKeepaliveController.snapshot().cpuRequired)
            assertTrue(TaskKeepaliveController.snapshot().wifiRequired)
            val renewal = service.javaClass.getDeclaredField("cpuRenewAt").apply { isAccessible = true }
            assertEquals(0L, renewal.getLong(service))
            verify(exactly = 1) {
                FileLogger.e(any(), "CPU wake lock acquisition failed; held=false errorType=SecurityException", null)
            }
            verify(exactly = 1) {
                FileLogger.w(any(), "Wi-Fi lock acquisition failed; held=false errorType=SecurityException", null)
            }
            verify(exactly = 0) { FileLogger.i(any(), match { it.contains("lock acquired") || it.contains("lock renewed") }) }
            verify(exactly = 0) { FileLogger.e(any(), match { it.contains("private task details") }, any()) }
            verify(exactly = 0) { FileLogger.w(any(), match { it.contains("private task details") }, any()) }
        } finally { unmockkObject(cpuShadow, networkShadow, FileLogger) }
    }

    @Test
    fun releaseFailureLogsTheStillHeldStateInsteadOfClaimingRelease() {
        val service = start(cpu = false, network = false)
        val lock = service.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:release")
            .apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        val lockShadow = shadowOf(lock)
        service.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }.set(service, lock)
        mockkObject(lockShadow, FileLogger, recordPrivateCalls = true)
        try {
            every { lockShadow["release"](any<Int>()) } throws IllegalStateException("private task details")
            every { FileLogger.i(any(), any()) } just Runs
            every { FileLogger.w(any(), any(), any()) } just Runs
            TaskKeepaliveController.release(context, owner)
            verify(exactly = 1) {
                FileLogger.w(any(), "CPU wake lock release failed; held=true errorType=IllegalStateException", null)
            }
            verify(exactly = 0) { FileLogger.i(any(), match { it.startsWith("CPU wake lock release requested") }) }
            assertTrue(lock.isHeld)
        } finally {
            unmockkObject(lockShadow, FileLogger)
            if (lock.isHeld) lock.release()
            service.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }.set(service, null)
        }
    }

    @Test
    fun completedFallbackStopsChronometer() {
        AgentLiveNotificationState.publish(
            AgentLiveNotificationState.Snapshot(
                enabled = true, completed = true, completionVerified = false,
                startedAtElapsedMs = 0, finishedAtElapsedMs = 10
            )
        )
        val service = start(cpu = false)
        assertFalse(shadowOf(service).lastForegroundNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
}
