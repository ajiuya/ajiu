package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RootAccessSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsOffAndPersistsGenerationInDeviceLocalStore() = runTest {
        val noBackup = temporaryFolder.newFolder("no-backup")
        val files = temporaryFolder.newFolder("files")
        val context = mockk<Context> {
            every { noBackupFilesDir } returns noBackup
            every { filesDir } returns files
        }
        val first = RootAccessSettingsRepository(context)
        assertEquals(false, first.snapshot().enabled)
        assertEquals(0L, first.snapshot().generation)
        val authorityId = first.snapshot().authorityId
        assertTrue(authorityId.isNotBlank())
        assertEquals(authorityId, UUID.fromString(authorityId).toString())

        first.setEnabled(true)
        assertEquals(true, first.state.value.enabled)
        assertEquals(1L, first.state.value.generation)
        first.setEnabled(true)
        assertEquals(1L, first.snapshot().generation)

        val second = RootAccessSettingsRepository(context)
        assertEquals(first.snapshot(), second.snapshot())
        assertEquals(authorityId, second.snapshot().authorityId)
        second.setEnabled(false)
        assertEquals(false, first.snapshot().enabled)
        assertEquals(2L, first.snapshot().generation)
        second.setEnabled(false)
        assertEquals(3L, second.snapshot().generation)
        second.setEnabled(true)
        assertEquals(4L, first.snapshot().generation)
        assertEquals(authorityId, first.snapshot().authorityId)
        assertEquals(authorityId, second.snapshot().authorityId)
        assertTrue(noBackup.resolve("root_access.preferences_pb").isFile)
        assertTrue(files.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun snapshotAndWritesWaitForInitialLoadRatherThanUsingInitialState() = runTest {
        val initial = CompletableDeferred<Preferences>()
        val stored = MutableStateFlow<Preferences>(emptyPreferences())
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                initial.await()
                stored.collect { emit(it) }
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                stored.value = transform(stored.value)
                return stored.value
            }
        }
        val repository = RootAccessSettingsRepository(dataStore, backgroundScope)
        val snapshot = async { repository.snapshot() }
        val update = async { repository.setEnabled(true) }
        runCurrent()
        assertFalse(snapshot.isCompleted)
        assertFalse(update.isCompleted)
        assertFalse(repository.state.value.enabled)

        val persisted = mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 41L
        )
        stored.value = persisted
        initial.complete(persisted)
        runCurrent()
        assertEquals(true, snapshot.await().enabled)
        assertEquals(41L, snapshot.await().generation)
        update.await()
        assertEquals(41L, repository.snapshot().generation)
        val authorityId = snapshot.await().authorityId
        assertTrue(authorityId.isNotBlank())
        assertEquals(authorityId, repository.snapshot().authorityId)
        assertEquals(authorityId, stored.value[stringPreferencesKey("authority_id")])
    }

    @Test
    fun unreadableStoreDoesNotReportAnEnabledSnapshot() = runTest {
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("unavailable")
        }
        val repository = RootAccessSettingsRepository(dataStore, backgroundScope)
        try {
            repository.snapshot()
            throw AssertionError("Read failure must not produce a snapshot")
        } catch (_: IOException) {
            assertFalse(repository.state.value.enabled)
        }
    }

    @Test
    fun differentNoBackupStoresHaveDifferentAuthoritiesAtTheSameGeneration() = runTest {
        val firstNoBackup = temporaryFolder.newFolder("first-no-backup")
        val secondNoBackup = temporaryFolder.newFolder("second-no-backup")
        val files = temporaryFolder.newFolder("shared-files")
        val firstContext = mockk<Context> {
            every { noBackupFilesDir } returns firstNoBackup
            every { filesDir } returns files
        }
        val secondContext = mockk<Context> {
            every { noBackupFilesDir } returns secondNoBackup
            every { filesDir } returns files
        }
        val first = RootAccessSettingsRepository(firstContext)
        val second = RootAccessSettingsRepository(secondContext)
        first.setEnabled(true)
        second.setEnabled(true)
        val firstState = first.snapshot()
        val secondState = second.snapshot()
        assertTrue(firstState.enabled)
        assertTrue(secondState.enabled)
        assertEquals(firstState.generation, secondState.generation)
        assertTrue(firstState.authorityId.isNotBlank())
        assertTrue(secondState.authorityId.isNotBlank())
        assertNotEquals(firstState.authorityId, secondState.authorityId)
        assertTrue(firstNoBackup.resolve("root_access.preferences_pb").isFile)
        assertTrue(secondNoBackup.resolve("root_access.preferences_pb").isFile)
        assertTrue(files.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun legacySettingsAddOnlyAuthorityAndPreserveEnabledGenerationAndOtherPreferences() = runTest {
        val legacy = mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 41L,
            stringPreferencesKey("retained") to "value"
        )
        val store = RootMemoryDataStore(legacy)
        var generated = 0
        val repository = RootAccessSettingsRepository(store, backgroundScope) {
            generated++
            UUID.randomUUID().toString()
        }
        val snapshot = repository.snapshot()
        assertTrue(snapshot.enabled)
        assertEquals(41L, snapshot.generation)
        assertEquals(snapshot.authorityId, UUID.fromString(snapshot.authorityId).toString())
        assertEquals(
            legacy.toMutablePreferences().apply { this[stringPreferencesKey("authority_id")] = snapshot.authorityId },
            store.data.value
        )
        assertEquals(snapshot, repository.snapshot())
        assertEquals(snapshot, RootAccessSettingsRepository(store, backgroundScope) {
            throw AssertionError("Persisted authority must be reused")
        }.snapshot())
        repository.setEnabled(true)
        runCurrent()
        assertEquals(snapshot, repository.snapshot())
        assertEquals(1, generated)
    }

    @Test
    fun concurrentRepositoriesPersistOneAuthorityAndReuseItForEverySnapshot() = runTest {
        val store = RootMemoryDataStore()
        var generated = 0
        val generate = {
            generated++
            UUID.randomUUID().toString()
        }
        val first = RootAccessSettingsRepository(store, backgroundScope, generate)
        val second = RootAccessSettingsRepository(store, backgroundScope, generate)
        val firstLoad = async { first.snapshot() }
        val secondLoad = async { second.snapshot() }
        val snapshot = firstLoad.await()
        assertEquals(snapshot, secondLoad.await())
        assertFalse(snapshot.enabled)
        assertEquals(0L, snapshot.generation)
        assertTrue(snapshot.authorityId.isNotBlank())
        runCurrent()
        assertEquals(snapshot, first.snapshot())
        assertEquals(snapshot, second.snapshot())
        assertEquals(snapshot.authorityId, store.data.value[stringPreferencesKey("authority_id")])
        assertEquals(1, generated)
    }

    @Test
    fun authorityPersistenceFailureKeepsInitializationOffAndPreventsWrites() = runTest {
        val legacy = mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 23L
        )
        val store = RootMemoryDataStore(legacy).also { it.failWrites = true }
        val repository = RootAccessSettingsRepository(store, backgroundScope)
        try {
            repository.snapshot()
            throw AssertionError("Unpersisted authority must not produce a snapshot")
        } catch (_: IOException) {
            assertEquals(RootAccessState(), repository.state.value)
            assertEquals(legacy, store.data.value)
        }
        store.failWrites = false
        try {
            repository.setEnabled(true)
            throw AssertionError("Failed initialization must not allow writes")
        } catch (_: IOException) {
            assertFalse(repository.state.value.enabled)
            assertEquals(legacy, store.data.value)
        }
    }

    @Test
    fun authorityGenerationFailureKeepsInitializationOffAndPreventsWrites() = runTest {
        val legacy = mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 29L
        )
        val store = RootMemoryDataStore(legacy)
        var generated = 0
        val repository = RootAccessSettingsRepository(store, backgroundScope) {
            generated++
            throw IOException("entropy unavailable")
        }
        try {
            repository.snapshot()
            throw AssertionError("Authority generation failure must not produce a snapshot")
        } catch (_: IOException) {
            assertEquals(RootAccessState(), repository.state.value)
            assertEquals(legacy, store.data.value)
        }
        try {
            repository.setEnabled(true)
            throw AssertionError("Failed initialization must not allow writes")
        } catch (_: IOException) {
            assertFalse(repository.state.value.enabled)
            assertEquals(legacy, store.data.value)
        }
        assertEquals(1, generated)
    }
}
