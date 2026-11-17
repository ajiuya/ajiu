package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatBubbleSettingsRepositoryTest {
    companion object {
        @ClassRule
        @JvmField
        val temporaryFolder = TemporaryFolder()

        private val context: Context by lazy {
            val filesDir = temporaryFolder.newFolder("files")
            mockk<Context>().also {
                every { it.applicationContext } returns it
                every { it.filesDir } returns filesDir
            }
        }
    }

    @Test
    fun defaultsPersistIndependentlyAndRestoreOnlyPresentBackupFields() = runTest {
        val repository = ChatBubbleSettingsRepository(context)
        assertEquals(ChatBubbleStyle.ORIGINAL, repository.userStyleFlow.first())
        assertEquals(ChatBubbleStyle.DOCUMENT, repository.assistantStyleFlow.first())

        repository.setAssistantStyle(ChatBubbleStyle.DARK)
        ChatBubbleStyle.userStyles.forEach { style ->
            repository.setUserStyle(style)
            val reloaded = ChatBubbleSettingsRepository(context)
            assertEquals(style, repository.userStyleFlow.first())
            assertEquals(style, reloaded.userStyleSnapshot())
            assertEquals(ChatBubbleStyle.DARK, reloaded.assistantStyleSnapshot())
        }

        repository.setUserStyle(ChatBubbleStyle.GREEN)
        ChatBubbleStyle.assistantStyles.forEach { style ->
            repository.setAssistantStyle(style)
            val reloaded = ChatBubbleSettingsRepository(context)
            assertEquals(style, repository.assistantStyleFlow.first())
            assertEquals(style, reloaded.assistantStyleSnapshot())
            assertEquals(ChatBubbleStyle.GREEN, reloaded.userStyleSnapshot())
        }

        repository.setUserStyle(ChatBubbleStyle.DOCUMENT)
        assertEquals(ChatBubbleStyle.ORIGINAL, repository.userStyleSnapshot())
        repository.setUserStyle(ChatBubbleStyle.GREEN)
        repository.setAssistantStyle(ChatBubbleStyle.DARK)

        repository.restore(null, null)
        assertEquals(ChatBubbleStyle.GREEN, repository.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.DARK, repository.assistantStyleSnapshot())

        repository.restore(ChatBubbleStyle.SQUARE.id, null)
        assertEquals(ChatBubbleStyle.SQUARE, repository.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.DARK, repository.assistantStyleSnapshot())

        repository.restore(null, ChatBubbleStyle.SHADOW.id)
        assertEquals(ChatBubbleStyle.SQUARE, repository.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.SHADOW, repository.assistantStyleSnapshot())

        repository.restore("future-user-style", "future-assistant-style")
        assertEquals(ChatBubbleStyle.ORIGINAL, repository.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.DOCUMENT, repository.assistantStyleSnapshot())

        repository.restore(ChatBubbleStyle.DOCUMENT.id, ChatBubbleStyle.GREEN.id)
        assertEquals(ChatBubbleStyle.ORIGINAL, repository.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.GREEN, repository.assistantStyleSnapshot())

        repository.restore(ChatBubbleStyle.VALENTINE.id, ChatBubbleStyle.CLASSIC.id)
        val reloaded = ChatBubbleSettingsRepository(context)
        assertEquals(ChatBubbleStyle.VALENTINE, reloaded.userStyleSnapshot())
        assertEquals(ChatBubbleStyle.CLASSIC, reloaded.assistantStyleSnapshot())
        assertTrue(context.preferencesDataStoreFile("chat_bubble_prefs").length() > 0L)
    }
}
