package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleStyleTest {
    @Test
    fun persistedIdsAreStableAndRoundTripWithoutUsingEnumNames() {
        val expected = mapOf(
            "classic" to ChatBubbleStyle.CLASSIC,
            "shadow" to ChatBubbleStyle.SHADOW,
            "minimal" to ChatBubbleStyle.MINIMAL,
            "dark" to ChatBubbleStyle.DARK,
            "square" to ChatBubbleStyle.SQUARE,
            "green" to ChatBubbleStyle.GREEN,
            "valentine" to ChatBubbleStyle.VALENTINE,
            "original" to ChatBubbleStyle.ORIGINAL,
            "document" to ChatBubbleStyle.DOCUMENT
        )
        assertEquals(expected.keys, ChatBubbleStyle.entries.map { it.id }.toSet())
        expected.forEach { (id, style) ->
            assertEquals(id, style.id)
            assertEquals(style, ChatBubbleStyle.fromId(id))
        }
        assertNull(ChatBubbleStyle.fromId("CLASSIC"))
    }

    @Test
    fun userHasEightPresetsAndKeepsOriginalAsDefault() {
        assertEquals(8, ChatBubbleStyle.userStyles.size)
        assertFalse(ChatBubbleStyle.DOCUMENT in ChatBubbleStyle.userStyles)
        ChatBubbleStyle.userStyles.forEach {
            assertEquals(it, ChatBubbleStyle.userStyleFromId(it.id))
        }
        listOf(null, "", "unknown", "document").forEach {
            assertEquals(ChatBubbleStyle.ORIGINAL, ChatBubbleStyle.userStyleFromId(it))
        }
    }

    @Test
    fun assistantHasDocumentAndAllPresetsWithDocumentAsDefault() {
        assertEquals(9, ChatBubbleStyle.assistantStyles.size)
        assertEquals(ChatBubbleStyle.DOCUMENT, ChatBubbleStyle.assistantStyles.first())
        ChatBubbleStyle.assistantStyles.forEach {
            assertEquals(it, ChatBubbleStyle.assistantStyleFromId(it.id))
        }
        listOf(null, "", "unknown").forEach {
            assertEquals(ChatBubbleStyle.DOCUMENT, ChatBubbleStyle.assistantStyleFromId(it))
        }
    }

    @Test
    fun everyPresetHasItsOwnLocalizedLabel() {
        assertEquals(ChatBubbleStyle.entries.size, ChatBubbleStyle.entries.map { it.labelRes }.toSet().size)
        assertTrue(ChatBubbleStyle.entries.all { it.labelRes != 0 })
    }
}
