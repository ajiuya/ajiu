package com.aicode.feature.plugin.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface PluginHostApi {
    val methods: Set<String>
    suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement
}
