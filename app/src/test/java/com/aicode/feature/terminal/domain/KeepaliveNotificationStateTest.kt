package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveNotificationStateTest {
    private val registry = KeepaliveNotificationRegistry()
    private val labels = KeepaliveNotificationContent.Labels(
        "Commands %1\$d · AI %2\$d · Terminals %3\$d", "%1\$s terminal %2\$d · %3\$s", "Agent %1\$d · %2\$s",
        "Local", "SSH", "Running", "Connected"
    )

    @Test
    fun displayFactsAreSeparateFromProtectionOwnerCounts() {
        registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("a", true)))
        registry.updateTerminals(TerminalNotificationSource.SSH, listOf(TerminalNotificationSession("a", false)))
        registry.updateAgents(listOf(AgentLiveNotificationState.Session("agent", "private prompt", "Command")))
        val state = registry.state.value
        assertEquals(2, state.terminalCount)
        assertEquals(1, state.taskCount)
        assertEquals(1, state.agents.size)
        assertEquals("", state.agents.single().title)
        assertEquals("Commands 1 · AI 1 · Terminals 2", KeepaliveNotificationContent.summary(state, labels))
    }

    @Test
    fun updatingOneTerminalSourceDoesNotReplaceTheOther() {
        registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("a", true)))
        registry.updateTerminals(TerminalNotificationSource.SSH, listOf(TerminalNotificationSession("b", true)))
        registry.updateTerminals(TerminalNotificationSource.LOCAL, emptyList())
        assertEquals(1, registry.state.value.taskCount)
        assertEquals(setOf(TerminalNotificationSource.SSH), registry.state.value.terminals.keys)
    }

    @Test
    fun repeatedSourceUpdatesDoNotInflateCounts() {
        repeat(5) {
            registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("a", true), TerminalNotificationSession("a", true)))
            registry.updateAgents(listOf(AgentLiveNotificationState.Session("agent", "", "Think")))
        }
        assertEquals(1, registry.state.value.taskCount)
        assertEquals(1, registry.state.value.agents.size)
    }

    @Test
    fun expandedNotificationUsesOnlyGenericRowsAndActions() {
        registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("private-command-id", true)))
        registry.updateAgents(listOf(AgentLiveNotificationState.Session("private-agent-id", "private-output", "Think")))
        val body = KeepaliveNotificationContent.expanded(registry.state.value, labels)
        assertTrue(body.contains("Local terminal 1 · Running"))
        assertTrue(body.contains("Agent 1 · Think"))
        assertFalse(body.contains("private"))
    }

    @Test
    fun companionOnlyAppearsWhenAgentLiveAndTerminalsBothExist() {
        val live = AgentLiveNotificationState.Snapshot(enabled = true, completed = true)
        assertFalse(KeepaliveNotificationContent.companionRequired(live, registry.state.value))
        registry.updateTerminals(TerminalNotificationSource.SSH, listOf(TerminalNotificationSession("a", false)))
        assertTrue(KeepaliveNotificationContent.companionRequired(live, registry.state.value))
        assertFalse(KeepaliveNotificationContent.companionRequired(live.copy(enabled = false), registry.state.value))
    }

    @Test
    fun finishingCommandCanKeepTerminalConnectedWithoutAdvertisingRunningTask() {
        registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("a", true)))
        registry.updateTerminals(TerminalNotificationSource.LOCAL, listOf(TerminalNotificationSession("a", false)))
        assertEquals(0, registry.state.value.taskCount)
        assertEquals(1, registry.state.value.terminalCount)
    }
}
