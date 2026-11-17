package com.aicode.feature.workspace.domain.remote

import kotlinx.coroutines.channels.Channel

internal class SyncPendingQueue(private val capacity: Int) {
    data class Batch(val paths: List<String>, val reconcile: Boolean)
    private val paths = LinkedHashSet<String>()
    private var reconcile = false
    val wakeup = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun offer(path: String) {
        if (path !in paths && paths.size >= capacity) reconcile = true
        else paths.add(path)
        wakeup.trySend(Unit)
    }

    @Synchronized fun requestReconcile() {
        reconcile = true
        wakeup.trySend(Unit)
    }

    @Synchronized fun take(limit: Int): Batch {
        val ready = paths.take(limit.coerceAtLeast(1))
        paths.removeAll(ready.toSet())
        val batch = Batch(ready, reconcile)
        reconcile = false
        if (paths.isNotEmpty()) wakeup.trySend(Unit)
        return batch
    }
}
