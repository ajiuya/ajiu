package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginTool
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal class PluginAgentTool(
    private val app: Context,
    val source: PluginContribution<PluginTool>,
    private val gateway: PluginHostGateway,
    private val runtime: PluginRuntimeManager
) : AgentTool() {
    private val capture = source.capture
    private val tool = source.value
    override val name = toolName(capture.id, tool.localId)
    override val description = Locale.getDefault().toLanguageTag().let { locale ->
        tool.description.resolve(locale).ifBlank { tool.title.resolve(locale) }
    }
    override val parameters = emptyMap<String, ToolParameter>()
    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
    override val permissionPolicy = ToolPermissionPolicy.ASK

    override fun toJsonSchema(): Map<String, Any> = tool.inputSchema.mapValues { requireNotNull(schemaValue(it.value)) }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
        ToolResult.Error(app.getString(R.string.plugin_error_context), "CONTEXT_REQUIRED")

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject(args.toMap())
            if (!acceptsArguments(tool.inputSchema, payload)) throw PluginHostException("PARAM_INVALID", "Invalid plugin tool arguments")
            val invocation = gateway.captureContext(capture, context.sessionId, context.projectRoot, context)
            try { invocation.whileCurrent {
                gateway.authorize(invocation, name, payload, PluginApprovalOperation.EXECUTE_TOOL, "${tool.key}\n$payload")
                invocation.checkIntegrity()
                gateway.checkPolicy(invocation, name, payload)
                ToolResult.Success(runtime.invoke(capture, tool.runtime, tool.handler, payload, invocation.runtimeContext(),
                    { method, params -> gateway.call(invocation, method, params) }))
            } } finally { invocation.close() }
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            ToolResult.Error(app.getString(R.string.plugin_error_context), "CONTEXT_CHANGED")
        } catch (failure: PluginHostException) {
            ToolResult.Error(failure.message.orEmpty(), failure.code)
        } catch (failure: PluginCaptureException) {
            ToolResult.Error(app.getString(R.string.plugin_error_stale), failure.error.code.name)
        } catch (failure: PluginProcessException) {
            ToolResult.Error(app.getString(R.string.plugin_error_runtime), failure.code)
        }
    }

    companion object {
        internal fun toolName(id: String, localId: String): String {
            val raw = "plugin__${id.replace('.', '_')}__$localId"
            if (raw.length <= 64) return raw
            val suffix = MessageDigest.getInstance("SHA-256").digest("$id:$localId".toByteArray(Charsets.UTF_8))
                .take(10).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            return raw.take(42) + "__" + suffix
        }

        private fun schemaValue(element: JsonElement): Any? = when (element) {
            is JsonObject -> element.mapValues { schemaValue(it.value) }
            is JsonArray -> element.map(::schemaValue)
            JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull
        }

        internal fun acceptsArguments(schema: JsonObject, args: JsonObject): Boolean {
            var budget = 4096
            fun visit(rule: JsonObject?, value: JsonElement, depth: Int): Boolean {
                if (--budget < 0 || depth > 24) return false
                val type = (rule?.get("type") as? JsonPrimitive)?.content
                val allowedValues = rule?.get("enum") as? JsonArray
                if (allowedValues != null && value !in allowedValues) return false
                fun length(key: String, fallback: Int): Int = (rule?.get(key) as? JsonPrimitive)?.intOrNull ?: fallback
                return when (value) {
                    is JsonObject -> {
                        val properties = rule?.get("properties") as? JsonObject
                        val required = rule?.get("required") as? JsonArray
                        (type == null || type == "object") && (required == null || required.all { (it as JsonPrimitive).content in value }) &&
                            value.all { (key, child) -> key.length <= 256 &&
                                (properties?.containsKey(key) == true || rule?.get("additionalProperties") != JsonPrimitive(false)) &&
                                visit(properties?.get(key) as? JsonObject, child, depth + 1) }
                    }
                    is JsonArray -> (type == null || type == "array") && value.size in length("minItems", 0)..length("maxItems", 1024) &&
                        value.all { visit(rule?.get("items") as? JsonObject, it, depth + 1) }
                    JsonNull -> type == null || type == "null"
                    is JsonPrimitive -> when {
                        value.isString -> (type == null || type == "string") && value.content.codePointCount(0, value.content.length) in
                            length("minLength", 0)..length("maxLength", 65536)
                        value.booleanOrNull != null -> type == null || type == "boolean"
                        else -> {
                            val number = value.content.toBigDecimalOrNull() ?: return false
                            val min = (rule?.get("minimum") as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                            val max = (rule?.get("maximum") as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                            (type == null || type == "number" || type == "integer" && number.stripTrailingZeros().scale() <= 0) &&
                                (min == null || number >= min) && (max == null || number <= max)
                        }
                    }
                }
            }
            return visit(schema, args, 0)
        }
    }
}
