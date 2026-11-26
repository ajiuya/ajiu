package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.plugin.data.PluginFiles
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.merge
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthException
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, NEEDS_AUTH, FAILED, DISABLED }
}

// reloadMutex 串行化重连，避免设置页连点导致并发注册/反注册竞态。
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val oauthManager: McpOAuthManager,
    private val pluginManager: PluginManager,
    private val runtimeCommands: LocalRuntimeCommands
) {
    private companion object {
        const val TAG = "McpManager"
        const val PLUGIN_MCP_PREFIX = "plugin__"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val activeClients = mutableMapOf<String, McpClient>()
    private val registeredToolNames = mutableSetOf<String>()
    private data class PluginRevision(val id: String, val revision: String, val hash: String, val epoch: Long, val config: McpServerConfig)
    private class PluginConnection(val revision: PluginRevision, val capture: PluginCapture, val client: McpClient, val tools: List<McpTool>)
    private val pluginClients = mutableMapOf<String, PluginConnection>()
    private val pendingPluginClients = mutableMapOf<String, Pair<PluginCapture, McpClient>>()
    private var pluginRevisions = emptyMap<String, PluginRevision>()
    private val pluginStatuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    private val started = AtomicBoolean()
    private val connectionGeneration = AtomicLong()
    private class StdioSource(val key: String, val selection: Any, val owner: RuntimeOwner,
        val workspace: String, val profile: ContainerProfile, val configurationRevision: Long,
        val fingerprint: String, private val checkSource: () -> Unit) {
        @Volatile var active = true
        @Volatile var connected = false
        fun checkCurrent() {
            if (!active) throw CancellationException("MCP connection source was revoked")
            checkSource()
        }
    }
    private val stdioSources = ConcurrentHashMap<String, StdioSource>()
    private val sourceChanges by lazy {
        merge(workspaceRepository.selection.map { Unit }, containerSettingsRepository.activeProfileSelectionRevisions.map { Unit },
            containerSettingsRepository.profileConfigurationRevisions.map { Unit }, configRepository.effectiveEntriesFlow.map { Unit },
            pluginManager.snapshot.map { Unit })
    }

    private suspend fun stdioTransport(cfg: McpServerConfig, entry: McpServerEntry? = null,
        pluginCapture: PluginCapture? = null): StdioTransport {
        entry?.let(configRepository::checkEntryCurrent)
        val selection = workspaceRepository.selection.value
        if (entry != null && (entry.workspaceSelectionId.isBlank() || entry.workspaceSelectionId != selection.id)) {
            throw CancellationException("MCP configuration workspace source changed")
        }
        val sourceWorkspace = (if (entry == null) selection.workspace?.takeIf { it.available }?.path else entry.workspacePath)
            ?: throw IllegalStateException("MCP stdio requires an available workspace")
        val snapshot = containerSettingsRepository.captureProfileSnapshot()
        val active = snapshot.activeProfile
        val profile = if (active.mode == ExecutionMode.REMOTE_SSH) snapshot.profiles.firstOrNull {
            it.id == snapshot.defaultContainerId && it.mode == ExecutionMode.LOCAL_PROOT
        } ?: ContainerProfile.BUILTIN_ALPINE else active
        val pluginSource = pluginCapture?.let { listOf(it.id, it.installRevision, it.contentHash, it.activationEpoch).joinToString("|") }
        val key = PluginFiles.sha256(listOf(entry?.scope, entry?.sourceIdentity, entry?.configurationRevision,
            entry?.overlaySourceIdentity, entry?.overlayRevision,
            pluginSource, configRepository.serialize(listOf(cfg)), profile, snapshot.selectionRevision,
            snapshot.configurationRevision, snapshot.defaultContainerId, active, sourceWorkspace).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        if (workspaceRepository.selection.value !== selection) throw CancellationException("MCP workspace selection changed")
        entry?.let(configRepository::checkEntryCurrent)
        val workspace = if (active.mode == ExecutionMode.REMOTE_SSH && profile.isChroot) {
            workspaceRepository.localMcpWorkspace(key)
        } else sourceWorkspace
        if (workspaceRepository.selection.value !== selection) throw CancellationException("MCP workspace selection changed")
        entry?.let(configRepository::checkEntryCurrent)
        val source = synchronized(stdioSources) {
            val old = stdioSources[cfg.name]
            if (old != null && !old.connected && old.key == key && old.selection === selection && runCatching { old.checkCurrent() }.isSuccess) old else {
                old?.active = false
                val generation = connectionGeneration.incrementAndGet()
                val fingerprint = "$key:$generation"
                StdioSource(key, selection, RuntimeOwner(RuntimeOwnerKind.MCP, fingerprint), workspace, profile,
                    snapshot.configurationRevision, fingerprint) {
                    if (workspaceRepository.selection.value !== selection) throw CancellationException("MCP workspace selection changed")
                    containerSettingsRepository.checkProfileSnapshot(snapshot)
                    entry?.let(configRepository::checkEntryCurrent)
                    pluginCapture?.checkActive()
                }.also { stdioSources[cfg.name] = it }
            }
        }
        source.checkCurrent()
        if (!profile.isChroot) containerEngine.notReadyHintFor(profile)?.let { throw IllegalStateException(it) }
        val capture = if (profile.isChroot) runtimeCommands.capture(profile, source.configurationRevision, workspace,
            source.owner, source.fingerprint, source::checkCurrent) else null
        source.checkCurrent()
        return StdioTransport(cfg.name, containerEngine, cfg.command!!, cfg.args, workspace, cfg.env, profile,
            runtimeCommands = runtimeCommands, runtimeCapture = capture, checkSource = source::checkCurrent,
            sourceChanges = sourceChanges)
    }

    init {
        pluginManager.registerRevocationListener { id ->
            pluginManager.snapshot.value.plugins.firstOrNull { it.record.id == id }?.content?.mcp?.forEach {
                oauthManager.cancel(it.config.name)
            }
            synchronized(activeClients) {
                pluginClients.filterValues { it.capture.id == id }.keys.toList().forEach { name ->
                    stdioSources.remove(name)?.active = false
                    teardownPluginServer(name)
                }
                pendingPluginClients.filterValues { it.first.id == id }.keys.toList().forEach { name ->
                    pendingPluginClients.remove(name)?.second?.close()
                }
            }
        }
    }

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    private val combinedStatuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = combinedStatuses.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.flow.combine(_statuses, pluginStatuses) { user, plugin -> user + plugin }.collect { combinedStatuses.value = it }
        }
        scope.launch {
            pluginManager.ensureLoaded()
            pluginManager.snapshot.map {
                pluginManager.mcpServers().map { contribution ->
                    PluginRevision(contribution.capture.id, contribution.capture.installRevision, contribution.capture.contentHash,
                        contribution.capture.activationEpoch, contribution.value.config)
                }
            }.distinctUntilChanged().collectLatest { syncPluginServers() }
        }
        // 跟随当前工作区切换自动重载（首帧立即发射当前值，等价启动即重连；
        // 项目切换时合并配置与 stdio 工具的项目路径都会变化，需要重建连接）。
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
                reloadMutex.withLock { syncPluginServersLocked(forceStdio = true) }
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内自动重载，使新增/删除/启停即时生效。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到 MCP 配置文件外部变更，自动重载")
                reload()
            }
        }
        // 容器 profile 切换：stdio server 的进程跑在旧容器的 rootfs 上，必须重建才能用新容器；
        // HTTP server 不依赖容器，不受影响。drop(1) 跳过启动首帧（reload 已处理）。
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        scope.launch {
            containerSettingsRepository.profileConfigurationRevisions.drop(1).collect { reloadStdioServers() }
        }
        // 默认容器变化：远程模式下 stdio server 运行在默认容器上，同样需要重建。
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveEntries().filterNot { it.server.name.startsWith(PLUGIN_MCP_PREFIX) }
        FileLogger.i(TAG, "重新加载 MCP 配置，共 ${servers.size} 个 server")

        teardown()

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // 先把所有 server 置为「连接中/禁用」，UI 立即有反馈。
        _statuses.value = servers.map { entry ->
            val cfg = entry.server
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // 并行连接所有启用的 server；各自独立失败。
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.server.enabled }.map { entry ->
                async { connectOne(entry) }
            }.awaitAll()
        }

        // 合并禁用项与连接结果，保持原始顺序。
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { entry ->
            byName[entry.server.name] ?: McpServerStatus(entry.server.name, McpServerStatus.State.DISABLED)
        }
    }

    private suspend fun connectOne(entry: McpServerEntry): McpServerStatus {
        val cfg = entry.server
        var ownedClient: McpClient? = null
        val checkSource = {
            configRepository.checkEntryCurrent(entry)
            val selection = workspaceRepository.selection.value
            if (entry.workspaceSelectionId.isBlank() || entry.workspaceSelectionId != selection.id ||
                entry.workspacePath != selection.workspace?.takeIf { it.available }?.path) {
                throw CancellationException("MCP configuration workspace source changed")
            }
        }
        val t0 = System.currentTimeMillis()
        return try {
            checkSource()
            FileLogger.i(TAG, "[${cfg.name}] 开始连接（${if (cfg.isStdio) "stdio" else "HTTP"}）")
            if (!cfg.isStdio && cfg.auth != null) {
                oauthManager.prepare(cfg)
            }
            val transport = if (cfg.isStdio) {
                stdioTransport(cfg, entry)
            } else {
                StreamableHttpTransport(
                    endpoint = cfg.url.orEmpty(),
                    client = okHttpClient,
                    extraHeaders = cfg.headers,
                    authorizationProvider = { oauthManager.cachedToken(cfg.name) }
                )
            }
            checkSource()
            val client = McpClient(serverName = cfg.name, transport = transport)
            ownedClient = client
            client.connect()
            checkSource()

            val tools = client.tools.map { McpTool(client, it, checkSource) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(activeClients) {
                checkSource()
                if (cfg.isStdio) stdioSources[cfg.name]?.connected = true
                activeClients[cfg.name] = client
                enabledTools.forEach { tool ->
                    toolRegistry.register(tool.name, tool)
                    registeredToolNames.add(tool.name)
                }
            }
            ownedClient = null
            FileLogger.i(TAG, "[${cfg.name}] 连接成功，注册 ${enabledTools.size}/${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: McpAuthRequiredException) {
            FileLogger.i(TAG, "[${cfg.name}] 需要 OAuth 授权（${System.currentTimeMillis() - t0}ms）")
            McpServerStatus(cfg.name, McpServerStatus.State.NEEDS_AUTH)
        } catch (e: CancellationException) {
            // reload 被新的配置/工作区变更取消是正常流程，不当连接失败上报。
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "[${cfg.name}] 连接失败（${System.currentTimeMillis() - t0}ms）", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        } finally {
            ownedClient?.close()
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: pluginClients[serverName]?.client?.tools ?: emptyList()
        }
    }

    /**
     * 仅重连单个 server（保存/启用开关/编辑刷新用），其他 server 的连接与已注册工具不受影响。
     * 配置中不存在该 name 时静默返回。
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        if (name.startsWith(PLUGIN_MCP_PREFIX)) { syncPluginServersLocked(forceName = name); return@withLock }
        val entry = configRepository.getEffectiveEntries().firstOrNull { it.server.name == name } ?: return@withLock
        val cfg = entry.server
        teardownServer(name)
        if (_statuses.value.none { it.name == name }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name == name) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(entry)
    }

    /**
     * 仅重连当前未连接的 server（已连接的跳过），新会话时兜底：manageMcp 新增/删除只改配置不重连，
     * 提示「下次会话生效」；本方法让新会话真正连上未连接的 server，不打断已连接的工具。
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        syncPluginServersLocked()
        val servers = configRepository.getEffectiveEntries().filter { it.server.enabled && !it.server.name.startsWith(PLUGIN_MCP_PREFIX) }
        for (entry in servers) {
            val cfg = entry.server
            val connected = synchronized(activeClients) { activeClients.containsKey(cfg.name) }
            if (connected) continue
            reconnectOne(entry)
        }
    }

    /**
     * 异步兜底重连未连接的 server（不阻塞调用方）。新会话创建不应等待 MCP 就绪：
     * stdio server 首跑可能长时间卡在下载依赖/握手超时，同步等待会让「新建会话」看起来卡死。
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(entry: McpServerEntry) {
        val cfg = entry.server
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(entry) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** 解析 stdio server 的运行时容器：本地模式用当前 profile，远程 SSH 模式用默认容器。 */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** 当前激活 profile：按 id 从配置列表解析，找不到回退内置 Alpine。 */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 默认容器：设置的 id 且为本地 PRoot 模式；找不到/被删/是远程则回退内置 Alpine。 */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 容器相关配置变化后重建所有 stdio server（HTTP 不依赖容器，不动）。
     * 旧进程钉在旧容器的 rootfs 上，必须 teardown 后才能用新容器拉起；单个 server 失败不影响其它。
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        syncPluginServersLocked(forceStdio = true)
        val servers = configRepository.getEffectiveEntries().filter { it.server.enabled && it.server.isStdio && !it.server.name.startsWith(PLUGIN_MCP_PREFIX) }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "容器配置变化，重建 ${servers.size} 个 stdio MCP server")
        for (entry in servers) {
            val cfg = entry.server
            teardownServer(cfg.name)
            reconnectOne(entry)
        }
    }

    /** 删除 server 时仅断开其连接并反注册其工具，不影响其他 server。 */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        stdioSources.remove(name)?.active = false
        teardownServer(name)
        _statuses.value = _statuses.value.filterNot { it.name == name }
    }

    /**
     * 批量删除：一次持锁断开多个 server（列表多选批量删除）。
     * 逐条调用 [removeServer] 会反复抢 reloadMutex，服务端较多时串行等待明显。
     */
    suspend fun removeServers(names: Collection<String>) = reloadMutex.withLock {
        if (names.isEmpty()) return@withLock
        names.forEach { stdioSources.remove(it)?.active = false; teardownServer(it) }
        _statuses.value = _statuses.value.filterNot { it.name in names }
    }

    /** 触发某个 server 的 OAuth 授权；成功后重连使其工具可用。 */
    suspend fun authorize(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val contribution = if (name.startsWith(PLUGIN_MCP_PREFIX)) pluginManager.mcpServers().firstOrNull { it.value.config.name == name } else null
        val cfg = contribution?.value?.config ?: if (name.startsWith(PLUGIN_MCP_PREFIX)) null
            else configRepository.getEffectiveServers().firstOrNull { it.name == name }
        if (cfg == null) return@withContext Result.failure(McpOAuthException("未找到 MCP server: $name"))
        runCatching {
            contribution?.capture?.checkCurrent()
            oauthManager.authorize(cfg)
            contribution?.capture?.checkCurrent()
            reloadServer(name)
        }
    }

    /** 退出登录：清除该 server 的令牌并重连（将再次变为需要授权）。 */
    suspend fun logout(name: String) = withContext(Dispatchers.IO) {
        val contribution = if (name.startsWith(PLUGIN_MCP_PREFIX)) pluginManager.mcpServers().firstOrNull { it.value.config.name == name } else null
        val cfg = contribution?.value?.config ?: if (name.startsWith(PLUGIN_MCP_PREFIX)) null
            else configRepository.getEffectiveServers().firstOrNull { it.name == name }
        if (cfg == null) return@withContext
        contribution?.capture?.checkCurrent()
        oauthManager.logout(cfg)
        contribution?.capture?.checkCurrent()
        reloadServer(name)
    }

    /** 取消某个 server 正在进行的 OAuth 授权。 */
    fun cancelAuthorize(name: String) {
        oauthManager.cancel(name)
    }

    private fun teardownServer(name: String) {
        synchronized(activeClients) {
            val client = activeClients.remove(name) ?: return
            stdioSources[name]?.takeIf { it.connected }?.active = false
            runCatching { client.close() }
            client.tools.forEach { tool ->
                toolRegistry.unregister(tool.name)
                registeredToolNames.remove(tool.name)
            }
        }
    }

    suspend fun syncPluginServers() = reloadMutex.withLock { syncPluginServersLocked() }

    private suspend fun syncPluginServersLocked(forceName: String? = null, forceStdio: Boolean = false) {
        val contributions = pluginManager.mcpServers()
        val wanted = contributions.associateBy { it.value.config.name }
        val duplicateNames = contributions.groupingBy { it.value.config.name }.eachCount().filterValues { it > 1 }.keys
        val revisions = contributions.associate { contribution -> contribution.value.config.name to PluginRevision(
            contribution.capture.id, contribution.capture.installRevision, contribution.capture.contentHash,
            contribution.capture.activationEpoch, contribution.value.config) }
        val previousStatuses = pluginStatuses.value.associateBy { it.name }
        val removed = synchronized(activeClients) {
            pluginClients.filter { (name, entry) ->
                val current = wanted[name]
                current == null || name in duplicateNames || entry.revision != PluginRevision(current.capture.id, current.capture.installRevision,
                    current.capture.contentHash, current.capture.activationEpoch, current.value.config) ||
                    forceName == name || forceStdio && entry.revision.config.isStdio
            }.keys.toList()
        }
        removed.forEach(::teardownPluginServer)
        val nextStatuses = mutableListOf<McpServerStatus>()
        for (contribution in contributions) {
            val config = contribution.value.config
            val capture = contribution.capture
            if (config.name in duplicateNames) {
                if (nextStatuses.none { it.name == config.name }) nextStatuses += McpServerStatus(config.name, McpServerStatus.State.FAILED, error = "PLUGIN_MCP_NAMESPACE_COLLISION")
                continue
            }
            val existing = synchronized(activeClients) { pluginClients[config.name] }
            if (existing != null) {
                nextStatuses += McpServerStatus(config.name, McpServerStatus.State.CONNECTED, existing.tools.size)
                continue
            }
            if (!config.enabled) { nextStatuses += McpServerStatus(config.name, McpServerStatus.State.DISABLED); continue }
            val revision = revisions.getValue(config.name)
            if (pluginRevisions[config.name] == revision && previousStatuses[config.name] != null && forceName != config.name &&
                !(forceStdio && config.isStdio)) {
                nextStatuses += previousStatuses.getValue(config.name)
                continue
            }
            var ownedClient: McpClient? = null
            try {
                capture.checkCurrent()
                val transport = if (config.isStdio) {
                    stdioTransport(config, pluginCapture = capture)
                } else {
                    if (config.auth != null) oauthManager.prepare(config)
                    StreamableHttpTransport(config.url.orEmpty(), okHttpClient, config.headers,
                        authorizationProvider = { oauthManager.cachedToken(config.name) })
                }
                val client = McpClient(config.name, transport)
                ownedClient = client
                synchronized(activeClients) { capture.checkActive(); pendingPluginClients[config.name] = capture to client }
                client.connect()
                capture.checkCurrent()
                val enabledTools = client.tools.filter { it.name !in config.disabledTools }.map { descriptor ->
                    McpTool(client, descriptor) { capture.checkActive() }
                }
                synchronized(activeClients) {
                    capture.checkActive()
                    if (activeClients.containsKey(config.name) || enabledTools.any { toolRegistry.getTool(it.name) != null }) {
                        throw IllegalStateException("Plugin MCP namespace is already owned")
                    }
                    if (config.isStdio) stdioSources[config.name]?.connected = true
                    pluginClients[config.name] = PluginConnection(revision, capture, client, enabledTools)
                    enabledTools.forEach { toolRegistry.register(it.name, it) }
                }
                ownedClient = null
                nextStatuses += McpServerStatus(config.name, McpServerStatus.State.CONNECTED, enabledTools.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: McpAuthRequiredException) { nextStatuses += McpServerStatus(config.name, McpServerStatus.State.NEEDS_AUTH) }
            catch (_: Exception) { nextStatuses += McpServerStatus(config.name, McpServerStatus.State.FAILED, error = "PLUGIN_MCP_CONNECTION_FAILED") }
            finally {
                synchronized(activeClients) { pendingPluginClients.remove(config.name) }
                ownedClient?.close()
            }
        }
        pluginRevisions = revisions
        pluginStatuses.value = nextStatuses
    }

    private fun teardownPluginServer(name: String) = synchronized(activeClients) {
        val entry = pluginClients.remove(name) ?: return@synchronized
        stdioSources[name]?.takeIf { it.connected }?.active = false
        entry.tools.forEach { tool -> if (toolRegistry.getTool(tool.name) === tool) toolRegistry.unregister(tool.name) }
        entry.client.close()
    }

    private fun teardown() {
        synchronized(activeClients) {
            registeredToolNames.forEach { toolRegistry.unregister(it) }
            registeredToolNames.clear()
            activeClients.forEach { (name, client) ->
                stdioSources[name]?.takeIf { it.connected }?.active = false
                runCatching { client.close() }
            }
            activeClients.clear()
        }
    }
}
