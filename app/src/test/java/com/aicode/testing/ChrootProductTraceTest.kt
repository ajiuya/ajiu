package com.aicode.testing

import android.app.Application
import com.aicode.testing.ChrootProductTrace.FailureCode
import com.aicode.testing.ChrootProductTrace.Kind
import com.aicode.testing.ChrootProductTrace.Phase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChrootProductTraceTest {
    @Test fun sameClockTimeHasDistinctOrderedSequencesAndOneOrigin() {
        var now = 20_000L
        val trace = ChrootProductTrace { now }
        now += 17
        trace.phase(Phase.PREPARE)
        trace.event(Kind.BEGIN)
        trace.event(Kind.END)
        val snapshot = trace.snapshot()
        assertEquals(listOf(1L, 2L, 3L), snapshot.events.map { it.sequence })
        assertEquals(listOf(17L, 17L, 17L), snapshot.events.map { it.relativeMs })
        assertEquals(List(3) { Phase.PREPARE }, snapshot.events.map { it.phase })
        assertEquals(Phase.PREPARE, snapshot.currentPhase)
    }

    @Test fun relativeTimeNeverRegressesOrOverflows() {
        var now = Long.MIN_VALUE
        val trace = ChrootProductTrace { now }
        now = Long.MAX_VALUE
        trace.event(Kind.BEGIN)
        now = Long.MIN_VALUE
        trace.event(Kind.END)
        assertEquals(listOf(Long.MAX_VALUE, Long.MAX_VALUE), trace.snapshot().events.map { it.relativeMs })

        var ordinary = 100L
        val clamped = ChrootProductTrace { ordinary }
        ordinary = 120L
        clamped.event(Kind.BEGIN)
        ordinary = 105L
        clamped.event(Kind.END)
        assertEquals(listOf(20L, 20L), clamped.snapshot().events.map { it.relativeMs })
    }

    @Test fun overflowKeepsLast32AndPinsTheOriginalLossAndFailure() {
        val trace = ChrootProductTrace { 1L }
        trace.event(Kind.FOCUS_LOST, ChrootProductTrace.ARMED)
        trace.failure(FailureCode.TIMEOUT)
        val original = trace.snapshot()
        repeat(40) { trace.event(Kind.END) }
        trace.event(Kind.PAUSED, ChrootProductTrace.ARMED)
        trace.failure(FailureCode.CANCELLED)
        val snapshot = trace.snapshot()
        assertEquals(32, snapshot.events.size)
        assertEquals(12L, snapshot.omittedCount)
        assertEquals((13L..44L).toList(), snapshot.events.map { it.sequence })
        assertEquals(original.firstLoss, snapshot.firstLoss)
        assertEquals(original.firstFailure, snapshot.firstFailure)
        assertEquals(1L, snapshot.firstLoss?.sequence)
        assertEquals(2L, snapshot.firstFailure?.sequence)
        assertEquals(Kind.FAIL_TIMEOUT, snapshot.firstFailure?.kind)
        assertFalse(snapshot.events.contains(snapshot.firstLoss))
        assertFalse(snapshot.events.contains(snapshot.firstFailure))
    }

    @Test fun twoConcurrentWritersHaveUniqueSequencesAndSnapshotsAreImmutableCopies() {
        val clock = AtomicLong()
        val trace = ChrootProductTrace(clock::incrementAndGet)
        val empty = trace.snapshot()
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val workers = List(2) {
            thread(isDaemon = true) {
                try {
                    check(start.await(2, TimeUnit.SECONDS))
                    repeat(16) { trace.event(Kind.BEGIN) }
                } catch (failure: Throwable) { errors += failure }
            }
        }
        start.countDown()
        workers.forEach { it.join(2_000L) }
        assertTrue(workers.none { it.isAlive })
        assertTrue(errors.isEmpty())
        val snapshot = trace.snapshot()
        assertEquals((1L..32L).toList(), snapshot.events.map { it.sequence })
        assertEquals(32, snapshot.events.map { it.sequence }.toSet().size)
        assertEquals(0L, snapshot.omittedCount)
        assertTrue(empty.events.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.events as MutableList).clear() }
        trace.event(Kind.END)
        assertEquals((1L..32L).toList(), snapshot.events.map { it.sequence })
        assertEquals((2L..33L).toList(), trace.snapshot().events.map { it.sequence })
    }

    @Test fun unarmedFocusPauseStopAndDestroyAreRecordedWithoutPinningLoss() {
        val trace = ChrootProductTrace { 0L }
        listOf(Kind.FOCUS_LOST, Kind.PAUSED, Kind.STOPPED, Kind.DESTROYED).forEach { trace.event(it) }
        assertEquals(4, trace.snapshot().events.size)
        assertNull(trace.snapshot().firstLoss)
    }

    @Test fun eachArmedForegroundTriggerPinsAndFocusGainNeverClearsIt() {
        for (kind in listOf(Kind.FOCUS_LOST, Kind.PAUSED, Kind.STOPPED, Kind.DESTROYED)) {
            val trace = ChrootProductTrace { 0L }
            trace.event(kind, ChrootProductTrace.ARMED)
            val first = trace.snapshot().firstLoss
            trace.event(Kind.FOCUS_GAINED, READY_ARMED)
            trace.event(Kind.ARMED, READY_ARMED)
            assertEquals(kind, first?.kind)
            assertEquals(first, trace.snapshot().firstLoss)
        }
    }

    @Test fun closingAndClosedForegroundCallbacksCannotPinExpectedTeardownAsLoss() {
        for (closing in listOf(ChrootProductTrace.CLOSING, ChrootProductTrace.CLOSED)) {
            val trace = ChrootProductTrace { 0L }
            trace.event(Kind.CLOSING, READY_ARMED or closing)
            listOf(Kind.FOCUS_LOST, Kind.PAUSED, Kind.STOPPED, Kind.DESTROYED).forEach {
                trace.event(it, ChrootProductTrace.ARMED or closing)
            }
            trace.event(Kind.CLOSED, ChrootProductTrace.CLOSED)
            assertNull(trace.snapshot().firstLoss)
        }
    }

    @Test fun sourceAndRootInvalidationRemainDistinctFromCancelledFailureAndForegroundLoss() {
        for (cause in listOf(Kind.SOURCE_CHANGED, Kind.ROOT_CHANGED)) {
            val trace = ChrootProductTrace { 0L }
            trace.event(cause, READY_ARMED)
            trace.failure(FailureCode.CANCELLED)
            assertNull(trace.snapshot().firstLoss)
            assertEquals(cause, trace.snapshot().events.first().kind)
            assertEquals(Kind.FAIL_CANCELLED, trace.snapshot().firstFailure?.kind)
        }
        val cancelled = ChrootProductTrace { 0L }
        cancelled.failure(FailureCode.CANCELLED)
        assertNull(cancelled.snapshot().firstLoss)
        assertTrue(cancelled.snapshot().events.none { it.kind in setOf(Kind.SOURCE_CHANGED, Kind.ROOT_CHANGED) })
    }

    @Test fun allExistingSafeFailureCodesMapToFixedKindsAndTimeoutIsNotCancelled() {
        assertEquals(setOf("ASSERTION", "CANCELLED", "IO", "SECURITY", "TIMEOUT", "UNEXPECTED", "BLOCKED"),
            FailureCode.entries.map { it.name }.toSet())
        for (code in FailureCode.entries) {
            val trace = ChrootProductTrace { 0L }
            trace.failure(code)
            assertEquals("FAIL_${code.name}", trace.snapshot().firstFailure?.kind?.name)
        }
        val trace = ChrootProductTrace { 0L }
        trace.failure(FailureCode.TIMEOUT)
        trace.failure(FailureCode.CANCELLED)
        assertEquals(listOf(Kind.FAIL_TIMEOUT, Kind.FAIL_CANCELLED), trace.snapshot().events.map { it.kind })
        assertEquals(Kind.FAIL_TIMEOUT, trace.snapshot().firstFailure?.kind)
        assertNull(trace.snapshot().firstLoss)
    }

    @Test fun foregroundCallbackOnlyRecordsMemoryAndTriggerIsCapturedBeforeStopRequest() {
        val trace = ChrootProductTrace { 0L }
        val gate = ChrootVerificationForeground()
        gate.onStarted()
        gate.onResumed()
        gate.onWindowFocusChanged(true)
        var stops = 0
        gate.attachStopCallback {
            stops++
            trace.event(Kind.STOP_REQUEST)
            assertEquals(Kind.FOCUS_LOST, trace.snapshot().firstLoss?.kind)
        }
        trace.event(Kind.FOCUS_LOST, ChrootProductTrace.STARTED or ChrootProductTrace.RESUMED or ChrootProductTrace.ARMED)
        gate.onWindowFocusChanged(false)
        gate.onPaused()
        assertEquals(1, stops)
        assertEquals(listOf(Kind.FOCUS_LOST, Kind.STOP_REQUEST), trace.snapshot().events.map { it.kind })
        assertTrue(checkNotNull(trace.snapshot().firstLoss).sequence < trace.snapshot().events.last().sequence)
    }

    @Test fun mutationApiAcceptsNoFreeTextThrowableOrPersistenceCallbackAndMasksUnknownFlags() {
        val setters = ChrootProductTrace::class.java.declaredMethods.filter { it.name in setOf("event", "phase", "failure") }
        assertEquals(3, setters.size)
        assertEquals(setOf(Kind::class.java, Phase::class.java, FailureCode::class.java, Int::class.javaPrimitiveType),
            setters.flatMap { it.parameterTypes.toList() }.toSet())
        assertTrue(Kind.entries.all { it.name.matches(Regex("[A-Z_]+")) })
        assertTrue(Phase.entries.all { it.name.matches(Regex("[A-Z_]+")) })
        val trace = ChrootProductTrace { 0L }
        trace.event(Kind.BEGIN, -1)
        assertEquals(63, trace.snapshot().events.single().stateBits)
        val json = trace.snapshot().json()
        assertFalse(json.contains("/data/"))
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("exception"))
        assertFalse(json.contains("token"))
    }

    @Test fun compactJsonExplainsTheFixedColumnsAndRetainsPinsOutsideTheRing() {
        val trace = ChrootProductTrace { 5L }
        trace.phase(Phase.E_STREAM_READ)
        trace.event(Kind.PAUSED, ChrootProductTrace.ARMED)
        trace.failure(FailureCode.IO)
        repeat(40) { trace.event(Kind.END) }
        val parsed = JSONObject(trace.snapshot().json())
        assertEquals(1, parsed.getInt("schema"))
        assertEquals(listOf("sequence", "relativeMs", "kind", "phase", "stateBits"),
            parsed.getJSONArray("fields").let { fields -> List(fields.length()) { fields.getString(it) } })
        assertEquals(Phase.E_STREAM_READ.name, parsed.getString("phase"))
        assertEquals(11L, parsed.getLong("omittedCount"))
        val events = parsed.getJSONArray("events")
        assertEquals(32, events.length())
        assertEquals(12L, events.getJSONArray(0).getLong(0))
        assertEquals(2L, parsed.getJSONArray("firstLoss").getLong(0))
        assertEquals(3L, parsed.getJSONArray("firstFailure").getLong(0))
        assertEquals(Kind.FAIL_IO.name, parsed.getJSONArray("firstFailure").getString(2))
        assertEquals(5, events.getJSONArray(0).length())
    }

    @Test fun maximumNumericValuesAndLongestFixedLabelsStayWithinThreeKiB() {
        val phase = Phase.entries.maxBy { it.name.length }
        val kind = Kind.entries.maxBy { it.name.length }
        val row = ChrootProductTrace.Event(Long.MAX_VALUE, Long.MAX_VALUE, kind, phase, 63)
        val loss = row.copy(kind = Kind.FOCUS_LOST)
        val snapshot = ChrootProductTrace.Snapshot(List(32) { row }, Long.MAX_VALUE, loss, row, phase)
        val json = snapshot.json()
        assertEquals(32, JSONObject(json).getJSONArray("events").length())
        assertEquals(3062, json.toByteArray(Charsets.UTF_8).size)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= ChrootProductTrace.MAX_BYTES)
        assertEquals(3 * 1024, ChrootProductTrace.MAX_BYTES)
        assertEquals(32, ChrootProductTrace.MAX_EVENTS)
    }

    companion object {
        private const val READY_ARMED = ChrootProductTrace.STARTED or ChrootProductTrace.RESUMED or
            ChrootProductTrace.FOCUSED or ChrootProductTrace.ARMED
    }
}
