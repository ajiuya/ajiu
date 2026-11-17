package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.session.WeightedLruCache
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ContextEstimateCache(
    private val maxMessageEntries: Int = 8_192,
    private val maxMessageBytes: Long = 24L * 1024 * 1024,
    private val maxSchemaEntries: Int = 512,
    private val maxSchemaBytes: Long = 4L * 1024 * 1024
) {
    internal data class Stats(
        val messageEntries: Int,
        val messageBytes: Long,
        val schemaEntries: Int,
        val schemaBytes: Long,
        val messageHits: Long,
        val schemaHits: Long,
        val messageMaterializations: Long,
        val schemaMaterializations: Long,
        val uncachedMessages: Long,
        val uncachedSchemas: Long
    )

    private enum class MessageKind { USER, ASSISTANT, TOOL }
    private data class MessageKey(val id: String, val kind: MessageKind, val charsPerToken: Int)
    private data class SchemaKey(val name: String, val charsPerToken: Int)
    private sealed interface MessageText
    private data class UserText(val content: String) : MessageText
    private data class CallText(val name: String, val arguments: Map<String, JsonElement>)
    private data class AssistantText(val content: String, val reasoning: String, val calls: List<CallText>) : MessageText
    private data class ToolText(val name: String, val result: String, val project: Boolean) : MessageText
    private sealed interface SchemaSnapshot
    private data class BaseSchema(
        val name: String,
        val description: String,
        val parameters: Map<String, ToolParameter>,
        val capabilities: Set<ToolCapability>
    ) : SchemaSnapshot
    private data class CustomSchema(val name: String, val description: String, val schema: Map<String, Any>) : SchemaSnapshot
    private data class MessageCount(val snapshot: MessageText, val tokens: Int)
    private data class SchemaCount(val snapshot: SchemaSnapshot, val tokens: Int)

    private val lock = Any()
    private val gson = Gson()
    // 锚定基类方法，让 R8 能跟踪反射名称；运行时子类可能覆写并动态生成 schema。
    private val baseSchemaMethod = AgentTool::class.java.getMethod("toJsonSchema")
    private val messages = WeightedLruCache<MessageKey, MessageCount>(maxMessageEntries, maxMessageBytes)
    private val schemas = WeightedLruCache<SchemaKey, SchemaCount>(maxSchemaEntries, maxSchemaBytes)
    private var messageHits = 0L
    private var schemaHits = 0L
    private var messageMaterializations = 0L
    private var schemaMaterializations = 0L
    private var uncachedMessages = 0L
    private var uncachedSchemas = 0L

    internal val stats: Stats
        get() = synchronized(lock) {
            Stats(messages.size, messages.bytes, schemas.size, schemas.bytes, messageHits, schemaHits,
                messageMaterializations, schemaMaterializations, uncachedMessages, uncachedSchemas)
        }

    internal fun messageTextTokens(message: AgentMessage, charsPerToken: Int): Int = synchronized(lock) {
        val kind: MessageKind
        val current: MessageText
        when (message) {
            is AgentMessage.UserMessage -> {
                kind = MessageKind.USER
                current = UserText(message.content)
            }
            is AgentMessage.AssistantMessage -> {
                kind = MessageKind.ASSISTANT
                current = AssistantText(message.content, message.reasoning,
                    message.toolCalls.map { CallText(it.name, it.arguments) })
            }
            is AgentMessage.ToolResultMessage -> {
                kind = MessageKind.TOOL
                current = ToolText(message.toolName, message.modelResult ?: message.result, message.modelResult == null)
            }
        }
        val key = MessageKey(message.id, kind, charsPerToken)
        messages[key]?.takeIf { messageMatches(it.snapshot, current) }?.let {
            messageHits++
            return@synchronized it.tokens
        }
        messages.remove(key)
        messageMaterializations++
        val text = when (current) {
            is UserText -> current.content
            is AssistantText -> current.content + current.reasoning +
                current.calls.joinToString { it.name + gson.toJson(it.arguments) }
            is ToolText -> current.name + if (current.project) {
                modelToolResultText(current.name, current.result) ?: current.result
            } else current.result
        }
        val tokens = ModelContextPolicy.estimateTextTokens(text, charsPerToken)
        val budget = RetainedBudget(maxMessageBytes)
        if (maxMessageEntries > 0 && budget.add(384) && budget.include(key.id) && budget.include(current)) {
            val snapshot = when (current) {
                is AssistantText -> current.copy(calls = current.calls.map { call ->
                    call.copy(arguments = call.arguments.mapValues { freezeJson(it.value) })
                })
                else -> current
            }
            messages.put(key, MessageCount(snapshot, tokens), budget.bytes)
        } else {
            uncachedMessages++
        }
        tokens
    }

    internal fun schemaTextTokens(tool: AgentTool, charsPerToken: Int): Int = synchronized(lock) {
        val name = tool.name
        val description = tool.description
        val key = SchemaKey(name, charsPerToken)
        val current: SchemaSnapshot = if (tool.javaClass.getMethod(baseSchemaMethod.name).declaringClass == AgentTool::class.java) {
            BaseSchema(name, description, tool.parameters, tool.capabilities)
        } else {
            CustomSchema(name, description, tool.toModelJsonSchema())
        }
        schemas[key]?.takeIf { schemaMatches(it.snapshot, current) }?.let {
            schemaHits++
            return@synchronized it.tokens
        }
        schemas.remove(key)
        schemaMaterializations++
        val schema = if (current is CustomSchema) current.schema else tool.toModelJsonSchema()
        val tokens = ModelContextPolicy.estimateTextTokens(name + description + gson.toJson(schema), charsPerToken)
        val budget = RetainedBudget(maxSchemaBytes)
        if (maxSchemaEntries > 0 && budget.add(384) && budget.include(key.name) && budget.include(current)) {
            val snapshot = when (current) {
                is BaseSchema -> current.copy(
                    parameters = current.parameters.mapValues { (_, parameter) ->
                        parameter.copy(enum = parameter.enum?.toList(), itemsSchema = parameter.itemsSchema?.let(::freezeMap))
                    },
                    capabilities = current.capabilities.toSet()
                )
                is CustomSchema -> current.copy(schema = freezeMap(current.schema))
            }
            schemas.put(key, SchemaCount(snapshot, tokens), budget.bytes)
        } else {
            uncachedSchemas++
        }
        tokens
    }

    private fun messageMatches(first: MessageText, second: MessageText): Boolean =
        if (first is AssistantText && second is AssistantText) {
            first.content == second.content && first.reasoning == second.reasoning &&
                first.calls.size == second.calls.size && first.calls.indices.all {
                    first.calls[it].name == second.calls[it].name && orderedEqual(first.calls[it].arguments, second.calls[it].arguments)
                }
        } else first == second

    private fun schemaMatches(first: SchemaSnapshot, second: SchemaSnapshot): Boolean = when {
        first is BaseSchema && second is BaseSchema -> first.name == second.name && first.description == second.description &&
            first.capabilities == second.capabilities && orderedEqual(first.parameters, second.parameters)
        first is CustomSchema && second is CustomSchema -> first.name == second.name && first.description == second.description &&
            orderedEqual(first.schema, second.schema)
        else -> false
    }

    private fun orderedEqual(first: Any?, second: Any?): Boolean = when {
        first is JsonObject || second is JsonObject -> first is JsonObject && second is JsonObject && orderedMapsEqual(first, second)
        first is JsonArray || second is JsonArray -> first is JsonArray && second is JsonArray && orderedListsEqual(first, second)
        first is Map<*, *> && second is Map<*, *> -> orderedMapsEqual(first, second)
        first is List<*> && second is List<*> -> orderedListsEqual(first, second)
        first is ToolParameter && second is ToolParameter -> first == second && orderedEqual(first.itemsSchema, second.itemsSchema)
        else -> first == second
    }

    private fun orderedMapsEqual(first: Map<*, *>, second: Map<*, *>): Boolean {
        if (first.size != second.size) return false
        val other = second.entries.iterator()
        return first.entries.all { entry ->
            val next = other.next()
            entry.key == next.key && orderedEqual(entry.value, next.value)
        }
    }

    private fun orderedListsEqual(first: List<*>, second: List<*>): Boolean =
        first.size == second.size && first.indices.all { orderedEqual(first[it], second[it]) }

    private fun freezeJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { freezeJson(it.value) })
        is JsonArray -> JsonArray(value.map(::freezeJson))
        else -> value
    }

    private fun freezeValue(value: Any?): Any? = when (value) {
        is JsonElement -> freezeJson(value)
        is Map<*, *> -> value.entries.associate { it.key to freezeValue(it.value) }
        is List<*> -> value.map(::freezeValue)
        is Set<*> -> value.toSet()
        is ToolParameter -> value.copy(enum = value.enum?.toList(), itemsSchema = value.itemsSchema?.let(::freezeMap))
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun freezeMap(value: Map<String, Any>): Map<String, Any> = freezeValue(value) as Map<String, Any>

    private class RetainedBudget(private val limit: Long) {
        var bytes = 0L
            private set

        fun add(weight: Long): Boolean {
            if (weight > limit - bytes) return false
            bytes += weight
            return true
        }

        // 按 UTF-16、容器节点及快照对象保守计费；未知可变类型直接不缓存。
        fun include(value: Any?): Boolean = when (value) {
            null -> add(16)
            is String -> add(64L + value.length * 2L)
            is JsonPrimitive -> add(64) && include(value.content)
            is Map<*, *> -> add(128) && value.entries.all {
                it.key is String && add(96) && include(it.key) && include(it.value)
            }
            is List<*> -> add(80) && value.all { add(16) && include(it) }
            is Set<*> -> add(128) && value.all { it is ToolCapability && add(96) }
            is ToolParameter -> add(112) && include(value.name) && include(value.description) &&
                include(value.enum) && include(value.itemsSchema)
            is UserText -> add(128) && include(value.content)
            is AssistantText -> add(128) && include(value.content) && include(value.reasoning) && include(value.calls)
            is CallText -> add(96) && include(value.name) && include(value.arguments)
            is ToolText -> add(128) && include(value.name) && include(value.result)
            is BaseSchema -> add(128) && include(value.name) && include(value.description) &&
                include(value.parameters) && include(value.capabilities)
            is CustomSchema -> add(128) && include(value.name) && include(value.description) && include(value.schema)
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> add(32)
            else -> false
        }
    }
}
