package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginSessionHostService @Inject constructor(
    private val registry: PluginSessionRegistry,
    private val events: PluginSessionEventBus
) {
    fun setSessions(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "sessions"))
        val source = requireSource(context, params)
        val sessions = PluginSessionCodec.sessions(context, source, params["sessions"])
        registry.setSessions(context, source.key, sessions)
        return ok()
    }

    fun setLoading(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "loading"))
        val source = requireSource(context, params)
        val loading = PluginConversationJson.boolean(params, "loading") ?: PluginConversationJson.invalid("loading is required")
        registry.setLoading(context, source.key, loading)
        return ok()
    }

    fun setError(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "error"))
        val source = requireSource(context, params)
        registry.setError(context, source.key, PluginConversationJson.optionalString(params, "error", 1000))
        return ok()
    }

    fun setThinking(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "sessionId", "visible", "text"))
        val source = requireSource(context, params)
        val id = PluginConversationJson.string(params, "sessionId", 256)
        val visible = PluginConversationJson.boolean(params, "visible") ?: PluginConversationJson.invalid("visible is required")
        val text = if (visible) PluginConversationJson.optionalString(params, "text", 200)
            ?: source.title.resolve(Locale.getDefault().toLanguageTag()) else null
        registry.setThinking(context, source.key, id, text)
        return ok()
    }

    suspend fun setMessages(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "sessionId", "page", "revision"))
        val source = requireSource(context, params)
        val id = PluginConversationJson.string(params, "sessionId", 256)
        registry.requireSession(context, source.key, id)
        val page = PluginConversationJson.objectValue(params["page"], "page")
        PluginSessionCodec.page(page, id)
        params["revision"]?.let { if (it.toString().length > 128) PluginConversationJson.invalid("revision is too large") }
        events.publish(context, "${source.key}|$id", buildJsonObject {
            put("type", "snapshot"); put("page", page); params["revision"]?.let { put("revision", it) }
        })
        return ok()
    }

    suspend fun pushEvent(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("sourceId", "sessionId", "type", "callId", "name", "input", "output", "text", "message", "status", "revision", "timestamp"))
        val source = requireSource(context, params)
        val id = PluginConversationJson.string(params, "sessionId", 256)
        registry.requireSession(context, source.key, id)
        val type = PluginConversationJson.string(params, "type", 32)
        if (params.toString().length > 256 * 1024) PluginConversationJson.invalid("Session event is too large")
        if (type !in setOf("tool-start", "tool-output", "tool-end", "message", "turn-end")) PluginConversationJson.invalid("Unsupported session event")
        if (type.startsWith("tool-")) PluginConversationJson.string(params, "callId", 128)
        if (type == "message") PluginSessionCodec.message(params["message"] ?: PluginConversationJson.invalid("message is required"))
        events.publish(context, "${source.key}|$id", params)
        return ok()
    }

    private fun requireSource(context: PluginInvocationContext, params: JsonObject): PluginSessionSourceDefinition {
        context.checkCurrent()
        val id = PluginConversationJson.string(params, "sourceId", 64)
        val source = context.capture.content.sessionSources.firstOrNull { it.localId == id }
            ?: throw PluginHostException("SESSION_SOURCE_NOT_FOUND", "Session source is not declared by this plugin")
        registry.ensureSource(context, source)
        return source
    }

    private fun ok() = buildJsonObject { put("ok", true) }
}
