package com.aicode.feature.plugin.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object PluginJson {
    fun parse(text: String, maxDepth: Int = 24): JsonElement {
        Scanner(text, maxDepth).validate()
        return Json.parseToJsonElement(text)
    }

    private class Scanner(val text: String, val maxDepth: Int) {
        var cursor = 0
        fun validate() {
            value(0)
            whitespace()
            require(cursor == text.length) { "trailing input" }
        }
        fun whitespace() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
        fun take(c: Char): Boolean {
            whitespace()
            return if (cursor < text.length && text[cursor] == c) { cursor++; true } else false
        }
        fun quoted(): String {
            whitespace()
            val start = cursor
            require(cursor < text.length && text[cursor++] == '"') { "string expected" }
            while (cursor < text.length) {
                when (text[cursor++]) {
                    '\\' -> { require(cursor < text.length); cursor++ }
                    '"' -> return (Json.parseToJsonElement(text.substring(start, cursor)) as JsonPrimitive).content
                }
            }
            error("unterminated string")
        }
        fun value(depth: Int) {
            require(depth <= maxDepth) { "json depth" }
            whitespace()
            require(cursor < text.length) { "missing value" }
            when (text[cursor]) {
                '{' -> {
                    cursor++
                    val keys = HashSet<String>()
                    if (take('}')) return
                    do {
                        require(keys.add(quoted())) { "duplicate json key" }
                        require(take(':'))
                        value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
                '[' -> {
                    cursor++
                    if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    require(take(']'))
                }
                '"' -> quoted()
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] !in ",]} \t\r\n") cursor++
                    require(cursor > start)
                }
            }
        }
    }
}
