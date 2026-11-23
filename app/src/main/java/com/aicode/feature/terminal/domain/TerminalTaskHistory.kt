package com.aicode.feature.terminal.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TerminalTaskScope(
    val profileId: String,
    val rootfsSourceIdentity: String,
    val rootfsPath: String,
    val rootfsIdentity: String,
    val workspaceRoot: String,
    val workspaceIdentity: String
) {
    internal fun validate() {
        require(profileId.isNotBlank() && profileId.length <= 256)
        require(rootfsSourceIdentity.matches(Regex("[0-9a-f]{64}")))
        require(rootfsPath.startsWith('/') && rootfsPath.length <= 4096)
        require(workspaceRoot.startsWith('/') && workspaceRoot.length <= 4096)
        require(rootfsIdentity.isNotBlank() && rootfsIdentity.length <= 1024)
        require(workspaceIdentity.isNotBlank() && workspaceIdentity.length <= 1024)
    }
}

@Serializable
enum class TerminalTaskStatus { RUNNING, FINISHED, UNKNOWN }

@Serializable
data class TerminalTaskRecord(
    val historyId: String,
    val tabId: String,
    val title: String,
    val sourceSessionId: String,
    val scope: TerminalTaskScope,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val status: TerminalTaskStatus = TerminalTaskStatus.RUNNING,
    val output: String = "",
    val outputRevision: Long = -1,
    val exitCode: Int? = null,
    val historyError: String? = null
) {
    internal fun validate() {
        requireTerminalHistoryId(historyId)
        require(tabId == "term-$historyId")
        require(title.length <= MAX_TERMINAL_TASK_TITLE_CHARS)
        requireTerminalHistorySession(sourceSessionId)
        scope.validate()
        require(startedAt >= 0 && updatedAt >= startedAt)
        require(finishedAt == null || finishedAt >= startedAt)
        require(output.length <= MAX_TERMINAL_OUTPUT_CHARS + 64)
        require(outputRevision >= -1)
        require((status == TerminalTaskStatus.FINISHED) == (exitCode != null))
        require((status == TerminalTaskStatus.FINISHED) == (finishedAt != null))
        require(historyError == null || historyError.length <= 128)
    }
}

data class TerminalTaskHistoryState(
    val loaded: Boolean = false,
    val records: List<TerminalTaskRecord> = emptyList(),
    val error: String? = null
)

const val MAX_TERMINAL_TASK_TITLE_CHARS = 256
const val TERMINAL_HISTORY_IO_ERROR = "history_io_error"
const val TERMINAL_HISTORY_CORRUPT_ERROR = "history_corrupt"

fun requireTerminalHistoryId(historyId: String) {
    require(historyId.length == 36 && historyId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) {
        "Invalid terminal history id"
    }
    require(UUID.fromString(historyId).toString() == historyId) { "Invalid terminal history id" }
}

internal fun requireTerminalHistorySession(sourceSessionId: String) {
    require(sourceSessionId.isNotBlank() && sourceSessionId.length <= 256 && sourceSessionId.none { it.isISOControl() }) {
        "Invalid terminal history session"
    }
}

internal fun terminalTaskHistoryEligible(notify: Boolean, sourceSessionId: String?): Boolean =
    notify && !sourceSessionId.isNullOrBlank()

internal fun terminalTaskTabId(historyId: String?, nextId: () -> String): String =
    historyId?.let { requireTerminalHistoryId(it); "term-$it" } ?: nextId()
