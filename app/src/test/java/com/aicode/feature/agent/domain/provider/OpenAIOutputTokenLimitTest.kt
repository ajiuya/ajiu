package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OpenAIOutputTokenLimitTest {
    private data class WireRequest(val path: String, val body: JsonObject)

    private class Fixture : Closeable {
        private val requests = LinkedBlockingQueue<WireRequest>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
            requests.add(WireRequest(request.url.encodedPath, body))
            val responses = request.url.encodedPath.endsWith("/responses")
            val streaming = body["stream"]?.asBoolean == true
            val response = if (responses) {
                """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"summary"}]}]}"""
            } else {
                """{"id":"fixture","object":"chat.completion","created":0,"model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"summary"},"finish_reason":"stop"}]}"""
            }
            val responseBody = when {
                streaming && responses -> "data: {\"type\":\"response.completed\",\"response\":$response}\n\n"
                streaming -> "data: {\"choices\":[{\"delta\":{\"content\":\"summary\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                else -> response
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType()))
                .build()
        }.build()
        val adapter = OpenAIAdapter(
            Retrofit.Builder()
                .baseUrl("https://fixture.invalid/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(Gson()))
                .build()
                .create(OpenAIApi::class.java)
        ).apply {
            baseUrl = "https://fixture.invalid/"
            firstByteTimeoutMs = 0
            maxNetworkRetries = 0
        }

        suspend fun send(streaming: Boolean): WireRequest {
            val messages = listOf(AgentMessage.UserMessage(content = "Summarize this history"))
            val response = if (streaming) {
                adapter.completeStream("summary fixture", messages).toList()
                    .filterIsInstance<AIStreamChunk.Final>().single().response
            } else {
                adapter.complete("summary fixture", messages)
            }
            assertEquals("summary", response.content)
            return requireNotNull(requests.poll())
        }

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun assertLimit(body: JsonObject, field: String, expected: Int) {
        assertEquals(listOf(field), listOf("max_tokens", "max_completion_tokens", "max_output_tokens").filter { body.has(it) })
        assertEquals(expected, body[field].asInt)
    }

    private fun assertNoLimit(body: JsonObject) {
        for (field in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            assertFalse("Unexpected $field", body.has(field))
        }
    }

    @Test
    fun ordinaryChatAndResponsesIgnoreMetadataOutputLimitForBothStreamModes() = runTest {
        Fixture().use { fixture ->
            assertNull(fixture.adapter.requestOutputTokenLimit)
            for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
                fixture.adapter.useResponseApi = responses
                for (model in listOf("gpt-5.2", "o3-mini", "deepseek-v4-pro")) {
                    fixture.adapter.model = model
                    for (metadataLimit in listOf(null, 16_384)) {
                        fixture.adapter.maxOutputTokens = metadataLimit
                        val request = fixture.send(streaming)
                        assertNoLimit(request.body)
                        assertEquals(if (responses) "/v1/responses" else "/v1/chat/completions", request.path)
                    }
                }
            }
        }
    }

    @Test
    fun compatibleChatUsesOnlyMaxTokensForBothStreamModes() = runTest {
        Fixture().use { fixture ->
            fixture.adapter.maxOutputTokens = 16_384
            fixture.adapter.requestOutputTokenLimit = 8_192
            for (streaming in listOf(false, true)) {
                for (model in listOf("deepseek-v4-pro", "DeepSeek/DeepSeek-V4-Pro", "qwen/qwen3.8-max", "kimi-k3", "other-model")) {
                    fixture.adapter.model = model
                    val request = fixture.send(streaming)
                    assertEquals("/v1/chat/completions", request.path)
                    assertEquals(model, request.body["model"].asString)
                    assertLimit(request.body, "max_tokens", 8_192)
                    assertEquals(streaming, request.body["stream"].asBoolean)
                }
            }
        }
    }

    @Test
    fun gptAndOfficialReasoningChatUseOnlyMaxCompletionTokensForBothStreamModes() = runTest {
        Fixture().use { fixture ->
            fixture.adapter.maxOutputTokens = 16_384
            fixture.adapter.requestOutputTokenLimit = 2_048
            for (streaming in listOf(false, true)) {
                for (model in listOf("gpt-4-turbo", "gpt-5.2", "GPT-6-astra", "openai/gpt-5.6", "o1", "o3-mini", "openai/o4-mini")) {
                    fixture.adapter.model = model
                    val request = fixture.send(streaming)
                    assertEquals("/v1/chat/completions", request.path)
                    assertEquals(model, request.body["model"].asString)
                    assertLimit(request.body, "max_completion_tokens", 2_048)
                    assertEquals(streaming, request.body["stream"].asBoolean)
                }
            }
        }
    }

    @Test
    fun responsesUseOnlyMaxOutputTokensForBothStreamModes() = runTest {
        Fixture().use { fixture ->
            fixture.adapter.useResponseApi = true
            fixture.adapter.maxOutputTokens = 16_384
            fixture.adapter.requestOutputTokenLimit = 4_096
            for (streaming in listOf(false, true)) {
                for (model in listOf("gpt-5.2", "o3-mini", "deepseek-v4-pro")) {
                    fixture.adapter.model = model
                    val request = fixture.send(streaming)
                    assertEquals("/v1/responses", request.path)
                    assertLimit(request.body, "max_output_tokens", 4_096)
                    assertEquals(streaming, request.body["stream"]?.asBoolean == true)
                }
            }
        }
    }

    @Test
    fun clearingExplicitLimitRestoresUncappedWireBodyForAllRequestPaths() = runTest {
        Fixture().use { fixture ->
            fixture.adapter.maxOutputTokens = 16_384
            for (responses in listOf(false, true)) for (streaming in listOf(false, true)) {
                fixture.adapter.useResponseApi = responses
                for (model in listOf("gpt-5.2", "deepseek-v4-pro")) {
                    fixture.adapter.model = model
                    fixture.adapter.requestOutputTokenLimit = null
                    val original = fixture.send(streaming)
                    fixture.adapter.requestOutputTokenLimit = 1_024
                    val capped = fixture.send(streaming)
                    val field = when {
                        responses -> "max_output_tokens"
                        model.startsWith("gpt-") -> "max_completion_tokens"
                        else -> "max_tokens"
                    }
                    assertLimit(capped.body, field, 1_024)
                    capped.body.remove(field)
                    assertEquals(original, capped)
                    fixture.adapter.requestOutputTokenLimit = null
                    assertEquals(original, fixture.send(streaming))
                    assertEquals(16_384, fixture.adapter.maxOutputTokens)
                    assertEquals("https://fixture.invalid/", fixture.adapter.baseUrl)
                }
            }
        }
    }
}
