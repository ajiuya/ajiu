package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.runtime.AgentRunManager
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginChatService @Inject constructor(
    @param:ApplicationContext private val app: Context,
    private val sessions: SessionUseCase,
    private val sessionDao: ChatSessionDao,
    private val messages: AgentMessageDao,
    private val defaults: DefaultModelSettingsRepository,
    private val reasoning: ModelReasoningEffortRepository,
    private val runs: AgentRunManager,
    private val ui: PluginChatUiRequests,
    private val toolPermissions: ToolPermissionManager,
    private val questions: AskUserQuestionManager,
    private val plans: PlanApprovalManager,
    private val gateway: Provider<PluginHostGateway>
) {
    suspend fun listSessions(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("includeSubagents", "limit"))
        val workspace = workspace(context)
        val limit = PluginConversationJson.int(params, "limit", 50, 1, 200)
        val subagents = PluginConversationJson.boolean(params, "includeSubagents") ?: false
        approve(context, "chat.listSessions", params)
        val rows = if (subagents) sessionDao.getAllSessionsByWorkspaceOnce(workspace) else sessionDao.getRootSessionsByWorkspaceOnce(workspace)
        context.checkCurrent()
        return buildJsonObject { put("sessions", JsonArray(rows.take(limit).map(::sessionJson))) }
    }

    suspend fun createSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("title"))
        val workspace = workspace(context)
        val title = PluginConversationJson.optionalString(params, "title", 100)?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.chat_new_session_btn)
        approve(context, "chat.createSession", params)
        val providerId = defaults.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaults.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) reasoning.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
            else ReasoningEffort.MEDIUM.name
        context.checkCurrent()
        val session = sessions.newSessionEntity(workspace, providerId, model, effort).copy(title = title)
        sessions.upsertSession(session)
        context.checkCurrent()
        return buildJsonObject { put("session", sessionJson(session)) }
    }

    suspend fun getSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        approve(context, "chat.getSession", params)
        return buildJsonObject { put("session", sessionJson(requireSameSession(context, session))) }
    }

    suspend fun listMessages(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "limit", "before", "after"))
        val session = requireSession(context, params)
        val limit = PluginConversationJson.int(params, "limit", 50, 1, 200)
        val before = PluginConversationJson.long(params, "before")
        val after = PluginConversationJson.long(params, "after")
        if (before != null && after != null) PluginConversationJson.invalid("before and after are mutually exclusive")
        approve(context, "chat.listMessages", params)
        requireSameSession(context, session)
        val rows = messages.getMessagesPageDescending(session.id, before, after, limit + 1)
        context.checkCurrent()
        var chars = 0
        val kept = mutableListOf<JsonObject>()
        for (row in rows.take(limit)) {
            val json = messageJson(row)
            if (chars + json.toString().length > 1024 * 1024) break
            kept += json
            chars += json.toString().length
        }
        return buildJsonObject { put("sessionId", session.id); put("messages", JsonArray(kept.asReversed())); put("hasMore", rows.size > kept.size) }
    }

    suspend fun sendMessage(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "text"))
        val session = requireSession(context, params)
        val text = PluginConversationJson.string(params, "text", 32_000)
        approve(context, "chat.sendMessage", params)
        requireSameSession(context, session)
        withContext(Dispatchers.Main.immediate) {
            context.checkCurrent()
            runs.enqueueAgentRequest(text, projectRoot = session.workspacePath, targetSessionId = session.id)
        }
        return accepted(session.id)
    }

    suspend fun setDraft(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "text", "append"))
        workspace(context)
        val session = PluginConversationJson.optionalString(params, "sessionId")?.let { requireSessionId(context, it) }
        val text = PluginConversationJson.string(params, "text", 32_000, empty = true)
        val append = PluginConversationJson.boolean(params, "append") ?: false
        approve(context, "chat.setDraft", params)
        session?.let { requireSameSession(context, it) }
        val id = ui.submit(context, PluginChatUiAction.SetDraft(session?.id, text, append))
        return accepted(session?.id, id)
    }

    suspend fun openSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        approve(context, "chat.openSession", params)
        requireSameSession(context, session)
        return accepted(session.id, ui.submit(context, PluginChatUiAction.Open(session.id)))
    }

    suspend fun stop(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        approve(context, "chat.stop", params)
        requireSameSession(context, session)
        withContext(Dispatchers.Main.immediate) { context.checkCurrent(); runs.stopAgentSession(session.id) }
        return accepted(session.id)
    }

    suspend fun renameSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "title"))
        val session = requireSession(context, params)
        val title = PluginConversationJson.string(params, "title", 100)
        approve(context, "chat.renameSession", params)
        requireSameSession(context, session)
        sessions.updateTitle(session.id, title)
        context.checkCurrent()
        return buildJsonObject { put("ok", true); put("session", sessionJson(session.copy(title = title))) }
    }

    suspend fun pinSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "pinned"))
        val session = requireSession(context, params)
        val pinned = PluginConversationJson.boolean(params, "pinned") ?: PluginConversationJson.invalid("pinned is required")
        approve(context, "chat.pinSession", params)
        requireSameSession(context, session)
        sessions.updatePinned(session.id, pinned)
        context.checkCurrent()
        return buildJsonObject { put("ok", true); put("session", sessionJson(session.copy(isPinned = pinned))) }
    }

    suspend fun deleteSession(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        approve(context, "chat.deleteSession", params)
        requireSameSession(context, session)
        val deleted = withContext(Dispatchers.Main.immediate) { context.checkCurrent(); runs.deleteSessions(setOf(session.id), notifyParent = true) }
        return buildJsonObject { put("sessionId", session.id); put("accepted", session.id in deleted) }
    }

    suspend fun getRunState(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        approve(context, "chat.getRunState", params)
        return buildJsonObject { put("sessionId", session.id); put("run", runJson(context, session.id)) }
    }

    suspend fun waitForRun(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId", "timeoutMs"))
        val session = requireSession(context, params)
        val timeout = PluginConversationJson.int(params, "timeoutMs", 120_000, 1, 600_000)
        approve(context, "chat.waitForRun", params)
        val idle = context.whileCurrent {
            withTimeoutOrNull(timeout.toLong()) {
                while (isRunning(context, session.id)) { requireSameSession(context, session); delay(50) }
                true
            } == true
        }
        return buildJsonObject { put("sessionId", session.id); put("timedOut", !idle); put("run", runJson(context, session.id)) }
    }

    suspend fun listApprovals(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sessionId"))
        val session = requireSession(context, params)
        context.checkCurrent()
        return buildJsonObject {
            put("sessionId", session.id)
            put("tool", toolPermissions.pendingForSession(session.id)?.let { pending -> buildJsonObject {
                put("id", pending.id); put("toolName", pending.toolName); put("requiresNativeInteraction", true)
            } } ?: JsonNull)
            put("question", questions.pendingQuestion.value?.takeIf { it.sessionId == session.id }?.let { pending -> buildJsonObject {
                put("id", pending.id); put("requiresNativeInteraction", true)
            } } ?: JsonNull)
            put("plan", plans.pendingForSession(session.id)?.let { pending -> buildJsonObject {
                put("id", pending.id); put("requiresNativeInteraction", true)
            } } ?: JsonNull)
            put("pendingNavigation", true)
        }
    }

    private suspend fun isRunning(context: PluginInvocationContext, id: String): Boolean = withContext(Dispatchers.Main.immediate) {
        context.checkCurrent()
        runs.isSessionRunning(id) || id in runs.runningCommandSessions.value || runs.queuedRequests.value[id].orEmpty().isNotEmpty() ||
            runs.agentStates.value[id] is AgentUIState.Loading || runs.agentStates.value[id] is AgentUIState.Streaming
    }

    private suspend fun runJson(context: PluginInvocationContext, id: String): JsonObject {
        val running = isRunning(context, id)
        val state = runs.agentStates.value[id]
        return buildJsonObject {
            put("running", running); put("queued", runs.queuedRequests.value[id].orEmpty().size)
            put("status", when { id in runs.approvalSessionIds.value -> "awaitingApproval"; running -> "running"; state is AgentUIState.Error -> "error"; else -> "idle" })
            if (state is AgentUIState.Error) put("error", state.message.take(4000))
        }
    }

    private fun workspace(context: PluginInvocationContext): String {
        context.checkCurrent()
        if (context.background || context.workspacePath.isBlank() || context.execution == null) throw PluginHostException("WORKSPACE_UNAVAILABLE", "A captured workspace is required")
        return context.workspacePath
    }

    private suspend fun requireSession(context: PluginInvocationContext, params: JsonObject) =
        requireSessionId(context, PluginConversationJson.string(params, "sessionId"))

    private suspend fun requireSessionId(context: PluginInvocationContext, id: String): ChatSessionEntity {
        val workspace = workspace(context)
        val session = sessions.getSessionById(id) ?: throw PluginHostException("SESSION_NOT_FOUND", "Session not found")
        context.checkCurrent()
        if (session.workspacePath != workspace) throw PluginHostException("SESSION_NOT_FOUND", "Session not found in the captured workspace")
        return session
    }

    private suspend fun requireSameSession(context: PluginInvocationContext, original: ChatSessionEntity): ChatSessionEntity =
        requireSessionId(context, original.id).also {
            if (it.executionGeneration != original.executionGeneration) throw PluginHostException("CONTEXT_CHANGED", "Session generation changed")
        }

    private suspend fun approve(context: PluginInvocationContext, method: String, params: JsonObject) {
        context.checkIntegrity()
        val details = if (method == "chat.sendMessage" || method == "chat.setDraft") {
            val text = (params.getValue("text") as JsonPrimitive).content
            JsonObject(params + ("text" to buildJsonObject {
                put("length", text.length)
                put("sha256", PluginFiles.sha256(text.toByteArray(Charsets.UTF_8)))
            }))
        } else params
        gateway.get().authorize(context, method, params, PluginApprovalOperation.EXECUTE_TOOL, "$method\n$details")
        context.checkCurrent()
    }

    private fun accepted(sessionId: String?, receipt: String? = null) = buildJsonObject {
        sessionId?.let { put("sessionId", it) }; put("accepted", true)
        receipt?.let { put("requestId", it); put("pendingNavigation", false) }
    }

    private fun sessionJson(session: ChatSessionEntity) = buildJsonObject {
        put("id", session.id); put("title", session.title); put("createdAt", session.createdAt); put("updatedAt", session.updatedAt)
        put("isPinned", session.isPinned); put("reasoningEffort", session.reasoningEffort.lowercase())
        session.parentId?.let { put("parentId", it) }; session.subagentType?.let { put("subagentType", it) }
        session.providerId?.let { put("providerId", it) }; session.model?.let { put("model", it) }
        session.goal?.let { put("goal", it) }; session.goalStatus?.let { put("goalStatus", it.lowercase()) }
        put("totalInputTokens", session.totalInputTokens); put("totalOutputTokens", session.totalOutputTokens); put("lastInputTokens", session.lastInputTokens)
    }

    private fun messageJson(message: AgentMessageEntity) = buildJsonObject {
        put("id", message.id); put("role", message.role.lowercase()); put("timestamp", message.timestamp)
        put("content", message.content.take(128 * 1024)); if (message.content.length > 128 * 1024) put("contentTruncated", true)
        message.toolName?.let { put("toolName", it) }; put("isError", message.isError); put("isCompacted", message.isCompacted)
        put("isContextSummary", message.isContextSummary); put("isCompactionMarker", message.isCompactionMarker)
        put("inputTokens", message.inputTokens); put("outputTokens", message.outputTokens); put("cachedInputTokens", message.cachedInputTokens)
        message.reasoning?.let { put("reasoning", it.take(8000)); if (it.length > 8000) put("reasoningTruncated", true) }
        message.toolCallsJson?.takeIf { it.length <= 64 * 1024 }?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let { put("toolCalls", it) }
        }
        message.attachmentsJson?.takeIf { it.length <= 16 * 1024 }?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let { put("attachments", it) }
        }
    }
}
