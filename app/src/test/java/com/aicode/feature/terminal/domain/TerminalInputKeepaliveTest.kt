package com.aicode.feature.terminal.domain

import android.app.Application
import android.view.KeyEvent
import com.aicode.feature.terminal.presentation.component.AppTerminalViewClient
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import com.termux.terminal.TerminalSession
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TerminalInputKeepaliveTest {
    private val received = mutableListOf<TerminalSession>()
    private val session = mockk<TerminalSession>()

    private fun client() = AppTerminalViewClient(
        context = RuntimeEnvironment.getApplication(),
        viewProvider = { null },
        modifiers = TerminalKeyModifiers(),
        currentFontSizeSp = { 14 },
        onFontSizeChange = {},
        onFontSizeCommit = {},
        onUserInput = { received += it }
    )

    @Test
    fun realCodePointRenewsButDoesNotConsumeTerminalInput() {
        assertFalse(client().onCodePoint('a'.code, false, session))
        assertEquals(listOf(session), received)
    }

    @Test
    fun navigationKeyRenewsButModifierAndSystemKeysDoNot() {
        val client = client()
        client.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT), session)
        assertEquals(1, received.size)
        client.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT), session)
        client.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), session)
        assertEquals(1, received.size)
    }

    @Test
    fun lifecycleSelectionAndEmptyInputDoNotRenew() {
        val client = client()
        client.onEmulatorSet()
        client.copyModeChanged(false)
        client.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        client.onKeyDown(KeyEvent.KEYCODE_ENTER, null, session)
        client.onCodePoint('a'.code, false, null)
        assertEquals(0, received.size)
    }
}
