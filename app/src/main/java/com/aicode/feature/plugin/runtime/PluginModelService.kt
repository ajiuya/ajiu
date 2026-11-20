package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.workflow.ContextTokenEstimator
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.google.gson.Gson
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginModelService internal constructor(
    private val providerRepository: AIProviderRepository,
    private val providerFactory: ProviderInstanceFactory,
    private val metadataService: ModelMetadataService,
    private val defaults: DefaultModelSettingsRepository,
    private val tools: ToolRegistry,
    private val capabilities: PluginCapabilityRepository,
    private val gateway: Provider<PluginHostGateway>,
    private val scope: CoroutineScope,
    private val clock: () -> Long
) : AutoCloseable {
    @Inject constructor(
        providerRepository: AIProviderRepository,
        providerFactory: ProviderInstanceFactory,
        metadataService: ModelMetadataService,
        defaults: DefaultModelSettingsRepository,
        tools: ToolRegistry,
        capabilities: PluginCapabilityRepository,
        gateway: Provider<PluginHostGateway>
    ) : this(providerRepository, providerFactory, metadataService, defaults, tools, capabilities, gateway,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), { System.currentTimeMillis() })

    private data class ModelParams(val reasoning: String? = null, val output: Int? = null, val temperature: Float? = null)
    private class Handle(val id: String, val owner: PluginRuntimeOwner, val providerId: String, val model: String,
        val name: String?, val system: String?, val params: ModelParams, val createdAt: Long) {
        var lastUsedAt = createdAt
    }
    private class Stream(val id: String, val handleId: String, val owner: PluginRuntimeOwner, now: Long) {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val reader = Mutex()
        val events = ArrayDeque<JsonObject>()
        var chars = 0
        var cursor = 0
        var done = false
        var status = "running"
        var final: JsonObject? = null
        var error: JsonObject? = null
        var doneAt = 0L
        var lastReadAt = now
        var job: Job? = null
    }
    private val lock = Any()
    private val handles = linkedMapOf<String, Handle>()
    private val streams = linkedMapOf<String, Stream>()
    private val hasResources = MutableStateFlow(false)
    private var closed = false

    init {
        scope.launch {
            hasResources.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) { delay(1_000); cleanup() }
            }
        }
    }

    private suspend fun access(context: PluginInvocationContext) {
        context.checkIntegrity()
        gateway.get().requireCapability(context, PluginCapability.MODEL_ACCESS)
        context.checkCurrent()
        if (context.capture.content.modelAccess == null) throw PluginHostException("MODEL_ACCESS_NOT_DECLARED", "Model access is not declared")
        synchronized(lock) { if (closed) throw PluginHostException("MODEL_CLOSED", "Model service is closed") }
    }

    suspend fun listProviders(context: PluginInvocationContext): JsonElement {
        access(context)
        val defaultId = defaults.getDefaultProviderId()
        val configs = providerRepository.getAllProviders().first()
        context.checkCurrent()
        return buildJsonObject {
            put("providers", JsonArray(configs.map { config -> buildJsonObject {
                put("id", config.id); put("name", config.name); put("type", config.type.name.lowercase(Locale.ROOT))
                put("enabled", config.isEnabled); put("hasKey", config.hasUsableApiKey)
                put("isPlugin", PluginProviderIds.isPluginId(config.id)); put("isDefault", config.id == defaultId)
                put("defaultModel", config.defaultModel); put("selectedModel", config.selectedModel); put("modelCount", config.models.size)
            } }))
        }
    }

    suspend fun listModels(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        PluginConversationJson.keys(params, setOf("providerId"))
        val config = requireProvider(PluginConversationJson.string(params, "providerId"))
        val ids = configuredModels(config).take(500)
        val metadata = metadataService.resolveAll(config.id, config.type, ids)
        context.checkCurrent()
        return buildJsonObject {
            put("providerId", config.id)
            put("models", JsonArray(ids.map { id -> modelJson(metadata.getValue(id)) }))
            if (configuredModels(config).size > ids.size) put("truncated", true)
        }
    }

    suspend fun create(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        cleanup()
        PluginConversationJson.keys(params, setOf("providerId", "model", "name", "systemPrompt", "params"))
        val config = requireProvider(PluginConversationJson.string(params, "providerId"))
        if (PluginProviderIds.pluginIdOf(config.id) == context.capture.id) {
            throw PluginHostException("SELF_PROVIDER_FORBIDDEN", "A plugin cannot use a model handle for its own provider")
        }
        val model = resolveModel(config, PluginConversationJson.optionalString(params, "model"))
        val options = parseParams(params["params"])
        validate(options, metadataService.resolve(config.id, config.type, model))
        val handle = Handle(UUID.randomUUID().toString(), PluginRuntimeOwner(context), config.id, model,
            PluginConversationJson.optionalString(params, "name", 64),
            PluginConversationJson.optionalString(params, "systemPrompt", 64 * 1024), options, clock())
        context.checkIntegrity()
        synchronized(lock) {
            checkGrant(handle.owner)
            if (handles.size >= 128 || handles.values.count { it.owner.capture.id == context.capture.id } >= 16) {
                throw PluginHostException("HANDLE_LIMIT", "Too many model handles")
            }
            handles[handle.id] = handle
            hasResources.value = true
        }
        return buildJsonObject { put("handle", buildJsonObject {
            put("id", handle.id); put("providerId", config.id); put("model", model); put("createdAt", handle.createdAt)
            handle.name?.let { put("name", it) }; put("params", paramsJson(options))
        }) }
    }

    suspend fun start(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        cleanup()
        PluginConversationJson.keys(params, setOf("handleId", "messages", "tools", "systemPrompt", "params"))
        if (params.toString().length > 3 * 1024 * 1024) throw PluginHostException("REQUEST_TOO_LARGE", "Model request is too large")
        val handle = requireHandle(context, PluginConversationJson.string(params, "handleId"))
        val codec = PluginModelImageCodec { path ->
            val response = gateway.get().call(context, "fs.readBinary", buildJsonObject { put("path", path) }) as? JsonObject
                ?: throw PluginHostException("IO_ERROR", "Image read failed")
            val encoded = PluginConversationJson.string(response, "base64", 2 * 1024 * 1024, empty = true)
            try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                throw PluginHostException("IO_ERROR", "Image response is invalid")
            }
        }
        val messages = parseMessages(params["messages"], codec)
        val definitions = parseTools(context, params["tools"])
        val system = PluginConversationJson.optionalString(params, "systemPrompt", 64 * 1024) ?: handle.system.orEmpty()
        val override = parseParams(params["params"])
        val options = ModelParams(override.reasoning ?: handle.params.reasoning, override.output ?: handle.params.output,
            override.temperature ?: handle.params.temperature)
        val config = requireProvider(handle.providerId)
        val model = resolveModel(config, handle.model)
        val metadata = metadataService.resolve(config.id, config.type, model)
        validate(options, metadata)
        val estimated = ContextTokenEstimator.estimate(system, messages, definitions)
        val budget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (estimated >= budget) throw PluginHostException("CONTEXT_TOO_LARGE", "Estimated input $estimated tokens exceeds budget $budget")
        val approvalArgs = buildJsonObject {
            put("providerId", config.id); put("model", model); put("handleId", handle.id)
            put("estimatedInputTokens", estimated); put("maxOutputTokens", options.output ?: metadata.outputTokens)
            put("request", params)
        }
        // The capability permits access to models; each paid request still needs the user's native decision.
        gateway.get().authorize(context, "model.start", approvalArgs, PluginApprovalOperation.EXECUTE_TOOL,
            "Model request may incur provider charges\nprovider=${config.name}\nmodel=$model\nestimatedInputTokens=$estimated\nmaxOutputTokens=${options.output ?: metadata.outputTokens}")
        handle.owner.requireCaller(context)
        val current = requireProvider(handle.providerId)
        if (current != config) throw PluginHostException("CONTEXT_CHANGED", "Provider configuration changed during approval")
        val provider = providerFactory.create(config.copy(selectedModel = model), "plugin:${context.capture.id}:${handle.id}")
        options.output?.let { provider.maxOutputTokens = it.coerceAtMost(metadata.outputTokens ?: 65_536) }
        options.temperature?.let { provider.temperature = it }
        val stream = Stream(UUID.randomUUID().toString(), handle.id, handle.owner, clock())
        synchronized(lock) {
            handle.owner.requireCaller(context); checkGrant(handle.owner)
            if (closed || handles[handle.id] !== handle) throw PluginHostException("HANDLE_NOT_FOUND", "Model handle was closed")
            if (streams.size >= 128 || streams.values.count { !it.done } >= 32 ||
                streams.values.count { !it.done && it.owner.capture.id == context.capture.id } >= 8) {
                throw PluginHostException("STREAM_LIMIT", "Too many model streams")
            }
            handle.lastUsedAt = clock()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    context.whileCurrent {
                        var final: AIResponse? = null
                        val started = clock()
                        provider.completeStream(system, messages, definitions, options.reasoning).takeWhile { chunk ->
                            stream.owner.checkCurrent(); checkGrant(stream.owner)
                            when (chunk) {
                                is AIStreamChunk.TextDelta -> emitText(stream, "text", chunk.text)
                                is AIStreamChunk.ReasoningDelta -> emitText(stream, "reasoning", chunk.text)
                                is AIStreamChunk.ToolCallDeclared -> emit(stream, buildJsonObject { put("type", "toolCall"); put("name", chunk.name.take(128)) })
                                is AIStreamChunk.ToolCallArgumentsDelta -> Unit
                                is AIStreamChunk.Retrying -> emit(stream, buildJsonObject {
                                    put("type", "retry"); put("attempt", chunk.attempt); put("maxRetries", chunk.maxRetries)
                                    put("error", buildJsonObject { put("code", chunk.error.kind.name.lowercase(Locale.ROOT)); chunk.error.statusCode?.let { put("statusCode", it) } })
                                })
                                is AIStreamChunk.KeySwitched -> emit(stream, buildJsonObject { put("type", "keySwitch"); put("index", chunk.newIndex); put("total", chunk.total) })
                                is AIStreamChunk.Final -> final = chunk.response
                            }
                            chunk !is AIStreamChunk.Final
                        }.collect { }
                        val response = final ?: throw PluginHostException("MODEL_INCOMPLETE", "Model stream ended without a final response")
                        stream.owner.checkCurrent(); checkGrant(stream.owner)
                        if (response.isAborted) throw PluginHostException("MODEL_TERMINAL_ERROR", "Model terminated with ${response.stopReason}")
                        val result = responseJson(response, clock() - started)
                        if (result.toString().toByteArray(Charsets.UTF_8).size > 512 * 1024) {
                            throw PluginHostException("MODEL_OUTPUT_TOO_LARGE", "Model response exceeds its transport budget")
                        }
                        finish(stream, "success", result, null)
                    }
                } catch (cancelled: CancellationException) {
                    finish(stream, "cancelled", null, null)
                    throw cancelled
                } catch (error: Exception) {
                    val code = (error as? PluginHostException)?.code ?: "MODEL_ERROR"
                    finish(stream, "error", null, buildJsonObject { put("code", code); put("message", "Model request failed") })
                    synchronized(lock) { handles.remove(handle.id, handle) }
                } finally {
                    synchronized(lock) { stream.job = null }
                }
            }
            stream.job = job
            streams[stream.id] = stream
            hasResources.value = true
            job.start()
        }
        return buildJsonObject { put("streamId", stream.id) }
    }

    suspend fun read(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        cleanup()
        PluginConversationJson.keys(params, setOf("streamId", "cursor", "maxEvents", "waitMs"))
        val stream = synchronized(lock) { streams[PluginConversationJson.string(params, "streamId")] }
            ?: throw PluginHostException("STREAM_NOT_FOUND", "Model stream not found")
        stream.owner.requireCaller(context)
        val cursor = PluginConversationJson.int(params, "cursor", 0, 0, Int.MAX_VALUE)
        val count = PluginConversationJson.int(params, "maxEvents", 100, 1, 500)
        val wait = PluginConversationJson.int(params, "waitMs", 10_000, 0, 20_000)
        return context.whileCurrent {
            stream.reader.withLock {
                val deadline = clock() + wait
                while (true) {
                    stream.owner.requireCaller(context); checkGrant(stream.owner)
                    val result = synchronized(lock) {
                        if (streams[stream.id] !== stream) throw PluginHostException("STREAM_NOT_FOUND", "Model stream was closed")
                        if (cursor != stream.cursor) throw PluginHostException("CURSOR_INVALID", "Cursor does not match the next unread event")
                        if (stream.events.isEmpty() && !stream.done && clock() < deadline) null else {
                            var chars = 0
                            val events = mutableListOf<JsonObject>()
                            while (events.size < count && stream.events.isNotEmpty()) {
                                val next = stream.events.first()
                                val size = next.toString().length
                                if (events.isNotEmpty() && chars + size > 256 * 1024) break
                                stream.events.removeFirst(); stream.chars -= size; stream.cursor++; chars += size; events += next
                            }
                            stream.lastReadAt = clock()
                            val drained = stream.done && stream.events.isEmpty()
                            buildJsonObject {
                                put("streamId", stream.id); put("events", JsonArray(events)); put("cursor", stream.cursor); put("done", drained)
                                if (drained) { put("status", stream.status); stream.final?.let { put("final", it) }; stream.error?.let { put("error", it) } }
                            }
                        }
                    }
                    if (result != null) return@withLock result
                    withTimeoutOrNull((deadline - clock()).coerceAtLeast(1)) { stream.signal.receive() }
                }
                @Suppress("UNREACHABLE_CODE")
                JsonNull
            }
        }
    }

    suspend fun cancel(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        PluginConversationJson.keys(params, setOf("streamId"))
        val stream = synchronized(lock) { streams[PluginConversationJson.string(params, "streamId")] }
            ?: throw PluginHostException("STREAM_NOT_FOUND", "Model stream not found")
        stream.owner.requireCaller(context)
        cancelStream(stream)
        return buildJsonObject { put("ok", true) }
    }

    suspend fun close(context: PluginInvocationContext, params: JsonObject): JsonElement {
        access(context)
        PluginConversationJson.keys(params, setOf("handleId"))
        val handle = requireHandle(context, PluginConversationJson.string(params, "handleId"))
        closeHandle(handle)
        return buildJsonObject { put("ok", true) }
    }

    fun closePlugin(pluginId: String) {
        val owned = synchronized(lock) { handles.values.filter { it.owner.capture.id == pluginId } }
        owned.forEach(::closeHandle)
        synchronized(lock) { streams.values.filter { it.owner.capture.id == pluginId }.toList() }.forEach(::cancelStream)
    }

    private fun requireHandle(context: PluginInvocationContext, id: String): Handle {
        val handle = synchronized(lock) { handles[id] } ?: throw PluginHostException("HANDLE_NOT_FOUND", "Model handle not found")
        handle.owner.requireCaller(context)
        return handle
    }

    private fun checkGrant(owner: PluginRuntimeOwner) {
        owner.checkCurrent()
        if (!capabilities.checkGranted(owner.capture, PluginCapability.MODEL_ACCESS)) throw PluginHostException("CAPABILITY_REQUIRED", "Native model access grant is required")
    }

    private fun emitText(stream: Stream, type: String, text: String) {
        var offset = 0
        while (offset < text.length) {
            val end = minOf(offset + 16 * 1024, text.length)
            emit(stream, buildJsonObject { put("type", type); put("text", text.substring(offset, end)) })
            offset = end
        }
    }

    private fun emit(stream: Stream, event: JsonObject) {
        synchronized(lock) {
            if (stream.done || streams[stream.id] !== stream) throw CancellationException("Model stream was closed")
            val size = event.toString().length
            if (stream.events.size >= 1024 || stream.chars + size > 2 * 1024 * 1024) {
                finish(stream, "error", null, buildJsonObject { put("code", "STREAM_BUFFER_OVERFLOW"); put("message", "Read the model stream more frequently") })
                handles.remove(stream.handleId)
                stream.job?.cancel()
                throw CancellationException("Model stream buffer overflow")
            }
            stream.events.addLast(event); stream.chars += size
            stream.signal.trySend(Unit)
        }
    }

    private fun finish(stream: Stream, status: String, final: JsonObject?, error: JsonObject?) {
        synchronized(lock) {
            if (stream.done) return
            stream.done = true; stream.status = status; stream.final = final; stream.error = error; stream.doneAt = clock()
            stream.signal.trySend(Unit)
        }
    }

    private fun cancelStream(stream: Stream) {
        synchronized(lock) { finish(stream, "cancelled", null, null); stream.job?.cancel() }
    }

    private fun closeHandle(handle: Handle) {
        synchronized(lock) {
            handles.remove(handle.id, handle)
            streams.values.filter { it.handleId == handle.id }.toList().forEach { stream ->
                cancelStream(stream); streams.remove(stream.id, stream); stream.signal.close()
            }
        }
    }

    private fun cleanup() {
        synchronized(lock) {
            val now = clock()
            handles.values.filter { now - it.lastUsedAt > 10 * 60_000 || runCatching { checkGrant(it.owner) }.isFailure }
                .toList().forEach(::closeHandle)
            streams.values.toList().forEach { stream ->
                if (runCatching { checkGrant(stream.owner) }.isFailure) {
                    cancelStream(stream); streams.remove(stream.id, stream); stream.signal.close()
                } else if (stream.done && now - stream.doneAt > 60_000 || !stream.done && now - stream.lastReadAt > 5 * 60_000) {
                    cancelStream(stream); streams.remove(stream.id, stream); stream.signal.close()
                }
            }
            hasResources.value = handles.isNotEmpty() || streams.isNotEmpty()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            handles.values.toList().forEach(::closeHandle)
            streams.values.toList().forEach(::cancelStream)
            streams.clear()
            hasResources.value = false
        }
        scope.cancel()
    }

    private suspend fun requireProvider(id: String): AIProviderConfig {
        val config = providerRepository.getProviderById(id) ?: throw PluginHostException("PROVIDER_NOT_FOUND", "Provider not found")
        if (!config.isEnabled) throw PluginHostException("PROVIDER_DISABLED", "Provider is disabled")
        if (!config.hasUsableApiKey) throw PluginHostException("PROVIDER_NO_KEY", "Provider has no usable key")
        return config
    }

    private fun configuredModels(config: AIProviderConfig) = (config.models + config.defaultModel + config.selectedModel)
        .filter { it.isNotBlank() }.distinct()

    private fun resolveModel(config: AIProviderConfig, requested: String?): String =
        (requested?.takeIf { it.isNotBlank() } ?: config.effectiveModel).takeIf { it in configuredModels(config) }
            ?: throw PluginHostException("MODEL_NOT_AVAILABLE", "Model is not configured for this provider")

    private fun parseParams(raw: JsonElement?): ModelParams {
        if (raw == null || raw is JsonNull) return ModelParams()
        val obj = PluginConversationJson.objectValue(raw, "params")
        PluginConversationJson.keys(obj, setOf("reasoningEffort", "maxOutputTokens", "temperature"))
        val output = if (obj["maxOutputTokens"] == null) null else PluginConversationJson.int(obj, "maxOutputTokens", 1, 1, 65_536)
        val temperature = PluginConversationJson.number(obj, "temperature")?.takeIf { it in 0.0..2.0 }?.toFloat()
        if (obj["temperature"] != null && obj["temperature"] !is JsonNull && temperature == null) PluginConversationJson.invalid("temperature must be in 0..2")
        return ModelParams(PluginConversationJson.optionalString(obj, "reasoningEffort", 32), output, temperature)
    }

    private fun validate(options: ModelParams, metadata: ModelMetadata) {
        if (metadata.modelType != ModelMetadata.ModelType.CHAT) throw PluginHostException("MODEL_UNSUPPORTED", "Model is not a chat model")
        if (options.temperature != null && !metadata.supportsCustomTemperature) throw PluginHostException("PARAM_UNSUPPORTED", "Model does not support temperature")
    }

    private suspend fun parseMessages(raw: JsonElement?, codec: PluginModelImageCodec): List<AgentMessage> {
        val array = raw as? JsonArray ?: PluginConversationJson.invalid("messages must be an array")
        if (array.size !in 1..200) PluginConversationJson.invalid("messages count is out of range")
        val pending = linkedMapOf<String, String>()
        var chars = 0
        return array.map { element ->
            val row = PluginConversationJson.objectValue(element, "message")
            val content = PluginConversationJson.string(row, "content", 128 * 1024, empty = true)
            chars += content.length
            if (chars > 512 * 1024) PluginConversationJson.invalid("messages exceed their budget")
            when (PluginConversationJson.string(row, "role", 16)) {
                "user" -> AgentMessage.UserMessage(content = content, images = codec.parse(row["images"], 0))
                "assistant" -> {
                    val calls = (row["toolCalls"] as? JsonArray)?.also { if (it.size > 32) PluginConversationJson.invalid("Too many tool calls") }
                        ?.map { value ->
                            val call = PluginConversationJson.objectValue(value, "toolCall")
                            val id = PluginConversationJson.string(call, "id", 128)
                            val name = PluginConversationJson.string(call, "name", 128)
                            if (pending.put(id, name) != null) PluginConversationJson.invalid("Duplicate tool call ID")
                            val args = PluginConversationJson.objectValue(call["arguments"], "arguments")
                            if (args.toString().length > 64 * 1024) PluginConversationJson.invalid("Tool arguments are too large")
                            ToolCall(id, name, args)
                        }.orEmpty()
                    val state = row["providerState"]?.takeUnless { it is JsonNull }?.let { PluginConversationJson.objectValue(it, "providerState") }
                    AgentMessage.AssistantMessage(content = content, toolCalls = calls,
                        reasoning = PluginConversationJson.optionalString(row, "reasoning", 64 * 1024).orEmpty(),
                        signature = state?.let { PluginConversationJson.optionalString(it, "signature", 128 * 1024) }.orEmpty(),
                        thinkingBlocksJson = state?.let { PluginConversationJson.optionalString(it, "thinkingBlocksJson", 128 * 1024) }.orEmpty(),
                        images = codec.parse(row["images"], 0))
                }
                "tool" -> {
                    val id = PluginConversationJson.string(row, "toolCallId", 128)
                    val name = PluginConversationJson.string(row, "name", 128)
                    if (pending.remove(id) != name) PluginConversationJson.invalid("Tool result has no matching assistant call")
                    AgentMessage.ToolResultMessage(id = id, toolName = name, result = content, images = codec.parse(row["images"], 0))
                }
                else -> PluginConversationJson.invalid("Message role must be user, assistant or tool")
            }
        }
    }

    private fun parseTools(context: PluginInvocationContext, raw: JsonElement?): List<AgentTool> {
        if (raw == null || raw is JsonNull) return emptyList()
        val array = raw as? JsonArray ?: PluginConversationJson.invalid("tools must be an array")
        if (array.size > 64 || array.toString().length > 256 * 1024) PluginConversationJson.invalid("Tool schemas exceed their budget")
        val names = mutableSetOf<String>()
        return array.map { element ->
            val row = PluginConversationJson.objectValue(element, "tool")
            val name = PluginConversationJson.string(row, "name", 64)
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(name) || !names.add(name)) PluginConversationJson.invalid("Invalid or duplicate tool name")
            val type = PluginConversationJson.string(row, "type", 16)
            val schema: JsonObject
            val description: String
            when (type) {
                "app" -> {
                    if (!context.allowsTool(name) || name.startsWith("plugin__")) throw PluginHostException("TOOL_NOT_ALLOWED", "Tool is not available")
                    val tool = tools.getTool(name) ?: throw PluginHostException("TOOL_NOT_FOUND", "Tool is not available")
                    schema = Json.parseToJsonElement(Gson().toJson(tool.toJsonSchema())) as JsonObject
                    description = tool.description
                }
                "custom" -> {
                    schema = row["inputSchema"]?.let { PluginConversationJson.objectValue(it, "inputSchema") }
                        ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }
                    if (schema.toString().length > 64 * 1024) PluginConversationJson.invalid("Tool schema is too large")
                    description = PluginConversationJson.optionalString(row, "description", 600).orEmpty()
                }
                else -> PluginConversationJson.invalid("Tool type must be app or custom")
            }
            PluginModelToolDefinition(name, description, schema)
        }
    }

    private fun modelJson(metadata: ModelMetadata) = buildJsonObject {
        put("id", metadata.id); put("displayName", metadata.displayName); put("contextTokens", metadata.contextTokens)
        put("inputTokens", metadata.inputTokens); put("outputTokens", metadata.outputTokens)
        put("supportsTools", metadata.supportsTools); put("supportsVision", metadata.supportsVision)
        put("supportsReasoning", metadata.supportsReasoning); put("supportsCustomTemperature", metadata.supportsCustomTemperature)
        put("supportsImageOutput", metadata.supportsImageOutput); put("modelType", metadata.modelType.name.lowercase(Locale.ROOT))
        metadata.reasoningLevels?.let { put("reasoningLevels", JsonArray(it.map(::JsonPrimitive))) }
        metadata.reasoningDefaultLevel?.let { put("reasoningDefaultLevel", it) }
        metadata.inputCostUsdPerM?.let { put("inputCostUsdPerM", it) }; metadata.outputCostUsdPerM?.let { put("outputCostUsdPerM", it) }
        metadata.cacheReadCostUsdPerM?.let { put("cacheReadCostUsdPerM", it) }; metadata.cacheWriteCostUsdPerM?.let { put("cacheWriteCostUsdPerM", it) }
        put("source", metadata.source.name.lowercase(Locale.ROOT))
    }

    private fun paramsJson(params: ModelParams) = buildJsonObject {
        params.reasoning?.let { put("reasoningEffort", it) }; params.output?.let { put("maxOutputTokens", it) }; params.temperature?.let { put("temperature", it) }
    }

    private fun responseJson(response: AIResponse, duration: Long) = buildJsonObject {
        put("content", response.content.take(256 * 1024)); if (response.content.length > 256 * 1024) put("contentTruncated", true)
        response.reasoning?.let { put("reasoning", it.take(64 * 1024)); if (it.length > 64 * 1024) put("reasoningTruncated", true) }
        put("toolCalls", JsonArray(response.toolCalls.take(32).map { call -> buildJsonObject {
            put("id", call.id.take(128)); put("name", call.name.take(128))
            val args = JsonObject(call.arguments)
            if (args.toString().length <= 8 * 1024) put("arguments", args) else { put("arguments", args.toString().take(8 * 1024)); put("argumentsTruncated", true) }
        } }))
        if (response.toolCalls.size > 32) put("toolCallsTruncated", true)
        response.stopReason?.let { put("stopReason", it.take(128)) }; response.stopDetail?.let { put("stopDetail", it.take(4000)) }
        put("providerState", buildJsonObject {
            response.signature?.takeIf { it.length <= 128 * 1024 }?.let { put("signature", it) }
            response.thinkingBlocksJson?.takeIf { it.length <= 128 * 1024 }?.let { put("thinkingBlocksJson", it) }
        })
        val images = PluginModelImageCodec.encodeOutput(response.images)
        put("images", images.array); if (images.truncated) put("imagesTruncated", true)
        put("usage", buildJsonObject { put("input", response.inputTokens); put("output", response.outputTokens)
            put("cachedInput", response.cachedInputTokens); put("cacheCreation", response.cacheCreationTokens) })
        put("durationMs", duration)
    }
}
