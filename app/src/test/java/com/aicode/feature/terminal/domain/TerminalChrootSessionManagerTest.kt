package com.aicode.feature.terminal.domain

import android.content.ContextWrapper
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.termux.terminal.TerminalSession
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalChrootSessionManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = File(".").absoluteFile
    }
    private val engine = mockk<LinuxContainerEngine>()
    private val runtime = mockk<LocalRuntimeCommands>()
    private val factory = mockk<WorkspaceExecutionContextFactory>()
    private val history = mockk<TerminalTaskHistoryRepository>()
    private val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "origin-session")
    private val other = RuntimeOwner(RuntimeOwnerKind.AGENT, "sibling-session")
    private val profile = ContainerProfile.BUILTIN_ALPINE.copy(
        id = "chroot-fixture", shellPath = "/bin/sh", localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = ChrootInstance("a".repeat(24), 1, 2, 1)
    )
    private lateinit var manager: TerminalSessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FileLogger, TaskKeepaliveController, KeepaliveNotificationState)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.d(any(), any()) } just Runs
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.acquireForInput(any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        every { KeepaliveNotificationState.updateTerminals(any(), any()) } just Runs
        mockkConstructor(TerminalSession::class)
        every { anyConstructed<TerminalSession>().updateSize(any(), any()) } just Runs
        every { anyConstructed<TerminalSession>().emulator } returns null
        every { anyConstructed<TerminalSession>().pid } returns -2
        every { anyConstructed<TerminalSession>().isRunning } returns true
        every { anyConstructed<TerminalSession>().finishIfRunning() } just Runs
        every { anyConstructed<TerminalSession>().write(any<ByteArray>(), any(), any()) } just Runs
        manager = TerminalSessionManager(context, engine, mockk(), history, runtime, factory)
    }

    @After
    fun tearDown() {
        (manager.javaClass.getDeclaredField("monitorScope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkConstructor(TerminalSession::class)
        unmockkObject(FileLogger, TaskKeepaliveController, KeepaliveNotificationState)
    }

    @Test
    fun nativeUiReservesTabIdBeforeCaptureAndAuthorizationRetriesKeepTheSameOwner() = runTest(dispatcher) {
        val capturedOwners = mutableListOf<RuntimeOwner>()
        var authorized = false
        coEvery { factory.freeze(any(), any(), any()) } coAnswers {
            val requested = requireNotNull(secondArg<RuntimeOwner?>())
            capturedOwners += requested
            if (!authorized) throw LocalRuntimeAuthorizationRequired()
            execution(requested)
        }
        repeat(2) {
            assertTrue(runCatching { manager.createInteractiveTab() }.exceptionOrNull() is LocalRuntimeAuthorizationRequired)
        }
        assertEquals(listOf(RuntimeOwner(RuntimeOwnerKind.TERMINAL, "term-1"), RuntimeOwner(RuntimeOwnerKind.TERMINAL, "term-1")), capturedOwners)
        coVerify(exactly = 0) { runtime.startPty(any(), any(), any(), any(), any(), any()) }
        authorized = true
        val task = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } returns task
        assertEquals("term-1", manager.createInteractiveTab())
        assertEquals(capturedOwners.first(), manager.tab("term-1")!!.runtimeOwner)
        assertEquals(emptyList<TabInfo>(), manager.listOwnedTabs(owner))
        assertNull(manager.getOwnedTabOutput("term-1", owner))
        assertFalse(manager.sendOwnedInput("term-1", owner, "must-not-write"))
        assertEquals(3, capturedOwners.size)
        coVerify(exactly = 1) { runtime.startPty(match { it.owner == capturedOwners.first() }, listOf("/bin/sh", "-i"), null, emptyMap(), 80, 24) }
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
        coVerify(exactly = 0) { engine.ensureInstalled() }
    }

    @Test
    fun aiOwnerIsFrozenAtCreationAndDifferentOrNativeOwnersCannotReadWriteResizeSignalOrClose() = runTest(dispatcher) {
        val tab = addRuntimeTab("term-1", TestTerminalRuntimeProcess())
        assertEquals(owner, tab.runtimeOwner)
        assertEquals(listOf("term-1"), manager.listOwnedTabs(owner).map { it.id })
        assertTrue(manager.listOwnedTabs(other).isEmpty())
        for (caller in listOf(other, RuntimeOwner(RuntimeOwnerKind.TERMINAL, "term-1"))) {
            assertNull(manager.getOwnedTabOutput(tab.id, caller))
            assertFalse(manager.sendOwnedInput(tab.id, caller, "private"))
            assertFalse(manager.writeOwnedText(tab.id, caller, "private"))
            assertFalse(manager.writeOwnedBytes(tab.id, caller, 3))
            assertFalse(manager.resizeOwnedTab(tab.id, caller, 100, 30))
            assertFalse(manager.signalOwnedTab(tab.id, caller, 2))
            assertNull(manager.closeOwnedTab(tab.id, caller))
        }
        assertEquals("", manager.getOwnedTabOutput(tab.id, owner))
        withContext(requireNotNull(tab.capturedExecution)) {
            assertTrue(manager.sendOwnedInput(tab.id, owner, "owned", false))
        }
        assertTrue(manager.resizeOwnedTab(tab.id, owner, 100, 30))
        assertTrue(manager.signalOwnedTab(tab.id, owner, 2))
        verify(exactly = 1) { tab.session.write(any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { tab.session.updateSize(100, 30) }
        assertEquals(listOf(2), (tab.runtimeTask as TestTerminalRuntimeProcess).signals)
    }

    @Test
    fun chrootBackgroundUsesOnlyTheCapturedOwnerAndRealTaskWithoutScreenMarkersOrProot() = runTest(dispatcher) {
        val execution = execution(owner)
        val task = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } returns task
        val previous = execution.updateThreadContext(execution)
        val id = try { manager.startOwnedBackgroundCommand("printf actual", "Task", true, owner) }
            finally { execution.restoreThreadContext(execution, previous) }
        assertEquals(owner, manager.tab(id)!!.runtimeOwner)
        assertSame(execution, manager.tab(id)!!.capturedExecution)
        coVerify(exactly = 1) { runtime.startPty(any(), listOf("/bin/sh", "-c", "printf actual"), null, emptyMap(), 80, 24) }
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
        coVerify(exactly = 0) { engine.ensureInstalled() }
        coVerify(exactly = 0) { history.create(any()) }
    }

    @Test
    fun screenExitMarkerCannotFinishChrootOrOverrideItsRealTaskExit() = runTest(dispatcher) {
        val task = TestTerminalRuntimeProcess()
        val tab = addRuntimeTab("term-1", task)
        tab.markerExitCode = 0
        manager.javaClass.getDeclaredMethod("monitorBackgroundExit", String::class.java)
            .apply { isAccessible = true }.invoke(manager, tab.id)
        dispatcher.scheduler.runCurrent()
        assertFalse(tab.finish(0))
        assertEquals(RunState.Running, tab.runState)
        task.completeTask(LocalRuntimeTaskResult.Exited(37, null))
        assertTrue(tab.finish(0))
        assertEquals(RunState.Finished(37), tab.runState)
        assertEquals(0, task.stops)
        assertNull(task.cleanupState.value)
        verify(exactly = 0) { tab.session.finishIfRunning() }
    }

    @Test
    fun unknownTaskExitNotifiesOnceWithUnknownMetadataWhileCleanupIsStillPending() = runTest(dispatcher) {
        val task = TestTerminalRuntimeProcess()
        val tab = addRuntimeTab("term-1", task)
        val events = mutableListOf<TabFinishedEvent>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { manager.tabFinishedEvents.collect { events += it } }
        tab.markerExitCode = 0
        task.completeTask(LocalRuntimeTaskResult.Unknown)
        val finish = manager.javaClass.getDeclaredMethod("finishTab", TerminalTab::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        finish.invoke(manager, tab, 0)
        finish.invoke(manager, tab, 137)
        dispatcher.scheduler.runCurrent()
        assertEquals(RunState.Finished(-1, false), tab.runState)
        assertEquals(1, events.size)
        assertFalse(events.single().exitKnown)
        assertEquals("UNKNOWN", manager.listOwnedTabs(owner).single().taskStatus)
        assertEquals("PENDING", manager.listOwnedTabs(owner).single().cleanupStatus)
        assertNull(manager.listOwnedTabs(owner).single().exitCode)
        assertEquals(0, task.stops)
        collector.cancel()
    }

    @Test
    fun closingOneTabWaitsForItsCleanupWithoutRevokingTheSharedOwnerOrStoppingItsSibling() = runTest(dispatcher) {
        val task = TestTerminalRuntimeProcess()
        val sibling = TestTerminalRuntimeProcess()
        val first = addRuntimeTab("term-1", task)
        addRuntimeTab("term-2", sibling)
        val close = async { manager.closeOwnedTab(first.id, owner) }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, task.stops)
        assertEquals(0, sibling.stops)
        assertFalse(close.isCompleted)
        task.completeTask(LocalRuntimeTaskResult.Unknown)
        dispatcher.scheduler.runCurrent()
        assertFalse(close.isCompleted)
        task.cleanupState.value = LocalRuntimeCleanup.UNKNOWN
        assertEquals(TerminalCloseResult(LocalRuntimeCleanup.UNKNOWN), close.await())
        assertEquals(listOf("term-2"), manager.listOwnedTabs(owner).map { it.id })
        verify(exactly = 0) { runtime.revokeOwner(any()) }
    }

    @Test
    fun cancellationOfCloseCannotPretendTaskExitIsCleanupOrRevokeSiblingLease() = runTest(dispatcher) {
        val task = TestTerminalRuntimeProcess()
        val tab = addRuntimeTab("term-1", task)
        val close = launch { manager.closeOwnedTab(tab.id, owner) }
        dispatcher.scheduler.runCurrent()
        close.cancel()
        task.completeTask(LocalRuntimeTaskResult.Unknown)
        dispatcher.scheduler.runCurrent()
        assertFalse(close.isCompleted)
        assertNull(task.cleanupState.value)
        task.cleanupState.value = LocalRuntimeCleanup.VERIFIED
        close.join()
        assertTrue(close.isCancelled)
        assertEquals(1, task.stops)
        verify(exactly = 0) { runtime.revokeOwner(any()) }
    }

    @Test
    fun sourceCancellationAfterLaunchStopsOnlyThatLaunchAndWaitsForItsIndependentCleanup() = runTest(dispatcher) {
        var valid = true
        val execution = execution(owner) { if (!valid) throw CancellationException("source changed") }
        val task = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } coAnswers { valid = false; task }
        val start = async(execution) { manager.startOwnedBackgroundCommand("must-stop", null, true, owner) }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, task.stops)
        assertFalse(start.isCompleted)
        task.completeTask(LocalRuntimeTaskResult.Unknown)
        dispatcher.scheduler.runCurrent()
        assertFalse(start.isCompleted)
        task.cleanupState.value = LocalRuntimeCleanup.VERIFIED
        assertTrue(runCatching { start.await() }.exceptionOrNull() is CancellationException)
        assertTrue(manager.listOwnedTabs(owner).isEmpty())
        verify(exactly = 0) { runtime.revokeOwner(any()) }
    }

    @Test
    fun pluginChrootStartUsesFrozenPluginOwnerRawOutputAndRealTaskInsteadOfProotOrMarkers() = runTest(dispatcher) {
        val pluginOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
        val resource = TerminalResourceOwner("fixture.plugin", "random-resource-not-the-lease-owner")
        val task = TestTerminalRuntimeProcess()
        val output = mutableListOf<ByteArray>()
        var listener: TerminalSession.RawOutputListener? = null
        every { anyConstructed<TerminalSession>().setRawOutputListener(any()) } answers {
            listener = firstArg<TerminalSession.RawOutputListener?>()
        }
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } returns task
        val id = withContext(execution(pluginOwner)) {
            manager.startPluginCommand("printf plugin", "Plugin", resource) { output += it }
        }
        val tab = requireNotNull(manager.tab(id))
        assertEquals(pluginOwner, tab.runtimeOwner)
        assertEquals(resource.pluginId, tab.ownerPluginId)
        assertEquals(resource.resourceId, tab.ownerResourceId)
        assertNull(manager.activeTab)
        val bytes = "raw plugin output".toByteArray()
        requireNotNull(listener).onOutput(tab.session, bytes, bytes.size)
        assertEquals("raw plugin output", output.single().toString(Charsets.UTF_8))
        coVerify(exactly = 1) { runtime.startPty(match { it.owner == pluginOwner }, listOf("/bin/sh", "-c", "printf plugin"), null, emptyMap(), 80, 24) }
        assertTrue(manager.listOwnedTabs(owner).isEmpty())
        assertTrue(manager.listOwnedTabs(pluginOwner).isEmpty())
        assertFalse(manager.sendOwnedInput(id, owner, "must-not-send"))
        assertTrue(manager.writePluginInput(id, resource, "owned input", false))
        assertTrue(manager.interruptPluginTab(id, resource))
        verify(exactly = 2) { tab.session.write(any<ByteArray>(), any(), any()) }
        tab.markerExitCode = 0
        assertFalse(tab.finish(0))
        assertEquals("RUNNING", manager.pluginTabStatus(id, resource)?.taskStatus)
        task.completeTask(LocalRuntimeTaskResult.Exited(23, null))
        assertTrue(tab.finish(0))
        assertEquals(RunState.Finished(23), tab.runState)
        val status = requireNotNull(manager.pluginTabStatus(id, resource))
        assertEquals(23, status.exitCode)
        assertTrue(status.exitKnown)
        assertEquals("EXITED", status.taskStatus)
        assertEquals("PENDING", status.cleanupStatus)
        assertFalse(manager.writePluginInput(id, resource, "after exit", true))
        assertFalse(manager.interruptPluginTab(id, resource))
        assertEquals(0, task.stops)
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
        coVerify(exactly = 0) { engine.ensureInstalled() }
    }

    @Test
    fun pluginAndAiEntryPointsRejectEachOthersFrozenExecutionOwnersBeforeLaunching() = runTest(dispatcher) {
        val resource = TerminalResourceOwner("fixture.plugin", "resource-1")
        val pluginOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
        for (execution in listOf(execution(owner), WorkspaceExecutionContext(
            ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null
        ) {})) {
            assertTrue(runCatching {
                withContext(execution) { manager.startPluginCommand("must-not-run", null, resource) {} }
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            withContext(execution(pluginOwner)) { manager.startOwnedBackgroundCommand("must-not-run", null, false, owner) }
        }.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { runtime.startPty(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engine.ensureInstalled() }
        assertTrue(manager.tabs.value.isEmpty())
    }

    @Test
    fun pluginResourcesSharingAStableOwnerCloseOnlyTheirOwnLaunchAndUnknownIsNotClean() = runTest(dispatcher) {
        val pluginOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
        val firstOwner = TerminalResourceOwner("fixture.plugin", "resource-1")
        val secondOwner = TerminalResourceOwner("fixture.plugin", "resource-2")
        val firstTask = TestTerminalRuntimeProcess()
        val secondTask = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } returnsMany listOf(firstTask, secondTask)
        val execution = execution(pluginOwner)
        val first = withContext(execution) { manager.startPluginCommand("first", null, firstOwner) {} }
        val second = withContext(execution) { manager.startPluginCommand("second", null, secondOwner) {} }
        assertNull(manager.pluginTabStatus(first, secondOwner))
        assertFalse(manager.writePluginInput(first, secondOwner, "private", true))
        assertFalse(manager.interruptPluginTab(first, secondOwner))
        assertFalse(manager.closePluginTab(first, secondOwner))
        val close = async { manager.closePluginTab(first, firstOwner) }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, firstTask.stops)
        assertEquals(0, secondTask.stops)
        firstTask.completeTask(LocalRuntimeTaskResult.Unknown)
        dispatcher.scheduler.runCurrent()
        assertFalse(close.isCompleted)
        val status = requireNotNull(manager.pluginTabStatus(first, firstOwner))
        assertFalse(status.exitKnown)
        assertNull(status.exitCode)
        assertEquals("UNKNOWN", status.taskStatus)
        assertEquals("PENDING", status.cleanupStatus)
        firstTask.cleanupState.value = LocalRuntimeCleanup.UNKNOWN
        assertFalse(close.await())
        assertNull(manager.tab(first))
        assertEquals(second, manager.tabs.value.single().id)
        secondTask.completeTask(LocalRuntimeTaskResult.Exited(0, null))
        secondTask.cleanupState.value = LocalRuntimeCleanup.VERIFIED
        assertTrue(manager.closePluginTab(second, secondOwner))
        verify(exactly = 0) { runtime.revokeOwner(any()) }
        assertEquals(0, secondTask.stops)
    }

    @Test
    fun pluginCleanupWaitIsBoundedAndTimeoutDoesNotClaimVerifiedCleanup() = runTest(dispatcher) {
        val pluginOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
        val resource = TerminalResourceOwner("fixture.plugin", "resource-1")
        val task = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } returns task
        val id = withContext(execution(pluginOwner)) { manager.startPluginCommand("task", null, resource) {} }
        val close = async { manager.closePluginTab(id, resource) }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, task.stops)
        assertFalse(close.isCompleted)
        dispatcher.scheduler.advanceTimeBy(6_000L)
        dispatcher.scheduler.runCurrent()
        assertFalse(close.await())
        assertNull(task.cleanupState.value)
        assertNull(manager.tab(id))
        verify(exactly = 0) { runtime.revokeOwner(any()) }
    }

    @Test
    fun pluginSourceRevocationDuringLaunchStopsItsTaskAndAwaitsCleanupWithoutProotFallback() = runTest(dispatcher) {
        var valid = true
        val pluginOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
        val execution = execution(pluginOwner) { if (!valid) throw CancellationException("plugin source revoked") }
        val resource = TerminalResourceOwner("fixture.plugin", "resource-1")
        val task = TestTerminalRuntimeProcess()
        coEvery { runtime.startPty(any(), any(), any(), any(), any(), any()) } coAnswers { valid = false; task }
        val start = async(execution) { manager.startPluginCommand("must-stop", null, resource) {} }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, task.stops)
        assertFalse(start.isCompleted)
        task.completeTask(LocalRuntimeTaskResult.Unknown)
        dispatcher.scheduler.runCurrent()
        assertFalse(start.isCompleted)
        task.cleanupState.value = LocalRuntimeCleanup.VERIFIED
        assertTrue(runCatching { start.await() }.exceptionOrNull() is CancellationException)
        assertTrue(manager.tabs.value.isEmpty())
        coVerify(exactly = 0) { engine.ensureInstalled() }
        verify(exactly = 0) { engine.buildProotInvocation(any(), any()) }
        verify(exactly = 0) { runtime.revokeOwner(any()) }
    }

    private fun execution(owner: RuntimeOwner, check: () -> Unit = {}): WorkspaceExecutionContext {
        val capture = LocalRuntimeCapture(owner, profile.id, 1, requireNotNull(profile.chrootInstance),
            "/data/user/0/com.ajiu/files/projects/fixture", "/root", "fixture-digest", Any(), check)
        val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 1, 1, Any())
        return WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
            runtimeOwner = owner, localRuntimeCapture = capture, profileSnapshot = snapshot, checkSource = check)
    }

    private fun addRuntimeTab(id: String, task: TestTerminalRuntimeProcess): TerminalTab {
        val backend = ChrootSessionBackend(task)
        val session = mockk<TerminalSession>(relaxed = true)
        every { session.emulator } returns null
        every { session.finishIfRunning() } answers { backend.close() }
        val tab = TerminalTab(id, id, session, true, "task", true, owner.scopeId,
            runState = RunState.Running, runtimeOwner = owner, runtimeTask = task, capturedExecution = execution(owner))
        manager.javaClass.getDeclaredMethod("addTab", TerminalTab::class.java)
            .apply { isAccessible = true }.invoke(manager, tab)
        return tab
    }
}
