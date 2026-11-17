package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import org.junit.Assert.*
import org.junit.Test

internal object CompactionSummaryFixture {
    val source: String = "任务约束尚待处理 id=42; path=/tmp/a.kt;\n".repeat(2_000).take(51_794)

    fun assertComplete(units: List<String>, chunks: List<String>) {
        val restored = units.map { StringBuilder() }
        val label = Regex("\\[history-unit (\\d+), character-offset (\\d+)\\]\\n")
        for (chunk in chunks) {
            val matches = label.findAll(chunk).toList()
            assertTrue(matches.isNotEmpty())
            for ((index, match) in matches.withIndex()) {
                val unit = match.groupValues[1].toInt() - 1
                assertTrue(unit in units.indices)
                assertEquals(restored[unit].length, match.groupValues[2].toInt())
                val end = matches.getOrNull(index + 1)?.range?.first ?: chunk.length
                val wrapped = chunk.substring(match.range.last + 1, end)
                val raw = if (wrapped.endsWith("\n[unit continues]\n")) wrapped.removeSuffix("\n[unit continues]\n")
                    else { assertTrue(wrapped.endsWith("\n\n")); wrapped.removeSuffix("\n\n") }
                assertFalse(raw.lastOrNull()?.isHighSurrogate() == true)
                restored[unit].append(raw)
            }
        }
        assertEquals(units, restored.map { it.toString() })
    }
}

class CompactionSummaryPolicyTest {
    private fun plan(
        budget: Int = 191_808,
        previous: String? = null,
        template: String = "{{INSTRUCTION}}",
        system: String = "Summarize history only.",
        known: Boolean = true,
        cap: Int = 65_536,
        output: Int = 8_192,
        remaining: Long = 100_000,
        longHistory: Boolean = false,
    ) = CompactionSummaryPolicy.plan(system, template, previous, budget, known, cap, output, remaining, 1, longHistory)

    @Test fun adaptiveCapUsesOnlySummaryEffectiveBudget() {
        assertEquals(65_536, CompactionStreamPolicy().materialTokens)
        assertEquals(16_384, CompactionSummaryPolicy.materialCap(12_000, true, 65_536))
        assertEquals(47_952, CompactionSummaryPolicy.materialCap(191_808, true, 65_536))
        assertEquals(65_536, CompactionSummaryPolicy.materialCap(Int.MAX_VALUE, true, Int.MAX_VALUE))
        assertEquals(16_384, CompactionSummaryPolicy.materialCap(Int.MAX_VALUE, false, Int.MAX_VALUE))
    }

    @Test fun explicitOverrideOnlyTightensAdaptiveCap() {
        assertEquals(16_384, CompactionSummaryPolicy.materialCap(191_808, true, 16_384))
        assertEquals(512, CompactionSummaryPolicy.materialCap(191_808, true, 512))
        assertEquals(47_952, CompactionSummaryPolicy.materialCap(191_808, true, 200_000))
        assertEquals(2_000, CompactionSummaryPolicy.materialCap(191_808, false, 2_000))
    }

    @Test fun mixedFixtureFitsOneAdaptiveBlockAndMultipleLegacyBlocksWithExactSource() {
        val units = listOf(CompactionSummaryFixture.source.take(40_000), CompactionSummaryFixture.source.drop(40_000))
        fun chunks(cap: Int): List<String> {
            val cursor = CompactionText.Cursor(units)
            val chunks = mutableListOf<String>()
            while (!cursor.finished) chunks.add(cursor.next(plan(cap = cap).materialBudget))
            assertEquals(51_794L, cursor.consumedCharacters)
            CompactionSummaryFixture.assertComplete(units, chunks)
            return chunks
        }
        assertEquals(1, chunks(65_536).size)
        assertTrue(chunks(16_384).size >= 2)
        assertTrue(chunks(16_384).all { CompactionText.tokens(it) <= 16_384 })
    }

