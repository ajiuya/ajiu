package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.ChatBubblePalette
import com.aicode.core.theme.DarkColorScheme
import com.aicode.core.theme.LightColorScheme
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleAppearanceTest {
    @Test
    fun documentFlowHasNoBubblePaddingOrCornersInAnyChunk() {
        listOf(true, false).forEach { header ->
            listOf(true, false).forEach { footer ->
                assertEquals(
                    ChatBubbleLayout(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    chatBubbleLayout(ChatBubbleStyle.DOCUMENT, header, footer)
                )
            }
        }
    }

    @Test
    fun onlyHeaderAndFooterAddVerticalPaddingAndCorners() {
        ChatBubbleStyle.userStyles.forEach { style ->
            val whole = chatBubbleLayout(style, true, true)
            val header = chatBubbleLayout(style, true, false)
            val middle = chatBubbleLayout(style, false, false)
            val footer = chatBubbleLayout(style, false, true)
            assertEquals(Spacing.md, whole.horizontalPadding)
            assertEquals(whole.horizontalPadding, middle.horizontalPadding)
            assertEquals(Spacing.sm, whole.topPadding)
            assertEquals(Spacing.sm, whole.bottomPadding)
            assertEquals(whole.topCorner, header.topCorner)
            assertEquals(0.dp, header.bottomCorner)
            assertEquals(0.dp, header.bottomPadding)
            assertEquals(0.dp, middle.topCorner)
            assertEquals(0.dp, middle.bottomCorner)
            assertEquals(0.dp, middle.topPadding)
            assertEquals(0.dp, middle.bottomPadding)
            assertEquals(0.dp, footer.topCorner)
            assertEquals(0.dp, footer.topPadding)
            assertEquals(whole.bottomCorner, footer.bottomCorner)
            assertEquals(Spacing.sm, footer.bottomPadding)
        }
        assertEquals(Radius.lg, chatBubbleLayout(ChatBubbleStyle.ORIGINAL, true, true).topCorner)
        assertEquals(Radius.xs, chatBubbleLayout(ChatBubbleStyle.SQUARE, true, true).topCorner)
    }

    @Test
    fun originalAndDocumentUseTheActiveThemeWhilePresetColorsAreExplicit() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            assertEquals(
                ChatBubbleColors(scheme.primaryContainer, scheme.onPrimaryContainer),
                chatBubbleColors(ChatBubbleStyle.ORIGINAL, scheme)
            )
            assertEquals(
                ChatBubbleColors(Color.Transparent, scheme.onSurface),
                chatBubbleColors(ChatBubbleStyle.DOCUMENT, scheme)
            )
            assertEquals(scheme.onSurface, chatBubbleColors(ChatBubbleStyle.CLASSIC, scheme).border)
            assertEquals(scheme.onSurface, chatBubbleColors(ChatBubbleStyle.SQUARE, scheme).border)
            assertEquals(ChatBubblePalette.NightSurface, chatBubbleColors(ChatBubbleStyle.DARK, scheme).surface)
            assertEquals(ChatBubblePalette.OnNight, chatBubbleColors(ChatBubbleStyle.DARK, scheme).content)
            assertEquals(ChatBubblePalette.FreshGreenSurface, chatBubbleColors(ChatBubbleStyle.GREEN, scheme).surface)
            assertEquals(ChatBubblePalette.OnFreshGreen, chatBubbleColors(ChatBubbleStyle.GREEN, scheme).content)
        }
    }

    @Test
    fun presetBodyTextRemainsReadableInLightAndDarkThemes() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            ChatBubbleStyle.userStyles.forEach { style ->
                val colors = chatBubbleColors(style, scheme)
                val surface = colors.surface.luminance()
                val text = colors.content.luminance()
                val contrast = (maxOf(surface, text) + 0.05f) / (minOf(surface, text) + 0.05f)
                assertTrue("${style.id} contrast: $contrast", contrast >= 4.5f)
            }
        }
    }
}
