package com.aicode.feature.agent.domain.permission

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPermissionGrantRepositoryTest {

    companion object {
        // preferencesDataStore 的委托在进程内缓存实例，所有测试须共享同一个临时文件。
        @ClassRule
        @JvmField
        val temporaryFolder = TemporaryFolder()

        private val context: Context by lazy {
            val filesDir = temporaryFolder.newFolder("files")
            mockk<Context>().also { context ->
                every { context.applicationContext } returns context
                every { context.filesDir } returns filesDir
            }
        }
    }

    @Test
    fun grant_isScopedToSessionAndAvailableAcrossRepositoryInstances() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val otherSessionId = UUID.randomUUID().toString()
        val grant = "tool:Shizuku"

        assertFalse(repository.isAllowed(sessionId, grant))
        repository.allow(sessionId, grant)

        assertTrue(repository.isAllowed(sessionId, grant))
        assertTrue(SessionPermissionGrantRepository(context).isAllowed(sessionId, grant))
        assertFalse(repository.isAllowed(otherSessionId, grant))
    }

    @Test
    fun rootAndElevationGrants_matchOnlyTheExactCommand() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val grants = listOf("root:sudo id", "elevate:rm -rf \$(pwd)/build")

        for (grant in grants) {
            repository.allow(sessionId, grant)
        }

        for (grant in grants) {
            assertTrue(grant, repository.isAllowed(sessionId, grant))
            assertFalse(grant, repository.isAllowed(sessionId, "$grant "))
            assertFalse(grant, repository.isAllowed(sessionId, "$grant --extra"))
            assertFalse(grant, repository.isAllowed(sessionId, grant.substringBefore(':')))
        }
        assertFalse(repository.isAllowed(sessionId, "elevate:sudo id"))
        assertFalse(repository.isAllowed(sessionId, "root:rm -rf \$(pwd)/build"))
        assertFalse(repository.isAllowed(sessionId, "tool:Shizuku"))
    }

    @Test
    fun grants_accumulateAndDuplicateApprovalIsIdempotent() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()

        repository.allow(sessionId, "tool:Shizuku")
        repository.allow(sessionId, "root:sudo id")
        val storedOnce = context.preferencesDataStoreFile("session_permission_grants").readBytes()
        repository.allow(sessionId, "root:sudo id")

        assertTrue(repository.isAllowed(sessionId, "tool:Shizuku"))
        assertTrue(repository.isAllowed(sessionId, "root:sudo id"))
        assertArrayEquals(storedOnce, context.preferencesDataStoreFile("session_permission_grants").readBytes())
    }

    @Test
    fun nullOrBlankSessions_neitherStoreNorMatchGrants() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        repository.allow(UUID.randomUUID().toString(), "tool:Shizuku")
        val storeFile = context.preferencesDataStoreFile("session_permission_grants")
        val before = storeFile.readBytes()

        for (sessionId in listOf(null, "", "  ")) {
            repository.allow(sessionId, "root:sudo id")
            assertFalse(repository.isAllowed(sessionId, "root:sudo id"))
            assertFalse(repository.isAllowed(sessionId, "tool:Shizuku"))
        }

        assertArrayEquals(before, storeFile.readBytes())
    }

    @Test
    fun readWritePolicyRegression_hostWriteScopeIsMemoryOnlyAndSessionScoped() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val otherSessionId = UUID.randomUUID().toString()
        val grant = "host-write-session-v1:${UUID.randomUUID()}"
        // Materialize the legacy store first, so any broad-grant disk write is observable.
        repository.allow(sessionId, "root:sudo id")
        val storeFile = context.preferencesDataStoreFile("session_permission_grants")
        val before = storeFile.readBytes()

        assertFalse(repository.isAllowed(sessionId, grant))
        repository.allow(sessionId, grant)
        repository.allow(sessionId, grant)

        assertTrue(repository.isAllowed(sessionId, grant))
        assertFalse(repository.isAllowed(otherSessionId, grant))
        assertFalse(repository.isAllowed(sessionId, "$grant-different-source"))
        assertArrayEquals("broad host-write grants must never reach preferences", before, storeFile.readBytes())
    }

    @Test
    fun readWritePolicyRegression_freshRepositoryNeverRestoresBroadScopeButKeepsLegacyExactGrant() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val hostGrant = "host-write-session-v1:${UUID.randomUUID()}"
        val legacyGrant = "elevate:rm -rf build-${UUID.randomUUID()}"
        repository.allow(sessionId, hostGrant)
        repository.allow(sessionId, legacyGrant)
        assertTrue(repository.isAllowed(sessionId, hostGrant))

        // The injected repository is a singleton in production. A fresh owner has no process-memory
        // approvals and must not reconstruct this broad scope from the old preferences store.
        val freshRepository = SessionPermissionGrantRepository(context)
        assertFalse(freshRepository.isAllowed(sessionId, hostGrant))
        assertTrue(freshRepository.isAllowed(sessionId, legacyGrant))
    }

    @Test
    fun readWritePolicyRegression_nullAndBlankSessionsCannotRetainHostWriteScopes() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val hostGrant = "host-write-session-v1:${UUID.randomUUID()}"
        repository.allow(UUID.randomUUID().toString(), "root:sudo id")
        val storeFile = context.preferencesDataStoreFile("session_permission_grants")
        val before = storeFile.readBytes()

        for (sessionId in listOf(null, "", "  ")) {
            repository.allow(sessionId, hostGrant)
            assertFalse(repository.isAllowed(sessionId, hostGrant))
        }

        assertArrayEquals(before, storeFile.readBytes())
    }

    @Test
    fun readWritePolicyRegression_hostWritePrefixRoutesWithoutOpeningDataStore() = runTest {
        val noFileAccessContext = mockk<Context>()
        val repository = SessionPermissionGrantRepository(noFileAccessContext)
        val sessionId = UUID.randomUUID().toString()
        val grant = SessionPermissionGrantRepository.HOST_WRITE_GRANT_PREFIX + "scope"

        assertFalse(repository.isAllowed(sessionId, grant))
        repository.allow(sessionId, grant)
        assertTrue(repository.isAllowed(sessionId, grant))
        assertFalse(SessionPermissionGrantRepository(noFileAccessContext).isAllowed(sessionId, grant))
        for (session in listOf(null, "", "  ")) {
            repository.allow(session, grant)
            assertFalse(repository.isAllowed(session, grant))
        }
    }

    @Test
    fun readWritePolicyRegression_revokeSessionRemovesOnlyItsBroadAndLegacyApprovals() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val otherSessionId = UUID.randomUUID().toString()
        val broad = SessionPermissionGrantRepository.HOST_WRITE_GRANT_PREFIX + UUID.randomUUID()
        val legacy = "root:sudo id"
        for (session in listOf(sessionId, otherSessionId)) {
            repository.allow(session, broad)
            repository.allow(session, legacy)
        }

        repository.revokeSession(sessionId)
        assertFalse(repository.isAllowed(sessionId, broad))
        assertFalse(repository.isAllowed(sessionId, legacy))
        assertTrue(repository.isAllowed(otherSessionId, broad))
        assertTrue(repository.isAllowed(otherSessionId, legacy))
        assertFalse(SessionPermissionGrantRepository(context).isAllowed(sessionId, legacy))
        for (session in listOf(null, "", "  ")) repository.revokeSession(session)
        assertTrue(repository.isAllowed(otherSessionId, broad))
    }

    @Test
    fun storedGrant_containsSha256InsteadOfTheFullCommand() = runTest {
        val repository = SessionPermissionGrantRepository(context)
        val sessionId = UUID.randomUUID().toString()
        val grant = "root:sudo printf unique-${UUID.randomUUID()}"

        repository.allow(sessionId, grant)

        val stored = String(
            context.preferencesDataStoreFile("session_permission_grants").readBytes(),
            StandardCharsets.ISO_8859_1
        )
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(grant.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertFalse(stored.contains(grant))
        assertFalse(stored.contains(grant.substringAfter(':')))
        assertTrue(stored.contains(hash, ignoreCase = true))
        assertTrue(repository.isAllowed(sessionId, grant))
    }
}
