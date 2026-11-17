package com.aicode.feature.agent.domain.tool.memory

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryPathResolver
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class MemoryJournalActions @Inject constructor(
    private val journal: ProjectMemoryJournal,
    private val identities: ProjectMemoryIdentityResolver,
    private val paths: ProjectMemoryPathResolver,
    @param:ApplicationContext private val appContext: Context
) {
    suspend fun execute(action: String, args: Map<String, JsonElement>, context: AgentContext): ToolResult = withContext(Dispatchers.IO) {
        val capture = context.projectMemoryCapture ?: return@withContext error(R.string.project_memory_environment_unavailable, "MEMORY_NO_PROJECT")
        val identity = context.projectMemoryIdentity
        if (identity == null || capture.identity != identity) return@withContext error(R.string.project_memory_scope_mismatch, "MEMORY_SCOPE_MISMATCH")
        try {
            capture.assertCurrent()
            executeCaptured(action, args, context, capture)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            error(R.string.project_memory_invalid_argument, "MEMORY_INVALID_ARGUMENT")
        } catch (failure: IllegalStateException) {
            if (failure.message.orEmpty().contains("conflict", true) || failure.message.orEmpty().contains("changed", true)) {
                error(R.string.project_memory_conflict, "MEMORY_CONFLICT")
            } else error(R.string.project_memory_operation_failed, "MEMORY_OPERATION_FAILED")
        } catch (_: IOException) {
            error(R.string.project_memory_io_failed, "MEMORY_IO_FAILED")
        } catch (_: SecurityException) {
            error(R.string.project_memory_io_failed, "MEMORY_IO_FAILED")
        }
    }

    private suspend fun executeCaptured(action: String, args: Map<String, JsonElement>, context: AgentContext, capture: ProjectMemoryCapture): ToolResult {
        val identity = capture.identity
        if (action in setOf("journal_capacity", "capacity")) {
            val capacity = checked(capture) { journal.capacity() }
            return ToolResult.Success(buildJsonObject {
                put("activeEntries", capacity.activeEntries); put("maxActiveEntries", capacity.maxActiveEntries)
                put("archivedEntries", capacity.archivedEntries); put("remainingEntries", capacity.remainingEntries)
                put("usedBytes", capacity.usedBytes); put("maxBytes", capacity.maxBytes); put("remainingBytes", capacity.remainingBytes)
                put("nearCapacity", capacity.nearCapacity); put("byteCapacityReached", capacity.byteCapacityReached)
                put("notice", appContext.getString(R.string.project_memory_capacity_notice))
            })
        }
        if (action == "register_project") {
            val name = string(args, "name") ?: return missing()
            val rawRoot = string(args, "project_root") ?: return missing()
            val root = checked(capture) { paths.resolve(identity, rawRoot) }
                ?: return error(R.string.project_memory_scope_mismatch, "MEMORY_SCOPE_MISMATCH")
            if (!identity.mode.equals("remote_ssh", true) && !checked(capture) { File(root).isDirectory }) return missing()
            val aliases = if (args.containsKey("aliases")) {
                val values = args["aliases"] as? JsonArray ?: return missing()
                values.map { value -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return missing() }
            } else null
            val project = checked(capture) { journal.registerProject(identity.copy(projectRoot = root), name, aliases, updateExisting = true) }
            return ToolResult.Success(buildJsonObject {
                put("project_id", project.id); put("name", project.name); put("root", project.identity.projectRoot)
                put("autoProgressEnabled", project.autoProgressEnabled)
            })
        }
        val candidates = identities.candidates(identity, checked(capture) { journal.listProjects() })
        if (action == "bind_project" || action == "bind_workspace_project") {
            val requested = string(args, "project_id") ?: return missing()
            val id = requested.takeIf { it.isNotBlank() }
            if (id != null && candidates.none { it.id == id }) return error(R.string.project_memory_scope_mismatch, "MEMORY_SCOPE_MISMATCH")
            if (action == "bind_workspace_project") {
                checked(capture) { journal.setWorkspaceProject(identity, id) }
            } else {
                val session = context.sessionId?.takeIf { it.isNotBlank() } ?: return missing()
                checked(capture) {
                    val persist = { journal.bindSessionProject(session, identity, id) }
                    val turnOverride = context.projectMemoryTurnOverride?.forTurn(context.sessionId, context.inputMessageId)
                    if (turnOverride == null) persist() else turnOverride.bind(id, persist)
                }
            }
            return ToolResult.Success(buildJsonObject {
                put("project_id", id?.let(::JsonPrimitive) ?: JsonNull)
                put("binding", if (action == "bind_project") "session" else "workspace")
                put("notice", appContext.getString(R.string.project_memory_binding_notice))
            })
        }
        if (action == "projects") {
            val limit = integer(args, "limit", 20) ?: return missing()
            val offset = integer(args, "offset", 0) ?: return missing()
            require(limit in 1..100 && offset >= 0)
            val query = string(args, "query").orEmpty()
            require(query.length <= 512)
            val matching = candidates.filter { it.name.contains(query, true) || it.aliases.any { alias -> alias.contains(query, true) } }
            val selected = matching.drop(offset).take(limit)
            val associations = checked(capture) { journal.associations(context.sessionId, identity) }
            return ToolResult.Success(buildJsonObject {
                put("session_binding_present", associations.sessionBindingPresent?.let(::JsonPrimitive) ?: JsonNull)
                put("session_binding_state", associations.sessionBindingState)
                put("workspace_binding_state", associations.workspaceBindingState)
                put("session_project_id", associations.sessionProject?.id?.let(::JsonPrimitive) ?: JsonNull)
                put("workspace_binding_present", associations.workspaceBindingPresent)
                put("workspace_project_id", associations.workspaceProject?.id?.let(::JsonPrimitive) ?: JsonNull)
                put("total", matching.size); put("offset", offset)
                put("has_more", offset + selected.size < matching.size)
                put("next_offset", if (offset + selected.size < matching.size) JsonPrimitive(offset + selected.size) else JsonNull)
                put("projects", JsonArray(selected.map { project -> buildJsonObject {
                    put("id", project.id); put("name", project.name); put("root", project.identity.projectRoot)
                    put("aliases", JsonArray(project.aliases.map(::JsonPrimitive)))
                    put("autoProgressEnabled", project.autoProgressEnabled)
                } }))
            })
        }
        val entryId = string(args, "entry_id")
        val entry = entryId?.let { checked(capture) { journal.read(it) } }
        val turnOverride = context.projectMemoryTurnOverride?.forTurn(context.sessionId, context.inputMessageId)?.get()
        val associations = checked(capture) { journal.associations(context.sessionId, identity) }
        val projectId = string(args, "project_id") ?: entry?.projectId ?: when {
            turnOverride != null -> turnOverride.projectId
            associations.sessionBindingPresent == true -> associations.sessionProject?.id
            context.projectMemoryProjectId != null -> context.projectMemoryProjectId
            else -> candidates.singleOrNull()?.id
        }
        val project = candidates.firstOrNull { it.id == projectId }
            ?: return error(R.string.project_memory_select_project, "MEMORY_PROJECT_AMBIGUOUS")
        if (entry != null && entry.projectId != project.id) return error(R.string.project_memory_scope_mismatch, "MEMORY_SCOPE_MISMATCH")
        val limit = integer(args, "limit", 20) ?: return missing()
        val offset = integer(args, "offset", 0) ?: return missing()
        require(limit in 1..100 && offset >= 0)
        val result = when (action) {
            "recent" -> page(checked(capture) { journal.listRecent(project.id, limit, maxChars = 8192, offset = offset) }, offset,
                checked(capture) { journal.entryCount(project.id) })
            "journal_search", "search" -> {
                val query = string(args, "query")?.takeIf { it.isNotBlank() } ?: return missing()
                page(checked(capture) { journal.search(project.id, query, limit, 8192, offset) }, offset,
                    checked(capture) { journal.entryCount(project.id, query = query) })
            }
            "journal_list_archive", "list_archive" -> {
                val query = string(args, "query").orEmpty()
                page(checked(capture) { journal.listArchive(project.id, limit, offset, query, 8192) }, offset,
                    checked(capture) { journal.entryCount(project.id, true, query) })
            }
            "entry" -> entry?.let(::entryJson) ?: return error(R.string.project_memory_not_found, "MEMORY_NOT_FOUND")
            "append", "handoff" -> {
                val summary = string(args, "description") ?: return missing()
                val body = string(args, "content") ?: return missing()
                entryJson(checked(capture) { journal.append(project.id, summary, body,
                    kind = if (action == "handoff") "handoff" else "note", sourceSessionId = context.sessionId) })
            }
            "update_entry", "update", "revoke", "journal_archive", "archive" -> {
                val current = entry ?: return error(R.string.project_memory_not_found, "MEMORY_NOT_FOUND")
                val revision = integer(args, "revision", null) ?: return missing()
                entryJson(checked(capture) {
                    when (action) {
                        "revoke" -> journal.revoke(current.id, revision)
                        "journal_archive", "archive" -> journal.archive(current.id, revision)
                        else -> journal.edit(current.id, revision, string(args, "description") ?: return@checked null,
                            string(args, "content") ?: return@checked null)
                    }
                } ?: return missing())
            }
            else -> return error(R.string.project_memory_invalid_action, "MEMORY_INVALID_ACTION")
        }
        return ToolResult.Success(result)
    }

    private suspend fun <T> checked(capture: ProjectMemoryCapture, action: () -> T): T {
        capture.assertCurrent()
        val result = action()
        capture.assertCurrent()
        return result
    }

    private fun string(args: Map<String, JsonElement>, name: String): String? =
        (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(args: Map<String, JsonElement>, name: String, default: Int?): Int? =
        if (name !in args) default else (args[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun missing() = error(R.string.project_memory_invalid_argument, "MEMORY_INVALID_ARGUMENT")
    private fun error(resource: Int, code: String) = ToolResult.Error(appContext.getString(resource), code)

    private fun page(entries: List<ProjectMemoryEntry>, offset: Int, total: Int) = buildJsonObject {
        put("entries", JsonArray(entries.map(::entryJson))); put("offset", offset); put("total", total)
        put("has_more", offset + entries.size < total)
        put("next_offset", if (offset + entries.size < total) JsonPrimitive(offset + entries.size) else JsonNull)
        put("truncated", entries.any { it.truncated })
        put("notice", appContext.getString(R.string.project_memory_page_notice))
    }

    private fun entryJson(entry: ProjectMemoryEntry): JsonObject = buildJsonObject {
        put("id", entry.id); put("project_id", entry.projectId); put("revision", entry.revision)
        put("createdAt", entry.createdAt); put("updatedAt", entry.updatedAt); put("kind", entry.kind)
        put("status", entry.status); put("summary", entry.summary); put("body", entry.body)
        put("turn_id", entry.turnId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceSessionId", entry.sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("revoked", entry.revoked); put("userEdited", entry.userEdited); put("archived", entry.archived); put("truncated", entry.truncated)
        put("fullRead", buildJsonObject { put("action", "entry"); put("entry_id", entry.id) })
        put("notice", appContext.getString(R.string.project_memory_history_notice))
    }

    companion object {
        val READ_ACTIONS = setOf("projects", "recent", "journal_search", "search", "entry", "journal_capacity", "capacity", "journal_list_archive", "list_archive")
        val ACTIONS = READ_ACTIONS + setOf("register_project", "bind_project", "bind_workspace_project", "append", "handoff", "update_entry", "update", "revoke", "journal_archive", "archive")
        val PARAMETERS = mapOf(
            "project_id" to ToolParameter("project_id", ParameterType.STRING, "Registered project ID; empty string explicitly clears a binding.", false),
            "project_root" to ToolParameter("project_root", ParameterType.STRING, "Project directory within the frozen current workspace. SSH containment is lexical attribution, not a remote sandbox.", false),
            "aliases" to ToolParameter("aliases", ParameterType.ARRAY, "Project aliases; omitted preserves existing aliases, [] clears them.", false),
            "entry_id" to ToolParameter("entry_id", ParameterType.STRING, "Exact journal entry ID from recent/search/entry.", false),
            "revision" to ToolParameter("revision", ParameterType.INTEGER, "Exact current revision required for update/revoke/archive.", false),
            "query" to ToolParameter("query", ParameterType.STRING, "Literal search terms, at most 512 characters.", false),
            "limit" to ToolParameter("limit", ParameterType.INTEGER, "Page limit, 1..100; default 20.", false),
            "offset" to ToolParameter("offset", ParameterType.INTEGER, "Nonnegative page offset; default 0.", false)
        )
    }
}
