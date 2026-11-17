package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId)")
    suspend fun sessionExists(sessionId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId AND (:generation IS NULL OR executionGeneration = :generation))")
    suspend fun sessionGenerationMatches(sessionId: String, generation: Long?): Boolean

    @Query("""
        UPDATE agent_messages SET modelInputSnapshotJson = :snapshotJson
        WHERE sessionId = :sessionId AND id = :messageId AND role = 'USER' AND modelInputSnapshotJson IS NULL
          AND EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId
              AND (:generation IS NULL OR executionGeneration = :generation))
    """)
    suspend fun writeModelInputSnapshotIfAbsent(sessionId: String, messageId: String, snapshotJson: String, generation: Long?): Int

    @Transaction
    suspend fun freezeModelInputSnapshot(
        sessionId: String,
        messageId: String,
        snapshotJson: String,
        generation: Long? = null
    ): String? {
        if (!sessionGenerationMatches(sessionId, generation)) return null
        val message = getMessageById(messageId) ?: return null
        if (message.sessionId != sessionId || message.role != "USER") return null
        message.modelInputSnapshotJson?.let { return it }
        writeModelInputSnapshotIfAbsent(sessionId, messageId, snapshotJson, generation)
        return getMessageById(messageId)?.modelInputSnapshotJson
    }

    @Query(
        """
        UPDATE agent_messages
        SET toolStartedAtMs = COALESCE(toolStartedAtMs, :startedAtMs),
            toolDurationMs = COALESCE(toolDurationMs, :durationMs)
        WHERE sessionId = :sessionId AND toolCallId = :callId
          AND id = 'tool_' || :callId AND role = 'TOOL'
          AND EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId)
        """
    )
    suspend fun updateToolTiming(sessionId: String, callId: String, startedAtMs: Long?, durationMs: Long?): Int

    @Query(
        """
        UPDATE agent_messages SET turnDurationMs = COALESCE(turnDurationMs, :durationMs)
        WHERE sessionId = :sessionId AND id = :messageId AND role = 'ASSISTANT'
          AND EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId)
        """
    )
    suspend fun updateTurnDuration(sessionId: String, messageId: String, durationMs: Long): Int

    @Transaction
    suspend fun insertToolMessagePreservingMetadata(message: AgentMessageEntity): Boolean {
        if (!sessionExists(message.sessionId)) return false
        val existing = getMessageById(message.id)
        if (existing != null && (
            existing.sessionId != message.sessionId || existing.role != message.role ||
                existing.toolCallId != message.toolCallId
        )) return false
        insert(message.copy(
            timestamp = existing?.timestamp ?: message.timestamp,
            toolActionTitle = existing?.toolActionTitle ?: message.toolActionTitle,
            toolStartedAtMs = existing?.toolStartedAtMs ?: message.toolStartedAtMs,
            toolDurationMs = existing?.toolDurationMs ?: message.toolDurationMs
        ))
        return true
    }

    @Transaction
    suspend fun insertMessageIfSessionExists(message: AgentMessageEntity) {
        if (!sessionExists(message.sessionId)) return
        if (message.role == "TOOL") {
            insertToolMessagePreservingMetadata(message)
        } else {
            val existing = getMessageById(message.id)
            if (existing != null && (existing.sessionId != message.sessionId || existing.role != message.role)) return
            insert(if (message.role == "USER") message.copy(modelInputSnapshotJson = existing?.modelInputSnapshotJson ?: message.modelInputSnapshotJson) else message)
        }
    }

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND role = 'TOOL' AND isCompacted = 0 ORDER BY timestamp ASC, rowid ASC")
    fun getToolMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    @Query("""
        SELECT m.sessionId AS sessionId,
               SUM(CASE WHEN m.role = 'USER' THEN 1 ELSE 0 END) AS userMessages,
               SUM(CASE WHEN m.role = 'ASSISTANT' THEN 1 ELSE 0 END) AS assistantMessages,
               SUM(CASE WHEN m.role = 'TOOL' THEN 1 ELSE 0 END) AS toolCallCount,
               MAX(m.timestamp) AS lastActivityAtMs,
               (SELECT substr(a.content, 1, 240) FROM agent_messages a
                WHERE a.sessionId = m.sessionId AND a.role = 'ASSISTANT'
                  AND a.content != '' AND a.isContextSummary = 0 AND a.isCompactionMarker = 0
                ORDER BY a.timestamp DESC, a.rowid DESC LIMIT 1) AS lastAssistantPreview
        FROM agent_messages m JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.parentId = :parentId
          AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT', 'TOOL')
          AND (m.content != '' OR m.role = 'TOOL' OR (m.attachmentsJson IS NOT NULL AND m.attachmentsJson NOT IN ('', '[]')))
        GROUP BY m.sessionId
    """)
    fun observeSubAgentHistoryStats(parentId: String): Flow<List<SubAgentHistoryStats>>

    @Query(SUB_AGENT_LEGACY_MESSAGE_PROJECTION + """
        WHERE m.sessionId = :childId AND s.parentId = :parentId
          AND m.rowid IN (
            SELECT recent.rowid FROM agent_messages recent
            WHERE recent.sessionId = :childId
              AND recent.isContextSummary = 0 AND recent.isCompactionMarker = 0
              AND recent.role IN ('USER', 'ASSISTANT', 'TOOL')
              AND (recent.content != '' OR recent.role = 'TOOL' OR (recent.attachmentsJson IS NOT NULL AND recent.attachmentsJson NOT IN ('', '[]'))
                OR (recent.role = 'ASSISTANT' AND recent.toolCallsJson IS NOT NULL AND recent.toolCallsJson NOT IN ('', '[]', 'null')))
              AND recent.timestamp < COALESCE(
                (SELECT MIN(i.timestamp) FROM subagent_interactions i
                 WHERE i.parentSessionId = :parentId AND i.subSessionId = :childId),
                9223372036854775807)
              AND NOT EXISTS (
                SELECT 1 FROM subagent_interactions recorded
                WHERE recorded.parentSessionId = :parentId AND recorded.subSessionId = :childId
                  AND recorded.sourceMessageId = recent.id)
            ORDER BY recent.timestamp DESC, recent.rowid DESC LIMIT :limit
          )
        ORDER BY m.timestamp ASC, m.rowid ASC
    """)
    fun observeSubAgentLegacyMessages(parentId: String, childId: String, limit: Int): Flow<List<AgentMessageEntity>>

    @Query(SUB_AGENT_EXECUTION_MESSAGE_PROJECTION + """
        WHERE m.sessionId = :childId AND s.parentId = :parentId
          AND m.rowid IN (
            SELECT recent.rowid FROM agent_messages recent
            WHERE recent.sessionId = :childId
              AND recent.isContextSummary = 0 AND recent.isCompactionMarker = 0
              AND recent.role IN ('USER', 'ASSISTANT', 'TOOL')
              AND (recent.content != '' OR recent.role = 'TOOL' OR (recent.attachmentsJson IS NOT NULL AND recent.attachmentsJson NOT IN ('', '[]')))
            ORDER BY recent.timestamp DESC, recent.rowid DESC LIMIT :limit
          )
        ORDER BY m.timestamp ASC, m.rowid ASC
    """)
    fun observeSubAgentExecutionMessages(parentId: String, childId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** 一次性读取（非 Flow），用于跨请求重建上下文历史。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND id IN (:headIds) AND isCompacted = 0 AND isContextExcluded = 0")
    suspend fun countActiveCompactionMessages(sessionId: String, headIds: List<String>): Int

    @Query("UPDATE agent_messages SET isCompacted = 1, compactedBySummaryId = :summaryId WHERE sessionId = :sessionId AND id IN (:headIds) AND isContextExcluded = 0")
    suspend fun markMessagesCompactedByIds(sessionId: String, headIds: List<String>, summaryId: String)

    @Query("UPDATE chat_sessions SET lastInputTokens = 0 WHERE id = :sessionId")
    suspend fun resetLastInputTokens(sessionId: String)

    @Transaction
    suspend fun commitCompaction(
        sessionId: String,
        headIds: List<String>,
        messages: List<AgentMessageEntity>,
        summaryId: String
    ) {
        if (!sessionExists(sessionId)) throw CancellationException("Target session deleted")
        require(messages.all { it.sessionId == sessionId } && messages.map { it.id }.distinct().size == messages.size) {
            "Compaction messages must belong to the target session and have distinct IDs"
        }
        require(messages.any { it.id == summaryId && it.role == "ASSISTANT" && it.isContextSummary }) {
            "Compaction summary is missing"
        }
        val headChunks = headIds.distinct().chunked(900)
        for (ids in headChunks) {
            if (countActiveCompactionMessages(sessionId, ids) != ids.size) {
                throw CancellationException("Compaction history changed")
            }
        }
        for (message in messages) {
            if (getMessageById(message.id) != null) throw CancellationException("Compaction message ID already exists")
        }
        headChunks.forEach { ids ->
            markMessagesCompactedByIds(sessionId, ids, summaryId)
        }
        insertAll(messages)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND (isCompactionMarker = 1 OR isContextSummary = 1)")
    suspend fun deleteCompactionMessages(sessionId: String)

    @Query("UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL WHERE sessionId = :sessionId AND timestamp < :cutoff AND isCompacted = 1 AND isContextExcluded = 0 AND isContextSummary = 0 AND isCompactionMarker = 0")
    suspend fun restoreCompactedMessagesBefore(sessionId: String, cutoff: Long)

    @Transaction
    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        deleteCompactionMessages(sessionId)
        restoreCompactedMessagesBefore(sessionId, cutoff)
        deleteMessagesFromTimestamp(sessionId, cutoff)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    /** 会话是否已有任何消息（LIMIT 1 快速判断，避免全量读取）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_messages WHERE sessionId = :sessionId LIMIT 1)")
    suspend fun hasMessages(sessionId: String): Boolean

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把残留的「执行中」工具行（content 以占位标记开头）批量收尾为「已中断」。
     * 用于冷启动：上次进程被杀时正在执行的工具不可能仍在跑，否则其占位行会永久显示转圈。
     * 返回受影响的行数。
     */
    @Query("""
        UPDATE agent_messages SET content = :interruptedContent, isError = 1
        WHERE role = :toolRole AND content LIKE :pendingPrefix
          AND NOT EXISTS (
              SELECT 1 FROM agent_recovery_tools t
              JOIN agent_recovery_runs r ON r.id = t.runId
              JOIN agent_recovery_requests q ON q.id = r.requestId AND q.activeRunId = r.id
              JOIN chat_sessions s ON s.id = r.sessionId
              WHERE r.sessionId = agent_messages.sessionId AND t.toolCallId = agent_messages.toolCallId
                AND t.assistantMessageId IS NOT NULL AND t.status IN ('DECLARED', 'WAITING_APPROVAL', 'STARTED')
                AND r.status = 'RUNNING' AND q.status = 'RUNNING'
                AND r.generation = s.executionGeneration AND q.generation = s.executionGeneration
          )
    """)
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    /**
     * 跨会话搜索某工作区下的聊天记录：命中用户 / 助手正文，排除已压缩与内部摘要行。
     * [escapedQuery] 已由调用方转义 LIKE 通配符（`!` `%` `_`），配合 SQL 里的 ESCAPE '!'。
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspace(workspacePath: String, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 指定会话中时间戳不早于 [timestamp] 的消息条数（含并列时间戳），供定位时确定所需分页上限。 */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun countMessagesFromTimestamp(sessionId: String, timestamp: Long): Int

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** 分页读取（keyset：按 timestamp,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /**
     * 各会话消息正文占用的字节数（降序取前 [limit] 个），供存储空间页拆解「聊天记录」构成。
     *
     * `LENGTH(CAST(x AS BLOB))` 取的是 UTF-8 字节数——直接 `LENGTH(x)` 对文本返回字符数，中文会少算三分之二。
     * 只统计几个大字段，因此是估算值：不含索引、页对齐与 WAL 开销，必然小于数据库文件本身。
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS title,
               COUNT(*) AS messageCount,
               SUM(
                   LENGTH(CAST(m.content AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolCallsJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolArgs, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.reasoning, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.thinkingBlocksJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.attachmentsJson, '') AS BLOB))
               ) AS bytes
        FROM agent_messages m
        LEFT JOIN chat_sessions s ON s.id = m.sessionId
        GROUP BY m.sessionId
        ORDER BY bytes DESC
        LIMIT :limit
        """
    )
    suspend fun sessionStorageUsage(limit: Int): List<SessionStorageUsage>
}

// 旧交互恢复需要 messageParent 的公开调用参数；两类历史均不物化私有思考块。
private const val SUB_AGENT_VISIBLE_MESSAGE_COLUMNS = """
           m.id, m.sessionId, m.role, m.content, m.timestamp,
           m.toolCallId, m.toolName, m.toolArgs, m.isError,
           NULL AS reasoning, NULL AS signature, m.attachmentsJson,
           m.isCompacted, m.isContextSummary, m.isCompactionMarker, m.inputTokens, m.outputTokens,
           NULL AS thinkingBlocksJson, m.cachedInputTokens, m.isContextExcluded, m.compactedBySummaryId,
           m.contextInputTokens, m.contextInputBudget, m.contextEstimated, m.contextBreakdownJson,
           m.toolActionTitle, m.toolStartedAtMs, m.toolDurationMs, m.turnDurationMs,
           NULL AS modelInputSnapshotJson
    FROM agent_messages m JOIN chat_sessions s ON s.id = m.sessionId
"""
private const val SUB_AGENT_LEGACY_MESSAGE_PROJECTION = "SELECT m.toolCallsJson, " + SUB_AGENT_VISIBLE_MESSAGE_COLUMNS
private const val SUB_AGENT_EXECUTION_MESSAGE_PROJECTION = "SELECT NULL AS toolCallsJson, " + SUB_AGENT_VISIBLE_MESSAGE_COLUMNS

data class SubAgentHistoryStats(
    val sessionId: String,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCallCount: Int,
    val lastActivityAtMs: Long,
    val lastAssistantPreview: String?
)

/** 跨会话聊天记录搜索的命中投影（[AgentMessageDao.searchInWorkspace] 的投影）。 */
data class ChatSearchMatch(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/** 单个会话的消息占用估算（[AgentMessageDao.sessionStorageUsage] 的投影）。 */
data class SessionStorageUsage(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val bytes: Long
)
