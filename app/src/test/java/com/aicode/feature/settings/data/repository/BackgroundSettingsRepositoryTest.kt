package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundSettingsRepositoryTest {
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
    fun chatAlphaPersistsAcrossInstancesWithoutChangingGlobalAlpha() = runTest {
        val repository = BackgroundSettingsRepository(context)
        repository.setBackgroundAlpha(0.12f)

        for ((value, expected) in listOf(-1f to 0f, 0f to 0f, 0.65f to 0.65f, 1f to 1f, 2f to 1f)) {
            repository.setChatBackgroundAlpha(value)

            assertEquals(expected, repository.chatAlphaFlow.first(), 0f)
            val anotherRepository = BackgroundSettingsRepository(context)
            assertEquals(expected, anotherRepository.chatAlphaSnapshot(), 0f)
            assertEquals(0.12f, anotherRepository.alphaFlow.first(), 0f)
        }
        assertTrue(context.preferencesDataStoreFile("background_prefs").length() > 0L)
    }

    @Test
    fun globalAlphaLimitDoesNotReducePersistedChatAlpha() = runTest {
        val repository = BackgroundSettingsRepository(context)
        repository.setChatBackgroundAlpha(0.85f)

        val anotherRepository = BackgroundSettingsRepository(context)
        anotherRepository.setBackgroundAlpha(1f)
        assertEquals(0.2f, repository.alphaFlow.first(), 0f)
        assertEquals(0.85f, anotherRepository.chatAlphaSnapshot(), 0f)

        anotherRepository.setBackgroundAlpha(-1f)
        assertEquals(0f, repository.alphaFlow.first(), 0f)
        assertEquals(0.85f, repository.chatAlphaFlow.first(), 0f)
        assertNull(repository.imagePathFlow.first())
        assertNull(repository.chatImagePathFlow.first())
    }

    @Test
    fun globalWatermarkRangeAndSliderMappingRemainUnchanged() {
        assertEquals(0.15f, BackgroundSettingsRepository.DEFAULT_ALPHA, 0f)
        assertEquals(0.2f, BackgroundSettingsRepository.MAX_ALPHA, 0f)
        assertEquals(0f, BackgroundSettingsRepository.sliderToAlpha(0f), 0f)
        assertEquals(0.1f, BackgroundSettingsRepository.sliderToAlpha(50f), 0.0001f)
        assertEquals(0.2f, BackgroundSettingsRepository.sliderToAlpha(100f), 0f)
        assertEquals(100f, BackgroundSettingsRepository.alphaToSlider(0.2f), 0f)
    }

    @Test
    fun chatAlphaUsesFullOpacityRange() {
        assertEquals(0.2f, BackgroundSettingsRepository.DEFAULT_CHAT_ALPHA, 0f)
        listOf(0f, 0.2f, 0.5f, 1f).forEach { alpha ->
            assertEquals(alpha, BackgroundSettingsRepository.clampChatAlpha(alpha), 0f)
        }
        assertEquals(0f, BackgroundSettingsRepository.clampChatAlpha(-0.1f), 0f)
        assertEquals(1f, BackgroundSettingsRepository.clampChatAlpha(1.1f), 0f)
    }

    @Test
    fun chatAlphaNormalizesNonFiniteValues() {
        assertEquals(0.2f, BackgroundSettingsRepository.clampChatAlpha(Float.NaN), 0f)
        assertEquals(0f, BackgroundSettingsRepository.clampChatAlpha(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, BackgroundSettingsRepository.clampChatAlpha(Float.POSITIVE_INFINITY), 0f)
    }
}
