package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubAgentInteractionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(interaction: SubAgentInteractionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(interactions: List<SubAgentInteractionEntity>): List<Long>

    @Query("SELECT * FROM subagent_interactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SubAgentInteractionEntity?

    @Query("""
        SELECT id, parentSessionId, subSessionId, kind, timestamp, origin, sourceMessageId, title, model, instructionId, agentName,
               substr(content, 1, 240) AS preview
        FROM subagent_interactions WHERE parentSessionId = :parentId
        ORDER BY timestamp ASC, id ASC
    """)
    fun observeByParent(parentId: String): Flow<List<SubAgentInteractionMetadata>>

    @Query("""
        SELECT id, parentSessionId, subSessionId, kind, timestamp, origin, sourceMessageId, title, model, instructionId, agentName,
               substr(content, 1, 240) AS preview
        FROM subagent_interactions WHERE parentSessionId = :parentId
        ORDER BY timestamp ASC, id ASC
    """)
    suspend fun getMetadataByParentOnce(parentId: String): List<SubAgentInteractionMetadata>

    @Query("""
        SELECT * FROM (
            SELECT * FROM subagent_interactions
            WHERE parentSessionId = :parentId AND subSessionId = :childId
            ORDER BY timestamp DESC, id DESC LIMIT :limit
        ) ORDER BY timestamp ASC, id ASC
    """)
    fun getByChildPaged(parentId: String, childId: String, limit: Int): Flow<List<SubAgentInteractionEntity>>

    @Query("""
        SELECT COUNT(*) FROM subagent_interactions
        WHERE parentSessionId = :parentId AND subSessionId = :childId
    """)
    fun observeCountByChild(parentId: String, childId: String): Flow<Int>

    @Query("SELECT DISTINCT parentSessionId FROM subagent_interactions WHERE subSessionId = :childId ORDER BY parentSessionId ASC")
    suspend fun getParentIdsByChildOnce(childId: String): List<String>

    @Query("SELECT * FROM subagent_interactions ORDER BY timestamp ASC, id ASC")
    suspend fun getAllOnce(): List<SubAgentInteractionEntity>

    @Query("""
        SELECT * FROM subagent_interactions
        WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)
        ORDER BY timestamp ASC, id ASC LIMIT :limit
    """)
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<SubAgentInteractionEntity>

    @Query("""
        SELECT * FROM subagent_interactions WHERE parentSessionId = :parentId
          AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId))
        ORDER BY timestamp ASC, id ASC LIMIT :limit
    """)
    suspend fun getPageByParentAfter(
        parentId: String,
        lastTimestamp: Long,
        lastId: String,
        limit: Int
    ): List<SubAgentInteractionEntity>

    @Query("""
        SELECT e.* FROM subagent_interactions e
        WHERE e.kind IN ('SPAWNED', 'STARTED')
          AND NOT EXISTS (
            SELECT 1 FROM subagent_interactions later
            WHERE later.parentSessionId = e.parentSessionId AND later.subSessionId = e.subSessionId
              AND (later.generation = e.generation OR later.generation IS NULL OR e.generation IS NULL)
              AND later.kind IN ('SPAWNED', 'STARTED')
              AND (later.timestamp > e.timestamp OR (later.timestamp = e.timestamp AND later.id > e.id))
          )
          AND NOT EXISTS (
            SELECT 1 FROM subagent_interactions terminal
            WHERE terminal.parentSessionId = e.parentSessionId AND terminal.subSessionId = e.subSessionId
              AND terminal.kind IN ('COMPLETED', 'FAILED', 'STOPPED', 'DELETED', 'INTERRUPTED')
              AND terminal.timestamp >= e.timestamp
              AND (terminal.kind = 'DELETED' OR (
                (terminal.generation = e.generation OR terminal.generation IS NULL OR e.generation IS NULL)
                AND (terminal.runId = e.runId OR terminal.runId IS NULL OR e.runId IS NULL)
              ))
          )
        ORDER BY e.timestamp, e.id
    """)
    suspend fun getUnfinishedExecutions(): List<SubAgentInteractionEntity>

    @Query("""
        SELECT * FROM subagent_interactions
        WHERE parentSessionId = :parentId AND subSessionId = :childId AND kind IN ('SPAWNED', 'STARTED')
          AND (:runId IS NULL OR runId = :runId)
        ORDER BY timestamp DESC, id DESC LIMIT 1
    """)
    suspend fun latestExecution(parentId: String, childId: String, runId: String?): SubAgentInteractionEntity?

    @Query("SELECT * FROM agent_recovery_runs WHERE sessionId = :childId ORDER BY createdAt DESC, id DESC")
    suspend fun recoveryRunsForChild(childId: String): List<RecoveryRunEntity>

    @Query("""
        SELECT id, toolName, toolArgs FROM agent_messages
        WHERE sessionId = :childId AND id IN (:messageIds) AND role = 'TOOL'
          AND toolName IN ('editFile', 'writeFile', 'generateImage')
          AND isContextSummary = 0 AND isCompactionMarker = 0 AND isContextExcluded = 0
        ORDER BY timestamp, id
    """)
    suspend fun changedFileSources(childId: String, messageIds: List<String>): List<SubAgentChangedFileSource>

    @Query("DELETE FROM subagent_interactions WHERE parentSessionId = :parentId")
    suspend fun deleteByParent(parentId: String)

    @Query("DELETE FROM subagent_interactions")
    suspend fun deleteAll()
}

data class SubAgentChangedFileSource(val id: String, val toolName: String?, val toolArgs: String?)

data class SubAgentInteractionMetadata(
    val id: String,
    val parentSessionId: String,
    val subSessionId: String,
    val kind: String,
    val timestamp: Long,
    val origin: String,
    val sourceMessageId: String?,
    val title: String?,
    val model: String?,
    val preview: String,
    val instructionId: String? = null,
    val agentName: String? = null
)
