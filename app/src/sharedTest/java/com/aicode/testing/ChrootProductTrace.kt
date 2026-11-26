package com.aicode.testing

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

internal class ChrootProductTrace(private val clock: () -> Long) {
    enum class Kind {
        PHASE, BEGIN, END, CASE_START, CASE_PASS, CASE_FAIL,
        STARTED, RESUMED, FOCUS_GAINED, FOCUS_LOST, PAUSED, STOPPED, DESTROYED, ARMED, CLOSING, CLOSED,
        SOURCE_CHANGED, ROOT_CHANGED, STOP_REQUEST, STOP_AWAIT, STOP_DONE,
        IDLE_WAIT, IDLE_READY, IDLE_CLOSE, IDLE_CLOSED,
        FAIL_ASSERTION, FAIL_CANCELLED, FAIL_IO, FAIL_SECURITY, FAIL_TIMEOUT, FAIL_UNEXPECTED, FAIL_BLOCKED
    }

    enum class Phase {
        NONE, PREFLIGHT, FOREGROUND, FD_SUITE, PREPARE,
        A_CREATE, B_AUTHORIZATION, C_STDIO, D_PTY, E_FILES,
        E_STREAM_WRITE, E_STREAM_READ, FILE_UPLOAD, FILE_DOWNLOAD, DIRECTORY_TRANSFER,
        CONTENT_URI, CONTENT_UPLOAD, CONTENT_VERIFY, F_DISCONNECT_REMOVE, SHUTDOWN
    }

    enum class FailureCode(val kind: Kind) {
        ASSERTION(Kind.FAIL_ASSERTION), CANCELLED(Kind.FAIL_CANCELLED), IO(Kind.FAIL_IO),
        SECURITY(Kind.FAIL_SECURITY), TIMEOUT(Kind.FAIL_TIMEOUT), UNEXPECTED(Kind.FAIL_UNEXPECTED),
        BLOCKED(Kind.FAIL_BLOCKED)
    }

    data class Event internal constructor(
        val sequence: Long,
        val relativeMs: Long,
        val kind: Kind,
        val phase: Phase,
        val stateBits: Int
    ) {
        init {
            require(sequence > 0 && relativeMs >= 0 && stateBits in 0..STATE_MASK)
        }

        internal fun json(): JSONArray = JSONArray().put(sequence).put(relativeMs).put(kind.name).put(phase.name).put(stateBits)
    }

    class Snapshot internal constructor(
        events: List<Event>,
        val omittedCount: Long,
        val firstLoss: Event?,
        val firstFailure: Event?,
        val currentPhase: Phase
    ) {
        val events: List<Event> = Collections.unmodifiableList(ArrayList(events))

        init {
            require(events.size <= MAX_EVENTS && omittedCount >= 0)
        }

        fun json(): String {
            val result = JSONObject().apply {
                put("schema", 1)
                put("fields", JSONArray(listOf("sequence", "relativeMs", "kind", "phase", "stateBits")))
                put("phase", currentPhase.name)
                put("omittedCount", omittedCount)
                put("events", JSONArray().also { array -> events.forEach { array.put(it.json()) } })
                put("firstLoss", firstLoss?.json() ?: JSONObject.NULL)
                put("firstFailure", firstFailure?.json() ?: JSONObject.NULL)
            }.toString()
            check(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            return result
        }
    }

    private val origin = clock()
    private val events = ArrayDeque<Event>(MAX_EVENTS)
    private var sequence = 0L
    private var relativeMs = 0L
    private var omittedCount = 0L
    private var firstLoss: Event? = null
    private var firstFailure: Event? = null
    private var currentPhase = Phase.NONE

    @Synchronized fun event(kind: Kind, stateBits: Int = 0) {
        val recorded = append(kind, stateBits)
        if (firstLoss == null && kind in LOSS_KINDS && recorded.stateBits and ARMED != 0 &&
            recorded.stateBits and (CLOSING or CLOSED) == 0) firstLoss = recorded
    }

    @Synchronized fun phase(phase: Phase) {
        currentPhase = phase
        append(Kind.PHASE, 0)
    }

    @Synchronized fun failure(code: FailureCode) {
        val recorded = append(code.kind, 0)
        if (firstFailure == null) firstFailure = recorded
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(events.toList(), omittedCount, firstLoss, firstFailure, currentPhase)

    private fun append(kind: Kind, stateBits: Int): Event {
        check(sequence < Long.MAX_VALUE)
        val now = clock()
        val elapsed = if (now <= origin) 0L else (now - origin).let { if (it < 0) Long.MAX_VALUE else it }
        relativeMs = maxOf(relativeMs, elapsed)
        val recorded = Event(++sequence, relativeMs, kind, currentPhase, stateBits and STATE_MASK)
        if (events.size == MAX_EVENTS) {
            events.removeFirst()
            omittedCount++
        }
        events.addLast(recorded)
        return recorded
    }

    companion object {
        const val MAX_EVENTS = 32
        const val MAX_BYTES = 3 * 1024
        const val STARTED = 1
        const val RESUMED = 2
        const val FOCUSED = 4
        const val ARMED = 8
        const val CLOSING = 16
        const val CLOSED = 32
        private const val STATE_MASK = STARTED or RESUMED or FOCUSED or ARMED or CLOSING or CLOSED
        private val LOSS_KINDS = setOf(Kind.FOCUS_LOST, Kind.PAUSED, Kind.STOPPED, Kind.DESTROYED)
    }
}
