package com.aicode.feature.agent.domain.provider

import com.aicode.core.net.NetworkStatus
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AnthropicStreamErrorTest {
    private var wasOnline = true

    @Before
    fun setUp() {
        wasOnline = NetworkStatus.isOnline
        NetworkStatus.update(true)
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
        NetworkStatus.update(wasOnline)
    }

    @Test
    fun serverError_retriesAreBoundedAndPreserveSseCodeAtEof() = runTest {
        assertBoundedRetry("server_error", RetryErrorKind.SERVER_ERROR)
    }

    @Test
    fun serverIsOverloaded_retriesAreBoundedAndPreserveSseCodeAtEof() = runTest {
        assertBoundedRetry("server_is_overloaded", RetryErrorKind.SERVER_OVERLOADED)
    }

    @Test
    fun overloadedError_retriesAreBoundedAndPreserveSseCodeAtEof() = runTest {
        assertBoundedRetry("overloaded_error", RetryErrorKind.UNKNOWN)
    }

    @Test
    fun invalidRequest_followedByMessageStop_doesNotRetryOrEmitFinal() = runTest {
        val api = FakeApi { sseBody(errorLine("invalid_request_error"), messageStop) }
        val adapter = adapter(api, maxRetries = 2)
        val keyFailures = mutableListOf<Throwable>()
        adapter.keySwitcher = { error, _, _ ->
            keyFailures += error
            null
        }
        val chunks = mutableListOf<AIStreamChunk>()

        val error = expectStreamError(adapter, chunks)

        assertEquals("invalid_request_error", error.code)
        assertEquals("upstream failure", error.message)
        assertSame(error, keyFailures.single())
        assertEquals(1, api.calls.size)
        assertTrue(chunks.none { it is AIStreamChunk.Retrying || it is AIStreamChunk.Final })
    }

    @Test
    fun partialTextThenServerError_retriesWithinLimitWithoutFalseFinal() = runTest {
        val api = FakeApi {
            sseBody(textLine("partial"), errorLine("server_error"), messageStop)
        }
        val chunks = mutableListOf<AIStreamChunk>()

        val error = expectStreamError(adapter(api, maxRetries = 1), chunks)

        assertEquals("server_error", error.code)
        assertEquals(2, api.calls.size)
        assertEquals("partial", chunks.filterIsInstance<AIStreamChunk.TextDelta>().first().text)
        assertEquals(
            listOf(AIStreamChunk.Retrying(1, 1, RetryErrorInfo(RetryErrorKind.SERVER_ERROR))),
            chunks.filterIsInstance<AIStreamChunk.Retrying>()
        )
        assertTrue(chunks.none { it is AIStreamChunk.Final })
    }

    @Test
    fun rateLimitBeforeContent_switchesKeyAndReissuesWithoutNetworkRetry() = runTest {
        val api = FakeApi { attempt ->
            when (attempt) {
                0 -> sseBody(errorLine("rate_limit_error"), messageStop)
                1 -> sseBody(textLine("recovered"), messageStop)
                else -> throw AssertionError("Unexpected extra request")
            }
        }
        val adapter = adapter(api, maxRetries = 0)
        val keyFailures = mutableListOf<Throwable>()
        val triedKeys = mutableListOf<Set<String>>()
        adapter.keySwitcher = { error, currentKey, tried ->
            assertEquals("test-key-1", currentKey)
            keyFailures += error
            triedKeys += tried.toSet()
            KeySwitchOutcome("test-key-2", 2, 2)
        }

        val chunks = adapter.completeStream("", emptyList()).buffer(0).toList()

        assertEquals("rate_limit_error", (keyFailures.single() as StreamApiException).code)
        assertEquals(listOf(setOf("test-key-1")), triedKeys)
        assertEquals(listOf("test-key-1", "test-key-2"), api.requestedKeys)
        assertEquals("test-key-2", adapter.apiKey)
        assertEquals(listOf(AIStreamChunk.KeySwitched(2, 2)), chunks.filterIsInstance<AIStreamChunk.KeySwitched>())
        assertTrue(chunks.none { it is AIStreamChunk.Retrying })
        assertEquals("recovered", chunks.filterIsInstance<AIStreamChunk.Final>().single().response.content)
    }

    @Test
    fun partialTextThenRateLimit_switchesKeyForLaterWithoutReplayOrFinal() = runTest {
        val api = FakeApi {
            sseBody(textLine("partial"), errorLine("rate_limit_error"), messageStop)
        }
        val adapter = adapter(api, maxRetries = 2)
        val keyFailures = mutableListOf<Throwable>()
        adapter.keySwitcher = { error, currentKey, tried ->
            assertEquals("test-key-1", currentKey)
            assertEquals(setOf("test-key-1"), tried)
            keyFailures += error
            KeySwitchOutcome("test-key-2", 2, 2)
        }
        val chunks = mutableListOf<AIStreamChunk>()

        val error = expectStreamError(adapter, chunks)

        assertEquals("rate_limit_error", error.code)
        assertSame(error, keyFailures.single())
        assertEquals(listOf("test-key-1"), api.requestedKeys)
        assertEquals("test-key-2", adapter.apiKey)
        assertTrue(chunks.none {
            it is AIStreamChunk.Retrying || it is AIStreamChunk.KeySwitched || it is AIStreamChunk.Final
        })
    }

    @Test
    fun malformedJsonAndWrongFieldTypes_skipOnlyBadLines() = runTest {
        val api = FakeApi {
            sseBody(
                textLine("before"),
                "data: {",
                "data: []",
                """data: {"type":{}}""",
                """data: {"type":"content_block_delta","delta":[]}""",
                """data: {"type":"message_start","message":{"usage":{"input_tokens":{}}}}""",
                textLine("after"),
                messageStop
            )
        }

        val chunks = adapter(api).completeStream("", emptyList()).buffer(0).toList()

        assertEquals(listOf("before", "after"), chunks.filterIsInstance<AIStreamChunk.TextDelta>().map { it.text })
        assertEquals("beforeafter", chunks.filterIsInstance<AIStreamChunk.Final>().single().response.content)
        assertEquals(1, api.calls.size)
        assertTrue(chunks.none { it is AIStreamChunk.Retrying })
    }

    @Test
    fun responseBodyCancellation_propagatesWithoutRetryOrFinalAndClosesBody() = runTest {
        val cancellation = CancellationException("cancelled while reading SSE")
        var closed = false
        val bodySource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw cancellation

            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val api = FakeApi {
            object : ResponseBody() {
                override fun contentType(): MediaType = "text/event-stream".toMediaType()
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = bodySource
            }
        }
        val chunks = mutableListOf<AIStreamChunk>()

        try {
            adapter(api, maxRetries = 2).completeStream("", emptyList()).buffer(0).toList(chunks)
            throw AssertionError("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals(cancellation.message, e.message)
        }

        assertEquals(1, api.calls.size)
        assertTrue(chunks.isEmpty())
        assertTrue(closed)
    }

    @Test
    fun messageStop_emitsNormalFinalWithUsageAndIgnoresTrailingError() = runTest {
        val api = FakeApi {
            sseBody(
                """data: {"type":"message_start","message":{"usage":{"input_tokens":1,"cache_read_input_tokens":2,"cache_creation_input_tokens":3}}}""",
                textLine("ok"),
                """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":7,"cache_creation_input_tokens":3}}""",
                messageStop,
                errorLine("server_error")
            )
        }

        val chunks = adapter(api).completeStream("", emptyList()).buffer(0).toList()

        val response = chunks.filterIsInstance<AIStreamChunk.Final>().single().response
        assertEquals("ok", response.content)
        assertEquals("end_turn", response.stopReason)
        assertEquals(20, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(7, response.cachedInputTokens)
        assertEquals(3, response.cacheCreationTokens)
        assertTrue(response.toolCalls.isEmpty())
        assertTrue(chunks.none { it is AIStreamChunk.Retrying || it is AIStreamChunk.KeySwitched })
        assertEquals(1, api.calls.size)
    }

    private suspend fun assertBoundedRetry(code: String, kind: RetryErrorKind) {
        val api = FakeApi { sseBody(errorLine(code)) }
        val adapter = adapter(api, maxRetries = 2)
        val keyFailures = mutableListOf<Throwable>()
        adapter.keySwitcher = { error, _, _ ->
            keyFailures += error
            null
        }
        val chunks = mutableListOf<AIStreamChunk>()

        val error = expectStreamError(adapter, chunks)

        assertEquals(code, error.code)
        assertEquals("upstream failure", error.message)
        assertEquals(3, api.calls.size)
        assertEquals(
            listOf(
                AIStreamChunk.Retrying(1, 2, RetryErrorInfo(kind)),
                AIStreamChunk.Retrying(2, 2, RetryErrorInfo(kind))
            ),
            chunks.filterIsInstance<AIStreamChunk.Retrying>()
        )
        assertTrue(keyFailures.isEmpty())
        assertTrue(chunks.none { it is AIStreamChunk.Final })
    }

    private suspend fun expectStreamError(
        adapter: AnthropicAdapter,
        chunks: MutableList<AIStreamChunk>
    ): StreamApiException = try {
        adapter.completeStream("", emptyList()).buffer(0).toList(chunks)
        throw AssertionError("Expected StreamApiException")
    } catch (e: StreamApiException) {
        e
    }

    private fun adapter(api: AnthropicApi, maxRetries: Int = 0) = AnthropicAdapter(api).apply {
        apiKey = "test-key-1"
        firstByteTimeoutMs = 0L
        streamIdleTimeoutMs = 0L
        maxNetworkRetries = maxRetries
    }

    private fun errorLine(code: String) =
        """data: {"type":"error","error":{"type":"$code","message":"upstream failure"}}"""

    private fun textLine(text: String) =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun sseBody(vararg lines: String): ResponseBody =
        (lines.joinToString("\n\n") + "\n\n").toResponseBody("text/event-stream".toMediaType())

    private val messageStop = """data: {"type":"message_stop"}"""

    private class FakeApi(private val newBody: (Int) -> ResponseBody) : AnthropicApi {
        val calls = mutableListOf<FakeCall>()
        val requestedKeys = mutableListOf<String>()

        override suspend fun createMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): AnthropicMessageResponse = throw UnsupportedOperationException("Streaming test only")

        override fun streamMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): Call<ResponseBody> {
            val attempt = calls.size
            requestedKeys += apiKey
            return FakeCall(url) { newBody(attempt) }.also { calls += it }
        }
    }

    private class FakeCall(
        private val url: String,
        private val newBody: () -> ResponseBody
    ) : Call<ResponseBody> {
        @Volatile private var executed = false
        @Volatile private var cancelled = false

        override fun execute(): Response<ResponseBody> {
            check(!executed)
            executed = true
            return Response.success(newBody())
        }

        override fun enqueue(callback: Callback<ResponseBody>) {
            callback.onResponse(this, execute())
        }

        override fun isExecuted(): Boolean = executed
        override fun cancel() { cancelled = true }
        override fun isCanceled(): Boolean = cancelled
        override fun clone(): Call<ResponseBody> = FakeCall(url, newBody)
        override fun request(): Request = Request.Builder().url(url).build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
