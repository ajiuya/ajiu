package com.aicode.feature.agent.presentation.component

import org.junit.Assert.*
import org.junit.Test

class SubAgentDispatchFallbackTest {
    private val args = """{"description":"Inspect sources","prompt":"Read only"}"""

    @Test fun representedSuccessIsReplacedButItsWarningAndFailedSiblingRemainVisible() {
        val result = """{"status":"partial","data":{"warning":"global warning","subagents":[
            {"id":"same","state":"running","warning":"model fallback"},
            {"id":"same","status":"error","error":"failed item"},
            {"id":"same","state":"pending"}]}}"""
        val parsed = parseSubAgentDispatch(args, result)!!
        assertTrue(parsed.items.first().claimable)
        assertFalse(parsed.items[1].claimable)
        assertFalse(parsed.items[2].claimable)
        val remaining = unrepresentedSubAgentDispatchItems(parsed.items, setOf("same"))
        assertEquals(2, remaining.size)
        assertEquals("failed item", remaining.first().warning)
        assertEquals(listOf("global warning", "model fallback"), subAgentDispatchWarnings(parsed))
    }

    @Test fun pendingMissingInvalidOrNumericIdsNeverClaimARunningChild() {
        assertFalse(parseSubAgentDispatch(args, null)!!.items.single().claimable)
        assertFalse(parseSubAgentDispatch(args, """{"status":"pending","data":{"id":"child"}}""")!!.items.single().claimable)
        assertFalse(parseSubAgentDispatch(args, """{"status":"success","data":{"id":3}}""")!!.items.single().claimable)
        assertNull(parseSubAgentDispatch("""{"action":null}""", """{"status":"success","data":{"id":"child"}}"""))
        assertNull(parseSubAgentDispatch(null, """{"status":"success","data":{"id":"child"}}"""))
        assertNull(parseSubAgentDispatch(args, """{"status":"error","message":"denied"}"""))
    }

    @Test fun oversizedAndDeepPayloadsFallBackToTheGeneralToolRatherThanUnboundedDispatchParsing() {
        assertNull(parseSubAgentDispatch(args, " ".repeat(SUB_AGENT_METADATA_MAX_CHARS + 1)))
        val tooMany = (0..SUB_AGENT_METADATA_MAX_ITEMS).joinToString(",") { "{\"id\":\"$it\"}" }
        assertNull(parseSubAgentDispatch(args, """{"status":"success","data":{"subagents":[$tooMany]}}"""))
        val deep = "{\"nest\":".repeat(257) + "{}" + "}".repeat(257)
        assertNull(parseSubAgentDispatch(deep, null))
        val bracesInString = """{"description":"{{{{[[[[\\\"escaped","prompt":"read"}"""
        assertNotNull(parseSubAgentDispatch(bracesInString, null))
    }
}
