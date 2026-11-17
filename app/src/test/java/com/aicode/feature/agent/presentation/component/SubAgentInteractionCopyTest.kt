package com.aicode.feature.agent.presentation.component

import org.junit.Assert.*
import org.junit.Test

class SubAgentInteractionCopyTest {
    @Test fun copyGuardCountsUtf8AndKeepsTheExactFullText() {
        val ascii = "a".repeat(SUBAGENT_CLIPBOARD_MAX_CHARS)
        assertTrue(canCopySubAgentInteractionText(ascii))
        assertFalse(canCopySubAgentInteractionText(ascii + "a"))
        var copied: String? = null
        assertEquals(SubAgentTextCopyResult.COPIED, copySubAgentInteractionText(ascii) { copied = it })
        assertSame(ascii, copied)
        val chinese = "中".repeat(SUBAGENT_CLIPBOARD_MAX_UTF8_BYTES / 3)
        assertTrue(canCopySubAgentInteractionText(chinese))
        assertFalse(canCopySubAgentInteractionText(chinese + "文"))
        val emoji = "😀".repeat(SUBAGENT_CLIPBOARD_MAX_UTF8_BYTES / 4)
        assertTrue(canCopySubAgentInteractionText(emoji))
        assertFalse(canCopySubAgentInteractionText(emoji + "😀"))
    }

    @Test fun oversizeAndClipboardFailureAreExplicitRatherThanSilentTruncation() {
        assertEquals(SubAgentTextCopyResult.TOO_LARGE, copySubAgentInteractionText("a".repeat(SUBAGENT_CLIPBOARD_MAX_CHARS + 1)) {
            error("must not reach clipboard")
        })
        assertEquals(SubAgentTextCopyResult.FAILED, copySubAgentInteractionText("whole body") { throw IllegalStateException("Binder") })
    }
}
