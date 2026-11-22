package com.aicode.feature.browser.presentation

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.container.PortMapping
import com.aicode.feature.agent.domain.container.PortMappingError
import com.aicode.feature.agent.domain.container.PortMappingState
import com.aicode.feature.agent.domain.container.PortMappingStatus
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PortMappingsUiTest {
    @Test(timeout = 30_000)
    fun remotePortIsRequiredAndInvalidInputNeverEnablesCreation() = render {
        assertDisabled(button(R.string.port_mapping_add))
        for (value in listOf("0", "65536", "-1", "abc", "1.5", "+80", "2147483648", " ", "")) {
            edit(REMOTE_FIELD, value)
            assertDisabled(button(R.string.port_mapping_add))
        }
        assertTrue(additions.isEmpty())
    }

    @Test(timeout = 30_000)
    fun invalidLocalPortsDisableCreationButAnEmptyLocalPortIsAutomatic() = render {
        edit(REMOTE_FIELD, "3000")
        assertEnabled(button(R.string.port_mapping_add))
        for (value in listOf("0", "65536", "-1", "abc", "1.5", "+80", "2147483648", " ")) {
            edit(LOCAL_FIELD, value)
            assertDisabled(button(R.string.port_mapping_add))
            assertTrue(hasText(text(R.string.port_mapping_error_invalid_port)))
        }
        edit(LOCAL_FIELD, "")
        click(R.string.port_mapping_add)
        assertEquals(listOf("3000" to ""), additions)
    }

    @Test(timeout = 30_000)
    fun remoteAndExplicitLocalPortBoundsAreAcceptedByTheRealForm() = render {
        edit(REMOTE_FIELD, "1")
        edit(LOCAL_FIELD, "65535")
        click(R.string.port_mapping_add)
        edit(REMOTE_FIELD, "65535")
        edit(LOCAL_FIELD, "1")
        click(R.string.port_mapping_add)
        assertEquals(listOf("1" to "65535", "65535" to "1"), additions)
    }

    @Test(timeout = 30_000)
    fun localModeExplainsDirectAccessAndNeverOffersAFormOrStaleMappings() = render {
        state = PortMappingState(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_error_local_mode)))
        assertFalse(hasField(REMOTE_FIELD))
        assertFalse(hasField(LOCAL_FIELD))
        for (resource in listOf(R.string.port_mapping_add, R.string.port_mapping_open,
            R.string.port_mapping_copy, R.string.port_mapping_close)) {
            assertFalse(hasText(text(resource)))
        }
        assertTrue(additions.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(copied.isEmpty())
        assertTrue(closed.isEmpty())
    }

    @Test(timeout = 30_000)
    fun sshNotReadyKeepsTheFormAndCreationDisabledWithAConnectionHint() = render {
        edit(REMOTE_FIELD, "3000")
        edit(LOCAL_FIELD, "8080")
        state = state.copy(available = false)
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_error_not_connected)))
        assertDisabled(field(REMOTE_FIELD))
        assertDisabled(field(LOCAL_FIELD))
        assertDisabled(button(R.string.port_mapping_add))
        assertTrue(additions.isEmpty())
        state = state.copy(available = true)
        pump(12)
        assertEnabled(field(REMOTE_FIELD))
        assertEnabled(field(LOCAL_FIELD))
        click(R.string.port_mapping_add)
        assertEquals(listOf("3000" to "8080"), additions)
    }

    @Test(timeout = 30_000)
    fun connectingRowsCanBeCancelledButCannotOpenOrCopy() = render {
        state = state.copy(mappings = listOf(mapping(PortMappingStatus.CONNECTING)))
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_status_connecting)))
        assertTrue(hasText(text(R.string.port_mapping_remote_endpoint, 3000)))
        assertFalse(hasText(text(R.string.port_mapping_open)))
        assertFalse(hasText(text(R.string.port_mapping_copy)))
        assertFalse(hasText(text(R.string.port_mapping_background_warning)))
        click(R.string.port_mapping_cancel)
        assertEquals(listOf(ID), closed)
        assertTrue(opened.isEmpty())
        assertTrue(copied.isEmpty())
    }

    @Test(timeout = 30_000)
    fun activeRowsShowTheActualLoopbackPortAndTargetCallbacksById() = render {
        state = state.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE, localPort = 51234)))
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_status_active)))
        assertTrue(hasText(text(R.string.port_mapping_route, 3000, 51234)))
        click(R.string.port_mapping_open)
        click(R.string.port_mapping_copy)
        click(R.string.port_mapping_close)
        assertEquals(listOf(ID), opened)
        assertEquals(listOf(ID), copied)
        assertEquals(listOf(ID), closed)
    }

    @Test(timeout = 30_000)
    fun failedRowsShowTheirTypedReasonAndCanOnlyBeClosed() = render {
        state = state.copy(mappings = listOf(mapping(
            PortMappingStatus.FAILED, error = PortMappingError.TARGET_UNREACHABLE
        )))
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_status_failed)))
        assertTrue(hasText(text(R.string.port_mapping_error_target_unreachable)))
        assertFalse(hasText(text(R.string.port_mapping_open)))
        assertFalse(hasText(text(R.string.port_mapping_copy)))
        assertFalse(hasText(text(R.string.port_mapping_cancel)))
        click(R.string.port_mapping_close)
        assertEquals(listOf(ID), closed)
    }

    @Test(timeout = 30_000)
    fun everyTypedFailureIsRenderedAsALocalizedReason() = render {
        for (error in PortMappingError.entries) {
            state = state.copy(mappings = listOf(mapping(PortMappingStatus.FAILED, error = error)))
            pump(12)
            assertTrue("Typed reason $error must be visible", hasText(text(error.messageRes())))
            assertEnabled(button(R.string.port_mapping_close))
            assertFalse(hasText(text(R.string.port_mapping_open)))
            assertFalse(hasText(text(R.string.port_mapping_copy)))
        }
    }

    @Test(timeout = 30_000)
    fun activeRowsWarnUntilTheSystemHasConfirmedBackgroundProtection() = render {
        state = state.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        pump(12)
        assertTrue(hasText(text(R.string.port_mapping_background_warning)))
        state = state.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE).copy(backgroundProtected = true)))
        pump(12)
        assertFalse(hasText(text(R.string.port_mapping_background_warning)))
        assertTrue(hasText(text(R.string.port_mapping_status_active)))
    }

    @Test(timeout = 30_000)
    fun fourConnectingOrActiveMappingsDisableCreationButFailedRowsDoNotCount() = render {
        edit(REMOTE_FIELD, "3000")
        val rows = List(4) { index ->
            mapping(if (index == 0) PortMappingStatus.ACTIVE else PortMappingStatus.CONNECTING).copy(id = "mapping-$index")
        }
        state = state.copy(mappings = rows)
        pump(12)
        assertDisabled(button(R.string.port_mapping_add))
        assertTrue(hasText(text(R.string.port_mapping_error_limit_reached)))
        state = state.copy(mappings = rows.dropLast(1) + mapping(
            PortMappingStatus.FAILED, error = PortMappingError.CONNECTION_CHANGED
        ))
        pump(12)
        assertEnabled(button(R.string.port_mapping_add))
        click(R.string.port_mapping_add)
        assertEquals(listOf("3000" to ""), additions)
    }

    @Test(timeout = 30_000)
    fun aLostConnectionDisablesActiveOpenAndCopyButStillAllowsClose() = render {
        state = state.copy(available = false, mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        pump(12)
        assertDisabled(button(R.string.port_mapping_open))
        assertDisabled(button(R.string.port_mapping_copy))
        click(R.string.port_mapping_close)
        assertEquals(listOf(ID), closed)
        assertTrue(opened.isEmpty())
        assertTrue(copied.isEmpty())
    }

    @Test(timeout = 30_000)
    fun dismissingAndReopeningTheRealSheetKeepsMappingsWithoutClosingThem() = render(useSheet = true) {
        state = state.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        pump(24)
        assertTrue(hasText(text(R.string.port_mapping_route, 3000, 51000)))
        click(R.string.common_done)
        waitFor("Port mapping sheet must close") { ShadowDialog.getLatestDialog()?.isShowing != true }
        assertEquals(1, dismissals)
        assertTrue(closed.isEmpty())
        assertEquals(listOf(mapping(PortMappingStatus.ACTIVE)), state.mappings)
        sheetVisible = true
        pump(24)
        assertTrue(hasText(text(R.string.port_mapping_route, 3000, 51000)))
        assertTrue(closed.isEmpty())
    }

    private class MappingUi(
        private val activity: ComponentActivity,
        private val view: ComposeView,
        private val useSheet: Boolean,
        private val advanceFrame: () -> Unit
    ) {
        var state by mutableStateOf(PortMappingState(remoteMode = true, available = true))
        var sheetVisible by mutableStateOf(true)
        var dismissals = 0
        val additions = mutableListOf<Pair<String, String>>()
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val closed = mutableListOf<String>()

        fun text(resource: Int, vararg arguments: Any): String = activity.getString(resource, *arguments)
        fun pump(count: Int = 1) = repeat(count) { advanceFrame() }
        private fun root(): ViewRootForTest = requireNotNull(if (useSheet) {
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::findRoot)
        } else findRoot(view))
        private fun nodes(merged: Boolean = true) = root().semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
        fun hasText(text: String): Boolean = nodes(merged = false).any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        fun hasField(tag: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag
        }
        fun field(tag: String): SemanticsNode = nodes().single {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag
        }
        fun button(resource: Int): SemanticsNode = nodes().single {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text(resource) } == true
        }
        fun assertEnabled(node: SemanticsNode) {
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
        }
        fun assertDisabled(node: SemanticsNode) {
            assertTrue(node.config.contains(SemanticsProperties.Disabled))
        }
        fun edit(tag: String, value: String) {
            val node = field(tag)
            assertEnabled(node)
            assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            pump(6)
        }
        fun click(resource: Int) {
            val node = button(resource)
            assertEnabled(node)
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            pump(6)
        }
        fun waitFor(message: String, condition: () -> Boolean) {
            repeat(120) {
                pump()
                if (condition()) return
            }
            assertTrue(message, condition())
        }
    }

    private fun render(useSheet: Boolean = false, inspect: MappingUi.() -> Unit) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        fun layout(window: View) {
            window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        val ui = MappingUi(activity, view, useSheet) {
            looper.idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::layout)
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        if (useSheet) {
                            if (ui.sheetVisible) {
                                PortMappingsSheet(
                                    state = ui.state,
                                    onDismiss = { ui.sheetVisible = false; ui.dismissals++ },
                                    onAdd = { remote, local -> ui.additions += remote to local },
                                    onOpen = { ui.opened += it },
                                    onCopy = { ui.copied += it },
                                    onClose = { ui.closed += it }
                                )
                            }
                        } else {
                            PortMappingsContent(
                                state = ui.state,
                                onAdd = { remote, local -> ui.additions += remote to local },
                                onOpen = { ui.opened += it },
                                onCopy = { ui.copied += it },
                                onClose = { ui.closed += it }
                            )
                        }
                    }
                }
            }
            activity.setContentView(view)
            ui.pump(24)
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }

    companion object {
        private const val ID = "mapping-one"
        private const val REMOTE_FIELD = "port-mapping-remote-port"
        private const val LOCAL_FIELD = "port-mapping-local-port"
        private fun mapping(
            status: PortMappingStatus,
            localPort: Int = 51000,
            error: PortMappingError? = null
        ) = PortMapping(
            id = ID,
            remotePort = 3000,
            localPort = if (status == PortMappingStatus.ACTIVE) localPort else null,
            status = status,
            error = error
        )
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }
}
