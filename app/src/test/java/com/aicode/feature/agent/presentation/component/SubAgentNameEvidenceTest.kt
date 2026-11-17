package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SubAgentNameEvidenceTest {
    private val parent = "parent"
    private val child = "child"
    private val names = listOf("编码员", "审核员", "自定义的很长很长的代理名称-保留完整定义名", "Explore")

    private fun tool(
        id: String = "create",
        content: String = """{"status":"success","data":{"id":"child","state":"running","agent":"编码员"}}""",
        args: String = """{"prompt":"Review sources","description":"task title","agent":"requested-name"}""",
    ) = AgentUIMessage(id, MessageRole.TOOL, content, timestamp = 1L, toolName = "task", toolArgs = args)

    private fun project(messages: List<AgentUIMessage>, owned: Set<String> = setOf(child)) =
        projectSubAgentTimeline(messages, owned, parent)

    private fun resolved(summary: SubAgentInteractionSummary?, fallback: String? = null) =
        resolveSubAgentSummary(child, "task title", summary, null, "running", fallback)

    @Test fun actualDefinitionNamesArePreservedWithoutRoleOrExploreSpecialCases() {
        names.forEach { name ->
            val summary = SubAgentInteractionSummary(child, title = "Do not infer a reviewer", agentName = " $name ")
            assertEquals(SubAgentDisplayIdentity(SubAgentIdentityKind.NAMED, name), subAgentDisplayIdentity(summary))
            assertEquals(subAgentDisplayIdentity(summary), subAgentDisplayIdentity(summary.agentName, child))
            assertEquals("Do not infer a reviewer", subAgentVisibleTask(summary))
            assertEquals("", subAgentVisibleTask(summary.copy(title = name)))
        }
    }

    @Test fun missingAndTechnicalNamesStayUnknownAndDefaultMarkersStayGeneric() {
        val uuid = "12345678-1234-1234-1234-123456789abc"
        listOf(null, "", " \n ", child, uuid, "agent $uuid").forEach { name ->
            assertEquals("name=$name", SubAgentIdentityKind.UNKNOWN, subAgentDisplayIdentity(name, child).kind)
        }
        listOf("generic", " GENERIC ", "subagent", " SubAgent ").forEach { name ->
            assertEquals(SubAgentIdentityKind.GENERIC, subAgentDisplayIdentity(name, child).kind)
        }
        val unknown = resolved(SubAgentInteractionSummary(child, title = "审核员", model = "编码员"))
        assertNull(unknown.agentName)
        assertEquals(SubAgentIdentityKind.UNKNOWN, subAgentDisplayIdentity(unknown).kind)
    }

    @Test fun currentExistingDefinitionNameWinsAndOnlyNameIsFilled() {
        names.forEach { name ->
            val live = SubAgentInteractionSummary(child, title = "current task", agentName = name,
                model = "actual-model", status = SubAgentInteractionStatus.AWAITING_CONFIRMATION,
                exchangeCount = 7, toolCallCount = 3, startedAtMs = 100, lastActivityAtMs = 130)
            assertEquals(live, resolved(live, "old dispatch name"))
            val nameless = live.copy(agentName = null)
            assertEquals(nameless.copy(agentName = name), resolved(nameless, name))
        }
        val legacy = SubAgentCardState(child, agentName = "审核员", title = "legacy task")
        val legacySummary = resolveSubAgentSummary(child, "task title", null, legacy, null, "old name")
        assertEquals("审核员", legacySummary.agentName)
    }

    @Test fun deletedChildUsesItsDispatchEvidenceInsteadOfStaleSummaryName() {
        val deleted = SubAgentInteractionSummary(child, agentName = "stale name",
            status = SubAgentInteractionStatus.DELETED, sessionExists = false)
        assertEquals(deleted.copy(title = "task title", agentName = "编码员"), resolved(deleted, "编码员"))
        assertNull(resolved(deleted).agentName)
        assertEquals("Explore", resolved(deleted.copy(status = SubAgentInteractionStatus.HISTORY), "Explore").agentName)
        assertEquals(SubAgentInteractionStatus.DELETED, resolved(deleted, "编码员").status)
        assertFalse(resolved(deleted, "编码员").sessionExists)
    }

    @Test fun namedDispatchCanFillDefaultMarkersButCannotReplaceAnExistingRealName() {
        listOf("generic", "subagent", "", child, "12345678-1234-1234-1234-123456789abc").forEach { marker ->
            assertEquals("审核员", resolved(SubAgentInteractionSummary(child, agentName = marker), "审核员").agentName)
        }
        listOf("generic", "subagent").forEach { marker ->
            val summary = SubAgentInteractionSummary(child, agentName = marker)
            assertEquals(marker, resolved(summary).agentName)
            assertEquals("编码员", resolved(summary.copy(agentName = "编码员"), marker).agentName)
        }
        val foreign = SubAgentInteractionSummary("foreign", title = "foreign title", agentName = "foreign name")
        assertNull(resolved(foreign).agentName)
        assertEquals("task title", resolved(foreign, "审核员").title)
        assertEquals("审核员", resolved(foreign, "审核员").agentName)
    }

    @Test fun successfulStringResultsReachReferenceCapsuleAndTurnWithBothDataTransports() {
        names.forEach { name ->
            val data = buildJsonObject { put("id", child); put("agent", name); put("state", "running") }
            listOf(false, true).forEach { wrapped ->
                val result = buildJsonObject {
                    put("status", "success")
                    if (wrapped) put("data", data.toString()) else put("data", data)
                }.toString()
                val message = tool(content = result)
                val projection = project(listOf(message))
                assertEquals(name, projection.references.single().fallbackAgentName)
                assertEquals(name, projection.capsules.single().fallbackAgentName)
                val turn = projectSubAgentTurnCapsules(projection, mapOf(message.id to "turn:one"), parent).single()
                assertEquals(name, turn.capsule.fallbackAgentName)
                assertEquals("task title", turn.capsule.title)
                assertEquals(result, message.content)
            }
        }
    }

    @Test fun onlyMissingResultNameCanUseTheSuccessfulMatchingCreateArgument() {
        val missing = tool(content = """{"status":"success","data":{"id":"child","state":"running"}}""")
        assertEquals("requested-name", project(listOf(missing)).capsules.single().fallbackAgentName)
        val malformedNames = listOf(JsonNull, JsonPrimitive(7), JsonPrimitive(true), JsonPrimitive(""),
            JsonPrimitive(child), buildJsonObject { put("name", "审核员") })
        malformedNames.forEach { value ->
            val result = buildJsonObject {
                put("status", "success")
                put("data", buildJsonObject { put("id", child); put("agent", value) })
            }.toString()
            assertNull("agent=$value", project(listOf(tool(content = result))).capsules.single().fallbackAgentName)
            assertNull(parseSubAgentDispatch(missing.toolArgs, result)!!.items.single().agentName?.takeIf {
                subAgentDisplayIdentity(it, child).kind != SubAgentIdentityKind.UNKNOWN
            })
        }
    }

    @Test fun batchPartialErrorsKeepOriginalNameIndicesAndNeverClaimFailedOrForeignSiblings() {
        val args = """{"tasks":[
            {"agent":"编码员"},{"agent":"must-not-shift"},{"agent":"failed agent"},
            {"agent":"审核员"},{"agent":"requested-last"},{"agent":"foreign name"}
        ]}"""
        val result = """{"status":"partial","data":{"subagents":[
            {"id":"child","state":"running"},null,
            {"id":"failed","agent":"failed real name","status":"error","error":"denied"},
            {"id":"second","state":"queued"},
            {"id":"last","agent":"Explore","state":"running"},
            {"id":"foreign","agent":"foreign name","state":"running"}
        ]}}"""
        val projection = project(listOf(tool(content = result, args = args)), setOf(child, "second", "last", "failed"))
        assertEquals(listOf(child, "second", "last"), projection.references.map { it.subSessionId })
        assertEquals(listOf(-1, -4, -5), projection.references.map { it.sourceRef.ordinal })
        assertEquals(listOf("编码员", "审核员", "Explore"), projection.references.map { it.fallbackAgentName })
        val parsed = parseSubAgentDispatch(args, result)!!
        assertEquals("编码员", parsed.items[0].agentName)
        assertEquals("审核员", parsed.items[3].agentName)
        assertEquals("Explore", parsed.items[4].agentName)
        assertFalse(parsed.items[2].claimable)
        assertFalse(parsed.items[1].claimable)
    }

    @Test fun mismatchedBatchShapeCannotBorrowTopLevelOrMissingTaskNames() {
        val twoArgs = """{"agent":"top-level","tasks":[{"agent":"first"},{"agent":"second"}]}"""
        val flat = """{"status":"success","data":{"id":"child"}}"""
        assertNull(project(listOf(tool(content = flat, args = twoArgs))).capsules.single().fallbackAgentName)
        val batch = """{"status":"success","data":{"subagents":[{"id":"child"},{"id":"second"}]}}"""
        val projection = project(listOf(tool(content = batch, args = """{"agent":"top-level","prompt":"work"}""")), setOf(child, "second"))
        assertTrue(projection.capsules.all { it.fallbackAgentName == null })
        val shortArgs = """{"agent":"top-level","tasks":[{"agent":"first"}]}"""
        val shortProjection = project(listOf(tool(content = batch, args = shortArgs)), setOf(child, "second"))
        assertEquals(listOf("first", null), shortProjection.capsules.map { it.fallbackAgentName })
    }

    @Test fun failedPlaceholderCorruptAndUnownedResultsCannotSupplyAName() {
        val invalid = listOf(
            tool(content = """{"status":"error","data":{"id":"child","agent":"审核员"}}"""),
            tool(content = """{"status":"pending","data":{"id":"child","agent":"审核员"}}"""),
            tool(content = "[running] waiting"), tool(content = "{broken"),
            tool(content = """{"status":"success","data":{"id":"child","agent":"审核员","error":"failed"}}"""),
            tool(content = """{"status":"success","data":{"id":"child","agent":"审核员","state":"failed"}}"""),
            tool(content = """{"status":"success","data":{"id":"foreign","agent":"审核员"}}"""),
            tool(content = """{"status":"success","data":{"id":"parent","agent":"审核员"}}"""),
            tool(content = """{"status":"success","data":{"id":3,"agent":"审核员"}}"""),
            tool(args = """{"action":null,"agent":"审核员"}"""), tool().copy(isError = true),
        )
        invalid.forEach { message ->
            val projection = project(listOf(message), setOf(child, parent, "3"))
            assertTrue("content=${message.content}", projection.references.isEmpty())
            assertTrue(projection.capsules.isEmpty())
        }
    }

    @Test fun createThenCrossTurnSendAndPureNoticeKeepNameWithoutChangingAnchorsOrKeys() {
        val create = tool()
        val send = tool("send", """{"status":"success","data":{"id":"child","agent":"not-create"}}""",
            """{"action":"send","id":"child","message":"continue","agent":"not-create-either"}""")
        val notice = AgentUIMessage("notice", MessageRole.USER,
            """{"notifications":[{"kind":"subagent","subagent_id":"child","subagent_title":"not-a-name","agent":"not-notice","status":"completed"}]}""",
            timestamp = 3L, isBackgroundNotification = true)
        val keys = mapOf("create" to "turn:one", "send" to "turn:two", "notice" to "turn:three")
        val before = projectSubAgentTurnCapsules(project(listOf(create, send)), keys, parent)
        val projection = project(listOf(create, send, notice))
        assertEquals(listOf("编码员", null, null), projection.references.map { it.fallbackAgentName })
        assertEquals("编码员", projection.capsules.single().fallbackAgentName)
        val after = projectSubAgentTurnCapsules(projection, keys, parent)
        assertEquals(listOf("turn:one", "turn:two"), after.map { it.turnKey })
        assertEquals(listOf("create", "send"), after.map { it.capsule.anchorMessageId })
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(before.map { it.capsule.key }, after.map { it.capsule.key })
        assertEquals(listOf("编码员", "编码员"), after.map { it.capsule.fallbackAgentName })
        assertTrue(after.all { it.capsule.fallbackStatus == "completed" })
        val noticeOnly = project(listOf(notice))
        assertNull(noticeOnly.capsules.single().fallbackAgentName)
        assertTrue(projectSubAgentTurnCapsules(noticeOnly, keys, parent).isEmpty())
    }

    @Test fun namelessOrDefaultMarkerCreateCannotEraseEarlierVerifiedName() {
        val missing = tool("nameless", """{"status":"success","data":{"id":"child"}}""", """{"prompt":"continue"}""")
        val generic = tool("default", """{"status":"success","data":{"id":"child","agent":"generic"}}""")
        val messages = listOf(tool(), missing, generic)
        val projection = project(messages)
        assertEquals("编码员", projection.capsules.single().fallbackAgentName)
        assertEquals("编码员", projectSubAgentTurnCapsules(projection, messages.associate { it.id to "turn:one" }, parent)
            .single().capsule.fallbackAgentName)
        assertEquals("generic", project(listOf(generic)).capsules.single().fallbackAgentName)
    }

    @Test fun foreignParentEvidenceNeverNamesAnOwnedSendAndCacheDoesNotLeakAcrossParents() {
        val send = tool("send", """{"status":"success","data":{"id":"child"}}""",
            """{"action":"send","id":"child","message":"continue"}""")
        val owned = project(listOf(send))
        val foreign = projectSubAgentTimeline(listOf(tool()), setOf(child), "other-parent")
        val mixed = SubAgentTimelineProjection(foreign.references + owned.references, foreign.capsules)
        val keys = mapOf("create" to "turn:one", "send" to "turn:two")
        val actual = projectSubAgentTurnCapsules(mixed, keys, parent).single()
        assertEquals("turn:two", actual.turnKey)
        assertNull(actual.capsule.fallbackAgentName)
        val cache = SubAgentTimelineCache()
        assertEquals("编码员", cache.project(listOf(tool()), setOf(child), parent).capsules.single().fallbackAgentName)
        assertNull(cache.project(listOf(send), setOf(child), "other-parent").capsules.single().fallbackAgentName)
        assertTrue(cache.project(listOf(tool()), setOf("different-child"), parent).capsules.isEmpty())
    }

    @Test fun nameEnrichmentLeavesStatusRanksCountsAndStableChildOrderUnchanged() {
        val message = tool()
        val timeline = project(listOf(message))
        val named = projectSubAgentTurnCapsules(timeline, mapOf(message.id to "turn:one"), parent).single()
        val unnamed = named.copy(capsule = named.capsule.copy(fallbackAgentName = null))
        fun item(turn: SubAgentTurnCapsule) = ChatRenderItem(message, key = turn.key,
            contentType = SUB_AGENT_CAPSULE_CONTENT_TYPE, subAgentCapsule = turn.capsule, chatTurnKey = turn.turnKey)
        SubAgentInteractionStatus.entries.forEach { status ->
            val summary = mapOf(child to SubAgentInteractionSummary(child, status = status))
            assertEquals(subAgentGroupStatusRanks(listOf(item(unnamed)), summary),
                subAgentGroupStatusRanks(listOf(item(named)), summary))
            assertEquals(summarizeSubAgentGroup(listOf(unnamed.capsule), summary),
                summarizeSubAgentGroup(listOf(named.capsule), summary))
            val namedGroup = projectSubAgentGroup(listOf(item(named)), parent, expanded = true)
            val unnamedGroup = projectSubAgentGroup(listOf(item(unnamed)), parent, expanded = true)
            assertEquals(unnamedGroup.groups.map { it.key }, namedGroup.groups.map { it.key })
            assertEquals(unnamedGroup.items.map { it.key }, namedGroup.items.map { it.key })
            assertEquals(unnamedGroup.representedChildIds, namedGroup.representedChildIds)
        }
    }
}
