package com.aicode.feature.agent.presentation.filebrowse

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FileBrowseViewModelScopeTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE).edit().clear().commit()
        fixture = SubAgentViewModelQueueFixture(context)
    }

    @After
    fun tearDown() {
        try {
            fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualRefreshRecapturesANotReadySourceAtTheSameWorkspaceAndFreezesTheListing() = runTest(dispatcher) {
        val ready = AtomicBoolean()
        val freezes = AtomicInteger()
        val listings = AtomicInteger()
        coEvery { fixture.executionFactory.freeze(any()) } coAnswers {
            freezes.incrementAndGet()
            if (!ready.get()) throw CancellationException("Workspace execution source is not ready")
            val mode = fixture.executionModeHolder.currentMode()
            WorkspaceExecutionContext(mode, "queue-fixture", fixture.browseFileAccess, null, null) {
                if (!ready.get() || fixture.executionModeHolder.currentMode() != mode) {
                    throw CancellationException("Fixture source changed")
                }
            }
        }
        every { fixture.browseFileAccess.listFiles(any()) } answers {
            assertNotNull(WorkspaceExecutionContext.current())
            assertEquals("queue-fixture", WorkspaceExecutionContext.current()?.fileCacheIdentity)
            listings.incrementAndGet()
            listOf(FileEntry("restored", false, 0, 0))
        }
        every { fixture.browseFileAccess.exists(any()) } returns false
        // 真实 IO 等待期间虚拟时间可跨过订阅宽限期，持续订阅表示文件页仍可见。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.viewModel.browseState.collect {}
        }
        fixture.viewModel.setWorkspace("/work")
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { fixture.viewModel.browseState.first { it is FileBrowseState.Error } }
        }
        assertEquals(0, listings.get())
        val before = freezes.get()
        ready.set(true)
        fixture.viewModel.refreshBrowse()
        runCurrent()
        val recovered = withContext(Dispatchers.Default) {
            withTimeout(5_000) { fixture.viewModel.browseState.first { it is FileBrowseState.Success } }
        } as FileBrowseState.Success
        assertTrue(freezes.get() > before)
        assertEquals(1, listings.get())
        assertTrue(recovered.nodes.any { it.entry.name == "restored" })
    }
}
