package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
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
                        shown = rememberTypewriterStreamingText(raw, active = true, sessionKey = "session", initialText = "").value
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
    fun foregroundCompletionKeepsTheSelectedLimitWithoutADelayedJump() = runTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        var active by mutableStateOf(true)
        val raw = "文".repeat(300)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    shown = rememberTypewriterStreamingText(raw, active, "session", initialText = "").value
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
                slow = rememberTypewriterStreamingText(raw, true, "slow", 30, initialText = "", workActive = true).value
                fast = rememberTypewriterStreamingText(raw, true, "fast", 350, initialText = "", workActive = true).value
            }
            runtime.frame(25L)
            val slowBefore = typewriterCodePointCount(slow.text)
            val fastBefore = typewriterCodePointCount(fast.text)
            repeat(40) { runtime.frame(25L) }
            val slowCount = typewriterCodePointCount(slow.text) - slowBefore
            val fastCount = typewriterCodePointCount(fast.text) - fastBefore
            assertTrue("Expected about 30 code points per second, got $slowCount", slowCount in 29..31)
            assertTrue("Expected about 350 code points per second, got $fastCount", fastCount in 349..351)
            assertFalse(slow.settled)
            assertFalse(fast.settled)
            assertFalse(slow.text.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(fast.text.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test
    fun highLimitAdaptsToSmallAndLargeBacklogsWithoutBankingIdleTime() = runTest {
        var raw by mutableStateOf("文".repeat(12))
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                shown = rememberTypewriterStreamingText(raw, true, "session", 350, initialText = "", workActive = true).value
            }
            assertEquals("", shown.text)
            runtime.frame(25L)
            assertEquals(2, shown.text.length)
            repeat(40) { runtime.frame(25L) }
            assertEquals(raw, shown.text)
            assertFalse(runtime.awaitsFrames)
            runtime.frame(2_000L)
            val beforeBurst = shown.text
            raw += "尾".repeat(500)
            runtime.flush()
            assertEquals(beforeBurst, shown.text)
            runtime.frame(25L)
            assertEquals(beforeBurst + "尾".repeat(8), shown.text)
        }
    }

    @Test
    fun changingSelectedSpeedDuringPlaybackTakesEffectWithoutResettingThePrefix() = runTest {
        val raw = "文".repeat(1_000)
        var speed by mutableStateOf(30)
        var active by mutableStateOf(true)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                shown = rememberTypewriterStreamingText(raw, active, "session", speed, initialText = "", workActive = true).value
            }
            repeat(41) { runtime.frame(25L) }
            val slowPrefix = shown.text
            assertTrue(slowPrefix.length in 29..31)
            speed = 350
            runtime.flush()
            assertEquals(slowPrefix, shown.text)
            repeat(40) { runtime.frame(25L) }
            val fastCount = shown.text.length
            assertTrue(fastCount - slowPrefix.length in 349..351)
            active = false
            speed = 30
            runtime.flush()
            repeat(40) { runtime.frame(25L) }
            assertTrue(shown.text.length - fastCount in 29..31)
            assertFalse(shown.settled)
        }
    }

    @Test
    fun partialTailDrainsAtTheRateSetByItsStartingLengthAndKeepsTheExistingPrefix() = runTest {
        val beginning = "回复："
        var raw by mutableStateOf(beginning)
        var active by mutableStateOf(true)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                shown = rememberTypewriterStreamingText(raw, active, "session", 350, initialText = beginning, workActive = true).value
            }
            assertEquals(beginning, shown.text)
            assertFalse(runtime.awaitsFrames)
            raw += "中🚀".repeat(10)
            active = false
            runtime.flush()
            assertEquals(beginning, shown.text)
            repeat(4) { runtime.frame(25L) }
            assertEquals(beginning + "中🚀".repeat(5), shown.text)
            assertFalse(shown.settled)
            repeat(4) { runtime.frame(25L) }
            assertEquals(raw, shown.text)
            assertTrue(shown.settled)
            assertFalse(runtime.awaitsFrames)
        }
    }

    @Test
    fun longTailKeepsPlayingPastSixHundredMillisWithoutJumpingToTheEnd() = runTest {
        val raw = "尾".repeat(400)
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                shown = rememberTypewriterStreamingText(raw, false, "session", 350, initialText = "", workActive = true).value
            }
            assertEquals("", shown.text)
            repeat(24) { runtime.frame(25L) }
            assertEquals(210, shown.text.length)
            assertFalse(shown.settled)
            repeat(22) { runtime.frame(25L) }
            assertEquals(raw, shown.text)
            assertTrue(shown.settled)
        }
    }

    @Test
    fun playbackFramesOnlyRecomposeTheTextReaderAndNotItsOwner() = runTest {
        val raw = "字🚀".repeat(500)
        var ownerCompositions = 0
        var leafCompositions = 0
        var shown = TypewriterText("", false)
        Runtime(this).use { runtime ->
            runtime.setContent {
                val state = rememberTypewriterStreamingText(raw, true, "local", 350, initialText = "", workActive = true)
                SideEffect { ownerCompositions++ }
                TypewriterTextProbe(state) { leafCompositions++; shown = it }
            }
            val initialOwner = ownerCompositions
            repeat(40) { runtime.frame(25L) }
            assertTrue(typewriterCodePointCount(shown.text) in 349..351)
            assertTrue(leafCompositions >= 20)
            assertEquals("The playback owner must not read or invalidate on every character", initialOwner, ownerCompositions)
            assertEquals(raw, shown.source)
        }
    }

    @Test
    fun listStructureOnlyTraversesHistoryWhenTheNextChunkBecomesVisible() = runTest {
        val raw = "甲".repeat(80) + "乙".repeat(80)
        val message = AgentUIMessage(id = "answer", role = MessageRole.ASSISTANT, content = raw, timestamp = 1)
        val first = ChatRenderItem(message, "answer#chunk0", "assistant-chunk", slice = raw.take(80),
            sliceStart = 0, isChunkHeader = true, isChunkFooter = false)
        val second = first.copy(key = "answer#chunk1", slice = raw.drop(80), sliceStart = 80,
            isChunkHeader = false, isChunkFooter = true)
        val history = List(1_000) { index ->
            val user = AgentUIMessage(id = "u$index", role = MessageRole.USER, content = "history", timestamp = index.toLong())
            ChatRenderItem(user, user.id, MessageRole.USER.name)
        }
        val allItems = history + listOf(first, second)
        var historyReads = 0
        val items = object : AbstractList<ChatRenderItem>() {
            override val size: Int get() = allItems.size
            override fun get(index: Int): ChatRenderItem { historyReads++; return allItems[index] }
        }
        val text = mutableStateOf(TypewriterText("", false, raw))
        val plan = TypewriterContentPlan(listOf(first, second), raw)
        val playback = mapOf(message.id to (plan to text))
        var ownerCompositions = 0
        var stableCompositions = 0
        var tailCompositions = 0
        var visible: List<ChatRenderItem> = emptyList()
        var stable: ChatRenderItem? = null
        var tail: ChatRenderItem? = null
        Runtime(this).use { runtime ->
            runtime.setContent {
                val projected by rememberTypewriterItemProjection(items, playback)
                SideEffect { ownerCompositions++; visible = projected }
                TypewriterChunkProbe(first, plan, text) { stableCompositions++; stable = it }
                if (second in projected) TypewriterChunkProbe(second, plan, text) { tailCompositions++; tail = it }
            }
            assertEquals(history + first, visible)
            val initialOwners = ownerCompositions
            historyReads = 0
            for (length in 1..80) {
                text.value = TypewriterText(raw.take(length), false, raw)
                runtime.flush()
            }
            assertEquals(0, historyReads)
            assertEquals(initialOwners, ownerCompositions)
            assertEquals(raw.take(80), stable!!.slice)
            assertEquals(0, tailCompositions)
            text.value = TypewriterText(raw.take(81), false, raw)
            runtime.flush()
            assertEquals(allItems, visible)
            assertTrue(historyReads >= allItems.size)
            assertEquals("乙", tail!!.slice)
            historyReads = 0
            val stableAtBoundary = stableCompositions
            val ownerAtBoundary = ownerCompositions
            for (length in 82..raw.length) {
                text.value = TypewriterText(raw.take(length), length == raw.length, raw)
                runtime.flush()
            }
            assertEquals(0, historyReads)
            assertEquals(ownerAtBoundary, ownerCompositions)
            assertEquals(stableAtBoundary, stableCompositions)
            assertTrue(tailCompositions >= 20)
            assertEquals(raw.drop(80), tail!!.slice)
        }
    }

    @Test
    fun completionDoesNotBecomeTrueForThePreviousSourceWhenTheNextDeltaArrives() = runTest {
        var raw by mutableStateOf("abc")
        lateinit var state: State<TypewriterText>
        Runtime(this).use { runtime ->
            runtime.setContent {
                state = rememberTypewriterStreamingText(raw, true, "source", 350, initialText = "", workActive = true)
            }
            repeat(8) { runtime.frame(25L) }
            assertTrue(state.value.settled)
            assertEquals(raw, state.value.source)
            raw += "more text"
            runtime.flush()
            assertEquals(raw, state.value.source)
            assertEquals("abc", state.value.text)
            assertFalse(state.value.settled)
            raw = "different response"
            runtime.flush()
            runtime.frame(25L)
            assertEquals(raw, state.value.source)
            assertTrue(raw.startsWith(state.value.text))
            assertFalse(state.value.text.startsWith("abc"))
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
                        key(session, entry) { shown = rememberTypewriterStreamingText(raw, true, session, initialText = "").value }
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

@Composable
private fun TypewriterTextProbe(state: State<TypewriterText>, onFrame: (TypewriterText) -> Unit) {
    val value = state.value
    SideEffect { onFrame(value) }
}

@Composable
private fun TypewriterChunkProbe(
    row: ChatRenderItem,
    plan: TypewriterContentPlan,
    text: State<TypewriterText>,
    onFrame: (ChatRenderItem?) -> Unit,
) {
    val state = remember(row, plan, text) {
        derivedStateOf(structuralEqualityPolicy()) { plan.render(row, text.value) }
    }
    val rendered = state.value
    SideEffect { onFrame(rendered) }
}
