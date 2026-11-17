package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.google.gson.Gson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenEstimatorTest {
    private val gson = Gson()
    private val image = AgentImage(mimeType = "image/png", base64Data = "image")
    private val tool = object : AgentTool() {
        override val name = "readFile"
        override val description = "Read file 内容"
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "File path")
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("result"))
    }

    @Test
    fun breakdownPreservesLegacyTotalForMixedRolesAndTokenDensities() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "Read 文件 please", images = listOf(image)),
            AgentMessage.AssistantMessage(
                content = "Reading file",
                reasoning = "先确认路径",
                toolCalls = listOf(ToolCall("call-1", "readFile", mapOf("path" to JsonPrimitive("file.kt")))),
                images = listOf(image, image)
            ),
            AgentMessage.ToolResultMessage(
                toolName = "readFile",
                result = "raw output".repeat(20),
                modelResult = "model output",
                images = listOf(image)
            ),
            AgentMessage.ToolResultMessage(toolName = "other", result = "full output")
        )
        for (charsPerToken in listOf(1, 3, ModelContextPolicy.CHARS_PER_TOKEN, 8)) {
            val breakdown = ContextTokenEstimator.breakdown("System 提示词", messages, listOf(tool), charsPerToken)
            val legacyTotal = legacyEstimate("System 提示词", messages, listOf(tool), charsPerToken)
            val sum = breakdown.systemTokens.toLong() + breakdown.toolDefinitionTokens +
                breakdown.chatTokens + breakdown.toolResultTokens + breakdown.imageTokens
            assertEquals(legacyTotal.toLong(), sum)
            assertEquals(legacyTotal, breakdown.estimatedTotal)
            assertEquals(legacyTotal, ContextTokenEstimator.estimate("System 提示词", messages, listOf(tool), charsPerToken))
        }
    }

    @Test
    fun breakdownAllocatesSystemDefinitionsChatToolResultsAndImagesSeparately() {
        val user = AgentMessage.UserMessage(content = "User 文本", images = listOf(image))
        val assistant = AgentMessage.AssistantMessage(
            content = "Assistant 文本",
            reasoning = "Reasoning 思考",
            toolCalls = listOf(
                ToolCall("call-1", "readFile", mapOf("path" to JsonPrimitive("a.kt"))),
                ToolCall("call-2", "readFile", mapOf("path" to JsonPrimitive("b.kt")))
            ),
            images = listOf(image, image)
        )
        val result = AgentMessage.ToolResultMessage(
            toolName = "readFile", result = "raw output", modelResult = "short output", images = listOf(image)
        )
        val breakdown = ContextTokenEstimator.breakdown("System 提示", listOf(user, assistant, result), listOf(tool))

        assertEquals(ModelContextPolicy.estimateTextTokens("System 提示"), breakdown.systemTokens)
        assertEquals(
            ModelContextPolicy.estimateTextTokens(tool.name + tool.description + gson.toJson(tool.toModelJsonSchema())) + 16,
            breakdown.toolDefinitionTokens
        )
        val assistantText = assistant.content + assistant.reasoning +
            assistant.toolCalls.joinToString { it.name + gson.toJson(it.arguments) }
        assertEquals(
            ModelContextPolicy.estimateTextTokens(user.content) + 12 +
                ModelContextPolicy.estimateTextTokens(assistantText) + 12,
            breakdown.chatTokens
        )
        assertEquals(ModelContextPolicy.estimateTextTokens("readFileshort output") + 12, breakdown.toolResultTokens)
        assertEquals(4 * 4_096, breakdown.imageTokens)
    }

    @Test
    fun definitionsIncludeModelOnlyTitleWhileMcpUsesOriginalSchema() {
        val schema = mapOf<String, Any>("type" to "object", "properties" to emptyMap<String, Any>())
        val external = object : AgentTool() {
            override val name = "mcp__server__tool"
            override val description = "External"
            override val parameters = emptyMap<String, ToolParameter>()
            override fun toJsonSchema() = schema
            override suspend fun execute(args: Map<String, JsonElement>) = ToolResult.Success(JsonPrimitive("ok"))
        }
        val breakdown = ContextTokenEstimator.breakdown("", emptyList(), listOf(tool, external))
        val expected = listOf(tool, external).sumOf {
            ModelContextPolicy.estimateTextTokens(it.name + it.description + gson.toJson(it.toModelJsonSchema())) + 16
        }
        assertEquals(expected, breakdown.toolDefinitionTokens)
        assertTrue(gson.toJson(tool.toModelJsonSchema()).length > gson.toJson(tool.toJsonSchema()).length)
        assertEquals(schema, external.toModelJsonSchema())
    }

    @Test
    fun breakdownUsesModelProjectionForToolResultsAndTransportFallbackOtherwise() {
        val writeResult = """{"status":"success","data":{"path":"file.kt","created":true,"lines_written":3}}"""
        val projected = modelToolResultText("writeFile", writeResult)!!
        val messages = listOf(
            AgentMessage.ToolResultMessage(toolName = "writeFile", result = writeResult),
            AgentMessage.ToolResultMessage(toolName = "unknown", result = "raw output")
        )
        val breakdown = ContextTokenEstimator.breakdown("", messages, emptyList())

        assertEquals(
            ModelContextPolicy.estimateTextTokens("writeFile$projected") + 12 +
                ModelContextPolicy.estimateTextTokens("unknownraw output") + 12,
            breakdown.toolResultTokens
        )
        assertEquals(0, breakdown.chatTokens)
        assertEquals(legacyEstimate("", messages, emptyList()), breakdown.estimatedTotal)
    }

    @Test
    fun emptyBreakdownHasNoTokenOverhead() {
        assertEquals(ContextUsageBreakdown(), ContextTokenEstimator.breakdown("", emptyList(), emptyList()))
    }

    @Test
    fun saturatedImageEstimateKeepsLegacyTotal() {
        val images = object : AbstractList<AgentImage>() {
            override val size = 600_000
            override fun get(index: Int): AgentImage = image
        }
        val messages = listOf(AgentMessage.UserMessage(content = "request", images = images))
        val breakdown = ContextTokenEstimator.breakdown("system", messages, listOf(tool))

        assertEquals(Int.MAX_VALUE, breakdown.imageTokens)
        assertEquals(Int.MAX_VALUE, breakdown.estimatedTotal)
        assertEquals(legacyEstimate("system", messages, listOf(tool)), breakdown.estimatedTotal)
    }

    @Test
    fun newlyAppendedToolOutputCountsAgainstPreviousUsage() {
        val before = listOf(AgentMessage.UserMessage(content = "request"))
        val after = before + AgentMessage.ToolResultMessage(id = "call", toolName = "readFile", result = "汉".repeat(25_000))
        val baseline = ContextTokenEstimator.estimate("system", before, emptyList())
        val current = ContextTokenEstimator.estimate("system", after, emptyList())
        assertTrue(ContextTokenEstimator.calibrated(current, baseline, 110_000) >= 135_000)
    }

    @Test
    fun realUsageAnchorsPredictionEvenWhenEstimateIsHigher() {
        assertEquals(12_000, ContextTokenEstimator.calibrated(22_000, 20_000, 10_000))
        assertEquals(22_000, ContextTokenEstimator.calibrated(22_000, 20_000, 0))
    }

    @Test
    fun inputLimitOverridesLargerSharedContext() {
        assertEquals(272_000, ModelContextPolicy.effectiveInputBudget(
            ModelMetadata(id = "model", contextTokens = 400_000, inputTokens = 272_000, outputTokens = 128_000)))
    }

    @Test
    fun contextReservesOutputSpace() {
        val metadata = ModelMetadata(id = "model", contextTokens = 128_000)
        assertEquals(8_192, ModelContextPolicy.outputReserveTokens(metadata))
        assertEquals(119_808, ModelContextPolicy.effectiveInputBudget(metadata))
    }

    @Test
    fun chineseIsNotCountedAsFourCharactersPerToken() {
        assertEquals(100, ModelContextPolicy.estimateTextTokens("汉".repeat(100)))
        assertEquals(25, ModelContextPolicy.estimateTextTokens("a".repeat(100)))
        assertEquals(536_870_912, ModelContextPolicy.estimateTokens(Int.MAX_VALUE))
    }

    private fun legacyEstimate(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN
    ): Int {
        val total = ModelContextPolicy.estimateTextTokens(systemPrompt, charsPerToken).toLong() +
            tools.sumOf { tool ->
                ModelContextPolicy.estimateTextTokens(tool.name + tool.description + gson.toJson(tool.toModelJsonSchema()), charsPerToken).toLong() + 16
            } + messages.sumOf { message ->
                val text: String
                val imageCount: Int
                when (message) {
                    is AgentMessage.UserMessage -> {
                        text = message.content
                        imageCount = message.images.size
                    }
                    is AgentMessage.AssistantMessage -> {
                        text = message.content + message.reasoning + message.toolCalls.joinToString { it.name + gson.toJson(it.arguments) }
                        imageCount = message.images.size
                    }
                    is AgentMessage.ToolResultMessage -> {
                        text = message.toolName + (message.modelResult ?: modelToolResultText(message.toolName, message.result) ?: message.result)
                        imageCount = message.images.size
                    }
                }
                (ModelContextPolicy.estimateTextTokens(text, charsPerToken).toLong() + imageCount * 4_096L + 12)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
            }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
