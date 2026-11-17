package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

internal interface PlayerFileExecution {
    val access: FileAccessProvider
    val identity: String
    fun checkCurrent()
    fun <T> run(block: () -> T): T
}

internal class PlayerWorkspaceBinding(
    private val execution: WorkspaceExecutionContext,
    private val capture: ProjectMemoryCapture,
    private val owner: Job,
    private val checkWorkspace: () -> Unit
) : PlayerFileExecution {
    override val access: FileAccessProvider get() = execution.fileAccess
    override val identity: String get() = execution.fileCacheIdentity
    val namespace: String = playerStateNamespace(capture.identity)

    override fun checkCurrent() {
        owner.ensureActive()
        checkWorkspace()
        execution.checkCurrent()
    }

    // Executor threads do not inherit coroutine thread-local workspace mappings.
    override fun <T> run(block: () -> T): T = runBlocking(execution + owner) {
        checkCurrent()
        capture.assertCurrent()
        block().also { checkCurrent(); capture.assertCurrent() }
    }

    companion object {
        suspend fun capture(repository: WorkspaceRepository, resolver: ProjectMemoryIdentityResolver,
            factory: WorkspaceExecutionContextFactory, owner: Job): PlayerWorkspaceBinding? {
            val workspace = repository.current.value?.takeIf { it.available } ?: return null
            val capture = resolver.capture(workspace.path) ?: return null
            val execution = factory.freeze(capture)
            return PlayerWorkspaceBinding(execution, capture, owner) {
                if (repository.current.value != workspace) throw CancellationException("Player workspace changed")
            }.also { it.checkCurrent(); capture.assertCurrent() }
        }
    }
}

internal fun playerStateNamespace(identity: ProjectMemoryIdentity): String {
    val normalized = ProjectMemoryIdentityResolver.normalize(identity)
    val fields = listOf(normalized.mode, normalized.profileId, normalized.host,
        normalized.port.toString(), normalized.username, normalized.projectRoot)
    return stateNamespaceHash(fields.joinToString("") { "${it.length}:$it" })
}

internal fun stateNamespaceHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
