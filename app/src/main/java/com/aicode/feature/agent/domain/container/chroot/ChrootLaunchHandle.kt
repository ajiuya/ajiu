package com.aicode.feature.agent.domain.container.chroot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface ChrootTaskResult {
    data class Exited(val exit: ChrootProcessExit) : ChrootTaskResult
    data object Unknown : ChrootTaskResult
}

internal class ChrootLaunchHandle internal constructor(
    val instanceId: String,
    val launchId: String,
    private val sendInput: (ByteArray) -> Unit,
    private val sendEof: () -> Unit,
    private val sendResize: (Int, Int) -> Boolean,
    private val sendSignal: (Int) -> Boolean,
    private val requestStop: () -> Unit
) : Process() {
    private val stdout = BoundedInput()
    private val stderr = BoundedInput()
    private val ready = CompletableDeferred<Boolean>()
    private val taskExit = CompletableDeferred<ChrootTaskResult>()
    private val cleanup = CompletableDeferred<ChrootCleanupResult>()
    private val taskLatch = CountDownLatch(1)
    private val stopped = AtomicBoolean()
    private val stdinClosed = AtomicBoolean()
    private val _taskState = MutableStateFlow<ChrootTaskResult?>(null)
    val taskState: StateFlow<ChrootTaskResult?> = _taskState.asStateFlow()
    private val _cleanupState = MutableStateFlow<ChrootCleanupResult?>(null)
    val cleanupState: StateFlow<ChrootCleanupResult?> = _cleanupState.asStateFlow()
    private val _failure = MutableStateFlow<ChrootRuntimeFailure?>(null)
    internal val failure: StateFlow<ChrootRuntimeFailure?> = _failure.asStateFlow()
    private val _nativeFailure = MutableStateFlow<ChrootRuntimeFailure?>(null)
    internal val nativeFailure: StateFlow<ChrootRuntimeFailure?> = _nativeFailure.asStateFlow()

    private val stdin = object : OutputStream() {
        @Synchronized
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            if (offset < 0 || count < 0 || offset > bytes.size - count) throw IndexOutOfBoundsException()
            if (stdinClosed.get() || stopped.get() || taskExit.isCompleted) throw IOException("Runtime input is closed")
            var position = offset
            val end = offset + count
            while (position < end) {
                if (stopped.get() || taskExit.isCompleted) throw IOException("Runtime input is closed")
                val next = minOf(end, position + MAX_INPUT_FRAME_BYTES)
                sendInput(bytes.copyOfRange(position, next))
                position = next
            }
        }

        @Synchronized
        override fun close() {
            if (stdinClosed.get()) return
            if (!stopped.get() && !taskExit.isCompleted) sendEof()
            stdinClosed.set(true)
        }
    }

    internal suspend fun awaitReady(): Boolean = ready.await()

    suspend fun awaitTaskExit(): ChrootTaskResult = taskExit.await()

    suspend fun awaitCleanup(): ChrootCleanupResult = cleanup.await()

    fun resize(columns: Int, rows: Int): Boolean {
        require(columns in 1..4096 && rows in 1..4096)
        return !stopped.get() && !taskExit.isCompleted && sendResize(columns, rows)
    }

    fun signal(signal: Int): Boolean {
        require(signal in setOf(1, 2, 15))
        return !stopped.get() && !taskExit.isCompleted && sendSignal(signal)
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            ready.complete(false)
            requestStop()
        }
    }

    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream(): OutputStream = stdin
    override fun isAlive(): Boolean = taskLatch.count != 0L

    override fun waitFor(): Int {
        taskLatch.await()
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = taskLatch.await(timeout, unit)

    override fun exitValue(): Int = when (val result = _taskState.value) {
        null -> throw IllegalThreadStateException("Runtime task has not exited")
        ChrootTaskResult.Unknown -> UNKNOWN_EXIT_STATUS
        is ChrootTaskResult.Exited -> result.exit.terminalStatus
    }

    override fun destroy() = stop()

    override fun destroyForcibly(): Process {
        stop()
        return this
    }

    internal fun recordFailure(failure: ChrootRuntimeFailure): Boolean = _failure.compareAndSet(null, failure)

    internal fun recordNativeFailure(failure: ChrootRuntimeFailure): Boolean =
        failure.kind == ChrootRuntimeFailureKind.NATIVE_ERROR && failure.nativeFailureObserved &&
            _nativeFailure.compareAndSet(null, failure)

    internal fun markReady() {
        ready.complete(true)
    }

    internal fun markStopOnly() {
        stopped.set(true)
        ready.complete(false)
    }

    internal fun appendOutput(error: Boolean, bytes: ByteArray): Boolean =
        if (error) stderr.offer(bytes) else stdout.offer(bytes)

    internal fun completeTask(result: ChrootTaskResult) {
        synchronized(taskExit) {
            if (taskExit.isCompleted) return
            ready.complete(false)
            _taskState.value = result
            stdout.finish()
            stderr.finish()
            taskExit.complete(result)
            taskLatch.countDown()
        }
    }

    internal fun completeCleanup(result: ChrootCleanupResult) {
        synchronized(cleanup) {
            if (cleanup.isCompleted) return
            _cleanupState.value = result
            cleanup.complete(result)
        }
    }

    private class BoundedInput : InputStream() {
        private val gate = java.lang.Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var offset = 0
        private var size = 0
        private var finished = false
        private var closed = false

        fun offer(bytes: ByteArray): Boolean = synchronized(gate) {
            if (finished || closed || bytes.size > MAX_BUFFER_BYTES - size) return@synchronized false
            if (bytes.isNotEmpty()) {
                chunks.addLast(bytes.copyOf())
                size += bytes.size
                gate.notifyAll()
            }
            true
        }

        fun finish() = synchronized(gate) {
            finished = true
            gate.notifyAll()
        }

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, target: Int, count: Int): Int = synchronized(gate) {
            if (target < 0 || count < 0 || target > bytes.size - count) throw IndexOutOfBoundsException()
            if (count == 0) return@synchronized 0
            while (size == 0 && !finished && !closed) {
                try {
                    gate.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Runtime output read was interrupted")
                }
            }
            if (closed) throw IOException("Runtime output is closed")
            if (size == 0) return@synchronized -1
            val chunk = chunks.first()
            val taken = minOf(count, chunk.size - offset)
            chunk.copyInto(bytes, target, offset, offset + taken)
            offset += taken
            size -= taken
            if (offset == chunk.size) {
                chunks.removeFirst()
                offset = 0
            }
            taken
        }

        override fun available(): Int = synchronized(gate) { size }

        override fun close() = synchronized(gate) {
            closed = true
            chunks.clear()
            size = 0
            gate.notifyAll()
        }
    }

    companion object {
        const val UNKNOWN_EXIT_STATUS = -1
        const val MAX_BUFFER_BYTES = 512 * 1024
        private const val MAX_INPUT_FRAME_BYTES = 64 * 1024
    }
}
