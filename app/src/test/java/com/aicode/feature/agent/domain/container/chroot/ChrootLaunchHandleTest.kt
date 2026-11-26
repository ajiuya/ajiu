package com.aicode.feature.agent.domain.container.chroot

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChrootLaunchHandleTest {
    private fun handle(
        input: (ByteArray) -> Unit = {},
        eof: () -> Unit = {},
        stop: () -> Unit = {}
    ) = ChrootLaunchHandle("instance", "launch", input, eof, { _, _ -> true }, { true }, stop)

    @Test
    fun inputIsSplitIntoBoundedFramesAndEofIsSentOnlyOnce() {
        val chunks = mutableListOf<ByteArray>()
        var eof = 0
        val handle = handle(chunks::add, { eof++ })
        val bytes = ByteArray(150_000) { (it % 127).toByte() }
        handle.outputStream.write(bytes)
        assertEquals(listOf(65_536, 65_536, 18_928), chunks.map { it.size })
        assertArrayEquals(bytes, chunks.fold(byteArrayOf()) { result, next -> result + next })
        handle.outputStream.close()
        handle.outputStream.close()
        assertEquals(1, eof)
        try {
            handle.outputStream.write(1)
            throw AssertionError("Writing after stdin EOF must fail")
        } catch (_: IOException) {
        }
    }

    @Test
    fun failedEofSubmissionDoesNotLatchClosedOrSuppressALaterClose() {
        var attempts = 0
        var sent = 0
        val handle = handle(eof = {
            attempts++
            if (attempts == 1) throw IOException("EOF submission unavailable")
            sent++
        })
        try {
            handle.outputStream.close()
            throw AssertionError("EOF submission failure must be reported")
        } catch (_: IOException) {
        }
        assertEquals(0, sent)
        handle.outputStream.close()
        handle.outputStream.close()
        assertEquals(2, attempts)
        assertEquals(1, sent)
        try {
            handle.outputStream.write(1)
            throw AssertionError("A successful EOF submission must close input")
        } catch (_: IOException) {
        }
    }

    @Test
    fun readinessWaitsForGoAndResolvesFalseWhenStoppedOrTerminatedBeforeGo() = runTest {
        val running = handle()
        val ready = async(start = CoroutineStart.UNDISPATCHED) { running.awaitReady() }
        assertFalse(ready.isCompleted)
        running.markReady()
        assertTrue(ready.await())
        running.stop()
        assertTrue(running.awaitReady())
        val stopped = handle()
        stopped.stop()
        stopped.markReady()
        assertFalse(stopped.awaitReady())
        val terminated = handle()
        terminated.completeTask(ChrootTaskResult.Unknown)
        assertFalse(terminated.awaitReady())
    }

    @Test
    fun realOutputStreamsRemainSeparatedAndWakeBlockedReadersAtTaskExit() = runTest {
        val handle = handle()
        val reader = CompletableFuture.supplyAsync<Int> { handle.inputStream.read() }
        assertFalse(reader.isDone)
        assertTrue(handle.appendOutput(false, byteArrayOf(65)))
        assertTrue(handle.appendOutput(true, byteArrayOf(66)))
        assertEquals(65, reader.get(5L, TimeUnit.SECONDS).toInt())
        assertEquals(66, handle.errorStream.read())
        val result = ChrootTaskResult.Exited(ChrootProcessExit(37, null))
        handle.completeTask(result)
        assertEquals(result, handle.awaitTaskExit())
        assertEquals(-1, handle.inputStream.read())
        assertEquals(-1, handle.errorStream.read())
        assertFalse(handle.isAlive)
        assertEquals(37, handle.waitFor())
        assertNull(handle.cleanupState.value)
    }

    @Test
    fun outputMemoryIsBoundedAndOverflowDoesNotReplaceExistingData() {
        val handle = handle()
        assertTrue(handle.appendOutput(false, ByteArray(ChrootLaunchHandle.MAX_BUFFER_BYTES) { 1 }))
        assertFalse(handle.appendOutput(false, byteArrayOf(2)))
        assertEquals(ChrootLaunchHandle.MAX_BUFFER_BYTES, handle.inputStream.available())
        assertEquals(1, handle.inputStream.read())
        assertTrue(handle.appendOutput(false, byteArrayOf(3)))
        handle.completeTask(ChrootTaskResult.Unknown)
        assertEquals(ChrootLaunchHandle.UNKNOWN_EXIT_STATUS, handle.exitValue())
    }

    @Test
    fun concurrentFailuresKeepOnlyTheFirstObservedValueAcrossTaskAndCleanupCompletion() {
        val handle = handle()
        val failures = listOf(
            ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR),
            ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=5".toByteArray())
        )
        val release = CountDownLatch(1)
        val records = failures.map { failure ->
            CompletableFuture.supplyAsync<Boolean> {
                check(release.await(5L, TimeUnit.SECONDS))
                handle.recordFailure(failure)
            }
        }
        release.countDown()
        assertEquals(1, records.count { it.get(5L, TimeUnit.SECONDS) })
        val first = handle.failure.value
        assertTrue(first in failures)
        handle.stop()
        handle.completeTask(ChrootTaskResult.Unknown)
        handle.completeCleanup(ChrootCleanupResult.UNKNOWN)
        failures.forEach { assertFalse(handle.recordFailure(it)) }
        assertEquals(first, handle.failure.value)
        assertEquals(ChrootCleanupResult.UNKNOWN, handle.cleanupState.value)
    }

    @Test
    fun diagnosticObservationDoesNotChangeReadinessTaskOrCleanup() = runTest {
        val handle = handle()
        val failure = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.STARTUP, ChrootRuntimeFailureStage.HELPER_CAPTURE)
        assertNull(handle.failure.value)
        assertTrue(handle.recordFailure(failure))
        assertNull(handle.taskState.value)
        assertNull(handle.cleanupState.value)
        handle.completeTask(ChrootTaskResult.Unknown)
        handle.completeCleanup(ChrootCleanupResult.VERIFIED)
        assertFalse(handle.awaitReady())
        assertEquals(ChrootCleanupResult.VERIFIED, handle.awaitCleanup())
        assertEquals(failure, handle.failure.value)
        val stderrFirst = this@ChrootLaunchHandleTest.handle()
        val stderr = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        assertTrue(stderrFirst.recordFailure(stderr))
        assertFalse(stderrFirst.recordFailure(ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=5".toByteArray())))
        assertEquals(stderr, stderrFirst.failure.value)
        assertFalse(stderrFirst.failure.value!!.nativeFailureObserved)
    }

    @Test
    fun nativeEvidenceIsIndependentOfTheFirstFailureAndCannotChangeUnknownCleanup() = runTest {
        val handle = handle()
        val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        val invalid = ChrootRuntimeFailure.nativeError("private_credential_marker".toByteArray())
        assertTrue(handle.recordFailure(first))
        assertFalse(handle.recordNativeFailure(first))
        assertFalse(handle.recordNativeFailure(invalid))
        assertNull(handle.nativeFailure.value)
        handle.stop()
        handle.completeTask(ChrootTaskResult.Unknown)
        handle.completeCleanup(ChrootCleanupResult.UNKNOWN)
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=app_control_authentication;errno=13".toByteArray())
        assertTrue(handle.recordNativeFailure(native))
        assertEquals(native, handle.nativeFailure.value)
        assertEquals(first, handle.failure.value)
        assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
        assertFalse(handle.awaitReady())
        assertFalse(handle.nativeFailure.value.toString().contains("private_credential_marker"))
    }

    @Test
    fun concurrentNativeEvidenceKeepsOnlyTheFirstValidCopyWithoutReplacingTheFirstFailure() {
        val handle = handle()
        val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        handle.recordFailure(first)
        val candidates = listOf(
            ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=5".toByteArray()),
            ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=app_control_authentication;errno=13".toByteArray())
        )
        val release = CountDownLatch(1)
        val records = candidates.map { failure ->
            CompletableFuture.supplyAsync<Boolean> {
                check(release.await(5L, TimeUnit.SECONDS))
                handle.recordNativeFailure(failure)
            }
        }
        release.countDown()
        assertEquals(1, records.count { it.get(5L, TimeUnit.SECONDS) })
        assertTrue(handle.nativeFailure.value in candidates)
        assertEquals(first, handle.failure.value)
        candidates.forEach { assertFalse(handle.recordNativeFailure(it)) }
        assertNull(handle.taskState.value)
        assertNull(handle.cleanupState.value)
    }

    @Test
    fun stopAndDestroyNeverPretendCleanupIsVerified() = runTest {
        var stop = 0
        val handle = handle(stop = { stop++ })
        handle.destroy()
        handle.destroyForcibly()
        handle.stop()
        assertEquals(1, stop)
        assertFalse(handle.resize(80, 24))
        assertFalse(handle.signal(2))
        assertNull(handle.taskState.value)
        assertNull(handle.cleanupState.value)
        handle.completeTask(ChrootTaskResult.Exited(ChrootProcessExit(0, null)))
        handle.completeCleanup(ChrootCleanupResult.UNKNOWN)
        handle.completeCleanup(ChrootCleanupResult.VERIFIED)
        assertEquals(0, handle.exitValue())
        assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
    }
}
