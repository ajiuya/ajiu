package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceTest {
    @Test
    fun defaultScale_keepsTextAndLineHeight() {
        val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        assertEquals(style, style.scaledForChat(1f))
    }

    @Test
    fun customScale_updatesFontSizeAndLineHeightTogether() {
        val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp).scaledForChat(1.4f)
        assertEquals(19.6f, style.fontSize.value, 0.001f)
        assertEquals(28f, style.lineHeight.value, 0.001f)
    }

    @Test
    fun inheritedTypography_staysUnspecified() {
        val style = TextStyle().scaledForChat(0.8f)
        assertEquals(TextUnit.Unspecified, style.fontSize)
        assertEquals(TextUnit.Unspecified, style.lineHeight)
    }
}
