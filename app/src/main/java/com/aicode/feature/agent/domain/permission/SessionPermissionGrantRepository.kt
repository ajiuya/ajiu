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
    companion object {
        /** Broad host-write scopes are never read from or written to the legacy persistent store. */
        const val HOST_WRITE_GRANT_PREFIX = "host-write-session-v1:"
    }

    // Instance-owned memory: Hilt supplies a process-lifetime singleton, but a new owner/restart
    // cannot reconstruct a broad approval from preferences. Session deletion can revoke it below.
    private val ephemeralGrants = mutableMapOf<String, MutableSet<String>>()

    suspend fun isAllowed(sessionId: String?, grant: String): Boolean {
        if (sessionId.isNullOrBlank()) return false
        if (grant.startsWith(HOST_WRITE_GRANT_PREFIX)) {
            return synchronized(ephemeralGrants) { fingerprint(grant) in ephemeralGrants[sessionId].orEmpty() }
        }
        return fingerprint(grant) in (context.sessionPermissionDataStore.data.first()[key(sessionId)] ?: emptySet())
    }

    suspend fun allow(sessionId: String?, grant: String) {
        if (sessionId.isNullOrBlank()) return
        if (grant.startsWith(HOST_WRITE_GRANT_PREFIX)) {
            synchronized(ephemeralGrants) { ephemeralGrants.getOrPut(sessionId) { mutableSetOf() }.add(fingerprint(grant)) }
            return
        }
        context.sessionPermissionDataStore.edit { preferences ->
            val key = key(sessionId)
            preferences[key] = preferences[key].orEmpty() + fingerprint(grant)
        }
    }

    /** Call when a session ends; revokes both process-only scopes and legacy exact approvals. */
    suspend fun revokeSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        synchronized(ephemeralGrants) { ephemeralGrants.remove(sessionId) }
        context.sessionPermissionDataStore.edit { preferences -> preferences.remove(key(sessionId)) }
    }

    private fun key(sessionId: String) = stringSetPreferencesKey("session_$sessionId")

    // 命令中可能含凭据，持久化时只保存授权指纹。
    private fun fingerprint(grant: String): String =
        MessageDigest.getInstance("SHA-256").digest(grant.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
