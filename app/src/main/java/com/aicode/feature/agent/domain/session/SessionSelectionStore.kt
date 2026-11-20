package com.aicode.feature.agent.domain.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.sessionSelectionDataStore by preferencesDataStore(
    name = "session_selection",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class SessionSelectionStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.sessionSelectionDataStore)

    suspend fun load(workspace: String): String? {
        if (workspace.isBlank()) return null
        return dataStore.data.first()[key(workspace)]
    }

    suspend fun save(sessionId: String, workspace: String) {
        if (sessionId.isBlank() || workspace.isBlank()) return
        dataStore.edit { it[key(workspace)] = sessionId }
    }

    suspend fun clear(workspace: String) {
        if (workspace.isBlank()) return
        dataStore.edit { it.remove(key(workspace)) }
    }

    private fun key(workspace: String) = stringPreferencesKey("local_session:$workspace")
}
