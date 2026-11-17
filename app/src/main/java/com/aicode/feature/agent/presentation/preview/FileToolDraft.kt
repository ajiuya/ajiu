package com.aicode.feature.agent.presentation.preview

internal const val PREVIEW_ARGUMENT_LIMIT = 262_144
internal const val PREVIEW_TEXT_LIMIT = 32_768

internal data class ToolPreviewDiff(val removed: String = "", val added: String = "")

internal data class FileToolDraft(
    val path: String? = null,
    val diff: ToolPreviewDiff = ToolPreviewDiff(),
    val bodyHidden: Boolean = true,
    val truncated: Boolean = false,
)

internal fun fileToolDraftOf(toolName: String, accumulated: String, buildDiff: Boolean = true): FileToolDraft? {
    val name = toolName.lowercase(java.util.Locale.ROOT)
    if (name !in setOf("readfile", "writefile", "editfile")) return null
    return FileDraftParser(accumulated.take(PREVIEW_ARGUMENT_LIMIT), name, buildDiff).parse()
}

private class FileDraftParser(private val source: String, private val tool: String, private val buildDiff: Boolean) {
    private data class Text(val value: String, val complete: Boolean, val bounded: Boolean = false)
    private var index = 0
    private var invalid = false
    private var path: String? = null
    private var sensitivePathSeen = false
    private var remaining = PREVIEW_TEXT_LIMIT
    private var truncated = source.length >= PREVIEW_ARGUMENT_LIMIT
    private var nodes = 0
    private val removed = mutableListOf<String>()
    private val added = mutableListOf<String>()

    fun parse(): FileToolDraft {
        if (!consume('{')) return FileToolDraft()
        val keys = mutableSetOf<String>()
        while (!invalid && hasMore()) {
            if (consume('}')) break
            val key = string(80) ?: break
            if (key.bounded) { invalid = true; break }
            if (!key.complete || !consume(':')) break
            if (!keys.add(key.value)) { invalid = true; break }
            val complete = when {
                key.value in setOf("path", "file", "filePath") -> {
                    val value = string(4096)
                    if (value == null) invalid = true
                    if (value?.complete == true && !value.bounded && value.value.isNotBlank() && value.value.none { it < ' ' }) {
                        path = value.value
                        sensitivePathSeen = sensitivePathSeen || isSensitivePreviewPath(value.value)
                    }
                    value?.complete == true
                }
                key.value == "content" && tool == "writefile" -> bodyString(added)
                key.value == "edits" && tool == "editfile" -> edits()
                else -> skipValue(0)
            }
            if (!complete || !separator('}')) break
        }
        if (index > 0 && source[index - 1] == '}' && hasMore()) invalid = true
        val confirmedPath = path.takeUnless { invalid }
        val hidden = confirmedPath == null || sensitivePathSeen || isSensitivePreviewPath(confirmedPath)
        return FileToolDraft(
            path = if (buildDiff) confirmedPath?.let(::safeToolPreviewText) else confirmedPath,
            diff = if (hidden || !buildDiff) ToolPreviewDiff() else ToolPreviewDiff(removed.joinToString("\n"), added.joinToString("\n")),
            bodyHidden = hidden,
            truncated = truncated,
        )
    }

    private fun edits(): Boolean {
        if (!consume('[')) return fail()
        var count = 0
        while (hasMore()) {
            if (consume(']')) return true
            if (++count > 32) { truncated = true; return false }
            if (!consume('{')) return fail()
            val keys = mutableSetOf<String>()
            while (hasMore()) {
                if (consume('}')) break
                val key = string(80) ?: return false
                if (key.bounded) return fail()
                if (!key.complete || !consume(':')) return false
                if (!keys.add(key.value)) return fail()
                val complete = when (key.value) {
                    "old_string" -> bodyString(removed)
                    "new_string" -> bodyString(added)
                    else -> skipValue(0)
                }
                if (!complete) return false
                if (!separator('}')) {
                    if (index > 0 && source[index - 1] == '}') break
                    return false
                }
            }
            if (!separator(']')) return index > 0 && source[index - 1] == ']'
        }
        return false
    }

