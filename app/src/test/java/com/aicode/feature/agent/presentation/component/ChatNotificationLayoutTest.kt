package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.domain.notification.AgentMessageAction
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class)
class ChatNotificationLayoutTest {
    @Test
    @TextLayoutMode(TextLayoutMode.Mode.LEGACY)
    fun consecutiveRowsKeep52DpCardsAndOnlyOne8DpFooterWithoutShrinkingFonts() {
        val models = listOf(
            metadata(kind = "subagent", status = "completed"),
            metadata(kind = "agent_message", status = "message"),
            metadata(kind = "agent_message", status = "message").copy(
                action = AgentMessageAction.ADD_INSTRUCTION, targetTitle = "Coder", fromParent = true,
            ),
            metadata(kind = "background_task", status = "completed").copy(taskTitle = "Compile", exitCode = "0"),
        ).map { notificationRowModel(it, "17:42") }
        val ui = measure(width = 400) { Column { models.forEach { ChatNotificationRow(it) } } }

        assertCardSpacing(ui, expectedCount = 4, expectedHeight = 52f)
        assertTypographyAndVerticalFit(ui, 1f, requireNonZeroText = false)
        assertEquals(4, ui.texts.count { it.text == "17:42" })
        assertEquals(3, ui.details.size)
        assertEquals(listOf("Task completed · exit code 0"), ui.statuses.map { it.text })
        ui.details.forEachIndexed { index, detail -> assertEquals(ui.titles[index].bounds.left, detail.bounds.left, 0.01f) }
    }

