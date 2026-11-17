package com.aicode.feature.agent.presentation.component

internal object MarkdownCacheBudget {
    const val PREPROCESS = 16L * 1024 * 1024
    const val AST = 32L * 1024 * 1024
    const val HIGHLIGHT = 16L * 1024 * 1024
    const val FORMULA_LAYOUT = 12L * 1024 * 1024
    const val FORMULA_BITMAP = 48L * 1024 * 1024

    fun textPair(source: String, result: String): Long = 256L + 2L * (source.length.toLong() + result.length)
}
