package com.aicode.feature.agent.domain.tool.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One WebView instance; bookkeeping is confined to Main, including bridge completions. */
internal class BrowserDocumentLifetime(
    val pendingJsCalls: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap()
) {
    private val pendingCallbacks = mutableSetOf<CompletableDeferred<*>>()
    private val operationJobs = mutableSetOf<Job>()
    private val captureMutex = Mutex()
    var generation = 0L
        private set
    var destroyed = false
        private set

    fun isCurrent(expectedGeneration: Long): Boolean = !destroyed && generation == expectedGeneration

    fun invalidate() {
        generation++
        pendingJsCalls.values.forEach { it.cancel() }
        pendingJsCalls.clear()
        pendingCallbacks.toList().forEach { it.cancel() }
        pendingCallbacks.clear()
        operationJobs.toList().forEach { it.cancel() }
        operationJobs.clear()
    }

    fun close() {
        destroyed = true
        invalidate()
    }

    suspend fun check(expectedGeneration: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(expectedGeneration)) throw CancellationException("Browser document changed")
    }

    fun <T> callback(expectedGeneration: Long): CompletableDeferred<T> {
        val deferred = CompletableDeferred<T>()
        if (isCurrent(expectedGeneration)) pendingCallbacks.add(deferred) else deferred.cancel()
        return deferred
    }

    fun <T> complete(expectedGeneration: Long, deferred: CompletableDeferred<T>, value: T) {
        if (isCurrent(expectedGeneration) && pendingCallbacks.remove(deferred)) deferred.complete(value)
    }

    fun release(deferred: CompletableDeferred<*>) {
        pendingCallbacks.remove(deferred)
        deferred.cancel()
    }

    suspend fun <T> operation(block: suspend (Long) -> T): T = coroutineScope {
        val expectedGeneration = generation
        val job = currentCoroutineContext()[Job]!!
        operationJobs.add(job)
        try {
            check(expectedGeneration)
            val result = block(expectedGeneration)
            check(expectedGeneration)
            result
        } finally {
            operationJobs.remove(job)
        }
    }

    suspend fun <T> capture(block: suspend (Long) -> T): T = operation { expectedGeneration ->
        captureMutex.withLock {
            check(expectedGeneration)
            block(expectedGeneration)
        }
    }
}

internal suspend fun <T> captureBrowserPreviewFrame(block: suspend () -> T): T? =
    try {
        block()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }
