package com.aicode.feature.agent.domain.container.chroot

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootBrokerFdDeviceTest {
    private class NativeRead(private val input: ParcelFileDescriptor.AutoCloseInputStream) : Closeable {
        private val task = FutureTask<Int> { input.read() }
        private val thread = Thread(task, "chroot-fd-test-stdin").apply { isDaemon = true; start() }

        fun awaitEof() {
            assertEquals(-1, task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).toInt())
            thread.join(TIMEOUT_MILLIS)
            assertFalse("Bounded stdin reader did not finish", thread.isAlive)
        }

        override fun close() {
            task.cancel(true)
            thread.interrupt()
            thread.join(TIMEOUT_MILLIS)
            assertFalse("Bounded stdin reader remained alive after pipe cleanup", thread.isAlive)
        }
    }

    private class PipeFixture : Closeable {
        val process = ChrootBrokerProcess { throw AssertionError("An in-process FD fixture must not request a broker stop") }
        val originals = ArrayList<FileDescriptor>(4)
        val exitCallbacks = AtomicInteger()
        private val owners = ArrayList<ParcelFileDescriptor>(4)
        private val reads = ArrayList<NativeRead>()
        private val statusExited = CountDownLatch(1)
        private val statusThread = AtomicReference<Thread?>()
        private var transferred = false
        private var attached = false
        private var closed = false
        lateinit var stdinReader: ParcelFileDescriptor.AutoCloseInputStream
            private set
        lateinit var stdoutWriter: ParcelFileDescriptor.AutoCloseOutputStream
            private set
        lateinit var stderrWriter: ParcelFileDescriptor.AutoCloseOutputStream
            private set
        lateinit var statusWriter: ParcelFileDescriptor.AutoCloseOutputStream
            private set

        init {
            try {
                stdinReader = ParcelFileDescriptor.AutoCloseInputStream(pipe(sourceIndex = 1))
                stdoutWriter = ParcelFileDescriptor.AutoCloseOutputStream(pipe(sourceIndex = 0))
                stderrWriter = ParcelFileDescriptor.AutoCloseOutputStream(pipe(sourceIndex = 0))
                statusWriter = ParcelFileDescriptor.AutoCloseOutputStream(pipe(sourceIndex = 0))
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun pipe(sourceIndex: Int): ParcelFileDescriptor {
            val pair = ParcelFileDescriptor.createPipe()
            try {
                val source = Os.dup(pair[sourceIndex].fileDescriptor)
                originals.add(source)
                val flags = Os.fcntlInt(source, OsConstants.F_GETFD, 0)
                Os.fcntlInt(source, OsConstants.F_SETFD, flags or OsConstants.FD_CLOEXEC)
                pair[sourceIndex].close()
                return pair[1 - sourceIndex].also { owners.add(it) }
            } catch (failure: Throwable) {
                pair.forEach { descriptor -> runCatching { descriptor.close() } }
                throw failure
            }
        }

        fun readNativeStdin(): NativeRead = NativeRead(stdinReader).also { reads.add(it) }

        fun attach() {
            check(!transferred)
            assertEquals(4, originals.size)
            originals.forEach { descriptor ->
                assertTrue(Os.fcntlInt(descriptor, OsConstants.F_GETFD, 0) and OsConstants.FD_CLOEXEC != 0)
            }
            transferred = true
            process.attach(originals) {
                statusThread.set(Thread.currentThread())
                exitCallbacks.incrementAndGet()
                statusExited.countDown()
            }
            attached = true
        }

        fun assertOriginalsClosed() {
            originals.forEach { descriptor ->
                val failure = assertThrows(ErrnoException::class.java) { Os.fstat(descriptor) }
                assertEquals(OsConstants.EBADF, failure.errno)
            }
        }

        fun writeStatus(bytes: ByteArray) {
            statusWriter.write(bytes)
            statusWriter.close()
        }

        fun awaitStatusThread() {
            assertTrue("Status reader did not finish within the pipe-only budget", statusExited.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val thread = checkNotNull(statusThread.get())
            if (thread !== Thread.currentThread()) {
                thread.join(TIMEOUT_MILLIS)
                assertFalse("Status reader remained alive after EOF", thread.isAlive)
            }
            assertEquals(1, exitCallbacks.get())
        }

        override fun close() {
            if (closed) return
            closed = true
            var primary: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val previous = primary
                    if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            cleanup { process.fail(IOException("In-process pipe fixture closed")) }
            cleanup { process.outputStream.close() }
            cleanup { process.inputStream.close() }
            cleanup { process.errorStream.close() }
            owners.forEach { descriptor -> cleanup { descriptor.close() } }
            if (!transferred) originals.forEach { descriptor -> cleanup { Os.close(descriptor) } }
            if (attached) cleanup { awaitStatusThread() }
            reads.forEach { reader -> cleanup { reader.close() } }
            primary?.let { throw it }
        }
    }

    @Test
    fun stdinClosedBeforeFourFdAttachDeliversRealPipeEofAndWaitsForStatusEof() {
        val baseline = fdCount()
        PipeFixture().use { fixture ->
            fixture.process.outputStream.close()
            val nativeRead = fixture.readNativeStdin()
            val beforeAttach = fdCount()
            fixture.attach()
            fixture.assertOriginalsClosed()
            assertEquals(beforeAttach - 1, fdCount())
            nativeRead.awaitEof()
            fixture.statusWriter.write(status(value = 37))
            assertFalse(fixture.process.waitFor(0, TimeUnit.NANOSECONDS))
            assertThrows(IllegalThreadStateException::class.java) { fixture.process.exitValue() }
            fixture.statusWriter.close()
            assertTrue(fixture.process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(37, fixture.process.waitFor())
            assertEquals(37, fixture.process.exitValue())
            assertFalse(fixture.process.isAlive)
            fixture.awaitStatusThread()
        }
        assertEquals(baseline, fdCount())
    }

    @Test
    fun closingProxyStreamsClosesEachOwnedDuplicateAndNativeStdinSeesEof() {
        val baseline = fdCount()
        PipeFixture().use { fixture ->
            fixture.attach()
            fixture.assertOriginalsClosed()
            val attachedCount = fdCount()
            fixture.process.inputStream.close()
            fixture.process.inputStream.close()
            assertEquals(attachedCount - 1, fdCount())
            assertThrows(IOException::class.java) { fixture.process.inputStream.read() }
            fixture.process.errorStream.close()
            fixture.process.errorStream.close()
            assertEquals(attachedCount - 2, fdCount())
            assertThrows(IOException::class.java) { fixture.process.errorStream.read() }
            val nativeRead = fixture.readNativeStdin()
            fixture.process.outputStream.close()
            fixture.process.outputStream.close()
            nativeRead.awaitEof()
            assertEquals(attachedCount - 3, fdCount())
            assertThrows(IOException::class.java) { fixture.process.outputStream.write(1) }
            fixture.writeStatus(status(value = 255))
            assertTrue(fixture.process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(255, fixture.process.waitFor())
            fixture.awaitStatusThread()
            assertEquals(attachedCount - 5, fdCount())
        }
        assertEquals(baseline, fdCount())
    }

    @Test
    fun proxyFailureClosesAllFourOwnedDuplicatesAndNeverInventsAnExitStatus() {
        val baseline = fdCount()
        PipeFixture().use { fixture ->
            fixture.attach()
            fixture.assertOriginalsClosed()
            val attachedCount = fdCount()
            val nativeRead = fixture.readNativeStdin()
            fixture.process.fail(IOException("Synthetic channel loss without Root or a child process"))
            nativeRead.awaitEof()
            fixture.statusWriter.close()
            fixture.awaitStatusThread()
            assertEquals(attachedCount - 5, fdCount())
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.process.waitFor(0, TimeUnit.NANOSECONDS))
            assertThrows(IllegalThreadStateException::class.java) { fixture.process.exitValue() }
            assertThrows(IllegalThreadStateException::class.java) { fixture.process.waitFor() }
            assertThrows(IOException::class.java) { fixture.process.inputStream.read() }
            assertThrows(IOException::class.java) { fixture.process.errorStream.read() }
            assertThrows(IOException::class.java) { fixture.process.outputStream.write(1) }
        }
        assertEquals(baseline, fdCount())
    }

    @Test
    fun failureBeforeAttachStillConsumesOriginalsAndClosesEveryReceivedDuplicate() {
        val baseline = fdCount()
        PipeFixture().use { fixture ->
            fixture.process.fail(IOException("Cancelled before descriptors arrived"))
            val beforeAttach = fdCount()
            fixture.attach()
            fixture.assertOriginalsClosed()
            fixture.awaitStatusThread()
            assertEquals(beforeAttach - 4, fdCount())
            assertFalse(fixture.process.isAlive)
            assertThrows(IllegalThreadStateException::class.java) { fixture.process.waitFor() }
        }
        assertEquals(baseline, fdCount())
    }

    @Test
    fun eightPipeOnlyCyclesHaveNoFdGrowthAndMalformedAjexNeverBecomesZeroOrSignalOne() {
        val baseline = fdCount()
        repeat(8) { iteration ->
            PipeFixture().use { fixture ->
                fixture.attach()
                fixture.assertOriginalsClosed()
                val bytes = when (iteration) {
                    0 -> status(value = 37)
                    1 -> status(outcome = 2, value = 1)
                    2 -> status(outcome = 3)
                    3 -> status().copyOf(15)
                    4 -> status() + byteArrayOf(0)
                    5 -> status(reserved = 1)
                    6 -> status(version = 2)
                    else -> byteArrayOf()
                }
                fixture.writeStatus(bytes)
                assertTrue(fixture.process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                fixture.awaitStatusThread()
                assertFalse(fixture.process.isAlive)
                when (iteration) {
                    0 -> assertEquals(37, fixture.process.waitFor())
                    1 -> assertEquals(-1, fixture.process.waitFor())
                    else -> {
                        assertThrows(IllegalThreadStateException::class.java) { fixture.process.exitValue() }
                        assertThrows(IllegalThreadStateException::class.java) { fixture.process.waitFor() }
                    }
                }
            }
            assertEquals("FD count changed after pipe-only cycle $iteration", baseline, fdCount())
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
        const val TIMEOUT_MILLIS = 3_000L

        fun fdCount(): Int = File("/proc/self/fd").list()?.size
            ?: throw IOException("The in-process FD count is unavailable")

        fun status(outcome: Int = 1, value: Int = 0, reserved: Int = 0, version: Int = 1): ByteArray =
            ByteBuffer.allocate(16).putInt(0x414a4558).putShort(version.toShort()).putShort(outcome.toShort())
                .putInt(value).putInt(reserved).array()
    }
}
