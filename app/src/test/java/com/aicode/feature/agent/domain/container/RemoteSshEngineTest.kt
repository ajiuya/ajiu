package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteSshEngineTest {
    private val connection = mockk<RemoteSshConnection>()
    private val command = mockk<Session.Command>(relaxed = true)
    private val engine = RemoteSshEngine(connection)

    @Before
    fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.v(any(), any()) } just Runs
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs
        every { connection.isConnected() } returns true
        every { connection.startExecSession(any()) } returns command
        every { command.inputStream } returns ByteArrayInputStream(byteArrayOf())
        every { command.errorStream } returns ByteArrayInputStream(byteArrayOf())
        every { command.outputStream } returns ByteArrayOutputStream()
        every { command.exitStatus } returns 7
    }

    @After
    fun release() { unmockkObject(FileLogger) }

    @Test
    fun syncPumpsStderrBeforeStdoutCanReachEofAndPreservesEveryLine() = runBlocking {
        val drained = CountDownLatch(1)
        val errors = (0 until 500).joinToString("\n", postfix = "\n") { "err-$it" }
        every { command.inputStream } returns object : InputStream() {
            override fun read(): Int {
                check(drained.await(3, TimeUnit.SECONDS)) { "stderr was not pumped concurrently" }
                return -1
            }
        }
        every { command.errorStream } returns object : ByteArrayInputStream(errors.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (it < 0) drained.countDown() }
        }
        val result = engine.runCommandSyncWithExit("fake", null, 5_000)
        assertEquals(errors, result.output)
        assertEquals(7, result.exitCode)
        assertFalse(result.outputTruncated)
        verify(exactly = 1) { command.join() }
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun streamingAlsoIncludesStderrAndKeepsEachStreamInOrder() = runBlocking {
        val stdoutRead = CountDownLatch(1)
        val stderrRead = CountDownLatch(1)
        fun gated(text: String, entered: CountDownLatch, other: CountDownLatch): InputStream =
            object : ByteArrayInputStream(text.toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(other.await(3, TimeUnit.SECONDS)) { "other stream did not start" }
                    return super.read(bytes, offset, length)
                }
            }
        every { command.inputStream } returns gated("out-1\nout-2\n", stdoutRead, stderrRead)
        every { command.errorStream } returns gated("err-1\nerr-2\n", stderrRead, stdoutRead)
        val events = engine.runCommandStream("fake", null, 5_000).toList()
        val lines = events.filterIsInstance<CommandEvent.Line>().map { it.text }
        assertEquals(listOf("out-1", "out-2"), lines.filter { it.startsWith("out-") })
        assertEquals(listOf("err-1", "err-2"), lines.filter { it.startsWith("err-") })
        assertEquals(CommandEvent.Exit(7), events.last())
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun bothStreamsAreBoundedPerLineAndSyncRetainsTheOutputCap() = runBlocking {
        val huge = "x".repeat(100_000) + "\n"
        every { command.inputStream } returns ByteArrayInputStream(huge.toByteArray())
        every { command.errorStream } returns ByteArrayInputStream("stderr-tail\n".toByteArray())
        val result = engine.runCommandSyncWithExit("fake", null, 5_000)
        assertTrue(result.outputTruncated)
        assertTrue(result.output.length < 41_000)
        assertTrue(result.output.contains("[该行输出过长，已截断]"))
    }

    @Test
    fun cancellingSyncClosesBothRawStreamsBeforeWaitingForReaders() = runBlocking {
        val stdout = BlockingInput()
        val stderr = BlockingInput()
        every { command.inputStream } returns stdout
        every { command.errorStream } returns stderr
        val job = launch(Dispatchers.IO) { engine.runCommandSyncWithExit("fake", null, 10_000) }
        assertTrue(withContext(Dispatchers.IO) { stdout.entered.await(3, TimeUnit.SECONDS) })
        assertTrue(withContext(Dispatchers.IO) { stderr.entered.await(3, TimeUnit.SECONDS) })
        withTimeout(3_000) { job.cancelAndJoin() }
        assertEquals(0L, stdout.closed.count)
        assertEquals(0L, stderr.closed.count)
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun cancellingStreamUnblocksBothPumpsWithoutEmittingAnExitEvent() = runBlocking {
        val stdout = BlockingInput()
        val stderr = BlockingInput()
        every { command.inputStream } returns stdout
        every { command.errorStream } returns stderr
        val events = mutableListOf<CommandEvent>()
        val job = launch(Dispatchers.IO) { engine.runCommandStream("fake", null, 10_000).collect { events += it } }
        assertTrue(withContext(Dispatchers.IO) { stdout.entered.await(3, TimeUnit.SECONDS) })
        assertTrue(withContext(Dispatchers.IO) { stderr.entered.await(3, TimeUnit.SECONDS) })
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(events.none { it is CommandEvent.Exit })
        assertEquals(0L, stderr.closed.count)
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun pumpFailureClosesTheOtherBlockedPumpAndPropagatesSyncFailure() = runBlocking {
        val stdout = BlockingInput()
        every { command.inputStream } returns stdout
        every { command.errorStream } returns object : InputStream() {
            override fun read(): Int {
                check(stdout.entered.await(3, TimeUnit.SECONDS))
                throw IOException("stderr failure")
            }
        }
        val failure = runCatching { engine.runCommandSyncWithExit("fake", null, 5_000) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0L, stdout.closed.count)
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun timeoutCoversAStderrReadAfterStdoutEof() = runBlocking {
        val stderr = BlockingInput()
        every { command.errorStream } returns stderr
        val result = engine.runCommandSyncWithExit("fake", null, 100)
        assertNull(result.exitCode)
        assertTrue(result.output.contains("超时"))
        assertEquals(0L, stderr.closed.count)
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun timeoutRemainsActiveAfterBothEofsUntilExitStatusIsReadyEvenWhenIsOpenIsFalse() = runBlocking {
        val closed = CountDownLatch(1)
        every { command.isOpen } returns false
        every { command.close() } answers { closed.countDown() }
        every { command.join() } answers { check(closed.await(3, TimeUnit.SECONDS)) { "join lost watchdog" } }
        val result = engine.runCommandSyncWithExit("fake", null, 100)
        assertNull(result.exitCode)
        assertTrue(result.output.contains("超时"))
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun streamingWatchdogAlsoCoversJoinAfterEof() = runBlocking {
        val closed = CountDownLatch(1)
        every { command.isOpen } returns false
        every { command.close() } answers { closed.countDown() }
        every { command.join() } answers { check(closed.await(3, TimeUnit.SECONDS)) { "join lost watchdog" } }
        val events = engine.runCommandStream("fake", null, 100).toList()
        assertEquals(CommandEvent.Exit(null), events.last())
        assertTrue(events.filterIsInstance<CommandEvent.Line>().any { it.text.contains("超时") })
        verify(exactly = 1) { command.close() }
    }

    @Test
    fun resourceCloseIsIdempotentAndOneCloseFailureDoesNotSkipOtherHandles() {
        val stderrClosed = CountDownLatch(1)
        every { command.inputStream } returns object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { throw IOException("stdout close") }
        }
        every { command.errorStream } returns object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { stderrClosed.countDown() }
        }
        val resources = SshCommandResources(command)
        resources.close()
        resources.close()
        assertEquals(0L, stderrClosed.count)
        verify(exactly = 1) { command.close() }
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            check(closed.await(3, TimeUnit.SECONDS)) { "raw stream was not closed" }
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }
}
