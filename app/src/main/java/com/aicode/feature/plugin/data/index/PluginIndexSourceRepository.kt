package com.aicode.feature.plugin.data.index

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.pluginIndexDataStore by preferencesDataStore(name = "plugin_index_sources", corruptionHandler = preferencesCorruptionHandler)

data class PluginIndexSource(val id: String, val name: String, val indexUrl: String, val builtIn: Boolean, val lastFetchedAt: Long = 0) {
    val rootUrl: String get() = indexUrl.substringBeforeLast('/') + "/"
}

object PluginIndexDefaults {
    const val COMMUNITY_URL = "https://raw.githubusercontent.com/jieapi/AiCode-Plugins-Store/main/index.json"
    const val COMMUNITY_ID = "aicode.upstream.community"
    val community = PluginIndexSource(COMMUNITY_ID, "AiCode Plugins", COMMUNITY_URL, true)
}

@Serializable private data class StoredSource(val url: String, val lastFetchedAt: Long = 0)
@Serializable private data class StoredSources(val sources: List<StoredSource> = emptyList())

@Singleton
class PluginIndexSourceRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    val sources: Flow<List<PluginIndexSource>> = context.pluginIndexDataStore.data.map { preferences ->
        val stored = decode(preferences[KEY])
        val fetched = stored.sources.associate { it.url to it.lastFetchedAt }
        buildList {
            add(PluginIndexDefaults.community.copy(lastFetchedAt = fetched[PluginIndexDefaults.COMMUNITY_URL] ?: 0))
            stored.sources.filter { it.url != PluginIndexDefaults.COMMUNITY_URL }.forEach { item ->
                val url = runCatching { PluginIndexAddress.normalize(item.url) }.getOrNull() ?: return@forEach
                add(PluginIndexSource(sourceId(url), URI(url).host, url, false, item.lastFetchedAt))
            }
        }.distinctBy { it.id }
    }
    val customIndexUrls: Flow<List<String>> = sources.map { items -> items.filterNot { it.builtIn }.map { it.indexUrl } }

    suspend fun setCustomSources(lines: List<String>): Result<Unit> = runCatching {
        require(lines.size <= 32) { "source limit" }
        val next = lines.filter { it.isNotBlank() }.map(PluginIndexAddress::normalize).distinct().filter { it != PluginIndexDefaults.COMMUNITY_URL }
        context.pluginIndexDataStore.edit { preferences ->
            val fetched = decode(preferences[KEY]).sources.associate { it.url to it.lastFetchedAt }
            preferences[KEY] = PluginIndexJson.encodeToString(StoredSources.serializer(), StoredSources(
                (listOf(PluginIndexDefaults.COMMUNITY_URL) + next).map { StoredSource(it, fetched[it] ?: 0) }
            ))
        }
    }

    suspend fun markFetched(id: String, at: Long) {
        val source = sources.first().firstOrNull { it.id == id } ?: return
        context.pluginIndexDataStore.edit { preferences ->
            val current = decode(preferences[KEY]).sources
            val next = if (current.any { it.url == source.indexUrl }) current.map { if (it.url == source.indexUrl) it.copy(lastFetchedAt = at) else it }
                else current + StoredSource(source.indexUrl, at)
            preferences[KEY] = PluginIndexJson.encodeToString(StoredSources.serializer(), StoredSources(next))
        }
    }

    private fun decode(raw: String?): StoredSources = raw?.let { runCatching { PluginIndexJson.decodeFromString(StoredSources.serializer(), it) }.getOrNull() } ?: StoredSources()
    companion object {
        private val KEY = stringPreferencesKey("sources_json")
        fun sourceId(url: String): String = MessageDigest.getInstance("SHA-256").digest(PluginIndexAddress.normalize(url).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)
    }
}
