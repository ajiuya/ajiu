package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.RecoveryDraftEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRequestEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.data.local.entity.RecoveryToolEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.recovery.RecoveryToolCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class RecoveryDaoFixture {
    val sessions = linkedMapOf("session" to RecoverySessionGate("session", 0, null))
    val requests = linkedMapOf<String, RecoveryRequestEntity>()
    val runs = linkedMapOf<String, RecoveryRunEntity>()
    val tools = linkedMapOf<Pair<String, String>, RecoveryToolEntity>()
    val drafts = linkedMapOf<String, RecoveryDraftEntity>()
    val messages = linkedMapOf<String, AgentMessageEntity>()
    val todos = linkedMapOf<String, TodoItemEntity>()
    val writes = mutableListOf<String>()
    val deliveredNotificationSeqs = linkedSetOf<Long>()
    val retiredNotificationSessions = linkedSetOf<String>()
    var initializations = 0
    private val queries = mockk<AgentRecoveryDao>()
    val dao = object : AgentRecoveryDao by queries {
        override suspend fun replaceRecoveryTodos(runId: String, ownerFence: String, items: List<TodoItemEntity>) =
            super<AgentRecoveryDao>.replaceRecoveryTodos(runId, ownerFence, items)
        override suspend fun enqueueRequest(request: RecoveryRequestEntity) = super<AgentRecoveryDao>.enqueueRequest(request)
        override suspend fun updateQueuedRequest(requestId: String, payloadJson: String, now: Long) =
            super<AgentRecoveryDao>.updateQueuedRequest(requestId, payloadJson, now)
        override suspend fun reorderQueuedRequests(sessionId: String, requestIds: List<String>, now: Long) =
            super<AgentRecoveryDao>.reorderQueuedRequests(sessionId, requestIds, now)
        override suspend fun claimRequest(requestId: String, environment: String, confirmed: Boolean, runId: String, ownerFence: String, now: Long) =
            super<AgentRecoveryDao>.claimRequest(requestId, environment, confirmed, runId, ownerFence, now)
        override suspend fun awaitRecoveryConfirmation(requestId: String, now: Long) =
            super<AgentRecoveryDao>.awaitRecoveryConfirmation(requestId, now)
        override suspend fun initializeRecovery(ownerFence: String, now: Long) {
            initializations++
            super<AgentRecoveryDao>.initializeRecovery(ownerFence, now)
        }
        override suspend fun beginRecoveryStep(runId: String, ownerFence: String, now: Long) =
            super<AgentRecoveryDao>.beginRecoveryStep(runId, ownerFence, now)
        override suspend fun restartRecoveryStream(runId: String, ownerFence: String, marker: RecoveryStep, now: Long) =
            super<AgentRecoveryDao>.restartRecoveryStream(runId, ownerFence, marker, now)
        override suspend fun saveRecoveryDraft(runId: String, ownerFence: String, marker: RecoveryStep, text: String, now: Long, minimumIntervalMs: Long, maximumBytes: Int) =
            super<AgentRecoveryDao>.saveRecoveryDraft(runId, ownerFence, marker, text, now, minimumIntervalMs, maximumBytes)
        override suspend fun declareRecoveryTools(runId: String, ownerFence: String, marker: RecoveryStep, calls: List<RecoveryToolCall>, now: Long) =
            super<AgentRecoveryDao>.declareRecoveryTools(runId, ownerFence, marker, calls, now)
        override suspend fun markRecoveryTool(runId: String, ownerFence: String, toolCallId: String, started: Boolean, now: Long) =
            super<AgentRecoveryDao>.markRecoveryTool(runId, ownerFence, toolCallId, started, now)
        override suspend fun commitRecoveryInput(runId: String, ownerFence: String, message: AgentMessageEntity, notificationSeqs: List<Long>) =
            super<AgentRecoveryDao>.commitRecoveryInput(runId, ownerFence, message, notificationSeqs)
        override suspend fun commitRecoverySupplementalInput(runId: String, ownerFence: String, message: AgentMessageEntity) =
            super<AgentRecoveryDao>.commitRecoverySupplementalInput(runId, ownerFence, message)
        override suspend fun freezeRecoveryInputSnapshot(runId: String, ownerFence: String, snapshotJson: String, messageId: String?) =
            super<AgentRecoveryDao>.freezeRecoveryInputSnapshot(runId, ownerFence, snapshotJson, messageId)
        override suspend fun commitRecoveryAssistant(runId: String, ownerFence: String, marker: RecoveryStep, message: AgentMessageEntity, now: Long) =
            super<AgentRecoveryDao>.commitRecoveryAssistant(runId, ownerFence, marker, message, now)
        override suspend fun commitRecoveryToolPlaceholder(runId: String, ownerFence: String, message: AgentMessageEntity) =
            super<AgentRecoveryDao>.commitRecoveryToolPlaceholder(runId, ownerFence, message)
        override suspend fun updateRecoveryToolTiming(runId: String, ownerFence: String, callId: String, startedAtMs: Long?, durationMs: Long?) =
            super<AgentRecoveryDao>.updateRecoveryToolTiming(runId, ownerFence, callId, startedAtMs, durationMs)
        override suspend fun updateRecoveryTurnDuration(runId: String, ownerFence: String, messageId: String, durationMs: Long) =
            super<AgentRecoveryDao>.updateRecoveryTurnDuration(runId, ownerFence, messageId, durationMs)
        override suspend fun commitRecoveryToolResult(runId: String, ownerFence: String, message: AgentMessageEntity, now: Long, notificationSeqs: List<Long>) =
            super<AgentRecoveryDao>.commitRecoveryToolResult(runId, ownerFence, message, now, notificationSeqs)
        override suspend fun finishRecoveryRun(runId: String, ownerFence: String, status: String, holdReason: String?, now: Long) =
            super<AgentRecoveryDao>.finishRecoveryRun(runId, ownerFence, status, holdReason, now)
        override suspend fun abandonPendingRecoveryRequest(requestId: String, sessionId: String, now: Long) =
            super<AgentRecoveryDao>.abandonPendingRecoveryRequest(requestId, sessionId, now)
        override suspend fun abandonRecoveryRequest(requestId: String, now: Long) =
            super<AgentRecoveryDao>.abandonRecoveryRequest(requestId, now)
        override suspend fun invalidateRecoverySession(sessionId: String, reason: String, generationBump: Boolean, now: Long) =
            super<AgentRecoveryDao>.invalidateRecoverySession(sessionId, reason, generationBump, now)
        override suspend fun deleteRecoverySession(sessionId: String, reason: String) =
            super<AgentRecoveryDao>.deleteRecoverySession(sessionId, reason)
        override suspend fun holdRecoverySession(sessionId: String, reason: String, now: Long) =
            super<AgentRecoveryDao>.holdRecoverySession(sessionId, reason, now)
        override suspend fun clearRecoveryHold(sessionId: String) = super<AgentRecoveryDao>.clearRecoveryHold(sessionId)
        override suspend fun incrementRecoveryBudget(runId: String, ownerFence: String, outputTokens: Int, chars: Int, now: Long) =
            super<AgentRecoveryDao>.incrementRecoveryBudget(runId, ownerFence, outputTokens, chars, now)
        override suspend fun retireRequest(request: RecoveryRequestEntity, status: String, now: Long) =
            super<AgentRecoveryDao>.retireRequest(request, status, now)
    }

    init {
        coEvery { queries.todoOwnedByAnotherSession(any(), any()) } answers {
            todos[firstArg<String>()]?.let { it.sessionId != secondArg<String>() } == true
        }
        coEvery { queries.deleteRecoveryTodoRows(any()) } answers {
            val sessionId = firstArg<String>()
            todos.values.filter { it.sessionId == sessionId }.map { it.id }.forEach { todos.remove(it) }
            writes.add("todoDelete:$sessionId")
            Unit
        }
        coEvery { queries.storeRecoveryTodoRows(any()) } answers {
            firstArg<List<TodoItemEntity>>().forEach { item ->
                todos[item.id] = item
                writes.add("todoUpsert:${item.id}")
            }
        }
        coEvery { queries.sessionGate(any()) } answers { sessions[firstArg<String>()] }
        coEvery { queries.requestRow(any()) } answers { requests[firstArg<String>()] }
        coEvery { queries.runRow(any()) } answers { runs[firstArg<String>()] }
        coEvery { queries.runsForRequest(any()) } answers { runs.values.filter { it.requestId == firstArg<String>() } }
        coEvery { queries.toolsForRun(any()) } answers { tools.values.filter { it.runId == firstArg<String>() } }
        coEvery { queries.toolsForRequest(any()) } answers {
            val runIds = runs.values.filter { it.requestId == firstArg<String>() }.map { it.id }.toSet()
            tools.values.filter { it.runId in runIds }
        }
        coEvery { queries.toolRow(any(), any()) } answers { tools[firstArg<String>() to secondArg<String>()] }
        coEvery { queries.draftRow(any()) } answers { drafts[firstArg<String>()] }
        coEvery { queries.messageRow(any()) } answers { messages[firstArg<String>()] }
        coEvery { queries.markRecoveryNotificationsDelivered(any(), any(), any()) } answers {
            val seqs = secondArg<List<Long>>()
            deliveredNotificationSeqs.addAll(seqs)
            writes.add("notifications:${seqs.joinToString()}")
            seqs.size
        }
        coEvery { queries.retireRecoveryNotifications(any(), any()) } answers {
            retiredNotificationSessions.add(firstArg())
            1
        }
        coEvery { queries.outstandingRequests() } answers { requests.values.filter { it.status !in listOf("COMPLETED", "ABANDONED") } }
        coEvery { queries.requestsForSession(any()) } answers { requests.values.filter { it.sessionId == firstArg<String>() } }
        coEvery { queries.nextQueueOrder(any(), any()) } answers {
            (requests.values.filter { it.sessionId == firstArg<String>() && it.generation == secondArg<Long>() }
                .maxOfOrNull { it.queueOrder } ?: 0L) + 1
        }
        coEvery { queries.hasRunning(any(), any()) } answers {
            runs.values.any { it.sessionId == firstArg<String>() && it.generation == secondArg<Long>() && it.status == "RUNNING" }
        }
        coEvery { queries.ownedRunning(any(), any()) } answers {
            runs[firstArg<String>()]?.takeIf { run ->
                val request = requests[run.requestId]
                val gate = sessions[run.sessionId]
                request != null && gate != null && run.ownerFence == secondArg<String>() && run.status == "RUNNING" && request.status == "RUNNING" &&
                    request.activeRunId == run.id && request.sessionId == run.sessionId && request.generation == run.generation &&
                    gate.executionGeneration == run.generation && gate.autoDispatchBlockedReason == null
            }
        }
        coEvery { queries.insertRequestRow(any()) } answers {
            val row = firstArg<RecoveryRequestEntity>()
            check(row.id !in requests && requests.values.none { it.inputMessageId == row.inputMessageId ||
                it.sessionId == row.sessionId && it.generation == row.generation && it.queueOrder == row.queueOrder })
            requests[row.id] = row
            writes.add("request:${row.id}")
            Unit
        }
        coEvery { queries.insertRunRow(any()) } answers {
            val row = firstArg<RecoveryRunEntity>()
            check(row.id !in runs)
            runs[row.id] = row
            writes.add("run:${row.id}")
            Unit
        }
        coEvery { queries.insertToolRows(any()) } answers {
            firstArg<List<RecoveryToolEntity>>().forEach { row ->
                check((row.runId to row.toolCallId) !in tools)
                tools[row.runId to row.toolCallId] = row
                writes.add("declare:${row.toolCallId}")
            }
        }
        coEvery { queries.updateRequestRow(any()) } answers {
            val row = firstArg<RecoveryRequestEntity>()
            check(requests.values.none { it.id != row.id && it.sessionId == row.sessionId &&
                it.generation == row.generation && it.queueOrder == row.queueOrder })
            requests[row.id] = row
            writes.add("request:${row.id}:${row.status}")
            Unit
        }
        coEvery { queries.updateRunRow(any()) } answers {
            val row = firstArg<RecoveryRunEntity>()
            runs[row.id] = row
            writes.add("run:${row.id}:${row.status}")
            Unit
        }
        coEvery { queries.updateToolRow(any()) } answers {
            val row = firstArg<RecoveryToolEntity>()
            tools[row.runId to row.toolCallId] = row
            writes.add("tool:${row.toolCallId}:${row.status}")
            Unit
        }
        coEvery { queries.storeDraftRow(any()) } answers {
            val row = firstArg<RecoveryDraftEntity>()
            drafts[row.runId] = row
            writes.add("draft:${row.runId}")
            Unit
        }
        coEvery { queries.deleteDraftRow(any()) } answers {
            drafts.remove(firstArg<String>())
            writes.add("clearDraft:${firstArg<String>()}")
            Unit
        }
        coEvery { queries.deleteUncommittedToolRows(any(), any()) } answers {
            val doomed = tools.filterValues { it.runId == firstArg<String>() && it.step == secondArg<Int>() &&
                it.assistantMessageId == null && it.status == "DECLARED" }.keys.toList()
            doomed.forEach { tools.remove(it) }
        }
        coEvery { queries.storeMessageRow(any()) } answers {
            val row = firstArg<AgentMessageEntity>()
            messages[row.id] = row
            writes.add("message:${row.id}")
            Unit
        }
        coEvery { queries.updateSessionGate(any(), any(), any()) } answers {
            val id = firstArg<String>()
            val gate = sessions[id]
            if (gate == null) 0 else {
                sessions[id] = gate.copy(executionGeneration = gate.executionGeneration + thirdArg<Int>(),
                    autoDispatchBlockedReason = secondArg())
                1
            }
        }
        coEvery { queries.deleteSessionToolRows(any()) } answers {
            val runIds = runs.values.filter { it.sessionId == firstArg<String>() }.map { it.id }.toSet()
            tools.keys.filter { it.first in runIds }.toList().forEach { tools.remove(it) }
        }
        coEvery { queries.deleteSessionDraftRows(any()) } answers {
            val runIds = runs.values.filter { it.sessionId == firstArg<String>() }.map { it.id }.toSet()
            drafts.keys.filter { it in runIds }.toList().forEach { drafts.remove(it) }
        }
        coEvery { queries.deleteSessionRunRows(any()) } answers {
            runs.values.filter { it.sessionId == firstArg<String>() }.map { it.id }.forEach { runs.remove(it) }
        }
        coEvery { queries.deleteSessionRequestRows(any()) } answers {
            requests.values.filter { it.sessionId == firstArg<String>() }.map { it.id }.forEach { requests.remove(it) }
        }
        coEvery { queries.updateRecoveryToolTimingRow(any(), any(), any(), any()) } answers {
            val id = "tool_${secondArg<String>()}"
            val row = messages[id]
            if (row == null || row.sessionId != firstArg<String>() || row.role != "TOOL" || row.toolCallId != secondArg<String>()) 0 else {
                messages[id] = row.copy(toolStartedAtMs = row.toolStartedAtMs ?: thirdArg(),
                    toolDurationMs = row.toolDurationMs ?: arg<Long?>(3))
                1
            }
        }
        coEvery { queries.updateRecoveryTurnDurationRow(any(), any(), any()) } answers {
            val id = secondArg<String>()
            val row = messages[id]
            if (row == null || row.sessionId != firstArg<String>() || row.role != "ASSISTANT") 0 else {
                messages[id] = row.copy(turnDurationMs = row.turnDurationMs ?: thirdArg())
                1
            }
        }
        every { queries.observePendingRows(any()) } answers {
            val sessionId = firstArg<String>()
            flow {
                val gate = sessions[sessionId]
                emit(requests.values.filter { it.sessionId == sessionId && it.generation == gate?.executionGeneration &&
                    it.status !in listOf("COMPLETED", "ABANDONED") }.sortedBy { it.queueOrder }.map { request ->
                    RecoveryPendingRows(request, runs.values.filter { it.requestId == request.id }.map { run ->
                        RecoveryRunRows(run, tools.values.filter { it.runId == run.id }, listOfNotNull(drafts[run.id]))
                    }, listOfNotNull(gate), listOfNotNull(messages[request.inputMessageId]?.id))
                })
            }
        }
    }

    suspend fun enqueue(id: String = "request"): RecoveryRequestEntity = checkNotNull(dao.enqueueRequest(
        RecoveryRequestEntity(id, "session", 0, "local", "input_$id", 0,
            Json.encodeToString(RecoveryRequestPayload("Request", "~/workspace")), "QUEUED", null, 0, 0, 0, 1_000, 1_000)
    ))

    suspend fun claim(requestId: String = "request", runId: String = "run", confirmed: Boolean = false, owner: String = "owner"): RecoveryRunEntity =
        checkNotNull(dao.claimRequest(requestId, "local", confirmed, runId, owner, 2_000))
}
