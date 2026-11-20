package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginCapture
import kotlinx.coroutines.flow.Flow

internal class PluginRuntimeOwner(context: PluginInvocationContext) {
    val capture: PluginCapture = context.capture
    val sourceIdentity: Any = context.sourceIdentity
    val workspacePath: String = context.workspacePath
    val sessionId: String? = context.sessionId
    val changes: Flow<Unit> = context.changes
    val background: Boolean = context.background
    private val checkSource = context::checkSourceCurrent

    fun checkCurrent() {
        capture.checkActive()
        checkSource()
    }

    fun requireCaller(context: PluginInvocationContext) {
        context.checkCurrent()
        checkCurrent()
        requireCapture(context)
        if (sourceIdentity != context.sourceIdentity || workspacePath != context.workspacePath || sessionId != context.sessionId) {
            throw PluginHostException("RESOURCE_NOT_FOUND", "Resource does not belong to this invocation source")
        }
    }

    fun requireCapture(context: PluginInvocationContext) {
        context.checkCurrent()
        checkCurrent()
        if (capture.id != context.capture.id || capture.installRevision != context.capture.installRevision ||
            capture.contentHash != context.capture.contentHash || capture.activationEpoch != context.capture.activationEpoch) {
            throw PluginHostException("RESOURCE_NOT_FOUND", "Resource does not belong to this plugin revision")
        }
    }
}
