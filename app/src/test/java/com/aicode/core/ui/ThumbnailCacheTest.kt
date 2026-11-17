package com.aicode.core.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
class ThumbnailCacheTest {
    @Test
    fun sameQualityConsumersShareBitmapAndChargeItsActualAllocation() = runBlocking {
        val file = File.createTempFile("thumbnail-cache-test-", ".png")
        try {
            val original = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val before = ThumbnailCache.stats().completedWeight
            val one = async { ThumbnailCache.load(file.absolutePath) }
            val two = async { ThumbnailCache.load(file.absolutePath) }
            val bitmap = requireNotNull(one.await())
            assertSame(bitmap, two.await())
            assertSame(bitmap, ThumbnailCache.peek(file.absolutePath))
            assertTrue(bitmap.width >= THUMBNAIL_MAX_EDGE)
            val allocation = bitmap.asAndroidBitmap().allocationByteCount.toLong()
            assertEquals(allocation + 128L + file.absolutePath.length * 2L,
                ThumbnailCache.stats().completedWeight - before)
            assertFalse(bitmap.asAndroidBitmap().isRecycled)
            assertTrue(ThumbnailCache.stats().entries <= 96)
            assertTrue(ThumbnailCache.stats().completedWeight <= 64L * 1024 * 1024)
        } finally { file.delete() }
    }

    @Test
    fun existingDecodeQualityAndViewerPixelPolicyAreUnchanged() {
        assertEquals(180, THUMBNAIL_MAX_EDGE)
        assertEquals(1024, MARKDOWN_MAX_EDGE)
        assertEquals(4, calculateInSampleSize(720, 360, THUMBNAIL_MAX_EDGE))
        assertEquals(2, calculateInSampleSize(4000, 300, MARKDOWN_MAX_EDGE))
        assertEquals(2, calculateInSampleSize(3000, 3000, 2048, 4_000_000L))
    }
}
