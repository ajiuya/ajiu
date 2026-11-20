package com.aicode.feature.plugin.runtime

import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PluginProviderDaoFixture : AIProviderDao, AutoCloseable {
    val database = mockk<AgentDatabase>()
    val writes = mutableListOf<String>()
    private val lock = Any()
    private val rows = linkedMapOf<String, AIProviderEntity>()
    private val state = MutableStateFlow<List<AIProviderEntity>>(emptyList())
    private val transaction = Mutex()
    private var transactionActive = false

    init {
        every { database.aiProviderDao() } returns this
        every { database.close() } returns Unit
        // Room owns the native boundary; its repository transaction lambda still executes unchanged.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            val block = arg<suspend () -> Unit>(1)
            transaction.withLock {
                val previous = synchronized(lock) { transactionActive = true; rows.toMap() }
                try { block() }
                catch (failure: Throwable) {
                    synchronized(lock) { rows.clear(); rows.putAll(previous) }
                    throw failure
                } finally {
                    synchronized(lock) { transactionActive = false; publish() }
                }
            }
        }
    }

    override fun getAllProviders(): Flow<List<AIProviderEntity>> = state
    override suspend fun getAllProvidersOnce(): List<AIProviderEntity> = synchronized(lock) { sortedRows() }
    override suspend fun getMaxSortOrder(): Int = synchronized(lock) { rows.values.maxOfOrNull { it.sortOrder } ?: -1 }
    override suspend fun getProviderById(id: String): AIProviderEntity? = synchronized(lock) { rows[id] }

    override suspend fun insertAllProviders(providers: List<AIProviderEntity>) = mutate("insertAllProviders") {
        providers.forEach { rows[it.id] = it }
    }

    override suspend fun insertProvider(provider: AIProviderEntity) = mutate("insertProvider") { rows[provider.id] = provider }
    override suspend fun updateProvider(provider: AIProviderEntity) = mutate("updateProvider") {
        if (provider.id in rows) rows[provider.id] = provider
    }

    override suspend fun updateSortOrder(id: String, sortOrder: Int) = mutate("updateSortOrder") {
        rows[id]?.let { rows[id] = it.copy(sortOrder = sortOrder) }
    }

    override suspend fun deleteProvider(id: String) = mutate("deleteProvider") { rows.remove(id); Unit }
    override suspend fun deleteProviders(ids: Collection<String>) = mutate("deleteProviders") { ids.forEach(rows::remove) }
    override suspend fun setSelectedModel(id: String, model: String) = mutate("setSelectedModel") {
        rows[id]?.let { rows[id] = it.copy(selectedModel = model) }
    }

    override suspend fun setModels(id: String, models: String) = mutate("setModels") {
        rows[id]?.let { rows[id] = it.copy(models = models) }
    }

    override suspend fun setProviderApiKey(id: String, encryptedApiKey: String, encryptedEmptyKeys: String) = mutate("setProviderApiKey") {
        rows[id]?.let { rows[id] = it.copy(apiKey = encryptedApiKey, multiKeyEnabled = false, apiKeys = encryptedEmptyKeys) }
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) = mutate("setProviderEnabled") {
        rows[id]?.let { rows[id] = it.copy(isEnabled = isEnabled) }
    }

    override suspend fun setProvidersEnabled(ids: Collection<String>, isEnabled: Boolean) = mutate("setProvidersEnabled") {
        ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(isEnabled = isEnabled) } }
    }

    private fun mutate(name: String, change: () -> Unit) = synchronized(lock) {
        writes += name
        change()
        if (!transactionActive) publish()
    }

    private fun sortedRows() = rows.values.sortedWith(compareBy<AIProviderEntity> { it.sortOrder }.thenBy { it.id })
    private fun publish() { state.value = sortedRows() }
    override fun close() { unmockkStatic("androidx.room.RoomDatabaseKt") }
}
