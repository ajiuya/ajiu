package com.aicode.feature.terminal.presentation.component

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalSession
import io.mockk.mockk
import java.time.Duration
import org.junit.Assert.*
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
class AppTerminalViewClientTest {
    private var size = 12
    private val changes = mutableListOf<Int>()
    private val commits = mutableListOf<Int>()
    private val inputs = mutableListOf<TerminalSession>()
    private val modifiers = TerminalKeyModifiers()
    private lateinit var client: AppTerminalViewClient

    @Before
    fun setUp() {
        client = AppTerminalViewClient(
            context = ApplicationProvider.getApplicationContext<Context>(),
            viewProvider = { null },
            modifiers = modifiers,
            currentFontSizeSp = { size },
            onFontSizeChange = { size = it; changes += it },
            onFontSizeCommit = { commits += it },
            onUserInput = { inputs += it }
        )
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun fontChangesBeforeReleaseAndIsSavedOnlyAfterRelease() {
        client.onScaleBegin()
        client.onScale(1.1f)
        assertEquals(listOf(13), changes)
        assertTrue(commits.isEmpty())
        client.onScaleEnd()
        assertEquals(listOf(13), changes)
        assertEquals(listOf(13), commits)
    }

    @Test
    fun tinyMovementsAccumulateWithoutRoundingEachIncrement() {
        client.onScaleBegin()
        client.onScale(1.01f)
        client.onScale(1.02f)
        assertTrue(changes.isEmpty())
        client.onScale(1.02f)
        assertEquals(listOf(13), changes)
    }

    @Test
    fun resizeEventsAreCoalescedAcrossTheEightyMillisecondBoundary() {
        client.onScaleBegin()
        client.onScale(1.1f)
        advance(79)
        client.onScale(1.1f)
        assertEquals(listOf(13), changes)
        advance(1)
        client.onScale(1.1f)
        assertEquals(listOf(13, 16), changes)
        assertTrue(commits.isEmpty())
        client.onScaleEnd()
        assertEquals(listOf(16), commits)
    }

    @Test
    fun releaseAppliesTheLastPendingSizeBeforePersistingIt() {
        client.onScaleBegin()
        client.onScale(1.1f)
        client.onScale(1.1f)
        assertEquals(13, size)
        client.onScaleEnd()
        assertEquals(listOf(13, 15), changes)
        assertEquals(15, size)
        assertEquals(listOf(15), commits)
    }

    @Test
    fun reversingAtTheUpperLimitDoesNotHaveAnOvershootDeadZone() {
        client.onScaleBegin()
        client.onScale(10f)
        assertEquals(22, size)
        advance(80)
        client.onScale(0.95f)
        assertEquals(21, size)
    }

    @Test
    fun reversingAtTheLowerLimitDoesNotHaveAnOvershootDeadZone() {
        client.onScaleBegin()
        client.onScale(0.01f)
        assertEquals(5, size)
        advance(80)
        client.onScale(1.2f)
        assertEquals(6, size)
    }

    @Test
    fun unchangedSizeDoesNotResizeOrPersist() {
        client.onScaleBegin()
        client.onScale(1.01f)
        client.onScaleEnd()
        assertTrue(changes.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test
    fun returningToTheInitialSizeRestoresItWithoutAnUnnecessarySave() {
        client.onScaleBegin()
        client.onScale(1.5f)
        advance(80)
        client.onScale(2f / 3f)
        client.onScaleEnd()
        assertEquals(listOf(18, 12), changes)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun nextPinchBeginsFromTheCurrentSettingAndResetsItsThrottle() {
        client.onScaleBegin()
        client.onScale(1.5f)
        client.onScaleEnd()
        size = 20
        client.onScaleBegin()
        client.onScale(0.5f)
        client.onScaleEnd()
        assertEquals(listOf(18, 10), changes)
        assertEquals(listOf(18, 10), commits)
    }

    @Test
    fun rapidPinchWithinOneWindowDoesNotResizeForEveryDelta() {
        client.onScaleBegin()
        repeat(1_000) { client.onScale(if (it % 2 == 0) 1.1f else 0.9f) }
        assertEquals(1, changes.size)
        assertTrue(commits.isEmpty())
        client.onScaleEnd()
        assertEquals(listOf(13, 5), changes)
        assertEquals(listOf(5), commits)
    }

    @Test
    fun zoomDoesNotCountAsTerminalInputOrConsumeOneShotModifiers() {
        modifiers.ctrl = true
        modifiers.alt = true
        client.onScaleBegin()
        client.onScale(1.5f)
        client.onScaleEnd()
        assertTrue(inputs.isEmpty())
        assertTrue(modifiers.ctrl)
        assertTrue(modifiers.alt)
        val session = mockk<TerminalSession>()
        assertFalse(client.onCodePoint('x'.code, false, session))
        assertEquals(listOf(session), inputs)
        assertFalse(modifiers.ctrl)
        assertFalse(modifiers.alt)
    }
}
