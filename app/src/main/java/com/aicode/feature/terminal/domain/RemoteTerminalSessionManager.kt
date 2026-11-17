package com.aicode.feature.terminal.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.acquireSshResource
import com.aicode.feature.agent.domain.container.withSshSetup
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import net.schmizz.sshj.connection.channel.direct.Session
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val EXIT_MARKER_PREFIX = "[command exited: "
private const val EXIT_MARKER_POLL_MS = 1_000L
private const val EXIT_MARKER_GRACE_MS = 1_500L

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    // 后台命令退出兜底监控（见 monitorBackgroundExit），随单例生命周期常驻。
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 仅当当前模式是 REMOTE_SSH 且已连接时才可使用。 */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(command, isBackground = true, notify = notify, title = title, sourceSessionId = sourceSessionId)
        monitorBackgroundExit(id)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?
    ): String {
        val execution = WorkspaceExecutionContext.current()
        execution?.requireMode(ExecutionMode.REMOTE_SSH)
        val identity = execution?.remoteSource?.identity ?: connection.captureIdentity()
        val channelGeneration = identity.generation.toString()
        val id = nextId()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = acquireSshResource(close = { it: Session.Shell -> it.close() }) {
            execution?.checkCurrent()
            connection.checkIdentity(identity)
            withSshSetup(identity.owner.startSession()) { session ->
                execution?.checkCurrent()
                connection.checkIdentity(identity)
                session.allocateDefaultPTY()
                session.startShell().also {
                    execution?.checkCurrent()
                    connection.checkIdentity(identity)
                }
            }
        }
        val backend = SshShellBackend(shell)
        var terminal: TerminalSession? = null
        try {
            execution?.checkCurrent()
            connection.checkIdentity(identity)
            val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
            terminal = termSession
            termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
            val wsPath = execution?.remoteSource?.workspaceRoot ?: workspaceRepository.currentPath()
            if (wsPath.isNotBlank() && wsPath != "/") {
                execution?.checkCurrent()
                val cd = if (execution == null) {
                    "cd ~/workspace 2>/dev/null || cd ${shellQuote(wsPath.trimEnd('/'))} 2>/dev/null"
                } else {
                    "cd ${shellQuote(wsPath.trimEnd('/'))} || exit"
                }
                termSession.write(cd + "\n")
            }
            if (command != null) {
                val init = if (notify) {
                    "$command; ec=\$?; echo \"$EXIT_MARKER_PREFIX\$ec]\"; exit \$ec"
                } else {
                    "$command; ec=\$?; echo \"$EXIT_MARKER_PREFIX\$ec]\"; exec /bin/sh"
                }
                execution?.checkCurrent()
                termSession.write(init + "\n")
            }
            val tab = TerminalTab(
                id = id,
                title = title ?: id,
                session = termSession,
                isBackground = isBackground,
                command = command,
                notifyOnExit = notify,
                sourceSessionId = sourceSessionId,
                runState = RunState.Running,
                sourceMode = ExecutionMode.REMOTE_SSH.name,
                channelGeneration = channelGeneration
            )
            execution?.checkCurrent()
            connection.checkIdentity(identity)
            addTab(tab)
            if (_activeTabId.value == null) _activeTabId.value = id
            return id
        } catch (failure: Throwable) {
            runCatching { terminal?.finishIfRunning() }.exceptionOrNull()?.let(failure::addSuppressed)
            if (terminal == null || terminal.emulator == null) backend.close()
            runCatching { shell.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty()) protectInput(tab)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty()) protectInput(tab)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        if (arr.isNotEmpty()) protectInput(tab)
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching { tab.readOutput() }.getOrDefault("")
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        releaseKeepalive(id)
        tab.onUserInput = {}
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()
        FileLogger.i(TAG, "关闭远程终端标签 $id")
        return true
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        _activeTabId.value?.let { writeToTab(it, text) }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        _activeTabId.value?.let { writeBytesToTab(it, *bytes) }
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        tab.onUserInput = { this.tab(tab.id)?.let { protectInput(it) } }
        TaskKeepaliveController.acquire(context, keepaliveOwner(tab.id), cpu = tab.isBackground, network = tab.isBackground)
        bumpRevision()
    }

    private fun keepaliveOwner(id: String): String = "remote:$id"

    private fun protectInput(tab: TerminalTab) {
        if (tab.runState is RunState.Running) {
            TaskKeepaliveController.acquireForInput(context, "${keepaliveOwner(tab.id)}:input")
        }
    }

    private fun releaseKeepalive(id: String) {
        TaskKeepaliveController.release(context, keepaliveOwner(id))
        TaskKeepaliveController.release(context, "${keepaliveOwner(id)}:input")
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
        KeepaliveNotificationState.updateTerminals(
            TerminalNotificationSource.SSH,
            _tabs.value.filter { it.runState is RunState.Running }.map {
                TerminalNotificationSession(it.id, it.isBackground && !it.commandFinished)
            }
        )
    }

    /**
     * 远程后台命令的退出兜底：交互式 shell channel 不保证在命令结束时及时关闭，单靠
     * onSessionFinished 可能永远收不到；轮询输出中的退出标记强制收尾（与本地实现对齐）。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch(Dispatchers.Main.immediate) {
            var seenExitCode: Int? = null
            var lastRevision = -1L
            while (true) {
                val current = tab(tabId) ?: return@launch
                if (current.runState !is RunState.Running) return@launch
                if (seenExitCode == null) {
                    if (current.outputRevision != lastRevision) {
                        val output = getTabOutput(tabId) ?: return@launch
                        lastRevision = current.outputRevision
                        seenExitCode = extractExitCode(output)
                        current.markerExitCode = seenExitCode
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    val tab2 = tab(tabId) ?: return@launch
                    if (tab2.runState is RunState.Running) {
                        tab2.commandFinished = true
                        if (tab2.notifyOnExit) {
                            finishTab(tab2, seenExitCode)
                        } else {
                            TaskKeepaliveController.acquire(context, keepaliveOwner(tabId), cpu = false, network = false)
                            bumpRevision()
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun extractExitCode(output: String): Int? = commandExitCode(output)

    private fun finishTab(tab: TerminalTab, exitCode: Int) {
        if (!tab.finish(exitCode)) return
        val completedExitCode = (tab.runState as RunState.Finished).exitCode
        releaseKeepalive(tab.id)
        bumpRevision()
        if (tab.notifyOnExit && !tab.finishedNotified) {
            tab.finishedNotified = true
            _tabFinishedEvents.tryEmit(
                TabFinishedEvent(
                    tab.id, tab.title, tab.command, completedExitCode, tab.sourceSessionId,
                    tailOutput = getTabOutput(tab.id)?.takeTailLines(TAIL_LINES),
                    sourceMode = tab.sourceMode,
                    channelGeneration = tab.channelGeneration
                )
            )
        }
    }

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.let { tab ->
                tab.outputState.changed()
                tab.view?.onScreenUpdated()
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                val exitCode = if (target.isBackground && target.notifyOnExit) {
                    target.markerExitCode ?: extractExitCode(getTabOutput(target.id).orEmpty()) ?: finishedSession.exitStatus
                } else finishedSession.exitStatus
                finishTab(target, exitCode)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            if (text.isNotEmpty()) {
                session?.let { target -> _tabs.value.firstOrNull { it.session === target }?.let { writeToTab(it.id, text) } }
            }
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
