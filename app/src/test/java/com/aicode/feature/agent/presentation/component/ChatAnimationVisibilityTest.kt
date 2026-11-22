package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.preview.ToolPreviewWorkVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAnimationVisibilityTest {
    private class Owner(state: Lifecycle.State) : LifecycleOwner {
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
        private var nanos = 1_000_000L
        val awaitsFrames get() = clock.hasAwaiters

        fun setContent(content: @Composable () -> Unit) { composition.setContent(content); flush() }
        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    clock.sendFrame(nanos)
                    scope.runCurrent()
                }
            }
        }
        fun frame(milliseconds: Long = 100) {
            nanos += milliseconds * 1_000_000L
            clock.sendFrame(nanos)
            flush()
        }
        override fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            scope.runCurrent()
        }
    }

    @Composable
    private fun pulse(enabled: Boolean = true, pausedValue: Float = 1f): State<Float> = rememberChatInfiniteFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "fixture-pulse",
        enabled = enabled,
        pausedValue = pausedValue
    )

    @Test
    fun decorationsRequireResumedWhileExistingUiWorkStillRunsAtStarted() = runTest {
        val owner = Owner(Lifecycle.State.STARTED)
        var uiWork = false
        var animations = true
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    uiWork = chatUiWorkActive()
                    animations = chatAnimationsEnabled()
                }
            }
            assertTrue(uiWork)
            assertFalse(animations)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runtime.flush()
            assertTrue(uiWork)
            assertTrue(animations)
            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            assertFalse(uiWork)
            assertFalse(animations)
        }
    }

    @Test
    fun pausedLifecycleDoesNotRequestFramesAndResumeRestartsOnlyOneTransition() = runTest {
        val owner = Owner(Lifecycle.State.STARTED)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { state = pulse() } }
            assertEquals(1f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
            assertTrue(runtime.awaitsFrames)
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertEquals(1f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
            runtime.frame(10_000)
            assertEquals(1f, state!!.value, 0f)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value in 0.25f..0.9999f)
        }
    }

    @Test
    fun hidingAVisibleUiSubtreeStopsItsFramesWithoutChangingLifecycle() = runTest {
        val owner = Owner(Lifecycle.State.RESUMED)
        var visible by mutableStateOf(true)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    ToolPreviewWorkVisibility(visible) { state = pulse() }
                }
            }
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
            visible = false
            runtime.flush()
            assertEquals(1f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
            visible = true
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
        }
    }

    @Test
    fun closedDrawerGateDoesNotAnimateEvenWhenTheActivityIsResumed() = runTest {
        val owner = Owner(Lifecycle.State.RESUMED)
        var drawerVisible by mutableStateOf(false)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalChatAnimationsEnabled provides drawerVisible) {
                    state = pulse()
                }
            }
            assertFalse(runtime.awaitsFrames)
            drawerVisible = true
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
            drawerVisible = false
            runtime.flush()
            assertEquals(1f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
        }
    }

    @Test
    fun localAnimationPermissionCannotOverrideAHiddenParent() = runTest {
        val owner = Owner(Lifecycle.State.RESUMED)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalChatAnimationsEnabled provides true) {
                    ToolPreviewWorkVisibility(false) {
                        ToolPreviewWorkVisibility(true) { state = pulse() }
                    }
                }
            }
            assertEquals(1f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
        }
    }

    @Test
    fun disabledAndPausedValuesRemainStaticWithoutAnInfiniteTransition() = runTest {
        val owner = Owner(Lifecycle.State.RESUMED)
        var enabled by mutableStateOf(false)
        var pausedValue by mutableStateOf(0.8f)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { state = pulse(enabled, pausedValue) }
            }
            assertEquals(0.8f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
            pausedValue = 0.6f
            runtime.flush()
            assertEquals(0.6f, state!!.value, 0f)
            enabled = true
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
            enabled = false
            runtime.flush()
            assertEquals(0.6f, state!!.value, 0f)
            assertFalse(runtime.awaitsFrames)
        }
    }

    @Test
    fun consumersCanReadFrameValuesWithoutPerFrameRecomposition() = runTest {
        val owner = Owner(Lifecycle.State.RESUMED)
        var state: State<Float>? = null
        var compositions = 0
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    state = pulse()
                    SideEffect { compositions++ }
                }
            }
            runtime.frame()
            val initialCompositions = compositions
            val values = (1..10).map { runtime.frame(40); state!!.value }
            assertTrue(values.distinct().size > 3)
            assertEquals(initialCompositions, compositions)
        }
    }

    @Test
    fun replacingLifecycleOwnerDoesNotKeepTheOldForegroundPermission() = runTest {
        val old = Owner(Lifecycle.State.RESUMED)
        val next = Owner(Lifecycle.State.CREATED)
        var owner by mutableStateOf<LifecycleOwner>(old)
        var state: State<Float>? = null
        Runtime(this).use { runtime ->
            runtime.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { state = pulse() } }
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
            owner = next
            runtime.flush()
            assertFalse(runtime.awaitsFrames)
            assertEquals(1f, state!!.value, 0f)
            old.registry.currentState = Lifecycle.State.STARTED
            old.registry.currentState = Lifecycle.State.RESUMED
            runtime.flush()
            assertFalse(runtime.awaitsFrames)
            next.registry.currentState = Lifecycle.State.RESUMED
            runtime.flush()
            runtime.frame()
            runtime.frame()
            assertTrue(state!!.value < 1f)
        }
    }
}
