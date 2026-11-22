package com.termux.view

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class GestureAndScaleRecognizerTest {
    private lateinit var recognizer: GestureAndScaleRecognizer
    private lateinit var listener: GestureAndScaleRecognizer.Listener
    private var downTime = 0L

    @Before
    fun setUp() {
        listener = mockk(relaxed = true)
        every { listener.onDown(any(), any()) } returns true
        every { listener.onScaleBegin() } returns true
        every { listener.onScale(any()) } returns true
        recognizer = GestureAndScaleRecognizer(ApplicationProvider.getApplicationContext<Context>(), listener)
    }

    @Test
    fun pinchAndTheRemainingFingerDoNotScrollFlingClickOrLongPress() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 400f)
        touch(MotionEvent.ACTION_MOVE, 80f, 440f, y = 130f)
        touch(MotionEvent.ACTION_MOVE, 60f, 500f, y = 190f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 60f, 500f, y = 190f)
        touch(MotionEvent.ACTION_MOVE, 60f, y = 300f)
        touch(MotionEvent.ACTION_UP, 60f, y = 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        verify(atLeast = 1) { listener.onScale(any()) }
        verify(exactly = 1) { listener.onScaleEnd() }
        verify(exactly = 0) { listener.onScroll(any(), any(), any()) }
        verify(exactly = 0) { listener.onFling(any(), any(), any()) }
        verify(exactly = 0) { listener.onUp(any()) }
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
        verify(exactly = 0) { listener.onLongPress(any()) }
    }

    @Test
    fun twoFingerTapBelowTheScaleThresholdDoesNotBecomeATerminalClick() {
        twoFingerTap()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        verify(exactly = 0) { listener.onScaleBegin() }
        verify(exactly = 0) { listener.onUp(any()) }
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
        verify(exactly = 0) { listener.onLongPress(any()) }
    }

    @Test
    fun aNewSingleFingerTapAfterMultitouchStillWorks() {
        twoFingerTap()
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_UP, 50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        verify(exactly = 1) { listener.onUp(any()) }
        verify(exactly = 1) { listener.onSingleTapUp(any()) }
    }

    @Test
    fun aNewSingleFingerScrollAfterMultitouchStillWorks() {
        twoFingerTap()
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_MOVE, 50f, y = 200f)
        touch(MotionEvent.ACTION_UP, 50f, y = 200f)
        verify(atLeast = 1) { listener.onScroll(any(), any(), any()) }
    }

    @Test
    fun singleFingerLongPressStillSuppressesTheReleaseClick() {
        touch(MotionEvent.ACTION_DOWN, 50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(MotionEvent.ACTION_UP, 50f)
        verify(exactly = 1) { listener.onLongPress(any()) }
        verify(exactly = 0) { listener.onUp(any()) }
    }

    @Test
    fun addingASecondFingerCancelsAPendingLongPressBeforeItsTimeout() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        touch(MotionEvent.ACTION_UP, 100f)
        verify(exactly = 0) { listener.onLongPress(any()) }
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
    }

    @Test
    fun cancelledMultitouchDoesNotLeakATapIntoTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 400f)
        touch(MotionEvent.ACTION_MOVE, 60f, 500f)
        touch(MotionEvent.ACTION_CANCEL, 60f, 500f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
        verify(exactly = 0) { listener.onUp(any()) }
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_UP, 50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        verify(exactly = 1) { listener.onSingleTapUp(any()) }
        verify(exactly = 1) { listener.onUp(any()) }
    }

    private fun twoFingerTap() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        touch(MotionEvent.ACTION_UP, 100f)
    }

    private fun touch(action: Int, vararg xs: Float, y: Float = 100f) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val time = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val properties = Array(xs.size) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(xs.size) { index -> MotionEvent.PointerCoords().apply {
            x = xs[index]
            this.y = y
            pressure = 1f
            size = 1f
        } }
        val event = MotionEvent.obtain(downTime, time, action, xs.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { recognizer.onTouchEvent(event) } finally { event.recycle() }
    }
}
