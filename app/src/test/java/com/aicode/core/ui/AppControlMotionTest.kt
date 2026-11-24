package com.aicode.core.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.AIEditorTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import java.time.Duration
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AppControlMotionTest {
    @Test(timeout = 30_000)
    fun switchRapidReversalSettlesAtTheLatestValueWithoutCallingBack() {
        var checked by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        render(content = {
            AppSwitch(checked, { changes += it }, Modifier.testTag("switch"))
        }) {
            assertEquals(Role.Switch, node("switch").config[SemanticsProperties.Role])
            assertEquals(2f, thumbX("switch"), 0.01f)
            checked = true
            pump(4)
            assertTrue(thumbX("switch") in 3f..19f)
            checked = false
            pump(2)
            checked = true
            pump(16)
            assertEquals(20f, thumbX("switch"), 0.01f)
            assertEquals(ToggleableState.On, node("switch").config[SemanticsProperties.ToggleableState])
            checked = false
            pump(16)
            assertEquals(2f, thumbX("switch"), 0.01f)
            assertTrue(changes.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun zeroDurationScaleMovesTheActualSwitchWithoutWaitingForItsTween() {
        var checked by mutableStateOf(false)
        render(durationScale = 0f, content = {
            AppSwitch(checked, { checked = it }, Modifier.testTag("switch"))
        }) {
            click(node("switch"))
            pump(4)
            assertTrue(checked)
            assertEquals(20f, thumbX("switch"), 0.01f)
            click(node("switch"))
            pump(4)
            assertFalse(checked)
            assertEquals(2f, thumbX("switch"), 0.01f)
        }
    }

    @Test(timeout = 30_000)
    fun switchTouchCancellationAndDisablingDuringPressNeverToggleIt() {
        var enabled by mutableStateOf(true)
        var checked by mutableStateOf(false)
        val source = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        val changes = mutableListOf<Boolean>()
        render(content = {
            LaunchedEffect(source) { source.interactions.collect { interactions += it } }
            AppSwitch(
                checked = checked,
                onCheckedChange = { checked = it; changes += it },
                modifier = Modifier.testTag("switch"),
                enabled = enabled,
                interactionSource = source
            )
        }) {
            val center = node("switch").boundsInRoot.center
            touch(MotionEvent.ACTION_DOWN, center)
            awaitUi { interactions.filterIsInstance<PressInteraction.Press>().size == 1 }
            touch(MotionEvent.ACTION_CANCEL, center)
            awaitUi { interactions.filterIsInstance<PressInteraction.Cancel>().size == 1 }
            assertTrue(changes.isEmpty())

            touch(MotionEvent.ACTION_DOWN, center)
            awaitUi { interactions.filterIsInstance<PressInteraction.Press>().size == 2 }
            enabled = false
            awaitUi { node("switch").config.contains(SemanticsProperties.Disabled) }
            awaitUi { interactions.filterIsInstance<PressInteraction.Cancel>().size == 2 }
            touch(MotionEvent.ACTION_UP, center)
            touch(MotionEvent.ACTION_DOWN, center)
            touch(MotionEvent.ACTION_UP, center)
            pump(4)
            assertTrue(changes.isEmpty())
            assertEquals(2, interactions.filterIsInstance<PressInteraction.Press>().size)
            assertEquals(2f, thumbX("switch"), 0.01f)

            enabled = true
            awaitUi { !node("switch").config.contains(SemanticsProperties.Disabled) }
            click(node("switch"))
            pump(16)
            assertEquals(listOf(true), changes)
            assertEquals(20f, thumbX("switch"), 0.01f)
        }
    }

    @Test(timeout = 30_000)
    fun segmentedTabsKeepLatestSelectionAndRemoveClickActionsWhenDisabled() {
        var selected by mutableIntStateOf(0)
        var enabled by mutableStateOf(true)
        val selections = mutableListOf<Int>()
        render(content = {
            SegmentedTabs(selected, listOf("Local", "Remote", "Other"), {
                selected = it
                selections += it
            }, Modifier.testTag("segments"), enabled)
        }) {
            val initialX = thumbX("segments")
            selected = 2
            pump(3)
            selected = 1
            pump(40)
            assertEquals(initialX + (400f - 6f) / 3f, thumbX("segments"), 1f)
            assertTrue(selections.isEmpty())
            clickText("Other")
            pump(40)
            assertEquals(listOf(2), selections)
            assertEquals(initialX + (400f - 6f) * 2f / 3f, thumbX("segments"), 1f)
            enabled = false
            pump(4)
            assertTrue(nodes(merged = true).none { it.config.contains(SemanticsActions.OnClick) })
            assertEquals(2, selected)
            assertEquals(listOf(2), selections)
        }
    }

    @Test(timeout = 30_000)
    fun indexedFloatingTabsKeepAllClickTargetsDuringRapidSelection() {
        var selected by mutableIntStateOf(0)
        val selections = mutableListOf<Int>()
        render(content = {
            FloatingTabBar(selected, { selected = it; selections += it }, tabItems, Color.White)
        }) {
            clickText("Files")
            pump(2)
            clickText("Chat")
            pump(2)
            clickText("Terminal")
            pump(40)
            assertEquals(listOf(1, 0, 2), selections)
            assertEquals(2, selected)
            assertEquals(3, nodes(merged = true).count { it.config.contains(SemanticsActions.OnClick) })
        }
    }

    @Test(timeout = 30_000)
    fun pagerFloatingTabsReverseAnInFlightClickAndSettleOnTheLatestPage() {
        lateinit var pager: PagerState
        render(content = {
            val state = rememberPagerState(pageCount = { tabItems.size })
            SideEffect { pager = state }
            Column {
                HorizontalPager(state = state, modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    Box(Modifier.fillMaxSize())
                }
                FloatingTabBar(state, tabItems, Color.White)
            }
        }) {
            clickText("Terminal")
            pump(3)
            clickText("Files")
            awaitUi { pager.currentPage == 1 && !pager.isScrollInProgress }
            assertEquals(0f, pager.currentPageOffsetFraction, 0.001f)
            clickText("Chat")
            awaitUi { pager.currentPage == 0 && !pager.isScrollInProgress }
            assertEquals(3, pager.pageCount)
            assertEquals(3, nodes(merged = true).count { it.config.contains(SemanticsActions.OnClick) })
        }
    }

    private val tabItems = listOf("Chat", "Files", "Terminal").map { FloatingTabItem(FeatherIcons.ChevronDown, it) }

    private inner class ControlUi(private val view: ComposeView, private val advanceFrame: () -> Unit) {
        private var downTime = 0L
        private val root: ViewRootForTest get() = view.getChildAt(0) as ViewRootForTest

        fun pump(count: Int = 1) = repeat(count) { advanceFrame() }
        fun nodes(merged: Boolean = false): List<SemanticsNode> =
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
        fun node(tag: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun thumbX(tag: String): Float {
            val track = node(tag).layoutInfo
            val thumb = layoutChildren(track).first()
            return thumb.coordinates.localToRoot(Offset.Zero).x - track.coordinates.localToRoot(Offset.Zero).x
        }
        fun click(node: SemanticsNode) {
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        }
        fun clickText(text: String) = click(nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        })
        fun awaitUi(condition: () -> Boolean) {
            repeat(120) {
                pump()
                if (condition()) return
            }
            assertTrue("Controls must settle to the latest requested state", condition())
        }
        fun touch(action: Int, position: Offset) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = position.x
                y = position.y
                pressure = 1f
                size = 1f
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                (root as View).dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            pump()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun layoutChildren(info: LayoutInfo): List<LayoutInfo> =
        info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>

    private fun render(
        durationScale: Float = 1f,
        content: @Composable () -> Unit,
        inspect: ControlUi.() -> Unit
    ) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val scale = object : MotionDurationScale { override val scaleFactor = durationScale }
        val recomposer = Recomposer(coroutineContext + clock + scale)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock + scale) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var time = 0L
        val ui = ControlUi(view) {
            looper.idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            runCurrent()
            Snapshot.sendApplyNotifications()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as? ViewRootForTest)?.measureAndLayoutForTest()
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            ui.pump(12)
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
