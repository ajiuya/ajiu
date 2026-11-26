package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootSessionBackendTest {
    @Test
    fun forwardsOutputInputAndStdinEofWithoutStoppingTheTask() {
        val task = TestTerminalRuntimeProcess("real output")
        val backend = ChrootSessionBackend(task)
        assertEquals("real output", backend.inputStream.readBytes().toString(Charsets.UTF_8))
        backend.outputStream.write(byteArrayOf(9, 10, 11, 12), 1, 2)
        backend.outputStream.flush()
        backend.outputStream.close()
        backend.outputStream.close()
        assertArrayEquals(byteArrayOf(10, 11), task.writes.toByteArray())
        assertEquals(1, task.stdinEofs)
        assertEquals(0, task.stops)
        assertNull(task.taskState.value)
        assertNull(task.cleanupState.value)
    }

    @Test
    fun resizesBeforeCloseAndCloseStopsOnlyItsTaskExactlyOnce() {
        val task = TestTerminalRuntimeProcess()
        val sibling = TestTerminalRuntimeProcess()
        val backend = ChrootSessionBackend(task)
        backend.resize(132, 43)
        backend.close()
        backend.close()
        backend.resize(80, 24)
        assertEquals(listOf(132 to 43), task.sizes)
        assertEquals(1, task.stops)
        assertEquals(0, sibling.stops)
        assertNull(task.cleanupState.value)
    }

    @Test
    fun waitsForRealTaskExitWithoutWaitingForOrInventingCleanup() {
        val task = TestTerminalRuntimeProcess()
        val backend = ChrootSessionBackend(task)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<com.termux.terminal.SessionBackend.ExitOutcome> { backend.waitForExitOutcome() }
        try {
            assertTrue(task.waiting.await(5, TimeUnit.SECONDS))
            assertFalse(future.isDone)
            task.completeTask(LocalRuntimeTaskResult.Exited(37, null))
            val outcome = future.get(5, TimeUnit.SECONDS)
            assertTrue(outcome.isKnown)
            assertEquals(37, outcome.status)
            assertNull(task.cleanupState.value)
            backend.close()
            assertEquals(0, task.stops)
            task.cleanupState.value = LocalRuntimeCleanup.UNKNOWN
            assertEquals(37, backend.waitForExit())
            assertEquals(LocalRuntimeCleanup.UNKNOWN, task.cleanupState.value)
        } finally {
            task.completeTask(LocalRuntimeTaskResult.Unknown)
            executor.shutdownNow()
        }
    }

    @Test
    fun unknownIsNotTheLegacyNegatedSignalOneAndSignalsRemainTypedKnownResults() {
        val unknown = TestTerminalRuntimeProcess().apply { completeTask(LocalRuntimeTaskResult.Unknown) }
        val unknownOutcome = ChrootSessionBackend(unknown).waitForExitOutcome()
        assertFalse(unknownOutcome.isKnown)
        assertEquals(-1, unknownOutcome.status)
        val signaled = TestTerminalRuntimeProcess().apply { completeTask(LocalRuntimeTaskResult.Exited(null, 1)) }
        val signalOutcome = ChrootSessionBackend(signaled).waitForExitOutcome()
        assertTrue(signalOutcome.isKnown)
        assertEquals(-1, signalOutcome.status)
    }
}
