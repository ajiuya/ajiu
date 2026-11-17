package com.aicode.feature.agent.presentation.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.aicode.feature.agent.presentation.component.taskOperationAction
import com.aicode.feature.agent.presentation.component.taskOperationActionResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val MASK = "•••"
private const val PREVIEW_FIELD_LIMIT = 8_192
private val sensitivePath = Regex("""(?i)(^|[/\\])(?:\.env(?:\.[^/\\]*)?|[^/\\]*(?:credential|keystore|secret)[^/\\]*|id_rsa|id_ed25519|[^/\\]*\.(?:pem|p12|pfx|jks|key))($|[/\\])""")
private val privateSections = Regex("""(?is)<(?:think|thinking|reasoning|analysis)>.*?(?:</(?:think|thinking|reasoning|analysis)>|$)""")
private val privateFields = Regex("""(?im)(["']?(?:reasoning(?:_content)?|thinking|analysis|thoughts|chain_of_thought)["']?\s*[:=])[^\n]*""")
private val privateJsonField = Regex("""(?i)["'](?:reasoning(?:_content)?|thinking|analysis|thoughts|chain_of_thought)["']\s*:""")
private val secretFields = Regex("""(?i)(["']?(?:[\w.-]*(?:password|passwd|secret|api[_-]?key|access[_-]?key|token|authorization|cookie|credential|private[_-]?key)[\w.-]*)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*(?:"|$)|'(?:\\.|[^'\\])*(?:'|$)|[^\s,;}&]+)""")
private val secretSwitch = Regex("""(?i)((?:--?)(?:password|passwd|secret|api[-_]?key|token|authorization|cookie|credential|private[-_]?key)(?:\s+|=))(?:"[^"\n]*(?:"|$)|'[^'\n]*(?:'|$)|[^\s]+)""")
private val bearer = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9+/=_~.-]+""")
private val secretHeaders = Regex("""(?im)((?:authorization|proxy-authorization|x-api-key|api-key|(?:set-)?cookie)\s*:\s*)[^\r\n]+""")
private val headerSwitch = Regex("""(?i)((?:-H|--header)\s+)(?:"[^"\n]*(?:"|$)|'[^'\n]*(?:'|$)|[^\s]+)""")
private val urlUserInfo = Regex("""(https?://)[^/\s@]+@""")
private val secretQuery = Regex("""(?i)([?&](?:key|token|api_key|access_token|password|secret|signature|sig)=)[^&#\s]+""")
private val privateKey = Regex("""(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)""")
private val privateMapField = Regex("""(?im)["']?(?:headers|environment|env|auth)["']?\s*[:=]\s*""")
private val hiddenKeys = setOf("reasoning", "reasoningcontent", "thinking", "analysis", "thoughts", "chainofthought", "thoughtsignature", "reasoningsignature")
private val bodyKeys = setOf("content", "body", "text", "output", "stdout", "stderr", "result", "data", "edits", "oldstring", "newstring", "value", "snippet", "description", "script", "input")
private val pathKeys = setOf("path", "file", "filepath", "filename", "outputpath", "output", "input")
private val pathListKeys = setOf("images", "paths")

private fun JsonObject.hasSensitivePreviewPaths(): Boolean = any { (key, value) ->
    when (normalizedKey(key)) {
        in pathKeys -> (value as? JsonPrimitive)?.contentOrNull?.let(::isSensitivePreviewPath) == true
        in pathListKeys -> {
            val paths = value as? JsonArray
            paths == null || paths.size > 32 || paths.any { path ->
                (path as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::isSensitivePreviewPath) != false
            }
        }
        else -> false
    }
}

private fun normalizedKey(key: String) = key.lowercase().filter { it.isLetterOrDigit() }
private fun isHiddenKey(key: String): Boolean = normalizedKey(key).let {
    it in hiddenKeys || it.startsWith("reasoning") && it != "reasoningeffort" || it.startsWith("thinking")
}
private fun isSecretKey(key: String): Boolean = normalizedKey(key).let { name ->
    listOf("password", "passwd", "secret", "apikey", "accesskey", "token", "authorization", "cookie", "credential", "privatekey").any { it in name } ||
        name in setOf("env", "environment", "headers", "auth")
}

internal fun isSensitivePreviewPath(path: String): Boolean = sensitivePath.containsMatchIn(path.substringBefore('?').substringBefore('#'))
internal fun isLocalPreviewImagePath(path: String): Boolean =
    (path.startsWith('/') && !path.startsWith("//") || path.startsWith("~/")) &&
        path.none { it < ' ' } && !isSensitivePreviewPath(path)

