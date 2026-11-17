package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HierarchicalCompactionEngineTest {
    private fun material(request: String): String = request.substringAfter(
        Regex("<history-material [^>]*>\\n").find(request)!!.value
    ).substringBeforeLast("\n</history-material>")
    private fun mergeUnits(request: String): List<String> = Regex("<summary-unit index=\"\\d+\">\\n([\\s\\S]*?)\\n</summary-unit>")
        .findAll(request).map { it.groupValues[1].substringAfter('\n') }.toList()
    private fun complete(text: String) = flowOf(AIStreamChunk.Final(AIResponse(text, stopReason = "stop")))

    @Test fun independentLeavesMergeInSourceOrderWithoutRollingPrevious() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val leaves = mutableListOf<String>()
        val merges = mutableListOf<String>()
        every { provider.completeStream(any(), any(), any(), "none") } answers {
            val text = (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content
            complete(if ("<history-material " in text) {
                leaves += text; "leaf-${leaves.size}"
            } else { merges += text; "MERGED-${merges.size}" })
        }
        val source = "源事实🙂🚀/id=".repeat(2_000)
        val phases = mutableListOf<CompactionPhase>()
        val successes = mutableListOf<Pair<Long, Int>>()
        val policy = CompactionStreamPolicy(retryDelayMs = 0)
        val result = HierarchicalCompaction.summarize(
            source = listOf(source), previousSummary = "OLD_SUMMARY_UNIQUE", system = "system",
            leafTemplate = "leaf {{INSTRUCTION}}", mergeTemplate = "merge {{INSTRUCTION}}",
            summaryBudget = 20_000, contextKnown = true, configuredLimit = 2_000, outputLimit = 1_024,
            longHistory = false, streamPolicy = policy,
            streamSession = CompactionStreamSession(policy) { testScheduler.currentTime }, provider = provider,
            onStreamUpdate = { phase, _ -> phases += phase }, onAttempt = {},
            onLeafSuccess = { characters, count -> successes += characters to count }, onMergeSuccess = { _, _ -> },
        )
        assertTrue(leaves.size > 4)
        assertTrue(merges.size > 1)
        assertTrue(leaves.all { "<previous-summary>" !in it && "OLD_SUMMARY_UNIQUE" !in it })
        CompactionSummaryFixture.assertComplete(listOf(source), leaves.map(::material))
        val firstLevel = merges.filter { "<summary-material level=\"1\"" in it }.flatMap(::mergeUnits)
        assertEquals(listOf("OLD_SUMMARY_UNIQUE") + (1..leaves.size).map { "leaf-$it" }, firstLevel)
        assertTrue(merges.all { mergeUnits(it).size in 1..4 })
        assertEquals(leaves.size, result.leafBlocks)
        assertEquals(merges.size, result.completedMerges)
        assertEquals(source.length.toLong(), successes.last().first)
        val firstMerge = phases.indexOfFirst { it == CompactionPhase.MERGING }
        assertTrue(firstMerge > 0)
        assertTrue(phases.take(firstMerge).all { it == CompactionPhase.SUMMARIZING })
        assertTrue(phases.drop(firstMerge).all { it == CompactionPhase.MERGING })
    }

    @Test fun orderedFourWayTreeCountsOnlySuccessfulMerges() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val requests = mutableListOf<String>()
        val levelOneUnits = mutableListOf<String>()
        var leafCount = 0
        var mergeCount = 0
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            val text = (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content
            requests += text
            complete(if ("<history-material " in text) "L${++leafCount}" else {
                if ("<summary-material level=\"1\"" in text) levelOneUnits += mergeUnits(text)
                "M${++mergeCount}"
            })
        }
        val policy = CompactionStreamPolicy(retryDelayMs = 0)
        val callbacks = mutableListOf<Pair<Int, Int>>()
        val result = HierarchicalCompaction.summarize(
            source = List(15) { "source-$it:" + "x".repeat(2_700) }, previousSummary = null, system = "system",
            leafTemplate = "leaf", mergeTemplate = "merge", summaryBudget = 20_000,
            contextKnown = true, configuredLimit = 1_024, outputLimit = 1_024, longHistory = false,
            streamPolicy = policy, streamSession = CompactionStreamSession(policy) { testScheduler.currentTime }, provider = provider,
            onStreamUpdate = { _, _ -> }, onAttempt = {}, onLeafSuccess = { _, _ -> },
            onMergeSuccess = { count, level -> callbacks += count to level },
        )
        assertEquals((1..15).map { "L$it" }, levelOneUnits)
        assertEquals(mergeCount, result.completedMerges)
        assertEquals((1..mergeCount).toList(), callbacks.map { it.first })
        assertEquals(callbacks.last().second, result.mergeLevels)
        assertTrue(requests.filter { "<summary-material " in it }.all { mergeUnits(it).size in 1..4 })
    }

    @Test fun validRootCanBeLongerThanLeavesWithoutClipping() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val root = "A detailed handoff retaining corrections, identifiers, constraints, and unresolved conflicts"
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            val text = (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content
            complete(if ("<history-material " in text) "L" else root)
        }
        val policy = CompactionStreamPolicy(retryDelayMs = 0)
        val result = HierarchicalCompaction.summarize(
            source = List(2) { "source-$it:" + "x".repeat(2_700) }, previousSummary = null, system = "system",
            leafTemplate = "leaf", mergeTemplate = "merge", summaryBudget = 20_000,
            contextKnown = true, configuredLimit = 1_024, outputLimit = 1_024, longHistory = false,
            streamPolicy = policy, streamSession = CompactionStreamSession(policy) { testScheduler.currentTime }, provider = provider,
            onStreamUpdate = { _, _ -> }, onAttempt = {}, onLeafSuccess = { _, _ -> }, onMergeSuccess = { _, _ -> },
        )
        assertEquals(root, result.summary)
        assertEquals(1, result.completedMerges)
    }

    @Test fun impossibleTreeFailsBeforeAnyRequest() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val policy = CompactionStreamPolicy(retryDelayMs = 0)
        val error = runCatching { HierarchicalCompaction.summarize(
            source = listOf("x".repeat(5_200_000)), previousSummary = null, system = "system",
            leafTemplate = "leaf", mergeTemplate = "merge", summaryBudget = 129_000,
            contextKnown = true, configuredLimit = 65_536, outputLimit = 8_192, longHistory = true,
            streamPolicy = policy, streamSession = CompactionStreamSession(policy) { testScheduler.currentTime }, provider = provider,
            onStreamUpdate = { _, _ -> }, onAttempt = {}, onLeafSuccess = { _, _ -> }, onMergeSuccess = { _, _ -> },
        ) }.exceptionOrNull()
        assertEquals("compaction_budget_unreachable", (error as CompactionStreamException).code)
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun mergeCancellationNeverReturnsPartialRoot() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            val text = (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content
            if ("<history-material " in text) complete("LEAF") else flow {
                emit(AIStreamChunk.TextDelta("PARTIAL_ROOT")); entered.complete(Unit); awaitCancellation()
            }
        }
        val policy = CompactionStreamPolicy(retryDelayMs = 0)
        var result: HierarchicalCompaction.Result? = null
        var completedMerges = 0
        val job = async { result = HierarchicalCompaction.summarize(
            source = List(3) { "source-$it:" + "x".repeat(2_700) }, previousSummary = null, system = "system",
            leafTemplate = "leaf", mergeTemplate = "merge", summaryBudget = 20_000,
            contextKnown = true, configuredLimit = 1_024, outputLimit = 1_024, longHistory = false,
            streamPolicy = policy, streamSession = CompactionStreamSession(policy) { testScheduler.currentTime }, provider = provider,
            onStreamUpdate = { _, _ -> }, onAttempt = {}, onLeafSuccess = { _, _ -> }, onMergeSuccess = { _, _ -> completedMerges++ },
        ) }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(result)
        assertEquals(0, completedMerges)
    }
}
