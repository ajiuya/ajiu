package com.aicode.feature.plugin.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

data class PluginSessionEvent internal constructor(
    val sessionId: String,
    val payload: JsonObject,
    val pluginId: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long,
    val sourceIdentity: Any,
    private val check: () -> Unit
) { fun checkCurrent() = check() }

@Singleton
class PluginSessionEventBus @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<PluginSessionEvent>(extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND)
    val events = mutableEvents.asSharedFlow()

    internal suspend fun publish(context: PluginInvocationContext, id: String, payload: JsonObject) {
        context.checkCurrent()
        val capture = context.capture
        val event = PluginSessionEvent(id, payload, capture.id, capture.installRevision, capture.contentHash,
            capture.activationEpoch, context.sourceIdentity, context::checkSourceCurrent)
        if (withTimeoutOrNull(5_000) { mutableEvents.emit(event); true } != true) {
            throw PluginHostException("SESSION_EVENT_BUSY", "Session event consumers are busy")
        }
        context.checkCurrent()
    }
}