internal fun parsePreviewObject(raw: String): JsonObject? = if (raw.length > PREVIEW_ARGUMENT_LIMIT) null else
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

private fun safePlainText(text: String): String {
    var out = privateSections.replace(stripPreviewControls(text), "")
    privateJsonField.find(out)?.let { out = out.substring(0, it.range.first) }
    out = privateFields.replace(out, "")
    privateMapField.find(out)?.let { out = out.substring(0, it.range.last + 1) + MASK }
    out = privateKey.replace(out, MASK)
    out = bearer.replace(out) { "${it.groupValues[1]} $MASK" }
    out = secretHeaders.replace(out) { "${it.groupValues[1]}$MASK" }
    out = secretFields.replace(out) { "${it.groupValues[1]}$MASK" }
    out = secretSwitch.replace(out) { "${it.groupValues[1]}$MASK" }
    out = headerSwitch.replace(out) { "${it.groupValues[1]}$MASK" }
    out = urlUserInfo.replace(out) { "${it.groupValues[1]}$MASK@" }
    return secretQuery.replace(out) { "${it.groupValues[1]}$MASK" }
}

private class PreviewSanitizer {
    private var nodes = 0
    private var remaining = PREVIEW_TEXT_LIMIT
    fun clean(value: JsonElement, depth: Int = 0, allowed: Set<String>? = null): JsonElement {
        if (depth > 6 || ++nodes > 256) return JsonPrimitive("…")
        return when (value) {
            is JsonObject -> {
                val hideBody = value.hasSensitivePreviewPaths() ||
                    (value["password"] as? JsonPrimitive)?.booleanOrNull == true
                JsonObject(value.entries.asSequence().filter { (key, _) -> !isHiddenKey(key) && (allowed == null || key in allowed) }.take(32).associate { (key, v) ->
                    val body = normalizedKey(key) in bodyKeys
                    safePlainText(key).take(80) to when {
                        isSecretKey(key) || hideBody && body -> JsonPrimitive(MASK)
                        else -> clean(v, depth + 1, allowed)
                    }
                })
            }
            is JsonArray -> JsonArray(value.take(32).map { clean(it, depth + 1, allowed) })
            is JsonPrimitive -> if (!value.isString) value else {
                val raw = value.content
                val nested = if (raw.length <= PREVIEW_ARGUMENT_LIMIT && raw.trimStart().let { it.startsWith('{') || it.startsWith('[') }) {
                    runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                } else null
                val text = if (nested != null) clean(nested, depth + 1).toString() else safePlainText(raw)
                val limit = minOf(PREVIEW_FIELD_LIMIT, remaining.coerceAtLeast(0))
                remaining -= minOf(text.length, limit)
                JsonPrimitive(text.take(limit).safeUnicodeEnd() + if (text.length > limit) "…" else "")
            }
        }
    }
}

internal fun safeToolPreviewText(text: String): String {
    if (text.length > PREVIEW_ARGUMENT_LIMIT) return "…"
    val jsonLike = text.trimStart().let { it.startsWith('{') || it.startsWith('[') }
    val parsed = if (jsonLike) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
    if (parsed != null) return PreviewSanitizer().clean(parsed).toString().take(PREVIEW_TEXT_LIMIT).safeUnicodeEnd()
    if (jsonLike) return "…"
    return safePlainText(text).take(PREVIEW_TEXT_LIMIT).safeUnicodeEnd()
}

internal fun safeToolPreviewTail(text: String): String {
    if (text.trimStart().let { it.startsWith('{') || it.startsWith('[') }) return safeToolPreviewText(text)
    if (text.length <= PREVIEW_ARGUMENT_LIMIT) return safePlainText(text).takeLast(PREVIEW_TEXT_LIMIT).safeUnicodeStart()
    val start = text.length - PREVIEW_ARGUMENT_LIMIT
    val pendingPrivate = privateSectionBoundary.findAll(text).lastOrNull { it.range.first < start }?.value
    val pendingKey = privateKeyBoundary.findAll(text).lastOrNull { it.range.first < start }?.value
    if (pendingPrivate?.startsWith("</") == false || pendingKey?.startsWith("-----BEGIN") == true) return "…"
    val suffix = text.substring(start).substringAfter('\n', "")
    return safePlainText(suffix).takeLast(PREVIEW_TEXT_LIMIT).safeUnicodeStart()
}

