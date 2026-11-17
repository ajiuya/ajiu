package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

private val SubAgentExpansionSaver = Saver<SubAgentCapsuleExpansion, List<String>>(
    save = { listOf(it.expanded.toString(), it.observedStatus?.name.orEmpty()) },
    restore = {
        SubAgentCapsuleExpansion(
            expanded = it.getOrNull(0) == "true",
            observedStatus = it.getOrNull(1)?.let { name ->
                SubAgentInteractionStatus.entries.firstOrNull { status -> status.name == name }
            },
        )
    },
)

@Composable
internal fun rememberSubAgentExpansion(subSessionId: String): StateHolderSubAgentExpansion {
    val state = rememberSaveable(subSessionId, stateSaver = SubAgentExpansionSaver) {
        mutableStateOf(SubAgentCapsuleExpansion())
    }
    return remember(state) { StateHolderSubAgentExpansion(state) }
}

internal class StateHolderSubAgentExpansion(private val state: MutableState<SubAgentCapsuleExpansion>) {
    val expanded: Boolean get() = state.value.expanded
    fun toggle() { state.value = state.value.toggle() }
    fun observe(status: SubAgentInteractionStatus) { state.value = state.value.observe(status) }
}

@Composable
internal fun <T> rememberSubAgentPage(
    subSessionId: String,
    expanded: Boolean,
    hostAvailable: Boolean,
    initial: T,
    source: () -> Flow<T>,
    onFailure: (T, Exception) -> T,
    onActive: (Boolean) -> Unit = {},
): State<T> {
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateAsState()
    val visibility by rememberUpdatedState(
        SubAgentPageVisibility(expanded, lifecycleState.isAtLeast(Lifecycle.State.STARTED), hostAvailable)
    )
    val latestSource by rememberUpdatedState(source)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestOnActive by rememberUpdatedState(onActive)
    return key(subSessionId, owner) {
        produceState(initialValue = initial) {
            subAgentVisiblePageFlow(
                visibility = snapshotFlow { visibility },
                initial = initial,
                source = { latestSource() },
                onFailure = { previous, failure -> latestFailure(previous, failure) },
                onActive = object : (Boolean) -> Unit {
                    private var release: ((Boolean) -> Unit)? = null
                    override fun invoke(active: Boolean) {
                        if (active) {
                            release = latestOnActive
                            release?.invoke(true)
                        } else {
                            release?.invoke(false)
                            release = null
                        }
                    }
                },
            ).collect { value = it }
        }
    }
}

@Composable
internal fun rememberSubAgentElapsedMs(summary: SubAgentInteractionSummary, lifecycleActive: Boolean): Long? {
    val live = summary.status.isActiveExecution() && summary.finishedAtMs == null &&
        summary.startedAtMs?.let { it > 0L } == true
    if (!live) return resolveSubAgentElapsedMs(summary, System.currentTimeMillis())
    var now by remember(summary.subSessionId, summary.startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(summary.subSessionId, summary.startedAtMs, lifecycleActive) {
        if (!lifecycleActive) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (isActive) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    return resolveSubAgentElapsedMs(summary, now)
}
