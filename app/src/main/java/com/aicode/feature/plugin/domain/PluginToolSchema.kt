package com.aicode.feature.plugin.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

object PluginToolSchema {
    private val KEYS = setOf("type", "title", "description", "properties", "required", "additionalProperties", "items", "enum",
        "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems")
    private val TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")

    fun validate(schema: JsonObject): Boolean = try {
        var nodes = 0
        fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun visit(value: JsonObject, depth: Int) {
            require(depth <= 8 && ++nodes <= 256 && value.keys.all { it in KEYS })
            val type = text(value["type"])
            require(type in TYPES)
            for (key in listOf("title", "description")) value[key]?.let { require(text(it)?.length?.let { length -> length <= 4096 } == true) }
            val properties = value["properties"]?.let { it as? JsonObject ?: error("properties") }
            if (properties != null) {
                require(type == "object" && properties.size <= 128)
                properties.forEach { (key, child) ->
                    require(key.isNotBlank() && key.length <= 128 && key.none { it.isISOControl() })
                    visit(child as? JsonObject ?: error("property"), depth + 1)
                }
            }
            value["required"]?.let { raw ->
                require(type == "object")
                val required = raw as? JsonArray ?: error("required")
                val names = required.map { text(it) ?: error("required name") }
                require(names.size <= 128 && names.distinct().size == names.size && names.all { properties?.containsKey(it) == true })
            }
            value["additionalProperties"]?.let { require(type == "object" && (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull != null) }
            value["items"]?.let { require(type == "array"); visit(it as? JsonObject ?: error("items"), depth + 1) }
            if (type == "array") require(value["items"] is JsonObject)
            value["enum"]?.let { raw ->
                val array = raw as? JsonArray ?: error("enum")
                require(array.isNotEmpty() && array.size <= 128 && array.distinct().size == array.size)
                array.forEach { element ->
                    val primitive = element as? JsonPrimitive ?: error("enum literal")
                    require(primitive.content.length <= 4096)
                    require(when (type) {
                        "string" -> primitive.isString
                        "null" -> primitive == JsonNull
                        "boolean" -> !primitive.isString && primitive.booleanOrNull != null
                        "integer" -> !primitive.isString && primitive.content.toLongOrNull() != null
                        "number" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                        else -> false
                    })
                }
            }
            for ((lower, upper, expected, limit) in listOf(
                listOf("minLength", "maxLength", "string", "65536"), listOf("minItems", "maxItems", "array", "1024")
            )) {
                fun bound(key: String): Int? = value[key]?.let { raw ->
                    require(type == expected)
                    val number = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: error("bound")
                    require(number in 0..limit.toInt())
                    number
                }
                val min = bound(lower)
                val max = bound(upper)
                require(min == null || max == null || min <= max)
            }
            val minimum = value["minimum"]?.let { raw ->
                require(type == "number" || type == "integer")
                (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.also { require(it.isFinite()) } ?: error("minimum")
            }
            val maximum = value["maximum"]?.let { raw ->
                require(type == "number" || type == "integer")
                (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.also { require(it.isFinite()) } ?: error("maximum")
            }
            require(minimum == null || maximum == null || minimum <= maximum)
        }
        require((schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object")
        visit(schema, 0)
        true
    } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
}
