package com.aicode.feature.agent.domain.context

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class CompactionProgressTest {
    @Test fun preparationStartsAtZeroWithoutAssumedBlockCount() {
        val progress = CompactionProgress()
        assertEquals(CompactionPhase.PREPARING, progress.phase)
        assertEquals(0, progress.percent)
        assertEquals(0L, progress.completedCharacters)
        assertEquals(0L, progress.totalCharacters)
        assertEquals(0, progress.completedBlocks)
        assertNull(progress.totalBlocks)
        assertFalse(progress.isTerminal)
    }

    @Test fun successfulSourceWorkDeterminesPercentAndOnlyLastBlockKnowsTotal() {
        val first = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(1_000), 250, 1)
        assertEquals(22, first.percent)
        assertEquals(250L, first.completedCharacters)
        assertEquals(1_000L, first.totalCharacters)
        assertEquals(1, first.completedBlocks)
        assertNull(first.totalBlocks)
        val last = CompactionProgressPolicy.summarized(first, 1_000, 3, 3)
        assertEquals(90, last.percent)
        assertEquals(3, last.totalBlocks)
        assertFalse(last.isTerminal)
    }

    @Test fun phaseAndRepeatedReportsDoNotInventWorkWhileModelIsPending() {
        val first = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(1_000), 500, 1)
        repeat(100) {
            assertEquals(first, CompactionProgressPolicy.withPhase(first, CompactionPhase.SUMMARIZING))
            assertEquals(first, CompactionProgressPolicy.summarized(first, 500, 1))
        }
        val validating = CompactionProgressPolicy.withPhase(first, CompactionPhase.VALIDATING)
        assertEquals(45, validating.percent)
        assertEquals(first.completedCharacters, validating.completedCharacters)
    }

    @Test fun mergingHoldsSourceProgressAndCountsMergesSeparately() {
        val leaves = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(1_000), 1_000, 4, 4)
        val merging = CompactionProgressPolicy.withPhase(leaves.copy(completedMerges = 3, mergeLevel = 2), CompactionPhase.MERGING)
        assertEquals(90, merging.percent)
        assertEquals(1_000L, merging.completedCharacters)
        assertEquals(4, merging.completedBlocks)
        assertEquals(3, merging.completedMerges)
        assertEquals(2, merging.mergeLevel)
        assertFalse(merging.isTerminal)
    }

    @Test fun onlyCommittedOrConfirmedUnchangedReachesOneHundred() {
        val summarized = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(10), 10, 1, 1)
        val validating = CompactionProgressPolicy.withPhase(summarized, CompactionPhase.VALIDATING)
        val saving = CompactionProgressPolicy.withPhase(validating, CompactionPhase.SAVING)
        assertEquals(90, validating.percent)
        assertEquals(95, saving.percent)
        assertFalse(saving.isTerminal)
        for (phase in listOf(CompactionPhase.COMPLETED, CompactionPhase.UNCHANGED)) {
            val terminal = CompactionProgressPolicy.withPhase(saving, phase)
            assertEquals(100, terminal.percent)
            assertTrue(terminal.isTerminal)
            assertEquals(10L, terminal.completedCharacters)
        }
    }

    @Test fun failureAndCancellationPreserveWorkAndCannotBecomeSuccess() {
        val partial = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(1_000), 400, 2)
        for (previous in listOf(CompactionProgress(), partial, CompactionProgressPolicy.withPhase(partial, CompactionPhase.SAVING))) {
            for (phase in listOf(CompactionPhase.FAILED, CompactionPhase.CANCELLED)) {
                val terminal = CompactionProgressPolicy.withPhase(previous, phase)
                assertTrue(terminal.isTerminal)
                assertEquals(previous.percent, terminal.percent)
                assertEquals(previous.completedCharacters, terminal.completedCharacters)
                assertEquals(previous.completedBlocks, terminal.completedBlocks)
                assertTrue(terminal.percent < 100)
                assertSame(terminal, CompactionProgressPolicy.withPhase(terminal, CompactionPhase.COMPLETED))
                assertSame(terminal, CompactionProgressPolicy.summarized(terminal, 1_000, 3, 3))
            }
        }
    }

    @Test fun countersAreMonotonicAndBounded() {
        val first = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(1_000), 600, 2)
        assertEquals(first, CompactionProgressPolicy.summarized(first, 100, 1))
        val bounded = CompactionProgressPolicy.summarized(first, Long.MAX_VALUE, 3, 1)
        assertEquals(1_000L, bounded.completedCharacters)
        assertEquals(90, bounded.percent)
        assertEquals(3, bounded.totalBlocks)
        assertEquals(0L, CompactionProgressPolicy.summarizing(-1).totalCharacters)
        assertEquals(0, CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(0), 0, 0).percent)
    }

    @Test fun largeLongCountersDoNotOverflowOrRoundUpPrematurely() {
        for (total in listOf(1L, 3L, 101L, Int.MAX_VALUE.toLong() + 123, Long.MAX_VALUE)) {
            for (completed in listOf(0L, total / 3, total / 2, total - 1, total)) {
                val progress = CompactionProgressPolicy.summarized(CompactionProgressPolicy.summarizing(total), completed, 1)
                val expected = BigInteger.valueOf(completed).multiply(BigInteger.valueOf(90)).divide(BigInteger.valueOf(total)).toInt()
                assertEquals("$completed / $total", expected, progress.percent)
            }
        }
        assertEquals(89, CompactionProgressPolicy.summarized(
            CompactionProgressPolicy.summarizing(Long.MAX_VALUE), Long.MAX_VALUE - 1, 1,
        ).percent)
    }
}
