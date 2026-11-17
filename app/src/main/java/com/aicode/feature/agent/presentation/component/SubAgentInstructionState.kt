package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.aicode.feature.agent.presentation.SubAgentInstructionFailure
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import kotlinx.coroutines.CancellationException

internal data class SubAgentInstructionUiState(
    val draft: String = "",
    val sending: Boolean = false,
    val failure: SubAgentInstructionFailure? = null,
    val sentNotice: Boolean = false,
    val revision: Long = 0,
)

private val SubAgentInstructionSaver = Saver<SubAgentInstructionUiState, List<String>>(
    save = { listOf(it.draft, it.failure?.name.orEmpty(), it.sentNotice.toString()) },
    restore = {
        SubAgentInstructionUiState(
            draft = it.getOrElse(0) { "" },
            failure = it.getOrNull(1)?.let { name ->
                SubAgentInstructionFailure.entries.firstOrNull { failure -> failure.name == name }
            },
            sentNotice = it.getOrNull(2) == "true",
        )
    },
)

@Composable
internal fun rememberSubAgentInstructionState(subSessionId: String): SubAgentInstructionState {
    val state = rememberSaveable(subSessionId, stateSaver = SubAgentInstructionSaver) {
        mutableStateOf(SubAgentInstructionUiState())
    }
    return remember(state) { SubAgentInstructionState(state) }
}

@Stable
internal class SubAgentInstructionState(private val state: MutableState<SubAgentInstructionUiState>) {
    val value: SubAgentInstructionUiState get() = state.value

    fun edit(text: String) {
        state.value = state.value.copy(draft = text, failure = null, sentNotice = false, revision = state.value.revision + 1)
    }

    suspend fun send(sender: suspend (String) -> SubAgentInstructionResult) {
        if (state.value.sending) return
        val submitted = state.value.draft
        val revision = state.value.revision
        if (submitted.isBlank()) {
            state.value = state.value.copy(failure = SubAgentInstructionFailure.EMPTY, sentNotice = false)
            return
        }
        state.value = state.value.copy(sending = true, failure = null, sentNotice = false)
        try {
            val result = try {
                sender(submitted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SubAgentInstructionResult(sent = false, failure = SubAgentInstructionFailure.SEND_FAILED)
            }
            val current = state.value
            state.value = if (result.sent) {
                current.copy(
                    // Delivery acknowledges a submission revision, not equal text typed later.
                    draft = if (current.revision == revision) "" else current.draft,
                    sentNotice = true,
                    failure = null,
                )
            } else current.copy(failure = result.failure ?: SubAgentInstructionFailure.SEND_FAILED, sentNotice = false)
        } finally {
            state.value = state.value.copy(sending = false)
        }
    }
}
