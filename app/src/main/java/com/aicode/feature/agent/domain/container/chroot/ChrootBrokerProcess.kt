package com.aicode.feature.agent.domain.container.chroot

import android.system.Os
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

internal class ChrootBrokerProcess(
    private val requestStop: (ChrootBrokerProcess) -> Unit
) : Process() {
    private class Streams(owned: List<ParcelFileDescriptor>) : AutoCloseable {
        val stdin = ParcelFileDescriptor.AutoCloseOutputStream(owned[0])
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(owned[1])
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(owned[2])
        val status = ParcelFileDescriptor.AutoCloseInputStream(owned[3])
        override fun close() {
            listOf(stdin, stdout, stderr, status).forEach { runCatching { it.close() } }
        }

        companion object {
            fun receive(descriptors: List<FileDescriptor>): Streams {
                val owned = ArrayList<ParcelFileDescriptor>(4)
                try {
                    descriptors.forEach { source ->
                        val duplicate = ParcelFileDescriptor.dup(source)
                        owned.add(duplicate)
                        val flags = Os.fcntlInt(duplicate.fileDescriptor, OsConstants.F_GETFD, 0)
                        Os.fcntlInt(duplicate.fileDescriptor, OsConstants.F_SETFD, flags or OsConstants.FD_CLOEXEC)
                    }
                    return Streams(owned)
                } catch (failure: Throwable) {
                    owned.forEach { runCatching { it.close() } }
                    throw failure
                } finally {
                    descriptors.forEach { runCatching { Os.close(it) } }
                }
            }
        }
    }

    private data class Result(val code: Int? = null, val failure: Throwable? = null)
    private val streams = CompletableFuture<Streams>()
    private val result = AtomicReference<Result?>()
    private val exited = CountDownLatch(1)
    private val inputClosed = AtomicBoolean()
    private val outputClosed = AtomicBoolean()
    private val errorClosed = AtomicBoolean()
    val cancelled = AtomicBoolean()
    private val dispatch = AtomicInteger()
    @Volatile var requestId: Long = 0

    private val input = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (inputClosed.get()) throw IOException("Root helper input is closed")
            obtain().stdin.write(bytes, offset, length)
        }
        override fun flush() {
            if (!inputClosed.get()) obtain().stdin.flush()
        }
        override fun close() {
            if (inputClosed.compareAndSet(false, true)) resolved()?.let { runCatching { it.stdin.close() } }
        }
    }
    private val output = reader(false)
    private val error = reader(true)

    private fun reader(errors: Boolean): InputStream = object : InputStream() {
        private val closed get() = if (errors) errorClosed else outputClosed
        private fun delegate(): InputStream {
            if (closed.get()) throw IOException("Root helper output is closed")
            val live = obtain()
            return if (errors) live.stderr else live.stdout
        }
        override fun read(): Int = delegate().read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = delegate().read(bytes, offset, length)
        override fun available(): Int {
            val live = resolved() ?: return 0
            return if (errors) live.stderr.available() else live.stdout.available()
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) resolved()?.let {
                runCatching { if (errors) it.stderr.close() else it.stdout.close() }
            }
        }
    }

    fun tryDispatch(): Boolean = !cancelled.get() && dispatch.compareAndSet(0, 1)

    fun cancelPendingLaunch() {
        if (dispatch.compareAndSet(0, 2)) {
            cancelled.set(true)
            fail(IOException("Root helper launch was cancelled before dispatch"))
        }
    }

    override fun getOutputStream(): OutputStream = input
    override fun getInputStream(): InputStream = output
    override fun getErrorStream(): InputStream = error

    fun attach(descriptors: List<FileDescriptor>, onExit: () -> Unit) {
        require(descriptors.size == 4)
        val live = Streams.receive(descriptors)
        if (!streams.complete(live) || result.get() != null) {
            live.close()
            onExit()
            return
        }
        if (inputClosed.get()) live.stdin.close()
        if (outputClosed.get()) live.stdout.close()
        if (errorClosed.get()) live.stderr.close()
        Thread({
            try {
                val status = ByteArray(16)
                var offset = 0
                while (offset < status.size) {
                    val count = live.status.read(status, offset, status.size - offset)
                    if (count <= 0) throw IOException("Root helper exit status ended early")
                    offset += count
                }
                if (live.status.read() != -1) throw IOException("Root helper exit status has trailing data")
                settle(Result(code = ChrootBrokerWire.exitStatus(status)))
            } catch (failure: Throwable) {
                settle(Result(failure = failure))
            } finally {
                runCatching { live.status.close() }
                onExit()
            }
        }, "chroot-helper-status").apply { isDaemon = true }.start()
    }

    fun fail(failure: Throwable) {
        settle(Result(failure = failure))
        streams.completeExceptionally(failure)
        resolved()?.close()
    }

    private fun settle(next: Result) {
        if (result.compareAndSet(null, next)) exited.countDown()
    }

    private fun resolved(): Streams? = if (streams.isDone && !streams.isCompletedExceptionally) streams.getNow(null) else null

    private fun obtain(): Streams = try {
        streams.get()
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Root helper descriptor wait was interrupted", failure)
    } catch (failure: ExecutionException) {
        throw IOException("Root helper descriptors are unavailable", failure.cause)
    }

    override fun isAlive(): Boolean = result.get() == null

    override fun exitValue(): Int {
        val observed = result.get() ?: throw IllegalThreadStateException("Root helper has not exited")
        return observed.code ?: throw IllegalThreadStateException("Root helper exit status is unknown").also {
            it.initCause(observed.failure)
        }
    }

    override fun waitFor(): Int {
        exited.await()
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun destroy() {
        cancelPendingLaunch()
        if (cancelled.compareAndSet(false, true)) {
            runCatching { input.close() }
            requestStop(this)
        }
    }

    override fun destroyForcibly(): Process = apply { destroy() }
}
