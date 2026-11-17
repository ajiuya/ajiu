package com.aicode.feature.agent.presentation.component

// Leave headroom for UTF-16 parcel overhead below Binder's shared transaction limit.
internal const val SUBAGENT_CLIPBOARD_MAX_UTF8_BYTES = 400 * 1024
internal const val SUBAGENT_CLIPBOARD_MAX_CHARS = 400 * 1024

internal enum class SubAgentTextCopyResult { COPIED, TOO_LARGE, FAILED }

internal fun canCopySubAgentInteractionText(text: CharSequence): Boolean {
    if (text.length > SUBAGENT_CLIPBOARD_MAX_CHARS) return false
    var bytes = 0
    var offset = 0
    while (offset < text.length) {
        val ch = text[offset]
        bytes += when {
            ch.code <= 0x7F -> 1
            ch.code <= 0x7FF -> 2
            Character.isHighSurrogate(ch) && offset + 1 < text.length &&
                Character.isLowSurrogate(text[offset + 1]) -> { offset++; 4 }
            else -> 3
        }
        if (bytes > SUBAGENT_CLIPBOARD_MAX_UTF8_BYTES) return false
        offset++
    }
    return true
}

internal fun copySubAgentInteractionText(text: String, write: (String) -> Unit): SubAgentTextCopyResult {
    if (!canCopySubAgentInteractionText(text)) return SubAgentTextCopyResult.TOO_LARGE
    return try {
        write(text)
        SubAgentTextCopyResult.COPIED
    } catch (_: Exception) {
        SubAgentTextCopyResult.FAILED
    }
}
