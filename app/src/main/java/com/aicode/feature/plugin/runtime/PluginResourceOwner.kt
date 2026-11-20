package com.aicode.feature.plugin.runtime

internal data class PluginResourceOwner(
    val pluginId: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long,
    val sourceIdentity: Any
) {
    companion object {
        fun of(context: PluginInvocationContext) = PluginResourceOwner(context.capture.id,
            context.capture.installRevision, context.capture.contentHash, context.capture.activationEpoch, context.sourceIdentity)
    }
}
