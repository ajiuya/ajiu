package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun messageWeight(message: AgentMessage): Long {
    fun text(value: String) = 48L + value.length * 2L
    fun json(value: JsonElement): Long = when (value) {
        is JsonObject -> 64L + value.entries.sumOf { text(it.key) + json(it.value) + 32 }
        is JsonArray -> 48L + value.sumOf { json(it) + 8 }
        is JsonPrimitive -> text(value.content)
    }
    val images = when (message) {
        is AgentMessage.UserMessage -> message.images
        is AgentMessage.AssistantMessage -> message.images
        is AgentMessage.ToolResultMessage -> message.images
    }
    val imageBytes = images.sumOf { 48L + text(it.path) + text(it.mimeType) + text(it.base64Data) }
    return 256L + imageBytes + when (message) {
        is AgentMessage.UserMessage -> text(message.id) + text(message.content) +
            text(message.modelInputOwnerId.orEmpty()) + text(message.projectMemoryKey.orEmpty())
        is AgentMessage.AssistantMessage -> text(message.id) + text(message.content) + text(message.reasoning) +
            text(message.signature) + text(message.thinkingBlocksJson) + message.toolCalls.sumOf { call ->
                64L + text(call.id) + text(call.name) + call.arguments.entries.sumOf { text(it.key) + json(it.value) }
            }
        is AgentMessage.ToolResultMessage -> text(message.id) + text(message.messageId) + text(message.toolName) +
            text(message.result) + text(message.modelResult.orEmpty())
    }
}
