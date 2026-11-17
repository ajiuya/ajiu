package com.aicode.feature.agent.presentation.component

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import ru.noties.jlatexmath.JLatexMathAndroid
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MarkdownMathResourcesTest {
    @Before
    fun initializeFormulaAssets() {
        JLatexMathAndroid.init(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test
    fun realFormulaLayoutAndBitmapShareFlightsAndHaveMatchingExactDimensions() = runTest {
        val source = MarkdownPreprocessor.process("\$\$\\frac{a+b}{c} = x_1^2\$\$")
        val first = async { LatexCache.prepare(source, 20f, 0xff123456.toInt()) }
        val second = async { LatexCache.prepare(source, 20f, 0xff123456.toInt()) }
        val one = first.await()
        val two = second.await()
        val link = one.layouts.keys.single()
        val layout = one.layouts.getValue(link)
        val bitmap = one.bitmaps.getValue(link)
        assertSame(layout, two.layouts.getValue(link))
        assertSame(bitmap, two.bitmaps.getValue(link))
        assertNotNull(layout.drawable)
        assertEquals(layout.width, bitmap.width)
        assertEquals(layout.height, bitmap.height)
        assertFalse(bitmap.isRecycled)
        val recolored = LatexCache.prepare(source, 20f, 0xffabcdef.toInt())
        assertSame(layout, recolored.layouts.getValue(link))
        assertNotSame(bitmap, recolored.bitmaps.getValue(link))
        assertEquals(layout.width, recolored.bitmaps.getValue(link).width)
    }

    @Test
    fun fullOriginalFormulaLinksRemainPresentAndFontSizeInvalidatesLayout() = runTest {
        val source = MarkdownPreprocessor.process("\$a+b\$ and \$\$\\sum_{i=1}^{10} i\$\$")
        val normal = LatexCache.prepare(source, 18f, 0xff000000.toInt())
        val enlarged = LatexCache.prepare(source, 27f, 0xff000000.toInt())
        assertEquals(2, normal.layouts.size)
        assertEquals(normal.layouts.keys, normal.bitmaps.keys)
        assertEquals(normal.layouts.keys, enlarged.layouts.keys)
        normal.layouts.forEach { (link, layout) ->
            assertTrue(enlarged.layouts.getValue(link).height >= layout.height)
            assertNotSame(layout, enlarged.layouts.getValue(link))
            assertFalse(normal.bitmaps.getValue(link).isRecycled)
        }
        assertTrue(LatexCache.layoutStats().completedWeight <= MarkdownCacheBudget.FORMULA_LAYOUT)
        assertTrue(LatexCache.bitmapStats().completedWeight <= MarkdownCacheBudget.FORMULA_BITMAP)
    }
}
