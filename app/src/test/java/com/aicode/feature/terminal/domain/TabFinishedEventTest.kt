package com.aicode.feature.terminal.domain

import android.content.ContextWrapper
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.ProotInvocation
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalBuffer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabFinishedEventTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = FakeContext()
    private val engine = mockk<LinuxContainerEngine>()
    private val connection = mockk<RemoteSshConnection>()
    private val workspace = mockk<WorkspaceRepository>()
    private val holder = ExecutionModeHolder()
    private var identity = RemoteConnectionIdentity(mockk(), "/home/fixture")
    private lateinit var local: TerminalSessionManager
    private lateinit var remote: RemoteTerminalSessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FileLogger, TaskKeepaliveController, KeepaliveNotificationState)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.d(any(), any()) } just Runs
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        every { KeepaliveNotificationState.updateTerminals(any(), any()) } just Runs
        mockkConstructor(TerminalSession::class)
        every { anyConstructed<TerminalSession>().updateSize(any(), any()) } just Runs
        every { anyConstructed<TerminalSession>().write(any<String>()) } just Runs
        every { anyConstructed<TerminalSession>().emulator } returns null
        every { anyConstructed<TerminalSession>().pid } returns 42
        every { anyConstructed<TerminalSession>().isRunning } returns true
        every { anyConstructed<TerminalSession>().finishIfRunning() } just Runs
        coEvery { engine.ensureInstalled() } returns Unit
        every { engine.isContainerInstalled() } returns true
        every { engine.defaultShell() } returns "/bin/sh"
        every { engine.buildProotInvocation(any(), any()) } returns ProotInvocation(listOf("/fixture/proot"), emptyMap())
        every { workspace.currentPath() } returns "/workspace/fixture"
        every { connection.isConnected() } returns true
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw kotlinx.coroutines.CancellationException("Changed identity")
        }
        val shellSession = mockk<Session>(relaxed = true)
        val shell = mockk<Session.Shell>(relaxed = true)
        every { connection.startShellSession() } returns shellSession
        every { identity.owner.startSession() } returns shellSession
        every { shellSession.startShell() } returns shell
        local = TerminalSessionManager(context, engine, workspace)
        remote = RemoteTerminalSessionManager(connection, holder, workspace, context)
    }

    @After
    fun tearDown() {
        for (manager in listOf(local, remote)) {
            val scope = manager.javaClass.getDeclaredField("monitorScope").apply { isAccessible = true }
            (scope.get(manager) as CoroutineScope).cancel()
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkConstructor(TerminalSession::class)
        unmockkObject(FileLogger, TaskKeepaliveController, KeepaliveNotificationState)
    }

    @Test
    fun existingPositionalConstructorsKeepTheirOrderAndDefaults() {
        val event = TabFinishedEvent("term-1", "Build", "build", 17, "session-1", "output")
        val (tabId, title, command, exitCode, sourceSessionId, tailOutput) = event
        assertEquals(listOf("term-1", "Build", "build", 17, "session-1", "output"),
            listOf(tabId, title, command, exitCode, sourceSessionId, tailOutput))
        assertNull(event.sourceMode)
        assertNull(event.channelGeneration)
        assertNull(TabFinishedEvent("term-1", "Build", "build", 0, null).tailOutput)
    }

    @Test
    fun localStartFreezesSourceAndFinishesOnlyOnceAfterModeSwitch() = runBlocking {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            local.tabFinishedEvents.collect { received += it }
        }
        try {
            val id = local.startBackgroundCommand("build", "Build", true, "session-1")
            val tab = local.tab(id)!!
            assertEquals("LOCAL_PROOT", tab.sourceMode)
            assertNull(tab.channelGeneration)
            holder.setMode(ExecutionMode.REMOTE_SSH)
            finish(local, tab, 17)
            finish(local, tab, 0)
            yield()
            assertEquals(listOf(TabFinishedEvent(id, "Build", "build", 17, "session-1", "",
                sourceMode = "LOCAL_PROOT", channelGeneration = null)), received)
            verify(exactly = 0) { connection.captureIdentity() }
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun remoteCompletionKeepsEachStartGenerationAfterReconnectAndDisconnect() = runBlocking {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            remote.tabFinishedEvents.collect { received += it }
        }
        try {
            val firstIdentity = identity
            val firstId = remote.startBackgroundCommand("first", "First", true, "session-1")
            identity = RemoteConnectionIdentity(mockk(), firstIdentity.home)
            every { identity.owner.startSession() } returns mockk<Session>(relaxed = true)
            val secondIdentity = identity
            val secondId = remote.startBackgroundCommand("second", "Second", true, "session-2")
            assertNotEquals(firstIdentity.generation, secondIdentity.generation)
            assertEquals(firstIdentity.generation.toString(), remote.tab(firstId)!!.channelGeneration)
            assertEquals(secondIdentity.generation.toString(), remote.tab(secondId)!!.channelGeneration)
            every { connection.isConnected() } returns false
            every { connection.captureIdentity() } throws IllegalStateException("Disconnected fixture")
            holder.setMode(ExecutionMode.LOCAL_PROOT)
            finish(remote, remote.tab(secondId)!!, 0)
            finish(remote, remote.tab(firstId)!!, 17)
            finish(remote, remote.tab(firstId)!!, 0)
            yield()
            assertEquals(listOf(
                TabFinishedEvent(secondId, "Second", "second", 0, "session-2", "",
                    sourceMode = "REMOTE_SSH", channelGeneration = secondIdentity.generation.toString()),
                TabFinishedEvent(firstId, "First", "first", 17, "session-1", "",
                    sourceMode = "REMOTE_SSH", channelGeneration = firstIdentity.generation.toString())
            ), received)
            verify(exactly = 2) { connection.captureIdentity() }
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun nonNotifyingTabsDoNotProduceCompletionEvents() = runBlocking {
        val received = mutableListOf<TabFinishedEvent>()
        val localCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            local.tabFinishedEvents.collect { received += it }
        }
        val remoteCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            remote.tabFinishedEvents.collect { received += it }
        }
        try {
            val localId = local.startBackgroundCommand("local", null, false, "session-1")
            holder.setMode(ExecutionMode.REMOTE_SSH)
            val remoteId = remote.startBackgroundCommand("remote", null, false, "session-2")
            finish(local, local.tab(localId)!!, 0)
            finish(remote, remote.tab(remoteId)!!, 0)
            yield()
            assertEquals(emptyList<TabFinishedEvent>(), received)
            assertFalse(local.tab(localId)!!.finishedNotified)
            assertFalse(remote.tab(remoteId)!!.finishedNotified)
        } finally {
            localCollector.cancelAndJoin()
            remoteCollector.cancelAndJoin()
        }
    }

    @Test
    fun markerResultIsFrozenBeforeBackendStopsAndLateCallbacksDoNotNotifyAgain() = runBlocking {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            local.tabFinishedEvents.collect { received += it }
        }
        try {
            val tab = local.tab(local.startBackgroundCommand("build", null, true, "session-1"))!!
            tab.markerExitCode = 23
            every { tab.session.finishIfRunning() } answers {
                assertEquals(RunState.Finished(23), tab.runState)
                finish(local, tab, 137)
            }
            finish(local, tab, 0)
            finish(local, tab, 137)
            yield()
            assertEquals(RunState.Finished(23), tab.runState)
            assertEquals(listOf(23), received.map { it.exitCode })
            verify(exactly = 1) { tab.session.finishIfRunning() }
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun localAndRemoteOutputCallbacksInvalidateCachedSnapshotsWithoutAView() = runBlocking {
        val emulator = mockk<TerminalEmulator>()
        val screen = mockk<TerminalBuffer>()
        emulator.mColumns = 80
        emulator.mRows = 24
        every { emulator.screen } returns screen
        var text = "before\n"
        var builds = 0
        every { screen.transcriptText } answers { builds++; text }
        every { anyConstructed<TerminalSession>().emulator } returns emulator
        val localId = local.startBackgroundCommand("local", null, false, null)
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val remoteId = remote.startBackgroundCommand("remote", null, false, null)
        val pairs = listOf(local.tab(localId)!! to { local.getTabOutput(localId) },
            remote.tab(remoteId)!! to { remote.getTabOutput(remoteId) })
        for ((tab, read) in pairs) {
            text = "before\n"
            val before = builds
            repeat(5) { assertEquals("before", read()) }
            assertEquals(before + 1, builds)
            text = "after!\n"
            val client = TerminalSession::class.java.getDeclaredField("mClient").apply { isAccessible = true }
                .get(tab.session) as TerminalSessionClient
            client.onTextChanged(tab.session)
            assertEquals("after!", read())
            assertEquals(before + 2, builds)
            val revision = tab.outputRevision
            emulator.mColumns++
            assertTrue(tab.outputRevision > revision)
            read()
            assertEquals(before + 3, builds)
        }
    }

    @Test
    fun monitorKeepsMarkerCodeWhenOutputChangesDuringGraceAndDoesNotKillReusableShell() = runBlocking {
        val emulator = mockk<TerminalEmulator>()
        val screen = mockk<TerminalBuffer>()
        every { emulator.screen } returns screen
        var text = "[command exited: 29]"
        every { screen.transcriptText } answers { text }
        every { anyConstructed<TerminalSession>().emulator } returns emulator
        val finished = local.tab(local.startBackgroundCommand("build", null, true, null))!!
        val reusable = local.tab(local.startBackgroundCommand("server", null, false, null))!!
        dispatcher.scheduler.runCurrent()
        text = "new screen without marker"
        finished.outputState.changed()
        reusable.outputState.changed()
        dispatcher.scheduler.advanceTimeBy(2_500)
        dispatcher.scheduler.runCurrent()
        assertEquals(RunState.Finished(29), finished.runState)
        assertEquals(RunState.Running, reusable.runState)
        assertTrue(reusable.commandFinished)
        verify(exactly = 1) { anyConstructed<TerminalSession>().finishIfRunning() }
    }

    @Test
    fun reconnectDuringRemoteSessionAcquisitionClosesOldOwnerWithoutAllocatingOrPublishing() = runBlocking {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val captured = identity
        val session = mockk<Session>(relaxed = true)
        every { captured.owner.startSession() } answers {
            identity = RemoteConnectionIdentity(mockk(), captured.home)
            session
        }
        val failure = runCatching { remote.startBackgroundCommand("must-not-run", null, true, null) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(remote.listTabs().isEmpty())
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { session.allocateDefaultPTY() }
        verify(exactly = 0) { connection.startShellSession() }
    }

    @Test
    fun failedRemoteShellSetupClosesSessionAndPublishesNoTab() = runBlocking {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val session = mockk<Session>(relaxed = true)
        every { identity.owner.startSession() } returns session
        every { session.allocateDefaultPTY() } throws java.io.IOException("PTY rejected")
        assertTrue(runCatching { remote.startBackgroundCommand("must-not-run", null, true, null) }.exceptionOrNull() is java.io.IOException)
        assertTrue(remote.listTabs().isEmpty())
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { session.startShell() }
        verify(exactly = 0) { connection.startShellSession() }
    }

    private fun finish(manager: Any, tab: TerminalTab, exitCode: Int) {
        manager.javaClass.getDeclaredMethod("finishTab", TerminalTab::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(manager, tab, exitCode)
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getFilesDir(): File = File(".").absoluteFile
    }
}
