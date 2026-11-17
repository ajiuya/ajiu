package com.aicode.feature.agent.presentation.preview

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.core.theme.AIEditorTheme
import com.aicode.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE, qualifiers = "w400dp-h1000dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ToolDetailGestureTest {
    @Test(timeout = 30_000)
    fun downwardDragFromTheOutputAtItsTopClosesTheDetail() = dragDetail(fromBody = true)

    @Test(timeout = 30_000)
    fun downwardDragFromTheHeaderClosesTheDetail() = dragDetail(fromBody = false)

    @Test(timeout = 30_000)
    fun touchDriverMovesAPlainDraggableAndDeliversRelease() = dragDetail(fromBody = true, plainControl = true)

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun longOutputScrollsNormallyAndOnlyHandsDownwardDragToTheSheetAtItsTop() =
        dragDetail(fromBody = true, longOutput = true)

    @Test(timeout = 30_000)
    fun holdingTheDetailKeepsItsOutputFrameThenPublishesTheLatestOutputOnRelease() =
        dragDetail(fromBody = true, pauseUpdates = true)

    @Test(timeout = 30_000)
    fun anEmptyCompletedToolShowsTheRealNoOutputPlaceholder() =
        dragDetail(fromBody = true, emptyOutputOnly = true)

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun largeOutputUsesVisibleBlocksAndDismissalNeverPublishesAnUpdateAfterRelease() =
        dragDetail(fromBody = true, longOutput = true, releaseUpdates = true)

    @Test(timeout = 30_000)
    @Config(qualifiers = "w800dp-h1000dp")
    fun wideDialogLoadsItsOutputWithoutWaitingForBottomSheetAnimation() =
        dragDetail(fromBody = true, wideDialogOnly = true)

    private fun dragDetail(
        fromBody: Boolean,
        plainControl: Boolean = false,
        longOutput: Boolean = false,
        pauseUpdates: Boolean = false,
        wideDialogOnly: Boolean = false,
        releaseUpdates: Boolean = false,
        emptyOutputOnly: Boolean = false,
    ) = runTest(timeout = 20.seconds) {
        val output = if (emptyOutputOnly) "" else if (longOutput) List(if (releaseUpdates) 800 else 120) { "Line $it: public tool output" }.joinToString("\n") else "public output"
        val target = ToolPreviewTarget("call", "Bash", if (emptyOutputOnly) "{}" else """{"command":"pwd"}""", output,
            ToolPreviewStage.COMPLETED, actionTitle = "Inspect workspace", sourceSessionId = "session")
        var currentTarget by mutableStateOf(target)
        val state = ToolPreviewHostState("session").apply { openDetails(target.id) }
        val cache = ToolPreviewBitmapCache()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val workDispatcher = StandardTestDispatcher(testScheduler)
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val mainLooper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        var controlDelta = 0f
        var controlReleased = false
        var dialogRoot: ViewRootForTest? = null
        fun composeRoot(candidate: View): ViewRootForTest? {
            if (candidate is ViewRootForTest) return candidate
            if (candidate is ViewGroup) repeat(candidate.childCount) { index ->
                composeRoot(candidate.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun pump() {
            mainLooper.idleFor(Duration.ofMillis(16))
            Snapshot.sendApplyNotifications()
            runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            composeRoot(view)?.measureAndLayoutForTest()
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { window ->
                window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, window.measuredWidth, window.measuredHeight)
                dialogRoot = composeRoot(window)
                dialogRoot?.measureAndLayoutForTest()
            }
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
            Snapshot.sendApplyNotifications()
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        if (plainControl) {
                            Box(Modifier.fillMaxSize().draggable(rememberDraggableState { controlDelta += it },
                                Orientation.Vertical, onDragStopped = { controlReleased = true }))
                        } else if (state.detailOpen) ToolDetailSheet(currentTarget, listOf(currentTarget), 0, state,
                            browserManager = null, cache = cache, readFileBytes = null,
                            resolveLocalImage = null, onOpenBrowser = null, workDispatcher = workDispatcher)
                    }
                }
            }
            activity.setContentView(view)
            repeat(90) { pump() }
            assertTrue(state.detailOpen)
            if (!plainControl) assertTrue("The real detail must own an independent dialog", dialogRoot != null)
            val root = if (plainControl) requireNotNull(composeRoot(view)) else requireNotNull(dialogRoot)
            val window = root as View
            assertTrue("The actual Compose root must be attached", window.isAttachedToWindow)
            fun containsText(text: String): Boolean = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text.contains(text) } == true
            }
            val visibleOutput = if (emptyOutputOnly) activity.getString(R.string.tool_preview_no_output) else output.lineSequence().first()
            if (!plainControl) {
                var attempts = 0
                while (!containsText(visibleOutput) && attempts++ < 180) pump()
                assertTrue("The real asynchronous output must be ready before a gesture starts", containsText(visibleOutput))
                root.measureAndLayoutForTest()
            }
            val body = if (plainControl) null else root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            val bodyTop = body?.boundsInRoot?.top ?: 0f
            val bodyHeight = body?.boundsInRoot?.height?.toInt() ?: 1000
            assertTrue("The actual output viewport must be visible: $bodyTop + $bodyHeight", bodyTop >= 0f && bodyTop + bodyHeight <= 1000f)
            if (body != null) assertTrue("The output starts at its scroll top", body.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.value() == 0f)
            val startY = if (fromBody) bodyTop + 80f else bodyTop - 45f
            if (wideDialogOnly || emptyOutputOnly) {
                assertTrue("A wide dialog must render the output", containsText(visibleOutput))
                return@runTest
            }
            var downTime = SystemClock.uptimeMillis()
            var moveEventsDelivered = 0
            fun touch(action: Int, y: Float) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { x = 200f; this.y = y; pressure = 1f; size = 1f }
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                    arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    assertEquals(y, event.y, 0f)
                    assertEquals(200f, event.x, 0f)
                    assertEquals(y, event.rawY, 0f)
                    assertEquals(200f, event.rawX, 0f)
                    assertEquals(MotionEvent.TOOL_TYPE_FINGER, event.getToolType(0))
                    val delivered = window.dispatchTouchEvent(event)
                    if (action == MotionEvent.ACTION_DOWN) assertTrue("The Compose root must accept the initial touch", delivered)
                    if (action == MotionEvent.ACTION_MOVE && delivered) moveEventsDelivered++
                } finally { event.recycle() }
                pump()
            }
            if (pauseUpdates) {
                assertTrue(containsText(output))
                touch(MotionEvent.ACTION_DOWN, startY)
                currentTarget = target.copy(content = "latest output while touching", stage = ToolPreviewStage.FAILED)
                repeat(30) { pump() }
                assertEquals("latest output while touching", currentTarget.content)
                assertTrue("Touching keeps the visible frame, not the live tool input", containsText(output))
                assertFalse(containsText("latest output while touching"))
                touch(MotionEvent.ACTION_UP, startY)
                repeat(90) { pump() }
                assertTrue(state.detailOpen)
                assertTrue("Release must publish the latest output", containsText("latest output while touching"))
                assertFalse(containsText(output))
                return@runTest
            }
            if (longOutput) {
                val range = requireNotNull(body).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!
                val scrollBy = requireNotNull(body.config.getOrNull(SemanticsActions.ScrollBy)?.action)
                assertTrue("The real output must be larger than its viewport; range=${range.maxValue()}, viewport=$bodyHeight", range.maxValue() > 1f)
                assertFalse("Long output must not eagerly compose its last block", containsText("Line ${if (releaseUpdates) 799 else 119}:"))
                assertTrue(scrollBy(0f, 400f))
                repeat(30) { pump() }
                val scrolledValue = range.value()
                assertTrue(scrolledValue > 0f)
                downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, startY)
                repeat(10) { index -> touch(MotionEvent.ACTION_MOVE, startY + (index + 1) * 10f) }
                repeat(15) { pump() }
                touch(MotionEvent.ACTION_UP, startY + 100f)
                repeat(60) { pump() }
                assertTrue("A drag within scrolled output must not close the detail", state.detailOpen)
                assertTrue("The output, not the sheet, must consume the middle drag", range.value() > 0f && range.value() < scrolledValue)
                assertTrue(scrollBy(0f, -100_000f))
                repeat(30) { pump() }
                assertEquals(0f, range.value(), 0f)
            }
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, startY)
            repeat(12) { index -> touch(MotionEvent.ACTION_MOVE, startY + (index + 1) * 25f) }
            val draggedTop = body?.layoutInfo?.coordinates?.takeIf { it.isAttached }?.localToRoot(Offset.Zero)?.y
            if (releaseUpdates) currentTarget = target.copy(content = "late output during dismissal", stage = ToolPreviewStage.FAILED)
            touch(MotionEvent.ACTION_UP, startY + 300f)
            var closeFrames = 0
            while ((if (plainControl) !controlReleased else state.detailOpen) && closeFrames++ < 120) {
                if (releaseUpdates) assertFalse("Release must not restart output layout during dismissal", containsText("late output during dismissal"))
                pump()
            }
            if (releaseUpdates) assertTrue("A settled release must finish its close animation without a long pause: $closeFrames frames", closeFrames <= 40)
            if (plainControl) {
                assertTrue("The touch driver must move a plain draggable; delta=$controlDelta; deliveredMoves=$moveEventsDelivered", controlDelta > 250f)
                assertTrue("The touch driver must deliver release", controlReleased)
            } else {
                assertFalse("A downward drag must close the real detail; fromBody=$fromBody, original top=$bodyTop, dragged top=$draggedTop, height=$bodyHeight, deliveredMoves=$moveEventsDelivered", state.detailOpen)
            }
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