    @Test fun smallWindowAndExplicitInputCapStayWithinCompleteRequestBudget() {
        for (model in listOf(ModelMetadata("small", contextTokens = 16_000), ModelMetadata("limited", contextTokens = 1_000_000, inputTokens = 12_000))) {
            val budget = ModelContextPolicy.effectiveInputBudget(model)
            val planned = plan(budget = budget, output = ModelContextPolicy.outputReserveTokens(model))
            val units = listOf(CompactionSummaryFixture.source)
            val cursor = CompactionText.Cursor(units)
            val chunks = mutableListOf<String>()
            while (!cursor.finished) {
                val chunk = cursor.next(planned.materialBudget)
                chunks.add(chunk)
                val request = AgentMessage.UserMessage(content = CompactionSummaryPolicy.requestContent(planned.instruction, chunks.size, chunk))
                assertTrue(CompactionText.estimateRequest("Summarize history only.", emptyList(), listOf(request)) <= budget)
            }
            assertTrue(chunks.size > 1)
            CompactionSummaryFixture.assertComplete(units, chunks)
        }
    }

    @Test fun fullPreviousSystemCustomTemplateAndGuidanceAreBudgeted() {
        val previous = "previous-unique-id-987\n" + "汉".repeat(4_000)
        val system = "系".repeat(1_000)
        val planned = plan(budget = 12_000, previous = previous, system = system, template = "指".repeat(1_000) + "{{INSTRUCTION}}")
        assertTrue(planned.instruction.contains("<previous-summary>\n$previous\n</previous-summary>"))
        assertTrue(planned.instruction.contains("<summary-guidance>"))
        val empty = AgentMessage.UserMessage(content = CompactionSummaryPolicy.requestContent(planned.instruction, 1, ""))
        val overhead = CompactionText.estimateRequest(system, emptyList(), listOf(empty)) + 64
        assertEquals(((12_000 - overhead) * 0.8).toInt(), planned.materialBudget)
        val chunk = CompactionText.Cursor(listOf("汉".repeat(20_000))).next(planned.materialBudget)
        val request = AgentMessage.UserMessage(content = CompactionSummaryPolicy.requestContent(planned.instruction, 1, chunk))
        assertTrue(CompactionText.estimateRequest(system, emptyList(), listOf(request)) <= 12_000)
    }

    @Test fun exhaustedBudgetNeverRaisesMaterialToFloor() {
        assertEquals(0, plan(budget = 1).materialBudget)
        assertEquals(0, plan(budget = 1_000, previous = "汉".repeat(2_000)).materialBudget)
        assertEquals(0, plan(budget = Int.MIN_VALUE).materialBudget)
    }