private val privateSectionBoundary = Regex("(?i)</?(?:think|thinking|reasoning|analysis)>")
private val privateKeyBoundary = Regex("-----[A-Z ]*PRIVATE KEY-----")

private val draftAssignedField = Regex("""(?<![\w.-])("(?:\\.|[^"\\])*+"|'(?:\\.|[^'\\])*+'|[\w.-]++)\s*+[:=]\s*+""")

internal fun safeFileDraftText(text: String): String {
    val source = stripPreviewControls(text)
    val sensitive = draftAssignedField.findAll(source).firstOrNull { match ->
        val token = match.groupValues[1]
        val key = if (token.startsWith('"')) runCatching { (Json.parseToJsonElement(token) as JsonPrimitive).content }.getOrNull().orEmpty()
            else token.trim('\'')
        isSecretKey(key) || isHiddenKey(key)
    }
    val prefix = sensitive?.let { source.substring(0, it.range.last + 1) } ?: source
    return (safePlainText(prefix) + if (sensitive != null) MASK else "").take(PREVIEW_TEXT_LIMIT).safeUnicodeEnd()
}

private val commandTokens = Regex("\"[^\"]*\"|'[^']*'|[^\\s;|&<>]+")
internal class ToolPreviewSafety(val name: String, val arguments: String) {
    private val complete: JsonObject? = parsePreviewObject(arguments)
    val parsed: JsonObject = complete ?: JsonObject(previewPartialStrings(arguments).mapValues { JsonPrimitive(it.value) })
    private val kind = ToolPreviewTarget("safety", name, arguments, "", ToolPreviewStage.PREPARING).kind
    val bodyHidden: Boolean = hidesPreviewBody(name, arguments, parsed, kind)
    val safeArgs: JsonObject by lazy { safePreviewArgs(this, 0) }
    val fileDraft: FileToolDraft? by lazy { fileToolDraftOf(name, arguments) }
    val titleHidden: Boolean by lazy { bodyHidden || kind != ToolPreviewKind.BROWSER && hasSensitivePreviewValues(parsed) }
    val taskActionResource: Int? by lazy {
        if (name.equals("task", true)) taskOperationActionResource(taskOperationAction(arguments)) else null
    }
    val browserTabId: String? = if (name.equals("browser", true) && complete != null && !bodyHidden) {
        ((parsed["tabId"] ?: parsed["tab_id"]) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
    } else null
}

@Composable
internal fun rememberToolPreviewSafety(target: ToolPreviewTarget): ToolPreviewSafety =
    remember(target.sourceSessionId, target.id, target.name, target.arguments) { ToolPreviewSafety(target.name, target.arguments) }

private fun hasSensitivePreviewValues(args: JsonObject): Boolean {
    var nodes = 0
    fun sensitive(value: JsonElement, depth: Int): Boolean {
        if (depth > 6 || ++nodes > 256) return true
        return when (value) {
            is JsonObject -> value.any { (key, v) -> isSecretKey(key) || sensitive(v, depth + 1) }
            is JsonArray -> value.any { sensitive(it, depth + 1) }
            is JsonPrimitive -> value.isString && safePlainText(value.content) != value.content
        }
    }
    return sensitive(args, 0)
}

internal fun hidesPreviewBody(target: ToolPreviewTarget): Boolean = ToolPreviewSafety(target.name, target.arguments).bodyHidden

private fun hidesPreviewBody(name: String, arguments: String, args: JsonObject, kind: ToolPreviewKind): Boolean {
    if (arguments.length > PREVIEW_ARGUMENT_LIMIT) return true
    if (args.hasSensitivePreviewPaths()) return true
    if (kind == ToolPreviewKind.FILE) {
        return fileToolDraftOf(name, arguments, buildDiff = false)?.bodyHidden != false
    }
    if (kind == ToolPreviewKind.BROWSER) {
        var nodes = 0
        fun sensitive(value: JsonElement, depth: Int): Boolean {
            if (depth > 6 || ++nodes > 256) return true
            return when (value) {
                is JsonObject -> value.any { (key, v) -> isSecretKey(key) || sensitive(v, depth + 1) }
                is JsonArray -> value.any { sensitive(it, depth + 1) }
                is JsonPrimitive -> value.isString && (value.content.length > PREVIEW_FIELD_LIMIT || safePlainText(value.content) != value.content)
            }
        }
        if (sensitive(args, 0)) return true
        val selector = (args["selector"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (Regex("(?i)password|passwd|token|secret|credential|authorization|api[-_]?key").containsMatchIn(selector)) return true
    }
    return listOf("command", "cmd", "script", "input").any { field ->
        val command = (args[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
        commandTokens.findAll(command).any { token ->
            val path = token.value.trim('\'', '"').substringAfter('=')
            ('/' in path || '\\' in path || '.' in path || path in setOf("id_rsa", "id_ed25519")) && isSensitivePreviewPath(path)
        }
    }
}

internal fun safePreviewArgs(target: ToolPreviewTarget): JsonObject = ToolPreviewSafety(target.name, target.arguments).safeArgs

private fun safePreviewArgs(safety: ToolPreviewSafety, depth: Int): JsonObject {
    if (depth > 6) return JsonObject(emptyMap())
    val parsed = safety.parsed
    val allowed = toolPreviewProfileOf(safety.name).fields + setOf("tool_title", "description")
    val hidden = safety.bodyHidden
    val fields = parsed.filterKeys { key -> key in allowed &&
        !(hidden && (normalizedKey(key) in bodyKeys || key in setOf("command", "cmd", "title", "tool_title"))) }
    if (safety.name.equals("scheduleTask", true) && fields["prefillToolCall"] is JsonObject) {
        val prefill = fields["prefillToolCall"] as JsonObject
        val name = (prefill["toolName"] as? JsonPrimitive)?.contentOrNull
        val arguments = prefill["arguments"] as? JsonObject
        val clean = PreviewSanitizer().clean(JsonObject(fields - "prefillToolCall"), allowed = allowed) as JsonObject
        if (name.isNullOrBlank() || arguments == null) return clean
        val nested = ToolPreviewSafety(name, arguments.toString())
        return JsonObject(clean + ("prefillToolCall" to JsonObject(mapOf(
            "toolName" to JsonPrimitive(safeToolPreviewText(name).take(160)), "arguments" to safePreviewArgs(nested, depth + 1),
        ))))
    }
    return PreviewSanitizer().clean(JsonObject(fields), allowed = allowed) as JsonObject
}

internal fun safePreviewActionTitle(target: ToolPreviewTarget, safety: ToolPreviewSafety = ToolPreviewSafety(target.name, target.arguments)): String? {
    val title = (target.actionTitle ?: (safety.parsed["tool_title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        ?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
    if (safety.titleHidden) return null
    return safeToolPreviewText(title).replace(Regex("\\s+"), " ").take(120).safeUnicodeEnd().takeIf { it.isNotBlank() }
}

private val ansiSequences = Regex("(?:\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\|$))|(?:\\u001B\\[[0-?]*[ -/]*[@-~])|(?:\\u001B[@-_])")
internal fun stripPreviewControls(text: String): String = ansiSequences.replace(text, "").filterNot {
    it.isISOControl() && it !in "\n\t" || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
}

internal fun previewTopLevelString(raw: String, name: String): String? =
    (parsePreviewObject(raw)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: previewPartialStrings(raw)[name]

private fun previewPartialStrings(raw: String): Map<String, String> {
    if (raw.length > PREVIEW_ARGUMENT_LIMIT || !raw.trimStart().startsWith('{')) return emptyMap()
    val fields = LinkedHashMap<String, String>()
    var depth = 0
    var index = 0
    while (index < raw.length) {
        when (raw[index]) {
            '{', '[' -> { if (++depth > 64) break; index++ }
            '}', ']' -> { depth--; index++ }
            '"' -> {
                val start = index++
                var escaped = false
                while (index < raw.length) {
                    val c = raw[index++]
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') break
                }
                val token = raw.substring(start, index)
                if (depth != 1 || !token.endsWith('"')) continue
                val key = runCatching { (Json.parseToJsonElement(token) as JsonPrimitive).content }.getOrNull() ?: continue
                var next = index
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (raw.getOrNull(next) != ':') continue
                next++
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (raw.getOrNull(next) != '"' || fields.size >= 32) continue
                val valueStart = next++
                escaped = false
                while (next < raw.length) {
                    val c = raw[next++]
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') break
                }
                val valueToken = raw.substring(valueStart, next)
                val completedToken = if (next == raw.length && valueToken.lastOrNull() != '"' && !escaped) "$valueToken\"" else valueToken
                val value = runCatching { (Json.parseToJsonElement(completedToken) as JsonPrimitive).content }.getOrNull()
                if (value != null) fields[key] = value
                index = next
            }
            else -> index++
        }
    }
    return fields
}
