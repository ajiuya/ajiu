package com.aicode.feature.agent.presentation.preview

import androidx.compose.ui.text.AnnotatedString
import com.aicode.feature.agent.presentation.component.EditHunk
import com.aicode.feature.agent.presentation.component.parseEditDiff
import com.aicode.feature.agent.presentation.component.withoutToolStatusPrefix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class ToolPreviewField(val name: String, val value: String)
internal data class ToolPreviewDisplay(
    val toolName: String,
    val stage: ToolPreviewStage,
    val parameters: List<ToolPreviewField> = emptyList(),
    val command: String = "",
    val result: String = "",
    val diff: ToolPreviewDiff = ToolPreviewDiff(),
    val taskArgs: String = "{}",
    val taskResult: String = "",
    val bodyHidden: Boolean = false,
    val truncated: Boolean = false,
    val resultHunks: List<EditHunk> = emptyList(),
) {
    val parameterText: String get() = parameters.joinToString("\n") { "${it.name}: ${it.value}" }
    val body: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (result.isNotBlank()) return@lazy result
        val hasDiff = diff.removed.isNotEmpty() || diff.added.isNotEmpty()
        val fields = parameters.filterNot {
            command.isNotEmpty() && it.name in setOf("command", "cmd", "script", "input", "key") ||
                hasDiff && it.name in setOf("edits", "old_string", "new_string")
        }.joinToString("\n") { "${it.name}: ${it.value}" }
        val changes = listOf(
            diff.removed.takeIf(String::isNotEmpty)?.lineSequence()?.joinToString("\n") { "- $it" }.orEmpty(),
            diff.added.takeIf(String::isNotEmpty)?.lineSequence()?.joinToString("\n") { "+ $it" }.orEmpty(),
        ).filter(String::isNotBlank).joinToString("\n")
        listOf(fields, changes).filter(String::isNotBlank).joinToString("\n\n").take(PREVIEW_TEXT_LIMIT * 2).safeUnicodeEnd()
    }
    val copyText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { toolPreviewCopyText(command, body) }
    val terminalCommand by lazy(LazyThreadSafetyMode.PUBLICATION) { toolDetailTextBlocks(AnnotatedString(if (command.isEmpty()) "" else "$ $command")) }
    val terminalBody by lazy(LazyThreadSafetyMode.PUBLICATION) { toolDetailTextBlocks(toolPreviewDiffBody(body, this)) }
}

internal fun toolPreviewCopyText(command: String, body: String): String =
    listOf(if (command.isNotEmpty()) "$ $command" else "", body).filter(String::isNotBlank).joinToString("\n\n")

internal fun toolDetailBodyOf(display: ToolPreviewDisplay, notExecuted: String, sensitiveBody: String, noOutput: String): String {
    val notices = listOf(
        notExecuted.takeIf { display.stage == ToolPreviewStage.PREPARING },
        sensitiveBody.takeIf { display.bodyHidden },
    ).filterNotNull()
    return (notices + display.body).filter(String::isNotBlank).joinToString("\n\n")
        .ifBlank { if (display.command.isEmpty()) noOutput else "" }
}

internal fun previewParameterFields(args: JsonObject): List<ToolPreviewField> = args.entries.asSequence()
    .filter { it.key !in setOf("tool_title", "content", "edits", "old_string", "new_string") }
    .take(24).map { (key, value) -> ToolPreviewField(key, previewReadableValue(value)) }.toList()

private fun previewReadableValue(value: JsonElement, depth: Int = 0): String = when {
    depth > 6 -> "…"
    value is JsonPrimitive -> value.contentOrNull.orEmpty()
    value is JsonArray -> value.take(32).mapIndexed { index, element -> "${index + 1}. ${previewReadableValue(element, depth + 1)}" }.joinToString("\n")
    value is JsonObject -> value.entries.take(32).joinToString("\n") { (key, element) -> "$key: ${previewReadableValue(element, depth + 1)}" }
    else -> ""
}.take(PREVIEW_TEXT_LIMIT).safeUnicodeEnd()

