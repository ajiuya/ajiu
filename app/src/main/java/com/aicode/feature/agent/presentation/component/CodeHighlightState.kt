package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.aicode.feature.agent.presentation.preview.UiWorkEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CodeHighlightBlockIdentity(val owner: Any, val startOffset: Int, val nodeType: String) {
    val key: String get() = "$startOffset:$nodeType"
}

private data class CodeHighlightInput(val code: String, val epoch: Any = Any())

@Composable
internal fun rememberCodeHighlightState(
    blockIdentity: CodeHighlightBlockIdentity,
    code: String,
    language: String?,
    dark: Boolean,
    cached: AnnotatedString? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    highlight: suspend (String) -> AnnotatedString,
): AnnotatedString {
    var input by remember(blockIdentity, language, dark) { mutableStateOf(CodeHighlightInput(code)) }
    if (input.code != code) {
        // 非追加输入即使随后变回原文，也不能接收替换前的在途结果。
        input = CodeHighlightInput(code, if (code.startsWith(input.code)) input.epoch else Any())
    }
    val latestInput by rememberUpdatedState(input)
    val latestHighlight by rememberUpdatedState(highlight)
    var highlighted by remember(input.epoch) { mutableStateOf(cached) }
    SideEffect {
        if (cached != null && cached.length > (highlighted?.length ?: -1)) highlighted = cached
    }
    UiWorkEffect(input.epoch, dispatcher) {
        snapshotFlow { latestInput }.collect { request ->
            val highlightInput = latestHighlight
            val prepared = withContext(dispatcher) { highlightInput(request.code) }
            currentCoroutineContext().ensureActive()
            val current = latestInput
            if (current.epoch === request.epoch && current.code.startsWith(request.code) &&
                prepared.length >= (highlighted?.length ?: -1)
            ) {
                highlighted = prepared
            }
        }
    }
    val retained = (cached ?: highlighted)?.takeIf { code.startsWith(it.text) }
    return remember(code, retained) {
        when {
            retained == null -> AnnotatedString(code)
            retained.length == code.length -> retained
            else -> buildAnnotatedString {
                append(retained)
                append(code, retained.length, code.length)
            }
        }
    }
}
