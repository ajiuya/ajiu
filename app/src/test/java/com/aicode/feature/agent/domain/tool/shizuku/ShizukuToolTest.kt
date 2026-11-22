package com.aicode.feature.agent.domain.tool.shizuku

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.shizuku.ShizukuCommandResult
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuShellService
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ShizukuToolTest {
    private val manager = mockk<ShizukuManager>()
    private val appContext = mockk<Context>()
    private val state = MutableStateFlow(ShizukuState.PERMISSION_DENIED)
    private var refreshedState = ShizukuState.READY
    private val calls = mutableListOf<String>()
    private val approvedContext = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/workspace",
        language = null,
        sessionId = "session-1",
        shizukuApprovalId = "native-permit-1"
    )
    private lateinit var tool: ShizukuTool

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.v(any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs
        every { manager.state } returns state
        every { manager.refreshState() } answers {
            calls += "refresh"
            state.value = refreshedState
        }
        every { manager.requestPermission() } just Runs
        coEvery { manager.runCommand(any(), any()) } throws AssertionError("The legacy shell interface must not run")
        coEvery { manager.runApprovedCommand(any(), any(), any(), any()) } throws AssertionError("Only explicitly stubbed approved commands may run")
        every { appContext.getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { appContext.getString(any(), *anyVararg()) } answers {
            "resource:${firstArg<Int>()}:${secondArg<Array<out Any?>>().joinToString()}"
        }
        tool = ShizukuTool(manager, appContext)
    }

    @After
    fun tearDown() {
        try {
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun cachedDeniedRefreshesToReadyBeforeExecutingFakeCommand() {
        runTest {
            assertEquals(ShizukuState.PERMISSION_DENIED, state.value)
            coEvery { manager.runApprovedCommand("never-execute", 120_000L, "session-1", "native-permit-1") } answers {
                assertEquals(ShizukuState.READY, state.value)
                calls += "run"
                ShizukuCommandResult("safe fake output", 0)
            }

            val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("never-execute")), approvedContext)

            assertEquals(ToolResult.Success(JsonPrimitive("safe fake output")), result)
            assertEquals(listOf("refresh", "run"), calls)
            verify(exactly = 1) { manager.refreshState() }
            coVerify(exactly = 1) { manager.runApprovedCommand("never-execute", 120_000L, "session-1", "native-permit-1") }
            coVerify(exactly = 1) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun cachedReadyCannotExecuteWhenRefreshFindsPermissionDenied() {
        runTest {
            state.value = ShizukuState.READY
            refreshedState = ShizukuState.PERMISSION_DENIED

            val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("never-execute")), approvedContext) as ToolResult.Error

            assertEquals("SHIZUKU_NOT_READY", result.code)
            assertEquals("resource:${R.string.shizuku_not_ready}:resource:${R.string.settings_shizuku_hint_denied}", result.message)
            verify(exactly = 1) {
                appContext.getString(R.string.shizuku_not_ready, "resource:${R.string.settings_shizuku_hint_denied}")
            }
            assertEquals(ShizukuState.PERMISSION_DENIED, state.value)
            assertEquals(listOf("refresh"), calls)
            verify(exactly = 1) { manager.refreshState() }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { appContext.getString(R.string.shizuku_shell_required) }
        }
    }

    @Test
    fun cachedReadyCannotExecuteWhenRefreshCannotConfirmIdentity() {
        runTest {
            state.value = ShizukuState.READY
            refreshedState = ShizukuState.IDENTITY_UNAVAILABLE

            val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("never-execute")), approvedContext) as ToolResult.Error

            assertEquals("SHIZUKU_NOT_READY", result.code)
            assertEquals("resource:${R.string.shizuku_not_ready}:resource:${R.string.shizuku_identity_unavailable}", result.message)
            verify(exactly = 1) {
                appContext.getString(R.string.shizuku_not_ready, "resource:${R.string.shizuku_identity_unavailable}")
            }
            assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, state.value)
            assertEquals(listOf("refresh"), calls)
            verify(exactly = 1) { manager.refreshState() }
            verify(exactly = 1) { appContext.getString(R.string.shizuku_identity_unavailable) }
            verify(exactly = 0) { appContext.getString(R.string.shizuku_shell_required) }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun cachedReadyCannotExecuteWhenRefreshFindsNonShellIdentity() {
        runTest {
            state.value = ShizukuState.READY
            refreshedState = ShizukuState.SHELL_REQUIRED

            val result = tool.executeWithContext(mapOf("command" to JsonPrimitive("never-execute")), approvedContext) as ToolResult.Error

            assertEquals("SHIZUKU_NOT_READY", result.code)
            assertEquals("resource:${R.string.shizuku_not_ready}:resource:${R.string.shizuku_shell_required}", result.message)
            verify(exactly = 1) {
                appContext.getString(R.string.shizuku_not_ready, "resource:${R.string.shizuku_shell_required}")
            }
            assertEquals(ShizukuState.SHELL_REQUIRED, state.value)
            assertEquals(listOf("refresh"), calls)
            verify(exactly = 1) { manager.refreshState() }
            verify(exactly = 1) { appContext.getString(R.string.shizuku_shell_required) }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun missingCommandCannotRefreshAuthorizeOrExecute() {
        runTest {
            state.value = ShizukuState.READY

            val invalidArgs = listOf<Map<String, JsonElement>>(
                emptyMap(),
                mapOf("command" to JsonPrimitive("")),
                mapOf("command" to JsonPrimitive(" \t\n")),
                mapOf("command" to JsonNull),
                mapOf("command" to JsonPrimitive(123)),
                mapOf("command" to JsonPrimitive(true)),
                mapOf("command" to JsonObject(emptyMap())),
                mapOf("command" to JsonArray(emptyList()))
            )
            for (args in invalidArgs) {
                val result = tool.executeWithContext(args, approvedContext) as ToolResult.Error

                assertEquals("INVALID_ARGUMENTS", result.code)
                assertEquals("resource:${R.string.shizuku_command_required}", result.message)
            }
            verify(exactly = invalidArgs.size) { appContext.getString(R.string.shizuku_command_required) }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
            assertEquals(emptyList<String>(), calls)
            verify(exactly = 0) { manager.refreshState() }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun rootReadyRefreshesBeforeExecutingFakeCommandWithNativeSessionAndPermit() {
        runTest {
            state.value = ShizukuState.READY
            refreshedState = ShizukuState.READY_ROOT
            coEvery { manager.runApprovedCommand("never-execute-root", 120_000L, "session-1", "native-permit-1") } answers {
                assertEquals(ShizukuState.READY_ROOT, state.value)
                calls += "run"
                ShizukuCommandResult("safe fake root output", 0)
            }
            val args = mapOf(
                "command" to JsonPrimitive("never-execute-root"),
                "sessionId" to JsonPrimitive("model-session"),
                "shizukuApprovalId" to JsonPrimitive("model-permit")
            )

            val result = tool.executeWithContext(args, approvedContext)

            assertEquals(ToolResult.Success(JsonPrimitive("safe fake root output")), result)
            assertEquals(listOf("refresh", "run"), calls)
            verify(exactly = 1) { manager.refreshState() }
            coVerify(exactly = 1) { manager.runApprovedCommand("never-execute-root", 120_000L, "session-1", "native-permit-1") }
            coVerify(exactly = 1) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun missingOrBlankSessionCannotAuthorizeOrExecuteInEitherReadyState() {
        runTest {
            val args = mapOf(
                "command" to JsonPrimitive("never-execute"),
                "sessionId" to JsonPrimitive("model-session")
            )
            val expectedCalls = mutableListOf<String>()
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState
                for (sessionId in listOf(null, "", " \t")) {
                    val result = tool.executeWithContext(args, approvedContext.copy(sessionId = sessionId)) as ToolResult.Error

                    assertEquals("SHIZUKU_APPROVAL_REQUIRED", result.code)
                    assertEquals("resource:${R.string.shizuku_approval_required}", result.message)
                    assertEquals(readyState, state.value)
                    expectedCalls += "refresh"
                    assertEquals(expectedCalls, calls)
                }
            }
            verify(exactly = 6) { manager.refreshState() }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun missingNativePermitCannotBeReplacedByModelArgumentsOrRootApproval() {
        runTest {
            val args = mapOf(
                "command" to JsonPrimitive("never-execute"),
                "shizukuApprovalId" to JsonPrimitive("model-permit"),
                "elevate" to JsonPrimitive(true)
            )
            val context = approvedContext.copy(shizukuApprovalId = null, rootApprovalId = "native-root-permit")
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState

                val result = tool.executeWithContext(args, context) as ToolResult.Error

                assertEquals("SHIZUKU_APPROVAL_REQUIRED", result.code)
                assertEquals("resource:${R.string.shizuku_approval_required}", result.message)
                assertEquals(readyState, state.value)
            }
            assertEquals(listOf("refresh", "refresh"), calls)
            verify(exactly = 2) { manager.refreshState() }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun directExecuteRejectsModelSuppliedApprovalWithoutRefreshingOrExecuting() {
        runTest {
            val args = mapOf(
                "command" to JsonPrimitive("never-execute"),
                "sessionId" to JsonPrimitive("session-1"),
                "shizukuApprovalId" to JsonPrimitive("native-permit-1"),
                "elevate" to JsonPrimitive(true)
            )
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                state.value = readyState
                refreshedState = readyState

                val result = tool.execute(args) as ToolResult.Error

                assertEquals("SHIZUKU_APPROVAL_REQUIRED", result.code)
                assertEquals("resource:${R.string.shizuku_approval_required}", result.message)
            }
            assertEquals(emptyList<String>(), calls)
            verify(exactly = 0) { manager.refreshState() }
            coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun approvedCommandTimeoutsClampToExistingBoundsAndInvalidValuesUseTheDefault() {
        runTest {
            val timeouts = listOf(
                JsonPrimitive(Long.MIN_VALUE) to 1_000L,
                JsonPrimitive(-1L) to 1_000L,
                JsonPrimitive(0L) to 1_000L,
                JsonPrimitive(1L) to 1_000L,
                JsonPrimitive(42L) to 42_000L,
                JsonPrimitive(1_800L) to 1_800_000L,
                JsonPrimitive(1_801L) to 1_800_000L,
                JsonPrimitive(Long.MAX_VALUE) to 1_800_000L,
                JsonPrimitive("invalid-timeout") to 120_000L,
                JsonPrimitive("9223372036854775808") to 120_000L,
                JsonPrimitive(true) to 120_000L,
                JsonPrimitive(1.5) to 120_000L,
                JsonNull to 120_000L,
                JsonObject(emptyMap()) to 120_000L,
                JsonArray(emptyList()) to 120_000L
            )
            val expectedCalls = mutableListOf<String>()
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState
                timeouts.forEachIndexed { index, (timeout, expectedMs) ->
                    val command = "never-execute-${readyState.name}-$index"
                    val permit = "native-permit-${readyState.name}-$index"
                    coEvery { manager.runApprovedCommand(command, expectedMs, "session-1", permit) } answers {
                        assertEquals(readyState, state.value)
                        calls += "run"
                        ShizukuCommandResult("safe fake timeout output", 0)
                    }

                    val result = tool.executeWithContext(
                        mapOf("command" to JsonPrimitive(command), "timeout" to timeout),
                        approvedContext.copy(shizukuApprovalId = permit)
                    )

                    assertEquals(ToolResult.Success(JsonPrimitive("safe fake timeout output")), result)
                    coVerify(exactly = 1) { manager.runApprovedCommand(command, expectedMs, "session-1", permit) }
                    expectedCalls += listOf("refresh", "run")
                    assertEquals(expectedCalls, calls)
                }
            }
            verify(exactly = timeouts.size * 2) { manager.refreshState() }
            coVerify(exactly = timeouts.size * 2) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
        }
    }

    @Test
    fun approvalContextIsNotExposedInParameterOrModelSchemas() {
        assertEquals(setOf("command", "timeout", "elevate"), tool.parameters.keys)
        for (schema in listOf(tool.toJsonSchema(), tool.toModelJsonSchema())) {
            val properties = schema["properties"] as Map<*, *>
            assertFalse(properties.containsKey("shizukuApprovalId"))
            assertFalse(properties.containsKey("sessionId"))
            assertFalse(schema.toString().contains("shizukuApprovalId"))
        }
        assertEquals(emptyList<String>(), calls)
        verify(exactly = 0) { manager.refreshState() }
        coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.runCommand(any(), any()) }
        verify(exactly = 0) { manager.requestPermission() }
    }

    @Test
    fun backendIdentityChangesReturnErrorsWithoutRetryOrReauthorization() {
        runTest {
            val identityChanged = "resource:${R.string.shizuku_identity_changed}"
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState
                val command = "never-execute-${readyState.name}"
                val permit = "native-permit-${readyState.name}"
                coEvery { manager.runApprovedCommand(command, 120_000L, "session-1", permit) } answers {
                    assertEquals(readyState, state.value)
                    calls += "run"
                    state.value = ShizukuState.IDENTITY_UNAVAILABLE
                    throw SecurityException(identityChanged)
                }

                val result = tool.executeWithContext(
                    mapOf("command" to JsonPrimitive(command)),
                    approvedContext.copy(shizukuApprovalId = permit)
                )

                assertEquals(ToolResult.Error("resource:${R.string.shizuku_command_failed}:$identityChanged"), result)
                assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, state.value)
                coVerify(exactly = 1) { manager.runApprovedCommand(command, 120_000L, "session-1", permit) }
            }
            assertEquals(listOf("refresh", "run", "refresh", "run"), calls)
            verify(exactly = 2) { manager.refreshState() }
            verify(exactly = 2) { appContext.getString(R.string.shizuku_command_failed, identityChanged) }
            coVerify(exactly = 2) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun nonZeroExitCodesReturnErrorsInEitherReadyStateWithoutRetryOrReauthorization() {
        runTest {
            val expectedCalls = mutableListOf<String>()
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState
                for (exitCode in listOf(23, -1)) {
                    val command = "never-execute-failure-${readyState.name}-$exitCode"
                    val permit = "native-failure-permit-${readyState.name}-$exitCode"
                    val output = "safe fake ${readyState.name} failure output $exitCode"
                    coEvery { manager.runApprovedCommand(command, 120_000L, "session-1", permit) } answers {
                        assertEquals(readyState, state.value)
                        calls += "run"
                        ShizukuCommandResult(output, exitCode)
                    }

                    val result = tool.executeWithContext(
                        mapOf("command" to JsonPrimitive(command)),
                        approvedContext.copy(shizukuApprovalId = permit)
                    )

                    assertEquals(
                        ToolResult.Error("resource:${R.string.shizuku_command_exit_failed}:$exitCode, $output", "SHIZUKU_COMMAND_FAILED"),
                        result
                    )
                    verify(exactly = 1) { appContext.getString(R.string.shizuku_command_exit_failed, exitCode, output) }
                    coVerify(exactly = 1) { manager.runApprovedCommand(command, 120_000L, "session-1", permit) }
                    expectedCalls += listOf("refresh", "run")
                    assertEquals(expectedCalls, calls)
                }
            }
            verify(exactly = 4) { manager.refreshState() }
            verify(exactly = 0) { appContext.getString(R.string.shizuku_command_timeout) }
            coVerify(exactly = 4) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun backendTimeoutReturnsAnErrorInEitherReadyStateWithoutRetryOrReauthorization() {
        runTest {
            for (readyState in listOf(ShizukuState.READY, ShizukuState.READY_ROOT)) {
                refreshedState = readyState
                val command = "never-execute-timeout-${readyState.name}"
                val permit = "native-timeout-permit-${readyState.name}"
                coEvery { manager.runApprovedCommand(command, 120_000L, "session-1", permit) } answers {
                    assertEquals(readyState, state.value)
                    calls += "run"
                    ShizukuCommandResult("safe fake timed out output", ShizukuShellService.EXIT_TIMEOUT)
                }

                val result = tool.executeWithContext(
                    mapOf("command" to JsonPrimitive(command)),
                    approvedContext.copy(shizukuApprovalId = permit)
                )

                assertEquals(
                    ToolResult.Error("resource:${R.string.shizuku_command_timeout}", "SHIZUKU_COMMAND_TIMEOUT"),
                    result
                )
                coVerify(exactly = 1) { manager.runApprovedCommand(command, 120_000L, "session-1", permit) }
            }
            assertEquals(listOf("refresh", "run", "refresh", "run"), calls)
            verify(exactly = 2) { manager.refreshState() }
            verify(exactly = 2) { appContext.getString(R.string.shizuku_command_timeout) }
            coVerify(exactly = 2) { manager.runApprovedCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.runCommand(any(), any()) }
            verify(exactly = 0) { manager.requestPermission() }
            verify(exactly = 0) { manager.authorize(any(), any(), any()) }
        }
    }

    @Test
    fun permissionRequestUsesLocalizedResourcesAndResolvedTimeoutWithoutExecuting() {
        val args = mapOf(
            "command" to JsonPrimitive("never-execute"),
            "timeout" to JsonPrimitive(Long.MAX_VALUE)
        )

        val request = tool.buildPermissionRequest("call-1", args, "preview-only")

        assertEquals("call-1", request.id)
        assertEquals("Shizuku", request.toolName)
        assertEquals("resource:${R.string.shizuku_confirmation_title}", request.title)
        assertEquals("never-execute", request.summary)
        assertEquals("resource:${R.string.shizuku_confirmation_details}:1800", request.details)
        assertEquals("preview-only", request.argsPreview)
        verify(exactly = 1) { appContext.getString(R.string.shizuku_confirmation_title) }
        verify(exactly = 1) { appContext.getString(R.string.shizuku_confirmation_details, 1_800L) }
        assertEquals(emptyList<String>(), calls)
        verify(exactly = 0) { manager.refreshState() }
        coVerify(exactly = 0) { manager.runApprovedCommand(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.runCommand(any(), any()) }
        verify(exactly = 0) { manager.requestPermission() }
    }
}
