package com.aicode.feature.plugin.runtime

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PluginPreparationProcessTest {
    @Test fun aSuccessfulProcessClosesStdinAndReleasesItsResourceOnce() = runBlocking<Unit> {
        val process = DependencyFakeProcess()
        val releases = AtomicInteger()
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = { releases.incrementAndGet() })
        assertEquals(0, withTimeout(5_000) { preparation.run() })
        withTimeout(5_000) { preparation.awaitClosed() }
        preparation.close()
        assertTrue(process.inputClosed.get())
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
        assertEquals(1, releases.get())
    }

    @Test fun aNonzeroInstallerExitCodeIsPreserved() = runBlocking<Unit> {
        val preparation = PluginPreparationProcess({ DependencyFakeProcess(exitCode = 17) }, {}, onClosed = {})
        assertEquals(17, withTimeout(5_000) { preparation.run() })
        withTimeout(5_000) { preparation.awaitClosed() }
    }

    @Test fun aClosedPreparationNeverCallsTheProcessFactory() = runBlocking<Unit> {
        val starts = AtomicInteger()
        val preparation = PluginPreparationProcess({ starts.incrementAndGet(); DependencyFakeProcess() }, {}, onClosed = {})
        preparation.close()
        withTimeout(5_000) { preparation.awaitClosed() }
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, starts.get())
    }

    @Test fun stdoutLimitTerminatesALiveProcessAndUnblocksItsPeerStream() = runBlocking<Unit> {
        val blocked = DependencyBlockingInput()
        val process = DependencyFakeProcess(stdout = DependencySizedInput(PLUGIN_MAX_OUTPUT_BYTES + 1),
            stderr = blocked, initiallyAlive = true)
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = {})
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        withTimeout(5_000) { preparation.awaitClosed() }
        val error = result.exceptionOrNull()
        assertTrue(error.toString(), error is PluginProcessException)
        assertEquals("PLUGIN_OUTPUT_LIMIT", (error as PluginProcessException).code)
        assertEquals(0L, blocked.closed.count)
        assertFalse(process.isAlive)
    }

    @Test fun stderrLimitTerminatesALiveProcessAndUnblocksStdout() = runBlocking<Unit> {
        val blocked = DependencyBlockingInput()
        val process = DependencyFakeProcess(stdout = blocked,
            stderr = DependencySizedInput(PLUGIN_MAX_STDERR_BYTES + 1), initiallyAlive = true)
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = {})
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        withTimeout(5_000) { preparation.awaitClosed() }
        val error = result.exceptionOrNull()
        assertTrue(error.toString(), error is PluginProcessException)
        assertEquals("PLUGIN_OUTPUT_LIMIT", (error as PluginProcessException).code)
        assertEquals(0L, blocked.closed.count)
        assertFalse(process.isAlive)
    }

    @Test fun aZeroProgressStreamFailsInsteadOfSpinningUntilTimeout() = runBlocking<Unit> {
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        }
        val process = DependencyFakeProcess(stdout = stalled, initiallyAlive = true)
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = {})
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        withTimeout(5_000) { preparation.awaitClosed() }
        val error = result.exceptionOrNull()
        assertTrue(error.toString(), error is PluginProcessException)
        assertEquals("PLUGIN_STREAM_STALLED", (error as PluginProcessException).code)
        assertFalse(process.isAlive)
    }

    @Test fun aStreamReadFailureAlsoTerminatesTheProcess() = runBlocking<Unit> {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("broken stream")
        }
        val process = DependencyFakeProcess(stdout = broken, initiallyAlive = true)
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = {})
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        withTimeout(5_000) { preparation.awaitClosed() }
        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(process.isAlive)
    }

    @Test fun callerCancellationClosesTheProcessAndBothBlockedStreamsBeforeRelease() = runBlocking<Unit> {
        val stdout = DependencyBlockingInput()
        val stderr = DependencyBlockingInput()
        val process = DependencyFakeProcess(stdout = stdout, stderr = stderr, initiallyAlive = true)
        val releases = AtomicInteger()
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = { releases.incrementAndGet() })
        val running = async(Dispatchers.Default) { preparation.run() }
        assertTrue(withContext(Dispatchers.IO) { stdout.entered.await(5, TimeUnit.SECONDS) })
        running.cancelAndJoin()
        withTimeout(5_000) { preparation.awaitClosed() }
        assertFalse(process.isAlive)
        assertEquals(0L, stdout.closed.count)
        assertEquals(0L, stderr.closed.count)
        assertEquals(1, releases.get())
    }

    @Test fun revocationDuringBlockedWorkClosesTheOriginalOwnedProcess() = runBlocking<Unit> {
        val revoked = AtomicBoolean()
        val process = DependencyFakeProcess(stdout = DependencyBlockingInput(), initiallyAlive = true)
        val preparation = PluginPreparationProcess({ process }, {
            if (revoked.get()) throw PluginProcessException("PLUGIN_STALE", "revoked")
        }, onClosed = {})
        val result = async(Dispatchers.Default) { runCatching { preparation.run() } }
        assertTrue(withContext(Dispatchers.IO) { process.waiting.await(5, TimeUnit.SECONDS) })
        revoked.set(true)
        val failure = withTimeout(5_000) { result.await() }.exceptionOrNull()
        withTimeout(5_000) { preparation.awaitClosed() }
        assertTrue(failure.toString(), failure is PluginProcessException)
        assertEquals("PLUGIN_STALE", (failure as PluginProcessException).code)
        assertFalse(process.isAlive)
    }

    @Test fun closingDuringFactoryStartupWaitsForTheLateProcessToBeDisposed() = runBlocking<Unit> {
        val starting = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val releases = AtomicInteger()
        val process = DependencyFakeProcess(initiallyAlive = true)
        val preparation = PluginPreparationProcess({
            starting.countDown()
            check(allowStart.await(5, TimeUnit.SECONDS))
            process
        }, {}, onClosed = { releases.incrementAndGet() })
        val result = async(Dispatchers.Default) { runCatching { preparation.run() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { starting.await(5, TimeUnit.SECONDS) })
            preparation.close()
            assertEquals(1L, process.destroyed.count)
            assertEquals(0, releases.get())
        } finally { allowStart.countDown() }
        assertTrue(withTimeout(5_000) { result.await() }.isFailure)
        withTimeout(5_000) { preparation.awaitClosed() }
        assertFalse(process.isAlive)
        assertEquals(1, releases.get())
    }

    @Test fun aDestroyRequestDoesNotReleaseTheBudgetBeforeTheProcessActuallyDies() = runBlocking<Unit> {
        val stdout = DependencyBlockingInput()
        val stderr = DependencyBlockingInput()
        val destroyRequested = CountDownLatch(1)
        val dead = CountDownLatch(1)
        val releases = AtomicInteger()
        val process = object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = stdout
            override fun getErrorStream() = stderr
            override fun isAlive() = dead.count > 0
            override fun waitFor(): Int { check(dead.await(5, TimeUnit.SECONDS)); return 0 }
            override fun waitFor(timeout: Long, unit: TimeUnit) = dead.await(timeout, unit)
            override fun exitValue(): Int {
                if (isAlive()) throw IllegalThreadStateException("still alive")
                return 0
            }
            override fun destroy() { destroyRequested.countDown() }
            override fun destroyForcibly(): Process { destroy(); return this }
        }
        val preparation = PluginPreparationProcess({ process }, {}, onClosed = { releases.incrementAndGet() })
        val result = async(Dispatchers.Default) { runCatching { preparation.run() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { stdout.entered.await(5, TimeUnit.SECONDS) })
            preparation.close()
            assertTrue(withContext(Dispatchers.IO) { destroyRequested.await(5, TimeUnit.SECONDS) })
            assertTrue(process.isAlive)
            assertEquals(0, releases.get())
        } finally { dead.countDown() }
        assertTrue(withTimeout(5_000) { result.await() }.isFailure)
        withTimeout(5_000) { preparation.awaitClosed() }
        assertEquals(1, releases.get())
    }

    @Test fun failedAuthorityCheckBeforeStartupDoesNotCallTheFactory() = runBlocking<Unit> {
        val starts = AtomicInteger()
        val denied = PluginProcessException("PLUGIN_STALE", "revoked")
        val preparation = PluginPreparationProcess({ starts.incrementAndGet(); DependencyFakeProcess() }, { throw denied }, onClosed = {})
        val result = runCatching { withTimeout(5_000) { preparation.run() } }
        withTimeout(5_000) { preparation.awaitClosed() }
        assertSame(denied, result.exceptionOrNull())
        assertEquals(0, starts.get())
    }
}
