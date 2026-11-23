package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import java.security.MessageDigest

internal object CompactionSourceFingerprint {
    fun of(entity: AgentMessageEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            val length = bytes?.size ?: -1
            digest.update(byteArrayOf(
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte()
            ))
            if (bytes != null) digest.update(bytes)
        }
        field(entity.id)
        field(entity.sessionId)
        field(entity.role)
        field(entity.content)
        field(entity.timestamp.toString())
        field(entity.toolCallsJson)
        field(entity.toolCallId)
        field(entity.toolName)
        field(entity.reasoning)
        field(entity.signature)
        field(entity.attachmentsJson)
        field(entity.thinkingBlocksJson)
        field(entity.modelInputSnapshotJson)
        field(entity.isCompacted.toString())
        field(entity.isContextExcluded.toString())
        field(entity.isContextSummary.toString())
        field(entity.isCompactionMarker.toString())
        field(entity.compactedBySummaryId)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
