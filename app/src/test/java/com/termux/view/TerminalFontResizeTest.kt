package com.termux.view

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.terminal.presentation.component.AppTerminalViewClient
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPaint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE,
    shadows = [TerminalFontResizeTest.SizedPaintShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalFontResizeTest {
    // LEGACY 的测宽与字号无关；为真实网格重排提供确定性度量，不验证系统字体光栅化。
    @Implements(value = Paint::class, minSdk = 28, maxSdk = 28)
    class SizedPaintShadow : ShadowPaint() {
        @RealObject lateinit var realPaint: Paint
        @Implementation override fun measureText(text: String): Float = text.length * realPaint.textSize * 0.6f
        @Implementation override fun measureText(text: CharSequence, start: Int, end: Int): Float =
            (end - start) * realPaint.textSize * 0.6f
        @Implementation override fun measureText(text: CharArray, start: Int, count: Int): Float =
            count * realPaint.textSize * 0.6f
        @Implementation fun getFontSpacing(): Float = realPaint.textSize * 1.2f
        @Implementation fun ascent(): Float = -realPaint.textSize * 0.9f
    }

    private lateinit var view: TerminalView
    private lateinit var session: TerminalSession
    private lateinit var emulator: TerminalEmulator
    private var size = 12
    private val commits = mutableListOf<Int>()
    private var inputs = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        emulator = TerminalEmulator(mockk(relaxed = true), 80, 24, 2_000, mockk(relaxed = true))
        session = mockk(relaxed = true)
        every { session.emulator } returns emulator
        every { session.updateSize(any(), any()) } answers { emulator.resize(firstArg(), secondArg()) }
        view = TerminalView(context, null)
        view.setTerminalViewClient(AppTerminalViewClient(
            context = context,
            viewProvider = { view },
            modifiers = TerminalKeyModifiers(),
            currentFontSizeSp = { size },
            onFontSizeChange = { size = it; view.setTextSize(it * 2) },
            onFontSizeCommit = { commits += it },
            onUserInput = { inputs++ }
        ))
        view.setTextSize(size * 2)
        view.layout(0, 0, 480, 800)
        view.attachSession(session)
    }

    @Test
    fun pinchUpdatesTheRendererAndTerminalGridBeforeItsRelease() {
        val columns = emulator.mColumns
        val rows = emulator.mRows
        assertTrue(view.mGestureRecognizer.mListener.onScaleBegin())
        view.mGestureRecognizer.mListener.onScale(1.5f)
        assertEquals(36, view.mRenderer.mTextSize)
        assertTrue(emulator.mColumns < columns)
        assertTrue(emulator.mRows < rows)
        assertTrue(commits.isEmpty())
        assertEquals(0, inputs)
        view.mGestureRecognizer.mListener.onScaleEnd()
        assertEquals(listOf(18), commits)
    }

    @Test
    fun unchangedTextSizeDoesNotRecreateRendererOrResizeTheSession() {
        val renderer = view.mRenderer
        clearMocks(session, answers = false)
        view.setTextSize(24)
        view.setTextSize(24)
        assertSame(renderer, view.mRenderer)
        verify(exactly = 0) { session.updateSize(any(), any()) }
    }

    @Test
    fun aNewTouchStopsOldFlingAndClearsItsScrollRemainder() {
        view.mScroller.fling(0, 0, 0, 2_000, 0, 0, 0, 1_000)
        view.mScrollRemainder = 10f
        view.mGestureRecognizer.mListener.onDown(100f, 100f)
        assertTrue(view.mScroller.isFinished)
        assertEquals(0f, view.mScrollRemainder, 0f)
        assertEquals(0, inputs)
    }

    @Test
    fun aViewWithoutAnEmulatorRejectsScalingWithoutSaving() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val empty = TerminalView(context, null)
        val client = mockk<TerminalViewClient>(relaxed = true)
        empty.setTerminalViewClient(client)
        assertFalse(empty.mGestureRecognizer.mListener.onScaleBegin())
        verify(exactly = 0) { client.onScaleBegin() }
    }

    @Test
    fun realDrawingClipsToTheViewAndRestoresTheCallerCanvas() {
        val bitmap = Bitmap.createBitmap(600, 1_000, Bitmap.Config.ARGB_8888)
        val clips = mutableListOf<List<Float>>()
        val canvas = object : Canvas(bitmap) {
            override fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean {
                clips += listOf(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                return super.clipRect(left, top, right, bottom)
            }
        }
        val saves = canvas.saveCount
        try {
            view.draw(canvas)
            assertTrue(clips.contains(listOf(0f, 0f, 480f, 800f)))
            assertEquals(saves, canvas.saveCount)
        } finally { bitmap.recycle() }
    }
}
