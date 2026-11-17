package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.preview.safeToolPreviewText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

// Identities provide names and task titles, never newer execution state or output.
enum class TaskOperationAction { CREATE, SEND, READ, STOP, DELETE, LIST, UNKNOWN }
enum class TaskOperationStatus {
    RUNNING, COMPLETED, QUEUED, DELIVERED, STOPPING, STOPPED, DELETED, FAILED,
    INTERRUPTED, AWAITING_CONFIRMATION, RECORDED, UNKNOWN,
}
data class TaskOperationIdentity(val name: String? = null, val task: String = "")
data class TaskOperationItem(
    val id: String? = null,
    val name: String? = null,
    val task: String = "",
    val instruction: String? = null,
    val status: TaskOperationStatus? = null,
    val output: String? = null,
    val warning: String? = null,
    val error: String? = null,
    val tier: String? = null,
    val model: String? = null,
)
data class TaskOperationPresentation(
    val action: TaskOperationAction,
    val items: List<TaskOperationItem>,
    val message: String? = null,
    val error: String? = null,
    val hasResult: Boolean = false,
    val partial: Boolean = false,
    val argumentsUnavailable: Boolean = false,
)

private const val MAX_INPUT = 2_097_152
private const val MAX_ITEMS = 256

fun taskOperationAction(argsJson: String?): TaskOperationAction =
    argsJson?.let(::parseTaskOperationObject)?.takeIf(::validTaskArguments)?.operationAction() ?: TaskOperationAction.UNKNOWN

fun projectTaskOperation(
    argsJson: String?,
    rawResult: String?,
    running: Boolean,
    identities: Map<String, TaskOperationIdentity> = emptyMap(),
): TaskOperationPresentation? {
    val parsedArgs = if (argsJson.isNullOrBlank()) null else parseTaskOperationObject(argsJson)?.takeIf(::validTaskArguments) ?: return null
    val args = parsedArgs?.takeUnless { it.isEmpty() && !rawResult.isNullOrBlank() }
    val result = if (rawResult.isNullOrBlank()) null else parseTaskOperationResult(rawResult) ?: return null
    val action = args?.operationAction() ?: result?.data?.let(::inferTaskAction) ?: return null
    val data = result?.data
    if (action == TaskOperationAction.LIST && result != null && !result.failed && data?.objects("subagents") == null) return null
    if (action !in setOf(TaskOperationAction.CREATE, TaskOperationAction.LIST) &&
        args?.nonBlank("id") != null && data?.nonBlank("id") != null && args.nonBlank("id") != data.nonBlank("id")) return null
    val items = when (action) {
        TaskOperationAction.CREATE -> projectCreation(args, result, running, identities)
        TaskOperationAction.LIST -> data?.objects("subagents")?.map {
            resultItem(it, identities, if (result?.failed == true) TaskOperationStatus.FAILED else TaskOperationStatus.UNKNOWN)
        }.orEmpty()
        else -> listOf(projectSingle(action, args, result, running, identities))
    }
    if (items.size > MAX_ITEMS) return null
    val warning = joinTaskText(result?.envelope?.text("warning"), data?.text("warning"))
    return TaskOperationPresentation(
        action = action,
        items = if (warning == null) items else if (items.isEmpty()) listOf(TaskOperationItem(warning = warning)) else
            items.mapIndexed { index, item -> if (index == 0) item.copy(warning = joinTaskText(warning, item.warning)) else item },
        message = joinTaskText(result?.envelope?.text("message"), data?.text("message")),
        error = joinTaskText(result?.envelope?.resultError(), data?.resultError()),
        hasResult = result != null,
        partial = result?.partial == true,
        argumentsUnavailable = args == null,
    )
}

