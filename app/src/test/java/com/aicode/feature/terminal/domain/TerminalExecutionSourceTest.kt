package com.aicode.feature.terminal.domain

import android.content.ContextWrapper
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ExecutionSourceChangedException
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.termux.terminal.TerminalSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerminalExecutionSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = object : ContextWrapper(null) {}
    private val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "source-session")
    private lateinit var local: TerminalSessionManager
    private lateinit var remote: RemoteTerminalSessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController, KeepaliveNotificationState)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.acquireForInput(any(), any()) } returns false
        every { KeepaliveNotificationState.updateTerminals(any(), any()) } just Runs
        local = TerminalSessionManager(context, mockk(), mockk(), mockk(), mockk(), mockk())
        remote = RemoteTerminalSessionManager(mockk(), ExecutionModeHolder(), mockk(), context)
    }

    @After
    fun tearDown() {
        for (manager in listOf(local, remote)) {
            (manager.javaClass.getDeclaredField("monitorScope").apply { isAccessible = true }
                .get(manager) as CoroutineScope).cancel()
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkObject(TaskKeepaliveController, KeepaliveNotificationState)
    }

    private fun capture(mode: ExecutionMode, identity: String, check: () -> Unit = {}) =
        WorkspaceExecutionContext(mode, identity, mockk(), null, null, runtimeOwner = owner, checkSource = check)

    private fun addTab(manager: TerminalSessionProvider, execution: WorkspaceExecutionContext?): TerminalTab {
        val session = mockk<TerminalSession>()
        every { session.write(any<ByteArray>(), any(), any()) } just Runs
        val tab = TerminalTab("term-source", "Source", session, true, "shell", sourceSessionId = owner.scopeId,
            runState = RunState.Running, runtimeOwner = owner, capturedExecution = execution)
        manager.javaClass.getDeclaredMethod("addTab", TerminalTab::class.java).apply { isAccessible = true }
            .invoke(manager, tab)
        return tab
    }

    private fun assertOwnedWritesRefused(manager: TerminalSessionProvider, id: String) {
        assertTrue(runCatching { manager.sendOwnedInput(id, owner, "must-not-send") }.exceptionOrNull() is ExecutionSourceChangedException)
        assertTrue(runCatching { manager.writeOwnedText(id, owner, "must-not-write") }.exceptionOrNull() is ExecutionSourceChangedException)
        assertTrue(runCatching { manager.writeOwnedBytes(id, owner, 13) }.exceptionOrNull() is ExecutionSourceChangedException)
    }

    @Test
    fun profileOrRemoteGenerationMismatchRejectsEveryOwnedWriteBeforeSessionOrKeepalive() = runTest(dispatcher) {
        for ((manager, mode) in listOf(local to ExecutionMode.LOCAL_PROOT, remote to ExecutionMode.REMOTE_SSH)) {
            val tab = addTab(manager, capture(mode, "old-profile-or-connection"))
            withContext(capture(mode, "selected-new-profile-or-connection")) { assertOwnedWritesRefused(manager, tab.id) }
            verify(exactly = 0) { tab.session.write(any<ByteArray>(), any(), any()) }
        }
        verify(exactly = 0) { TaskKeepaliveController.acquireForInput(any(), any()) }
    }

    @Test
    fun modeMismatchRejectsOwnedWritesEvenWhenCacheIdentityAndOwnerAreEqual() = runTest(dispatcher) {
        val tab = addTab(local, capture(ExecutionMode.LOCAL_PROOT, "same-identity"))
        withContext(capture(ExecutionMode.REMOTE_SSH, "same-identity")) { assertOwnedWritesRefused(local, tab.id) }
        verify(exactly = 0) { tab.session.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun absentCreationOrCurrentCaptureCannotAuthorizeAiInput() = runTest(dispatcher) {
        val unbound = addTab(local, null)
        withContext(capture(ExecutionMode.LOCAL_PROOT, "selected")) { assertOwnedWritesRefused(local, unbound.id) }
        val bound = addTab(remote, capture(ExecutionMode.REMOTE_SSH, "remote"))
        assertNull(WorkspaceExecutionContext.current())
        assertOwnedWritesRefused(remote, bound.id)
        verify(exactly = 0) { unbound.session.write(any<ByteArray>(), any(), any()) }
        verify(exactly = 0) { bound.session.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun equalModeAndIdentityAcceptANewCaptureButStillCheckTheActualCreationSource() = runTest(dispatcher) {
        for ((manager, mode) in listOf(local to ExecutionMode.LOCAL_PROOT, remote to ExecutionMode.REMOTE_SSH)) {
            var checked = false
            val captured = capture(mode, "unchanged") { checked = true }
            val tab = addTab(manager, captured)
            withContext(capture(mode, "unchanged")) {
                assertTrue(manager.sendOwnedInput(tab.id, owner, "command", false))
                assertTrue(manager.writeOwnedText(tab.id, owner, "text"))
                assertTrue(manager.writeOwnedBytes(tab.id, owner, 13))
            }
            assertTrue(checked)
            assertSame(captured, tab.capturedExecution)
            verify(exactly = 3) { tab.session.write(any<ByteArray>(), any(), any()) }
        }
    }

    @Test
    fun capturedCancellationIsNotConvertedOrIgnoredAndWrongOwnersRemainInaccessible() = runTest(dispatcher) {
        val cancelled = CancellationException("Captured source was revoked")
        val captured = capture(ExecutionMode.LOCAL_PROOT, "unchanged") { throw cancelled }
        val tab = addTab(local, captured)
        withContext(capture(ExecutionMode.LOCAL_PROOT, "unchanged")) {
            val other = RuntimeOwner(RuntimeOwnerKind.AGENT, "another-session")
            assertFalse(local.sendOwnedInput(tab.id, other, "private"))
            assertSame(cancelled, runCatching { local.sendOwnedInput(tab.id, owner, "must-not-write") }.exceptionOrNull())
        }
        verify(exactly = 0) { tab.session.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun nativeRawAndUserInteractiveInputDoNotAcquirePerLineAiApprovalOrSelectedSourceChecks() = runTest(dispatcher) {
        for ((manager, mode) in listOf(local to ExecutionMode.LOCAL_PROOT, remote to ExecutionMode.REMOTE_SSH)) {
            val tab = addTab(manager, capture(mode, "old-source") { error("Native input must not check AI execution sources") })
            assertTrue(manager.sendInput(tab.id, "interactive input", false))
            assertTrue(manager.writeToTab(tab.id, "raw text"))
            assertTrue(manager.writeBytesToTab(tab.id, 3))
            tab.onUserInput()
            verify(exactly = 3) { tab.session.write(any<ByteArray>(), any(), any()) }
        }
    }
}
