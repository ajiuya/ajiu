package com.aicode.feature.agent.presentation.notification

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.R
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.terminal.domain.KeepaliveNotificationState
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AgentLiveNotificationManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val enabled = MutableStateFlow(false)
    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }
    private val service = mockk<TerminalKeepaliveService>(relaxed = true)
    private lateinit var context: StartingContext
    private lateinit var manager: AgentLiveNotificationManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = StartingContext(RuntimeEnvironment.getApplication())
        AgentLiveNotificationState.clear()
        KeepaliveNotificationState.updateAgents(emptyList())
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onAppVisible(context)
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns owner
        val settings = mockk<LiveNotificationSettingsRepository>()
        every { settings.enabledFlow } returns enabled
        manager = AgentLiveNotificationManager(context, settings, managerScope)
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        managerScope.cancel()
        dispatcher.scheduler.runCurrent()
        TaskKeepaliveController.detach(service)
        manager.dismiss()
        TaskKeepaliveController.setPersistent(context, false)
        TaskKeepaliveController.onTaskRemoved(context)
        TaskKeepaliveController.onAppVisible(context)
        shadowOf(Looper.getMainLooper()).idle()
        unmockkObject(ProcessLifecycleOwner.Companion)
        Dispatchers.resetMain()
    }

    private fun setEnabled(value: Boolean) {
        enabled.value = value
        dispatcher.scheduler.runCurrent()
    }

    private fun update(id: String = "session") {
        manager.update(listOf(AgentLiveNotificationManager.RunningSession(
            id, "private prompt", context.getString(R.string.chat_status_running_command)
        )))
    }

    @Test
    fun togglingSettingRestoresTheActiveRunWithoutAnotherProducerUpdate() {
        setEnabled(true)
        update()
        assertTrue(AgentLiveNotificationState.state.value.active)
        setEnabled(false)
        assertFalse(AgentLiveNotificationState.state.value.active)
        assertFalse(TaskKeepaliveController.snapshot().serviceRequired)
        assertEquals(listOf("session"), KeepaliveNotificationState.state.value.agents.map { it.sessionId })
        setEnabled(true)
        assertTrue(AgentLiveNotificationState.state.value.active)
        assertFalse(AgentLiveNotificationState.state.value.completed)
        assertEquals(listOf("session"), AgentLiveNotificationState.state.value.sessions.map { it.sessionId })
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
        assertFalse(TaskKeepaliveController.snapshot().cpuRequired)
        assertFalse(TaskKeepaliveController.snapshot().wifiRequired)
    }

    @Test
    fun aRunRemovedWhileDisabledCannotReappearAsRunningOrCompleted() {
        setEnabled(true)
        update()
        setEnabled(false)
        manager.markCompletionOutcome("session", failed = false)
        manager.update(emptyList())
        setEnabled(true)
        assertFalse(AgentLiveNotificationState.state.value.active)
        assertFalse(AgentLiveNotificationState.state.value.completed)
        assertTrue(KeepaliveNotificationState.state.value.agents.isEmpty())
        assertEquals(0, TaskKeepaliveController.snapshot().ownerCount)
        assertFalse(TaskKeepaliveController.snapshot().serviceRequired)
    }

    @Test
    fun explicitDismissRemovesTheProducerBeforeLifecycleAndSettingRefreshes() {
        setEnabled(true)
        update()
        manager.dismiss()
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED
        setEnabled(false)
        setEnabled(true)
        assertFalse(AgentLiveNotificationState.state.value.active)
        assertTrue(KeepaliveNotificationState.state.value.agents.isEmpty())
        assertEquals(0, TaskKeepaliveController.snapshot().ownerCount)
    }

    @Test
    fun eachActivePublicationRetriesARejectedServiceStartWithoutDuplicatingOwners() {
        context.reject = true
        setEnabled(true)
        update()
        assertEquals(1, context.starts)
        assertFalse(manager.hasLiveNotificationEnabled())
        assertFalse(TaskKeepaliveController.snapshot().startPending)
        context.reject = false
        update()
        assertEquals(2, context.starts)
        assertTrue(TaskKeepaliveController.snapshot().startPending)
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
        assertFalse(manager.hasLiveNotificationEnabled())
        TaskKeepaliveController.attach(service)
        assertTrue(manager.hasLiveNotificationEnabled())
        repeat(3) { update() }
        assertEquals(2, context.starts)
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
    }

    @Test
    fun anActivePublicationReacquiresAfterServiceDetachButNotWhileTaskRemoved() {
        setEnabled(true)
        update()
        TaskKeepaliveController.attach(service)
        assertTrue(manager.hasLiveNotificationEnabled())
        TaskKeepaliveController.detach(service)
        update()
        assertEquals(2, context.starts)
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
        TaskKeepaliveController.onTaskRemoved(context)
        repeat(3) { update() }
        assertEquals(2, context.starts)
        assertTrue(TaskKeepaliveController.isSuspended(context))
        assertFalse(manager.hasLiveNotificationEnabled())
        TaskKeepaliveController.onAppVisible(context)
        assertEquals(3, context.starts)
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
    }

    @Test
    fun completionLeaseExpiresAfterFiveMinutesAndCannotClearANewerRun() {
        setEnabled(true)
        update()
        manager.markCompletionOutcome("session", failed = false)
        manager.update(emptyList())
        assertTrue(AgentLiveNotificationState.state.value.completed)
        assertTrue(AgentLiveNotificationState.state.value.completionVerified)
        assertEquals(1, TaskKeepaliveController.snapshot().ownerCount)
        dispatcher.scheduler.advanceTimeBy(4 * 60 * 1000L)
        manager.markSessionStarted("next")
        update("next")
        dispatcher.scheduler.advanceTimeBy(60 * 1000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf("next"), AgentLiveNotificationState.state.value.sessions.map { it.sessionId })
        manager.markCompletionOutcome("next", failed = true)
        manager.update(emptyList())
        assertTrue(AgentLiveNotificationState.state.value.failed)
        dispatcher.scheduler.advanceTimeBy(5 * 60 * 1000L)
        dispatcher.scheduler.runCurrent()
        assertFalse(AgentLiveNotificationState.state.value.active)
        assertEquals(0, TaskKeepaliveController.snapshot().ownerCount)
        assertFalse(TaskKeepaliveController.snapshot().serviceRequired)
    }

    @Test
    fun returningToForegroundClearsCompletionButKeepsAnActiveRun() {
        setEnabled(true)
        update()
        owner.registry.currentState = Lifecycle.State.STARTED
        assertTrue(AgentLiveNotificationState.state.value.active)
        owner.registry.currentState = Lifecycle.State.CREATED
        manager.markCompletionOutcome("session", failed = false)
        manager.update(emptyList())
        assertTrue(AgentLiveNotificationState.state.value.completed)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse(AgentLiveNotificationState.state.value.active)
        assertEquals(0, TaskKeepaliveController.snapshot().ownerCount)
        owner.registry.currentState = Lifecycle.State.CREATED
        assertFalse(AgentLiveNotificationState.state.value.active)
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
