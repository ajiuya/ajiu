package com.aicode.feature.agent.presentation.component

import com.aicode.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolActionTitleTest {
    @Test
    fun fileToolsUseGenericActionUntilModelTitleArrives() {
        assertEquals(R.string.chat_tool_action_read_file, toolActionPlaceholderRes("readFile"))
        assertEquals(R.string.chat_tool_action_edit_file, toolActionPlaceholderRes("editFile"))
        assertEquals(R.string.chat_tool_action_write_file, toolActionPlaceholderRes("writeFile"))
        assertEquals(R.string.chat_tool_action_send_file, toolActionPlaceholderRes("sendFile"))
    }

    @Test
    fun shellToolsDoNotGuessCommandPurpose() {
        for (name in listOf("Bash", "Root", "Shizuku")) {
            assertEquals(R.string.chat_tool_action_execute_command, toolActionPlaceholderRes(name))
        }
        assertEquals(R.string.chat_tool_action_manage_terminal, toolActionPlaceholderRes("terminal"))
    }

    @Test
    fun unknownAndExternalToolsHaveActionPlaceholders() {
        assertEquals(R.string.chat_tool_action_generic, toolActionPlaceholderRes(null))
        assertEquals(R.string.chat_tool_action_generic, toolActionPlaceholderRes("custom"))
        assertEquals(R.string.chat_tool_action_external, toolActionPlaceholderRes("mcp__server__tool"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(toolActionPlaceholderRes("readFile"), toolActionPlaceholderRes("READFILE"))
    }
}
