package com.aicode.core.watch

import android.app.Application
import android.os.FileObserver
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE,
    shadows = [FileChangeHubLifecycleTest.RecordingFileObserver::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FileChangeHubLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = mockk<WorkspaceRepository>()
    private val installer = mockk<ContainerInstaller>()
    private val mapper = mockk<WorkspacePathMapper>()
    private var scans = 0
    private var snapshotEntries: Map<String, String> = emptyMap()
    private var snapshotComplete = true

    @Before fun setUp() = RecordingFileObserver.reset()
    @After fun tearDown() = RecordingFileObserver.reset()

    private fun TestScope.hub(
        mainDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        ioDispatcher: CoroutineDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        realSnapshot: Boolean = false,
        executionFactory: WorkspaceExecutionContextFactory? = null,
        profileChanges: Flow<Long> = flowOf(0L),
        modeHolder: ExecutionModeHolder = ExecutionModeHolder(),
        remoteHub: RemoteFileWatchHub = RemoteFileWatchHub(),
        pathMapper: WorkspacePathMapper = mapper
    ) = FileChangeHub(workspace, installer, pathMapper, modeHolder, ioDispatcher, mainDispatcher,
        observerFactory = { path, mask, accept ->
            @Suppress("DEPRECATION")
            object : FileObserver(path, mask) {
                override fun onEvent(event: Int, path: String?) = accept(this, event, path)
            }
        },
        snapshot = { key, limit ->
            scans++
            assertEquals(4096, limit)
            if (realSnapshot) snapshotOfScope(key, limit) else ScopeSnapshot(snapshotEntries, snapshotComplete)
        }, remoteFileWatchHub = remoteHub, executionFactory = executionFactory, profileChanges = profileChanges)

    private fun FileChangeHub.watch(root: File, recursive: Boolean = false, poll: Boolean = false,
        filter: WatchFilter = LOCAL_FILTER): Flow<FileChangeBatch> = watchHostDir(root,
        containerRootPath = "/container", recursive = recursive, filter = filter,
        fallbackPoll = poll, batchWindowMs = 0)

    private fun TestScope.collect(flow: Flow<FileChangeBatch>, received: MutableList<FileChangeBatch> = mutableListOf()): Job =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.toList(received) }

    @Test
    fun watchWorkspaceColdChrootWithoutInstanceOrAuthorizationDoesNotMapHostOrFreezeExecution() = runTest {
        val root = temporary.newFolder("workspace")
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "uncreated-chroot", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        every { workspace.current } returns MutableStateFlow<Workspace?>(Workspace("workspace", root.path))
        every { workspace.currentPath() } returns root.path
        every { mapper.isChrootRuntime() } returns profile.isChroot
        every { mapper.runtimeProfileState } returns MutableStateFlow(WorkspacePathMapper.RuntimeProfileState(profile, true, 1L))
        every { mapper.toHostFile(any()) } throws LocalRuntimeAuthorizationRequired()
        val hub = hub(executionFactory = executionFactory)
        val errors = mutableListOf<LocalRuntimeAuthorizationRequired>()
        val received = mutableListOf<FileChangeBatch>()
        val beforeScans = scans
        val beforeStarts = RecordingFileObserver.started.size
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0).toList(received)
            } catch (error: LocalRuntimeAuthorizationRequired) {
                errors += error
            }
        }
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(beforeScans, scans)
        assertEquals(beforeStarts, RecordingFileObserver.started.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertTrue(received.isEmpty())
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        assertTrue("CHROOT workspace collection leaked authorization errors: $errors", errors.isEmpty())
        assertTrue(collector.isActive)
        verify(exactly = 0) { mapper.toHostFile(any()) }
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceProotToChrootStopsObserverAndPollingWithoutEndingCollector() = runTest {
        val root = temporary.newFolder("workspace")
        var profile = ContainerProfile.BUILTIN_ALPINE
        val changes = MutableStateFlow(0L)
        val state = MutableStateFlow(WorkspacePathMapper.RuntimeProfileState(profile, true, 1L))
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        every { workspace.current } returns MutableStateFlow<Workspace?>(Workspace("workspace", root.path))
        every { workspace.currentPath() } returns root.path
        every { mapper.isChrootRuntime() } answers { profile.isChroot }
        every { mapper.runtimeProfileState } returns state
        every { mapper.toHostFile(any()) } answers {
            if (profile.isChroot) throw LocalRuntimeAuthorizationRequired()
            root
        }
        val hub = hub(executionFactory = executionFactory, profileChanges = changes)
        val errors = mutableListOf<LocalRuntimeAuthorizationRequired>()
        val received = mutableListOf<FileChangeBatch>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0).toList(received)
            } catch (error: LocalRuntimeAuthorizationRequired) {
                errors += error
            }
        }
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        assertEquals(1, scans)
        assertEquals(1, RecordingFileObserver.started.size)
        val beforeScans = scans
        val beforeStarts = RecordingFileObserver.started.size
        profile = profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        state.value = WorkspacePathMapper.RuntimeProfileState(profile, true, state.value.epoch + 1)
        changes.value++
        runCurrent()
        old.onEvent(FileObserver.CLOSE_WRITE, "late.txt")
        snapshotEntries = mapOf("late-poll.txt" to "f:1:1")
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(beforeScans, scans)
        assertEquals(beforeStarts, RecordingFileObserver.started.size)
        assertEquals(listOf(old), RecordingFileObserver.stopped.map { it.second })
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertTrue(received.isEmpty())
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        assertTrue("CHROOT switch leaked authorization errors: $errors", errors.isEmpty())
        assertTrue(collector.isActive)
        verify(exactly = 1) { mapper.toHostFile(any()) }
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceWaitsForReadyAndRebuildsFromActualCachePublicationAfterSelectionChanges() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root, ready = false)
        val changes = MutableStateFlow(0L)
        val hub = hub(profileChanges = changes)
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0), received)
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        verify(exactly = 0) { mapper.toHostFile(any()) }
        val chroot = ContainerProfile.BUILTIN_ALPINE.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        changes.value++
        runCurrent()
        state.publish(chroot)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        verify(exactly = 0) { mapper.toHostFile(any()) }
        changes.value++
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
        state.publish(ContainerProfile.BUILTIN_ALPINE)
        runCurrent()
        assertEquals(1, scans)
        assertEquals(1, RecordingFileObserver.started.size)
        val old = RecordingFileObserver.active.getValue(root.path)
        state.publish(chroot)
        runCurrent()
        val stoppedScans = scans
        old.onEvent(FileObserver.CLOSE_WRITE, "stale.txt")
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(stoppedScans, scans)
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertTrue(received.isEmpty())
        changes.value++
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
        state.publish(ContainerProfile.BUILTIN_ALPINE)
        runCurrent()
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        current.onEvent(FileObserver.CLOSE_WRITE, "restored.txt")
        runCurrent()
        assertEquals(listOf("~/workspace/restored.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertEquals(stoppedScans + 1, scans)
        assertTrue(collector.isActive)
        verify(exactly = 2) { mapper.toHostFile(any()) }
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun watchWorkspaceSameIdChrootAbaEpochDropsEventsBeforeQueuedRebuildAndRestartsPolling() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val hub = hub()
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 300), received)
        runCurrent()
        val firstState = state.value
        val old = RecordingFileObserver.active.getValue(root.path)
        old.onEvent(FileObserver.CLOSE_WRITE, "queued-before-switch.txt")
        runCurrent()
        state.publish(firstState.profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
        old.onEvent(FileObserver.CLOSE_WRITE, "after-cache-switch.txt")
        old.onEvent(IN_IGNORED, null)
        state.publish(firstState.profile)
        assertEquals(firstState.profile, state.value.profile)
        assertTrue(state.value.epoch > firstState.epoch)
        runCurrent()
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        assertEquals(listOf(old), RecordingFileObserver.stopped.map { it.second })
        old.onEvent(FileObserver.CLOSE_WRITE, "late-after-aba.txt")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(received.isEmpty())
        current.onEvent(FileObserver.CLOSE_WRITE, "current.txt")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("~/workspace/current.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertEquals(2, scans)
        snapshotEntries = mapOf("fresh-poll.txt" to "f:1:1")
        advanceTimeBy(2000)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(3, scans)
        assertEquals(listOf("~/workspace/current.txt", "~/workspace/fresh-poll.txt"),
            received.flatMap { it.changes }.map { it.containerPath })
        assertTrue(collector.isActive)
        verify(exactly = 2) { mapper.toHostFile(any()) }
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceSameIdProfileContentsRebuildWithoutSelectionSignal() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val hub = hub()
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0))
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        val initial = state.value
        state.publish(initial.profile.copy(env = mapOf("EDITOR" to "vim")))
        runCurrent()
        assertEquals(initial.profile.id, state.value.profile.id)
        assertTrue(state.value.epoch > initial.epoch)
        assertNotSame(old, RecordingFileObserver.active.getValue(root.path))
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(listOf(old), RecordingFileObserver.stopped.map { it.second })
        assertEquals(2, scans)
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceTypedAuthorizationRaceKeepsOuterFlowAliveForProotRecovery() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val proot = state.value.profile
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        var race = true
        every { mapper.toHostFile(any()) } answers {
            if (race) {
                race = false
                state.publish(proot.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
                throw LocalRuntimeAuthorizationRequired()
            }
            root
        }
        val hub = hub(executionFactory = executionFactory)
        val errors = mutableListOf<LocalRuntimeAuthorizationRequired>()
        val received = mutableListOf<FileChangeBatch>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0).toList(received)
            } catch (error: LocalRuntimeAuthorizationRequired) {
                errors += error
            }
        }
        runCurrent()
        assertTrue(errors.isEmpty())
        assertTrue(collector.isActive)
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        state.publish(proot)
        runCurrent()
        RecordingFileObserver.active.getValue(root.path).onEvent(FileObserver.CLOSE_WRITE, "recovered.txt")
        runCurrent()
        assertEquals(listOf("~/workspace/recovered.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertEquals(1, scans)
        verify(exactly = 2) { mapper.toHostFile(any()) }
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceCacheChangeAfterHostMappingRejectsStaleAttachment() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val proot = state.value.profile
        var race = true
        every { mapper.toHostFile(any()) } answers {
            if (race) {
                race = false
                state.publish(proot.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
            }
            root
        }
        val hub = hub()
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0))
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        assertTrue(collector.isActive)
        state.publish(proot)
        runCurrent()
        assertEquals(1, scans)
        assertEquals(1, RecordingFileObserver.started.size)
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceCacheChangeBeforeQueuedIoStartDoesNotScanOrStartObserver() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val ioDispatcher = QueuedObserverDispatcher()
        val hub = hub(ioDispatcher = ioDispatcher)
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0))
        runCurrent()
        assertEquals(1, ioDispatcher.size)
        state.publish(state.value.profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
        ioDispatcher.runAll()
        runCurrent()
        ioDispatcher.runAll()
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceBackpressuredOldBatchCannotCrossSameIdAbaEpoch() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val proot = state.value.profile
        val hub = hub()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<FileChangeBatch>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0).collect { batch ->
                received += batch
                if (received.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        old.onEvent(FileObserver.CLOSE_WRITE, "first.txt")
        entered.await()
        old.onEvent(FileObserver.CLOSE_WRITE, "queued-old.txt")
        runCurrent()
        state.publish(proot.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
        state.publish(proot)
        release.complete(Unit)
        runCurrent()
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        current.onEvent(FileObserver.CLOSE_WRITE, "current.txt")
        runCurrent()
        assertEquals(listOf("~/workspace/first.txt", "~/workspace/current.txt"),
            received.flatMap { it.changes }.map { it.containerPath })
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun watchAllKeepsPrivateAicodeObserverAndPollingWhenGuestWorkspaceBecomesChroot() = runTest {
        val root = temporary.newFolder("workspace")
        val global = temporary.newFolder("aicode")
        val state = workspaceState(root)
        every { installer.aicodeDir } returns global
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        val hub = hub(executionFactory = executionFactory)
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchAll(filter = LOCAL_FILTER, batchWindowMs = 0), received)
        runCurrent()
        val configObserver = RecordingFileObserver.active.getValue(global.path)
        assertEquals(2, scans)
        state.publish(state.value.profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
        runCurrent()
        assertEquals(setOf(global.path), RecordingFileObserver.active.keys)
        assertSame(configObserver, RecordingFileObserver.active.getValue(global.path))
        val beforeScans = scans
        configObserver.onEvent(FileObserver.CLOSE_WRITE, "mcp.json")
        runCurrent()
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(beforeScans + 2, scans)
        assertEquals(listOf("/root/.aicode/mcp.json"), received.flatMap { it.changes }.map { it.containerPath })
        assertEquals(listOf(ChangeRoot.AICODE), received.flatMap { it.changes }.map { it.root })
        assertTrue(collector.isActive)
        verify(exactly = 1) { mapper.toHostFile(any()) }
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun explicitHostWatchWithContainerRootIsIndependentOfChrootGuestReadiness() = runTest {
        val root = temporary.newFolder("workspace")
        val host = temporary.newFolder("authorized-host")
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        val state = workspaceState(root, profile)
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        val hub = hub(executionFactory = executionFactory)
        val guest = collect(hub.watchWorkspace(filter = LOCAL_FILTER, batchWindowMs = 0))
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchHostDir(host, containerRootPath = "/explicit-host", filter = LOCAL_FILTER,
            fallbackPoll = true, batchWindowMs = 0), received)
        runCurrent()
        val observer = RecordingFileObserver.active.getValue(host.path)
        assertEquals(listOf(host.path), RecordingFileObserver.started.map { it.first })
        assertEquals(1, scans)
        state.publish(profile, ready = false)
        runCurrent()
        state.publish(profile)
        runCurrent()
        assertSame(observer, RecordingFileObserver.active.getValue(host.path))
        observer.onEvent(FileObserver.CLOSE_WRITE, "kept.txt")
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2, scans)
        assertEquals(listOf("/explicit-host/kept.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertEquals(listOf(ChangeRoot.OTHER), received.flatMap { it.changes }.map { it.root })
        verify(exactly = 0) { mapper.toHostFile(any()) }
        verify(exactly = 0) { mapper.toContainerPath(any()) }
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        assertTrue(guest.isActive)
        assertTrue(collector.isActive)
        guest.cancelAndJoin()
        collector.cancelAndJoin()
    }

    @Test
    fun watchWorkspaceSshUsesFrozenRemoteSamplesWithoutWaitingForLocalProfileOrHostMapping() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root, ready = false)
        val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
        val access = mockk<FileAccessProvider>()
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "frozen-ssh", access, null, null) {}
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        val remoteHub = mockk<RemoteFileWatchHub>()
        val samples = listOf(FileChangeBatch(listOf(FileChange(ChangeRoot.OTHER, "/ssh/workspace/remote.txt",
            "/ssh/workspace/remote.txt", ChangeKind.MODIFIED))), FileChangeBatch(emptyList(), truncated = true))
        coEvery { executionFactory.freeze(any(), any(), any()) } returns execution
        every { access.resolvePath(WorkspacePathMapper.CONTAINER_ROOT, true) } answers {
            assertSame(execution, WorkspaceExecutionContext.current())
            "/ssh/workspace"
        }
        every { remoteHub.watch(execution, "/ssh/workspace", true, LOCAL_FILTER) } returns flow {
            samples.forEach { emit(it) }
            awaitCancellation()
        }
        val hub = hub(executionFactory = executionFactory, modeHolder = mode, remoteHub = remoteHub)
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchWorkspace(recursive = true, filter = LOCAL_FILTER, batchWindowMs = 0), received)
        runCurrent()
        assertFalse(state.value.ready)
        assertEquals(samples, received)
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        verify(exactly = 0) { mapper.toHostFile(any()) }
        coVerify(exactly = 1) { executionFactory.freeze(any(), any(), any()) }
        verify(exactly = 1) { remoteHub.watch(execution, "/ssh/workspace", true, LOCAL_FILTER) }
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun staleQueuedWorkspaceStartDoesNotBlockNewSharedHostSubscriberBeforeOldSubscribersLeave() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = collect(hub.watchWorkspace(filter = LOCAL_FILTER, fallbackPoll = false, batchWindowMs = 0))
        val second = collect(hub.watchWorkspace(filter = LOCAL_FILTER, fallbackPoll = false, batchWindowMs = 0))
        runCurrent()
        assertEquals(1, observerDispatcher.size)
        state.publish(state.value.profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
        observerDispatcher.runNext()
        assertEquals(0, observerDispatcher.size)
        assertTrue(RecordingFileObserver.started.isEmpty())
        val received = mutableListOf<FileChangeBatch>()
        val host = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.watchHostDir(root, containerRootPath = "/explicit-host", filter = LOCAL_FILTER,
                fallbackPoll = false, batchWindowMs = 0).toList(received)
        }
        assertEquals(1, observerDispatcher.size)
        runCurrent()
        observerDispatcher.runAll()
        runCurrent()
        assertEquals(1, RecordingFileObserver.started.size)
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        val observer = RecordingFileObserver.active.getValue(root.path)
        observer.onEvent(FileObserver.CLOSE_WRITE, "shared-host.txt")
        runCurrent()
        assertEquals(listOf("/explicit-host/shared-host.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertTrue(first.isActive)
        assertTrue(second.isActive)
        first.cancelAndJoin()
        second.cancelAndJoin()
        host.cancelAndJoin()
        observerDispatcher.runAll()
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertEquals(0, observerDispatcher.size)
    }

    @Test
    fun exitingWorkspaceStartCannotBeReusedBeforeChildCompletionOrClearTheReplacementStart() = runTest {
        val root = temporary.newFolder("workspace")
        val state = workspaceState(root)
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = collect(hub.watchWorkspace(filter = LOCAL_FILTER, fallbackPoll = false, batchWindowMs = 0))
        val second = collect(hub.watchWorkspace(filter = LOCAL_FILTER, fallbackPoll = false, batchWindowMs = 0))
        runCurrent()
        assertEquals(1, observerDispatcher.size)
        val observerScope = FileChangeHub::class.java.getDeclaredField("mainScope").apply { isAccessible = true }.get(hub) as CoroutineScope
        val oldStart = observerScope.coroutineContext[Job]!!.children.single()
        val completionBarrier = Job(oldStart)
        val firstReceived = mutableListOf<FileChangeBatch>()
        val secondReceived = mutableListOf<FileChangeBatch>()
        var firstHost: Job? = null
        var secondHost: Job? = null
        try {
            state.publish(state.value.profile.copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null))
            observerDispatcher.runNext()
            assertFalse(oldStart.isCompleted)
            assertTrue(completionBarrier.isActive)
            assertTrue(RecordingFileObserver.started.isEmpty())
            firstHost = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                hub.watchHostDir(root, containerRootPath = "/first-host", filter = LOCAL_FILTER,
                    fallbackPoll = false, batchWindowMs = 0).toList(firstReceived)
            }
            assertEquals(1, observerDispatcher.size)
            completionBarrier.complete()
            assertTrue(oldStart.isCompleted)
            secondHost = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                hub.watchHostDir(root, containerRootPath = "/second-host", filter = LOCAL_FILTER,
                    fallbackPoll = false, batchWindowMs = 0).toList(secondReceived)
            }
            assertEquals(1, observerDispatcher.size)
            runCurrent()
            observerDispatcher.runAll()
            runCurrent()
            assertEquals(1, RecordingFileObserver.started.size)
            assertEquals(0, scans)
            assertTrue(RecordingFileObserver.stopped.isEmpty())
            RecordingFileObserver.active.getValue(root.path).onEvent(FileObserver.CLOSE_WRITE, "tail-window.txt")
            runCurrent()
            assertEquals(listOf("/first-host/tail-window.txt"), firstReceived.flatMap { it.changes }.map { it.containerPath })
            assertEquals(listOf("/second-host/tail-window.txt"), secondReceived.flatMap { it.changes }.map { it.containerPath })
            assertTrue(first.isActive)
            assertTrue(second.isActive)
        } finally {
            completionBarrier.cancel()
            first.cancelAndJoin()
            second.cancelAndJoin()
            firstHost?.cancelAndJoin()
            secondHost?.cancelAndJoin()
            observerDispatcher.runAll()
        }
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertEquals(0, observerDispatcher.size)
    }

    @Test
    fun watchWorkspaceUsesActualMapperPublicationsForColdChrootAndDelayedProotRecovery() = runTest {
        val root = temporary.newFolder("workspace")
        val rootfs = temporary.newFolder("rootfs")
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val executionFactory = mockk<WorkspaceExecutionContextFactory>()
        val chroot = ContainerProfile.BUILTIN_ALPINE.copy(id = "cold-chroot", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        val profiles = MutableStateFlow(listOf(chroot))
        val selectionChanges = MutableStateFlow(0L)
        val release = CompletableDeferred<Unit>()
        every { workspace.current } returns MutableStateFlow<Workspace?>(Workspace("workspace", root.path))
        every { workspace.currentPath() } returns root.path
        every { settings.activeProfileIdFlow } returns flow { release.await(); emit(chroot.id) }
        every { settings.customProfilesFlow } returns profiles
        every { installer.rootfsDirFor(any()) } returns rootfs
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            firstArg<String>().let { if (it == "~") "/root" else if (it.startsWith("~/")) "/root" + it.removePrefix("~") else it }
        }
        val actualMapper = WorkspacePathMapper(workspace, installer, settings, home)
        suspend fun awaitProfile(profile: ContainerProfile) = withContext(Dispatchers.IO) {
            withTimeout(5_000) { actualMapper.runtimeProfileState.first { it.ready && it.profile == profile } }
        }
        val hub = hub(executionFactory = executionFactory, profileChanges = selectionChanges, pathMapper = actualMapper)
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watchWorkspace(filter = LOCAL_FILTER, fallbackPoll = false, batchWindowMs = 0), received)
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertFalse(actualMapper.runtimeProfileState.value.ready)
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        release.complete(Unit)
        val cold = awaitProfile(chroot)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { actualMapper.toHostFile("~/workspace") }
        verify(exactly = 0) { installer.rootfsDirFor(any()) }
        val proot = chroot.copy(localRuntime = LocalContainerRuntime.PROOT)
        selectionChanges.value++
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
        profiles.value = listOf(proot)
        val firstProot = awaitProfile(proot)
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        assertTrue(firstProot.epoch > cold.epoch)
        assertEquals(1, RecordingFileObserver.started.size)
        assertEquals(0, scans)
        profiles.value = listOf(chroot)
        val stopped = awaitProfile(chroot)
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
        old.onEvent(FileObserver.CLOSE_WRITE, "stale.txt")
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(received.isEmpty())
        profiles.value = listOf(proot)
        val restored = awaitProfile(proot)
        runCurrent()
        assertTrue(restored.epoch > stopped.epoch)
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        current.onEvent(FileObserver.CLOSE_WRITE, "actual-cache-restored.txt")
        runCurrent()
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(0, scans)
        assertEquals(listOf("~/workspace/actual-cache-restored.txt"), received.flatMap { it.changes }.map { it.containerPath })
        assertTrue(collector.isActive)
        coVerify(exactly = 0) { executionFactory.freeze(any(), any(), any()) }
        collector.cancelAndJoin()
    }

    private fun workspaceState(root: File, profile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE,
        ready: Boolean = true): MutableStateFlow<WorkspacePathMapper.RuntimeProfileState> {
        val state = MutableStateFlow(WorkspacePathMapper.RuntimeProfileState(profile, ready, if (ready) 1L else 0L))
        every { workspace.current } returns MutableStateFlow<Workspace?>(Workspace("workspace", root.path))
        every { workspace.currentPath() } returns root.path
        every { mapper.runtimeProfileState } returns state
        every { mapper.isChrootRuntime() } answers { state.value.profile.isChroot }
        every { mapper.toHostFile(any()) } answers {
            if (state.value.profile.isChroot) throw LocalRuntimeAuthorizationRequired()
            root
        }
        return state
    }

    private fun MutableStateFlow<WorkspacePathMapper.RuntimeProfileState>.publish(profile: ContainerProfile, ready: Boolean = true) {
        value = WorkspacePathMapper.RuntimeProfileState(profile, ready, value.epoch + 1)
    }

    @Test
    fun uncollectedFlowDoesNoFilesystemOrObserverWork() = runTest {
        val root = spyk(temporary.newFolder("root"))
        hub().watch(root, recursive = true, poll = true)
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        verify(exactly = 0) { root.isDirectory }
        verify(exactly = 0) { root.listFiles() }
    }

    @Test
    fun cancellationBeforeQueuedIoStartDoesNotReadOrRegister() = runTest {
        val ioDispatcher = QueuedObserverDispatcher()
        val root = spyk(temporary.newFolder("root"))
        val hub = hub(ioDispatcher = ioDispatcher)
        val collector = collect(hub.watch(root, recursive = true, poll = true))
        collector.cancel()
        ioDispatcher.runAll()
        runCurrent()
        collector.join()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, scans)
        verify(exactly = 0) { root.isDirectory }
        verify(exactly = 0) { root.listFiles() }
        assertTrue(RecordingFileObserver.started.isEmpty())
    }

    @Test
    fun cancellationBeforeQueuedObserverStartReapsWatchAndSharedPollLease() = runTest {
        val observerDispatcher = QueuedObserverDispatcher()
        val ioDispatcher = QueuedObserverDispatcher()
        val root = temporary.newFolder("root")
        val hub = hub(mainDispatcher = observerDispatcher, ioDispatcher = ioDispatcher)
        val collector = collect(hub.watch(root, poll = true))
        ioDispatcher.runNext()
        assertEquals(1, observerDispatcher.size)
        assertEquals(2, ioDispatcher.size)
        assertEquals(0, scans)
        collector.cancel()
        runCurrent()
        ioDispatcher.runAll()
        runCurrent()
        collector.join()
        observerDispatcher.runAll()
        advanceTimeBy(6000)
        runCurrent()
        ioDispatcher.runAll()
        observerDispatcher.runAll()
        assertEquals(0, ioDispatcher.size)
        assertEquals(0, observerDispatcher.size)
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun cancellationWhileRootMissingNeverAttachesAfterItAppears() = runTest {
        val root = File(temporary.root, "missing")
        val hub = hub()
        val collector = collect(hub.watch(root, recursive = true, poll = true))
        runCurrent()
        collector.cancelAndJoin()
        assertTrue(root.mkdir())
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
    }

    @Test
    fun cancellationDuringTraversalPreventsVisitingChildrenAndStartingWatch() = runTest {
        val root = spyk(temporary.newFolder("root"))
        val child = spyk(File(root, "child").apply { mkdir() })
        val hub = hub()
        lateinit var collector: Job
        every { root.listFiles() } answers { collector.cancel(); arrayOf(child) }
        collector = backgroundScope.launch(start = CoroutineStart.LAZY) { hub.watch(root, recursive = true, poll = true).collect() }
        collector.start()
        runCurrent()
        collector.join()
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 0) { child.isDirectory }
        assertEquals(0, scans)
        assertTrue(RecordingFileObserver.started.isEmpty())
    }

    @Test
    fun sharedWatchSurvivesOneUnsubscribeAndStopsAfterLastSubscriber() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val flow = hub.watch(root)
        val first = collect(flow)
        val received = mutableListOf<FileChangeBatch>()
        val second = collect(flow, received)
        runCurrent()
        val observer = RecordingFileObserver.active.getValue(root.path)
        first.cancelAndJoin()
        runCurrent()
        observer.onEvent(FileObserver.CLOSE_WRITE, "keep.txt")
        runCurrent()
        assertEquals(1, RecordingFileObserver.started.size)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        assertEquals(listOf("/container/keep.txt"), received.flatMap { it.changes }.map { it.containerPath })
        second.cancelAndJoin()
        runCurrent()
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun oldQueuedStartCannotRegisterAfterReplacementSubscription() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val hub = hub(mainDispatcher = StandardTestDispatcher(observerScheduler))
        val root = temporary.newFolder("root")
        val first = collect(hub.watch(root))
        first.cancelAndJoin()
        val second = collect(hub.watch(root))
        observerScheduler.runCurrent()
        assertEquals(1, RecordingFileObserver.started.size)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        second.cancelAndJoin()
        observerScheduler.runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun samePathAbaIgnoresBothOldEventsAndOldInvalidation() = runTest {
        val hub = hub()
        val root = temporary.newFolder("root")
        val first = collect(hub.watch(root))
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        first.cancelAndJoin()
        val received = mutableListOf<FileChangeBatch>()
        val second = collect(hub.watch(root), received)
        runCurrent()
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        old.onEvent(FileObserver.CLOSE_WRITE, "stale.txt")
        old.onEvent(IN_IGNORED, null)
        current.onEvent(FileObserver.CLOSE_WRITE, "current.txt")
        runCurrent()
        assertSame(current, RecordingFileObserver.active.getValue(root.path))
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(listOf("/container/current.txt"), received.flatMap { it.changes }.map { it.containerPath })
        second.cancelAndJoin()
        runCurrent()
        assertEquals(2, RecordingFileObserver.stopped.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun replacementStartExecutedBeforeOldStopWaitsForTheStopJob() = runTest {
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher)
        val root = temporary.newFolder("root")
        val first = collect(hub.watch(root))
        observerDispatcher.runNext()
        first.cancelAndJoin()
        val second = collect(hub.watch(root))
        observerDispatcher.runLast()
        assertEquals(1, RecordingFileObserver.started.size)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        observerDispatcher.runNext()
        observerDispatcher.runNext()
        assertEquals(listOf("start", "stop", "start"), RecordingFileObserver.order.map { it.first })
        assertEquals(1, RecordingFileObserver.active.size)
        second.cancelAndJoin()
        observerDispatcher.runNext()
        assertTrue(RecordingFileObserver.active.isEmpty())
        assertEquals(0, observerDispatcher.size)
    }

    @Test
    fun subscribingReentrantlyDuringNativeStopCannotLoseTheReplacementWatch() = runTest {
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher)
        val root = temporary.newFolder("root")
        val first = collect(hub.watch(root))
        observerDispatcher.runNext()
        lateinit var second: Job
        RecordingFileObserver.beforeStop = {
            RecordingFileObserver.beforeStop = null
            second = collect(hub.watch(root))
            observerDispatcher.runLast()
            assertEquals(1, RecordingFileObserver.started.size)
        }
        first.cancelAndJoin()
        observerDispatcher.runNext()
        observerDispatcher.runNext()
        assertEquals(listOf("start", "stop", "start"), RecordingFileObserver.order.map { it.first })
        assertEquals(1, RecordingFileObserver.active.size)
        second.cancelAndJoin()
        observerDispatcher.runNext()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun systemInvalidationRebuildsOneSharedWatchForAllSubscribers() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val firstReceived = mutableListOf<FileChangeBatch>()
        val secondReceived = mutableListOf<FileChangeBatch>()
        val first = collect(hub.watch(root), firstReceived)
        val second = collect(hub.watch(root), secondReceived)
        runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        old.onEvent(IN_IGNORED, null)
        runCurrent()
        val current = RecordingFileObserver.active.getValue(root.path)
        assertNotSame(old, current)
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.stopped.size)
        current.onEvent(FileObserver.CLOSE_WRITE, "new.txt")
        runCurrent()
        assertEquals(listOf("/container/new.txt"), firstReceived.flatMap { it.changes }.map { it.containerPath })
        assertEquals(firstReceived, secondReceived)
        first.cancelAndJoin()
        second.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun synchronousInvalidationInsideStartDoesNotDeadlockOrLoseReattachment() = runTest {
        val root = temporary.newFolder("root")
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher)
        RecordingFileObserver.beforeStart = { observer ->
            RecordingFileObserver.beforeStart = null
            observer.onEvent(IN_IGNORED, null)
        }
        val collector = collect(hub.watch(root))
        observerDispatcher.runNext()
        runCurrent()
        observerDispatcher.runAll()
        runCurrent()
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertEquals(1, RecordingFileObserver.active.size)
        collector.cancelAndJoin()
        observerDispatcher.runAll()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun observerCallbackOnAnotherThreadCanInvalidateDuringNativeStartWithoutTakingTheLifecycleLockInReverse() = runTest {
        val root = temporary.newFolder("root")
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher)
        RecordingFileObserver.beforeStart = { observer ->
            RecordingFileObserver.beforeStart = null
            val completed = CountDownLatch(1)
            val callback = Thread {
                try { observer.onEvent(IN_IGNORED, null) } finally { completed.countDown() }
            }
            callback.start()
            assertTrue("native callback was blocked by the observer start lock", completed.await(5, TimeUnit.SECONDS))
            callback.join()
        }
        val collector = collect(hub.watch(root))
        observerDispatcher.runNext()
        runCurrent()
        observerDispatcher.runAll()
        runCurrent()
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.active.size)
        collector.cancelAndJoin()
        observerDispatcher.runAll()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun rootRemovedDuringStartCanBeWaitedForAgainAndRestored() = runTest {
        val root = temporary.newFolder("root")
        val observerDispatcher = QueuedObserverDispatcher()
        val hub = hub(mainDispatcher = observerDispatcher)
        RecordingFileObserver.beforeStart = { observer ->
            RecordingFileObserver.beforeStart = null
            assertTrue(root.delete())
            observer.onEvent(IN_IGNORED, null)
        }
        val collector = collect(hub.watch(root))
        observerDispatcher.runNext()
        runCurrent()
        observerDispatcher.runAll()
        assertTrue(root.mkdir())
        advanceTimeBy(2000)
        runCurrent()
        observerDispatcher.runAll()
        runCurrent()
        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.active.size)
        collector.cancelAndJoin()
        observerDispatcher.runAll()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun queuedRecursiveAttachmentIsCancelledWithItsSubscription() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val collector = collect(hub.watch(root, recursive = true))
        runCurrent()
        File(root, "child").mkdir()
        RecordingFileObserver.active.getValue(root.path).onEvent(FileObserver.CREATE, "child")
        collector.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(root.path), RecordingFileObserver.started.map { it.first })
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun pendingReadyScanIsCancelledBeforeIoAndNeverLeaksIntoNewSubscription() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val root = temporary.newFolder("root")
        val hub = hub(mainDispatcher = StandardTestDispatcher(observerScheduler))
        val collector = collect(hub.watch(root, recursive = true))
        runCurrent()
        observerScheduler.runCurrent()
        val old = RecordingFileObserver.active.getValue(root.path)
        old.onEvent(IN_IGNORED, null)
        runCurrent()
        RecordingFileObserver.beforeStart = {
            RecordingFileObserver.beforeStart = null
            File(root, "late-child").mkdir()
        }
        observerScheduler.runCurrent()
        collector.cancelAndJoin()
        runCurrent()
        observerScheduler.runCurrent()
        assertEquals(listOf(root.path, root.path), RecordingFileObserver.started.map { it.first })
        val received = mutableListOf<FileChangeBatch>()
        val replacement = collect(hub.watch(root, recursive = true), received)
        runCurrent()
        observerScheduler.runCurrent()
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(setOf(root.path, File(root, "late-child").path), RecordingFileObserver.active.keys)
        replacement.cancelAndJoin()
        observerScheduler.runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun equalScopesSharePollingAndOneClosingLeaseDoesNotStopTheOther() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val firstReceived = mutableListOf<FileChangeBatch>()
        val secondReceived = mutableListOf<FileChangeBatch>()
        val first = collect(hub.watch(root, poll = true), firstReceived)
        val second = collect(hub.watch(root, poll = true), secondReceived)
        runCurrent()
        assertEquals(1, scans)
        first.cancelAndJoin()
        snapshotEntries = mapOf("poll.txt" to "f:1:1")
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2, scans)
        assertTrue(firstReceived.isEmpty())
        assertEquals(listOf("/container/poll.txt"), secondReceived.flatMap { it.changes }.map { it.containerPath })
        second.cancelAndJoin()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(2, scans)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun consumerFinishingDuringSharedPollLeavesTheOtherConsumerAlive() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.watch(root, poll = true).take(1).collect() }
        val received = mutableListOf<FileChangeBatch>()
        val second = collect(hub.watch(root, poll = true), received)
        runCurrent()
        snapshotEntries = mapOf("first.txt" to "f:1:1")
        advanceTimeBy(2000)
        runCurrent()
        first.join()
        snapshotEntries = snapshotEntries + ("second.txt" to "f:1:1")
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(3, scans)
        assertEquals(listOf("/container/first.txt", "/container/second.txt"), received.flatMap { it.changes }.map { it.containerPath })
        second.cancelAndJoin()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(3, scans)
    }

    @Test
    fun incompleteSharedSnapshotSignalsTruncationWithoutFalseDeletion() = runTest {
        val root = temporary.newFolder("root")
        snapshotEntries = mapOf("kept.txt" to "f:1:1")
        val hub = hub()
        val received = mutableListOf<FileChangeBatch>()
        val collector = collect(hub.watch(root, poll = true), received)
        runCurrent()
        snapshotEntries = emptyMap()
        snapshotComplete = false
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(FileChangeBatch(emptyList(), truncated = true)), received)
        collector.cancelAndJoin()
        runCurrent()
    }

    @Test
    fun initialAndEventDrivenTraversalNeverFollowRecursiveSymlinks() = runTest {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        File(outside, "nested").mkdir()
        Files.createSymbolicLink(File(root, "initial-link").toPath(), outside.toPath())
        val hub = hub(realSnapshot = true)
        val collector = collect(hub.watch(root, recursive = true, poll = true))
        runCurrent()
        Files.createSymbolicLink(File(root, "new-link").toPath(), outside.toPath())
        RecordingFileObserver.active.getValue(root.path).onEvent(FileObserver.CREATE, "new-link")
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(root.path), RecordingFileObserver.started.map { it.first })
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun watchLimitStillAllowsAnotherSubscriberToReachAnExistingSharedDirectory() = runTest {
        val root = spyk(temporary.newFolder("root"))
        val overflow = spyk(File(root, "overflow").apply { mkdir() })
        val shared = File(overflow, "shared").apply { mkdir() }
        val children = (0 until 510).map { File(root, "dir$it").apply { mkdir() } } + overflow
        every { root.listFiles() } returns children.toTypedArray()
        val hub = hub()
        val first = collect(hub.watch(shared))
        val second = collect(hub.watch(root, recursive = true))
        runCurrent()
        assertEquals(512, RecordingFileObserver.active.size)
        verify(exactly = 1) { overflow.listFiles() }
        assertFalse(overflow.path in RecordingFileObserver.active)
        first.cancelAndJoin()
        runCurrent()
        assertTrue(shared.path in RecordingFileObserver.active)
        second.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun slowConsumerBackpressureRetainsOverflowSignalAndBoundedBatchQueue() = runTest {
        val root = temporary.newFolder("root")
        val hub = hub()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val received = mutableListOf<FileChangeBatch>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.watch(root).collect { batch ->
                received += batch
                if (received.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        runCurrent()
        val observer = RecordingFileObserver.active.getValue(root.path)
        observer.onEvent(FileObserver.CLOSE_WRITE, "first")
        entered.await()
        repeat(257) { index ->
            observer.onEvent(FileObserver.CLOSE_WRITE, "queued-$index")
            runCurrent()
        }
        repeat(5000) { observer.onEvent(FileObserver.CLOSE_WRITE, "burst-$it") }
        runCurrent()
        assertEquals(1, received.size)
        release.complete(Unit)
        runCurrent()
        assertEquals(259, received.size)
        assertTrue(received.last().truncated)
        assertEquals(512, received.last().changes.size)
        assertEquals((0 until 257).map { "/container/queued-$it" },
            received.drop(1).take(257).flatMap { it.changes }.map { it.containerPath })
        assertFalse(received.drop(1).take(257).any { it.truncated })
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    private class QueuedObserverDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() = tasks.removeFirst().run()
        fun runLast() = tasks.removeLast().run()
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }

    @Implements(FileObserver::class)
    class RecordingFileObserver {
        @RealObject private lateinit var realObserver: FileObserver
        private lateinit var path: String
        @Implementation fun __constructor__(path: String, mask: Int) { this.path = path }
        @Implementation fun startWatching() {
            started += path to realObserver
            active[path] = realObserver
            order += "start" to path
            beforeStart?.invoke(realObserver)
        }
        @Implementation fun stopWatching() {
            beforeStop?.invoke(realObserver)
            stopped += path to realObserver
            order += "stop" to path
            active.remove(path)
        }
        companion object {
            val started = mutableListOf<Pair<String, FileObserver>>()
            val stopped = mutableListOf<Pair<String, FileObserver>>()
            val active = mutableMapOf<String, FileObserver>()
            val order = mutableListOf<Pair<String, String>>()
            var beforeStart: ((FileObserver) -> Unit)? = null
            var beforeStop: ((FileObserver) -> Unit)? = null
            fun reset() {
                started.clear(); stopped.clear(); active.clear(); order.clear()
                beforeStart = null; beforeStop = null
            }
        }
    }

    private companion object {
        const val IN_IGNORED = 0x00008000
        val LOCAL_FILTER = WatchFilter(followGitignore = false)
    }
}
