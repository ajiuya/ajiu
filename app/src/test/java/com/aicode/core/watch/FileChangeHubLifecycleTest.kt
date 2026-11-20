package com.aicode.core.watch

import android.app.Application
import android.os.FileObserver
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
        realSnapshot: Boolean = false
    ) = FileChangeHub(workspace, installer, mapper, ExecutionModeHolder(), ioDispatcher, mainDispatcher,
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
        })

    private fun FileChangeHub.watch(root: File, recursive: Boolean = false, poll: Boolean = false,
        filter: WatchFilter = LOCAL_FILTER): Flow<FileChangeBatch> = watchHostDir(root,
        containerRootPath = "/container", recursive = recursive, filter = filter,
        fallbackPoll = poll, batchWindowMs = 0)

    private fun TestScope.collect(flow: Flow<FileChangeBatch>, received: MutableList<FileChangeBatch> = mutableListOf()): Job =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.toList(received) }

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
