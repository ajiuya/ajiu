package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneralTypewriterSettingsTest {
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
    @Suppress("UNCHECKED_CAST")
    fun missingValueDefaultsToThirtyAndReadsExistingSettingsWithTheNewBounds() = runTest {
        val getter = Class.forName("com.aicode.feature.settings.data.repository.GeneralSettingsRepositoryKt")
            .getDeclaredMethod("getGeneralDataStore", Context::class.java)
        getter.isAccessible = true
        val store = getter.invoke(null, context) as DataStore<Preferences>
        val key = intPreferencesKey("typewriter_chars_per_second")
        val repository = GeneralSettingsRepository(context)
        store.edit { it.remove(key) }
        assertEquals(30, repository.typewriterCharsPerSecond())
        for ((stored, expected) in listOf(10 to 30, 25 to 30, 85 to 85, 120 to 120, 400 to 350)) {
            store.edit { it[key] = stored }
            assertEquals(expected, repository.typewriterCharsPerSecond())
        }
    }

    @Test
    fun integerSpeedsPersistAcrossRepositoryInstances() = runTest {
        val repository = GeneralSettingsRepository(context)
        for (speed in listOf(30, 75, 173, 350)) {
            repository.setTypewriterCharsPerSecond(speed)
            assertEquals(speed, repository.typewriterCharsPerSecondFlow.first())
            assertEquals(speed, GeneralSettingsRepository(context).typewriterCharsPerSecond())
        }
    }

    @Test
    fun playbackRateIsBoundedWithoutChangingOtherPreferences() = runTest {
        val repository = GeneralSettingsRepository(context)
        repository.setEnterToSend(true)
        repository.setCompactionThresholdPercent(85)
        repository.setTypewriterCharsPerSecond(0)
        assertEquals(30, repository.typewriterCharsPerSecond())
        repository.setTypewriterCharsPerSecond(1_000)
        assertEquals(350, repository.typewriterCharsPerSecond())
        assertEquals(true, repository.enterToSendFlow.first())
        assertEquals(85, repository.compactionThresholdPercent())
    }
}
