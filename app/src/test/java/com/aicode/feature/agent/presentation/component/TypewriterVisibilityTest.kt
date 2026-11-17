package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
class TypewriterVisibilityTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
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
        val awaitsFrames: Boolean get() = clock.hasAwaiters

        fun setContent(content: @Composable () -> Unit) { composition.setContent(content); flush() }
        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    clock.sendFrame(time)
                    scope.runCurrent()
                }
            }
        }
        fun frame(milliseconds: Long = 24L) {
            time += milliseconds * 1_000_000L
            clock.sendFrame(time)
            flush()
        }
        override fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            scope.runCurrent()
        }
    }

    @Test
    fun providerBeforeTypewriterRequiresVisibleAndStartedAndResumesWithOneCatchup() = runTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        var visible by mutableStateOf(true)
        var raw by mutableStateOf("后台已到🚀\uD83D")
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    ToolPreviewWorkVisibility(visible) {
                        shown = rememberTypewriterStreamingText(raw, active = true, sessionKey = "session", initialText = "")
                    }
                }
            }
            assertEquals("", shown.text)
            assertFalse(runtime.awaitsFrames)
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertEquals("后台已到🚀", shown.text)
            raw += "\uDE00" + "前台增量".repeat(10)
            runtime.flush()
            repeat(10) { runtime.frame() }
            val beforeHide = shown.text
            assertTrue(beforeHide.length > "后台已到🚀".length)
            assertTrue(beforeHide.length < raw.length)
            visible = false
            runtime.flush()
            raw += "离屏更多𝄞"
            runtime.flush()
            repeat(5) { runtime.frame(10_000L) }
            assertEquals(beforeHide, shown.text)
            assertFalse(runtime.awaitsFrames)
            visible = true
            runtime.flush()
            assertEquals(raw, shown.text)
            assertTrue(shown.settled)
            raw += "新前台尾巴".repeat(10)
            runtime.flush()
            repeat(10) { runtime.frame() }
            assertFalse(shown.settled)
            assertTrue(typewriterCodePointCount(shown.text) - typewriterCodePointCount(raw.substringBeforeLast("新前台尾巴".repeat(10))) <= 8)
        }
    }

    @Test
    fun foregroundCompletionStillDrainsAtUserRateWithoutADelayedJump() = runTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        var active by mutableStateOf(true)
        val raw = "文".repeat(300)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberTypewriterStreamingText(raw, active, "session", initialText = "")
                }
            }
            repeat(10) { runtime.frame() }
            val before = typewriterCodePointCount(shown.text)
            assertTrue(before in 1..8)
            active = false
            runtime.flush()
            repeat(25) { runtime.frame() }
            val after = typewriterCodePointCount(shown.text)
            assertTrue(after - before in 16..20)
            assertFalse(shown.settled)
        }
    }

    @Test
    fun actualPlayersShowDifferentCharacterCountsAtTheSelectedSpeeds() = runTest {
        val raw = "文🚀".repeat(1_000)
        var slow = TypewriterText("", false)
        var fast = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                slow = rememberTypewriterStreamingText(raw, true, "slow", 10, initialText = "", workActive = true)
                fast = rememberTypewriterStreamingText(raw, true, "fast", 120, initialText = "", workActive = true)
            }
            runtime.frame(25L)
            val slowBefore = typewriterCodePointCount(slow.text)
            val fastBefore = typewriterCodePointCount(fast.text)
            repeat(40) { runtime.frame(25L) }
            val slowCount = typewriterCodePointCount(slow.text) - slowBefore
            val fastCount = typewriterCodePointCount(fast.text) - fastBefore
            assertTrue("Expected about 10 code points per second, got $slowCount", slowCount in 9..11)
            assertTrue("Expected about 120 code points per second, got $fastCount", fastCount in 119..121)
            assertFalse(slow.settled)
            assertFalse(fast.settled)
            assertFalse(slow.text.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(fast.text.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test
    fun changingSelectedSpeedDuringPlaybackTakesEffectWithoutResettingThePrefix() = runTest {
        val raw = "文".repeat(1_000)
        var speed by mutableStateOf(10)
        var active by mutableStateOf(true)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                shown = rememberTypewriterStreamingText(raw, active, "session", speed, initialText = "", workActive = true)
            }
            repeat(41) { runtime.frame(25L) }
            val slowPrefix = shown.text
            assertTrue(slowPrefix.length in 9..11)
            speed = 120
            runtime.flush()
            assertEquals(slowPrefix, shown.text)
            repeat(40) { runtime.frame(25L) }
            val fastCount = shown.text.length
            assertTrue(fastCount - slowPrefix.length in 119..121)
            active = false
            speed = 10
            runtime.flush()
            repeat(40) { runtime.frame(25L) }
            assertTrue(shown.text.length - fastCount in 9..11)
            assertFalse(shown.settled)
        }
    }

    @Test
    fun stoppedLifecycleFreezesFramesAndSessionAndEntryKeysNeverBorrowOldText() = runTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        var session by mutableStateOf("old")
        var entry by mutableStateOf("first")
        var raw by mutableStateOf("old text ".repeat(20))
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    ToolPreviewWorkVisibility(true) {
                        key(session, entry) { shown = rememberTypewriterStreamingText(raw, true, session, initialText = "") }
                    }
                }
            }
            repeat(10) { runtime.frame() }
            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            val before = shown.text
            raw += "new hidden text"
            runtime.flush()
            repeat(3) { runtime.frame(5_000L) }
            assertEquals(before, shown.text)
            assertFalse(runtime.awaitsFrames)
            session = "new"
            entry = "second"
            raw = "new session 🚀"
            runtime.flush()
            assertEquals("", shown.text)
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertEquals("new session 🚀", shown.text)
            assertFalse(shown.text.contains("old"))
        }
    }
}
