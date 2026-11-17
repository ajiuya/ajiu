package com.aicode.feature.agent.presentation.preview

import java.util.Locale

internal data class ToolPreviewProfile(val family: String, val fields: Set<String>)
private fun profile(family: String, vararg fields: String) = ToolPreviewProfile(family, fields.toSet())

internal val toolPreviewProfiles: Map<String, ToolPreviewProfile> = linkedMapOf(
    "readFile" to profile("files", "path", "file", "filePath", "start_line", "end_line"),
    "sendFile" to profile("files", "paths", "names", "path", "file", "filePath", "caption"),
    "viewImage" to profile("images", "images", "id", "prompt", "detail"),
    "writeFile" to profile("edits", "path", "file", "filePath", "content", "overwrite"),
    "editFile" to profile("edits", "path", "file", "filePath", "edits", "old_string", "new_string", "replace_all"),
    "generateImage" to profile("images", "prompt", "size", "n", "quality", "background", "moderation", "style", "output_format", "output_path"),
    "Bash" to profile("commands", "command", "cmd", "script", "timeout", "elevate"),
    "Shizuku" to profile("commands", "command", "cmd", "script", "timeout"),
    "Root" to profile("commands", "command", "cmd", "script", "timeout", "elevate"),
    "terminal" to profile("commands", "action", "command", "title", "tab_id", "tab_ids", "input", "key", "submit", "filter", "notify", "elevate"),
    "list" to profile("files", "args"),
    "search" to profile("files", "args", "query", "path"),
    "history" to profile("memory", "action", "query", "limit", "cursor", "message_id", "tool_call_id", "body_offset", "body_limit", "expected_version"),
    "loadSkill" to profile("skills", "skill_name"),
    "askUserQuestion" to profile("questions", "questions", "question", "header", "options", "label", "description", "multiSelect"),
    "manageMcp" to profile("mcp", "action", "server_name", "command", "args", "url", "scope"),
    "websearch" to profile("web", "query", "queries"),
    "webfetch" to profile("web", "url", "format"),
    "media" to profile("media", "action", "mode", "src", "title", "items", "name", "url", "kind", "api", "query", "root", "referer"),
    "mediasearch" to profile("media", "query", "queries", "sources", "limit", "title"),
    "mediacatalog" to profile("media", "api", "query", "episodes", "name", "title", "url", "kind", "referer"),
    "videosearch" to profile("media", "action", "query", "sources", "source", "id", "page", "limit", "title"),
    "goalStatus" to profile("goal", "action", "reason"),
    "todo" to profile("todo", "items", "subject", "description", "id", "content", "status", "priority"),
    "memory" to profile("memory", "action", "name", "scope", "description", "content", "edits", "old_string", "new_string", "replace_all", "query", "project_root", "project_id", "entry_id", "revision", "aliases", "limit", "offset", "body_offset", "body_limit", "expected_version", "replace_oversize"),
    "task" to profile("tasks", "action", "id", "description", "prompt", "agent", "tier", "tasks", "message"),
    "messageParent" to profile("tasks", "message"),
    "browser" to profile("browser", "action", "url", "tabId", "tab_id", "selector", "ref", "text", "value", "script", "key", "direction", "path", "timeout", "waitUntil", "x", "y", "accept", "level", "clear", "condition", "maxDepth"),
    "virtualDisplay" to profile("device", "action", "displayId", "include_screenshot", "packageName", "uri", "observation_id", "node_id", "x", "y", "x1", "y1", "x2", "y2", "durationMs", "direction", "text", "key"),
    "ffmpeg" to profile("media", "action", "input", "output", "customArgs", "startTime", "duration", "crf", "preset", "width", "timeout"),
    "scheduleTask" to profile("schedule", "action", "taskId", "label", "prompt", "hour", "minute", "repeat", "days", "afterMinutes", "intervalMinutes", "maxRuns", "afterTask", "startDate", "endDate", "targetSession", "targetSessionId", "targetMessageId", "providerId", "modelId", "reasoningEffort", "prefillToolCall", "enabled", "confirmRunNow"),
)
private val unknownProfile = profile("other", "action", "path", "file", "filePath", "query", "command", "cmd", "script", "url", "tab_id", "tabId", "selector", "filter", "direction", "packageName")
internal fun toolPreviewProfileOf(name: String): ToolPreviewProfile =
    toolPreviewProfiles.entries.firstOrNull { it.key.equals(name.trim(), true) }?.value ?: unknownProfile

private val browserFrameActions = setOf(
    "navigate", "newtab", "click", "fill", "select", "hover", "press", "evaluate", "wait",
    "back", "forward", "goback", "goforward", "reload", "scroll", "selecttab",
)
internal fun wantsBrowserFrames(arguments: String): Boolean {
    val action = previewTopLevelString(arguments, "action")?.trim()?.lowercase(Locale.ROOT)
    return action.isNullOrEmpty() || action in browserFrameActions
}
internal fun producesBrowserScreenshot(arguments: String): Boolean =
    previewTopLevelString(arguments, "action").equals("screenshot", true)
