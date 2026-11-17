package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.domain.model.ModelContextPolicy

internal object CompactionSummaryPolicy {
    const val LEGACY_MATERIAL_TOKENS = 16_384
    const val MAX_MATERIAL_TOKENS = 65_536
    const val MAX_LONG_MATERIAL_TOKENS = 131_072
    const val MAX_REDUCE_UNITS = 4
    private const val MIN_SOFT_TOKENS = 512
    private const val MAX_SOFT_TOKENS = 3_072

    data class Plan(val instruction: String, val materialBudget: Int, val softTargetTokens: Int)
    data class IndependentPlan(val instruction: String, val materialBudget: Int)

    fun materialCap(summaryBudget: Int, contextKnown: Boolean, configuredLimit: Int): Int {
        require(configuredLimit > 0)
        val adaptive = if (contextKnown) maxOf(LEGACY_MATERIAL_TOKENS, summaryBudget / 4) else LEGACY_MATERIAL_TOKENS
        return minOf(MAX_MATERIAL_TOKENS, adaptive, configuredLimit)
    }

    fun useHierarchicalHistory(summaryBudget: Int, contextKnown: Boolean, configuredLimit: Int, projectedTokens: Long): Boolean =
        projectedTokens > 4L * materialCap(summaryBudget, contextKnown, configuredLimit)

    fun useLongHistory(summaryBudget: Int, contextKnown: Boolean, configuredLimit: Int, projectedTokens: Long): Boolean =
        contextKnown && summaryBudget > ModelContextPolicy.DEFAULT_CONTEXT_TOKENS &&
            configuredLimit == MAX_MATERIAL_TOKENS &&
            projectedTokens > 4L * materialCap(summaryBudget, contextKnown, configuredLimit)

    fun longMaterialCap(summaryBudget: Int, contextKnown: Boolean, configuredLimit: Int): Int {
        require(configuredLimit > 0)
        val regular = materialCap(summaryBudget, contextKnown, MAX_MATERIAL_TOKENS)
        if (!contextKnown || summaryBudget <= ModelContextPolicy.DEFAULT_CONTEXT_TOKENS) return minOf(regular, configuredLimit)
        return minOf(MAX_LONG_MATERIAL_TOKENS, regular * 2, configuredLimit)
    }

    fun softTargetTokens(materialEstimate: Long, previousTokens: Int, hardOutputLimit: Int): Int {
        require(hardOutputLimit > 0)
        val source = materialEstimate.coerceIn(0, MAX_MATERIAL_TOKENS.toLong()) + previousTokens.coerceAtLeast(0).toLong()
        val desired = (source / 8).coerceIn(MIN_SOFT_TOKENS.toLong(), MAX_SOFT_TOKENS.toLong()).toInt()
        return minOf(desired, maxOf(1, hardOutputLimit / 2))
    }

    fun instruction(template: String, previous: String?, softTarget: Int): String {
        val merge = if (previous.isNullOrBlank()) {
            "Create a new handoff summary from this sequential history block."
        } else {
            "Update the previous summary using this next history block. Preserve still-valid facts and unfinished goals.\n<previous-summary>\n$previous\n</previous-summary>"
        }
        val guidance = """
            <summary-guidance>
            Aim for about $softTarget tokens of summary text. This is a soft target, not a cutoff; exceed it when needed to preserve critical goals, facts, exact IDs, paths, constraints, decisions and unfinished tasks.
            Merge repeated facts and repeated descriptions of the same operation. Keep one concise statement per fact, preserving distinct details, chronological corrections and unresolved conflicts. Do not delete unique facts from the previous summary merely to meet the target. Prefer handoff facts over repeated narration; do not reproduce the transcript.
            </summary-guidance>
        """.trimIndent()
        val full = "$merge\n\n$guidance"
        return if ("{{INSTRUCTION}}" in template) template.replace("{{INSTRUCTION}}", full) else "$template\n\n$full"
    }

    fun requestContent(instruction: String, block: Int, material: String): String =
        "$instruction\n\n<history-material block=\"$block\">\n$material\n</history-material>"

    fun independentInstruction(template: String, kind: String, softTarget: Int): String {
        require(kind == "leaf" || kind == "merge")
        val full = "Create a self-contained $kind summary. Preserve unique facts, exact IDs, paths, chronological corrections and unresolved conflicts. The target of $softTarget tokens is soft; do not truncate necessary facts."
        return if ("{{INSTRUCTION}}" in template) template.replace("{{INSTRUCTION}}", full) else "$template\n\n$full"
    }

