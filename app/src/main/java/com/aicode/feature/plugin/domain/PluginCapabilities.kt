package com.aicode.feature.plugin.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PluginInstallOriginKind {
    @SerialName("local") LOCAL,
    @SerialName("index") INDEX,
    @SerialName("development") DEVELOPMENT
}

@Serializable
data class PluginInstallOrigin(
    val kind: PluginInstallOriginKind = PluginInstallOriginKind.LOCAL,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val indexUrl: String? = null,
    val repository: String? = null,
    val officialUpstream: Boolean = false
)

@Serializable
enum class PluginCapability { MODEL_ACCESS, SHIZUKU, OVERLAY, BACKUP, HTTP_TRANSFORM, AUTOSTART }

@Serializable
data class PluginCapabilityGrant(
    val token: String,
    val pluginId: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long,
    val capability: PluginCapability,
    val purpose: String,
    val source: PluginInstallOrigin,
    val grantedAt: Long
) { val key: String get() = "$pluginId:${capability.name}" }

data class PluginNativeCapabilityChallenge internal constructor(
    val nativeToken: String,
    val pluginId: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long,
    val capability: PluginCapability,
    val purpose: String,
    val source: PluginInstallOrigin,
    val expiresAt: Long
)

interface PluginCapabilityAccess {
    val grants: StateFlow<Map<String, PluginCapabilityGrant>>
    suspend fun ensureLoaded()
    suspend fun prepareGrant(capture: PluginCapture, capability: PluginCapability): PluginNativeCapabilityChallenge?
    suspend fun confirmGrant(nativeToken: String): PluginOperationResult
    suspend fun revoke(pluginId: String, capability: PluginCapability? = null): PluginOperationResult
    fun checkGranted(capture: PluginCapture, capability: PluginCapability): Boolean
}

fun PluginManifest.capabilityPurpose(capability: PluginCapability): String? = when (capability) {
    PluginCapability.MODEL_ACCESS -> modelAccess?.purpose
    PluginCapability.SHIZUKU -> shizuku?.purpose
    PluginCapability.OVERLAY -> overlay?.purpose
    PluginCapability.BACKUP -> backup?.purpose
    PluginCapability.HTTP_TRANSFORM -> permissions[capability] ?: hooks.firstOrNull {
        it.event == PluginHookEvents.AGENT_HTTP_BEFORE && it.mode == PluginHookModes.TRANSFORM
    }?.let { "${it.event}: ${it.handler}" }
    PluginCapability.AUTOSTART -> permissions[capability] ?: hooks.firstOrNull {
        it.event == PluginHookEvents.APP_STARTED
    }?.let { "${it.event}: ${it.handler}" }
}
