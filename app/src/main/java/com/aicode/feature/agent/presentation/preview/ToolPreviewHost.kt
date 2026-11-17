package com.aicode.feature.agent.presentation.preview

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.RunningToolOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.io.File

/** [visible] also excludes in-app occlusion. [onHeightChanged] includes the 6dp bottom inset, not the 8dp row gap. */
@Composable
fun ToolPreviewHost(
    sessionId: String?,
    messages: List<AgentUIMessage>,
    running: List<RunningToolOutput>,
    preparing: List<PreparingToolPreviewSnapshot> = emptyList(),
    browserManager: BrowserManager? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    state: ToolPreviewHostState = rememberToolPreviewHostState(sessionId),
    onOpenBrowser: (() -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    resolveLocalImage: ((String) -> File?)? = null,
    readFileBytes: ((String) -> ByteArray?)? = null,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val heightChanged by rememberUpdatedState(onHeightChanged)
    val active = toolPreviewWorkVisible(sessionId, state.sessionId, visible && LocalUiWorkVisible.current,
        lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    val cache = remember(sessionId) { ToolPreviewBitmapCache() }
    val preferences = LocalContext.current.applicationContext.getSharedPreferences("tool_preview", Context.MODE_PRIVATE)
    var thumbnailEnabled by remember(preferences) { mutableStateOf(preferences.getBoolean("enabled", true)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, name ->
            if (name == "enabled") thumbnailEnabled = prefs.getBoolean("enabled", true)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(cache) { onDispose { cache.clear(); heightChanged(0) } }
    LaunchedEffect(active) { if (!active) heightChanged(0) }
    val history = rememberToolPreviewHistory(sessionId, messages, enabled = active)
    val sampled = rememberPreviewRunning(running, sessionId, active)
    val targets = rememberToolPreviewTargets(history, messages, sampled, preparing, active)
    if (!active) return
    if (targets.isEmpty()) {
        LaunchedEffect(Unit) { heightChanged(0) }
        return
    }
    val index = remember(targets, state.selectedMessageId, state.followingLatest) {
        previewSelectedIndex(targets, state.selectedMessageId, state.followingLatest)
    }
    val target = remember(targets[index], messages, sampled, preparing, sessionId) {
        currentToolPreviewTarget(targets[index], messages, sampled, preparing, sessionId)
    }
    val thumbWork = thumbnailEnabled && !state.detailOpen
    val safety = target?.let { rememberToolPreviewSafety(it) }
    val presentation = target?.let { rememberToolPreviewPresentation(it, thumbWork, safety = requireNotNull(safety)) }
    val bitmap = target?.let { rememberToolPreviewBitmap(it, browserManager, cache, thumbWork, resolveLocalImage, readFileBytes,
        safety = requireNotNull(safety)) }
    ToolPreviewWorkVisibility(thumbWork) {
        Box(modifier.fillMaxWidth().onSizeChanged { heightChanged(it.height) }.padding(bottom = ToolPreviewMetrics.bottomPadding)) {
            FloatingToolStatusBar(target ?: targets[index], presentation?.display, bitmap, targets, index, state, thumbnailEnabled,
                presentation = presentation ?: ToolPreviewPresentation()) { enabled ->
                thumbnailEnabled = enabled
                preferences.edit().putBoolean("enabled", enabled).apply()
            }
        }
    }
    if (state.detailOpen) {
        val detailIndex = targets.indexOfFirst { it.id == state.detailMessageId }
        val detailTarget = remember(targets, detailIndex, messages, sampled, preparing, sessionId) {
            targets.getOrNull(detailIndex)?.let { currentToolPreviewTarget(it, messages, sampled, preparing, sessionId) }
        }
        if (detailTarget == null) LaunchedEffect(state.detailMessageId) { state.closeDetails() }
        else ToolDetailSheet(detailTarget, targets, detailIndex, state, browserManager, cache,
            readFileBytes = readFileBytes, resolveLocalImage = resolveLocalImage, onOpenBrowser = onOpenBrowser,
            safety = if (detailTarget == target) requireNotNull(safety) else rememberToolPreviewSafety(detailTarget),
            initialPresentation = if (detailTarget == target) presentation else null)
    }
}

@Composable
private fun rememberPreviewRunning(running: List<RunningToolOutput>, sessionId: String?, enabled: Boolean): List<RunningToolOutput> {
    val latest by rememberUpdatedState(running)
    val sampled by produceState(running, sessionId, enabled) {
        if (!enabled) return@produceState
        snapshotFlow { latest }.distinctUntilChanged().coalesceToolPreviewContent().collect { value = it }
    }
    val keys = remember(running) { runningToolPreviewKeysOf(running) }
    val sampledKeys = remember(sampled) { runningToolPreviewKeysOf(sampled) }
    return if (keys != sampledKeys) running else sampled
}

@Composable
internal fun rememberToolPreviewTargets(
    history: ToolPreviewHistory,
    messages: List<AgentUIMessage>,
    running: List<RunningToolOutput>,
    preparing: List<PreparingToolPreviewSnapshot>,
    enabled: Boolean = true,
): List<ToolPreviewTarget> {
    var previous by remember(history.sessionId) { mutableStateOf(emptyList<ToolPreviewTarget>()) }
    if (!enabled) return previous
    val executed = remember(history, running) { overlayToolPreviewRunning(history, running) }
    val overlaid = remember(executed, preparing, history.sessionId) { overlayToolPreviewPreparing(executed, preparing, history.sessionId) }
    val targets = remember(previous, overlaid, history.inputMessages, messages, running, preparing, history.sessionId) {
        if (history.inputMessages !== messages) retainToolPreviewNavigation(previous, overlaid, messages, running, preparing, history.sessionId) else overlaid
    }
    SideEffect { previous = targets }
    return targets
}

private class ToolPreviewHistoryInput(val sessionId: String?, val messages: List<AgentUIMessage>)

@Composable
internal fun rememberToolPreviewHistory(
    sessionId: String?,
    messages: List<AgentUIMessage>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    enabled: Boolean = true,
    build: suspend (String?, List<AgentUIMessage>) -> ToolPreviewHistory = { sid, rows -> buildToolPreviewHistory(sid, rows) },
): ToolPreviewHistory {
    val input = remember(sessionId, messages) { ToolPreviewHistoryInput(sessionId, messages) }
    val latest by rememberUpdatedState(input)
    val builder by rememberUpdatedState(build)
    return key(sessionId) {
        val history by produceState(ToolPreviewHistory(sessionId, emptyList(), emptyMap(), emptySet()), dispatcher, enabled) {
            if (!enabled) return@produceState
            snapshotFlow { latest }.collectLatest { next ->
                val built = withContext(dispatcher) { builder(next.sessionId, next.messages) }
                currentCoroutineContext().ensureActive()
                if (latest === next && built.sessionId == sessionId) value = built
            }
        }
        history
    }
}

internal data class ToolPreviewPresentation(
    val display: ToolPreviewDisplay? = null,
    val actionTitle: String? = null,
    val actionResource: Int? = null,
    val sourceTarget: ToolPreviewTarget? = null,
)

private class ToolPreviewInput(val target: ToolPreviewTarget, val safety: ToolPreviewSafety)
private class ToolPreviewFrame(val input: ToolPreviewInput, val presentation: ToolPreviewPresentation)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberToolPreviewPresentation(
    target: ToolPreviewTarget,
    enabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    safety: ToolPreviewSafety = rememberToolPreviewSafety(target),
    buildDisplay: suspend (ToolPreviewTarget, ToolPreviewSafety) -> ToolPreviewDisplay = { next, checked -> buildToolPreviewDisplay(next, checked) },
    initialPresentation: ToolPreviewPresentation? = null,
    prepareInitialWhilePaused: Boolean = false,
): ToolPreviewPresentation {
    val input = remember(safety, target.actionTitle, if (enabled) target else null) { ToolPreviewInput(target, safety) }
    val latest by rememberUpdatedState(input)
    val builder by rememberUpdatedState(buildDisplay)
    // The synchronous safety key must retire content even while presentation work is paused.
    return key(target.sourceSessionId, target.id, target.name, safety.bodyHidden, safety.browserTabId) {
        val initialFrame = remember {
            initialPresentation?.takeIf { it.sourceTarget == target && it.display != null }?.let { ToolPreviewFrame(input, it) }
        }
        val frame by produceState(initialFrame, enabled, dispatcher, prepareInitialWhilePaused) {
            if (!enabled && (!prepareInitialWhilePaused || value != null)) return@produceState
            val frames = snapshotFlow { latest }.mapLatest { next ->
                val presentation = withContext(dispatcher) {
                    val display = builder(next.target, next.safety)
                    display.terminalBody
                    display.terminalCommand
                    display.copyText
                    ToolPreviewPresentation(display = display)
                }
                currentCoroutineContext().ensureActive()
                if (latest === next) ToolPreviewFrame(next, presentation) else null
            }.filterNotNull()
            (if (enabled) frames else frames.take(1)).collect { value = it }
        }
        val actionTitle = remember(safety, target.actionTitle) { safePreviewActionTitle(target, safety) }
        ToolPreviewPresentation(
            display = frame?.presentation?.display,
            actionTitle = actionTitle,
            actionResource = safety.taskActionResource,
            sourceTarget = frame?.input?.target,
        )
    }
}

@Composable
internal fun rememberToolPreviewDisplay(
    target: ToolPreviewTarget,
    enabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): ToolPreviewDisplay? = rememberToolPreviewPresentation(target, enabled, dispatcher).display
