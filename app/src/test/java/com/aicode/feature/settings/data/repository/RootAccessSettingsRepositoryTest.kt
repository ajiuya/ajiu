package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

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

        first.setEnabled(true)
        assertEquals(true, first.state.value.enabled)
        assertEquals(1L, first.state.value.generation)
        first.setEnabled(true)
        assertEquals(1L, first.snapshot().generation)

        val second = RootAccessSettingsRepository(context)
        assertEquals(first.snapshot(), second.snapshot())
        second.setEnabled(false)
        assertEquals(false, first.snapshot().enabled)
        assertEquals(2L, first.snapshot().generation)
        second.setEnabled(false)
        assertEquals(3L, second.snapshot().generation)
        second.setEnabled(true)
        assertEquals(4L, first.snapshot().generation)
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
}
