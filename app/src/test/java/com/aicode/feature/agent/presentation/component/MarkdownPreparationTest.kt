package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.preview.ToolPreviewWorkVisibility
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownPreparationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

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

    private fun prepared(raw: String, size: Float = 16f, color: Int = 1): PreparedMarkdown = mockk {
        every { this@mockk.raw } returns raw
        every { textSizePx } returns size
        every { colorArgb } returns color
    }

    @Test
    fun continuousPrefixesKeepPublishingWhenPreparationTakesLongerThanTheUpdateInterval() = runTest(dispatcher) {
        val owner = Owner()
        val cache = MarkdownRenderCache()
        var text by mutableStateOf("文")
        var displayed = MarkdownPreparation()
        val started = mutableListOf<String>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    displayed = rememberMarkdownPreparation(text, cache, 16f, 1, dispatcher) { raw, _ ->
                        started += raw
                        delay(80L)
                        MarkdownPreparation(prepared(raw))
                    }
                }
            }
            repeat(20) { index ->
                advanceTimeBy(24L)
                runCurrent()
                text += "文"
                runtime.flush()
                if (index == 10) {
                    assertNotNull("An in-flight prefix must finish while newer prefixes keep arriving", displayed.content)
                    assertTrue(text.startsWith(displayed.content!!.raw))
                }
            }
            assertTrue(started.size < 10)
            advanceTimeBy(200L)
            runtime.flush()
            assertEquals(text, displayed.content!!.raw)
            assertFalse(displayed.failed)
        }
    }

    @Test
    fun replacingTextNeverPublishesAnIncompatibleInFlightPrefix() = runTest(dispatcher) {
        val owner = Owner()
        val cache = MarkdownRenderCache()
        var text by mutableStateOf("old response")
        val published = mutableListOf<String>()
        var displayed = MarkdownPreparation()
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    displayed = rememberMarkdownPreparation(text, cache, 16f, 1, dispatcher) { raw, _ ->
                        delay(80L)
                        MarkdownPreparation(prepared(raw))
                    }
                    SideEffect { displayed.content?.raw?.let(published::add) }
                }
            }
            advanceTimeBy(24L)
            text = "new response"
            runtime.flush()
            advanceTimeBy(60L)
            runtime.flush()
            assertFalse(published.contains("old response"))
            advanceTimeBy(100L)
            runtime.flush()
            assertEquals("new response", displayed.content!!.raw)
            assertFalse(published.contains("old response"))
        }
    }

    @Test
    fun styleChangesAndVisibilityCancelOldWorkAndResumeWithTheCurrentInput() = runTest(dispatcher) {
        val owner = Owner()
        val cache = MarkdownRenderCache()
        var text by mutableStateOf("old")
        var size by mutableStateOf(16f)
        var visible by mutableStateOf(true)
        var displayed = MarkdownPreparation()
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    ToolPreviewWorkVisibility(visible) {
                        displayed = rememberMarkdownPreparation(text, cache, size, 1, dispatcher) { raw, _ ->
                            val requestedSize = size
                            delay(80L)
                            MarkdownPreparation(prepared(raw, requestedSize))
                        }
                    }
                }
            }
            advanceTimeBy(24L)
            size = 22f
            text = "new"
            runtime.flush()
            advanceTimeBy(60L)
            runtime.flush()
            assertNull(displayed.content)
            visible = false
            runtime.flush()
            advanceTimeBy(200L)
            runtime.flush()
            assertNull(displayed.content)
            text = "newest"
            visible = true
            runtime.flush()
            advanceTimeBy(100L)
            runtime.flush()
            assertEquals("newest", displayed.content!!.raw)
            assertEquals(22f, displayed.content!!.textSizePx, 0f)
        }
    }
}
