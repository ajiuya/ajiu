package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val subAgentInteractionDao: SubAgentInteractionDao,
    private val recoveryRepository: RecoveryRepository? = null,
    private val subAgentJournal: com.aicode.feature.agent.domain.subagent.SubAgentInteractionJournal? = null,
    private val notificationRecovery: com.aicode.feature.agent.domain.recovery.RecoveryNotificationCoordinator? = null
) {
    private val coldStartCleanupStarted = AtomicBoolean(false)
    private val coldStartCleanupComplete = CompletableDeferred<Unit>()

    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** 工具占位行前缀：标记「执行中、结果未回」的孤儿，UI 与回放据此识别。 */
        const val PENDING_TOOL_MARKER = "[running]"
        /** 历史版本 emoji 前缀；冷启动收尾与回放仍需识别。 */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** 历史版本停止 emoji 前缀；UI 剥离结果文本时兼容。 */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "执行被中断（应用已关闭）"
    }

    /** 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。 */
    suspend fun initColdStartCleanup() {
        if (!coldStartCleanupStarted.compareAndSet(false, true)) {
            coldStartCleanupComplete.await()
            return
        }
        try {
            recoveryRepository?.initialize()
            subAgentJournal?.recoverInterrupted()
            notificationRecovery?.prepareConfirmationRequests()
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                agentMessageDao.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "冷启动收尾 $n 条残留「执行中」工具行为已中断")
            coldStartCleanupComplete.complete(Unit)
        } catch (error: Throwable) {
            coldStartCleanupComplete.completeExceptionally(error)
            FileLogger.e(TAG, "回收残留执行中工具行失败", error)
            throw error
        }
    }

    fun newSessionEntity(
        workspacePath: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String = ReasoningEffort.MEDIUM.name
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "新对话" }
        else clean.take(TITLE_MAX) + "…"
    }

    /** 删除会话及其全部子代理会话（消息 + 会话记录一并删除）。返回被删除的会话 id 列表。 */
    suspend fun deleteSession(id: String): List<String> {
        val deleted = linkedSetOf(id)
        var descendants = listOf(id)
        while (descendants.isNotEmpty()) {
            descendants = descendants.flatMap { parentId ->
                recoveryRepository?.deleteSession(parentId, "session_deleted")
                chatSessionDao.getSubSessionsByParentOnce(parentId).map { it.id }
            }.filter { deleted.add(it) }
        }
        deleted.toList().asReversed().forEach { sessionId ->
            subAgentInteractionDao.deleteByParent(sessionId)
            agentMessageDao.deleteBySession(sessionId)
            chatSessionDao.delete(sessionId)
        }
        return deleted.toList()
    }

    /** 删除某工作区下的全部会话（含子代理会话）及其消息，返回删除的会话数。 */
    suspend fun deleteSessionsByWorkspace(workspacePath: String): Int {
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath)
        if (sessions.isEmpty()) return 0
        sessions.forEach { session ->
            recoveryRepository?.deleteSession(session.id, "session_deleted")
        }
        sessions.forEach { session ->
            subAgentInteractionDao.deleteByParent(session.id)
            agentMessageDao.deleteBySession(session.id)
        }
        chatSessionDao.deleteByWorkspace(workspacePath)
        return sessions.size
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /** 工作区里最近更新的根会话（忽略置顶），供启动时进入最近会话。 */
    suspend fun getMostRecentSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getLatestRootSessionByWorkspace(workspacePath)
    }

    /** 工作区下可复用的空会话（从未发送过消息的根会话，取最近更新的一个），供「新建会话」直接切过去。 */
    suspend fun getEmptySessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getEmptyRootSessionByWorkspace(workspacePath)
    }

    /** 回收工作区下多余的空会话（从未发送过消息），保留 [keepId]；保证列表最多一个空会话。 */
    suspend fun recycleEmptySessions(workspacePath: String, keepId: String? = null): Int {
        var count = 0
        chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).forEach { session ->
            if (session.id != keepId && isSessionEmpty(session.id)) {
                deleteSession(session.id)
                count++
            }
        }
        return count
    }

    /**
     * 创建子代理会话，继承父会话的 provider/model/reasoningEffort/workspacePath。
     * @param title 子会话标题（由 task 描述派生）
     * @param parentId 父会话 id
     * @param subagentType 子代理类型 id（自定义 agent 名，或默认通用子代理标识）
     * @param providerId 覆盖 provider；null 表示继承父会话
     * @param model 覆盖模型；null 表示继承父会话
     * @param reasoningEffort 覆盖思考强度；null 表示继承父会话
     */
    fun newSubSessionEntity(
        title: String,
        parentId: String,
        parent: ChatSessionEntity,
        subagentType: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId ?: parent.providerId,
            model = model ?: parent.model,
            reasoningEffort = reasoningEffort ?: parent.reasoningEffort,
            parentId = parentId,
            subagentType = subagentType
        )
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title)
    }

    suspend fun updatePinned(sessionId: String, pinned: Boolean) {
        chatSessionDao.updatePinned(sessionId, pinned)
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateProviderModelWithDirectChildren(sessionId: String, providerId: String?, model: String?): List<String> {
        return chatSessionDao.updateProviderModelWithDirectChildren(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    /**
     * 设定/覆盖会话 goal：重置轮次计数，并记录当前累计 token 作为消耗基线，状态置 ACTIVE。
     * [goal] 为 null/空白则清除目标。
     */
    suspend fun setGoal(sessionId: String, goal: String?, startInputTokens: Int, startOutputTokens: Int) {
        val trimmed = goal?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            chatSessionDao.updateGoal(sessionId, null, null, 0, 0)
        } else {
            chatSessionDao.updateGoal(sessionId, trimmed, GoalStatus.ACTIVE.name, startInputTokens, startOutputTokens)
        }
    }

    suspend fun updateGoalStatus(sessionId: String, status: GoalStatus?) {
        chatSessionDao.updateGoalStatus(sessionId, status?.name)
    }

    suspend fun incrementGoalTurn(sessionId: String) {
        chatSessionDao.incrementGoalTurn(sessionId)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return !agentMessageDao.hasMessages(sessionId) &&
            !chatSessionDao.hasPendingRecovery(sessionId) &&
            chatSessionDao.getRecoveryGate(sessionId)?.autoDispatchBlockedReason == null
    }
}
