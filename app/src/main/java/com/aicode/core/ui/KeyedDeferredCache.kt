package com.aicode.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 淘汰只释放缓存引用；已交给 AST、painter 或其它消费者的对象仍由消费者持有。 */
internal class KeyedDeferredCache<K : Any, V>(
    private val scope: CoroutineScope,
    maxEntries: Int,
    private val retain: (V) -> Boolean = { true },
    maxWeight: Long = Long.MAX_VALUE,
    weigh: (K, V) -> Long = { _, _ -> 1L },
) {
    private class Flight<V>(val task: Deferred<V>, var consumers: Int = 0)
    private val lock = Any()
    private val completed = WeightedLruCache(maxEntries, maxWeight, weigh)
    private val flights = HashMap<K, Flight<V>>()

    fun peek(key: K): V? = synchronized(lock) { completed[key] }

    suspend fun get(key: K, load: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(lock) {
            completed[key]?.let { return it }
            (flights[key] ?: createFlight(key, load)).also { it.consumers++ }
        }
        try {
            flight.task.start()
            return flight.task.await()
        } finally {
            synchronized(lock) {
                flight.consumers--
                if (flight.consumers == 0 && !flight.task.isCompleted) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.task.cancel()
                }
            }
        }
    }

    private fun createFlight(key: K, load: suspend () -> V): Flight<V> {
        lateinit var flight: Flight<V>
        val task = scope.async(start = CoroutineStart.LAZY) {
            val result = load()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                // 最后一个消费者可能在不可中断的解码/解析返回前离开。
                if (flights[key] === flight && flight.consumers > 0 && retain(result)) {
                    completed.put(key, result)
                }
            }
            result
        }
        flight = Flight(task)
        flights[key] = flight
        task.invokeOnCompletion {
            synchronized(lock) {
                if (flights[key] === flight) flights.remove(key)
            }
        }
        return flight
    }

    data class Stats(val entries: Int, val completedWeight: Long, val inFlight: Int, val consumers: Int)

    fun stats(): Stats = synchronized(lock) {
        val cached = completed.stats()
        Stats(cached.entries, cached.weight, flights.size, flights.values.sumOf { it.consumers })
    }
}
