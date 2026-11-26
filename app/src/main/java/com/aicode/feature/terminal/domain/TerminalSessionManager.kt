package com.aicode.feature.terminal.domain

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryRepository
import com.aicode.feature.terminal.presentation.component.AppTerminalSessionClient
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.termux.terminal.TerminalSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内常驻的终端会话池：所有 [TerminalSession] 的唯一所有者。
 *
 * 之所以放在 [Singleton] 而非 ViewModel：终端要「常驻后台」，离开终端页/切到聊天页都不能杀会话。
 * ViewModel 绑定在导航路由上、出栈即 onCleared，会连带 finishIfRunning 杀掉 proot——故把会话
 * 所有权上移到本管理器，ViewModel 退化为只读观察层。只要 App 进程还活着，会话就一直在跑。
 *
 * 每个标签有稳定且对 AI 友好的唯一 id（`term-N`）。AI 可凭 id：
 *  - [startBackgroundCommand] 把 `npm run dev` 之类挂后台并拿到 id；
 *  - [sendInput] 按 id 持续发命令；
 *  - [writeBytesToTab] 按 id 发送控制字符（如 Ctrl-C=0x03）；
 *  - [closeTab] 按 id 关闭并销毁会话；
 *  - [getTabOutput] 按 id 读终端内容（emulator 屏幕缓冲）。
 *
 * 所有可变状态读写都在主线程（UI 事件、AI 工作流派发到主线程的调用），不额外加锁。
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val historyRepository: TerminalTaskHistoryRepository,
    private val runtimeCommands: LocalRuntimeCommands,
    private val executionFactory: WorkspaceExecutionContextFactory
) : TerminalSessionProvider {
    private companion object {
        const val TAG = "TerminalSessionManager"
        const val TRANSCRIPT_ROWS = 2000
        // 无视图挂载时用于「就地启动」会话的默认终端尺寸；视图挂载后会按真实尺寸 resize。
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        /** 命令打印 `[command exited: N]` 后等待正常 onFinished 回调的缓冲（毫秒）。 */
        const val EXIT_MARKER_GRACE_MS = 1_500L
        /** 完成兜底监控轮询屏幕缓冲的间隔（毫秒）。旧值 200ms 每轮都会构建 2000 行 transcript 字符串，
         *  长命令（构建/安装）期间持续占 CPU；间隔放宽到 1s 并配合「输出无增长跳过扫描」后开销可忽略。 */
        const val EXIT_MARKER_POLL_MS = 1_000L
        /** 交互标签创建后多久仍无任何输出就 dump 容器诊断（proot 没起来/卡住时的主动取证）。 */
        const val SILENT_DIAGNOSTIC_DELAY_MS = 10_000L
        const val PLUGIN_CLEANUP_TIMEOUT_MS = 6_000L
    }

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** 触发任一标签输出/状态变化时自增，供 Compose 重组拉取最新屏幕内容。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)
    private var pendingInteractiveTabId: String? = null

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 是否有后台终端标签仍在运行（用于判断 KeepaliveService 能否停）。 */
    fun hasBackgroundTabs(): Boolean =
        _tabs.value.any { it.isBackground && it.runState is RunState.Running }

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /**
     * 新建一个交互 shell 标签并设为当前。返回新标签 id。
     *
     * 首次会触发 rootfs/proot 解压（幂等）；失败抛异常由调用方处理。
     */
    suspend fun createInteractiveTab(): String {
        val id = pendingInteractiveTabId ?: nextId().also { pendingInteractiveTabId = it }
        val owner = RuntimeOwner(RuntimeOwnerKind.TERMINAL, id)
        val execution = executionFactory.freeze(runtimeOwner = owner)
        return withContext(execution) {
            execution.requireMode(ExecutionMode.LOCAL_PROOT)
            if (isChrootExecution(execution)) {
                createChrootTab(id, null, null, false, null, owner, execution).also { _activeTabId.value = it }
            } else createTab(id)
        }.also { pendingInteractiveTabId = null }
    }

    private suspend fun createTab(id: String): String {
        FileLogger.i(TAG, "新建交互终端标签：开始准备容器")
        ensureContainer()
        val shellCommand = buildInteractiveCommand()
        FileLogger.i(TAG, "交互 shell 命令（$id）：$shellCommand")
        val (session, client) = try {
            buildSession(shellCommand)
        } catch (e: Exception) {
            FileLogger.e(TAG, "创建交互终端会话失败（$id）", e)
            containerEngine.logContainerDiagnostics("创建交互终端会话失败")
            throw e
        }
        addTab(
            TerminalTab(
                id = id,
                title = id,
                session = session,
                isBackground = false,
                command = null,
                client = client,
                runState = RunState.Running,
                capturedExecution = WorkspaceExecutionContext.current()
            )
        )
        _activeTabId.value = id
        FileLogger.i(TAG, "新建交互终端标签 $id：pid=${session.pid} 运行中=${session.isRunning}")
        scheduleSilentSessionDiagnostic(id)
        return id
    }

    /**
     * 交互 shell 启动命令。-w 已把 cwd 设为 /root/workspace，cd 仅作兜底；裸 sh/bash 在 tty 上自动进交互模式，
     * 靠 ENV=/etc/profile 加载登录环境；exec 让 shell 取代外层 sh -c 成为前台交互 shell。
     * 启动时跑首次初始化菜单（脚本自行判断已完成/已跳过则秒退）；用 `;` 分隔保证脚本任何失败都不阻塞进入 shell。
     */
    private fun buildInteractiveCommand(): String {
        val startup = "[ -f /root/.aicode/provision.sh ] && sh /root/.aicode/provision.sh; "
        return "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
            startup +
            "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '; " +
            "alias ls='ls --color=auto' 2>/dev/null; alias grep='grep --color=auto' 2>/dev/null; alias ll='ls -la --color=auto' 2>/dev/null; " +
            "exec ${containerEngine.defaultShell()}"
    }

    /**
     * 静默会话诊断：交互标签创建后若迟迟没有任何输出，说明 proot 可能没起来或卡在内核态
     * （用户报「解压完成后终端一片空白、卡住」正是这种形态）。主动 dump 一次容器状态，
     * 让日志里留下可定位的证据，而不是只能看到「新建标签」后再无下文。
     */
    private fun scheduleSilentSessionDiagnostic(tabId: String) {
        monitorScope.launch {
            delay(SILENT_DIAGNOSTIC_DELAY_MS)
            val silent = withContext(Dispatchers.Main.immediate) {
                val tab = tab(tabId) ?: return@withContext false
                tab.runState is RunState.Running && getTabOutput(tabId).isNullOrBlank()
            }
            if (!silent) return@launch
            FileLogger.w(TAG, "终端标签 $tabId 启动 ${SILENT_DIAGNOSTIC_DELAY_MS}ms 后仍无任何输出，dump 容器状态")
            containerEngine.logContainerDiagnostics("终端标签 $tabId 静默")
        }
    }

    /**
     * 供 AI 预留接口：把一条命令挂后台跑（如 `npm run dev`），返回唯一 tabId。
     *
     * 命令跑完后 `exec /bin/sh` 保活，使该标签仍是一个可继续输入的会话（dev server 退出后也能复用），
     * 且输出全程留在 emulator 缓冲里，用户切过去或 AI 用 [getTabOutput] 都能看到累计输出。
     */
    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        sourceSessionId?.let { RuntimeOwner(RuntimeOwnerKind.AGENT, it) }
        val execution = WorkspaceExecutionContext.current()
        if (isChrootExecution(execution)) {
            val id = nextId()
            val owner = terminalRuntimeOwner(id, sourceSessionId)
            return createChrootTab(id, command, title, notify, sourceSessionId, owner, requireNotNull(execution))
        }
        val protectedTask = terminalTaskHistoryEligible(notify, sourceSessionId)
        if (protectedTask) {
            requireTerminalHistorySession(requireNotNull(sourceSessionId))
            requireNotNull(execution) { "Captured local execution required for terminal history" }
            execution.requireMode(ExecutionMode.LOCAL_PROOT)
        }
        ensureContainer()
        val historyScope = if (protectedTask) {
            captureTerminalTaskScope(requireNotNull(execution))
                ?: error("Terminal history execution scope unavailable")
        } else null
        val historyId = if (protectedTask) UUID.randomUUID().toString() else null
        val id = terminalTaskTabId(historyId, ::nextId)
        val taskTitle = if (protectedTask) (title ?: id).takeTerminalChars(MAX_TERMINAL_TASK_TITLE_CHARS) else title ?: id
        var startedSession: TerminalSession? = null
        try {
            if (historyScope != null && historyId != null) {
                val now = System.currentTimeMillis()
                historyRepository.create(TerminalTaskRecord(
                    historyId = historyId, tabId = id, title = taskTitle,
                    sourceSessionId = requireNotNull(sourceSessionId), scope = historyScope,
                    startedAt = now, updatedAt = now
                ))
            }
            // 用 `ec=$?` 捕获命令真实退出码后再 echo，否则 echo 本身恒为 0 会覆盖进程退出码，导致
            // onFinished 回调里的 exitStatus 永远是 0、与屏幕上 echo 出来的码对不上。
            // notify=true：echo 后 `exit $ec` 让 shell 以命令真实退出码结束 → proot 透传 → 回调 exitStatus
            //   正确，且 shell 自然退出稳定触发 MSG_PROCESS_EXITED；否则脚本末尾是 echo，进程退出码被
            //   污染成 0，而某些情况下进程不干净退出还会导致回调不触发。
            // notify=false：echo 后 `exec ${shell}` 保活，标签可复用（dev server 退出后也能继续输入）；
            //   此时进程不退出、不回调，退出码无意义，符合 dev server 场景设计。
            val afterCommand = if (notify) "; exit \$ec" else "; exec ${containerEngine.defaultShell()}"
            val shellCommand = "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
            execution?.checkCurrent()
            if (historyScope != null) {
                check(captureTerminalTaskScope(requireNotNull(execution)) == historyScope) {
                    "Terminal history execution scope changed"
                }
            }
            val (session, client) = buildSession(shellCommand)
            startedSession = session
            execution?.checkCurrent()
            addTab(
                TerminalTab(
                    id = id,
                    title = taskTitle,
                    session = session,
                    isBackground = true,
                    command = command,
                    notifyOnExit = notify,
                    sourceSessionId = sourceSessionId,
                    client = client,
                    runState = RunState.Running,
                    sourceMode = ExecutionMode.LOCAL_PROOT.name,
                    channelGeneration = null,
                    historyId = historyId,
                    capturedExecution = execution
                )
            )
            if (historyId != null && !session.isRunning) finishTab(requireNotNull(tab(id)), session.exitStatus)
        } catch (failure: Throwable) {
            startedSession?.let { runCatching { it.finishIfRunning() } }
            if (historyId != null) withContext(NonCancellable) {
                runCatching { historyRepository.unknown(historyId, "", 0) }
            }
            throw failure
        }
        // 后台命令不抢占当前标签焦点：仅当没有活动标签时才设为当前。
        if (_activeTabId.value == null) _activeTabId.value = id

        monitorBackgroundExit(id)
        if (historyId != null) monitorTaskHistory(id)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    override suspend fun startOwnedBackgroundCommand(command: String, title: String?, notify: Boolean, owner: RuntimeOwner): String {
        require(owner.kind == RuntimeOwnerKind.AGENT)
        val execution = WorkspaceExecutionContext.current() ?: executionFactory.freeze(runtimeOwner = owner)
        require(execution.runtimeOwner == owner) { "Terminal execution owner mismatch" }
        return withContext(execution) { startBackgroundCommand(command, title, notify, owner.scopeId) }
    }

    private suspend fun createChrootTab(
        id: String,
        command: String?,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?,
        owner: RuntimeOwner,
        execution: WorkspaceExecutionContext,
        pluginOwner: TerminalResourceOwner? = null,
        onOutput: ((ByteArray) -> Unit)? = null
    ): String {
        execution.requireMode(ExecutionMode.LOCAL_PROOT)
        require(execution.runtimeOwner == owner) { "Terminal execution owner mismatch" }
        val capture = requireNotNull(execution.localRuntimeCapture) { "CHROOT execution capture required" }
        require(capture.owner == owner) { "Terminal runtime owner mismatch" }
        val shell = execution.profileSnapshot?.activeProfile?.shellPath ?: execution.localMapping?.profile?.shellPath ?: "/bin/sh"
        val argv = if (command == null) listOf(shell, "-i") else {
            val script = if (notify || pluginOwner != null) command else "$command; exec ${shellQuote(shell)} -i"
            listOf(shell, "-c", script)
        }
        val task = runtimeCommands.startPty(capture, argv, columns = DEFAULT_COLUMNS, rows = DEFAULT_ROWS)
        try {
            execution.checkCurrent()
            val (session, client) = openSession(onOutput) { inputClient ->
                TerminalSession(TRANSCRIPT_ROWS, inputClient, ChrootSessionBackend(task))
            }
            execution.checkCurrent()
            val tab = TerminalTab(
                id, title ?: id, session, command != null, command,
                notifyOnExit = notify, sourceSessionId = sourceSessionId, client = client,
                runState = RunState.Running, sourceMode = ExecutionMode.LOCAL_PROOT.name,
                runtimeOwner = owner, runtimeTask = task,
                ownerPluginId = pluginOwner?.pluginId, ownerResourceId = pluginOwner?.resourceId,
                capturedExecution = execution
            )
            addTab(tab)
            if (pluginOwner == null && _activeTabId.value == null) _activeTabId.value = id
            if (task.taskState.value != null) finishTab(tab, task.exitValue())
            monitorScope.launch(Dispatchers.Main.immediate) {
                task.awaitCleanup()
                if (tab(id) === tab) bumpRevision()
            }
            return id
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                task.stop()
                if (pluginOwner == null) task.awaitCleanup() else awaitPluginCleanup(task)
            }
            throw failure
        }
    }

    override suspend fun startPluginCommand(command: String, title: String?, owner: TerminalResourceOwner, onOutput: (ByteArray) -> Unit): String {
        val execution = WorkspaceExecutionContext.current() ?: error("Captured execution required")
        execution.requireMode(ExecutionMode.LOCAL_PROOT)
        val runtimeOwner = requireNotNull(execution.runtimeOwner) { "Plugin execution owner required" }
        require(runtimeOwner.kind == RuntimeOwnerKind.PLUGIN) { "Plugin execution owner mismatch" }
        if (isChrootExecution(execution)) {
            return createChrootTab(nextId(), command, title, false, null, runtimeOwner, execution, owner, onOutput)
        }
        ensureContainer()
        execution.checkCurrent()
        val id = nextId()
        val shell = containerEngine.defaultShell()
        val (session, client) = buildSession("export ENV=/etc/profile; cd ~/workspace || exit; exec ${com.aicode.feature.agent.domain.tool.explorer.shellQuote(shell)} -c ${com.aicode.feature.agent.domain.tool.explorer.shellQuote(command)}", onOutput)
        try {
            execution.checkCurrent()
            addTab(TerminalTab(id, title ?: id, session, true, command, client = client,
                runState = RunState.Running, sourceMode = ExecutionMode.LOCAL_PROOT.name,
                ownerPluginId = owner.pluginId, ownerResourceId = owner.resourceId, runtimeOwner = runtimeOwner,
                capturedExecution = execution))
            return id
        } catch (failure: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable) {
                session.finishIfRunning()
                while (session.isRunning) delay(25)
            }
            throw failure
        }
    }

    override fun pluginTabStatus(id: String, owner: TerminalResourceOwner): TerminalTabStatus? =
        tab(id)?.takeIf { it.isOwnedBy(owner) }?.let { tab ->
            val task = tab.runtimeTask
            if (task == null) TerminalTabStatus(tab.session.isRunning, (tab.runState as? RunState.Finished)?.exitCode)
            else when (val result = task.taskState.value) {
                null -> TerminalTabStatus(true, null, exitKnown = false, taskStatus = "RUNNING",
                    cleanupStatus = task.cleanupState.value?.name ?: "PENDING")
                LocalRuntimeTaskResult.Unknown -> TerminalTabStatus(tab.session.isRunning, null, exitKnown = false, taskStatus = "UNKNOWN",
                    cleanupStatus = task.cleanupState.value?.name ?: "PENDING")
                is LocalRuntimeTaskResult.Exited -> TerminalTabStatus(tab.session.isRunning, result.terminalStatus, taskStatus = "EXITED",
                    cleanupStatus = task.cleanupState.value?.name ?: "PENDING")
            }
        }

    override fun writePluginInput(id: String, owner: TerminalResourceOwner, text: String, appendNewline: Boolean): Boolean =
        tab(id)?.takeIf { it.isOwnedBy(owner) && it.runtimeTask?.taskState?.value == null }
            ?.let { sendInput(id, text, appendNewline) } ?: false

    override fun interruptPluginTab(id: String, owner: TerminalResourceOwner): Boolean =
        tab(id)?.takeIf { it.isOwnedBy(owner) && it.runtimeTask?.taskState?.value == null }
            ?.let { writeBytesToTab(id, 3) } ?: false

    override suspend fun closePluginTab(id: String, owner: TerminalResourceOwner): Boolean {
        val tab = tab(id)?.takeIf { it.isOwnedBy(owner) } ?: return false
        tab.runtimeTask?.let { task ->
            tab.onUserInput = {}
            tab.session.setRawOutputListener(null)
            if (task.taskState.value == null) task.stop()
            val cleanup = awaitPluginCleanup(task)
            closeTab(id)
            return cleanup == LocalRuntimeCleanup.VERIFIED
        }
        tab.session.finishIfRunning()
        while (tab.session.isRunning) delay(25)
        tab.session.setRawOutputListener(null)
        closeTab(id)
        return true
    }

    private suspend fun awaitPluginCleanup(task: LocalRuntimeProcess): LocalRuntimeCleanup = withContext(NonCancellable) {
        withTimeoutOrNull(PLUGIN_CLEANUP_TIMEOUT_MS) { task.awaitCleanup() } ?: LocalRuntimeCleanup.UNKNOWN
    }

    private fun runningTab(id: String): TerminalTab? {
        val t = tab(id) ?: return null
        return if (t.runState is RunState.Running) t else null
    }

    /** 按 id 向标签发送输入并回车执行（AI 持续发命令的入口）。返回是否命中标签且仍活跃。 */
    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = runningTab(id) ?: return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始文本，不自动追加回车。 */
    override fun writeToTab(id: String, text: String): Boolean {
        val tab = runningTab(id) ?: return false
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = runningTab(id) ?: return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        if (arr.isNotEmpty()) protectInput(tab)
        tab.session.write(arr, 0, arr.size)
        return true
    }

    private fun ownedInputTab(id: String, owner: RuntimeOwner): TerminalTab? =
        runningTab(id)?.takeIf { it.isAccessibleTo(owner) }?.also { it.checkOwnedInputExecution() }

    override fun sendOwnedInput(id: String, owner: RuntimeOwner, input: String, appendNewline: Boolean): Boolean =
        ownedInputTab(id, owner)?.let { sendInput(it.id, input, appendNewline) } ?: false

    override fun writeOwnedText(id: String, owner: RuntimeOwner, text: String): Boolean =
        ownedInputTab(id, owner)?.let { writeToTab(it.id, text) } ?: false

    override fun writeOwnedBytes(id: String, owner: RuntimeOwner, vararg bytes: Int): Boolean =
        ownedInputTab(id, owner)?.let { writeBytesToTab(it.id, *bytes) } ?: false

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        _activeTabId.value?.let { writeToTab(it, text) }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        _activeTabId.value?.let { writeBytesToTab(it, *bytes) }
    }

    /**
     * 按 id 读取终端内容（emulator 屏幕缓冲的完整 transcript），供 AI 拉取。
     * 返回 null 表示无此标签。
     */
    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching { tab.readOutput() }.getOrDefault("")
    }

    /** 列出全部标签的摘要（id/标题/是否后台/运行状态/命令），供 AI 选目标。 */
    override fun listTabs(): List<TabInfo> = _tabs.value.filter { it.ownerPluginId == null }.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command,
            historyId = it.historyId,
            historyError = it.historyError,
            runtimeOwner = it.runtimeOwner,
            taskStatus = it.runtimeTask?.let { task -> when (task.taskState.value) {
                null -> "RUNNING"
                LocalRuntimeTaskResult.Unknown -> "UNKNOWN"
                is LocalRuntimeTaskResult.Exited -> "EXITED"
            } },
            cleanupStatus = it.runtimeTask?.let { task -> task.cleanupState.value?.name ?: "PENDING" },
            exitCode = (it.runState as? RunState.Finished)?.takeIf { state -> state.exitKnown }?.exitCode,
            exitKnown = (it.runState as? RunState.Finished)?.exitKnown ?: true
        )
    }

    override fun resizeOwnedTab(id: String, owner: RuntimeOwner, columns: Int, rows: Int): Boolean {
        require(columns in 1..4096 && rows in 1..4096)
        val tab = runningTab(id)?.takeIf { it.isAccessibleTo(owner) } ?: return false
        tab.session.updateSize(columns, rows)
        return true
    }

    override fun signalOwnedTab(id: String, owner: RuntimeOwner, signal: Int): Boolean {
        val tab = runningTab(id)?.takeIf { it.isAccessibleTo(owner) } ?: return false
        if (tab.runtimeTask != null) return tab.runtimeTask.signal(signal)
        return signal == 2 && writeBytesToTab(id, 3)
    }

    override suspend fun closeOwnedTab(id: String, owner: RuntimeOwner): TerminalCloseResult? {
        val tab = tab(id)?.takeIf { it.isAccessibleTo(owner) } ?: return null
        if (!closeTab(id)) return null
        val cleanup = tab.runtimeTask?.let { task -> withContext(NonCancellable) { task.awaitCleanup() } }
        return TerminalCloseResult(cleanup)
    }

    /** 切换当前标签。 */
    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** 关闭并销毁标签（用户主动关 / AI close）。从列表移除并杀会话。 */
    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.historyId != null && tab.runState is RunState.Running) {
            val output = getTabOutput(id).orEmpty()
            val observedExit = tab.markerExitCode ?: extractExitCode(output)
                ?: if (!tab.session.isRunning) tab.session.exitStatus else null
            if (observedExit != null) {
                finishTab(tab, observedExit)
            } else {
                tab.historyDetached = true
                val revision = tab.outputRevision
                monitorScope.launch(Dispatchers.Main.immediate) {
                    runCatching { historyRepository.unknown(tab.historyId, output, revision) }
                        .onFailure { tab.historyError = TERMINAL_HISTORY_IO_ERROR }
                }
            }
        }
        releaseKeepalive(id)
        tab.onUserInput = {}
        tab.session.setRawOutputListener(null)
        runCatching { tab.session.finishIfRunning() }
        tab.runtimeTask?.let { task ->
            monitorScope.launch { task.awaitCleanup() }
        }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()

        FileLogger.i(TAG, "关闭终端标签 $id")
        return true
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    private fun isChrootExecution(execution: WorkspaceExecutionContext?): Boolean =
        execution?.localRuntimeCapture != null || execution?.profileSnapshot?.activeProfile?.isChroot == true ||
            execution?.localMapping?.profile?.isChroot == true

    private suspend fun ensureContainer() {
        check(!isChrootExecution(WorkspaceExecutionContext.current())) {
            "CHROOT cannot use PRoot installation"
        }
        val startedAt = System.currentTimeMillis()
        containerEngine.ensureInstalled()
        if (!containerEngine.isContainerInstalled()) {
            throw IllegalStateException("容器未安装（缺少 rootfs/proot）")
        }
        FileLogger.i(TAG, "容器就绪（耗时 ${System.currentTimeMillis() - startedAt}ms）")
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /**
     * 完成兜底监控：termux 的会话结束依赖 PTY 读到 EOF，而 proot 宿主进程在 Android 上可能
     * 概率性不退出（即使其 bash 已执行 exit，proot 仍攥着 /dev/pts 不释放），导致 onFinished
     * 永不回调、notify=true 的任务不触发通知。bash 在真正退出前必打印 `[command exited: N]`，
     * 以此作为命令结束的可靠信号：监控到后短缓冲（给正常回调留时间），仍 Running 则强制收尾。
     */
    /** 在输出文本的末尾行中定位退出标记 `[command exited: N]` 并解析退出码；未找到返回 null。
     *  要求退出标记必须作为独立行出现（以换行开头或位于文本起始），且从末尾向后定位，避免 PTY 命令行回显误判。 */
    private fun extractExitCode(output: String): Int? = commandExitCode(output)

    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch(Dispatchers.Main.immediate) {
            var seenExitCode: Int? = null
            var lastRevision = -1L
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running || tab.runtimeTask != null) return@launch
                if (seenExitCode == null) {
                    if (tab.outputRevision != lastRevision) {
                        val output = getTabOutput(tabId) ?: return@launch
                        lastRevision = tab.outputRevision
                        seenExitCode = extractExitCode(output)
                        tab.markerExitCode = seenExitCode
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // 缓冲后再查：正常 onFinished 回调若已触发，状态不再 Running，此处直接退出。
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        current.commandFinished = true
                        if (current.notifyOnExit) {
                            finishTab(current, seenExitCode)
                        } else {
                            TaskKeepaliveController.acquire(appContext, keepaliveOwner(tabId), cpu = false, network = false)
                            bumpRevision()
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun monitorTaskHistory(tabId: String) {
        monitorScope.launch(Dispatchers.Main.immediate) {
            var lastRevision = -1L
            while (true) {
                delay(1_500L)
                val current = tab(tabId) ?: return@launch
                val historyId = current.historyId ?: return@launch
                if (current.runState !is RunState.Running || current.historyDetached) return@launch
                val revision = current.outputRevision
                if (revision == lastRevision) continue
                val output = current.readOutput()
                lastRevision = revision
                try {
                    historyRepository.snapshot(historyId, output, revision)
                    if (current.runState is RunState.Running && !current.historyDetached) current.historyError = null
                } catch (_: Exception) {
                    if (current.runState is RunState.Running && !current.historyDetached) current.historyError = TERMINAL_HISTORY_IO_ERROR
                }
                bumpRevision()
            }
        }
    }

    private fun finishTab(tab: TerminalTab, exitCode: Int) {
        if (tab.historyDetached || tab.runState !is RunState.Running) return
        val output = getTabOutput(tab.id).orEmpty()
        val revision = tab.outputRevision
        if (!tab.finish(exitCode)) return
        val completed = tab.runState as RunState.Finished
        val completedExitCode = completed.exitCode
        releaseKeepalive(tab.id)
        bumpRevision()
        val notify = tab.ownerPluginId == null && tab.notifyOnExit && !tab.finishedNotified
        if (notify) tab.finishedNotified = true
        if (tab.historyId == null) {
            if (notify) emitFinished(tab, completedExitCode, output)
            return
        }
        monitorScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    historyRepository.finish(tab.historyId, output, revision, completedExitCode)
                    tab.historyError = null
                } catch (_: Exception) {
                    tab.historyError = TERMINAL_HISTORY_IO_ERROR
                } finally {
                    bumpRevision()
                    if (notify) emitFinished(tab, completedExitCode, output)
                }
            }
        }
    }

    private fun emitFinished(tab: TerminalTab, exitCode: Int, output: String) {
        _tabFinishedEvents.tryEmit(TabFinishedEvent(
            tab.id, tab.title, tab.command, exitCode, tab.sourceSessionId,
            tailOutput = output.takeTailLines(TAIL_LINES),
            sourceMode = tab.sourceMode,
            channelGeneration = tab.channelGeneration,
            historyId = tab.historyId,
            historyError = tab.historyError,
            exitKnown = (tab.runState as? RunState.Finished)?.exitKnown ?: true
        ))
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        tab.onUserInput = { runningTab(tab.id)?.let { protectInput(it) } }
        TaskKeepaliveController.acquire(appContext, keepaliveOwner(tab.id), cpu = tab.isBackground, network = tab.isBackground)
        bumpRevision()
    }

    private fun writeToSession(session: TerminalSession, text: String) {
        if (text.isNotEmpty()) _tabs.value.firstOrNull { it.session === session }?.let { protectInput(it) }
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
        KeepaliveNotificationState.updateTerminals(
            TerminalNotificationSource.LOCAL,
            _tabs.value.filter { it.runState is RunState.Running }.map {
                TerminalNotificationSession(it.id, it.isBackground && !it.commandFinished)
            }
        )
    }

    /**
     * 构造一个进入容器的 PTY 会话，并接好输出/结束回调。
     *
     * client 的 viewProvider/onFinished 都以 session 为键回查 [_tabs]：会话与标签一一对应，
     * 故无需把 tab 引用提前注入 client（避免「构造 client 时 tab 还不存在」的先有鸡先有蛋）。
     */
    private fun buildSession(shellCommand: String, onOutput: ((ByteArray) -> Unit)? = null): Pair<TerminalSession, AppTerminalSessionClient> {
        val execution = WorkspaceExecutionContext.current()
        execution?.requireMode(ExecutionMode.LOCAL_PROOT)
        check(!isChrootExecution(execution)) { "CHROOT cannot use a PRoot session" }
        val workspace = execution?.localMapping?.workspaceRoot?.absolutePath ?: workspaceRepository.currentPath()
        val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
        FileLogger.i(
            TAG,
            "PTY 启动：workspace=$workspace 可执行=${invocation.executable}" +
                "（存在=${java.io.File(invocation.executable).exists()}）argv=${invocation.argv.joinToString(" ")}"
        )
        FileLogger.d(TAG, "PTY 环境变量键：${invocation.env.keys.joinToString(",")}")
        return openSession(onOutput) { inputClient ->
            TerminalSession(
                invocation.executable,
                appContext.filesDir.absolutePath,
                invocation.argv.toTypedArray(),
                invocation.ptyEnvArray,
                TRANSCRIPT_ROWS,
                inputClient
            )
        }
    }

    private fun openSession(
        onOutput: ((ByteArray) -> Unit)? = null,
        create: (com.termux.terminal.TerminalSessionClient) -> TerminalSession
    ): Pair<TerminalSession, AppTerminalSessionClient> {
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    val exitCode = if (target.runtimeTask == null && target.isBackground && target.notifyOnExit) {
                        target.markerExitCode ?: extractExitCode(getTabOutput(target.id).orEmpty()) ?: finished.exitStatus
                    } else finished.exitStatus
                    finishTab(target, exitCode)
                }
            }
        )
        val inputTrackingClient = object : com.termux.terminal.TerminalSessionClient by client {
            override fun onTextChanged(changedSession: TerminalSession) {
                _tabs.value.firstOrNull { it.session === changedSession }?.outputState?.changed()
                client.onTextChanged(changedSession)
            }

            override fun onPasteTextFromClipboard(target: TerminalSession?) {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return
                if (text.isNotEmpty() && target != null) writeToSession(target, text)
            }
        }
        WorkspaceExecutionContext.current()?.checkCurrent()
        session = create(inputTrackingClient)
        if (onOutput != null) session.setRawOutputListener { _, data, length -> onOutput(data.copyOf(length)) }
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        FileLogger.i(TAG, "PTY 会话已创建：pid=${session.pid} 运行中=${session.isRunning}")
        return session to client
    }

    private fun keepaliveOwner(id: String): String = "local:$id"

    private fun protectInput(tab: TerminalTab) {
        if (tab.runState is RunState.Running) {
            TaskKeepaliveController.acquireForInput(appContext, "${keepaliveOwner(tab.id)}:input")
        }
    }

    private fun releaseKeepalive(id: String) {
        TaskKeepaliveController.release(appContext, keepaliveOwner(id))
        TaskKeepaliveController.release(appContext, "${keepaliveOwner(id)}:input")
    }
}