    @Test
    @TextLayoutMode(TextLayoutMode.Mode.LEGACY)
    fun legacyCombinedAndSeparateCarriersAndProjectedToolNoticesShareTheSame8DpSpacing() {
        val notices = listOf(
            PendingNotification(AgentNotificationKind.SUBAGENT, "coder", "Coder", NotificationOutcome.COMPLETED,
                resultExcerpt = "Result ready", eventId = "result"),
            PendingNotification(AgentNotificationKind.AGENT_MESSAGE, "tester", "Tester", NotificationOutcome.COMPLETED,
                message = "Review ready", eventId = "message"),
            PendingNotification(AgentNotificationKind.BACKGROUND_TASK, "compile", "Compile", NotificationOutcome.COMPLETED,
                exitCode = 0, eventId = "compile"),
        )
        val injector = DefaultAgentEventInjector()
        val legacy = listOf(
            AgentUIMessage("combined", MessageRole.USER, injector.inject("", notices.take(2)),
                timestamp = 120_000L, isBackgroundNotification = true),
            AgentUIMessage("separate", MessageRole.USER, injector.inject("", notices.takeLast(1)),
                timestamp = 120_001L, isBackgroundNotification = true),
        )
        val oldUi = measure(width = 400) { Column { legacy.forEach { AgentMessageItem(it) } } }
        assertCardSpacing(oldUi, expectedCount = 3, expectedHeight = 52f)
        assertTypographyAndVerticalFit(oldUi, 1f, requireNonZeroText = false)
        assertEquals(listOf(oldUi.activityTitle("Coder"), oldUi.activityTitle("Tester"), "Compile"),
            oldUi.titles.map { it.text })
        assertEquals(listOf("Task completed · exit code 0"), oldUi.statuses.map { it.text })
        val titleByEventId = notices.mapIndexed { index, notice -> notice.eventId to oldUi.titles[index].text }.toMap()
        val detailByEventId = mapOf("result" to oldUi.details[0].text, "message" to oldUi.details[1].text)

        val tool = AgentUIMessage("tool", MessageRole.TOOL, injector.inject("{\"status\":\"success\"}", notices),
            timestamp = 120_000L, toolName = "bash")
        for ((messages, expectedEventIds) in listOf(
            legacy to listOf("message", "result", "compile"),
            listOf(tool) to listOf("compile", "message", "result"),
        )) {
            val projectedItems = projectChatNotificationItems(
                messages.map { ChatRenderItem(it, it.id, it.role.name) }, messages, emptyList(), emptyList(), emptyMap(),
            ).filter { it.notification != null }
            assertEquals(expectedEventIds.map { "chat-notification:$it" }, projectedItems.map { it.key })
            val rows = projectedItems.map { requireNotNull(it.notification) }
            assertEquals(3, rows.size)
            val projected = measure(width = 400) { Column { rows.forEach { ChatNotificationRow(it) } } }
            assertCardSpacing(projected, expectedCount = 3, expectedHeight = 52f)
            assertTypographyAndVerticalFit(projected, 1f, requireNonZeroText = false)
            assertEquals(expectedEventIds.map { titleByEventId.getValue(it) }, projected.titles.map { it.text })
            assertEquals(expectedEventIds.mapNotNull(detailByEventId::get), projected.details.map { it.text })
            assertEquals(oldUi.statuses.map { it.text }, projected.statuses.map { it.text })
            assertEquals(oldUi.bounds.height, projected.bounds.height, 0.01f)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishAgentHeadersShowTheSenderOnceAndKeepTimeAndRealResultPrefixes() = assertLocalizedAgentRows()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseAgentHeadersShowTheSenderOnceAndKeepTimeAndRealResultPrefixes() = assertLocalizedAgentRows()

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishLongSendersAndSummariesKeepEllipsisPolicyAndBoundedSlotsAt320AndLargeFont() =
        assertLongSenderLayout("Long named reviewer checking notification layout ")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseLongSendersAndSummariesKeepEllipsisPolicyAndBoundedSlotsAt320AndLargeFont() =
        assertLongSenderLayout("核对真实通知布局的超长名称测试代理")

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishLongTaskNamesKeepIndependentVisibleStatusAndExitAtEveryWidthAndFontScale() =
        assertLongBackgroundTaskLayout("Recheck the notification layout after the terminal task completes ", "Build")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseLongTaskNamesKeepIndependentVisibleStatusAndExitAtEveryWidthAndFontScale() =
        assertLongBackgroundTaskLayout("重新核对终端后台任务完成后的通知布局与退出码显示", "构建")

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishTimelineXmlAndJsonBackgroundPayloadsShareVisibleStatusGeometry() =
        assertBackgroundPayloadLayouts("Check the long background notification task title ")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseTimelineXmlAndJsonBackgroundPayloadsShareVisibleStatusGeometry() =
        assertBackgroundPayloadLayouts("核对后台通知任务名称与独立状态行")

    @Test
    fun emptyDetailNonBackgroundRowsCenterTheirOnlyHeaderWithoutAnyPlaceholderNodes() {
        val models = listOf(
            metadata("agent_message", "message").copy(body = ""),
            metadata("user_message", "message").copy(body = "  \n"),
            metadata("agent_message", "message").copy(
                body = "", action = AgentMessageAction.ADD_INSTRUCTION, targetTitle = "Coder", fromParent = true,
            ),
            metadata("custom", "message").copy(body = "", detail = "", summary = "Notice"),
        ).map { notificationRowModel(it, "17:42") }
        for (width in listOf(320, 400)) {
            for (fontScale in listOf(1f, 1.5f)) {
                val ui = measure(width, fontScale) { Column { models.forEach { ChatNotificationRow(it) } } }
                assertTrue(ui.details.isEmpty())
                assertTrue(ui.statuses.isEmpty())
                assertEquals(models.size * 2, ui.texts.size)
                assertTrue(ui.texts.none { it.text.isBlank() })
                assertEquals(models.size, ui.headers.size)
                ui.cards.forEachIndexed { index, card ->
                    val header = ui.headers[index]
                    assertEquals("A single header must be vertically centered in the card",
                        card.bounds.center.y, header.bounds.center.y, 0.01f)
                    assertEquals(maxOf(52f, 12f + header.bounds.height), card.bounds.height, 0.01f)
                    assertEquals(1, ui.titles[index].layout!!.layoutInput.maxLines)
                    assertFullTextFits(ui.clocks[index])
                }
                assertCardSpacing(ui, models.size)
                assertTypographyAndVerticalFit(ui, fontScale)
            }
        }
    }

    @Test
    fun unnamedBackgroundTaskHasALocalizedNameAndExplicitUnknownStatusAndExit() {
        val model = notificationRowModel(metadata("background_task", "unrecognized").copy(
            title = "", taskTitle = "", body = "", exitCode = "",
        ), "17:42")
        val ui = measure(width = 320, fontScale = 1.5f) { ChatNotificationRow(model) }

        assertEquals(ui.string(R.string.chat_notification_background_task_default_name), ui.titles.single().text)
        assertEquals(ui.taskStatus(R.string.chat_notification_background_task_status_unknown, null), ui.statuses.single().text)
        assertTrue(ui.details.isEmpty())
        assertBackgroundStatusGeometry(ui)
        assertTypographyAndVerticalFit(ui, 1.5f)
        assertCardSpacing(ui, 1)
    }

    @Test
    fun commandsKeepTheirInstructionOrCorrectionTitleInsteadOfBecomingGenericAgentMessages() {
        val instruction = notificationRowModel(metadata("agent_message", "message").copy(
            action = AgentMessageAction.ADD_INSTRUCTION, fromParent = true, targetTitle = "探索员",
            body = "继续核对真实输出",
        ), "17:42")
        val correction = notificationRowModel(metadata("agent_message", "message").copy(
            action = AgentMessageAction.CORRECTION, fromParent = true, targetTitle = "探索员",
            body = "保留真实失败结果",
        ), "17:43")
        val event = SubAgentInteractionMetadata(
            id = "parent-command", parentSessionId = "parent", subSessionId = "child",
            kind = "MESSAGE_FROM_PARENT", timestamp = 120_000L, origin = "USER", sourceMessageId = null,
            title = "继续核对", model = null, preview = "事件中的原始指令", agentName = "探索员",
        )
        val fromInteraction = requireNotNull(subAgentInstructionNotification(event, null).notification)
        val models = listOf(instruction, correction, fromInteraction)
        val ui = measure(width = 400) { Column { models.forEach { ChatNotificationRow(it) } } }

        assertEquals(listOf("主代理 给探索员代理添加指令", "主代理 给探索员代理发送纠偏", "主代理 给探索员代理添加指令"),
            ui.titles.map { it.text })
        assertEquals(models.map { it.detail }, ui.details.map { it.text })
        assertTrue(models.all { it.agentSender == null && it.agentOutcome == null && it.tone == ChatNotificationTone.COMMAND })
        assertTrue(ui.titles.none { "sent a message" in it.text || "发来一条消息" in it.text })
        assertCardSpacing(ui, expectedCount = 3)
        assertTypographyAndVerticalFit(ui, 1f)
    }

    @Test
    fun nonAgentTitlesOnlyAddASourceWhenItIsNotAlreadyTheLeadingText() {
        val base = notificationRowModel(metadata("user_message", "message"), "17:42")
        val models = listOf(
            base.copy(source = "用户", title = "用户补充了一条消息"),
            base.copy(source = "主代理", title = "给探索员代理添加指令"),
            base.copy(source = "", title = "系统事件"),
            base.copy(source = "系统", title = "系统事件"),
        )
        val ui = measure(width = 400) { Column { models.forEach { ChatNotificationRow(it) } } }

        assertEquals(listOf("用户补充了一条消息", "主代理 给探索员代理添加指令", "系统事件", "系统事件"), ui.titles.map { it.text })
        assertEquals(12, ui.texts.size)
        assertTrue(ui.texts.none { it.text in setOf("用户", "主代理", "系统") })
    }

    @Test
    fun legacyGraphicsTextCalibrationUsesSyntheticWidthsMetricsAndSubstringInsteadOfGlyphEllipsis() {
        val paint = TextPaint()
        for (size in listOf(12f, 24f)) {
            paint.textSize = size
            assertEquals(6f, paint.measureText("abcdef"), 0f)
            val metrics = paint.fontMetricsInt
            assertEquals(-28, metrics.ascent)
            assertEquals(7, metrics.descent)
            assertEquals(35, paint.getFontMetricsInt(null))
            assertEquals("abc", TextUtils.ellipsize("abcdef", paint, 3f, TextUtils.TruncateAt.END).toString())
        }
        val sender = "Long sender ".repeat(200)
        val model = notificationRowModel(metadata("subagent", "completed").copy(title = sender), "17:42")
        val ui = measure(width = 400) { ChatNotificationRow(model) }
        val title = ui.titles.single()
        val layout = requireNotNull(title.layout)
        assertEquals(ui.activityTitle(sender), title.text)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        assertEquals(1, layout.layoutInput.maxLines)
        assertTrue(layout.multiParagraph.intrinsics.maxIntrinsicWidth > title.bounds.width)
        assertFalse("The shadow cannot prove a requested overflowing ellipsis glyph", layout.isLineEllipsized(0))
    }

    private fun assertLocalizedAgentRows() {
        val outcomes = listOf(
            "completed" to R.string.chat_notification_agent_completed,
            "failed" to R.string.chat_notification_agent_failed,
            "stopped" to R.string.chat_notification_agent_stopped,
        )
        val metadata = outcomes.map { (status, _) -> metadata("subagent", status).copy(
            title = "Coder", summary = "Original event summary: $status", body = "Public summary\nSecond line stays in the single-line preview",
        ) } + metadata("agent_message", "message").copy(title = "Coder", sourceTitle = "Coder", body = "A normal agent message")
        for ((index, notice) in metadata.withIndex()) {
            val model = notificationRowModel(notice, "17:42")
            val ui = measure(width = 400) { ChatNotificationRow(model) }
            val expectedTitle = ui.activityTitle("Coder")
            val expectedDetail = if (index < outcomes.size) {
                "${ui.string(outcomes[index].second)} · ${notice.body}"
            } else notice.body

            assertEquals(expectedTitle, ui.titles.single().text)
            assertEquals(1, "Coder".toRegex().findAll(ui.titles.single().text).count())
            assertEquals(expectedDetail, ui.details.single().text)
            assertEquals(3, ui.texts.size)
            assertEquals(1, ui.texts.count { it.text == "17:42" })
            assertFalse(ui.texts.any { it.text == "Coder" || it.text == notice.summary })
            assertEquals(notice.body, model.detail)
            if (index < outcomes.size) assertEquals(notice.summary, model.title)
            assertEquals(1, ui.details.single().layout!!.lineCount)
            assertEquals(TextOverflow.Ellipsis, ui.details.single().layout!!.layoutInput.overflow)
            assertFalse(ui.titles.single().layout!!.isLineEllipsized(0))
            assertTitleTimeAndDetailGeometry(ui, "17:42")
            assertTypographyAndVerticalFit(ui, 1f)
            assertCardSpacing(ui, expectedCount = 1)
        }
    }

    private fun assertLongSenderLayout(senderPrefix: String) {
        for ((width, fontScale) in listOf(400 to 1f, 320 to 1.5f)) {
            val sender = senderPrefix.repeat(64)
            val model = notificationRowModel(metadata("subagent", "failed").copy(
                title = sender, summary = "Original failed summary", body = "Actual result preview ".repeat(30),
            ), "23:59")
            val ui = measure(width = width, fontScale = fontScale) { ChatNotificationRow(model) }

            assertEquals(ui.activityTitle(sender), ui.titles.single().text)
            assertEquals(1, Regex(Regex.escape(sender)).findAll(ui.titles.single().text).count())
            assertTrue(ui.details.single().text.startsWith("${ui.string(R.string.chat_notification_agent_failed)} · "))
            for (node in listOf(ui.titles.single(), ui.details.single())) {
                val layout = requireNotNull(node.layout)
                assertTrue("Long fixture must exceed the measured text slot",
                    layout.multiParagraph.intrinsics.maxIntrinsicWidth > node.bounds.width)
                assertEquals("The UI must request single-line ellipsis", TextOverflow.Ellipsis, layout.layoutInput.overflow)
                assertEquals(1, layout.layoutInput.maxLines)
                assertTrue("The visible text slot must contain the constrained layout", node.bounds.width >= layout.size.width - 0.01f)
                assertTrue("The constrained layout must stay within its available width", layout.size.width <= layout.layoutInput.constraints.maxWidth)
            }
            assertEquals(1, ui.titles.single().layout!!.lineCount)
            assertEquals(1, ui.details.single().layout!!.lineCount)
            assertTitleTimeAndDetailGeometry(ui, "23:59")
            assertTypographyAndVerticalFit(ui, fontScale)
            val titleHeight = ui.titles.single().layout!!.size.height.toFloat()
            val timeHeight = ui.texts.single { it.text == "23:59" }.layout!!.size.height.toFloat()
            val detailHeight = ui.details.single().layout!!.size.height.toFloat()
            val measuredContentHeight = 12f + maxOf(20f, titleHeight, timeHeight) + 2f + detailHeight
            assertEquals("The card must contain the nonzero measured lines and all padding",
                maxOf(52f, measuredContentHeight), ui.cards.single().bounds.height, 0.01f)
            assertTrue("Nonzero simulated metrics must grow beyond the minimum", measuredContentHeight > 52f)
            assertCardSpacing(ui, expectedCount = 1)
        }
    }

    private data class BackgroundCase(val status: String?, val exit: String?, val statusResource: Int)

    private val backgroundCases = listOf(
        BackgroundCase("completed", "0", R.string.chat_notification_background_task_completed),
        BackgroundCase("failed", "23", R.string.chat_notification_background_task_failed),
        BackgroundCase("stopped", "130", R.string.chat_notification_background_task_stopped),
        BackgroundCase("completed", null, R.string.chat_notification_background_task_completed),
        BackgroundCase("failed", "unknown", R.string.chat_notification_background_task_failed),
        BackgroundCase(null, null, R.string.chat_notification_background_task_status_unknown),
        BackgroundCase("unrecognized", "0", R.string.chat_notification_background_task_status_unknown),
    )

    private fun assertLongBackgroundTaskLayout(titlePrefix: String, paragraphTitle: String) {
        val title = titlePrefix.repeat(64).trimEnd()
        val models = backgroundCases.map { case -> notificationRowModel(
            metadata("background_task", "completed").copy(
                taskTitle = title, status = case.status, exitCode = case.exit.orEmpty(),
                command = "private command", detail = "private detail", tailOutput = "private output",
            ), "23:59",
        ) }
        for (width in listOf(320, 400)) {
            for (fontScale in listOf(1f, 1.5f)) {
                val ui = measure(width, fontScale) { Column { models.forEach { ChatNotificationRow(it) } } }
                assertEquals(models.size, ui.statuses.size)
                assertTrue(ui.details.isEmpty())
                assertEquals(models.size * 3, ui.texts.size)
                ui.titles.forEachIndexed { index, node ->
                    val layout = requireNotNull(node.layout)
                    val case = backgroundCases[index]
                    assertEquals(title, node.text)
                    assertTrue("The long title fixture must exceed both available lines",
                        layout.multiParagraph.intrinsics.maxIntrinsicWidth > node.bounds.width * 2)
                    assertEquals(2, layout.layoutInput.maxLines)
                    assertTrue(layout.layoutInput.softWrap)
                    assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
                    assertEquals("API 28 legacy nComputeLineBreaks returns the full paragraph as one line",
                        1, layout.lineCount)
                    assertEquals("The shadow limitation must not be mistaken for measured width wrapping",
                        title.length, layout.getLineEnd(0, visibleEnd = true))
                    assertTrue(node.bounds.width >= layout.size.width - 0.01f)
                    assertTrue(layout.size.width <= layout.layoutInput.constraints.maxWidth)
                    assertEquals(ui.taskStatus(case.statusResource, case.exit?.takeUnless { it == "unknown" }),
                        ui.statuses[index].text)
                    assertBackgroundStatusGeometry(ui, index)
                    val expectedContentHeight = 12f + ui.headers[index].bounds.height + 2f + ui.statuses[index].bounds.height
                    assertEquals("The card must grow instead of clipping the status or scaled title",
                        maxOf(52f, expectedContentHeight), ui.cards[index].bounds.height, 0.01f)
                    assertTrue(ui.cards[index].bounds.height > 52f)
                }
                assertCardSpacing(ui, models.size)
                assertTypographyAndVerticalFit(ui, fontScale)
                assertTwoLineBackgroundTitleGeometry(width, fontScale, paragraphTitle)
            }
        }
    }

    private fun assertTwoLineBackgroundTitleGeometry(width: Int, fontScale: Float, paragraphTitle: String) {
        val title = List(3) { paragraphTitle }.joinToString("\n")
        val models = backgroundCases.map { case -> notificationRowModel(
            metadata("background_task", "completed").copy(
                taskTitle = title, status = case.status, exitCode = case.exit.orEmpty(),
            ), "23:59",
        ) }
        val ui = measure(width, fontScale) { Column { models.forEach { ChatNotificationRow(it) } } }
        assertTrue(ui.details.isEmpty())
        assertEquals(models.size * 3, ui.texts.size)
        assertEquals(models.size, ui.statuses.size)
        ui.titles.forEachIndexed { index, node ->
            val layout = requireNotNull(node.layout)
            val case = backgroundCases[index]
            assertEquals(title, node.text)
            assertEquals(2, layout.layoutInput.maxLines)
            assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
            assertTrue(layout.layoutInput.softWrap)
            assertEquals("Explicit paragraphs must exercise an actual two-line production header", 2, layout.lineCount)
            assertEquals(0, layout.getLineStart(0))
            assertEquals(paragraphTitle.length, layout.getLineEnd(0, visibleEnd = true))
            assertEquals(paragraphTitle.length + 1, layout.getLineStart(1))
            assertEquals("The third paragraph must not expand the title beyond its two-line limit",
                paragraphTitle.length * 2 + 1, layout.getLineEnd(1, visibleEnd = true))
            assertTrue(layout.getLineEnd(1, visibleEnd = true) < title.length)
            for (line in 0 until 2) {
                assertTrue(layout.getLineBottom(line) > layout.getLineTop(line))
                assertTrue(layout.getLineLeft(line) >= -0.01f && layout.getLineRight(line) <= node.bounds.width + 0.01f)
                assertTrue(layout.getLineTop(line) >= -0.01f && layout.getLineBottom(line) <= node.bounds.height + 0.01f)
            }
            assertTrue(layout.getLineTop(1) >= layout.getLineBottom(0))
            assertTrue(node.bounds.height >= layout.size.height - 0.01f)
            assertTrue(layout.multiParagraph.height <= node.bounds.height + 0.01f)
            assertEquals("A two-line header must reserve both measured lines",
                node.bounds.height, ui.headers[index].bounds.height, 0.01f)
            assertEquals(ui.taskStatus(case.statusResource, case.exit?.takeUnless { it == "unknown" }), ui.statuses[index].text)
            assertBackgroundStatusGeometry(ui, index)
            assertEquals("No extra blank row may remain below the complete status",
                12f + ui.headers[index].bounds.height + 2f + ui.statuses[index].bounds.height,
                ui.cards[index].bounds.height, 0.01f)
            assertTrue(ui.cards[index].bounds.height > 52f)
        }
        assertCardSpacing(ui, models.size)
        assertTypographyAndVerticalFit(ui, fontScale)
    }

    private fun assertBackgroundPayloadLayouts(titlePrefix: String) {
        val title = titlePrefix.repeat(64).trimEnd()
        val notices = listOf(
            PendingNotification(AgentNotificationKind.BACKGROUND_TASK, "term-success", title, NotificationOutcome.COMPLETED,
                exitCode = 0, eventId = "payload-success"),
            PendingNotification(AgentNotificationKind.BACKGROUND_TASK, "term-failed", title, NotificationOutcome.FAILED,
                exitCode = 23, eventId = "payload-failed"),
            PendingNotification(AgentNotificationKind.BACKGROUND_TASK, "term-stopped", title, NotificationOutcome.STOPPED,
                exitCode = null, eventId = "payload-stopped"),
        )
        val statusResources = listOf(R.string.chat_notification_background_task_completed,
            R.string.chat_notification_background_task_failed, R.string.chat_notification_background_task_stopped)
        val timestamp = 120_000L
        val injector = DefaultAgentEventInjector()
        val legacy = AgentUIMessage("legacy-background", MessageRole.USER, injector.inject("", notices),
            timestamp = timestamp, isBackgroundNotification = true)
        val tool = AgentUIMessage("tool-background", MessageRole.TOOL,
            injector.inject("{\"status\":\"success\"}", notices), timestamp = timestamp, toolName = "bash")
        val timelineModels = notices.map { requireNotNull(notificationTimelineItem(it, timestamp).notification) }
        val projected = projectChatNotificationItems(listOf(ChatRenderItem(tool, tool.id, "TOOL")),
            listOf(tool), emptyList(), emptyList(), emptyMap()).filter { it.notification != null }
        val projectedByKey = projected.associateBy { it.key }
        assertEquals(notices.map { "chat-notification:${it.eventId}" }.toSet(), projectedByKey.keys)
        val projectedModels = notices.map { requireNotNull(projectedByKey.getValue("chat-notification:${it.eventId}").notification) }
        assertEquals(timelineModels, projectedModels)
        for (width in listOf(320, 400)) {
            for (fontScale in listOf(1f, 1.5f)) {
                val legacyUi = measure(width, fontScale) { AgentMessageItem(legacy) }
                val timelineUi = measure(width, fontScale) { Column { timelineModels.forEach { ChatNotificationRow(it) } } }
                val toolUi = measure(width, fontScale) { Column { projectedModels.forEach { ChatNotificationRow(it) } } }
                for (ui in listOf(legacyUi, timelineUi, toolUi)) {
                    assertEquals(notices.map { it.title }, ui.titles.map { it.text })
                    assertEquals(notices.mapIndexed { index, notice -> ui.taskStatus(statusResources[index], notice.exitCode?.toString()) },
                        ui.statuses.map { it.text })
                    assertTrue(ui.details.isEmpty())
                    notices.indices.forEach { assertBackgroundStatusGeometry(ui, it) }
                    assertTypographyAndVerticalFit(ui, fontScale)
                    assertCardSpacing(ui, notices.size)
                    assertEquals(timelineUi.titles.map { it.bounds }, ui.titles.map { it.bounds })
                    assertEquals(timelineUi.clocks.map { it.bounds }, ui.clocks.map { it.bounds })
                    assertEquals(timelineUi.statuses.map { it.bounds }, ui.statuses.map { it.bounds })
                    assertEquals(timelineUi.cards.map { it.bounds }, ui.cards.map { it.bounds })
                }
            }
        }
    }

    private fun assertBackgroundStatusGeometry(ui: MeasuredNotification, index: Int = 0) {
        val card = ui.cards[index]
        val header = ui.headers[index]
        val title = ui.titles[index]
        val clock = ui.clocks[index]
        val status = ui.statuses[index]
        assertTrue("The title must keep a visible measured slot", title.bounds.width > 0f && title.bounds.height > 0f)
        assertTrue("Time must retain its own non-overlapping slot", title.bounds.right + 8f <= clock.bounds.left + 0.01f)
        assertFullTextFits(clock)
        assertTrue(clock.bounds.right <= card.bounds.right - 8f + 0.01f)
        assertEquals("The status must sit below the complete header", header.bounds.bottom + 2f, status.bounds.top, 0.01f)
        assertTrue(status.bounds.top >= maxOf(title.bounds.bottom, clock.bounds.bottom))
        assertEquals(title.bounds.left, status.bounds.left, 0.01f)
        assertTrue(status.bounds.right <= card.bounds.right - 8f + 0.01f)
        assertTrue(status.bounds.bottom <= card.bounds.bottom - 6f + 0.01f)
        val layout = requireNotNull(status.layout)
        assertEquals(Int.MAX_VALUE, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Clip, layout.layoutInput.overflow)
        assertTrue(layout.layoutInput.softWrap)
        assertCompleteStatusFits(status)
    }

    private fun assertCompleteStatusFits(node: MeasuredNode) {
        val layout = requireNotNull(node.layout)
        assertTrue("The status must have nonzero measured visible bounds", node.bounds.width > 0f && node.bounds.height > 0f)
        assertTrue(layout.lineCount > 0)
        assertEquals("The last visible line must reach the end, including the exit code",
            node.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        assertTrue(layout.multiParagraph.height <= layout.size.height + 0.01f)
        assertTrue(node.bounds.width >= layout.size.width - 0.01f)
        assertTrue(node.bounds.height >= layout.size.height - 0.01f)
        if (layout.lineCount == 1) assertFullTextFits(node)
        for (line in 0 until layout.lineCount) {
            assertTrue("Each status/exit line must retain nonzero measured height",
                layout.getLineBottom(line) > layout.getLineTop(line))
            assertTrue("No status/exit line may escape its measured text slot",
                layout.getLineLeft(line) >= -0.01f && layout.getLineRight(line) <= node.bounds.width + 0.01f &&
                    layout.getLineTop(line) >= -0.01f && layout.getLineBottom(line) <= node.bounds.height + 0.01f)
        }
    }

    private fun metadata(kind: String, status: String) = SubAgentNotificationMetadata(
        ordinal = 0, kind = kind, title = "Coder", sourceTitle = "Coder", status = status,
        summary = "Original summary", body = "Public summary", raw = "fixture",
    )

    private fun assertTitleTimeAndDetailGeometry(ui: MeasuredNotification, time: String) {
        val title = ui.titles.single()
        val detail = ui.details.single()
        val clock = ui.texts.single { it.text == time }
        assertTrue("The title must retain visible width", title.bounds.width > 0f)
        assertTrue("Time must never overlap the title", title.bounds.right + 8f <= clock.bounds.left + 0.01f)
        assertTrue(clock.bounds.right <= ui.cards.single().bounds.right)
        assertFullTextFits(clock)
        assertFalse(clock.layout!!.isLineEllipsized(0))
        assertTrue(detail.bounds.top >= maxOf(title.bounds.bottom, clock.bounds.bottom))
        assertEquals(title.bounds.left, detail.bounds.left, 0.01f)
    }

    private fun assertCardSpacing(ui: MeasuredNotification, expectedCount: Int, expectedHeight: Float? = null) {
        assertEquals(expectedCount, ui.cards.size)
        assertEquals("No old carrier top padding is allowed", ui.bounds.top, ui.cards.first().bounds.top, 0.01f)
        ui.cards.forEach { card ->
            expectedHeight?.let { assertEquals("Regular notification card height", it, card.bounds.height, 0.01f) }
        }
        ui.cards.zipWithNext().forEach { (first, second) ->
            assertEquals("Exactly one centralized 8dp gap", 8f, second.bounds.top - first.bounds.bottom, 0.01f)
        }
        assertEquals("No additional carrier footer is allowed", 8f, ui.bounds.bottom - ui.cards.last().bounds.bottom, 0.01f)
    }

    private fun assertTypographyAndVerticalFit(
        ui: MeasuredNotification,
        fontScale: Float,
        requireNonZeroText: Boolean = true,
    ) {
        ui.texts.forEach { text ->
            val layout = requireNotNull(text.layout)
            if (requireNonZeroText) {
                assertTrue("REALISTIC text must have nonzero layout height", layout.size.height > 0)
                assertTrue("REALISTIC text must retain nonzero visible height", text.bounds.height > 0f)
            }
            assertEquals(if (text.tag == "chat_notification_title") ui.titleFontSize else ui.detailFontSize,
                layout.layoutInput.style.fontSize)
            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0f)
            assertTrue("The measured paragraph must fit vertically: ${text.text}",
                layout.multiParagraph.height <= layout.size.height + 0.01f)
            assertTrue("The full layout must fit its text bounds", text.bounds.height >= layout.size.height - 0.01f)
            val card = ui.cards.single { text.bounds.center.y in it.bounds.top..it.bounds.bottom }
            when (text.tag) {
                "chat_notification_title" -> {
                    val maxLines = if (ui.statuses.any { it.bounds.center.y in card.bounds.top..card.bounds.bottom }) 2 else 1
                    assertEquals(maxLines, layout.layoutInput.maxLines)
                    assertTrue(layout.lineCount in 1..maxLines)
                }
                "chat_notification_status" -> assertTrue(layout.lineCount >= 1)
                else -> assertEquals(1, layout.lineCount)
            }
            assertTrue("Top padding must survive", text.bounds.top >= card.bounds.top + 6f - 0.01f)
            assertTrue("Bottom padding must survive", text.bounds.bottom <= card.bounds.bottom - 6f + 0.01f)
        }
    }

    private fun assertFullTextFits(node: MeasuredNode) {
        val layout = requireNotNull(node.layout)
        val description = "text=${node.text}, intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, size=${layout.size}, bounds=${node.bounds}"
        assertTrue(description, layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.size.width + 0.01f)
        assertTrue(description, layout.multiParagraph.height <= layout.size.height + 0.01f)
        assertTrue(description, node.bounds.width >= layout.size.width - 0.01f)
        assertTrue(description, node.bounds.height >= layout.size.height - 0.01f)
    }

    private data class MeasuredNode(val tag: String?, val text: String, val bounds: Rect, val layout: TextLayoutResult?)

    private data class MeasuredNotification(
        val nodes: List<MeasuredNode>, val bounds: Rect, val titleFontSize: TextUnit, val detailFontSize: TextUnit,
        val strings: Map<Int, String>, val agentMessageTemplate: (String) -> String,
    ) {
        val cards get() = nodes.filter { it.tag == "chat_notification_card" }.sortedBy { it.bounds.top }
        val titles get() = nodes.filter { it.tag == "chat_notification_title" }.sortedBy { it.bounds.top }
        val details get() = nodes.filter { it.tag == "chat_notification_detail" }.sortedBy { it.bounds.top }
        val statuses get() = nodes.filter { it.tag == "chat_notification_status" }.sortedBy { it.bounds.top }
        val headers get() = nodes.filter { it.tag == "chat_notification_header" }.sortedBy { it.bounds.top }
        val clocks get() = nodes.filter { it.tag == "chat_notification_time" }.sortedBy { it.bounds.top }
        val texts get() = nodes.filter { it.layout != null }
        fun string(id: Int): String = strings.getValue(id)
        fun activityTitle(sender: String): String = agentMessageTemplate(sender)
        fun taskStatus(status: Int, exit: String?): String = String.format(
            string(R.string.chat_notification_background_task_status_with_exit), string(status),
            exit ?: string(R.string.chat_notification_background_task_exit_unknown),
        )
    }

    private fun measure(width: Int = 400, fontScale: Float = 1f, content: @Composable () -> Unit): MeasuredNotification {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        lateinit var bounds: Rect
        var titleSize = TextUnit.Unspecified
        var detailSize = TextUnit.Unspecified
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = false) {
                        titleSize = MaterialTheme.typography.bodySmall.fontSize
                        detailSize = MaterialTheme.typography.labelSmall.fontSize
                        Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) { content() }
                    }
                }
            }
            activity.setContentView(view)
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(4_000, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            val root = view.getChildAt(0) as ViewRootForTest
            root.measureAndLayoutForTest()
            val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).map { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { assertTrue(it(layouts)) }
                // LEGACY 的零高文本会被裁切为 Rect.Zero，语义位置仍是有效布局坐标。
                val nodeBounds = if (layouts.singleOrNull()?.size?.height == 0) {
                    Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
                } else node.boundsInRoot
                MeasuredNode(node.config.getOrNull(SemanticsProperties.TestTag),
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty(),
                    nodeBounds, layouts.singleOrNull())
            }
            val strings = listOf(R.string.chat_notification_agent_completed, R.string.chat_notification_agent_failed,
                R.string.chat_notification_agent_stopped, R.string.chat_notification_background_task_completed,
                R.string.chat_notification_background_task_failed, R.string.chat_notification_background_task_stopped,
                R.string.chat_notification_background_task_status_unknown, R.string.chat_notification_background_task_exit_unknown,
                R.string.chat_notification_background_task_status_with_exit,
                R.string.chat_notification_background_task_default_name).associateWith { activity.getString(it) }
            val template = activity.getString(R.string.chat_notification_agent_message)
            return MeasuredNotification(nodes, bounds, titleSize, detailSize, strings) { sender -> String.format(template, sender) }
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
