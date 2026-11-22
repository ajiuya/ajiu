package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.pluginOverlayPositions by preferencesDataStore(name = "plugin_overlay_positions", corruptionHandler = preferencesCorruptionHandler)
@Serializable data class PluginOverlayPosition(val x: Int, val y: Int)

@Singleton
class PluginOverlayPositionRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PluginOverlayPosition.serializer())
    suspend fun get(pluginId: String, surfaceId: String): PluginOverlayPosition? = decode(context.pluginOverlayPositions.data.first()[KEY])["$pluginId:$surfaceId"]
    suspend fun save(pluginId: String, surfaceId: String, x: Int, y: Int) {
        context.pluginOverlayPositions.edit { preferences ->
            val next = decode(preferences[KEY]).toMutableMap()
            next["$pluginId:$surfaceId"] = PluginOverlayPosition(x, y)
            preferences[KEY] = json.encodeToString(serializer, next.entries.toList().takeLast(256).associate { it.toPair() })
        }
    }
    private fun decode(raw: String?): Map<String, PluginOverlayPosition> = if (raw.isNullOrBlank()) emptyMap() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    private companion object { val KEY = stringPreferencesKey("positions") }
}
