package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TerminalNotificationSource { LOCAL, SSH }

internal data class TerminalNotificationSession(
    val id: String,
    val commandRunning: Boolean
)

internal data class KeepaliveNotificationSnapshot(
    val terminals: Map<TerminalNotificationSource, List<TerminalNotificationSession>> = emptyMap(),
    val agents: List<AgentLiveNotificationState.Session> = emptyList()
) {
    val taskCount: Int get() = terminals.values.sumOf { list -> list.count { it.commandRunning } }
    val terminalCount: Int get() = terminals.values.sumOf { it.size }
}

internal class KeepaliveNotificationRegistry {
    private val terminals = linkedMapOf<TerminalNotificationSource, List<TerminalNotificationSession>>()
    private var agents = emptyList<AgentLiveNotificationState.Session>()
    private val mutableState = MutableStateFlow(KeepaliveNotificationSnapshot())
    val state: StateFlow<KeepaliveNotificationSnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun updateTerminals(source: TerminalNotificationSource, sessions: List<TerminalNotificationSession>) {
        val clean = sessions.distinctBy { it.id }
        if (clean.isEmpty()) terminals.remove(source) else terminals[source] = clean
        publish()
    }

    @Synchronized
    fun updateAgents(sessions: List<AgentLiveNotificationState.Session>) {
        agents = sessions.distinctBy { it.sessionId }.map { it.copy(title = "") }
        publish()
    }

    private fun publish() {
        mutableState.value = KeepaliveNotificationSnapshot(terminals.toMap(), agents)
    }
}

internal object KeepaliveNotificationState {
    private val registry = KeepaliveNotificationRegistry()
    val state: StateFlow<KeepaliveNotificationSnapshot> = registry.state

    fun updateTerminals(source: TerminalNotificationSource, sessions: List<TerminalNotificationSession>) =
        registry.updateTerminals(source, sessions)

    fun updateAgents(sessions: List<AgentLiveNotificationState.Session>) = registry.updateAgents(sessions)
}

internal object KeepaliveNotificationContent {
    data class Labels(
        val summary: String,
        val terminalRow: String,
        val agentRow: String,
        val local: String,
        val ssh: String,
        val running: String,
        val idle: String
    )

    fun summary(snapshot: KeepaliveNotificationSnapshot, labels: Labels): String =
        String.format(Locale.ROOT, labels.summary, snapshot.taskCount, snapshot.agents.size, snapshot.terminalCount)

    fun companionRequired(live: AgentLiveNotificationState.Snapshot, snapshot: KeepaliveNotificationSnapshot): Boolean =
        live.active && snapshot.terminalCount > 0

    fun expanded(snapshot: KeepaliveNotificationSnapshot, labels: Labels): String {
        val rows = mutableListOf(summary(snapshot, labels))
        snapshot.terminals.forEach { (source, sessions) ->
            val location = if (source == TerminalNotificationSource.LOCAL) labels.local else labels.ssh
            sessions.take(10).forEachIndexed { index, session ->
                rows += String.format(
                    Locale.ROOT, labels.terminalRow, location, index + 1,
                    if (session.commandRunning) labels.running else labels.idle
                )
            }
        }
        snapshot.agents.take(10).forEachIndexed { index, session ->
            rows += String.format(Locale.ROOT, labels.agentRow, index + 1, session.statusLabel)
        }
        return rows.joinToString("\n")
    }
}
