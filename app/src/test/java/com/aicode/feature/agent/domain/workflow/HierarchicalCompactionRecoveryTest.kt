package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HierarchicalCompactionRecoveryTest {
    private class Sent(val content: String, val leavesBefore: Int, val mergesBefore: Int) {
        val leaf = "<history-material " in content
        private val tag = if (leaf) "history-material" else "summary-material"
        private val header = content.substringAfter("<$tag ").substringBefore('>')
        private fun attribute(name: String) = header.substringAfter("$name=\"").substringBefore('"')
        val level = if (leaf) 0 else attribute("level").toInt()
        val block = attribute("block").toInt()
        val offset = attribute(if (leaf) "character-offset" else "offset").toLong()
        val material = content.substringAfter("<$tag $header>\n").substringBeforeLast("\n</$tag>")
        private val parts = Regex("<summary-unit index=\"\\d+\">\\n([\\s\\S]*?)\\n</summary-unit>")
            .findAll(material).map { it.groupValues[1] }.toList()
        val inputTokens = parts.sumOf(CompactionText::tokens)
        val text = if (leaf) material.replace(Regex("\\[history-unit \\d+, character-offset \\d+\\]\\n"), "")
            .replace("\n[unit continues]\n", "").replace("\n\n", "")
        else parts.joinToString("") { it.substringAfter('\n').removeSuffix("\n[unit continues]") }
        var output: String? = null
    }

    private fun stopped(text: String) = AIResponse(text, stopReason = "stop")
    private fun marker(sent: Sent, serial: Int) = "MERGE_L${sent.level}_B${sent.block}_O${sent.offset}_N$serial"

    private inner class Probe(maxAttempts: Int = 32, now: () -> Long, respond: (Sent) -> AIResponse) {
        val requests = mutableListOf<Sent>()
        val leaves = mutableListOf<Pair<Long, Int>>()
        val merges = mutableListOf<Pair<Int, Int>>()
        val starts = mutableListOf<Pair<Int, Int>>()
        private val policy = CompactionStreamPolicy(maxAttempts = maxAttempts, retryDelayMs = 0)
        val session = CompactionStreamSession(policy, now)
        private val provider = mockk<AIProvider>(relaxed = true)
        private var streamLevel = 0

        init {
            every { provider.completeStream(any(), any(), any(), any()) } answers {
                val sent = Sent((secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content, leaves.size, merges.size)
                if (!sent.leaf) {
                    assertEquals(sent.level, starts.lastOrNull()?.first ?: 0)
                    assertEquals(sent.level, streamLevel)
                }
                requests += sent
                flow {
                    emit(AIStreamChunk.TextDelta("UNCOMMITTED_PARTIAL"))
                    val response = respond(sent)
                    if (response.stopReason == "stop") sent.output = response.content
                    emit(AIStreamChunk.Final(response))
                }
            }
        }

        suspend fun run(source: List<String>, cap: Int = 65_536, longHistory: Boolean = true) = HierarchicalCompaction.summarize(
            source = source, previousSummary = null, system = "system", leafTemplate = "leaf", mergeTemplate = "merge",
            summaryBudget = 500_000, contextKnown = true, configuredLimit = cap, outputLimit = 8_192, longHistory = longHistory,
            streamPolicy = policy, streamSession = session, provider = provider,
            onStreamUpdate = { phase, update ->
                if (phase == CompactionPhase.MERGING) { assertTrue(starts.isNotEmpty()); streamLevel = starts.last().first }
                if (update.receivedCharacters > 0) {
                    val sent = requests.last()
                    assertEquals(sent.leaf, phase == CompactionPhase.SUMMARIZING)
                    assertEquals(sent.leavesBefore, leaves.size)
                    assertEquals(sent.mergesBefore, merges.size)
                }
            },
            onAttempt = {},
            onLeafSuccess = { characters, count ->
                assertEquals(leaves.size + 1, count)
                assertEquals(requests.count { it.leaf && it.output != null }, count)
                leaves += characters to count
            },
            onMergeSuccess = { count, level ->
                assertEquals(merges.size + 1, count)
                assertEquals(requests.count { !it.leaf && it.output != null }, count)
                assertEquals(starts.last().first, level)
                merges += count to level
            },
            onMergeStarted = { level ->
                assertEquals(starts.size + 1, level)
                assertFalse(requests.any { !it.leaf && it.level == level })
                starts += level to merges.size
            },
        )
    }

    @Test fun fiveMergeTruncationsRecoverWithoutLosingOrderedFragmentsOrSuccessMarkers() = runTest {
        var failures = 0
        var serial = 0
        val probe = Probe(now = { testScheduler.currentTime }) { sent -> when {
            sent.leaf -> stopped("LEAF_B${sent.block}_".padEnd(24_000, ('A'.code + sent.block).toChar()))
            failures < 5 -> AIResponse("REJECTED_TRUNCATED_${++failures}", stopReason = "length")
            else -> stopped(marker(sent, ++serial))
        } }
        val source = List(4) { ('a'.code + it).toChar().toString().repeat(350_000) }
        val result = probe.run(source)
        val leaves = probe.requests.filter(Sent::leaf)
        val merges = probe.requests.filterNot(Sent::leaf)
        val failed = merges.filter { it.output == null }
        val successful = merges.filter { it.output != null }
        assertTrue(probe.session.attempts <= 32)
        assertEquals(probe.requests.size, probe.session.attempts)
        assertEquals(4, result.leafBlocks)
        assertEquals(source.joinToString(""), leaves.joinToString("") { it.text })
        assertEquals(listOf(350_000L, 700_000L, 1_050_000L, 1_400_000L), probe.leaves.map { it.first })
        assertEquals(5, failed.size)
        assertTrue(failed.all { it.level == 1 && it.block == 1 && it.offset == 0L })
        assertEquals(leaves.joinToString("") { it.output!! }, successful.filter { it.level == 1 }.joinToString("") { it.text })
        merges.groupBy { it.level to it.block }.values.forEach { attempts ->
            var committed = 0L
            attempts.forEach { sent -> assertEquals(committed, sent.offset); if (sent.output != null) committed += sent.text.length }
        }
        val recovered = successful.filter { it.level == 1 && it.block == 1 }
        assertTrue(recovered.size > 1)
        assertTrue(recovered.drop(1).any { it.inputTokens > recovered.first().inputTokens })
        var ceiling = maxOf(128, failed.last().inputTokens / 2)
        recovered.forEach { sent -> assertTrue(sent.inputTokens <= ceiling); ceiling = minOf(65_536, ceiling * 2) }
        val pattern = Regex("MERGE_L\\d+_B\\d+_O\\d+_N\\d+")
        successful.groupBy(Sent::level).forEach { (level, produced) ->
            val expected = produced.mapNotNull { it.output }.filter { it != result.summary }
            val future = successful.filter { it.level > level }.joinToString("") { it.text }
            assertEquals(expected, pattern.findAll(future).map { it.value }.filter { it in expected }.toList())
        }
        assertEquals(successful.last().output, result.summary)
        assertEquals(successful.size, result.completedMerges)
        assertEquals((1..result.mergeLevels).toList(), probe.starts.map { it.first })
        assertTrue(probe.requests.none { "REJECTED_TRUNCATED_" in it.content || "UNCOMMITTED_PARTIAL" in it.content })
    }

    @Test fun firstLeafFailureMakesTwentyThreeLeafTreeUnreachableImmediately() = runTest {
        lateinit var probe: Probe
        probe = Probe(now = { testScheduler.currentTime }) { sent ->
            if (probe.requests.size == 1) throw IOException("transient")
            stopped(if (sent.leaf) "LEAF_${sent.block}" else marker(sent, probe.requests.size))
        }
        val error = runCatching { probe.run(List(23) { "x".repeat(350_000) }) }.exceptionOrNull()
        assertEquals("compaction_budget_unreachable", (error as CompactionStreamException).code)
        assertEquals(1, probe.requests.size)
        assertTrue(probe.leaves.isEmpty())
        assertTrue(probe.merges.isEmpty())
        assertTrue(probe.starts.isEmpty())
    }

    @Test fun mergeFailureStopsUnreachableTreeButOneSpareAttemptCompletes() = runTest {
        for (maxAttempts in listOf(12, 13)) {
            var failed = false
            var serial = 0
            val probe = Probe(maxAttempts, { testScheduler.currentTime }) { sent ->
                if (!sent.leaf && sent.level == 1 && sent.block == 2 && !failed) { failed = true; throw IOException("transient") }
                stopped(if (sent.leaf) "LEAF_${sent.block}" else marker(sent, ++serial))
            }
            var result: HierarchicalCompaction.Result? = null
            val error = runCatching { result = probe.run(List(9) { "x".repeat(2_700) }, 1_024, false) }.exceptionOrNull()
            assertEquals(9, probe.leaves.size)
            if (maxAttempts == 12) {
                assertEquals("compaction_budget_unreachable", (error as CompactionStreamException).code)
                assertNull(result)
                assertEquals(11, probe.requests.size)
                assertEquals(listOf(1 to 1), probe.merges)
            } else {
                assertNull(error)
                assertEquals(13, probe.requests.size)
                assertEquals(3, result!!.completedMerges)
                val retry = probe.requests.filter { !it.leaf && it.level == 1 && it.block == 2 }
                assertEquals(2, retry.size)
                assertEquals(retry[0].content, retry[1].content)
                assertEquals(listOf(1 to 0, 2 to 2), probe.starts)
            }
            assertEquals(probe.requests.size, probe.session.attempts)
        }
    }

    @Test fun spareAttemptAllowsLeafRetryWithoutFixedExtraRetryReservations() = runTest {
        lateinit var probe: Probe
        probe = Probe(5, { testScheduler.currentTime }) { sent ->
            if (probe.requests.size == 1) throw IOException("transient")
            stopped(if (sent.leaf) "LEAF_${sent.block}" else marker(sent, 1))
        }
        val result = probe.run(List(3) { "x".repeat(2_700) }, 1_024, false)
        assertEquals(5, probe.requests.size)
        assertEquals(probe.requests[0].content, probe.requests[1].content)
        assertEquals(0, probe.requests[1].leavesBefore)
        assertEquals(listOf(2_700L, 5_400L, 8_100L), probe.leaves.map { it.first })
        assertEquals(3, result.leafBlocks)
        assertEquals(1, result.completedMerges)
    }
}
