package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "agent_recovery_tools",
    primaryKeys = ["runId", "toolCallId"],
    indices = [Index(value = ["runId", "status"])]
)
data class RecoveryToolEntity(
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val step: Int,
    val revision: Long,
    val status: String,
    val assistantMessageId: String?,
    val resultMessageId: String?,
    val updatedAt: Long
)
