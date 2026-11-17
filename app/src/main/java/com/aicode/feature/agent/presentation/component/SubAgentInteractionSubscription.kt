package com.aicode.feature.agent.presentation.component

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

internal data class SubAgentPageVisibility(
    val expanded: Boolean,
    val lifecycleActive: Boolean,
    val hasHost: Boolean,
) {
    val shouldCollect: Boolean get() = expanded && lifecycleActive && hasHost
}

// The factory, not just collection, is gated; every acquisition has a matching release.
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> subAgentVisiblePageFlow(
    visibility: Flow<SubAgentPageVisibility>,
    initial: T,
    source: () -> Flow<T>,
    onFailure: (T, Exception) -> T,
    onActive: (Boolean) -> Unit = {},
): Flow<T> = visibility.distinctUntilChanged().flatMapLatest { gate ->
    if (!gate.shouldCollect) flowOf(initial) else flow {
        var lastPage = initial
        try {
            onActive(true)
            source().collect { page ->
                lastPage = page
                emit(page)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(onFailure(lastPage, failure))
        } finally {
            onActive(false)
        }
    }
}.buffer(0)
