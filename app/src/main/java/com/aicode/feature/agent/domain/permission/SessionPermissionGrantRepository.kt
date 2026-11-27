package com.aicode.feature.agent.domain.permission

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionPermissionGrantRepository @Inject constructor(
    @param:ApplicationContext context: Context
) {
    companion object {
        const val HOST_WRITE_GRANT_PREFIX = "host-write-session-v1:"
    }

    private val grants = mutableMapOf<String, MutableSet<String>>()

    suspend fun isAllowed(sessionId: String?, grant: String): Boolean {
        if (sessionId.isNullOrBlank() || grant.isBlank()) return false
        return synchronized(grants) { fingerprint(grant) in grants[sessionId].orEmpty() }
    }

    suspend fun allow(sessionId: String?, grant: String) {
        if (sessionId.isNullOrBlank() || grant.isBlank()) return
        synchronized(grants) { grants.getOrPut(sessionId) { mutableSetOf() }.add(fingerprint(grant)) }
    }

    suspend fun revokeSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        synchronized(grants) { grants.remove(sessionId) }
    }

    private fun fingerprint(grant: String): String =
        MessageDigest.getInstance("SHA-256").digest(grant.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
