package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
@Config(
    sdk = [28],
    qualifiers = "zh-rCN-w400dp-h1000dp",
    application = Application::class,
    manifest = Config.NONE
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SessionRecoveryCardTest {
    @Test(timeout = 30_000)
    fun emptyQueueDoesNotShowARecoveryCardOrDispatchAnything() {
        val callbacks = mutableListOf<String>()
        render({ SessionRecoveryCard(emptyList(), false, callbacks::add, callbacks::add) }) {
            assertFalse(hostNodes().any { it.tag() == "session_recovery_card" })
            assertFalse(hasDialog())
            assertTrue(callbacks.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun summaryOnlyShowsTheFirstQueuedItemAndMarksTheDraftIncompleteAndUnverified() {
        val first = item().copy(unknownTools = listOf("Bash", "writeFile"), pendingCount = 2,
            stage = com.aicode.feature.agent.domain.recovery.RecoveryStage.TOOLS_UNKNOWN)
        val later = item("later-request").copy(request = "Later queued request", draft = "Later visible draft")
        render({ SessionRecoveryCard(listOf(first, later), false, {}, {}) }) {
            val texts = hostTexts()
            assertTrue(activity.getString(R.string.session_recovery_interrupted) in texts)
            assertTrue(activity.getString(recoveryStageLabel(first.stage)) in texts)
            assertTrue(activity.getString(R.string.session_recovery_pending_count, 3) in texts)
            assertTrue("最后可见草稿（不完整、未验证）" in texts)
            assertTrue(first.request in texts)
            assertTrue(first.draft in texts)
            assertTrue(activity.getString(R.string.session_recovery_unknown_summary, "Bash, writeFile") in texts)
            assertFalse(later.request in texts)
            assertFalse(later.draft in texts)
            assertFalse(hasDialog())
        }
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "en-w400dp-h1000dp")
    fun englishSummaryAlsoMarksTheVisibleDraftIncompleteAndUnverified() {
        render({ SessionRecoveryCard(listOf(item()), false, {}, {}) }) {
            assertTrue("Session interrupted" in hostTexts())
            assertTrue("Last visible draft (incomplete, unverified)" in hostTexts())
        }
    }

    @Test(timeout = 30_000)
    fun environmentMismatchDisablesContinueButStillAllowsInspectCopyAndConfirmedAbandon() {
        val recovery = item("original/request:mismatch").copy(environmentMatches = false)
        val continued = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        val inspected = mutableListOf<String>()
        render({
            SessionRecoveryCard(listOf(recovery), false, continued::add, abandoned::add, inspected::add)
        }) {
            assertDisabled(hostNode("session_recovery_continue"))
            invokeIfPresent(hostNode("session_recovery_continue"))
            assertTrue(continued.isEmpty())
            assertFalse(hasDialog())
            assertTrue(activity.getString(R.string.session_recovery_environment_mismatch) in hostTexts())
            clickHost("session_recovery_inspect")
            awaitSheet("session_recovery_sheet_continue")
            assertEquals(listOf(recovery.requestId), inspected)
            assertDisabled(sheetNode("session_recovery_sheet_continue"))
            invokeIfPresent(sheetNode("session_recovery_sheet_continue"))
            assertTrue(continued.isEmpty())
            assertFalse(sheetNodes().any { it.tag() == "session_recovery_confirm_continue" })
            clickSheet("session_recovery_copy_draft")
            assertEquals(recovery.draft, clipboardText())
            clickSheet("session_recovery_sheet_abandon")
            awaitSheet("session_recovery_confirm_abandon")
            assertTrue(abandoned.isEmpty())
            clickSheet("session_recovery_confirm_abandon")
            assertEquals(listOf(recovery.requestId), abandoned)
            assertTrue(continued.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun unknownResultsNeedOptInAndAConfirmedContinueDispatchesTheOriginalIdOnlyOnce() {
        val recovery = item("original/request:unknown").copy(unknownTools = listOf("Bash", "writeFile"))
        val busy = mutableStateOf(false)
        val continued = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        render({
            SessionRecoveryCard(listOf(recovery), busy.value, onContinue = {
                continued += it
                busy.value = true
            }, onAbandon = abandoned::add)
        }) {
            clickHost("session_recovery_continue")
            awaitSheet("session_recovery_confirm_continue")
            assertTrue(continued.isEmpty())
            assertTrue(recovery.request in sheetTexts())
            assertTrue(recovery.draft in sheetTexts())
            assertTrue("Bash\nwriteFile" in sheetTexts())
            assertTrue(activity.getString(R.string.session_recovery_unknown_hint) in sheetTexts())
            assertEquals(ToggleableState.Off, sheetNode("session_recovery_unknown_opt_in").toggleState())
            assertDisabled(sheetNode("session_recovery_confirm_continue"))
            invokeIfPresent(sheetNode("session_recovery_confirm_continue"))
            assertTrue(continued.isEmpty())

            clickSheet("session_recovery_unknown_opt_in")
            awaitUi("Unknown-result acknowledgement") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            assertEquals(ToggleableState.On, sheetNode("session_recovery_unknown_opt_in").toggleState())
            val previouslyEnabledConfirm = sheetNode("session_recovery_confirm_continue").clickAction()
            clickSheet("session_recovery_unknown_opt_in")
            assertTrue(previouslyEnabledConfirm())
            assertTrue(continued.isEmpty())
            awaitUi("Opt-in revoked") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == false
            }
            clickSheet("session_recovery_unknown_opt_in")
            awaitUi("Opt-in reconfirmed") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            val confirm = sheetNode("session_recovery_confirm_continue").clickAction()
            assertTrue(confirm())
            assertTrue(confirm())
            assertEquals(listOf(recovery.requestId), continued)
            assertTrue(abandoned.isEmpty())

            awaitUi("Busy recovery card") {
                !hostNode("session_recovery_continue").isEnabled() && !hostNode("session_recovery_abandon").isEnabled()
            }
            invokeIfPresent(hostNode("session_recovery_continue"))
            invokeIfPresent(hostNode("session_recovery_abandon"))
            assertEquals(listOf(recovery.requestId), continued)
            assertTrue(abandoned.isEmpty())
            clickHost("session_recovery_inspect")
            awaitSheet("session_recovery_copy_draft")
            assertDisabled(sheetNode("session_recovery_sheet_continue"))
            clickSheet("session_recovery_copy_draft")
            assertEquals(recovery.draft, clipboardText())
        }
    }

    @Test(timeout = 30_000)
    fun continueUsesALocalDoubleClickLockWhileTheHostBusyUpdateIsStillPending() {
        val recovery = item("not-an-index/or-title")
        val busy = mutableStateOf(false)
        val continued = mutableListOf<String>()
        render({ SessionRecoveryCard(listOf(recovery), busy.value, continued::add, {}) }) {
            clickHost("session_recovery_continue")
            awaitSheet("session_recovery_confirm_continue")
            assertTrue(continued.isEmpty())
            val confirm = sheetNode("session_recovery_confirm_continue").clickAction()
            assertTrue(confirm())
            assertTrue(confirm())
            assertFalse(busy.value)
            assertEquals(listOf(recovery.requestId), continued)
            awaitUi("Local submission lock") { !hostNode("session_recovery_continue").isEnabled() }
            assertDisabled(hostNode("session_recovery_abandon"))

            busy.value = true
            awaitUi("Host becomes busy") { !hostNode("session_recovery_continue").isEnabled() }
            busy.value = false
            awaitUi("Host finishes processing") { hostNode("session_recovery_continue").isEnabled() }
            assertEquals(listOf(recovery.requestId), continued)
        }
    }

    @Test(timeout = 30_000)
    fun busyBecomingTrueDuringReviewDisablesConfirmationWithoutDispatchingARequest() {
        val busy = mutableStateOf(false)
        val continued = mutableListOf<String>()
        render({ SessionRecoveryCard(listOf(item()), busy.value, continued::add, {}) }) {
            clickHost("session_recovery_continue")
            awaitSheet("session_recovery_confirm_continue")
            assertEnabled(sheetNode("session_recovery_confirm_continue"))
            busy.value = true
            awaitUi("Review becomes busy") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == false
            }
            invokeIfPresent(sheetNode("session_recovery_confirm_continue"))
            assertTrue(continued.isEmpty())
            assertTrue(activity.getString(R.string.session_recovery_busy) in sheetTexts())
            busy.value = false
            awaitUi("Review unlocks") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            assertTrue(continued.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun unknownResultOptInResetsWhenReviewReopensOrItsSnapshotChanges() {
        val recovery = mutableStateOf(item().copy(unknownTools = listOf("originalTool")))
        val continued = mutableListOf<String>()
        render({ SessionRecoveryCard(listOf(recovery.value), false, continued::add, {}) }) {
            clickHost("session_recovery_continue")
            awaitSheet("session_recovery_unknown_opt_in")
            clickSheet("session_recovery_unknown_opt_in")
            awaitUi("Initial opt-in") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            clickSheet("session_recovery_back")
            awaitSheet("session_recovery_sheet_continue")
            clickSheet("session_recovery_sheet_continue")
            awaitSheet("session_recovery_confirm_continue")
            assertEquals(ToggleableState.Off, sheetNode("session_recovery_unknown_opt_in").toggleState())
            assertDisabled(sheetNode("session_recovery_confirm_continue"))

            clickSheet("session_recovery_unknown_opt_in")
            awaitUi("Second opt-in") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            recovery.value = recovery.value.copy(unknownTools = listOf("differentTool"))
            awaitUi("Updated unknown tools") {
                "differentTool" in sheetTexts() &&
                    sheetNodeOrNull("session_recovery_unknown_opt_in")?.toggleState() == ToggleableState.Off
            }
            assertDisabled(sheetNode("session_recovery_confirm_continue"))
            clickSheet("session_recovery_unknown_opt_in")
            awaitUi("Updated tools acknowledged") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == true
            }
            recovery.value = recovery.value.copy(environmentMatches = false)
            awaitUi("Environment mismatch during review") {
                sheetNodeOrNull("session_recovery_confirm_continue")?.isEnabled() == false &&
                    sheetNodeOrNull("session_recovery_unknown_opt_in")?.toggleState() == ToggleableState.Off
            }
            assertDisabled(sheetNode("session_recovery_unknown_opt_in"))
            invokeIfPresent(sheetNode("session_recovery_confirm_continue"))
            assertTrue(continued.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun abandonRequiresConfirmationAndCancelDoesNotDiscardTheRequest() {
        val recovery = item("abandon/original:id").copy(unknownTools = listOf("Bash"))
        val continued = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        render({ SessionRecoveryCard(listOf(recovery), false, continued::add, abandoned::add) }) {
            clickHost("session_recovery_abandon")
            awaitSheet("session_recovery_confirm_abandon")
            assertTrue(abandoned.isEmpty())
            assertTrue(activity.getString(R.string.session_recovery_abandon_hint) in sheetTexts())
            assertFalse(sheetNodes().any { it.tag() == "session_recovery_unknown_opt_in" })
            clickSheet("session_recovery_back")
            awaitSheet("session_recovery_sheet_abandon")
            assertTrue(abandoned.isEmpty())
            clickSheet("session_recovery_sheet_abandon")
            awaitSheet("session_recovery_confirm_abandon")
            val confirm = sheetNode("session_recovery_confirm_abandon").clickAction()
            assertTrue(confirm())
            assertTrue(confirm())
            assertEquals(listOf(recovery.requestId), abandoned)
            assertTrue(continued.isEmpty())
            awaitUi("Abandon submission lock") { !hostNode("session_recovery_abandon").isEnabled() }
        }
    }

    @Test(timeout = 30_000)
    fun pagingCanReachEveryQueuedItemAndContinuesTheSelectedOriginalRequestId() {
        val recoveries = (1..8).map { index ->
            item("queued/original:$index").copy(request = "Public request $index", draft = "Visible draft $index")
        }
        val continued = mutableListOf<String>()
        val inspected = mutableListOf<String>()
        render({ SessionRecoveryCard(recoveries, false, continued::add, {}, inspected::add) }) {
            clickHost("session_recovery_inspect")
            recoveries.forEachIndexed { index, recovery ->
                awaitUi("Recovery page ${index + 1}") { recovery.draft in sheetTexts() }
                assertTrue(recovery.request in sheetTexts())
                assertTrue(activity.getString(R.string.session_recovery_page, index + 1, recoveries.size) in sheetTexts())
                if (index == 0) assertDisabled(sheetNode("session_recovery_previous"))
                if (index < recoveries.lastIndex) clickSheet("session_recovery_next")
            }
            assertDisabled(sheetNode("session_recovery_next"))
            assertEquals(listOf(recoveries.first().requestId), inspected)
            assertTrue(continued.isEmpty())
            clickSheet("session_recovery_sheet_continue")
            awaitSheet("session_recovery_confirm_continue")
            clickSheet("session_recovery_confirm_continue")
            assertEquals(listOf(recoveries.last().requestId), continued)
        }
    }

    @Test(timeout = 30_000)
    fun pagingRetainsTheSelectedRequestIdentityWhenOtherQueuedItemsReorder() {
        val first = item("first").copy(draft = "First draft")
        val selected = item("selected").copy(draft = "Selected draft", unknownTools = listOf("selectedTool"))
        val other = item("other").copy(draft = "Other draft")
        val items = mutableStateOf(listOf(first, selected, other))
        render({ SessionRecoveryCard(items.value, false, {}, {}) }) {
            clickHost("session_recovery_inspect")
            awaitSheet("session_recovery_next")
            clickSheet("session_recovery_next")
            awaitUi("Selected request") { selected.draft in sheetTexts() }
            items.value = listOf(first, other, selected)
            awaitUi("Selected request after reorder") {
                selected.draft in sheetTexts() &&
                    activity.getString(R.string.session_recovery_page, 3, 3) in sheetTexts()
            }
            assertTrue("selectedTool" in sheetTexts())
            assertFalse(other.draft in sheetTexts())
            assertDisabled(sheetNode("session_recovery_next"))
        }
    }

    @Test(timeout = 30_000)
    fun longVisibleDraftRemainsPlainTextCopiesInFullAndDoesNotExposeARawExceptionReason() {
        val rawReason = "java.lang.IllegalStateException: internal diagnostic details"
        val draft = "<b>Literal draft text, not HTML</b>\n" + (1..120).joinToString("\n") { "Visible draft line $it" }
        val recovery = item().copy(draft = draft, reason = rawReason)
        render({ SessionRecoveryCard(listOf(recovery), false, {}, {}) }) {
            clickHost("session_recovery_inspect")
            awaitSheet("session_recovery_draft_body")
            assertTrue(draft in sheetTexts())
            assertTrue(activity.getString(R.string.session_recovery_draft_label) in sheetTexts())
            assertTrue(activity.getString(R.string.session_recovery_reason_interrupted) in sheetTexts())
            assertFalse(rawReason in sheetTexts())
            val sheet = sheetNode("session_recovery_sheet")
            assertTrue(sheet.boundsInRoot.height > 0f)
            assertTrue(sheet.boundsInRoot.height <= 1_000f)
            assertFalse(sheetNodes().any { it.config.contains(SemanticsActions.SetText) })
            clickSheet("session_recovery_copy_draft")
            assertEquals(draft, clipboardText())
        }
    }

    private fun item(requestId: String = "original-request-id") = SessionRecoveryItemUi(
        requestId = requestId,
        request = "Public original request",
        draft = "Last public visible draft\nNot a verified result",
        unknownTools = emptyList(),
        reason = "process_interrupted",
        environmentMatches = true
    )

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)
    private fun SemanticsNode.toggleState(): ToggleableState? = config.getOrNull(SemanticsProperties.ToggleableState)
    private fun SemanticsNode.isEnabled(): Boolean = !config.contains(SemanticsProperties.Disabled)
    private fun assertDisabled(node: SemanticsNode) = assertFalse("${node.tag()} must be disabled", node.isEnabled())
    private fun assertEnabled(node: SemanticsNode) = assertTrue("${node.tag()} must be enabled", node.isEnabled())

    private fun SemanticsNode.clickAction(): () -> Boolean {
        val action = config.getOrNull(SemanticsActions.OnClick)?.action
        assertNotNull("${tag()} must expose a click action", action)
        return requireNotNull(action)
    }

    private fun invokeIfPresent(node: SemanticsNode) {
        node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun findComposeRoot(candidate: View): ViewRootForTest? {
        if (candidate is ViewRootForTest) return candidate
        if (candidate is ViewGroup) {
            repeat(candidate.childCount) { index ->
                findComposeRoot(candidate.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun nodes(root: ViewRootForTest?): List<SemanticsNode> =
        root?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = false) ?: emptyList()

    private fun texts(nodes: List<SemanticsNode>): List<String> = nodes.flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    }

    private inner class RecoveryUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit
    ) {
        fun hostNodes(): List<SemanticsNode> = nodes(findComposeRoot(view))
        fun sheetNodes(): List<SemanticsNode> = nodes(
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::findComposeRoot)
        )
        fun hostTexts(): List<String> = texts(hostNodes())
        fun sheetTexts(): List<String> = texts(sheetNodes())
        fun hostNode(tag: String): SemanticsNode = hostNodes().single { it.tag() == tag }
        fun sheetNode(tag: String): SemanticsNode = sheetNodes().single { it.tag() == tag }
        fun sheetNodeOrNull(tag: String): SemanticsNode? = sheetNodes().singleOrNull { it.tag() == tag }
        fun hasDialog(): Boolean = ShadowDialog.getLatestDialog()?.isShowing == true
        fun clickHost(tag: String) {
            val node = hostNode(tag)
            assertEnabled(node)
            assertTrue(node.clickAction()())
        }
        fun clickSheet(tag: String) {
            val node = sheetNode(tag)
            assertEnabled(node)
            assertTrue(node.clickAction()())
        }
        fun awaitSheet(tag: String) = awaitUi("Sheet node $tag") { sheetNodeOrNull(tag) != null }
        fun clipboardText(): String? =
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.text?.toString()

        fun awaitUi(message: String, condition: () -> Boolean) {
            repeat(180) {
                advanceFrame()
                if (condition()) return
            }
            fail("$message did not complete within 180 controlled frames; " +
                "hostTags=${hostNodes().mapNotNull { it.tag() }}, " +
                "sheetTags=${sheetNodes().mapNotNull { it.tag() }}, dialog=${hasDialog()}")
        }
    }

    private fun render(content: @Composable () -> Unit, inspect: RecoveryUi.() -> Unit) =
        runTest(timeout = 15.seconds) {
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
            val ui = RecoveryUi(activity, view) {
                repeat(4) { looper.runOneTask() }
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                findComposeRoot(view)?.measureAndLayoutForTest()
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    findComposeRoot(decor)?.measureAndLayoutForTest()
                }
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
            }
            try {
                view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                // Modal and switch animations advance only on bounded, explicitly supplied frames.
                view.setParentCompositionContext(recomposer)
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        AIEditorTheme(darkTheme = false, content = content)
                    }
                }
                activity.setContentView(view)
                ui.awaitUi("Initial composition") { findComposeRoot(view) != null }
                ui.inspect()
            } finally {
                view.disposeComposition()
                recomposer.cancel()
                runner.cancel()
                runCurrent()
                controller.pause().stop().destroy()
            }
        }
}
