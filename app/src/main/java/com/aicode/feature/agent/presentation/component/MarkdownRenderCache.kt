package com.aicode.feature.agent.presentation.component

import com.aicode.core.ui.KeyedDeferredCache
import com.aicode.core.ui.WeightedLruCache
import com.mikepenz.markdown.model.State as MarkdownParseState
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.intellij.markdown.ast.ASTNode
import java.lang.ref.WeakReference

internal class MarkdownRenderCache(
    maxEntries: Int = 80,
    maxWeight: Long = MarkdownCacheBudget.AST,
    private val parse: suspend (String) -> MarkdownParseState = { text ->
        parseMarkdownFlow(content = text).first { it !is MarkdownParseState.Loading }
    },
) {
    private val parsedStates = WeightedLruCache<String, MarkdownParseState.Success>(maxEntries, maxWeight) { key, state ->
        markdownAstWeight(key, state)
    }
    private val displayed = ArrayDeque<WeakReference<PreparedMarkdown>>()
    private val tasks = KeyedDeferredCache<String, MarkdownParseState>(
        CoroutineScope(SupervisorJob() + Dispatchers.Default), maxEntries, retain = { false },
    )

    fun get(text: String): MarkdownParseState.Success? = parsedStates[text]

    fun getBestPrefix(text: String): MarkdownParseState.Success? = get(text) ?: parsedStates.best { key ->
        if (text.startsWith(key)) key.length else -1
    }

    fun put(state: MarkdownParseState.Success) {
        parsedStates.put(state.content, state)
    }

    suspend fun resolve(text: String): MarkdownParseState {
        get(text)?.let { return it }
        return tasks.get(text) {
            get(text) ?: parse(text).also {
                currentCoroutineContext().ensureActive()
                if (it is MarkdownParseState.Success) put(it)
            }
        }
    }

    @Synchronized
    fun rememberDisplayed(value: PreparedMarkdown) {
        displayed.removeAll { it.get() == null }
        displayed.addLast(WeakReference(value))
        while (displayed.size > 80) displayed.removeFirst()
    }

    @Synchronized
    fun displayedPrefix(raw: String, textSizePx: Float, colorArgb: Int): PreparedMarkdown? = displayed
        .mapNotNull { it.get() }
        .filter { it.textSizePx == textSizePx && it.colorArgb == colorArgb && raw.startsWith(it.raw) }
        .maxByOrNull { it.raw.length }

    fun stats() = parsedStates.stats()
}

internal fun markdownAstWeight(key: String, state: MarkdownParseState.Success): Long {
    var nodes = 0L
    val stack = ArrayDeque<ASTNode>()
    stack.add(state.node)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        nodes++
        node.children.forEach(stack::add)
    }
    return 512L + 2L * (key.length.toLong() + state.content.length) + 192L * nodes
}
