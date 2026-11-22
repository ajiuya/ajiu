package com.aicode.feature.plugin.runtime

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class PluginApprovalOperation {
    EXECUTE_TOOL, EXECUTE_COMMAND, READ_FILES, WRITE_FILES, DELETE_FILES, PREPARE_DEPENDENCIES
}

enum class PluginApprovalChoice { REJECT, ONCE, ALWAYS }

data class PluginHostApproval(
    val id: String,
    val pluginId: String,
    val pluginName: String,
    val operation: PluginApprovalOperation,
    val details: String,
    val canAlwaysAllow: Boolean = false,
    val scopeSummary: String = ""
)

@Singleton
class PluginApprovalCoordinator @Inject constructor() {
    private class Entry(val request: PluginHostApproval, val decision: CompletableDeferred<PluginApprovalChoice>, val sourceIdentity: Any?)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val _pending = MutableStateFlow<PluginHostApproval?>(null)
    val pending: StateFlow<PluginHostApproval?> = _pending.asStateFlow()

    internal suspend fun awaitApproval(
        pluginId: String,
        pluginName: String,
        operation: PluginApprovalOperation,
        details: String,
        changes: Flow<Unit>,
        checkCurrent: () -> Unit,
        canAlwaysAllow: Boolean = false,
        scopeSummary: String = "",
        sourceIdentity: Any? = null,
        remember: suspend () -> Unit = {}
    ): Boolean {
        checkCurrent()
        if (details.length > MAX_DETAILS) throw PluginHostException("APPROVAL_TOO_LARGE", "Approval details cannot be displayed in full")
        val request = PluginHostApproval(UUID.randomUUID().toString(), pluginId,
            pluginName.take(200), operation, details, canAlwaysAllow, scopeSummary)
        val entry = Entry(request, CompletableDeferred(), sourceIdentity)
        synchronized(lock) {
            if (entries.size >= MAX_PENDING) return false
            entries[request.id] = entry
            publish()
        }
        return try {
            val allowed = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                coroutineScope {
                    val watcher = launch {
                        changes.collect {
                            try { checkCurrent() } catch (_: CancellationException) {
                                entry.decision.complete(PluginApprovalChoice.REJECT)
                            } catch (_: IllegalStateException) {
                                entry.decision.complete(PluginApprovalChoice.REJECT)
                            }
                        }
                    }
                    try { entry.decision.await() } finally { watcher.cancelAndJoin() }
                }
            } ?: PluginApprovalChoice.REJECT
            checkCurrent()
            if (allowed == PluginApprovalChoice.ALWAYS) {
                if (!canAlwaysAllow) return false
                remember()
                checkCurrent()
            }
            allowed != PluginApprovalChoice.REJECT
        } finally {
            synchronized(lock) {
                entries.remove(request.id, entry)
                publish()
            }
        }
    }

    fun resolve(id: String, allowed: Boolean) = resolve(id, if (allowed) PluginApprovalChoice.ONCE else PluginApprovalChoice.REJECT)

    fun resolve(id: String, choice: PluginApprovalChoice) {
        val entry = synchronized(lock) { entries[id]?.takeIf { _pending.value?.id == id } } ?: return
        if (choice == PluginApprovalChoice.ALWAYS && !entry.request.canAlwaysAllow) return
        entry.decision.complete(choice)
    }

    internal fun pendingFor(pluginId: String, sourceIdentity: Any): PluginHostApproval? = synchronized(lock) {
        entries.values.firstOrNull { it.request.pluginId == pluginId && it.sourceIdentity == sourceIdentity }?.request
    }

    private fun publish() { _pending.value = entries.values.firstOrNull()?.request }

    internal companion object {
        private const val MAX_PENDING = 16
        const val MAX_DETAILS = 8_000
        private const val APPROVAL_TIMEOUT_MS = 120_000L
    }
}
