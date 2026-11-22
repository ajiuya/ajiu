package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.preview.ToolPreviewWorkVisibility
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowLegacyBitmap

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    manifest = Config.NONE,
    shadows = [ChatStatusMotionTest.ColorSpaceBitmapShadow::class]
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ChatStatusMotionTest {
    @Test(timeout = 30_000)
    fun capsuleEntranceClipsItsOwnHeightUntilTheVideoCurveCompletes() {
        var progress by mutableStateOf(0f)
        var capsuleHeight = -1
        render(content = {
            CapsuleEntrance(
                progress = progress,
                modifier = Modifier.onGloballyPositioned { capsuleHeight = it.size.height },
            ) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().size(width = 200.dp, height = 100.dp))
            }
        }) {
            assertEquals(0, capsuleHeight)
            progress = 0.5f
            pump(2)
            assertEquals(87, capsuleHeight)
            progress = 1f
            pump(2)
            assertEquals(100, capsuleHeight)
        }
    }

    @Test(timeout = 30_000)
    fun sessionExecutionDotPulsesInItsLayerAndPermissionReplacesItWithAStaticDot() {
        var awaitingPermission by mutableStateOf(false)
        render(content = {
            ChatSessionRow(
                session = session,
                selected = false,
                isExecuting = true,
                awaitingPermission = awaitingPermission,
                onClick = {},
                onLongClick = {}
            )
        }) {
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
            assertText(session.title)
            awaitingPermission = true
            assertNoInfiniteFrames()
            assertText(session.title)
            awaitingPermission = false
            pump(8)
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
        }
    }

    @Test(timeout = 30_000)
    fun inProgressTodoPulsesWithoutPerFrameCompositionOrMeasureAndCompletionStopsIt() {
        var status by mutableStateOf("in_progress")
        render(content = { TodoItemRow(todo.copy(status = status)) }) {
            assertDrawOnlyPulse(minimumAlpha = 0.3f)
            assertText(todo.subject)
            assertText(activity.getString(R.string.todo_in_progress))
            status = "completed"
            assertNoInfiniteFrames()
            assertDescription(activity.getString(R.string.todo_completed))
            assertText(todo.subject)
        }
    }

    @Test(timeout = 30_000)
    fun runningToolPulsesWithoutPerFrameCompositionOrMeasureAndSuccessAndFailureAreStatic() {
        var running by mutableStateOf(true)
        var isError by mutableStateOf(false)
        render(content = { ToolStatusIcon(running, isError, "readFile") }) {
            assertDrawOnlyPulse(minimumAlpha = 0.35f)
            assertDescription(activity.getString(R.string.chat_tool_running))
            running = false
            assertNoInfiniteFrames()
            assertDescription(activity.getString(R.string.chat_tool_succeeded))
            isError = true
            assertNoInfiniteFrames()
            assertDescription(activity.getString(R.string.chat_tool_failed))
        }
    }

    @Test(timeout = 30_000)
    fun hiddenStatusControlsStayMountedButStopPulsesAndRippleUntilVisibleAgain() {
        render(content = { activeStatusControls() }) {
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
            assertTrue(canvas.gradientRects.isNotEmpty())
            visible.value = false
            assertNoInfiniteFrames()
            assertStatusControlsPresent()
            visible.value = true
            pump(8)
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
            assertTrue(canvas.gradientRects.isNotEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun startedLifecycleAndClosedDrawerStopTheActualMountedDecorations() {
        render(content = { activeStatusControls() }) {
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertNoInfiniteFrames()
            assertStatusControlsPresent()
            owner.registry.currentState = Lifecycle.State.RESUMED
            pump(8)
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
            drawerOpen.value = false
            assertNoInfiniteFrames()
            assertStatusControlsPresent()
            drawerOpen.value = true
            pump(8)
            assertDrawOnlyPulse(minimumAlpha = 0.25f)
        }
    }

    @Test(timeout = 30_000)
    fun rippleMovesTheDrawnBandWhileReusingItsShaderWithoutRecompositionOrMeasure() {
        render(content = {
            ComposeCanvas(Modifier.size(180.dp, 30.dp).rippleHighlight()) { drawRect(Color.Black) }
        }) {
            val shader = canvas.gradientRects.single().shader
            val initialChanges = compositionChanges()
            val initialMeasures = measures
            val centers = mutableListOf<Float>()
            repeat(20) {
                pump()
                val band = canvas.gradientRects.single()
                assertSame("Progress must not recreate the cached gradient shader", shader, band.shader)
                assertEquals(90f, band.width, 0.01f)
                centers += band.translationX
                assertTrue("The real Canvas content must still be drawn", canvas.solidRectDraws > 0)
                assertEquals(initialChanges, compositionChanges())
                assertEquals(initialMeasures, measures)
            }
            assertTrue("The highlight must really travel rather than remain static", centers.distinct().size > 10)
            assertTrue(awaitsFrames())
        }
    }

    @Test(timeout = 30_000)
    fun rippleRebuildsItsGradientForSizeOrColorChangesButNotForSubsequentProgress() {
        var width by mutableStateOf(180.dp)
        var highlight by mutableStateOf(Color.White)
        render(content = {
            ComposeCanvas(Modifier.size(width, 30.dp).rippleHighlight(highlight)) { drawRect(Color.Black) }
        }) {
            val original = canvas.gradientRects.single().shader
            width = 240.dp
            pump(6)
            val resized = canvas.gradientRects.single()
            assertEquals(120f, resized.width, 0.01f)
            assertNotSame(original, resized.shader)
            highlight = Color.Cyan
            pump(6)
            val recolored = canvas.gradientRects.single().shader
            assertNotSame(resized.shader, recolored)
            val initialChanges = compositionChanges()
            val initialMeasures = measures
            repeat(12) {
                pump()
                assertSame(recolored, canvas.gradientRects.single().shader)
                assertEquals(initialChanges, compositionChanges())
                assertEquals(initialMeasures, measures)
            }
        }
    }

    private val session = ChatSession("motion-session", "Session for motion", 0L, 0L)
    private val todo = ParsedTodoItem("motion-todo", "Task for motion", "", "in_progress", 0, 0)

    @Composable
    private fun activeStatusControls() {
        ChatSessionRow(session, selected = false, isExecuting = true, onClick = {}, onLongClick = {})
        TodoItemRow(todo)
        ToolStatusIcon(running = true, isError = false, toolName = "readFile")
        ComposeCanvas(Modifier.size(180.dp, 30.dp).rippleHighlight()) { drawRect(Color.Black) }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    // LEGACY 缺少带 DisplayMetrics 的 ColorSpace 重载；只补软件分配，不替换矢量绘制或模拟 GPU 像素。
    @Implements(value = Bitmap::class, minSdk = 28, maxSdk = 28)
    class ColorSpaceBitmapShadow : ShadowLegacyBitmap() {
        companion object {
            @JvmStatic
            @Implementation(minSdk = 28, maxSdk = 28)
            fun createBitmap(
                display: DisplayMetrics?,
                width: Int,
                height: Int,
                config: Bitmap.Config,
                hasAlpha: Boolean,
                colorSpace: ColorSpace
            ): Bitmap = Bitmap.createBitmap(width, height, config, hasAlpha, colorSpace).also { bitmap ->
                if (display != null) bitmap.density = display.densityDpi
            }
        }
    }

    private data class GradientRect(val shader: Shader, val width: Float, val translationX: Float)

    // 软件 Canvas 保留真实绘制调用与 Shader 身份，不依赖 ARM64 不可用的 native 像素光栅化。
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val layerAlphas = mutableListOf<Int>()
        val gradientRects = mutableListOf<GradientRect>()
        var solidRectDraws = 0
        private var lastTranslationX = 0f

        fun resetRecording() {
            layerAlphas.clear()
            gradientRects.clear()
            solidRectDraws = 0
            lastTranslationX = 0f
        }

        override fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?): Int {
            layerAlphas += paint?.alpha ?: 255
            return super.saveLayer(left, top, right, bottom, paint)
        }

        @Suppress("DEPRECATION")
        override fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?, flags: Int): Int {
            layerAlphas += paint?.alpha ?: 255
            return super.saveLayer(left, top, right, bottom, paint, flags)
        }

        override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int {
            layerAlphas += alpha
            return super.saveLayerAlpha(left, top, right, bottom, alpha)
        }

        @Suppress("DEPRECATION")
        override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int, flags: Int): Int {
            layerAlphas += alpha
            return super.saveLayerAlpha(left, top, right, bottom, alpha, flags)
        }

        override fun translate(dx: Float, dy: Float) {
            lastTranslationX = dx
            super.translate(dx, dy)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val shader = paint.shader
            if (shader == null) solidRectDraws++
            else gradientRects += GradientRect(shader, right - left, lastTranslationX)
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    private inner class StatusUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        val canvas: RecordingCanvas,
        private val advanceFrame: () -> Unit,
        val compositionChanges: () -> Long,
        val awaitsFrames: () -> Boolean,
    ) {
        val owner = Owner()
        val visible = mutableStateOf(true)
        val drawerOpen = mutableStateOf(true)
        var measures = 0

        fun pump(count: Int = 1) = repeat(count) { advanceFrame() }

        fun assertDrawOnlyPulse(minimumAlpha: Float) {
            assertTrue("An active mounted control must request animation frames", awaitsFrames())
            val initialChanges = compositionChanges()
            val initialMeasures = measures
            val alphas = mutableListOf<Int>()
            repeat(20) {
                pump()
                val alpha = canvas.layerAlphas.minOrNull() ?: 255
                assertTrue("The actual layer alpha must stay in its pulse range: $alpha", alpha / 255f in (minimumAlpha - 0.01f)..1f)
                alphas += alpha
                assertEquals("Pulse frames must not recompose the controls", initialChanges, compositionChanges())
                assertEquals("Pulse frames must not remeasure their layout", initialMeasures, measures)
            }
            assertTrue("The actual software layer must draw different pulse alphas: $alphas", alphas.distinct().size > 5)
        }

        fun assertNoInfiniteFrames() {
            pump(12)
            assertFalse("Static mounted decorations must release their frame awaiters", awaitsFrames())
            val initialChanges = compositionChanges()
            val initialMeasures = measures
            repeat(8) {
                pump()
                assertFalse(awaitsFrames())
                assertTrue("Paused layers must return to full opacity", canvas.layerAlphas.all { it == 255 })
                assertTrue("Paused ripple must not draw a highlight band", canvas.gradientRects.isEmpty())
                assertEquals(initialChanges, compositionChanges())
                assertEquals(initialMeasures, measures)
            }
        }

        fun assertText(text: String) {
            assertTrue("The mounted UI must still show $text", nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            })
        }

        fun assertDescription(text: String) {
            assertTrue("The mounted UI must still announce $text", nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
            })
        }

        fun assertStatusControlsPresent() {
            assertText(session.title)
            assertText(todo.subject)
            assertText(activity.getString(R.string.todo_in_progress))
            assertDescription(activity.getString(R.string.chat_tool_running))
            assertTrue("The paused ripple content must remain drawn", canvas.solidRectDraws > 0)
        }

        private fun nodes() = (view.getChildAt(0) as ViewRootForTest)
            .semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
    }

    private fun render(content: @Composable () -> Unit, inspect: StatusUi.() -> Unit) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val bitmap = Bitmap.createBitmap(400, 1_000, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        val clock = BroadcastFrameClock()
        val scale = object : MotionDurationScale { override val scaleFactor = 1f }
        val recomposer = Recomposer(coroutineContext + clock + scale)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock + scale) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var nanos = 0L
        val ui = StatusUi(activity, view, canvas, advanceFrame = {
            looper.idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            nanos += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(nanos)
            runCurrent()
            Snapshot.sendApplyNotifications()
            runCurrent()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as? ViewRootForTest)?.measureAndLayoutForTest()
            canvas.resetRecording()
            view.draw(canvas)
        }, compositionChanges = { recomposer.changeCount }, awaitsFrames = { clock.hasAwaiters })
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalLifecycleOwner provides ui.owner,
                    LocalChatAnimationsEnabled provides ui.drawerOpen.value
                ) {
                    ToolPreviewWorkVisibility(ui.visible.value) {
                        AIEditorTheme(darkTheme = false) {
                            Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
                                ui.measures++
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }) {
                                content()
                            }
                        }
                    }
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
            bitmap.recycle()
        }
    }
}
