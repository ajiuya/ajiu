package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.context.CompactionStreamState
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.EnrichedHttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompactionSplitRetryTest {
    private fun http499() = EnrichedHttpException("private failed body", 499, "private body", IllegalStateException("local-499"))
    private fun complete() = AIStreamChunk.Final(AIResponse("clean-summary", stopReason = "stop"))

    private fun rejectCommit(block: () -> Unit) {
        try { block(); fail("Foreign or stale fork must not commit") } catch (_: IllegalArgumentException) { }
    }

    private fun appendMaterial(chunk: String, decoded: List<StringBuilder>) {
        val labels = Regex("\\[history-unit (\\d+), character-offset (\\d+)\\]\\n").findAll(chunk).toList()
        assertTrue(labels.isNotEmpty())
        labels.forEachIndexed { index, label ->
            val unit = label.groupValues[1].toInt() - 1
            val offset = label.groupValues[2].toInt()
            assertTrue(unit in decoded.indices)
            assertEquals(decoded[unit].length, offset)
            val end = labels.getOrNull(index + 1)?.range?.first ?: chunk.length
            val wrapped = chunk.substring(label.range.last + 1, end)
            val raw = if (wrapped.endsWith("\n[unit continues]\n")) wrapped.removeSuffix("\n[unit continues]\n") else wrapped.removeSuffix("\n\n")
            assertFalse(raw.firstOrNull()?.isLowSurrogate() == true)
            assertFalse(raw.lastOrNull()?.isHighSurrogate() == true)
            decoded[unit].append(raw)
        }
    }

    @Test fun discardedLargeForkAndSmallerRetryStartAtSameCheckpoint() {
        val source = "fact-中文🙂🚀/exact-id\n".repeat(700)
        val parent = CompactionText.Cursor(listOf(source))
        val failed = parent.fork()
        val large = failed.next(1_024)
        val retry = parent.fork()
        val small = retry.next(512)
        assertEquals(0L, parent.consumedCharacters)
        assertFalse(parent.finished)
        assertEquals(source.length.toLong(), failed.totalCharacters)
        assertTrue(failed.consumedCharacters > retry.consumedCharacters)
        assertTrue(large.startsWith("[history-unit 1, character-offset 0]\n"))
        assertTrue(small.startsWith("[history-unit 1, character-offset 0]\n"))
        parent.commitFrom(retry)
        assertTrue(parent.next(512).startsWith("[history-unit 1, character-offset ${retry.consumedCharacters}]\n"))
    }

    @Test fun successiveCommittedForksCoverEveryUnicodeCharacterExactlyOnce() {
        val units = listOf("header\n" + "A中🙂/path🚀=".repeat(600), "second\n" + "βB𠮷-end\n".repeat(170), "last-唯一事实")
        val parent = CompactionText.Cursor(units)
        val decoded = units.map { StringBuilder() }
        appendMaterial(parent.next(311), decoded)
        var blocks = 0
        while (!parent.finished) {
            val saved = parent.consumedCharacters
            parent.fork().next(1_024)
            assertEquals(saved, parent.consumedCharacters)
            val retry = parent.fork()
            appendMaterial(retry.next(listOf(128, 257, 511)[blocks % 3]), decoded)
            assertEquals(saved, parent.consumedCharacters)
            parent.commitFrom(retry)
            assertEquals(decoded.sumOf { it.length.toLong() }, parent.consumedCharacters)
            assertTrue(++blocks < 200)
        }
        assertEquals(units, decoded.map { it.toString() })
        assertEquals(parent.totalCharacters, parent.consumedCharacters)
    }

    @Test fun boundariesNeverSplitSurrogatePairsAcrossOffsetsAndBudgets() {
        for (prefix in 0..15) {
            val source = "x".repeat(prefix) + "🙂𠮷🚀中".repeat(90) + "END"
            val cursor = CompactionText.Cursor(listOf(source))
            val decoded = listOf(StringBuilder())
            var blocks = 0
            while (!cursor.finished) {
                cursor.fork().next(257)
                val retry = cursor.fork()
                appendMaterial(retry.next(128 + prefix), decoded)
                cursor.commitFrom(retry)
                assertTrue(++blocks < 50)
            }
            assertEquals(source, decoded.single().toString())
        }
    }

    @Test fun foreignCursorWithSameUnitsCannotCommit() {
        val units = listOf("unique".repeat(400))
        val parent = CompactionText.Cursor(units)
        val other = CompactionText.Cursor(units)
        val foreign = other.fork()
        foreign.next(128)
        rejectCommit { parent.commitFrom(foreign) }
        assertEquals(0L, parent.consumedCharacters)
    }

    @Test fun grandchildMustCommitThroughItsDirectParent() {
        val parent = CompactionText.Cursor(listOf("中文🙂fact".repeat(300)))
        val child = parent.fork()
        val grandchild = child.fork()
        grandchild.next(128)
        rejectCommit { parent.commitFrom(grandchild) }
        child.commitFrom(grandchild)
        parent.commitFrom(child)
        assertEquals(grandchild.consumedCharacters, parent.consumedCharacters)
    }

    @Test fun advancingParentInvalidatesPreviousFork() {
        val parent = CompactionText.Cursor(listOf("中文🙂fact".repeat(300)))
        val stale = parent.fork()
        stale.next(512)
        parent.next(128)
        val saved = parent.consumedCharacters
        rejectCommit { parent.commitFrom(stale) }
        assertEquals(saved, parent.consumedCharacters)
    }

    @Test fun committingSiblingInvalidatesBothOldCandidates() {
        val parent = CompactionText.Cursor(listOf("中文🙂fact".repeat(300)))
        val failed = parent.fork().also { it.next(512) }
        val successful = parent.fork().also { it.next(128) }
        parent.commitFrom(successful)
        val saved = parent.consumedCharacters
        rejectCommit { parent.commitFrom(failed) }
        rejectCommit { parent.commitFrom(successful) }
        assertEquals(saved, parent.consumedCharacters)
    }

    @Test fun finishedForkDoesNotFinishParentUntilCommit() {
        val parent = CompactionText.Cursor(listOf("first🙂", "second中文", ""))
        val candidate = parent.fork().also { it.next(1_024) }
        assertTrue(candidate.finished)
        assertFalse(parent.finished)
        assertEquals(0L, parent.consumedCharacters)
        parent.commitFrom(candidate)
        assertTrue(parent.finished)
        assertEquals(parent.totalCharacters, parent.consumedCharacters)
    }

    @Test fun retryCallbackRunsAfterDelayBeforeNextSubscription() = runTest {
        val events = mutableListOf<String>()
        val updates = mutableListOf<CompactionStreamUpdate>()
        val records = mutableListOf<CompactionStreamAttempt>()
        var material = "large"
        val session = CompactionStreamSession(CompactionStreamPolicy(retryDelayMs = 17)) { testScheduler.currentTime }
        val result = session.withDeadline {
            session.summarize(
                stream = { flow {
                    events.add("subscribe:$material:${testScheduler.currentTime}")
                    if (material == "large") { emit(AIStreamChunk.TextDelta("FAILED_PARTIAL")); throw http499() }
                    emit(complete())
                } },
                onUpdate = { updates.add(it) },
                onAttempt = { records.add(it); events.add("record:${it.errorCode}") },
                onRetry = { events.add("retry:${it.code}:${testScheduler.currentTime}"); material = "small" },
            )
        }
        assertEquals("clean-summary", result)
        assertEquals(listOf("subscribe:large:0", "record:compaction_http_499", "retry:compaction_http_499:17", "subscribe:small:17", "record:null"), events)
        assertEquals(listOf(0, 1), records.map { it.retryCount })
        assertEquals(0L, updates.single { it.state == CompactionStreamState.WAITING && it.attempts == 2 }.receivedCharacters)
    }

    @Test fun repeated499GetsTwoCallsAndOneCallback() = runTest {
        var callbacks = 0
        var subscriptions = 0
        val session = CompactionStreamSession(CompactionStreamPolicy(retryDelayMs = 0)) { testScheduler.currentTime }
        val error = runCatching {
            session.withDeadline { session.summarize({ flow { subscriptions++; throw http499() } }, {}, {}, { callbacks++ }) }
        }.exceptionOrNull()
        assertEquals("compaction_http_499", (error as CompactionStreamException).code)
        assertEquals(2, subscriptions)
        assertEquals(1, callbacks)
    }

    @Test fun exhaustedBudgetsNeverInvokeRetryCallback() = runTest {
        for ((policy, code) in listOf(
            CompactionStreamPolicy(maxAttempts = 1) to "compaction_attempt_limit",
            CompactionStreamPolicy(attemptsPerBlock = 1) to "compaction_http_499",
        )) {
            var callbacks = 0
            val session = CompactionStreamSession(policy) { testScheduler.currentTime }
            val error = runCatching { session.withDeadline { session.summarize({ flow { throw http499() } }, {}, {}, { callbacks++ }) } }.exceptionOrNull()
            assertEquals(code, (error as CompactionStreamException).code)
            assertEquals(1, session.attempts)
            assertEquals(0, callbacks)
        }
    }

    @Test fun cancellationDuringRetryDelayDoesNotRebuildOrResubscribe() = runTest {
        var callbacks = 0
        var subscriptions = 0
        val session = CompactionStreamSession(CompactionStreamPolicy(retryDelayMs = 1_000)) { testScheduler.currentTime }
        val job = async { session.withDeadline {
            session.summarize({ flow { subscriptions++; throw http499() } }, {}, {}, { callbacks++ })
        } }
        runCurrent()
        assertEquals(1, subscriptions)
        job.cancelAndJoin()
        assertEquals(0, callbacks)
        assertEquals(1, subscriptions)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun totalDeadlineDuringRetryDelayPreventsCallback() = runTest {
        var callbacks = 0
        var subscriptions = 0
        val session = CompactionStreamSession(CompactionStreamPolicy(totalTimeoutMs = 10, retryDelayMs = 20)) { testScheduler.currentTime }
        val error = runCatching { session.withDeadline {
            session.summarize({ flow { subscriptions++; throw http499() } }, {}, {}, { callbacks++ })
        } }.exceptionOrNull()
        assertEquals("compaction_total_timeout", (error as CompactionStreamException).code)
        assertEquals(1, subscriptions)
        assertEquals(0, callbacks)
        assertEquals(10L, testScheduler.currentTime)
    }
}
