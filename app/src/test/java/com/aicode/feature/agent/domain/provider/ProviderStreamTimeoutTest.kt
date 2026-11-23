package com.aicode.feature.agent.domain.provider

import com.aicode.core.net.NetworkStatus
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(Parameterized::class)
class ProviderStreamTimeoutTest(private val protocol: StreamProtocol) {
    enum class StreamProtocol {
        OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI_GENERATE, GEMINI_INTERACTIONS;

        fun textLine(text: String): String = when (this) {
            OPENAI_CHAT -> """data: {"choices":[{"delta":{"content":"$text"}}]}"""
            OPENAI_RESPONSES -> """data: {"type":"response.output_text.delta","delta":"$text"}"""
            ANTHROPIC -> """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""
            GEMINI_GENERATE -> """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""
            GEMINI_INTERACTIONS -> """data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"$text"}}"""
        }

        fun terminalLine(): String = when (this) {
            OPENAI_CHAT -> "data: [DONE]"
            OPENAI_RESPONSES -> """data: {"type":"response.completed","response":{"status":"completed","output":[]}}"""
            ANTHROPIC -> """data: {"type":"message_stop"}"""
            GEMINI_GENERATE -> """data: {"candidates":[{"finishReason":"STOP"}]}"""
            GEMINI_INTERACTIONS -> """data: {"event_type":"interaction.completed","interaction":{"status":"completed"}}"""
        }
    }

    private var wasOnline = true

    @Before
    fun setUp() {
        wasOnline = NetworkStatus.isOnline
        NetworkStatus.update(true)
    }

    @After
    fun tearDown() {
        NetworkStatus.update(wasOnline)
    }

