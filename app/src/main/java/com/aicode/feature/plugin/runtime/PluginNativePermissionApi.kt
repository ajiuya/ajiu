package com.aicode.feature.plugin.runtime

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class PluginNativePermissionApi @Inject constructor(
    private val gateway: Provider<PluginHostGateway>,
    private val approvals: PluginApprovalCoordinator
) : PluginHostApi {
    override val methods = setOf("permissions.request", "permissions.cancel", "tools.pending")
    private data class Identity(val pluginId: String, val revision: String, val epoch: Long, val source: Any, val requestId: String)
    private val pending = ConcurrentHashMap<Identity, CompletableDeferred<Unit>>()

    override suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement {
        context.checkIntegrity()
        if (method == "tools.pending") {
            val request = approvals.pendingFor(context.capture.id, context.sourceIdentity)
            return buildJsonObject { put("approval", request?.let { buildJsonObject {
                put("id", it.id); put("toolName", it.operation.name); put("details", it.details)
            } } ?: JsonNull) }
        }
        val requestId = text(params, "requestId", 256)
        val identity = Identity(context.capture.id, context.capture.installRevision, context.capture.activationEpoch,
            context.sourceIdentity, requestId)
        if (method == "permissions.cancel") return buildJsonObject { put("cancelled", pending[identity]?.complete(Unit) ?: false) }
        val session = text(params, "sessionId", 1024)
        if (context.sessionId != session) throw PluginHostException("CONTEXT_CHANGED", "Permission request must belong to its originating session")
        val title = text(params, "title", 200)
        val description = text(params, "description", 4000)
        val operation = params["operation"] as? JsonObject ?: invalid()
        val kind = text(operation, "kind", 100)
        if (kind in setOf("Root", "submitPlan", "planMode", "askUserQuestion", "Shizuku")) {
            throw PluginHostException("SYSTEM_PROTECTED", "This operation requires the host's dedicated native authorization")
        }
        val limit = (params["timeoutMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?.takeIf { it in 1000..600000 } ?: if ("timeoutMs" in params) invalid() else 120000L
        val cancellation = CompletableDeferred<Unit>()
        if (pending.size >= 32) throw PluginHostException("BUSY", "Too many permission requests")
        if (pending.putIfAbsent(identity, cancellation) != null) throw PluginHostException("REQUEST_EXISTS", "Permission request is already pending")
        return try {
            val result = withTimeoutOrNull(limit) { coroutineScope {
                val decision = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        gateway.get().authorize(context, "pluginPermission", operation, PluginApprovalOperation.EXECUTE_TOOL,
                            "$title\n$description\n$operation")
                        "allow"
                    } catch (rejected: PluginHostException) {
                        if (rejected.code == "USER_REJECTED") "deny" else throw rejected
                    }
                }
                try { select { decision.onAwait { it }; cancellation.onAwait { "cancel" } } }
                finally { decision.cancelAndJoin() }
            } }
            context.checkIntegrity()
            buildJsonObject { put("decision", result ?: "cancel"); if (result == null) put("reason", "timeout") }
        } catch (stale: CancellationException) {
            throw stale
        } finally { pending.remove(identity, cancellation) }
    }

    private fun text(values: JsonObject, name: String, maximum: Int): String = (values[name] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.length in 1..maximum && '\u0000' !in it.content }?.content ?: invalid()
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid permission request")
}
