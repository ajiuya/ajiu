package com.aicode.feature.agent.presentation.component

import org.junit.Assert.*
import org.junit.Test

class TaskOperationPresentationTest {
    private val identities = mapOf("coder" to TaskOperationIdentity("编码员", "Implement feature"),
        "reviewer" to TaskOperationIdentity("审核员", "Review feature"))

    @Test fun sixActionsAndDeleteAliasRemainReadOnlyCallSnapshots() {
        val actions = mapOf("create" to TaskOperationAction.CREATE, "send" to TaskOperationAction.SEND,
            "read" to TaskOperationAction.READ, "stop" to TaskOperationAction.STOP, "del" to TaskOperationAction.DELETE,
            "delete" to TaskOperationAction.DELETE, "list" to TaskOperationAction.LIST)
        actions.forEach { (value, expected) -> assertEquals(expected, taskOperationAction("""{"action":"$value"}""")) }
        assertEquals(TaskOperationAction.CREATE, taskOperationAction("{}"))
        assertEquals(TaskOperationAction.UNKNOWN, taskOperationAction("""{"action":"unknown"}"""))
        assertEquals(TaskOperationAction.UNKNOWN, taskOperationAction("""{"action":null}"""))
    }

    @Test fun sendDeliveredUsesExactRealNameAndTaskButNotCompletionState() {
        val value = projectTaskOperation("""{"action":"send","id":"coder","message":"Implement public API"}""",
            """{"status":"success","data":{"id":"coder","state":"delivered"}}""", false, identities)!!
        val item = value.items.single()
        assertEquals("编码员", item.name)
        assertEquals("Implement feature", item.task)
        assertEquals("Implement public API", item.instruction)
        assertEquals(TaskOperationStatus.DELIVERED, item.status)
        assertNull(item.output)
        assertNull(item.model)
    }

    @Test fun realExploreNameIsNotInventedAndMissingNameDoesNotBorrowAnotherIdentity() {
        val args = """{"action":"stop","id":"missing"}"""
        val output = """{"status":"success","data":{"id":"missing","state":"stopping"}}"""
        assertNull(projectTaskOperation(args, output, false, identities)!!.items.single().name)
        val explore = projectTaskOperation("""{"agent":"Explore","description":"Inspect","prompt":"Read public files"}""",
            """{"status":"success","data":{"id":"explore","agent":"Explore","description":"Inspect","state":"running"}}""", false, identities)!!
        assertEquals("Explore", explore.items.single().name)
    }

    @Test fun reorderedCreationMatchesByEvidenceNotArrayPositionAndKeepsUnmatchedRequests() {
        val args = """{"action":"create","tasks":[{"agent":"编码员","description":"Implement","prompt":"Code instruction"},{"agent":"审核员","description":"Review","prompt":"Review instruction"}]}"""
        val output = """{"status":"partial","warning":"one still pending","data":{"subagents":[{"id":"reviewer","agent":"审核员","description":"Review","state":"running"}],"state":"running"}}"""
        val value = projectTaskOperation(args, output, false)!!
        assertTrue(value.partial)
        assertEquals(2, value.items.size)
        assertEquals("Review instruction", value.items[0].instruction)
        assertEquals("Code instruction", value.items[1].instruction)
        assertTrue(value.items[0].warning!!.contains("one still pending"))
        assertEquals(TaskOperationStatus.RUNNING, value.items[0].status)
        assertEquals(TaskOperationStatus.UNKNOWN, value.items[1].status)
    }

    @Test fun uncorrelatedCreationResultNeverAcquiresAnotherPrompt() {
        val args = """{"tasks":[{"agent":"A","description":"Alpha","prompt":"A instruction"},{"agent":"B","description":"Beta","prompt":"B instruction"}]}"""
        val output = """{"status":"success","data":{"subagents":[{"id":"new-a"},{"id":"new-b"}]}}"""
        val value = projectTaskOperation(args, output, false)!!
        assertEquals(4, value.items.size)
        assertNull(value.items[0].instruction)
        assertNull(value.items[1].instruction)
        assertEquals(setOf("A instruction", "B instruction"), value.items.drop(2).map { it.instruction }.toSet())
    }

