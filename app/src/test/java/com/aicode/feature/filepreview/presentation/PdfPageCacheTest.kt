package com.aicode.feature.filepreview.presentation

import android.app.Application
import android.graphics.Bitmap
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfPageCacheTest {
    @Test
    fun renderSizeFollowsTheViewport() {
        assertEquals(PdfPageCache.RenderSize(600, 1200), PdfPageCache.renderSize(1000, 2000, 600))
    }

    @Test
    fun renderWidthNeverExceeds1600Pixels() {
        assertEquals(PdfPageCache.RenderSize(1600, 800), PdfPageCache.renderSize(2000, 1000, 8000))
    }

    @Test
    fun portraitPagesAreReducedByThePixelBudget() {
        val size = PdfPageCache.renderSize(1000, 2000, 1600)
        assertEquals(PdfPageCache.RenderSize(1024, 2048), size)
        assertEquals(PdfPageCache.MAX_PAGE_PIXELS, size.width.toLong() * size.height)
    }

    @Test
    fun extremelyTallPagesCannotAllocateHugeBitmaps() {
        val size = PdfPageCache.renderSize(1, Int.MAX_VALUE, 1600)
        assertEquals(1, size.width)
        assertEquals(PdfPageCache.MAX_HEIGHT_PX, size.height)
        assertTrue(size.width.toLong() * size.height <= PdfPageCache.MAX_PAGE_PIXELS)
    }

    @Test
    fun extremelyWidePagesStillHavePositiveHeight() {
        assertEquals(PdfPageCache.RenderSize(1600, 1), PdfPageCache.renderSize(Int.MAX_VALUE, 1, 1600))
    }

    @Test
    fun maximumIntegerPageDimensionsDoNotOverflowTheBudget() {
        val size = PdfPageCache.renderSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(size.width, size.height)
        assertTrue(size.width.toLong() * size.height <= PdfPageCache.MAX_PAGE_PIXELS)
    }

    @Test
    fun dimensionBoundaryMatrixAlwaysFitsAllBudgets() {
        val dimensions = listOf(1, 2, 595, 842, 10_000, Int.MAX_VALUE)
        val viewports = listOf(1, 80, 1600, Int.MAX_VALUE)
        dimensions.forEach { width ->
            dimensions.forEach { height ->
                viewports.forEach { viewport ->
                    val size = PdfPageCache.renderSize(width, height, viewport)
                    assertTrue(size.width in 1..minOf(viewport, PdfPageCache.MAX_WIDTH_PX))
                    assertTrue(size.height in 1..PdfPageCache.MAX_HEIGHT_PX)
                    assertTrue(size.width.toLong() * size.height <= PdfPageCache.MAX_PAGE_PIXELS)
                }
            }
        }
    }

    @Test
    fun invalidPageDimensionsAndViewportAreRejected() {
        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PdfPageCache.renderSize(invalid, 100, 100) }
            assertThrows(IllegalArgumentException::class.java) { PdfPageCache.renderSize(100, invalid, 100) }
            assertThrows(IllegalArgumentException::class.java) { PdfPageCache.renderSize(100, 100, invalid) }
        }
    }

    @Test
    fun cacheCannotBeConfiguredAbove24MiBOrBelowZero() {
        assertThrows(IllegalArgumentException::class.java) { PdfPageCache(PdfPageCache.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageCache(-1) }
    }

    @Test
    fun defaultCacheRetainsAtMost24MiB() {
        val cache = PdfPageCache()
        val bitmaps = List(4) { bitmap(1024, 2048) }
        bitmaps.forEachIndexed { index, bitmap -> assertTrue(cache.publish(key(index), bitmap)) }
        assertEquals(PdfPageCache.MAX_BYTES, cache.byteCount)
        assertNull(cache[key(0)])
        bitmaps.drop(1).forEachIndexed { index, bitmap -> assertSame(bitmap, cache[key(index + 1)]) }
        bitmaps.forEach { assertFalse(it.isRecycled) }
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryWithoutRecyclingAnyPublishedBitmap() {
        val first = bitmap()
        val second = bitmap()
        val third = bitmap()
        val cache = PdfPageCache(first.allocationByteCount.toLong() * 2)
        cache.publish(key(0), first)
        cache.publish(key(1), second)
        assertSame(first, cache[key(0)])
        cache.publish(key(2), third)
        assertNull(cache[key(1)])
        assertSame(first, cache[key(0)])
        assertSame(third, cache[key(2)])
        assertEquals(first.allocationByteCount.toLong() * 2, cache.byteCount)
        listOf(first, second, third).forEach { assertFalse(it.isRecycled) }
    }

    @Test
    fun replacingAKeyUpdatesBytesWithoutRecyclingThePreviousDisplay() {
        val cache = PdfPageCache()
        val first = bitmap()
        val replacement = bitmap(20, 20)
        cache.publish(key(0), first)
        cache.publish(key(0), replacement)
        assertSame(replacement, cache[key(0)])
        assertEquals(replacement.allocationByteCount.toLong(), cache.byteCount)
        assertFalse(first.isRecycled)
    }

    @Test
    fun oversizeBitmapIsDeliveredButNotRetained() = runTest {
        val bitmap = bitmap()
        val cache = PdfPageCache(bitmap.allocationByteCount.toLong() - 1, StandardTestDispatcher(testScheduler))
        assertSame(bitmap, cache.load(key(0)) { bitmap })
        assertNull(cache[key(0)])
        assertEquals(0L, cache.byteCount)
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun zeroBudgetCanDeliverAPageWithoutRetainingIt() = runTest {
        val cache = PdfPageCache(0, StandardTestDispatcher(testScheduler))
        val bitmap = bitmap()
        assertSame(bitmap, cache.load(key(0)) { bitmap })
        assertEquals(0L, cache.byteCount)
        assertNull(cache[key(0)])
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun pageIndexWidthAndBackgroundAllSeparateCacheEntries() {
        val cache = PdfPageCache()
        val keys = listOf(key(0), key(1), key(0, 800), key(0, background = 1), key(99))
        val bitmaps = keys.map { bitmap() }
        keys.zip(bitmaps).forEach { (key, bitmap) -> cache.publish(key, bitmap) }
        keys.zip(bitmaps).forEach { (key, bitmap) -> assertSame(bitmap, cache[key]) }
    }

    @Test
    fun closeIsIdempotentAndNeverRecyclesPublishedBitmaps() {
        val cache = PdfPageCache()
        val displayed = bitmap()
        cache.publish(key(0), displayed)
        cache.close()
        cache.close()
        assertTrue(cache.isClosed)
        assertEquals(0L, cache.byteCount)
        assertNull(cache[key(0)])
        assertFalse(displayed.isRecycled)
        assertFalse(cache.publish(key(1), bitmap()))
    }

    @Test
    fun cachedPageDoesNotRunTheProducerAgain() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        val bitmap = bitmap()
        var calls = 0
        assertSame(bitmap, cache.load(key(0)) { calls++; bitmap })
        assertSame(bitmap, cache.load(key(0)) { error("Cache hit must not render") })
        assertEquals(1, calls)
    }

    @Test
    fun closedCacheDoesNotStartPageWork() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        cache.close()
        var called = false
        try {
            cache.load(key(0)) { called = true; bitmap() }
            throw AssertionError("Closed cache returned a page")
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        assertFalse(called)
    }

    @Test
    fun closeDuringUninterruptibleRenderRejectsAndRecyclesItsLateResult() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        var published = false
        val request = launch {
            cache.load(key(0)) {
                withContext(NonCancellable) { release.await() }
                bitmap
            }
            published = true
        }
        runCurrent()
        cache.close()
        release.complete(Unit)
        request.join()
        assertFalse(published)
        assertNull(cache[key(0)])
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun cancellationDuringUninterruptibleRenderRecyclesOnlyTheUnpublishedResult() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        val displayed = bitmap()
        cache.publish(key(0), displayed)
        val release = CompletableDeferred<Unit>()
        val unpublished = bitmap()
        var published = false
        val request = launch {
            cache.load(key(1)) {
                withContext(NonCancellable) { release.await() }
                unpublished
            }
            published = true
        }
        runCurrent()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertFalse(published)
        assertNull(cache[key(1)])
        assertSame(displayed, cache[key(0)])
        assertFalse(displayed.isRecycled)
        assertTrue(unpublished.isRecycled)
    }

    @Test
    fun closeBetweenRenderAndDeliveryDoesNotPublishTheOldResult() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        val bitmap = bitmap()
        try {
            cache.load(key(0)) { bitmap.also { cache.close() } }
            throw AssertionError("Closed cache published a late result")
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        assertTrue(bitmap.isRecycled)
        assertEquals(0L, cache.byteCount)
    }

    @Test
    fun failedProducerCanBeRetriedWithoutClosingTheSession() = runTest {
        val cache = PdfPageCache(dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.load(key(0)) { throw IOException("Invalid page") }
            throw AssertionError("Failed render returned a page")
        } catch (_: IOException) {
            assertFalse(cache.isClosed)
        }
        assertEquals(0L, cache.byteCount)
        val bitmap = bitmap()
        assertSame(bitmap, cache.load(key(0)) { bitmap })
        assertSame(bitmap, cache[key(0)])
        assertFalse(bitmap.isRecycled)
    }

    private fun bitmap(width: Int = 10, height: Int = 10): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun key(index: Int, width: Int = 1600, background: Int = 0) =
        PdfPageCache.Key(index, width, background)
}