    private fun bodyString(destination: MutableList<String>): Boolean {
        if (!buildDiff) return string(0)?.complete ?: fail()
        val text = string(remaining.coerceAtLeast(0)) ?: return fail()
        if (text.bounded) truncated = true
        val safe = safeFileDraftText(text.value).take(remaining.coerceAtLeast(0)).safeUnicodeEnd()
        if (safe.isNotEmpty()) {
            destination += safe
            remaining = (remaining - safe.length - 1).coerceAtLeast(0)
        }
        return text.complete
    }

    private fun string(limit: Int): Text? {
        if (!consume('"')) return null
        val out = StringBuilder()
        var pendingHigh: Char? = null
        var bounded = false
        fun append(c: Char): Boolean {
            val high = pendingHigh
            if (high != null) {
                if (!c.isLowSurrogate()) return fail()
                if (!bounded && out.length + 2 <= limit) out.append(high).append(c) else bounded = true
                pendingHigh = null
            } else when {
                c.isHighSurrogate() -> pendingHigh = c
                c.isLowSurrogate() -> return fail()
                !bounded && out.length < limit -> out.append(c)
                else -> bounded = true
            }
            return true
        }
        while (index < source.length) {
            val c = source[index++]
            if (c == '"') {
                if (pendingHigh != null) { invalid = true; return null }
                return Text(out.toString(), true, bounded)
            }
            if (c < ' ') { invalid = true; return null }
            val decoded = if (c != '\\') c else {
                if (index == source.length) return Text(out.toString(), false, bounded)
                when (source[index++]) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (source.length - index < 4) return Text(out.toString(), false, bounded)
                        val hex = source.substring(index, index + 4)
                        val code = hex.takeIf { it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }?.toIntOrNull(16)
                        if (code == null) { invalid = true; return null }
                        index += 4
                        code.toChar()
                    }
                    else -> { invalid = true; return null }
                }
            }
            if (!append(decoded)) return null
        }
        return Text(out.toString(), false, bounded)
    }

    private fun skipValue(depth: Int): Boolean {
        if (depth > 8 || ++nodes > 256) { truncated = true; return false }
        whitespace()
        if (index == source.length) return false
        return when (source[index]) {
            '"' -> string(0)?.complete == true
            '{' -> {
                index++
                while (hasMore()) {
                    if (consume('}')) return true
                    if (string(0)?.complete != true || !consume(':') || !skipValue(depth + 1)) return false
                    if (!separator('}')) return index > 0 && source[index - 1] == '}'
                }
                false
            }
            '[' -> {
                index++
                while (hasMore()) {
                    if (consume(']')) return true
                    if (!skipValue(depth + 1)) return false
                    if (!separator(']')) return index > 0 && source[index - 1] == ']'
                }
                false
            }
            else -> {
                val start = index
                while (index < source.length && source[index] !in ",]} \t\r\n") index++
                if (index == source.length) return false
                val value = source.substring(start, index)
                if (value !in setOf("true", "false", "null") && !NUMBER.matches(value)) return fail()
                true
            }
        }
    }

    private fun separator(end: Char): Boolean {
        if (consume(',')) return true
        if (!consume(end) && hasMore()) invalid = true
        return false
    }

    private fun consume(c: Char): Boolean {
        whitespace()
        if (index >= source.length || source[index] != c) return false
        index++
        return true
    }

    private fun hasMore(): Boolean { whitespace(); return index < source.length }
    private fun whitespace() { while (index < source.length && source[index] in " \t\r\n") index++ }
    private fun fail(): Boolean { invalid = true; return false }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

internal fun String.safeUnicodeEnd(): String = if (lastOrNull()?.isHighSurrogate() == true) dropLast(1) else this