    @Test fun readStopDeleteAndListShowTheirOwnCapturedStatusAndOutput() {
        val cases = mapOf("read" to ("completed" to TaskOperationStatus.COMPLETED),
            "stop" to ("stopping" to TaskOperationStatus.STOPPING), "delete" to ("deleted" to TaskOperationStatus.DELETED))
        cases.forEach { (action, state) ->
            val value = projectTaskOperation("""{"action":"$action","id":"coder"}""",
                """{"status":"success","data":{"id":"coder","state":"${state.first}","lastOutput":"captured output"}}""", false, identities)!!
            assertEquals(state.second, value.items.single().status)
            assertEquals("captured output", value.items.single().output)
            assertEquals("编码员", value.items.single().name)
        }
        val list = projectTaskOperation("""{"action":"list"}""",
            """{"status":"success","data":{"subagents":[{"id":"coder","state":"queued"},{"id":"reviewer","state":"awaiting_confirmation"}]}}""", false, identities)!!
        assertEquals(listOf("编码员", "审核员"), list.items.map { it.name })
        assertEquals(listOf(TaskOperationStatus.QUEUED, TaskOperationStatus.AWAITING_CONFIRMATION), list.items.map { it.status })
    }

    @Test fun warningsErrorsUnknownStatusAndPartialResultsAreNotDropped() {
        val value = projectTaskOperation("""{"action":"read","id":"coder"}""",
            """{"status":"partial","message":"partial note","warning":"outer warning","data":{"id":"coder","state":"unexpected","warning":"inner warning","lastOutput":"real output","error":{"code":"CAPTURE","message":"incomplete capture"}}}""", false, identities)!!
        assertTrue(value.partial)
        assertEquals("partial note", value.message)
        val item = value.items.single()
        assertEquals(TaskOperationStatus.FAILED, item.status)
        assertTrue(item.warning!!.contains("outer warning"))
        assertTrue(item.warning!!.contains("inner warning"))
        assertTrue(item.error!!.contains("CAPTURE"))
        assertEquals("real output", item.output)
        assertEquals(TaskOperationStatus.UNKNOWN, projectTaskOperation("""{"action":"stop","id":"coder"}""",
            """{"status":"success","data":{"id":"coder","state":"future-state"}}""", false)!!.items.single().status)
        assertEquals(TaskOperationAction.UNKNOWN, projectTaskOperation("""{"action":"future-action"}""", "", false)!!.action)
    }

    @Test fun malformedAndMismatchedResultsNeverAuthorizeOrMisidentifyATask() {
        for (result in listOf("not json", """{"status":"success","data":{"id":"reviewer","state":"delivered"}}""",
            """{"status":"success","data":{"id":123}}""")) {
            assertNull(projectTaskOperation("""{"action":"send","id":"coder"}""", result, false, identities))
        }
        assertNull(projectTaskOperation("""{"action":"list"}""", """{"status":"success","data":{"subagents":[123]}}""", false))
        assertNull(projectTaskOperation("[".repeat(100_000), "", false))
        assertNull(projectTaskOperation("""{"action":"read","message":"${"x".repeat(2_100_000)}"}""", "", false))
    }

    @Test fun historyWithoutArgsShowsOnlyCapturedEvidenceAndDoesNotRecoverInstructionsFromTaskTitle() {
        val value = projectTaskOperation(null, """{"status":"success","data":{"id":"coder","lastOutput":"captured"}}""", false, identities)!!
        assertTrue(value.argumentsUnavailable)
        assertEquals(TaskOperationAction.READ, value.action)
        assertNull(value.items.single().instruction)
        assertEquals("Implement feature", value.items.single().task)
        assertNull(projectTaskOperation(null, "", false, identities))
    }

    @Test fun namesInstructionsWarningsAndOutputAreSanitizedWithoutModifyingInput() {
        val args = """{"action":"send","id":"coder","message":"public token=private-instruction"}"""
        val result = """{"status":"success","data":{"id":"coder","state":"delivered","lastOutput":"password=private-output","warning":"token=private-warning"}}"""
        val value = projectTaskOperation(args, result, false, mapOf("coder" to TaskOperationIdentity("Reviewer token=private-name", "Task<think>private-title</think>")))!!
        val item = value.items.single()
        assertEquals("Reviewer token=•••", item.name)
        assertEquals("Task", item.task)
        assertFalse(value.toString().contains("private-"))
        assertTrue(args.contains("private-instruction"))
        assertTrue(result.contains("private-output"))
    }
}
