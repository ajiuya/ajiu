package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.ChatCompletionResponse
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response

class OpenAISpeedTierTest {
    private val gson = Gson()

    private fun adapter(api: OpenAIApi) = OpenAIAdapter(api).apply {
        apiKey = "test-key"
        model = "gpt-6-astra"
        firstByteTimeoutMs = 0
        maxNetworkRetries = 0
    }

    private fun chatResponse() = ChatCompletionResponse("id", "chat.completion", 0, "model", emptyList(), null)

    private fun responseCall(responses: Boolean): Call<ResponseBody> {
        val data = if (responses) {
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
        } else {
            "data: [DONE]\n\n"
        }
        val call = mockk<Call<ResponseBody>>()
        every { call.enqueue(any()) } answers {
            firstArg<Callback<ResponseBody>>().onResponse(call, Response.success(data.toResponseBody()))
        }
        every { call.cancel() } returns Unit
        every { call.isCanceled } returns false
        every { call.request() } returns Request.Builder().url("https://api.openai.com/").build()
        return call
    }

    private suspend fun send(adapter: OpenAIAdapter, streaming: Boolean) {
        if (streaming) adapter.completeStream("", emptyList()).toList()
        else adapter.complete("", emptyList())
    }

    @Test
    fun defaultSourceOmitsTierForBothProtocolsAndStreamModes() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val chat = slot<ChatCompletionRequest>()
            val body = slot<Map<String, Any?>>()
            coEvery { api.createChatCompletion(any(), any(), any(), capture(chat)) } returns chatResponse()
            every { api.streamChatCompletion(any(), any(), any(), capture(chat)) } answers { responseCall(false) }
            coEvery { api.createResponses(any(), any(), any(), capture(body)) } returns JsonObject()
            every { api.streamResponses(any(), any(), any(), capture(body)) } answers { responseCall(true) }
            val adapter = adapter(api).apply {
                providerId = "xai"
                useResponseApi = responses
            }
            send(adapter, streaming)
            val json = if (responses) gson.toJsonTree(body.captured).asJsonObject else gson.toJsonTree(chat.captured).asJsonObject
            assertFalse(json.has("service_tier"))
        }
    }

    @Test
    fun responsesAllThreeTiersAreReadDynamicallyOnSameAdapterForBothStreamModes() = runTest {
        for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val requests = mutableListOf<Map<String, Any?>>()
            coEvery { api.createResponses(any(), any(), any(), capture(requests)) } returns JsonObject()
            every { api.streamResponses(any(), any(), any(), capture(requests)) } answers { responseCall(true) }
            var tier = SpeedTier.STANDARD
            var reads = 0
            val adapter = adapter(api).apply {
                useResponseApi = true
                speedTierSource = { reads++; tier }
            }
            for (chosen in SpeedTier.entries) {
                tier = chosen
                send(adapter, streaming)
            }
            assertEquals(listOf(null, "priority", "ultrafast"), requests.map { it["service_tier"] })
            assertFalse(requests.first().containsKey("service_tier"))
            assertEquals(3, reads)
        }
    }

    @Test
    fun xaiChatAllThreeTiersAreDynamicAndAlwaysUsePriorityForBothStreamModes() = runTest {
        for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val requests = mutableListOf<ChatCompletionRequest>()
            coEvery { api.createChatCompletion(any(), any(), any(), capture(requests)) } returns chatResponse()
            every { api.streamChatCompletion(any(), any(), any(), capture(requests)) } answers { responseCall(false) }
            var tier = SpeedTier.STANDARD
            var reads = 0
            val adapter = adapter(api).apply {
                providerId = "xai"
                model = "grok-4"
                speedTierSource = { reads++; tier }
            }
            for (chosen in SpeedTier.entries) {
                tier = chosen
                send(adapter, streaming)
            }
            assertEquals(listOf(null, "priority", "priority"), requests.map { it.service_tier })
            assertEquals(3, reads)
        }
    }

    @Test
    fun ordinaryChatOmitsTierEvenForGptAndBothFastChoices() = runTest {
        for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val requests = mutableListOf<ChatCompletionRequest>()
            coEvery { api.createChatCompletion(any(), any(), any(), capture(requests)) } returns chatResponse()
            every { api.streamChatCompletion(any(), any(), any(), capture(requests)) } answers { responseCall(false) }
            val adapter = adapter(api)
            for (chosen in listOf(SpeedTier.FAST, SpeedTier.ULTRAFAST)) {
                adapter.speedTierSource = { chosen }
                send(adapter, streaming)
            }
            assertTrue(requests.all { !gson.toJsonTree(it).asJsonObject.has("service_tier") })
        }
    }

    @Test
    fun nonGptResponsesOmitTierAndOlderGptDowngradesWithoutChangingChoice() = runTest {
        for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val requests = mutableListOf<Map<String, Any?>>()
            coEvery { api.createResponses(any(), any(), any(), capture(requests)) } returns JsonObject()
            every { api.streamResponses(any(), any(), any(), capture(requests)) } answers { responseCall(true) }
            val chosen = SpeedTier.ULTRAFAST
            val adapter = adapter(api).apply {
                useResponseApi = true
                speedTierSource = { chosen }
            }
            for (name in listOf("o3", "qwen3", "claude-opus", "gemini-pro", "gpt-5.6", "gpt-6-astra")) {
                adapter.model = name
                send(adapter, streaming)
            }
            assertTrue(requests.take(4).all { !it.containsKey("service_tier") })
            assertEquals("priority", requests[4]["service_tier"])
            assertEquals("ultrafast", requests[5]["service_tier"])
            assertEquals(SpeedTier.ULTRAFAST, chosen)
        }
    }

    @Test
    fun xaiFullUrlUsesPriorityOnBothProtocolsWithoutChangingCustomHeaders() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val chat = slot<ChatCompletionRequest>()
            val body = slot<Map<String, Any?>>()
            val headers = slot<Map<String, String>>()
            val url = slot<String>()
            coEvery { api.createChatCompletion(capture(url), any(), capture(headers), capture(chat)) } returns chatResponse()
            every { api.streamChatCompletion(capture(url), any(), capture(headers), capture(chat)) } answers { responseCall(false) }
            coEvery { api.createResponses(capture(url), any(), capture(headers), capture(body)) } returns JsonObject()
            every { api.streamResponses(capture(url), any(), capture(headers), capture(body)) } answers { responseCall(true) }
            val fullUrl = "https://API.X.AI/custom/endpoint"
            val custom = mapOf("X-Test" to "preserved")
            val adapter = adapter(api).apply {
                baseUrl = fullUrl
                useFullUrl = true
                useResponseApi = responses
                model = "grok-4"
                customHeaders = custom
                speedTierSource = { SpeedTier.ULTRAFAST }
            }
            send(adapter, streaming)
            assertEquals(fullUrl, url.captured)
            assertEquals(custom, headers.captured)
            assertEquals("priority", if (responses) body.captured["service_tier"] else chat.captured.service_tier)
        }
    }

    @Test
    fun networkRetryReusesBuiltBodyAndReadsPreferenceOnlyOnceForBothProtocolsAndStreamModes() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val bodies = mutableListOf<Any>()
            var chosen = SpeedTier.ULTRAFAST
            var reads = 0
            fun checkAttempt(body: Any) {
                bodies.add(body)
                if (bodies.size == 1) {
                    chosen = SpeedTier.STANDARD
                    throw IOException("temporary network failure")
                }
            }
            coEvery { api.createChatCompletion(any(), any(), any(), any()) } answers {
                checkAttempt(arg<ChatCompletionRequest>(3)); chatResponse()
            }
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                checkAttempt(arg<ChatCompletionRequest>(3)); responseCall(false)
            }
            coEvery { api.createResponses(any(), any(), any(), any()) } answers {
                checkAttempt(arg<Any>(3)); JsonObject()
            }
            every { api.streamResponses(any(), any(), any(), any()) } answers {
                checkAttempt(arg<Any>(3)); responseCall(true)
            }
            val adapter = adapter(api).apply {
                useResponseApi = responses
                providerId = if (responses) "openai" else "xai"
                maxNetworkRetries = 1
                speedTierSource = { reads++; chosen }
            }
            send(adapter, streaming)
            assertEquals(2, bodies.size)
            assertSame(bodies[0], bodies[1])
            val expectedTier = if (responses) "ultrafast" else "priority"
            assertTrue(bodies.all { gson.toJsonTree(it).asJsonObject["service_tier"].asString == expectedTier })
            assertEquals(1, reads)
        }
    }

    @Test
    fun rejectedTierIsNotRetriedAsStandardForBothProtocolsAndStreamModes() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val bodies = mutableListOf<Any>()
            var reads = 0
            fun reject(body: Any): Nothing {
                bodies.add(body)
                throw HttpException(Response.error<Any>(400, "unsupported service_tier".toResponseBody()))
            }
            coEvery { api.createChatCompletion(any(), any(), any(), any()) } answers {
                reject(arg<ChatCompletionRequest>(3))
            }
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                reject(arg<ChatCompletionRequest>(3))
            }
            coEvery { api.createResponses(any(), any(), any(), any()) } answers {
                reject(arg<Any>(3))
            }
            every { api.streamResponses(any(), any(), any(), any()) } answers {
                reject(arg<Any>(3))
            }
            val adapter = adapter(api).apply {
                useResponseApi = responses
                providerId = if (responses) "openai" else "xai"
                maxNetworkRetries = 3
                speedTierSource = { reads++; SpeedTier.ULTRAFAST }
            }
            val result = runCatching { send(adapter, streaming) }
            assertTrue(result.isFailure)
            assertEquals(1, bodies.size)
            val expectedTier = if (responses) "ultrafast" else "priority"
            assertEquals(expectedTier, gson.toJsonTree(bodies.single()).asJsonObject["service_tier"].asString)
            assertEquals(1, reads)
        }
    }

    @Test
    fun keySwitchRetryReusesTierForBothProtocolsAndStreamModes() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val bodies = mutableListOf<Any>()
            var chosen = SpeedTier.ULTRAFAST
            var reads = 0
            fun checkAttempt(body: Any) {
                bodies.add(body)
                if (bodies.size == 1) throw HttpException(Response.error<Any>(401, "unauthorized".toResponseBody()))
            }
            coEvery { api.createChatCompletion(any(), any(), any(), any()) } answers {
                checkAttempt(arg<ChatCompletionRequest>(3)); chatResponse()
            }
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                checkAttempt(arg<ChatCompletionRequest>(3)); responseCall(false)
            }
            coEvery { api.createResponses(any(), any(), any(), any()) } answers {
                checkAttempt(arg<Any>(3)); JsonObject()
            }
            every { api.streamResponses(any(), any(), any(), any()) } answers {
                checkAttempt(arg<Any>(3)); responseCall(true)
            }
            val adapter = adapter(api).apply {
                useResponseApi = responses
                providerId = "xai"
                model = "grok-4"
                speedTierSource = { reads++; chosen }
                keySwitcher = { error, failedKey, triedKeys ->
                    assertTrue(error is HttpException)
                    assertEquals(401, (error as HttpException).code())
                    assertEquals("test-key", failedKey)
                    assertEquals(setOf("test-key"), triedKeys)
                    chosen = SpeedTier.STANDARD
                    KeySwitchOutcome("other-test-key", 1, 2)
                }
            }
            send(adapter, streaming)
            assertEquals(2, bodies.size)
            assertSame(bodies[0], bodies[1])
            assertTrue(bodies.all { gson.toJsonTree(it).asJsonObject["service_tier"].asString == "priority" })
            assertEquals(1, reads)
        }
    }

    @Test
    fun changingTierLeavesReasoningBudgetTemperatureHistoryAndMcpSchemaUntouched() = runTest {
        for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
            val api = mockk<OpenAIApi>()
            val bodies = mutableListOf<Any>()
            coEvery { api.createChatCompletion(any(), any(), any(), any()) } answers {
                bodies.add(arg<ChatCompletionRequest>(3)); chatResponse()
            }
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                bodies.add(arg<ChatCompletionRequest>(3)); responseCall(false)
            }
            coEvery { api.createResponses(any(), any(), any(), any()) } answers {
                bodies.add(arg<Any>(3)); JsonObject()
            }
            every { api.streamResponses(any(), any(), any(), any()) } answers {
                bodies.add(arg<Any>(3)); responseCall(true)
            }
            val schema = mapOf<String, Any>(
                "type" to "object",
                "properties" to mapOf("query" to mapOf("type" to "string")),
                "additionalProperties" to false,
                "required" to listOf("query")
            )
            val tool = object : AgentTool() {
                override val name = "mcp__speed__search"
                override val description = "Search"
                override val parameters = emptyMap<String, ToolParameter>()
                override fun toJsonSchema(): Map<String, Any> = schema
                override suspend fun execute(args: Map<String, JsonElement>): ToolResult = error("Unexpected execution")
            }
            val arguments = mapOf("query" to JsonPrimitive("full arguments ".repeat(100)))
            val history = listOf(
                AgentMessage.UserMessage(content = "question"),
                AgentMessage.AssistantMessage(
                    content = "",
                    reasoning = "preserved reasoning",
                    toolCalls = listOf(ToolCall("call", tool.name, arguments))
                ),
                AgentMessage.ToolResultMessage(id = "call", toolName = tool.name, result = "result")
            )
            var chosen = SpeedTier.STANDARD
            val adapter = adapter(api).apply {
                useResponseApi = responses
                providerId = if (responses) "openai" else "xai"
                chatCacheKeyEnabled = true
                logSessionId = "speed-tier-fixture"
                temperature = 0.25f
                maxOutputTokens = 1234
                speedTierSource = { chosen }
            }
            for (tier in listOf(SpeedTier.STANDARD, SpeedTier.ULTRAFAST)) {
                chosen = tier
                if (streaming) adapter.completeStream("system", history, listOf(tool), "high").toList()
                else adapter.complete("system", history, listOf(tool), "high")
            }
            val standard = gson.toJsonTree(bodies[0]).asJsonObject
            val fast = gson.toJsonTree(bodies[1]).asJsonObject
            assertFalse(standard.has("service_tier"))
            assertEquals(if (responses) "ultrafast" else "priority", fast.remove("service_tier").asString)
            assertEquals(standard, fast)
            val wireTool = standard.getAsJsonArray("tools").single().asJsonObject
            val parameters = if (responses) wireTool["parameters"] else wireTool.getAsJsonObject("function")["parameters"]
            assertEquals(gson.toJsonTree(schema), parameters)
            assertTrue(standard.toString().contains("full arguments ".repeat(100)))
        }
    }
}
