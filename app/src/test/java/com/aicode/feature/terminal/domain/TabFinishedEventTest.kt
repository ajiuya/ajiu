package com.aicode.feature.terminal.domain

import android.content.ContextWrapper
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.ProotInvocation
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryFiles
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TabFinishedEventTest {
    @get:Rule val temporary = TemporaryFolder()
    private val historyFiles = HistoryFiles()
    private val history = TerminalTaskHistoryRepository(historyFiles, Dispatchers.Unconfined)
    private lateinit var execution: WorkspaceExecutionContext
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
        val rootfs = temporary.newFolder("rootfs")
        File(rootfs, ".installed").writeText("version")
        val mapping = WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE,
            temporary.newFolder("workspace"), rootfs, temporary.newFolder("aicode"), "/root")
        execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), mapping, null) {}
        local = TerminalSessionManager(context, engine, workspace, history, mockk(), mockk())
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
        assertNull(event.historyId)
        assertNull(event.historyError)
        val info = TabInfo("term-1", "Build", true, false, "build")
        assertNull(info.historyId)
        assertNull(info.historyError)
        assertNull(TabFinishedEvent("term-1", "Build", "build", 0, null).tailOutput)
    }

    @Test
    fun localStartFreezesSourceAndFinishesOnlyOnceAfterModeSwitch() = runBlocking(execution) {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            local.tabFinishedEvents.collect { received += it }
        }
        try {
            val id = local.startBackgroundCommand("build", "Build", true, "session-1")
            val tab = local.tab(id)!!
            assertEquals("LOCAL_PROOT", tab.sourceMode)
            assertSame(execution, tab.capturedExecution)
            assertNull(tab.channelGeneration)
            holder.setMode(ExecutionMode.REMOTE_SSH)
            finish(local, tab, 17)
            finish(local, tab, 0)
            yield()
            assertEquals(listOf(TabFinishedEvent(id, "Build", "build", 17, "session-1", "",
                sourceMode = "LOCAL_PROOT", channelGeneration = null, historyId = tab.historyId)), received)
            verify(exactly = 0) { connection.captureIdentity() }
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun remoteAiCreationRetainsTheExactExecutionAndRejectsAnotherOwnersCapture() = runBlocking {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "source-session")
        val captured = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "captured-remote", mockk(), null, null,
            runtimeOwner = owner, checkSource = {})
        val id = withContext(captured) { remote.startOwnedBackgroundCommand("remote", null, false, owner) }
        val tab = requireNotNull(remote.tab(id))
        assertSame(captured, tab.capturedExecution)
        assertEquals(owner, tab.runtimeOwner)
        assertTrue(withContext(captured) {
            runCatching {
                remote.startOwnedBackgroundCommand("must-not-start", null, false, RuntimeOwner(RuntimeOwnerKind.AGENT, "foreign-session"))
            }.exceptionOrNull() is IllegalArgumentException
        })
        assertEquals(listOf(id), remote.tabs.value.map { it.id })
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
    fun markerResultIsFrozenBeforeBackendStopsAndLateCallbacksDoNotNotifyAgain() = runBlocking(execution) {
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
    fun protectedTasksPersistBeforeSessionConstructionAndUseNonRecycledIds() = runBlocking(execution) {
        every { engine.buildProotInvocation(any(), any()) } answers {
            assertEquals(TerminalTaskStatus.RUNNING, historyFiles.records.values.last().status)
            ProotInvocation(listOf("/fixture/proot"), emptyMap())
        }
        val first = local.startBackgroundCommand("build", "Build", true, "session-1")
        val second = local.startBackgroundCommand("build", "Build", true, "session-1")
        assertNotEquals(first, second)
        for (id in listOf(first, second)) {
            val tab = local.tab(id)!!
            requireTerminalHistoryId(tab.historyId!!)
            assertEquals("term-${tab.historyId}", id)
            assertEquals(tab.historyId, local.listTabs().single { it.id == id }.historyId)
        }
        every { engine.buildProotInvocation(any(), any()) } returns ProotInvocation(listOf("/fixture/proot"), emptyMap())
        assertEquals("term-1", local.startBackgroundCommand("server", null, false, "session-1"))
        assertEquals("term-2", local.startBackgroundCommand("anonymous", null, true, null))
        assertEquals(2, historyFiles.records.size)
    }

    @Test
    fun failedInitialHistoryWritePreventsExecutingTheCommand() = runBlocking(execution) {
        historyFiles.failWrites = true
        assertTrue(runCatching { local.startBackgroundCommand("must-not-run", null, true, "session-1") }
            .exceptionOrNull() is java.io.IOException)
        assertTrue(local.listTabs().isEmpty())
        assertEquals(TERMINAL_HISTORY_IO_ERROR, history.state.value.error)
        assertTrue(historyFiles.records.isEmpty())
        assertTrue(history.state.value.records.isEmpty())
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
    }

    @Test
    fun cancellationDuringHistoryCreateMarksSavedRecordUnknownWithoutExecutingOrNotifying() = runBlocking {
        val repository = TerminalTaskHistoryRepository(historyFiles, Dispatchers.IO)
        local = TerminalSessionManager(context, engine, workspace, repository, mockk(), mockk())
        val persisted = CompletableDeferred<TerminalTaskRecord>()
        val releaseWrite = CountDownLatch(1)
        historyFiles.afterWrite = { record ->
            if (record.status == TerminalTaskStatus.RUNNING) {
                persisted.complete(record)
                check(releaseWrite.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release history write" }
            }
        }
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { local.tabFinishedEvents.collect { received += it } }
        val caller = launch(Dispatchers.Default + execution) {
            local.startBackgroundCommand("must-not-run", null, true, "session-1")
        }
        try {
            val saved = withTimeout(10_000L) { persisted.await() }
            assertEquals(TerminalTaskStatus.RUNNING, historyFiles.records.getValue(saved.historyId).status)
            caller.cancel()
            releaseWrite.countDown()
            withTimeout(10_000L) { caller.join() }
            yield()
            assertTrue(caller.isCancelled)
            assertTrue(local.listTabs().isEmpty())
            verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
            assertTrue(received.isEmpty())
            assertEquals(TerminalTaskStatus.UNKNOWN, repository.state.value.records.single().status)
            assertEquals(TerminalTaskStatus.UNKNOWN, historyFiles.records.getValue(saved.historyId).status)
            assertNull(historyFiles.records.getValue(saved.historyId).exitCode)
        } finally {
            releaseWrite.countDown()
            caller.cancelAndJoin()
            collector.cancelAndJoin()
            historyFiles.afterWrite = null
        }
    }

    @Test
    fun protectedStartWithoutCapturedScopeDoesNotExecuteOrCreateARecord() = runBlocking {
        assertTrue(runCatching { local.startBackgroundCommand("must-not-run", null, true, "session-1") }.isFailure)
        assertTrue(local.listTabs().isEmpty())
        assertTrue(historyFiles.records.isEmpty())
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
    }

    @Test
    fun finalHistoryIoFailureStillNotifiesExactlyOnceWithObservedExitAndVisibleError() = runBlocking(execution) {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { local.tabFinishedEvents.collect { received += it } }
        try {
            val tab = local.tab(local.startBackgroundCommand("build", null, true, "session-1"))!!
            historyFiles.failWrites = true
            finish(local, tab, 37)
            finish(local, tab, 0)
            dispatcher.scheduler.runCurrent()
            yield()
            assertEquals(listOf(37), received.map { it.exitCode })
            assertEquals(TERMINAL_HISTORY_IO_ERROR, received.single().historyError)
            assertEquals(tab.historyId, received.single().historyId)
            assertEquals(TERMINAL_HISTORY_IO_ERROR, local.listTabs().single().historyError)
            assertTrue(tab.finishedNotified)
            assertEquals(TerminalTaskStatus.FINISHED, history.state.value.records.single().status)
            assertEquals(37, history.state.value.records.single().exitCode)
            historyFiles.failWrites = false
            val reopened = TerminalTaskHistoryRepository(historyFiles, Dispatchers.Unconfined).load()
            assertEquals(TerminalTaskStatus.UNKNOWN, reopened.records.single().status)
            assertNull(reopened.records.single().exitCode)
            assertEquals(1, received.size)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun closingARunningProtectedTaskRecordsUnknownAndDoesNotNotifyForcedKillSuccess() = runBlocking(execution) {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { local.tabFinishedEvents.collect { received += it } }
        try {
            val tab = local.tab(local.startBackgroundCommand("build", null, true, "session-1"))!!
            every { tab.session.finishIfRunning() } answers { finish(local, tab, 0) }
            assertTrue(local.closeTab(tab.id))
            dispatcher.scheduler.runCurrent()
            yield()
            assertTrue(received.isEmpty())
            assertTrue(local.listTabs().isEmpty())
            assertFalse(tab.finishedNotified)
            assertEquals(TerminalTaskStatus.UNKNOWN, history.state.value.records.single().status)
            assertNull(history.state.value.records.single().exitCode)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun closingWithAnObservedCommandExitPreservesAndNotifiesThatRealResult() = runBlocking(execution) {
        val received = mutableListOf<TabFinishedEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { local.tabFinishedEvents.collect { received += it } }
        try {
            val tab = local.tab(local.startBackgroundCommand("build", null, true, "session-1"))!!
            tab.markerExitCode = 41
            assertTrue(local.closeTab(tab.id))
            dispatcher.scheduler.runCurrent()
            yield()
            assertEquals(listOf(41), received.map { it.exitCode })
            assertEquals(41, history.state.value.records.single().exitCode)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun historyCoalescesRevisionBurstsAndDoesNotRewriteSilentOutput() = runBlocking(execution) {
        val tab = local.tab(local.startBackgroundCommand("build", null, true, "session-1"))!!
        dispatcher.scheduler.runCurrent()
        repeat(100) { tab.outputState.changed() }
        assertEquals(1, historyFiles.writes)
        dispatcher.scheduler.advanceTimeBy(1_499)
        assertEquals(1, historyFiles.writes)
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, historyFiles.writes)
        dispatcher.scheduler.advanceTimeBy(3_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, historyFiles.writes)
        finish(local, tab, 0)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, historyFiles.writes)
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

    private class HistoryFiles : TerminalTaskHistoryFiles {
        val records = linkedMapOf<String, TerminalTaskRecord>()
        var failWrites = false
        var writes = 0
        var afterWrite: ((TerminalTaskRecord) -> Unit)? = null
        override fun ids(): List<String> = records.keys.toList()
        override fun read(historyId: String): TerminalTaskRecord = records.getValue(historyId)
        override fun write(record: TerminalTaskRecord) {
            if (failWrites) throw java.io.IOException("disk full")
            writes++
            records[record.historyId] = record
            afterWrite?.invoke(record)
        }
        override fun delete(historyId: String) { records.remove(historyId) }
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getFilesDir(): File = File(".").absoluteFile
    }
}