private data class TaskResult(val envelope: JsonObject? = null, val data: JsonObject? = null, val failed: Boolean = false, val partial: Boolean = false)
private fun parseTaskOperationResult(raw: String): TaskResult? {
    val source = raw.withoutToolStatusPrefix()
    val legacy = source.startsWith("Success(data=") && source.endsWith(')')
    val value = parseTaskOperationObject(if (legacy) source.removePrefix("Success(data=").removeSuffix(")").removeSuffix(", images=[]") else source) ?: return null
    val status = value.text("status")?.lowercase(Locale.ROOT)
    val envelope = !legacy && ("data" in value || status in setOf("success", "partial", "error") &&
        listOf("id", "subagents", "lastOutput", "state", "description", "title", "agent", "prompt").none(value::containsKey))
    if (!envelope) return value.takeIf(::validTaskData)?.let { TaskResult(data = it, failed = it.failed(), partial = status == "partial") }
    if (!value.hasText("status", "message", "code", "warning") || !value.validError() || status !in setOf("success", "partial", "error")) return null
    val data = when (val element = value["data"]) {
        null, JsonNull -> null
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) parseTaskOperationObject(element.content) ?: return null else return null
        else -> return null
    }
    if (data != null && !validTaskData(data) || data == null && status != "error") return null
    return TaskResult(value, data, failed = status == "error", partial = status == "partial" || data?.text("status") == "partial")
}

private fun validTaskArguments(args: JsonObject): Boolean = args.hasText("action", "id", "description", "prompt", "agent", "tier", "message") &&
    args["action"] != JsonNull && (!args.containsKey("tasks") || (args["tasks"] as? JsonArray)?.let { tasks ->
        tasks.size <= MAX_ITEMS && tasks.all { it is JsonObject && it.hasText("id", "description", "prompt", "agent", "tier") }
    } == true)
private fun validTaskData(data: JsonObject): Boolean = validTaskItem(data) &&
    (!data.containsKey("subagents") || (data["subagents"] as? JsonArray)?.let { items ->
        items.size <= MAX_ITEMS && items.all { it is JsonObject && validTaskItem(it) }
    } == true)
private fun validTaskItem(item: JsonObject): Boolean = item.hasText(
    "id", "agent", "name", "description", "title", "prompt", "state", "status", "lastOutput", "output", "warning", "message", "code", "tier", "model",
) && item.validError()
private fun JsonObject.hasText(vararg fields: String): Boolean = fields.all {
    val value = get(it)
    value == null || value == JsonNull || value is JsonPrimitive && value.isString
}
private fun JsonObject.validError(): Boolean = when (val error = get("error")) {
    null, JsonNull -> true
    is JsonPrimitive -> error.isString
    is JsonObject -> error.hasText("message", "code")
    else -> false
}
private fun JsonObject.operationAction(): TaskOperationAction = when (text("action")?.trim()?.lowercase(Locale.ROOT)) {
    null -> if (containsKey("action")) TaskOperationAction.UNKNOWN else TaskOperationAction.CREATE
    "create" -> TaskOperationAction.CREATE
    "send" -> TaskOperationAction.SEND
    "read" -> TaskOperationAction.READ
    "stop" -> TaskOperationAction.STOP
    "del", "delete" -> TaskOperationAction.DELETE
    "list" -> TaskOperationAction.LIST
    else -> TaskOperationAction.UNKNOWN
}
private fun inferTaskAction(data: JsonObject): TaskOperationAction? {
    val id = data.nonBlank("id")
    if (id != null && "lastOutput" in data) return TaskOperationAction.READ
    when (data.text("state")?.lowercase(Locale.ROOT)) {
        "delivered" -> if (id != null) return TaskOperationAction.SEND
        "stopping" -> if (id != null) return TaskOperationAction.STOP
        "deleted" -> if (id != null) return TaskOperationAction.DELETE
    }
    if (data.objects("subagents") != null) {
        if ("runningCount" in data && "maxRunning" in data) return TaskOperationAction.LIST
        if (id != null && data.text("state") == "running") return TaskOperationAction.CREATE
    }
    if (id != null && data.text("state") == "running" && "tier" in data && "model" in data) return TaskOperationAction.CREATE
    return null
}

