package com.aicode.feature.agent.presentation.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AgentLiveNotificationState {
    data class Session(
        val sessionId: String,
        val title: String,
        val statusLabel: String
    )

    data class Snapshot(
        val enabled: Boolean = false,
        val sessions: List<Session> = emptyList(),
        val completed: Boolean = false,
        val failed: Boolean = false,
        val startedAtElapsedMs: Long? = null,
        val finishedAtElapsedMs: Long? = null,
        val completionCount: Int = 0,
        val completionVerified: Boolean = true
    ) {
        val active: Boolean get() = enabled && (sessions.isNotEmpty() || completed)
    }

    private val mutableState = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    internal fun publish(snapshot: Snapshot) {
        mutableState.value = snapshot
    }

    internal fun clear() {
        mutableState.value = Snapshot()
    }
}

internal class AgentLiveNotificationPolicy {
    companion object {
        const val COMPLETION_LINGER_MS = 5 * 60 * 1000L
    }

    private var running = emptyList<AgentLiveNotificationState.Session>()
    private val outcomes = mutableMapOf<String, Boolean>()
    private val batchSessionIds = linkedSetOf<String>()
    private var startedAtElapsedMs: Long? = null
    private var hadRunning = false
    private var hadFailure = false

    var snapshot = AgentLiveNotificationState.Snapshot()
        private set

    val hasRunning: Boolean get() = running.isNotEmpty()

    fun update(sessions: List<AgentLiveNotificationState.Session>) {
        running = sessions.distinctBy { it.sessionId }
    }

    fun markSessionStarted(sessionId: String) {
        if (snapshot.completed) clear()
        outcomes.remove(sessionId)
    }

    fun markCompletionOutcome(sessionId: String, failed: Boolean) {
        if (sessionId !in batchSessionIds && running.none { it.sessionId == sessionId }) return
        outcomes[sessionId] = outcomes[sessionId] == true || failed
        hadFailure = hadFailure || failed
        if (snapshot.completed && sessionId in batchSessionIds) {
            snapshot = snapshot.copy(
                failed = hadFailure,
                completionVerified = batchSessionIds.all { it in outcomes }
            )
        }
    }

    fun render(enabled: Boolean, inForeground: Boolean, nowElapsedMs: Long): AgentLiveNotificationState.Snapshot {
        if (!enabled) return clear()
        if (running.isNotEmpty()) {
            if (!hadRunning) {
                if (snapshot.completed) {
                    outcomes.clear()
                    hadFailure = false
                } else outcomes.keys.retainAll(running.map { it.sessionId }.toSet())
                batchSessionIds.clear()
                startedAtElapsedMs = nowElapsedMs
            }
            val currentIds = running.mapTo(hashSetOf()) { it.sessionId }
            // A session can start a second turn while another session keeps the batch alive.
            currentIds.filter { id -> snapshot.sessions.none { it.sessionId == id } }.forEach { outcomes.remove(it) }
            batchSessionIds.addAll(currentIds)
            hadRunning = true
            snapshot = AgentLiveNotificationState.Snapshot(
                enabled = true,
                sessions = running,
                startedAtElapsedMs = startedAtElapsedMs
            )
        } else if (hadRunning) {
            hadRunning = false
            if (inForeground) return clear()
            snapshot = AgentLiveNotificationState.Snapshot(
                enabled = true,
                completed = true,
                failed = hadFailure,
                startedAtElapsedMs = startedAtElapsedMs,
                finishedAtElapsedMs = nowElapsedMs,
                completionCount = batchSessionIds.size,
                completionVerified = batchSessionIds.all { it in outcomes }
            )
        } else if (!snapshot.completed || inForeground ||
            snapshot.finishedAtElapsedMs?.let { nowElapsedMs - it >= COMPLETION_LINGER_MS } == true) {
            return clear()
        }
        return snapshot
    }

    fun clear(): AgentLiveNotificationState.Snapshot {
        hadRunning = false
        hadFailure = false
        startedAtElapsedMs = null
        batchSessionIds.clear()
        outcomes.clear()
        snapshot = AgentLiveNotificationState.Snapshot()
        return snapshot
    }

    fun dismiss(): AgentLiveNotificationState.Snapshot {
        running = emptyList()
        return clear()
    }
}
