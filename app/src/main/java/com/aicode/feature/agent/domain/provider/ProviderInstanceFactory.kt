package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderInstanceFactory @Inject constructor(
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keyRotator: ProviderKeyRotator
) {
    suspend fun create(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
                it.speedTierSource = generalSettingsRepository::speedTier
            }
        }
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        provider.keySwitcher = if (activeKeys.size > 1) {
            { error, failedKey, triedKeys ->
                if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                    null
                } else {
                    val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        ?: throw AllKeysFailedException(
                            "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}", error
                        )
                    KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                }
            }
        } else null
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }
}
