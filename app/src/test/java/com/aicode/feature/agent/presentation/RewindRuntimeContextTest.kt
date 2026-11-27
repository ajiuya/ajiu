package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.domain.checkpoint.CheckpointRestoreResult
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
class RewindRuntimeContextTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun codeAndConversationRestoreUsesSessionAgentOwnerAndFinishesCodeBeforeConversation() = runTest(dispatcher, timeout = 15.seconds) {
        assertCodeRestore(PARENT, RewindOption.RESTORE_CODE_AND_CONVERSATION)
    }

    @Test
    fun codeOnlyRestoreUsesChildSessionAgentOwnerWithoutRewindingConversation() = runTest(dispatcher, timeout = 15.seconds) {
        assertCodeRestore(CHILD, RewindOption.RESTORE_CODE)
    }

    @Test
    fun everyOptionWithoutCheckpointSkipsExecutionFreezeAndCodeRestoration() = runTest(dispatcher, timeout = 15.seconds) {
        prepareTarget(PARENT, hasCheckpoint = false)
        coEvery { fixture.executionFactory.freeze(any(), any(), any()) } throws LocalRuntimeAuthorizationRequired()

        for (option in RewindOption.entries) {
            val result = requireNotNull(fixture.manager.rewind(PARENT, MESSAGE, option))
            assertEquals(if (option == RewindOption.RESTORE_CODE) null else PROMPT, result.fillPrompt)
            assertTrue(result.attachments.isEmpty())
            assertTrue(result.conflicts.isEmpty())
        }

        coVerify(exactly = 0) { fixture.executionFactory.freeze(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(any(), any()) }
        coVerify(exactly = 2) { fixture.persistence.rewindConversation(PARENT, TIMESTAMP) }
        assertFalse(PARENT in fixture.stopping)
    }

    @Test
    fun restoreFailureOrCancellationNeverRewindsConversationAndClearsStoppingState() = runTest(dispatcher, timeout = 15.seconds) {
        prepareTarget(PARENT)
        val execution = installExecution(PARENT)
        for (failure in listOf(IOException("Restore failed"), CancellationException("Restore cancelled"))) {
            coEvery { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(PARENT, CHECKPOINT) } coAnswers {
                assertSame(execution, currentCoroutineContext()[WorkspaceExecutionContext])
                assertSame(execution, WorkspaceExecutionContext.current())
                throw failure
            }

            val outcome = runCatching { fixture.manager.rewind(PARENT, MESSAGE, RewindOption.RESTORE_CODE_AND_CONVERSATION) }

            assertTrue(failure.javaClass.isInstance(outcome.exceptionOrNull()))
            assertEquals(failure.message, outcome.exceptionOrNull()?.message)
            assertFalse(PARENT in fixture.stopping)
            assertNull(WorkspaceExecutionContext.current())
        }
        coVerify(exactly = 2) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(PARENT, CHECKPOINT) }
        coVerify(exactly = 0) { fixture.persistence.rewindConversation(any(), any()) }
    }

    @Test
    fun executionFreezeCallbackRejectsSourceThatChangedAndReturnedToOriginalMode() = runTest(dispatcher, timeout = 15.seconds) {
        prepareTarget(PARENT)
        coEvery { fixture.executionFactory.freeze(any(), any(), any()) } coAnswers {
            assertEquals(RuntimeOwner(RuntimeOwnerKind.AGENT, PARENT), secondArg<RuntimeOwner?>())
            val checkSource = requireNotNull(thirdArg<(() -> Unit)?>())
            checkSource()
            switchSourceAwayAndBack()
            checkSource()
            error("Source callback accepted a changed execution source")
        }

        val outcome = runCatching { fixture.manager.rewind(PARENT, MESSAGE, RewindOption.RESTORE_CODE_AND_CONVERSATION) }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        coVerify(exactly = 1) { fixture.executionFactory.freeze(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(any(), any()) }
        coVerify(exactly = 0) { fixture.persistence.rewindConversation(any(), any()) }
        assertFalse(PARENT in fixture.stopping)
    }

    @Test
    fun sourceChangeDuringCodeRestorationPreventsConversationRewind() = runTest(dispatcher, timeout = 15.seconds) {
        prepareTarget(PARENT)
        val execution = installExecution(PARENT)
        coEvery { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(PARENT, CHECKPOINT) } coAnswers {
            assertSame(execution, currentCoroutineContext()[WorkspaceExecutionContext])
            assertSame(execution, WorkspaceExecutionContext.current())
            switchSourceAwayAndBack()
            CheckpointRestoreResult(1, emptyList())
        }

        val outcome = runCatching { fixture.manager.rewind(PARENT, MESSAGE, RewindOption.RESTORE_CODE_AND_CONVERSATION) }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        coVerify(exactly = 1) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(PARENT, CHECKPOINT) }
        coVerify(exactly = 0) { fixture.persistence.rewindConversation(any(), any()) }
        assertFalse(PARENT in fixture.stopping)
        assertNull(WorkspaceExecutionContext.current())
    }

    private fun prepareTarget(sessionId: String, hasCheckpoint: Boolean = true) {
        coEvery { fixture.messages.getMessageById(MESSAGE) } returns AgentMessageEntity(
            id = MESSAGE, sessionId = sessionId, role = MessageRole.USER.name,
            content = PROMPT, timestamp = TIMESTAMP
        )
        coEvery { fixture.dependencies.checkpointDao.getCheckpointBySessionAndMessage(sessionId, MESSAGE) } returns
            if (hasCheckpoint) CheckpointEntity(CHECKPOINT, sessionId, MESSAGE, PROMPT, TIMESTAMP) else null
    }

    private fun installExecution(sessionId: String): WorkspaceExecutionContext {
        val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, sessionId)
        val execution = WorkspaceExecutionContext(
            ExecutionMode.LOCAL_PROOT, "rewind-fixture", fixture.browseFileAccess, null, null, runtimeOwner = owner
        ) {}
        coEvery { fixture.executionFactory.freeze(any(), any(), any()) } coAnswers {
            val capture = requireNotNull(firstArg<ProjectMemoryCapture?>())
            assertEquals(fixture.projectIdentity, capture.identity)
            capture.assertCurrent()
            assertEquals(owner, secondArg<RuntimeOwner?>())
            requireNotNull(thirdArg<(() -> Unit)?>()).invoke()
            execution
        }
        return execution
    }

    private suspend fun assertCodeRestore(sessionId: String, option: RewindOption) {
        prepareTarget(sessionId)
        val execution = installExecution(sessionId)
        var codeRestored = false
        val conflicts = listOf("/work/shared.kt")
        coEvery { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(sessionId, CHECKPOINT) } coAnswers {
            assertSame(execution, currentCoroutineContext()[WorkspaceExecutionContext])
            assertSame(execution, WorkspaceExecutionContext.current())
            assertEquals(RuntimeOwner(RuntimeOwnerKind.AGENT, sessionId), execution.runtimeOwner)
            coVerify(exactly = 0) { fixture.persistence.rewindConversation(any(), any()) }
            codeRestored = true
            CheckpointRestoreResult(1, conflicts)
        }
        coEvery { fixture.persistence.rewindConversation(sessionId, TIMESTAMP) } coAnswers {
            assertTrue(codeRestored)
        }

        val result = requireNotNull(fixture.manager.rewind(sessionId, MESSAGE, option))

        assertTrue(codeRestored)
        assertEquals(conflicts, result.conflicts)
        assertTrue(result.attachments.isEmpty())
        assertEquals(if (option == RewindOption.RESTORE_CODE) null else PROMPT, result.fillPrompt)
        coVerify(exactly = 1) {
            fixture.executionFactory.freeze(any(), RuntimeOwner(RuntimeOwnerKind.AGENT, sessionId), any())
        }
        coVerify(exactly = 1) { fixture.dependencies.checkpointManager.restoreCodeToCheckpoint(sessionId, CHECKPOINT) }
        coVerify(exactly = if (option == RewindOption.RESTORE_CODE) 0 else 1) {
            fixture.persistence.rewindConversation(any(), any())
        }
        assertFalse(sessionId in fixture.stopping)
        assertNull(WorkspaceExecutionContext.current())
    }

    private fun switchSourceAwayAndBack() {
        fixture.executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
        fixture.executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
    }

    private companion object {
        const val MESSAGE = "rewind-target"
        const val CHECKPOINT = "rewind-checkpoint"
        const val PROMPT = "original prompt"
        const val TIMESTAMP = 42L
    }
}
