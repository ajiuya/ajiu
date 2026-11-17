package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.permission.ShellCommandParser.BlockerKind
import com.aicode.feature.agent.domain.root.RootCommandManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val sessionPermissionGrants: SessionPermissionGrantRepository,
    @param:ApplicationContext private val appContext: Context,
    private val rootCommandManager: RootCommandManager
) {
    private companion object {
        const val SHIZUKU_TOOL = "Shizuku"
        const val ROOT_TOOL = "Root"
        val HOST_ROOT_REFERENCE = Regex("(?<![A-Za-z0-9_])(?:su|sudo)(?![A-Za-z0-9_])")
        const val TERMINAL_TOOL = "terminal"
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    data class EvalResult(
        val verdict: Verdict,
        val denyReason: String? = null,
        val approvalNote: String? = null,
        val sessionGrant: String? = null,
        val askTitle: String? = null,
        val rootGeneration: Long? = null
    )

    suspend fun evaluate(toolName: String, args: Map<String, JsonElement>, sessionId: String? = null): EvalResult {
        val rules = rulesRepo.loadEffectiveForCurrentProject().filter {
            it.toolName == toolName && it.decision == PermissionDecision.DENY
        }
        if (rules.any { it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.tool_permission_rule_denied))
        }
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val rootState = if (toolName == ROOT_TOOL) rootCommandManager.accessSnapshot() else null
        if (rootState != null && !rootState.enabled) {
            return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.root_access_disabled))
        }
        val shellTool = toolName == "Bash" || toolName == SHIZUKU_TOOL || toolName == ROOT_TOOL ||
            (toolName == TERMINAL_TOOL && action in setOf("start", "send"))
        if (!shellTool) return EvalResult(Verdict.ALLOW)

        val commandKey = if (toolName == TERMINAL_TOOL && action == "send") "input" else "command"
        val command = (args[commandKey] as? JsonPrimitive)?.content ?: return EvalResult(Verdict.ALLOW)
        if (toolName == SHIZUKU_TOOL &&
            (ShellCommandParser.requiresRootApproval(command) || HOST_ROOT_REFERENCE.containsMatchIn(command))
        ) {
            return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.root_shizuku_use_root_tool))
        }
        val analysis = ShellCommandParser.analyze(command)
        val executableSegments = ShellCommandParser.executableSegments(command)
        val denied = rules.firstOrNull { rule ->
            executableSegments.any { ShellCommandParser.matches(rule.pattern, it) }
        }
        if (denied != null) {
            val segment = executableSegments.first { ShellCommandParser.matches(denied.pattern, it) }
            return EvalResult(
                Verdict.DENY,
                denyReason = appContext.getString(
                    R.string.tool_command_rule_denied_detail,
                    denied.toCompact(),
                    segment.joinToString(" ")
                )
            )
        }

        val safetyReason = checkCatastrophicRm(executableSegments, hostRoot = toolName == ROOT_TOOL) ?: if (!analysis.analyzable && looksDestructive(command, analysis, executableSegments)) {
            val constructs = analysis.blockers.joinToString("\n") { blocker ->
                val resource = when (blocker.kind) {
                    BlockerKind.COMMAND_SUBSTITUTION -> R.string.tool_safety_blocker_command_substitution
                    BlockerKind.BACKTICK_SUBSTITUTION -> R.string.tool_safety_blocker_backtick_substitution
                    BlockerKind.SUBSHELL_OR_GROUP -> R.string.tool_safety_blocker_subshell_or_group
                    BlockerKind.ABSOLUTE_OUTPUT_REDIRECT -> R.string.tool_safety_blocker_absolute_output_redirect
                    BlockerKind.DYNAMIC_OUTPUT_REDIRECT -> R.string.tool_safety_blocker_dynamic_output_redirect
                    BlockerKind.UNKNOWN_OUTPUT_REDIRECT -> R.string.tool_safety_blocker_unknown_output_redirect
                    BlockerKind.DYNAMIC_ARGUMENT -> R.string.tool_safety_blocker_dynamic_argument
                    BlockerKind.MISSING_REDIRECT_TARGET -> R.string.tool_safety_blocker_missing_redirect_target
                    BlockerKind.UNKNOWN_FILE_DESCRIPTOR -> R.string.tool_safety_blocker_unknown_file_descriptor
                    BlockerKind.COMPOUND_COMMAND -> R.string.tool_safety_blocker_compound_command
                    BlockerKind.UNTERMINATED_ESCAPE -> R.string.tool_safety_blocker_unterminated_escape
                    BlockerKind.UNTERMINATED_QUOTE -> R.string.tool_safety_blocker_unterminated_quote
                }
                if (blocker.detail == null) appContext.getString(resource) else appContext.getString(resource, blocker.detail)
            }
            appContext.getString(R.string.tool_safety_dynamic_command, constructs)
        } else {
            null
        }
        if (safetyReason != null) {
            if ((args["elevate"] as? JsonPrimitive)?.booleanOrNull != true) {
                return EvalResult(
                    Verdict.DENY,
                    denyReason = appContext.getString(R.string.tool_safety_denied_with_elevation, safetyReason)
                )
            }
            if (rootState != null) return rootApproval(rootState.generation)
            return approval(
                sessionId,
                "elevate:$command",
                appContext.getString(R.string.tool_elevation_confirmation_title),
                appContext.getString(R.string.tool_elevation_confirmation_reason)
            )
        }

        return when {
            rootState != null -> rootApproval(rootState.generation)
            toolName == SHIZUKU_TOOL -> approval(
                sessionId,
                "tool:$SHIZUKU_TOOL",
                null,
                appContext.getString(R.string.tool_shizuku_confirmation_reason)
            )
            ShellCommandParser.requiresRootApproval(command) -> approval(
                sessionId,
                "root:$command",
                appContext.getString(R.string.tool_root_confirmation_title),
                appContext.getString(R.string.tool_root_confirmation_reason)
            )
            else -> EvalResult(Verdict.ALLOW)
        }
    }

    private fun rootApproval(generation: Long): EvalResult = EvalResult(
        Verdict.ASK,
        askTitle = appContext.getString(R.string.root_confirmation_title),
        approvalNote = appContext.getString(R.string.root_confirmation_reason),
        rootGeneration = generation
    )

    private suspend fun approval(sessionId: String?, grant: String, title: String?, note: String): EvalResult {
        if (!sessionId.isNullOrBlank() && sessionPermissionGrants.isAllowed(sessionId, grant)) {
            return EvalResult(Verdict.ALLOW)
        }
        return EvalResult(
            verdict = Verdict.ASK,
            approvalNote = note,
            sessionGrant = grant.takeIf { !sessionId.isNullOrBlank() },
            askTitle = title
        )
    }

    private fun looksDestructive(
        command: String,
        analysis: ShellCommandParser.Analysis,
        segments: List<List<String>>
    ): Boolean = analysis.hasFileOutputRedirect || command.contains("-delete") ||
        DESTRUCTIVE_PROGRAM.containsMatchIn(command) || segments.any {
            DESTRUCTIVE_PROGRAM.matches(ShellCommandParser.programPrefix(it)?.substringAfterLast('/').orEmpty())
        }

    private fun checkCatastrophicRm(segments: List<List<String>>, hostRoot: Boolean): String? {
        for (segment in segments) {
            val info = ShellCommandParser.parseRmInfo(segment)
            if (!info.isRm) continue
            for (path in info.targetPaths) {
                if (hostRoot) {
                    val normalized = normalizePath(path.trim())
                    val androidPaths = setOf("/data", "/storage", "/sdcard", "/system", "/vendor", "/product", "/odm", "/metadata", "/persist")
                    if (androidPaths.any { normalized == it || normalized.startsWith("$it/") || normalized.startsWith("$it*") }) {
                        return appContext.getString(R.string.root_safety_android_delete)
                    }
                }
                catastrophicReasonFor(path)?.let { return it }
            }
        }
        return null
    }

    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        val reason = when {
            path == "/" || path == "/*" -> R.string.tool_safety_delete_root
            path in RELATIVE_WILDCARD_TARGETS -> R.string.tool_safety_delete_wildcard
            ROOT_LEVEL_GLOB.matches(path) -> R.string.tool_safety_delete_system
            path == HOME_DIR || path == "$HOME_DIR/*" -> R.string.tool_safety_delete_home
            path in WORKSPACE_ROOT_TARGETS -> R.string.tool_safety_delete_workspace
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> return null
            path == "/tmp" || path == "/tmp/*" -> R.string.tool_safety_delete_tmp
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> R.string.tool_safety_delete_system
            else -> return null
        }
        return appContext.getString(reason)
    }

    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }
}
