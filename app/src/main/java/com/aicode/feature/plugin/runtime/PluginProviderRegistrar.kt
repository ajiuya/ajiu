package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PluginProviderRegistrar @Inject constructor(
    private val plugins: PluginManager,
    private val providers: AIProviderRepository
) {
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            plugins.ensureLoaded()
            plugins.snapshot.collectLatest { syncAll() }
        }
    }

    suspend fun syncAll() = mutex.withLock {
        plugins.ensureLoaded()
        for (contribution in plugins.providers()) {
            val capture = contribution.capture
            val definition = contribution.value
            capture.checkCurrent()
            val id = PluginProviderIds.idOf(capture.id, definition.localId)
            if (providers.getProviderById(id) != null) continue
            // Existing user credentials and endpoints must never be rewritten by a new package revision.
            providers.saveProvider(AIProviderConfig(
                id = id,
                name = definition.name.resolve(Locale.getDefault().toLanguageTag()),
                type = ProviderType.OPENAI,
                apiKey = PLACEHOLDER_API_KEY,
                baseUrl = definition.baseUrl,
                defaultModel = "",
                customHeaders = mapOf(SESSION_HEADER_NAME to SESSION_HEADER_VALUE)
            ))
            try { capture.checkCurrent() } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    suspend fun sync(pluginId: String) {
        if (plugins.snapshot.value.plugins.any { it.record.id == pluginId }) syncAll()
    }

    companion object {
        const val PLACEHOLDER_API_KEY = "plugin"
        const val SESSION_HEADER_NAME = "X-AiCode-Session-Id"
        const val SESSION_HEADER_VALUE = "{{SESSION_ID}}"
    }
}
