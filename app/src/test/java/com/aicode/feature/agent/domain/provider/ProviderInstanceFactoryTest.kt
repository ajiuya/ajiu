package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderKey
import com.aicode.feature.settings.domain.model.ProviderType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderInstanceFactoryTest {
    private val metadata = mockk<ModelMetadataService>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val keys = ProviderKeyRotator()
    private val factory = ProviderInstanceFactory(mockk(), mockk(), mockk(), metadata, settings, keys)
    private val config = AIProviderConfig(id = "p", name = "Provider", type = ProviderType.OPENAI, apiKey = "one",
        baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model"),
        customHeaders = mapOf("X-Custom" to "value"), openaiChatCacheKey = false)

    private fun configure() {
        coEvery { metadata.resolve("p", any(), "model") } returns ModelMetadata("model", contextTokens = 64_000,
            outputTokens = 8192, supportsCustomTemperature = true, supportsReasoning = true, reasoningLevels = listOf("low", "high"))
        coEvery { settings.firstByteTimeoutMs() } returns 12_345
        coEvery { settings.streamIdleTimeoutMs() } returns 23_456
        coEvery { settings.maxNetworkRetries() } returns 4
        coEvery { settings.speedTier() } returns SpeedTier.STANDARD
    }

    @Test fun standaloneProviderRetainsHeadersMetadataTimeoutsAndDynamicSpeedSource() = runBlocking<Unit> {
        configure()
        val provider = factory.create(config, "session") as OpenAIAdapter
        assertEquals("one", provider.apiKey)
        assertEquals(config.customHeaders, provider.customHeaders)
        assertEquals(config.id, provider.providerId)
        assertEquals("session", provider.logSessionId)
        assertEquals(8192, provider.maxOutputTokens)
        assertEquals(12_345L, provider.firstByteTimeoutMs)
        assertEquals(23_456L, provider.streamIdleTimeoutMs)
        assertEquals(4, provider.maxNetworkRetries)
        assertNotNull(provider.reasoningCapability)
        assertFalse(provider.chatCacheKeyEnabled)
        assertNotNull(provider.speedTierSource)
        assertEquals(SpeedTier.STANDARD, provider.speedTierSource.invoke())
        assertNotSame(provider, factory.create(config, "session"))
    }

    @Test fun multikeyFailoverIsSharedStickyAndKeepsCoolingSemantics() = runBlocking<Unit> {
        configure()
        val multi = config.copy(multiKeyEnabled = true, apiKeys = listOf(ProviderKey("one"), ProviderKey("two")))
        val first = factory.create(multi, "session")
        val switched = first.keySwitcher!!.invoke(StreamApiException("insufficient_quota", "quota"), "one", setOf("one"))
        assertEquals("two", switched?.newKey)
        assertEquals("two", factory.create(multi, "session").apiKey)
        try {
            first.keySwitcher!!.invoke(StreamApiException("insufficient_quota", "quota"), "two", setOf("one", "two"))
            fail("Expected all keys failed")
        } catch (_: AllKeysFailedException) { }
    }

    @Test fun singleKeyNeverInstallsAnUnusableFailoverAndProviderTypesStayIndependent() = runBlocking<Unit> {
        configure()
        assertNull(factory.create(config, null).keySwitcher)
        val anthropic = factory.create(config.copy(type = ProviderType.ANTHROPIC, anthropicCacheBreakpoints = false), "a")
        val gemini = factory.create(config.copy(type = ProviderType.GEMINI), "g")
        assertTrue(anthropic is AnthropicAdapter)
        assertFalse((anthropic as AnthropicAdapter).cacheBreakpointsEnabled)
        assertTrue(gemini is GeminiAdapter)
        assertEquals(8192, gemini.maxOutputTokens)
    }
}
