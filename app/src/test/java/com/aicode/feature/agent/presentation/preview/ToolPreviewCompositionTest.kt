package com.aicode.feature.agent.presentation.preview

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.component.taskOperationAction
import com.aicode.feature.agent.presentation.component.taskOperationActionResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ToolPreviewCompositionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun streamingArgumentsAndStageHandoffNeverPublishABlankDisplayAfterTheFirstFrame() = runTest(dispatcher) {
        var target by mutableStateOf(ToolPreviewTarget("call", "writeFile",
            """{"path":"/work/a.kt","content":"first"}""", "", ToolPreviewStage.PREPARING, sourceSessionId = "session"))
        val seen = mutableListOf<ToolPreviewDisplay?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val display = rememberToolPreviewDisplay(target, enabled = true, dispatcher = dispatcher)
                SideEffect { seen += display }
            }
            assertEquals("first", seen.last()!!.diff.added)
            for (next in listOf(
                target.copy(arguments = """{"path":"/work/a.kt","content":"first and second"}"""),
                target.copy(stage = ToolPreviewStage.RUNNING),
                target.copy(stage = ToolPreviewStage.COMPLETED, content = "written"),
            )) {
                seen.clear()
                target = next
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue("same-call updates must not render a loading frame", seen.all { it != null })
                assertEquals(buildToolPreviewDisplay(next), seen.last())
            }
        }
    }

    @Test
    fun matchingThumbnailFrameIsImmediatelyAvailableToOpeningDetailsAndCannotCrossSources() = runTest(dispatcher) {
        val original = ToolPreviewTarget("call", "Bash", """{"command":"pwd"}""", "public output", ToolPreviewStage.COMPLETED,
            sourceSessionId = "session")
        val display = buildToolPreviewDisplay(original)
        val seed = ToolPreviewPresentation(display = display, sourceTarget = original)
        for (next in listOf(original, original.copy(sourceSessionId = "other"), original.copy(id = "other"),
            original.copy(arguments = """{"command":"env"}"""), original.copy(content = "new output"))) {
            val seen = mutableListOf<ToolPreviewPresentation>()
            Runtime(this).use { runtime ->
                runtime.setContent {
                    val presentation = rememberToolPreviewPresentation(next, false, dispatcher, initialPresentation = seed)
                    SideEffect { seen += presentation }
                }
                if (next == original) assertTrue(seen.all { it.display === display && it.sourceTarget == original })
                else assertTrue("a seed must match the entire frozen input, not only its call ID", seen.all { it.display == null && it.sourceTarget == null })
            }
        }
    }

    @Test
    fun openingDetailsPrepareOnlyTheirMissingFirstFrameBeforeTheSheetIsExpanded() = runTest(dispatcher) {
        var target by mutableStateOf(ToolPreviewTarget("call", "Bash", """{"command":"pwd"}""", "first output", ToolPreviewStage.COMPLETED))
        var enabled by mutableStateOf(false)
        var builds = 0
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, dispatcher, buildDisplay = { next, safety ->
                    builds++
                    buildToolPreviewDisplay(next, safety)
                }, prepareInitialWhilePaused = true)
                SideEffect { seen += presentation }
            }
            assertEquals(1, builds)
            assertEquals("first output", seen.last().display!!.body)
            target = target.copy(content = "latest output")
            runtime.flush()
            assertEquals("opening animation must not start continuous output work", 1, builds)
            assertEquals("first output", seen.last().display!!.body)
            enabled = true
            runtime.flush()
            assertEquals(2, builds)
            assertEquals("latest output", seen.last().display!!.body)
        }
    }

    @Test
    fun openingPreheatRetriesAChangedInputUntilOneCurrentFrameIsPublished() = runTest(dispatcher) {
        val first = ToolPreviewTarget("call", "Bash", """{"command":"pwd"}""", "first output", ToolPreviewStage.RUNNING)
        var target by mutableStateOf(first)
        val gate = CompletableDeferred<Unit>()
        val built = mutableListOf<ToolPreviewTarget>()
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, false, dispatcher, buildDisplay = { next, safety ->
                    built += next
                    if (next === first) withContext(NonCancellable) { gate.await() }
                    buildToolPreviewDisplay(next, safety)
                }, prepareInitialWhilePaused = true)
                SideEffect { seen += presentation }
            }
            assertNull(seen.last().display)
            val replacement = first.copy(arguments = """{"command":"whoami"}""", content = "current opening output")
            target = replacement
            runtime.flush()
            gate.complete(Unit)
            runtime.flush()
            assertEquals(listOf(first, replacement), built)
            assertEquals(replacement, seen.last().sourceTarget)
            assertEquals("current opening output", seen.last().display!!.body)
            assertTrue("a cancelled opening frame must not publish later", seen.none { it.display?.body == "first output" })
        }
    }

    @Test
    fun actionTitlesAreSynchronousAndStayVisibleAcrossStreamingArgumentsAndEveryStageWhileDisplayWorkWaits() = runTest(dispatcher) {
        val original = ToolPreviewTarget("call", "writeFile",
            """{"tool_title":"Write public file","path":"/work/a.kt","content":"first"}""", "",
            ToolPreviewStage.PREPARING, sourceSessionId = "session")
        var target by mutableStateOf(original)
        var gate: CompletableDeferred<Unit>? = null
        val worker = StandardTestDispatcher(testScheduler, name = "display-worker")
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, true, worker, buildDisplay = { next, safety ->
                    assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                    gate?.await()
                    buildToolPreviewDisplay(next, safety)
                })
                SideEffect { seen += presentation }
            }
            assertEquals("Write public file", seen.first().actionTitle)
            assertNull(seen.first().display)
            assertEquals("first", seen.last().display!!.diff.added)
            for (next in listOf(
                original.copy(arguments = """{"tool_title":"Write public file","path":"/work/a.kt","content":"first and second"""),
                original.copy(arguments = """{"tool_title":"Write public file","path":"/work/a.kt","content":"first and second"}"""),
                original.copy(stage = ToolPreviewStage.RUNNING),
                original.copy(stage = ToolPreviewStage.COMPLETED, content = "written", actionTitle = "Saved public file"),
            )) {
                val previous = seen.last().display
                gate = CompletableDeferred()
                seen.clear()
                target = next
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue("an in-flight body must not erase the current safe title", seen.all { it.actionTitle == safePreviewActionTitle(next) })
                assertTrue("only the same call's body may bridge its worker", seen.all { it.display === previous })
                gate!!.complete(Unit)
                runtime.flush()
                assertTrue(seen.all { it.actionTitle == safePreviewActionTitle(next) && it.display != null })
                assertEquals(buildToolPreviewDisplay(next), seen.last().display)
            }
        }
    }

    @Test
    fun synchronousTitlesNeverBorrowAcrossOwnersTabsOrHiddenBodiesEvenWhenDisplayWorkIsPaused() = runTest(dispatcher) {
        val original = browserTarget().copy(content = "old public output", actionTitle = "Old public action")
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, dispatcher)
                SideEffect { seen += presentation }
            }
            for (workEnabled in listOf(true, false)) for (next in listOf(
                original.copy(id = "next", actionTitle = null, content = "current output"),
                original.copy(sourceSessionId = "next session", actionTitle = null, content = "current output"),
                original.copy(name = "Root", actionTitle = null, content = "current output"),
                original.copy(arguments = """{"action":"click","tabId":"tab-2"}""", actionTitle = null, content = "current output"),
                original.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}"""),
            )) {
                target = original
                enabled = true
                runtime.flush()
                assertEquals(original.actionTitle, seen.last().actionTitle)
                enabled = workEnabled
                runtime.flush()
                seen.clear()
                target = next
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertNull("routing and body-hidden boundaries must retire the old frame immediately", seen.first().display)
                assertTrue(seen.all { it.actionTitle == null })
                assertTrue(seen.none { it.display?.copyText?.contains("old public output") == true })
            }
        }
    }

    @Test
    fun titleOnlySafetyAndTaskActionsAreEvaluatedBeforeAPausedDisplayWorker() = runTest(dispatcher) {
        var target by mutableStateOf(ToolPreviewTarget("call", "Bash", """{"command":"pwd"}""", "public output",
            ToolPreviewStage.RUNNING, actionTitle = "Public action", sourceSessionId = "session"))
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, dispatcher)
                SideEffect { seen += presentation }
            }
            val first = seen.last().display
            enabled = false
            runtime.flush()
            seen.clear()
            target = target.copy(arguments = """{"command":"pwd","headers":{"Authorization":"private"}}""")
            assertFalse(ToolPreviewSafety(target.name, target.arguments).bodyHidden)
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.actionTitle == null && it.display === first })
            for (action in listOf("create", "send", "read")) {
                seen.clear()
                target = target.copy(name = "task", arguments = """{"action":"$action"}""", actionTitle = null)
                runtime.flush()
                val expected = taskOperationActionResource(taskOperationAction(target.arguments))
                assertTrue(seen.isNotEmpty())
                assertTrue("task labels must not wait for body publication", seen.all { it.actionResource == expected && it.actionTitle == null })
            }
        }
    }

    @Test
    fun disabledDisplayWorkRetainsItsFrameAndResumesWithTheLatestInput() = runTest(dispatcher) {
        var target by mutableStateOf(ToolPreviewTarget("call", "Bash", """{"command":"pwd"}""",
            "first output", ToolPreviewStage.RUNNING, sourceSessionId = "session"))
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewDisplay?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val display = rememberToolPreviewDisplay(target, enabled, dispatcher)
                SideEffect { seen += display }
            }
            val first = seen.last()
            assertNotNull(first)
            enabled = false
            runtime.flush()
            target = target.copy(arguments = """{"command":"ls"}""", content = "latest output")
            runtime.flush()
            assertSame(first, seen.last())
            seen.clear()
            enabled = true
            runtime.flush()
            assertTrue(seen.all { it != null })
            assertEquals(buildToolPreviewDisplay(target), seen.last())
        }
    }

    @Test
    fun pausedStreamingInputsDoNotDispatchOrBuildBodiesAndResumeOnlyTheLatestSafeFrame() = runTest(dispatcher) {
        val worker = StandardTestDispatcher(testScheduler, name = "counted-preview-worker")
        var dispatches = 0
        val counted = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                worker.dispatch(context, block)
            }
        }
        val original = ToolPreviewTarget("call", "writeFile", """{"path":"/work/a.kt","content":"first source"}""",
            "first output", ToolPreviewStage.RUNNING, actionTitle = "First public action", sourceSessionId = "session")
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        val built = mutableListOf<ToolPreviewTarget>()
        val seen = mutableListOf<ToolPreviewPresentation>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, counted, buildDisplay = { next, safety ->
                    assertSame(counted, currentCoroutineContext()[ContinuationInterceptor])
                    built += next
                    buildToolPreviewDisplay(next, safety)
                })
                SideEffect { seen += presentation }
            }
            val first = requireNotNull(seen.last().display)
            assertEquals(listOf(original), built)
            enabled = false
            runtime.flush()
            val pausedDispatches = dispatches
            repeat(4) { index ->
                seen.clear()
                target = original.copy(arguments = """{"path":"/work/a.kt","content":"source-$index"}""",
                    content = "output-$index", actionTitle = "Public action $index",
                    stage = if (index == 3) ToolPreviewStage.COMPLETED else ToolPreviewStage.RUNNING)
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it.display === first && it.actionTitle == "Public action $index" })
                assertEquals("paused arguments must not enqueue background presentation work", pausedDispatches, dispatches)
                assertEquals(listOf(original), built)
            }
            val latest = target
            seen.clear()
            enabled = true
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.display != null })
            assertEquals(listOf(original, latest), built)
            assertEquals(buildToolPreviewDisplay(latest), seen.last().display)
            enabled = false
            runtime.flush()
            val beforeSensitive = dispatches
            seen.clear()
            target = latest.copy(arguments = """{"path":"/work/.env","content":"private source"}""",
                content = "private output", actionTitle = "Private action")
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue("safety must still retire body and title in the first paused composition", seen.all { it.display == null && it.actionTitle == null })
            assertEquals(beforeSensitive, dispatches)
            assertEquals(listOf(original, latest), built)
            val safeAfterPause = original.copy(arguments = """{"path":"/work/b.kt","content":"latest safe source"}""",
                content = "latest safe output", actionTitle = "Latest safe action")
            seen.clear()
            target = safeAfterPause
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.display == null && it.actionTitle == safeAfterPause.actionTitle })
            assertEquals(beforeSensitive, dispatches)
            enabled = true
            runtime.flush()
            assertEquals(listOf(original, latest, safeAfterPause), built)
            assertEquals(buildToolPreviewDisplay(safeAfterPause), seen.last().display)
        }
    }

    @Test
    fun pausingRejectsLateBodyWorkersForSameCallSensitiveInputsAndNewOwnersBeforeResuming() = runTest(dispatcher) {
        val original = ToolPreviewTarget("call", "writeFile", """{"path":"/work/a.kt","content":"first source"}""",
            "first output", ToolPreviewStage.RUNNING, actionTitle = "Public action", sourceSessionId = "session")
        for (replacement in listOf(
            original.copy(content = "latest same-call output"),
            original.copy(arguments = """{"path":"/work/.env","content":"private source"}""", content = "private output", actionTitle = "Private action"),
            original.copy(id = "next call", sourceSessionId = "next session", content = "next output", actionTitle = "Next public action"),
        )) {
            val release = CompletableDeferred<Unit>()
            var target by mutableStateOf(original)
            var enabled by mutableStateOf(true)
            val built = mutableListOf<ToolPreviewTarget>()
            val seen = mutableListOf<ToolPreviewPresentation>()
            try {
                Runtime(this).use { runtime ->
                    runtime.setContent {
                        val presentation = rememberToolPreviewPresentation(target, enabled, dispatcher, buildDisplay = { next, safety ->
                            built += next
                            if (next.content == "late output") withContext(NonCancellable) { release.await() }
                            buildToolPreviewDisplay(next, safety)
                        })
                        SideEffect { seen += presentation }
                    }
                    val first = requireNotNull(seen.last().display)
                    target = original.copy(content = "late output")
                    runtime.flush()
                    assertEquals("late output", built.last().content)
                    enabled = false
                    runtime.flush()
                    seen.clear()
                    target = replacement
                    runtime.flush()
                    assertTrue(seen.isNotEmpty())
                    val sameSafeOwner = replacement.id == original.id && replacement.sourceSessionId == original.sourceSessionId && !hidesPreviewBody(replacement)
                    val retained = if (sameSafeOwner) first else null
                    assertTrue(seen.all { it.display === retained })
                    assertTrue(seen.all { it.actionTitle == safePreviewActionTitle(replacement) })
                    release.complete(Unit)
                    runtime.flush()
                    assertEquals("paused replacements must not launch new body work", 2, built.size)
                    assertTrue(seen.all { it.display === retained })
                    assertTrue(seen.none { it.display?.copyText?.contains("late output") == true })
                    enabled = true
                    runtime.flush()
                    assertEquals(3, built.size)
                    assertEquals(replacement, built.last())
                    assertEquals(buildToolPreviewDisplay(replacement), seen.last().display)
                    assertEquals(safePreviewActionTitle(replacement), seen.last().actionTitle)
                    assertTrue(seen.none { it.display?.copyText?.contains("late output") == true })
                }
            } finally { release.complete(Unit) }
        }
    }

    @Test
    fun differentCallsSessionsAndToolsNeverBorrowThePreviousDisplay() = runTest(dispatcher) {
        var target by mutableStateOf(ToolPreviewTarget("call", "Bash", "{}", "first",
            ToolPreviewStage.COMPLETED, sourceSessionId = "session"))
        val seen = mutableListOf<ToolPreviewDisplay?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val display = rememberToolPreviewDisplay(target, true, dispatcher)
                SideEffect { seen += display }
            }
            assertNotNull(seen.last())
            for (next in listOf(
                target.copy(id = "next"),
                target.copy(id = "next", sourceSessionId = "other"),
                target.copy(id = "next", sourceSessionId = "other", name = "Root"),
            )) {
                seen.clear()
                target = next
                runtime.flush()
                assertNull("a new owner must begin without the previous owner's frame", seen.first())
                assertEquals(buildToolPreviewDisplay(next), seen.last())
            }
        }
    }

    @Test
    fun sensitiveArgumentUpdatesImmediatelyRetireThePreviousDisplay() = runTest(dispatcher) {
        val original = browserTarget().copy(content = "previous public output")
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewDisplay?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val display = rememberToolPreviewDisplay(target, enabled, dispatcher)
                SideEffect { seen += display }
            }
            for (workEnabled in listOf(true, false)) {
                target = original
                enabled = true
                runtime.flush()
                assertFalse(requireNotNull(seen.last()).bodyHidden)
                enabled = workEnabled
                runtime.flush()
                seen.clear()
                target = target.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}""")
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it == null || it.bodyHidden })
                assertTrue(seen.none { it?.copyText?.contains("previous public output") == true })
            }
        }
    }

    @Test
    fun fileSensitivityAndInvalidStructureRetireDisplayDetailsCopyAndTitleInTheirFirstCompositionEvenWhenPaused() = runTest(dispatcher) {
        val original = ToolPreviewTarget("call", "writeFile", """{"path":"/work/a.kt","content":"old visible source"}""",
            "old visible result", ToolPreviewStage.RUNNING, actionTitle = "Old visible title", sourceSessionId = "session")
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewPresentation>()
        val details = mutableListOf<String>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, dispatcher)
                val detail = presentation.display?.let { toolDetailBodyOf(it, "not executed", "hidden", "empty") }.orEmpty()
                SideEffect { seen += presentation; details += detail }
            }
            for (workEnabled in listOf(true, false)) for (stage in listOf(ToolPreviewStage.PREPARING, ToolPreviewStage.RUNNING)) {
                for (args in listOf(
                    """{"path":"/work/.env","content":"private new body"}""",
                    """{"path":"/work/a.kt","path":"/work/b.kt","content":"private new body"}""",
                    """{"path":"/work/a.kt","content":"bad\q"}""",
                )) {
                    target = original.copy(stage = stage)
                    enabled = true
                    runtime.flush()
                    assertNotNull(seen.last().display)
                    assertEquals("Old visible title", seen.last().actionTitle)
                    enabled = workEnabled
                    runtime.flush()
                    seen.clear(); details.clear()
                    target = target.copy(arguments = args)
                    runtime.flush()
                    assertTrue(seen.isNotEmpty())
                    assertNull("safety must retire the old frame before background work", seen.first().display)
                    assertTrue(seen.all { it.display == null || it.display.bodyHidden })
                    assertTrue(seen.none { it.actionTitle != null })
                    assertTrue(seen.none { it.display?.copyText?.contains("old visible") == true })
                    assertTrue(details.none { it.contains("old visible") || it.contains("private new body") })
                }
            }
        }
    }

    @Test
    fun lateDisplayWorkCannotPublishAcrossSensitiveInputOrSessionBoundaries() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var entered = false
        val original = browserTarget().copy(content = "first visible output", actionTitle = "Public action")
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<ToolPreviewPresentation>()
        val worker = StandardTestDispatcher(testScheduler, name = "preview-worker")
        Runtime(this).use { runtime ->
            runtime.setContent {
                val presentation = rememberToolPreviewPresentation(target, enabled, worker, buildDisplay = { next, safety ->
                    assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                    if (next.content == "late old output") {
                        entered = true
                        withContext(NonCancellable) { release.await() }
                    }
                    buildToolPreviewDisplay(next, safety)
                })
                SideEffect { seen += presentation }
            }
            assertEquals("first visible output", seen.last().display!!.result)
            target = original.copy(content = "late old output")
            runtime.flush()
            assertTrue(entered)
            seen.clear()
            target = target.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}""")
            enabled = false
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.display == null && it.actionTitle == null })
            release.complete(Unit)
            runtime.flush()
            assertTrue(seen.all { it.display == null && it.actionTitle == null })
            enabled = true
            target = original.copy(sourceSessionId = "next session", content = "new session output")
            runtime.flush()
            assertEquals("new session output", seen.last().display!!.result)
            assertTrue(seen.none { it.display?.copyText?.contains("late old output") == true })
        }
    }

    @Test
    fun sameOwnerContentFramesReuseArgumentSafetyAndBrowserTabsStillIsolateDisplays() = runTest(dispatcher) {
        var target by mutableStateOf(browserTarget().copy(content = "first"))
        val checks = mutableListOf<ToolPreviewSafety>()
        val seen = mutableListOf<ToolPreviewDisplay?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val safety = rememberToolPreviewSafety(target)
                val presentation = rememberToolPreviewPresentation(target, true, dispatcher, safety)
                SideEffect { checks += safety; seen += presentation.display }
            }
            val firstCheck = checks.last()
            repeat(3) { index ->
                seen.clear()
                target = target.copy(content = "output-$index")
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it != null })
                assertTrue(checks.all { it === firstCheck })
                assertEquals("output-$index", seen.last()!!.result)
            }
            seen.clear()
            target = target.copy(arguments = """{"action":"click","tabId":"tab-2"}""")
            runtime.flush()
            assertNull(seen.first())
            assertNotSame(firstCheck, checks.last())
        }
    }

    @Test
    fun backgroundHistoryKeepsCompleteNavigationAndManualSelectionUntilTheNextVersionIsReady() = runTest(dispatcher) {
        val initial = (0..2_000).map { index ->
            AgentUIMessage("call-$index", MessageRole.TOOL, "result-$index", timestamp = index.toLong(), toolName = "Bash", toolArgs = "{}")
        }
        var messages by mutableStateOf(initial)
        val release = CompletableDeferred<Unit>()
        val state = ToolPreviewHostState("session").apply { select("call-0") }
        val seen = mutableListOf<ToolPreviewHistory>()
        var entered = false
        val worker = StandardTestDispatcher(testScheduler, name = "history-worker")
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory("session", messages, worker) { sid, rows ->
                    assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                    if (rows.size > initial.size) { entered = true; release.await() }
                    buildToolPreviewHistory(sid, rows)
                }
                SideEffect { seen += history }
            }
            val complete = seen.last()
            assertEquals(initial.size, complete.targets.size)
            assertEquals("call-0", complete.targets[previewSelectedIndex(complete.targets, state.selectedMessageId, state.followingLatest)].id)
            seen.clear()
            messages = initial + AgentUIMessage("new", MessageRole.TOOL, "new result", timestamp = 5_000, toolName = "Bash", toolArgs = "{}")
            runtime.flush()
            assertTrue(entered)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it === complete })
            release.complete(Unit)
            runtime.flush()
            assertTrue(seen.all { it.targets.isNotEmpty() })
            assertEquals(initial.size + 1, seen.last().targets.size)
            assertEquals("call-0", seen.last().targets[previewSelectedIndex(seen.last().targets, state.selectedMessageId, state.followingLatest)].id)
        }
    }

    @Test
    fun preparingToHistoryHandoffKeepsTheWholeNavigationAndItsSelectedDisplayWhileHistoryBuilds() = runTest(dispatcher) {
        val existing = AgentUIMessage("older", MessageRole.TOOL, "older result", timestamp = 1, toolName = "Bash", toolArgs = "{}")
        val draft = PreparingToolPreviewSnapshot("call", "writeFile", """{"path":"/work/a.kt","content":"new source"}""")
        val finished = AgentUIMessage("call", MessageRole.TOOL, "written", timestamp = 2, toolName = draft.name, toolArgs = draft.accumulated)
        var messages by mutableStateOf(listOf(existing))
        var preparing by mutableStateOf(listOf(draft))
        var running by mutableStateOf(emptyList<RunningToolOutput>())
        val release = CompletableDeferred<Unit>()
        val state = ToolPreviewHostState("session").apply { select("call") }
        val seen = mutableListOf<Pair<List<ToolPreviewTarget>, ToolPreviewDisplay?>>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory("session", messages, dispatcher) { sid, rows ->
                    if (rows.size > 1) release.await()
                    buildToolPreviewHistory(sid, rows)
                }
                val targets = rememberToolPreviewTargets(history, messages, running, preparing)
                val index = previewSelectedIndex(targets, state.selectedMessageId, state.followingLatest)
                val target = targets.getOrNull(index)?.let { currentToolPreviewTarget(it, messages, running, preparing, "session") }
                val display = target?.let { rememberToolPreviewDisplay(it, true, dispatcher) }
                SideEffect { seen += targets to display }
            }
            assertEquals(listOf("older", "call"), seen.last().first.map { it.id })
            assertEquals("new source", seen.last().second!!.diff.added)
            seen.clear()
            running = listOf(RunningToolOutput("call", "", draft.name, draft.accumulated))
            preparing = emptyList()
            runtime.flush()
            assertTrue(seen.all { it.first.map { target -> target.id } == listOf("older", "call") && it.second != null })
            seen.clear()
            messages = listOf(existing, finished)
            running = emptyList()
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.first.map { target -> target.id } == listOf("older", "call") && it.second != null })
            release.complete(Unit)
            runtime.flush()
            assertEquals("written", seen.last().second!!.result)
            assertEquals(listOf("older", "call"), seen.last().first.map { it.id })
            assertFalse(state.followingLatest)
        }
    }

    @Test
    fun newlyPersistedCallsCannotDisappearOrFallBackToAnOlderCallWhileHistoryBuilds() = runTest(dispatcher) {
        val older = AgentUIMessage("older", MessageRole.TOOL, "older output", timestamp = 1, toolName = "Bash", toolArgs = "{}")
        val pending = AgentUIMessage("call", MessageRole.TOOL, SessionUseCase.PENDING_TOOL_MARKER, timestamp = 2,
            toolName = "Bash", toolArgs = """{"command":"pwd","tool_title":"Inspect working directory"}""")
        for (initial in listOf(emptyList(), listOf(older))) {
            var messages by mutableStateOf(initial)
            var running by mutableStateOf(emptyList<RunningToolOutput>())
            val release = CompletableDeferred<Unit>()
            var entered = false
            val seen = mutableListOf<Pair<List<ToolPreviewTarget>, ToolPreviewPresentation?>>()
            Runtime(this).use { runtime ->
                runtime.setContent {
                    val history = rememberToolPreviewHistory("session", messages, dispatcher) { sid, rows ->
                        if (rows.any { it.id == pending.id }) { entered = true; release.await() }
                        buildToolPreviewHistory(sid, rows)
                    }
                    val targets = rememberToolPreviewTargets(history, messages, running, emptyList())
                    val index = previewSelectedIndex(targets, null, true)
                    val target = targets.getOrNull(index)?.let { currentToolPreviewTarget(it, messages, running, emptyList(), "session") }
                    val presentation = target?.let { rememberToolPreviewPresentation(it, true, dispatcher) }
                    SideEffect { seen += targets to presentation }
                }
                seen.clear()
                messages = initial + pending
                runtime.flush()
                assertTrue(entered)
                assertTrue(seen.isNotEmpty())
                assertTrue("new current messages cannot wait for their background history entry", seen.all { it.first.lastOrNull()?.id == "call" })
                assertTrue(seen.all { it.second?.actionTitle == "Inspect working directory" })
                running = listOf(RunningToolOutput("call", "live output", "Bash", pending.toolArgs.orEmpty()))
                runtime.flush()
                assertEquals("live output", seen.last().second!!.display!!.result)
                seen.clear()
                messages = initial + pending.copy(content = "finished output", toolActionTitle = "Inspect working directory")
                running = emptyList()
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it.first.lastOrNull()?.let { last -> last.id == "call" && last.stage == ToolPreviewStage.COMPLETED } == true })
                assertTrue("the running-to-message relay must not recreate an empty presentation", seen.all {
                    it.second?.display != null && it.second?.actionTitle == "Inspect working directory"
                })
                release.complete(Unit)
                runtime.flush()
                assertEquals("finished output", seen.last().second!!.display!!.result)
            }
        }
    }

    @Test
    fun pendingHistoryNavigationImmediatelyDropsDeletedCallsAndRechecksSensitiveAndSessionInputs() = runTest(dispatcher) {
        val older = AgentUIMessage("older", MessageRole.TOOL, "older output", timestamp = 1, toolName = "Bash", toolArgs = "{}")
        val original = AgentUIMessage("call", MessageRole.TOOL, "old public output", timestamp = 2, toolName = "writeFile",
            toolArgs = """{"path":"/work/a.kt","content":"old public source"}""", toolActionTitle = "Old public action")
        val initial = listOf(older, original)
        var messages by mutableStateOf(initial)
        var session by mutableStateOf("session")
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<Pair<List<ToolPreviewTarget>, ToolPreviewPresentation?>>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory(session, messages, dispatcher) { sid, rows ->
                    if (rows !== initial) release.await()
                    buildToolPreviewHistory(sid, rows)
                }
                val targets = rememberToolPreviewTargets(history, messages, emptyList(), emptyList())
                val index = previewSelectedIndex(targets, "call", false)
                val target = targets.getOrNull(index)?.let { currentToolPreviewTarget(it, messages, emptyList(), emptyList(), session) }
                val presentation = target?.let { rememberToolPreviewPresentation(it, true, dispatcher) }
                SideEffect { seen += targets to presentation }
            }
            assertEquals("Old public action", seen.last().second!!.actionTitle)
            seen.clear()
            messages = listOf(older)
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue("a deleted call must not survive in a retained page", seen.all { pair -> pair.first.map { it.id } == listOf("older") })
            assertTrue(seen.none { it.second?.actionTitle == "Old public action" || it.second?.display?.copyText?.contains("old public") == true })
            seen.clear()
            messages = listOf(older, original.copy(toolArgs = """{"path":"/work/.env","content":"private source"}"""))
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.second?.actionTitle == null && it.second?.display?.bodyHidden != false })
            seen.clear()
            session = "next session"
            messages = listOf(original.copy(id = "next call", toolActionTitle = "Next public action", content = "next output"))
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { pair -> pair.first.all { it.id == "next call" && it.sourceSessionId == "next session" } })
            assertTrue(seen.none { it.second?.actionTitle == "Old public action" || it.second?.display?.copyText?.contains("old public") == true })
            release.complete(Unit)
            runtime.flush()
            assertEquals("Next public action", seen.last().second!!.actionTitle)
        }
    }

    @Test
    fun pausedHistoryDoesNoWorkAndResumesWithItsLastCompleteNavigation() = runTest(dispatcher) {
        val initial = listOf(AgentUIMessage("first", MessageRole.TOOL, "done", toolName = "Bash", toolArgs = "{}"))
        var messages by mutableStateOf(initial)
        var enabled by mutableStateOf(true)
        var builds = 0
        val seen = mutableListOf<ToolPreviewHistory>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory("session", messages, dispatcher, enabled) { sid, rows ->
                    builds++
                    buildToolPreviewHistory(sid, rows)
                }
                SideEffect { seen += history }
            }
            val first = seen.last()
            assertEquals(1, builds)
            enabled = false
            runtime.flush()
            messages = initial + initial.single().copy(id = "next")
            runtime.flush()
            assertEquals(1, builds)
            assertSame(first, seen.last())
            seen.clear()
            enabled = true
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.targets.isNotEmpty() })
            assertEquals(2, builds)
            assertEquals(2, seen.last().targets.size)
        }
    }

    @Test
    fun retainedNavigationCannotDelayTheSelectedCallsSynchronousSensitiveFence() = runTest(dispatcher) {
        val original = AgentUIMessage("call", MessageRole.TOOL, "old visible result", toolName = "writeFile",
            toolArgs = """{"path":"/work/a.kt","content":"old visible source"}""")
        var messages by mutableStateOf(listOf(original))
        val release = CompletableDeferred<Unit>()
        var entered = false
        var enabled by mutableStateOf(true)
        val seen = mutableListOf<Pair<ToolPreviewHistory, ToolPreviewDisplay?>>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory("session", messages, dispatcher) { sid, rows ->
                    if (rows.single().toolArgs != original.toolArgs) { entered = true; release.await() }
                    buildToolPreviewHistory(sid, rows)
                }
                val selected = history.targets.singleOrNull()?.let {
                    currentToolPreviewTarget(it, messages, emptyList(), emptyList(), "session")
                }
                val display = selected?.let { rememberToolPreviewDisplay(it, enabled, dispatcher) }
                SideEffect { seen += history to display }
            }
            val complete = seen.last().first
            assertEquals("old visible result", seen.last().second!!.result)
            enabled = false
            runtime.flush()
            seen.clear()
            messages = listOf(original.copy(toolArgs = """{"path":"/work/.env","content":"private source"}"""))
            runtime.flush()
            assertTrue(entered)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.first === complete && it.second == null })
            release.complete(Unit)
            runtime.flush()
            assertTrue(seen.all { it.second == null })
            enabled = true
            runtime.flush()
            assertTrue(seen.last().second!!.bodyHidden)
            assertTrue(seen.none { it.second?.copyText?.contains("old visible") == true })
        }
    }

    @Test
    fun anObsoleteHistoryVersionCannotReplaceTheLastCompleteFrameBeforeTheLatestVersion() = runTest(dispatcher) {
        val original = AgentUIMessage("call", MessageRole.TOOL, "first", toolName = "Bash", toolArgs = "{}")
        var messages by mutableStateOf(listOf(original))
        val release = CompletableDeferred<Unit>()
        var entered = false
        val seen = mutableListOf<ToolPreviewHistory>()
        try {
            Runtime(this).use { runtime ->
                runtime.setContent {
                    val history = rememberToolPreviewHistory("session", messages, dispatcher) { sid, rows ->
                        if (rows.single().content == "obsolete") {
                            entered = true
                            withContext(NonCancellable) { release.await() }
                        }
                        buildToolPreviewHistory(sid, rows)
                    }
                    SideEffect { seen += history }
                }
                val first = seen.last()
                messages = listOf(original.copy(content = "obsolete"))
                runtime.flush()
                assertTrue(entered)
                seen.clear()
                messages = listOf(original.copy(content = "latest"))
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === first })
                release.complete(Unit)
                runtime.flush()
                assertEquals("latest", seen.last().targets.single().content)
                assertTrue(seen.flatMap { it.targets }.none { it.content == "obsolete" })
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun lateHistoryResultsNeverCrossSessionsAndNewInputVersionsSupersedeOldWork() = runTest(dispatcher) {
        val old = AgentUIMessage("old", MessageRole.TOOL, "old result", toolName = "Bash", toolArgs = "{}")
        val latest = old.copy(id = "latest", content = "latest result")
        var session by mutableStateOf("session")
        var messages by mutableStateOf(listOf(old))
        val release = CompletableDeferred<Unit>()
        var blockOld = false
        var entered = false
        val seen = mutableListOf<ToolPreviewHistory>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val history = rememberToolPreviewHistory(session, messages, dispatcher) { sid, rows ->
                    if (blockOld && sid == "session" && rows.firstOrNull()?.id == "old") {
                        entered = true
                        withContext(NonCancellable) { release.await() }
                    }
                    buildToolPreviewHistory(sid, rows)
                }
                SideEffect { seen += history }
            }
            assertEquals("old", seen.last().targets.single().id)
            blockOld = true
            messages = listOf(old.copy(content = "blocked old result"))
            runtime.flush()
            assertTrue(entered)
            seen.clear()
            session = "next"
            messages = listOf(latest)
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertEquals("next", seen.first().sessionId)
            assertTrue(seen.first().targets.isEmpty())
            assertEquals("latest", seen.last().targets.single().id)
            release.complete(Unit)
            runtime.flush()
            assertTrue(seen.all { it.sessionId == "next" })
            assertTrue(seen.flatMap { it.targets }.all { it.id == "latest" && it.sourceSessionId == "next" })
        }
    }

    @Test
    fun browserCompletionAndDetailSamplingStartWithTheCachedFrameWithoutAnEmptyComposition() = runTest(dispatcher) {
        var target by mutableStateOf(browserTarget())
        var edge by mutableStateOf(TOOL_PREVIEW_MAX_EDGE_PX)
        var enabled by mutableStateOf(true)
        val cache = ToolPreviewBitmapCache()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        cache.put(requireNotNull(previewFrameKey(target, TOOL_PREVIEW_MAX_EDGE_PX)), frame)
        val seen = mutableListOf<Bitmap?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val bitmap = rememberToolPreviewBitmap(target, null, cache, enabled, maxEdge = edge)
                SideEffect { seen += bitmap }
            }
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it === frame })
            seen.clear()
            target = target.copy(stage = ToolPreviewStage.COMPLETED, content = "done")
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue("running-to-completed handoff must keep the last browser frame", seen.all { it === frame })
            seen.clear()
            edge = TOOL_DETAIL_MAX_EDGE_PX
            runtime.flush()
            assertTrue(seen.isNotEmpty())
            assertTrue("detail opening must use this call's thumbnail until its own image is ready", seen.all { it === frame })
            enabled = false
            runtime.flush()
            assertNull(seen.last())
            enabled = true
            runtime.flush()
            assertSame(frame, seen.last())
        }
        cache.clear()
        assertFalse(frame.isRecycled)
        frame.recycle()
    }

    @Test
    fun pausedBitmapsRetainVisibleFramesWithoutCaptureOrLocalReadsAndStillRespectVisibilityAndOwnerFences() = runTest(dispatcher, timeout = 20.seconds) {
        val original = browserTarget()
        var target by mutableStateOf(original)
        var paused by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var visible by mutableStateOf(true)
        val owner = Owner()
        val cache = ToolPreviewBitmapCache()
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val resumed = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val manager = mockk<BrowserManager>()
        var captures = 0
        coEvery { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) } coAnswers {
            captures++
            if (captures == 1) first else resumed
        }
        val seen = mutableListOf<Bitmap?>()
        try {
            Runtime(this, owner).use { runtime ->
                runtime.setContent {
                    ToolPreviewWorkVisibility(visible) {
                        val bitmap = rememberToolPreviewBitmap(target, manager, cache, enabled, samplingPaused = paused)
                        SideEffect { seen += bitmap }
                    }
                }
                assertSame(first, seen.last())
                coVerify(exactly = 1) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                paused = true
                runtime.flush()
                for (next in listOf(
                    original.copy(arguments = """{"action":"click","tabId":"tab-1","selector":"#public"}""", content = "live output"),
                    original.copy(stage = ToolPreviewStage.COMPLETED, content = "finished output"),
                    original.copy(stage = ToolPreviewStage.COMPLETED, content = "newer finished output"),
                )) {
                    seen.clear()
                    target = next
                    runtime.flush()
                    advanceTimeBy(2_000)
                    runtime.flush()
                    assertTrue(seen.isNotEmpty())
                    assertTrue("touching may pause work, not erase this call's visible image", seen.all { it === first })
                    coVerify(exactly = 1) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                }
                target = original.copy(content = "resumed live output")
                runtime.flush()
                seen.clear()
                paused = false
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === first || it === resumed })
                assertSame(resumed, seen.last())
                coVerify(exactly = 2) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                advanceTimeBy(1_000)
                runtime.flush()
                coVerify(exactly = 3) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                paused = true
                runtime.flush()
                enabled = false
                runtime.flush()
                assertNull(seen.last())
                advanceTimeBy(2_000)
                runtime.flush()
                enabled = true
                runtime.flush()
                assertSame(resumed, seen.last())
                visible = false
                runtime.flush()
                assertNull(seen.last())
                advanceTimeBy(2_000)
                runtime.flush()
                visible = true
                runtime.flush()
                assertSame(resumed, seen.last())
                owner.registry.currentState = Lifecycle.State.CREATED
                paused = false
                runtime.flush()
                assertNull(seen.last())
                advanceTimeBy(2_000)
                runtime.flush()
                assertNull(seen.last())
                coVerify(exactly = 3) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                paused = true
                owner.registry.currentState = Lifecycle.State.STARTED
                runtime.flush()
                assertSame(resumed, seen.last())
                for (next in listOf(
                    original.copy(id = "another call"),
                    original.copy(sourceSessionId = "another session"),
                    original.copy(arguments = """{"action":"click","tabId":"tab-2"}"""),
                    original.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}"""),
                )) {
                    target = original
                    runtime.flush()
                    assertSame(resumed, seen.last())
                    seen.clear()
                    target = next
                    runtime.flush()
                    advanceTimeBy(2_000)
                    runtime.flush()
                    assertTrue(seen.isNotEmpty())
                    assertTrue("paused images still belong to one current public call/session/tab", seen.all { it == null })
                    coVerify(exactly = 3) { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) }
                }
            }
        } finally {
            cache.clear()
            if (!first.isRecycled) first.recycle()
            if (!resumed.isRecycled) resumed.recycle()
        }
        val encoded = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val imageBytes = try {
            ByteArrayOutputStream().use { output ->
                assertTrue(encoded.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { encoded.recycle() }
        val reads = AtomicInteger()
        val bytes = AtomicReference(imageBytes)
        val path = "/work/public-preview.png"
        val read: (String) -> ByteArray? = { requested ->
            assertEquals(path, requested)
            reads.incrementAndGet()
            bytes.get()
        }
        target = ToolPreviewTarget("image call", "viewImage", """{"images":["/work/public-preview.png"]}""", "first image result",
            ToolPreviewStage.COMPLETED, imagePath = path, sourceSessionId = "session")
        paused = false
        enabled = true
        visible = true
        seen.clear()
        val localCache = ToolPreviewBitmapCache()
        try {
            Runtime(this).use { runtime ->
                runtime.setContent {
                    val bitmap = rememberToolPreviewBitmap(target, null, localCache, true, readFileBytes = read, samplingPaused = paused)
                    SideEffect { seen += bitmap }
                }
                withContext(Dispatchers.Default) {
                    while (withContext(dispatcher) { runtime.flush(); seen.lastOrNull() == null }) yield()
                }
                val decoded = requireNotNull(seen.last())
                val initialReads = reads.get()
                assertTrue(initialReads > 0)
                paused = true
                runtime.flush()
                repeat(3) { index ->
                    seen.clear()
                    target = target.copy(content = "same-path image result-$index", arguments = """{"images":["/work/public-preview.png"],"detail":"revision-$index"}""")
                    runtime.flush()
                    assertTrue(seen.isNotEmpty())
                    assertTrue("same-path result versions must not clear a paused local image", seen.all { it === decoded })
                    assertEquals(initialReads, reads.get())
                }
                bytes.set(imageBytes.copyOf())
                paused = false
                runtime.flush()
                withContext(Dispatchers.Default) {
                    while (withContext(dispatcher) { runtime.flush(); seen.lastOrNull() == null || seen.lastOrNull() === decoded }) yield()
                }
                assertTrue("resuming must read the latest result version", reads.get() > initialReads)
                assertNotSame(decoded, seen.last())
                assertFalse(decoded.isRecycled)
            }
        } finally {
            localCache.clear()
            seen.filterNotNull().distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test
    fun sensitiveChangesImmediatelyHideCachedBitmapsAtBothSampleSizesWhilePausedAndAfterResuming() = runTest(dispatcher) {
        val original = browserTarget()
        var target by mutableStateOf(original)
        var enabled by mutableStateOf(true)
        var edge by mutableStateOf(TOOL_PREVIEW_MAX_EDGE_PX)
        val cache = ToolPreviewBitmapCache()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        cache.put(requireNotNull(previewFrameKey(original, TOOL_PREVIEW_MAX_EDGE_PX)), frame)
        cache.put(requireNotNull(previewFrameKey(original, TOOL_DETAIL_MAX_EDGE_PX)), frame)
        val seen = mutableListOf<Bitmap?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val bitmap = rememberToolPreviewBitmap(target, null, cache, enabled, maxEdge = edge)
                SideEffect { seen += bitmap }
            }
            for (sampleSize in listOf(TOOL_PREVIEW_MAX_EDGE_PX, TOOL_DETAIL_MAX_EDGE_PX)) {
                target = original
                enabled = true
                edge = sampleSize
                runtime.flush()
                assertSame(frame, seen.last())
                enabled = false
                runtime.flush()
                seen.clear()
                target = target.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}""")
                runtime.flush()
                enabled = true
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it == null })
            }
        }
        cache.clear()
        frame.recycle()
    }

    @Test
    fun lateBrowserCaptureIsDiscardedAndNeverCachedAfterItsInputBecomesSensitive() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var entered = false
        val manager = mockk<BrowserManager>()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        coEvery { manager.captureThumbnail("tab-1", TOOL_PREVIEW_MAX_EDGE_PX) } coAnswers {
            entered = true
            withContext(NonCancellable) { release.await() }
            frame
        }
        val original = browserTarget()
        var target by mutableStateOf(original)
        val cache = ToolPreviewBitmapCache()
        val seen = mutableListOf<Bitmap?>()
        try {
            Runtime(this).use { runtime ->
                runtime.setContent {
                    val bitmap = rememberToolPreviewBitmap(target, manager, cache, true)
                    SideEffect { seen += bitmap }
                }
                assertTrue(entered)
                target = target.copy(arguments = """{"action":"fill","tabId":"tab-1","selector":"#password","value":"private"}""")
                runtime.flush()
                release.complete(Unit)
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it == null })
                assertNull(cache[requireNotNull(previewFrameKey(original, TOOL_PREVIEW_MAX_EDGE_PX))])
                assertTrue(frame.isRecycled)
            }
        } finally {
            release.complete(Unit)
            cache.clear()
            if (!frame.isRecycled) frame.recycle()
        }
    }

    @Test
    fun cachedBrowserFramesNeverCrossCallsSessionsTabsOrSensitiveInputs() = runTest(dispatcher) {
        val original = browserTarget()
        var target by mutableStateOf(original)
        val cache = ToolPreviewBitmapCache()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        cache.put(requireNotNull(previewFrameKey(original, TOOL_PREVIEW_MAX_EDGE_PX)), frame)
        val seen = mutableListOf<Bitmap?>()
        Runtime(this).use { runtime ->
            runtime.setContent {
                val bitmap = rememberToolPreviewBitmap(target, null, cache, true)
                SideEffect { seen += bitmap }
            }
            assertSame(frame, seen.last())
            for (next in listOf(
                original.copy(id = "another"),
                original.copy(sourceSessionId = "another"),
                original.copy(arguments = """{"action":"click","tabId":"tab-2"}"""),
                original.copy(arguments = """{"action":"fill","tabId":"tab-1","password":true,"value":"hidden"}"""),
                original.copy(stage = ToolPreviewStage.PREPARING),
            )) {
                seen.clear()
                target = next
                runtime.flush()
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it == null })
            }
        }
        cache.clear()
        assertFalse(frame.isRecycled)
        frame.recycle()
    }

    @Test
    fun uiWorkRestartsOnlyForItsKeysVisibilityAndLifecycleNotUpdatedBlockCaptures() = runTest(dispatcher) {
        val owner = Owner()
        var input by mutableStateOf("first")
        var visible by mutableStateOf(true)
        var effectKey by mutableStateOf("call")
        val started = mutableListOf<String>()
        var stopped = 0
        Runtime(this, owner).use { runtime ->
            runtime.setContent {
                val captured = input
                UiWorkEffect(effectKey, visible = visible) {
                    started += captured
                    try { awaitCancellation() } finally { stopped++ }
                }
            }
            assertEquals(listOf("first"), started)
            input = "updated"
            runtime.flush()
            assertEquals(listOf("first"), started)
            assertEquals(0, stopped)
            visible = false
            runtime.flush()
            assertEquals(1, stopped)
            visible = true
            runtime.flush()
            assertEquals(listOf("first", "updated"), started)
            owner.registry.currentState = Lifecycle.State.CREATED
            runtime.flush()
            assertEquals(2, stopped)
            input = "resumed"
            runtime.flush()
            owner.registry.currentState = Lifecycle.State.STARTED
            runtime.flush()
            assertEquals(listOf("first", "updated", "resumed"), started)
            effectKey = "next"
            runtime.flush()
            assertEquals(3, stopped)
            assertEquals(listOf("first", "updated", "resumed", "resumed"), started)
        }
        assertEquals(4, stopped)
    }

    private fun browserTarget() = ToolPreviewTarget("call", "browser",
        """{"action":"click","tabId":"tab-1"}""", "", ToolPreviewStage.RUNNING, sourceSessionId = "session")

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Applier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Runtime(private val scope: TestScope, private val owner: Owner = Owner()) : AutoCloseable {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val composition = Composition(Applier(), recomposer)
        private val runner = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var time = 0L

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content) }
            flush()
        }

        fun flush() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (recomposer.hasPendingWork && clock.hasAwaiters) {
                    time += 16_000_000L
                    clock.sendFrame(time)
                    scope.runCurrent()
                }
            }
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            scope.runCurrent()
        }
    }
}
