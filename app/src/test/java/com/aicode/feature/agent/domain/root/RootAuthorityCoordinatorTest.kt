package com.aicode.feature.agent.domain.root

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RootAuthorityCoordinatorTest {
    private data class Fixture(
        val store: RootMemoryDataStore,
        val repository: RootAccessSettingsRepository,
        val coordinator: RootAuthorityCoordinator
    )

    private fun TestScope.fixture(): Fixture {
        val store = RootMemoryDataStore()
        val repository = RootAccessSettingsRepository(store, backgroundScope)
        return Fixture(store, repository, RootAuthorityCoordinator(repository, backgroundScope))
    }

    @Test
    fun coldStartBlocksSynchronousGuardsUntilPersistedAuthorityIsLoaded() = runTest {
        val loaded = CompletableDeferred<Preferences>()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { emit(loaded.await()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(loaded.await())
        }
        val repository = RootAccessSettingsRepository(store, backgroundScope)
        val coordinator = RootAuthorityCoordinator(repository, backgroundScope)
        val authorityId = UUID.randomUUID().toString()
        var entered = false
        assertFalse(coordinator.accessState.value.enabled)
        assertNull(coordinator.withCurrent(authorityId, 19L) { entered = true })
        val snapshot = async { coordinator.accessSnapshot() }
        runCurrent()
        assertFalse(snapshot.isCompleted)
        assertFalse(entered)
        loaded.complete(mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 19L,
            stringPreferencesKey("authority_id") to authorityId
        ))
        assertEquals(RootAccessState(true, 19L, authorityId), snapshot.await())
        assertNotNull(coordinator.withCurrent(authorityId, 19L) { Unit })
        for ((authority, generation) in listOf("" to 19L, " " to 19L, "other" to 19L, authorityId to 18L)) {
            assertNull(coordinator.withCurrent(authority, generation) { entered = true })
        }
        assertFalse(entered)
    }

    @Test
    fun revocationBlocksReentrantGuardsAndAwaitsCleanupOutsideGateBeforePersistence() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var stopOnly = false
        var reentrantAllowed = false
        var exposedEnabled = false
        var cleanupBeforeStop = false
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val registration = coordinator.registerRevocation(
            onRevoke = {
                stopOnly = true
                exposedEnabled = exposedEnabled || coordinator.accessState.value.enabled
                reentrantAllowed = reentrantAllowed ||
                    coordinator.withCurrent(before.authorityId, before.generation) { true } == true
            },
            awaitStopped = {
                cleanupBeforeStop = !stopOnly
                cleanupStarted.complete(Unit)
                cleanupFinished.await()
            }
        )
        val closing = async { coordinator.setEnabled(false) }
        runCurrent()
        assertTrue(stopOnly)
        assertFalse(exposedEnabled)
        assertFalse(reentrantAllowed)
        assertFalse(cleanupBeforeStop)
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(closing.isCompleted)
        assertFalse(coordinator.accessState.value.enabled)
        assertTrue(fixture.store.data.value[booleanPreferencesKey("enabled")] == true)
        assertEquals(before.generation, fixture.store.data.value[longPreferencesKey("generation")])
        assertFalse(coordinator.accessSnapshot().enabled)
        assertNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        assertNull(CompletableFuture.supplyAsync<Boolean?> {
            coordinator.withCurrent(before.authorityId, before.generation) { true }
        }.get(5L, TimeUnit.SECONDS))
        registration.close()
        registration.close()
        runCurrent()
        assertFalse(closing.isCompleted)
        cleanupFinished.complete(Unit)
        closing.await()
        val after = coordinator.accessSnapshot()
        assertFalse(after.enabled)
        assertEquals(before.generation + 1L, after.generation)
        assertEquals(before.authorityId, after.authorityId)
    }

    @Test
    fun callbackAndCleanupFailuresAreIsolatedAndCannotPreventClosingPersistence() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var failingInvalidations = 0
        var reentrantAllowed = false
        var otherInvalidations = 0
        var failingCleanups = 0
        var otherCleanups = 0
        coordinator.registerRevocation(
            onRevoke = {
                failingInvalidations++
                reentrantAllowed = reentrantAllowed ||
                    coordinator.withCurrent(before.authorityId, before.generation) { true } == true
                throw IllegalStateException("failed stop signal")
            },
            awaitStopped = {
                failingCleanups++
                throw IOException("failed cleanup")
            }
        )
        coordinator.registerRevocation(
            onRevoke = { otherInvalidations++ },
            awaitStopped = { otherCleanups++ }
        )
        coordinator.setEnabled(false)
        assertTrue(failingInvalidations > 0)
        assertFalse(reentrantAllowed)
        assertTrue(otherInvalidations > 0)
        assertEquals(1, failingCleanups)
        assertEquals(1, otherCleanups)
        val after = coordinator.accessSnapshot()
        assertFalse(after.enabled)
        assertEquals(before.generation + 1L, after.generation)
    }

    @Test
    fun cleanupFailureDoesNotReopenAnUnchangedGenerationAndExplicitRecoveryCannotRestorePermits() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var permitValid = true
        val registration = coordinator.registerRevocation(
            onRevoke = { permitValid = false },
            awaitStopped = { throw IOException("failed cleanup") }
        )
        coordinator.setEnabled(true)
        assertFalse(permitValid)
        assertTrue(fixture.store.data.value[booleanPreferencesKey("enabled")] == true)
        assertEquals(before.generation, fixture.store.data.value[longPreferencesKey("generation")])
        assertFalse(coordinator.accessSnapshot().enabled)
        assertNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        registration.close()
        coordinator.setEnabled(true)
        assertTrue(coordinator.accessSnapshot().enabled)
        assertNotNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        assertFalse(permitValid)
    }

    @Test
    fun cleanupHasOneTwelveSecondBudgetAndTimeoutStillPersistsClosing() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var waiting = 0
        var unknown = 0
        repeat(2) {
            coordinator.registerRevocation(
                onRevoke = {},
                awaitStopped = {
                    waiting++
                    try {
                        awaitCancellation()
                    } finally {
                        unknown++
                    }
                }
            )
        }
        val startedAt = testScheduler.currentTime
        val closing = async { coordinator.setEnabled(false) }
        runCurrent()
        assertEquals(2, waiting)
        advanceTimeBy(11_999L)
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, unknown)
        assertTrue(fixture.store.data.value[booleanPreferencesKey("enabled")] == true)
        advanceTimeBy(1L)
        runCurrent()
        closing.await()
        assertEquals(12_000L, testScheduler.currentTime - startedAt)
        assertEquals(2, unknown)
        assertFalse(coordinator.accessSnapshot().enabled)
        assertEquals(before.generation + 1L, coordinator.accessSnapshot().generation)
    }

    @Test
    fun cleanupBeyondTheManagerDeadlineHasPublicationMarginBeforeRevocationFails() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        coordinator.registerRevocation(onRevoke = {}, awaitStopped = { delay(10_500L) })
        val enabling = async { coordinator.setEnabled(true) }
        runCurrent()
        advanceTimeBy(6_500L)
        runCurrent()
        assertFalse(enabling.isCompleted)
        assertFalse(coordinator.accessState.value.enabled)
        advanceTimeBy(3_500L)
        runCurrent()
        assertFalse(enabling.isCompleted)
        advanceTimeBy(500L)
        runCurrent()
        enabling.await()
        assertEquals(before, coordinator.accessSnapshot())
        assertNotNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
    }

    @Test
    fun callerCancellationAfterInvalidationDoesNotCancelCleanupOrPersistence() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val registration = coordinator.registerRevocation(
            onRevoke = {},
            awaitStopped = {
                cleanupStarted.complete(Unit)
                cleanupFinished.await()
            }
        )
        val closing = async { coordinator.setEnabled(false) }
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        assertFalse(coordinator.accessState.value.enabled)
        assertTrue(fixture.store.data.value[booleanPreferencesKey("enabled")] == true)
        cleanupFinished.complete(Unit)
        closing.join()
        registration.close()
        assertTrue(closing.isCancelled)
        val after = coordinator.accessSnapshot()
        assertFalse(after.enabled)
        assertEquals(before.generation + 1L, after.generation)
        coordinator.setEnabled(true)
        assertTrue(coordinator.accessSnapshot().enabled)
    }

    @Test
    fun persistenceFailureStaysFailClosedEvenWhenSnapshotReadsOldEnabledSettings() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var permitValid = true
        coordinator.registerRevocation(onRevoke = { permitValid = false })
        fixture.store.failWrites = true
        try {
            coordinator.setEnabled(false)
            throw AssertionError("Persistence failure must be reported")
        } catch (_: IOException) {
        }
        assertFalse(permitValid)
        assertTrue(fixture.store.data.value[booleanPreferencesKey("enabled")] == true)
        assertFalse(coordinator.accessSnapshot().enabled)
        assertFalse(coordinator.accessState.value.enabled)
        assertNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        fixture.store.failWrites = false
        coordinator.setEnabled(true)
        val recovered = coordinator.accessSnapshot()
        assertTrue(recovered.enabled)
        assertNotNull(coordinator.withCurrent(recovered.authorityId, recovered.generation) { Unit })
        assertFalse(permitValid)
    }

    @Test
    fun synchronousGuardObservesAuthorityChangesBeforeTheFlowCollectorRuns() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        var revoked = false
        var reentrantEntered = false
        coordinator.registerRevocation(onRevoke = {
            revoked = true
            coordinator.withCurrent(fixture.repository.state.value.authorityId, before.generation) {
                reentrantEntered = true
            }
        })
        val nextAuthority = UUID.randomUUID().toString()
        fixture.store.updateData {
            it.toMutablePreferences().apply { this[stringPreferencesKey("authority_id")] = nextAuthority }
        }
        fixture.repository.snapshot()
        assertFalse(revoked)
        assertNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        assertTrue(revoked)
        assertFalse(reentrantEntered)
        assertNotNull(coordinator.withCurrent(nextAuthority, before.generation) { Unit })
        assertEquals(nextAuthority, coordinator.accessState.value.authorityId)
    }

    @Test
    fun registrationWhileDisabledIsImmediatelyStopOnlyAndCloseUnregistersIdempotently() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.accessSnapshot()
        var revocations = 0
        var cleanups = 0
        val registration = coordinator.registerRevocation(
            onRevoke = { revocations++ },
            awaitStopped = { cleanups++ }
        )
        assertEquals(1, revocations)
        assertEquals(0, cleanups)
        registration.close()
        registration.close()
        coordinator.setEnabled(true)
        coordinator.setEnabled(false)
        assertEquals(1, revocations)
        assertEquals(0, cleanups)
    }

    @Test
    fun startingRegistrationAndPermitConsumptionAreAtomicAgainstClosing() = runTest {
        val fixture = fixture()
        val coordinator = fixture.coordinator
        coordinator.setEnabled(true)
        val before = coordinator.accessSnapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        runCurrent()
        val starting = CompletableFuture.supplyAsync<Boolean?> {
            coordinator.withCurrent(before.authorityId, before.generation) {
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS))
                coordinator.registerRevocation(
                    onRevoke = { events.add("stop-only") },
                    awaitStopped = { events.add("cleanup") }
                )
                events.add("consume-and-starting")
            }
        }
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            val closing = CompletableFuture.runAsync {
                closeEntered.countDown()
                runBlocking { coordinator.setEnabled(false) }
            }
            assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
            assertFalse(closing.isDone)
            release.countDown()
            assertNotNull(starting.get(5L, TimeUnit.SECONDS))
            closing.get(5L, TimeUnit.SECONDS)
            assertEquals("consume-and-starting", events.first())
            assertTrue(events.indexOf("stop-only") < events.indexOf("cleanup"))
            assertFalse(coordinator.accessSnapshot().enabled)
            assertNull(coordinator.withCurrent(before.authorityId, before.generation) { Unit })
        } finally {
            release.countDown()
        }
    }
}
