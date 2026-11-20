package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.plugin.data.index.PluginIndexAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

private val Context.pluginStoreSettingsDataStore by preferencesDataStore(name = "plugin_store_settings", corruptionHandler = preferencesCorruptionHandler)

@Singleton
class PluginStoreSettingsRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    val downloadMirror = context.pluginStoreSettingsDataStore.data.map { it[KEY].orEmpty() }
    suspend fun setDownloadMirror(raw: String): Result<Unit> = runCatching {
        val value = raw.trim().trimEnd('/')
        require(value.isEmpty() || (PluginIndexAddress.isHttps(value) && java.net.URI(value).rawQuery == null)) { "HTTPS mirror required" }
        context.pluginStoreSettingsDataStore.edit { it[KEY] = value }
    }
    private companion object { val KEY = stringPreferencesKey("download_mirror") }
}
