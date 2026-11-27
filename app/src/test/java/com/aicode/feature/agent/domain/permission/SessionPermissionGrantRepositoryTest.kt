package com.aicode.feature.agent.domain.permission

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPermissionGrantRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = mockk<Context>()
    private val hostGrant = SessionPermissionGrantRepository.HOST_WRITE_GRANT_PREFIX + "source-A"
    private val exactGrant = "command-session-v1:command-and-source-A"

    @Test fun grant_isScopedToSessionAndClearsWithRepositoryOwner() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        for (grant in listOf(hostGrant, exactGrant, "tool:Shizuku", "root:sudo id", "elevate:rm -rf build")) {
            assertFalse(repository.isAllowed("session-A", grant))
            repository.allow("session-A", grant)
            assertTrue(repository.isAllowed("session-A", grant))
            assertFalse(repository.isAllowed("session-B", grant))
            assertFalse(SessionPermissionGrantRepository(context).isAllowed("session-A", grant))
        }
        verify { context wasNot Called }
    }

    @Test fun rootAndElevationGrants_matchOnlyTheExactCommand() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val grants = listOf("root:sudo id", "elevate:rm -rf \$(pwd)/build")
        for (grant in grants) repository.allow("session", grant)
        for (grant in grants) {
            assertTrue(repository.isAllowed("session", grant))
            assertFalse(repository.isAllowed("session", "$grant "))
            assertFalse(repository.isAllowed("session", "$grant --extra"))
            assertFalse(repository.isAllowed("session", grant.substringBefore(':')))
        }
        assertFalse(repository.isAllowed("session", "elevate:sudo id"))
        assertFalse(repository.isAllowed("session", "root:rm -rf \$(pwd)/build"))
        assertFalse(repository.isAllowed("session", "tool:Shizuku"))
    }

    @Test fun grants_accumulateAndDuplicateApprovalIsIdempotentWithoutDiskAccess() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        repository.allow("session", hostGrant)
        repository.allow("session", exactGrant)
        repository.allow("session", exactGrant)
        assertTrue(repository.isAllowed("session", hostGrant))
        assertTrue(repository.isAllowed("session", exactGrant))
        repository.revokeSession("session")
        assertFalse(repository.isAllowed("session", hostGrant))
        assertFalse(repository.isAllowed("session", exactGrant))
        verify { context wasNot Called }
    }

    @Test fun nullOrBlankSessions_neitherStoreNorMatchGrants() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        repository.allow("session", exactGrant)
        for (session in listOf(null, "", "  ")) {
            repository.allow(session, exactGrant)
            assertFalse(repository.isAllowed(session, exactGrant))
        }
        assertTrue(repository.isAllowed("session", exactGrant))
        verify { context wasNot Called }
    }

    @Test fun readWritePolicyRegression_hostWriteScopeIsMemoryOnlyAndSessionScoped() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        repository.allow("session", hostGrant)
        repository.allow("session", hostGrant)
        assertTrue(repository.isAllowed("session", hostGrant))
        assertFalse(repository.isAllowed("other-session", hostGrant))
        assertFalse(repository.isAllowed("session", "$hostGrant-different-source"))
        verify { context wasNot Called }
    }

    @Test fun readWritePolicyRegression_freshRepositoryRestoresNeitherBroadNorExactGrant() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        for (grant in listOf(hostGrant, exactGrant, "root:sudo id")) repository.allow("session", grant)
        val fresh = SessionPermissionGrantRepository(context)
        for (grant in listOf(hostGrant, exactGrant, "root:sudo id")) {
            assertTrue(repository.isAllowed("session", grant))
            assertFalse(fresh.isAllowed("session", grant))
        }
    }

    @Test fun readWritePolicyRegression_nullAndBlankSessionsCannotRetainHostWriteScopes() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        for (session in listOf(null, "", "  ")) {
            repository.allow(session, hostGrant)
            assertFalse(repository.isAllowed(session, hostGrant))
        }
        verify { context wasNot Called }
    }

    @Test fun readWritePolicyRegression_hostWritePrefixRoutesWithoutOpeningDataStore() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        assertFalse(repository.isAllowed("session", hostGrant))
        repository.allow("session", hostGrant)
        assertTrue(repository.isAllowed("session", hostGrant))
        assertFalse(SessionPermissionGrantRepository(context).isAllowed("session", hostGrant))
        verify { context wasNot Called }
    }

    @Test fun readWritePolicyRegression_revokeSessionRemovesOnlyItsBroadAndExactApprovals() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        for (session in listOf("session-A", "session-B")) {
            repository.allow(session, hostGrant)
            repository.allow(session, exactGrant)
        }
        repository.revokeSession("session-A")
        for (grant in listOf(hostGrant, exactGrant)) {
            assertFalse(repository.isAllowed("session-A", grant))
            assertTrue(repository.isAllowed("session-B", grant))
        }
        for (session in listOf(null, "", "  ")) repository.revokeSession(session)
        assertTrue(repository.isAllowed("session-B", hostGrant))
    }

    @Test fun storedLegacyFingerprintsAreNeverRestoredOrRewritten() = runTest {
        val filesDir = temporaryFolder.newFolder("files")
        val file = File(filesDir, "datastore/session_permission_grants.preferences_pb")
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val legacy = "root:sudo private-command-argument"
        val hash = MessageDigest.getInstance("SHA-256").digest(legacy.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val owner = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + owner)) { file }
        store.edit { it[stringSetPreferencesKey("session_session-A")] = setOf(hash) }
        owner.cancelAndJoin()
        val before = file.readBytes()
        val repository = SessionPermissionGrantRepository(context)
        assertFalse(repository.isAllowed("session-A", legacy))
        repository.allow("session-A", legacy)
        assertTrue(repository.isAllowed("session-A", legacy))
        assertFalse(SessionPermissionGrantRepository(context).isAllowed("session-A", legacy))
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test fun blankGrantCannotBeRemembered() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        for (grant in listOf("", " ", "\t")) {
            repository.allow("session", grant)
            assertFalse(repository.isAllowed("session", grant))
        }
    }
}
