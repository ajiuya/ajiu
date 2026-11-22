package com.aicode.feature.plugin.runtime

import com.aicode.feature.terminal.domain.TerminalResourceOwner
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTabStatus
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PluginTerminalOutputBuffer(private val capacity: Int = 128 * 1024) {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private val text = StringBuilder()
    private var total = 0L
    private var finished = false

    @Synchronized fun append(bytes: ByteArray) {
        if (finished) return
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(4096, bytes.size - offset)
            decode(pending + bytes.copyOfRange(offset, offset + count), false)
            offset += count
        }
    }

    private fun decode(bytes: ByteArray, end: Boolean) {
        val input = ByteBuffer.wrap(bytes)
        val chars = CharBuffer.allocate(4096)
        while (true) {
            val result = decoder.decode(input, chars, end)
            chars.flip()
            total += chars.remaining()
            text.append(chars)
            if (text.length > capacity) text.delete(0, text.length - capacity)
            chars.clear()
            if (!result.isOverflow) break
        }
        pending = ByteArray(input.remaining()).also { input.get(it) }
    }

    @Synchronized fun finish() {
        if (finished) return
        finished = true
        decode(pending, true)
        pending = ByteArray(0)
    }

    @Synchronized fun poll(offset: Long): Triple<String, Long, Boolean> {
        val dropped = total - text.length
        val from = (offset - dropped).coerceIn(0, text.length.toLong()).toInt()
        return Triple(text.substring(from), total, offset < dropped)
    }
}

@Singleton
class PluginTerminalRunner @Inject constructor(private val provider: TerminalSessionProvider) {
    private class Run(val context: PluginInvocationContext, val owner: PluginResourceOwner, val title: String?, val command: String) {
        val id = UUID.randomUUID().toString()
        val terminalOwner = TerminalResourceOwner(owner.pluginId, id)
        val output = PluginTerminalOutputBuffer()
        val ready = CompletableDeferred<String>()
        var tabId: String? = null
        var job: Job? = null
        var closing = false
        var stopped = false
        var stoppedStatus: TerminalTabStatus? = null
        var entered = false
    }
    private val lock = Any()
    private val runs = linkedMapOf<String, Run>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(context: PluginInvocationContext, command: String, title: String?): JsonObject {
        context.checkIntegrity()
        val execution = context.execution ?: throw PluginHostException("CONTEXT_REQUIRED", "A ready captured workspace is required")
        val run = Run(context, PluginResourceOwner.of(context), title, command)
        synchronized(lock) {
            if (runs.size >= 8 || runs.values.count { it.owner.pluginId == run.owner.pluginId } >= 4) {
                throw PluginHostException("TERMINAL_LIMIT", "Plugin terminal resources are still active or closing")
            }
            runs[run.id] = run
            run.job = scope.launch(start = CoroutineStart.LAZY) {
                run.entered = true
                var observer: Job? = null
                try {
                    observer = launch(start = CoroutineStart.UNDISPATCHED) {
                        context.changes.collect {
                            if (runCatching { context.checkCurrent() }.isFailure) retire(run)
                        }
                    }
                    context.checkIntegrity()
                    val id = withContext(Dispatchers.Main.immediate + execution) {
                        context.checkCurrent()
                        provider.startPluginCommand(command, title, run.terminalOwner) { bytes ->
                            if (!run.closing && runCatching { context.checkCurrent() }.isSuccess) run.output.append(bytes)
                        }.also { created -> synchronized(lock) { run.tabId = created } }
                    }
                    synchronized(lock) { run.tabId = id }
                    context.checkIntegrity()
                    run.ready.complete(id)
                    awaitCancellation()
                } catch (failure: Throwable) {
                    run.ready.completeExceptionally(failure)
                    if (failure is Error) throw failure
                } finally {
                    withContext(NonCancellable) {
                        observer?.cancelAndJoin()
                        run.tabId?.let { id -> withContext(Dispatchers.Main.immediate) { provider.closePluginTab(id, run.terminalOwner) } }
                        run.output.finish()
                        synchronized(lock) { runs.remove(run.id, run) }
                    }
                }
            }.also { job ->
                job.invokeOnCompletion { failure ->
                    if (!run.ready.isCompleted) run.ready.completeExceptionally(failure ?: CancellationException("Terminal start cancelled"))
                    synchronized(lock) { if (!run.entered) runs.remove(run.id, run) }
                }
            }
        }
        run.job!!.start()
        try {
            run.ready.await()
            context.checkIntegrity()
            return buildJsonObject { put("tabId", run.id) }
        } catch (failure: Throwable) {
            retire(run)
            throw failure
        }
    }

    suspend fun poll(context: PluginInvocationContext, id: String, offset: Long): JsonObject {
        val run = owned(context, id)
        val status = run.stoppedStatus ?: withContext(Dispatchers.Main.immediate) { provider.pluginTabStatus(requireNotNull(run.tabId), run.terminalOwner) }
        if (status == null) throw PluginHostException("TAB_CLOSED", "The terminal was closed")
        if (!status.running) run.output.finish()
        val (output, next, truncated) = run.output.poll(offset)
        context.checkCurrent()
        return buildJsonObject {
            put("output", output); put("offset", next); put("state", if (run.stopped) "cancelled" else if (status.running) "running" else "finished")
            status.exitCode?.let { put("exitCode", it) }; put("truncated", truncated)
        }
    }

    suspend fun write(context: PluginInvocationContext, id: String, text: String, appendNewline: Boolean): Boolean {
        val run = owned(context, id)
        return withContext(Dispatchers.Main.immediate) {
            context.checkCurrent()
            provider.writePluginInput(requireNotNull(run.tabId), run.terminalOwner, text, appendNewline)
        }
    }

    suspend fun stop(context: PluginInvocationContext, id: String): Boolean {
        val run = owned(context, id)
        run.stopped = true
        val sent = withContext(Dispatchers.Main.immediate) {
            context.checkCurrent(); provider.interruptPluginTab(requireNotNull(run.tabId), run.terminalOwner)
        }
        val completed = withTimeoutOrNull(2_000) {
            var status: TerminalTabStatus?
            do {
                context.checkCurrent()
                status = withContext(Dispatchers.Main.immediate) { provider.pluginTabStatus(requireNotNull(run.tabId), run.terminalOwner) }
                if (status?.running == true) delay(50)
            } while (status?.running == true)
            status
        }
        if (completed != null) run.stoppedStatus = completed else {
            withContext(Dispatchers.Main.immediate) { provider.closePluginTab(requireNotNull(run.tabId), run.terminalOwner) }
            run.stoppedStatus = TerminalTabStatus(false, null)
        }
        run.output.finish()
        context.checkCurrent()
        return sent
    }

    suspend fun close(context: PluginInvocationContext, id: String): Boolean {
        val run = owned(context, id)
        retire(run)
        run.job?.join()
        return true
    }

    suspend fun list(context: PluginInvocationContext): JsonArray {
        context.checkIntegrity()
        val owner = PluginResourceOwner.of(context)
        val mine = synchronized(lock) { runs.values.filter { it.owner == owner && !it.closing && it.tabId != null } }
        return JsonArray(mine.map { run ->
            val status = run.stoppedStatus ?: withContext(Dispatchers.Main.immediate) { provider.pluginTabStatus(requireNotNull(run.tabId), run.terminalOwner) }
            buildJsonObject {
                put("tabId", run.id); put("title", run.title ?: run.id); put("command", run.command)
                put("state", if (run.stopped) "cancelled" else if (status?.running == true) "running" else "finished")
            }
        }).also { context.checkCurrent() }
    }

    private fun owned(context: PluginInvocationContext, id: String): Run {
        context.checkIntegrity()
        return synchronized(lock) { runs[id]?.takeIf { it.owner == PluginResourceOwner.of(context) && !it.closing } }
            ?: throw PluginHostException("TAB_NOT_FOUND", "No terminal belongs to this plugin and source")
    }

    private fun retire(run: Run) {
        synchronized(lock) { run.closing = true }
        run.job?.cancel(CancellationException("Plugin terminal authority ended"))
    }
}
