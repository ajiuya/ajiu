package com.aicode.feature.agent.domain.container.chroot

import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootBrokerProcessTest {
    @Test
    fun freshQueuedProcessHasNoExitAndZeroTimeoutDoesNotProveCompletion() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        try {
            assertTrue(process.isAlive)
            assertEquals(0L, process.requestId)
            assertFalse(process.waitFor(0, TimeUnit.NANOSECONDS))
            assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
            assertEquals(0, process.inputStream.available())
            assertEquals(0, process.errorStream.available())
            assertTrue(stops.isEmpty())
        } finally {
            process.destroy()
        }
    }

    @Test
    fun cancelBeforeDispatchSettlesUnknownWithoutCallingStopOrAllowingDispatch() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        process.cancelPendingLaunch()
        process.cancelPendingLaunch()
        process.destroy()
        assertSame(process, process.destroyForcibly())
        assertTrue(process.cancelled.get())
        assertFalse(process.tryDispatch())
        assertFalse(process.tryDispatch())
        assertFalse(process.isAlive)
        assertTrue(process.waitFor(0, TimeUnit.NANOSECONDS))
        assertEquals(0L, process.requestId)
        assertTrue(stops.isEmpty())
        assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
        assertThrows(IllegalThreadStateException::class.java) { process.waitFor() }
        assertThrows(IOException::class.java) { process.inputStream.read() }
        assertThrows(IOException::class.java) { process.errorStream.read() }
        assertThrows(IOException::class.java) { process.outputStream.write(1) }
    }

    @Test
    fun destroyBeforeStartIsIdempotentAndNeverCallsTheStopCallback() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        repeat(4) { process.destroy(); process.destroyForcibly() }
        assertTrue(process.cancelled.get())
        assertFalse(process.tryDispatch())
        assertFalse(process.isAlive)
        assertTrue(stops.isEmpty())
        assertThrows(IllegalThreadStateException::class.java) { process.waitFor() }
    }

    @Test
    fun dispatchSucceedsOnlyOnceAndPendingCancellationCannotCancelADispatchedJob() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        try {
            assertTrue(process.tryDispatch())
            assertFalse(process.tryDispatch())
            process.requestId = Long.MAX_VALUE
            process.cancelPendingLaunch()
            assertFalse(process.cancelled.get())
            assertTrue(process.isAlive)
            assertFalse(process.waitFor(0, TimeUnit.NANOSECONDS))
            assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
            assertTrue(stops.isEmpty())
        } finally {
            process.fail(IOException("fixture end"))
        }
    }

    @Test
    fun destroyAfterDispatchStopsOnlyThatJobOnceAndDoesNotInventAnExit() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val first = ChrootBrokerProcess { stops += it }
        val second = ChrootBrokerProcess { stops += it }
        try {
            assertTrue(first.tryDispatch())
            assertTrue(second.tryDispatch())
            first.requestId = 17
            second.requestId = 18
            first.destroy()
            first.destroy()
            assertSame(first, first.destroyForcibly())
            assertEquals(1, stops.size)
            assertSame(first, stops.single())
            assertEquals(17L, stops.single().requestId)
            assertTrue(first.cancelled.get())
            assertFalse(first.tryDispatch())
            assertFalse(second.cancelled.get())
            assertTrue(second.isAlive)
            assertTrue(first.isAlive)
            assertFalse(first.waitFor(0, TimeUnit.NANOSECONDS))
            assertThrows(IllegalThreadStateException::class.java) { first.exitValue() }
            assertThrows(IOException::class.java) { first.outputStream.write(1) }
        } finally {
            first.fail(IOException("fixture end"))
            second.fail(IOException("fixture end"))
        }
    }

    @Test
    fun descriptorFailureMakesAllStreamOperationsThrowAndKeepsFirstUnknownCause() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        val unavailable = IOException("descriptor receive failed")
        process.fail(unavailable)
        process.fail(IOException("later failure"))
        assertFalse(process.isAlive)
        assertTrue(process.waitFor(0, TimeUnit.NANOSECONDS))
        val exitFailure = assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
        val waitFailure = assertThrows(IllegalThreadStateException::class.java) { process.waitFor() }
        assertSame(unavailable, exitFailure.cause)
        assertSame(unavailable, waitFailure.cause)
        assertThrows(IOException::class.java) { process.inputStream.read() }
        assertThrows(IOException::class.java) { process.inputStream.read(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { process.errorStream.read() }
        assertThrows(IOException::class.java) { process.outputStream.write(1) }
        assertThrows(IOException::class.java) { process.outputStream.write(byteArrayOf(1), 0, 1) }
        assertThrows(IOException::class.java) { process.outputStream.flush() }
        assertTrue(stops.isEmpty())
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }

    @Test
    fun streamsClosedWhileQueuedRejectIoAndKeepTheLaunchCancellable() {
        val stops = mutableListOf<ChrootBrokerProcess>()
        val process = ChrootBrokerProcess { stops += it }
        try {
            repeat(2) {
                process.outputStream.close()
                process.inputStream.close()
                process.errorStream.close()
            }
            assertThrows(IOException::class.java) { process.outputStream.write(1) }
            assertThrows(IOException::class.java) { process.inputStream.read() }
            assertThrows(IOException::class.java) { process.errorStream.read() }
            assertTrue(process.isAlive)
            assertFalse(process.waitFor(0, TimeUnit.NANOSECONDS))
            assertTrue(stops.isEmpty())
        } finally {
            process.destroy()
        }
        assertFalse(process.tryDispatch())
        assertTrue(stops.isEmpty())
    }

    @Test
    fun attachRejectsAnyDescriptorCountOtherThanFourBeforeTouchingAndroid() {
        val process = ChrootBrokerProcess { throw AssertionError("No dispatched job may be stopped") }
        var exits = 0
        try {
            for (count in listOf(0, 1, 2, 3, 5)) {
                assertThrows(IllegalArgumentException::class.java) {
                    process.attach(List(count) { java.io.FileDescriptor() }) { exits++ }
                }
            }
            assertTrue(process.isAlive)
            assertEquals(0, exits)
        } finally {
            process.destroy()
        }
    }
}
