package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTimelineProjectionTest {
    private val parent = "parent"
    private val child = "child"

    private fun tool(
        id: String,
        content: String = """{"status":"success","data":{"id":"child","state":"running"}}""",
        args: String? = """{"prompt":"Investigate\nKeep it focused"}""",
        name: String = "task",
    ) = AgentUIMessage(id, MessageRole.TOOL, content, timestamp = 1L, toolName = name, toolArgs = args)

    private fun notice(id: String, content: String = xml()) =
        AgentUIMessage(id, MessageRole.USER, content, timestamp = 2L, isBackgroundNotification = true)

    private fun xml(childId: String = child, status: String = "completed") =
        "$BACKGROUND_NOTIFICATION_PREFIX\n<subagent-notification><subagent-id>$childId</subagent-id><subagent-title>Investigation</subagent-title><status>$status</status><result-excerpt>Same result</result-excerpt></subagent-notification>"

    private fun send(id: String) = tool(id, args = """{"action":"send","id":"$child","message":"Continue"}""")

    private fun project(messages: List<AgentUIMessage>, known: Set<String> = setOf(child)) =
        projectSubAgentTimeline(messages, known, parent)

    @Test
    fun sameTurnCreateAndRealSendsDeduplicateByChildAtLatestCallNotLatestNotice() {
        val messages = listOf(tool("create"), send("send1"), send("send2"), notice("completion"))
        val timeline = project(messages)
        assertEquals(listOf(-1, -1, -1, 0), timeline.references.map { it.sourceRef.ordinal })
        assertEquals(listOf(SubAgentTimelineReferenceKind.DISPATCH, SubAgentTimelineReferenceKind.INSTRUCTION,
            SubAgentTimelineReferenceKind.INSTRUCTION, SubAgentTimelineReferenceKind.NOTIFICATION), timeline.references.map { it.kind })
        val capsules = projectSubAgentTurnCapsules(timeline, messages.associate { it.id to "turn:user" }, parent)
        assertEquals(1, capsules.size)
        assertEquals("send2", capsules.single().capsule.anchorMessageId)
        assertEquals("Investigate", capsules.single().capsule.title)
        assertEquals("completed", capsules.single().capsule.fallbackStatus)
        assertEquals(setOf("create", "send1", "send2", "completion"), capsules.single().carrierMessageIds)
        assertTrue(timeline.references.all { it.sourceRef.sessionId == parent })
    }

    @Test
    fun crossTurnSendIsIndependentAndLaterCompletionDoesNotInventANewCallTurn() {
        val messages = listOf(tool("create"), notice("notice1"), send("send2"), notice("notice2"), notice("notice3"))
        val keys = mapOf("create" to "turn:one", "notice1" to "turn:one", "send2" to "turn:two", "notice2" to "turn:two", "notice3" to "turn:three")
        val capsules = projectSubAgentTurnCapsules(project(messages), keys, parent)
        assertEquals(listOf("turn:one", "turn:two"), capsules.map { it.turnKey })
        assertEquals(listOf("create", "send2"), capsules.map { it.capsule.anchorMessageId })
        assertEquals(setOf("create", "notice1", "notice3"), capsules[0].carrierMessageIds)
        assertEquals(setOf("send2", "notice2"), capsules[1].carrierMessageIds)
        assertNotEquals(capsules[0].key, capsules[1].key)
        val other = projectSubAgentTurnCapsules(projectSubAgentTimeline(messages, setOf(child), "other-parent"), keys, "other-parent")
        assertTrue(other.none { it.key in capsules.map(SubAgentTurnCapsule::key) })
        assertTrue(projectSubAgentTurnCapsules(project(messages), keys, "wrong-parent").isEmpty())
    }

    @Test
    fun prependedCallHistoryPreservesCurrentTurnKeyAnchorAndItsOwnNoticeCarriers() {
        val current = listOf(send("send2"), notice("notice2"))
        val currentKeys = current.associate { it.id to "turn:two" }
        val partial = projectSubAgentTurnCapsules(project(current), currentKeys, parent).single()
        val full = listOf(tool("create1"), notice("notice1")) + current + notice("later")
        val keys = currentKeys + mapOf("create1" to "turn:one", "notice1" to "turn:one", "later" to "turn:three")
        val loaded = projectSubAgentTurnCapsules(project(full), keys, parent)
        val sameTurn = loaded.single { it.turnKey == "turn:two" }
        assertEquals(partial, sameTurn)
        assertTrue(loaded.none { it.turnKey == "turn:three" })
    }

    @Test
    fun noticeOnlyAndActiveOnlyChildrenCannotCreateTurnCapsulesOrClaimRawFallback() {
        val messages = listOf(notice("notice"))
        val timeline = projectSubAgentTimeline(messages, setOf(child, "active", parent), parent, setOf("active", parent, "foreign"))
        assertEquals(listOf(child, "active"), timeline.capsules.map { it.subSessionId })
        val turns = projectSubAgentTurnCapsules(timeline, mapOf("notice" to "turn:user"), parent)
        assertTrue(turns.isEmpty())
        val groups = projectSubAgentGroup(emptyList(), parent, activeTurnKey = "turn:user")
        val rawNotice = parseSubAgentNotificationMetadata(messages.single().content)
        assertEquals(rawNotice, unrepresentedSubAgentNotifications(rawNotice, groups.representedChildIds))
        assertTrue(groups.footerItems.isEmpty())
    }

    @Test
    fun partialBatchClaimsOnlyOwnedSuccessfulStringIdsAndRetainsOriginalSourceOrdinals() {
        val raw = """{"status":"partial","data":{"warning":"batch warning","subagents":[
            {"id":"child","state":"running","warning":"model warning"},
            null,
            {"id":"failed","status":"error","error":"denied"},
            {"id":"pending","status":"pending"},
            {"id":"malformed","status":{"bad":true}},
            {"id":123,"state":"running"},
            {"id":"foreign","state":"running"},
            {"id":"second","state":"queued"}
        ]}}""".trimIndent()
        val args = """{"tasks":[{"description":"First"},{},{},{},{},{},{},{"description":"Second"}]}"""
        val carrier = tool("batch", raw, args)
        val projected = project(listOf(carrier), setOf(child, "failed", "pending", "malformed", "123", "second"))
        assertEquals(listOf(child, "second"), projected.references.map { it.subSessionId })
        assertEquals(listOf(-1, -8), projected.references.map { it.sourceRef.ordinal })
        assertEquals(listOf("First", "Second"), projected.references.map { it.title })
        assertEquals(raw, carrier.content)
        assertTrue(carrier.content.contains("batch warning"))
        assertTrue(carrier.content.contains("model warning"))
        assertTrue(carrier.content.contains("denied"))
    }

    @Test
    fun missingMalformedPendingOrNonCallArgumentsNeverBecomeAnImplicitCreate() {
        val invalidArgs = listOf<String?>(null, "", "{broken", "[]", """{"action":null}""", """{"action":4}""",
            """{"tasks":{}}""", """{"tasks":[null]}""", """{"prompt":{}}""")
        invalidArgs.forEach { args -> assertTrue("args=$args", project(listOf(tool("invalid", args = args))).references.isEmpty()) }
        listOf("read", "list", "stop", "delete", "unknown").forEach { action ->
            assertTrue(project(listOf(tool(action, args = """{"action":"$action","id":"$child"}"""))).references.isEmpty())
        }
        assertTrue(project(listOf(tool("pending", content = "[running] waiting"))).references.isEmpty())
        assertTrue(project(listOf(tool("error").copy(isError = true))).references.isEmpty())
        assertTrue(project(listOf(tool("failed", content = """{"status":"error","message":"no agent"}"""))).references.isEmpty())
        assertEquals(1, project(listOf(tool("omitted", args = "{}"))).references.size)
    }

    @Test
    fun sendRequiresOwnedArgumentIdAndMatchingReturnedIdentity() {
        val successful = send("send").copy(content = """{"status":"success","data":"delivered"}""")
        assertEquals(SubAgentTimelineReferenceKind.INSTRUCTION, project(listOf(successful)).references.single().kind)
        assertTrue(project(listOf(successful.copy(toolArgs = """{"action":"send","id":"foreign","message":"continue"}"""))).references.isEmpty())
        assertTrue(project(listOf(successful.copy(toolArgs = """{"action":"send","id":123}""")), setOf("123")).references.isEmpty())
        assertTrue(project(listOf(successful.copy(content = """{"status":"success","data":{"id":"foreign"}}"""))).references.isEmpty())
        assertTrue(project(listOf(successful.copy(content = """{"status":"success","data":{"id":123}}"""))).references.isEmpty())
    }

    @Test
    fun serializedCreateDataAndStatusPrefixUseTheSameBoundedProjection() {
        val content = buildJsonObject {
            put("status", "success")
            put("data", """{"id":"child","state":"queued"}""")
        }.toString()
        val reference = project(listOf(tool("create", "${SessionUseCase.LEGACY_STOPPED_TOOL_MARKER} $content"))).references.single()
        assertEquals(child, reference.subSessionId)
        assertEquals("queued", reference.fallbackStatus)
        assertEquals(-1, reference.sourceRef.ordinal)
    }

    @Test
    fun identicalNotificationsRemainDistinctSourcesAndKeepOriginalArrayOrdinals() {
        val entries = JsonArray(listOf(
            buildJsonObject { put("kind", "background_task"); put("summary", "keep") },
            buildJsonObject { put("kind", "agent_message"); put("from_id", child); put("message", "same") },
            JsonPrimitive("unknown raw"),
            buildJsonObject { put("kind", "agent_message"); put("from_id", child); put("message", "same") },
            buildJsonObject { put("kind", "user_message"); put("message", "user instruction") },
            buildJsonObject { put("kind", "future_kind"); put("from_id", child); put("detail", "unknown detail") },
        ))
        val content = buildJsonObject { put("status", "success"); put("data", "stdout"); put("notifications", entries) }.toString()
        val messages = listOf(tool("piggyback", content, name = "Bash"), notice("standalone1"), notice("standalone2"))
        val projected = project(messages)
        assertEquals(listOf(1, 3, 0, 0), projected.references.map { it.sourceRef.ordinal })
        assertEquals(4, projected.references.map { it.sourceRef }.toSet().size)
        val notices = parseSubAgentNotificationMetadata(content, toolResult = true)
        val fallback = unrepresentedSubAgentNotifications(notices, setOf(child))
        assertEquals(listOf(0, 2, 4, 5), fallback.map { it.ordinal })
        assertTrue(fallback.last().raw.contains("unknown detail"))
    }

    @Test
    fun xmlAndTextPiggybackRetainUnknownMalformedAndParentNoticeFallback() {
        val own = "<agent-message><from-id>$child</from-id><message>&lt;ready&gt; &amp;lt;escaped&amp;gt;\nnext</message></agent-message>"
        val foreign = "<agent-message><from-id>$parent</from-id><message>parent</message></agent-message>"
        val unknown = "<future-notice><summary>keep &amp; detail</summary></future-notice>"
        val malformed = "<agent-message><from-id>$child</from-id><message>broken</agent-message>"
        val raw = "stdout\n$BACKGROUND_NOTIFICATION_PREFIX\n$own\n$foreign\n$unknown\n$malformed\n<broken>"
        val notices = parseSubAgentNotificationMetadata(raw, toolResult = true)
        assertEquals(5, notices.size)
        assertEquals("<ready> &lt;escaped&gt;\nnext", notices.first().body)
        assertEquals(listOf(0), project(listOf(tool("stdout", raw, name = "Bash"))).references.map { it.sourceRef.ordinal })
        val fallback = unrepresentedSubAgentNotifications(notices, setOf(child))
        assertEquals(listOf(1, 2, 3, 4), fallback.map { it.ordinal })
        assertEquals(malformed, fallback[2].raw)
        assertNull(fallback[2].subSessionId)
        assertEquals("<broken>", fallback.last().raw)
        assertTrue(project(listOf(tool("plain-xml", own, name = "Bash"))).references.isEmpty())
        assertTrue(projectSubAgentTimeline(listOf(notice("self", xml(parent))), setOf(parent), parent).references.isEmpty())
    }

    @Test
    fun duplicateXmlIdentityAndUnknownBareFramingAreNotSilentlyClaimed() {
        val ambiguous = "<agent-message><from-id>foreign</from-id><from-id>$child</from-id><message>body</message></agent-message>"
        val parsed = parseSubAgentNotificationMetadata("important\n$ambiguous\n<broken>")
        assertEquals(3, parsed.size)
        assertTrue(parsed.all { it.subSessionId == null })
        assertEquals(parsed, unrepresentedSubAgentNotifications(parsed, setOf(child)))
    }

    @Test
    fun parserAndTaskMetadataRespectCharacterItemAndDepthBudgets() {
        val huge = "x".repeat(SUB_AGENT_METADATA_MAX_CHARS + 1)
        val hugeNotice = parseSubAgentNotificationMetadata(huge).single()
        assertNull(hugeNotice.subSessionId)
        assertSame(huge, hugeNotice.raw)
        assertTrue(project(listOf(tool("huge", huge))).references.isEmpty())
        assertTrue(project(listOf(tool("args", args = """{"prompt":"$huge"}"""))).references.isEmpty())
        val entry = buildJsonObject { put("kind", "subagent"); put("subagent_id", child) }
        val array = JsonArray(List(SUB_AGENT_METADATA_MAX_ITEMS + 1) { entry })
        val overItems = buildJsonObject { put("notifications", array) }.toString()
        assertNull(parseSubAgentNotificationMetadata(overItems, toolResult = true).single().subSessionId)
        assertTrue(project(listOf(tool("many", overItems, name = "Bash"))).references.isEmpty())
        val deep = "[".repeat(257) + "0" + "]".repeat(257)
        val deepNotice = """{"notifications":[{"kind":"subagent","subagent_id":"$child","extra":$deep}]}"""
        assertNull(parseSubAgentNotificationMetadata(deepNotice, toolResult = true).single().subSessionId)
        assertTrue(project(listOf(tool("deep", args = """{"prompt":"task","extra":$deep}"""))).references.isEmpty())
        val tooManyTasks = buildJsonObject { put("tasks", JsonArray(List(SUB_AGENT_METADATA_MAX_ITEMS + 1) { buildJsonObject {} })) }.toString()
        assertTrue(project(listOf(tool("many-tasks", args = tooManyTasks))).references.isEmpty())
    }

    @Test
    fun cacheReusesRelevantCarriersAcrossContentTimerAndTokenRefreshAndRechecksOwnership() {
        var parses = 0
        val cache = SubAgentTimelineCache { raw, isTool -> parses++; parseSubAgentNotificationMetadata(raw, isTool) }
        val history = List(40) { tool("call-$it") }
        val initial = cache.project(history, setOf(child), parent)
        assertEquals(40, parses)
        val timerOnly = history.map { it.copy(toolDurationMs = 800L, inputTokens = 200, outputTokens = 90) }
        val assistant = AgentUIMessage("answer", MessageRole.ASSISTANT, "longer text", timestamp = 5L)
        assertSame(initial, cache.project(timerOnly + assistant, setOf(child), parent))
        assertEquals(40, parses)
        val changed = timerOnly.toMutableList().apply { this[0] = this[0].copy(toolArgs = """{"prompt":"changed"}""") }
        assertNotEquals(initial, cache.project(changed, setOf(child), parent))
        assertEquals(41, parses)
        assertTrue(cache.project(changed, setOf("foreign"), parent).references.isEmpty())
        assertEquals(81, parses)
        val other = cache.project(changed, setOf(child), "other-parent")
        assertEquals(121, parses)
        assertTrue(other.references.all { it.sourceRef.sessionId == "other-parent" })
    }

    @Test
    fun cacheEvictsUnloadedCarriersWithoutParsingUnchangedVisibleWindow() {
        var parses = 0
        val cache = SubAgentTimelineCache { raw, isTool -> parses++; parseSubAgentNotificationMetadata(raw, isTool) }
        val history = List(30) { tool("call-$it") }
        val initial = cache.project(history, setOf(child), parent)
        assertEquals(30, parses)
        assertEquals(12, cache.project(history.takeLast(12), setOf(child), parent).references.size)
        assertEquals(30, parses)
        assertEquals(initial, cache.project(history, setOf(child), parent))
        assertEquals(48, parses)
        assertFalse(initial.references.isEmpty())
    }
}
