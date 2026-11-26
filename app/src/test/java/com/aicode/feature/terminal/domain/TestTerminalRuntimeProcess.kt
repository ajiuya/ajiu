package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal class TestTerminalRuntimeProcess(output: String = "") : LocalRuntimeProcess() {
    override val instanceId = "terminal-fixture-instance"
    override val launchId = "terminal-fixture-launch"
    override val taskState = MutableStateFlow<LocalRuntimeTaskResult?>(null)
    override val cleanupState = MutableStateFlow<LocalRuntimeCleanup?>(null)
    val waiting = CountDownLatch(1)
    private val exited = CountDownLatch(1)
    private val stopped = AtomicBoolean()
    var stops = 0
        private set
    var stdinEofs = 0
        private set
    val writes = ByteArrayOutputStream()
    val sizes = mutableListOf<Pair<Int, Int>>()
    val signals = mutableListOf<Int>()
    private val input = ByteArrayInputStream(output.toByteArray())
    private val output = object : OutputStream() {
        private val closed = AtomicBoolean()
        override fun write(value: Int) = writes.write(value)
        override fun write(bytes: ByteArray, offset: Int, count: Int) = writes.write(bytes, offset, count)
        override fun close() {
            if (closed.compareAndSet(false, true)) stdinEofs++
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getOutputStream(): OutputStream = output
    override fun waitFor(): Int {
        waiting.countDown()
        exited.await()
        return exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun isAlive(): Boolean = taskState.value == null
    override fun exitValue(): Int = when (val result = taskState.value) {
        null -> throw IllegalThreadStateException()
        LocalRuntimeTaskResult.Unknown -> -1
        is LocalRuntimeTaskResult.Exited -> result.terminalStatus
    }
    override fun destroy() = stop()
    override fun resize(columns: Int, rows: Int): Boolean {
        sizes += columns to rows
        return isAlive
    }
    override fun signal(signal: Int): Boolean {
        signals += signal
        return isAlive
    }
    override fun stop() {
        if (stopped.compareAndSet(false, true)) stops++
    }
    override suspend fun awaitTaskExit(): LocalRuntimeTaskResult = taskState.filterNotNull().first()
    override suspend fun awaitCleanup(): LocalRuntimeCleanup = cleanupState.filterNotNull().first()

    fun completeTask(result: LocalRuntimeTaskResult) {
        taskState.value = result
        exited.countDown()
    }
}
