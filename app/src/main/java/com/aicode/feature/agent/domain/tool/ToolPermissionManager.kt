package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolPermissionManager @Inject constructor(
    private val sessionPermissionGrants: SessionPermissionGrantRepository
) {
    companion object {
        const val HOST_WRITE_GRANT_PREFIX = SessionPermissionGrantRepository.HOST_WRITE_GRANT_PREFIX
    }

    /**
     * 所有待决授权请求，按插入顺序保存（key = 请求 id）。多会话并行时每个会话可各自挂起一个请求，
     * 互不阻塞——这样侧边栏才能同时点亮所有等待授权的会话。同一会话内的权限请求由 workflow 串行处理，
     * 故一个会话在此表中至多出现一次。
     */
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** 弹窗展示用：当前应展示的单个请求（取最早进入的），解决后自动切到下一个。 */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** 侧边栏用：当前正在等待授权的会话 id 集合。 */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /** 挂起等待用户在弹窗中的选择。不同会话可并行挂起，互不阻塞。 */
    suspend fun awaitApproval(request: PendingToolPermission): PermissionChoice {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            val choice = decision.await()
            val grant = request.sessionGrant
            // Legacy exact-command grants keep their original persistence contract. A host-write
            // scope is only a choice here: the workflow must validate its frozen proof first.
            if (choice == PermissionChoice.ALWAYS_SESSION && !grant.isNullOrBlank() &&
                request.sessionId.isNotBlank() && !grant.startsWith(HOST_WRITE_GRANT_PREFIX)
            ) {
                sessionPermissionGrants.allow(request.sessionId, grant)
            }
            return choice
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /** Only the workflow calls this, after immediately rechecking the reviewed source/evidence. */
    suspend fun confirmHostWriteSessionGrant(sessionId: String?, grant: String) {
        if (sessionId.isNullOrBlank() || !grant.startsWith(HOST_WRITE_GRANT_PREFIX) ||
            grant.removePrefix(HOST_WRITE_GRANT_PREFIX).isBlank()
        ) return
        sessionPermissionGrants.allow(sessionId, grant)
    }

    /** UI 回传用户选择，唤醒对应 id 的挂起请求。 */
    fun resolve(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pending[id] } ?: return
        entry.decision.complete(choice)
    }

    fun rejectForTool(toolName: String) {
        val entries = synchronized(lock) { pending.values.filter { it.request.toolName == toolName } }
        entries.forEach { it.decision.complete(PermissionChoice.REJECT) }
    }

    /** 取某会话当前的待决请求（stopAgent 停止单个会话时用，避免误取其它会话的弹窗）。 */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    /** 在持锁区内调用：把内部表投影到两个对外 StateFlow。 */
    private fun publish() {
        _pendingRequest.value = pending.values.firstOrNull()?.request
        _awaitingSessionIds.value = pending.values.mapNotNull { it.request.sessionId.ifBlank { null } }.toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )
}
