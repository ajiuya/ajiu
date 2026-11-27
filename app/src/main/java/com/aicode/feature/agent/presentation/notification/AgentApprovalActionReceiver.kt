package com.aicode.feature.agent.presentation.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 审批通知上按钮的接收器：把用户在通知上的选择直接回传给挂起的审批请求，无需回到 App。
 *
 * 按通知绑定的请求 id 回传，过期请求不会影响其它待决审批。
 */
@AndroidEntryPoint
class AgentApprovalActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var toolPermissionManager: ToolPermissionManager

    override fun onReceive(context: Context, intent: Intent) {
        resolveApprovalAction(intent.action, intent.getStringExtra(EXTRA_KIND),
            intent.getStringExtra(EXTRA_CHOICE), intent.getStringExtra(EXTRA_REQUEST_ID), toolPermissionManager)
    }

    companion object {
        const val ACTION = "com.aicode.action.AGENT_APPROVAL"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_CHOICE = "extra_choice"
    }
}

internal fun resolveApprovalAction(action: String?, kind: String?, choice: String?, requestId: String?,
    toolPermissionManager: ToolPermissionManager) {
    if (action != AgentApprovalActionReceiver.ACTION || requestId.isNullOrBlank()) return
    if (kind != ApprovalKind.PERMISSION.name) return
    val permissionChoice = PermissionChoice.entries.find { it.name == choice } ?: return
    val request = toolPermissionManager.pendingById(requestId) ?: return
    if (permissionChoice == PermissionChoice.ALWAYS_SESSION && !request.canAllowSession) return
    toolPermissionManager.resolve(requestId, permissionChoice)
}
