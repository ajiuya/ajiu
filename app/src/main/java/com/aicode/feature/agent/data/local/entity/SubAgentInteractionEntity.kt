package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 会话使用 INSERT OR REPLACE；外键级联会误删审计。删除时仅按 parentSessionId 显式清理。
@Entity(
    tableName = "subagent_interactions",
    indices = [
        Index(value = ["parentSessionId", "subSessionId", "timestamp", "id"]),
        Index(value = ["parentSessionId", "timestamp", "id"]),
        Index(value = ["timestamp", "id"]),
        Index(value = ["parentSessionId", "subSessionId", "sourceMessageId"])
    ]
)
data class SubAgentInteractionEntity(
    @PrimaryKey val id: String,
    val parentSessionId: String,
    val subSessionId: String,
    val kind: String,
    val content: String,
    val timestamp: Long,
    val origin: String = when (kind) {
        "SPAWNED", "MESSAGE_FROM_PARENT" -> "PARENT"
        "MESSAGE_FROM_SUB", "COMPLETED", "FAILED" -> "SUB"
        else -> "SYSTEM"
    },
    val sourceMessageId: String? = null,
    val title: String? = null,
    val model: String? = null,
    val instructionId: String? = null,
    val generation: Long? = null,
    val parentGeneration: Long? = null,
    val runId: String? = null,
    val agentName: String? = null
)
