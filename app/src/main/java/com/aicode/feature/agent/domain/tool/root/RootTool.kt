package com.aicode.feature.agent.domain.tool.root

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

class RootTool @Inject constructor(
    private val manager: RootCommandManager,
    @param:ApplicationContext private val appContext: Context
) : AgentTool() {
    companion object {
        const val NAME = "Root"
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = NAME
    override val description =
        "Execute a command on the Android host using the user's existing su/Magisk authorization. " +
            "Requires the device-local Root switch and actual OS authorization. Confirmed read-only host-system-file operations are allowed by default; " +
            "host modifications or scripts/dynamic paths not confirmed read-only require approval first. " +
            "Allow once or allow all subsequent host modifications and uncertain operations in this chat session for Root with the same source and identity, " +
            "including different commands and targets, not just this command. Session grants are memory-only and cleared on process restart; " +
            "source, identity, Root generation, profile/configuration contents or selection, or instance changes require fresh approval. " +
            "Explicit deny rules, the Root switch, and actual OS authorization still apply; this is not unlimited OS Root permission. " +
            "Each execution still consumes an identity-bound, single-use internal permit for its full command. Disabling Root revokes pending approvals and unused permits and invalidates related grants. " +
            "Recognition is limited and static: it does not execute or open scripts to classify them and is not runtime interception or a sandbox; " +
            "symlinks, PATH resolution, and arbitrary program side effects cannot be guaranteed. Even an actually read-only script may prompt. " +
            "This is not container root and does not grant root to an unrooted device. " +
            "Stopping a managed process cannot undo completed writes or guarantee termination of arbitrary detached daemons."
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)
    override val parameters = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Complete shell command to execute on the Android host with existing root authorization.",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "Maximum runtime in seconds; defaults to 120 and is capped at 1800.",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "Legacy compatibility flag, not a prerequisite for host approval; cannot override explicit deny rules, the host modification/uncertain-operation approval policy, the Root switch, or actual OS authorization.",
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ) = PendingToolPermission(
        id = callId,
        toolName = name,
        title = appContext.getString(R.string.root_confirmation_title),
        summary = command(args).orEmpty(),
        details = appContext.getString(R.string.root_confirmation_reason),
        argsPreview = argsPreview
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = approvalRequired()

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId?.takeIf { it.isNotBlank() } ?: return approvalRequired()
        val permitId = context.rootApprovalId ?: return approvalRequired()
        val command = command(args) ?: return ToolResult.Error(
            appContext.getString(R.string.root_command_required),
            "INVALID_ARGUMENTS"
        )
        return try {
            val result = manager.execute(command, timeoutMs(args), sessionId, permitId)
            when (result.exitCode) {
                0 -> ToolResult.Success(JsonPrimitive(result.output))
                RootCommandManager.EXIT_DISABLED -> ToolResult.Error(
                    appContext.getString(R.string.root_access_disabled), "ROOT_ACCESS_DISABLED"
                )
                RootCommandManager.EXIT_APPROVAL_REQUIRED -> approvalRequired()
                RootCommandManager.EXIT_PERMISSION_UNAVAILABLE, 126 -> ToolResult.Error(
                    appContext.getString(R.string.root_permission_unavailable), "ROOT_PERMISSION_UNAVAILABLE"
                )
                RootCommandManager.EXIT_TIMEOUT -> ToolResult.Error(
                    appContext.getString(R.string.root_command_timeout), "ROOT_COMMAND_TIMEOUT"
                )
                RootCommandManager.EXIT_INTERRUPTED -> ToolResult.Error(
                    appContext.getString(R.string.root_command_interrupted), "ROOT_COMMAND_INTERRUPTED"
                )
                else -> ToolResult.Error(
                    appContext.getString(
                        R.string.root_command_failed,
                        result.output.ifBlank { result.exitCode.toString() }
                    ).take(RootCommandManager.MAX_OUTPUT_CHARS),
                    "ROOT_COMMAND_FAILED"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(
                appContext.getString(R.string.root_command_failed, appContext.getString(R.string.root_permission_unavailable)),
                "ROOT_COMMAND_FAILED"
            )
        }
    }

    private fun approvalRequired() = ToolResult.Error(
        appContext.getString(R.string.root_approval_required), "ROOT_APPROVAL_REQUIRED"
    )

    private fun command(args: Map<String, JsonElement>): String? =
        (args["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun timeoutMs(args: Map<String, JsonElement>): Long =
        ((args["timeout"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1_000L
}
