package com.aicode.feature.agent.presentation.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.component.isToolRunning
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** [accumulated] replaces the previous JSON snapshot; it is never an incremental suffix. */
data class PreparingToolPreviewSnapshot(val id: String, val name: String, val accumulated: String) {
    companion object {
        fun fromInput(id: String, name: String, input: Map<String, JsonElement>) =
            PreparingToolPreviewSnapshot(id, name, JsonObject(input).toString())
    }
}

@Stable
class ToolPreviewHostState internal constructor(internal val sessionId: String?) {
    var selectedMessageId: String? by mutableStateOf(null)
        private set
    var followingLatest: Boolean by mutableStateOf(true)
        private set
    var detailOpen: Boolean by mutableStateOf(false)
        private set

    fun select(messageId: String) {
        selectedMessageId = messageId
        followingLatest = false
    }

    var detailMessageId: String? by mutableStateOf(null)
        private set

    fun openDetails(messageId: String) {
        detailMessageId = messageId
        detailOpen = true
    }

    fun selectDetail(messageId: String) {
        detailMessageId = messageId
    }

    fun closeDetails() {
        detailOpen = false
        detailMessageId = null
    }

    fun followLatest() {
        followingLatest = true
    }
}

@Composable
fun rememberToolPreviewHostState(sessionId: String?): ToolPreviewHostState =
    remember(sessionId) { ToolPreviewHostState(sessionId) }

internal fun toolPreviewWorkVisible(sessionId: String?, ownerSessionId: String?, visible: Boolean, lifecycleStarted: Boolean): Boolean =
    sessionId != null && sessionId == ownerSessionId && visible && lifecycleStarted

internal enum class ToolPreviewKind { COMMAND, FILE, BROWSER, TEXT, GENERAL }
internal enum class ToolPreviewStage { PREPARING, RUNNING, COMPLETED, FAILED }

internal data class ToolPreviewTarget(
    val id: String,
    val name: String,
    val arguments: String,
    val content: String,
    val stage: ToolPreviewStage,
    val actionTitle: String? = null,
    val imagePath: String? = null,
    val sourceSessionId: String? = null,
) {
    val kind: ToolPreviewKind get() = when (name.lowercase(Locale.ROOT)) {
        "bash", "terminal", "root", "shizuku" -> ToolPreviewKind.COMMAND
        "writefile", "editfile" -> ToolPreviewKind.FILE
        "browser" -> if (wantsBrowserFrames(arguments) || producesBrowserScreenshot(arguments)) ToolPreviewKind.BROWSER else ToolPreviewKind.GENERAL
        "readfile", "list", "search", "webfetch" -> ToolPreviewKind.TEXT
        else -> ToolPreviewKind.GENERAL
    }
    val active: Boolean get() = stage == ToolPreviewStage.RUNNING || stage == ToolPreviewStage.PREPARING
}

internal fun buildToolPreviewTargets(
    messages: List<AgentUIMessage>,
    running: List<RunningToolOutput>,
    preparing: List<PreparingToolPreviewSnapshot>,
    sessionId: String? = null,
): List<ToolPreviewTarget> {
    val live = running.associateBy { it.messageId }
    val seen = HashSet<String>()
    val targets = ArrayList<ToolPreviewTarget>()
    for (message in messages) {
        if (message.role != MessageRole.TOOL || message.isCompactionFailure || message.toolName.isNullOrBlank() || !seen.add(message.id)) continue
        val isRunning = message.isToolRunning(null)
        val output = if (isRunning) live[message.id] else null
        val name = output?.toolName?.takeIf { it.isNotBlank() } ?: message.toolName.orEmpty()
        targets += ToolPreviewTarget(
            id = message.id,
            name = name,
            arguments = output?.toolArgs?.takeIf { it.isNotBlank() } ?: message.toolArgs.orEmpty(),
            content = output?.text ?: if (isRunning) "" else message.content,
            stage = when {
                isRunning -> ToolPreviewStage.RUNNING
                message.isError -> ToolPreviewStage.FAILED
                else -> ToolPreviewStage.COMPLETED
            },
            actionTitle = message.toolActionTitle,
            imagePath = if (isRunning) null else browserScreenshotPath(ToolPreviewTarget(
                message.id, name, message.toolArgs.orEmpty(), message.content,
                if (message.isError) ToolPreviewStage.FAILED else ToolPreviewStage.COMPLETED,
            )) ?: toolPreviewAttachmentPath(message),
            sourceSessionId = sessionId,
        )
    }
    for (output in running) {
        if (output.toolName.isBlank() || !seen.add(output.messageId)) continue
        targets += ToolPreviewTarget(output.messageId, output.toolName, output.toolArgs, output.text, ToolPreviewStage.RUNNING, sourceSessionId = sessionId)
    }
    for (draft in preparing) {
        if (draft.name.isBlank() || !seen.add(draft.id)) continue
        targets += ToolPreviewTarget(draft.id, draft.name, draft.accumulated, "", ToolPreviewStage.PREPARING, sourceSessionId = sessionId)
    }
    return targets
}

internal fun previewSelectedIndex(targets: List<ToolPreviewTarget>, selectedId: String?, following: Boolean): Int {
    if (targets.isEmpty()) return -1
    if (!following) targets.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { return it }
    return targets.indexOfLast { it.stage == ToolPreviewStage.PREPARING }.takeIf { it >= 0 }
        ?: targets.indexOfLast { it.stage == ToolPreviewStage.RUNNING }.takeIf { it >= 0 }
        ?: targets.lastIndex
}

private val previewImageMimeTypes = setOf(
    "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif",
)

internal fun toolPreviewAttachmentPath(message: AgentUIMessage): String? = message.attachments.asSequence()
    .filter { it.isImage && it.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT) in previewImageMimeTypes }
    .mapNotNull { attachment ->
        sequenceOf(attachment.localPath, attachment.containerPath).map(String::trim).firstOrNull(::isLocalPreviewImagePath)
    }.firstOrNull()
