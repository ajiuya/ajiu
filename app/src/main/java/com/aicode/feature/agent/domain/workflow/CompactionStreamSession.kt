package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.context.CompactionStreamState
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CompactionStreamUpdate(
    val state: CompactionStreamState,
    val attempts: Int,
    val maxAttempts: Int,
    val receivedCharacters: Long,
    val elapsedMs: Long,
    val lastErrorCode: String?,
)

internal data class CompactionStreamAttempt(
    val response: AIResponse?,
    val outputTokens: Int,
    val durationMs: Long,
    val ttfbMs: Long?,
    val errorCode: String?,
    val createdAt: Long,
    val retryCount: Int,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CompactionStreamSession(
    val policy: CompactionStreamPolicy = CompactionStreamPolicy(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val started = now()
    var attempts: Int = 0
        private set
    val elapsedMs: Long get() = (now() - started).coerceAtLeast(0)
    private data class Done<T>(val value: T)
    private var work: CompactionCheckpoints.Work? = null
    private var onCheckpoint: () -> Unit = {}
    private var blockIndex = 0
    private var pendingBlock: CompactionCheckpoints.Block? = null
    val replaying: Boolean get() = work?.block(blockIndex) != null

    fun restore(checkpoint: CompactionCheckpoints.Work, onUpdate: () -> Unit) {
        check(attempts == 0 && work == null)
        work = checkpoint
        attempts = checkpoint.attempts
        onCheckpoint = onUpdate
    }

    fun materialBudget(default: Int): Int = work?.block(blockIndex)?.materialBudget
        ?: work?.failure?.takeIf { it.index == blockIndex }?.materialBudget?.takeIf { it > 0 }
        ?: default

    fun commitBlock() {
        val completed = pendingBlock ?: return
        requireNotNull(work).complete(blockIndex, completed)
        blockIndex++
        pendingBlock = null
        onCheckpoint()
    }

    suspend fun <T> withDeadline(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val remaining = policy.totalTimeoutMs - elapsedMs
        if (remaining <= 0) throw CompactionStreamException("compaction_total_timeout")
        val done = withTimeoutOrNull(remaining) { Done(block()) }
            ?: throw CompactionStreamException("compaction_total_timeout")
        return done.value
    }

    suspend fun summarize(
        stream: () -> Flow<AIStreamChunk>,
        onUpdate: suspend (CompactionStreamUpdate) -> Unit,
        onAttempt: suspend (CompactionStreamAttempt) -> Unit,
        onRetry: suspend (CompactionStreamException) -> Unit = {},
        requestFingerprint: (() -> String)? = null,
        materialBudget: () -> Int = { 0 },
    ): String {
        currentCoroutineContext().ensureActive()
        work?.block(blockIndex)?.let { completed ->
            if (completed.request != requestFingerprint?.invoke()) {
                throw CompactionStreamException("compaction_checkpoint_mismatch")
            }
            blockIndex++
            return completed.summary
        }
        val previousFailure = work?.failure?.takeIf { it.index == blockIndex }
        var retryableFailures = previousFailure?.retryable ?: 0
        var truncationFailures = previousFailure?.truncated ?: 0
        if (retryableFailures >= policy.attemptsPerBlock || truncationFailures >= policy.truncationAttemptsPerBlock) {
            throw CompactionStreamException(requireNotNull(previousFailure).code)
        }
        var index = retryableFailures + truncationFailures
        while (true) {
            currentCoroutineContext().ensureActive()
            if (elapsedMs >= policy.totalTimeoutMs) throw CompactionStreamException("compaction_total_timeout")
            if (attempts >= policy.maxAttempts) throw CompactionStreamException("compaction_attempt_limit")
            val attemptStart = now()
            val wall = System.currentTimeMillis()
            var response: AIResponse? = null
            var outputTokens = 0
            var received = 0L
            var firstContent: Long? = null
            var lastProgress = attemptStart
            var lastEmission = Long.MIN_VALUE
            var errorCode: String? = null
            var failure: CompactionStreamException? = null
            attempts++
            work?.attempts = attempts
            onCheckpoint()
            suspend fun update(state: CompactionStreamState, error: String? = null, force: Boolean = false) {
                val time = now()
                if (force || lastEmission == Long.MIN_VALUE || time - lastEmission >= policy.progressIntervalMs) {
                    lastEmission = time
                    onUpdate(CompactionStreamUpdate(state, attempts, policy.maxAttempts, received, elapsedMs, error))
                }
            }
            try {
                update(CompactionStreamState.WAITING, force = true)
                currentCoroutineContext().ensureActive()
                coroutineScope {
                    // 零缓冲避免积压的旧数据掩盖真实停滞。
                    val channel = stream().buffer(0).produceIn(this)
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val remaining = (if (firstContent == null) policy.firstChunkTimeoutMs else policy.idleTimeoutMs) -
                                (now() - lastProgress)
                            val timeoutCode = if (firstContent == null) "compaction_first_chunk_timeout" else "compaction_idle_timeout"
                            if (remaining <= 0) throw CompactionStreamException(timeoutCode, true)
                            // 原子选择数据或心跳，不能用可取消 receive 的超时丢掉同刻到达的数据。
                            val next = select<ChannelResult<AIStreamChunk>?> {
                                channel.onReceiveCatching { it }
                                onTimeout(minOf(remaining, policy.heartbeatIntervalMs)) { null }
                            }
                            if (next == null) {
                                if (now() - lastProgress >= if (firstContent == null) policy.firstChunkTimeoutMs else policy.idleTimeoutMs) {
                                    throw CompactionStreamException(timeoutCode, true)
                                }
                                update(if (firstContent == null) CompactionStreamState.WAITING else CompactionStreamState.RECEIVING)
                                continue
                            }
                            if (next.isClosed) {
                                next.exceptionOrNull()?.let { throw it }
                                break
                            }
                            when (val chunk = next.getOrThrow()) {
                                is AIStreamChunk.TextDelta, is AIStreamChunk.ReasoningDelta -> {
                                    val text = when (chunk) {
                                        is AIStreamChunk.TextDelta -> chunk.text
                                        is AIStreamChunk.ReasoningDelta -> chunk.text
                                        else -> error("unreachable")
                                    }
                                    if (text.isNotEmpty()) {
                                        if (response != null) throw CompactionStreamException("compaction_invalid_response")
                                        val first = firstContent == null
                                        lastProgress = now()
                                        if (first) firstContent = lastProgress
                                        if (chunk is AIStreamChunk.TextDelta) received += text.length.toLong()
                                        update(CompactionStreamState.RECEIVING, force = first)
                                    }
                                }
                                is AIStreamChunk.Final -> {
                                    if (response != null) throw CompactionStreamException("compaction_invalid_response")
                                    response = chunk.response
                                    outputTokens = chunk.response.outputTokens
                                    CompactionStreamErrors.validate(chunk.response)
                                }
                                is AIStreamChunk.ToolCallDeclared, is AIStreamChunk.ToolCallArgumentsDelta ->
                                    throw CompactionStreamException("compaction_tools")
                                is AIStreamChunk.Retrying, is AIStreamChunk.KeySwitched ->
                                    throw CompactionStreamException("compaction_invalid_response")
                            }
                        }
                    } finally {
                        channel.cancel()
                    }
                }
                val complete = response ?: throw CompactionStreamException("compaction_no_final")
                currentCoroutineContext().ensureActive()
                received = maxOf(received, complete.content.length.toLong())
                update(CompactionStreamState.NONE, force = true)
                currentCoroutineContext().ensureActive()
                if (work != null && requestFingerprint != null) {
                    pendingBlock = CompactionCheckpoints.Block(requestFingerprint(), materialBudget(), complete.content)
                }
                return complete.content
            } catch (cancelled: CancellationException) {
                errorCode = if (elapsedMs >= policy.totalTimeoutMs) "compaction_total_timeout" else "compaction_cancelled"
                if (errorCode == "compaction_total_timeout") {
                    work?.failure = CompactionCheckpoints.Failure(blockIndex, materialBudget(), retryableFailures + 1,
                        truncationFailures, errorCode)
                    onCheckpoint()
                }
                throw cancelled
            } catch (error: Exception) {
                failure = CompactionStreamErrors.classify(error)
                errorCode = failure.code
                if (failure.code == "compaction_truncated") truncationFailures++ else retryableFailures++
                work?.failure = CompactionCheckpoints.Failure(blockIndex, materialBudget(), retryableFailures,
                    truncationFailures, failure.code)
                onCheckpoint()
            } finally {
                val record = CompactionStreamAttempt(response, outputTokens, (now() - attemptStart).coerceAtLeast(0),
                    firstContent?.let { (it - attemptStart).coerceAtLeast(0) }, errorCode, wall, index)
                withContext(NonCancellable) {
                    try {
                        if (withTimeoutOrNull(policy.recordTimeoutMs) { onAttempt(record); true } != true) {
                            FileLogger.w("CompactionStream", "记录压缩调用统计超时")
                        }
                    } catch (_: Exception) {
                        FileLogger.w("CompactionStream", "记录压缩调用统计失败")
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val error = requireNotNull(failure)
            val exhausted = if (error.code == "compaction_truncated") {
                truncationFailures >= policy.truncationAttemptsPerBlock
            } else {
                retryableFailures >= policy.attemptsPerBlock
            }
            work?.failure = CompactionCheckpoints.Failure(blockIndex, materialBudget(), retryableFailures,
                truncationFailures, error.code)
            onCheckpoint()
            if (!error.retryable || exhausted) throw error
            if (attempts >= policy.maxAttempts) throw CompactionStreamException("compaction_attempt_limit")
            received = 0
            update(CompactionStreamState.RETRYING, error.code, force = true)
            delay(policy.retryDelayMs)
            currentCoroutineContext().ensureActive()
            if (elapsedMs >= policy.totalTimeoutMs) throw CompactionStreamException("compaction_total_timeout")
            onRetry(error)
            work?.failure = CompactionCheckpoints.Failure(blockIndex, materialBudget(), retryableFailures,
                truncationFailures, error.code)
            onCheckpoint()
            index++
        }
    }
}
