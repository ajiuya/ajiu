package com.aicode.core.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AppPressFeedbackTest {
    @Test
    fun realTouchCancelRestoresTheLayerWithoutClickingAndReleaseClicksOnce() = withButton {
        val measuresBeforePress = measures
        touch(MotionEvent.ACTION_DOWN)
        frames(9)
        assertScale(0.98f)
        assertEquals(0, clicks)
        val cancelledPress = interactions.filterIsInstance<PressInteraction.Press>().single()

        touch(MotionEvent.ACTION_CANCEL)
        frames(13)
        assertScale(1f)
        assertEquals(0, clicks)
        assertSame(cancelledPress, interactions.filterIsInstance<PressInteraction.Cancel>().single().press)
        assertTrue(interactions.filterIsInstance<PressInteraction.Release>().isEmpty())

        touch(MotionEvent.ACTION_DOWN)
        frames(9)
        assertScale(0.98f)
        touch(MotionEvent.ACTION_UP)
        frames(13)
        assertScale(1f)
        assertEquals(1, clicks)
        val releasedPress = interactions.filterIsInstance<PressInteraction.Press>().last()
        assertSame(releasedPress, interactions.filterIsInstance<PressInteraction.Release>().single().press)
        assertEquals(measuresBeforePress, measures)
    }

    @Test
    fun animatedGraphicsLayerChangesBoundsWithoutRemeasureOrPerFrameRecomposition() = withButton {
        val initialMeasures = measures
        val press = PressInteraction.Press(Offset(100f, 50f))
        source.emit(press)
        frames(3)
        assertTrue("The press tween must produce an intermediate layer scale: $scale", scale > 0.98f && scale < 1f)
        val changesDuringPress = compositionChanges()
        repeat(6) {
            frames(1)
            assertTrue("Press scale must stay restrained: $scale", scale in 0.9799f..1.0001f)
            assertEquals(changesDuringPress, compositionChanges())
            assertEquals(initialMeasures, measures)
        }
        assertScale(0.98f)

        source.emit(PressInteraction.Release(press))
        frames(3)
        assertTrue("The release tween must animate rather than jump: $scale", scale > 0.98f && scale < 1f)
        val changesDuringRelease = compositionChanges()
        repeat(12) {
            frames(1)
            assertTrue("Return scale must not overshoot: $scale", scale in 0.9799f..1.0001f)
            assertEquals(changesDuringRelease, compositionChanges())
            assertEquals(initialMeasures, measures)
        }
        assertScale(1f)
    }

    @Test
    fun rapidPressReleaseCancelAndRepressAlwaysReturnsToTheUnscaledTarget() = withButton {
        repeat(4) {
            val first = PressInteraction.Press(Offset.Zero)
            source.emit(first)
            frames(3)
            source.emit(PressInteraction.Release(first))
            frames(1)
            val second = PressInteraction.Press(Offset.Zero)
            source.emit(second)
            frames(2)
            source.emit(PressInteraction.Cancel(second))
            val third = PressInteraction.Press(Offset.Zero)
            source.emit(third)
            frames(2)
            source.emit(PressInteraction.Release(third))
            frames(16)
            assertScale(1f)
        }
        assertEquals(0, clicks)
        assertEquals(12, interactions.filterIsInstance<PressInteraction.Press>().size)
        assertEquals(8, interactions.filterIsInstance<PressInteraction.Release>().size)
        assertEquals(4, interactions.filterIsInstance<PressInteraction.Cancel>().size)
    }

    @Test
    fun disabledFeedbackNeverShrinksAndDisablingAnActivePressRestoresTheLayer() = withButton(enabled = false) {
        val disabledPress = PressInteraction.Press(Offset.Zero)
        source.emit(disabledPress)
        frames(9)
        assertScale(1f)
        source.emit(PressInteraction.Cancel(disabledPress))
        frames(3)

        enabled.value = true
        frames(3)
        val activePress = PressInteraction.Press(Offset.Zero)
        source.emit(activePress)
        frames(9)
        assertScale(0.98f)
        enabled.value = false
        frames(13)
        assertScale(1f)
        source.emit(PressInteraction.Cancel(activePress))
        frames(13)
        assertScale(1f)
        assertEquals(0, clicks)
    }

    @Test
    fun zeroMotionDurationScaleSkipsPressReleaseAndCancelTweens() = withButton(durationScale = 0f) {
        val releasedPress = PressInteraction.Press(Offset.Zero)
        source.emit(releasedPress)
        frames(3)
        assertScale(0.98f)
        source.emit(PressInteraction.Release(releasedPress))
        frames(3)
        assertScale(1f)

        val cancelledPress = PressInteraction.Press(Offset.Zero)
        source.emit(cancelledPress)
        frames(3)
        assertScale(0.98f)
        source.emit(PressInteraction.Cancel(cancelledPress))
        frames(3)
        assertScale(1f)
        assertEquals(0, clicks)
    }

    private class PressUi(
        initiallyEnabled: Boolean,
        private val pump: () -> Unit,
        val compositionChanges: () -> Long,
        val touch: (Int) -> Unit,
    ) {
        val source = MutableInteractionSource()
        val enabled = mutableStateOf(initiallyEnabled)
        val interactions = mutableListOf<Interaction>()
        lateinit var coordinates: LayoutCoordinates
        var measures = 0
        var clicks = 0
        val scale get() = coordinates.boundsInRoot().width / coordinates.size.width

        fun frames(count: Int) = repeat(count) { pump() }

        fun assertScale(expected: Float) {
            val bounds = coordinates.boundsInRoot()
            assertEquals(IntSize(200, 100), coordinates.size)
            assertEquals(200f * expected, bounds.width, 0.02f)
            assertEquals(100f * expected, bounds.height, 0.02f)
            assertEquals(160f, bounds.center.x, 0.02f)
            assertEquals(120f, bounds.center.y, 0.02f)
        }
    }

    private fun withButton(
        enabled: Boolean = true,
        durationScale: Float = 1f,
        inspect: suspend PressUi.() -> Unit,
    ) = runTest(timeout = 15.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val motionScale = object : MotionDurationScale {
            override val scaleFactor = durationScale
        }
        val recomposer = Recomposer(coroutineContext + clock + motionScale)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock + motionScale) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        var downTime = 0L
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
        }
        val ui = PressUi(enabled, pump = {
            looper.idleFor(Duration.ofMillis(16))
            runCurrent()
            Snapshot.sendApplyNotifications()
            runCurrent()
            frame++
            if (clock.hasAwaiters) clock.sendFrame(frame * 16_000_000L)
            runCurrent()
            Snapshot.sendApplyNotifications()
            runCurrent()
            looper.idle()
            layout()
        }, compositionChanges = { recomposer.changeCount }, touch = { action ->
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { x = 160f; y = 120f; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                val delivered = view.getChildAt(0).dispatchTouchEvent(event)
                if (action == MotionEvent.ACTION_DOWN) assertTrue(delivered)
            } finally {
                event.recycle()
            }
        })
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    LaunchedEffect(ui.source) { ui.source.interactions.collect { ui.interactions += it } }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier
                            .clickable(interactionSource = ui.source, indication = null, enabled = ui.enabled.value) { ui.clicks++ }
                            .appPressFeedback(ui.source, ui.enabled.value)
                            .layout { measurable, constraints ->
                                ui.measures++
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }
                            .size(200.dp, 100.dp)
                            .onGloballyPositioned { ui.coordinates = it })
                    }
                }
            }
            activity.setContentView(view, ViewGroup.LayoutParams(320, 240))
            ui.frames(6)
            ui.assertScale(1f)
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
