package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.ChatCompletionResponse
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolPresentation
import com.aicode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ToolSchemaBoundaryTest {
    private val gson = Gson()
    private val tools = listOf(
        object : AgentTool() {
            override val name = "writeFile"
            override val description = "Write"
            override val parameters = linkedMapOf(
                "path" to ToolParameter("path", ParameterType.STRING, "Path"),
                "content" to ToolParameter("content", ParameterType.STRING, "Content"),
                "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "Overwrite", false)
            )
            override suspend fun execute(args: Map<String, JsonElement>) = ToolResult.Success(JsonNull)
        },
        object : AgentTool() {
            override val name = "mcp__server__remote"
            override val description = "Remote"
            override val parameters = emptyMap<String, ToolParameter>()
            override fun toJsonSchema(): Map<String, Any> = mapOf(
                "type" to "object",
                "properties" to mapOf("remote" to mapOf("type" to "string")),
                "required" to listOf("remote")
            )
            override suspend fun execute(args: Map<String, JsonElement>) = ToolResult.Success(JsonNull)
        }
    )

    private fun call(vararg data: String): Call<ResponseBody> {
        val body = data.joinToString("\n") { "data: $it" }.plus("\n").toResponseBody(null)
        val call = mockk<Call<ResponseBody>>()
        every { call.enqueue(any()) } answers {
            firstArg<Callback<ResponseBody>>().onResponse(call, Response.success(body))
        }
        every { call.cancel() } returns Unit
        every { call.isCanceled } returns false
        return call
    }

    @Test
    fun allFiveProtocolsUseModelSchemasInStreamAndNonStreamRequests() = runTest {
        val requests = mutableListOf<Any>()
        val openai = mockk<OpenAIApi>()
        val anthropic = mockk<AnthropicApi>()
        val gemini = mockk<GeminiApi>()
        val streamedArguments = """{"tool_title":"Inspect","path":"file.txt","content":"streamed body"}"""
        val quotedArguments = gson.toJson(streamedArguments)
        coEvery { openai.createChatCompletion(any(), any(), any(), any()) } coAnswers {
            requests += arg<Any>(3)
            gson.fromJson("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""", ChatCompletionResponse::class.java)
        }
        coEvery { openai.createResponses(any(), any(), any(), any()) } coAnswers {
            requests += arg<Any>(3)
            JsonParser.parseString("""{"status":"completed","output":[]}""").asJsonObject
        }
        coEvery { anthropic.createMessage(any(), any(), any(), any(), any()) } coAnswers {
            requests += arg<Any>(4)
            gson.fromJson("""{"content":[],"usage":{"input_tokens":1,"output_tokens":1}}""", AnthropicMessageResponse::class.java)
        }
        coEvery { gemini.generateContent(any(), any(), any(), any()) } coAnswers {
            requests += arg<Any>(3)
            JsonParser.parseString("""{"candidates":[{"finishReason":"STOP","content":{"parts":[]}}]}""").asJsonObject
        }
        coEvery { gemini.createInteraction(any(), any(), any(), any()) } coAnswers {
            requests += arg<Any>(3)
            JsonParser.parseString("""{"status":"completed","steps":[]}""").asJsonObject
        }
        every { openai.streamChatCompletion(any(), any(), any(), any()) } answers {
            requests += arg<Any>(3)
            call(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"new-call","function":{"name":"writeFile","arguments":$quotedArguments}}]}}]}""",
                "[DONE]"
            )
        }
        every { openai.streamResponses(any(), any(), any(), any()) } answers {
            requests += arg<Any>(3)
            call(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_new","call_id":"new-call","name":"writeFile","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_new","delta":$quotedArguments}""",
                """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
            )
        }
        every { anthropic.streamMessage(any(), any(), any(), any(), any()) } answers {
            requests += arg<Any>(4)
            call(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"new-call","name":"writeFile","input":$streamedArguments}}""",
                """{"type":"message_stop"}"""
            )
        }
        every { gemini.streamGenerateContent(any(), any(), any(), any()) } answers {
            requests += arg<Any>(3)
            call("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"id":"new-call","name":"writeFile","args":$streamedArguments},"thoughtSignature":"new-signature"}]}}]}""")
        }
        every { gemini.streamInteraction(any(), any(), any(), any()) } answers {
            requests += arg<Any>(3)
            call(
                """{"event_type":"step.start","index":0,"step":{"type":"function_call","id":"new-call","name":"writeFile","arguments":$streamedArguments}}""",
                """{"event_type":"interaction.requires_action","interaction":{"status":"requires_action"}}"""
            )
        }
        val providers = listOf(
            OpenAIAdapter(openai),
            OpenAIAdapter(openai).apply { useResponseApi = true },
            AnthropicAdapter(anthropic),
            GeminiAdapter(gemini),
            GeminiAdapter(gemini).apply { useResponseApi = true }
        )
        val call = ToolCall("call", "writeFile", mapOf(
            "tool_title" to kotlinx.serialization.json.JsonPrimitive("Write file"),
            "path" to kotlinx.serialization.json.JsonPrimitive("file.txt"),
            "content" to kotlinx.serialization.json.JsonPrimitive("full body")
        ))
        assertFalse(ToolPresentation.executionArguments(tools[0], call.arguments).containsKey("tool_title"))
        val args = kotlinx.serialization.json.JsonObject(call.arguments).toString()
        val snapshots = listOf(
            "",
            """{"type":"reasoning","encrypted_content":"raw-encrypted","summary":[]}""",
            """[{"type":"thinking","thinking":"thought","signature":"raw-signature"}]""",
            """[{"functionCall":{"id":"call","name":"writeFile","args":$args},"thoughtSignature":"raw-signature"}]""",
            """[{"type":"thought","signature":"raw-signature"},{"type":"function_call","id":"call","name":"writeFile","arguments":$args}]"""
        )
        providers.forEachIndexed { index, provider ->
            val history = listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call), thinkingBlocksJson = snapshots[index]),
                AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = "ok")
            )
            provider.maxNetworkRetries = 0
            provider.complete("sys", history, tools)
            val chunks = provider.completeStream("sys", history, tools).toList()
            val preview = chunks.filterIsInstance<AIStreamChunk.ToolCallArgumentsDelta>().last()
            assertEquals("new-call", preview.id)
            assertEquals("writeFile", preview.name)
            assertEquals(JsonParser.parseString(streamedArguments), JsonParser.parseString(preview.accumulated))
            val final = chunks.last() as AIStreamChunk.Final
            assertTrue(requireNotNull(final.requestDurationMillis) >= 0)
            val finalCall = final.response.toolCalls.single()
            assertEquals(streamedArguments, kotlinx.serialization.json.JsonObject(finalCall.arguments).toString())
            assertEquals(args, kotlinx.serialization.json.JsonObject(call.arguments).toString())
        }
        assertEquals(10, requests.size)
        requests.forEachIndexed { requestIndex, request ->
            val wire = gson.toJsonTree(request).asJsonObject
            when (requestIndex / 2) {
                0 -> {
                    val assistant = wire.getAsJsonArray("messages").first { it.asJsonObject.get("role").asString == "assistant" }.asJsonObject
                    val historyArgs = assistant.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").get("arguments").asString
                    assertEquals(JsonParser.parseString(args), JsonParser.parseString(historyArgs))
                }
                1 -> {
                    val items = wire.getAsJsonArray("input")
                    val function = items.first { it.asJsonObject.get("type")?.asString == "function_call" }.asJsonObject
                    assertEquals(JsonParser.parseString(args), JsonParser.parseString(function.get("arguments").asString))
                    assertTrue(wire.toString().contains("raw-encrypted"))
                }
                2 -> {
                    val assistant = wire.getAsJsonArray("messages").first { it.asJsonObject.get("role").asString == "assistant" }.asJsonObject
                    val input = assistant.getAsJsonArray("content").first { it.asJsonObject.get("type").asString == "tool_use" }.asJsonObject.get("input")
                    assertEquals(JsonParser.parseString(args), input)
                    assertTrue(wire.toString().contains("raw-signature"))
                }
                3 -> {
                    val model = wire.getAsJsonArray("contents").first { it.asJsonObject.get("role").asString == "model" }.asJsonObject
                    assertEquals(JsonParser.parseString(snapshots[3]), model.get("parts"))
                }
                4 -> {
                    val input = wire.getAsJsonArray("input")
                    assertEquals(JsonParser.parseString(snapshots[4]).asJsonArray.toList(), input.filter { it.asJsonObject.get("type").asString in listOf("thought", "function_call") })
                }
            }
            val root = wire.getAsJsonArray("tools")
            val definitions = if (root[0].asJsonObject.has("functionDeclarations")) {
                root[0].asJsonObject.getAsJsonArray("functionDeclarations")
            } else root
            assertEquals(2, definitions.size())
            definitions.forEachIndexed { index, element ->
                val definition = element.asJsonObject
                val function = definition.get("function")?.asJsonObject ?: definition
                val schema = (function.get("parameters") ?: function.get("input_schema")).asJsonObject
                assertEquals(gson.toJsonTree(tools[index].toModelJsonSchema()), schema)
                if (index == 0) {
                    assertEquals(listOf("tool_title", "path", "content", "overwrite"), schema.getAsJsonObject("properties").keySet().toList())
                    assertEquals(listOf("tool_title", "path", "content"), schema.getAsJsonArray("required").map { it.asString })
                    assertFalse(schema.has("additionalProperties"))
                } else {
                    assertTrue(schema.getAsJsonObject("properties").has("remote"))
                    assertFalse(schema.getAsJsonObject("properties").has("tool_title"))
                }
            }
        }
    }
}
