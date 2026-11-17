package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_recovery_requests",
    indices = [
        Index(value = ["sessionId", "generation", "queueOrder"], unique = true),
        Index(value = ["inputMessageId"], unique = true),
        Index(value = ["sessionId", "status"])
    ]
)
data class RecoveryRequestEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val generation: Long,
    val environment: String,
    val inputMessageId: String,
    val queueOrder: Long,
    val payloadJson: String,
    val status: String,
    val activeRunId: String?,
    val continuationCount: Int,
    val continuationOutputTokens: Long,
    val continuationChars: Long,
    val createdAt: Long,
    val updatedAt: Long
)
