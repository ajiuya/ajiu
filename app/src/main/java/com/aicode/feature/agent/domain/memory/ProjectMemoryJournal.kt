package com.aicode.feature.agent.domain.memory

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ProjectJournalHistory(
    val schemaVersion: Int = 1,
    val projects: List<ProjectMemoryProject> = emptyList(),
    val entries: List<ProjectMemoryEntry> = emptyList(),
    val archives: List<ProjectMemoryEntry> = emptyList()
)

@Serializable
private data class SessionProjectBinding(
    val identity: ProjectMemoryIdentity,
    val hasProjectBinding: Boolean = false,
    val projectId: String? = null
)

@Serializable
private data class WorkspaceProjectBinding(val identity: ProjectMemoryIdentity, val projectId: String? = null)

@Singleton
class ProjectMemoryJournal(
    directory: File,
    trustedFilesDir: File? = null,
    private val beforeCommit: (() -> Unit)? = null
) {
    @Inject constructor(containerInstaller: ContainerInstaller, @ApplicationContext context: Context) :
        this(File(containerInstaller.aicodeDir, "memory/project-journal"), context.filesDir)

    private val root = if (trustedFilesDir == null) directory.absoluteFile else {
        val prefix = trustedFilesDir.absoluteFile.toPath().normalize()
        val path = directory.absoluteFile.toPath().normalize()
        require(path.startsWith(prefix) && path != prefix) { "Journal must be below app files directory" }
        trustedFilesDir.canonicalFile.toPath().resolve(prefix.relativize(path)).toFile()
    }
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val changeCounter = CHANGES.getOrPut(root.toPath().normalize().toString()) { MutableStateFlow(0L) }
    val changes: StateFlow<Long> = changeCounter.asStateFlow()

    fun registerProject(
        identity: ProjectMemoryIdentity,
        name: String,
        aliases: List<String>? = null,
        updateExisting: Boolean = false
    ): ProjectMemoryProject = locked {
        validateProjectName(name, aliases.orEmpty())
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        val current = historySnapshot()
        val existing = current.projects.firstOrNull { it.identity == normalized }
        if (existing != null) {
            if (!updateExisting) return@locked existing
            val updated = existing.copy(name = name.trim(), aliases = aliases?.map { it.trim() }?.distinct() ?: existing.aliases)
            if (updated != existing) writeHistory(current.copy(projects = current.projects.map { if (it.id == existing.id) updated else it }))
            return@locked updated
        }
        val project = ProjectMemoryProject(UUID.randomUUID().toString(), name.trim(), aliases.orEmpty().map { it.trim() }.distinct(), normalized)
        writeHistory(current.copy(projects = current.projects + project))
        project
    }

    fun listProjects(limit: Int = MAX_PROJECTS): List<ProjectMemoryProject> = locked {
        historySnapshot().projects.take(limit.coerceIn(0, MAX_PROJECTS))
    }

    fun findProjects(query: String, limit: Int = MAX_PROJECTS): List<ProjectMemoryProject> = locked {
        require(query.length <= 512)
        if (query.isBlank()) emptyList() else historySnapshot().projects.filter {
            it.name.contains(query.trim(), true) || it.aliases.any { alias -> alias.contains(query.trim(), true) }
        }.take(limit.coerceIn(0, MAX_PROJECTS))
    }

    fun setAutoProgressEnabled(projectId: String, enabled: Boolean): ProjectMemoryProject = locked {
        val current = historySnapshot()
        val updated = project(current, projectId).copy(autoProgressEnabled = enabled)
        writeHistory(current.copy(projects = current.projects.map { if (it.id == projectId) updated else it }))
        updated
    }

    fun append(
        projectId: String,
        summary: String,
        body: String,
        kind: String = "note",
        status: String = "recorded",
        turnId: String? = null,
        sourceSessionId: String? = null
    ): ProjectMemoryEntry = locked {
        val current = historySnapshot()
        project(current, projectId)
        validateEntry(summary, body, kind, status, turnId, sourceSessionId)
        val previous = turnId?.let { turn -> all(current).firstOrNull { it.projectId == projectId && it.turnId == turn } }
        if (previous != null) return@locked previous
        newEntry(current, projectId, turnId, summary, body, kind, status, sourceSessionId).also {
            writeHistory(current.copy(entries = current.entries + it))
        }
    }

    fun upsertTurn(
        projectId: String,
        turnId: String,
        summary: String,
        body: String,
        status: String,
        eventSequence: Long = 0,
        sourceSessionId: String? = null
    ): ProjectMemoryEntry? = locked {
        val current = historySnapshot()
        if (!project(current, projectId).autoProgressEnabled) return@locked null
        validateEntry(summary, body, "turn", status, turnId, sourceSessionId)
        require(status in TURN_STATUSES && eventSequence >= 0)
        val previous = all(current).firstOrNull { it.projectId == projectId && it.turnId == turnId }
        if (previous != null) {
            check(sourceSessionId == null || previous.sourceSessionId == null || sourceSessionId == previous.sourceSessionId) {
                "Turn source session conflict"
            }
            if (protected(previous) || (eventSequence > 0 && eventSequence <= previous.eventSequence) ||
                (previous.summary == summary && previous.body == body && previous.status == status)
            ) return@locked previous
        }
        val next = previous?.copy(summary = summary, body = body, status = status, eventSequence = eventSequence,
            revision = previous.revision + 1, updatedAt = nowAfter(previous.updatedAt), sourceSessionId = previous.sourceSessionId ?: sourceSessionId)
            ?: newEntry(current, projectId, turnId, summary, body, "turn", status, sourceSessionId).copy(eventSequence = eventSequence)
        writeHistory(current.copy(entries = current.entries.filterNot { it.id == next.id } + next))
        next
    }

    fun read(entryId: String): ProjectMemoryEntry? = locked {
        entryProjectId(entryId)
        all(historySnapshot()).firstOrNull { it.id == entryId }
    }

    fun entry(entryId: String): ProjectMemoryEntry? = read(entryId)

    fun handoff(projectId: String, summary: String, body: String, sourceSessionId: String? = null): ProjectMemoryEntry =
        append(projectId, summary, body, kind = "handoff", sourceSessionId = sourceSessionId)

    fun listRecent(projectId: String, limit: Int = 20, includeRevoked: Boolean = false, maxChars: Int = 16384, offset: Int = 0, kind: String? = null): List<ProjectMemoryEntry> = locked {
        require(kind == null || kind in setOf("turn", "handoff"))
        val current = historySnapshot()
        project(current, projectId)
        bounded(current.entries.filter { it.projectId == projectId && (includeRevoked || !it.revoked) && (kind == null || it.kind == kind) }, limit, maxChars, offset)
    }

    fun history(projectId: String, limit: Int = 20, offset: Int = 0, includeRevoked: Boolean = true, maxChars: Int = 16384): List<ProjectMemoryEntry> = locked {
        val current = historySnapshot()
        project(current, projectId)
        bounded(all(current).filter { it.projectId == projectId && (includeRevoked || !it.revoked) }, limit, maxChars, offset)
    }

    fun search(projectId: String, query: String, limit: Int = 20, maxChars: Int = 16384, offset: Int = 0): List<ProjectMemoryEntry> = locked {
        val current = historySnapshot()
        project(current, projectId)
        val terms = terms(query)
        if (terms.isEmpty()) emptyList() else bounded(current.entries.filter { it.projectId == projectId && !it.revoked && matches(it, terms) }, limit, maxChars, offset)
    }

    fun entryCount(projectId: String, archived: Boolean = false, query: String = ""): Int = locked {
        val current = historySnapshot()
        project(current, projectId)
        val terms = terms(query)
        (if (archived) current.archives else current.entries).count { it.projectId == projectId && (archived || !it.revoked) && matches(it, terms) }
    }

    fun resumeRecords(projectId: String, limit: Int = 32): ProjectMemoryResumeRecords = locked {
        val current = historySnapshot()
        project(current, projectId)
        val active = all(current).filter { it.projectId == projectId && !it.revoked }.sortedWith(RECENT_ORDER)
        val handoff = active.filter { it.kind == "handoff" }.maxWithOrNull(compareBy<ProjectMemoryEntry> { it.createdAt }.thenBy { it.id })
        val unresolved = active.filter { !it.archived && it.kind == "turn" && it.status != "completed" }
        val pending = if (handoff == null) unresolved.take(8) else unresolved.filter { it.updatedAt >= handoff.createdAt }.take(4)
        val facts = active.filter { !it.archived && it.kind != "handoff" && (it.kind != "turn" || it.status == "completed") &&
            (handoff == null || it.updatedAt >= handoff.createdAt) }.take(if (handoff == null) 3 else 12)
        val candidates = (pending + facts).distinctBy { it.id }.sortedWith(RECENT_ORDER)
        val selected = candidates.take(limit.coerceIn(0, 64))
        ProjectMemoryResumeRecords(handoff, selected, candidates.size - selected.size, unresolved.size,
            unresolved.count { item -> selected.none { it.id == item.id } })
    }

    fun edit(entryId: String, expectedRevision: Int, summary: String, body: String): ProjectMemoryEntry = mutate(entryId, expectedRevision) {
        check(!it.revoked) { "Entry is revoked" }
        validateEntry(summary, body, it.kind, it.status, it.turnId, it.sourceSessionId)
        it.copy(summary = summary, body = body, userEdited = true)
    }

    fun revoke(entryId: String, expectedRevision: Int): ProjectMemoryEntry = mutate(entryId, expectedRevision) {
        it.copy(revoked = true, userEdited = true)
    }

    fun archive(entryId: String, expectedRevision: Int): ProjectMemoryEntry = locked {
        val current = historySnapshot()
        val previous = requireNotNull(all(current).firstOrNull { it.id == entryId }) { "Entry not found" }
        check(previous.revision == expectedRevision) { "Entry revision conflict" }
        if (previous.archived) return@locked previous
        previous.copy(archived = true, revision = previous.revision + 1, updatedAt = nowAfter(previous.updatedAt)).also {
            writeHistory(current.copy(entries = current.entries.filterNot { item -> item.id == entryId }, archives = current.archives + it))
        }
    }

    fun listArchive(projectId: String? = null, limit: Int = 20, offset: Int = 0, query: String = "", maxChars: Int = 16384, kind: String? = null): List<ProjectMemoryEntry> = locked {
        require(kind == null || kind in setOf("turn", "handoff"))
        val current = historySnapshot()
        if (projectId != null) project(current, projectId)
        val terms = terms(query)
        bounded(current.archives.filter { (projectId == null || it.projectId == projectId) && matches(it, terms) && (kind == null || it.kind == kind) }, limit, maxChars, offset)
    }

    fun capacity(): ProjectMemoryCapacity = locked {
        val current = historySnapshot()
        ProjectMemoryCapacity(current.entries.size, MAX_ENTRIES, current.archives.size, MAX_ENTRIES - current.entries.size,
            json.encodeToString(current).toByteArray(Charsets.UTF_8).size, MAX_BYTES)
    }

    fun exportHistory(): String = locked { json.encodeToString(historySnapshot()) }
    fun validateHistory(text: String) { decodeHistory(text) }
    fun historyVersion(): String = locked { digest(json.encodeToString(historySnapshot())) }
    fun validateImportHistory(text: String, expectedVersion: String) = locked { mergedHistory(text, expectedVersion); Unit }
    fun importHistory(text: String, expectedVersion: String? = null) = locked { writeHistory(mergedHistory(text, expectedVersion)) }

    fun rememberSessionIdentity(sessionId: String, identity: ProjectMemoryIdentity) = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        if (sessionBinding(sessionId, normalized) == null) writeSessionBinding(sessionId, SessionProjectBinding(normalized))
    }

    fun sessionIdentity(sessionId: String): ProjectMemoryIdentity? = locked { sessionBinding(sessionId)?.identity }

    fun bindSessionProject(sessionId: String, identity: ProjectMemoryIdentity, projectId: String?) = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        val previous = sessionBinding(sessionId, normalized)
        if (projectId != null) boundProject(normalized, projectId)
        val binding = SessionProjectBinding(normalized, true, projectId)
        if (previous != binding) writeSessionBinding(sessionId, binding)
    }

    fun hasSessionProjectBinding(sessionId: String, identity: ProjectMemoryIdentity): Boolean = locked {
        sessionBinding(sessionId, ProjectMemoryIdentityResolver.normalize(identity))?.hasProjectBinding == true
    }

    fun sessionProject(sessionId: String, identity: ProjectMemoryIdentity): ProjectMemoryProject? = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        sessionBinding(sessionId, normalized)?.projectId?.let { boundProject(normalized, it) }
    }

    fun setWorkspaceProject(identity: ProjectMemoryIdentity, projectId: String?) = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        if (projectId != null) boundProject(normalized, projectId)
        val name = workspaceName(normalized)
        val previous = workspaceBinding(normalized)
        val next = WorkspaceProjectBinding(normalized, projectId)
        if (previous != next) {
            check(previous != null || metadata("workspace-").size < 1024) { "Workspace binding capacity reached" }
            writeMetadata(name, json.encodeToString(next))
        }
    }

    fun hasWorkspaceProjectBinding(identity: ProjectMemoryIdentity): Boolean = locked {
        workspaceBinding(ProjectMemoryIdentityResolver.normalize(identity)) != null
    }

    fun workspaceProject(identity: ProjectMemoryIdentity): ProjectMemoryProject? = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        workspaceBinding(normalized)?.projectId?.let { boundProject(normalized, it) }
    }

    fun associations(sessionId: String?, identity: ProjectMemoryIdentity): ProjectMemoryAssociations = locked {
        val normalized = ProjectMemoryIdentityResolver.normalize(identity)
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { sessionBinding(it, normalized) }
        val workspace = workspaceBinding(normalized)
        ProjectMemoryAssociations(if (sessionId.isNullOrBlank()) null else session?.hasProjectBinding == true,
            session?.projectId?.let { boundProject(normalized, it) }, workspace != null,
            workspace?.projectId?.let { boundProject(normalized, it) },
            sessionBindingState = when {
                session == null -> "none"
                !session.hasProjectBinding -> "unbound"
                session.projectId == null -> "explicit_null"
                else -> "bound"
            },
            workspaceBindingState = when {
                workspace == null -> "none"
                workspace.projectId == null -> "explicit_null"
                else -> "bound"
            })
    }

    fun clearSession(sessionId: String) = locked {
        val name = sessionName(sessionId)
        val receipt = readJson(name, MAX_METADATA_BYTES)
        if (receipt != null) {
            sessionBinding(sessionId)
            deleteReceipt(name, receipt)
            changeCounter.value += 1
        }
        metadata("progress-").forEach { file ->
            val text = requireNotNull(readJson(file.name, MAX_CHECKPOINT_BYTES))
            val checkpoint = decodeCheckpoint(file.name, text)
            if (checkpoint.sourceSessionId == sessionId) deleteReceipt(file.name, text)
        }
    }

    fun checkpointProgress(
        turnId: String,
        identity: ProjectMemoryIdentity,
        drafts: List<ProjectProgressDraft>,
        pendingTabs: List<String>,
        eventSequence: Long = 0,
        sourceSessionId: String? = null
    ) = locked {
        val checkpoint = ProjectProgressCheckpoint(turnId, ProjectMemoryIdentityResolver.normalize(identity), drafts,
            PROCESS_ID, pendingTabs, sourceSessionId, eventSequence)
        val name = checkpointName(turnId)
        val text = json.encodeToString(checkpoint)
        decodeCheckpoint(name, text)
        checkpoint.drafts.forEach { boundProject(checkpoint.identity, it.projectId) }
        val previous = readJson(name, MAX_CHECKPOINT_BYTES)?.let { decodeCheckpoint(name, it) }
        check(previous == null || (previous.identity == checkpoint.identity && previous.sourceSessionId == sourceSessionId)) { "Checkpoint owner conflict" }
        if (previous != null && eventSequence > 0 && eventSequence <= previous.eventSequence) return@locked
        check(previous != null || metadata("progress-").size < 256) { "Progress checkpoint capacity reached" }
        if (previous != checkpoint) atomicWrite(name, text)
    }

    fun clearProgressCheckpoint(turnId: String) = locked {
        val name = checkpointName(turnId)
        val text = readJson(name, MAX_CHECKPOINT_BYTES) ?: return@locked
        decodeCheckpoint(name, text)
        deleteReceipt(name, text)
    }

    fun recoverProgress(currentProcessId: String = PROCESS_ID): Int = locked {
        var current = historySnapshot()
        var recovered = 0
        val consumed = mutableListOf<Pair<String, String>>()
        metadata("progress-").forEach { file ->
            val text = requireNotNull(readJson(file.name, MAX_CHECKPOINT_BYTES))
            val checkpoint = decodeCheckpoint(file.name, text)
            if (checkpoint.processId == currentProcessId) return@forEach
            var deferred = false
            checkpoint.drafts.forEach draftLoop@ { draft ->
                val project = project(current, draft.projectId)
                check(ProjectMemoryIdentityResolver.matchingCandidates(checkpoint.identity, listOf(project)).size == 1) { "Checkpoint scope mismatch" }
                if (!project.autoProgressEnabled) { deferred = true; return@draftLoop }
                val previous = all(current).firstOrNull { it.projectId == project.id && it.turnId == checkpoint.turnId }
                if (previous != null && (protected(previous) || (previous.eventSequence > checkpoint.eventSequence && checkpoint.eventSequence > 0))) return@draftLoop
                val body = ("Recovery: interrupted / unknown / needs_verification. Process outcome cannot be verified after restart.\n" + draft.body).take(8192)
                val summary = "Project activity · interrupted · needs_verification"
                val next = previous?.copy(summary = summary, body = body, status = "interrupted", revision = previous.revision + 1,
                    updatedAt = nowAfter(previous.updatedAt), eventSequence = checkpoint.eventSequence)
                    ?: newEntry(current, project.id, checkpoint.turnId, summary, body, "turn", "interrupted", checkpoint.sourceSessionId)
                        .copy(eventSequence = checkpoint.eventSequence)
                current = current.copy(entries = current.entries.filterNot { it.id == next.id } + next)
                recovered++
            }
            if (!deferred) consumed.add(file.name to text)
        }
        if (recovered > 0) writeHistory(current)
        consumed.forEach { (name, receipt) -> deleteReceipt(name, receipt) }
        recovered
    }

    private fun mutate(entryId: String, expectedRevision: Int, change: (ProjectMemoryEntry) -> ProjectMemoryEntry): ProjectMemoryEntry = locked {
        entryProjectId(entryId)
        val current = historySnapshot()
        val previous = requireNotNull(all(current).firstOrNull { it.id == entryId }) { "Entry not found" }
        check(previous.revision == expectedRevision) { "Entry revision conflict" }
        val next = change(previous).copy(revision = previous.revision + 1, updatedAt = nowAfter(previous.updatedAt))
        writeHistory(current.copy(entries = current.entries.map { if (it.id == entryId) next else it },
            archives = current.archives.map { if (it.id == entryId) next else it }))
        next
    }

    private fun mergedHistory(text: String, expectedVersion: String?): ProjectJournalHistory {
        val current = historySnapshot()
        check(expectedVersion == null || digest(json.encodeToString(current)) == expectedVersion) { "Journal preview changed" }
        val incoming = decodeHistory(text)
        val projects = current.projects.associateBy { it.id }.toMutableMap()
        incoming.projects.forEach { item ->
            check(projects[item.id] == null || projects[item.id] == item) { "Project import conflict" }
            projects[item.id] = item
        }
        val entries = all(current).associateBy { it.id }.toMutableMap()
        all(incoming).forEach { item ->
            check(entries[item.id] == null || entries[item.id] == item) { "Entry import conflict" }
            entries[item.id] = item
        }
        return decodeHistory(json.encodeToString(ProjectJournalHistory(projects = projects.values.toList(),
            entries = entries.values.filterNot { it.archived }, archives = entries.values.filter { it.archived })))
    }

    private fun decodeCheckpoint(name: String, text: String): ProjectProgressCheckpoint {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CHECKPOINT_BYTES)
        return json.decodeFromString<ProjectProgressCheckpoint>(text).also { checkpoint ->
            require(name == checkpointName(checkpoint.turnId) && checkpoint.processId.isNotBlank() && checkpoint.processId.length <= 256)
            require(ProjectMemoryIdentityResolver.normalize(checkpoint.identity) == checkpoint.identity)
            require(checkpoint.drafts.size in 1..MAX_PROJECTS && checkpoint.drafts.map { it.projectId }.distinct().size == checkpoint.drafts.size)
            require(checkpoint.pendingTabs.size <= 128 && checkpoint.pendingTabs.distinct().size == checkpoint.pendingTabs.size &&
                checkpoint.pendingTabs.all { SAFE_TAB.matches(it) })
            require(checkpoint.eventSequence >= 0)
            checkpoint.drafts.forEach { draft ->
                requireUuid(draft.projectId)
                require(draft.body.length <= 8192)
                ProjectMemoryContentPolicy.validateReport("Checkpoint", draft.body)
            }
            validateSource(checkpoint.sourceSessionId)
        }
    }

    private fun historySnapshot(): ProjectJournalHistory = readJson("state.json", MAX_BYTES)?.let(::decodeHistory) ?: ProjectJournalHistory()
    private fun all(value: ProjectJournalHistory) = value.entries + value.archives

    private fun decodeHistory(text: String): ProjectJournalHistory {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Journal storage budget exceeded" }
        return json.decodeFromString<ProjectJournalHistory>(text).also { value ->
            require(value.schemaVersion == 1 && value.projects.size <= MAX_PROJECTS && value.entries.size <= MAX_ENTRIES)
            require(value.projects.map { it.id }.distinct().size == value.projects.size && value.projects.map { it.identity }.distinct().size == value.projects.size)
            value.projects.forEach {
                requireUuid(it.id)
                validateProjectName(it.name, it.aliases)
                require(ProjectMemoryIdentityResolver.normalize(it.identity) == it.identity)
            }
            val projectIds = value.projects.map { it.id }.toSet()
            require(value.entries.none { it.archived } && value.archives.all { it.archived })
            val entries = all(value)
            require(entries.map { it.id }.distinct().size == entries.size)
            val turns = entries.filter { it.turnId != null }.map { it.projectId to it.turnId }
            require(turns.distinct().size == turns.size)
            entries.forEach {
                require(it.projectId in projectIds && entryProjectId(it.id) == it.projectId && !it.truncated)
                require(it.revision in 1 until Int.MAX_VALUE && it.createdAt >= 0 && it.updatedAt >= it.createdAt && it.eventSequence >= 0)
                validateEntry(it.summary, it.body, it.kind, it.status, it.turnId, it.sourceSessionId)
                require(it.kind != "turn" || it.status in TURN_STATUSES)
            }
        }
    }

    private fun writeHistory(value: ProjectJournalHistory) {
        val text = json.encodeToString(value)
        decodeHistory(text)
        atomicWrite("state.json", text)
        changeCounter.value += 1
    }

    private fun project(current: ProjectJournalHistory, id: String): ProjectMemoryProject {
        requireUuid(id)
        return requireNotNull(current.projects.firstOrNull { it.id == id }) { "Unknown project" }
    }

    private fun boundProject(identity: ProjectMemoryIdentity, projectId: String): ProjectMemoryProject =
        project(historySnapshot(), projectId).also {
            check(ProjectMemoryIdentityResolver.matchingCandidates(identity, listOf(it)).size == 1) { "Project binding scope mismatch" }
        }

    private fun sessionBinding(sessionId: String, expected: ProjectMemoryIdentity? = null): SessionProjectBinding? {
        val text = readJson(sessionName(sessionId), MAX_METADATA_BYTES) ?: return null
        return json.decodeFromString<SessionProjectBinding>(text).also {
            check(ProjectMemoryIdentityResolver.normalize(it.identity) == it.identity && (!it.hasProjectBinding || it.projectId == null || UUID_PATTERN.matches(it.projectId)))
            check(it.hasProjectBinding || it.projectId == null) { "Invalid session binding" }
            check(expected == null || expected == it.identity) { "Session memory environment mismatch" }
            it.projectId?.let { id -> boundProject(it.identity, id) }
        }
    }

    private fun workspaceBinding(identity: ProjectMemoryIdentity): WorkspaceProjectBinding? {
        val text = readJson(workspaceName(identity), MAX_METADATA_BYTES) ?: return null
        return json.decodeFromString<WorkspaceProjectBinding>(text).also {
            check(it.identity == identity) { "Workspace memory environment mismatch" }
            it.projectId?.let { id -> boundProject(identity, id) }
        }
    }

    private fun writeSessionBinding(sessionId: String, binding: SessionProjectBinding) {
        val name = sessionName(sessionId)
        check(readJson(name, MAX_METADATA_BYTES) != null || metadata("session-").size < 4096) { "Session binding capacity reached" }
        writeMetadata(name, json.encodeToString(binding))
    }

    private fun writeMetadata(name: String, text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        atomicWrite(name, text)
        changeCounter.value += 1
    }

    private fun newEntry(current: ProjectJournalHistory, projectId: String, turnId: String?, summary: String, body: String,
        kind: String, status: String, sourceSessionId: String?): ProjectMemoryEntry {
        val previous = all(current).filter { it.projectId == projectId }.maxOfOrNull { it.createdAt } ?: -1L
        return ProjectMemoryEntry("${projectId}_${UUID.randomUUID()}", projectId, turnId, nowAfter(previous), kind = kind,
            summary = summary, body = body, status = status, sourceSessionId = sourceSessionId)
    }

    private fun protected(entry: ProjectMemoryEntry): Boolean = entry.archived || entry.revoked || entry.userEdited ||
        entry.kind != "turn" || entry.status in setOf("completed", "failed", "interrupted")

    private fun nowAfter(previous: Long): Long {
        check(previous < Long.MAX_VALUE)
        return maxOf(System.currentTimeMillis(), previous + 1)
    }

    private fun validateProjectName(name: String, aliases: List<String>) {
        require(name.isNotBlank() && name.length <= 200 && aliases.size <= 20 && aliases.all { it.isNotBlank() && it.length <= 200 })
        (aliases + name).forEach { ProjectMemoryContentPolicy.validateReport(it, "") }
    }

    private fun validateEntry(summary: String, body: String, kind: String, status: String, turnId: String?, sourceSessionId: String?) {
        require(summary.isNotBlank() && summary.length <= 512 && body.length <= 8192)
        require(kind in setOf("note", "handoff", "turn") && status.isNotBlank() && status.length <= 64 && status.none { it.isISOControl() })
        require(turnId == null || (turnId.isNotBlank() && turnId.length <= 256 && turnId.none { it.isISOControl() }))
        validateSource(sourceSessionId)
        ProjectMemoryContentPolicy.validateReport(summary, body)
    }

    private fun validateSource(source: String?) {
        require(source == null || Regex("[A-Za-z0-9_:.\\-]{1,256}").matches(source))
    }

    private fun terms(query: String): List<String> {
        require(query.length <= 512)
        return query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.also { require(it.size <= 16) }
    }

    private fun matches(entry: ProjectMemoryEntry, terms: List<String>): Boolean =
        terms.all { entry.summary.contains(it, true) || entry.body.contains(it, true) }

    private fun bounded(entries: List<ProjectMemoryEntry>, limit: Int, maxChars: Int, offset: Int): List<ProjectMemoryEntry> {
        require(offset >= 0)
        var remaining = maxChars.coerceIn(0, 65536)
        val result = mutableListOf<ProjectMemoryEntry>()
        for (entry in entries.sortedWith(RECENT_ORDER).drop(offset)) {
            if (result.size >= limit.coerceIn(0, 100) || remaining < 64) break
            val cost = entry.summary.length + entry.body.length
            if (cost > remaining) {
                val summary = entry.summary.take(remaining)
                result.add(entry.copy(summary = summary, body = entry.body.take((remaining - summary.length).coerceAtLeast(0)), truncated = true))
                break
            }
            remaining -= cost
            result.add(entry)
        }
        return result
    }

    private fun entryProjectId(id: String): String {
        require(id.length == 73 && id[36] == '_') { "Invalid entry ID" }
        requireUuid(id.substring(0, 36)); requireUuid(id.substring(37))
        return id.substring(0, 36)
    }

    private fun requireUuid(id: String) { require(UUID_PATTERN.matches(id)) { "Invalid journal ID" } }
    private fun sessionName(id: String): String { validateSource(id); require(id.isNotBlank()); return "session-${digest(id)}.json" }
    private fun workspaceName(identity: ProjectMemoryIdentity) = "workspace-${digest(json.encodeToString(identity))}.json"
    private fun checkpointName(turnId: String): String { validateSource(turnId); require(turnId.isNotBlank()); return "progress-${digest(turnId)}.json" }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun checkedRoot(create: Boolean = false) {
        val absolute = root.toPath().toAbsolutePath()
        require(absolute == absolute.normalize()) { "Journal root must be normalized" }
        var path = absolute.root
        for (segment in absolute) {
            path = path.resolve(segment)
            if (create && !Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            check(!Files.isSymbolicLink(path) && (!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))) {
                "Journal symbolic links and non-directory ancestors are not allowed"
            }
        }
        check(root.canonicalFile.toPath() == absolute) { "Journal root changed" }
    }

    private fun checkedFile(name: String): File {
        require(name == "state.json" || name == "journal.lock" || METADATA_NAME.matches(name) || TEMP_NAME.matches(name)) { "Invalid journal file name" }
        checkedRoot()
        return File(root, name).also {
            check(!Files.isSymbolicLink(it.toPath()) && (!Files.exists(it.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS))) {
                "Journal target is not a regular file"
            }
        }
    }

    private fun readJson(name: String, maxBytes: Int): String? {
        val file = checkedFile(name)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.size() <= maxBytes) { "Journal file budget exceeded" }
        val bytes = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            Channels.newInputStream(channel).use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxBytes) { "Journal file budget exceeded" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        checkedFile(name)
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
            "Journal file changed during read"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun atomicWrite(name: String, text: String) {
        val target = checkedFile(name)
        val temporaryName = ".${UUID.randomUUID()}.tmp"
        val temporary = checkedFile(temporaryName)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        var ownedTemporary = false
        try {
            FileChannel.open(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                ownedTemporary = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeCommit?.invoke()
            checkedFile(name); checkedFile(temporaryName)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            ownedTemporary = false
            checkedRoot()
            FileChannel.open(root.toPath(), READ).use { it.force(true) }
        } finally {
            if (ownedTemporary && !Files.isSymbolicLink(temporary.toPath())) {
                checkedFile(temporaryName)
                Files.deleteIfExists(temporary.toPath())
            }
        }
    }

    private fun metadata(prefix: String): List<File> {
        checkedRoot()
        return checkNotNull(root.listFiles()) { "Cannot enumerate journal metadata" }.filter {
            it.name.startsWith(prefix) && METADATA_NAME.matches(it.name)
        }.onEach { checkedFile(it.name) }
    }

    private fun deleteReceipt(name: String, receipt: String) {
        check(readJson(name, maxOf(MAX_METADATA_BYTES, MAX_CHECKPOINT_BYTES)) == receipt) { "Journal receipt changed" }
        Files.delete(checkedFile(name).toPath())
        FileChannel.open(root.toPath(), READ).use { it.force(true) }
    }

    private fun <T> locked(action: () -> T): T = synchronized(JOURNAL_LOCK) {
        checkedRoot(create = true)
        FileChannel.open(checkedFile("journal.lock").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { checkedRoot(); action() }
        }
    }

    companion object {
        private val JOURNAL_LOCK = Any()
        private val PROCESS_ID = UUID.randomUUID().toString()
        private const val MAX_PROJECTS = 256
        private const val MAX_ENTRIES = 2048
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 16384
        private const val MAX_CHECKPOINT_BYTES = 256 * 1024
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val METADATA_NAME = Regex("(?:session|workspace|progress)-[0-9a-f]{64}\\.json")
        private val TEMP_NAME = Regex("\\.[0-9a-f-]{36}\\.tmp")
        private val SAFE_TAB = Regex("[A-Za-z0-9_-]{1,100}")
        private val TURN_STATUSES = setOf("completed", "failed", "interrupted", "background_pending")
        private val RECENT_ORDER = compareByDescending<ProjectMemoryEntry> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id }
        private val CHANGES = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    }
}
