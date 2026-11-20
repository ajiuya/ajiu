package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun currentVersionOrdering_isPreservedForAllPairs() {
        val versions = listOf(
            "1.12.9",
            "1.13.0-dev.2+old",
            "1.13.0-dev.10+new",
            "1.13.0-rc2",
            "1.13.0-rc10",
            "1.13.0",
            "1.13.1-dev.1",
            "2.0.0"
        )
        versions.forEachIndexed { firstIndex, first ->
            versions.forEachIndexed { secondIndex, second ->
                val comparison = compareVersions(first, second)
                assertEquals(
                    "$first versus $second",
                    firstIndex.compareTo(secondIndex),
                    comparison.compareTo(0)
                )
                assertEquals(firstIndex <= secondIndex, isUpToDate(first, second))
            }
        }
    }

    @Test
    fun prereleaseNumbers_useNumericOrderingForMatchingIdentifiers() {
        listOf("rc", "RC", "dev.", "\u03b2").forEach { prefix ->
            assertTrue(compareVersions("1.13.0-${prefix}10", "1.13.0-${prefix}2") > 0)
            assertTrue(compareVersions("1.13.0-${prefix}2", "1.13.0-${prefix}10") < 0)
        }
    }

    @Test
    fun differentPrereleaseIdentifiers_keepLexicographicAndCaseSensitiveOrdering() {
        assertTrue(compareVersions("1.13.0-rc2", "1.13.0-dev.10") > 0)
        assertTrue(compareVersions("1.13.0-RC2", "1.13.0-rc2") < 0)
        assertTrue(compareVersions("1.13.0-\u03b22", "1.13.0-rc2") > 0)
    }

    @Test
    fun buildMetadata_doesNotChangeVersionPrecedence() {
        listOf("1.13.0", "1.13.0-rc2", "1.13.0-dev.10").forEach { version ->
            assertEquals(0, compareVersions("$version+old", "$version+new"))
            assertEquals(0, compareVersions(version, "$version+new"))
            assertEquals(0, compareVersions("$version+old", version))
        }
        assertTrue(compareVersions("1.13.0+release", "1.13.0-rc10+candidate") > 0)
    }

    @Test
    fun parseVersionTag_keepsExistingWhitespaceAndUnicodeHandling() {
        listOf("", " ", "\t\n", "\u2003", "\u00a0").forEach { tag ->
            assertNull(parseVersionTag(tag))
        }
        assertEquals("1.13.0-rc10+build.2", parseVersionTag("\u2003v1.13.0-rc10+build.2\u2003"))
        assertEquals("1.13.0-\u03b22", parseVersionTag("v1.13.0-\u03b22 extra metadata"))
    }

    @Test
    fun missingBaseSegments_stillCompareAsZero() {
        assertEquals(0, compareVersions("1.13", "1.13.0"))
        assertEquals(0, compareVersions("1.13.0.0", "1.13.0"))
    }
}
