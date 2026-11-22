package com.aicode.feature.plugin.data.index

import com.aicode.feature.plugin.domain.LocalizedText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val PluginIndexJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = false }

@Serializable
data class PluginIndexDocument(@SerialName("schema_version") val schemaVersion: Int, val plugins: List<PluginIndexPlugin> = emptyList())

@Serializable
data class PluginIndexPlugin(
    val id: String,
    @Serializable(with = LocalizedTextJsonSerializer::class) val name: LocalizedText,
    @Serializable(with = LocalizedTextJsonSerializer::class) val description: LocalizedText,
    val author: String,
    val repository: String,
    val logo: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val tags: List<String> = emptyList(),
    val version: String? = null,
    @SerialName("min_app_version") val minAppVersion: String? = null
)

internal object LocalizedTextJsonSerializer : KSerializer<LocalizedText> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): LocalizedText {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON required")
        return when (val value = json.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!value.isString) throw SerializationException("localized text must be a string")
                LocalizedText.single(value.content.trim())
            }
            is JsonObject -> {
                if (value.isEmpty()) throw SerializationException("empty localized text")
                val entries = linkedMapOf<String, String>()
                value.forEach { (tag, text) ->
                    val content = (text as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw SerializationException("invalid localized text")
                    if (entries.put(LocalizedText.normalize(tag), content.trim()) != null) throw SerializationException("duplicate locale")
                }
                LocalizedText(entries)
            }
            else -> throw SerializationException("invalid localized text")
        }
    }
    override fun serialize(encoder: Encoder, value: LocalizedText) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON required")
        if (value.values.keys == setOf(LocalizedText.DEFAULT_KEY)) json.encodeString(value.values.getValue(LocalizedText.DEFAULT_KEY))
        else json.encodeJsonElement(JsonObject(value.values.mapValues { JsonPrimitive(it.value) }))
    }
}

object PluginTags {
    const val PROVIDER = "provider"
    const val TOOL = "tool"
    const val SKILL = "skill"
    const val AGENT = "agent"
    const val MCP = "mcp"
    const val UI = "ui"
    const val INTEGRATION = "integration"
    const val OTHER = "other"
    val ALL = setOf(PROVIDER, TOOL, SKILL, AGENT, MCP, UI, INTEGRATION, OTHER)
}
