package com.aicode.feature.terminal.domain

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

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegate().sendInput(id, input, appendNewline)

    override fun writeToTab(id: String, text: String): Boolean =
        delegate().writeToTab(id, text)

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegate().writeBytesToTab(id, *bytes)

    override fun getTabOutput(id: String): String? = delegate().getTabOutput(id)

    override fun listTabs(): List<TabInfo> = delegate().listTabs()

    override fun closeTab(id: String): Boolean = delegate().closeTab(id)
}
