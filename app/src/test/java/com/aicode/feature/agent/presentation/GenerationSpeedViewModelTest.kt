package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class GenerationSpeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private val vm get() = fixture.viewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        vm.setWorkspace("/work")
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

    @Test
    fun liveReadingSurvivesIdleRetryKeySwitchAndNextModelCall() = runTest(dispatcher) {
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.GenerationStarted)
            started.complete(Unit)
            for (event in events) emit(event)
        }
        val run = vm.executeAgentRequestStream("first", targetSessionId = PARENT)
        started.await()
        runCurrent()
        assertNull(vm.generationSpeed.value)

        val live = AgentEvent.GenerationSpeed(21.5, estimated = true)
        events.send(live)
        assertEquals(live, vm.generationSpeed.first { it == live })

        events.send(AgentEvent.GenerationSpeed(0.0, estimated = true))
        events.send(AgentEvent.Retrying(1, 3, RetryErrorInfo(RetryErrorKind.TIMEOUT)))
        vm.retryState.first { it?.attempt == 1 }
        assertEquals(live, vm.generationSpeed.value)

        events.send(AgentEvent.GenerationStarted)
        events.send(AgentEvent.Retrying(2, 3, RetryErrorInfo(RetryErrorKind.TIMEOUT)))
        vm.retryState.first { it?.attempt == 2 }
        assertEquals(live, vm.generationSpeed.value)

        events.send(AgentEvent.KeySwitched(2, 3))
        vm.keySwitchState.first { it?.newIndex == 2 }
        assertEquals(live, vm.generationSpeed.value)

        events.send(AgentEvent.GenerationSpeed(0.0, estimated = true))
        events.send(AgentEvent.Retrying(3, 3, RetryErrorInfo(RetryErrorKind.TIMEOUT)))
        vm.retryState.first { it?.attempt == 3 }
        assertEquals(live, vm.generationSpeed.value)

        val nextLive = AgentEvent.GenerationSpeed(13.0, estimated = true)
        events.send(nextLive)
        assertEquals(nextLive, vm.generationSpeed.first { it == nextLive })
        val calibrated = AgentEvent.GenerationSpeed(18.25, estimated = false)
        events.send(calibrated)
        assertEquals(calibrated, vm.generationSpeed.first { it == calibrated })
        events.send(AgentEvent.GenerationStarted)
        events.send(AgentEvent.Completed)
        events.close()
        run.join()
        runCurrent()
        assertEquals(calibrated, vm.generationSpeed.value)
    }

    @Test
    fun completedReadingRemainsVisibleWhileNextRequestWaitsForSamples() = runTest(dispatcher) {
        val previous = AgentEvent.GenerationSpeed(24.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(previous)
            emit(AgentEvent.Completed)
        }
        vm.executeAgentRequestStream("first", targetSessionId = PARENT).join()
        runCurrent()
        assertEquals(previous, vm.generationSpeed.value)

        val started = CompletableDeferred<Unit>()
        val releaseSample = CompletableDeferred<Unit>()
        val replacement = AgentEvent.GenerationSpeed(12.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.GenerationStarted)
            started.complete(Unit)
            releaseSample.await()
            emit(replacement)
            emit(AgentEvent.Completed)
        }
        val next = vm.executeAgentRequestStream("next", targetSessionId = PARENT)
        started.await()
        runCurrent()
        assertEquals(previous, vm.generationSpeed.value)
        releaseSample.complete(Unit)
        next.join()
        runCurrent()
        assertEquals(replacement, vm.generationSpeed.value)
    }

    @Test
    fun zeroOnlyStreamDoesNotCreateAReading() = runTest(dispatcher) {
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.GenerationStarted)
            emit(AgentEvent.GenerationSpeed(0.0, estimated = true))
            emit(AgentEvent.Completed)
        }
        vm.executeAgentRequestStream("empty", targetSessionId = PARENT).join()
        runCurrent()
        verify(exactly = 1) { fixture.workflow.executeEvents("empty", any(), any()) }
        assertTrue(PARENT in vm.completedSessions.value)
        assertNull(vm.generationSpeed.value)
    }

    @Test
    fun failedRequestRetainsLastLiveReading() = runTest(dispatcher) {
        val live = AgentEvent.GenerationSpeed(16.0, estimated = true)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(live)
            emit(AgentEvent.Failed("network failure"))
        }
        vm.executeAgentRequestStream("fail", targetSessionId = PARENT).join()
        runCurrent()
        assertTrue(vm.agentStates.value[PARENT] is AgentUIState.Error)
        assertEquals(live, vm.generationSpeed.value)
    }

    @Test
    fun unexpectedStreamFailureRetainsLastLiveReading() = runTest(dispatcher) {
        val live = AgentEvent.GenerationSpeed(17.0, estimated = true)
        val releaseFailure = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(live)
            releaseFailure.await()
            throw IOException("connection closed")
        }
        val run = vm.executeAgentRequestStream("fail", targetSessionId = PARENT)
        assertEquals(live, vm.generationSpeed.first { it == live })
        releaseFailure.complete(Unit)
        run.join()
        vm.agentStates.first { it[PARENT] is AgentUIState.Error }
        assertEquals(live, vm.generationSpeed.value)
    }

    @Test
    fun stoppingRequestRetainsLastLiveReading() = runTest(dispatcher) {
        val live = AgentEvent.GenerationSpeed(19.0, estimated = true)
        val started = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(live)
            started.complete(Unit)
            awaitCancellation()
        }
        val run = vm.executeAgentRequestStream("stop", targetSessionId = PARENT)
        started.await()
        runCurrent()
        vm.stopAgentSession(PARENT)
        run.join()
        runCurrent()
        assertTrue(run.isCancelled)
        assertEquals(live, vm.generationSpeed.value)
    }

    @Test
    fun stoppingAllKeepsIndependentSessionReadings() = runTest(dispatcher) {
        val parentSpeed = AgentEvent.GenerationSpeed(22.0, estimated = false)
        val childSpeed = AgentEvent.GenerationSpeed(33.0, estimated = true)
        val started = listOf(PARENT, CHILD).associateWith { CompletableDeferred<Unit>() }
        every { fixture.workflow.executeEvents(any(), any(), any()) } answers {
            val sessionId = requireNotNull(secondArg<AgentContext>().sessionId)
            flow {
                emit(if (sessionId == PARENT) parentSpeed else childSpeed)
                started.getValue(sessionId).complete(Unit)
                awaitCancellation()
            }
        }
        val parentRun = vm.executeAgentRequestStream("parent", targetSessionId = PARENT)
        started.getValue(PARENT).await()
        val childRun = vm.executeAgentRequestStream("child", targetSessionId = CHILD)
        started.getValue(CHILD).await()
        assertTrue(parentRun.isActive)
        assertTrue(childRun.isActive)
        vm.stopAllAgents()
        parentRun.join()
        childRun.join()
        runCurrent()
        assertTrue(parentRun.isCancelled)
        assertTrue(childRun.isCancelled)
        vm.selectSession(CHILD)
        runCurrent()
        assertEquals(childSpeed, vm.generationSpeed.value)
        vm.selectSession("no-samples")
        runCurrent()
        assertNull(vm.generationSpeed.value)
        vm.selectSession(PARENT)
        runCurrent()
        assertEquals(parentSpeed, vm.generationSpeed.value)
    }

    @Test
    fun changingModelInvalidatesCurrentSessionAndDirectChildrenWhileRewindStaysLocal() = runTest(dispatcher) {
        val speed = AgentEvent.GenerationSpeed(23.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(speed)
            emit(AgentEvent.Completed)
        }
        vm.executeAgentRequestStream("parent", targetSessionId = PARENT).join()
        vm.executeAgentRequestStream("child", targetSessionId = CHILD).join()
        vm.selectSession(PARENT)
        vm.setSessionProviderModel("new-provider", "new-model")
        runCurrent()
        assertNull(vm.generationSpeed.value)
        vm.selectSession(CHILD)
        runCurrent()
        assertNull(vm.generationSpeed.value)
        assertEquals("new-model", fixture.sessionRows.getValue(CHILD).model)
        coVerify(exactly = 1) { fixture.sessions.updateProviderModelWithDirectChildren(PARENT, "new-provider", "new-model") }
        vm.executeAgentRequestStream("child-again", targetSessionId = CHILD).join()
        runCurrent()
        assertEquals(speed, vm.generationSpeed.value)

        coEvery { fixture.messages.getMessageById("rewind-target") } returns AgentMessageEntity(
            id = "rewind-target", sessionId = CHILD, role = "USER", content = "original", timestamp = 1
        )
        vm.executeRewindOption("rewind-target", RewindOption.RESTORE_CONVERSATION) { _, _ -> }.join()
        runCurrent()
        assertNull(vm.generationSpeed.value)
    }

    @Test
    fun modelSelectionTargetsTheCapturedSessionAndNeverUnrelatedOrGrandchildReadings() = runTest(dispatcher) {
        val other = "other-root"
        val grandchild = "grandchild"
        fixture.sessionRows[other] = fixture.sessionRows.getValue(PARENT).copy(id = other, title = "Other")
        fixture.sessionRows[grandchild] = fixture.sessionRows.getValue(CHILD).copy(id = grandchild, parentId = CHILD)
        val speed = AgentEvent.GenerationSpeed(23.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(speed)
            emit(AgentEvent.Completed)
        }
        for (id in listOf(PARENT, CHILD, other, grandchild)) {
            vm.executeAgentRequestStream("sample-$id", targetSessionId = id).join()
        }
        vm.selectSession(PARENT)
        runCurrent()
        vm.setSessionProviderModel("new-provider", "new-model")
        vm.selectSession(other)
        runCurrent()

        assertEquals(speed, vm.generationSpeed.value)
        assertEquals("model", fixture.sessionRows.getValue(other).model)
        assertEquals("model", fixture.sessionRows.getValue(grandchild).model)
        assertEquals("new-model", fixture.sessionRows.getValue(PARENT).model)
        assertEquals("new-model", fixture.sessionRows.getValue(CHILD).model)
        vm.selectSession(grandchild)
        runCurrent()
        assertEquals(speed, vm.generationSpeed.value)
        vm.selectSession(CHILD)
        runCurrent()
        assertNull(vm.generationSpeed.value)
        coVerify(exactly = 1) { fixture.sessions.updateProviderModelWithDirectChildren(PARENT, "new-provider", "new-model") }
        coVerify(exactly = 0) { fixture.sessions.updateProviderModelWithDirectChildren(other, any(), any()) }
    }

    @Test
    fun modelSelectionWhileRunningDoesNotChangeTheGlobalDefault() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            started.complete(Unit)
            release.await()
            emit(AgentEvent.Completed)
        }
        val run = vm.executeAgentRequestStream("running", targetSessionId = PARENT)
        started.await()
        vm.selectSession(PARENT)
        runCurrent()
        vm.setSessionProviderModel("new-provider", "new-model")
        runCurrent()
        coVerify(exactly = 0) { fixture.dependencies.defaultModelSettingsRepository.setDefaultModel(any(), any()) }
        assertEquals("new-model", fixture.sessionRows.getValue(CHILD).model)
        release.complete(Unit)
        run.join()
    }

    @Test
    fun deletingSessionClearsItsReadingAndPreservesAnotherSessionsReading() = runTest(dispatcher) {
        val other = "other"
        fixture.sessionRows[other] = fixture.sessionRows.getValue(PARENT).copy(id = other)
        val speed = AgentEvent.GenerationSpeed(25.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(speed)
            emit(AgentEvent.Completed)
        }
        vm.executeAgentRequestStream("parent", targetSessionId = PARENT).join()
        vm.executeAgentRequestStream("other", targetSessionId = other).join()
        vm.selectSession(PARENT)
        vm.deleteSession(other).join()
        runCurrent()
        assertEquals(speed, vm.generationSpeed.value)
        vm.selectSession(other)
        runCurrent()
        assertNull(vm.generationSpeed.value)
    }

    @Test
    fun bulkCascadeDeletionClearsParentAndChildReadingsOnly() = runTest(dispatcher) {
        val other = "other"
        fixture.sessionRows[other] = fixture.sessionRows.getValue(PARENT).copy(id = other)
        val speed = AgentEvent.GenerationSpeed(26.0, estimated = false)
        every { fixture.workflow.executeEvents(any(), any(), any()) } returns flow {
            emit(speed)
            emit(AgentEvent.Completed)
        }
        listOf(PARENT, CHILD, other).forEach { id ->
            vm.executeAgentRequestStream(id, targetSessionId = id).join()
        }
        coEvery { fixture.sessions.deleteSession(PARENT) } answers {
            fixture.sessionRows.remove(PARENT)
            fixture.sessionRows.remove(CHILD)
            listOf(PARENT, CHILD)
        }
        vm.selectSession(other)
        vm.deleteSessions(setOf(PARENT)).join()
        runCurrent()
        assertEquals(speed, vm.generationSpeed.value)
        listOf(PARENT, CHILD).forEach { id ->
            vm.selectSession(id)
            runCurrent()
            assertNull(vm.generationSpeed.value)
        }
    }
}
