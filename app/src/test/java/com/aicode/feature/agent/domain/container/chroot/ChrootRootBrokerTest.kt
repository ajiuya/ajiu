package com.aicode.feature.agent.domain.container.chroot

import android.net.LocalSocket
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private class ExecutorGate(executor: ExecutorService) : AutoCloseable {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        init {
            executor.execute {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Broker test queue gate was not released" }
            }
        }
        fun awaitEntered() = assertTrue("Broker queue did not reach the gate", entered.await(5, TimeUnit.SECONDS))
        override fun close() = release.countDown()
    }

    /** Uses the production Connection and executor; only the Android socket/bootstrap are replaced. */
    private class ConnectedFixture(val fixture: Fixture, replyId: Long = 1L) : AutoCloseable {
        val session = ChrootRuntimeSession("queue-test-session", fixture.root)
        val socket = mockk<LocalSocket>(relaxed = true)
        val bootstrap = mockk<Process>(relaxed = true)
        val output = ByteArrayOutputStream()
        val revocations = AtomicInteger()
        val authorityGate: Any = field(fixture.authority, "gate")
        private val connectionType = ChrootRootBroker::class.java.declaredClasses.single { it.simpleName == "Connection" }
        private val connection: Any
        val executor: ExecutorService
        private val registration: AutoCloseable

        init {
            // A valid per-request FAILED reply avoids Android descriptor attachment while still
            // exercising send/receive, request identity validation and normal request removal.
            val reply = ChrootBrokerWire.frame(ChrootBrokerWire.FAILED,
                ByteBuffer.allocate(16).putLong(replyId).putInt(1).putInt(5).array())
            every { socket.outputStream } returns output
            every { socket.inputStream } returns ByteArrayInputStream(reply)
            every { socket.ancillaryFileDescriptors } returns null
            every { bootstrap.isAlive } returns true
            connection = connectionType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(fixture.broker, session, socket, bootstrap)
            executor = field(connection, "executor")
            fixture.authority.withLock {
                field<AtomicReference<Any?>>(fixture.broker, "connection").set(connection)
                field<MutableStateFlow<ChrootRuntimeSession?>>(fixture.broker, "_currentSession").value = session
            }
            registration = fixture.broker.registerSessionRevocation { revocations.incrementAndGet() }
        }

        fun awaitQueue() { executor.submit {}.get(5, TimeUnit.SECONDS) }

        override fun close() {
            registration.close()
            connectionType.let { type ->
                ChrootRootBroker::class.java.getDeclaredMethod("invalidate", type).apply { isAccessible = true }
                    .invoke(fixture.broker, connection)
            }
            assertTrue("Broker executor must terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            private fun <T> field(target: Any, name: String): T =
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
        }
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
    fun runtimeSourceExpiryInTheActualQueueCancelsOnlyThatRequestAndTheNextStillDispatches() = runTest {
        for (throws in listOf(false, true)) {
            val f = fixture(enabled = true)
            ConnectedFixture(f).use { live ->
                val current = AtomicBoolean(true)
                val helperChecks = AtomicInteger()
                val guardChecks = AtomicInteger()
                val nextChecks = AtomicInteger()
                val head = ExecutorGate(live.executor)
                var afterTarget: ExecutorGate? = null
                try {
                    head.awaitEntered()
                    val target = f.broker.startRuntimeChecked(checkHelper = { helperChecks.incrementAndGet() }, canDispatch = {
                        assertTrue("Dispatch guard must hold the real authority monitor", Thread.holdsLock(live.authorityGate))
                        assertEquals(1, helperChecks.get())
                        guardChecks.incrementAndGet()
                        if (!current.get() && throws) throw CancellationException("Queued source expired")
                        current.get()
                    }) as ChrootBrokerProcess
                    val targetFinished = ExecutorGate(live.executor).also { afterTarget = it }
                    val next = f.broker.startRuntimeChecked(checkHelper = {}, canDispatch = {
                        assertTrue(Thread.holdsLock(live.authorityGate))
                        nextChecks.incrementAndGet()
                        true
                    }) as ChrootBrokerProcess
                    assertEquals(2, f.broker.registeredProcessCount.value)
                    assertEquals(0, helperChecks.get())
                    assertEquals(0, guardChecks.get())
                    assertEquals(0, nextChecks.get())
                    assertEquals(0, live.output.size())
                    current.set(false)
                    head.close()
                    targetFinished.awaitEntered()

                    assertEquals(1, guardChecks.get())
                    assertTrue(target.wasCancelledBeforeDispatch)
                    assertTrue(target.cancelled.get())
                    assertFalse(target.isAlive)
                    assertFalse(target.tryDispatch())
                    assertEquals(0L, target.requestId)
                    assertEquals(0, live.output.size())
                    assertEquals(1, f.broker.registeredProcessCount.value)
                    assertTrue(next.isAlive)
                    assertFalse(next.cancelled.get())
                    assertFalse(next.wasCancelledBeforeDispatch)
                    assertEquals(0, nextChecks.get())
                    assertSame(live.session, f.broker.currentSession.value)
                    assertEquals(0, live.revocations.get())
                    verify(exactly = 0) { live.socket.close() }

                    targetFinished.close()
                    live.awaitQueue()
                    assertEquals(1, nextChecks.get())
                    assertEquals(1L, next.requestId)
                    assertFalse(next.wasCancelledBeforeDispatch)
                    assertFalse(next.cancelled.get())
                    assertFalse(next.isAlive) // The valid FAILED reply settles only this request.
                    assertArrayEquals(ChrootBrokerWire.frame(ChrootBrokerWire.START,
                        ChrootBrokerWire.start(1, ChrootBrokerWire.RUNTIME)), live.output.toByteArray())
                    assertEquals(0, f.broker.registeredProcessCount.value)
                    assertSame(live.session, f.broker.currentSession.value)
                    f.broker.checkSession(live.session)
                    assertTrue(live.bootstrap.isAlive)
                    assertEquals(0, live.revocations.get())
                    verify(exactly = 0) { live.socket.close() }
                    verify(exactly = 0) { live.bootstrap.destroy() }
                } finally {
                    head.close()
                    afterTarget?.close()
                }
            }
        }
    }

    @Test
    fun helperRecoveryCheckAndProtocolFailuresStillInvalidateTheSharedConnection() = runTest {
        for (failureAt in listOf("helper", "recovery", "protocol")) {
            val f = fixture(enabled = true)
            ConnectedFixture(f, replyId = if (failureAt == "protocol") 99L else 1L).use { live ->
                val closed = CountDownLatch(1)
                val revoked = f.broker.registerSessionRevocation { closed.countDown() }
                val head = ExecutorGate(live.executor)
                try {
                    head.awaitEntered()
                    val target = (if (failureAt == "recovery") {
                        f.broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, "0123456789abcdef01234567",
                            ChrootFileIdentity(5, 19), live.session, {},
                            { throw CancellationException("Recovery capture expired") })
                    } else {
                        f.broker.startRuntimeChecked(checkHelper = {
                            if (failureAt == "helper") throw IOException("Helper capture expired")
                        }, canDispatch = { true })
                    }) as ChrootBrokerProcess
                    val nextChecks = AtomicInteger()
                    val next = f.broker.startRuntimeChecked(checkHelper = {}, canDispatch = {
                        nextChecks.incrementAndGet()
                        true
                    }) as ChrootBrokerProcess
                    head.close()
                    assertTrue("Failure must revoke the shared session", closed.await(5, TimeUnit.SECONDS))
                    assertTrue(live.executor.awaitTermination(5, TimeUnit.SECONDS))
                    assertFalse(target.wasCancelledBeforeDispatch)
                    assertFalse(next.wasCancelledBeforeDispatch)
                    assertFalse(target.isAlive)
                    assertFalse(next.isAlive)
                    assertEquals(0, nextChecks.get())
                    assertEquals(0L, next.requestId)
                    assertNull(f.broker.currentSession.value)
                    assertEquals(0, f.broker.registeredProcessCount.value)
                    assertEquals(1, live.revocations.get())
                    assertEquals(if (failureAt == "protocol") 1L else 0L, target.requestId)
                    if (failureAt != "protocol") assertEquals(0, live.output.size())
                    verify(exactly = 1) { live.socket.close() }
                } finally {
                    head.close()
                    revoked.close()
                }
            }
        }
    }

    @Test
    fun checkedRuntimeWithoutAnIssuedSessionRunsNeitherCallback() = runTest {
        val f = fixture(enabled = true)
        val checks = AtomicInteger()
        assertThrows(ChrootRuntimeUnavailable::class.java) {
            f.broker.startRuntimeChecked(checkHelper = { checks.incrementAndGet() }, canDispatch = {
                checks.incrementAndGet()
                true
            })
        }
        assertEquals(0, checks.get())
        assertEquals(0, f.broker.bootstrapCount)
        assertEquals(0, f.broker.registeredProcessCount.value)
        assertNull(f.broker.currentSession.value)
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
    fun checkedRecoveryWithoutAnIssuedSessionCannotRunEitherCallbackOrBootstrap() = runTest {
        for (enabled in listOf(false, true)) {
            val f = fixture(enabled)
            val expected = ChrootRuntimeSession("not-issued", f.root)
            val helperChecks = AtomicInteger()
            val sourceChecks = AtomicInteger()
            assertThrows(ChrootRuntimeUnavailable::class.java) {
                f.broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, "0123456789abcdef01234567",
                    ChrootFileIdentity(5, 19), expected,
                    checkHelper = { helperChecks.incrementAndGet() },
                    checkCurrent = { sourceChecks.incrementAndGet() })
            }
            assertEquals(0, helperChecks.get())
            assertEquals(0, sourceChecks.get())
            assertEquals(0, f.broker.bootstrapCount)
            assertEquals(0, f.broker.registeredProcessCount.value)
            assertNull(f.broker.currentSession.value)
            assertEquals(f.root, f.authority.accessSnapshot())
        }
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
