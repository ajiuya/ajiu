package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandKind
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.model.ReasoningEffort
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [28], qualifiers = "zh-rCN-w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
// REALISTIC 固定返回 35px 字体高度；LEGACY 让主题 lineHeight 驱动容器尺寸，不作字形验收。
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ChatComposerTextFieldTest {
    @Test(timeout = 30_000)
    fun emptyEditorMeasures40DpWithoutChangingItsWidthOrHorizontalInsets() {
        val value = mutableStateOf(TextFieldValue())
        render(content = { Composer(value) }) {
            val field = taggedBounds("composer")
            val editor = editor()
            assertEquals(400f, field.width, 0.01f)
            assertEquals(40f, field.height, 0.01f)
            val editorBounds = editor.layoutBounds()
            assertEquals(368f, editorBounds.width, 0.01f)
            assertEquals(field.left + 16f, editorBounds.left, 0.01f)
            assertEquals(field.right - 16f, editorBounds.right, 0.01f)
            assertEquals(field.top + 6f, editorBounds.top, 0.01f)
            assertEquals("", editor.text())
        }
    }

    @Test(timeout = 30_000)
    fun singleLineKeeps40DpAndTheThemeBodyLargeFont() {
        val value = mutableStateOf(TextFieldValue("Hello"))
        var expectedStyle = TextStyle.Default
        render(content = {
            expectedStyle = MaterialTheme.typography.bodyLarge
            Composer(value)
        }) {
            val field = taggedBounds("composer")
            val layout = editor().textLayout()
            assertEquals(40f, field.height, 0.01f)
            assertEquals(400f, field.width, 0.01f)
            assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(expectedStyle.lineHeight, layout.layoutInput.style.lineHeight)
            assertEquals(1, layout.lineCount)
            assertFalse(layout.didOverflowHeight)
            assertTrue(editor().boundsInRoot.bottom <= field.bottom - 6f)
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun multipleLinesGrowNaturallyUntilThe120DpViewportLimit() {
        val value = mutableStateOf(TextFieldValue("First\nSecond"))
        render(content = { Composer(value) }) {
            val twoLineHeight = taggedBounds("composer").height
            assertTrue(twoLineHeight > 40f)
            assertEquals(editor().textLayout().size.height + 12f, twoLineHeight, 0.01f)
            value.value = TextFieldValue("First\nSecond\nThird")
            awaitUi("Three-line height") { taggedBounds("composer").height > twoLineHeight }
            assertEquals(editor().textLayout().size.height + 12f, taggedBounds("composer").height, 0.01f)
            assertTrue(taggedBounds("composer").height < 120f)

            value.value = TextFieldValue(longDraft())
            awaitUi("Capped multiline viewport") { taggedBounds("composer").height == 120f }
            assertEquals(108f, editor().boundsInRoot.height, 0.01f)
            assertTrue(editor().textLayout().size.height > editor().boundsInRoot.height)
            assertEquals(longDraft(), editor().text())
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun enlargedFontGrowsTheMinimumWithoutShrinkingOrClippingTheLine() {
        val value = mutableStateOf(TextFieldValue("Large text"))
        var expectedStyle = TextStyle.Default
        render(fontScale = 3f, content = {
            expectedStyle = MaterialTheme.typography.bodyLarge
            Composer(value)
        }) {
            val field = taggedBounds("composer")
            val editor = editor()
            val layout = editor.textLayout()
            assertTrue(field.height > 40f)
            assertTrue(field.height <= 120f)
            assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(expectedStyle.lineHeight, layout.layoutInput.style.lineHeight)
            assertEquals(3f, layout.layoutInput.density.fontScale, 0f)
            assertFalse(layout.didOverflowHeight)
            assertTrue(editor.boundsInRoot.height >= layout.size.height)
            assertEquals(field.top + 6f, editor.boundsInRoot.top, 0.01f)
            assertTrue(editor.boundsInRoot.bottom <= field.bottom - 6f)
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun fractionalDensityUsesTheExactRoundedPaddingAndHeightConstraints() {
        val value = mutableStateOf(TextFieldValue("Hello"))
        render(widthPx = 413, density = 1.3f, content = { Composer(value) }) {
            val horizontalPadding = (16f * 1.3f).roundToInt()
            val verticalPadding = (6f * 1.3f).roundToInt()
            val field = taggedBounds("composer")
            assertEquals(413f, field.width, 0.01f)
            assertEquals((40f * 1.3f).roundToInt().toFloat(), field.height, 0.01f)
            assertEquals(413f - horizontalPadding * 2, editor().boundsInRoot.width, 0.01f)
            assertEquals(field.top + verticalPadding, editor().boundsInRoot.top, 0.01f)
            assertEquals(editor().boundsInRoot.width.roundToInt(), editor().textLayout().layoutInput.constraints.maxWidth)

            value.value = TextFieldValue(longDraft())
            awaitUi("Scaled maximum height") {
                taggedBounds("composer").height == (120f * 1.3f).roundToInt().toFloat()
            }
            assertEquals(413f, taggedBounds("composer").width, 0.01f)
            assertEquals((120f * 1.3f).roundToInt() - verticalPadding * 2f, editor().boundsInRoot.height, 0.01f)
        }
    }

    @Test(timeout = 30_000)
    fun placeholderSharesTheEditorTypographyAndTracksEmptyText() {
        val value = mutableStateOf(TextFieldValue())
        render(content = { Composer(value) }) {
            val placeholder = activity.getString(R.string.chat_input_placeholder)
            val placeholderNode = hostNodes().single { placeholder in it.texts() }
            val placeholderLayout = placeholderNode.textLayout()
            val editorLayout = editor().textLayout()
            assertEquals(editorLayout.layoutInput.style.fontSize, placeholderLayout.layoutInput.style.fontSize)
            assertEquals(editorLayout.layoutInput.style.lineHeight, placeholderLayout.layoutInput.style.lineHeight)
            assertEquals(editor().boundsInRoot.left, placeholderNode.boundsInRoot.left, 0.01f)
            assertEquals(editor().boundsInRoot.top, placeholderNode.boundsInRoot.top, 0.01f)

            setText("Edited")
            awaitUi("Placeholder hidden") { hostNodes().none { placeholder in it.texts() } }
            assertEquals("Edited", value.value.text)
            setText("")
            awaitUi("Placeholder restored") { hostNodes().any { placeholder in it.texts() } }
            assertEquals(40f, taggedBounds("composer").height, 0.01f)
        }
    }

    @Test(timeout = 30_000)
    fun parentStringRoundTripsKeepSelectionAndReplaceOnlyTheSelectedRange() {
        val value = mutableStateOf("abcdef")
        render(content = { InputBar(value) }) {
            focusEditor()
            select(1, 4)
            awaitUi("Selected text range") { editor().selection() == TextRange(1, 4) }
            assertEquals("abcdef", value.value)
            insert("X")
            awaitUi("Selected range replaced") { editor().text() == "aXef" }
            assertEquals("aXef", value.value)
            assertEquals(TextRange(2), editor().selection())
        }
    }

    @Test(timeout = 30_000)
    fun sendImeActionDispatchesOnceAndStillPreservesMultilineText() {
        val value = mutableStateOf(TextFieldValue("First\nSecond"))
        var sends = 0
        render(content = { Composer(value, enterToSend = true, onSend = { sends++ }) }) {
            focusEditor()
            assertEquals(ImeAction.Send, editor().config.getOrNull(SemanticsProperties.ImeAction))
            assertTrue(requireNotNull(editor().config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke())
            assertEquals(1, sends)
            assertEquals("First\nSecond", value.value.text)
            assertEquals(2, editor().textLayout().lineCount)
        }
    }

    @Test(timeout = 30_000)
    fun defaultImeActionAcceptsNewlinesWithoutDispatchingSend() {
        val value = mutableStateOf(TextFieldValue("First", TextRange(5)))
        var sends = 0
        render(content = { Composer(value, onSend = { sends++ }) }) {
            focusEditor()
            assertEquals(ImeAction.Default, editor().config.getOrNull(SemanticsProperties.ImeAction))
            insert("\nSecond")
            awaitUi("Default multiline edit and layout") {
                editor().text() == "First\nSecond" &&
                    editor().textLayout().layoutInput.text.text == "First\nSecond" &&
                    editor().textLayout().lineCount == 2
            }
            assertEquals("First\nSecond", value.value.text)
            assertEquals(2, editor().textLayout().lineCount)
            assertEquals(0, sends)
        }
    }

    @Test(timeout = 30_000)
    fun wholeInputBarKeepsItsCurrentWidthAndBothPlaceholderResources() {
        for (width in listOf(400, 280)) {
            val value = mutableStateOf("")
            val busy = mutableStateOf(false)
            render(widthPx = width, content = { InputBar(value, isBusy = busy.value) }) {
                assertEquals(width.toFloat(), taggedBounds("bar").width, 0.01f)
                assertEquals(40f, editor().layoutBounds().left, 0.01f)
                assertEquals(width - 80f, editor().layoutBounds().width, 0.01f)
                assertTrue(hostNodes().any { activity.getString(R.string.chat_input_placeholder) in it.texts() })
                busy.value = true
                awaitUi("Busy placeholder") {
                    hostNodes().any { activity.getString(R.string.chat_queue_hint) in it.texts() }
                }
                assertEquals(width.toFloat(), taggedBounds("bar").width, 0.01f)
                assertEquals(width - 80f, editor().layoutBounds().width, 0.01f)
            }
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun expandTracksTheMeasuredBodyLargeHeightAndDisappearsAfterShrinking() {
        val value = mutableStateOf("Line")
        render(content = { InputBar(value) }) {
            var exceededViewport = false
            for (lines in 1..20) {
                value.value = List(lines) { "Line" }.joinToString("\n")
                awaitUi("Measured $lines-line draft") { editor().text() == value.value }
                advanceFrames()
                val layout = editor().textLayout()
                assertEquals(layout.size.height > 108, hasExpand())
                if (layout.size.height > 108) {
                    exceededViewport = true
                    break
                }
            }
            assertTrue("The actual measured text must exceed the viewport", exceededViewport)
            assertEquals(108f, editor().boundsInRoot.height, 0.01f)
            assertEquals(400f, taggedBounds("bar").width, 0.01f)
            assertEquals(288f, editor().boundsInRoot.width, 0.01f)
            value.value = "Short"
            awaitUi("Expansion affordance removed") { !hasExpand() && editor().text() == "Short" }
            advanceFrames()
            assertEquals(320f, editor().boundsInRoot.width, 0.01f)
        }
    }

    @Test(timeout = 30_000)
    fun softWrappedTextUsesTheActualContentWidthAndExposesScrollingAndEditing() {
        val value = mutableStateOf("wrapped words ".repeat(150))
        var expectedStyle = TextStyle.Default
        render(content = {
            expectedStyle = MaterialTheme.typography.bodyLarge
            InputBar(value)
        }) {
            awaitUi("Soft-wrap overflow") { hasExpand() }
            val layout = editor().textLayout()
            assertFalse(value.value.contains('\n'))
            assertTrue(layout.lineCount > 1)
            assertTrue(layout.size.height > 108)
            assertEquals(editor().boundsInRoot.width.roundToInt(), layout.layoutInput.constraints.maxWidth)
            assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(expectedStyle.lineHeight, layout.layoutInput.style.lineHeight)
            val scroll = hostNodes().single { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
            assertTrue(requireNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 96f))
            advanceFrames()
            assertEquals(108f, editor().boundsInRoot.height, 0.01f)

            focusEditor()
            val original = value.value
            select(original.length, original.length)
            insert("tail")
            awaitUi("Overflowing draft stays editable") { editor().text() == original + "tail" }
            assertEquals(original + "tail", value.value)
            assertTrue(hasExpand())
        }
    }

    @Test(timeout = 30_000)
    fun slashCompletionMovesTheCursorToTheEndOfTheReplacement() {
        val value = mutableStateOf("/")
        val command = SlashCommand("review", "Inspect changes", SlashCommandKind.ACTION, acceptsArgs = true)
        render(content = { InputBar(value, slashCommands = listOf(command)) }) {
            focusEditor()
            select(0, 0)
            clickText("/review")
            awaitUi("Slash replacement and cursor") {
                editor().text() == "/review " && editor().selection() == TextRange(8)
            }
            assertEquals("/review ", value.value)
            insert("file")
            awaitUi("Typing after slash completion") { editor().text() == "/review file" }
            assertEquals("/review file", value.value)
        }
    }

    @Test(timeout = 30_000)
    fun fileMentionCompletionReplacesTheQueryAndKeepsTheCursorAtTheEnd() {
        val value = mutableStateOf("See @sr")
        render(content = { InputBar(value, mentionFiles = listOf("src/main.kt")) }) {
            clickText("src/main.kt")
            val expected = "See @src/main.kt "
            awaitUi("Mention replacement and cursor") {
                editor().text() == expected && editor().selection() == TextRange(expected.length)
            }
            assertEquals(expected, value.value)
            assertTrue(hostNodes().none { "src/main.kt" in it.texts() })
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun fullScreenAndCollapsedEditorsShareTheSameLiveDraftInBothDirections() {
        val value = mutableStateOf(longDraft())
        val fullScreenStates = mutableListOf<Boolean>()
        render(content = { InputBar(value, onFullScreenChanged = fullScreenStates::add) }) {
            awaitUi("Expandable draft") { hasExpand() }
            focusEditor()
            select(value.value.length, value.value.length)
            insert("\nCollapsed edit")
            awaitUi("Collapsed edit committed") { value.value.endsWith("Collapsed edit") }
            clickDescription(activity.getString(R.string.chat_input_expand))
            awaitUi("Full-screen editor") { dialogNodes().any { it.config.contains(SemanticsActions.SetText) } }
            assertEquals(value.value, editor(inDialog = true).text())
            assertTrue(fullScreenStates.last())

            val edited = longDraft() + "\nFullscreen edit"
            setText(edited, inDialog = true)
            awaitUi("Full-screen draft propagated") { value.value == edited && editor().text() == edited }
            val external = longDraft() + "\nExternal replacement"
            value.value = external
            awaitUi("External draft reaches both editors") {
                editor().text() == external && editor(inDialog = true).text() == external
            }
            clickDescription(activity.getString(R.string.common_collapse_action), inDialog = true)
            awaitUi("Full-screen dismissed") { !hasDialog() && !fullScreenStates.last() }
            assertEquals(external, editor().text())
            assertEquals(TextRange(external.length), editor().selection())
        }
    }

    @Composable
    private fun Composer(
        value: MutableState<TextFieldValue>,
        enterToSend: Boolean = false,
        onSend: () -> Unit = {}
    ) {
        Row(Modifier.fillMaxWidth()) {
            ChatComposerTextField(
                value = value.value,
                onValueChange = { value.value = it },
                placeholder = stringResource(R.string.chat_input_placeholder),
                enterToSend = enterToSend,
                onSend = onSend,
                modifier = Modifier.weight(1f).testTag("composer")
            )
        }
    }

    @Composable
    private fun InputBar(
        value: MutableState<String>,
        isBusy: Boolean = false,
        slashCommands: List<SlashCommand> = emptyList(),
        mentionFiles: List<String> = emptyList(),
        onFullScreenChanged: (Boolean) -> Unit = {}
    ) {
        ChatInputBar(
            value = value.value,
            onValueChange = { value.value = it },
            onSend = {},
            onStop = {},
            isBusy = isBusy,
            workspaceViewModel = null,
            hasRunningSessions = { false },
            activeProvider = null,
            providers = emptyList(),
            modelMetadata = emptyMap(),
            onSelectModel = { _, _ -> },
            reasoningEffort = ReasoningEffort.MEDIUM,
            onReasoningEffortChange = {},
            pendingAttachments = emptyList(),
            onRemoveAttachment = {},
            canUploadFiles = true,
            canUploadImages = true,
            onUploadFile = {},
            onUploadImage = {},
            onTakePhoto = {},
            slashCommands = slashCommands,
            fileMentionState = FileMentionState.Ready(mentionFiles),
            matchFileMentions = { query -> mentionFiles.filter { it.startsWith(query) } },
            modifier = Modifier.testTag("bar"),
            onFullScreenInputChanged = onFullScreenChanged
        )
    }

    private fun longDraft(): String = (1..20).joinToString("\n") { "Line $it" }
    // 零高模拟文本的 clipped bounds 为 Rect.Zero，水平尺寸仍由真实布局约束决定。
    private fun SemanticsNode.layoutBounds(): Rect {
        val info = layoutInfo
        return Rect(info.coordinates.localToRoot(Offset.Zero), Size(info.width.toFloat(), info.height.toFloat()))
    }

    private fun SemanticsNode.text(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun SemanticsNode.selection(): TextRange? = config.getOrNull(SemanticsProperties.TextSelectionRange)
    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
        return layouts.single()
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> findComposeRoot(view.getChildAt(index))?.let { return it } }
        }
        return null
    }

    private inner class ComposerUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit
    ) {
        fun hasDialog(): Boolean = ShadowDialog.getLatestDialog()?.isShowing == true
        fun hostNodes(merged: Boolean = false): List<SemanticsNode> =
            findComposeRoot(view)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = merged).orEmpty()
        fun dialogNodes(merged: Boolean = false): List<SemanticsNode> =
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::findComposeRoot)
                ?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = merged).orEmpty()
        fun editor(inDialog: Boolean = false): SemanticsNode =
            (if (inDialog) dialogNodes() else hostNodes()).single { it.config.contains(SemanticsActions.SetText) }
        fun taggedBounds(tag: String): Rect = hostNodes().single {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag
        }.boundsInRoot
        fun hasExpand(): Boolean = hostNodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.getString(R.string.chat_input_expand)) == true
        }
        fun advanceFrames() = repeat(6) { advanceFrame() }
        fun focusEditor() {
            assertTrue(requireNotNull(editor().config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            awaitUi("Editor focus") { editor().config.getOrNull(SemanticsProperties.Focused) == true }
        }
        fun select(start: Int, end: Int) {
            val expected = TextRange(start, end)
            val current = editor()
            // Compose 对未改变的选区返回 false，不代表不可编辑或失焦。
            if (current.selection() == expected) {
                assertEquals(expected, current.selection())
                return
            }
            assertTrue(requireNotNull(current.config.getOrNull(SemanticsActions.SetSelection)?.action).invoke(start, end, false))
            awaitUi("Selection $start..$end") { editor().selection() == expected }
        }
        fun setText(text: String, inDialog: Boolean = false) {
            assertTrue(requireNotNull(editor(inDialog).config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)))
            awaitUi("Set editor text and layout") {
                editor(inDialog).text() == text && editor(inDialog).textLayout().layoutInput.text.text == text
            }
        }
        fun insert(text: String) {
            assertTrue(requireNotNull(editor().config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action).invoke(AnnotatedString(text)))
        }
        fun clickText(text: String) {
            val node = hostNodes(merged = true).single {
                text in it.texts() && it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        }
        fun clickDescription(description: String, inDialog: Boolean = false) {
            val node = (if (inDialog) dialogNodes(merged = true) else hostNodes(merged = true)).single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        }
        fun awaitUi(message: String, condition: () -> Boolean) {
            repeat(180) {
                advanceFrame()
                if (condition()) return
            }
            fail("$message did not complete within 180 controlled frames; " +
                "host=${hostNodes().map { it.config }}, dialog=${dialogNodes().map { it.config }}")
        }
    }

    private fun render(
        widthPx: Int = 400,
        density: Float = 1f,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        inspect: ComposerUi.() -> Unit
    ) = runTest(timeout = 15.seconds) {
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
        val ui = ComposerUi(activity, view) {
            repeat(4) { looper.runOneTask() }
            Snapshot.sendApplyNotifications()
            runCurrent()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
            Snapshot.sendApplyNotifications()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            findComposeRoot(view)?.measureAndLayoutForTest()
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
                )
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                findComposeRoot(decor)?.measureAndLayoutForTest()
            }
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            ui.awaitUi("Initial editor composition") { ui.hostNodes().any { it.config.contains(SemanticsActions.SetText) } }
            ui.advanceFrames()
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
