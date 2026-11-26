package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import io.mockk.coVerify
import io.mockk.spyk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootRootBrokerTest {
    private data class Fixture(
        val authority: RootAuthorityCoordinator,
        val root: RootAccessState,
        val broker: ChrootRootBroker
    )

    private suspend fun TestScope.fixture(enabled: Boolean, beforeBootstrap: (() -> Unit)? = null): Fixture {
        val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val authority = RootAuthorityCoordinator(repository, backgroundScope)
        authority.setEnabled(enabled)
        val root = authority.accessSnapshot()
        val broker = if (beforeBootstrap == null) ChrootRootBroker(authority) else ChrootRootBroker(authority, beforeBootstrap)
        return Fixture(authority, root, broker)
    }

    private suspend fun rejects(block: suspend () -> Unit): ChrootRuntimeUnavailable {
        try {
            withContext(Dispatchers.IO) { withTimeout(2_000) { block() } }
            throw AssertionError("A current broker session is required")
        } catch (failure: ChrootRuntimeUnavailable) {
            return failure
        }
    }

    @Test
    fun backgroundStartWithEnabledAuthorityButNoSessionNeverEnsuresOrChecksHelper() = runTest {
        val f = fixture(enabled = true)
        val broker = spyk(f.broker)
        val helperChecks = AtomicInteger()
        val expected = ChrootRuntimeSession("a".repeat(64), f.root)
        for (operation in listOf(ChrootBrokerWire.RUNTIME, ChrootBrokerWire.INSTALL, ChrootBrokerWire.REMOVE)) {
            for (session in listOf(null, expected)) {
                assertThrows(ChrootRuntimeUnavailable::class.java) {
                    broker.start(operation, if (operation == 1) null else "0123456789abcdef01234567",
                        if (operation == 3) ChrootFileIdentity(5, 19) else null, session) {
                        helperChecks.incrementAndGet()
                    }
                }
            }
        }
        coVerify(exactly = 0) { broker.ensureFromUser(any(), any()) }
        coVerify(exactly = 0) { broker.ensureSession(any(), any()) }
        assertEquals(0, helperChecks.get())
        assertNull(broker.currentSession.value)
        assertTrue(f.authority.accessSnapshot().enabled)
    }

    @Test
    fun idleCloseWithoutTheCapturedSessionCannotBootstrapOrChangeRootAuthority() = runTest {
        val f = fixture(enabled = true)
        rejects { f.broker.closeIdleFromUser(ChrootRuntimeSession("absent", f.root)) }
        assertEquals(0, f.broker.bootstrapCount)
        assertEquals(0, f.broker.registeredProcessCount.value)
        assertNull(f.broker.currentSession.value)
        assertEquals(f.root, f.authority.accessSnapshot())
    }

    @Test
    fun disabledEnsureRejectsBeforeAndroidSocketAndNeverCallsBootstrap() = runTest {
        val f = fixture(enabled = false)
        val bootstraps = AtomicInteger()
        val failure = rejects {
            f.broker.ensureSession(f.root) {
                bootstraps.incrementAndGet()
                throw AssertionError("A disabled Root authority must never request su")
            }
        }
        assertEquals("Root runtime authorization expired", failure.message)
        assertEquals(0, bootstraps.get())
        assertNull(f.broker.currentSession.value)
        assertFalse(f.authority.accessSnapshot().enabled)
    }

    @Test
    fun staleAuthorityGenerationOrDisabledExpectedStateCannotBootstrap() = runTest {
        val f = fixture(enabled = true)
        val bootstraps = AtomicInteger()
        for (expected in listOf(f.root.copy(authorityId = "other"), f.root.copy(generation = f.root.generation + 1),
            f.root.copy(enabled = false))) {
            rejects {
                f.broker.ensureSession(expected) {
                    bootstraps.incrementAndGet()
                    throw AssertionError("A stale authority must never request su")
                }
            }
        }
        assertEquals(0, bootstraps.get())
        assertNull(f.broker.currentSession.value)
        assertEquals(f.root, f.authority.accessSnapshot())
    }

    @Test
    fun suppliedSessionObjectsWithIdenticalRootStateCannotCreateACurrentConnection() = runTest {
        val f = fixture(enabled = true)
        val first = ChrootRuntimeSession("a".repeat(64), f.root)
        val copy = ChrootRuntimeSession(first.id, f.root.copy())
        assertNotSame(first, copy)
        assertEquals(first.rootState, copy.rootState)
        assertThrows(ChrootRuntimeUnavailable::class.java) { f.broker.checkSession(first) }
        assertThrows(ChrootRuntimeUnavailable::class.java) { f.broker.checkSession(copy) }
        assertNull(f.broker.currentSession.value)
    }

    @Test
    fun revokingAuthorityDoesNotPermitOldUserSnapshotToBootstrapOrStart() = runTest {
        val f = fixture(enabled = true)
        val bootstraps = AtomicInteger()
        val helperChecks = AtomicInteger()
        val oldSession = ChrootRuntimeSession("b".repeat(64), f.root)
        f.authority.setEnabled(false)
        rejects {
            f.broker.ensureSession(f.root) {
                bootstraps.incrementAndGet()
                throw AssertionError("Revoked Root authority must never request su")
            }
        }
        assertThrows(ChrootRuntimeUnavailable::class.java) {
            f.broker.start(ChrootBrokerWire.RUNTIME, expectedSession = oldSession) { helperChecks.incrementAndGet() }
        }
        assertThrows(ChrootRuntimeUnavailable::class.java) { f.broker.checkSession(oldSession) }
        assertEquals(0, bootstraps.get())
        assertEquals(0, helperChecks.get())
        assertNull(f.broker.currentSession.value)
    }

    @Test
    fun disabledAndStalePolicySnapshotsNeverReachTheBootstrapGuard() = runTest {
        for (enabled in listOf(false, true)) {
            val guardCalls = AtomicInteger()
            val bootstraps = AtomicInteger()
            val f = fixture(enabled, beforeBootstrap = {
                guardCalls.incrementAndGet()
                throw AssertionError("Rejected policy snapshots must not consume the bootstrap guard")
            })
            val snapshots = if (enabled) listOf(
                f.root.copy(authorityId = "other"),
                f.root.copy(generation = f.root.generation + 1),
                f.root.copy(enabled = false)
            ) else listOf(f.root, f.root.copy(enabled = true))
            for (expected in snapshots) {
                rejects {
                    f.broker.ensureSession(expected) {
                        bootstraps.incrementAndGet()
                        throw AssertionError("Rejected policy snapshots must not invoke a bootstrap callback")
                    }
                }
            }
            assertEquals(0, guardCalls.get())
            assertEquals(0, bootstraps.get())
            assertEquals(0, f.broker.bootstrapCount)
            assertEquals(0, f.broker.registeredProcessCount.value)
            assertNull(f.broker.currentSession.value)
            assertEquals(f.root, f.authority.accessSnapshot())
        }
    }

    @Test
    fun missingSessionBackgroundStartAndIdleCloseNeverInvokeTheBootstrapGuardPolicy() = runTest {
        for (enabled in listOf(false, true)) {
            val guardCalls = AtomicInteger()
            val helperChecks = AtomicInteger()
            val f = fixture(enabled, beforeBootstrap = {
                guardCalls.incrementAndGet()
                throw AssertionError("Background operations must not consume the user bootstrap guard")
            })
            val broker = spyk(f.broker)
            val absent = ChrootRuntimeSession("absent-guarded-session", f.root)
            for (operation in listOf(ChrootBrokerWire.RUNTIME, ChrootBrokerWire.INSTALL, ChrootBrokerWire.REMOVE)) {
                for (expected in listOf(null, absent)) {
                    assertThrows(ChrootRuntimeUnavailable::class.java) {
                        broker.start(operation, if (operation == ChrootBrokerWire.RUNTIME) null else "0123456789abcdef01234567",
                            if (operation == ChrootBrokerWire.REMOVE) ChrootFileIdentity(5, 19) else null, expected) {
                            helperChecks.incrementAndGet()
                        }
                    }
                }
            }
            rejects { broker.closeIdleFromUser(absent) }
            coVerify(exactly = 0) { broker.ensureFromUser(any(), any()) }
            coVerify(exactly = 0) { broker.ensureSession(any(), any()) }
            assertEquals(0, guardCalls.get())
            assertEquals(0, helperChecks.get())
            assertEquals(0, broker.bootstrapCount)
            assertEquals(0, broker.registeredProcessCount.value)
            assertNull(broker.currentSession.value)
            assertEquals(f.root, f.authority.accessSnapshot())
        }
    }
}
