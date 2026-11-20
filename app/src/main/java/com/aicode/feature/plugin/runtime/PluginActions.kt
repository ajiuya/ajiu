package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginCapture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Singleton
class PluginActions @Inject constructor(
    private val gateway: PluginHostGateway,
    private val runtime: PluginRuntimeManager
) {
    suspend fun invoke(
        capture: PluginCapture,
        handler: String,
        args: JsonObject,
        context: PluginInvocationContext? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        if (context != null && context.capture !== capture) throw PluginHostException("CONTEXT_CHANGED", "Plugin capture changed")
        val invocation = context?.child() ?: gateway.captureContext(capture)
        invocation.checkIntegrity()
        if (handler.isEmpty() || capture.content.actions.none { it.handler == handler }) {
            throw PluginHostException("HANDLER_NOT_FOUND", "The plugin action was not declared")
        }
        val process = capture.content.runtime ?: throw PluginHostException("RUNTIME_REQUIRED", "No plugin runtime declared")
        try {
            invocation.whileCurrent {
                runtime.invoke(capture, process, handler, args, invocation.runtimeContext(),
                    { name, params -> gateway.call(invocation, name, params) }, method = "ui.invoke")
            }
        } finally { invocation.close() }
    }

    suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement {
        if (method != "plugin.invoke") return gateway.call(context, method, params)
        context.checkIntegrity()
        val handler = (params["handler"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw PluginHostException("PARAM_INVALID", "Expected an action handler")
        val args = if ("args" !in params) JsonObject(emptyMap()) else params["args"] as? JsonObject
            ?: throw PluginHostException("PARAM_INVALID", "Expected an action argument object")
        if (!handler.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")) || args.toString().length > PluginHookTransform.MAX_JSON_CHARS) {
            throw PluginHostException("PARAM_INVALID", "Invalid plugin handler invocation")
        }
        val process = context.capture.content.runtime ?: throw PluginHostException("RUNTIME_REQUIRED", "No plugin runtime declared")
        val invocation = context.child()
        return try {
            invocation.whileCurrent {
                runtime.invoke(context.capture, process, handler, args, invocation.runtimeContext(),
                    { name, parameters -> gateway.call(invocation, name, parameters) }, method = "ui.invoke")
            }
        } finally { invocation.close() }
    }
}
