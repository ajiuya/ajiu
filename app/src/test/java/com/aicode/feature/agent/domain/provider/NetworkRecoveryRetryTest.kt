package com.aicode.feature.agent.domain.provider

import com.aicode.core.net.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import javax.net.ssl.SSLException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class NetworkRecoveryRetryTest(private val entryPoint: EntryPoint) {
    enum class EntryPoint { REQUEST, STREAM }

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

    @Test
    fun repeatedOnlineNotificationsDoNotSkipBackoff() = runTest {
        val version = NetworkStatus.states.value.recoveryVersion
        val attempts = mutableListOf<Long>()
        val job = launch {
            retry {
                attempts += testScheduler.currentTime
                if (attempts.size == 1) throw IOException("connection lost")
            }
        }
        runCurrent()
        advanceTimeBy(100)
        repeat(3) { NetworkStatus.update(true) }
        runCurrent()
        assertEquals(version, NetworkStatus.states.value.recoveryVersion)
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(399)
        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(listOf(0L, 500L), attempts)
    }

    @Test
    fun recoveryDuringRequestIsRememberedWhenThatRequestFails() = runTest {
        val attempts = mutableListOf<Long>()
        val version = NetworkStatus.states.value.recoveryVersion
        retry {
            attempts += testScheduler.currentTime
            if (attempts.size == 1) {
                recoverNetwork()
                throw IOException("old connection failed after recovery")
            }
        }
        assertEquals(version + 1, NetworkStatus.states.value.recoveryVersion)
        assertEquals(listOf(0L, 0L), attempts)
    }

    @Test
    fun recoveryDuringSuspendingOnRetryIsNotLost() = runTest {
        val attempts = mutableListOf<Long>()
        val retryKinds = mutableListOf<RetryErrorKind>()
        retry(onRetry = { _, _, error ->
            retryKinds += error.kind
            NetworkStatus.update(false)
            delay(75)
            NetworkStatus.update(true)
        }) {
            attempts += testScheduler.currentTime
            if (attempts.size == 1) throw IOException("connection lost")
        }
        assertEquals(listOf(0L, 75L), attempts)
        assertEquals(listOf(RetryErrorKind.NETWORK), retryKinds)
    }

    @Test
    fun recoveryDuringBackoffWakesImmediately() = runTest {
        val attempts = mutableListOf<Long>()
        val job = launch {
            retry {
                attempts += testScheduler.currentTime
                if (attempts.size == 1) throw IOException("connection lost")
            }
        }
        runCurrent()
        advanceTimeBy(200)
        recoverNetwork()
        runCurrent()
        job.join()
        assertEquals(listOf(0L, 200L), attempts)
    }

    @Test
    fun offlineFailureWaitsForRecoveryWithoutAddingBackoff() = runTest {
        NetworkStatus.update(false)
        val attempts = mutableListOf<Long>()
        val job = launch {
            retry {
                attempts += testScheduler.currentTime
                if (attempts.size == 1) throw IOException("offline")
            }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L), attempts)
        NetworkStatus.update(true)
        runCurrent()
        job.join()
        assertEquals(listOf(0L, 1_000L), attempts)
    }

    @Test
    fun offlineWaitIsBoundedToThirtySecondsThenUsesNormalBackoff() = runTest {
        NetworkStatus.update(false)
        val attempts = mutableListOf<Long>()
        val job = launch {
            retry {
                attempts += testScheduler.currentTime
                if (attempts.size == 1) throw IOException("offline")
            }
        }
        runCurrent()
        advanceTimeBy(30_499)
        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(listOf(0L, 30_500L), attempts)
        assertFalse(NetworkStatus.isOnline)
    }

    @Test
    fun oneRecoveryCannotSkipTheNextAttemptsBackoff() = runTest {
        val attempts = mutableListOf<Long>()
        retry(maxRetries = 2, onRetry = { attempt, _, _ ->
            if (attempt == 1) recoverNetwork()
        }) {
            attempts += testScheduler.currentTime
            if (attempts.size <= 2) throw IOException("connection lost")
        }
        assertEquals(listOf(0L, 0L, 1_000L), attempts)
    }

    @Test
    fun http5xxDoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(httpError(503), 500)
    }

    @Test
    fun http408DoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(httpError(408), 500)
    }

    @Test
    fun http429DoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(httpError(429), 500)
    }

    @Test
    fun retryAfter429MustBeRespectedAcrossRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(httpError(429, "2"), 2_000)
    }

    @Test
    fun retryAfter503MustBeRespectedAcrossRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(httpError(503, "2"), 2_000)
    }

    @Test
    fun streamApiServerErrorDoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(StreamApiException("server_error", "overloaded"), 500)
    }

    @Test
    fun streamApiRateLimitDoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(StreamApiException("rate_limit_exceeded", "slow down"), 500)
    }

    @Test
    fun sslFailureDoesNotWakeOnNetworkRecovery() = runTest {
        assertRecoveryDoesNotShortenDelay(SSLException("handshake failed"), 500)
    }

    @Test
    fun retryDisabledStillThrowsOriginalFailureAfterRecovery() = runTest {
        val failure = IOException("connection lost")
        var attempts = 0
        var notifications = 0
        val actual = try {
            retry(maxRetries = 0, onRetry = { _, _, _ -> notifications++ }) {
                attempts++
                recoverNetwork()
                throw failure
            }
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            e
        }
        assertSame(failure, actual)
        assertEquals(1, attempts)
        assertEquals(0, notifications)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun repeatedRecoveryDoesNotResetRetryLimit() = runTest {
        val failure = IOException("still disconnected")
        var attempts = 0
        val notifications = mutableListOf<Pair<Int, Int>>()
        val actual = try {
            retry(maxRetries = 2, onRetry = { attempt, max, _ ->
                notifications += attempt to max
                recoverNetwork()
            }) {
                attempts++
                throw failure
            }
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            e
        }
        assertSame(failure, actual)
        assertEquals(3, attempts)
        assertEquals(listOf(1 to 2, 2 to 2), notifications)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun recoveryDoesNotMakePermanentFailureRetriable() = runTest {
        val failure = IllegalArgumentException("invalid request")
        var attempts = 0
        val actual = try {
            retry {
                attempts++
                recoverNetwork()
                throw failure
            }
            throw AssertionError("Expected permanent failure")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertSame(failure, actual)
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun cancellationDuringOfflineWaitDoesNotRetryAfterLaterRecovery() = runTest {
        NetworkStatus.update(false)
        assertCancellationStopsRetries()
    }

    @Test
    fun cancellationDuringBackoffDoesNotRetryAfterLaterRecovery() = runTest {
        assertCancellationStopsRetries()
    }

    @Test
    fun cancellationFromOnRetryIsNotRetriedOrConverted() = runTest {
        val cancellation = CancellationException("user cancelled in retry notification")
        var attempts = 0
        val actual = try {
            retry(onRetry = { _, _, _ ->
                recoverNetwork()
                throw cancellation
            }) {
                attempts++
                throw IOException("connection lost")
            }
            throw AssertionError("Expected cancellation")
        } catch (e: CancellationException) {
            e
        }
        assertSame(cancellation, actual)
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun recoveryDoesNotCancelOrReplayAnInFlightRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var finished = false
        var cancelled = false
        val job = launch {
            retry { onContent ->
                attempts++
                onContent()
                try {
                    release.await()
                    finished = true
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
        }
        runCurrent()
        repeat(3) { recoverNetwork() }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(finished)
        assertFalse(cancelled)
        assertTrue(job.isActive)
        release.complete(Unit)
        job.join()
        assertTrue(finished)
        assertFalse(cancelled)
        assertEquals(1, attempts)
    }

    @Test
    fun userCancellationDuringRequestWinsOverRecovery() = runTest {
        var attempts = 0
        var cancellationObserved = false
        var retries = 0
        val job = launch {
            try {
                retry(onRetry = { _, _, _ -> retries++ }) {
                    attempts++
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        }
        runCurrent()
        recoverNetwork()
        job.cancel(CancellationException("user cancelled"))
        job.join()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(cancellationObserved)
        assertEquals(1, attempts)
        assertEquals(0, retries)
    }

    private suspend fun TestScope.assertRecoveryDoesNotShortenDelay(error: Throwable, expectedWait: Long) {
        val startedAt = testScheduler.currentTime
        val attempts = mutableListOf<Long>()
        val job = launch {
            retry {
                attempts += testScheduler.currentTime - startedAt
                if (attempts.size == 1) throw error
            }
        }
        runCurrent()
        advanceTimeBy(100)
        recoverNetwork()
        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(expectedWait - 101)
        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(listOf(0L, expectedWait), attempts)
    }

    private suspend fun TestScope.assertCancellationStopsRetries() {
        var attempts = 0
        var cancellationObserved = false
        val job = launch {
            try {
                retry {
                    attempts++
                    throw IOException("connection lost")
                }
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        }
        runCurrent()
        advanceTimeBy(100)
        job.cancel(CancellationException("user cancelled"))
        job.join()
        recoverNetwork()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(cancellationObserved)
        assertEquals(1, attempts)
    }

    private suspend fun retry(
        maxRetries: Int = 1,
        onRetry: (suspend (Int, Int, RetryErrorInfo) -> Unit)? = null,
        block: suspend (() -> Unit) -> Unit
    ) {
        when (entryPoint) {
            EntryPoint.REQUEST -> retryStaircase(maxRetries = maxRetries, onRetry = onRetry) { block {} }
            EntryPoint.STREAM -> streamWithStaircaseRetry(
                maxRetries = maxRetries,
                attemptOnce = block,
                onRetry = onRetry
            )
        }
    }

    private fun recoverNetwork() {
        NetworkStatus.update(false)
        NetworkStatus.update(true)
    }

    private fun httpError(code: Int, retryAfter: String? = null): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test response")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        return HttpException(Response.error<Any>("{}".toResponseBody(null), raw))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entryPoints(): List<Array<Any>> = EntryPoint.entries.map { arrayOf<Any>(it) }
    }
}
