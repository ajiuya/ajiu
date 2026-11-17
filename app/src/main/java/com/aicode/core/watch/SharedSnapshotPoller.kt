package com.aicode.core.watch

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class SharedSnapshotPoller<K, S : Any>(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val snapshot: (K) -> S,
    private val onError: (Throwable) -> Unit = {}
) {
    private val lock = Any()
    private class Consumer<S>(val accept: (S, S) -> Unit) {
        val closed = AtomicBoolean()
    }
    private inner class Group {
        val consumers = LinkedHashSet<Consumer<S>>()
        var job: Job? = null
    }
    private val groups = HashMap<K, Group>()

    fun subscribe(key: K, onChange: (S, S) -> Unit): Closeable {
        val consumer = Consumer(onChange)
        val group = synchronized(lock) {
            groups.getOrPut(key) { Group() }.also { group ->
                group.consumers.add(consumer)
                if (group.job == null) group.job = scope.launch {
                    var previous: S? = null
                    while (isActive) {
                        try {
                            val current = snapshot(key)
                            previous?.let { before ->
                                val consumers = synchronized(lock) { group.consumers.toList() }
                                for (target in consumers) {
                                    if (target.closed.get()) continue
                                    try {
                                        target.accept(before, current)
                                    } catch (error: Exception) {
                                        // A consumer's cancellation is not cancellation of the shared scan.
                                        if (error is CancellationException && !isActive) throw error
                                        onError(error)
                                    }
                                }
                            }
                            previous = current
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            onError(error)
                        }
                        delay(intervalMs)
                    }
                }
            }
        }
        return Closeable {
            if (consumer.closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    group.consumers.remove(consumer)
                    if (group.consumers.isEmpty()) {
                        if (groups[key] === group) groups.remove(key)
                        group.job?.cancel()
                    }
                }
            }
        }
    }
}
