package com.aicode.feature.agent.domain.container

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class SshCommandResources(private val command: Session.Command) {
    private val closed = AtomicBoolean(false)
    private var stdout: InputStream? = null
    private var stderr: InputStream? = null
    private var stdin: OutputStream? = null

    init {
        try {
            stdout = command.inputStream
            stderr = command.errorStream
            stdin = command.outputStream
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    val streams: List<InputStream> get() = listOfNotNull(stdout, stderr)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Reader.close() 会等待正在 readLine 的锁；先关 raw 流和 channel 才能唤醒阻塞读取。
        runCatching { stdout?.close() }
        runCatching { stderr?.close() }
        runCatching { stdin?.close() }
        runCatching { command.close() }
    }
}

@OptIn(InternalCoroutinesApi::class)
internal fun drainSshOutput(resources: SshCommandResources): Flow<String> = channelFlow {
    coroutineScope {
        val hook = coroutineContext[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) resources.close()
        }
        try {
            resources.streams.map { stream ->
                launch(Dispatchers.IO) {
                    BoundedLineReader(InputStreamReader(stream)).use { reader ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = reader.readLine() ?: break
                            send(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                        }
                    }
                }
            }.joinAll()
        } finally {
            hook.dispose()
        }
    }
}.buffer(16)
