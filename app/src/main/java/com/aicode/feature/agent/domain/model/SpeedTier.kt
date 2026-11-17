package com.aicode.feature.agent.domain.model

import com.aicode.feature.settings.domain.model.ProviderType
import java.net.URI

enum class SpeedTier(val storageValue: String, val wireValue: String?) {
    STANDARD("off", null),
    FAST("fast", "priority"),
    ULTRAFAST("ultrafast", "ultrafast");

    companion object {
        fun fromStorageValue(value: String?): SpeedTier =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

object SpeedTierPolicy {
    fun effective(
        chosen: SpeedTier,
        providerType: ProviderType,
        baseUrl: String,
        providerId: String,
        useResponses: Boolean,
        model: String
    ): SpeedTier {
        if (chosen == SpeedTier.STANDARD || providerType != ProviderType.OPENAI) return SpeedTier.STANDARD
        val host = runCatching { URI(baseUrl).host }.getOrNull()
        val isXai = providerId == "xai" || host.equals("api.x.ai", ignoreCase = true)
        if (isXai) return SpeedTier.FAST
        if (!useResponses || !model.contains("gpt", ignoreCase = true)) return SpeedTier.STANDARD
        return if (chosen == SpeedTier.ULTRAFAST && !model.startsWith("gpt-6-astra", ignoreCase = true)) {
            SpeedTier.FAST
        } else {
            chosen
        }
    }
}
