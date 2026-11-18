package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "hdpi", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ChatBottomFollowTest {
    @Test
    fun visibleFooterMovesByTheMeasuredOverflowOnlyBeyondTwoPixels() {
        var streamPx by mutableIntStateOf(60)
        withList(rows = {
            items(12, key = { it }) { PixelRow(40) }
            item(key = "stream") { PixelRow(streamPx) }
            item(key = "footer") { PixelRow(18) }
        }) {
            state.scrollToItem(info.totalItemsCount - 1)
            settle()
            assertAtBottom("footer")
            val pinned = position

            streamPx += 2
            settle()
            assertEquals(2, overflow)
            follow()
            assertEquals(pinned, position)

            streamPx += 7
            settle()
            val excess = overflow
            val footerTop = last.offset
            assertEquals(9, excess)
            assertTrue(excess < info.afterContentPadding)
            follow()
            assertAtBottom("footer")
            assertEquals(footerTop - excess, last.offset)
        }
    }

    @Test
    fun smallGrowthsInAnOversizedTailKeepTheVisibleRowMountedAndMovingOneWay() {
        var endPx by mutableIntStateOf(40)
        var mounts = 0
        var disposals = 0
        val rowTops = mutableListOf<Int>()
        withList(rows = {
            item(key = "stream") {
                Column {
                    PixelRow(720)
                    DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                    PixelRow(16, Modifier.onGloballyPositioned { rowTops += it.positionInRoot().y.roundToInt() })
                    PixelRow(endPx)
                }
            }
        }) {
            state.scrollBy(last.size.toFloat())
            settle()
            assertAtBottom("stream")
            val startY = rowTops.last()
            assertTrue(startY in 0 until info.viewportSize.height - 16)
            val mountCount = mounts
            val disposalCount = disposals
            rowTops.clear()
            rowTops += startY

            repeat(8) { step ->
                endPx += 3
                settle()
                assertEquals(3, overflow)
                follow()
                assertAtBottom("stream")
                assertEquals(startY - (step + 1) * 3, rowTops.last())
            }
            assertTrue("Visible row tops: $rowTops", rowTops.zipWithNext().all { (a, b) -> a - b in 0..3 })
            assertEquals(mountCount, mounts)
            assertEquals(disposalCount, disposals)
        }
    }

    @Test
    fun callerGateKeepsHistoryInPlaceUntilResumingFindsTheOffscreenFooter() {
        var following by mutableStateOf(false)
        var streamPx by mutableIntStateOf(0)
        withList(effect = { state ->
            LaunchedEffect(following, streamPx) { if (following) state.followChatBottom() }
        }, rows = {
            items(80, key = { it }) { PixelRow(40) }
            item(key = "stream") { PixelRow(streamPx) }
            item(key = "footer") { PixelRow(20) }
        }) {
            state.scrollToItem(info.totalItemsCount - 1)
            settle()
            assertAtBottom("footer")
            state.scrollToItem(5, 11)
            settle()
            val history = position
            assertTrue(info.visibleItemsInfo.last().index < info.totalItemsCount - 1)

            streamPx = 600
            settle()
            assertEquals(history, position)
            following = true
            settle()
            assertAtBottom("footer")
        }
    }

    @Test
    fun shrinkingToAZeroHeightTailAddsNoScrollAfterTheListClampsItself() {
        var streamPx by mutableIntStateOf(80)
        withList(rows = {
            items(12, key = { it }) { PixelRow(40) }
            item(key = "stream") { PixelRow(streamPx) }
        }) {
            state.scrollToItem(info.totalItemsCount - 1)
            settle()
            assertAtBottom("stream")
            val oldPosition = position
            streamPx = 0
            settle()
            assertEquals(0, last.size)
            val clamped = position
            assertTrue(clamped != oldPosition)
            assertTrue(state.canScrollBackward)
            follow()
            assertEquals(clamped, position)
            assertAtBottom("stream")
        }
    }

    @Test
    fun emptyAndUnderfilledListsStayAtTheirStartingPosition() {
        var count by mutableIntStateOf(0)
        withList(rows = { items(count) { PixelRow(32) } }) {
            assertTrue(info.visibleItemsInfo.isEmpty())
            follow()
            assertEquals(0 to 0, position)
            count = 1
            settle()
            assertTrue(overflow < -2)
            assertFalse(state.canScrollForward)
            follow()
            assertEquals(0 to 0, position)
        }
    }

    @Composable
    private fun PixelRow(heightPx: Int, modifier: Modifier = Modifier) {
        Spacer(modifier.fillMaxWidth().height(with(LocalDensity.current) { heightPx.toDp() }))
    }

    private class ListUi(val state: LazyListState, val settle: () -> Unit) {
        val info get() = state.layoutInfo
        val position get() = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        val last get() = info.visibleItemsInfo.single { it.index == info.totalItemsCount - 1 }
        val overflow get() = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)

        fun assertAtBottom(key: String) {
            assertEquals(key, last.key)
            assertTrue("Last item bottom is off by ${overflow}px", overflow in -2..2)
        }

        suspend fun follow() {
            state.followChatBottom()
            settle()
        }
    }

    private fun withList(
        effect: @Composable (LazyListState) -> Unit = {},
        rows: LazyListScope.() -> Unit,
        inspect: suspend ListUi.() -> Unit,
    ) = runTest(timeout = 15.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val state = LazyListState()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val widthPx = 320
        val heightPx = 240
        var frame = 0L
        val ui = ListUi(state) {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
                view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
            }
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                effect(state)
                LazyColumn(Modifier.fillMaxSize(), state = state,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), content = rows)
            }
            activity.setContentView(view, ViewGroup.LayoutParams(widthPx, heightPx))
            ui.settle()
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }
}
