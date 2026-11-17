package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.context.CompactionStreamState
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.EnrichedHttpException
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompactionStreamSessionTest {
    private fun TestScope.session(policy: CompactionStreamPolicy = CompactionStreamPolicy()) =
        CompactionStreamSession(policy) { testScheduler.currentTime }
    private fun complete(content: String = "summary") = AIStreamChunk.Final(AIResponse(content, stopReason = "stop"))
    private suspend fun CompactionStreamSession.call(
        stream: () -> Flow<AIStreamChunk>,
        updates: MutableList<CompactionStreamUpdate> = mutableListOf(),
        records: MutableList<CompactionStreamAttempt> = mutableListOf(),
    ) = withDeadline { summarize(stream, { updates.add(it) }, { records.add(it) }) }
    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: CompactionStreamException) { assertEquals(code, e.code) }
    }

    @Test fun healthySlowStreamCanExceedOneHundredFiftySeconds() = runTest {
        val s = session()
        val updates = mutableListOf<CompactionStreamUpdate>()
        assertEquals("summary", s.call({ flow {
            repeat(5) { delay(60_000); emit(AIStreamChunk.TextDelta("s")) }
            emit(complete())
        } }, updates))
        assertEquals(300_000L, testScheduler.currentTime)
        assertEquals(1, s.attempts)
        assertEquals(7L, updates.last().receivedCharacters)
    }

    @Test fun reasoningIsProgressButNeverExposedAsBody() = runTest {
        val updates = mutableListOf<CompactionStreamUpdate>()
        assertEquals("summary", session().call({ flow {
            repeat(4) { delay(90_000); emit(AIStreamChunk.ReasoningDelta("private reasoning")) }
            emit(complete())
        } }, updates))
        assertTrue(updates.filter { it.state == CompactionStreamState.RECEIVING }.all { it.receivedCharacters == 0L })
        assertFalse(updates.joinToString().contains("private reasoning"))
    }

    @Test fun firstChunkTimeoutRetriesOnlyOnce() = runTest {
        val records = mutableListOf<CompactionStreamAttempt>()
        val s = session()
        failure("compaction_first_chunk_timeout") { s.call({ flow { awaitCancellation() } }, records = records) }
        assertEquals(2, s.attempts)
        assertEquals(241_000L, testScheduler.currentTime)
        assertTrue(records.all { it.errorCode == "compaction_first_chunk_timeout" })
    }

    @Test fun heartbeatUpdatesElapsedWithoutExtendingFirstChunkDeadline() = runTest {
        val updates = mutableListOf<CompactionStreamUpdate>()
        failure("compaction_first_chunk_timeout") {
            session(CompactionStreamPolicy(attemptsPerBlock = 1, firstChunkTimeoutMs = 5_000))
                .call({ flow { awaitCancellation() } }, updates)
        }
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), updates.map { it.elapsedMs })
        assertTrue(updates.all { it.state == CompactionStreamState.WAITING && it.attempts == 1 && it.receivedCharacters == 0L })
    }

    @Test fun receivingHeartbeatDoesNotKeepStalledBodyAlive() = runTest {
        val updates = mutableListOf<CompactionStreamUpdate>()
        failure("compaction_idle_timeout") {
            session(CompactionStreamPolicy(attemptsPerBlock = 1, idleTimeoutMs = 5_000))
                .call({ flow { emit(AIStreamChunk.TextDelta("abc")); awaitCancellation() } }, updates)
        }
        val receiving = updates.filter { it.state == CompactionStreamState.RECEIVING }
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), receiving.map { it.elapsedMs })
        assertTrue(receiving.all { it.attempts == 1 && it.receivedCharacters == 3L })
    }

    @Test fun emptyDeltasCannotKeepFirstByteOrStalledBodyAlive() = runTest {
        for (body in listOf(false, true)) {
            val records = mutableListOf<CompactionStreamAttempt>()
            val before = testScheduler.currentTime
            val code = if (body) "compaction_idle_timeout" else "compaction_first_chunk_timeout"
            failure(code) { session(CompactionStreamPolicy(attemptsPerBlock = 1)).call({ flow {
                if (body) emit(AIStreamChunk.TextDelta("partial"))
                repeat(30) { delay(10_000); emit(AIStreamChunk.TextDelta("")); emit(AIStreamChunk.ReasoningDelta("")) }
            } }, records = records) }
            assertEquals(120_000L, testScheduler.currentTime - before)
            assertEquals(0, records.single().outputTokens)
            assertNull(records.single().response)
        }
    }

    @Test fun totalDeadlineStopsContinuouslyHealthyStream() = runTest {
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_total_timeout") { session().call({ flow {
            while (true) { delay(60_000); emit(AIStreamChunk.TextDelta("progress")) }
        } }, records = records) }
        assertEquals(1_200_000L, testScheduler.currentTime)
        assertEquals("compaction_total_timeout", records.single().errorCode)
    }

    @Test fun totalDeadlineIsSharedAcrossBlocks() = runTest {
        val s = session(CompactionStreamPolicy(totalTimeoutMs = 200_000))
        failure("compaction_total_timeout") {
            s.withDeadline { repeat(4) { s.summarize({ flow { delay(60_000); emit(complete()) } }, {}, {}) } }
        }
        assertEquals(200_000L, testScheduler.currentTime)
        assertEquals(4, s.attempts)
    }

    @Test fun eofWithoutFinalDiscardsPartialAndDoesNotInventUsage() = runTest {
        val s = session()
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_no_final") { s.call({ flow { emit(AIStreamChunk.TextDelta("plausible summary")) } }, records = records) }
        assertEquals(1, s.attempts)
        assertEquals(0, records.single().outputTokens)
        assertNull(records.single().response)
    }

    @Test fun invalidFinalKindsAreNotRetried() = runTest {
        for ((code, response) in listOf(
            "compaction_empty" to AIResponse("", stopReason = "stop"),
            "compaction_aborted" to AIResponse("blocked", stopReason = "refusal"),
            "compaction_tools" to AIResponse("text", stopReason = "stop", toolCalls = listOf(ToolCall("id", "read", emptyMap()))),
            "compaction_images" to AIResponse("text", stopReason = "stop", images = listOf(AgentImage("image/png", "secret"))),
            "compaction_invalid_response" to AIResponse("text", stopReason = "incomplete"),
            "compaction_invalid_response" to AIResponse("text"),
        )) {
            val s = session()
            val records = mutableListOf<CompactionStreamAttempt>()
            failure(code) { s.call({ flow { emit(AIStreamChunk.Final(response)) } }, records = records) }
            assertEquals(1, s.attempts)
            assertEquals(code, records.single().errorCode)
        }
    }

    @Test fun truncationRetriesDiscardPreviousPartial() = runTest {
        var calls = 0
        val records = mutableListOf<CompactionStreamAttempt>()
        assertEquals("complete", session().call({ flow {
            if (++calls == 1) {
                emit(AIStreamChunk.TextDelta("partial"))
                emit(AIStreamChunk.Final(AIResponse("partial", stopReason = "length")))
            } else emit(complete("complete"))
        } }, records = records))
        assertEquals(listOf("compaction_truncated", null), records.map { it.errorCode })
        assertEquals(1, records.last().retryCount)
    }

    @Test fun sixTruncationsExhaustBlockBudget() = runTest {
        val s = session()
        failure("compaction_truncated") {
            s.call({ flow { emit(AIStreamChunk.Final(AIResponse("partial", stopReason = "length"))) } })
        }
        assertEquals(6, s.attempts)
    }

    @Test fun finalFollowedByFailureIsNotSuccessful() = runTest {
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_io") { session().call({ flow { emit(complete()); throw IOException("secret") } }, records = records) }
        assertEquals(2, records.size)
        assertTrue(records.all { it.errorCode == "compaction_io" })
    }

    @Test fun duplicateFinalAndContentAfterFinalAreInvalid() = runTest {
        for (after in listOf(complete(), AIStreamChunk.TextDelta("extra"), AIStreamChunk.ReasoningDelta("extra"))) {
            failure("compaction_invalid_response") { session().call({ flow { emit(complete()); emit(after) } }) }
        }
    }

    @Test fun toolChunksAndHiddenKeySwitchAreRejected() = runTest {
        for (chunk in listOf(AIStreamChunk.ToolCallDeclared("read"), AIStreamChunk.ToolCallArgumentsDelta("id", "read", "{}"))) {
            failure("compaction_tools") { session().call({ flow { emit(chunk); emit(complete()) } }) }
        }
        failure("compaction_invalid_response") { session().call({ flow { emit(AIStreamChunk.KeySwitched(2, 3)) } }) }
    }

    @Test fun retryClearsPartialAndUsageIsOnlyTakenFromFinal() = runTest {
        var calls = 0
        val updates = mutableListOf<CompactionStreamUpdate>()
        val records = mutableListOf<CompactionStreamAttempt>()
        assertEquals("fresh", session().call({ flow {
            if (++calls == 1) { emit(AIStreamChunk.TextDelta("discard me")); throw IOException("credential") }
            emit(AIStreamChunk.TextDelta("fresh"))
            emit(AIStreamChunk.Final(AIResponse("fresh", stopReason = "stop", inputTokens = 99, outputTokens = 20)))
        } }, updates, records))
        assertEquals(listOf("compaction_io", null), records.map { it.errorCode })
        assertEquals(listOf(0, 20), records.map { it.outputTokens })
        assertEquals(99, records.last().response!!.inputTokens)
        assertEquals(0L, updates.single { it.state == CompactionStreamState.RETRYING }.receivedCharacters)
        assertEquals(0L, updates.single { it.state == CompactionStreamState.WAITING && it.attempts == 2 }.receivedCharacters)
        assertEquals(5L, updates.last().receivedCharacters)
    }

    @Test fun globalBudgetCountsRetriesAndNeverStartsThirtyThirdCall() = runTest {
        val s = session()
        val records = mutableListOf<CompactionStreamAttempt>()
        var calls = 0
        repeat(16) { s.call({ flow { if (++calls % 2 == 1) throw IOException(); emit(complete()) } }, records = records) }
        failure("compaction_attempt_limit") { s.call({ flow { calls++; emit(complete()) } }, records = records) }
        assertEquals(32, calls)
        assertEquals(32, records.size)
    }

    @Test fun exhaustedGlobalBudgetCannotStartRetry() = runTest {
        val records = mutableListOf<CompactionStreamAttempt>()
        failure("compaction_attempt_limit") {
            session(CompactionStreamPolicy(maxAttempts = 1)).call({ flow { throw IOException() } }, records = records)
        }
        assertEquals(1, records.size)
    }

    @Test fun safeHttpClassificationHonorsAuthAndParameterErrors() = runTest {
        for (http in listOf(400, 401, 403, 404, 408, 429, 499, 500, 524)) {
            val records = mutableListOf<CompactionStreamAttempt>()
            failure("compaction_http_$http") { session().call({ flow {
                throw EnrichedHttpException("private body", http, "private", IOException("wrapper"))
            } }, records = records) }
            assertEquals(if (http in listOf(408, 429, 499, 500, 524)) 2 else 1, records.size)
            assertTrue(records.all { it.errorCode == "compaction_http_$http" })
        }
    }

    @Test fun streamApiCodesAreAllowlistedAndUnsafeExceptionsBecomeFixedCodes() = runTest {
        for ((raw, code, count) in listOf(
            Triple("invalid_request_error", "compaction_invalid_response", 1),
            Triple("private-provider-detail", "compaction_invalid_response", 1),
            Triple("server_error", "compaction_http_503", 2),
            Triple("rate_limit_error", "compaction_http_429", 2),
        )) {
            val records = mutableListOf<CompactionStreamAttempt>()
            failure(code) { session().call({ flow { throw StreamApiException(raw, "private content") } }, records = records) }
            assertEquals(count, records.size)
        }
        val error = CompactionStreamErrors.classify(IllegalArgumentException("private provider detail"))
        assertEquals("compaction_failed", error.message)
        assertNull(error.cause)
    }

    @Test fun progressIsThrottledWithoutExposingBody() = runTest {
        val updates = mutableListOf<CompactionStreamUpdate>()
        session().call({ flow {
            repeat(1_000) { emit(AIStreamChunk.TextDelta("secret")); delay(1) }
            emit(complete("secret".repeat(1_000)))
        } }, updates)
        assertTrue(updates.size in 3..7)
        assertEquals(6_000L, updates.last().receivedCharacters)
        assertFalse(updates.joinToString().contains("secret"))
    }

    @Test fun bookkeepingFailureCannotMaskSuccessOrOriginalFailure() = runTest {
        assertEquals("summary", session().withDeadline {
            session().summarize({ flow { emit(complete()) } }, {}, { throw IllegalStateException("private database detail") })
        })
        val s = session(CompactionStreamPolicy(attemptsPerBlock = 1))
        failure("compaction_io") {
            s.withDeadline { s.summarize({ flow { throw IOException("network") } }, {}, { throw IllegalStateException("database") }) }
        }
    }

    @Test fun hangingBookkeepingIsBoundedOnSuccessAndCancellation() = runTest {
        val s = session()
        assertEquals("summary", s.withDeadline { s.summarize({ flow { emit(complete()) } }, {}, { awaitCancellation() }) })
        assertEquals(2_000L, testScheduler.currentTime)
        val entered = CompletableDeferred<Unit>()
        val job = async { s.withDeadline { s.summarize({ flow { entered.complete(Unit); awaitCancellation() } }, {}, { awaitCancellation() }) } }
        entered.await(); job.cancel(); job.join()
        assertEquals(4_000L, testScheduler.currentTime)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellationRecordsOnlyCurrentAttempt() = runTest {
        val records = mutableListOf<CompactionStreamAttempt>()
        val s = session()
        val entered = CompletableDeferred<Unit>()
        val job = async { s.call({ flow { entered.complete(Unit); awaitCancellation() } }, records = records) }
        entered.await(); job.cancel(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, records.size)
        assertEquals("compaction_cancelled", records.single().errorCode)
    }

    @Test fun ownersOnlyCancelTargetSession() = runTest {
        val owners = CompactionStreamOwners()
        val a = async { owners.run("a") { awaitCancellation() } }
        val b = async { owners.run("b") { awaitCancellation() } }
        runCurrent()
        assertFalse(owners.cancel("chat-only"))
        assertTrue(owners.isActive("a"))
        assertTrue(owners.cancel("a")); a.join()
        assertFalse(owners.isActive("a"))
        assertTrue(b.isActive)
        b.cancel(); b.join()
    }

    @Test fun lateCancelAndOldOwnerCannotDisableNewOwnerWhileSavingStillOwnsSession() = runTest {
        val owners = CompactionStreamOwners()
        val released = CompletableDeferred<kotlinx.coroutines.Job>()
        val oldDone = CompletableDeferred<Unit>()
        val old = async { owners.run("same") {
            val owner = currentCoroutineContext().job
            owners.finishCancelable("same", owner)
            released.complete(owner); oldDone.await()
        } }
        val oldOwner = released.await()
        assertTrue(owners.isActive("same"))
        assertFalse(owners.cancel("same"))
        failure("compaction_busy") { owners.run("same") { fail("Saving must retain ownership") } }
        oldDone.complete(Unit); old.await()
        val fresh = async { owners.run("same") { awaitCancellation() } }
        runCurrent()
        owners.finishCancelable("same", oldOwner)
        assertTrue(owners.cancel("same")); fresh.join()
    }

    @Test fun overlappingSessionDoesNotReplaceActiveOwner() = runTest {
        val owners = CompactionStreamOwners()
        val active = async { owners.run("same") { awaitCancellation() } }
        runCurrent()
        failure("compaction_busy") { owners.run("same") { fail("Must not enter") } }
        assertTrue(owners.cancel("same")); active.join()
    }
}
