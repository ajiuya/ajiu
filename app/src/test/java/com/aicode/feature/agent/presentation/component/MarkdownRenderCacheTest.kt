package com.aicode.feature.agent.presentation.component

import com.mikepenz.markdown.model.State as MarkdownParseState
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MarkdownRenderCacheTest {
    @Test
    fun exactSourcesAreChargedOnceIndentationStaysDistinctAndOversizeStillReachesItsConsumer() = runTest {
        val state = parseMarkdownFlow(content = "**all text**").first { it is MarkdownParseState.Success } as MarkdownParseState.Success
        val weight = markdownAstWeight(state.content, state)
        val cache = MarkdownRenderCache(maxEntries = 2, maxWeight = weight, parse = { state })
        cache.put(state)
        cache.put(state)
        assertSame(state, cache.get(state.content))
        assertNull(cache.get("    ${state.content}"))
        assertEquals(1, cache.stats().entries)
        assertEquals(weight, cache.stats().weight)
        val oversize = MarkdownRenderCache(maxEntries = 2, maxWeight = weight - 1, parse = { state })
        assertSame(state, oversize.resolve(state.content))
        assertNull(oversize.get(state.content))
        assertEquals(0L, oversize.stats().weight)
        assertEquals("**all text**", state.content)
        val exact = MarkdownRenderCache()
        val plainTask = async { exact.resolve("text") }
        val indentedTask = async { exact.resolve("    text") }
        val plain = plainTask.await() as MarkdownParseState.Success
        val indented = indentedTask.await() as MarkdownParseState.Success
        assertEquals("text", plain.content)
        assertEquals("    text", indented.content)
        assertNotEquals(plain.node.children.first().type, indented.node.children.first().type)
        assertSame(plain, exact.get("text"))
        assertSame(indented, exact.get("    text"))
        assertEquals(2, exact.stats().entries)
    }

    @Test
    fun sameAstRequestSharesParserAndCancellingOneConsumerDoesNotCancelAnother() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val state = parseMarkdownFlow(content = "# title\n\nbody").first { it is MarkdownParseState.Success } as MarkdownParseState.Success
        var calls = 0
        val cache = MarkdownRenderCache(parse = { calls++; entered.complete(Unit); finish.await(); state })
        val first = async { cache.resolve(state.content) }
        entered.await()
        val second = async { cache.resolve(state.content) }
        kotlinx.coroutines.yield()
        first.cancelAndJoin()
        finish.complete(Unit)
        assertSame(state, second.await())
        assertEquals(1, calls)
    }
}
