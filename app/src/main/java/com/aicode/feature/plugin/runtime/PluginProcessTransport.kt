package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginJson
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class PluginProcessTransport(
    private val processFactory: suspend () -> Process,
    private val checkStart: () -> Unit = {},
    private val hostBudget: PluginRequestBudget = PluginRequestBudget(PLUGIN_MAX_HOST_REQUESTS),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onClosed: () -> Unit = {}
) : AutoCloseable, PluginOwnedProcess {
    private class Invocation(
        val context: PluginRuntimeContext,
        val hostRequest: suspend (String, JsonObject) -> JsonElement,
        val job: Job
    )

    private val lock = Any()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    private val invocationMutex = Mutex()
    private val writeMutex = Mutex()
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val peerIds = ConcurrentHashMap.newKeySet<String>()
    private val stopped = CompletableDeferred<Unit>()
    private var startup: kotlinx.coroutines.Deferred<Unit>? = null
    private var process: Process? = null
    private var active: Invocation? = null
    private var idle: Invocation? = null
    private var authority: Any? = null
    @Volatile private var failure: PluginProcessException? = null

    val isClosed: Boolean get() = failure != null

    suspend fun request(
        method: String,
        params: JsonObject,
        context: PluginRuntimeContext,
        hostRequest: suspend (String, JsonObject) -> JsonElement,
        timeoutMs: Long
    ): JsonElement = invocationMutex.withLock {
        context.checkCurrent()
        val invocation = Invocation(context, hostRequest, SupervisorJob(owner))
        var previousIdle: Invocation? = null
        synchronized(lock) {
            failure?.let { invocation.job.cancel(); throw it }
            if (authority != null && authority !== context.authorityIdentity) {
                invocation.job.cancel()
                val error = PluginProcessException("PLUGIN_CONTEXT_MISMATCH", "Plugin process belongs to another invocation authority")
                close(error)
                throw error
            }
            authority = context.authorityIdentity
            previousIdle = idle
            idle = null
            active = invocation
        }
        var id: Long? = null
        var completed = false
        try {
            withTimeout(timeoutMs) {
                ensureStarted()
                checkInvocation(invocation)
                val requestId = nextId.incrementAndGet()
                id = requestId
                val response = CompletableDeferred<JsonElement>()
                synchronized(lock) {
                    failure?.let { throw it }
                    pending[requestId] = response
                }
                writeLine(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put("method", method)
                    put("params", params)
                }, invocation)
                response.await().also { invocation.context.checkCurrent(); completed = true }
            }
        } catch (timeout: TimeoutCancellationException) {
            val error = PluginProcessException("PLUGIN_TIMEOUT", "Plugin invocation timed out")
            close(error)
            throw error
        } catch (cancelled: CancellationException) {
            close(PluginProcessException("PLUGIN_CANCELLED", "Plugin invocation was cancelled"))
            throw cancelled
        } catch (error: Exception) {
            close(error as? PluginProcessException ?: PluginProcessException("PLUGIN_WRITE_FAILED", "Plugin stream failed"))
            throw error
        } finally {
            synchronized(lock) {
                if (active === invocation) {
                    active = null
                    if (completed && failure == null) {
                        idle = if (context.allowIdleCallbacks) Invocation(context, hostRequest, SupervisorJob(owner))
                            else previousIdle?.takeIf { it.job.isActive && runCatching { it.context.checkCurrent() }.isSuccess }
                    }
                    if (idle !== previousIdle) previousIdle?.job?.cancel()
                }
            }
            invocation.job.cancel()
            id?.let { pending.remove(it) }
        }
    }

    private suspend fun ensureStarted() {
        val task = synchronized(lock) {
            failure?.let { throw it }
            startup ?: scope.async {
                var acquired: Process? = null
                try {
                    checkStart()
                    val target = processFactory()
                    acquired = target
                    checkStart()
                    synchronized(lock) {
                        failure?.let { throw it }
                        owner.ensureActive()
                        process = target
                        scope.launch { readLoop(target) }
                        scope.launch { drainStderr(target) }
                        acquired = null
                    }
                } catch (error: Exception) {
                    close(error as? PluginProcessException ?: PluginProcessException("PLUGIN_START_FAILED", "Plugin process could not start"))
                    throw failure ?: error
                } finally {
                    acquired?.let { withContext(NonCancellable) { disposeProcess(it) } }
                }
            }.also { startup = it }
        }
        task.await()
        failure?.let { throw it }
    }

    private suspend fun writeLine(value: JsonObject, invocation: Invocation? = null) {
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > PLUGIN_MAX_LINE_BYTES + 1) {
            throw PluginProcessException("PLUGIN_OUTPUT_LIMIT", "Plugin RPC result exceeds the byte limit")
        }
        scope.async {
            writeMutex.withLock {
                invocation?.let(::checkInvocation)
                val target = synchronized(lock) {
                    failure?.let { throw it }
                    process ?: throw PluginProcessException("PLUGIN_NOT_STARTED", "Plugin process is not started")
                }
                target.outputStream.write(bytes)
                target.outputStream.flush()
                invocation?.let(::checkInvocation)
            }
        }.await()
    }

    private suspend fun readLoop(target: Process) {
        try {
            val reader = PluginBoundedLineReader(target.inputStream, checkCurrent = {
                synchronized(lock) { active ?: idle }?.let { checkInvocation(it) }
            })
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val value = try { PluginJson.parse(line) as? JsonObject } catch (_: Exception) { null }
                    ?: throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin stdout is not a JSON-RPC object")
                if ((value["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "2.0") {
                    throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin JSON-RPC version is invalid")
                }
                val method = (value["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val id = value["id"]
                if (value.containsKey("method")) {
                    if (method.isNullOrBlank() || method.length > 128) {
                        throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin RPC method is invalid")
                    }
                    if (value.keys.any { it !in setOf("jsonrpc", "id", "method", "params") }) {
                        throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin RPC request contains incompatible fields")
                    }
                    val params = parameters(value)
                    if (id != null) handlePeerRequest(id, method, params)
                } else if (id != null) {
                    handleResponse(id, value)
                } else {
                    throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin RPC message has no method or id")
                }
            }
            val missing = runCatching { target.exitValue() == 127 }.getOrDefault(false)
            close(if (missing) PluginProcessException("PLUGIN_RUNTIME_MISSING", "Install the declared runtime in the local container before retrying")
                else PluginProcessException("PLUGIN_EXITED", "Plugin stdout closed"))
        } catch (error: Exception) {
            close(error as? PluginProcessException ?: PluginProcessException("PLUGIN_STREAM_FAILED", "Plugin stdout failed"))
        }
    }

    private fun parameters(value: JsonObject): JsonObject {
        val params = value["params"] ?: return JsonObject(emptyMap())
        return params as? JsonObject ?: throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin RPC parameters must be an object")
    }

    private fun handleResponse(id: JsonElement, value: JsonObject) {
        val number = (id as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin response id is invalid")
        if (value.keys.any { it !in setOf("jsonrpc", "id", "result", "error") }) {
            throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin RPC response contains incompatible fields")
        }
        val response = pending.remove(number) ?: return
        val error = value["error"]
        when {
            error is JsonObject && !value.containsKey("result") -> {
                val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length in 1..64 } ?: "PLUGIN_ERROR"
                response.completeExceptionally(PluginProcessException(code, "Plugin RPC returned an error"))
            }
            error == null && value.containsKey("result") -> response.complete(value["result"] ?: JsonNull)
            else -> {
                val invalid = PluginProcessException("PLUGIN_RPC_INVALID", "Plugin response must contain a result or error")
                response.completeExceptionally(invalid)
                throw invalid
            }
        }
    }

    private suspend fun handlePeerRequest(id: JsonElement, method: String, params: JsonObject) {
        val primitive = id as? JsonPrimitive
        if (primitive == null || id == JsonNull || (!primitive.isString && primitive.longOrNull == null) || id.toString().length > 128) {
            throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin host request id is invalid")
        }
        val invocation = synchronized(lock) {
            active ?: idle?.takeIf { method.removePrefix("host.") in IDLE_METHODS }
        }
        if (invocation == null) {
            replyError(id, "PLUGIN_CONTEXT_REQUIRED")
            return
        }
        if (!hostBudget.acquire()) {
            throw PluginProcessException("PLUGIN_HOST_BUSY", "Plugin host request limit reached")
        }
        val peerKey = id.toString()
        if (!peerIds.add(peerKey)) {
            hostBudget.release()
            throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin host request id is duplicated")
        }
        val child = CoroutineScope(scope.coroutineContext + invocation.job).launch {
            try {
                checkInvocation(invocation)
                val result = invocation.hostRequest(method, params)
                checkInvocation(invocation)
                writeLine(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }, invocation)
            } catch (cancelled: CancellationException) {
                if (synchronized(lock) { active === invocation || idle === invocation }) {
                    close(PluginProcessException("PLUGIN_CONTEXT_REQUIRED", "Plugin invocation context is no longer current"))
                }
            } catch (error: Exception) {
                if (synchronized(lock) { active === invocation || idle === invocation } && !isClosed) {
                    try {
                        val code = when (error) {
                            is PluginProcessException -> error.code
                            is PluginHostException -> error.code
                            else -> "HOST_ERROR"
                        }
                        replyError(id, code, invocation)
                    } catch (_: Exception) {
                        close(PluginProcessException("PLUGIN_WRITE_FAILED", "Plugin host response could not be written"))
                    }
                }
            }
        }
        child.invokeOnCompletion {
            peerIds.remove(peerKey)
            hostBudget.release()
        }
    }

    private suspend fun replyError(id: JsonElement, code: String, invocation: Invocation? = null) {
        writeLine(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code.take(64))
                put("message", "Plugin host request failed")
            })
        }, invocation)
    }

    private fun checkInvocation(invocation: Invocation) {
        invocation.job.ensureActive()
        if (synchronized(lock) { active !== invocation && idle !== invocation }) throw CancellationException("Plugin invocation ended")
        invocation.context.checkCurrent()
    }

    private suspend fun drainStderr(target: Process) {
        try {
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = target.errorStream.read(buffer)
                if (count < 0) break
                if (count == 0) throw PluginProcessException("PLUGIN_STREAM_STALLED", "Plugin stderr made no progress")
                total += count
                if (total > PLUGIN_MAX_STDERR_BYTES) throw PluginProcessException("PLUGIN_STDERR_LIMIT", "Plugin stderr exceeded the byte limit")
            }
        } catch (error: Exception) {
            if (!isClosed) close(error as? PluginProcessException ?: PluginProcessException("PLUGIN_STREAM_FAILED", "Plugin stderr failed"))
        }
    }

    override fun close() = close(PluginProcessException("PLUGIN_CLOSED", "Plugin process closed"))

    internal fun close(error: PluginProcessException) {
        synchronized(lock) {
            if (failure != null) return
            failure = error
            active = null
            idle = null
        }
        pending.entries.forEach { (id, response) -> if (pending.remove(id, response)) response.completeExceptionally(error) }
        scope.launch {
            val self = currentCoroutineContext()[Job]
            val children = owner.children.filter { it !== self }.toList()
            children.forEach { it.cancel() }
            synchronized(lock) { process }?.let { disposeProcess(it) }
            children.joinAll()
            synchronized(lock) { process = null }
            try { onClosed() } finally {
                stopped.complete(Unit)
                owner.cancel()
            }
        }
    }

    override suspend fun awaitClosed() { stopped.await() }

    private companion object {
        val IDLE_METHODS = setOf("sessionSource.setSessions", "sessionSource.setLoading", "sessionSource.setError",
            "sessionSource.setThinking", "sessionSource.setMessages", "sessionSource.pushEvent", "app.info", "app.isForeground",
            "log", "secrets.get", "secrets.set", "secrets.delete", "secrets.list")
    }

    private suspend fun disposeProcess(target: Process) {
        if (target is LocalRuntimeProcess) {
            runCatching { target.stop() }
            withTimeoutOrNull(10_000L) { target.awaitCleanup() }
            runCatching { target.outputStream.close() }
            runCatching { target.inputStream.close() }
            runCatching { target.errorStream.close() }
            return
        }
        runCatching { target.destroy() }
        runCatching { if (target.isAlive) target.destroyForcibly() }
        runCatching { target.outputStream.close() }
        runCatching { target.inputStream.close() }
        runCatching { target.errorStream.close() }
        while (runCatching { target.isAlive }.getOrDefault(false)) {
            runCatching { target.waitFor(250, TimeUnit.MILLISECONDS) }
            if (runCatching { target.isAlive }.getOrDefault(false)) delay(250)
        }
    }

}

