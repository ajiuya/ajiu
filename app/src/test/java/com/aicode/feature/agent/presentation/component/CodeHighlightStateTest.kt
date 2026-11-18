package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.preview.ToolPreviewWorkVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeHighlightStateTest {
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

    private class Runtime(private val scope: TestScope, private val owner: Owner = Owner()) : AutoCloseable {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val composition = Composition(Applier(), recomposer)
        private val runner = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var time = 1_000_000L

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content) }
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

    private fun highlighted(code: String, color: Color = Color.Red): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), 0, code.length)
        addStringAnnotation("token", "keyword", 0, code.length)
    }

    @Test
    fun appendRetainsRealSpansAndImmediatelyDisplaysTheCompleteTail() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var code by mutableStateOf("val")
        var displayed = AnnotatedString("")
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(identity, code, "kotlin", false, dispatcher = dispatcher) {
                    delay(80L)
                    highlighted(it)
                }
            }
            advanceTimeBy(80L)
            runtime.flush()
            val prefix = displayed
            assertEquals(highlighted("val"), prefix)
            code = "val answer = 42\n"
            runtime.flush()
            assertEquals(code, displayed.text)
            assertEquals(prefix.spanStyles, displayed.spanStyles)
            assertEquals(prefix.getStringAnnotations(0, prefix.length), displayed.getStringAnnotations(0, displayed.length))
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
        }
    }

    @Test
    fun slowHighlightingPublishesCompatiblePrefixesDuring24msAppends() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var code by mutableStateOf("v")
        var displayed = AnnotatedString("")
        val started = mutableListOf<String>()
        val published = mutableListOf<AnnotatedString>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(identity, code, "kotlin", false, dispatcher = dispatcher) {
                    started += it
                    delay(80L)
                    highlighted(it)
                }
                SideEffect { published += displayed }
            }
            repeat(20) { index ->
                advanceTimeBy(24L)
                runCurrent()
                code += "x"
                runtime.flush()
                assertEquals(code, displayed.text)
                if (index >= 3) assertTrue("In-flight prefixes must keep their spans", displayed.spanStyles.isNotEmpty())
            }
            assertTrue(started.size < 10)
            assertTrue(published.any { value -> value.spanStyles.singleOrNull()?.end?.let { it in 1 until code.length } == true })
            advanceTimeBy(200L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
        }
    }

    @Test
    fun replacementClearsSpansSynchronouslyAndRejectsItsOldInFlightPrefix() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var code by mutableStateOf("old")
        var displayed = AnnotatedString("")
        val published = mutableListOf<AnnotatedString>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(identity, code, "kotlin", false, dispatcher = dispatcher) {
                    withContext(NonCancellable) { delay(80L) }
                    highlighted(it)
                }
                SideEffect { published += displayed }
            }
            advanceTimeBy(80L)
            runtime.flush()
            assertTrue(displayed.spanStyles.isNotEmpty())
            code = "old pending"
            runtime.flush()
            advanceTimeBy(24L)
            code = "replacement"
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            published.clear()
            advanceTimeBy(60L)
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            assertTrue(published.all { it.text == code && it.spanStyles.isEmpty() })
            advanceTimeBy(24L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
        }
    }

    @Test
    fun replacementThenReturningToTheOriginalCodeDoesNotReviveTheOriginalFlight() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var code by mutableStateOf("A")
        var displayed = AnnotatedString("")
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(identity, code, "kotlin", false, dispatcher = dispatcher) {
                    withContext(NonCancellable) { delay(80L) }
                    highlighted(it)
                }
            }
            advanceTimeBy(24L)
            code = "B"
            runtime.flush()
            advanceTimeBy(24L)
            code = "A"
            runtime.flush()
            advanceTimeBy(40L)
            runtime.flush()
            assertEquals(AnnotatedString("A"), displayed)
            advanceTimeBy(40L)
            runtime.flush()
            assertEquals(highlighted("A"), displayed)
        }
    }

    @Test
    fun themeAndLanguageChangesClearSpansAndRejectOldStyleFlights() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var code by mutableStateOf("val")
        var dark by mutableStateOf(false)
        var language by mutableStateOf("kotlin")
        var displayed = AnnotatedString("")
        Runtime(this).use { runtime ->
            runtime.setContent {
                val requestedColor = if (language == "java") Color.Green else if (dark) Color.Blue else Color.Red
                displayed = rememberCodeHighlightState(identity, code, language, dark, dispatcher = dispatcher) {
                    withContext(NonCancellable) { delay(80L) }
                    highlighted(it, requestedColor)
                }
            }
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
            code += " x"
            runtime.flush()
            advanceTimeBy(24L)
            dark = true
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            advanceTimeBy(60L)
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            advanceTimeBy(24L)
            runtime.flush()
            assertEquals(highlighted(code, Color.Blue), displayed)
            code += " y"
            runtime.flush()
            advanceTimeBy(24L)
            language = "java"
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            advanceTimeBy(60L)
            runtime.flush()
            assertEquals(AnnotatedString(code), displayed)
            advanceTimeBy(24L)
            runtime.flush()
            assertEquals(highlighted(code, Color.Green), displayed)
        }
    }

    @Test
    fun hiddenOrStoppedWorkCannotPublishLateAndResumesWithCurrentCode() = runTest(dispatcher) {
        val owner = Owner()
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        var visible by mutableStateOf(true)
        var code by mutableStateOf("val")
        var displayed = AnnotatedString("")
        Runtime(this, owner).use { runtime ->
            runtime.setContent {
                ToolPreviewWorkVisibility(visible) {
                    displayed = rememberCodeHighlightState(identity, code, "kotlin", false, dispatcher = dispatcher) {
                        withContext(NonCancellable) { delay(80L) }
                        highlighted(it)
                    }
                }
            }
            advanceTimeBy(80L)
            runtime.flush()
            val first = displayed
            code += " x"
            runtime.flush()
            advanceTimeBy(24L)
            visible = false
            runtime.flush()
            advanceTimeBy(100L)
            runtime.flush()
            assertEquals(code, displayed.text)
            assertEquals(first.spanStyles, displayed.spanStyles)
            visible = true
            runtime.flush()
            advanceTimeBy(80L)
            runtime.flush()
            val resumed = displayed
            assertEquals(highlighted(code), resumed)
            code += " y"
            runtime.flush()
            advanceTimeBy(24L)
            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            advanceTimeBy(100L)
            runtime.flush()
            assertEquals(resumed.spanStyles, displayed.spanStyles)
            code += " z"
            runtime.flush()
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
        }
    }

    @Test
    fun aLongerCacheHitSurvivesAnOlderShortFlightAndIsRetainedOnAppend() = runTest(dispatcher) {
        val identity = CodeHighlightBlockIdentity(Any(), 0, "CODE_FENCE")
        val cached = highlighted("val cached", Color.Green)
        var code by mutableStateOf("val")
        var displayed = AnnotatedString("")
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(
                    identity, code, "kotlin", false, cached.takeIf { code == it.text }, dispatcher,
                ) {
                    delay(80L)
                    highlighted(it)
                }
            }
            advanceTimeBy(24L)
            code = cached.text
            runtime.flush()
            assertEquals(cached, displayed)
            code += " tail"
            runtime.flush()
            assertEquals(cached.spanStyles, displayed.spanStyles)
            advanceTimeBy(60L)
            runtime.flush()
            assertEquals(code, displayed.text)
            assertEquals(cached.spanStyles, displayed.spanStyles)
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted(code), displayed)
        }
    }

    @Test
    fun blockPositionTypeAndMessageOrChunkOwnerKeepDuplicateCodeIndependent() = runTest(dispatcher) {
        val owner = Any()
        var identity by mutableStateOf(CodeHighlightBlockIdentity(owner, 0, "CODE_FENCE"))
        var displayed = AnnotatedString("")
        var calls = 0
        Runtime(this).use { runtime ->
            runtime.setContent {
                displayed = rememberCodeHighlightState(identity, "val", "kotlin", false, dispatcher = dispatcher) {
                    calls++
                    withContext(NonCancellable) { delay(80L) }
                    highlighted(it)
                }
            }
            advanceTimeBy(80L)
            runtime.flush()
            assertTrue(displayed.spanStyles.isNotEmpty())
            val initialCalls = calls
            identity = CodeHighlightBlockIdentity(owner, 0, "CODE_FENCE")
            runtime.flush()
            assertEquals(initialCalls, calls)
            assertTrue(displayed.spanStyles.isNotEmpty())
            for (next in listOf(
                CodeHighlightBlockIdentity(owner, 20, "CODE_FENCE"),
                CodeHighlightBlockIdentity(owner, 20, "CODE_BLOCK"),
                CodeHighlightBlockIdentity(Any(), 20, "CODE_BLOCK"),
            )) {
                advanceTimeBy(24L)
                identity = next
                runtime.flush()
                assertEquals(AnnotatedString("val"), displayed)
            }
            advanceTimeBy(40L)
            runtime.flush()
            assertEquals(AnnotatedString("val"), displayed)
            advanceTimeBy(40L)
            runtime.flush()
            assertEquals(highlighted("val"), displayed)
        }
    }

    @Test
    fun stablePositionKeysKeepExistingBlocksWhenTheAstSnapshotGrows() = runTest(dispatcher) {
        val owner = Any()
        val first = CodeHighlightBlockIdentity(owner, 0, "CODE_FENCE")
        val second = CodeHighlightBlockIdentity(owner, 20, "CODE_FENCE")
        var blocks by mutableStateOf(listOf(first))
        val displayed = mutableMapOf<CodeHighlightBlockIdentity, AnnotatedString>()
        val started = mutableListOf<CodeHighlightBlockIdentity>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                for (identity in blocks) key(identity.key) {
                    val value = rememberCodeHighlightState(identity, "val", "kotlin", false, dispatcher = dispatcher) {
                        started += identity
                        delay(80L)
                        highlighted(it)
                    }
                    SideEffect { displayed[identity] = value }
                }
            }
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted("val"), displayed[first])
            blocks = listOf(first.copy(), second)
            runtime.flush()
            assertEquals(highlighted("val"), displayed[first])
            assertEquals(AnnotatedString("val"), displayed[second])
            assertEquals(1, started.count { it == first })
            assertEquals(1, started.count { it == second })
            advanceTimeBy(80L)
            runtime.flush()
            assertEquals(highlighted("val"), displayed[second])
        }
    }
}
