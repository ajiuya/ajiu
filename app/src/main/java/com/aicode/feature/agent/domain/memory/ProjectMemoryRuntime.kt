package com.aicode.feature.agent.domain.memory

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

@Serializable
data class ProjectMemoryIdentity(
    val mode: String,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val projectRoot: String,
    val profileId: String = ""
)

@Serializable
data class ProjectMemoryProject(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val identity: ProjectMemoryIdentity,
    val autoProgressEnabled: Boolean = true
)

@Serializable
data class ProjectMemoryEntry(
    val id: String,
    val projectId: String,
    val turnId: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val kind: String,
    val summary: String,
    val body: String,
    val status: String,
    val revision: Int = 1,
    val revoked: Boolean = false,
    val userEdited: Boolean = false,
    val truncated: Boolean = false,
    val archived: Boolean = false,
    val sourceSessionId: String? = null,
    val eventSequence: Long = 0
)

data class ProjectMemoryCapacity(
    val activeEntries: Int,
    val maxActiveEntries: Int,
    val archivedEntries: Int,
    val remainingEntries: Int,
    val usedBytes: Int,
    val maxBytes: Int,
    val remainingBytes: Int = maxBytes - usedBytes,
    val nearCapacity: Boolean = remainingEntries < 100 || remainingBytes < maxBytes / 10,
    val byteCapacityReached: Boolean = remainingBytes <= 0
)

data class ProjectMemoryResumeRecords(
    val handoff: ProjectMemoryEntry?,
    val entries: List<ProjectMemoryEntry>,
    val omittedEntries: Int,
    val unresolvedTurns: Int,
    val omittedUnresolvedTurns: Int
)

data class ProjectMemoryAssociations(
    val sessionBindingPresent: Boolean?,
    val sessionProject: ProjectMemoryProject?,
    val workspaceBindingPresent: Boolean,
    val workspaceProject: ProjectMemoryProject?,
    val sessionBindingState: String = "none",
    val workspaceBindingState: String = "none"
)

@Serializable
data class ProjectProgressDraft(val projectId: String, val body: String)

@Serializable
data class ProjectProgressCheckpoint(
    val turnId: String,
    val identity: ProjectMemoryIdentity,
    val drafts: List<ProjectProgressDraft>,
    val processId: String,
    val pendingTabs: List<String> = emptyList(),
    val sourceSessionId: String? = null,
    val eventSequence: Long = 0
)

class ProjectMemoryTurnOverride(
    private val sessionId: String?,
    private val inputMessageId: String
) {
    data class Selection(val projectId: String?)
    @Volatile private var selection: Selection? = null

    fun forTurn(sessionId: String?, inputMessageId: String): ProjectMemoryTurnOverride =
        if (this.sessionId == sessionId && this.inputMessageId == inputMessageId) this
        else ProjectMemoryTurnOverride(sessionId, inputMessageId)

    fun get(): Selection? = selection

    @Synchronized fun bind(projectId: String?, persist: () -> Unit) {
        persist()
        selection = Selection(projectId)
    }
}

@Singleton
class ProjectMemoryRuntimeBindings @Inject constructor() {
    data class Binding(
        val identity: ProjectMemoryIdentity?,
        val context: String?,
        val projectId: String? = null,
        val capture: ProjectMemoryCapture? = null
    )

    private val bindings = ConcurrentHashMap<String, Binding>()

    @Synchronized fun remember(
        sessionId: String,
        identity: ProjectMemoryIdentity?,
        context: String?,
        projectId: String? = null,
        capture: ProjectMemoryCapture? = null
    ) {
        require(sessionId.isNotBlank())
        require(capture == null || capture.identity == identity)
        check(bindings.containsKey(sessionId) || bindings.size < 4096) { "Project memory runtime capacity reached" }
        bindings[sessionId] = Binding(identity, context, projectId, capture)
    }

    fun get(sessionId: String): Binding? = bindings[sessionId]
    fun release(sessionId: String) { bindings.remove(sessionId) }
    fun forget(sessionId: String) = release(sessionId)
}
