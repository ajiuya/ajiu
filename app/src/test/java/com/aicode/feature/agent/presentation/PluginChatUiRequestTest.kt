package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.CHILD
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture.Companion.PARENT
import com.aicode.feature.plugin.runtime.PluginChatUiAction
import com.aicode.feature.plugin.runtime.PluginChatUiRequest
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PluginChatUiRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(TaskKeepaliveController)
        every { TaskKeepaliveController.acquire(any(), any(), any(), any()) } returns false
        every { TaskKeepaliveController.release(any(), any()) } just Runs
        fixture = SubAgentViewModelQueueFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.viewModel.setWorkspace("/work")
        dispatcher.scheduler.runCurrent()
    }
    @After fun tearDown() {
        try { fixture.close(); dispatcher.scheduler.runCurrent() }
        finally { unmockkObject(TaskKeepaliveController); Dispatchers.resetMain() }
    }
    @Test fun explicitDraftSessionSwitchesToThatValidatedNativeSession() = runTest(dispatcher) {
        val viewModel = fixture.viewModel
        viewModel.selectSession(PARENT)
        assertTrue(viewModel.applyPluginChatUiRequest(request(PluginChatUiAction.SetDraft(CHILD, "draft from plugin", false))))
        runCurrent()
        assertEquals(CHILD, viewModel.currentSessionId.value)
        assertEquals("draft from plugin", viewModel.inputDraft.value)
    }
    @Test fun appendPreservesTargetDraftAndSeparatesParagraphs() = runTest(dispatcher) {
        val viewModel = fixture.viewModel
        viewModel.selectSession(CHILD)
        viewModel.updateInputDraft("Existing draft  ")
        viewModel.selectSession(PARENT)
        assertTrue(viewModel.applyPluginChatUiRequest(request(PluginChatUiAction.SetDraft(CHILD, "New paragraph", true))))
        runCurrent()
        assertEquals(CHILD, viewModel.currentSessionId.value)
        assertEquals("Existing draft\n\nNew paragraph", viewModel.inputDraft.value)
    }
    @Test fun workspaceMismatchNeverSwitchesOrWritesAnyDraft() = runTest(dispatcher) {
        val viewModel = fixture.viewModel
        viewModel.selectSession(PARENT)
        assertFalse(viewModel.applyPluginChatUiRequest(request(PluginChatUiAction.SetDraft(CHILD, "wrong workspace", false), "/elsewhere")))
        runCurrent()
        assertEquals(PARENT, viewModel.currentSessionId.value)
        assertEquals("", viewModel.inputDraft.value)
    }
    private fun request(action: PluginChatUiAction, workspace: String = "/work") = PluginChatUiRequest("request-one", "test.sample", workspace, PARENT, action, {}, { _, _ -> })
}
