package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

private const val SUB_AGENT_GROUP_EXPANSION_STATE_VERSION = "v2"

@Stable
internal class StateHolderSubAgentGroupExpansion(
    private val state: MutableState<Boolean>,
) {
    val expanded: Boolean get() = state.value

    fun toggle() { state.value = !state.value }
}

@Composable
internal fun rememberSubAgentGroupExpansion(
    sessionId: String?,
    groupKey: String? = null,
): StateHolderSubAgentGroupExpansion = key(SUB_AGENT_GROUP_EXPANSION_STATE_VERSION, sessionId, groupKey) {
    val parentSessionId = sessionId?.takeIf { it.isNotBlank() }
    val saver = remember(parentSessionId, groupKey) {
        Saver<Boolean, List<String>>(
            save = { listOf(parentSessionId.orEmpty(), groupKey.orEmpty(), it.toString()) },
            restore = { saved ->
                if (parentSessionId != null && saved.getOrNull(0) == parentSessionId &&
                    saved.getOrNull(1) == groupKey.orEmpty()
                ) {
                    saved.getOrNull(2)?.toBooleanStrictOrNull() ?: false
                } else false
            },
        )
    }
    val state = rememberSaveable(sessionId, groupKey, SUB_AGENT_GROUP_EXPANSION_STATE_VERSION, stateSaver = saver) {
        mutableStateOf(false)
    }
    remember(state) { StateHolderSubAgentGroupExpansion(state) }
}

@Composable
internal fun rememberRetainedSubAgentGroupExpansion(
    sessionId: String?,
    groupKey: String,
    stateHolder: SaveableStateHolder,
): StateHolderSubAgentGroupExpansion {
    lateinit var expansion: StateHolderSubAgentGroupExpansion
    stateHolder.SaveableStateProvider("$SUB_AGENT_GROUP_EXPANSION_STATE_VERSION:$groupKey") {
        expansion = rememberSubAgentGroupExpansion(sessionId, groupKey)
    }
    return expansion
}
