package com.aicode.feature.agent.domain.tool.browser

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class BrowserThumbnailScopeTest {
    private val dispatcher = StandardTestDispatcher()
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private lateinit var manager: BrowserManager
    private lateinit var view: WebView
    private lateinit var holder: Any

    @Before
    fun prepare() {
        Dispatchers.setMain(dispatcher)
        manager = BrowserManager(ApplicationProvider.getApplicationContext<Context>(), files, mockk<PathHomeResolver>())
        view = mockk(relaxed = true) {
            every { width } returns 1200
            every { height } returns 2400
            every { progress } returns 100
            every { url } returns "about:blank"
            every { layerType } returns View.LAYER_TYPE_HARDWARE
        }
        val type = BrowserManager::class.java.declaredClasses.single { it.simpleName == "TabHolder" }
        holder = type.declaredConstructors.single { it.parameterCount == 15 }.apply { isAccessible = true }
            .newInstance("existing", view, mockk<ContextThemeWrapper>(), mockk<MutableContextWrapper>(), "about:blank", "", false, null, false,
                ArrayDeque<Any>(), null, null, null, java.util.concurrent.ConcurrentHashMap<String, Any>(), null)
        @Suppress("UNCHECKED_CAST")
        val tabs = BrowserManager::class.java.getDeclaredField("tabs").apply { isAccessible = true }.get(manager) as MutableList<Any>
        tabs += holder
    }

    @After
    fun cleanup() { Dispatchers.resetMain() }

    @Test
    fun detailAndThumbnailHaveIndependentBoundedSamplingWithoutChangingTheTab() = runTest(dispatcher) {
        val before = manager.state.value
        val thumbnail = manager.captureThumbnail("existing")!!
        val detail = manager.captureThumbnail("existing", 2048)!!
        val capped = manager.captureThumbnail("existing", 8192)!!
        try {
            assertEquals(240, thumbnail.width)
            assertEquals(480, thumbnail.height)
            assertEquals(1024, detail.width)
            assertEquals(2048, detail.height)
            assertEquals(2048, capped.height)
            assertEquals(before, manager.state.value)
            verify(exactly = 0) { view.loadUrl(any<String>()) }
            verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
        } finally {
            thumbnail.recycle(); detail.recycle(); capped.recycle()
        }
    }

    @Test
    fun pendingNavigationDuringDrawingDiscardsTheFrameWithoutExportOrSelection() = runTest(dispatcher) {
        val before = manager.state.value
        every { view.draw(any<Canvas>()) } answers {
            holder.javaClass.getDeclaredField("loadDeferred").apply { isAccessible = true }
                .set(holder, CompletableDeferred<Result<String>>())
        }
        assertNull(manager.captureThumbnail("existing"))
        assertEquals(before, manager.state.value)
        verify { view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        verify(exactly = 0) { view.loadUrl(any<String>()) }
        verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
    }

    @Test
    fun incompleteProgressAfterDrawingDiscardsTheFrame() = runTest(dispatcher) {
        every { view.draw(any<Canvas>()) } answers { every { view.progress } returns 20 }
        assertNull(manager.captureThumbnail("existing"))
        verify { view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
    }
}
