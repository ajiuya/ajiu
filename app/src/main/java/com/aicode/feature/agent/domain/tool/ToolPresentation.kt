package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object ToolPresentation {
    fun titleOf(call: ToolCall): String? {
        val value = call.arguments[AgentTool.TOOL_TITLE_PARAM] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val clean = buildString {
            var index = 0
            var count = 0
            val text = value.content.trim()
            while (index < text.length && count < 48) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                if (Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.SURROGATE.toInt()
                ) continue
                appendCodePoint(codePoint)
                count++
            }
        }.trim()
        return clean.takeIf { it.isNotEmpty() }
    }

    fun executionArguments(tool: AgentTool, arguments: Map<String, JsonElement>): Map<String, JsonElement> =
        if (tool.hasModelActionTitle() && AgentTool.TOOL_TITLE_PARAM in arguments) {
            arguments - AgentTool.TOOL_TITLE_PARAM
        } else {
            arguments
        }
}
