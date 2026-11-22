package com.aicode.feature.plugin.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal object PluginConversationJson {
    fun invalid(message: String): Nothing = throw PluginHostException("PARAM_INVALID", message)

    fun string(params: JsonObject, key: String, max: Int = 200, empty: Boolean = false): String =
        optionalString(params, key, max)?.takeIf { empty || it.isNotBlank() } ?: invalid("$key is required")

    fun optionalString(params: JsonObject, key: String, max: Int = 200): String? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= max && '\u0000' !in it.content }
            ?.content ?: invalid("$key must be a string of at most $max characters")
    }

    fun boolean(params: JsonObject, key: String): Boolean? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid("$key must be a boolean")
    }

    fun long(params: JsonObject, key: String): Long? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid("$key must be an integer")
    }

    fun int(params: JsonObject, key: String, default: Int, min: Int, max: Int): Int =
        (long(params, key) ?: default.toLong()).takeIf { it in min.toLong()..max.toLong() }?.toInt()
            ?: invalid("$key must be in $min..$max")

    fun number(params: JsonObject, key: String): Double? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            ?: invalid("$key must be a finite number")
    }

    fun keys(params: JsonObject, allowed: Set<String>) {
        if (params.keys.any { it !in allowed }) invalid("Unknown parameters")
    }

    fun objectValue(raw: JsonElement?, name: String): JsonObject = raw as? JsonObject ?: invalid("$name must be an object")
}
