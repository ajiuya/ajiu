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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDrawerScopeSnapshotTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
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
        fun setContent(content: @Composable () -> Unit) { composition.setContent(content); flush() }
        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    clock.sendFrame(time++)
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

    private fun tree(name: String) = FileBrowseState.Success(listOf(
        FileTreeNode(FileEntry(name, isDirectory = false, size = 1, lastModified = 1), "~/workspace/$name", 1, isExpanded = false)
    ))

    @Test
    fun reopeningAfterHiddenScopeResetStartsWithLoadingRatherThanTheOldTree() = runTest(dispatcher) {
        val owner = Owner()
        val old = tree("old-server")
        val source = MutableStateFlow<FileBrowseState>(old)
        var visible by mutableStateOf(true)
        val frames = mutableListOf<FileBrowseState>()
        var refreshes = 0
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val state = rememberDrawerBrowseState(source, visible, 1) { refreshes++ }
                    if (visible) frames += state
                }
            }
            assertSame(old, frames.last())
            visible = false
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            source.value = FileBrowseState.Loading
            frames.clear()
            visible = true
            runtime.flush()
            assertTrue(frames.isNotEmpty())
            assertSame(FileBrowseState.Loading, frames.first())
            assertTrue(frames.none { it === old })
            assertEquals(1, source.subscriptionCount.value)
            assertEquals(2, refreshes)
        }
    }

    @Test
    fun returningToTheFileTabSamplesTheNewScopeBeforeItsCollectorStarts() = runTest(dispatcher) {
        val owner = Owner()
        val old = tree("old-profile")
        val source = MutableStateFlow<FileBrowseState>(old)
        var tab by mutableStateOf(1)
        val frames = mutableListOf<FileBrowseState>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val state = rememberDrawerBrowseState(source, true, tab) {}
                    if (tab == 1) frames += state
                }
            }
            tab = 0
            runtime.flush()
            val latest = tree("new-profile")
            source.value = latest
            frames.clear()
            tab = 1
            runtime.flush()
            assertTrue(frames.isNotEmpty())
            assertSame(latest, frames.first())
            assertTrue(frames.none { it === old })
        }
    }

    @Test
    fun foregroundRestartDoesNotRenderAnOldBackgroundScopeOnItsFirstComposition() = runTest(dispatcher) {
        val owner = Owner()
        val old = tree("before-background")
        val source = MutableStateFlow<FileBrowseState>(old)
        val frames = mutableListOf<FileBrowseState>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    frames += rememberDrawerBrowseState(source, true, 1) {}
                }
            }
            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            assertEquals(0, source.subscriptionCount.value)
            source.value = FileBrowseState.Loading
            frames.clear()
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertTrue(frames.isNotEmpty())
            assertSame(FileBrowseState.Loading, frames.first())
            assertTrue(frames.none { it === old })
        }
    }

    @Test
    fun hiddenRefreshesDoNotAddASubscriptionOrUpdateTheRetainedSnapshot() = runTest(dispatcher) {
        val owner = Owner()
        val first = tree("first")
        val source = MutableStateFlow<FileBrowseState>(first)
        var visible by mutableStateOf(true)
        var tick by mutableStateOf(0)
        var shown: FileBrowseState = FileBrowseState.Loading
        var refreshes = 0
        Runtime(this).use { runtime ->
            runtime.setContent {
                tick
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberDrawerBrowseState(source, visible, 1) { refreshes++ }
                }
            }
            visible = false
            runtime.flush()
            repeat(50) {
                source.value = tree("hidden-$it")
                tick++
                runtime.flush()
                assertEquals(0, source.subscriptionCount.value)
                assertSame(first, shown)
            }
            assertEquals(1, refreshes)
            visible = true
            runtime.flush()
            assertSame(source.value, shown)
            assertEquals(2, refreshes)
        }
    }
}
