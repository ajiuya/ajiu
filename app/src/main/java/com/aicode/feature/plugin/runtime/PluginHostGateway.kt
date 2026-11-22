package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.permission.ShellCommandParser
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginOperationGrantRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.google.gson.Gson
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class PluginHostGateway @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val androidApp: android.content.Context,
    private val plugins: PluginManager,
    private val workspaces: WorkspaceRepository,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val modes: ExecutionModeHolder,
    private val settings: ContainerSettingsRepository,
    private val connection: RemoteSshConnection,
    private val localFolders: LocalFolderGrantRepository,
    private val fileBridge: PluginFileBridge,
    private val commands: CommandEngine,
    private val policy: ToolPermissionPolicyEngine,
    private val tools: ToolRegistry,
    private val approvals: PluginApprovalCoordinator,
    private val capabilities: PluginCapabilityRepository,
    private val operationGrants: PluginOperationGrantRepository,
    private val extensions: Set<@JvmSuppressWildcards PluginHostApi>
) {
    private val environmentChanges = combine(workspaces.selection, modes.modeSelectionRevisions,
        settings.activeProfileSelectionRevisions, connection.identityState, localFolders.snapshots) { _, _, _, _, _ -> Unit }
    private val changes: Flow<Unit> = merge(environmentChanges, plugins.snapshot.map { Unit }, capabilities.grants.map { Unit })
    private data class SourceIdentity(val workspace: Any, val mode: ExecutionMode, val modeRevision: Long,
        val profileRevision: Long, val rootsRevision: Long, val remote: RemoteConnectionIdentity?, val files: String?)
    private data class BackgroundIdentity(val id: String, val revision: String, val hash: String, val epoch: Long)
    private val backgroundAuthorities = linkedMapOf<BackgroundIdentity, Any>()

    suspend fun captureContext(
        capture: PluginCapture, sessionId: String? = null, workspacePath: String? = null,
        agentContext: AgentContext? = null
    ): PluginInvocationContext = withContext(Dispatchers.IO) {
        capture.checkCurrent()
        localFolders.awaitReady()
        val grants = localFolders.snapshot()
        val mode = modes.currentMode()
        val modeRevision = modes.modeSelectionRevision
        val profileRevision = settings.activeProfileSelectionRevision
        val remote = if (mode == ExecutionMode.REMOTE_SSH) connection.identityState.value else null
        val selection = workspaces.selection.value
        val selected = selection.workspace?.takeIf { it.available }?.path.orEmpty()
        val fixed = workspacePath ?: selected
        if (fixed != selected || agentContext != null && (fixed.isBlank() || sessionId.isNullOrBlank())) {
            throw PluginHostException("CONTEXT_CHANGED", "The requested workspace is not selected")
        }
        val execution = if (fixed.isEmpty()) null else executionFactory.freeze(agentContext?.projectMemoryCapture)
        if (execution != null) {
            val matches = when (mode) {
                ExecutionMode.LOCAL_PROOT -> execution.localMapping?.workspaceRoot == java.io.File(fixed).canonicalFile
                ExecutionMode.REMOTE_SSH -> execution.remoteSource?.let { it.workspaceRoot.trimEnd('/') == fixed.trimEnd('/') && it.identity == remote } == true
            }
            if (execution.mode != mode || !matches) throw PluginHostException("CONTEXT_CHANGED", "Workspace binding does not match the selected root")
        }
        val definition = agentContext?.agentDefinition
        val stableTarget = listOf(mode.name, fixed,
            execution?.localMapping?.let { com.aicode.feature.plugin.data.PluginFiles.sha256(it.profile.toString().toByteArray(Charsets.UTF_8)) }.orEmpty(),
            if (remote == null) "" else connection.config?.let { "${it.host}:${it.port}:${it.username}:${remote.home}" }.orEmpty()).joinToString("|")
        PluginInvocationContext(capture, execution, fixed, sessionId, plugins.dataDirectory(capture.id), changes,
            { name -> definition?.allowsTool(name) != false },
            SourceIdentity(selection, mode, modeRevision, profileRevision, grants.revision, remote, execution?.fileCacheIdentity), persistentIdentity = stableTarget) {
            if (modes.currentMode() != mode || modes.modeSelectionRevision != modeRevision ||
                settings.activeProfileSelectionRevision != profileRevision || workspaces.selection.value !== selection ||
                mode == ExecutionMode.REMOTE_SSH && connection.identityState.value != remote) {
                throw CancellationException("Plugin execution source changed")
            }
            grants.checkCurrent()
        }.also { it.checkIntegrity() }
    }

    suspend fun captureBackgroundContext(capture: PluginCapture, capability: PluginCapability? = null): PluginInvocationContext = withContext(Dispatchers.IO) {
        capture.checkCurrent()
        capabilities.ensureLoaded()
        if (capability != null && !capabilities.checkGranted(capture, capability)) throw PluginHostException("CAPABILITY_REQUIRED", "Background capability is not granted")
        val identity = BackgroundIdentity(capture.id, capture.installRevision, capture.contentHash, capture.activationEpoch)
        val authority = synchronized(backgroundAuthorities) {
            val live = plugins.snapshot.value.plugins.filter { it.isActive }.map {
                BackgroundIdentity(it.record.id, it.record.installRevision, it.record.contentHash, it.record.activationEpoch)
            }.toSet()
            backgroundAuthorities.keys.retainAll(live)
            if (backgroundAuthorities.size >= 256 && identity !in backgroundAuthorities) throw PluginHostException("BUSY", "Background plugin limit reached")
            backgroundAuthorities.getOrPut(identity) { Any() }
        }
        val auto = capability == PluginCapability.AUTOSTART && !capture.cleanup && capabilities.checkGranted(capture, PluginCapability.AUTOSTART)
        PluginInvocationContext(capture, null, "", null, plugins.dataDirectory(capture.id),
            merge(plugins.snapshot.map { Unit }, capabilities.grants.map { Unit }),
            { name -> name !in setOf("Bash", "terminal", "Root", "Shizuku", "readFile", "writeFile", "editFile", "list", "search") },
            "background:${capture.id}:${capture.installRevision}:${capture.activationEpoch}", background = true,
            persistentIdentity = "plugin-background:${capture.id}:${capture.installRevision}",
            allowIdleCallbacks = auto, backgroundAuthority = authority) {
            if (capability != null && !capabilities.checkGranted(capture, capability) ||
                auto && !capabilities.checkGranted(capture, PluginCapability.AUTOSTART)) throw CancellationException("Background capability revoked")
        }
    }

    suspend fun requireCapability(context: PluginInvocationContext, capability: PluginCapability) {
        context.checkIntegrity()
        capabilities.ensureLoaded()
        if (!capabilities.checkGranted(context.capture, capability)) {
            throw PluginHostException("CAPABILITY_REQUIRED", "Grant this capability in native plugin settings")
        }
        context.checkIntegrity()
    }

    suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        if (method.length !in 1..128 || params.toString().length > MAX_MESSAGE_CHARS) invalid()
        val api = when (method) {
            "log.write" -> "log"
            else -> method.removePrefix("host.")
        }
        if (context.capture.cleanup && (api !in setOf("fs.read", "fs.readBinary", "fs.list", "fs.stat", "fs.write", "fs.writeBinary", "fs.append", "fs.mkdir", "fs.delete", "fs.rename", "fs.copy") ||
                listOfNotNull(params["path"], params["from"], params["to"]).any { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("data://") != true
                })) throw PluginHostException("SYSTEM_PROTECTED", "Cleanup can only access this plugin's data")
        context.whileCurrent {
            when (api) {
                "toast" -> {
                    val text = string(params, "message", 1000)
                    withContext(Dispatchers.Main.immediate) {
                        context.checkCurrent()
                        android.widget.Toast.makeText(androidApp, text, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    JsonObject(mapOf("ok" to JsonPrimitive(true)))
                }
                "context" -> buildJsonObject {
                    put("pluginId", context.capture.id); put("version", context.capture.manifest.version.toString())
                    put("pluginApi", "1.0.0"); context.sessionId?.let { put("sessionId", it) }
                }
                "fs.read", "fs.readBinary", "fs.list", "fs.stat", "fs.write", "fs.writeBinary",
                "fs.append", "fs.mkdir", "fs.delete", "fs.rename", "fs.copy" -> fileBridge.call(context, api, params) { name, args, op, details ->
                    val copySource = args["from"]?.takeIf { api == "fs.copy" }
                    authorize(context, name, args, op, details) {
                        if (copySource != null) checkPolicy(context, "readFile", buildJsonObject { put("path", copySource) })
                    }
                }
                "tools.list" -> listTools(context, params)
                "tools.execute" -> {
                    if (params.keys.any { it !in setOf("name", "arguments", "timeoutMs") }) invalid()
                    val name = string(params, "name", 64)
                    val args = params["arguments"]?.let { it as? JsonObject ?: invalid() } ?: JsonObject(emptyMap())
                    val limit = duration(params, "timeoutMs", 120_000, 1_800_000)
                    executeTool(context, name, args, limit)
                }
                "bash" -> bash(context, params)
                else -> {
                    if (api in PROTECTED_METHODS) throw PluginHostException("SYSTEM_PROTECTED", "User decisions cannot be delegated to plugin scripts")
                    val matching = extensions.filter { api in it.methods }
                    if (matching.size != 1) throw PluginHostException("METHOD_NOT_SUPPORTED", "This host API is not supported")
                    matching.single().call(context, api, params)
                }
            }.also { if (it.toString().length > MAX_MESSAGE_CHARS) throw PluginHostException("OUTPUT_TOO_LARGE", "Host output exceeds its limit") }
        }
    }

    private fun listTools(context: PluginInvocationContext, params: JsonObject): JsonObject {
        val includeSchema = params["includeSchema"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: invalid() } ?: false
        return buildJsonObject {
            put("tools", JsonArray(tools.getAvailableTools().filter { bridgeToolAllowed(it.name) && context.allowsTool(it.name) }.map { tool ->
                buildJsonObject {
                    put("name", tool.name); put("type", "builtin"); put("description", tool.description)
                    put("permissionPolicy", "ask")
                    if (includeSchema) put("inputSchema", Json.parseToJsonElement(gson.toJson(tool.toJsonSchema())))
                }
            }))
        }
    }

    private suspend fun executeTool(context: PluginInvocationContext, name: String, args: JsonObject, timeoutMs: Long): JsonElement {
        if (!bridgeToolAllowed(name) || !context.allowsTool(name)) forbidden()
        val execution = context.execution ?: throw PluginHostException("CONTEXT_REQUIRED", "A ready workspace is required")
        val tool = tools.getTool(name) ?: throw PluginHostException("TOOL_NOT_FOUND", "Tool is unavailable")
        val result = withContext(execution) {
            withTimeoutOrNull(timeoutMs) {
                if (name == "Bash") {
                    val output = bash(context, args)
                    buildJsonObject { put("status", "success"); put("data", output.getValue("output")) }
                } else {
                    if (com.aicode.feature.agent.domain.tool.ToolCapability.EXTERNAL_TOOL !in tool.capabilities && args.keys.any { it !in tool.parameters }) invalid()
                    authorize(context, name, args, PluginApprovalOperation.EXECUTE_TOOL, "$name\n$args")
                    if (tools.getTool(name) !== tool) throw PluginHostException("CONTEXT_CHANGED", "Tool registration changed")
                    val value: ToolResult = tool.executeWithContext(args, AgentContext(currentFile = null, selectedCode = null,
                        projectRoot = context.workspacePath, language = null, sessionId = context.sessionId))
                    Json.parseToJsonElement(value.toTransportString())
                }
            } ?: throw PluginHostException("TOOL_TIMEOUT", "Tool call timed out")
        }
        return result
    }

    private suspend fun bash(context: PluginInvocationContext, params: JsonObject): JsonObject {
        if (params.keys.any { it !in setOf("command", "timeout", "elevate") }) forbidden()
        val command = string(params, "command", PluginApprovalCoordinator.MAX_DETAILS)
        if (command.isBlank()) forbidden()
        params["elevate"]?.let { (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull ?: invalid() }
        val seconds = duration(params, "timeout", 120, 1800)
        val execution = context.execution ?: throw PluginHostException("CONTEXT_REQUIRED", "A ready workspace is required")
        return withContext(execution) {
            authorize(context, "Bash", params, PluginApprovalOperation.EXECUTE_COMMAND, params.toString())
            if (commands.notReadyHint() != null) throw PluginHostException("RUNTIME_UNAVAILABLE", "Execution environment is not ready")
            val result = commands.runCommandSyncWithExit(command, context.workspacePath, seconds * 1000)
            buildJsonObject { put("output", result.output); put("exitCode", result.exitCode); put("truncated", result.outputTruncated) }
        }
    }

    internal suspend fun checkPolicy(context: PluginInvocationContext, name: String, args: JsonObject) {
        context.checkCurrent()
        if (!context.allowsTool(name)) forbidden()
        val decision = policy.evaluate(name, args, context.sessionId)
        context.checkCurrent()
        if (decision.verdict == ToolPermissionPolicyEngine.Verdict.DENY || decision.rootGeneration != null) {
            throw PluginHostException("SYSTEM_DENIED", "The host policy denied this operation")
        }
    }

    suspend fun authorize(context: PluginInvocationContext, name: String, args: JsonObject,
        operation: PluginApprovalOperation, details: String, additionalPolicy: suspend () -> Unit = {}) {
        if (context.capture.cleanup) {
            context.checkIntegrity()
            if (name !in setOf("readFile", "writeFile", "list") ||
                listOfNotNull(args["path"], args["from"], args["to"]).any { (it as? JsonPrimitive)?.content?.startsWith("data://") != true }) {
                throw PluginHostException("SYSTEM_PROTECTED", "Cleanup has no host-operation authority")
            }
            return
        }
        checkPolicy(context, name, args); additionalPolicy()
        if (operationGrants.isAllowed(context, name, args)) {
            checkPolicy(context, name, args); additionalPolicy()
            return
        }
        val canRemember = operationGrants.canRemember(context, name)
        val allowed = approvals.awaitApproval(context.capture.id, context.capture.manifest.name.resolve(Locale.getDefault().toLanguageTag()),
            operation, context.approvalDetails(details), context.changes, context::checkCurrent,
            canAlwaysAllow = canRemember, scopeSummary = name, sourceIdentity = context.sourceIdentity) {
                checkPolicy(context, name, args); additionalPolicy()
                operationGrants.remember(context, name, args)
            }
        if (!allowed) throw PluginHostException("USER_REJECTED", "This operation was not approved")
        context.checkIntegrity()
        checkPolicy(context, name, args); additionalPolicy()
    }

    private fun duration(value: JsonObject, key: String, default: Long, max: Long): Long = value[key]?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull?.takeIf { n -> n in 1..max } ?: invalid()
    } ?: default

    private fun string(params: JsonObject, key: String, limit: Int): String = (params[key] as? JsonPrimitive)?.takeIf {
        it.isString && it.content.length in 1..limit && '\u0000' !in it.content
    }?.content ?: invalid()

    private fun bridgeToolAllowed(name: String): Boolean = !name.startsWith("plugin__") && name !in setOf(
        "Root", "Shizuku", "submitPlan", "askUserQuestion", "goalStatus", "todo", "task", "messageParent", "terminal")

    private fun forbidden(): Nothing = throw PluginHostException("TOOL_NOT_ALLOWED", "This operation is not available through the plugin bridge")
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid plugin host parameters")
    private companion object {
        const val MAX_MESSAGE_CHARS = 2 * 1024 * 1024
        val PROTECTED_METHODS = setOf("tools.resolve", "chat.resolveToolApproval", "chat.resolveQuestion", "chat.resolvePlanApproval")
        val gson = Gson()
    }
}
