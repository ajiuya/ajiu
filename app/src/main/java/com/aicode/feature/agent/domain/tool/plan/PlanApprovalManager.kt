package com.aicode.feature.agent.domain.tool.plan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanApprovalManager @Inject constructor() {

    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    private val _pendingPlan = MutableStateFlow<PendingPlanApproval?>(null)
    val pendingPlan: StateFlow<PendingPlanApproval?> = _pendingPlan.asStateFlow()

    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    suspend fun awaitApproval(request: PendingPlanApproval): PlanApprovalDecision {
        val decision = CompletableDeferred<PlanApprovalDecision>()
        synchronized(lock) {
            require(request.id !in pending)
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                if (pending[request.id]?.decision === decision) {
                    pending.remove(request.id)
                    publish()
                }
            }
        }
    }

    fun pendingForSession(sessionId: String): PendingPlanApproval? = synchronized(lock) {
        pending.values.firstOrNull { it.request.sessionId == sessionId }?.request
    }

    fun resolve(id: String, decision: PlanApprovalDecision): Boolean = synchronized(lock) {
        val entry = pending.remove(id) ?: return@synchronized false
        publish()
        entry.decision.complete(decision)
    }

    private fun publish() {
        _pendingPlan.value = pending.values.firstOrNull()?.request
        _awaitingSessionIds.value = pending.values.map { it.request.sessionId }.toSet()
    }

    private class Entry(
        val request: PendingPlanApproval,
        val decision: CompletableDeferred<PlanApprovalDecision>
    )
}
