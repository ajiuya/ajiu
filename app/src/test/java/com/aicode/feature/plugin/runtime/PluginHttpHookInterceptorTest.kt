package com.aicode.feature.plugin.runtime

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class PluginHttpHookInterceptorTest {
    private val dispatcher = mockk<PluginHookDispatcher>()
    private val interceptor = PluginHttpHookInterceptor(Provider { dispatcher })
    private class Body(private val text: String = "{\"model\":\"original\"}", private val oneShot: Boolean = false,
        private val duplex: Boolean = false, private val length: Long = -1, private val media: String = "application/json") : RequestBody() {
        val writes = AtomicInteger()
        override fun contentType() = media.toMediaType()
        override fun contentLength() = length
        override fun isOneShot() = oneShot
        override fun isDuplex() = duplex
        override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); sink.writeUtf8(text) }
    }
    private fun request(body: RequestBody = Body(), url: String = "https://example.com/v1/chat/completions") = Request.Builder()
        .url(url).header("Authorization", "Bearer private-key").header("Cookie", "session=private-cookie")
        .header("Proxy-Authorization", "Basic private-proxy").post(body).build()
    private class ControlledCall(private val original: Request) : Call {
        private val cancelled = AtomicBoolean()
        private val callTimeout = Timeout()
        override fun request(): Request = original
        override fun execute(): Response = throw AssertionError("Interceptor fixture must not execute a network call")
        override fun enqueue(responseCallback: Callback): Unit = throw AssertionError("Interceptor fixture must not enqueue a network call")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = callTimeout
        override fun clone(): Call = ControlledCall(original)
    }
    private class ControlledChain(
        private val original: Request,
        private val controlledCall: Call,
        private val connectMillis: Int = 10_000,
        private val readMillis: Int = 10_000,
        private val writeMillis: Int = 10_000,
        private val onProceed: (Request) -> Unit
    ) : Interceptor.Chain {
        override fun request(): Request = original
        override fun call(): Call = controlledCall
        override fun connection(): Connection? = null
        override fun proceed(request: Request): Response {
            onProceed(request)
            return Response.Builder().request(request).code(200).message("ok").protocol(Protocol.HTTP_1_1)
                .body("{}".toResponseBody()).build()
        }
        override fun connectTimeoutMillis(): Int = connectMillis
        override fun readTimeoutMillis(): Int = readMillis
        override fun writeTimeoutMillis(): Int = writeMillis
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ControlledChain(original, controlledCall, unit.toMillis(timeout.toLong()).toInt(), readMillis, writeMillis, onProceed)
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ControlledChain(original, controlledCall, connectMillis, unit.toMillis(timeout.toLong()).toInt(), writeMillis, onProceed)
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ControlledChain(original, controlledCall, connectMillis, readMillis, unit.toMillis(timeout.toLong()).toInt(), onProceed)
    }
    private fun chain(request: Request, onProceed: (Request) -> Unit): Interceptor.Chain =
        ControlledChain(request, ControlledCall(request), onProceed = onProceed)
    @Test fun retrofitTaggedProviderCustomRouteAppliesHttpHooks() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } returns HttpRequestHookResult(body = "{\"model\":\"custom-route-model\"}")
        val body = Body()
        val customUrl = "https://example.com/custom/provider-route"
        val method = com.aicode.feature.agent.data.remote.openai.OpenAIApi::class.java.declaredMethods.first { it.name == "streamChatCompletion" }
        val original = request(body, customUrl).newBuilder()
            .tag(retrofit2.Invocation::class.java, retrofit2.Invocation.of(method, emptyList<Any>())).build()
        var sent: Request? = null
        interceptor.intercept(chain(original) { sent = it }).close()
        assertEquals(1, body.writes.get())
        assertEquals(original.url, requireNotNull(sent).url)
        assertNotSame(original, sent)
        val buffer = okio.Buffer()
        sent!!.body!!.writeTo(buffer)
        assertEquals("{\"model\":\"custom-route-model\"}", buffer.readUtf8())
        io.mockk.coVerify(exactly = 1) { dispatcher.beforeHttpRequest(customUrl, "POST", any(), any()) }
    }
    @Test fun ordinaryMcpJsonRpcCustomRouteDoesNotReadBodyOrInvokeHttpHooks() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        val body = Body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
        val original = request(body, "https://example.com/custom/mcp-route")
        var sent: Request? = null
        interceptor.intercept(chain(original) { sent = it }).close()
        assertSame(original, sent)
        assertEquals(0, body.writes.get())
        io.mockk.verify(exactly = 0) { dispatcher.hasHttpHooks() }
        io.mockk.coVerify(exactly = 0) { dispatcher.beforeHttpRequest(any(), any(), any(), any()) }
    }
    @Test fun noHookFastPathDoesNotReadProviderBodyOrRebuildTheRequest() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns false
        val body = Body()
        val original = request(body)
        var sent: Request? = null
        interceptor.intercept(chain(original) { sent = it }).close()
        assertSame(original, sent)
        assertEquals(0, body.writes.get())
    }
    @Test fun streamingOneShotNonProviderAndNonJsonRequestsAreNotRead() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        for ((body, url) in listOf(Body(oneShot = true) to "https://example.com/v1/messages",
            Body(duplex = true) to "https://example.com/v1/messages", Body() to "https://example.com/mcp",
            Body(media = "text/plain") to "https://example.com/v1/responses")) {
            interceptor.intercept(chain(request(body, url)) {}).close()
            assertEquals(0, body.writes.get())
        }
    }
    @Test fun oversizedAndMalformedBodiesNeverInvokeHooksAndRemainOriginal() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } throws AssertionError("must not invoke")
        for (body in listOf(Body(length = PluginHookTransform.MAX_HTTP_BODY_BYTES + 1L), Body("[1,2]"), Body("{broken"),
            Body("{\"text\":\"${"x".repeat(PluginHookTransform.MAX_HTTP_BODY_BYTES)}\"}"))) {
            val original = request(body)
            var sent: Request? = null
            interceptor.intercept(chain(original) { sent = it }).close()
            assertSame(original, sent)
        }
    }
    @Test fun transformedRequestsUseFinalBodyAndStripCrossOriginCredentials() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } returns HttpRequestHookResult(
            "https://other.example/v1/chat/completions", mapOf("X-New" to "allowed"), "{\"model\":\"new\"}")
        var sent: Request? = null
        interceptor.intercept(chain(request()) { sent = it }).close()
        val result = requireNotNull(sent)
        assertEquals("other.example", result.url.host)
        assertNull(result.header("Authorization"))
        assertNull(result.header("Cookie"))
        assertNull(result.header("Proxy-Authorization"))
        assertEquals("allowed", result.header("X-New"))
        val buffer = okio.Buffer()
        result.body!!.writeTo(buffer)
        assertEquals("{\"model\":\"new\"}", buffer.readUtf8())
    }
    @Test fun staleCapabilityOrInvalidTargetNeverAppliesAChange() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        for (transform in listOf(HttpRequestHookResult(url = "https://evil.example/") { throw IllegalStateException("revoked") },
            HttpRequestHookResult(url = "https://user:secret@evil.example/v1"), HttpRequestHookResult(url = "file:///secret"))) {
            coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } returns transform
            val original = request()
            var sent: Request? = null
            interceptor.intercept(chain(original) { sent = it }).close()
            assertSame(original, sent)
        }
    }
    @Test fun downstreamIoFailureIsNeverReplayedAsASecondRequest() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } returns HttpRequestHookResult(body = "{\"new\":true}")
        val calls = AtomicInteger()
        assertThrows(IOException::class.java) { interceptor.intercept(chain(request()) { calls.incrementAndGet(); throw IOException("network") }) }
        assertEquals(1, calls.get())
    }
    @Test fun callCancellationCancelsAnInFlightHookAndDoesNotProceed() = runBlocking<Unit> {
        every { dispatcher.hasHttpHooks() } returns true
        val original = request()
        val call = ControlledCall(original)
        val started = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val proceeded = AtomicInteger()
        val chain = ControlledChain(original, call) { proceeded.incrementAndGet(); error("must not proceed") }
        coEvery { dispatcher.beforeHttpRequest(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            call.cancel()
            try { awaitCancellation() } finally { completed.complete(Unit) }
        }
        val failure = assertThrows(IOException::class.java) { interceptor.intercept(chain) }
        assertEquals("Canceled", failure.message)
        assertTrue(started.isCompleted)
        assertTrue(call.isCanceled())
        assertTrue(completed.isCompleted)
        assertEquals(0, proceeded.get())
    }
}
