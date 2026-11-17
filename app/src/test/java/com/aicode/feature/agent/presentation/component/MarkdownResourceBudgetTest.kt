package com.aicode.feature.agent.presentation.component

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MarkdownResourceBudgetTest {
    @Test
    fun oversizedPreprocessingReturnsEveryCharacterAndKeepsWarmEntries() = runTest {
        val warm = "<b>warm budget fixture</b>"
        val warmResult = MarkdownPreprocessor.resolve(warm)
        val input = "&amp;" + "x".repeat((MarkdownCacheBudget.PREPROCESS / 2L).toInt())
        val result = MarkdownPreprocessor.resolve(input)
        assertEquals("&" + input.substring(5), result)
        assertNull(MarkdownPreprocessor.peek(input))
        assertSame(warmResult, MarkdownPreprocessor.peek(warm))
        assertTrue(MarkdownPreprocessor.stats().weight <= MarkdownCacheBudget.PREPROCESS)
    }

    @Test
    fun concurrentPreprocessingUsesSharedIdentityAndPreservesCodeAndMathSemantics() = runTest {
        val source = "<tag> &amp; \$x+y\$\n\n```html\n<safe>\$never\$</safe>\n```"
        val first = async { MarkdownPreprocessor.resolve(source) }
        val second = async { MarkdownPreprocessor.resolve(source) }
        val result = first.await()
        assertSame(result, second.await())
        assertTrue(result.contains("<\u200Btag> &"))
        assertTrue(result.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
        assertTrue(result.contains("```html\n<safe>\$never\$</safe>\n```"))
    }

    @Test
    fun textBudgetCountsBothUtf16SourcesAndResultsWithLongArithmetic() {
        assertEquals(256L + 2L * 9L, MarkdownCacheBudget.textPair("abcd", "12345"))
        assertTrue(MarkdownCacheBudget.textPair("emoji 🚀", "emoji 🚀") >= 256L + 4L * "emoji 🚀".length)
    }
}
