package com.aicode.feature.agent.domain.memory

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class ProjectProgressCaptureState(val status: String, val projectId: String? = null, val reason: String? = null)

class ProjectProgressCollector(
    private val journal: ProjectMemoryJournal,
    private val frozen: ProjectMemoryIdentity,
    private val turnId: String,
    private val enabled: Boolean,
    private val declaredProjectId: String? = null,
    private val allowUnboundDiscovery: Boolean = true,
    private val resolveProjectPath: ((String) -> String?)? = null,
    private val sourceSessionId: String? = null,
    val channelGeneration: String? = null,
    private val turnOverride: ProjectMemoryTurnOverride? = null
) {
    private val facts = linkedMapOf<String, LinkedHashSet<String>>()
    private val pendingTools = linkedMapOf<String, String>()
    private val finishedTools = linkedSetOf<String>()
    private val recordedCalls = linkedSetOf<String>()
    private val attempts = linkedSetOf<String>()
    private val pendingTabs = linkedMapOf<String, String>()
    private var todos = emptyList<String>()
    private var hasTodoSnapshot = false
    private var todoItemCount = 0
    private var unmatchedStarts = 0
    private var lastStatus: String? = null
    private var interrupted = false
    private var blocked = !enabled
    private var lastCheckpoint: String? = null
    private var eventSequence = 0L
    private val pathResolver = resolveProjectPath ?: ProjectMemoryPathResolver().freeze(frozen)
    private var capture = ProjectProgressCaptureState(if (enabled) "unbound" else "disabled", declaredProjectId,
        if (enabled) "no_activity" else "collection_disabled")
    val executionMode: String get() = frozen.mode.lowercase(Locale.ROOT)

    @Synchronized fun captureState(): ProjectProgressCaptureState = capture

    @Synchronized fun recordStarted(callId: String, tool: String) {
        if (blocked || tool !in EXECUTING_TOOLS || !SAFE_CALL.matches(callId) || callId in finishedTools || callId in pendingTools) return
        val project = selectedProject() ?: return
        check(pendingTools.size < 32) { "Progress pending tool capacity reached" }
        pendingTools[callId] = tool
        facts.getOrPut(project.id) { linkedSetOf() }
        capture = ProjectProgressCaptureState("recording", project.id)
        checkpoint()
    }

    @Synchronized fun recordFinished(callId: String, isError: Boolean) {
        if (blocked) return
        val tool = pendingTools.remove(callId) ?: return
        if (finishedTools.size < 512) finishedTools.add(callId)
        if (attempts.size < 20) attempts.add("Tool attempt: $tool; call: $callId; ${if (isError) "reported_failure" else "reported_success"}; effect and build/test results unverified")
        checkpoint()
    }

    @Synchronized fun recordOutcome(isError: Boolean, result: String) {
        if (blocked || !isError) return
        val code = (parseObject(result)?.get("code") as? JsonPrimitive)?.content
        if (code in setOf("USER_REJECTED", "CANCELLED", "CANCELED", "TOOL_CANCELLED")) {
            interrupted = true
            capture = ProjectProgressCaptureState("needs_verification", declaredProjectId, "tool_interrupted")
            checkpoint()
        }
    }

    @Synchronized fun record(tool: String, args: String?, isError: Boolean, callId: String? = null) {
        if (blocked || (callId != null && callId in recordedCalls)) return
        val input = parseObject(args) ?: return
        if (callId != null) {
            require(SAFE_CALL.matches(callId))
            check(recordedCalls.size < 512) { "Progress event capacity reached" }
            recordedCalls.add(callId)
        }
        when (tool) {
            "todo" -> {
                if (isError) return
                val items = input["items"] as? JsonArray ?: return
                todos = items.take(12).mapNotNull { value ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    val subject = safeText((item["subject"] as? JsonPrimitive)?.content.orEmpty()) ?: return@mapNotNull null
                    val status = (item["status"] as? JsonPrimitive)?.content?.takeIf { it in TODO_STATUSES } ?: "pending"
                    "$status: $subject"
                }
                hasTodoSnapshot = true
                todoItemCount = ((input["itemCount"] as? JsonPrimitive)?.intOrNull ?: items.size).coerceIn(items.size, 100000)
            }
            "terminal" -> {
                if (isError || (input["action"] as? JsonPrimitive)?.content != "start") return
                unmatchedStarts = (unmatchedStarts + 1).coerceAtMost(128)
            }
            "writeFile", "editFile" -> {
                val project = selectedProject() ?: return
                val raw = (input["path"] as? JsonPrimitive)?.content ?: return
                val path = try { pathResolver(raw) } catch (_: java.io.IOException) { null }
                if (path == null || !ProjectMemoryIdentityResolver.containsRoot(project.identity.projectRoot, path)) {
                    capture = ProjectProgressCaptureState("path_unmatched", project.id, "outside_project")
                    return
                }
                val relative = path.removePrefix(project.identity.projectRoot).trimStart('/')
                if (!safePath(relative)) {
                    capture = ProjectProgressCaptureState("path_unmatched", project.id, "path_filtered")
                    return
                }
                val bucket = facts.getOrPut(project.id) { linkedSetOf() }
                if (bucket.size < 40) bucket.add("$tool: ${if (isError) "reported_failure" else "reported_success"}: $relative")
                capture = ProjectProgressCaptureState("recording", project.id)
            }
            else -> return
        }
        checkpoint()
    }

    @Synchronized fun backgroundStarted(tabId: String): Boolean {
        if (blocked || !SAFE_TAB.matches(tabId)) return false
        if (tabId in pendingTabs) return true
        val project = selectedProject() ?: return false
        if (currentProjectId() == null || project.identity.projectRoot != frozen.projectRoot ||
            (executionMode == "remote_ssh" && channelGeneration == null)
        ) {
            capture = ProjectProgressCaptureState("needs_verification", project.id, "background_unverified")
            return false
        }
        check(pendingTabs.size < 128) { "Progress pending task capacity reached" }
        facts.getOrPut(project.id) { linkedSetOf() }
        pendingTabs[tabId] = project.id
        unmatchedStarts = (unmatchedStarts - 1).coerceAtLeast(0)
        capture = ProjectProgressCaptureState("needs_verification", project.id, "background_pending")
        checkpoint()
        return true
    }

    @Synchronized fun backgroundFinished(tabId: String, exitCode: Int) {
        if (blocked) return
        val projectId = pendingTabs.remove(tabId) ?: return
        val bucket = facts.getOrPut(projectId) { linkedSetOf() }
        if (bucket.size < 40) bucket.add("terminal: exit_code=$exitCode (process exit only; build/test unverified)")
        checkpoint()
        lastStatus?.let { save(it) }
    }

    @Synchronized fun save(status: String) {
        if (blocked) return
        require(status in setOf("completed", "failed", "interrupted", "background_pending"))
        lastStatus = status
        ensureActivityProject()
        var saved: String? = null
        var uncertain = false
        facts.forEach { (projectId, events) ->
            val finalStatus = when {
                status == "completed" && (interrupted || pendingTools.isNotEmpty()) -> "interrupted"
                status == "completed" && (unmatchedStarts > 0 || pendingTabs.isNotEmpty()) -> "background_pending"
                else -> status
            }
            eventSequence++
            val entry = journal.upsertTurn(projectId, turnId, "Project activity · $finalStatus · unverified",
                body(events, finalStatus), finalStatus, eventSequence, sourceSessionId)
            if (entry != null) {
                saved = projectId
                uncertain = uncertain || finalStatus != "completed"
            }
        }
        if (unmatchedStarts == 0 && pendingTabs.isEmpty()) journal.clearProgressCheckpoint(turnId) else checkpoint()
        capture = when {
            saved != null -> ProjectProgressCaptureState(if (uncertain) "needs_verification" else "saved", saved,
                if (uncertain) "outcome_unverified" else null)
            facts.isNotEmpty() -> ProjectProgressCaptureState("disabled", facts.keys.singleOrNull(), "project_disabled")
            else -> capture
        }
    }

    @Synchronized fun checkpoint() {
        if (blocked) return
        ensureActivityProject()
        val enabledIds = journal.listProjects().filter { it.autoProgressEnabled }.map { it.id }.toSet()
        val drafts = facts.filterKeys { it in enabledIds }.map { (id, events) -> ProjectProgressDraft(id, body(events, "running")) }
        if (drafts.isEmpty()) return
        val signature = drafts.toString() + pendingTabs.keys.toString()
        if (signature == lastCheckpoint) return
        eventSequence++
        journal.checkpointProgress(turnId, frozen, drafts, pendingTabs.keys.toList(), eventSequence, sourceSessionId)
        lastCheckpoint = signature
    }

    @Synchronized fun block() {
        if (blocked) return
        blocked = true
        journal.clearProgressCheckpoint(turnId)
        capture = ProjectProgressCaptureState("disabled", declaredProjectId, "collector_stopped")
    }

    private fun currentProjectId(): String? {
        val selection = turnOverride?.get()
        return if (selection != null) selection.projectId else declaredProjectId
    }

    private fun selectedProject(): ProjectMemoryProject? {
        val selection = turnOverride?.get()
        val projectId = if (selection != null) selection.projectId else declaredProjectId
        if (projectId == null && (selection != null || !allowUnboundDiscovery || executionMode == "remote_ssh")) return null
        val candidates = ProjectMemoryIdentityResolver.matchingCandidates(frozen, journal.listProjects()).filter {
            if (projectId != null) it.id == projectId else it.identity.projectRoot == frozen.projectRoot
        }
        val project = candidates.singleOrNull()
        if (project == null) capture = ProjectProgressCaptureState("unbound", projectId, "binding_mismatch")
        return project?.takeIf { it.autoProgressEnabled }
    }

    private fun ensureActivityProject() {
        if (facts.isEmpty() && (hasTodoSnapshot || unmatchedStarts > 0 || pendingTools.isNotEmpty() || attempts.isNotEmpty())) {
            selectedProject()?.let { facts[it.id] = linkedSetOf() }
        }
    }

    private fun body(events: Set<String>, status: String): String = buildString {
        appendLine("Turn: $status (not project completion)")
        appendLine("Verification: unverified; reported tool success is not proof of build/test success.")
        if (status == "running" || interrupted || pendingTools.isNotEmpty()) appendLine("Outcome: unknown / needs_verification.")
        events.forEach { appendLine(it) }
        attempts.forEach { appendLine(it) }
        pendingTools.forEach { (call, tool) -> appendLine("Tool attempt: $tool; call: $call; pending / unknown / needs_verification") }
        pendingTabs.keys.forEach { appendLine("Task tab: $it; turn: $turnId; mode: $executionMode; needs_verification") }
        if (unmatchedStarts > 0) appendLine("Uncorrelated terminal starts: $unmatchedStarts; ownership/result unknown")
        if (hasTodoSnapshot) {
            appendLine("Todo snapshot at capture time (historical report, not the current todo list):")
            if (todoItemCount == 0) appendLine("Todo snapshot explicitly cleared; no task items at capture time.")
            todos.forEach { appendLine(it) }
            if (todoItemCount > todos.size) appendLine("Some task items omitted by filtering or limits.")
        }
    }.take(8000)

    companion object {
        private val EXECUTING_TOOLS = setOf("Bash", "terminal", "writeFile", "editFile")
        private val SAFE_CALL = Regex("[A-Za-z0-9_-]{1,160}")
        private val SAFE_TAB = Regex("[A-Za-z0-9_-]{1,100}")
        private val TODO_STATUSES = setOf("pending", "in_progress", "completed")

        fun projectArguments(tool: String, args: Map<String, kotlinx.serialization.json.JsonElement>): String? = when (tool) {
            "writeFile", "editFile" -> (args["path"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 4096 }?.let {
                buildJsonObject { put("path", it.content) }.toString()
            }
            "terminal" -> buildJsonObject { put("action", (args["action"] as? JsonPrimitive)?.content.orEmpty().take(24)) }.toString()
            "todo" -> {
                val items = args["items"] as? JsonArray
                items?.let { buildJsonObject {
                    put("itemCount", it.size)
                    put("items", JsonArray(it.take(12).mapNotNull { value ->
                        val item = value as? JsonObject ?: return@mapNotNull null
                        val subject = safeText((item["subject"] as? JsonPrimitive)?.content.orEmpty()) ?: return@mapNotNull null
                        buildJsonObject {
                            put("subject", subject)
                            put("status", (item["status"] as? JsonPrimitive)?.content?.takeIf { status -> status in TODO_STATUSES } ?: "pending")
                        }
                    }))
                }.toString() }
            }
            else -> null
        }

        fun projectArguments(tool: String, args: String?): String? = parseObject(args)?.let { projectArguments(tool, it) }
        fun projectSafeProjection(tool: String, args: Map<String, kotlinx.serialization.json.JsonElement>): String? = projectArguments(tool, args)
        fun projectSafeProjection(tool: String, args: String?): String? = projectArguments(tool, args)
        fun safeText(text: String): String? = ProjectMemoryContentPolicy.safeText(text)
        fun safePath(path: String): Boolean = ProjectMemoryContentPolicy.safePath(path)
        private fun parseObject(text: String?): JsonObject? =
            if (text == null || text.length > 65536) null else runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}
