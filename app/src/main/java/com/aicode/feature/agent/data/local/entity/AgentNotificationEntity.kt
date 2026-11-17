package com.aicode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_notification_outbox",
    indices = [
        Index(value = ["eventKey"], unique = true),
        Index(value = ["sessionId", "generation", "deliveredAt", "retiredAt", "seq"])
    ]
)
data class AgentNotificationEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val sessionId: String,
    val generation: Long,
    val eventKey: String,
    val payloadJson: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val requiresConfirmation: Boolean = false,
    val deliveredAt: Long? = null,
    val retiredAt: Long? = null
)