    @Test fun extremeEstimatesCannotOverflowSoftTarget() {
        assertEquals(3_072, CompactionSummaryPolicy.softTargetTokens(Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(512, CompactionSummaryPolicy.softTargetTokens(Long.MIN_VALUE, Int.MIN_VALUE, 8_192))
        assertEquals(65_536, plan(budget = Int.MAX_VALUE, remaining = Long.MAX_VALUE).materialBudget)
    }

    @Test fun softTargetLeavesHardOutputRoomWithoutClippingSummary() {
        for ((material, previous, hard, expected) in listOf(
            listOf(100, 0, 8_192, 512), listOf(8_000, 0, 8_192, 1_000), listOf(8_000, 8_000, 8_192, 2_000),
            listOf(65_536, 8_000, 8_192, 3_072), listOf(65_536, 8_000, 256, 128),
            listOf(65_536, 8_000, 1, 1), listOf(65_536, 8_000, 2, 1), listOf(65_536, 8_000, 511, 255),
        )) assertEquals(expected, CompactionSummaryPolicy.softTargetTokens(material.toLong(), previous, hard))
    }

    @Test fun customPromptWithoutPlaceholderStillCarriesPreviousAndGuidance() {
        val previous = "same fact\nsame fact\nID=42\nID=43\nconflicting unresolved state"
        val rendered = CompactionSummaryPolicy.instruction("Custom format: JSON.", previous, 512)
        assertTrue(rendered.startsWith("Custom format: JSON."))
        assertTrue(rendered.contains("<previous-summary>\n$previous\n</previous-summary>"))
        assertTrue(rendered.contains("soft target, not a cutoff"))
        assertTrue(rendered.contains("Merge repeated facts"))
        assertTrue(rendered.contains("Do not delete unique facts"))
    }

    @Test fun repeatedPlaceholdersAreBudgetedInFull() {
        val previous = "汉".repeat(1_000)
        val once = plan(budget = 12_000, previous = previous)
        val twice = plan(budget = 12_000, previous = previous, template = "{{INSTRUCTION}}\n{{INSTRUCTION}}")
        assertEquals(2, Regex("<previous-summary>").findAll(twice.instruction).count())
        assertTrue(twice.materialBudget < once.materialBudget)
    }

    @Test fun hierarchyStrictFourCapBoundaryDoesNotRequireExpansionPermission() {
        for ((budget, known, limit) in listOf(
            Triple(119_808, true, 65_536), Triple(119_808, false, 65_536),
            Triple(991_808, true, 16_384), Triple(991_808, true, 1_024), Triple(991_808, true, 65_536),
        )) {
            val cap = CompactionSummaryPolicy.materialCap(budget, known, limit)
            assertFalse(CompactionSummaryPolicy.useHierarchicalHistory(budget, known, limit, cap * 4L))
            assertTrue(CompactionSummaryPolicy.useHierarchicalHistory(budget, known, limit, cap * 4L + 1))
            assertEquals(known && budget > 128_000 && limit == 65_536,
                CompactionSummaryPolicy.useLongHistory(budget, known, limit, cap * 4L + 1))
        }
    }

    @Test fun longHistoryRequiresKnownLargeBudgetAndExactDefaultLimit() {
        assertFalse(CompactionSummaryPolicy.useLongHistory(991_808, false, 65_536, Long.MAX_VALUE))
        for (budget in listOf(32_000, 119_808, 128_000)) assertFalse(CompactionSummaryPolicy.useLongHistory(budget, true, 65_536, Long.MAX_VALUE))
        for (limit in listOf(128, 1_024, 16_384, 65_535, 65_537, 131_072, Int.MAX_VALUE)) {
            assertFalse(CompactionSummaryPolicy.useLongHistory(991_808, true, limit, Long.MAX_VALUE))
        }
        assertTrue(CompactionSummaryPolicy.useLongHistory(128_001, true, 65_536, Long.MAX_VALUE))
    }

    @Test fun expandedCapIsAtMostTwiceRegularAndHonorsReducedLimit() {
        for (budget in listOf(128_001, 200_000, 400_000, 991_808, Int.MAX_VALUE)) {
            val regular = CompactionSummaryPolicy.materialCap(budget, true, 65_536)
            assertEquals(minOf(131_072, regular * 2), plan(budget = budget, cap = Int.MAX_VALUE, longHistory = true).materialBudget)
        }
        for (limit in listOf(128, 1_024, 16_384, 65_536, 90_000, 100_000, 131_072)) {
            assertEquals(minOf(100_000, limit), plan(budget = 200_000, cap = limit, longHistory = true).materialBudget)
        }
        assertEquals(16_384, plan(known = false, cap = Int.MAX_VALUE, longHistory = true).materialBudget)
    }

    @Test fun expansionDoesNotRaiseOutputOrSoftTargetAndStillCountsPrevious() {
        for (output in listOf(1, 512, 2_000, 8_192)) {
            val ordinary = plan(budget = 991_808, cap = 131_072, output = output)
            val expanded = plan(budget = 991_808, cap = 131_072, output = output, longHistory = true)
            assertTrue(expanded.materialBudget > ordinary.materialBudget)
            assertEquals(ordinary.softTargetTokens, expanded.softTargetTokens)
        }
        assertEquals(0, plan(budget = 200_000, previous = "旧".repeat(210_000), longHistory = true).materialBudget)
    }
}
