package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_recovery_drafts")
data class RecoveryDraftEntity(
    @PrimaryKey val runId: String,
    val step: Int,
    val revision: Long,
    val text: String,
    val updatedAt: Long
)
