package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneralReasoningSettingsTest {
    companion object {
        @ClassRule @JvmField val temporary = TemporaryFolder()
        private val context: Context by lazy {
            val files = temporary.newFolder("files")
            mockk<Context>().also {
                every { it.applicationContext } returns it
                every { it.filesDir } returns files
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun missingReasoningPreferencePreservesTheCollapsedDefault() = runTest {
        val getter = Class.forName("com.aicode.feature.settings.data.repository.GeneralSettingsRepositoryKt")
            .getDeclaredMethod("getGeneralDataStore", Context::class.java).apply { isAccessible = true }
        val store = getter.invoke(null, context) as DataStore<Preferences>
        store.edit { it.remove(booleanPreferencesKey("auto_expand_reasoning")) }
        val repository = GeneralSettingsRepository(context)
        assertFalse(repository.autoExpandReasoning())
        assertFalse(repository.autoExpandReasoningFlow.first())
    }

    @Test
    fun reasoningPreferencePersistsInBothDirectionsAcrossRepositoryInstances() = runTest {
        val repository = GeneralSettingsRepository(context)
        for (enabled in listOf(true, false, true, false)) {
            repository.setAutoExpandReasoning(enabled)
            assertEquals(enabled, repository.autoExpandReasoningFlow.first())
            assertEquals(enabled, GeneralSettingsRepository(context).autoExpandReasoning())
        }
    }

    @Test
    fun reasoningPreferenceDoesNotChangeTypingRateOrOtherInputSettings() = runTest {
        val repository = GeneralSettingsRepository(context)
        repository.setTypewriterCharsPerSecond(173)
        repository.setEnterToSend(true)
        repository.setCompactionThresholdPercent(85)
        repository.setAutoExpandReasoning(true)
        assertTrue(repository.autoExpandReasoning())
        repository.setAutoExpandReasoning(false)
        assertEquals(173, repository.typewriterCharsPerSecond())
        assertTrue(repository.enterToSendFlow.first())
        assertEquals(85, repository.compactionThresholdPercent())
    }
}
