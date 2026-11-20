package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import net.schmizz.sshj.connection.channel.direct.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * [CommandEngine] 的远程 SSH 实现：用 sshj exec channel 在远程服务器上执行命令。
 *
 * 共享一个 [RemoteSshConnection]（持有 sshj [SSHClient]）执行命令；文件读写由 [RemoteSftpFileAccess]
 * 走另一条独立的 SFTP transport（见 [RemoteSshConnection.sftp]），两者隔离互不影响。
 *
 * 与 [LinuxContainerEngine] 的语义对应：
 * - [ensureInstalled]：建立/维持 SSH 连接（对应本地解压 rootfs）；
 * - [isContainerInstalled]：SSH 连接是否存活（对应本地 rootfs 是否就绪）；
 * - [isProvisioned]：恒 true（远程工具由用户自行保证，对应本地 apk 装包完成）；
 * - [defaultShell]：/bin/bash（远程服务器通常有 bash）。
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    @OptIn(InternalCoroutinesApi::class)
    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程流式) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = acquireSshResource(close = { it: Session.Command -> it.close() }) {
            startExecSession(buildCdCommand(command, projectPath))
        }
        val resources = SshCommandResources(session)
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            timedOut.set(true)
            FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
            resources.close()
        }
        val execution = WorkspaceExecutionContext.current()
        val sourceCancellation = execution?.onCancellation(resources::close)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true
        ) { cause -> if (cause != null) resources.close() }
        try {
            drainSshOutput(resources).collect { line ->
                execution?.checkCurrent()
                emit(CommandEvent.Line(line))
            }
            // EOF 不代表 exit-status 已到达，超时保护必须覆盖 join。
            session.join()
            currentCoroutineContext().ensureActive()
            execution?.checkCurrent()
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: ${sanitizeCommandForLog(command)}")
                else FileLogger.v(TAG, "命令完成(退出码 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            execution?.checkCurrent()
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            sourceCancellation?.dispose()
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            resources.close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        WorkspaceExecutionContext.current()?.requireMode(ExecutionMode.REMOTE_SSH)
        if (!isContainerInstalled()) return null
        return try {
            execCaptured(command, projectPath, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            WorkspaceExecutionContext.current()?.checkCurrent()
            FileLogger.w(TAG, "远程命令执行失败(连接可能已断): ${sanitizeCommandForLog(command)}", failure)
            null
        }
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs, unbounded = true)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程同步) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = acquireSshResource(close = { it: Session.Command -> it.close() }) {
            startExecSession(buildCdCommand(command, projectPath))
        }
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        val resources = SshCommandResources(session)
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            timedOut.set(true)
            FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
            resources.close()
        }
        var exitCode: Int? = null
        val execution = WorkspaceExecutionContext.current()
        val sourceCancellation = execution?.onCancellation(resources::close)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true
        ) { cause -> if (cause != null) resources.close() }
        try {
            drainSshOutput(resources).collect { line ->
                execution?.checkCurrent()
                output.append(line + "\n")
            }
            session.join()
            currentCoroutineContext().ensureActive()
            execution?.checkCurrent()
            exitCode = session.exitStatus
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            execution?.checkCurrent()
            if (!timedOut.get()) throw failure
        } finally {
            sourceCancellation?.dispose()
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            resources.close()
        }
        currentCoroutineContext().ensureActive()
        execution?.checkCurrent()
        if (timedOut.get()) {
            output.append("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]\n")
            exitCode = null
        }
        FileLogger.v(TAG, "命令完成(远程, 退出码 $exitCode，输出 ${output.totalChars} 字符): ${sanitizeCommandForLog(command)}")
        CommandResult(output.build(), exitCode, output.truncated)
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        WorkspaceExecutionContext.current()?.let {
            it.requireMode(ExecutionMode.REMOTE_SSH)
            return@withLock
        }
        if (connection.isConnected()) {
            _initProgress.value = ContainerInitState.Ready
            return@withLock
        }
        _initProgress.value = ContainerInitState.InstallingPackages(line = "正在连接 SSH 服务器…")
        try {
            connection.connect()
            _initProgress.value = ContainerInitState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FileLogger.e(TAG, "SSH 连接失败", e)
            val friendly = friendlySshError(e)
            _initProgress.value = ContainerInitState.Failed(friendly)
            throw RuntimeException(friendly, e)
        }
    }

    private fun startExecSession(command: String): Session.Command {
        val execution = WorkspaceExecutionContext.current() ?: return connection.startExecSession(command)
        execution.requireMode(ExecutionMode.REMOTE_SSH)
        val identity = execution.remoteSource?.identity ?: throw CancellationException("Remote execution identity missing")
        connection.checkIdentity(identity)
        return withSshSetup(identity.owner.startSession()) { session ->
            execution.checkCurrent()
            connection.checkIdentity(identity)
            session.exec(command).also {
                execution.checkCurrent()
                connection.checkIdentity(identity)
                runCatching { it.outputStream.close() }
            }
        }
    }

    /** 拼接 cd 到 projectPath 再执行 command 的完整命令；projectPath 为 null 则直接执行。
     *  优先 cd 到 ~/workspace（符号链接），让 AI 执行 pwd 时看到 ~/workspace 而非真实路径。
     *  ~/workspace 不存在（符号链接未建成）时 fallback 到 projectPath。 */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        val prefix = gitCredentialEnvPrefix()
        if (projectPath == null) return prefix + command
        WorkspaceExecutionContext.current()?.let { execution ->
            execution.requireMode(ExecutionMode.REMOTE_SSH)
            val root = execution.remoteSource?.workspaceRoot ?: throw CancellationException("Remote execution root missing")
            return prefix + "cd ${shellQuote(root)} && $command"
        }
        return prefix + "cd ~/workspace 2>/dev/null || cd ${shellQuote(projectPath)} 2>/dev/null; $command"
    }

    private fun gitCredentialEnvPrefix(): String {
        val root = connection.gitCredentialWorkspaceRoot ?: return ""
        // gitdir 条件是 glob，工作区名称中的通配字符必须按字面匹配。
        val pattern = root.replace("\\", "\\\\").replace("*", "\\*")
            .replace("?", "\\?").replace("[", "\\[").replace("]", "\\]")
        val key = shellQuote("includeIf.gitdir:$pattern/.path")
        return "export GIT_CONFIG_COUNT=1; " +
            "export GIT_CONFIG_KEY_0=$key; " +
            "export GIT_CONFIG_VALUE_0=\"\${HOME}/.aicode/gitconfig.credential\"; "
    }
}