private fun projectCreation(args: JsonObject?, result: TaskResult?, running: Boolean, identities: Map<String, TaskOperationIdentity>): List<TaskOperationItem> {
    val results = result?.data?.let { it.objects("subagents") ?: listOf(it) }.orEmpty()
    val tasks = args?.objects("tasks")?.takeIf { it.isNotEmpty() } ?: listOfNotNull(args?.takeIf {
        results.isEmpty() || listOf("description", "prompt", "agent", "tier").any(it::containsKey)
    })
    if (results.isEmpty()) return tasks.map { argumentItem(it, pendingStatus(result, running)) }
    val matches = IntArray(results.size) { -1 }
    if (tasks.size == 1 && results.size == 1 && !argumentsConflict(tasks.single(), results.single())) matches[0] = 0
    else {
        val candidates = results.map { item -> tasks.indices.filter { taskMatchesResult(tasks[it], item) } }
        candidates.forEachIndexed { index, possible ->
            possible.singleOrNull()?.takeIf { candidate -> candidates.count { candidate in it } == 1 }?.let { matches[index] = it }
        }
    }
    // A reordered or partial batch must never inherit a different task's instruction by array position.
    val items = results.mapIndexed { index, item ->
        val task = tasks.getOrNull(matches[index])
        val projected = resultItem(item, identities, if (result?.failed == true) TaskOperationStatus.FAILED else TaskOperationStatus.UNKNOWN)
        projected.copy(name = projected.name ?: taskAgentName(task?.text("agent"), projected.id),
            task = task?.argumentTask()?.takeIf(String::isNotBlank) ?: projected.task,
            instruction = task?.text("prompt") ?: item.text("prompt"))
    }.toMutableList()
    tasks.forEachIndexed { index, task -> if (index !in matches) items += argumentItem(task, pendingStatus(result, running)) }
    return items
}
private fun argumentItem(task: JsonObject, status: TaskOperationStatus?): TaskOperationItem = TaskOperationItem(
    name = taskAgentName(task.text("agent")), task = task.argumentTask(), instruction = task.text("prompt"), status = status,
)
private fun JsonObject.argumentTask(): String = nonBlank("description") ?: text("prompt")?.lineSequence()?.firstOrNull().orEmpty()
private fun canonicalDescription(value: String): String = value.trim().replace(Regex("\\s+"), " ").take(30)
private fun taskMatchesResult(task: JsonObject, item: JsonObject): Boolean = !argumentsConflict(task, item) &&
    listOf("id", "prompt", "description", "agent").any { key ->
        val expected = task.nonBlank(key)
        val actual = item.nonBlank(key) ?: when (key) { "agent" -> item.nonBlank("name"); "description" -> item.nonBlank("title"); else -> null }
        expected != null && actual != null && (expected == actual || key == "description" && canonicalDescription(expected) == actual)
    }
