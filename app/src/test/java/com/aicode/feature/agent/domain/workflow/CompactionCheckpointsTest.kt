package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.StreamApiException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompactionCheckpointsTest {
    private fun hash(text: String) = CompactionFingerprint().add(text).finish()
    private fun complete(text: String = "  COMPLETE\nexact-END  ") =
        flowOf(AIStreamChunk.Final(AIResponse(text, stopReason = "stop", inputTokens = 9, outputTokens = 3)))

    private fun TestScope.session(
        work: CompactionCheckpoints.Work,
        policy: CompactionStreamPolicy = CompactionStreamPolicy(retryDelayMs = 0),
    ) = CompactionStreamSession(policy) { testScheduler.currentTime }.also { it.restore(work) {} }

    private suspend fun CompactionStreamSession.call(
        request: String = "request",
        budget: Int = 1_024,
        records: MutableList<CompactionStreamAttempt> = mutableListOf(),
        onRetry: suspend (CompactionStreamException) -> Unit = {},
        stream: () -> Flow<AIStreamChunk> = { complete() },
    ): String = withDeadline {
        summarize(stream, {}, { records += it }, onRetry, { hash(request) }, { budget })
    }

    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: CompactionStreamException) {
            assertEquals(code, error.code)
        }
    }

    @Test fun defaultStoreKeepsFourMostRecentlyUsedSessions() = runTest {
        val store = CompactionCheckpoints(now = { testScheduler.currentTime })
        val works = (0..3).map { store.open("s$it", "work") }
        assertEquals(4, store.size())
        assertSame(works[0], store.open("s0", "work"))
        store.open("s4", "work")
        assertEquals(4, store.size())
        assertSame(works[0], store.open("s0", "work"))
        assertSame(works[2], store.open("s2", "work"))
        assertSame(works[3], store.open("s3", "work"))
        assertNotSame(works[1], store.open("s1", "work"))
        assertEquals(4, store.size())
    }

    @Test fun byteLimitIncludesExactFourMiBBoundaryAndEvictsOversizedWork() = runTest {
        val store = CompactionCheckpoints(now = { testScheduler.currentTime })
        val work = store.open("s", "fingerprint")
        val request = hash("request")
        val limit = 4L * 1024 * 1024
        val summary = "x".repeat(((limit - 256 - 96 - request.length * 2) / 2).toInt())
        work.complete(0, CompactionCheckpoints.Block(request, 1_024, summary))
        store.updated("s", work)
        assertEquals(limit, store.bytes())
        assertSame(work, store.open("s", "fingerprint"))
        work.complete(1, CompactionCheckpoints.Block(hash("next"), 1_024, "x"))
        store.updated("s", work)
        assertEquals(0, store.size())
        assertEquals(0L, store.bytes())
        assertNotSame(work, store.open("s", "fingerprint"))
    }

    @Test fun aggregateBytePressureEvictsLeastRecentlyUsedWork() = runTest {
        val block = CompactionCheckpoints.Block(hash("r"), 512, "summary")
        val oneWork = 256L + 96 + block.request.length * 2L + block.summary.length * 2L
        val store = CompactionCheckpoints(maxBytes = oneWork * 2, now = { testScheduler.currentTime })
        val a = store.open("a", "f").also { it.complete(0, block); store.updated("a", it) }
        val b = store.open("b", "f").also { it.complete(0, block); store.updated("b", it) }
        assertEquals(oneWork * 2, store.bytes())
        store.open("a", "f")
        store.open("c", "f")
        assertSame(a, store.open("a", "f"))
        assertEquals(oneWork + 256, store.bytes())
        assertNotSame(b, store.open("b", "f"))
    }

    @Test fun ttlExpiresAtExactlySixtyMinutesWithoutTouchingWork() = runTest {
        val store = CompactionCheckpoints(now = { testScheduler.currentTime })
        val old = store.open("old", "f")
        advanceTimeBy(60 * 60_000L - 1)
        store.open("other", "f")
        assertEquals(2, store.size())
        advanceTimeBy(1)
        assertNotSame(old, store.open("old", "f"))
        assertEquals(2, store.size())
    }

    @Test fun openingOrUpdatingWorkRefreshesItsIdleTtl() = runTest {
        val store = CompactionCheckpoints(ttlMs = 100, now = { testScheduler.currentTime })
        val work = store.open("s", "f")
        advanceTimeBy(99)
        assertSame(work, store.open("s", "f"))
        advanceTimeBy(99)
        store.updated("s", work)
        advanceTimeBy(99)
        assertSame(work, store.open("s", "f"))
        advanceTimeBy(100)
        assertNotSame(work, store.open("s", "f"))
    }

    @Test fun changedFingerprintDropsBlocksAttemptsAndFailureWithoutResurrectingOldWork() = runTest {
        val store = CompactionCheckpoints(now = { testScheduler.currentTime })
        val old = store.open("s", hash("first"))
        old.complete(0, CompactionCheckpoints.Block(hash("request"), 128, "summary"))
        old.attempts = 17
        old.failure = CompactionCheckpoints.Failure(1, 64, 1, 2, "compaction_truncated")
        val changed = store.open("s", hash("changed"))
        assertNotSame(old, changed)
        assertEquals(0, changed.completedCount)
        assertEquals(0, changed.attempts)
        assertNull(changed.failure)
        store.updated("s", old)
        assertSame(changed, store.open("s", hash("changed")))
        assertNotSame(old, store.open("s", hash("first")))
    }

    @Test fun clearingOneSessionCannotRemoveOrReviveAnother() = runTest {
        val store = CompactionCheckpoints(now = { testScheduler.currentTime })
        val a = store.open("a", "f")
        val b = store.open("b", "f")
        store.clear("a")
        store.updated("a", a)
        assertEquals(1, store.size())
        assertSame(b, store.open("b", "f"))
        assertNotSame(a, store.open("a", "f"))
    }

    @Test fun checkpointShapeRetainsOnlyHashesCompletedSummariesAndScalarFailureState() {
        fun fields(type: Class<*>) = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("fingerprint", "touchedAt", "blocks", "attempts", "failure"), fields(CompactionCheckpoints.Work::class.java))
        assertEquals(setOf("request", "materialBudget", "summary"), fields(CompactionCheckpoints.Block::class.java))
        assertEquals(setOf("index", "materialBudget", "retryable", "truncated", "code"), fields(CompactionCheckpoints.Failure::class.java))
        assertEquals(64, hash("history that must not be retained").length)
        assertNotEquals(CompactionFingerprint().add("ab").add("c").finish(), CompactionFingerprint().add("a").add("bc").finish())
        assertEquals(hash("same"), hash("same"))
    }

    @Test fun onlyExplicitCommitPublishesWholeSummaryAndReplayHasNoCallsOrStatistics() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val first = session(work)
        val records = mutableListOf<CompactionStreamAttempt>()
        val full = first.call(records = records)
        assertEquals("  COMPLETE\nexact-END  ", full)
        assertEquals(0, work.completedCount)
        first.commitBlock()
        assertEquals(1, work.completedCount)
        assertEquals(hash("request"), work.block(0)!!.request)
        assertEquals(64, work.block(0)!!.request.length)
        val resumed = session(work)
        assertTrue(resumed.replaying)
        assertEquals(1_024, resumed.materialBudget(8_192))
        assertEquals(full, resumed.call(records = records, stream = { error("Replay must not call the model") }))
        resumed.commitBlock()
        assertFalse(resumed.replaying)
        assertEquals(1, work.completedCount)
        assertEquals(1, resumed.attempts)
        assertEquals(1, records.size)
    }

    @Test fun successfulButUncommittedResponseIsRequestedAgainAfterResume() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val records = mutableListOf<CompactionStreamAttempt>()
        session(work).call(records = records)
        assertEquals(0, work.completedCount)
        val resumed = session(work)
        assertFalse(resumed.replaying)
        assertEquals("fresh", resumed.call(records = records, stream = { complete("fresh") }))
        resumed.commitBlock()
        assertEquals(2, resumed.attempts)
        assertEquals(2, records.size)
        assertEquals("fresh", work.block(0)!!.summary)
    }

    @Test fun replayRequiresExactRequestHashEvenWhenBlockNumberMatches() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        session(work).also { it.call(); it.commitBlock() }
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_checkpoint_mismatch") {
            session(work).call(request = "changed source", records = records, stream = { error("Mismatch must not call model") })
        }
        assertTrue(records.isEmpty())
        assertEquals(1, work.attempts)
    }

    @Test fun partialMissingStopAndNoFinalNeverBecomeCompletedBlocks() = runTest {
        for ((code, stream) in listOf<Pair<String, () -> Flow<AIStreamChunk>>>(
            "compaction_missing_stop" to { flowOf(AIStreamChunk.Final(AIResponse("REJECTED_BODY"))) },
            "compaction_no_final" to { flow { emit(AIStreamChunk.TextDelta("REJECTED_PARTIAL")) } },
            "compaction_invalid_response" to { flowOf(AIStreamChunk.Final(AIResponse("REJECTED_BODY", stopReason = "incomplete"))) },
        )) {
            val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", code)
            val first = session(work)
            failure(code) { first.call(stream = stream) }
            first.commitBlock()
            assertEquals(0, work.completedCount)
            val resumed = session(work)
            assertFalse(resumed.replaying)
            assertEquals("fresh", resumed.call(stream = { complete("fresh") }))
            resumed.commitBlock()
            assertEquals("fresh", work.block(0)!!.summary)
            assertEquals(2, resumed.attempts)
        }
    }

    @Test fun totalTimeoutAfterManyBlocksReplaysOnlyCommittedPrefixAndResendsFailedRequest() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val policy = CompactionStreamPolicy(totalTimeoutMs = 1_000, retryDelayMs = 0)
        val first = session(work, policy)
        val records = mutableListOf<CompactionStreamAttempt>()
        repeat(20) { index -> first.call(request = "request-$index", records = records); first.commitBlock() }
        failure("compaction_total_timeout") {
            first.call(request = "unfinished", records = records, stream = { flow {
                emit(AIStreamChunk.TextDelta("HALF_BODY"))
                awaitCancellation()
            } })
        }
        assertEquals(1_000L, testScheduler.currentTime)
        first.commitBlock()
        assertEquals(20, work.completedCount)
        val resumed = session(work, policy)
        repeat(20) { index ->
            assertEquals("  COMPLETE\nexact-END  ", resumed.call(request = "request-$index", records = records,
                stream = { error("Committed prefix must be replayed") }))
        }
        assertEquals(21, records.size)
        assertEquals("compaction_total_timeout", records.last().errorCode)
        assertEquals("fresh", resumed.call(request = "unfinished", records = records, stream = { complete("fresh") }))
        resumed.commitBlock()
        assertEquals(22, resumed.attempts)
        assertEquals(22, records.size)
        assertEquals(21, work.completedCount)
    }

    @Test fun cumulativeThirtyTwoCallLimitCannotBeResetByResumeOrReplay() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val records = mutableListOf<CompactionStreamAttempt>()
        val first = session(work)
        repeat(30) { index -> first.call(request = "r$index", records = records); first.commitBlock() }
        failure("compaction_no_final") { first.call(request = "failed", records = records, stream = { flow {} }) }
        val resumed = session(work)
        repeat(30) { index -> resumed.call(request = "r$index", records = records, stream = { error("Cached") }) }
        resumed.call(request = "failed", records = records)
        resumed.commitBlock()
        val exhausted = session(work)
        repeat(30) { index -> exhausted.call(request = "r$index", records = records, stream = { error("Cached") }) }
        exhausted.call(request = "failed", records = records, stream = { error("Cached") })
        failure("compaction_attempt_limit") { exhausted.call(request = "33", records = records, stream = { error("Call 33 must not start") }) }
        assertEquals(32, records.size)
        assertEquals(32, work.attempts)
    }

    @Test fun exhaustedBlockRetryBudgetIsNotResetAcrossResume() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_io") { session(work).call(records = records, stream = { flow { throw IOException("offline") } }) }
        assertEquals(2, work.failure!!.retryable)
        failure("compaction_io") { session(work).call(records = records, stream = { error("Exhausted block must not restart") }) }
        assertEquals(2, records.size)
        assertEquals(2, work.attempts)
    }

    @Test fun upstreamOutcomeUnknownKeepsCompletedPrefixAndCannotResetFailedBlockBudget() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val records = mutableListOf<CompactionStreamAttempt>()
        val first = session(work)
        assertEquals("PREFIX", first.call(request = "prefix", records = records, stream = { complete("PREFIX") }))
        first.commitBlock()
        failure("compaction_upstream_interrupted") {
            first.call(request = "unfinished", records = records, stream = { flow {
                emit(AIStreamChunk.TextDelta("REJECTED_PARTIAL"))
                throw StreamApiException("upstream_ws_outcome_unknown", "private upstream detail")
            } })
        }
        first.commitBlock()
        assertEquals(1, work.completedCount)
        assertEquals("PREFIX", work.block(0)!!.summary)
        assertEquals(2, work.failure!!.retryable)
        assertEquals("compaction_upstream_interrupted", work.failure!!.code)
        val resumed = session(work)
        assertEquals("PREFIX", resumed.call(request = "prefix", records = records,
            stream = { error("Committed prefix must not be requested again") }))
        failure("compaction_upstream_interrupted") {
            resumed.call(request = "unfinished", records = records,
                stream = { error("Exhausted block must not restart") })
        }
        assertEquals(3, work.attempts)
        assertEquals(3, records.size)
        assertEquals(1, work.completedCount)
        assertFalse(work.block(0)!!.summary.contains("REJECTED_PARTIAL"))
    }

    @Test fun exhaustedTruncationBudgetIsNotResetAcrossResume() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_truncated") {
            session(work).call(records = records, stream = { flowOf(AIStreamChunk.Final(AIResponse("HALF", stopReason = "length"))) })
        }
        assertEquals(6, work.failure!!.truncated)
        failure("compaction_truncated") { session(work).call(records = records, stream = { error("Truncation budget must not reset") }) }
        assertEquals(6, records.size)
        assertEquals(6, work.attempts)
        assertEquals(0, work.completedCount)
    }

    @Test fun repeatedTotalTimeoutsCannotResetBlockBudgetOrBypassItWithAnotherRequestHash() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val policy = CompactionStreamPolicy(totalTimeoutMs = 50, retryDelayMs = 0)
        val records = mutableListOf<CompactionStreamAttempt>()
        var calls = 0
        repeat(2) { index ->
            failure("compaction_total_timeout") {
                session(work, policy).call(records = records, stream = { flow {
                    calls++
                    emit(AIStreamChunk.TextDelta("HALF_BODY"))
                    awaitCancellation()
                } })
            }
            assertEquals(index + 1, work.failure!!.retryable)
            assertEquals(index + 1, work.attempts)
        }
        failure("compaction_total_timeout") {
            session(work, policy).call(request = "different-hash-same-work", records = records,
                stream = { error("A new hash must not bypass this block's exhausted budget") })
        }
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(2, calls)
        assertEquals(2, records.size)
        assertTrue(records.all { it.errorCode == "compaction_total_timeout" })
        assertEquals(0, work.completedCount)
    }

    @Test fun invalidFinalBeforeDeadlineKeepsFailureBudgetWhenStatisticsCrossDeadline() = runTest {
        for ((code, stop) in listOf("compaction_missing_stop" to null, "compaction_invalid_response" to "incomplete")) {
            val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", code)
            val policy = CompactionStreamPolicy(totalTimeoutMs = 25, retryDelayMs = 0)
            val records = mutableListOf<CompactionStreamAttempt>()
            val before = testScheduler.currentTime
            repeat(2) { index ->
                val resumed = session(work, policy)
                failure("compaction_total_timeout") {
                    resumed.withDeadline { resumed.summarize(
                        stream = { flow { delay(24); emit(AIStreamChunk.Final(AIResponse("REJECTED_BODY", stopReason = stop))) } },
                        onUpdate = {}, onAttempt = { records += it; delay(2) },
                        requestFingerprint = { hash("request") }, materialBudget = { 512 },
                    ) }
                }
                assertEquals(index + 1, work.failure!!.retryable)
                assertEquals(code, work.failure!!.code)
            }
            failure(code) {
                session(work, policy).call(records = records, stream = { error("An audited invalid response must consume the block budget") })
            }
            assertEquals(52L, testScheduler.currentTime - before)
            assertEquals(2, work.attempts)
            assertEquals(2, records.size)
            assertTrue(records.all { it.errorCode == code })
            assertEquals(0, work.completedCount)
        }
    }

    @Test fun failureCountersAndShrunkMaterialBudgetSurviveTimeoutDuringRetryDelay() = runTest {
        val work = CompactionCheckpoints(now = { testScheduler.currentTime }).open("s", "f")
        val policy = CompactionStreamPolicy(totalTimeoutMs = 25, retryDelayMs = 10)
        val records = mutableListOf<CompactionStreamAttempt>()
        var calls = 0
        var budget = 1_024
        val first = session(work, policy)
        failure("compaction_total_timeout") {
            first.withDeadline { first.summarize(
                stream = { flow {
                    if (++calls == 1) throw IOException("retryable")
                    emit(AIStreamChunk.Final(AIResponse("HALF", stopReason = "length")))
                } }, onUpdate = {}, onAttempt = { records += it },
                onRetry = { if (it.code == "compaction_truncated") budget /= 2 },
                requestFingerprint = { hash("request-$budget") }, materialBudget = { budget },
            ) }
        }
        assertEquals(3, calls)
        assertEquals(1, work.failure!!.retryable)
        assertEquals(2, work.failure!!.truncated)
        assertEquals(512, work.failure!!.materialBudget)
        val resumed = session(work, policy)
        assertEquals(512, resumed.materialBudget(1_024))
        assertEquals("fresh", resumed.call(request = "request-512", budget = 512, records = records, stream = { complete("fresh") }))
        resumed.commitBlock()
        assertEquals(3, records.last().retryCount)
        assertEquals(4, work.attempts)
        assertNull(work.failure)
    }
}
