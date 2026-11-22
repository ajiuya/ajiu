package com.aicode.feature.plugin.domain

import org.junit.Assert.*
import org.junit.Test

class SemVerTest {
    @Test fun strictVersionsRejectLeadingZerosAndOverflow() {
        for (value in listOf("1", "1.0", "01.0.0", "1.0.0-01", "2147483648.0.0", "1.0.0-", "1.0.0+")) {
            assertNull(value, SemVer.parse(value))
        }
    }
    @Test fun prereleaseOrderingMatchesNumericThenTextThenStable() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.2", "1.0.0-alpha.10", "1.0.0-beta", "1.0.0-rc.1", "1.0.0")
            .map { requireNotNull(SemVer.parse(it)) }
        assertEquals(versions, versions.reversed().sorted())
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1.0.0+build.1"))
    }
    @Test fun rangeComparisonCaretAndTildeMatchExpectedBounds() {
        for ((range, yes, no) in listOf(Triple("^1.2.3", "1.9.9", "2.0.0"), Triple("^0.2.3", "0.2.9", "0.3.0"),
            Triple("^0.0.3", "0.0.3", "0.0.4"), Triple("~1.2.3", "1.2.9", "1.3.0"), Triple(">=1.0.0 <2.0.0", "1.5.0", "2.0.0"))) {
            val parsed = requireNotNull(SemVerRange.parse(range))
            assertTrue(range, parsed.matches(requireNotNull(SemVer.parse(yes))))
            assertFalse(range, parsed.matches(requireNotNull(SemVer.parse(no))))
        }
    }
    @Test fun rangeUnknownSyntaxAndUpperBoundOverflowAreRejected() {
        for (range in listOf("", "*", "1.x", "1.0.0 || 2.0.0", "1.0.0 - 2.0.0", "^2147483647.0.0", "~1.2147483647.0")) {
            assertNull(range, SemVerRange.parse(range))
        }
    }
    @Test fun localizedFallbackAndMapsAreImmutable() {
        val mutable = linkedMapOf("zh" to "ZH", "en" to "EN", "default" to "Fallback")
        val text = LocalizedText(mutable)
        mutable["zh"] = "changed"
        assertEquals("ZH", text.resolve("zh-TW"))
        assertEquals("Fallback", text.resolve("fr"))
        assertThrows(UnsupportedOperationException::class.java) { (text.values as MutableMap<String, String>)["zh"] = "bad" }
    }
}
