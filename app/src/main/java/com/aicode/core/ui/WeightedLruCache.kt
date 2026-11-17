package com.aicode.core.ui

internal class WeightedLruCache<K : Any, V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weigh: (K, V) -> Long,
) {
    private data class Entry<V>(val value: V, val weight: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var weight = 0L

    init {
        require(maxEntries > 0 && maxWeight > 0)
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        if (entries[key]?.value === value) return
        val bytes = weigh(key, value).coerceAtLeast(1L)
        entries.remove(key)?.let { weight -= it.weight }
        if (bytes > maxWeight) return
        entries[key] = Entry(value, bytes)
        weight += bytes
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || weight > maxWeight) {
            weight -= iterator.next().value.weight
            iterator.remove()
        }
    }

    @Synchronized
    fun best(score: (K) -> Int): V? {
        var bestScore = -1
        var best: V? = null
        for ((key, entry) in entries) {
            val candidate = score(key)
            if (candidate > bestScore) {
                bestScore = candidate
                best = entry.value
            }
        }
        return best
    }

    data class Stats(val entries: Int, val weight: Long)

    @Synchronized
    fun stats(): Stats = Stats(entries.size, weight)
}
