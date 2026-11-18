package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDrawerBrowseCollectionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Owner(state: Lifecycle.State = Lifecycle.State.STARTED) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Applier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Runtime(private val scope: TestScope) : AutoCloseable {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val composition = Composition(Applier(), recomposer)
        private val runner = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var time = 1_000_000L

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent(content)
            flush()
        }

        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    time += 1_000_000L
                    clock.sendFrame(time)
                    scope.runCurrent()
                }
            }
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            scope.runCurrent()
        }
    }

    private fun tree(name: String) = FileBrowseState.Success(
        listOf(
            FileTreeNode(
                entry = FileEntry(name = name, isDirectory = true, size = 0L, lastModified = 0L),
                path = "~/workspace/$name",
                depth = 1,
                isExpanded = true
            )
        )
    )

    @Test
    fun onlyVisibleFileTabCollectsAndSwitchingTabsKeepsTheLastTree() = runTest(dispatcher) {
        val owner = Owner()
        val initial = tree("initial")
        val source = MutableStateFlow<FileBrowseState>(initial)
        val subscriptions = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.subscriptionCount.collect { subscriptions += it }
        }
        var sidebarVisible by mutableStateOf(false)
        var selectedTab by mutableStateOf(0)
        var shown: FileBrowseState = FileBrowseState.Loading
        var refreshes = 0
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberDrawerBrowseState(source, sidebarVisible, selectedTab) { refreshes++ }
                }
            }
            assertSame(initial, shown)
            assertEquals(0, source.subscriptionCount.value)
            sidebarVisible = true
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            assertEquals(0, refreshes)

            selectedTab = 1
            runtime.flush()
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(1, refreshes)
            val live = tree("live")
            source.value = live
            runtime.flush()
            assertSame(live, shown)
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(1, refreshes)

            selectedTab = 0
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            val whileInSessions = tree("while-in-sessions")
            source.value = whileInSessions
            runtime.flush()
            assertSame(live, shown)
            selectedTab = 1
            runtime.flush()
            assertSame(whileInSessions, shown)
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(2, refreshes)

            sidebarVisible = false
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            val hidden = tree("hidden")
            source.value = hidden
            runtime.flush()
            assertSame(whileInSessions, shown)
            sidebarVisible = true
            runtime.flush()
            assertSame(hidden, shown)
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(3, refreshes)
            assertEquals(listOf(0, 1, 0, 1, 0, 1), subscriptions)
        }
        assertEquals(0, source.subscriptionCount.value)
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), subscriptions)
    }

    @Test
    fun lifecycleMustBeStartedAndEachRestartUsesTheLatestRefreshCallback() = runTest(dispatcher) {
        val owner = Owner(Lifecycle.State.CREATED)
        val initial = tree("initial")
        val source = MutableStateFlow<FileBrowseState>(initial)
        var shown: FileBrowseState = FileBrowseState.Loading
        var firstRefreshes = 0
        var latestRefreshes = 0
        var onRefresh by mutableStateOf<() -> Unit>({ firstRefreshes++ })
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberDrawerBrowseState(source, true, 1, onRefresh)
                }
            }
            assertEquals(0, source.subscriptionCount.value)
            assertEquals(0, firstRefreshes)
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(1, firstRefreshes)
            owner.registry.currentState = Lifecycle.State.RESUMED
            onRefresh = { latestRefreshes++ }
            runtime.flush()
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(0, latestRefreshes)

            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            val background = tree("background")
            source.value = background
            runtime.flush()
            assertSame(initial, shown)
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertSame(background, shown)
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(1, firstRefreshes)
            assertEquals(1, latestRefreshes)
            owner.registry.currentState = Lifecycle.State.DESTROYED
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
        }
    }

    @Test
    fun reopenRefreshesInsideTheFiveSecondGraceAndAfterUpstreamRestartsWithoutClearingTheCache() = runTest(dispatcher) {
        val owner = Owner()
        val first = tree("first")
        val upstream = MutableStateFlow<FileBrowseState>(first)
        var starts = 0
        var stops = 0
        val source = upstream
            .onStart { starts++; delay(25L) }
            .onCompletion { stops++ }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000L), FileBrowseState.Loading)
        var sidebarVisible by mutableStateOf(true)
        var shown: FileBrowseState = FileBrowseState.Loading
        var refreshes = 0
        val refreshed = tree("refreshed")
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberDrawerBrowseState(source, sidebarVisible, 1) {
                        refreshes++
                        if (refreshes == 2) upstream.value = refreshed
                    }
                }
            }
            assertSame(FileBrowseState.Loading, shown)
            advanceTimeBy(25L)
            runtime.flush()
            assertSame(first, shown)
            assertEquals(1, starts)
            assertEquals(1, refreshes)

            sidebarVisible = false
            runtime.flush()
            advanceTimeBy(4_999L)
            runtime.flush()
            assertEquals(1, upstream.subscriptionCount.value)
            assertEquals(0, stops)
            assertSame(first, shown)
            sidebarVisible = true
            runtime.flush()
            assertSame(refreshed, shown)
            assertEquals(1, starts)
            assertEquals(2, refreshes)

            sidebarVisible = false
            runtime.flush()
            advanceTimeBy(5_001L)
            runtime.flush()
            assertEquals(0, upstream.subscriptionCount.value)
            assertEquals(1, stops)
            val afterStop = tree("after-stop")
            upstream.value = afterStop
            sidebarVisible = true
            runtime.flush()
            assertSame(refreshed, shown)
            assertEquals(2, starts)
            assertEquals(3, refreshes)
            advanceTimeBy(25L)
            runtime.flush()
            assertSame(afterStop, shown)
            assertEquals(1, upstream.subscriptionCount.value)
        }
    }
}
