package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningScrollStateStoreTest {
    private class Applier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun sameSessionAndMessageReuseScrollPosition() {
        val store = ReasoningScrollStateStore()
        val first = store.get("session", "message")
        first.requestScrollToItem(7, 23)
        val returned = store.get("session", "message")
        assertSame(first, returned)
        assertEquals(7, returned.firstVisibleItemIndex)
        assertEquals(23, returned.firstVisibleItemScrollOffset)
        assertEquals(1, store.size)
    }

    @Test
    fun identicalMessageKeysInOtherSessionsHaveIndependentStates() {
        val store = ReasoningScrollStateStore()
        val first = store.get("one", "message")
        first.requestScrollToItem(5, 9)
        val other = store.get("two", "message")
        assertNotSame(first, other)
        assertEquals(0, other.firstVisibleItemIndex)
        assertEquals(0, other.firstVisibleItemScrollOffset)
        assertEquals(2, store.size)
    }

    @Test
    fun handoffMovesTheStreamStateBeforeAHistoryMessageRequestsIt() {
        val store = ReasoningScrollStateStore()
        val stream = store.get("one", "reasoning:stream:one")
        stream.requestScrollToItem(8, 19)
        store.retain("one", "message", stream)
        store.remove("one", "reasoning:stream:one")
        assertSame(stream, store.get("one", "message"))
        assertEquals(8, store.get("one", "message").firstVisibleItemIndex)
        assertEquals(19, store.get("one", "message").firstVisibleItemScrollOffset)
        assertEquals(1, store.size)
        assertNotSame(stream, store.get("one", "reasoning:stream:one"))
    }

    @Test
    fun commitFactsWithNoExistingStreamDoNotCreateWindowsOrEvictReadingPositions() {
        val store = ReasoningScrollStateStore(maxEntries = 2)
        val first = store.get("one", "history-one")
        val second = store.get("two", "history-two")
        first.requestScrollToItem(3, 11)
        repeat(1_000) { index ->
            store.moveIfPresent("session-$index", "stream-$index", "message-$index")
        }
        assertEquals(2, store.size)
        assertSame(first, store.get("one", "history-one"))
        assertSame(second, store.get("two", "history-two"))
        assertEquals(3, first.firstVisibleItemIndex)
        assertEquals(11, first.firstVisibleItemScrollOffset)
        val empty = ReasoningScrollStateStore()
        empty.moveIfPresent("one", "stream", "message")
        assertEquals(0, empty.size)
    }

    @Test
    fun movingAnExistingStreamPreservesIdentityPositionAndOtherSessions() {
        val store = ReasoningScrollStateStore(maxEntries = 2)
        val stream = store.get("one", "stream")
        stream.requestScrollToItem(8, 19)
        val other = store.get("two", "history")
        store.moveIfPresent("one", "stream", "history")
        assertEquals(2, store.size)
        assertSame(stream, store.get("one", "history"))
        assertSame(other, store.get("two", "history"))
        assertEquals(8, stream.firstVisibleItemIndex)
        assertEquals(19, stream.firstVisibleItemScrollOffset)
        store.moveIfPresent("one", "stream", "missing")
        assertEquals(2, store.size)
        store.moveIfPresent("one", "history", "history")
        assertSame(stream, store.get("one", "history"))
        assertEquals(2, store.size)
    }

    @Test
    fun retainingAHistoryStateDoesNotChangeOtherSessions() {
        val store = ReasoningScrollStateStore()
        val other = store.get("two", "history")
        val history = newReasoningScrollState()
        store.retain("one", "history", history)
        assertSame(history, store.get("one", "history"))
        assertSame(other, store.get("two", "history"))
        assertEquals(2, store.size)
    }

    @Test
    fun handoffToTheSameKeyDoesNotDiscardItsState() {
        val store = ReasoningScrollStateStore()
        val state = store.get("one", "message")
        store.retain("one", "message", state)
        assertSame(state, store.get("one", "message"))
        assertEquals(1, store.size)
    }

    @Test
    fun deletingSessionOnlyRemovesItsEntries() {
        val store = ReasoningScrollStateStore()
        store.get("one", "a")
        store.get("one", "b")
        val other = store.get("two", "a")
        store.removeSession("one")
        assertEquals(1, store.size)
        assertSame(other, store.get("two", "a"))
    }

    @Test
    fun entriesAreBoundedAndRecentReadsProtectTheirState() {
        val store = ReasoningScrollStateStore(maxEntries = 2)
        val first = store.get("one", "a")
        val evicted = store.get("one", "b")
        assertSame(first, store.get("one", "a"))
        store.get("one", "c")
        assertEquals(2, store.size)
        assertSame(first, store.get("one", "a"))
        assertNotSame(evicted, store.get("one", "b"))
        assertEquals(2, store.size)
    }

    @Test
    fun defaultStoreNeverRetainsMoreThanThirtyTwoWindows() {
        val store = ReasoningScrollStateStore()
        repeat(1_000) {
            store.get("session-$it", "message-$it")
            assertTrue(store.size <= 32)
        }
        assertEquals(32, store.size)
    }

    @Test
    fun activeCompositionKeepsItsStateAfterCacheEvictionAndTextUpdates() = runTest {
        val store = ReasoningScrollStateStore(maxEntries = 1)
        val message = mutableStateOf("a")
        val text = mutableStateOf("initial")
        val states = mutableListOf<LazyListState>()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(Applier(), recomposer)
        var frame = 1_000_000L
        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    clock.sendFrame(frame++)
                    runCurrent()
                }
            }
        }
        try {
            composition.setContent {
                text.value
                states += rememberReasoningScrollState(store, "one", message.value)
            }
            flush()
            val active = states.last()
            active.requestScrollToItem(6, 17)
            store.get("one", "b")
            text.value = "more streaming text"
            flush()
            assertSame(active, states.last())
            assertEquals(6, states.last().firstVisibleItemIndex)
            assertEquals(17, states.last().firstVisibleItemScrollOffset)
            message.value = "b"
            flush()
            assertSame(store.get("one", "b"), states.last())
            assertNotSame(active, states.last())
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }

    @Test
    fun pendingPlaybacksKeepIndependentStatesWhileHistoryBindingIsDelayed() = runTest {
        val store = ReasoningScrollStateStore()
        val boundMessage = mutableStateOf<String?>(null)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(Applier(), recomposer)
        var first: LazyListState? = null
        var second: LazyListState? = null
        var frame = 1_000_000L
        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    clock.sendFrame(frame++)
                    runCurrent()
                }
            }
        }
        try {
            composition.setContent {
                first = rememberPlaybackReasoningScrollState(store, "session", "stream:1", boundMessage.value)
                second = rememberPlaybackReasoningScrollState(store, "session", "stream:2", null)
            }
            flush()
            val old = first!!
            val next = second!!
            assertNotSame(old, next)
            old.requestScrollToItem(9, 21)
            next.requestScrollToItem(2, 7)
            boundMessage.value = "history:1"
            flush()
            assertSame(old, first)
            assertSame(next, second)
            assertSame(old, store.get("session", "history:1"))
            assertEquals(9, first!!.firstVisibleItemIndex)
            assertEquals(2, second!!.firstVisibleItemIndex)
            assertNotSame(old, store.get("session", "reasoning:playback:stream:1"))
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }

    @Test
    fun reusingAPendingPlaybackNumberAfterOwnerDisposalCannotReuseAnOlderTurnPosition() = runTest {
        val store = ReasoningScrollStateStore()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var state: LazyListState? = null
        val first = Composition(Applier(), recomposer)
        val second = Composition(Applier(), recomposer)
        val content: @Composable () -> Unit = {
            state = rememberPlaybackReasoningScrollState(store, "session", "stream:0", null)
        }
        try {
            first.setContent(content)
            runCurrent()
            val original = state!!
            original.requestScrollToItem(8, 31)
            assertEquals(0, store.size)
            first.dispose()
            second.setContent(content)
            runCurrent()
            assertNotSame(original, state)
            assertEquals(0, state!!.firstVisibleItemIndex)
            assertEquals(0, state!!.firstVisibleItemScrollOffset)
            assertEquals(0, store.size)
        } finally {
            first.dispose()
            second.dispose()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }

    @Test
    fun disposeAndRecomposeRetrieveTheSameHostOwnedState() = runTest {
        val store = ReasoningScrollStateStore()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var state: LazyListState? = null
        val content: @Composable () -> Unit = { state = rememberReasoningScrollState(store, "session", "message") }
        val first = Composition(Applier(), recomposer)
        val second = Composition(Applier(), recomposer)
        try {
            first.setContent(content)
            runCurrent()
            val original = state!!
            original.requestScrollToItem(4, 11)
            first.dispose()
            second.setContent(content)
            runCurrent()
            assertSame(original, state)
            assertEquals(4, state!!.firstVisibleItemIndex)
            assertEquals(11, state!!.firstVisibleItemScrollOffset)
        } finally {
            first.dispose()
            second.dispose()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }
}
