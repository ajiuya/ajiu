package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.R
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LiveNotificationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        context = ApplicationProvider.getApplicationContext()
        fixture = SubAgentViewModelQueueFixture(context)
        fixture.viewModel.setWorkspace("/work")
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            unmockkObject(TaskKeepaliveController)
            Dispatchers.resetMain()
        }
    }

    private fun progress(text: String, toolName: String = "Bash") {
        fixture.runningTools.value = mapOf(PARENT to mapOf("tool" to RunningToolOutput("tool", text, toolName)))
    }

    @Test
    fun continuousToolOutputDoesNotDelayOrRepublishTheSameActivityLabel() = runTest(dispatcher) {
        clearMocks(fixture.liveNotifications, answers = false)
        fixture.agentStates.value = mapOf(PARENT to AgentUIState.Streaming)
        progress("first")
        runCurrent()
        verify(exactly = 1) {
            fixture.liveNotifications.update(match { it.singleOrNull()?.statusLabel == context.getString(R.string.chat_status_running_command) })
        }
        repeat(20) {
            advanceTimeBy(250L)
            progress("line $it")
            runCurrent()
        }
        verify(exactly = 1) { fixture.liveNotifications.update(match { it.isNotEmpty() }) }
        fixture.agentStates.value = mapOf(PARENT to AgentUIState.Result(WorkflowStatus.SUCCESS))
        runCurrent()
        verify(exactly = 1) { fixture.liveNotifications.update(emptyList()) }
    }

    @Test
    fun changedActivityAndShortTasksArePublishedWithoutAQuietWindow() = runTest(dispatcher) {
        clearMocks(fixture.liveNotifications, answers = false)
        fixture.agentStates.value = mapOf(PARENT to AgentUIState.Loading)
        runCurrent()
        verify(exactly = 1) {
            fixture.liveNotifications.update(match { it.singleOrNull()?.statusLabel == context.getString(R.string.chat_status_thinking) })
        }
        advanceTimeBy(100L)
        progress("reading", "readFile")
        runCurrent()
        verify(exactly = 1) {
            fixture.liveNotifications.update(match { it.singleOrNull()?.statusLabel == context.getString(R.string.chat_status_reading_file) })
        }
        fixture.agentStates.value = emptyMap()
        runCurrent()
        verify(exactly = 1) { fixture.liveNotifications.update(emptyList()) }
    }

    @Test
    fun cancelledSessionLookupCannotPublishAStaleRunningNotification() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { fixture.sessions.getSessionById(PARENT) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            fixture.sessionRows[PARENT]
        }
        clearMocks(fixture.liveNotifications, answers = false)
        fixture.agentStates.value = mapOf(PARENT to AgentUIState.Streaming)
        runCurrent()
        assertTrue(entered.isCompleted)
        fixture.agentStates.value = emptyMap()
        runCurrent()
        finish.complete(Unit)
        runCurrent()
        verify(exactly = 0) { fixture.liveNotifications.update(match { it.isNotEmpty() }) }
        verify(exactly = 1) { fixture.liveNotifications.update(emptyList()) }
    }

    @Test
    fun disabledSettingDoesNotResetTheManagersProducerStateFromTheViewModel() {
        verify(exactly = 0) { fixture.liveNotifications.dismiss() }
    }
}
