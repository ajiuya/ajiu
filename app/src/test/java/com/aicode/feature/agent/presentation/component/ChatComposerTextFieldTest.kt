package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    fun emptyEditorMeasures36DpWithoutChangingItsWidthOrHorizontalInsets() {
        val value = mutableStateOf(TextFieldValue())
        render(content = { Composer(value) }) {
            val field = taggedBounds("composer")
            val editor = editor()
            assertEquals(400f, field.width, 0.01f)
            assertEquals(36f, field.height, 0.01f)
            val editorBounds = editor.layoutBounds()
            assertEquals(368f, editorBounds.width, 0.01f)
            assertEquals(field.left + 16f, editorBounds.left, 0.01f)
            assertEquals(field.right - 16f, editorBounds.right, 0.01f)
            assertEquals(field.top + 6f, editorBounds.top, 0.01f)
            assertEquals("", editor.text())
        }
    }

    @Test(timeout = 30_000)
    fun singleLineKeeps36DpAndTheThemeBodyLargeFont() {
        val value = mutableStateOf(TextFieldValue("Hello"))
        var expectedStyle = TextStyle.Default
        render(content = {
            expectedStyle = MaterialTheme.typography.bodyLarge
            Composer(value)
        }) {
            val field = taggedBounds("composer")
            val layout = editor().textLayout()
            assertEquals(36f, field.height, 0.01f)
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
            assertTrue(twoLineHeight > 36f)
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
            assertTrue(field.height > 36f)
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
            val expectedHeight = maxOf(
                (36f * 1.3f).roundToInt(),
                editor().textLayout().size.height + verticalPadding * 2
            )
            assertEquals(expectedHeight.toFloat(), field.height, 0.01f)
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
            assertEquals(36f, taggedBounds("composer").height, 0.01f)
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
    fun emptyAndSingleLineCardsMeasure84DpWithoutTheMaskInflatingTheReportedHeight() {
        for (width in listOf(400, 320)) {
            val value = mutableStateOf("")
            var measured = IntSize.Zero
            var expectedStyle = TextStyle.Default
            render(widthPx = width, content = {
                expectedStyle = MaterialTheme.typography.bodyLarge
                InputBar(value, onSizeChanged = { measured = it })
            }) {
                val card = taggedBounds("chat_composer")
                assertEquals(width - 32f, card.width, 0.01f)
                assertEquals(84f, card.height, 0.01f)
                assertEquals(1_000f, taggedBounds("chat_input_host").height, 0.01f)
                assertEquals(36f, taggedBounds("chat_composer_row").height, 0.01f)
                assertEquals(96f, taggedBounds("bar").height, 0.01f)
                assertEquals(IntSize(width, 96), measured)

                value.value = "Hello"
                awaitUi("Single-line compact card") { editor().text() == "Hello" }
                advanceFrames()
                assertEquals(card, taggedBounds("chat_composer"))
                assertEquals(IntSize(width, 96), measured)
                val layout = editor().textLayout()
                assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
                assertEquals(expectedStyle.lineHeight, layout.layoutInput.style.lineHeight)
                assertEquals(1, layout.lineCount)
                assertFalse(layout.didOverflowHeight)
            }
        }
    }

    @Test(timeout = 30_000)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun narrowCardsGrowWithMultilineTextAtNormalAndEnlargedFontScales() {
        for (fontScale in listOf(1f, 1.5f)) {
            val value = mutableStateOf("")
            render(widthPx = 320, fontScale = fontScale, content = { InputBar(value, tokenProgress = 0.5f) }) {
                val singleLineHeight = taggedBounds("chat_composer_row").height
                val composerOverhead = taggedBounds("chat_composer").height - singleLineHeight
                assertTrue(composerOverhead >= 48f)
                value.value = "Hello"
                awaitUi("Single-line text at font scale $fontScale") { editor().text() == value.value }
                advanceFrames()
                assertEquals(singleLineHeight, taggedBounds("chat_composer_row").height, 0.01f)
                assertEquals(fontScale, editor().textLayout().layoutInput.density.fontScale, 0f)

                value.value = "First\nSecond"
                awaitUi("Multiline card at font scale $fontScale") {
                    editor().textLayout().lineCount == 2 && taggedBounds("chat_composer_row").height > singleLineHeight
                }
                val row = taggedBounds("chat_composer_row")
                val card = taggedBounds("chat_composer")
                val layout = editor().textLayout()
                assertEquals(layout.size.height + 12f, row.height, 0.01f)
                assertEquals(row.height + composerOverhead, card.height, 0.01f)
                assertEquals(card.height + 12f, taggedBounds("bar").height, 0.01f)
                assertEquals(row.top + 6f, editor().boundsInRoot.top, 0.01f)
                assertTrue(editor().boundsInRoot.bottom <= row.bottom - 6f)
                assertFalse(layout.didOverflowHeight)
                assertEquals(44f, usageRing().layoutBounds().height, 0.01f)

                val maximumDraft = longDraft()
                value.value = maximumDraft
                awaitUi("Maximum multiline viewport at font scale $fontScale") {
                    editor().text() == maximumDraft &&
                        editor().textLayout().layoutInput.text.text == maximumDraft &&
                        editor().textLayout().lineCount == 20 &&
                        taggedBounds("chat_composer_row").height == 120f &&
                        editor().boundsInRoot.height == 108f && hasExpand()
                }
                assertEquals(120f, taggedBounds("chat_composer_row").height, 0.01f)
                assertEquals(108f, editor().boundsInRoot.height, 0.01f)
                assertEquals(120f + composerOverhead, taggedBounds("chat_composer").height, 0.01f)
                assertEquals(132f + composerOverhead, taggedBounds("bar").height, 0.01f)
                assertEquals(longDraft(), editor().text())
                assertTrue(editor().textLayout().size.height > editor().boundsInRoot.height)
            }
        }
    }

    @Test(timeout = 30_000)
    fun recoveryTargetsStay30DpAndDispatchAtTheirEdgesOnNarrowCards() {
        for (fontScale in listOf(1f, 1.5f)) {
            val value = mutableStateOf("")
            val pending = mutableStateOf(0)
            val calls = mutableListOf<String>()
            render(widthPx = 320, fontScale = fontScale, content = {
                InputBar(value, recoveryBubble = {
                    SessionRecoveryBubble(pending.value, true, true, false,
                        { calls += "continue" }, { calls += "abandon" })
                })
            }) {
                val card = taggedBounds("chat_composer")
                val row = taggedBounds("chat_composer_row")
                pending.value = 3
                awaitUi("Recovery targets at font scale $fontScale") { taggedNodeOrNull("session_recovery_bubble") != null }
                assertEquals(card, taggedBounds("chat_composer"))
                assertEquals(row, taggedBounds("chat_composer_row"))
                val bubble = taggedBounds("session_recovery_bubble")
                assertEquals(104f, bubble.width, 0.01f)
                assertEquals(30f, bubble.height, 0.01f)
                assertEquals(row.top + 4f, bubble.top, 0.01f)
                assertEquals(row.right, bubble.right, 0.01f)
                assertTrue(bubble.bottom <= row.bottom)
                assertTrue(editor().layoutBounds().right <= bubble.left)
                val placeholder = activity.getString(R.string.chat_input_placeholder)
                assertTrue(hostNodes().single { placeholder in it.texts() }.layoutBounds().right <= bubble.left)

                val continueButton = taggedBounds("session_recovery_continue")
                val abandonButton = taggedBounds("session_recovery_abandon")
                assertEquals(30f, continueButton.height, 0.01f)
                assertEquals(30f, abandonButton.height, 0.01f)
                assertTrue(continueButton.right < abandonButton.left)
                tap(Offset(continueButton.right - 2f, continueButton.center.y))
                tap(Offset(abandonButton.left + 2f, abandonButton.center.y))
                assertEquals(listOf("continue", "abandon"), calls)
                assertFalse(hasDialog())
            }
        }
    }

    @Test(timeout = 30_000)
    fun sendStopAndAttachmentKeepTheirVisibleSizesAndTouchCallbacksAtBothFontScales() {
        for (fontScale in listOf(1f, 1.5f)) {
            val value = mutableStateOf("")
            val busy = mutableStateOf(false)
            var sends = 0
            var stops = 0
            render(widthPx = 320, fontScale = fontScale, content = {
                InputBar(value, isBusy = busy.value, tokenProgress = 0.5f,
                    onSend = { sends++ }, onStop = { stops++ })
            }) {
                val sendLabel = activity.getString(R.string.chat_send)
                val stopLabel = activity.getString(R.string.chat_stop)
                val attachmentLabel = activity.getString(R.string.chat_add_attachment)
                val send = describedButton(sendLabel)
                val attachment = describedButton(attachmentLabel)
                for (button in listOf(send, attachment)) {
                    assertEquals(36f, button.layoutBounds().width, 0.01f)
                    assertEquals(36f, button.layoutBounds().height, 0.01f)
                }
                assertEquals(44f, usageRing().layoutBounds().width, 0.01f)
                assertEquals(44f, usageRing().layoutBounds().height, 0.01f)
                assertTrue(send.config.contains(SemanticsProperties.Disabled))
                tap(send.boundsInRoot.center)
                assertEquals(0, sends)

                value.value = "Send this"
                awaitUi("Send enabled") { !describedButton(sendLabel).config.contains(SemanticsProperties.Disabled) }
                val sendBounds = describedButton(sendLabel).boundsInRoot
                tap(Offset(sendBounds.left + 2f, sendBounds.center.y))
                tap(Offset(sendBounds.right - 2f, sendBounds.center.y))
                assertEquals(2, sends)
                busy.value = true
                awaitUi("Busy attachment disabled") {
                    describedButton(attachmentLabel).config.contains(SemanticsProperties.Disabled)
                }
                tap(describedButton(sendLabel).boundsInRoot.center)
                assertEquals(3, sends)
                tap(describedButton(attachmentLabel).boundsInRoot.center)
                assertFalse(hasDialog())
                value.value = ""
                awaitUi("Stop replaces send") { hasDescription(stopLabel) }
                val stop = describedButton(stopLabel).layoutBounds()
                assertEquals(36f, stop.width, 0.01f)
                assertEquals(36f, stop.height, 0.01f)
                tap(Offset(stop.right - 2f, stop.center.y))
                assertEquals(1, stops)
                assertEquals(3, sends)
                assertEquals(44f, usageRing().layoutBounds().height, 0.01f)
            }
        }
    }

    @Test(timeout = 30_000)
    fun attachmentSheetKeepsAllThreeUploadCallbacksAndPreviewRemovalGrowsTheCardNaturally() {
        val value = mutableStateOf("")
        val attachment = PendingUploadAttachment("notes.txt", "notes.txt", "", "text/plain", 12L)
        val attachments = mutableStateOf(listOf(attachment))
        val calls = mutableListOf<String>()
        render(widthPx = 320, content = {
            InputBar(value, pendingAttachments = attachments.value,
                onRemoveAttachment = { removedIndex ->
                    attachments.value = attachments.value.filterIndexed { index, _ -> index != removedIndex }
                },
                onUploadFile = { calls += "file" }, onUploadImage = { calls += "image" }, onTakePhoto = { calls += "photo" })
        }) {
            assertEquals(168f, taggedBounds("chat_composer").height, 0.01f)
            assertTrue(hostNodes().any { "notes.txt" in it.texts() })
            assertFalse(describedButton(activity.getString(R.string.chat_send)).config.contains(SemanticsProperties.Disabled))
            tap(describedButton(activity.getString(R.string.chat_remove_attachment)).boundsInRoot.center)
            awaitUi("Attachment preview removed") { taggedBounds("chat_composer").height == 84f }
            assertTrue(attachments.value.isEmpty())
            for ((labelId, callback) in listOf(
                R.string.chat_upload_file to "file",
                R.string.chat_upload_image to "image",
                R.string.chat_take_photo to "photo"
            )) {
                tap(describedButton(activity.getString(R.string.chat_add_attachment)).boundsInRoot.center)
                val label = activity.getString(labelId)
                awaitUi("Attachment sheet $callback action") {
                    dialogNodes(merged = true).any { label in it.texts() && it.config.contains(SemanticsActions.OnClick) }
                }
                clickText(label, inDialog = true)
                awaitUi("Attachment sheet $callback dispatched") { calls.lastOrNull() == callback && !hasDialog() }
            }
            assertEquals(listOf("file", "image", "photo"), calls)
            assertEquals(84f, taggedBounds("chat_composer").height, 0.01f)
        }
    }

    @Test(timeout = 30_000)
    @Config(sdk = [30])
    fun imeInsetAddsOnlyItsOwnHeightAndKeepsTheCompactCardAboveIt() {
        val value = mutableStateOf("")
        render(widthPx = 320, content = { InputBar(value) }) {
            val card = taggedBounds("chat_composer")
            val bar = taggedBounds("bar")
            assertEquals(96f, bar.height, 0.01f)
            setImeBottom(240)
            awaitUi("IME height applied") { taggedBounds("bar").height == 336f }
            val raisedCard = taggedBounds("chat_composer")
            assertEquals(card.width, raisedCard.width, 0.01f)
            assertEquals(card.height, raisedCard.height, 0.01f)
            assertEquals(card.top - 240f, raisedCard.top, 0.01f)
            assertEquals(bar.bottom, taggedBounds("bar").bottom, 0.01f)
            assertEquals(252f, taggedBounds("bar").bottom - raisedCard.bottom, 0.01f)
            setImeBottom(0)
            awaitUi("IME height removed") { taggedBounds("bar").height == 96f }
            assertEquals(card, taggedBounds("chat_composer"))
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
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun longUnbrokenTextKeepsItsConstrainedWidthAndWrappingPolicyAndStaysEditable() {
        val unbrokenDraft = "wrapped words ".repeat(1_000)
        val value = mutableStateOf(unbrokenDraft)
        var expectedStyle = TextStyle.Default
        render(content = {
            expectedStyle = MaterialTheme.typography.bodyLarge
            InputBar(value)
        }) {
            // Robolectric API 28 的 hidden SDK 断行桩只返回一行，策略断言不代表已验证真机软换行。
            val layout = editor().textLayout()
            val editorBounds = editor().layoutBounds()
            val expandWidth = if (hasExpand()) 32f else 0f
            assertEquals(400f, taggedBounds("bar").width, 0.01f)
            assertEquals(40f, editorBounds.left, 0.01f)
            assertEquals(taggedBounds("chat_composer_row").width - 32f - expandWidth, editorBounds.width, 0.01f)
            assertEquals(editorBounds.width.roundToInt(), layout.layoutInput.constraints.maxWidth)
            assertTrue(layout.layoutInput.softWrap)
            assertTrue(layout.layoutInput.maxLines > 1)
            assertEquals(unbrokenDraft, editor().text())
            assertEquals(unbrokenDraft, layout.layoutInput.text.text)
            assertFalse(value.value.contains('\n'))
            assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(expectedStyle.lineHeight, layout.layoutInput.style.lineHeight)

            focusEditor()
            select(unbrokenDraft.length, unbrokenDraft.length)
            insert("tail")
            awaitUi("Unbroken draft stays editable") { editor().text() == unbrokenDraft + "tail" }
            assertEquals(unbrokenDraft + "tail", value.value)
            assertFalse(value.value.contains('\n'))

            val multilineDraft = longDraft()
            value.value = multilineDraft
            awaitUi("Multiline draft reaches the capped viewport") {
                editor().text() == multilineDraft && editor().textLayout().lineCount == 20 && hasExpand()
            }
            advanceFrames()
            val multilineLayout = editor().textLayout()
            assertEquals(120f, taggedBounds("chat_composer_row").height, 0.01f)
            assertEquals(108f, editor().boundsInRoot.height, 0.01f)
            assertTrue(multilineLayout.size.height > editor().boundsInRoot.height)
            assertEquals(editor().boundsInRoot.width.roundToInt(), multilineLayout.layoutInput.constraints.maxWidth)
            val scroll = hostNodes().single { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
            assertTrue(requireNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, -96f))
            advanceFrames()
            assertEquals(108f, editor().boundsInRoot.height, 0.01f)
            select(multilineDraft.length, multilineDraft.length)
            insert("tail")
            awaitUi("Overflowing multiline draft stays editable") { editor().text() == multilineDraft + "tail" }
            assertEquals(multilineDraft + "tail", value.value)
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
        onFullScreenChanged: (Boolean) -> Unit = {},
        tokenProgress: Float = 0f,
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        pendingAttachments: List<PendingUploadAttachment> = emptyList(),
        onRemoveAttachment: (Int) -> Unit = {},
        onUploadFile: () -> Unit = {},
        onUploadImage: () -> Unit = {},
        onTakePhoto: () -> Unit = {},
        recoveryBubble: @Composable () -> Unit = {},
        onSizeChanged: (IntSize) -> Unit = {}
    ) {
        ChatInputBar(
            value = value.value,
            onValueChange = { value.value = it },
            onSend = onSend,
            onStop = onStop,
            isBusy = isBusy,
            workspaceViewModel = null,
            hasRunningSessions = { false },
            activeProvider = null,
            providers = emptyList(),
            modelMetadata = emptyMap(),
            onSelectModel = { _, _ -> },
            reasoningEffort = ReasoningEffort.MEDIUM,
            onReasoningEffortChange = {},
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = onRemoveAttachment,
            canUploadFiles = true,
            canUploadImages = true,
            onUploadFile = onUploadFile,
            onUploadImage = onUploadImage,
            onTakePhoto = onTakePhoto,
            slashCommands = slashCommands,
            fileMentionState = FileMentionState.Ready(mentionFiles),
            matchFileMentions = { query -> mentionFiles.filter { it.startsWith(query) } },
            modifier = Modifier.testTag("bar").onSizeChanged(onSizeChanged),
            tokenProgress = tokenProgress,
            recoveryBubble = recoveryBubble,
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
        fun taggedNodeOrNull(tag: String): SemanticsNode? = hostNodes().singleOrNull {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag
        }
        fun taggedBounds(tag: String): Rect = requireNotNull(taggedNodeOrNull(tag)).boundsInRoot
        fun hasDescription(description: String): Boolean = hostNodes().any {
            description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        fun describedButton(description: String): SemanticsNode = hostNodes(merged = true).single {
            description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                it.config.contains(SemanticsActions.OnClick)
        }
        fun usageRing(): SemanticsNode {
            val label = activity.getString(R.string.chat_context_usage, 50)
            return hostNodes().single {
                label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
        }
        fun setImeBottom(bottom: Int) {
            val root = requireNotNull(findComposeRoot(view)) as View
            ViewCompat.dispatchApplyWindowInsets(root,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build())
        }
        fun tap(position: Offset) {
            val root = requireNotNull(findComposeRoot(view)) as View
            val downTime = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = position.x
                y = position.y
                pressure = 1f
                size = 1f
            }
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    root.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                advanceFrame()
            }
        }
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
        fun clickText(text: String, inDialog: Boolean = false) {
            val node = (if (inDialog) dialogNodes(merged = true) else hostNodes(merged = true)).single {
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
                    AIEditorTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().testTag("chat_input_host")) {
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                content()
                            }
                        }
                    }
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
