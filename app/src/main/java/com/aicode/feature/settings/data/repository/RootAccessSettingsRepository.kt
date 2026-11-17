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
    scope: CoroutineScope
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        rootAccessDataStore(context),
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val GENERATION = longPreferencesKey("generation")
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
                        _state.value = dataStore.data.first().toState()
                        loaded.complete(Unit)
                    }
                }
            } catch (e: CancellationException) {
                loaded.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                mutex.withLock {
                    _state.value = RootAccessState(false, _state.value.generation)
                    loaded.completeExceptionally(e)
                }
            }
        }
    }

    suspend fun snapshot(): RootAccessState {
        loaded.await()
        return mutex.withLock {
            _state.value = dataStore.data.first().toState()
            _state.value
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        loaded.await()
        mutex.withLock {
            val updated = dataStore.edit { preferences ->
                val previous = preferences.toState()
                preferences[ENABLED] = enabled
                preferences[GENERATION] = if (!enabled || previous.enabled != enabled) {
                    Math.addExact(previous.generation, 1L)
                } else {
                    previous.generation
                }
            }
            _state.value = updated.toState()
        }
    }

    private fun Preferences.toState() = RootAccessState(
        enabled = this[ENABLED] ?: false,
        generation = this[GENERATION] ?: 0L
    )
}
