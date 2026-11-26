package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.plugin.domain.PluginJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

internal class PluginFakeProcess : Process() {
    val messages = Channel<JsonObject>(Channel.UNLIMITED)
    val destroyed = AtomicInteger()
    val outputClosed = AtomicBoolean()
    val stdinClosed = AtomicBoolean()
    val errorClosed = AtomicBoolean()
    private val alive = AtomicBoolean(true)
    private val exited = CountDownLatch(1)
    private var code = 0
    private val stdout = QueueInput(outputClosed)
    private val stderr = QueueInput(errorClosed)
    private val stdin = object : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Synchronized override fun write(value: Int) {
            check(!stdinClosed.get()) { "stdin closed" }
            if (value == 10) {
                val line = bytes.toString("UTF-8")
                bytes.reset()
                check(messages.trySend(PluginJson.parse(line) as JsonObject).isSuccess)
            } else bytes.write(value)
        }
        override fun write(b: ByteArray, off: Int, len: Int) { for (index in off until off + len) write(b[index].toInt() and 255) }
        override fun close() { stdinClosed.set(true) }
    }
    fun send(value: JsonObject) = stdout.feed((value.toString() + "\n").toByteArray())
    fun raw(value: ByteArray) = stdout.feed(value)
    fun error(value: ByteArray) = stderr.feed(value)
    suspend fun message(): JsonObject = withTimeout(5_000) { messages.receive() }
    fun exit(value: Int = 0) {
        code = value
        alive.set(false)
        stdout.close()
        stderr.close()
        exited.countDown()
    }
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun waitFor(): Int { exited.await(); return code }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun exitValue(): Int { if (alive.get()) throw IllegalThreadStateException("alive"); return code }
    override fun isAlive(): Boolean = alive.get()
    override fun destroy() { destroyed.incrementAndGet(); exit() }
    override fun destroyForcibly(): Process { destroy(); return this }

    private class QueueInput(private val closed: AtomicBoolean) : InputStream() {
        val queue = LinkedBlockingQueue<Int>()
        fun feed(bytes: ByteArray) { check(!closed.get()); bytes.forEach { queue.add(it.toInt() and 255) } }
        override fun read(): Int = queue.take().also { if (it == -1) queue.offer(-1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first < 0) return -1
            b[off] = first.toByte()
            var count = 1
            while (count < len) {
                val next = queue.poll() ?: break
                if (next < 0) { queue.offer(-1); break }
                b[off + count++] = next.toByte()
            }
            return count
        }
        override fun close() { if (closed.compareAndSet(false, true)) { queue.clear(); queue.offer(-1) } }
    }
}

internal class PluginFakeProcessFactory : PluginRuntimeProcessFactory {
    val started = Channel<PluginFakeProcess>(Channel.UNLIMITED)
    val launches: MutableList<PluginLocalLaunch> = Collections.synchronizedList(mutableListOf())
    val processes: MutableList<PluginFakeProcess> = Collections.synchronizedList(mutableListOf())
    var beforeStart: (() -> Unit)? = null
    override suspend fun captureProfile(): ContainerProfile = ContainerProfile.BUILTIN_ALPINE
    override fun containerPath(file: File): String = file.absolutePath
    override suspend fun start(launch: PluginLocalLaunch): Process {
        beforeStart?.invoke()
        return PluginFakeProcess().also { launches.add(launch); processes.add(it); check(started.trySend(it).isSuccess) }
    }
}
