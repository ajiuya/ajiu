package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TerminalSessionProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [TerminalSessionProvider] 在何时被首次注入，
 * 真正使用终端时才读取当前模式。
 */
@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val local: TerminalSessionManager,
    private val remote: RemoteTerminalSessionManager
) : TerminalSessionProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun delegate(): TerminalSessionProvider {
        val execution = WorkspaceExecutionContext.current()
        execution?.checkCurrent()
        val mode = execution?.mode ?: modeHolder.currentMode()
        return if (mode == ExecutionMode.REMOTE_SSH) remote else local
    }

    // 切换执行模式后，旧后端的后台任务仍可能完成，因此两侧事件必须常驻订阅。
    @OptIn(ExperimentalCoroutinesApi::class)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> =
        merge(local.tabFinishedEvents, remote.tabFinishedEvents)
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String = delegate().startBackgroundCommand(command, title, notify, sourceSessionId)

    override suspend fun startPluginCommand(command: String, title: String?, owner: TerminalResourceOwner, onOutput: (ByteArray) -> Unit): String =
        delegate().startPluginCommand(command, title, owner, onOutput)

    private fun pluginBackend(id: String, owner: TerminalResourceOwner): TerminalSessionProvider? =
        listOf(local, remote).firstOrNull { it.pluginTabStatus(id, owner) != null }

    override fun pluginTabStatus(id: String, owner: TerminalResourceOwner): TerminalTabStatus? =
        pluginBackend(id, owner)?.pluginTabStatus(id, owner)

    override fun writePluginInput(id: String, owner: TerminalResourceOwner, text: String, appendNewline: Boolean): Boolean =
        pluginBackend(id, owner)?.writePluginInput(id, owner, text, appendNewline) ?: false

    override fun interruptPluginTab(id: String, owner: TerminalResourceOwner): Boolean =
        pluginBackend(id, owner)?.interruptPluginTab(id, owner) ?: false

    override suspend fun closePluginTab(id: String, owner: TerminalResourceOwner): Boolean =
        pluginBackend(id, owner)?.closePluginTab(id, owner) ?: false

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegate().sendInput(id, input, appendNewline)

    override fun writeToTab(id: String, text: String): Boolean =
        delegate().writeToTab(id, text)

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegate().writeBytesToTab(id, *bytes)

    override fun getTabOutput(id: String): String? = delegate().getTabOutput(id)

    override fun listTabs(): List<TabInfo> = delegate().listTabs()

    override fun closeTab(id: String): Boolean = delegate().closeTab(id)

    override suspend fun startOwnedBackgroundCommand(command: String, title: String?, notify: Boolean, owner: RuntimeOwner): String =
        delegate().startOwnedBackgroundCommand(command, title, notify, owner)

    override fun listOwnedTabs(owner: RuntimeOwner): List<TabInfo> = delegate().listOwnedTabs(owner)

    override fun getOwnedTabOutput(id: String, owner: RuntimeOwner): String? = delegate().getOwnedTabOutput(id, owner)

    override fun sendOwnedInput(id: String, owner: RuntimeOwner, input: String, appendNewline: Boolean): Boolean =
        delegate().sendOwnedInput(id, owner, input, appendNewline)

    override fun writeOwnedText(id: String, owner: RuntimeOwner, text: String): Boolean =
        delegate().writeOwnedText(id, owner, text)

    override fun writeOwnedBytes(id: String, owner: RuntimeOwner, vararg bytes: Int): Boolean =
        delegate().writeOwnedBytes(id, owner, *bytes)

    override fun resizeOwnedTab(id: String, owner: RuntimeOwner, columns: Int, rows: Int): Boolean =
        delegate().resizeOwnedTab(id, owner, columns, rows)

    override fun signalOwnedTab(id: String, owner: RuntimeOwner, signal: Int): Boolean =
        delegate().signalOwnedTab(id, owner, signal)

    override suspend fun closeOwnedTab(id: String, owner: RuntimeOwner): TerminalCloseResult? = delegate().closeOwnedTab(id, owner)
}
