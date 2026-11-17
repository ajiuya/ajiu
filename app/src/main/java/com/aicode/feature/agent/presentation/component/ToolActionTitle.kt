package com.aicode.feature.agent.presentation.component

import androidx.annotation.StringRes
import com.aicode.R
import java.util.Locale

@StringRes
internal fun toolActionPlaceholderRes(toolName: String?): Int = when (val name = toolName?.lowercase(Locale.ROOT)) {
    "readfile" -> R.string.chat_tool_action_read_file
    "editfile" -> R.string.chat_tool_action_edit_file
    "writefile" -> R.string.chat_tool_action_write_file
    "sendfile" -> R.string.chat_tool_action_send_file
    "viewimage" -> R.string.chat_tool_action_view_image
    "generateimage" -> R.string.chat_tool_action_generate_image
    "list" -> R.string.chat_tool_action_list_directory
    "search" -> R.string.chat_tool_action_search_code
    "websearch" -> R.string.chat_tool_action_search_web
    "webfetch" -> R.string.chat_tool_action_fetch_page
    "bash", "root", "shizuku" -> R.string.chat_tool_action_execute_command
    "terminal" -> R.string.chat_tool_action_manage_terminal
    "task" -> R.string.chat_tool_action_manage_subagent
    "todo" -> R.string.chat_tool_action_update_todo
    "memory" -> R.string.chat_tool_action_manage_memory
    "browser" -> R.string.chat_tool_action_operate_browser
    "askuserquestion" -> R.string.chat_tool_action_ask_user
    "submitplan" -> R.string.chat_tool_action_submit_plan
    "loadskill" -> R.string.chat_tool_action_load_skill
    "messageparent" -> R.string.chat_tool_action_message_parent
    "goalstatus" -> R.string.chat_tool_action_update_goal
    "managemcp" -> R.string.chat_tool_action_manage_services
    else -> if (name?.startsWith("mcp__") == true) R.string.chat_tool_action_external
        else R.string.chat_tool_action_generic
}
