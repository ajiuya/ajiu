package com.aicode.feature.agent.domain.tool.shizuku

import android.content.Context
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.shizuku.ShizukuShellService
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 通过 Shizuku 执行命令的工具。
 *
 * 与 [com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool]（本地容器 / 远程 SSH）不同，
 * 本工具直接作用于 Android 系统本身：可执行 `pm` / `am` / `cmd` 等系统命令。
 * 需用户已安装 Shizuku 并授予本应用权限，否则返回错误提示。
 *
 * UserService 的 AIDL 调用是同步、一次性的（无逐行回传），故不实现流式输出。
 */
class ShizukuTool @Inject constructor(
    private val shizukuManager: ShizukuManager,
    @param:ApplicationContext private val appContext: Context
) : AgentTool() {
    private companion object {
        const val TAG = "ShizukuTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Shizuku"

    override val description =
        "通过 Shizuku 在宿主 Android 系统上执行 pm/am/cmd 等命令，用于辅助调试。" +
            "需已安装、运行并授权 Shizuku，支持 ADB shell 与 Root 启动。默认需用户确认，可选择本会话始终允许；" +
            "授权按当前服务身份和会话隔离，不同会话不共享，显式禁止规则与高危保护仍优先。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要通过 Shizuku 执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "请求对被高危策略拦截的命令单独审批，不绕过禁止规则或系统授权。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = (args["timeout"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = command(args) ?: appContext.getString(R.string.shizuku_command_required)
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = appContext.getString(R.string.shizuku_confirmation_title),
            summary = command,
            details = appContext.getString(R.string.shizuku_confirmation_details, timeoutSeconds),
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = approvalRequired()

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val command = command(args)
            ?: return ToolResult.Error(appContext.getString(R.string.shizuku_command_required), "INVALID_ARGUMENTS")

        shizukuManager.refreshState()
        val state = shizukuManager.state.value
        if (state != ShizukuState.READY && state != ShizukuState.READY_ROOT) {
            return ToolResult.Error(appContext.getString(R.string.shizuku_not_ready, stateHint(state)), code = "SHIZUKU_NOT_READY")
        }
        val sessionId = context.sessionId?.takeIf { it.isNotBlank() } ?: return approvalRequired()
        val permitId = context.shizukuApprovalId ?: return approvalRequired()

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Shizuku exec (timeout=${timeoutMs}ms): $command")
            val result = shizukuManager.runApprovedCommand(command, timeoutMs, sessionId, permitId)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Shizuku exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            when (result.exitCode) {
                0 -> ToolResult.Success(JsonPrimitive(output))
                ShizukuShellService.EXIT_TIMEOUT -> ToolResult.Error(
                    appContext.getString(R.string.shizuku_command_timeout), "SHIZUKU_COMMAND_TIMEOUT"
                )
                else -> ToolResult.Error(
                    appContext.getString(R.string.shizuku_command_exit_failed, result.exitCode, output), "SHIZUKU_COMMAND_FAILED"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku exec 失败: $command", e)
            ToolResult.Error(appContext.getString(R.string.shizuku_command_failed, e.message.orEmpty()))
        }
    }

    private fun command(args: Map<String, JsonElement>): String? =
        (args["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun approvalRequired() = ToolResult.Error(
        appContext.getString(R.string.shizuku_approval_required), "SHIZUKU_APPROVAL_REQUIRED"
    )

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> appContext.getString(R.string.settings_shizuku_hint_not_installed)
        ShizukuState.NOT_RUNNING -> appContext.getString(R.string.settings_shizuku_hint_not_running)
        ShizukuState.PERMISSION_DENIED -> appContext.getString(R.string.settings_shizuku_hint_denied)
        ShizukuState.SHELL_REQUIRED -> appContext.getString(R.string.shizuku_shell_required)
        ShizukuState.IDENTITY_UNAVAILABLE -> appContext.getString(R.string.shizuku_identity_unavailable)
        ShizukuState.READY, ShizukuState.READY_ROOT -> ""
    }
}
