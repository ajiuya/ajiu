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

class ThemeSettingsRepositoryTest {
    companion object {
        // preferencesDataStore 委托在进程内缓存实例，各用例须共享同一个临时文件。
        @ClassRule
        @JvmField
        val temporaryFolder = TemporaryFolder()

        private val context: Context by lazy {
            val filesDir = temporaryFolder.newFolder("files")
            mockk<Context>().also { context ->
                every { context.applicationContext } returns context
                every { context.filesDir } returns filesDir
            }
        }
    }

    @Test
    fun chatTextScalePersistsAcrossInstancesWithoutOverwritingThemeSettings() = runTest {
        val repository = ThemeSettingsRepository(context)
        repository.setThemeMode(AppThemeMode.DARK)
        repository.setThemePresetId("persisted-theme-preset")
        repository.setDynamicColorEnabled(true)

        for ((value, expected) in listOf(-1f to 0.8f, 1.15f to 1.15f, 2f to 1.4f)) {
            repository.setChatTextScale(value)

            assertEquals(expected, repository.chatTextScaleFlow.first(), 0f)
            val anotherRepository = ThemeSettingsRepository(context)
            assertEquals(expected, anotherRepository.chatTextScaleSnapshot(), 0f)
            assertEquals(AppThemeMode.DARK, anotherRepository.themeModeFlow.first())
            assertEquals("persisted-theme-preset", anotherRepository.presetSnapshot())
            assertTrue(anotherRepository.dynamicColorSnapshot())
        }
        assertTrue(context.preferencesDataStoreFile("theme_prefs").length() > 0L)
    }

    @Test
    fun themeChangesDoNotOverwritePersistedChatTextScale() = runTest {
        val repository = ThemeSettingsRepository(context)
        repository.setChatTextScale(1.25f)

        val anotherRepository = ThemeSettingsRepository(context)
        anotherRepository.setThemeMode(AppThemeMode.LIGHT)
        anotherRepository.restoreColors("restored-theme-preset", false)

        assertEquals(1.25f, repository.chatTextScaleFlow.first(), 0f)
        assertEquals(1.25f, ThemeSettingsRepository(context).chatTextScaleSnapshot(), 0f)
        assertEquals(AppThemeMode.LIGHT, repository.themeModeFlow.first())
        assertEquals("restored-theme-preset", repository.presetSnapshot())
        assertEquals(false, repository.dynamicColorSnapshot())
    }

    @Test
    fun chatTextScaleDefaultsToOriginalSize() {
        assertEquals(1f, ThemeSettingsRepository.DEFAULT_CHAT_TEXT_SCALE, 0f)
        assertEquals(0.8f, ThemeSettingsRepository.MIN_CHAT_TEXT_SCALE, 0f)
        assertEquals(1.4f, ThemeSettingsRepository.MAX_CHAT_TEXT_SCALE, 0f)
    }

    @Test
    fun chatTextScaleIsClampedToSupportedRange() {
        listOf(0.8f, 1f, 1.15f, 1.4f).forEach { scale ->
            assertEquals(scale, ThemeSettingsRepository.clampChatTextScale(scale), 0f)
        }
        assertEquals(0.8f, ThemeSettingsRepository.clampChatTextScale(0.5f), 0f)
        assertEquals(1.4f, ThemeSettingsRepository.clampChatTextScale(2f), 0f)
    }

    @Test
    fun chatTextScaleNormalizesNonFiniteValues() {
        assertEquals(1f, ThemeSettingsRepository.clampChatTextScale(Float.NaN), 0f)
        assertEquals(0.8f, ThemeSettingsRepository.clampChatTextScale(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1.4f, ThemeSettingsRepository.clampChatTextScale(Float.POSITIVE_INFINITY), 0f)
    }
}
