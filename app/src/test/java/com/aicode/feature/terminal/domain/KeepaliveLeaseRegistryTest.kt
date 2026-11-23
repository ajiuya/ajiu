package com.aicode.feature.terminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveLeaseRegistryTest {
    private var now = 0L
    private val registry = KeepaliveLeaseRegistry { now }

    @Test
    fun repeatedAcquireAndReleaseAreIdempotent() {
        repeat(20) { registry.acquire("job:a", cpu = true, network = true) }
        assertEquals(1, registry.snapshot().ownerCount)
        repeat(20) { registry.release("job:a") }
        assertEquals(0, registry.snapshot().ownerCount)
        assertFalse(registry.snapshot().serviceRequired)
    }

    @Test
    fun independentJobsAndTerminalOwnersDoNotLeakCounts() {
        registry.acquire("job:a", true, true)
        registry.acquire("local:term-1", true, true)
        registry.acquire("local:term-2", true, true)
        registry.acquire("remote:term-1", false, false)
        registry.release("local:term-1")
        assertEquals(3, registry.snapshot().ownerCount)
        registry.release("job:a")
        registry.release("local:term-2")
        assertTrue(registry.snapshot().serviceRequired)
        assertFalse(registry.snapshot().cpuRequired)
        registry.release("remote:term-1")
        assertFalse(registry.snapshot().serviceRequired)
    }

    @Test
    fun persistentAndTaskLeasesAreIndependent() {
        registry.setPersistent(true)
        registry.acquire("job", true, true)
        registry.setPersistent(false)
        assertTrue(registry.snapshot().cpuRequired)
        registry.setPersistent(true)
        registry.release("job")
        assertTrue(registry.snapshot().persistent)
        assertTrue(registry.snapshot().serviceRequired)
        assertFalse(registry.snapshot().cpuRequired)
        assertFalse(registry.snapshot().wifiRequired)
    }

    @Test
    fun cpuAndNetworkRequirementsAreAggregatedIndependently() {
        registry.acquire("cpu", true, false)
        registry.acquire("network", false, true)
        assertTrue(registry.snapshot().cpuRequired)
        assertTrue(registry.snapshot().wifiRequired)
        registry.release("cpu")
        assertFalse(registry.snapshot().cpuRequired)
        assertTrue(registry.snapshot().wifiRequired)
        registry.acquire("network", false, false)
        assertFalse(registry.snapshot().wifiRequired)
        assertTrue(registry.snapshot().serviceRequired)
    }

    @Test
    fun acceptedStartupIsNotConfirmedProtection() {
        registry.acquire("job", true, true)
        registry.markStartRequested()
        assertTrue(registry.snapshot().startPending)
        assertFalse(registry.snapshot().protected)
        registry.markForeground()
        assertTrue(registry.snapshot().protected)
        assertFalse(registry.snapshot().startPending)
        registry.markUnavailable()
        assertFalse(registry.snapshot().protected)
    }

    @Test
    fun taskRemovalSuppressesAllPowerRequirementsWithoutLosingOwners() {
        registry.setPersistent(true)
        registry.acquire("job", true, true)
        registry.markForeground()
        registry.setSuspended(true)
        registry.markUnavailable()
        registry.acquire("late-job", true, true)
        val stopped = registry.snapshot()
        assertTrue(stopped.persistent)
        assertEquals(2, stopped.ownerCount)
        assertFalse(stopped.serviceRequired)
        assertFalse(stopped.cpuRequired)
        assertFalse(stopped.wifiRequired)
        assertFalse(stopped.protected)
        registry.release("job")
        registry.setSuspended(false)
        assertTrue(registry.snapshot().serviceRequired)
        assertEquals(1, registry.snapshot().ownerCount)
    }

    @Test
    fun stalePersistentReadCannotOverrideUserDisable() {
        val revision = registry.persistentRevision()
        registry.setPersistent(false)
        assertFalse(registry.restorePersistent(revision))
        assertFalse(registry.snapshot().persistent)
        assertTrue(registry.restorePersistent(registry.persistentRevision()))
        assertTrue(registry.snapshot().persistent)
    }

    @Test
    fun timedInputExpiresAfterSixtyMinutesButDoesNotCloseTerminal() {
        registry.acquire("local:term-1", false, false)
        registry.acquireTimed("local:term-1:input", 60 * 60 * 1000L)
        now = 60 * 60 * 1000L - 1
        assertTrue(registry.snapshot().cpuRequired)
        now++
        assertFalse(registry.snapshot().cpuRequired)
        assertFalse(registry.snapshot().wifiRequired)
        assertEquals(1, registry.snapshot().ownerCount)
        assertTrue(registry.snapshot().serviceRequired)
    }

    @Test
    fun renewalIgnoresOldExpirationAndCloseReleasesBothOwners() {
        registry.acquire("remote:term-1", false, false)
        val first = registry.acquireTimed("remote:term-1:input", 100)
        now = 50
        val second = registry.acquireTimed("remote:term-1:input", 100)
        now = 100
        assertFalse(registry.expire("remote:term-1:input", first))
        assertTrue(registry.snapshot().cpuRequired)
        now = 149
        assertFalse(registry.expire("remote:term-1:input", second))
        registry.release("remote:term-1")
        registry.release("remote:term-1:input")
        now = 150
        assertFalse(registry.expire("remote:term-1:input", second))
        assertFalse(registry.snapshot().serviceRequired)
    }

    @Test
    fun reacquiringTimedOwnerAsUntimedCannotBeExpiredByOldTimer() {
        val expiry = registry.acquireTimed("job", 100)
        registry.acquire("job", false, false)
        now = 100
        assertFalse(registry.expire("job", expiry))
        assertEquals(1, registry.snapshot().ownerCount)
        assertFalse(registry.snapshot().cpuRequired)
    }

    @Test
    fun suspendTimeDoesNotExtendAnInputLease() {
        registry.acquireTimed("input", 100)
        registry.setSuspended(true)
        now = 101
        registry.setSuspended(false)
        assertFalse(registry.snapshot().serviceRequired)
    }

    @Test
    fun lastOwnerReleaseRemovesProtectionEvenBeforeServiceDetaches() {
        registry.acquire("display", false, false)
        registry.markForeground()
        registry.release("display")
        assertFalse(registry.snapshot().protected)
    }

    @Test
    fun recoveryStateDoesNotConfirmProtectionOrChangePowerRequirements() {
        registry.acquire("job", true, false)
        registry.setRecoveryState(attempts = 2, pending = true)
        val recovering = registry.snapshot()
        assertTrue(recovering.activeRequired)
        assertEquals(2, recovering.recoveryAttempts)
        assertTrue(recovering.recoveryPending)
        assertFalse(recovering.recoveryExhausted)
        assertFalse(recovering.protected)
        assertTrue(recovering.cpuRequired)
        assertFalse(recovering.wifiRequired)
        registry.setRecoveryState(attempts = 3, pending = false, exhausted = true)
        assertTrue(registry.snapshot().recoveryExhausted)
        assertFalse(registry.snapshot().protected)
        registry.markForeground()
        assertTrue(registry.snapshot().protected)
        assertEquals(3, registry.snapshot().recoveryAttempts)
    }

    @Test
    fun onlyUnsuspendedCpuOrNetworkLeasesCreateActiveDemand() {
        registry.setPersistent(true)
        registry.acquire("display", false, false)
        assertFalse(registry.snapshot().activeRequired)
        registry.acquire("network", false, true)
        assertTrue(registry.snapshot().activeRequired)
        registry.acquire("cpu", true, false)
        registry.release("network")
        assertTrue(registry.snapshot().activeRequired)
        registry.setSuspended(true)
        assertFalse(registry.snapshot().activeRequired)
        registry.setSuspended(false)
        registry.release("cpu")
        assertFalse(registry.snapshot().activeRequired)
        assertTrue(registry.snapshot().serviceRequired)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankOwner() {
        registry.acquire(" ", true, true)
    }
}
