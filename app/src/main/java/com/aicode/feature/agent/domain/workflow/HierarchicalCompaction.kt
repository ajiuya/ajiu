package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object HierarchicalCompaction {
    data class Result(val summary: String, val leafBlocks: Int, val completedMerges: Int, val mergeLevels: Int)

    private data class LeafBlock(val cursor: CompactionText.Cursor, val budget: Int, val tokens: Int, val request: List<AgentMessage>)
    private data class SummaryPart(val text: String, val offset: Int)
    private data class ReduceBlock(val cursor: ReduceCursor, val parts: List<SummaryPart>, val request: List<AgentMessage>, val budget: Int)

    suspend fun summarize(
        source: List<String>,
        previousSummary: String?,
        system: String,
        leafTemplate: String,
        mergeTemplate: String,
        summaryBudget: Int,
        contextKnown: Boolean,
        configuredLimit: Int,
        outputLimit: Int,
        longHistory: Boolean,
        streamPolicy: CompactionStreamPolicy,
        streamSession: CompactionStreamSession,
        provider: AIProvider,
        onStreamUpdate: suspend (CompactionPhase, CompactionStreamUpdate) -> Unit,
        onAttempt: suspend (CompactionStreamAttempt) -> Unit,
        onLeafSuccess: suspend (Long, Int) -> Unit,
        onMergeSuccess: suspend (Int, Int) -> Unit,
        onMergeStarted: suspend (Int) -> Unit = {},
    ): Result {
        require(source.isNotEmpty())
        val rootCursor = CompactionText.Cursor(source)
        val effectiveConfiguredLimit = if (longHistory) CompactionSummaryPolicy.MAX_LONG_MATERIAL_TOKENS else configuredLimit
        val initialCap = if (longHistory) {
            CompactionSummaryPolicy.longMaterialCap(summaryBudget, contextKnown, effectiveConfiguredLimit)
        } else {
            CompactionSummaryPolicy.materialCap(summaryBudget, contextKnown, effectiveConfiguredLimit)
        }
        val leaves = mutableListOf<String>()
        var leafLimit = initialCap
        var block = 0
        suspend fun checkLeafBudget(pending: LeafBlock) {
            val available = streamPolicy.maxAttempts - streamSession.attempts
            val remaining = pending.cursor.fork()
            var requiredLeaves = 1
            while (!remaining.finished) {
                currentCoroutineContext().ensureActive()
                if (requiredLeaves >= available) throw CompactionStreamException("compaction_budget_unreachable")
                val plan = CompactionSummaryPolicy.planIndependent(
                    system, leafTemplate, summaryBudget, contextKnown, initialCap, outputLimit,
                    remaining.totalCharacters - remaining.consumedCharacters,
                    block + requiredLeaves + 1, longHistory,
                )
                if (plan.materialBudget <= 0) throw CompactionStreamException("compaction_budget_unreachable")
                val candidate = remaining.fork()
                candidate.next(plan.materialBudget)
                remaining.commitFrom(candidate)
                requiredLeaves++
            }
            val mergeBudget = available.toLong() - requiredLeaves
            if (mergeBudget < 0) throw CompactionStreamException("compaction_budget_unreachable")
            // 未生成摘要只用最短非空占位，算的是不可达下界，不预留假想重试。
            val minimumMerges = estimateMinimumMergeCalls(
                buildList {
                    previousSummary?.takeIf(String::isNotBlank)?.let(::add)
                    addAll(leaves)
                    repeat(requiredLeaves) { add("x") }
                },
                system, mergeTemplate, summaryBudget, outputLimit, mergeBudget,
            )
            if (minimumMerges > mergeBudget) throw CompactionStreamException("compaction_budget_unreachable")
        }
        while (!rootCursor.finished) {
            currentCoroutineContext().ensureActive()
            if (streamSession.attempts >= streamPolicy.maxAttempts) throw CompactionStreamException("compaction_budget_unreachable")
            if (block >= 32) throw CompactionStreamException("compaction_block_limit")
            fun prepare(limit: Int): LeafBlock {
                val plan = CompactionSummaryPolicy.planIndependent(
                    system, leafTemplate, summaryBudget, contextKnown, limit, outputLimit,
                    rootCursor.totalCharacters - rootCursor.consumedCharacters, block + 1, longHistory,
                )
                check(plan.materialBudget > 0) { "Summary instructions leave no input budget for history" }
                val candidate = rootCursor.fork()
                val chunk = candidate.next(plan.materialBudget)
                val request = listOf(AgentMessage.UserMessage(content =
                    CompactionSummaryPolicy.independentRequestContent(plan.instruction, block + 1, chunk,
                        rootCursor.consumedCharacters)))
                check(CompactionText.estimateRequest(system, emptyList(), request) <= summaryBudget) {
                    "Independent summary block exceeds its input budget"
                }
                return LeafBlock(candidate, plan.materialBudget, CompactionText.tokens(chunk), request)
            }
            var pending = prepare(leafLimit)
            checkLeafBudget(pending)
            val summary = streamSession.summarize(
                stream = { provider.completeStream(system, pending.request, emptyList(), reasoningEffort = "none") },
                onUpdate = { onStreamUpdate(CompactionPhase.SUMMARIZING, it) },
                onAttempt = onAttempt,
                onRetry = { error ->
                    if (error.code == "compaction_http_499" || error.code == "compaction_truncated") {
                        val smallerLimit = maxOf(128, minOf(pending.budget, pending.tokens) / 2)
                        if (smallerLimit < pending.budget) {
                            val smaller = prepare(smallerLimit)
                            if (smaller.cursor.consumedCharacters < pending.cursor.consumedCharacters) {
                                pending = smaller
                                leafLimit = minOf(leafLimit, smallerLimit)
                            } else throw error
                        } else throw error
                    }
                    checkLeafBudget(pending)
                },
            )
            currentCoroutineContext().ensureActive()
            rootCursor.commitFrom(pending.cursor)
            leaves += summary
            block++
            onLeafSuccess(rootCursor.consumedCharacters, block)
            leafLimit = minOf(initialCap, maxOf(leafLimit, pending.tokens).coerceAtLeast(1) * 2)
        }

        val nodes = buildList {
            previousSummary?.takeIf(String::isNotBlank)?.let(::add)
            addAll(leaves)
        }.toMutableList()
        var merges = 0
        var level = 0
        while (nodes.size > 1) {
            currentCoroutineContext().ensureActive()
            level++
            if (level > 32) throw CompactionStreamException("compaction_block_limit")
            onMergeStarted(level)
            val next = mutableListOf<String>()
            var first = 0
            var reduceBlock = 0
            while (first < nodes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(CompactionSummaryPolicy.MAX_REDUCE_UNITS, nodes.size - first)
                if (count == 1) {
                    next += nodes[first]
                    first++
                    continue
                }
                if (streamSession.attempts >= streamPolicy.maxAttempts) throw CompactionStreamException("compaction_budget_unreachable")
                reduceBlock++
                val group = nodes.subList(first, first + count).toList()
                val cursor = ReduceCursor(group)
                var reduceLimit = CompactionSummaryPolicy.MAX_MATERIAL_TOKENS
                val outputs = mutableListOf<String>()
                suspend fun checkMergeBudget(pending: ReduceBlock) {
                    val available = streamPolicy.maxAttempts - streamSession.attempts
                    var required = 1L
                    val projected = (next + outputs).toMutableList()
                    projected += "x"
                    suspend fun appendRemaining(remaining: ReduceCursor, material: List<String>, groupBlock: Int) {
                        while (!remaining.finished) {
                            currentCoroutineContext().ensureActive()
                            if (required >= available) throw CompactionStreamException("compaction_budget_unreachable")
                            val candidate = prepareReduceBlock(remaining, material, system, mergeTemplate,
                                summaryBudget, outputLimit, level, groupBlock, CompactionSummaryPolicy.MAX_MATERIAL_TOKENS)
                            remaining.commitFrom(candidate.cursor)
                            required++
                            projected += "x"
                        }
                    }
                    appendRemaining(pending.cursor.fork(), group, reduceBlock)
                    var remainingFirst = first + count
                    var remainingBlock = reduceBlock
                    while (remainingFirst < nodes.size) {
                        currentCoroutineContext().ensureActive()
                        val size = minOf(CompactionSummaryPolicy.MAX_REDUCE_UNITS, nodes.size - remainingFirst)
                        val material = nodes.subList(remainingFirst, remainingFirst + size).toList()
                        if (size == 1) projected += material.single()
                        else appendRemaining(ReduceCursor(material), material, ++remainingBlock)
                        remainingFirst += size
                    }
                    val futureBudget = available - required
                    if (futureBudget < 0 || estimateMinimumMergeCalls(projected, system, mergeTemplate,
                            summaryBudget, outputLimit, futureBudget, level) > futureBudget) {
                        throw CompactionStreamException("compaction_budget_unreachable")
                    }
                }
                while (!cursor.finished) {
                    currentCoroutineContext().ensureActive()
                    fun prepare(limit: Int) = prepareReduceBlock(cursor, group, system, mergeTemplate,
                        summaryBudget, outputLimit, level, reduceBlock, limit)
                    var pending = prepare(reduceLimit)
                    checkMergeBudget(pending)
                    val summary = streamSession.summarize(
                        stream = { provider.completeStream(system, pending.request, emptyList(), reasoningEffort = "none") },
                        onUpdate = { onStreamUpdate(CompactionPhase.MERGING, it) },
                        onAttempt = onAttempt,
                        onRetry = { error ->
                            if (error.code == "compaction_http_499" || error.code == "compaction_truncated") {
                                val smallerLimit = maxOf(128, minOf(pending.budget, pending.parts.sumOf { CompactionText.tokens(it.text) }) / 2)
                                if (smallerLimit < pending.budget) {
                                    val smaller = prepare(smallerLimit)
                                    if (smaller.cursor.position < pending.cursor.position) {
                                        pending = smaller
                                        reduceLimit = minOf(reduceLimit, smallerLimit)
                                    } else throw error
                                } else throw error
                            }
                            checkMergeBudget(pending)
                        },
                    )
                    currentCoroutineContext().ensureActive()
                    cursor.commitFrom(pending.cursor)
                    outputs += summary
                    reduceLimit = minOf(CompactionSummaryPolicy.MAX_MATERIAL_TOKENS, pending.budget * 2)
                    merges++
                    onMergeSuccess(merges, level)
                }
                next += outputs
                first += count
            }
            nodes.clear()
            nodes.addAll(next)
        }
        currentCoroutineContext().ensureActive()
        return Result(nodes.single(), block, merges, level)
    }

    private fun prepareReduceBlock(
        cursor: ReduceCursor,
        group: List<String>,
        system: String,
        template: String,
        summaryBudget: Int,
        outputLimit: Int,
        level: Int,
        block: Int,
        limit: Int,
    ): ReduceBlock {
        val plan = CompactionSummaryPolicy.planReduce(system, template, summaryBudget, outputLimit,
            level, block, cursor.offset, group.size, group.sumOf { CompactionText.tokens(it).toLong() }, configuredLimit = limit)
        if (plan.materialBudget <= 0) throw CompactionStreamException("compaction_budget_unreachable")
        val candidate = cursor.fork()
        val materialOffset = cursor.offset
        val parts = candidate.next(CompactionSummaryPolicy.MAX_REDUCE_UNITS, plan.materialBudget) { proposed ->
            if (proposed.sumOf { CompactionText.tokens(it.text).toLong() } > plan.materialBudget) return@next false
            val request = CompactionSummaryPolicy.reduceRequestContent(plan.instruction, level,
                block, proposed.map(SummaryPart::text), materialOffset)
            CompactionText.estimateRequest(system, emptyList(), listOf(AgentMessage.UserMessage(content = request))) <= summaryBudget
        }
        val request = listOf(AgentMessage.UserMessage(content = CompactionSummaryPolicy.reduceRequestContent(
            plan.instruction, level, block, parts.map(SummaryPart::text), materialOffset)))
        check(CompactionText.estimateRequest(system, emptyList(), request) <= summaryBudget) { "Merge block exceeds its input budget" }
        return ReduceBlock(candidate, parts, request, plan.materialBudget)
    }

    private suspend fun estimateMinimumMergeCalls(
        initialNodes: List<String>,
        system: String,
        mergeTemplate: String,
        summaryBudget: Int,
        outputLimit: Int,
        maximumCalls: Long,
        initialLevel: Int = 0,
    ): Long {
        var nodes = initialNodes
        var calls = 0L
        var level = initialLevel
        while (nodes.size > 1) {
            currentCoroutineContext().ensureActive()
            level++
            if (level > 32) return maximumCalls + 1
            val next = mutableListOf<String>()
            var first = 0
            var block = 0
            while (first < nodes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(CompactionSummaryPolicy.MAX_REDUCE_UNITS, nodes.size - first)
                if (count == 1) {
                    next += nodes[first]
                    first++
                    continue
                }
                block++
                val group = nodes.subList(first, first + count).toList()
                val cursor = ReduceCursor(group)
                while (!cursor.finished) {
                    currentCoroutineContext().ensureActive()
                    val candidate = prepareReduceBlock(cursor, group, system, mergeTemplate, summaryBudget,
                        outputLimit, level, block, CompactionSummaryPolicy.MAX_MATERIAL_TOKENS)
                    cursor.commitFrom(candidate.cursor)
                    next += "x"
                    calls++
                    if (calls > maximumCalls) return calls
                }
                first += count
            }
            nodes = next
        }
        return calls
    }

    private class ReduceCursor private constructor(
        private val units: List<String>,
        private val parent: ReduceCursor?,
        private val parentRevision: Long,
    ) {
        constructor(units: List<String>) : this(units.toList(), null, 0)

        private var index = 0
        private var characterOffset = 0
        private var revision = 0L
        val finished: Boolean get() = index >= units.size
        val position: Long get() = index.toLong() * (Int.MAX_VALUE.toLong() + 1) + characterOffset
        val offset: Long get() = units.take(index).sumOf { it.length.toLong() } + characterOffset

        fun fork(): ReduceCursor = ReduceCursor(units, this, revision).also {
            it.index = index
            it.characterOffset = characterOffset
        }

        fun commitFrom(candidate: ReduceCursor) {
            require(candidate.parent === this && candidate.parentRevision == revision) { "Merge cursor is stale" }
            require(candidate.position > position) { "Merge cursor made no progress" }
            index = candidate.index
            characterOffset = candidate.characterOffset
            revision++
        }

        fun next(maxUnits: Int, budget: Int, fits: (List<SummaryPart>) -> Boolean): List<SummaryPart> {
            require(maxUnits > 0 && budget > 0)
            val result = mutableListOf<SummaryPart>()
            while (!finished && result.size < maxUnits) {
                val source = units[index]
                val remaining = source.substring(characterOffset)
                fun part(text: String, size: Int): SummaryPart = SummaryPart(
                    text = "[summary-unit ${index + 1}, character-offset $characterOffset]\n$text" +
                        if (size < remaining.length) "\n[unit continues]" else "",
                    offset = characterOffset,
                )
                val complete = part(remaining, remaining.length)
                if (CompactionText.tokens(complete.text) <= budget && fits(result + complete)) {
                    result += complete
                    index++
                    characterOffset = 0
                    continue
                }
                if (result.isNotEmpty()) break
                var low = 0
                var high = remaining.length
                while (low < high) {
                    val mid = low + (high - low + 1) / 2
                    val candidate = part(remaining.substring(0, mid), mid)
                    if (CompactionText.tokens(candidate.text) <= budget && fits(listOf(candidate))) low = mid else high = mid - 1
                }
                if (low > 0 && low < remaining.length && remaining[low - 1].isHighSurrogate() && remaining[low].isLowSurrogate()) low--
                check(low > 0) { "A summary fragment cannot fit in the merge input budget" }
                result += part(remaining.substring(0, low), low)
                characterOffset += low
                if (characterOffset == source.length) {
                    index++
                    characterOffset = 0
                }
                break
            }
            check(result.isNotEmpty()) { "Merge cursor made no progress" }
            revision++
            return result
        }
    }
}
