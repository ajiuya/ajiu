package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

class ProjectMemoryCapture(
    val identity: ProjectMemoryIdentity,
    val channelGeneration: String? = null,
    private val checkCurrent: suspend () -> Unit
) {
    suspend fun assertCurrent() = checkCurrent()
}

class ProjectMemoryIdentityResolver @Inject constructor(
    private val executionModeHolder: ExecutionModeHolder,
    private val containerSettings: ContainerSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection
) {
    val environmentChanges: Flow<Unit>
        get() = combine(executionModeHolder.mode, containerSettings.activeProfileIdFlow, remoteSshConnection.connectionState) { _, _, _ -> Unit }.drop(1)

    suspend fun capture(
        workspaceRoot: String,
        expected: ProjectMemoryIdentity? = null,
        headless: Boolean = false
    ): ProjectMemoryCapture? {
        if (!validRoot(workspaceRoot)) return null
        val mode = executionModeHolder.currentMode()
        val captured = if (mode == ExecutionMode.REMOTE_SSH) {
            if (headless && expected == null) return null
            val transport = remoteSshConnection.captureIdentity()
            val config = remoteSshConnection.config ?: return null
            remoteSshConnection.checkIdentity(transport)
            val identity = normalize(ProjectMemoryIdentity(mode.name, config.host, config.port, config.username,
                normalizeRoot(workspaceRoot)))
            ProjectMemoryCapture(identity, transport.generation.toString()) {
                if (executionModeHolder.currentMode() != mode) throw CancellationException("Project execution mode changed")
                remoteSshConnection.checkIdentity(transport)
            }
        } else {
            val profileId = containerSettings.activeProfileIdFlow.first()
            val root = File(workspaceRoot).canonicalPath
            val identity = normalize(ProjectMemoryIdentity(mode.name, projectRoot = root, profileId = profileId))
            ProjectMemoryCapture(identity) {
                if (executionModeHolder.currentMode() != mode || containerSettings.activeProfileIdFlow.first() != profileId ||
                    File(workspaceRoot).canonicalPath != root
                ) throw CancellationException("Project execution identity changed")
            }
        }
        captured.assertCurrent()
        check(expected == null || captured.identity == normalize(expected)) { "Project memory environment changed" }
        return captured
    }

    suspend fun freeze(workspaceRoot: String): ProjectMemoryIdentity? = capture(workspaceRoot)?.identity

    suspend fun freezeForSession(workspaceRoot: String, expected: ProjectMemoryIdentity?, headless: Boolean): ProjectMemoryIdentity? =
        capture(workspaceRoot, expected, headless)?.identity

    fun candidates(frozen: ProjectMemoryIdentity, projects: List<ProjectMemoryProject>): List<ProjectMemoryProject> =
        matchingCandidates(frozen, projects)

    companion object {
        fun matchingCandidates(frozen: ProjectMemoryIdentity, projects: List<ProjectMemoryProject>): List<ProjectMemoryProject> =
            projects.filter { sameEndpoint(frozen, it.identity) && containsRoot(frozen.projectRoot, it.identity.projectRoot) }

        fun sameEndpoint(a: ProjectMemoryIdentity, b: ProjectMemoryIdentity): Boolean =
            a.mode.equals(b.mode, true) && a.profileId == b.profileId && a.host.equals(b.host, true) &&
                a.port == b.port && a.username == b.username

        fun containsRoot(workspace: String, project: String): Boolean =
            project == workspace || project.startsWith(workspace.trimEnd('/') + "/")

        fun normalizeRoot(path: String): String {
            require(validRoot(path)) { "Invalid project root" }
            val parts = mutableListOf<String>()
            path.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> { require(parts.isNotEmpty()) { "Project root escapes filesystem" }; parts.removeAt(parts.lastIndex) }
                    else -> parts.add(part)
                }
            }
            return "/" + parts.joinToString("/")
        }

        fun normalize(identity: ProjectMemoryIdentity): ProjectMemoryIdentity {
            val mode = identity.mode.trim().lowercase(Locale.ROOT)
            require(mode in setOf("local_proot", "remote_ssh")) { "Invalid execution mode" }
            require(identity.host.length <= 512 && identity.username.length <= 256 && identity.profileId.length <= 256)
            require(listOf(identity.host, identity.username, identity.profileId).none { value -> value.any { it.isISOControl() } })
            if (mode == "remote_ssh") {
                require(identity.host.isNotBlank() && identity.username.isNotBlank() && identity.port in 1..65535 && identity.profileId.isEmpty())
                require(identity.host.none { it in "/\\@?#" } && identity.username.none { it in "/\\@:" })
            } else {
                require(identity.host.isEmpty() && identity.username.isEmpty() && identity.port == 0 && identity.profileId.isNotBlank())
            }
            return identity.copy(mode = mode, host = identity.host.trim().lowercase(Locale.ROOT),
                projectRoot = normalizeRoot(identity.projectRoot))
        }

        private fun validRoot(path: String): Boolean = path.startsWith('/') && path.length <= 4096 &&
            path.none { it.isISOControl() || it == '\\' }
    }
}
