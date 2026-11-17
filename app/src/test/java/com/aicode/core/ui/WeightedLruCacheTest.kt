package com.aicode.core.ui

import org.junit.Assert.*
import org.junit.Test

class WeightedLruCacheTest {
    @Test
    fun entryAndByteLimitsApplyTogetherWithAccessOrder() {
        val cache = WeightedLruCache<String, String>(2, 9L) { _, value -> value.length.toLong() }
        cache.put("a", "aaa")
        cache.put("b", "bbb")
        assertEquals("aaa", cache["a"])
        cache.put("c", "ccc")
        assertNull(cache["b"])
        assertEquals(2, cache.stats().entries)
        cache.put("d", "ddddddd")
        assertNull(cache["a"])
        assertNull(cache["c"])
        assertEquals(7L, cache.stats().weight)
    }

    @Test
    fun oversizedValueDoesNotDisplaceWarmEntriesOrMutateDeliveredReferences() {
        val cache = WeightedLruCache<String, StringBuilder>(4, 8L) { _, value -> value.length.toLong() }
        val visible = StringBuilder("visible")
        cache.put("visible", visible)
        val oversized = StringBuilder("x".repeat(20))
        cache.put("oversized", oversized)
        assertNull(cache["oversized"])
        assertSame(visible, cache["visible"])
        assertEquals(20, oversized.length)
        cache.put("new", StringBuilder("new"))
        assertNull(cache["visible"])
        assertEquals("visible", visible.toString())
    }

    @Test
    fun replacementSubtractsOldWeightAndBestPrefixDoesNotCreateAnAliasEntry() {
        val cache = WeightedLruCache<String, String>(5, 20L) { key, value -> (key.length + value.length).toLong() }
        cache.put("a", "old")
        cache.put("a", "newer")
        cache.put("abc", "result")
        assertEquals(15L, cache.stats().weight)
        assertEquals("result", cache.best { if ("abcdef".startsWith(it)) it.length else -1 })
        assertNull(cache.best { -1 })
        assertEquals(2, cache.stats().entries)
    }
}
