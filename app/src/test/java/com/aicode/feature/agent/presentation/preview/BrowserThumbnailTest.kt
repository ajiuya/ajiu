package com.aicode.feature.agent.presentation.preview

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ContextThemeWrapper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BrowserThumbnailTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var manager: BrowserManager
    private lateinit var files: FileAccessProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        files = mockk(relaxed = true)
        manager = BrowserManager(context, files, mockk<PathHomeResolver>(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun missingAndBlankTabsDoNotCreateOrSelectAnything() = runTest(dispatcher) {
        val before = manager.state.value
        assertNull(manager.captureThumbnail(""))
        assertNull(manager.captureThumbnail("missing"))
        assertEquals(before, manager.state.value)
        assertTrue(manager.state.value.tabs.isEmpty())
        verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
    }

    @Test
    fun loadingUnlaidOutAndPendingNavigationReturnWithoutDrawing() = runTest(dispatcher) {
        val view = viewport()
        val holder = addTab("existing", view)
        every { view.progress } returns 50
        assertNull(manager.captureThumbnail("existing"))
        every { view.progress } returns 100
        every { view.width } returns 0
        assertNull(manager.captureThumbnail("existing"))
        every { view.width } returns 1_200
        field(holder, "loading", true)
        assertNull(manager.captureThumbnail("existing"))
        verify(exactly = 0) { view.draw(any()) }
    }

    @Test
    fun scaledDrawingStaysOnMainRestoresLayerAndNeverUsesExportFiles() = runTest(dispatcher) {
        val view = viewport()
        var drawnOnMain = false
        every { view.draw(any()) } answers {
            drawnOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        }
        addTab("existing", view)
        val bitmap = manager.captureThumbnail("existing", maxEdge = 480)!!
        assertTrue(drawnOnMain)
        assertEquals(240, bitmap.width)
        assertEquals(480, bitmap.height)
        verify { view.setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        verify { view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        verify(exactly = 0) { view.loadUrl(any<String>()) }
        verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
        bitmap.recycle()
    }

    @Test
    fun navigationOrClosureDuringDrawDiscardsTheUndeliveredFrame() = runTest(dispatcher) {
        val view = viewport()
        val holder = addTab("existing", view)
        every { view.draw(any<Canvas>()) } answers { field(holder, "loading", true) }
        assertNull(manager.captureThumbnail("existing"))
        field(holder, "loading", false)
        every { view.draw(any<Canvas>()) } answers { tabs().clear() }
        assertNull(manager.captureThumbnail("existing"))
    }

    @Test
    fun cancellationBeforeDispatchDoesNotDrawOrAllocateATab() = runTest(dispatcher) {
        val job = launch { manager.captureThumbnail("existing") }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(manager.state.value.tabs.isEmpty())
    }

    @Test
    fun bitmapCacheEvictsWithoutRecyclingImagesStillHeldByCompose() {
        val cache = ToolPreviewBitmapCache()
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        cache.put("first", first)
        for (i in 1..3) cache.put("$i", Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        assertNull(cache["first"])
        assertFalse(first.isRecycled)
        cache.clear()
        assertFalse(first.isRecycled)
        first.recycle()
    }

    private fun viewport(): WebView = mockk(relaxed = true) {
        every { width } returns 1_200
        every { height } returns 2_400
        every { progress } returns 100
        every { url } returns "about:blank"
        every { layerType } returns View.LAYER_TYPE_HARDWARE
    }

    @Suppress("UNCHECKED_CAST")
    private fun tabs(): MutableList<Any> = BrowserManager::class.java.getDeclaredField("tabs").let {
        it.isAccessible = true
        it.get(manager) as MutableList<Any>
    }

    private fun addTab(id: String, view: WebView): Any {
        val holderType = BrowserManager::class.java.declaredClasses.single { it.simpleName == "TabHolder" }
        val constructor = holderType.declaredConstructors.single { it.parameterCount == 15 }
        constructor.isAccessible = true
        val holder = constructor.newInstance(id, view, mockk<ContextThemeWrapper>(), mockk<MutableContextWrapper>(), "about:blank", "", false, null, false,
            ArrayDeque<Any>(), null, null, null, java.util.concurrent.ConcurrentHashMap<String, Any>(), null)
        tabs().add(holder)
        return holder
    }

    private fun field(holder: Any, name: String, value: Any) {
        holder.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(holder, value)
    }
}
