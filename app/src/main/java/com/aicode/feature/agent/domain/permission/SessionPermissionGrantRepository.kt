package com.aicode.feature.agent.domain.permission

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionPermissionDataStore by preferencesDataStore(
    name = "session_permission_grants",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class SessionPermissionGrantRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun isAllowed(sessionId: String?, grant: String): Boolean {
        if (sessionId.isNullOrBlank()) return false
        return fingerprint(grant) in (context.sessionPermissionDataStore.data.first()[key(sessionId)] ?: emptySet())
    }

    suspend fun allow(sessionId: String?, grant: String) {
        if (sessionId.isNullOrBlank()) return
        context.sessionPermissionDataStore.edit { preferences ->
            val key = key(sessionId)
            preferences[key] = preferences[key].orEmpty() + fingerprint(grant)
        }
    }

    private fun key(sessionId: String) = stringSetPreferencesKey("session_$sessionId")

    // 命令中可能含凭据，持久化时只保存授权指纹。
    private fun fingerprint(grant: String): String =
        MessageDigest.getInstance("SHA-256").digest(grant.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
