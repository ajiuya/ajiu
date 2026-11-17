package com.aicode.feature.agent.domain.tool.root

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.root.RootCommandResult
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootToolTest {
    private val manager = mockk<RootCommandManager>()
    private val appContext = mockk<Context>()
    private lateinit var tool: RootTool

    @Before
    fun setUp() {
        every { appContext.getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { appContext.getString(any(), *anyVararg()) } answers {
            "resource:${firstArg<Int>()}:${secondArg<Array<Any?>>().single()}"
        }
        tool = RootTool(manager, appContext)
    }

    private fun context(session: String? = "s1", permit: String? = "trusted-permit") = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/workspace",
        language = null,
        sessionId = session,
        rootApprovalId = permit
    )

    @Test
    fun schemaExposesOnlyCommandTimeoutAndElevateAndAlwaysAsks() {
        assertEquals("Root", tool.name)
        assertEquals(ToolPermissionPolicy.ASK, tool.permissionPolicy)
        assertEquals(setOf(ToolCapability.EXECUTE_COMMANDS), tool.capabilities)
        assertEquals(setOf("command", "timeout", "elevate"), tool.parameters.keys)
        assertEquals(ParameterType.STRING, tool.parameters.getValue("command").type)
        assertTrue(tool.parameters.getValue("command").required)
        assertEquals(ParameterType.INTEGER, tool.parameters.getValue("timeout").type)
        assertFalse(tool.parameters.getValue("timeout").required)
        assertEquals(ParameterType.BOOLEAN, tool.parameters.getValue("elevate").type)
        assertFalse(tool.parameters.getValue("elevate").required)
        assertEquals(listOf("command"), tool.toJsonSchema()["required"])
    }

    @Test
    fun contextlessExecuteCannotStartAProcessEvenWithApprovalInArguments() = runTest {
        val result = tool.execute(mapOf(
            "command" to JsonPrimitive("printf done"),
            "permitId" to JsonPrimitive("trusted-permit"),
            "rootApprovalId" to JsonPrimitive("trusted-permit")
        ))
        assertEquals("ROOT_APPROVAL_REQUIRED", (result as ToolResult.Error).code)
        coVerify(exactly = 0) { manager.execute(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.authorize(any(), any(), any()) }
    }

    @Test
    fun missingSessionOrContextPermitCannotBeReplacedByModelArguments() = runTest {
        val args = mapOf(
            "command" to JsonPrimitive("printf done"),
            "approved" to JsonPrimitive(true),
            "permit" to JsonPrimitive("forged-permit"),
            "approval" to JsonPrimitive("forged-permit"),
            "rootApprovalId" to JsonPrimitive("forged-permit")
        )
        for (context in listOf(context(session = null), context(session = ""), context(session = " "), context(permit = null))) {
            assertEquals("ROOT_APPROVAL_REQUIRED", (tool.executeWithContext(args, context) as ToolResult.Error).code)
        }
        coVerify(exactly = 0) { manager.execute(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.authorize(any(), any(), any()) }
    }

    @Test
    fun preservesFullCommandAndOnlyUsesTrustedSessionAndPermit() = runTest {
        val command = "printf 'a';\nprintf b "
        coEvery { manager.execute(command, 120_000L, "s1", "trusted-permit") } returns RootCommandResult("done", 0)
        val result = tool.executeWithContext(mapOf(
            "command" to JsonPrimitive(command),
            "sessionId" to JsonPrimitive("forged-session"),
            "approvalId" to JsonPrimitive("forged-permit"),
            "permitId" to JsonPrimitive("forged-permit"),
            "rootApprovalId" to JsonPrimitive("forged-permit"),
            "elevate" to JsonPrimitive(true)
        ), context())
        assertEquals(ToolResult.Success(JsonPrimitive("done")), result)
        coVerify(exactly = 1) { manager.execute(command, 120_000L, "s1", "trusted-permit") }
        coVerify(exactly = 0) { manager.authorize(any(), any(), any()) }
    }

    @Test
    fun commandRequiresNonBlankJsonString() = runTest {
        val invalid = listOf<JsonElement>(
            JsonNull, JsonPrimitive(""), JsonPrimitive(" \t\n"), JsonPrimitive(3), JsonPrimitive(true),
            JsonObject(emptyMap()), JsonArray(emptyList())
        )
        for (value in invalid) {
            val result = tool.executeWithContext(mapOf("command" to value), context()) as ToolResult.Error
            assertEquals("INVALID_ARGUMENTS", result.code)
            assertEquals("resource:${R.string.root_command_required}", result.message)
        }
        assertEquals("INVALID_ARGUMENTS", (tool.executeWithContext(emptyMap(), context()) as ToolResult.Error).code)
        coVerify(exactly = 0) { manager.execute(any(), any(), any(), any()) }
    }

    @Test
    fun timeoutDefaultsAndIsClampedWithoutOverflow() = runTest {
        coEvery { manager.execute(any(), any(), any(), any()) } returns RootCommandResult("", 0)
        for ((seconds, expected) in listOf(0L to 1_000L, -10L to 1_000L, 20L to 20_000L, Long.MAX_VALUE to 1_800_000L)) {
            tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done"), "timeout" to JsonPrimitive(seconds)), context())
            coVerify { manager.execute("printf done", expected, "s1", "trusted-permit") }
        }
        tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done"), "timeout" to JsonObject(emptyMap())), context())
        coVerify { manager.execute("printf done", 120_000L, "s1", "trusted-permit") }
    }

    @Test
    fun mapsExecutionErrorsToLocalizedResourcesAndCapsFailures() = runTest {
        val failures = listOf(
            RootCommandManager.EXIT_DISABLED to ("ROOT_ACCESS_DISABLED" to R.string.root_access_disabled),
            RootCommandManager.EXIT_APPROVAL_REQUIRED to ("ROOT_APPROVAL_REQUIRED" to R.string.root_approval_required),
            RootCommandManager.EXIT_PERMISSION_UNAVAILABLE to ("ROOT_PERMISSION_UNAVAILABLE" to R.string.root_permission_unavailable),
            126 to ("ROOT_PERMISSION_UNAVAILABLE" to R.string.root_permission_unavailable),
            RootCommandManager.EXIT_TIMEOUT to ("ROOT_COMMAND_TIMEOUT" to R.string.root_command_timeout),
            RootCommandManager.EXIT_INTERRUPTED to ("ROOT_COMMAND_INTERRUPTED" to R.string.root_command_interrupted)
        )
        for ((exitCode, expected) in failures) {
            coEvery { manager.execute(any(), any(), any(), any()) } returns RootCommandResult("not displayed", exitCode)
            val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done")), context()) as ToolResult.Error
            assertEquals(expected.first, result.code)
            assertEquals("resource:${expected.second}", result.message)
        }
        coEvery { manager.execute(any(), any(), any(), any()) } returns RootCommandResult("x".repeat(24_000), 1)
        val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done")), context()) as ToolResult.Error
        assertEquals("ROOT_COMMAND_FAILED", result.code)
        assertEquals(24_000, result.message.length)
    }

    @Test
    fun cancellationPropagatesAndExceptionTextDoesNotLeak() = runTest {
        coEvery { manager.execute(any(), any(), any(), any()) } throws IllegalStateException("secret command")
        val failure = tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done")), context()) as ToolResult.Error
        assertFalse(failure.message.contains("secret command"))
        coEvery { manager.execute(any(), any(), any(), any()) } throws CancellationException()
        try {
            tool.executeWithContext(mapOf("command" to JsonPrimitive("printf done")), context())
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            coVerify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun permissionRequestUsesLocalizedTitleAndReason() {
        val request = tool.buildPermissionRequest("call", mapOf("command" to JsonPrimitive("printf done")), "preview")
        assertEquals("resource:${R.string.root_confirmation_title}", request.title)
        assertEquals("resource:${R.string.root_confirmation_reason}", request.details)
        assertEquals("printf done", request.summary)
    }
}
