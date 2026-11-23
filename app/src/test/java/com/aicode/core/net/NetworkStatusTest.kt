package com.aicode.core.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun defaultsToOnlineBeforeObservation() {
        val snapshot = NetworkStatusStore().states.value

        assertTrue(snapshot.isOnline)
        assertEquals(0L, snapshot.recoveryVersion)
        assertEquals(0L, snapshot.networkGeneration)
        assertEquals(null, snapshot.hasInternet)
        assertEquals(null, snapshot.validated)
        assertEquals(null, snapshot.blocked)
    }

    @Test
    fun booleanUpdatesAdvanceOnlyOnOfflineToOnline() {
        val status = NetworkStatusStore()

        status.update(true)
        assertEquals(0L, status.states.value.recoveryVersion)
        status.update(false)
        status.update(false)
        assertFalse(status.states.value.isOnline)
        assertEquals(0L, status.states.value.recoveryVersion)
        status.update(true)
        assertTrue(status.states.value.isOnline)
        assertEquals(1L, status.states.value.recoveryVersion)
        status.update(true)
        assertEquals(1L, status.states.value.recoveryVersion)
        status.update(false)
        status.update(true)
        assertEquals(2L, status.states.value.recoveryVersion)
    }

    @Test
    fun networkChangesAdvanceVersionsAtomicallyWithoutValidationGating() {
        val status = NetworkStatusStore()
        fun publish(changed: Boolean, validated: Boolean) = status.updateNetwork(
            online = true,
            networkChanged = changed,
            hasInternet = true,
            validated = validated,
            blocked = null,
            transports = setOf(NetworkStatus.Transport.WIFI)
        ).second

        val first = publish(changed = true, validated = false)
        assertTrue(first.isOnline)
        assertEquals(1L, first.recoveryVersion)
        assertEquals(1L, first.networkGeneration)
        assertEquals(first, status.states.value)

        val validated = publish(changed = false, validated = true)
        assertEquals(first.recoveryVersion, validated.recoveryVersion)
        assertEquals(first.networkGeneration, validated.networkGeneration)

        val switched = publish(changed = true, validated = false)
        assertTrue(switched.isOnline)
        assertEquals(2L, switched.recoveryVersion)
        assertEquals(2L, switched.networkGeneration)
        assertEquals(switched, status.states.value)
    }

    @Test
    fun recoveryWaitRequiresANewerVersionEvenWhenAlreadyOnline() = runTest {
        val status = NetworkStatusStore()
        val attemptVersion = status.states.value.recoveryVersion
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            status.states.first { it.isOnline && it.recoveryVersion > attemptVersion }
        }

        status.update(true)
        assertFalse(recovery.isCompleted)
        status.update(false)
        assertFalse(recovery.isCompleted)
        status.update(true)

        val recovered = recovery.await()
        assertTrue(recovered.isOnline)
        assertEquals(attemptVersion + 1, recovered.recoveryVersion)
    }

    @Test
    fun concurrentLinkUpdatesDoNotLoseOrTearVersions() = runTest {
        val status = NetworkStatusStore()
        val updates = (1..64).map {
            async(Dispatchers.Default) {
                status.updateNetwork(
                    online = true,
                    networkChanged = true,
                    hasInternet = true,
                    validated = false,
                    blocked = null,
                    transports = setOf(NetworkStatus.Transport.WIFI)
                )
            }
        }.awaitAll()

        updates.forEach { (previous, current) ->
            assertTrue(current.isOnline)
            assertEquals(previous.recoveryVersion + 1, current.recoveryVersion)
            assertEquals(previous.networkGeneration + 1, current.networkGeneration)
            assertEquals(current.recoveryVersion, current.networkGeneration)
        }
        assertEquals((1L..64L).toSet(), updates.map { it.second.recoveryVersion }.toSet())
        assertEquals(64L, status.states.value.recoveryVersion)
        assertEquals(64L, status.states.value.networkGeneration)
    }

    @Test
    fun versionsNeverWrapBackwards() {
        val status = NetworkStatusStore(
            NetworkStatus.Snapshot(isOnline = false, recoveryVersion = Long.MAX_VALUE)
        )

        status.update(true)
        assertEquals(Long.MAX_VALUE, status.states.value.recoveryVersion)
        assertTrue(status.states.value.isOnline)
    }

    @Test
    fun storesAreIndependentAndDoNotChangeTheGlobalSnapshot() {
        val globalBefore = NetworkStatus.states.value
        val first = NetworkStatusStore()
        val second = NetworkStatusStore()

        first.update(false)
        first.update(true)

        assertEquals(1L, first.states.value.recoveryVersion)
        assertEquals(NetworkStatus.Snapshot(), second.states.value)
        assertEquals(globalBefore, NetworkStatus.states.value)
        assertEquals(globalBefore.isOnline, NetworkStatus.isOnline)
    }
}
