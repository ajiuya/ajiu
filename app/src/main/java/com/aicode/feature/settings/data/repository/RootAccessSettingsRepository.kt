package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.feature.agent.domain.root.RootAccessState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val rootAccessDataStores = mutableMapOf<String, DataStore<Preferences>>()

private fun rootAccessDataStore(context: Context): DataStore<Preferences> {
    val file = File(context.noBackupFilesDir, "root_access.preferences_pb")
    return synchronized(rootAccessDataStores) {
        rootAccessDataStores.getOrPut(file.absolutePath) {
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { file }
            )
        }
    }
}

@Singleton
class RootAccessSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val authorityIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        rootAccessDataStore(context),
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val GENERATION = longPreferencesKey("generation")
        val AUTHORITY_ID = stringPreferencesKey("authority_id")
    }

    private val _state = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = _state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val mutex = Mutex()

    init {
        scope.launch {
            try {
                dataStore.data.collect {
                    mutex.withLock {
                        // edit 可能先于旧的 collect 回调完成，锁内重新读取避免旧状态倒灌。
                        _state.value = readStateLocked()
                        loaded.complete(Unit)
                    }
                }
            } catch (e: CancellationException) {
                loaded.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                mutex.withLock {
                    _state.value = _state.value.copy(enabled = false)
                    loaded.completeExceptionally(e)
                }
            }
        }
    }

    suspend fun snapshot(): RootAccessState {
        loaded.await()
        return mutex.withLock {
            try {
                _state.value = readStateLocked()
                _state.value
            } catch (e: Exception) {
                _state.value = _state.value.copy(enabled = false)
                throw e
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        loaded.await()
        mutex.withLock {
            try {
                val updated = dataStore.edit { preferences ->
                    if (preferences[AUTHORITY_ID].isNullOrBlank()) {
                        preferences[AUTHORITY_ID] = authorityIdFactory().also { check(it.isNotBlank()) }
                    }
                    val previous = preferences.toState()
                    preferences[ENABLED] = enabled
                    preferences[GENERATION] = if (!enabled || previous.enabled != enabled) {
                        Math.addExact(previous.generation, 1L)
                    } else {
                        previous.generation
                    }
                }
                _state.value = updated.toState()
            } catch (e: Exception) {
                _state.value = _state.value.copy(enabled = false)
                throw e
            }
        }
    }

    private suspend fun readStateLocked(): RootAccessState {
        val preferences = dataStore.data.first()
        if (!preferences[AUTHORITY_ID].isNullOrBlank()) return preferences.toState()
        return dataStore.edit { current ->
            if (current[AUTHORITY_ID].isNullOrBlank()) {
                current[AUTHORITY_ID] = authorityIdFactory().also { check(it.isNotBlank()) }
            }
        }.toState()
    }

    private fun Preferences.toState() = RootAccessState(
        enabled = this[ENABLED] ?: false,
        generation = this[GENERATION] ?: 0L,
        authorityId = checkNotNull(this[AUTHORITY_ID]).also { check(it.isNotBlank()) }
    )
}
