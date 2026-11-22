package com.aicode.feature.plugin.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PluginAgentArgumentsTest {
    private fun accepts(schema: String, arguments: String) = PluginAgentTool.acceptsArguments(
        Json.parseToJsonElement(schema).jsonObject, Json.parseToJsonElement(arguments).jsonObject)

    @Test fun requiredNestedTypesAndExtraKeysAreValidated() {
        val schema = """{"type":"object","required":["list"],"additionalProperties":false,"properties":{"list":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["one","two"]}}}}"""
        assertTrue(accepts(schema, """{"list":["one","two"]}"""))
        listOf("{}", """{"list":null}""", """{"list":[]}""", """{"list":[1]}""", """{"list":["three"]}""",
            """{"list":["one","two","one"]}""", """{"list":["two"],"other":true}""").forEach { assertFalse(it, accepts(schema, it)) }
    }

    @Test fun decimalBoundariesAndUnicodeCodePointsKeepTheirMeaning() {
        val schema = """{"type":"object","properties":{"n":{"type":"integer","minimum":9007199254740993,"maximum":9007199254740995},"s":{"type":"string","maxLength":1}}}"""
        assertTrue(accepts(schema, """{"n":9007199254740993.0,"s":"𠀀"}"""))
        assertFalse(accepts(schema, """{"n":9007199254740992}""")); assertFalse(accepts(schema, """{"n":9007199254740994.5}"""))
        assertFalse(accepts(schema, """{"n":"9007199254740993"}""")); assertFalse(accepts(schema, """{"s":"AB"}"""))
    }

    @Test fun schemaDoesNotAllowArbitrarilyNestedUntypedExtras() {
        assertFalse(accepts("""{"type":"object"}""", "{\"x\":" + "[".repeat(26) + "null" + "]".repeat(26) + "}"))
    }

    @Test fun longNamesStayBoundedWithoutSharingTruncatedSuffix() {
        val id = "a".repeat(30) + "." + "b".repeat(30)
        val first = PluginAgentTool.toolName(id, "report001")
        assertEquals(64, first.length); assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, PluginAgentTool.toolName(id, "report002"))
        assertEquals("plugin__sample_plugin__Report", PluginAgentTool.toolName("sample.plugin", "Report"))
    }

    @Test fun oldApprovalIdCannotResolveNextRequestAndCancellationRemovesIt() = runTest {
        val approvals = PluginApprovalCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            approvals.awaitApproval("one.id", "One", PluginApprovalOperation.EXECUTE_TOOL, "first", emptyFlow(), checkCurrent = {})
        }
        val firstId = requireNotNull(approvals.pending.value).id
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            approvals.awaitApproval("two.id", "Two", PluginApprovalOperation.WRITE_FILES, "next", emptyFlow(), checkCurrent = {})
        }
        first.cancelAndJoin()
        val nextId = requireNotNull(approvals.pending.value).id
        assertNotEquals(firstId, nextId)
        approvals.resolve(firstId, true); assertFalse(next.isCompleted)
        approvals.resolve(nextId, false); assertFalse(next.await()); assertNull(approvals.pending.value)
    }

    @Test fun approvalCannotHideTrailingArgumentsBehindATruncatedPreview() = runTest {
        val approvals = PluginApprovalCoordinator()
        try {
            approvals.awaitApproval("one.id", "One", PluginApprovalOperation.EXECUTE_COMMAND,
                "x".repeat(PluginApprovalCoordinator.MAX_DETAILS + 1), emptyFlow(), checkCurrent = {})
            fail("oversized approval")
        } catch (expected: PluginHostException) { assertEquals("APPROVAL_TOO_LARGE", expected.code) }
        assertNull(approvals.pending.value)
    }
}
