package com.aicode.core.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedDeferredCacheTest {
    @Test
    fun oneConsumerCancellationNeverCancelsTheOtherConsumersSharedResult() = runTest {
        val cache = KeyedDeferredCache<String, Any>(backgroundScope, 2)
        val result = Any()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        var cancelled = 0
        val first = async { cache.get("same") { loads++; try { gate.await(); result } finally { cancelled++ } } }
        val second = async { cache.get("same") { error("duplicate work") } }
        runCurrent()
        assertEquals(1, loads)
        assertEquals(2, cache.stats().consumers)
        first.cancelAndJoin()
        assertEquals(0, cancelled)
        assertEquals(1, cache.stats().consumers)
        gate.complete(Unit)
        assertSame(result, second.await())
        assertSame(result, cache.peek("same"))
        assertEquals(1, cancelled)
        assertEquals(0, cache.stats().inFlight)
    }

    @Test
    fun lastDepartingConsumerCancelsWorkAndAllowsAFreshFlight() = runTest {
        val cache = KeyedDeferredCache<String, String>(backgroundScope, 2)
        var stops = 0
        val first = async { cache.get("key") { try { awaitCancellation() } finally { stops++ } } }
        val second = async { cache.get("key") { error("duplicate work") } }
        runCurrent()
        first.cancelAndJoin()
        second.cancelAndJoin()
        runCurrent()
        assertEquals(1, stops)
        assertEquals(0, cache.stats().inFlight)
        assertNull(cache.peek("key"))
        val retry = async { cache.get("key") { "fresh" } }
        runCurrent()
        assertEquals("fresh", retry.await())
    }

    @Test
    fun distinctKeysKeepTheirParallelismAndInflightTasksAreNotLruVictims() = runTest {
        val cache = KeyedDeferredCache<Int, Int>(backgroundScope, 1, maxWeight = 1L)
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val tasks = (0 until 12).map { key -> async { cache.get(key) { started++; gate.await(); key } } }
        runCurrent()
        assertEquals(12, started)
        assertEquals(12, cache.stats().inFlight)
        gate.complete(Unit)
        runCurrent()
        assertEquals((0 until 12).toList(), tasks.map { it.await() })
        assertEquals(1, cache.stats().entries)
        assertEquals(1L, cache.stats().completedWeight)
    }

    @Test
    fun oversizeIsDeliveredWholeToEveryConsumerButNeverRetained() = runTest {
        val cache = KeyedDeferredCache<String, String>(backgroundScope, 3, maxWeight = 8L,
            weigh = { _, value -> value.length.toLong() })
        val warm = async { cache.get("warm") { "warm" } }
        runCurrent()
        warm.await()
        val gate = CompletableDeferred<Unit>()
        val value = "x".repeat(100)
        val first = async { cache.get("large") { gate.await(); value } }
        val second = async { cache.get("large") { error("duplicate work") } }
        runCurrent()
        gate.complete(Unit)
        assertSame(value, first.await())
        assertSame(value, second.await())
        assertNull(cache.peek("large"))
        assertEquals("warm", cache.peek("warm"))
        assertEquals(4L, cache.stats().completedWeight)
    }

    @Test
    fun lateUninterruptibleResultCannotReplaceTheNewFlightOrBecomeRetained() = runTest {
        val cache = KeyedDeferredCache<String, String>(backgroundScope, 2)
        val gate = CompletableDeferred<Unit>()
        val first = async { cache.get("key") { withContext(NonCancellable) { gate.await() }; "obsolete" } }
        runCurrent()
        first.cancelAndJoin()
        val fresh = async { cache.get("key") { "current" } }
        runCurrent()
        assertEquals("current", fresh.await())
        gate.complete(Unit)
        runCurrent()
        assertEquals("current", cache.peek("key"))
    }

    @Test
    fun failedAndNonRetainedResultsCanBeRetried() = runTest {
        val workerScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val cache = KeyedDeferredCache<String, String?>(workerScope, 2, retain = { it != null })
        val first = async { runCatching { cache.get("key") { throw IllegalArgumentException("invalid source") } } }
        runCurrent()
        assertTrue(first.await().exceptionOrNull() is IllegalArgumentException)
        val missing = async { cache.get("key") { null } }
        runCurrent()
        assertNull(missing.await())
        assertEquals(0, cache.stats().entries)
        val fresh = async { cache.get("key") { "available" } }
        runCurrent()
        assertEquals("available", fresh.await())
    }
}
