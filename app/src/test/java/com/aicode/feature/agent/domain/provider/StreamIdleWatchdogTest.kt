package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class StreamIdleWatchdogTest {
    @Test
    fun constructorStartsTimeoutBeforeAnyDataOrTouch() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            runCurrent()
            advanceTimeBy(99)
            runCurrent()
            assertEquals(0, closes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, closes)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun heartbeatsReplaceOldTimerAndOnlyLatestDeadlineCanExpire() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            runCurrent()
            repeat(4) {
                advanceTimeBy(75)
                watchdog.touch()
                runCurrent()
                assertEquals(0, closes)
            }
            advanceTimeBy(99)
            runCurrent()
            assertEquals(0, closes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun touchAtOldDeadlineBeforeTimerRunsDoesNotLetStaleTimerClose() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            runCurrent()
            advanceTimeBy(100)
            watchdog.touch()
            runCurrent()
            assertEquals(0, closes)
            advanceTimeBy(99)
            runCurrent()
            assertEquals(0, closes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun cancelIsPermanentEvenIfTouchedOrReadAgain() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        runCurrent()
        advanceTimeBy(50)
        watchdog.cancel()
        watchdog.cancel()
        watchdog.touch()
        assertEquals("after cancel", watchdog.read { "after cancel" })
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, closes)
    }

    @Test
    fun zeroTimeoutDisablesAutomaticClose() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 0) { closes++ }
        try {
            watchdog.touch()
            assertEquals("ok", watchdog.read { "ok" })
            advanceTimeBy(1_000_000)
            runCurrent()
            assertEquals(0, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun expiredWatchdogCannotBeResurrectedByTouch() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            watchdog.touch()
            val error = expectIdleTimeout { watchdog.read { "late data" } }
            assertEquals(100L, error.timeoutMs)
            assertEquals(RetryErrorKind.STREAM_IDLE_TIMEOUT, error.toRetryErrorInfo().kind)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun successfulReadStopsClockThroughoutParsingAndConsumerDelay() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            runCurrent()
            advanceTimeBy(20)
            assertEquals("payload", watchdog.read { "payload" })
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, closes)
            assertEquals("next payload", watchdog.read {
                runCurrent()
                advanceTimeBy(99)
                runCurrent()
                assertEquals(0, closes)
                "next payload"
            })
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun nextReadStartsANewTimeoutAfterLongParsingPause() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            assertEquals("first", watchdog.read { "first" })
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, closes)
            val error = expectIdleTimeout {
                watchdog.read {
                    runCurrent()
                    advanceTimeBy(100)
                    runCurrent()
                    null
                }
            }
            assertEquals(100L, error.timeoutMs)
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun failedReadPreservesOriginalErrorAndStopsTimer() = runTest {
        var closes = 0
        val failure = IOException("peer reset before deadline")
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            val actual = try {
                watchdog.read { throw failure }
            } catch (e: IOException) {
                e
            }
            assertSame(failure, actual)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun expiredReadMapsCloseInducedIOExceptionToIdleTimeout() = runTest {
        var closes = 0
        val watchdog = StreamIdleWatchdog(this, 100) { closes++ }
        try {
            val error = expectIdleTimeout {
                watchdog.read {
                    runCurrent()
                    advanceTimeBy(100)
                    runCurrent()
                    throw IOException("socket closed by watchdog")
                }
            }
            assertEquals(100L, error.timeoutMs)
            assertEquals(RetryErrorKind.STREAM_IDLE_TIMEOUT, error.toRetryErrorInfo().kind)
            assertTrue(isRetriableNetworkError(error))
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun cancelledScopeWinsOverAlreadyExpiredWatchdog() = runTest {
        val parent = Job()
        val scope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        var closes = 0
        val watchdog = StreamIdleWatchdog(scope, 100) { closes++ }
        try {
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            parent.cancel(CancellationException("user cancelled"))
            var readCalled = false
            val error = try {
                watchdog.read { readCalled = true }
                throw AssertionError("Expected cancellation")
            } catch (e: CancellationException) {
                e
            }
            assertEquals("user cancelled", error.message)
            assertFalse(readCalled)
            assertEquals(1, closes)
        } finally {
            watchdog.cancel()
            parent.cancel()
        }
    }

    @Test
    fun cancellationDuringReadWinsOverCloseInducedIOException() = runTest {
        val parent = Job()
        val scope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val watchdog = StreamIdleWatchdog(scope, 100) {}
        try {
            val error = try {
                watchdog.read {
                    parent.cancel(CancellationException("user cancelled during read"))
                    throw IOException("socket closed")
                }
            } catch (e: CancellationException) {
                e
            }
            assertTrue(error is CancellationException)
            assertEquals("user cancelled during read", (error as CancellationException).message)
        } finally {
            watchdog.cancel()
            parent.cancel()
        }
    }

    @Test
    fun throwingCloseCallbackDoesNotHideIdleTimeout() = runTest {
        val watchdog = StreamIdleWatchdog(this, 100) { throw IOException("close failed") }
        try {
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(100L, expectIdleTimeout { watchdog.read { null } }.timeoutMs)
        } finally {
            watchdog.cancel()
        }
    }

    @Test
    fun timeoutAndEofExceptionsHaveDistinctRetryCategories() {
        val first = FirstContentTimeoutException(123)
        val idle = StreamIdleTimeoutException(456)
        val eof = UnexpectedStreamEndException("missing terminal event")
        assertEquals(123L, first.timeoutMs)
        assertEquals(456L, idle.timeoutMs)
        assertEquals(RetryErrorKind.FIRST_CONTENT_TIMEOUT, first.toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.STREAM_IDLE_TIMEOUT, idle.toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.STREAM_INTERRUPTED, eof.toRetryErrorInfo().kind)
        listOf(first, idle, eof).forEach { assertTrue(isRetriableNetworkError(it)) }
    }

    @Test(timeout = 10_000)
    fun realHttp1FirstContentTimeoutCancelsCallBeforeClosingBlockedBody() = runBlocking(Dispatchers.IO) {
        withStalledHttp1Response { call ->
            val enteredRead = AtomicBoolean(false)
            val bodyClosed = AtomicBoolean(false)
            val cancelledBeforeClose = AtomicBoolean(false)
            val error = try {
                withFirstContentTimeout(1_000) { guard ->
                    val body = guard.awaitBody(call)
                    body.use {
                        guard.attach {
                            cancelledBeforeClose.set(call.isCanceled)
                            body.close()
                            bodyClosed.set(true)
                        }
                        enteredRead.set(true)
                        body.charStream().buffered().readLine()
                    }
                }
                throw AssertionError("Expected first-content timeout")
            } catch (e: FirstContentTimeoutException) {
                e
            }
            assertTrue("Response headers must arrive before blocking body read", enteredRead.get())
            assertTrue(call.isCanceled)
            assertTrue(bodyClosed.get())
            assertTrue("Call.cancel must precede ResponseBody.close", cancelledBeforeClose.get())
            assertEquals(1_000L, error.timeoutMs)
            assertEquals(RetryErrorKind.FIRST_CONTENT_TIMEOUT, error.toRetryErrorInfo().kind)
        }
    }

    @Test(timeout = 10_000)
    fun realHttp1IdleWatchdogUnblocksReadAndPreservesIdleClassification() = runBlocking(Dispatchers.IO) {
        withStalledHttp1Response { call ->
            val enteredRead = AtomicBoolean(false)
            val bodyClosed = AtomicBoolean(false)
            val cancelledBeforeClose = AtomicBoolean(false)
            val error = try {
                withFirstContentTimeout(0) { guard ->
                    val body = guard.awaitBody(call)
                    body.use {
                        guard.attach {
                            cancelledBeforeClose.set(call.isCanceled)
                            body.close()
                            bodyClosed.set(true)
                        }
                        guard.receivedContent()
                        val watchdog = launchStreamIdleWatchdog(100) { guard.closeBody() }
                        try {
                            watchdog.read {
                                enteredRead.set(true)
                                body.charStream().buffered().readLine()
                            }
                        } finally {
                            watchdog.cancel()
                        }
                    }
                }
                throw AssertionError("Expected stream-idle timeout")
            } catch (e: StreamIdleTimeoutException) {
                e
            }
            assertTrue(enteredRead.get())
            assertTrue(call.isCanceled)
            assertTrue(bodyClosed.get())
            assertTrue("Call.cancel must precede ResponseBody.close", cancelledBeforeClose.get())
            assertEquals(100L, error.timeoutMs)
            assertEquals(RetryErrorKind.STREAM_IDLE_TIMEOUT, error.toRetryErrorInfo().kind)
        }
    }

    private fun expectIdleTimeout(block: () -> Unit): StreamIdleTimeoutException = try {
        block()
        throw AssertionError("Expected StreamIdleTimeoutException")
    } catch (e: StreamIdleTimeoutException) {
        e
    }

    private suspend fun withStalledHttp1Response(block: suspend (Call<ResponseBody>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val release = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Transfer-Encoding: chunked\r\n\r\n").toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        serverFailure.set(AssertionError("Local HTTP/1 fixture safety timeout closed the stream"))
                    }
                }
            } catch (e: Throwable) {
                if (release.count != 0L) serverFailure.set(e)
            }
        }.apply { isDaemon = true; name = "watchdog-test-http1"; start() }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .protocols(listOf(Protocol.HTTP_1_1))
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        val url = "http://127.0.0.1:${server.localPort}/"
        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApi::class.java)
        val call = api.streamChatCompletion(
            url, "test-key", emptyMap(),
            ChatCompletionRequest(model = "test-model", messages = emptyList(), stream = true)
        )
        try {
            block(call)
            serverFailure.get()?.let { throw AssertionError("Local HTTP/1 fixture failed", it) }
        } finally {
            call.cancel()
            release.countDown()
            server.close()
            serverThread.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
