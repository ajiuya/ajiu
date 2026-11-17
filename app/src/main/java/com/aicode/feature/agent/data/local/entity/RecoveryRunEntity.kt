package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_recovery_runs",
    indices = [Index(value = ["requestId", "createdAt"]), Index(value = ["sessionId", "status"])]
)
data class RecoveryRunEntity(
    @PrimaryKey val id: String,
    val requestId: String,
    val sessionId: String,
    val generation: Long,
    val ownerFence: String,
    val status: String,
    val step: Int,
    val revision: Long,
    val committedStep: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long?,
    val resumeSnapshotJson: String? = null
)
