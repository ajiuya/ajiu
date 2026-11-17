package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationSpeedFormatTest {
    @Test
    fun displays_one_decimal_place() {
        assertEquals("12.0", formatGenerationSpeed(12.0))
        assertEquals("12.4", formatGenerationSpeed(12.375))
    }

    @Test
    fun measured_zero_is_formatted_as_zero() {
        assertEquals("0.0", formatGenerationSpeed(0.0))
    }

    @Test
    fun keeps_large_speeds_without_grouping() {
        assertEquals("1234.5", formatGenerationSpeed(1234.5))
    }
}
