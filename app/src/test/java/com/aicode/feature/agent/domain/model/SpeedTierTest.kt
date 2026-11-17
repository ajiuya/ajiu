package com.aicode.feature.agent.domain.model

import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTierTest {
    private fun effective(
        tier: SpeedTier = SpeedTier.ULTRAFAST,
        type: ProviderType = ProviderType.OPENAI,
        url: String = "https://api.openai.com/",
        id: String = "openai",
        responses: Boolean = true,
        model: String = "gpt-6-astra"
    ) = SpeedTierPolicy.effective(tier, type, url, id, responses, model)

    @Test
    fun storageAndWireValuesAreIndependent() {
        assertEquals(listOf("off", "fast", "ultrafast"), SpeedTier.entries.map { it.storageValue })
        assertEquals(listOf(null, "priority", "ultrafast"), SpeedTier.entries.map { it.wireValue })
        SpeedTier.entries.forEach { assertEquals(it, SpeedTier.fromStorageValue(it.storageValue)) }
    }

    @Test
    fun unknownOrMissingStorageDefaultsToStandard() {
        listOf(null, "", "true", "FAST", "priority", "future").forEach {
            assertEquals(SpeedTier.STANDARD, SpeedTier.fromStorageValue(it))
        }
    }

    @Test
    fun standardAlwaysStaysStandard() {
        ProviderType.entries.forEach { type ->
            assertEquals(SpeedTier.STANDARD, effective(SpeedTier.STANDARD, type, id = "xai"))
        }
    }

    @Test
    fun ordinaryChatNeverUsesTier() {
        SpeedTier.entries.forEach {
            assertEquals(SpeedTier.STANDARD, effective(it, responses = false))
        }
    }

    @Test
    fun onlyGptResponsesAreEligibleOutsideXai() {
        listOf("o3", "qwen3", "claude-opus", "gemini-pro", "grok-4").forEach {
            assertEquals(SpeedTier.STANDARD, effective(model = it))
        }
        assertEquals(SpeedTier.FAST, effective(model = "vendor/GPT-5.6"))
    }

    @Test
    fun ultrafastIsLimitedToAstraPrefixAndDoesNotMutateChoice() {
        val chosen = SpeedTier.ULTRAFAST
        assertEquals(SpeedTier.ULTRAFAST, effective(chosen, model = "GPT-6-ASTRA-preview"))
        listOf("gpt-6", "gpt-5.6", "vendor/gpt-6-astra").forEach {
            assertEquals(SpeedTier.FAST, effective(chosen, model = it))
        }
        assertEquals(SpeedTier.ULTRAFAST, chosen)
        assertEquals(SpeedTier.FAST, effective(SpeedTier.FAST))
    }

    @Test
    fun xaiPresetOrExactHostUsePriorityOnBothProtocols() {
        listOf(false, true).forEach { responses ->
            listOf(SpeedTier.FAST, SpeedTier.ULTRAFAST).forEach { tier ->
                assertEquals(SpeedTier.FAST, effective(tier, id = "xai", responses = responses, model = "grok-4"))
                assertEquals(SpeedTier.FAST, effective(tier, url = "https://API.X.AI/v1/responses", id = "custom", responses = responses, model = "grok-4"))
            }
        }
    }

    @Test
    fun spoofedHostsNamesAndUnknownRelaysAreNotXai() {
        listOf(
            "https://api.x.ai.evil.test/v1",
            "https://evil.test/api.x.ai",
            "https://api.x.ai@evil.test/",
            "not a url",
            "https://relay.test/"
        ).forEach {
            assertEquals(SpeedTier.STANDARD, effective(url = it, id = "Grok", model = "grok-4"))
        }
    }

    @Test
    fun nonOpenaiProvidersNeverSendTierEvenWithXaiIdentity() {
        listOf(ProviderType.ANTHROPIC, ProviderType.GEMINI).forEach {
            assertEquals(SpeedTier.STANDARD, effective(type = it, id = "xai", url = "https://api.x.ai/"))
        }
    }
}
