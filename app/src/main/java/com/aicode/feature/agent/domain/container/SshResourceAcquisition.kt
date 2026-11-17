package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// withContext 的 prompt cancellation 可能丢弃已打开但尚未交付的句柄。
internal suspend fun <T : Any> acquireSshResource(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    close: (T) -> Unit,
    open: () -> T
): T {
    var owned: T? = null
    return try {
        withContext(dispatcher) {
            open().also { owned = it; currentCoroutineContext().ensureActive() }
        }
    } catch (failure: Throwable) {
        owned?.let { runCatching { close(it) }.exceptionOrNull()?.let(failure::addSuppressed) }
        throw failure
    }
}

internal inline fun <T : AutoCloseable, R> withSshSetup(resource: T, setup: (T) -> R): R =
    try { setup(resource) } catch (failure: Throwable) {
        runCatching { resource.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