internal class PluginBoundedLineReader(
    private val input: InputStream,
    private val maxLineBytes: Int = PLUGIN_MAX_LINE_BYTES,
    private val maxTotalBytes: Long = PLUGIN_MAX_OUTPUT_BYTES,
    private val checkCurrent: () -> Unit = {}
) {
    private val chunk = ByteArray(8192)
    private var position = 0
    private var length = 0
    private var total = 0L

    fun readLine(): String? {
        val output = ByteArrayOutputStream(minOf(maxLineBytes, 8192))
        while (true) {
            if (position == length) {
                checkCurrent()
                length = input.read(chunk)
                checkCurrent()
                position = 0
                if (length < 0) {
                    if (output.size() != 0) throw PluginProcessException("PLUGIN_RPC_TRUNCATED", "Plugin stdout ended inside a line")
                    return null
                }
                if (length == 0) throw PluginProcessException("PLUGIN_STREAM_STALLED", "Plugin stdout made no progress")
                total += length
                if (total > maxTotalBytes) throw PluginProcessException("PLUGIN_OUTPUT_LIMIT", "Plugin stdout exceeded the byte limit")
            }
            val value = chunk[position++].toInt() and 0xff
            if (value == '\n'.code) {
                val bytes = output.toByteArray()
                val size = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
                } catch (_: Exception) {
                    throw PluginProcessException("PLUGIN_RPC_INVALID", "Plugin stdout is not valid UTF-8")
                }
            }
            if (output.size() >= maxLineBytes) throw PluginProcessException("PLUGIN_LINE_LIMIT", "Plugin stdout line exceeded the byte limit")
            output.write(value)
        }
    }
}
