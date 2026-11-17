package com.aicode.feature.agent.domain.root

import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class RootCommandManager internal constructor(
    private val repository: RootAccessSettingsRepository,
    private val rootProcessFactory: RootProcessFactory,
    private val scope: CoroutineScope,
    private val processDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(
        repository: RootAccessSettingsRepository,
        rootProcessFactory: RootProcessFactory
    ) : this(
        repository,
        rootProcessFactory,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        Dispatchers.IO
    )

    companion object {
        const val EXIT_FAILURE = -1
        const val EXIT_DISABLED = -2
        const val EXIT_APPROVAL_REQUIRED = -3
        const val EXIT_PERMISSION_UNAVAILABLE = -4
        const val EXIT_TIMEOUT = -1000
        const val EXIT_INTERRUPTED = -1001
        const val MAX_OUTPUT_CHARS = 24_000
        const val MAX_TIMEOUT_MS = 1_800_000L
        private const val POLL_INTERVAL_MS = 10L
    }

    private data class Permit(val sessionId: String, val command: String, val generation: Long)

    private class ManagedCommand(val permit: Permit) {
        var process: Process? = null
        var continuation: CancellableContinuation<RootCommandResult>? = null
        var worker: Job? = null
        @Volatile var result: RootCommandResult? = null
    }

    private val gate = Any()
    private val settingsMutex = Mutex()
    private val permits = mutableMapOf<String, Permit>()
    private val active = mutableSetOf<ManagedCommand>()
    private var observedState: RootAccessState? = null
    private var blocked = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.state.collect {
                synchronized(gate) { observeLocked(repository.state.value) }
            }
        }
    }

    val accessState get() = repository.state

    suspend fun accessSnapshot(): RootAccessState {
        repository.snapshot()
        return synchronized(gate) {
            val current = repository.state.value
            observeLocked(current)
            if (blocked) current.copy(enabled = false) else current
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settingsMutex.withLock {
            synchronized(gate) {
                blocked = true
                revokeAllLocked()
            }
            // 撤销后即使调用方取消，也必须把关闭状态持久化，写入失败时保持阻断。
            withContext(NonCancellable) {
                repository.setEnabled(enabled)
                repository.snapshot()
                synchronized(gate) {
                    observeLocked(repository.state.value)
                    blocked = false
                }
            }
        }
    }

    suspend fun authorize(sessionId: String, command: String, expectedGeneration: Long): String? {
        if (sessionId.isBlank() || command.isBlank() || !accessSnapshot().enabled) return null
        return synchronized(gate) {
            val current = repository.state.value
            observeLocked(current)
            if (blocked || !current.enabled || current.generation != expectedGeneration) {
                null
            } else {
                UUID.randomUUID().toString().also {
                    permits[it] = Permit(sessionId, command, current.generation)
                }
            }
        }
    }

    fun revokePermit(permitId: String) {
        synchronized(gate) { permits.remove(permitId) }
    }

    suspend fun execute(
        command: String,
        timeoutMs: Long,
        sessionId: String,
        permitId: String?
    ): RootCommandResult {
        accessSnapshot()
        val execution = synchronized(gate) {
            val current = repository.state.value
            observeLocked(current)
            val permit = permitId?.let { permits.remove(it) }
            if (blocked || !current.enabled) return RootCommandResult("", EXIT_DISABLED)
            if (permit == null || permit.sessionId != sessionId || permit.command != command ||
                permit.generation != current.generation
            ) {
                return RootCommandResult("", EXIT_APPROVAL_REQUIRED)
            }
            ManagedCommand(permit).also { active.add(it) }
        }
        return try {
            withTimeout(timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cause ->
                        finish(
                            execution,
                            RootCommandResult("", if (cause is TimeoutCancellationException) EXIT_TIMEOUT else EXIT_INTERRUPTED)
                        )
                    }
                    synchronized(gate) {
                        val result = execution.result
                        if (result != null) {
                            continuation.resume(result)
                        } else {
                            execution.continuation = continuation
                            val worker = scope.launch(processDispatcher, start = CoroutineStart.LAZY) {
                                runCommand(execution)
                            }
                            execution.worker = worker
                            worker.invokeOnCompletion { cause ->
                                if (cause != null) finish(execution, RootCommandResult("", EXIT_INTERRUPTED))
                            }
                            worker.start()
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            finish(execution, RootCommandResult("", EXIT_TIMEOUT))
            currentCoroutineContext().ensureActive()
            RootCommandResult("", EXIT_TIMEOUT)
        } catch (e: CancellationException) {
            finish(execution, RootCommandResult("", EXIT_INTERRUPTED))
            throw e
        }
    }

    private suspend fun runCommand(execution: ManagedCommand) {
        try {
            val process = synchronized(gate) {
                observeLocked(repository.state.value)
                if (execution.result != null) return
                val current = repository.state.value
                if (blocked || !current.enabled || current.generation != execution.permit.generation) {
                    finish(execution, RootCommandResult("", EXIT_DISABLED))
                    return
                }
                // 创建与登记共用关闭闸锁；关闭返回后，排队的旧许可不可能再启动。
                rootProcessFactory.create(execution.permit.command).also {
                    execution.process = it
                    observeLocked(repository.state.value)
                }
            }
            if (execution.result != null) return
            val output = CommandOutput()
            while (execution.result == null) {
                output.drain(process.inputStream)
                if (!process.isAlive) {
                    output.drain(process.inputStream, MAX_OUTPUT_CHARS * 4 + 4)
                    val text = output.build()
                    val exitCode = process.exitValue()
                    finish(execution, RootCommandResult(text, exitCode))
                    return
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: RootPermissionUnavailableException) {
            finish(execution, RootCommandResult("", EXIT_PERMISSION_UNAVAILABLE))
        } catch (e: CancellationException) {
            finish(execution, RootCommandResult("", EXIT_INTERRUPTED))
        } catch (e: Exception) {
            finish(execution, RootCommandResult("", EXIT_FAILURE))
        }
    }

    private fun observeLocked(next: RootAccessState) {
        if (observedState != next) {
            observedState = next
            revokeAllLocked()
        }
    }

    private fun revokeAllLocked() {
        permits.clear()
        active.toList().forEach { finish(it, RootCommandResult("", EXIT_INTERRUPTED)) }
    }

    private fun finish(execution: ManagedCommand, result: RootCommandResult) {
        synchronized(gate) {
            if (execution.result != null) return
            execution.result = result
            active.remove(execution)
            execution.process?.let { runCatching { rootProcessFactory.destroy(it) } }
            execution.continuation?.resume(result)
            execution.worker?.cancel()
        }
    }

    private class CommandOutput {
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private val bytes = ByteBuffer.allocate(4096)
        private val chars = CharBuffer.allocate(4096)
        private val text = StringBuilder()

        fun drain(input: InputStream, maxBytes: Int = 16_384) {
            // 不等待 EOF：脱离的子进程可能持有管道；关闭和取消不能依赖阻塞读取结束。
            var remaining = maxBytes
            while (remaining > 0) {
                val available = input.available()
                if (available <= 0) return
                val count = minOf(available, bytes.remaining(), remaining)
                val read = input.read(bytes.array(), bytes.position(), count)
                if (read <= 0) return
                bytes.position(bytes.position() + read)
                remaining -= read
                decode(false)
            }
        }

        fun build(): String {
            decode(true)
            return text.toString()
        }

        private fun decode(endOfInput: Boolean) {
            bytes.flip()
            do {
                chars.clear()
                val result = decoder.decode(bytes, chars, endOfInput)
                chars.flip()
                val count = minOf(chars.remaining(), MAX_OUTPUT_CHARS - text.length)
                text.append(chars, 0, count)
            } while (result.isOverflow)
            bytes.compact()
        }
    }
}
