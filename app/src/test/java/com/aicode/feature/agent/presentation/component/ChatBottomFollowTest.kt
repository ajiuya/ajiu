package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.MutatePriority
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.ChatMessagesState
import com.aicode.feature.agent.presentation.ChatNotificationTimelineEvent
import com.aicode.feature.agent.presentation.MessageRole
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
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
    fun acceptedNotificationMovesWithActiveTailAndHistoryStaysDetached() {
        var tailPx by mutableIntStateOf(40)
        var following by mutableStateOf(true)
        withList(effect = { state ->
            LaunchedEffect(following, tailPx) { if (following) state.followChatBottom() }
        }, rows = {
            items(12, key = { "history-$it" }) { PixelRow(40) }
            item(key = "notification") { PixelRow(24) }
            item(key = "__active__") { PixelRow(tailPx) }
        }) {
            state.scrollToItem(info.totalItemsCount - 1)
            settle()
            val notice = { info.visibleItemsInfo.single { it.key == "notification" }.offset }
            val initialOffset = notice()

            tailPx += 12
            settle()
            follow()
            assertEquals(initialOffset - 12, notice())
            assertAtBottom("__active__")

            following = false
            state.scrollToItem(12)
            settle()
            val detachedOffset = notice()
            tailPx += 20
            settle()
            assertEquals(detachedOffset, notice())
        }
    }

    @Test
    fun projectedNotificationsStayAboveNewToolWhileFollowingTheCollapsedGroup() {
        val parent = "parent"
        val user = AgentUIMessage("request", MessageRole.USER, "Review the changes", timestamp = 15L)
        val activeTurn = turnKeyOf(user.id)
        val dispatch = AgentUIMessage(
            id = "dispatch", role = MessageRole.TOOL, timestamp = 20L, toolName = "task",
            content = """{"status":"success","data":{"id":"child","state":"running"}}""",
            toolArgs = """{"prompt":"review","description":"Review worker"}""",
        )
        val history = List(12) { index ->
            val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
            AgentUIMessage("history-$index", role, "history", timestamp = index.toLong())
        }
        val messages = history + listOf(user, dispatch)
        val newTool = AgentUIMessage("new-tool", MessageRole.TOOL, "result", timestamp = 60L, toolName = "readFile")
        val notifications = listOf(
            "background" to AgentNotificationKind.BACKGROUND_TASK,
            "agent" to AgentNotificationKind.AGENT_MESSAGE,
            "user" to AgentNotificationKind.USER_MESSAGE,
        ).mapIndexed { index, (id, kind) ->
            val notice = PendingNotification(
                kind = kind, sourceId = id, title = id, outcome = NotificationOutcome.COMPLETED,
                message = "Accepted $id notification", eventId = id,
            )
            ChatNotificationTimelineEvent(
                AgentNotificationTimelineEntry(index + 1L, 30L + index * 10L, notice),
                sourceMessageId = null,
            )
        }
        val noticeKeys = notifications.map { "chat-notification:${it.entry.notification.eventId}" }

        fun project(chatMessages: List<AgentUIMessage>): SubAgentGroupProjection {
            val turnKeys = reconcileTurnKeys(chatMessages, emptyMap(), parent)
            val timeline = projectSubAgentTimeline(chatMessages, setOf("child"), parent)
            val capsules = projectSubAgentTurnCapsules(timeline, turnKeys, parent)
            val base = buildChatItems(
                chatMessages, activeTurnKey = activeTurn, sessionId = parent, turnKeys = turnKeys,
            )
            val withNotices = projectChatNotificationItems(base, chatMessages, notifications, emptyList(), emptyMap())
            val flattened = flattenChatProcesses(withNotices)
            val withCapsules = insertSubAgentTurnItems(flattened, capsules, chatMessages)
            return projectSubAgentGroup(withCapsules, parent, activeTurnKey = activeTurn)
        }

        var projection by mutableStateOf(project(messages))
        val groupKey = projection.footerItems.single().key
        var following by mutableStateOf(true)
        var tailHeight by mutableIntStateOf(24)
        var userScrollGesture by mutableStateOf(false)
        var dimmedWhileFollowing = false
        withList(effect = { state ->
            val scrolling = state.isScrollInProgress
            val dimFloatingLayer = shouldDimChatFloatingLayer(userScrollGesture)
            SideEffect { if (scrolling && dimFloatingLayer) dimmedWhileFollowing = true }
            LaunchedEffect(state) {
                snapshotFlow { if (following) state.layoutInfo else null }.collectLatest { layout ->
                    if (layout != null && following) state.followChatBottom()
                }
            }
        }, rows = {
            items(projection.bodyItems, key = { it.key }, contentType = { it.contentType }) { PixelRow(28) }
            item(key = "__active__", contentType = "tail") { PixelRow(tailHeight) }
            items(projection.footerItems, key = { it.key }, contentType = { it.contentType }) { PixelRow(28) }
        }) {
            fun row(key: String) = info.visibleItemsInfo.single { it.key == key }
            fun assertActiveFooter() {
                assertAtBottom(groupKey)
                assertEquals(SUB_AGENT_GROUP_CONTENT_TYPE, last.contentType)
                val reasoning = row("__active__")
                assertEquals(last.index - 1, reasoning.index)
                assertEquals(last.offset, reasoning.offset + reasoning.size)
            }

            state.scrollToItem(info.totalItemsCount - 1)
            settle()
            assertActiveFooter()
            val initialCount = info.totalItemsCount
            val initialOffsets = noticeKeys.associateWith { row(it).offset }
            dimmedWhileFollowing = false

            projection = project(messages + newTool)
            settle()
            assertEquals(initialCount + 1, info.totalItemsCount)
            assertActiveFooter()
            val tool = row(newTool.id)
            noticeKeys.forEach { key ->
                val notice = row(key)
                assertTrue("$key should stay before ${newTool.id}", notice.index < tool.index)
                assertEquals(key, initialOffsets.getValue(key) - tool.size, notice.offset)
            }

            tailHeight += 8
            settle()
            assertActiveFooter()
            val afterGrowth = noticeKeys.associateWith { row(it).offset }
            noticeKeys.forEach { key -> assertEquals(initialOffsets.getValue(key) - tool.size - 8, afterGrowth.getValue(key)) }
            assertFalse("Automatic following must not dim the floating layer", dimmedWhileFollowing)
            userScrollGesture = true
            settle()
            assertTrue(shouldDimChatFloatingLayer(userScrollGesture))
            userScrollGesture = false
            settle()
            assertFalse(shouldDimChatFloatingLayer(userScrollGesture))

            projection = project(messages + newTool)
            settle()
            assertAtBottom(groupKey)
            assertEquals(row("__active__").index + 1, row(groupKey).index)
            assertEquals(listOf(SUB_AGENT_GROUP_CONTENT_TYPE), projection.footerItems.map { it.contentType })
            noticeKeys.forEach { key -> assertTrue(row(key).index < row(groupKey).index) }

            projection = project(messages + newTool)
            settle()
            assertActiveFooter()
            following = false
            state.scrollToItem(2, 11)
            settle()
            val anchor = position
            val anchorOffset = row("history-2").offset
            assertEquals(2 to 11, anchor)
            assertTrue(info.visibleItemsInfo.last().index < info.totalItemsCount - 1)

            projection = project(messages + newTool + newTool.copy(id = "later-tool", timestamp = 70L))
            settle()
            assertEquals(initialCount + 2, info.totalItemsCount)
            assertEquals(anchor, position)
            assertEquals(anchorOffset, row("history-2").offset)

            following = true
            settle()
            assertActiveFooter()
            assertTrue(row(noticeKeys.last()).index < row("later-tool").index)
        }
    }

    @Test
    fun prependedHistoryBeyondComposeKeyRangePreservesTheVisibleRow() {
        val recent = List(20) { historyRow("recent-$it") }
        val older = List(160) { historyRow("older-$it") }
        val owner = Any()
        var page by mutableStateOf(historyPage(recent, 30, owner))
        var dragging by mutableStateOf(false)
        lateinit var pageState: ChatHistoryPageState<SubAgentGroupProjection>
        withList(content = { state ->
            val presentation = rememberChatHistoryPage(state, page, keepPosition = { true },
                scrolling = { dragging || state.isScrollInProgress }, indexOfKey = { projection, key -> projection.lazyIndexOfKey(key) })
            pageState = presentation.state
            val shown = presentation.visible!!
            ObserveChatHistoryLayout(state, presentation.state, shown.request, shown.value)
            Scaffold { _ ->
                PreserveChatHistoryAnchor(state, presentation.state, shown.request, shown.value,
                    { true }, { dragging || state.isScrollInProgress })
                HistoryTestList(state) {
                    items(shown.value.bodyItems, key = { it.key }) { PixelRow(40) }
                    item(key = CHAT_ACTIVE_TAIL_KEY) { PixelRow(0) }
                }
            }
        }) {
            state.scrollToItem(2, 11)
            settle()
            assertEquals("recent-2", info.visibleItemsInfo.single { it.index == position.first }.key)

            page = historyPage(older + recent, 60, owner)
            dragging = true
            assertTrue(pageState.deferLargeHistoryPage(state, page.request, page.value, keepPosition = true))
            settle()
            assertEquals(recent.size + 1, info.totalItemsCount)
            assertEquals("recent-2", info.visibleItemsInfo.single { it.index == position.first }.key)
            state.scrollBy(50f)
            settle()
            val before = position
            val anchor = info.visibleItemsInfo.single { it.index == before.first }
            assertEquals("recent-3", anchor.key)

            dragging = false
            settle()
            val first = info.visibleItemsInfo.single { it.index == position.first }
            assertEquals(older.size + recent.size + 1, info.totalItemsCount)
            assertEquals(anchor.key, first.key)
            assertEquals(anchor.offset, first.offset)
            assertEquals((older.size + before.first) to before.second, position)
            assertTrue(pageState.isLayoutCommitted(page.request, info.totalItemsCount))
        }
    }

    @Test
    fun gestureAfterPageOfferBeforeMeasurementKeepsTheVisibleKey() {
        val recent = List(20) { historyRow("recent-$it") }
        val older = List(160) { historyRow("older-$it") }
        val owner = Any()
        val oldPage = historyPage(recent, 30, owner)
        val nextPage = historyPage(older + recent, 60, owner)
        val pageState = ChatHistoryPageState<SubAgentGroupProjection>()
        withList(rows = {
            items(recent, key = { it.key }) { PixelRow(40) }
            item(key = CHAT_ACTIVE_TAIL_KEY) { PixelRow(0) }
        }) {
            state.scrollToItem(2, 11)
            settle()
            assertEquals(2 to 11, position)
            val savedOffset = state.firstVisibleItemScrollOffset
            pageState.offer(oldPage)
            pageState.reportLayout(
                oldPage.request, info.totalItemsCount, oldPage.value.expectedLazyItemCount,
                ChatHistoryAnchor("recent-2", state.firstVisibleItemIndex, savedOffset), oldPage.value,
            )
            pageState.recordAnchor(ChatHistoryAnchor("recent-2", state.firstVisibleItemIndex, savedOffset))
            pageState.offer(nextPage)
            pageState.requireAnchor("recent-2", 162)
            pageState.markAnchorRequested(nextPage.request)

            val scrollStarted = CompletableDeferred<Unit>()
            val allowScroll = CompletableDeferred<Unit>()
            val activeScroll = launchInBackground {
                state.scroll(MutatePriority.UserInput) {
                    scrollStarted.complete(Unit)
                    allowScroll.await()
                    scrollBy(-1f)
                    awaitCancellation()
                }
            }
            runCurrentTasks()
            scrollStarted.await()
            assertTrue(state.isScrollInProgress)
            state.requestScrollToItem(162, savedOffset)
            assertTrue(pageState.deferLargeHistoryPage(
                state, nextPage.request, nextPage.value, keepPosition = true, cancelPendingScroll = true,
            ))
            allowScroll.complete(Unit)
            runCurrentTasks()
            settle()
            assertEquals("recent-2", info.visibleItemsInfo.single { it.index == position.first }.key)
            assertEquals(savedOffset - 1, position.second)
            assertEquals(recent.size + 1, info.totalItemsCount)
            assertEquals(nextPage.request, pageState.deferredRequest)
            assertFalse(pageState.hasRequestedAnchor(nextPage.request))
            activeScroll.cancelAndJoin()
        }
    }

    @Test
    fun smallHistoryPageAppearsDuringDraggingWithoutWaitingForTheGestureToEnd() {
        val recent = List(20) { historyRow("recent-$it") }
        val owner = Any()
        var page by mutableStateOf(historyPage(recent, 30, owner))
        var dragging by mutableStateOf(false)
        withList(content = { state ->
            val presentation = rememberChatHistoryPage(state, page, keepPosition = { true },
                scrolling = { dragging || state.isScrollInProgress }, indexOfKey = { projection, key -> projection.lazyIndexOfKey(key) })
            val shown = presentation.visible!!
            ObserveChatHistoryLayout(state, presentation.state, shown.request, shown.value)
            Scaffold {
                PreserveChatHistoryAnchor(state, presentation.state, shown.request, shown.value,
                    { true }, { dragging || state.isScrollInProgress })
                HistoryTestList(state) {
                    items(shown.value.bodyItems, key = { it.key }) { PixelRow(40) }
                    item(key = CHAT_ACTIVE_TAIL_KEY) { PixelRow(0) }
                }
            }
        }) {
            state.scrollToItem(2, 11)
            settle()
            dragging = true
            page = historyPage(listOf(historyRow("older-single")) + recent, 31, owner)
            settle()
            assertEquals(recent.size + 2, info.totalItemsCount)
            assertEquals("recent-2", info.visibleItemsInfo.single { it.index == position.first }.key)
        }
    }

    private fun assertSmoothOffsets(offsets: List<Int>) {
        val deltas = offsets.zipWithNext { before, after -> before - after }.filter { it != 0 }
        assertTrue("Anchor offsets: $offsets", deltas.isNotEmpty())
        assertTrue("Anchor offsets: $offsets", deltas.all { it in 1..40 })
    }

    private fun historyRow(key: String) = ChatRenderItem(AgentUIMessage(key, MessageRole.USER, key), key, "user")

    private fun historyPage(rows: List<ChatRenderItem>, limit: Int, owner: Any) = ChatItemsProjection(
        ChatItemsBuildRequest(ChatMessagesState("session", rows.map { it.message }, loaded = true, limit = limit), Any(), owner),
        SubAgentGroupProjection(rows, emptyList(), emptyList(), emptySet(), emptySet()),
    )

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

    private class ListUi(
        val state: LazyListState,
        private val backgroundScope: CoroutineScope,
        private val runCurrentAction: () -> Unit,
        private val settleAction: () -> Unit,
        private val composeAction: () -> Unit,
    ) {
        val info get() = state.layoutInfo
        fun launchInBackground(block: suspend CoroutineScope.() -> Unit): Job = backgroundScope.launch(block = block)
        fun runCurrentTasks() = runCurrentAction()
        fun settle() = settleAction()
        fun composeOnly() = composeAction()
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
    ) = withList(content = { state ->
        effect(state)
        HistoryTestList(state, rows)
    }, inspect = inspect)

    @Composable
    private fun HistoryTestList(state: LazyListState, rows: LazyListScope.() -> Unit) {
        LazyColumn(Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), content = rows)
    }


    private fun withList(
        content: @Composable (LazyListState) -> Unit,
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
        val ui = ListUi(state, backgroundScope, { runCurrent() }, {
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
        }, {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
            }
        })
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent { content(state) }
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
