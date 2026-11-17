package com.aicode.feature.agent.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextUsageBreakdownTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun emptyAndPartialSnapshotsUseZeroDefaults() {
        assertEquals(ContextUsageBreakdown(), json.decodeFromString<ContextUsageBreakdown>("{}"))
        assertEquals(
            ContextUsageBreakdown(systemTokens = 10),
            json.decodeFromString<ContextUsageBreakdown>("""{"systemTokens":10,"futureTokens":99}""")
        )
    }

    @Test
    fun roundtripPreservesAllEstimatesWithoutSerializingTotal() {
        val breakdown = ContextUsageBreakdown(10, 20, 30, 40, 4_096)
        val encoded = json.encodeToString(breakdown)

        assertEquals(4_196, breakdown.estimatedTotal)
        assertFalse(json.parseToJsonElement(encoded).jsonObject.containsKey("estimatedTotal"))
        assertEquals(breakdown, json.decodeFromString<ContextUsageBreakdown>(encoded))
    }

    @Test
    fun totalSaturatesInsteadOfOverflowing() {
        val breakdown = ContextUsageBreakdown(
            systemTokens = Int.MAX_VALUE,
            toolDefinitionTokens = Int.MAX_VALUE,
            chatTokens = Int.MAX_VALUE,
            toolResultTokens = Int.MAX_VALUE,
            imageTokens = Int.MAX_VALUE
        )

        assertEquals(Int.MAX_VALUE, breakdown.estimatedTotal)
        assertEquals(0, ContextUsageBreakdown().estimatedTotal)
    }
}
