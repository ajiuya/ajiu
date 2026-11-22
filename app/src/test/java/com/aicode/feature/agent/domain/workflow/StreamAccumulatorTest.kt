package com.aicode.feature.agent.domain.workflow

import android.app.Application
import android.os.SystemClock
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class StreamAccumulatorTest {
    private val events = mutableListOf<AgentEvent>()
    private val materializations = mutableListOf<String>()
    private lateinit var stream: StatefulAgentWorkflow.StreamAccumulator

    @Before
    fun setUp() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        stream = StatefulAgentWorkflow.StreamAccumulator(
            callStartElapsed = SystemClock.elapsedRealtime(),
            materialize = { buffer -> buffer.toString().also { materializations += it } }
        ) { events += it }
    }

    @Test
    fun highFrequencyDeltasMaterializeOnlyAtTheSixtyMillisecondBoundary() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta("a"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        repeat(59) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            repeat(10) {
                stream.onChunk(AIStreamChunk.TextDelta("b"))
                stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
            }
        }
        assertEquals(listOf("a", "r"), materializations)
        assertEquals(listOf(AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r")), events)
        assertEquals("a" + "b".repeat(590), stream.text.toString())
        assertEquals("r" + "s".repeat(590), stream.reasoning.toString())

        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta("c"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("t"))
        val text = "a" + "b".repeat(590) + "c"
        val reasoning = "r" + "s".repeat(590) + "t"
        assertEquals(listOf("a", "r", text, reasoning), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r"),
                AgentEvent.AssistantDelta(text), AgentEvent.ReasoningDelta(reasoning)
            ),
            events
        )
        stream.flush()
        stream.flush()
        assertEquals(4, materializations.size)
        assertEquals(4, events.size)
    }

    @Test
    fun textAndReasoningUseIndependentThrottleWindows() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta("a"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(30))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(29))
        stream.onChunk(AIStreamChunk.TextDelta("b"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
        assertEquals(listOf("a", "r"), materializations)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta("c"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("t"))
        assertEquals(listOf("a", "r", "abc"), materializations)
        ShadowSystemClock.advanceBy(Duration.ofMillis(29))
        stream.onChunk(AIStreamChunk.ReasoningDelta("u"))
        assertEquals(listOf("a", "r", "abc"), materializations)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.ReasoningDelta("v"))
        assertEquals(listOf("a", "r", "abc", "rstuv"), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r"),
                AgentEvent.AssistantDelta("abc"), AgentEvent.ReasoningDelta("rstuv")
            ),
            events
        )
    }

    @Test
    fun finalFlushPublishesTheDirtyTailInTextThenReasoningOrderOnlyOnce() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta("a"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
        stream.onChunk(AIStreamChunk.TextDelta("b"))
        val response = AIResponse("ab", reasoning = "rs")
        stream.onChunk(AIStreamChunk.Final(response))
        assertSame(response, stream.finalResponse)
        assertEquals(listOf("a", "r"), materializations)
        assertEquals(2, events.size)

        stream.flush()
        stream.flush()
        assertEquals(listOf("a", "r", "ab", "rs"), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r"),
                AgentEvent.AssistantDelta("ab"), AgentEvent.ReasoningDelta("rs")
            ),
            events
        )
        assertSame(response, stream.finalResponse)
    }

    @Test
    fun flushOnlyMaterializesDirtyBuffersAndEmptyFlushIsANoOp() = runTest {
        stream.flush()
        stream.flush()
        assertTrue(materializations.isEmpty())
        assertTrue(events.isEmpty())
        stream.onChunk(AIStreamChunk.TextDelta("a"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta("b"))
        stream.flush()
        stream.flush()
        assertEquals(listOf("a", "r", "ab"), materializations)

        stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
        stream.flush()
        stream.flush()
        assertEquals(listOf("a", "r", "ab", "rs"), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r"),
                AgentEvent.AssistantDelta("ab"), AgentEvent.ReasoningDelta("rs")
            ),
            events
        )
    }

    @Test
    fun defaultMaterializerProducesImmutableFullSnapshots() = runTest {
        val accumulator = StatefulAgentWorkflow.StreamAccumulator(SystemClock.elapsedRealtime()) { events += it }
        accumulator.onChunk(AIStreamChunk.TextDelta("a"))
        accumulator.onChunk(AIStreamChunk.ReasoningDelta("r"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        accumulator.onChunk(AIStreamChunk.TextDelta("b"))
        accumulator.onChunk(AIStreamChunk.ReasoningDelta("s"))
        accumulator.flush()
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("a"), AgentEvent.ReasoningDelta("r"),
                AgentEvent.AssistantDelta("ab"), AgentEvent.ReasoningDelta("rs")
            ),
            events
        )
        assertEquals("ab", accumulator.text.toString())
        assertEquals("rs", accumulator.reasoning.toString())
    }

    @Test
    fun retryAndKeySwitchDiscardDirtyTailsBeforeReplay() = runTest {
        val error = RetryErrorInfo(RetryErrorKind.TIMEOUT)
        stream.onChunk(AIStreamChunk.TextDelta("old"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("old reasoning"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta(" discarded"))
        stream.onChunk(AIStreamChunk.ReasoningDelta(" discarded"))
        stream.onChunk(AIStreamChunk.Retrying(2, 3, error))
        stream.flush()
        assertEquals(2, materializations.size)
        assertTrue(stream.text.isEmpty())
        assertTrue(stream.reasoning.isEmpty())
        assertEquals(2, stream.retryAttempts)

        stream.onChunk(AIStreamChunk.TextDelta("retry"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("retry reasoning"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta(" discarded"))
        stream.onChunk(AIStreamChunk.ReasoningDelta(" discarded"))
        stream.onChunk(AIStreamChunk.KeySwitched(2, 3))
        stream.flush()
        assertEquals(4, materializations.size)
        assertTrue(stream.text.isEmpty())
        assertTrue(stream.reasoning.isEmpty())
        assertEquals(2, stream.retryAttempts)

        stream.onChunk(AIStreamChunk.TextDelta("final"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("final reasoning"))
        stream.flush()
        stream.flush()
        assertEquals(listOf("old", "old reasoning", "retry", "retry reasoning", "final", "final reasoning"), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("old"), AgentEvent.ReasoningDelta("old reasoning"),
                AgentEvent.Retrying(2, 3, error),
                AgentEvent.AssistantDelta("retry"), AgentEvent.ReasoningDelta("retry reasoning"),
                AgentEvent.KeySwitched(2, 3),
                AgentEvent.AssistantDelta("final"), AgentEvent.ReasoningDelta("final reasoning")
            ),
            events
        )
        assertEquals(0L, stream.ttfbElapsed)
    }

    @Test
    fun retryAndKeySwitchResetSpeedTimingCountersAndPreviewDeduplication() = runTest {
        val error = RetryErrorInfo(RetryErrorKind.TIMEOUT)
        for ((restart, expectedEvent) in listOf(
            AIStreamChunk.Retrying(1, 3, error) to AgentEvent.Retrying(1, 3, error),
            AIStreamChunk.KeySwitched(2, 3) to AgentEvent.KeySwitched(2, 3)
        )) {
            val emitted = mutableListOf<AgentEvent>()
            val accumulator = StatefulAgentWorkflow.StreamAccumulator(SystemClock.elapsedRealtime()) { emitted += it }
            accumulator.onChunk(AIStreamChunk.ToolCallDeclared("writeFile"))
            ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            accumulator.onChunk(AIStreamChunk.ToolCallArgumentsDelta("same-id", "writeFile", "same-preview", 400_000, 0))
            ShadowSystemClock.advanceBy(Duration.ofMillis(750))
            accumulator.emitSpeed()
            assertEquals(100_000.0, emitted.filterIsInstance<AgentEvent.GenerationSpeed>().single().tokensPerSecond, 0.001)
            accumulator.onChunk(AIStreamChunk.Final(AIResponse("", outputTokens = 100), requestDurationMillis = 1_000))
            accumulator.finishSpeed(100)
            assertFalse(emitted.filterIsInstance<AgentEvent.GenerationSpeed>().last().estimated)
            accumulator.onChunk(restart)
            assertEquals(expectedEvent, emitted.last())
            val eventsAfterRestart = emitted.toList()
            accumulator.emitSpeed()
            accumulator.finishSpeed(100)
            accumulator.flush()
            assertEquals(eventsAfterRestart, emitted)

            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            accumulator.onChunk(AIStreamChunk.ToolCallDeclared("writeFile"))
            ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            accumulator.onChunk(AIStreamChunk.ToolCallArgumentsDelta("same-id", "writeFile", "same-preview", 4, 0))
            ShadowSystemClock.advanceBy(Duration.ofMillis(750))
            accumulator.emitSpeed()
            val live = emitted.filterIsInstance<AgentEvent.GenerationSpeed>().last()
            assertEquals(1.0, live.tokensPerSecond, 0.001)
            assertTrue(live.estimated)
            accumulator.onChunk(AIStreamChunk.Final(AIResponse(""), requestDurationMillis = 1_000))
            accumulator.finishSpeed(0)
            assertEquals(AgentEvent.GenerationSpeed(1.0, estimated = true), emitted.last())
            assertEquals(
                listOf(
                    AgentEvent.ToolCallArgumentsPreview("same-id", "writeFile", "same-preview"),
                    AgentEvent.ToolCallArgumentsPreview("same-id", "writeFile", "same-preview")
                ),
                emitted.filterIsInstance<AgentEvent.ToolCallArgumentsPreview>()
            )
        }
    }

    @Test
    fun finalAverageUsesMeasuredRequestDurationRatherThanVisibleOrCleanupTime() = runTest {
        ShadowSystemClock.advanceBy(Duration.ofMillis(6_778))
        stream.onChunk(AIStreamChunk.TextDelta("body"))
        assertEquals(6_778L, stream.ttfbElapsed)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3_222))
        val response = AIResponse("body", outputTokens = 290)
        stream.onChunk(AIStreamChunk.Final(response, requestDurationMillis = 10_000))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        stream.flush()
        stream.finishSpeed(response.outputTokens)
        assertSame(response, stream.finalResponse)
        assertEquals(
            listOf(AgentEvent.AssistantDelta("body"), AgentEvent.GenerationSpeed(29.0, estimated = false)),
            events
        )
        assertEquals(listOf("body"), materializations)
        assertEquals(6_778L, stream.ttfbElapsed)
    }

    @Test
    fun finalOnlyResponseUsesMeasuredDurationAndRecordsTtfb() = runTest {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val response = AIResponse("done", outputTokens = 290)
        stream.onChunk(AIStreamChunk.Final(response, requestDurationMillis = 10_000))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        stream.flush()
        stream.finishSpeed(response.outputTokens)
        assertEquals(listOf(AgentEvent.GenerationSpeed(29.0, estimated = false)), events)
        assertEquals(10_000L, stream.ttfbElapsed)
        assertSame(response, stream.finalResponse)
        assertTrue(stream.text.isEmpty())
        assertTrue(stream.reasoning.isEmpty())
        assertTrue(materializations.isEmpty())
    }

    @Test
    fun missingRequestTimingDoesNotReplaceALiveEstimateWithAFinalAverage() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta("a".repeat(400)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(750))
        stream.onChunk(AIStreamChunk.TextDelta("aaaa"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        stream.emitSpeed()
        val live = events.filterIsInstance<AgentEvent.GenerationSpeed>().single()
        assertEquals(1.0, live.tokensPerSecond, 0.001)
        assertTrue(live.estimated)
        stream.onChunk(AIStreamChunk.Final(AIResponse("done", outputTokens = 290)))
        val beforeFinish = events.toList()
        stream.finishSpeed(290)
        assertEquals(beforeFinish, events)
        assertEquals(live, events.last())
    }

    @Test
    fun finalDurationsBelowQuarterSecondDoNotInventASpeed() = runTest {
        for (duration in listOf(0L, 249L)) {
            val emitted = mutableListOf<AgentEvent>()
            val accumulator = StatefulAgentWorkflow.StreamAccumulator(SystemClock.elapsedRealtime()) { emitted += it }
            accumulator.onChunk(AIStreamChunk.TextDelta("body"))
            accumulator.onChunk(AIStreamChunk.Final(AIResponse("body", outputTokens = 40_000), duration))
            accumulator.finishSpeed(40_000)
            assertEquals(listOf(AgentEvent.AssistantDelta("body")), emitted)
        }
        stream.onChunk(AIStreamChunk.Final(AIResponse("done", outputTokens = 40), requestDurationMillis = 250))
        stream.finishSpeed(40)
        assertEquals(listOf(AgentEvent.GenerationSpeed(160.0, estimated = false)), events)
    }

    @Test
    fun dirtyTextAndReasoningStillContributeToLiveAndEstimatedFinalSpeed() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta("aaaa"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("思"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta("bbbb"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("考"))
        assertEquals(listOf("aaaa", "思"), materializations)
        ShadowSystemClock.advanceBy(Duration.ofMillis(999))
        stream.emitSpeed()
        assertEquals(AgentEvent.GenerationSpeed(2.0, estimated = true), events.last())
        stream.onChunk(AIStreamChunk.Final(AIResponse("aaaabbbb", reasoning = "思考"), requestDurationMillis = 1_000))
        stream.finishSpeed(0)
        assertEquals(AgentEvent.GenerationSpeed(4.0, estimated = true), events.last())
        assertEquals(listOf("aaaa", "思"), materializations)
        stream.flush()
        assertEquals(listOf("aaaa", "思", "aaaabbbb", "思考"), materializations)
    }

    @Test
    fun toolPreviewsRemainImmediateAndDeduplicateByIdNameAndTextRatherThanCounters() = runTest {
        val preview = "{\"path\":"
        val updated = "{\"path\":\"file.kt\""
        stream.onChunk(AIStreamChunk.ToolCallDeclared("writeFile"))
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("one", "writeFile", preview, 400_000, 0))
        assertEquals(
            listOf(AgentEvent.ToolCallPreparing("writeFile"), AgentEvent.ToolCallArgumentsPreview("one", "writeFile", preview)),
            events
        )
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("one", "writeFile", preview, 400_000, 0))
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("one", "writeFile", preview, 400_004, 0))
        assertEquals(2, events.size)
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("one", "editFile", preview, 400_004, 0))
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("one", "editFile", updated, 400_008, 0))
        stream.onChunk(AIStreamChunk.ToolCallArgumentsDelta("two", "editFile", updated, 4, 0))
        assertEquals(
            listOf(
                AgentEvent.ToolCallPreparing("writeFile"),
                AgentEvent.ToolCallArgumentsPreview("one", "writeFile", preview),
                AgentEvent.ToolCallArgumentsPreview("one", "editFile", preview),
                AgentEvent.ToolCallArgumentsPreview("one", "editFile", updated),
                AgentEvent.ToolCallArgumentsPreview("two", "editFile", updated)
            ),
            events
        )
        ShadowSystemClock.advanceBy(Duration.ofMillis(999))
        stream.onChunk(AIStreamChunk.Final(AIResponse(""), requestDurationMillis = 1_000))
        stream.finishSpeed(0)
        assertEquals(AgentEvent.GenerationSpeed(100_003.0, estimated = true), events.last())
        stream.flush()
        assertTrue(materializations.isEmpty())
        assertTrue(stream.text.isEmpty())
        assertTrue(stream.reasoning.isEmpty())
        assertEquals(0L, stream.ttfbElapsed)
    }

    @Test
    fun firstReasoningRecordsTtfbAndLaterTextDoesNotOverwriteIt() = runTest {
        assertNull(stream.ttfbElapsed)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        stream.onChunk(AIStreamChunk.ReasoningDelta("thinking"))
        assertEquals(400L, stream.ttfbElapsed)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        stream.onChunk(AIStreamChunk.TextDelta("body"))
        assertEquals(400L, stream.ttfbElapsed)
        assertEquals(listOf(AgentEvent.ReasoningDelta("thinking"), AgentEvent.AssistantDelta("body")), events)
    }

    @Test
    fun emptyDeltasKeepTheirPublicationAndFlushSemantics() = runTest {
        stream.onChunk(AIStreamChunk.TextDelta(""))
        stream.onChunk(AIStreamChunk.ReasoningDelta(""))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        stream.onChunk(AIStreamChunk.TextDelta(""))
        stream.onChunk(AIStreamChunk.ReasoningDelta(""))
        assertEquals(listOf("", ""), materializations)
        stream.flush()
        stream.flush()
        assertEquals(listOf("", "", "", ""), materializations)
        assertEquals(
            listOf(
                AgentEvent.AssistantDelta(""), AgentEvent.ReasoningDelta(""),
                AgentEvent.AssistantDelta(""), AgentEvent.ReasoningDelta("")
            ),
            events
        )
    }
}