private fun argumentsConflict(task: JsonObject, item: JsonObject): Boolean = listOf("id", "prompt", "description", "agent").any { key ->
    val expected = task.nonBlank(key)
    val actual = item.nonBlank(key) ?: when (key) { "agent" -> item.nonBlank("name"); "description" -> item.nonBlank("title"); else -> null }
    expected != null && actual != null && when (key) {
        "description" -> expected != actual && canonicalDescription(expected) != actual
        "agent" -> taskAgentName(expected) != null && taskAgentName(actual) != null && !expected.equals(actual, true)
        else -> expected != actual
    }
}
private fun projectSingle(action: TaskOperationAction, args: JsonObject?, result: TaskResult?, running: Boolean, identities: Map<String, TaskOperationIdentity>): TaskOperationItem {
    val data = result?.data
    val id = data?.nonBlank("id") ?: args?.nonBlank("id")
    val default = when {
        result?.failed == true -> TaskOperationStatus.FAILED
        data == null -> pendingStatus(result, running)
        action == TaskOperationAction.READ -> TaskOperationStatus.RECORDED
        else -> TaskOperationStatus.UNKNOWN
    }
    val item = resultItem(data ?: JsonObject(emptyMap()), identities, default, id)
    return item.copy(
        task = if (action == TaskOperationAction.SEND) identities[id]?.task?.let(::safeToolPreviewText)?.takeIf(String::isNotBlank) ?: item.task else item.task,
        instruction = if (action == TaskOperationAction.SEND) args?.text("message") else item.instruction,
    )
}
private fun resultItem(item: JsonObject, identities: Map<String, TaskOperationIdentity>, default: TaskOperationStatus?, fallbackId: String? = null): TaskOperationItem {
    val id = item.nonBlank("id") ?: fallbackId
    val identity = identities[id]
    val error = item.resultError()
    val state = item.text("state") ?: item.text("status")?.takeUnless { it in setOf("success", "partial") }
    return TaskOperationItem(
        id = id, name = taskAgentName(identity?.name, id) ?: taskAgentName(item.text("agent"), id) ?: taskAgentName(item.text("name"), id),
        task = item.nonBlank("description") ?: item.nonBlank("title") ?: identity?.task?.let(::safeToolPreviewText).orEmpty(),
        instruction = item.text("prompt"),
        status = when { error != null || item.failed() || default == TaskOperationStatus.FAILED -> TaskOperationStatus.FAILED; state != null -> snapshotStatus(state); else -> default },
        output = item.text("lastOutput") ?: item.text("output"), warning = item.nonBlank("warning"), error = error,
        tier = item.nonBlank("tier"), model = item.nonBlank("model"),
    )
}
private fun pendingStatus(result: TaskResult?, running: Boolean): TaskOperationStatus? = when {
    result?.failed == true -> TaskOperationStatus.FAILED
    result != null -> TaskOperationStatus.UNKNOWN
    running -> TaskOperationStatus.RUNNING
    else -> null
}
private fun snapshotStatus(value: String): TaskOperationStatus = when (value.trim().lowercase(Locale.ROOT)) {
    "running", "created" -> TaskOperationStatus.RUNNING
    "completed" -> TaskOperationStatus.COMPLETED
    "queued" -> TaskOperationStatus.QUEUED
    "delivered" -> TaskOperationStatus.DELIVERED
    "stopping" -> TaskOperationStatus.STOPPING
    "stopped" -> TaskOperationStatus.STOPPED
    "deleted" -> TaskOperationStatus.DELETED
    "failed", "error" -> TaskOperationStatus.FAILED
    "interrupted" -> TaskOperationStatus.INTERRUPTED
    "awaiting_confirmation" -> TaskOperationStatus.AWAITING_CONFIRMATION
    "recorded" -> TaskOperationStatus.RECORDED
    else -> TaskOperationStatus.UNKNOWN
}
private fun JsonObject.failed(): Boolean = text("status")?.lowercase(Locale.ROOT) in setOf("error", "failed") ||
    text("state")?.lowercase(Locale.ROOT) in setOf("error", "failed") || errorText() != null
private fun JsonObject.errorText(): String? = when (val error = get("error")) {
    is JsonObject -> joinTaskText(error.nonBlank("code"), error.nonBlank("message")) ?: error.toString().takeUnless { error.isEmpty() }?.let(::safeToolPreviewText)
    is JsonPrimitive -> error.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)?.let(::safeToolPreviewText)
    else -> null
}
private fun JsonObject.resultError(): String? = joinTaskText(errorText(), nonBlank("code"), nonBlank("message")?.takeIf { failed() || nonBlank("code") != null })
private fun taskAgentName(value: String?, id: String? = null): String? = value?.let(::safeToolPreviewText)?.trim()?.takeIf {
    it.isNotEmpty() && it.lowercase(Locale.ROOT) !in setOf("subagent", "default", "inherit") && it != id &&
        !Regex("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it)
}
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::safeToolPreviewText)
private fun JsonObject.nonBlank(key: String): String? = text(key)?.takeIf(String::isNotBlank)
private fun JsonObject.objects(key: String): List<JsonObject>? = (get(key) as? JsonArray)?.map { it as JsonObject }
private fun joinTaskText(vararg values: String?): String? = values.filterNotNull().filter(String::isNotBlank).distinct().joinToString("\n").takeIf(String::isNotEmpty)
private fun parseTaskOperationObject(raw: String): JsonObject? {
    if (raw.length > MAX_INPUT || raw.toByteArray(Charsets.UTF_8).size > MAX_INPUT) return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) when { escaped -> escaped = false; char == '\\' -> escaped = true; char == '"' -> quoted = false }
        else when (char) { '"' -> quoted = true; '{', '[' -> if (++depth > 64) return null; '}', ']' -> depth-- }
    }
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