    @Test(timeout = 10_000)
    fun eofWithoutTerminalIsInterruptedRatherThanFalseFinal() = runBlocking(Dispatchers.IO) {
        val contentReceived = CountDownLatch(1)
        val harness = Harness { ControlledBody(sse(protocol.textLine("partial")), beforeEof = contentReceived) }
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks) {
            if (it is AIStreamChunk.TextDelta) contentReceived.countDown()
        }
        assertTrue(error is UnexpectedStreamEndException)
        assertEquals(RetryErrorKind.STREAM_INTERRUPTED, error.toRetryErrorInfo().kind)
        assertEquals(listOf("partial"), chunks.filterIsInstance<AIStreamChunk.TextDelta>().map { it.text })
        assertTrue(chunks.none { it is AIStreamChunk.Final || it is AIStreamChunk.Retrying })
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().body.closed.get())
    }

    @Test(timeout = 10_000)
    fun emptyEofIsInterruptedNotFirstContentTimeout() = runBlocking(Dispatchers.IO) {
        val harness = Harness { ControlledBody("") }
        harness.provider.firstByteTimeoutMs = 5_000
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks)
        assertTrue(error is UnexpectedStreamEndException)
        assertEquals(RetryErrorKind.STREAM_INTERRUPTED, error.toRetryErrorInfo().kind)
        assertTrue(chunks.isEmpty())
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().body.closed.get())
    }

    @Test(timeout = 10_000)
    fun eofRetryEventCarriesInterruptedClassificationThenRecovers() = runBlocking(Dispatchers.IO) {
        val harness = Harness { attempt ->
            if (attempt == 0) ControlledBody("")
            else ControlledBody(sse(protocol.textLine("ok"), protocol.terminalLine()))
        }
        harness.provider.maxNetworkRetries = 1
        val chunks = harness.provider.completeStream("", emptyList()).buffer(0).toList()
        assertEquals(2, harness.calls.size)
        assertEquals(
            listOf(AIStreamChunk.Retrying(1, 1, RetryErrorInfo(RetryErrorKind.STREAM_INTERRUPTED))),
            chunks.filterIsInstance<AIStreamChunk.Retrying>()
        )
        assertEquals("ok", chunks.filterIsInstance<AIStreamChunk.Final>().single().response.content)
        assertTrue(harness.calls.all { it.body.closed.get() })
    }

    @Test(timeout = 10_000)
    fun terminalEventProducesOneFinalWithoutWaitingForEof() = runBlocking(Dispatchers.IO) {
        val harness = Harness {
            ControlledBody(sse(protocol.textLine("ok"), protocol.terminalLine()), blockAtEnd = true)
        }
        harness.provider.firstByteTimeoutMs = 5_000
        harness.provider.streamIdleTimeoutMs = 1_000
        val chunks = harness.provider.completeStream("", emptyList()).buffer(0).toList()
        assertEquals(listOf("ok"), chunks.filterIsInstance<AIStreamChunk.TextDelta>().map { it.text })
        assertEquals("ok", chunks.filterIsInstance<AIStreamChunk.Final>().single().response.content)
        assertTrue(chunks.none { it is AIStreamChunk.Retrying })
        assertEquals(1, harness.calls.size)
        assertEquals("Adapter must stop at the terminal event, not read to EOF", 1L,
            harness.calls.single().body.blockedReadStarted.count)
        assertTrue(harness.calls.single().body.closed.get())
        assertFalse(harness.calls.single().isCanceled)
    }

    @Test(timeout = 10_000)
    fun missingFirstContentCancelsCallAndClassifiesFirstContentTimeout() = runBlocking(Dispatchers.IO) {
        val harness = Harness { ControlledBody(": heartbeat\n\n", blockAtEnd = true) }
        harness.provider.firstByteTimeoutMs = 200
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks)
        assertTrue(error is FirstContentTimeoutException)
        assertEquals(200L, (error as FirstContentTimeoutException).timeoutMs)
        assertEquals(RetryErrorKind.FIRST_CONTENT_TIMEOUT, error.toRetryErrorInfo().kind)
        assertTrue(chunks.isEmpty())
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().isCanceled)
        assertTrue(harness.calls.single().body.closed.get())
    }

    @Test(timeout = 10_000)
    fun firstContentTimeoutAlsoCoversWaitingForResponseHeaders() = runBlocking(Dispatchers.IO) {
        val harness = Harness(deferHeaders = true) { ControlledBody("") }
        harness.provider.firstByteTimeoutMs = 200
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks)
        assertTrue(error is FirstContentTimeoutException)
        assertEquals(RetryErrorKind.FIRST_CONTENT_TIMEOUT, error.toRetryErrorInfo().kind)
        assertTrue(chunks.isEmpty())
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().isCanceled)
        assertEquals(1L, harness.calls.single().body.blockedReadStarted.count)
    }

    @Test(timeout = 10_000)
    fun partialContentThenBlockedReadIsIdleTimeoutNotFirstContentTimeout() = runBlocking(Dispatchers.IO) {
        val harness = Harness { ControlledBody(sse(protocol.textLine("partial")), blockAtEnd = true) }
        harness.provider.firstByteTimeoutMs = 5_000
        harness.provider.streamIdleTimeoutMs = 100
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks)
        assertTrue(error is StreamIdleTimeoutException)
        assertEquals(100L, (error as StreamIdleTimeoutException).timeoutMs)
        assertEquals(RetryErrorKind.STREAM_IDLE_TIMEOUT, error.toRetryErrorInfo().kind)
        assertEquals(listOf("partial"), chunks.filterIsInstance<AIStreamChunk.TextDelta>().map { it.text })
        assertTrue(chunks.none { it is AIStreamChunk.Final || it is AIStreamChunk.Retrying })
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().isCanceled)
        assertTrue(harness.calls.single().body.closed.get())
        assertEquals(0L, harness.calls.single().body.blockedReadStarted.count)
    }

    @Test(timeout = 10_000)
    fun userCancellationUnblocksReaderWithoutRetryOrTimeoutConversion() = runBlocking(Dispatchers.IO) {
        val harness = Harness { ControlledBody("", blockAtEnd = true) }
        harness.provider.firstByteTimeoutMs = 5_000
        harness.provider.streamIdleTimeoutMs = 5_000
        harness.provider.maxNetworkRetries = 2
        val chunks = CopyOnWriteArrayList<AIStreamChunk>()
        val failure = AtomicReference<Throwable?>(null)
        val job = launch {
            try {
                harness.provider.completeStream("", emptyList()).buffer(0).toList(chunks)
            } catch (e: Throwable) {
                failure.set(e)
                throw e
            }
        }
        try {
            assertTrue("Adapter did not issue its request", harness.requestStarted.await(2, TimeUnit.SECONDS))
            assertTrue("Adapter did not enter a blocking read",
                harness.calls.single().body.blockedReadStarted.await(2, TimeUnit.SECONDS))
            job.cancel(CancellationException("user cancelled provider stream"))
            job.join()
            assertTrue(failure.get() is CancellationException)
            assertEquals(1, harness.calls.size)
            assertTrue(chunks.isEmpty())
            assertTrue(harness.calls.single().isCanceled)
            assertTrue(harness.calls.single().body.closed.get())
        } finally {
            job.cancel()
            harness.calls.forEach { it.cancel() }
            job.join()
        }
    }

    @Test(timeout = 10_000)
    fun cancellationExceptionFromReaderIsNeverConvertedToInterruptedOrTimeout() = runBlocking(Dispatchers.IO) {
        val cancellation = CancellationException("reader cancelled")
        val harness = Harness { ControlledBody("", endFailure = cancellation) }
        harness.provider.maxNetworkRetries = 2
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectFailure(harness.provider, chunks)
        assertTrue(error is CancellationException)
        assertEquals(cancellation.message, error.message)
        assertTrue(chunks.isEmpty())
        assertEquals(1, harness.calls.size)
        assertTrue(harness.calls.single().body.closed.get())
    }

    @Test(timeout = 10_000)
    fun slowConsumerDoesNotCountAsNetworkIdle() = runBlocking(Dispatchers.IO) {
        val harness = Harness {
            ControlledBody(sse(protocol.textLine("hello"), protocol.textLine("world"), protocol.terminalLine()))
        }
        harness.provider.firstByteTimeoutMs = 5_000
        harness.provider.streamIdleTimeoutMs = 100
        val chunks = mutableListOf<AIStreamChunk>()
        harness.provider.completeStream("", emptyList()).buffer(0).collect { chunk ->
            chunks += chunk
            if (chunk is AIStreamChunk.TextDelta) delay(250)
        }
        assertEquals(listOf("hello", "world"), chunks.filterIsInstance<AIStreamChunk.TextDelta>().map { it.text })
        assertEquals("helloworld", chunks.filterIsInstance<AIStreamChunk.Final>().single().response.content)
        assertTrue(chunks.none { it is AIStreamChunk.Retrying })
        assertEquals(1, harness.calls.size)
        assertFalse(harness.calls.single().isCanceled)
        assertTrue(harness.calls.single().body.closed.get())
    }

    private suspend fun collectFailure(
        provider: AIProvider,
        chunks: MutableList<AIStreamChunk>,
        onChunk: (AIStreamChunk) -> Unit = {}
    ): Throwable = try {
        provider.completeStream("", emptyList()).buffer(0).collect {
            chunks += it
            onChunk(it)
        }
        throw AssertionError("Expected stream failure for $protocol")
    } catch (e: Exception) {
        e
    }

    private inner class Harness(
        private val deferHeaders: Boolean = false,
        private val newBody: (Int) -> ControlledBody
    ) {
        val calls = CopyOnWriteArrayList<ControlledCall>()
        val requestStarted = CountDownLatch(1)
        val provider: AIProvider = when (protocol) {
            StreamProtocol.OPENAI_CHAT, StreamProtocol.OPENAI_RESPONSES -> {
                val api = mockk<OpenAIApi>()
                if (protocol == StreamProtocol.OPENAI_CHAT) {
                    every { api.streamChatCompletion(any(), any(), any(), any()) } answers { newCall() }
                } else {
                    every { api.streamResponses(any(), any(), any(), any()) } answers { newCall() }
                }
                OpenAIAdapter(api).apply {
                    model = "gpt-4-turbo"
                    useResponseApi = protocol == StreamProtocol.OPENAI_RESPONSES
                }
            }
            StreamProtocol.ANTHROPIC -> {
                val api = mockk<AnthropicApi>()
                every { api.streamMessage(any(), any(), any(), any(), any()) } answers { newCall() }
                AnthropicAdapter(api).apply { model = "claude-sonnet-test" }
            }
            StreamProtocol.GEMINI_GENERATE, StreamProtocol.GEMINI_INTERACTIONS -> {
                val api = mockk<GeminiApi>()
                if (protocol == StreamProtocol.GEMINI_GENERATE) {
                    every { api.streamGenerateContent(any(), any(), any(), any()) } answers { newCall() }
                } else {
                    every { api.streamInteraction(any(), any(), any(), any()) } answers { newCall() }
                }
                GeminiAdapter(api).apply {
                    model = "gemini-2.5-flash"
                    useResponseApi = protocol == StreamProtocol.GEMINI_INTERACTIONS
                }
            }
        }.apply {
            apiKey = "test-key"
            baseUrl = "http://127.0.0.1/"
            firstByteTimeoutMs = 0
            streamIdleTimeoutMs = 0
            maxNetworkRetries = 0
        }

        private fun newCall(): ControlledCall =
            ControlledCall(newBody(calls.size), deferHeaders).also {
                calls += it
                requestStarted.countDown()
            }
    }

    private class ControlledBody(
        prefix: String,
        private val blockAtEnd: Boolean = false,
        private val endFailure: Throwable? = null,
        private val beforeEof: CountDownLatch? = null
    ) : ResponseBody() {
        val closed = AtomicBoolean(false)
        val blockedReadStarted = CountDownLatch(1)
        private val releaseRead = CountDownLatch(1)
        private val bytes = Buffer().writeUtf8(prefix)
        private val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (closed.get()) throw IOException("body closed")
                if (bytes.size > 0) return bytes.read(sink, byteCount)
                check(beforeEof?.await(2, TimeUnit.SECONDS) != false) { "Content was not consumed before fixture EOF" }
                if (blockAtEnd) {
                    blockedReadStarted.countDown()
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw SocketTimeoutException("Test fixture was not cancelled or closed within five seconds")
                    }
                    throw IOException("read released by cancellation or body close")
                }
                endFailure?.let { throw it }
                return -1
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed.set(true)
                releaseRead.countDown()
            }
        }.buffer()

        fun cancelRead() {
            releaseRead.countDown()
        }

        override fun contentType(): MediaType = "text/event-stream; charset=utf-8".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    private class ControlledCall(
        val body: ControlledBody,
        private val deferHeaders: Boolean
    ) : Call<ResponseBody> {
        @Volatile private var executed = false
        @Volatile private var cancelled = false
        private val callback = AtomicReference<Callback<ResponseBody>?>(null)

        override fun enqueue(callback: Callback<ResponseBody>) {
            check(!executed)
            executed = true
            if (deferHeaders) {
                this.callback.set(callback)
                if (cancelled) failPendingCallback()
            } else {
                callback.onResponse(this, Response.success(body))
            }
        }

        override fun execute(): Response<ResponseBody> = throw UnsupportedOperationException("Async stream test only")
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call<ResponseBody> = throw UnsupportedOperationException("No replay through Call.clone")

        override fun cancel() {
            cancelled = true
            body.cancelRead()
            failPendingCallback()
        }

        private fun failPendingCallback() {
            callback.getAndSet(null)?.onFailure(this, IOException("call cancelled before headers"))
        }
    }

    private fun sse(vararg lines: String): String = lines.joinToString("\n\n", postfix = "\n\n")

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun protocols(): List<Array<Any>> = StreamProtocol.entries.map { arrayOf<Any>(it) }
    }
}
