package com.aicode.feature.filepreview.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTextRowsTest {
    @Test fun keepsEmptyLinesAndTrailingNewline() {
        assertEquals(listOf("", "alpha", "", ""), previewTextRows("\nalpha\n\n"))
        assertEquals(listOf(""), previewTextRows(""))
    }

    @Test fun longUnbrokenTextIsRenderedAsBoundedRowsWithoutDroppingContent() {
        val text = "x".repeat(2 * 1024 * 1024)
        val rows = previewTextRows(text)
        assertEquals(512, rows.size)
        assertTrue(rows.all { it.length <= 4096 })
        assertEquals(text, rows.joinToString(""))
    }

    @Test fun unicodePairsNeverSplitAcrossRows() {
        val text = "a".repeat(4095) + "\uD83D\uDE00" + "b".repeat(4095)
        val rows = previewTextRows(text)
        assertTrue(rows.all { it.length <= 4096 })
        assertTrue(rows.none { it.firstOrNull()?.isLowSurrogate() == true || it.lastOrNull()?.isHighSurrogate() == true })
        assertEquals(text, rows.joinToString(""))
    }
}
