package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsPreviewTest {
    @Test
    fun previewsAreCumulativeIdIsolatedAndThrottledWithForcedTail() {
        var now = 0L
        val previews = ToolArgumentsPreview(intervalNanos = 75, nanoTime = { now })
        val first = StringBuilder("{\"tool_title\":\"Write")
        assertEquals(first.toString(), previews.update("one", "writeFile", first)?.accumulated)
        first.append(" file\",\"content\":\"body")
        assertNull(previews.update("one", "writeFile", first))
        assertEquals("{\"path\":", previews.update("two", "editFile", "{\"path\":")?.accumulated)
        now = 75
        assertEquals(first.toString(), previews.update("one", "writeFile", first)?.accumulated)
        first.append("\"}")
        assertEquals(first.toString(), previews.update("one", "writeFile", first, force = true)?.accumulated)
        assertNull(previews.update("one", "writeFile", first, force = true))
    }

    @Test
    fun previewsWaitForBothIdAndNameWithoutParsingPartialJson() {
        val previews = ToolArgumentsPreview()
        val partial = "{\"tool_title\":\"Inspect\",\"content\":\"incomplete"
        assertNull(previews.update("", "writeFile", partial))
        assertNull(previews.update("id", "", partial))
        assertNull(previews.update("id", "writeFile", ""))
        val preview = previews.update("id", "writeFile", partial)!!
        assertEquals("id", preview.id)
        assertEquals("writeFile", preview.name)
        assertEquals(partial, preview.accumulated)
    }

    @Test
    fun previewsAreBoundedWithoutBreakingSurrogatePairsAndDoNotTruncateSource() {
        val previews = ToolArgumentsPreview(maxChars = 8, intervalNanos = 0)
        val original = StringBuilder("1234567").appendCodePoint(0x1F4C4).append("tail")
        val first = previews.update("id", "writeFile", original)!!
        assertEquals("1234567", first.accumulated)
        assertFalse(first.accumulated.last().isHighSurrogate())
        assertEquals(13, original.length)
        assertEquals(11, first.totalAsciiChars)
        assertEquals(2, first.totalOtherChars)
        original.append("more")
        val continued = previews.update("id", "writeFile", original, force = true)!!
        assertEquals(first.accumulated, continued.accumulated)
        assertEquals(15, continued.totalAsciiChars)
        assertEquals(2, continued.totalOtherChars)
        assertNull(previews.update("id", "writeFile", original, force = true))
    }

    @Test
    fun countsFollowFullSourceReplacementEvenWhenThePreviewAndLengthAreIdentical() {
        val previews = ToolArgumentsPreview(maxChars = 4, intervalNanos = 0)
        val first = previews.update("id", "writeFile", StringBuilder("abcd汉字"))!!
        val replacement = previews.update("id", "writeFile", StringBuilder("abcdef"))!!
        assertEquals(first.accumulated, replacement.accumulated)
        assertEquals(4, first.totalAsciiChars)
        assertEquals(2, first.totalOtherChars)
        assertEquals(6, replacement.totalAsciiChars)
        assertEquals(0, replacement.totalOtherChars)
        val shortened = previews.update("id", "writeFile", "a", force = true)!!
        assertEquals(1, shortened.totalAsciiChars)
        assertEquals(0, shortened.totalOtherChars)
    }

    @Test
    fun countingOnlyReadsNewSuffixAndKeepsTheSourceOutOfTheUiEvent() {
        val source = object : CharSequence {
            val text = StringBuilder()
            var reads = 0
            override val length get() = text.length
            override fun get(index: Int): Char { reads++; return text[index] }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.substring(startIndex, endIndex)
        }
        val previews = ToolArgumentsPreview(maxChars = 4, intervalNanos = 0)
        repeat(1_000) { index ->
            source.text.append("a汉")
            val event = previews.update("id", "writeFile", source)!!
            assertEquals(index + 1, event.totalAsciiChars)
            assertEquals(index + 1, event.totalOtherChars)
            assertEquals(minOf(4, source.length), event.accumulated.length)
        }
        assertTrue(source.reads <= source.length * 2)
        repeat(10) { assertNull(previews.update("id", "writeFile", source, force = true)) }
        assertTrue(source.reads <= source.length * 2)
    }

    @Test
    fun throttledChangesKeepFullCountsForTheForcedTail() {
        var now = 0L
        val previews = ToolArgumentsPreview(maxChars = 4, intervalNanos = 75, nanoTime = { now })
        val source = StringBuilder("head")
        previews.update("id", "writeFile", source)
        source.append("body汉")
        now = 50
        assertNull(previews.update("id", "writeFile", source))
        val tail = previews.update("id", "writeFile", source, force = true)!!
        assertEquals("head", tail.accumulated)
        assertEquals(8, tail.totalAsciiChars)
        assertEquals(1, tail.totalOtherChars)
    }

    @Test
    fun newAttemptDoesNotRetainPreviousAttemptPreview() {
        val oldAttempt = ToolArgumentsPreview()
        oldAttempt.update("id", "writeFile", "old body")
        val retry = ToolArgumentsPreview()
        assertEquals("new body", retry.update("id", "writeFile", "new body")?.accumulated)
    }
}
