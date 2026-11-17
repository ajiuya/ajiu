package com.aicode.feature.agent.domain.recovery

import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.RecoveryDraftEntity
import com.aicode.feature.agent.presentation.AgentAttachment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRepositoryTest {
    private val fixture = RecoveryDaoFixture()
    private val repository = RecoveryRepository(fixture.dao)
    private val payload = RecoveryRequestPayload("User request", "~/workspace")

    @Test
    fun initializeRunsOnceAcrossConcurrentEntrypointsAndDoesNotClaimQueuedWork() = runTest {
        fixture.enqueue()

        (1..20).map { async { repository.initialize() } }.awaitAll()
        repository.initialize()

        assertEquals(1, fixture.initializations)
        assertEquals("AWAITING_CONFIRMATION", fixture.requests.getValue("request").status)
        assertTrue(fixture.runs.isEmpty())
        assertTrue(fixture.messages.isEmpty())
        assertNull(repository.claim("request", "local"))
    }

    @Test
    fun unclaimedAcceptedRequestCanBecomeVisibleWithoutRestartAndNeedsConfirmation() = runTest {
        val request = repository.enqueue("session", payload, "local")
        assertNull(repository.claim(request.id, "different-environment"))
        assertTrue(repository.awaitConfirmation(request.id))
        val pending = repository.observePending("session").first().single()
        assertEquals(RecoveryStatus.AWAITING_CONFIRMATION, pending.request.status)
        assertEquals(request.inputMessageId, pending.request.inputMessageId)
        assertNull(repository.claim(request.id, "local"))
        val run = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        assertFalse(repository.awaitConfirmation(request.id))
        assertTrue(repository.isCurrent(run.id))
    }

    @Test
    fun latePendingCardAbandonCannotRetireNewlyClaimedRun() = runTest {
        val request = repository.enqueue("session", payload, "local")
        assertTrue(repository.awaitConfirmation(request.id))
        val oldCard = repository.observePending("session").first().single()
        assertEquals(RecoveryStatus.AWAITING_CONFIRMATION, oldCard.request.status)
        val run = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        val writes = fixture.writes.toList()

        assertFalse(repository.abandonPending(oldCard.request.id, "session"))
        assertEquals(writes, fixture.writes)
        assertTrue(repository.isCurrent(run.id))
        assertEquals(RecoveryStatus.RUNNING, repository.request(request.id)?.status)
        assertTrue(repository.finish(run.id, RecoveryStatus.INTERRUPTED))
        assertTrue(repository.abandonPending(request.id, "session"))
        assertEquals(RecoveryStatus.ABANDONED, repository.request(request.id)?.status)
    }

    @Test
    fun pendingCardAbandonRequiresSessionGenerationAndRecoverableStatus() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val queuedWrites = fixture.writes.toList()
        assertFalse(repository.abandonPending(request.id, "session"))
        assertEquals(queuedWrites, fixture.writes)
        assertTrue(repository.awaitConfirmation(request.id))
        val pendingWrites = fixture.writes.toList()
        assertFalse(repository.abandonPending(request.id, "other-session"))
        assertEquals(pendingWrites, fixture.writes)
        val gate = fixture.sessions.getValue("session")
        fixture.sessions["session"] = gate.copy(executionGeneration = gate.executionGeneration + 1)
        assertFalse(repository.abandonPending(request.id, "session"))
        assertEquals(pendingWrites, fixture.writes)
        assertEquals(RecoveryStatus.AWAITING_CONFIRMATION, repository.request(request.id)?.status)
    }

    @Test
    fun confirmedRecoveryReturnsNewRunWithOriginalInputIdAndTypedCardState() = runTest {
        fixture.enqueue()
        val old = fixture.claim()
        val step = checkNotNull(fixture.dao.beginRecoveryStep(old.id, "owner", 3_000))
        assertTrue(fixture.dao.saveRecoveryDraft(old.id, "owner", step, "Visible body only", 4_000, 500, 32_768))

        val card = repository.observePending("session").first().single()

        assertEquals(RecoveryStatus.INTERRUPTED, card.request.status)
        assertEquals(RecoveryStatus.INTERRUPTED, card.run?.status)
        assertEquals("Visible body only", card.draft?.text)
        assertNull(repository.claim("request", "local"))
        val resumed = checkNotNull(repository.claim("request", "local", confirmed = true))
        assertNotEquals(old.id, resumed.id)
        assertEquals(card.request.inputMessageId, repository.request("request")?.inputMessageId)
        assertEquals(resumed, repository.runFor("request"))
        assertTrue(repository.isCurrent(resumed.id))
        assertFalse(repository.isCurrent(old.id))
        assertEquals(1, fixture.initializations)
    }

    @Test
    fun enqueueIsPersistentAndIdempotentAndHoldsDoNotGrantConfirmedDispatch() = runTest {
        val first = repository.enqueue("session", payload, "local", requestId = "request", inputMessageId = "stable-input")
        val again = repository.enqueue("session", payload, "local", requestId = "request")
        assertEquals(first, again)
        assertEquals("stable-input", again.inputMessageId)
        assertTrue(repository.holdSession("session", "plan_rejected"))
        assertNull(repository.claim(first.id, "local", confirmed = true))
        assertEquals("plan_rejected", repository.observePending("session").first().single().holdReason)
        assertTrue(repository.clearExplicitHold("session"))
        assertNull(repository.claim(first.id, "local"))
        assertTrue(fixture.runs.isEmpty())
        assertTrue(fixture.messages.isEmpty())
    }

    @Test
    fun draftKeepsHeadAndTailWithinUtf8BudgetWithoutStoringPartialToolOrPrivateFields() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val run = checkNotNull(repository.claim(request.id, "local"))
        val step = checkNotNull(repository.beginStep(run.id))
        val visible = "开头\uD83D\uDE80" + "汉\uD83D\uDE80ab".repeat(10_000) + "继续这里\uD83D\uDE80"

        assertTrue(repository.saveDraft(run.id, step, visible))

        val draft = fixture.drafts.getValue(run.id)
        val bytes = draft.text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= RecoveryRepository.MAX_DRAFT_BYTES)
        assertTrue(draft.text.startsWith("开头\uD83D\uDE80"))
        assertTrue(draft.text.endsWith("继续这里\uD83D\uDE80"))
        assertEquals(2, draft.text.split(RecoveryRepository.DRAFT_OMITTED_MARKER).size)
        assertEquals(draft.text, bytes.toString(Charsets.UTF_8))
        assertEquals(draft.text, Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        assertEquals(setOf("runId", "step", "revision", "text", "updatedAt"),
            Json.parseToJsonElement(Json.encodeToString(RecoveryDraft(draft.runId, draft.step, draft.revision, draft.text, draft.updatedAt))).jsonObject.keys)
        assertTrue(repository.finish(run.id, RecoveryStatus.INTERRUPTED))
        val resumed = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        assertEquals(RecoveryResumeSnapshot(RecoveryStage.TEXT, draft.text, run.id, step.step, step.revision), resumed.resumeSnapshot)
        assertTrue(fixture.tools.isEmpty())
    }

    @Test
    fun claimedDraftSurvivesRepeatedRestartsBeforeAnyResumeInputIsStored() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val first = checkNotNull(repository.claim(request.id, "local"))
        assertTrue(repository.commitInput(first.id, AgentMessageEntity(request.inputMessageId, "session", "USER", payload.request, 1)))
        val step = checkNotNull(repository.beginStep(first.id))
        assertTrue(repository.saveDraft(first.id, step, "Visible tail before first restart", force = true))
        assertTrue(repository.finish(first.id, RecoveryStatus.INTERRUPTED))
        val second = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        assertEquals(first.id, second.resumeSnapshot?.sourceRunId)
        assertTrue(checkNotNull(second.resumeSnapshot).inputMessageExists)
        assertEquals(listOf(request.inputMessageId), fixture.messages.keys.toList())

        val afterSecondRestart = RecoveryRepository(fixture.dao)
        val secondCard = afterSecondRestart.observePending("session").first().single()
        assertEquals(second.id, secondCard.run?.id)
        assertEquals(RecoveryStage.TEXT, secondCard.stage)
        assertEquals("Visible tail before first restart", secondCard.draft?.text)
        val third = checkNotNull(afterSecondRestart.claim(request.id, "local", confirmed = true))
        assertEquals(second.id, third.resumeSnapshot?.sourceRunId)
        assertEquals(second.resumeSnapshot?.draftText, third.resumeSnapshot?.draftText)
        assertEquals(0, third.resumeSnapshot?.sourceStep)
        assertEquals(listOf(request.inputMessageId), fixture.messages.keys.toList())

        val afterThirdRestart = RecoveryRepository(fixture.dao)
        val thirdCard = afterThirdRestart.observePending("session").first().single()
        assertEquals(third.id, thirdCard.run?.id)
        assertEquals(RecoveryStatus.INTERRUPTED, thirdCard.request.status)
        assertEquals("Visible tail before first restart", thirdCard.draft?.text)
        assertFalse(repository.saveDraft(first.id, step, "Late old callback", force = true))
        assertEquals(second.resumeSnapshot, afterThirdRestart.run(second.id)?.resumeSnapshot)
    }

    @Test
    fun activeDraftWinsOverInheritedSnapshotAndStaleRevisionCannotReplaceIt() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val first = checkNotNull(repository.claim(request.id, "local"))
        val firstStep = checkNotNull(repository.beginStep(first.id))
        assertTrue(repository.saveDraft(first.id, firstStep, "Inherited visible draft"))
        assertTrue(repository.finish(first.id, RecoveryStatus.INTERRUPTED))
        val second = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        val secondStep = checkNotNull(repository.beginStep(second.id))
        assertTrue(repository.saveDraft(second.id, secondStep, "New active visible draft"))
        assertEquals("New active visible draft", repository.observePending("session").first().single().draft?.text)
        val restarted = checkNotNull(repository.restartStream(second.id, secondStep))
        fixture.drafts[second.id] = RecoveryDraftEntity(second.id, secondStep.step, secondStep.revision, "Stale revision", 1)
        assertEquals("Inherited visible draft", repository.observePending("session").first().single().draft?.text)
        assertTrue(repository.saveDraft(second.id, restarted, "Latest active draft", force = true))
        assertTrue(repository.finish(second.id, RecoveryStatus.INTERRUPTED))

        val third = checkNotNull(repository.claim(request.id, "local", confirmed = true))

        assertEquals(RecoveryResumeSnapshot(RecoveryStage.TEXT, "Latest active draft", second.id,
            restarted.step, restarted.revision), third.resumeSnapshot)
    }

    @Test
    fun committedAssistantConsumesInheritedDraftAndLaterRecoveryDoesNotReviveEarlierDraft() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val first = checkNotNull(repository.claim(request.id, "local"))
        val firstStep = checkNotNull(repository.beginStep(first.id))
        assertTrue(repository.saveDraft(first.id, firstStep, "Old uncommitted visible draft"))
        assertTrue(repository.finish(first.id, RecoveryStatus.INTERRUPTED))
        val second = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        val input = AgentMessageEntity("resume_${second.id}", "session", "USER", "Continue", 1)
        assertTrue(repository.commitInput(second.id, input))
        assertEquals(request.inputMessageId, fixture.messages.getValue(input.id).continuationOfMessageId)
        assertEquals("Old uncommitted visible draft", fixture.messages.getValue(input.id).continuationDraft)
        val secondStep = checkNotNull(repository.beginStep(second.id))
        val committed = AgentMessageEntity("resumed-assistant", "session", "ASSISTANT", "Completed visible response", 2,
            reasoning = "Private reasoning", signature = "Private signature", thinkingBlocksJson = "[]")
        assertTrue(repository.commitAssistant(second.id, secondStep, committed))
        assertEquals("", repository.run(second.id)?.resumeSnapshot?.draftText)
        assertNull(repository.observePending("session").first().single().draft)
        assertEquals(committed, fixture.messages.getValue(committed.id))
        assertTrue(repository.finish(second.id, RecoveryStatus.INTERRUPTED))

        val third = checkNotNull(repository.claim(request.id, "local", confirmed = true))

        assertEquals(RecoveryStage.TEXT, third.resumeSnapshot?.stage)
        assertEquals("", third.resumeSnapshot?.draftText)
        assertNull(repository.observePending("session").first().single().draft)
        assertEquals("Old uncommitted visible draft", fixture.drafts.getValue(first.id).text)
        assertFalse(fixture.runs.getValue(third.id).resumeSnapshotJson.orEmpty().contains("Private"))
    }

    @Test
    fun cardNeverSelectsArbitraryOlderRunWhenActiveSnapshotIsMissingOrInvalid() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val first = checkNotNull(repository.claim(request.id, "local"))
        val step = checkNotNull(repository.beginStep(first.id))
        assertTrue(repository.saveDraft(first.id, step, "Older draft should not be selected"))
        assertTrue(repository.finish(first.id, RecoveryStatus.INTERRUPTED))
        val second = checkNotNull(repository.claim(request.id, "local", confirmed = true))
        fixture.runs[second.id] = fixture.runs.getValue(second.id).copy(resumeSnapshotJson = "{invalid")
        assertNull(repository.observePending("session").first().single().draft)
        fixture.requests[request.id] = fixture.requests.getValue(request.id).copy(activeRunId = null)

        val card = repository.observePending("session").first().single()

        assertNull(card.run)
        assertNull(card.draft)
        assertEquals(RecoveryStage.NOT_STARTED, card.stage)
        assertEquals(2, fixture.runs.size)
    }

    @Test
    fun legacyRunSerializationDefaultsToNoResumeSnapshot() {
        val run = RecoveryRun("run", "request", "session", 0, "owner", RecoveryStatus.INTERRUPTED, 1, 0, 0, 1, 2, 2)
        val encoded = Json.encodeToString(run)
        assertFalse(Json.parseToJsonElement(encoded).jsonObject.containsKey("resumeSnapshot"))
        assertEquals(run, Json.decodeFromString<RecoveryRun>(encoded))
        assertNull(run.resumeSnapshot)
    }

    @Test
    fun forcedDraftFlushBypassesThrottleButNotStoppedRunFence() = runTest {
        val request = repository.enqueue("session", payload, "local")
        val run = checkNotNull(repository.claim(request.id, "local"))
        val step = checkNotNull(repository.beginStep(run.id))
        assertTrue(repository.saveDraft(run.id, step, "Visible partial"))
        assertTrue(repository.saveDraft(run.id, step, "Visible final partial", force = true))
        assertEquals("Visible final partial", fixture.drafts.getValue(run.id).text)
        assertTrue(repository.finish(run.id, RecoveryStatus.INTERRUPTED))
        assertFalse(repository.saveDraft(run.id, step, "Late callback", force = true))
        assertEquals("Visible final partial", fixture.drafts.getValue(run.id).text)
    }

    @Test
    fun sessionDomainRoundTripPreservesGenerationAndExplicitHold() {
        val entity = ChatSessionEntity("session", "Title", 1, 2, executionGeneration = 7,
            autoDispatchBlockedReason = "plan_rejected")
        assertEquals(entity, ChatSessionEntity.fromDomain(entity.toDomain()))
    }

    @Test
    fun payloadContainsRequestDataAttachmentPathsAndNotificationReferences() = runTest {
        val attachment = AgentAttachment("photo.png", "~/workspace/photo.png", "/files/photo.png", "image/png", 12, true)
        val original = payload.copy(inputAttachments = listOf(attachment), origin = RecoveryOrigin.NOTIFICATION,
            isAutoTrigger = true, persistUserMessage = false, subAgentInstructionId = "instruction", notificationSeqs = listOf(17, 18))
        val request = repository.enqueue("session", original, "local")

        assertEquals(original, repository.payload(request.id))
        val keys = Json.parseToJsonElement(fixture.requests.getValue(request.id).payloadJson).jsonObject.keys
        assertTrue(keys.all { it in setOf("request", "projectRoot", "modelRequest", "currentFile", "selectedCode",
            "inputAttachments", "origin", "isAutoTrigger", "persistUserMessage", "subAgentInstructionId", "notificationSeqs") })
        assertFalse(keys.any { it in setOf("base64", "apiKey", "headers", "rootPermit", "planApproval", "goal") })
        assertTrue(fixture.runs.isEmpty())
    }
}
