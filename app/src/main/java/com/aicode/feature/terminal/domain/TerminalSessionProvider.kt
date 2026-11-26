package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import kotlinx.coroutines.flow.SharedFlow

/**
 * 终端会话后端抽象：把"终端会话在哪运行"从硬编码的本地 Termux PTY 解耦。
 *
 * [TerminalSessionTool]（AI 的 terminal 工具）依赖本接口而非具体实现，
 * 由 DI 委托层按当前执行模式转发到对应实现：
 * - [TerminalSessionManager]：本地 Termux PTY（fork proot 进程）；
 * - `RemoteTerminalSessionManager`：远程 SSH shell channel。
 */
interface TerminalSessionProvider {

    /** 后台命令结束时 emit 的事件，供 ViewModel 订阅后通知 AI。 */
    val tabFinishedEvents: SharedFlow<TabFinishedEvent>

    /** 把一条命令挂后台跑（如 `npm run dev`），返回唯一 tabId。 */
    suspend fun startBackgroundCommand(
        command: String,
        title: String? = null,
        notify: Boolean = false,
        sourceSessionId: String? = null
    ): String

    suspend fun startPluginCommand(
        command: String,
        title: String?,
        owner: TerminalResourceOwner,
        onOutput: (ByteArray) -> Unit
    ): String = throw UnsupportedOperationException("Plugin terminals are unavailable")

    fun pluginTabStatus(id: String, owner: TerminalResourceOwner): TerminalTabStatus? = null

    fun writePluginInput(id: String, owner: TerminalResourceOwner, text: String, appendNewline: Boolean): Boolean = false

    fun interruptPluginTab(id: String, owner: TerminalResourceOwner): Boolean = false

    suspend fun closePluginTab(id: String, owner: TerminalResourceOwner): Boolean = false

    /** 按 id 向标签发送输入并回车执行。返回是否命中标签且仍活跃。 */
    fun sendInput(id: String, input: String, appendNewline: Boolean = true): Boolean

    /** 按 id 向标签写入原始文本，不自动追加回车。 */
    fun writeToTab(id: String, text: String): Boolean

    /** 按 id 向标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToTab(id: String, vararg bytes: Int): Boolean

    /** 按 id 读取终端内容（emulator 屏幕缓冲）。返回 null 表示无此标签。 */
    fun getTabOutput(id: String): String?

    /** 列出全部标签的摘要。 */
    fun listTabs(): List<TabInfo>

    /** 关闭并销毁标签。返回是否成功。 */
    fun closeTab(id: String): Boolean

    suspend fun startOwnedBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        owner: RuntimeOwner
    ): String {
        require(owner.kind == RuntimeOwnerKind.AGENT)
        return startBackgroundCommand(command, title, notify, owner.scopeId)
    }

    fun listOwnedTabs(owner: RuntimeOwner): List<TabInfo> = listTabs().filter { it.runtimeOwner == owner }

    fun getOwnedTabOutput(id: String, owner: RuntimeOwner): String? =
        if (listOwnedTabs(owner).any { it.id == id }) getTabOutput(id) else null

    fun sendOwnedInput(id: String, owner: RuntimeOwner, input: String, appendNewline: Boolean = true): Boolean =
        listOwnedTabs(owner).any { it.id == id } && sendInput(id, input, appendNewline)

    fun writeOwnedText(id: String, owner: RuntimeOwner, text: String): Boolean =
        listOwnedTabs(owner).any { it.id == id } && writeToTab(id, text)

    fun writeOwnedBytes(id: String, owner: RuntimeOwner, vararg bytes: Int): Boolean =
        listOwnedTabs(owner).any { it.id == id } && writeBytesToTab(id, *bytes)

    fun resizeOwnedTab(id: String, owner: RuntimeOwner, columns: Int, rows: Int): Boolean = false

    fun signalOwnedTab(id: String, owner: RuntimeOwner, signal: Int): Boolean = false

    suspend fun closeOwnedTab(id: String, owner: RuntimeOwner): TerminalCloseResult? =
        if (listOwnedTabs(owner).any { it.id == id } && closeTab(id)) TerminalCloseResult() else null
}

data class TerminalResourceOwner(val pluginId: String, val resourceId: String)

data class TerminalTabStatus(
    val running: Boolean,
    val exitCode: Int?,
    val exitKnown: Boolean = exitCode != null,
    val taskStatus: String? = null,
    val cleanupStatus: String? = null
)

data class TerminalCloseResult(val cleanup: LocalRuntimeCleanup? = null)

internal fun TerminalTab.isOwnedBy(owner: TerminalResourceOwner): Boolean =
    ownerPluginId == owner.pluginId && ownerResourceId == owner.resourceId
