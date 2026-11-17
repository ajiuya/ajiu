package com.aicode.feature.agent.domain.session

internal class WeightedLruCache<K, V>(private val maxEntries: Int, private val maxBytes: Long) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    var bytes: Long = 0
        private set
    val size: Int get() = entries.size

    operator fun get(key: K): V? = entries[key]?.value

    fun put(key: K, value: V, weight: Long) {
        require(weight >= 0)
        remove(key)
        if (weight > maxBytes || maxEntries <= 0) return
        entries[key] = Entry(value, weight)
        bytes += weight
        while (entries.size > maxEntries || bytes > maxBytes) remove(entries.keys.first())
    }

    fun remove(key: K): V? = entries.remove(key)?.also { bytes -= it.bytes }?.value

    fun clear() {
        entries.clear()
        bytes = 0
    }
}
