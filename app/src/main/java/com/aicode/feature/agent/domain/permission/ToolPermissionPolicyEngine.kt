package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.permission.ShellCommandParser.BlockerKind
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.shizuku.ShizukuIdentity
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val sessionPermissionGrants: SessionPermissionGrantRepository,
    @param:ApplicationContext private val appContext: Context,
    private val rootCommandManager: RootCommandManager,
    private val shizukuManager: ShizukuManager,
    // Defaults preserve legacy explicit constructors; Hilt injects the real singleton dependencies.
    private val modeHolder: ExecutionModeHolder? = null,
    private val containerSettings: ContainerSettingsRepository? = null,
    private val workspaceRepository: WorkspaceRepository? = null,
    private val remoteConnection: RemoteSshConnection? = null
) {
    private companion object {
        const val SHIZUKU_TOOL = "Shizuku"
        const val ROOT_TOOL = "Root"
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

    enum class PermissionNamespace { HOST, PROOT_GUEST, CHROOT_GUEST, REMOTE }

    /** Value-only evidence: never retains mutable flows, selected-profile providers or live lists. */
    data class PermissionSource(
        val namespace: PermissionNamespace,
        val mode: ExecutionMode? = null,
        val modeSelectionRevision: Long? = null,
        val selectedProfileId: String? = null,
        val profileSelectionRevision: Long? = null,
        val profileConfigurationRevision: Long? = null,
        val activeProfileId: String? = null,
        val localRuntime: LocalContainerRuntime? = null,
        val chrootInstance: ChrootInstance? = null,
        val workspaceSelectionId: String? = null,
        val workspacePath: String? = null,
        val remoteConnectionGeneration: String? = null
    )

    data class EvalResult(
        val verdict: Verdict,
        val denyReason: String? = null,
        val approvalNote: String? = null,
        val sessionGrant: String? = null,
        val askTitle: String? = null,
        val rootGeneration: Long? = null,
        val rootAuthorityId: String? = null,
        val shizukuIdentity: ShizukuIdentity? = null,
        val permissionSource: PermissionSource? = null,
        val hostSystemTargets: Set<String> = emptySet(),
        val channelUnavailable: Boolean = false,
        val hostSystemUncertainties: Set<String> = emptySet(),
        // CHROOT file/command review authority, never a Root tool command-permit request.
        val hostWriteRootGeneration: Long? = null,
        val hostWriteRootAuthorityId: String? = null,
        // Diagnostic only: ordinary CHROOT uncertainty never requires host approval authority.
        val ordinaryGuestUncertainties: Set<String> = emptySet()
    )

    suspend fun evaluate(
        toolName: String,
        args: Map<String, JsonElement>,
        sessionId: String? = null,
        execution: WorkspaceExecutionContext? = null
    ): EvalResult {
        val rules = rulesRepo.loadEffectiveForCurrentProject().filter {
            it.toolName == toolName && it.decision == PermissionDecision.DENY
        }
        if (rules.any { it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.tool_permission_rule_denied))
        }
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val shellTool = toolName == "Bash" || toolName == SHIZUKU_TOOL || toolName == ROOT_TOOL ||
            (toolName == TERMINAL_TOOL && action in setOf("start", "send"))
        val commandKey = if (toolName == TERMINAL_TOOL && action == "send") "input" else "command"
        val command = if (shellTool) (args[commandKey] as? JsonPrimitive)?.takeIf { it.isString }?.content else null
        val executableSegments = command?.let(ShellCommandParser::executableSegments).orEmpty()
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

        val sourceCapture = capturePermissionSource(toolName, execution)
        val source = sourceCapture.value
        val rootState = if (toolName == ROOT_TOOL) rootCommandManager.accessSnapshot() else null
        if (rootState != null && (!rootState.enabled || rootState.authorityId.isBlank())) {
            return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.root_access_disabled), permissionSource = source, channelUnavailable = true)
        }
        val shizukuIdentity = if (toolName == SHIZUKU_TOOL) {
            shizukuManager.accessSnapshot()
                ?: return EvalResult(Verdict.DENY, denyReason = appContext.getString(R.string.shizuku_identity_unavailable), permissionSource = source, channelUnavailable = true)
        } else null
        if (source.namespace == PermissionNamespace.HOST || source.namespace == PermissionNamespace.CHROOT_GUEST) {
            val namespace = if (source.namespace == PermissionNamespace.HOST) HostSystemFileAccessClassifier.Namespace.HOST
                else HostSystemFileAccessClassifier.Namespace.CHROOT_GUEST
            val assessment = if (toolName == TERMINAL_TOOL && action in setOf("send", "key"))
                HostSystemFileAccessClassifier.assessFileTool(toolName, args, namespace)
            else if (command != null) HostSystemFileAccessClassifier.assessCommand(command, namespace)
            else HostSystemFileAccessClassifier.assessFileTool(toolName, args, namespace)
            val targets = immutable(assessment.targets)
            // Host proof contains only review evidence. Preserve ordinary guest uncertainty in
            // a separate diagnostic snapshot so it cannot be mistaken for approval authority.
            val reviewUncertainties = if (source.namespace == PermissionNamespace.HOST) assessment.uncertainties
                else assessment.hostLinkedUncertainties
            val guestUncertainties = assessment.ordinaryGuestUncertainties
            val writes = targets.filter { it.startsWith("WRITE:") || it.startsWith("READ_WRITE:") }.sorted()
            val needsReview = writes.isNotEmpty() || reviewUncertainties.isNotEmpty()
            // Ordinary guest work and reads need no additional Root access. Host-linked writes and
            // uncertainty bind their separate proof slots to the live Root epoch.
            val hostWriteRoot = if (source.namespace == PermissionNamespace.CHROOT_GUEST && needsReview) {
                rootCommandManager.accessSnapshot()
            } else null
            sourceCapture.checkCurrent()
            if (hostWriteRoot != null && (!hostWriteRoot.enabled || hostWriteRoot.authorityId.isBlank())) {
                return EvalResult(
                    Verdict.DENY, denyReason = appContext.getString(R.string.root_access_disabled),
                    permissionSource = source, hostSystemTargets = targets,
                    hostSystemUncertainties = reviewUncertainties, ordinaryGuestUncertainties = guestUncertainties,
                    channelUnavailable = true
                )
            }
            val result = if (!needsReview) EvalResult(Verdict.ALLOW) else {
                val title = appContext.getString(R.string.tool_host_system_file_confirmation_title)
                // READ evidence remains in the proof but is never described as needing write approval.
                val note = if (reviewUncertainties.isEmpty()) {
                    appContext.getString(R.string.tool_host_system_file_confirmation_reason, writes.joinToString("\n"))
                } else {
                    appContext.getString(R.string.tool_host_system_file_uncertain_reason, (writes + reviewUncertainties.sorted()).joinToString("\n"))
                }
                val grant = hostWriteGrant(toolName, source, rootState ?: hostWriteRoot, shizukuIdentity)
                if (grant == null) EvalResult(Verdict.ASK, askTitle = title, approvalNote = note)
                else approval(sessionId, grant, title, note)
            }
            sourceCapture.checkCurrent()
            // ALLOW still needs native, exact-command, single-use permits. Never manufacture an identity.
            return result.copy(
                rootGeneration = rootState?.generation, rootAuthorityId = rootState?.authorityId,
                shizukuIdentity = shizukuIdentity, permissionSource = source, hostSystemTargets = targets,
                hostSystemUncertainties = reviewUncertainties, hostWriteRootGeneration = hostWriteRoot?.generation,
                hostWriteRootAuthorityId = hostWriteRoot?.authorityId, ordinaryGuestUncertainties = guestUncertainties
            )
        }
        if (command == null) return EvalResult(Verdict.ALLOW, permissionSource = source)

        sourceCapture.checkCurrent()
        val analysis = ShellCommandParser.analyze(command)
        val safetyReason = checkCatastrophicRm(executableSegments) ?: if (!analysis.analyzable && looksDestructive(command, analysis, executableSegments)) {
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
                    denyReason = appContext.getString(R.string.tool_safety_denied_with_elevation, safetyReason),
                    permissionSource = source
                )
            }
            return approval(
                sessionId,
                commandGrant(toolName, "elevate", command, source),
                appContext.getString(R.string.tool_elevation_confirmation_title),
                appContext.getString(R.string.tool_elevation_confirmation_reason)
            ).copy(permissionSource = source)
        }

        return when {
            ShellCommandParser.requiresRootApproval(command) -> approval(
                sessionId,
                commandGrant(toolName, "root", command, source),
                appContext.getString(R.string.tool_root_confirmation_title),
                appContext.getString(R.string.tool_root_confirmation_reason)
            )
            else -> EvalResult(Verdict.ALLOW)
        }.copy(permissionSource = source)
    }

    private data class CapturedPermissionSource(val value: PermissionSource, val checkCurrent: () -> Unit)

    private suspend fun capturePermissionSource(toolName: String, execution: WorkspaceExecutionContext?): CapturedPermissionSource {
        // Explicit host channels are independent of the currently selected guest/workspace.
        if (toolName == ROOT_TOOL || toolName == SHIZUKU_TOOL) {
            return CapturedPermissionSource(PermissionSource(PermissionNamespace.HOST)) {}
        }
        execution?.checkCurrent()
        val revision = modeHolder?.modeSelectionRevision
        val mode = execution?.mode ?: modeHolder?.currentMode() ?: ExecutionMode.LOCAL_PROOT
        if (modeHolder != null && modeHolder.currentMode() != mode) throw CancellationException("Workspace execution mode changed")
        // Capture before the suspending profile read: A -> B -> A must not repair a torn snapshot.
        val workspaceBefore = workspaceRepository?.selection?.value
        val remote = if (mode == ExecutionMode.REMOTE_SSH) remoteConnection?.identityState?.value else null
        val profile = if (execution != null) execution.profileSnapshot else containerSettings?.captureProfileSnapshot()
        if (execution != null && profile == null && containerSettings != null) {
            throw CancellationException("Captured workspace profile is unavailable")
        }
        if (profile != null && mode == ExecutionMode.LOCAL_PROOT && profile.selectedProfileId != profile.activeProfile.id) {
            throw CancellationException("Workspace execution source is not ready")
        }
        val active = profile?.activeProfile ?: execution?.localMapping?.profile
        val namespace = when {
            mode == ExecutionMode.REMOTE_SSH -> PermissionNamespace.REMOTE
            active?.isChroot == true || execution?.localRuntimeCapture != null -> PermissionNamespace.CHROOT_GUEST
            else -> PermissionNamespace.PROOT_GUEST
        }
        val frozenWorkspace = execution?.runtimeWorkspaceSource
        val workspaceSelection = if (namespace == PermissionNamespace.CHROOT_GUEST && workspaceRepository != null && execution != null) {
            frozenWorkspace?.selection
        } else workspaceBefore
        val workspacePath = if (workspaceSelection == null) null else {
            if (namespace == PermissionNamespace.CHROOT_GUEST && execution != null) frozenWorkspace?.sourcePath
            else workspaceSelection.workspace?.path
        }
        val checkCurrent = {
            execution?.checkCurrent()
            if (modeHolder != null && (modeHolder.currentMode() != mode || modeHolder.modeSelectionRevision != revision)) {
                throw CancellationException("Workspace execution mode changed")
            }
            profile?.let { containerSettings?.checkProfileSnapshot(it) }
            if (mode == ExecutionMode.REMOTE_SSH && remoteConnection != null && remoteConnection.identityState.value !== remote) {
                throw CancellationException("Remote permission source changed")
            }
            if (workspaceRepository != null) {
                if (workspaceRepository.selection.value !== workspaceBefore ||
                    (workspaceSelection != null && workspaceSelection !== workspaceBefore) ||
                    (workspaceSelection != null && workspacePath != workspaceBefore?.workspace?.path)) {
                    throw CancellationException("Workspace permission source changed")
                }
            }
            Unit
        }
        checkCurrent()
        return CapturedPermissionSource(
            PermissionSource(
                namespace, mode, revision, profile?.selectedProfileId, profile?.selectionRevision,
                profile?.configurationRevision, active?.id, active?.localRuntime, active?.chrootInstance,
                workspaceSelection?.id, workspacePath, remote?.generation?.toString()
            ), checkCurrent
        )
    }

    private fun commandGrant(toolName: String, kind: String, command: String, source: PermissionSource): String? {
        if (modeHolder == null || containerSettings == null || workspaceRepository == null ||
            source.mode == null || source.modeSelectionRevision == null || source.selectedProfileId.isNullOrBlank() ||
            source.profileSelectionRevision == null || source.profileConfigurationRevision == null ||
            source.activeProfileId != source.selectedProfileId || source.workspaceSelectionId.isNullOrBlank() ||
            source.workspacePath.isNullOrBlank() || workspaceRepository.selection.value.workspace?.available != true ||
            source.namespace == PermissionNamespace.REMOTE && (remoteConnection == null || source.remoteConnectionGeneration.isNullOrBlank())) return null
        return "command-session-v1:" + hashFields(listOf(
            toolName, kind, command, source.namespace.name, source.mode?.name, source.modeSelectionRevision?.toString(),
            source.selectedProfileId, source.profileSelectionRevision?.toString(), source.profileConfigurationRevision?.toString(),
            source.activeProfileId, source.localRuntime?.name, source.chrootInstance?.id,
            source.chrootInstance?.device?.toString(), source.chrootInstance?.inode?.toString(), source.chrootInstance?.installGeneration?.toString(),
            source.workspaceSelectionId, source.workspacePath, source.remoteConnectionGeneration
        ))
    }

    /** A missing source component permits one-shot review only, never an unbound broad grant. */
    private fun hostWriteGrant(
        toolName: String,
        source: PermissionSource,
        root: RootAccessState?,
        shizuku: ShizukuIdentity?
    ): String? {
        when (source.namespace) {
            PermissionNamespace.HOST -> when (toolName) {
                ROOT_TOOL -> if (root == null || !root.enabled || root.authorityId.isBlank()) return null
                SHIZUKU_TOOL -> if (shizuku == null) return null
                else -> return null
            }
            PermissionNamespace.CHROOT_GUEST -> {
                if (modeHolder == null || containerSettings == null || workspaceRepository == null ||
                    source.mode != ExecutionMode.LOCAL_PROOT || source.modeSelectionRevision == null ||
                    source.selectedProfileId.isNullOrBlank() || source.profileSelectionRevision == null ||
                    source.profileConfigurationRevision == null || source.activeProfileId != source.selectedProfileId ||
                    source.localRuntime != LocalContainerRuntime.CHROOT || source.chrootInstance?.id.isNullOrBlank() ||
                    source.workspaceSelectionId.isNullOrBlank() || source.workspacePath.isNullOrBlank() ||
                    root == null || !root.enabled || root.authorityId.isBlank()) return null
            }
            else -> return null
        }
        // Length-framed nullable UTF-8 fields avoid delimiter/null collisions. No command, target,
        // content, prompt or secret-bearing arguments ever participate in this source-wide key.
        val material = listOf(
            toolName, source.namespace.name, source.mode?.name, source.modeSelectionRevision?.toString(),
            source.selectedProfileId, source.profileSelectionRevision?.toString(), source.profileConfigurationRevision?.toString(),
            source.activeProfileId, source.localRuntime?.name, source.chrootInstance?.id,
            source.chrootInstance?.device?.toString(), source.chrootInstance?.inode?.toString(), source.chrootInstance?.installGeneration?.toString(),
            source.workspaceSelectionId, source.workspacePath, root?.authorityId, root?.generation?.toString(),
            shizuku?.uid?.toString(), shizuku?.connectionId
        )
        return SessionPermissionGrantRepository.HOST_WRITE_GRANT_PREFIX + hashFields(material)
    }

    private fun hashFields(material: List<String?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in material) {
            val bytes = field?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun immutable(values: Collection<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values))

    private suspend fun approval(sessionId: String?, grant: String?, title: String?, note: String): EvalResult {
        if (!sessionId.isNullOrBlank() && grant != null && sessionPermissionGrants.isAllowed(sessionId, grant)) {
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

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (segment in segments) {
            val info = ShellCommandParser.parseRmInfo(segment)
            if (!info.isRm) continue
            for (path in info.targetPaths) {
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
