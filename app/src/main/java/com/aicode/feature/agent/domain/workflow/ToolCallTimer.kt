package com.aicode.feature.agent.domain.workflow

internal class ToolCallTimer(
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long
) {
    data class Started(val startedAtMs: Long, val startedElapsedMs: Long)
    data class Finished(val startedAtMs: Long, val durationMs: Long)

    fun start(): Started = Started(wallClockMs(), elapsedRealtimeMs())

    fun finish(started: Started): Finished = Finished(
        started.startedAtMs,
        (elapsedRealtimeMs() - started.startedElapsedMs).coerceAtLeast(0L)
    )
}
