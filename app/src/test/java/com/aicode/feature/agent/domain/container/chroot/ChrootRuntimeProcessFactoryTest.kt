package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootRuntimeProcessFactoryTest {
    private val instanceId = "0123456789abcdef01234567"
    private val identity = ChrootFileIdentity(5, 19)
    private val root = RootAccessState(authorityId = "factory-test", generation = 1, enabled = true)

    @Test
    fun runtimeCheckedStartForwardsTheSameLiveCallbackWithoutEvaluatingItAtEnqueue() {
        val broker = mockk<ChrootRootBroker>()
        val factory = spyk(ChrootRuntimeProcessFactory(mockk<Context>(), broker), recordPrivateCalls = true)
        val helper = ChrootHelperIdentity("/data/app/test/lib/arm64/libajiu_chroot_runtime.so", 7, 23, 4096, "a".repeat(64))
        val process = mockk<Process>()
        val current = AtomicBoolean(true)
        val calls = AtomicInteger()
        val canDispatch: () -> Boolean = { calls.incrementAndGet(); current.get() }
        val queuedGuard = slot<() -> Boolean>()
        val helperCheck = slot<() -> Unit>()
        every { factory["checkCaptured"](helper, ChrootRuntimeProcessFactory.HELPER_NAME) } returns Unit
        every { broker.startRuntimeChecked(capture(helperCheck), capture(queuedGuard)) } returns process

        assertSame(process, factory.startChecked(helper, canDispatch))
        assertSame(canDispatch, queuedGuard.captured)
        assertEquals(0, calls.get())
        verify(exactly = 0) { factory["checkCaptured"](helper, ChrootRuntimeProcessFactory.HELPER_NAME) }
        current.set(false)
        helperCheck.captured()
        assertFalse(queuedGuard.captured())
        assertEquals(1, calls.get())
        verify(exactly = 1) { factory["checkCaptured"](helper, ChrootRuntimeProcessFactory.HELPER_NAME) }
        verify(exactly = 0) { broker.start(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { broker.startChecked(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun defaultCheckedStartKeepsOldFactoriesCompatibleAndRejectsBeforeStarting() {
        val helper = ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
        val process = mockk<Process>()
        val starts = AtomicInteger()
        val startedHelpers = mutableListOf<ChrootHelperIdentity>()
        val factory = object : ChrootProcessFactory {
            override fun captureHelper() = helper
            override fun start(helper: ChrootHelperIdentity): Process {
                startedHelpers.add(helper)
                starts.incrementAndGet()
                return process
            }
        }
        assertThrows(ChrootRuntimeUnavailable::class.java) { factory.startChecked(helper) { false } }
        val changed = CancellationException("Source changed")
        assertSame(changed, assertThrows(CancellationException::class.java) {
            factory.startChecked(helper) { throw changed }
        })
        assertEquals(0, starts.get())
        var checked = false
        assertSame(process, factory.startChecked(helper) { checked = true; true })
        assertTrue(checked)
        assertEquals(1, starts.get())
        assertSame(helper, startedHelpers.single())
    }

    @Test
    fun recoveryRemovalChecksTheFixedInstallerAndForwardsTheExactSession() {
        val broker = mockk<ChrootRootBroker>()
        val factory = spyk(ChrootRuntimeProcessFactory(mockk<Context>(), broker), recordPrivateCalls = true)
        val session = ChrootRuntimeSession("reviewed-session", root)
        val process = mockk<Process>()
        val helper = ChrootHelperIdentity("/data/app/test/lib/arm64/libajiu_chroot_installer.so", 7, 23, 4096, "a".repeat(64))
        every { factory["capture"](ChrootRuntimeProcessFactory.INSTALLER_NAME, "installer") } returns helper
        every { factory["checkCaptured"](helper, ChrootRuntimeProcessFactory.INSTALLER_NAME) } returns Unit
        val checks = AtomicInteger()
        val checkCurrent: () -> Unit = { checks.incrementAndGet(); Unit }
        every { broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, instanceId, identity, refEq(session), any(), refEq(checkCurrent)) } answers {
            arg<() -> Unit>(4).invoke()
            lastArg<() -> Unit>().invoke()
            process
        }

        assertSame(process, factory.startRecoveryRemoval(instanceId, identity, session, checkCurrent))
        assertEquals(1, checks.get())

        verify(exactly = 1) { broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, instanceId, identity, refEq(session), any(), refEq(checkCurrent)) }
        verify(exactly = 1) { factory["capture"](ChrootRuntimeProcessFactory.INSTALLER_NAME, "installer") }
        verify(exactly = 1) { factory["checkCaptured"](helper, ChrootRuntimeProcessFactory.INSTALLER_NAME) }
    }

    @Test
    fun invalidRecoveryInstanceNeverReachesTheBroker() {
        val broker = mockk<ChrootRootBroker>()
        val factory = ChrootRuntimeProcessFactory(mockk<Context>(), broker)
        val session = ChrootRuntimeSession("reviewed-session", root)
        for (candidate in listOf("", instanceId.dropLast(1), instanceId + "0", instanceId.uppercase(),
            "g".repeat(24), "../$instanceId", "$instanceId --remove", "/bin/sh -c anything")) {
            assertThrows(IllegalArgumentException::class.java) {
                factory.startRecoveryRemoval(candidate, identity, session)
            }
        }
        verify(exactly = 0) { broker.startChecked(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { broker.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun recoveryPassesTheLiveSourceCheckForEvaluationAfterTheBrokerQueue() {
        val broker = mockk<ChrootRootBroker>()
        val factory = ChrootRuntimeProcessFactory(mockk<Context>(), broker)
        val session = ChrootRuntimeSession("reviewed-session", root)
        val process = mockk<Process>()
        val current = AtomicBoolean(true)
        val checkCurrent: () -> Unit = {
            if (!current.get()) throw CancellationException("Recovery source changed in broker queue")
        }
        val queuedCheck = slot<() -> Unit>()
        every { broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, instanceId, identity, refEq(session), any(), capture(queuedCheck)) } returns process

        assertSame(process, factory.startRecoveryRemoval(instanceId, identity, session, checkCurrent))
        assertSame(checkCurrent, queuedCheck.captured)
        current.set(false)
        assertThrows(CancellationException::class.java) { queuedCheck.captured() }
        verify(exactly = 0) { broker.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun rejectedRecoverySessionDoesNotFallBackToAnUnboundOrOrdinaryRemoval() {
        val broker = mockk<ChrootRootBroker>()
        val factory = ChrootRuntimeProcessFactory(mockk<Context>(), broker)
        val session = ChrootRuntimeSession("replaced-session", root)
        val failure = ChrootRuntimeUnavailable("Root runtime session changed before dispatch")
        every { broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, instanceId, identity, refEq(session), any(), any()) } throws failure

        assertSame(failure, assertThrows(ChrootRuntimeUnavailable::class.java) {
            factory.startRecoveryRemoval(instanceId, identity, session)
        })
        verify(exactly = 1) { broker.startChecked(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { broker.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun recoveryWithoutAnExistingBrokerSessionCannotBootstrapOrReadTheHelper() = runTest {
        for (enabled in listOf(false, true)) {
            val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
            val authority = RootAuthorityCoordinator(repository, backgroundScope)
            authority.setEnabled(enabled)
            val state = authority.accessSnapshot()
            val broker = ChrootRootBroker(authority)
            val factory = ChrootRuntimeProcessFactory(mockk<Context>(), broker)
            val supplied = ChrootRuntimeSession("not-an-issued-session", state)

            assertThrows(ChrootRuntimeUnavailable::class.java) {
                factory.startRecoveryRemoval(instanceId, identity, supplied)
            }
            assertEquals(0, broker.bootstrapCount)
            assertEquals(0, broker.registeredProcessCount.value)
            assertNull(broker.currentSession.value)
            assertEquals(state, authority.accessSnapshot())
        }
    }
}
