package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWeightTest {
    @Test
    fun signaturesThinkingBlocksAndImagePayloadsAllContributeToTheRetentionBudget() {
        val empty = AgentMessage.AssistantMessage(id = "assistant", content = "")
        val signed = empty.copy(signature = "signature", thinkingBlocksJson = "[thinking]")
        assertEquals(2L * ("signature".length + "[thinking]".length), messageWeight(signed) - messageWeight(empty))
        val pictured = signed.copy(images = listOf(AgentImage("image/png", "base64".repeat(2_000), "/image.png")))
        assertTrue(messageWeight(pictured) - messageWeight(signed) >= 24_000)
    }

    @Test
    fun nestedToolArgumentsAndModelOnlyResultsAreNotInvisibleToTheBudget() {
        val assistant = AgentMessage.AssistantMessage(id = "assistant", content = "body")
        val arguments = JsonObject(mapOf("nested" to JsonArray(listOf(JsonPrimitive("payload".repeat(1_000))))))
        val called = assistant.copy(toolCalls = listOf(ToolCall("call", "writeFile", mapOf("args" to arguments))))
        assertTrue(messageWeight(called) - messageWeight(assistant) > 14_000)
        val result = AgentMessage.ToolResultMessage(id = "call", toolName = "Bash", result = "visible")
        assertEquals(2L * "model only".length, messageWeight(result.copy(modelResult = "model only")) - messageWeight(result))
    }
}
