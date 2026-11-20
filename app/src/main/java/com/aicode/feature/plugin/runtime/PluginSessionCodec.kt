package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.domain.session.PluginSessionMessage
import com.aicode.feature.plugin.domain.session.PluginSessionPage
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PluginSessionCodec {
    fun sessions(context: PluginInvocationContext, source: PluginSessionSourceDefinition, raw: JsonElement?): List<PluginSessionSummary> {
        val rows = raw as? JsonArray ?: PluginConversationJson.invalid("sessions must be an array")
        if (rows.size > 1000 || rows.toString().length > 1024 * 1024) PluginConversationJson.invalid("Session list is too large")
        val locale = Locale.getDefault().toLanguageTag()
        val sourceTitle = source.title.resolve(locale)
        val name = context.capture.manifest.name.resolve(locale)
        val ids = mutableSetOf<String>()
        return rows.map { element ->
            val row = PluginConversationJson.objectValue(element, "session")
            val id = PluginConversationJson.string(row, "sessionId", 256)
            if (!ids.add(id) || '|' in id) PluginConversationJson.invalid("Invalid or duplicate external session ID")
            val status = PluginConversationJson.optionalString(row, "status", 32)
                ?: if (PluginConversationJson.boolean(row, "running") == true) "running" else "idle"
            val host = PluginConversationJson.optionalString(row, "hostName", 200).orEmpty()
            val workspace = PluginConversationJson.optionalString(row, "workspaceTitle", 200).orEmpty()
            val capabilities = row["capabilities"]?.let { value ->
                val list = value as? JsonArray ?: PluginConversationJson.invalid("capabilities must be an array")
                if (list.size > 32) PluginConversationJson.invalid("Too many session capabilities")
                list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 64 }?.content
                    ?: PluginConversationJson.invalid("Invalid session capability") }
            }.orEmpty()
            PluginSessionSummary("${source.key}|$id", id, source.key, sourceTitle, context.capture.id, name, source.pluginVersion,
                PluginConversationJson.optionalString(row, "title", 200)?.takeIf { it.isNotBlank() } ?: sourceTitle,
                status, PluginConversationJson.boolean(row, "running") == true || status == "running",
                PluginConversationJson.long(row, "updatedAt"), PluginConversationJson.optionalString(row, "group", 200)
                    ?: listOf(host, workspace).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { sourceTitle },
                host, workspace, capabilities, PluginConversationJson.optionalString(row, "providerId"),
                PluginConversationJson.optionalString(row, "model"), PluginConversationJson.optionalString(row, "executionMode", 32))
        }
    }

    fun page(raw: JsonElement, sessionId: String): PluginSessionPage {
        val root = PluginConversationJson.objectValue(raw, "page")
        if (root.toString().length > 1024 * 1024) PluginConversationJson.invalid("Session page is too large")
        val returnedId = PluginConversationJson.optionalString(root, "sessionId", 256) ?: sessionId
        if (returnedId != sessionId) throw PluginHostException("SESSION_FORBIDDEN", "Session response belongs to a different session")
        val messages = root["messages"] as? JsonArray ?: PluginConversationJson.invalid("page.messages must be an array")
        if (messages.size > 100) PluginConversationJson.invalid("Too many messages in a page")
        val ids = mutableSetOf<String>()
        val parsed = messages.map { message(it).also { row -> if (!ids.add(row.id)) PluginConversationJson.invalid("Duplicate message ID") } }
        val cursor = root["nextCursor"]?.takeUnless { it is JsonNull }
        if (cursor != null && cursor.toString().length > 4096) PluginConversationJson.invalid("Cursor is too large")
        return PluginSessionPage(sessionId, PluginConversationJson.optionalString(root, "title", 200).orEmpty(),
            PluginConversationJson.optionalString(root, "status", 32).orEmpty(), parsed,
            PluginConversationJson.boolean(root, "hasMore") == true, cursor)
    }

    fun message(raw: JsonElement): PluginSessionMessage {
        val row = PluginConversationJson.objectValue(raw, "message")
        val id = PluginConversationJson.string(row, "id", 256)
        val role = PluginConversationJson.optionalString(row, "role", 16) ?: "assistant"
        if (role !in setOf("user", "assistant", "tool", "notice")) PluginConversationJson.invalid("Invalid message role")
        val blocks = row["blocks"] as? JsonArray ?: PluginConversationJson.invalid("Message blocks must be an array")
        if (blocks.size > 64) PluginConversationJson.invalid("Too many message blocks")
        return PluginSessionMessage(id, role, PluginConversationJson.long(row, "createdAt"),
            PluginConversationJson.optionalString(row, "status", 32) ?: "done", blocks.mapNotNull(::block))
    }

    private fun block(raw: JsonElement): PluginMessageBlock? {
        val row = PluginConversationJson.objectValue(raw, "block")
        fun text(key: String, max: Int = 128 * 1024) = PluginConversationJson.optionalString(row, key, max)
        return when (PluginConversationJson.string(row, "type", 32)) {
            "text" -> text("text")?.let(PluginMessageBlock::Text)
            "reasoning" -> text("text")?.let(PluginMessageBlock::Reasoning)
            "tool" -> PluginMessageBlock.Tool(text("callId", 128), text("name", 128) ?: "tool", text("status", 32) ?: "success",
                jsonText(row["input"]), outputText(row["output"]))
            "attachment" -> PluginMessageBlock.Attachment(text("kind", 32), text("name", 200), text("mimeType", 128), text("path", 4096))
            "notice" -> text("text")?.let(PluginMessageBlock::Notice)
            else -> text("text")?.let(PluginMessageBlock::Text)
        }
    }

    private fun outputText(raw: JsonElement?): String? = if (raw is JsonArray) raw.joinToString("\n") {
        if (it is JsonObject) PluginConversationJson.optionalString(it, "text", 128 * 1024) ?: it.toString() else jsonText(it).orEmpty()
    }.also { if (it.length > 128 * 1024) PluginConversationJson.invalid("Tool output is too large") } else jsonText(raw)

    private fun jsonText(raw: JsonElement?): String? = when (raw) {
        null, is JsonNull -> null
        is JsonPrimitive -> raw.content
        else -> raw.toString()
    }?.also { if (it.length > 128 * 1024) PluginConversationJson.invalid("Tool payload is too large") }
}