    fun independentRequestContent(instruction: String, block: Int, material: String, offset: Long): String =
        "$instruction\n\n<history-material block=\"$block\" character-offset=\"$offset\">\n$material\n</history-material>"

    fun reduceRequestContent(instruction: String, level: Int, block: Int, materials: List<String>, offset: Long = 0): String =
        buildString {
            append(instruction).append("\n\n<summary-material level=\"").append(level)
                .append("\" block=\"").append(block).append("\" offset=\"").append(offset).append("\">\n")
            materials.forEachIndexed { index, text ->
                append("<summary-unit index=\"").append(index + 1).append("\">\n")
                    .append(text).append("\n</summary-unit>\n")
            }
            append("</summary-material>")
        }

    fun planIndependent(
        system: String,
        template: String,
        summaryBudget: Int,
        contextKnown: Boolean,
        configuredLimit: Int,
        hardOutputLimit: Int,
        remainingSourceCharacters: Long,
        block: Int,
        longHistory: Boolean,
        previousSummaryTokens: Int = 0,
    ): IndependentPlan {
        val cap = if (longHistory) longMaterialCap(summaryBudget, contextKnown, configuredLimit)
            else materialCap(summaryBudget, contextKnown, configuredLimit)
        val materialEstimate = minOf(cap.toLong(), remainingSourceCharacters.coerceAtLeast(0))
        val target = softTargetTokens(materialEstimate, previousSummaryTokens, hardOutputLimit)
        val instruction = independentInstruction(template, "leaf", target)
        val empty = AgentMessage.UserMessage(content = independentRequestContent(instruction, block, "", 0))
        val overhead = CompactionText.estimateRequest(system, emptyList(), listOf(empty)).toLong() + 64
        val available = ((summaryBudget.toLong() - overhead).coerceAtLeast(0) * ModelContextPolicy.COMPACTION_BUDGET_RATIO).toLong()
        return IndependentPlan(instruction, minOf(cap.toLong(), available).toInt())
    }

    fun planReduce(
        system: String,
        template: String,
        summaryBudget: Int,
        hardOutputLimit: Int,
        level: Int,
        block: Int,
        offset: Long,
        unitCount: Int,
        materialEstimateTokens: Long,
        configuredLimit: Int = MAX_MATERIAL_TOKENS,
        previousSummaryTokens: Int = 0,
    ): IndependentPlan {
        require(unitCount > 0)
        val target = softTargetTokens(materialEstimateTokens, previousSummaryTokens, hardOutputLimit)
        val instruction = independentInstruction(template, "merge", target)
        val empty = AgentMessage.UserMessage(content = reduceRequestContent(instruction, level, block, List(unitCount) { "" }, offset))
        val overhead = CompactionText.estimateRequest(system, emptyList(), listOf(empty)).toLong() + 64
        val available = ((summaryBudget.toLong() - overhead).coerceAtLeast(0) * ModelContextPolicy.COMPACTION_BUDGET_RATIO).toLong()
        return IndependentPlan(instruction, minOf(configuredLimit.toLong(), available).coerceAtLeast(0).toInt())
    }

    fun plan(
        system: String,
        template: String,
        previous: String?,
        summaryBudget: Int,
        contextKnown: Boolean,
        configuredLimit: Int,
        hardOutputLimit: Int,
        remainingSourceCharacters: Long,
        block: Int,
        longHistory: Boolean = false,
    ): Plan {
        val cap = if (longHistory) longMaterialCap(summaryBudget, contextKnown, configuredLimit)
            else materialCap(summaryBudget, contextKnown, configuredLimit)
        val materialEstimate = minOf(cap.toLong(), remainingSourceCharacters.coerceAtLeast(0))
        val softTarget = softTargetTokens(materialEstimate, previous?.let(CompactionText::tokens) ?: 0, hardOutputLimit)
        val instruction = instruction(template, previous, softTarget)
        val emptyRequest = listOf(AgentMessage.UserMessage(content = requestContent(instruction, block, "")))
        val overhead = CompactionText.estimateRequest(system, emptyList(), emptyRequest).toLong() + 64
        val available = ((summaryBudget.toLong() - overhead).coerceAtLeast(0) * ModelContextPolicy.COMPACTION_BUDGET_RATIO).toLong()
        return Plan(instruction, minOf(cap.toLong(), available).toInt(), softTarget)
    }
}
