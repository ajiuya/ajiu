package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.EnrichedHttpException
import com.aicode.feature.agent.domain.provider.StreamApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class CompactionStreamPolicy(
    val attemptsPerBlock: Int = 2,
    val truncationAttemptsPerBlock: Int = 6,
    val maxAttempts: Int = 32,
    val materialTokens: Int = CompactionSummaryPolicy.MAX_MATERIAL_TOKENS,
    val firstChunkTimeoutMs: Long = 120_000,
    val idleTimeoutMs: Long = 120_000,
    val totalTimeoutMs: Long = 35 * 60_000,
    val progressIntervalMs: Long = 250,
    val heartbeatIntervalMs: Long = 1_000,
    val recordTimeoutMs: Long = 2_000,
    val retryDelayMs: Long = 1_000,
) {
    init {
        require(attemptsPerBlock in 1..2 && truncationAttemptsPerBlock in 1..6 && maxAttempts in 1..32 && materialTokens > 0)
        require(firstChunkTimeoutMs > 0 && idleTimeoutMs > 0 && totalTimeoutMs > 0)
        require(progressIntervalMs > 0 && heartbeatIntervalMs > 0 && recordTimeoutMs > 0 && retryDelayMs >= 0)
    }
}

/** 不携带上游正文或异常 cause，message 只允许固定安全码。 */
internal class CompactionStreamException(val code: String, val retryable: Boolean = false) : Exception(code)

internal object CompactionStreamErrors {
    fun classify(error: Throwable): CompactionStreamException {
        if (error is CompactionStreamException) return error
        if (error is CancellationException) return CompactionStreamException("compaction_cancelled")
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val http = chain.firstNotNullOfOrNull {
            when (it) {
                is EnrichedHttpException -> it.statusCode
                is HttpException -> it.code()
                else -> null
            }
        }
        if (http != null) return CompactionStreamException(
            "compaction_http_$http", http == 408 || http == 429 || http == 499 || http in 500..599
        )
        chain.filterIsInstance<StreamApiException>().firstOrNull()?.let {
            return when (it.code?.lowercase()) {
                "rate_limit_exceeded", "rate_limit_error", "too_many_requests" ->
                    CompactionStreamException("compaction_http_429", true)
                "server_error", "internal_error", "server_is_overloaded", "overloaded" ->
                    CompactionStreamException("compaction_http_503", true)
                else -> CompactionStreamException("compaction_invalid_response")
            }
        }
        if (chain.any { it is IOException }) return CompactionStreamException("compaction_io", true)
        return CompactionStreamException("compaction_failed")
    }

    fun validate(response: AIResponse) {
        if (response.isTruncated) throw CompactionStreamException("compaction_truncated", retryable = true)
        val code = when {
            response.isAborted -> "compaction_aborted"
            response.toolCalls.isNotEmpty() -> "compaction_tools"
            response.images.isNotEmpty() -> "compaction_images"
            response.content.isBlank() -> "compaction_empty"
            response.stopReason.isNullOrBlank() -> "compaction_missing_stop"
            response.stopReason !in setOf("stop", "end_turn", "STOP", "completed") -> "compaction_invalid_response"
            else -> null
        }
        if (code != null) throw CompactionStreamException(code)
    }
}

internal class CompactionStreamOwners {
    private class Owner(val job: Job, var cancellable: Boolean = true)
    private val jobs = ConcurrentHashMap<String, Owner>()

    suspend fun <T> run(sessionId: String?, block: suspend () -> T): T = coroutineScope {
        currentCoroutineContext().ensureActive()
        val owner = Owner(currentCoroutineContext().job)
        if (sessionId != null && jobs.putIfAbsent(sessionId, owner) != null) {
            throw CompactionStreamException("compaction_busy")
        }
        try {
            block()
        } finally {
            if (sessionId != null) jobs.remove(sessionId, owner)
        }
    }

    fun isActive(sessionId: String): Boolean = jobs.containsKey(sessionId)

    fun cancel(sessionId: String): Boolean {
        var cancelled = false
        // 与关闭取消位串行，避免检查后延迟到原子保存阶段才取消。
        jobs.computeIfPresent(sessionId) { _, owner ->
            if (owner.cancellable && owner.job.isActive) {
                cancelled = true
                owner.job.cancel(CancellationException("compaction_cancelled"))
            }
            owner
        }
        return cancelled
    }

    fun finishCancelable(sessionId: String?, owner: Job) {
        if (sessionId != null) jobs.computeIfPresent(sessionId) { _, entry ->
            if (entry.job === owner) entry.cancellable = false
            entry
        }
    }
}

internal class CompactionStreamProviderSettings(private val provider: AIProvider) {
    val outputLimit = provider.maxOutputTokens
    private val requestOutputLimit = provider.requestOutputTokenLimit
    private val retries = provider.maxNetworkRetries
    private val switcher = provider.keySwitcher
    private val firstByte = provider.firstByteTimeoutMs
    private val idle = provider.streamIdleTimeoutMs

    fun apply(output: Int, policy: CompactionStreamPolicy) {
        provider.maxOutputTokens = output
        provider.requestOutputTokenLimit = output
        provider.maxNetworkRetries = 0
        provider.keySwitcher = null
        provider.firstByteTimeoutMs = policy.firstChunkTimeoutMs
        provider.streamIdleTimeoutMs = policy.idleTimeoutMs
    }

    fun restore() {
        provider.maxOutputTokens = outputLimit
        provider.requestOutputTokenLimit = requestOutputLimit
        provider.maxNetworkRetries = retries
        provider.keySwitcher = switcher
        provider.firstByteTimeoutMs = firstByte
        provider.streamIdleTimeoutMs = idle
    }
}
