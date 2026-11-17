package com.aicode.feature.agent.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WeightedLruCacheTest {
    @Test
    fun entryAndByteEvictionUseTheSameAccessOrderedLedger() {
        val cache = WeightedLruCache<String, String>(2, 10)
        cache.put("a", "A", 4)
        cache.put("b", "B", 4)
        assertEquals("A", cache["a"])
        cache.put("c", "C", 4)
        assertNull(cache["b"])
        assertEquals(8L, cache.bytes)
        cache.put("a", "new A", 8)
        assertNull(cache["c"])
        assertEquals("new A", cache["a"])
        assertEquals(8L, cache.bytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun oversizeResultIsNotRetainedAndDoesNotEvictTheWarmWorkingSet() {
        val cache = WeightedLruCache<String, Any>(3, 10)
        val warm = Any()
        val large = Any()
        cache.put("warm", warm, 4)
        cache.put("large", large, 11)
        assertNull(cache["large"])
        assertSame(warm, cache["warm"])
        assertEquals(4L, cache.bytes)
        cache.put("warm", large, 12)
        assertNull(cache["warm"])
        assertEquals(0L, cache.bytes)
    }

    @Test
    fun replacementRemovalAndClearDoNotLeakWeight() {
        val cache = WeightedLruCache<String, String>(12, 100)
        repeat(1_000) { index -> cache.put("key", "$index", 3) }
        assertEquals(3L, cache.bytes)
        assertEquals("999", cache.remove("key"))
        assertEquals(0L, cache.bytes)
        cache.put("a", "A", 5)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)
    }
}
