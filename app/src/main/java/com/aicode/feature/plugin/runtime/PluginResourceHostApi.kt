package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.permission.ShellCommandParser
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class PluginResourceHostApi @Inject constructor(
    private val gateway: Provider<PluginHostGateway>,
    private val terminals: PluginTerminalRunner,
    private val ports: PluginPortService
) : PluginHostApi {
    override val methods = setOf("terminal.run", "terminal.poll", "terminal.stop", "terminal.close", "terminal.write", "terminal.list",
        "ports.forward", "ports.close", "ports.list")

    override suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement {
        context.checkIntegrity()
        return when (method) {
            "terminal.run" -> {
                keys(params, "command", "title")
                val command = string(params, "command", PluginApprovalCoordinator.MAX_DETAILS)
                val title = params["title"]?.let { string(params, "title", 200) }
                commandAllowed(command)
                val args = buildJsonObject { put("action", "start"); put("command", command); put("workspace", context.workspacePath); title?.let { put("title", it) } }
                gateway.get().authorize(context, "terminal", args, PluginApprovalOperation.EXECUTE_COMMAND, args.toString())
                terminals.start(context, command, title)
            }
            "terminal.poll" -> {
                keys(params, "tabId", "offset")
                val offset = params["offset"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull?.takeIf { n -> n >= 0 } ?: invalid() } ?: 0L
                terminals.poll(context, string(params, "tabId", 100), offset)
            }
            "terminal.write" -> {
                keys(params, "tabId", "text", "appendNewline")
                val id = string(params, "tabId", 100)
                val text = string(params, "text", 4000)
                val newline = params["appendNewline"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: invalid() } ?: true
                commandAllowed(text)
                val args = buildJsonObject { put("action", "send"); put("tab_id", id); put("input", text); put("submit", newline); put("workspace", context.workspacePath) }
                gateway.get().authorize(context, "terminal", args, PluginApprovalOperation.EXECUTE_COMMAND, args.toString())
                ok(terminals.write(context, id, text, newline))
            }
            "terminal.stop", "terminal.close" -> {
                keys(params, "tabId")
                val id = string(params, "tabId", 100)
                ok(if (method == "terminal.stop") terminals.stop(context, id) else terminals.close(context, id))
            }
            "terminal.list" -> { keys(params); terminals.list(context) }
            "ports.forward" -> {
                keys(params, "port", "localPort", "host")
                val port = port(params, "port")
                val localPort = params["localPort"]?.let { port(params, "localPort") }
                val host = params["host"]?.let { string(params, "host", 255) } ?: "127.0.0.1"
                if (host !in setOf("127.0.0.1", "localhost", "::1")) throw PluginHostException("HOST_FORBIDDEN", "Only remote loopback port targets are permitted")
                val args = buildJsonObject { put("action", "portForward"); put("port", port); localPort?.let { put("localPort", it) }; put("host", "127.0.0.1"); put("workspace", context.workspacePath) }
                gateway.get().authorize(context, "terminal", args, PluginApprovalOperation.EXECUTE_TOOL, args.toString())
                ports.forward(context, port, localPort)
            }
            "ports.close" -> {
                keys(params, "id")
                buildJsonObject { put("closed", ports.close(context, string(params, "id", 100))) }
            }
            "ports.list" -> { keys(params); ports.list(context) }
            else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Resource method is unavailable")
        }
    }

    private fun keys(params: JsonObject, vararg allowed: String) {
        if (params.keys.any { it !in allowed }) invalid()
    }
    private fun string(params: JsonObject, key: String, limit: Int): String =
        (params[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= limit && '\u0000' !in it.content }?.content ?: invalid()
    private fun port(params: JsonObject, key: String): Int = (params[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..65535 } ?: invalid()
    private fun commandAllowed(command: String) {
        if (ShellCommandParser.requiresRootApproval(command)) throw PluginHostException("SYSTEM_DENIED", "Sensitive root operations require the user's independent approval")
    }
    private fun ok(value: Boolean) = buildJsonObject { put("ok", value) }
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid resource parameters")
}
