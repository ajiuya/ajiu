package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import java.net.InetAddress
import java.net.Socket
import java.util.EnumSet
import java.util.UUID
import javax.net.SocketFactory
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.userauth.password.PasswordUtils
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/**
 * 共享的 SSH 连接管理器：持有两条独立的 sshj [SSHClient]——exec 通道供 [RemoteSshEngine] 执行命令，
 * SFTP 通道供 [RemoteSftpFileAccess] 读写文件。两者隔离，SFTP 的 Buffer 溢出不会拖垮命令通道。
 *
 * 连接配置（host/port/username/auth）由调用方在 [connect] 时传入。连接断开后下次 [connect]
 * 重新建立；SFTP 通道按需惰性建立（见 [sftp]）。exec 侧操作串行化（[mutex]），SFTP 侧由
 * [RemoteSftpFileAccess] 串行化（sshj 的 SFTPClient 非线程安全）。
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val containerInstaller: ContainerInstaller
) {

    @Volatile
    private var sshClient: SSHClient? = null

    /**
     * 独立的 SFTP 通道：与 exec 各用一条 SSH transport。sshj 的 SFTP 有间歇性 Buffer 溢出
     * （hierynomus/sshj#461），共用 transport 时崩溃会一并拖垮 Bash/终端；分开后只影响文件通道，
     * 崩溃由 [invalidateSftp] + 下次 [sftp] 重建兜底。
     */
    @Volatile
    private var sftpSshClient: SSHClient? = null

    @Volatile
    private var sftpClient: SFTPClient? = null

    private val mutex = Mutex()
    private val sftpLock = Mutex()
    private val credentialSyncMutex = Mutex()
    private val builtinDefinitionMutex = Mutex()
    private val builtinDocsMutex = Mutex()
    internal val sftpOperationMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** 连接状态流，供 UI 显示指示器、工作区初始化等待连接就绪。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 重连成功后回调，供工作区重新加载。由 [startSupervisor] 注册。 */
    private var onReconnected: (suspend () -> Unit)? = null
    private var supervisorJob: Job? = null

    /** 远程服务器上的真实 home 路径（连接成功后查 $HOME 缓存），供文件工具展开 ~。 */
    @Volatile
    var remoteHome: String? = null
        private set

    private val _identityState = MutableStateFlow<RemoteConnectionIdentity?>(null)
    val identityState: StateFlow<RemoteConnectionIdentity?> = _identityState.asStateFlow()

    @Volatile
    private var activeIdentity: RemoteConnectionIdentity? = null
        set(value) {
            field = value
            _identityState.value = value
        }

    private data class GitCredentialScope(val identity: RemoteConnectionIdentity, val workspaceRoot: String)

    @Volatile
    private var gitCredentialScope: GitCredentialScope? = null

    internal val gitCredentialWorkspaceRoot: String?
        get() = gitCredentialScope?.takeIf { it.identity === activeIdentity && isConnected() }?.workspaceRoot

    /** 当前连接配置快照，供重连与路径映射使用。 */
    @Volatile
    var config: RemoteConnectionConfig? = null
        private set

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            var home: String? = null
            val client = acquireSshResource(close = { it: SSHClient -> it.close() }) {
                withSshSetup(newSshClient(config)) { client ->
                    // 查远程真实 home（root 是 /root，普通用户是 /home/xxx），供文件工具展开 ~
                    runCatching {
                        client.startSession().use { session ->
                            val cmd = session.exec("echo \$HOME")
                            home = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                                .use { it.readText().trim().ifEmpty { null } }
                        }
                    }
                    // 注册 transport 断开监听：连接断开时即时推状态，不等 supervisor 轮询
                    client.transport.setDisconnectListener { reason, info ->
                        FileLogger.w(TAG, "SSH 连接断开: $reason, $info")
                        if (sshClient === client) {
                            activeIdentity = null
                            gitCredentialScope = null
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                    }
                    client
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                sshClient = client
                remoteHome = home
                home?.let { activeIdentity = RemoteConnectionIdentity(client, it) }
                _connectionState.value = ConnectionState.CONNECTED
                FileLogger.i(TAG, "SSH 已连接 ${config.host}:${config.port} as ${config.username}")
            } catch (failure: Throwable) {
                if (sshClient === client) sshClient = null
                runCatching { client.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        } catch (e: Exception) {
            activeIdentity = null
            remoteHome = null
            _connectionState.value = ConnectionState.FAILED
            throw e
        }
    }

    fun removeHostKey(host: String, port: Int) {
        hostKeyStore.remove(host, port)
    }

    fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()
    /** 无参 connect：用上次保存的 config 重连。 */
    suspend fun connect() {
        val cfg = config ?: throw IllegalStateException("未配置 SSH 连接")
        connect(cfg)
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        activeIdentity = null
        gitCredentialScope = null
        remoteHome = null
        closeSftpInternal()
        runCatching { sshClient?.disconnect() }
        sshClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** 关闭并清空独立 SFTP 通道；下次 [sftp] 会按当前 config 重建。 */
    private fun closeSftpInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sftpSshClient?.disconnect() }
        sftpClient = null
        sftpSshClient = null
    }

    /** 建立一条 SSH transport 并完成认证与保活（exec 与 SFTP 各建一条）。 */
    private fun newSshClient(
        config: RemoteConnectionConfig,
        setupOwner: ((SSHClient) -> Unit)? = null
    ): SSHClient = withSshSetup(SSHClient()) { client ->
        setupOwner?.invoke(client)
        client.apply {
            addHostKeyVerifier(hostKeyVerifier)
            connect(config.host, config.port)
            when (val auth = config.auth) {
                is RemoteAuth.Password -> authPassword(config.username, auth.password)
                is RemoteAuth.PrivateKey -> {
                    val pem = privateKeyStore.readPem(auth.privateKeyPath)
                    val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    authPublickey(config.username, loadKeys(pem, null, passwordFinder))
                }
            }
            // 启用 SSH 心跳保活，防止空闲超时断连
            runCatching {
                connection.keepAlive?.let {
                    it.setKeepAliveInterval(30)
                    it.start()
                }
            }
        }
    }

    fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    /** 开一个 exec session 执行命令。调用方负责关闭返回的 Command。 */
    fun startExecSession(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return withSshSetup(client.startSession()) { session ->
            session.exec(command).also { cmd ->
                // 不发送 EOF 的非交互命令可能一直等待 stdin。
                runCatching { cmd.outputStream.close() }
            }
        }
    }

    /**
     * 开 exec 会话但**保留 stdin 可写**，供大内容经 stdin 传输（避免命令行参数超过远端 ARG_MAX）。
     * 调用方负责写入并关闭 [Session.Command.outputStream]、读取输出、关闭 session。
     */
    fun startExecSessionWithStdin(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return withSshSetup(client.startSession()) { it.exec(command) }
    }

    /** 开一个新的 Session，供调用方分配 PTY 并启动 shell。调用方负责关闭 Session。 */
    fun startShellSession(): Session {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return client.startSession()
    }

    /**
     * 获取独立的 SFTP client（惰性建立第二条 SSH 连接，与 exec 隔离）。调用方不应关闭它——
     * 由 [disconnect] 统一管理。连接失效（未连接/未认证）时丢弃重建，覆盖 SFTP 通道崩溃后的自愈。
     */
    suspend fun sftp(): SFTPClient = sftpLock.withLock {
        val cfg = config ?: throw IllegalStateException("SSH 未配置")
        sftpClient
            ?.takeIf { sftpSshClient?.isConnected == true && sftpSshClient?.isAuthenticated == true }
            ?.let { return@withLock it }
        closeSftpInternal()
        val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
        sftpSshClient = client
        val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
        sftpClient = sftp
        sftp
    }

    fun captureIdentity(): RemoteConnectionIdentity {
        val identity = activeIdentity ?: throw IllegalStateException("SSH 未连接或远程 home 未就绪")
        checkIdentity(identity)
        return identity
    }

    /** A reconnect, even to the same host/home, invalidates a previously captured identity. */
    fun checkIdentity(identity: RemoteConnectionIdentity) {
        if (activeIdentity !== identity || sshClient !== identity.owner || remoteHome != identity.home || !isConnected()) {
            throw CancellationException("SSH connection identity changed")
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    internal suspend fun openForwardingClient(
        identity: RemoteConnectionIdentity,
        checkCurrent: () -> Unit,
        onDisconnected: () -> Unit
    ): SSHClient {
        val frozenConfig = mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkIdentity(identity)
            checkCurrent()
            config ?: throw CancellationException("SSH configuration changed")
        }
        val owner = ForwardingSetupOwner()
        val context = currentCoroutineContext()
        val cancellation = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) owner.close()
        }
        var delivered = false
        try {
            val client = acquireSshResource(close = { _: SSHClient -> owner.close() }) {
                withSshSetup(newSshClient(frozenConfig) { client ->
                    owner.attach(client)
                    context.ensureActive()
                    checkIdentity(identity)
                    checkCurrent()
                }) { client ->
                    context.ensureActive()
                    checkIdentity(identity)
                    checkCurrent()
                    if (config != frozenConfig) throw CancellationException("SSH configuration changed")
                    client.transport.setDisconnectListener { _, _ -> onDisconnected() }
                    client
                }
            }
            context.ensureActive()
            checkIdentity(identity)
            checkCurrent()
            if (config != frozenConfig) throw CancellationException("SSH configuration changed")
            delivered = true
            return client
        } catch (failure: Exception) {
            context.ensureActive()
            throw failure
        } finally {
            cancellation?.dispose()
            if (!delivered) owner.close()
        }
    }

    private class ForwardingSetupOwner : AutoCloseable {
        private val lock = Any()
        private var closed = false
        private var client: SSHClient? = null
        private val sockets = mutableSetOf<Socket>()

        fun attach(client: SSHClient) {
            synchronized(lock) {
                if (closed) {
                    runCatching { client.close() }
                    throw CancellationException("SSH forwarding setup cancelled")
                }
                this.client = client
            }
            val delegate = client.socketFactory
            client.socketFactory = object : SocketFactory() {
                override fun createSocket(): Socket = own(delegate.createSocket())
                override fun createSocket(host: String, port: Int): Socket = own(delegate.createSocket(host, port))
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                    own(delegate.createSocket(host, port, local, localPort))
                override fun createSocket(host: InetAddress, port: Int): Socket = own(delegate.createSocket(host, port))
                override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                    own(delegate.createSocket(host, port, local, localPort))
            }
        }

        private fun own(socket: Socket): Socket = synchronized(lock) {
            if (closed) {
                runCatching { socket.close() }
                throw CancellationException("SSH forwarding setup cancelled")
            }
            sockets += socket
            socket
        }

        override fun close() {
            val owned = synchronized(lock) {
                if (closed) return
                closed = true
                sockets.toList() to client.also { client = null; sockets.clear() }
            }
            owned.first.forEach { runCatching { it.close() } }
            owned.second?.let {
                runCatching { it.socket?.close() }
                runCatching { it.close() }
            }
        }
    }

    /** Caller owns this subsystem and must close it; it never shares or replaces the cached sftp(). */
    suspend fun openReadChannel(identity: RemoteConnectionIdentity): SFTPClient {
        var owned: SFTPClient? = null
        return try {
            val channel = withContext(Dispatchers.IO) {
                mutex.withLock {
                    checkIdentity(identity)
                    identity.owner.newSFTPClient().also {
                        owned = it
                        currentCoroutineContext().ensureActive()
                        checkIdentity(identity)
                    }
                }
            }
            checkIdentity(identity)
            channel
        } catch (failure: Throwable) {
            // withContext may discard a successfully opened resource on prompt cancellation.
            owned?.let { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    /** 丢弃当前 SFTP 通道（通道异常后调用），下次 [sftp] 会重建。 */
    suspend fun invalidateSftp() = sftpLock.withLock { closeSftpInternal() }

    /** 若已配置但未连接，立即尝试重连一次。返回是否最终连通。供 App 回到前台时主动触发。 */
    suspend fun tryReconnectIfDisconnected(): Boolean {
        val cfg = config ?: return false
        if (isConnected()) return true
        _connectionState.value = ConnectionState.CONNECTING
        return runCatching { connect(cfg) }
            .onSuccess {
                FileLogger.i(TAG, "SSH 重连成功（前台触发）")
                runCatching { onReconnected?.invoke() }
            }
            .onFailure {
                FileLogger.w(TAG, "SSH 重连失败（前台触发）", it)
                _connectionState.value = ConnectionState.FAILED
            }
            .isSuccess
    }

    /**
     * 启动连接监督协程：远程模式下定期探活，连接断开则自动重连（指数退避），
     * 重连成功后触发 [onReconnected] 回调（供工作区重新加载）并维护 [connectionState]。
     * 幂等：重复调用不会起多个 supervisor。仅在远程模式下生效。 */
    fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        this.onReconnected = onReconnected
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var backoffMs = 5000L
            val maxBackoffMs = 30000L
            while (isActive) {
                delay(backoffMs)
                val cfg = config ?: continue
                if (isConnected()) {
                    backoffMs = 15000 // 连接正常，拉长探活间隔（仅兜底，断开有 DisconnectListener 即时通知）
                    continue
                }
                // 连接已断，尝试重连
                tryReconnectIfDisconnected()
                // 重连失败后指数退避
                if (!isConnected()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                } else {
                    backoffMs = 15000
                }
            }
        }
    }

    suspend fun stopSupervisor() {
        supervisorJob?.cancelAndJoin()
        supervisorJob = null
        onReconnected = null
    }

    /**
     * 更新 ~/workspace 符号链接指向当前选中工作区的远程路径，让 AI 用 ~/workspace/... 路径时
     * Bash 命令（pwd 等）能直接访问到正确的工作区目录。应在工作区选中/初始化后调用。
     *
     * `~/workspace` 已是真实目录时跳过（工作区根被配成了 `~/workspace` 的情况）：此时 `ln` 会把
     * 链接建到该目录**内部**，形成自引用死循环，破坏整个工作区；跳过时命令链路会回退到真实路径。
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec(
                    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                        "else ln -sfn ${shellQuote(ws)} ~/workspace 2>/dev/null; echo done; fi"
                )
                val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
                session.close()
                if (out == "skip") {
                    FileLogger.w(TAG, "~/workspace 已是真实目录，跳过符号链接（工作区根可能配成了 ~/workspace）")
                }
            }.onFailure { FileLogger.w(TAG, "更新 workspace 符号链接失败: $ws", it) }
        }
    }

    /** Same assets leave remote edits and deletions untouched; changed assets are reconciled with verified backups. */
    suspend fun uploadDocs(docs: Map<String, String>, identity: RemoteConnectionIdentity? = null) {
        if (docs.isEmpty()) return
        val target = identity ?: captureIdentity()
        val snapshot = BuiltinDocsSync.snapshot(docs)
        val result = builtinDocsMutex.withLock {
            currentCoroutineContext().ensureActive()
            checkIdentity(target)
            withContext(Dispatchers.IO) {
                val channel = openReadChannel(target)
                val syncContext = currentCoroutineContext()
                val checkCurrent = {
                    syncContext.ensureActive()
                    checkIdentity(target)
                }
                withBuiltinDocsChannel(channel) {
                    BuiltinDocsSync(RemoteBuiltinDocsStore(channel, target.home, checkCurrent), checkCurrent).sync(snapshot)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        checkIdentity(target)
        if (result.changed) {
            FileLogger.i(TAG, "内置文档增量同步完成：上传 ${result.uploaded}，删除 ${result.deleted}")
        }
    }

    /**
     * 释放 App 内置资源（子代理定义、面板示例脚本等）到远程 `~/.aicode/<subdir>/`。
     * 仅补齐服务器上缺失的文件、**不覆盖已存在**（保留用户修改），脚本赋可执行位。
     * 与 [uploadDocs] 一样在连接/重连后调用——远程模式下全局 agents/scripts 以远端为准，
     * 内置默认需在远端存在才能「开箱即用」。二进制安全（base64 传输）。
     */
    suspend fun uploadBuiltinFiles(
        destSubdir: String,
        files: Map<String, ByteArray>,
        executable: Boolean = false
    ) {
        if (files.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aicode/" + destSubdir.trim('/')
        withContext(Dispatchers.IO) {
            runCatching {
                sftpOperationMutex.withLock { sftp().mkdirs(destDir) }
                for ((name, bytes) in files) {
                    val dest = "$destDir/$name"
                    val exists = sftpOperationMutex.withLock { sftp().statExistence(dest) != null }
                    if (!exists) writeRemoteFile(dest, bytes, executable, overwrite = false)
                }
                FileLogger.i(TAG, "已释放 ${files.size} 个内置文件到远程 $destDir")
            }.onFailure { FileLogger.w(TAG, "释放内置文件到远程失败: $destSubdir", it) }
        }
    }

    suspend fun uploadBuiltinDefinitions(
        agents: Map<String, ByteArray>,
        skills: Map<String, Map<String, ByteArray>>,
        identity: RemoteConnectionIdentity = captureIdentity()
    ): BuiltinInstallReport = builtinDefinitionMutex.withLock {
        BuiltinDefinitionPaths.validate(agents, skills)
        if (agents.isEmpty() && skills.isEmpty()) return@withLock BuiltinInstallReport()
        withContext(Dispatchers.IO) {
            openReadChannel(identity).use { channel ->
                val installContext = currentCoroutineContext()
                val validateScope = {
                    installContext.ensureActive()
                    checkBuiltinInstallThread()
                    checkIdentity(identity)
                }
                runInterruptible {
                    BuiltinDefinitionInstaller(
                        RemoteBuiltinDefinitionStore(channel, identity.home, validateScope),
                        checkActive = validateScope,
                        onFailure = { name, error -> FileLogger.w(TAG, "释放远程内置定义失败: $name", error) }
                    ).install(agents, skills)
                }
            }
        }
    }

    /**
     * 凭据只经当前连接、当前工作区的条件 include 注入，不接管服务器的全局 Git 配置。
     * 旧版 `~/.aicode/gitconfig` 可能存有用户的署名或代理设置，因此既不重写也不删除。
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) {
        if (!isConnected() || remoteHome == null) return
        // 排队期间可能切换服务器，不能在取得锁后重新选择写入目标。
        val identity = captureIdentity()
        credentialSyncMutex.withLock {
            checkIdentity(identity)
            gitCredentialScope = null
            val aicodeDir = identity.home.trimEnd('/') + "/.aicode"
            val wsRoot = workspaceRoot.trimEnd('/').ifEmpty { return@withLock }
            try {
                withContext(Dispatchers.IO) {
                    openReadChannel(identity).use { channel ->
                        val installContext = currentCoroutineContext()
                        val checkCurrent = {
                            installContext.ensureActive()
                            checkIdentity(identity)
                        }
                        checkCurrent()
                        channel.mkdirs(aicodeDir)
                        val creds = credentials.joinToString("\n") { c ->
                            "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                        }.let { if (it.isNotEmpty()) "$it\n" else "" }
                        containerInstaller.extractCredentialHelper()
                        val helperScript = java.io.File(containerInstaller.aicodeDir, "git-credential-aicode").readText()
                        check(helperScript.isNotBlank()) { "Git credential helper is empty" }
                        val remoteHelperScript = helperScript.replaceFirst(
                            "#!/bin/sh\n",
                            "#!/bin/sh\nexport AICODE_CRED_NO_PROMPT=1\n"
                        )
                        checkCurrent()
                        writeRemoteFile(channel, "$aicodeDir/git-credential-aicode", remoteHelperScript.toByteArray(Charsets.UTF_8), executable = true)
                        checkCurrent()
                        writeRemoteFile(channel, "$aicodeDir/git-credentials", encodeCreds(creds).toByteArray(Charsets.UTF_8))
                        val helper = "!AICODE_CRED_NO_PROMPT=1 ${shellQuote("$aicodeDir/git-credential-aicode")}"
                        val credentialConfig = "[credential]\n    helper = \"${gitConfigValue(helper)}\"\n"
                        checkCurrent()
                        writeRemoteFile(channel, "$aicodeDir/gitconfig.credential", credentialConfig.toByteArray(Charsets.UTF_8))
                        checkCurrent()
                        gitCredentialScope = GitCredentialScope(identity, wsRoot)
                        FileLogger.i(TAG, "已注入 git 凭据到远程 $aicodeDir（限定 $wsRoot/）")
                    }
                }
            } catch (e: CancellationException) {
                gitCredentialScope = null
                throw e
            } catch (e: Exception) {
                gitCredentialScope = null
                currentCoroutineContext().ensureActive()
                FileLogger.w(TAG, "注入 git 凭据到远程失败", e)
            }
        }
    }

    suspend fun removeGitCredentialConfig() {
        if (!isConnected() || remoteHome == null) return
        val identity = captureIdentity()
        credentialSyncMutex.withLock {
            checkIdentity(identity)
            gitCredentialScope = null
            val aicodeDir = identity.home.trimEnd('/') + "/.aicode"
            try {
                withContext(Dispatchers.IO) {
                    openReadChannel(identity).use { channel ->
                        for (name in listOf("gitconfig.credential", "git-credentials", "git-credential-aicode")) {
                            currentCoroutineContext().ensureActive()
                            checkIdentity(identity)
                            val path = "$aicodeDir/$name"
                            if (channel.statExistence(path) != null) channel.rm(path)
                        }
                    }
                    FileLogger.i(TAG, "已撤销远程 git 凭据注入")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                FileLogger.w(TAG, "撤销远程 git 凭据注入失败", e)
            }
        }
    }

    private suspend fun writeRemoteFile(
        dest: String,
        bytes: ByteArray,
        executable: Boolean = false,
        overwrite: Boolean = true
    ) = sftpOperationMutex.withLock {
        writeRemoteFile(sftp(), dest, bytes, executable, overwrite)
    }

    private fun writeRemoteFile(
        client: SFTPClient,
        dest: String,
        bytes: ByteArray,
        executable: Boolean = false,
        overwrite: Boolean = true
    ) {
        val temporary = "$dest.tmp.${UUID.randomUUID()}"
        try {
            client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                client.chmod(temporary, if (executable) 448 else 384)
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(32 * 1024, bytes.size - offset)
                    file.write(offset.toLong(), bytes, offset, count)
                    offset += count
                }
            }
            check(client.size(temporary) == bytes.size.toLong()) { "Remote file size mismatch" }
            if (overwrite) {
                client.rename(temporary, dest, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
            } else if (client.statExistence(dest) == null) {
                client.rename(temporary, dest)
            }
        } finally {
            runCatching { client.rm(temporary) }
        }
    }

    private fun gitConfigValue(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")

    /** 与 app 侧一致的编码：base64 后整体反转（属混淆，非加密）。 */
    private fun encodeCreds(plain: String): String =
        android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP).reversed()
}

/** Opaque transport generation; no password, private key or mutable connection configuration. */
class RemoteConnectionIdentity internal constructor(
    internal val owner: SSHClient,
    val home: String,
    val generation: UUID = UUID.randomUUID()
)

/** 远程 SSH 连接状态，供 UI 指示器与工作区初始化时序判断。 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** 把 SSH 连接异常翻译成友好提示，供工具层回显给 AI/用户。 */
fun friendlySshError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is KeyDecryptionFailedException ->
            "SSH 认证失败：私钥口令不正确或私钥格式不受支持（$msg）"
        e is UserAuthException ->
            "SSH 认证失败：用户名、密码或密钥未被服务器接受（$msg）"
        "ECONNREFUSED" in msg || "Connection refused" in msg ->
            "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确"
        "UnknownHost" in msg || "unknown host" in msg ->
            "无法连接到 SSH 服务器（未知主机），请检查主机地址"
        "Auth fail" in msg || "authentication" in msg ->
            "SSH 认证失败，请检查用户名和密码"
        "Network is unreachable" in msg ->
            "网络不可达，请检查网络连接"
        "SocketTimeout" in msg || "timed out" in msg ->
            "连接 SSH 服务器超时，请检查网络或服务器状态"
        "SSH 未连接" in msg ->
            "SSH 未连接，请等待连接恢复或在设置中检查配置"
        else -> "SSH 连接失败: ${e.message ?: "未知错误"}"
    }
}

/** 远程 SSH 连接配置。 */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** 远程服务器上的工作区根路径（AI 的 ~/workspace 映射到此路径）。 */
    val remoteWorkspacePath: String
)
