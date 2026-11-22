package com.aicode.feature.terminal.domain

import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 终端标签的运行状态。Finished 保留在列表里不移除，供用户/AI 回看输出。
 */
sealed interface RunState {
    data object Running : RunState
    data class Finished(val exitCode: Int) : RunState
}

/** 后台任务完成通知携带的终端输出行数。 */
const val TAIL_LINES = 50

/** 对外暴露的终端快照上限，避免超宽终端行或异常输出长期占用应用堆。 */
const val MAX_TERMINAL_OUTPUT_CHARS = 256 * 1024

/** 保留开头与结尾，限制终端快照的字符数。 */
internal fun boundTerminalOutput(output: String, maxChars: Int = MAX_TERMINAL_OUTPUT_CHARS): String {
    require(maxChars > 0)
    if (output.length <= maxChars) return output
    val headChars = maxChars / 2
    val tailChars = maxChars - headChars
    return buildString(maxChars + 64) {
        append(output, 0, headChars)
        append("\n\n…[terminal output truncated; head and tail kept]…\n\n")
        append(output, output.length - tailChars, output.length)
    }
}

/** 截取终端 transcript 的最后 n 行；null 输入返回 null。 */
fun String?.takeTailLines(n: Int): String? =
    this?.lines()?.takeLast(n)?.joinToString("\n")

/**
 * 后台命令结束时 emit 的事件，供 ViewModel 订阅后通知 AI。
 */
data class TabFinishedEvent(
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    /** 发起该后台命令的会话 id；回调据此路由回原会话，而非用户当前所在会话。 */
    val sourceSessionId: String?,
    /** 结束时终端 transcript 的最后 [TAIL_LINES] 行快照。事件可能被缓存到会话空闲才合并发送，期间标签可能已关闭，故在 emit 处提前截取。 */
    val tailOutput: String? = null,
    val sourceMode: String? = null,
    val channelGeneration: String? = null
)

/**
 * 一个终端标签：会话 + 渲染视图 + 元数据。
 *
 * [view] 由 Compose 在创建 [TerminalView] 后回填；切换标签时复用同一会话、重新挂载视图。
 * [client] 的 viewProvider 始终读 [view]，故无论视图如何重建都能把输出刷到当前挂载的视图。
 *
 * 本地与远程终端模式共用此类型——区别仅在 [session] 背后的 [com.termux.terminal.SessionBackend]
 * （本地 SubprocessBackend fork 进程，远程 SSH shell 流）。
 */
class TerminalTab(
    val id: String,
    title: String,
    val session: TerminalSession,
    val isBackground: Boolean,
    val command: String?,
    val notifyOnExit: Boolean = false,
    /** 发起该后台命令的会话 id；交互标签为 null。回调据此路由回原会话。 */
    val sourceSessionId: String? = null,
    val client: com.termux.terminal.TerminalSessionClient? = null,
    runState: RunState,
    val sourceMode: String? = null,
    val channelGeneration: String? = null,
    val ownerPluginId: String? = null,
    val ownerResourceId: String? = null
) {
    var title: String = title
        internal set

    @Volatile
    var view: TerminalView? = null

    var runState: RunState = runState
        internal set

    internal var commandFinished: Boolean = false
    internal val outputState = TerminalOutputState()
    internal var markerExitCode: Int? = null

    internal val outputRevision: Long
        get() {
            val emulator = session.emulator
            return outputState.observe(emulator?.screen, emulator?.mColumns ?: 0, emulator?.mRows ?: 0)
        }

    internal fun readOutput(): String {
        val emulator = session.emulator
        val screen = emulator?.screen
        return outputState.read(screen, emulator?.mColumns ?: 0, emulator?.mRows ?: 0) {
            boundTerminalOutput(screen?.transcriptText?.trimEnd('\n') ?: "")
        }
    }

    internal fun finish(exitCode: Int): Boolean {
        if (runState !is RunState.Running) return false
        val completedCommand = isBackground && notifyOnExit
        runState = RunState.Finished(if (completedCommand) markerExitCode ?: exitCode else exitCode)
        commandFinished = true
        onUserInput = {}
        // backend 关闭可能同步回调；先冻结逻辑结果，不能让强杀状态覆盖命令退出码。
        if (completedCommand) runCatching { session.finishIfRunning() }
        return true
    }

    var onUserInput: () -> Unit = {}
        internal set

    /**
     * 是否已向 AI 发出过完成事件。兜底监控与 onFinished 都可能触发完成，
     * 用此标记保证同一命令只回调一次，避免进程迟退时重复通知。
     */
    @Volatile
    var finishedNotified: Boolean = false
}

/**
 * 终端标签摘要，供 AI 的 terminal 工具列出标签时使用（不携带 session/view 等运行时对象）。
 */
data class TabInfo(
    val id: String,
    val title: String,
    val isBackground: Boolean,
    val running: Boolean,
    val command: String?
)
