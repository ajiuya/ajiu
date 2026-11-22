package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.mcp.McpManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginConversationApi @Inject constructor(
    private val models: PluginModelService,
    private val chat: PluginChatService,
    private val providers: PluginProviderService,
    private val sessions: PluginSessionHostService,
    private val mcp: McpManager
) : PluginHostApi {
    override val methods: Set<String> = setOf(
        "model.listProviders", "model.listModels", "model.create", "model.start", "model.read", "model.cancel", "model.close",
        "chat.listSessions", "chat.createSession", "chat.listMessages", "chat.sendMessage", "chat.setDraft", "chat.stop",
        "chat.openSession", "chat.getSession", "chat.renameSession", "chat.pinSession", "chat.deleteSession", "chat.getRunState",
        "chat.waitForRun", "chat.listApprovals", "providers.listModels", "providers.addModel", "providers.deleteModel",
        "providers.setModelContext", "providers.setApiKey", "sessionSource.setSessions", "sessionSource.setLoading",
        "sessionSource.setError", "sessionSource.setThinking", "sessionSource.setMessages", "sessionSource.pushEvent", "mcp.status"
    )

    override suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement = context.whileCurrent {
        when (method) {
            "model.listProviders" -> { PluginConversationJson.keys(params, emptySet()); models.listProviders(context) }
            "model.listModels" -> models.listModels(context, params)
            "model.create" -> models.create(context, params)
            "model.start" -> models.start(context, params)
            "model.read" -> models.read(context, params)
            "model.cancel" -> models.cancel(context, params)
            "model.close" -> models.close(context, params)
            "chat.listSessions" -> chat.listSessions(context, params)
            "chat.createSession" -> chat.createSession(context, params)
            "chat.listMessages" -> chat.listMessages(context, params)
            "chat.sendMessage" -> chat.sendMessage(context, params)
            "chat.setDraft" -> chat.setDraft(context, params)
            "chat.stop" -> chat.stop(context, params)
            "chat.openSession" -> chat.openSession(context, params)
            "chat.getSession" -> chat.getSession(context, params)
            "chat.renameSession" -> chat.renameSession(context, params)
            "chat.pinSession" -> chat.pinSession(context, params)
            "chat.deleteSession" -> chat.deleteSession(context, params)
            "chat.getRunState" -> chat.getRunState(context, params)
            "chat.waitForRun" -> chat.waitForRun(context, params)
            "chat.listApprovals" -> chat.listApprovals(context, params)
            "providers.listModels" -> providers.listModels(context, params)
            "providers.addModel" -> providers.addModel(context, params)
            "providers.deleteModel" -> providers.deleteModel(context, params)
            "providers.setModelContext" -> providers.setModelContext(context, params)
            "providers.setApiKey" -> providers.setApiKey(context, params)
            "sessionSource.setSessions" -> sessions.setSessions(context, params)
            "sessionSource.setLoading" -> sessions.setLoading(context, params)
            "sessionSource.setError" -> sessions.setError(context, params)
            "sessionSource.setThinking" -> sessions.setThinking(context, params)
            "sessionSource.setMessages" -> sessions.setMessages(context, params)
            "sessionSource.pushEvent" -> sessions.pushEvent(context, params)
            "mcp.status" -> {
                PluginConversationJson.keys(params, emptySet())
                context.checkIntegrity()
                val names = context.capture.content.mcp.mapTo(hashSetOf()) { it.config.name }
                buildJsonObject { put("servers", JsonArray(mcp.statuses.value.filter { it.name in names }.map { status ->
                    buildJsonObject { put("name", status.name); put("state", status.state.name.lowercase()); put("toolCount", status.toolCount) }
                })) }
            }
            else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Conversation API method is not supported")
        }
    }
}
