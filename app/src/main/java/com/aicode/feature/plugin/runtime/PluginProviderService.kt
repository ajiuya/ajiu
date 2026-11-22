package com.aicode.feature.plugin.runtime

import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginProviderService @Inject constructor(
    private val providers: AIProviderRepository,
    private val registrar: PluginProviderRegistrar,
    private val metadata: ModelMetadataService,
    private val customMetadata: CustomModelMetadataStore,
    private val defaults: DefaultModelSettingsRepository,
    private val vision: VisionModelSettingsRepository,
    private val image: ImageGenModelSettingsRepository,
    private val compaction: CompactionModelSettingsRepository,
    private val titles: TitleModelSettingsRepository,
    private val tiers: SubagentTierSettingsRepository,
    private val gateway: Provider<PluginHostGateway>
) {
    private val mutations = Mutex()

    suspend fun listModels(context: PluginInvocationContext, params: JsonObject): JsonElement {
        PluginConversationJson.keys(params, setOf("providerId"))
        return modelsJson(requireOwned(context, params))
    }

    suspend fun addModel(context: PluginInvocationContext, params: JsonObject): JsonElement = mutations.withLock {
        PluginConversationJson.keys(params, setOf("providerId", "model", "contextTokens"))
        val config = requireOwned(context, params)
        val model = PluginConversationJson.string(params, "model")
        val tokens = contextTokens(params)
        if (config.models.size >= 500 && model !in config.models) throw PluginHostException("MODEL_LIMIT", "Too many provider models")
        approve(context, "providers.addModel", params, "Add model ${JsonPrimitive(model)} to ${JsonPrimitive(config.id)}")
        val current = requireOwned(context, params)
        val models = (current.models + model).distinct()
        if (models.size > 500) throw PluginHostException("MODEL_LIMIT", "Too many provider models")
        providers.updateModels(config.id, models)
        val selected = current.selectedModel.ifBlank { model }
        if (selected != current.selectedModel) providers.setSelectedModel(config.id, selected)
        tokens?.let { setContext(context, current, model, it) }
        context.checkCurrent()
        modelsJson(current.copy(models = models, selectedModel = selected))
    }

    suspend fun deleteModel(context: PluginInvocationContext, params: JsonObject): JsonElement = mutations.withLock {
        PluginConversationJson.keys(params, setOf("providerId", "model"))
        val config = requireOwned(context, params)
        val model = PluginConversationJson.string(params, "model")
        approve(context, "providers.deleteModel", params,
            "Delete model ${JsonPrimitive(model)} from ${JsonPrimitive(config.id)} and clear its model selection bindings")
        val current = requireOwned(context, params)
        val models = current.models.filterNot { it == model }
        providers.updateModels(config.id, models)
        val selected = if (current.selectedModel == model) models.firstOrNull().orEmpty() else current.selectedModel
        if (selected != current.selectedModel) providers.setSelectedModel(config.id, selected)
        customMetadata.remove(config.id, model)
        clearBindings(config.id, model)
        context.checkCurrent()
        buildJsonObject { put("providerId", config.id); put("model", model); put("removed", model in current.models)
            put("models", JsonArray(models.map(::JsonPrimitive))); put("selectedModel", selected) }
    }

    suspend fun setModelContext(context: PluginInvocationContext, params: JsonObject): JsonElement = mutations.withLock {
        PluginConversationJson.keys(params, setOf("providerId", "model", "contextTokens"))
        val config = requireOwned(context, params)
        val model = PluginConversationJson.string(params, "model")
        if (model !in config.models) throw PluginHostException("MODEL_NOT_AVAILABLE", "Model is not configured")
        val tokens = contextTokens(params) ?: PluginConversationJson.invalid("contextTokens is required")
        approve(context, "providers.setModelContext", params, "Change model context budget\n$params")
        val current = requireOwned(context, params)
        if (model !in current.models) throw PluginHostException("MODEL_NOT_AVAILABLE", "Model is no longer configured")
        setContext(context, current, model, tokens)
        buildJsonObject { put("providerId", config.id); put("model", model); put("contextTokens", tokens) }
    }

    suspend fun setApiKey(context: PluginInvocationContext, params: JsonObject): JsonElement = mutations.withLock {
        PluginConversationJson.keys(params, setOf("providerId", "apiKey"))
        val config = requireOwned(context, params)
        val key = PluginConversationJson.string(params, "apiKey", 16 * 1024, empty = true)
        if (key.toByteArray(Charsets.UTF_8).size > 16 * 1024 || key.any { it.isWhitespace() }) PluginConversationJson.invalid("Invalid API key")
        val redacted = buildJsonObject { put("providerId", config.id); put("hasKey", key.isNotEmpty()) }
        val approvalArgs = JsonObject(redacted + ("keySha256" to JsonPrimitive(
            com.aicode.feature.plugin.data.PluginFiles.sha256(key.toByteArray(Charsets.UTF_8)))))
        approve(context, "providers.setApiKey", approvalArgs, "Replace provider API key (write-only)\n$redacted")
        requireOwned(context, params)
        providers.setProviderApiKey(config.id, key)
        context.checkCurrent()
        buildJsonObject { put("providerId", config.id); put("hasKey", key.isNotEmpty()) }
    }

    private suspend fun requireOwned(context: PluginInvocationContext, params: JsonObject): AIProviderConfig {
        context.checkIntegrity()
        val raw = PluginConversationJson.string(params, "providerId")
        val local = if (PluginProviderIds.isPluginId(raw)) {
            if (PluginProviderIds.pluginIdOf(raw) != context.capture.id) notFound()
            PluginProviderIds.localIdOf(raw) ?: notFound()
        } else raw
        if (context.capture.content.providers.none { it.localId == local }) notFound()
        registrar.sync(context.capture.id)
        context.checkCurrent()
        return providers.getProviderById(PluginProviderIds.idOf(context.capture.id, local))?.also { context.checkCurrent() } ?: notFound()
    }

    private suspend fun approve(context: PluginInvocationContext, method: String, params: JsonObject, details: String) {
        gateway.get().authorize(context, method, params, PluginApprovalOperation.EXECUTE_TOOL, details)
        context.checkIntegrity()
    }

    private suspend fun setContext(context: PluginInvocationContext, config: AIProviderConfig, model: String, tokens: Int) {
        val current = metadata.resolve(config.id, config.type, model)
        context.checkCurrent()
        customMetadata.put(config.id, model, current.copy(contextTokens = tokens))
    }

    private fun contextTokens(params: JsonObject): Int? = if (params["contextTokens"] == null) null else
        PluginConversationJson.int(params, "contextTokens", 1, 1, 10_000_000)

    private fun modelsJson(config: AIProviderConfig) = buildJsonObject {
        put("providerId", config.id); put("models", JsonArray(config.models.map(::JsonPrimitive))); put("selectedModel", config.selectedModel)
    }

    private suspend fun clearBindings(id: String, model: String) {
        if (defaults.getDefaultProviderId() == id && defaults.getDefaultModel() == model) defaults.clear()
        if (vision.getVisionProviderId() == id && vision.getVisionModel() == model) vision.clear()
        if (image.getImageGenProviderId() == id && image.getImageGenModel() == model) image.clear()
        if (compaction.getCompactionProviderId() == id && compaction.getCompactionModel() == model) compaction.clear()
        if (titles.getTitleProviderId() == id && titles.getTitleModel() == model) titles.clear()
        tiers.snapshot().filterValues { it.providerId == id && it.model == model }.keys.forEach { tiers.clear(it) }
    }

    private fun notFound(): Nothing = throw PluginHostException("PROVIDER_NOT_FOUND", "Provider is not declared by this plugin")
}
