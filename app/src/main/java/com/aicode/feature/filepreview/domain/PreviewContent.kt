package com.aicode.feature.filepreview.domain

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val JSON_DEPTH_LIMIT = 64
private const val TABLE_CELL_CHARS = 8_192
private const val ARCHIVE_NAME_CHARS = 1_024

private val previewJson = Json { prettyPrint = true }

data class PreviewText(val text: String, val truncated: Boolean)

fun readPreviewText(input: InputStream, limit: Int = PreviewLimits.TEXT_BYTES): PreviewText {
    require(limit in 0..PreviewLimits.TEXT_BYTES)
    val bytes = ByteArray(limit + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            bytes[count++] = byte.toByte()
        } else {
            count += read
        }
    }
    val truncated = count > limit
    val length = minOf(count, limit)
    var offset = 0
    val charset = when {
        count >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
            offset = 3
            Charsets.UTF_8
        }
        count >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
            offset = 2
            Charsets.UTF_16LE
        }
        count >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
            offset = 2
            Charsets.UTF_16BE
        }
        else -> Charsets.UTF_8
    }
    if (offset > length) return PreviewText("", truncated)
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val source = ByteBuffer.wrap(bytes, offset, length - offset)
    val output = CharBuffer.allocate(length - offset)
    // 截断时不结束 decoder，使尾部半个 UTF 字符留在输入中，而不是生成替换字符。
    val result = decoder.decode(source, output, !truncated)
    if (result.isError) result.throwException()
    if (!truncated) {
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
    }
    output.flip()
    val text = output.toString()
    if ('\u0000' in text) throw IOException("Binary data is not preview text")
    return PreviewText(text, truncated)
}

fun prettyPreviewJson(text: PreviewText): PreviewText {
    if (text.truncated || !jsonWithinBudget(text.text) || !prettyJsonWithinBudget(text.text)) return text
    return try {
        val element = previewJson.parseToJsonElement(text.text)
        val formatted = previewJson.encodeToString(JsonElement.serializer(), element)
        if (utf8WithinBudget(formatted)) PreviewText(formatted, false) else text
    } catch (_: SerializationException) {
        text
    }
}

private fun jsonWithinBudget(text: String): Boolean {
    if (!utf8WithinBudget(text)) return false
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (char.code < 0x20) return false
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > JSON_DEPTH_LIMIT) return false
                '}', ']' -> depth--
            }
        }
    }
    return true
}

private fun prettyJsonWithinBudget(text: String): Boolean {
    var bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    bytes += 1L + 4L * depth
                }
                '}', ']' -> {
                    depth--
                    bytes += 1L + 4L * depth
                }
                ',' -> bytes += 1L + 4L * depth
                ':' -> bytes++
            }
        }
        if (bytes > PreviewLimits.TEXT_BYTES) return false
    }
    return true
}

private fun utf8WithinBudget(text: String): Boolean {
    if (text.length > PreviewLimits.TEXT_BYTES) return false
    return text.toByteArray(Charsets.UTF_8).size <= PreviewLimits.TEXT_BYTES
}

data class PreviewTable(val rows: List<List<String>>, val truncated: Boolean)

fun parsePreviewTable(text: PreviewText, delimiter: Char): PreviewTable {
    require(delimiter != '"' && delimiter != '\r' && delimiter != '\n' && delimiter != '\u0000')
    val source = text.text
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var afterQuote = false
    var fieldStarted = false
    var rowStarted = false
    var truncated = text.truncated
    var index = 0
    var bytes = 0
    var chars = 0
    var previous: Char? = null
    while (index < source.length && rows.size < PreviewLimits.CSV_ROWS) {
        val char = source[index]
        // 除逐格上限外，直接调用解析器也受总输入/输出预算约束。
        val byteCost = when {
            char.code <= 0x7F -> 1
            char.code <= 0x7FF -> 2
            char.isLowSurrogate() && previous?.isHighSurrogate() == true -> 1
            else -> 3
        }
        if (bytes + byteCost > PreviewLimits.TEXT_BYTES) {
            truncated = true
            break
        }
        bytes += byteCost
        previous = char
        index++
        rowStarted = true
        var append = false
        var endCell = false
        var endRow = false
        when {
            inQuotes -> {
                if (char == '"') {
                    inQuotes = false
                    afterQuote = true
                } else append = true
            }
            afterQuote && char == '"' -> {
                inQuotes = true
                afterQuote = false
                append = true
            }
            char == delimiter -> endCell = true
            char == '\r' || char == '\n' -> {
                endCell = true
                endRow = true
                if (char == '\r' && index < source.length && source[index] == '\n') {
                    if (bytes == PreviewLimits.TEXT_BYTES) truncated = true
                    else {
                        index++
                        bytes++
                        previous = '\n'
                    }
                }
            }
            char == '"' && !fieldStarted -> {
                inQuotes = true
                fieldStarted = true
            }
            else -> {
                if (afterQuote || char == '"') truncated = true
                afterQuote = false
                append = true
            }
        }
        if (append) {
            fieldStarted = true
            if (row.size >= PreviewLimits.CSV_COLUMNS || cell.length >= TABLE_CELL_CHARS ||
                chars >= PreviewLimits.TEXT_BYTES) {
                truncated = true
            } else {
                cell.append(char)
                chars++
            }
        }
        if (endCell) {
            if (row.size < PreviewLimits.CSV_COLUMNS) row.add(previewCell(cell))
            else truncated = true
            cell.setLength(0)
            fieldStarted = false
            afterQuote = false
        }
        if (endRow) {
            rows.add(row.toList())
            row = mutableListOf()
            rowStarted = false
        }
        if (truncated && bytes >= PreviewLimits.TEXT_BYTES) break
    }
    if (inQuotes) truncated = true
    if (rowStarted && rows.size < PreviewLimits.CSV_ROWS) {
        if (row.size < PreviewLimits.CSV_COLUMNS) row.add(previewCell(cell))
        else truncated = true
        rows.add(row.toList())
    }
    if (index < source.length) truncated = true
    return PreviewTable(rows.toList(), truncated)
}

private fun previewCell(cell: StringBuilder): String {
    val end = if (cell.isNotEmpty() && cell.last().isHighSurrogate()) cell.length - 1 else cell.length
    return cell.substring(0, end)
}

data class PreviewArchiveEntry(val name: String, val size: Long, val directory: Boolean)

data class PreviewArchive(val entries: List<PreviewArchiveEntry>, val truncated: Boolean)

fun listPreviewArchive(file: File): PreviewArchive {
    if (file.length() > PreviewLimits.FILE_BYTES) throw IOException("Archive exceeds preview byte limit")
    return ZipFile(file).use { zip ->
        val entries = mutableListOf<PreviewArchiveEntry>()
        val source = zip.entries()
        var truncated = false
        while (source.hasMoreElements() && entries.size < PreviewLimits.ARCHIVE_ENTRIES) {
            val entry = source.nextElement()
            val name = if (entry.name.length > ARCHIVE_NAME_CHARS) {
                truncated = true
                var end = ARCHIVE_NAME_CHARS - 1
                if (entry.name[end - 1].isHighSurrogate() && entry.name[end].isLowSurrogate()) end--
                entry.name.substring(0, end) + "\u2026"
            } else entry.name
            entries.add(PreviewArchiveEntry(name, entry.size, entry.isDirectory))
        }
        PreviewArchive(entries.toList(), truncated || source.hasMoreElements())
    }
}