internal fun buildToolPreviewDisplay(target: ToolPreviewTarget, safety: ToolPreviewSafety = ToolPreviewSafety(target.name, target.arguments)): ToolPreviewDisplay {
    val draft = safety.fileDraft
    if (target.stage == ToolPreviewStage.PREPARING && draft != null) {
        return ToolPreviewDisplay(
            toolName = target.name, stage = target.stage,
            parameters = listOfNotNull(draft.path?.let { ToolPreviewField("path", it) }),
            diff = if (safety.bodyHidden) ToolPreviewDiff() else draft.diff,
            bodyHidden = safety.bodyHidden, truncated = draft.truncated,
        )
    }
    val hidden = safety.bodyHidden
    val args = safety.safeArgs
    val raw = target.content.withoutToolStatusPrefix()
    val safeResult = if (hidden || target.stage == ToolPreviewStage.PREPARING) "" else
        if (target.kind == ToolPreviewKind.COMMAND) safeToolPreviewTail(raw) else safeToolPreviewText(raw)
    val hunks = if (!hidden && target.stage != ToolPreviewStage.FAILED && target.kind == ToolPreviewKind.FILE) {
        runCatching { parseEditDiff(safeResult)?.hunks.orEmpty() }.getOrDefault(emptyList())
    } else emptyList()
    val result = if (hunks.isNotEmpty()) hunks.joinToString("\n\n") { "@@ ${it.startLine} @@\n${it.diff}" }
        else safeToolPreviewText(normalizeToolOutput(safeResult))
    val params = previewParameterFields(args).toMutableList()
    if (!hidden && target.name.equals("memory", true)) {
        (args["content"] as? JsonPrimitive)?.contentOrNull?.let { params += ToolPreviewField("content", it) }
        args["edits"]?.let { params += ToolPreviewField("edits", previewReadableValue(it)) }
    }
    if (!hidden && target.name.equals("editFile", true) && target.stage != ToolPreviewStage.FAILED) {
        args["edits"]?.let { params += ToolPreviewField("edits", previewReadableValue(it)) }
    }
    val commandFields = listOf("command", "cmd", "script") + if (target.name.equals("terminal", true)) listOf("input", "key") else emptyList()
    val diff = if (hidden || result.isNotBlank() || target.stage == ToolPreviewStage.FAILED) ToolPreviewDiff() else draft?.diff ?: ToolPreviewDiff()
    return ToolPreviewDisplay(
        toolName = safeToolPreviewText(target.name).take(160), stage = target.stage, parameters = params,
        command = commandFields.firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        result = result, diff = diff, taskArgs = args.toString(), taskResult = safeResult, bodyHidden = hidden,
        truncated = raw.length > PREVIEW_ARGUMENT_LIMIT || raw.length > PREVIEW_TEXT_LIMIT || target.arguments.length > PREVIEW_ARGUMENT_LIMIT || draft?.truncated == true,
        resultHunks = hunks,
    )
}

internal fun previewTailLines(text: String, count: Int, maxChars: Int = 2_048): String {
    if (count <= 0) return ""
    val tail = text.takeLast(maxChars).safeUnicodeStart().trimEnd('\n', '\r')
    var breaks = 0
    var start = 0
    for (i in tail.indices.reversed()) if (tail[i] == '\n' && ++breaks == count) { start = i + 1; break }
    return tail.substring(start)
}
internal fun String.safeUnicodeStart(): String = if (firstOrNull()?.isLowSurrogate() == true) drop(1) else this

internal fun explicitBrowserTabId(target: ToolPreviewTarget): String? = ToolPreviewSafety(target.name, target.arguments).browserTabId
internal fun browserPreviewTabId(target: ToolPreviewTarget): String? =
    if (target.kind == ToolPreviewKind.BROWSER && target.stage == ToolPreviewStage.RUNNING && wantsBrowserFrames(target.arguments)) explicitBrowserTabId(target) else null

internal fun browserScreenshotPath(target: ToolPreviewTarget): String? {
    if (!target.name.equals("browser", true) || target.active || target.stage == ToolPreviewStage.FAILED || !producesBrowserScreenshot(target.arguments)) return null
    var result = parsePreviewObject(target.content.withoutToolStatusPrefix()) ?: return null
    if ((result["status"] as? JsonPrimitive)?.contentOrNull == "error") return null
    repeat(2) {
        val data = result["data"]
        val nested = if (data is JsonObject) data else (data as? JsonPrimitive)?.contentOrNull?.let(::parsePreviewObject)
        if (nested != null) result = nested
    }
    val status = (result["status"] as? JsonPrimitive)?.contentOrNull
    if (status != null && status !in setOf("success", "captured")) return null
    return (result["filePath"] as? JsonPrimitive)?.contentOrNull?.takeIf(::isLocalPreviewImagePath)
}

internal fun normalizeToolOutput(raw: String): String {
    if (raw.isBlank()) return raw
    val parsed = parsePreviewObject(raw.trim()) ?: return unescapePreviewOutput(raw)
    val status = (parsed["status"] as? JsonPrimitive)?.contentOrNull ?: return unescapePreviewOutput(raw)
    val data = parsed["data"]
    val readable = when (data) {
        is JsonPrimitive -> data.contentOrNull.orEmpty().let { nested ->
            if (parsePreviewObject(nested)?.containsKey("status") == true) normalizeToolOutput(nested) else nested
        }
        is JsonObject -> if (data["status"] != null) normalizeToolOutput(data.toString()) else {
            listOf("output", "stdout", "content", "text", "result", "data").firstNotNullOfOrNull {
                (data[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            } ?: previewReadableValue(data)
        }
        null -> if (status == "error") "" else raw
        else -> previewReadableValue(data)
    }
    val notices = listOf("message", "warning", "code").mapNotNull { (parsed[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    return (listOf(unescapePreviewOutput(readable)) + notices).filter(String::isNotBlank).distinct().joinToString("\n\n")
}

private fun unescapePreviewOutput(text: String): String {
    if ('\n' in text || !text.contains('\\')) return text
    val encoded = "\"" + text.replace("\"", "\\\"") + "\""
    return runCatching { (Json.parseToJsonElement(encoded) as JsonPrimitive).content }.getOrDefault(text)
}
