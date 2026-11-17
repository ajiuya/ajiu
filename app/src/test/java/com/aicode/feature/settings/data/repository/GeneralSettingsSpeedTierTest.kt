package com.aicode.feature.settings.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.feature.agent.domain.model.SpeedTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GeneralSettingsSpeedTierTest {
    private lateinit var context: Context
    private lateinit var store: DataStore<Preferences>

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        val getter = Class.forName("com.aicode.feature.settings.data.repository.GeneralSettingsRepositoryKt")
            .getDeclaredMethod("getGeneralDataStore", Context::class.java)
        getter.isAccessible = true
        store = getter.invoke(null, context) as DataStore<Preferences>
        store.edit { it.clear() }
        Unit
    }

    @Test
    fun missingValueDefaultsToStandard() = runBlocking {
        val repository = GeneralSettingsRepository(context)
        assertEquals(SpeedTier.STANDARD, repository.speedTier())
        assertEquals(SpeedTier.STANDARD, repository.speedTierFlow.first())
    }

    @Test
    fun allTiersRoundTripWithStorageValuesAcrossRepositoryInstances() = runBlocking {
        val repository = GeneralSettingsRepository(context)
        SpeedTier.entries.forEach { tier ->
            repository.setSpeedTier(tier)
            assertEquals(tier.storageValue, store.data.first()[stringPreferencesKey("speed_tier")])
            assertEquals(tier, repository.speedTierFlow.first())
            assertEquals(tier, GeneralSettingsRepository(context).speedTier())
        }
    }

    @Test
    fun wrongStoredTypesDefaultToStandardAndCanBeOverwritten() = runBlocking {
        val repository = GeneralSettingsRepository(context)
        store.edit { it[intPreferencesKey("speed_tier")] = 42 }
        assertEquals(SpeedTier.STANDARD, repository.speedTier())
        assertEquals(SpeedTier.STANDARD, repository.speedTierFlow.first())
        store.edit { it[booleanPreferencesKey("speed_tier")] = true }
        assertEquals(SpeedTier.STANDARD, repository.speedTier())
        assertEquals(SpeedTier.STANDARD, repository.speedTierFlow.first())
        repository.setSpeedTier(SpeedTier.FAST)
        assertEquals(SpeedTier.FAST, repository.speedTier())
    }

    @Test
    fun unknownStoredStringDefaultsToStandard() = runBlocking {
        store.edit { it[stringPreferencesKey("speed_tier")] = "future-tier" }
        assertEquals(SpeedTier.STANDARD, GeneralSettingsRepository(context).speedTier())
    }

    @Test
    fun speedPreferenceDoesNotOverwriteOtherSettings() = runBlocking {
        val repository = GeneralSettingsRepository(context)
        repository.setEnterToSend(true)
        repository.setCompactionThresholdPercent(85)
        repository.setTypewriterCharsPerSecond(75)
        repository.setMaxNetworkRetries(4)
        repository.setSpeedTier(SpeedTier.ULTRAFAST)
        repository.setSpeedTier(SpeedTier.STANDARD)
        assertEquals(true, repository.enterToSendFlow.first())
        assertEquals(85, repository.compactionThresholdPercent())
        assertEquals(75, repository.typewriterCharsPerSecond())
        assertEquals(4, repository.maxNetworkRetries())
    }
}
