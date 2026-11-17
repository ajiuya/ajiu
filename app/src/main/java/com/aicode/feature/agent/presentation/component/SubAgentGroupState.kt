package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

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
): StateHolderSubAgentGroupExpansion = key(sessionId, groupKey) {
    val parentSessionId = sessionId?.takeIf { it.isNotBlank() }
    val saver = remember(parentSessionId, groupKey) {
        Saver<Boolean, List<String>>(
            save = { listOf(parentSessionId.orEmpty(), groupKey.orEmpty(), it.toString()) },
            restore = { saved ->
                if (parentSessionId != null && saved.getOrNull(0) == parentSessionId && saved.getOrNull(1) == groupKey.orEmpty()) {
                    saved.getOrNull(2)?.toBooleanStrictOrNull() ?: false
                } else false
            },
        )
    }
    val state = rememberSaveable(sessionId, groupKey, stateSaver = saver) { mutableStateOf(false) }
    remember(state) { StateHolderSubAgentGroupExpansion(state) }
}

@Composable
internal fun rememberRetainedSubAgentGroupExpansion(
    sessionId: String?,
    groupKey: String,
    stateHolder: SaveableStateHolder,
): StateHolderSubAgentGroupExpansion {
    lateinit var expansion: StateHolderSubAgentGroupExpansion
    stateHolder.SaveableStateProvider(groupKey) {
        expansion = rememberSubAgentGroupExpansion(sessionId, groupKey)
    }
    return expansion
}

@Composable
internal fun rememberSubAgentGroupChildStateHolder(sessionId: String?): SaveableStateHolder = key(sessionId) {
    val parentSessionId = sessionId?.takeIf { it.isNotBlank() }
    val parentRegistry = LocalSaveableStateRegistry.current
    val scopedRegistry = remember(parentSessionId, parentRegistry) {
        if (parentSessionId != null && parentRegistry != null) {
            SubAgentGroupChildStateRegistry(parentSessionId, parentRegistry)
        } else null
    }
    lateinit var holder: SaveableStateHolder
    CompositionLocalProvider(LocalSaveableStateRegistry provides scopedRegistry) {
        holder = rememberSaveableStateHolder()
    }
    holder
}

private class SubAgentGroupChildStateRegistry(
    sessionId: String,
    private val parent: SaveableStateRegistry,
) : SaveableStateRegistry {
    private val prefix = "subagent-group-children:${sessionId.length}:$sessionId:"

    override fun consumeRestored(key: String): Any? = parent.consumeRestored(prefix + key)

    override fun registerProvider(key: String, valueProvider: () -> Any?): SaveableStateRegistry.Entry =
        parent.registerProvider(prefix + key, valueProvider)

    override fun canBeSaved(value: Any): Boolean = parent.canBeSaved(value)

    override fun performSave(): Map<String, List<Any?>> = parent.performSave()
        .filterKeys { it.startsWith(prefix) }
        .mapKeys { (key, _) -> key.removePrefix(prefix) }
}
