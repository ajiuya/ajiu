package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.domain.notification.AgentMessageAction
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry
import com.aicode.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.ChatNotificationTimelineEvent
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationRowTest {
    @Test
    fun backgroundTaskUsesTerminalTitleAndHidesCommandPath() {
        val model = notificationRowModel(
            SubAgentNotificationMetadata(
                ordinal = 0,
                kind = "background_task",
                taskTitle = "重跑 APK 预览操作回归",
                command = "/root/workspace/ajiu-workbench && ./gradlew assembleArmsoloDebug",
                exitCode = "0",
                status = "completed",
                raw = "task",
            ),
            time = "01:23",
        )

        assertEquals("", model.source)
        assertEquals("重跑 APK 预览操作回归", model.title)
        assertEquals("", model.detail)
        assertTrue(model.isBackgroundTask)
        assertEquals("0", model.backgroundTaskExitCode)
        assertEquals(NotificationOutcome.COMPLETED, model.backgroundTaskOutcome)
        assertEquals("01:23", model.time)
        assertEquals(ChatNotificationTone.TASK_SUCCESS, model.tone)
    }

    @Test
    fun backgroundTaskKeepsPayloadStatusIndependentOfKnownAndUnknownExitCodes() {
        val outcomes = mapOf(
            "completed" to NotificationOutcome.COMPLETED,
            "failed" to NotificationOutcome.FAILED,
            "stopped" to NotificationOutcome.STOPPED,
            "unrecognized" to null,
            null to null,
        )
        for ((status, outcome) in outcomes) {
            for (exit in listOf("0", "23", "", "unknown")) {
                val model = notificationRowModel(
                    SubAgentNotificationMetadata(
                        ordinal = 0, kind = "background_task", title = "Fallback task title", taskTitle = "",
                        status = status, exitCode = exit, command = "private command", detail = "private detail",
                        tailOutput = "private output", raw = "task",
                    ),
                )

                assertTrue(model.isBackgroundTask)
                assertEquals("Fallback task title", model.title)
                assertEquals("", model.detail)
                assertEquals(exit.takeIf(String::isNotBlank), model.backgroundTaskExitCode)
                assertEquals(outcome, model.backgroundTaskOutcome)
                assertEquals(if (status == "completed") ChatNotificationTone.TASK_SUCCESS
                    else ChatNotificationTone.TASK_FAILURE, model.tone)
                assertEquals(null, model.agentOutcome)
                assertEquals(null, model.agentSender)
            }
        }
    }

    @Test
    fun timelineXmlAndJsonBackgroundTasksPreserveTheSameTitleStatusAndNullableExitCode() {
        val injector = DefaultAgentEventInjector()
        for (outcome in NotificationOutcome.entries) {
            for (exit in listOf(0, 23, null)) {
                val pending = PendingNotification(
                    kind = AgentNotificationKind.BACKGROUND_TASK, sourceId = "term-layout",
                    title = "Build & verify <notification> layout", outcome = outcome, exitCode = exit,
                    command = "private command", detail = "private detail", tailOutput = "private output",
                    eventId = "layout-${outcome.name}-$exit",
                )
                val timestamp = 120_000L
                val timelineModel = requireNotNull(notificationTimelineItem(pending, timestamp).notification)
                val expectedStatus = when (outcome) {
                    NotificationOutcome.COMPLETED -> "completed"
                    NotificationOutcome.FAILED -> "failed"
                    NotificationOutcome.STOPPED -> "stopped"
                }
                for ((raw, toolResult) in listOf(
                    injector.inject("", listOf(pending)) to false,
                    injector.inject("{\"status\":\"success\"}", listOf(pending)) to true,
                )) {
                    val parsed = parseSubAgentNotificationMetadata(raw, toolResult).single()
                    assertEquals("background_task", parsed.kind)
                    assertEquals(pending.eventId, parsed.eventId)
                    assertEquals(pending.sourceId, parsed.sourceId)
                    assertEquals(pending.title, parsed.taskTitle)
                    assertEquals(expectedStatus, parsed.status)
                    assertEquals(exit?.toString().orEmpty(), parsed.exitCode)
                    assertEquals(pending.command, parsed.command)
                    assertEquals(pending.detail, parsed.detail)
                    assertEquals(pending.tailOutput, parsed.tailOutput)
                    assertEquals(timelineModel, notificationRowModel(parsed, formatClockTime(timestamp)))
                }
                assertEquals(pending.title, timelineModel.title)
                assertEquals(outcome, timelineModel.backgroundTaskOutcome)
                assertEquals(exit?.toString(), timelineModel.backgroundTaskExitCode)
                assertEquals("", timelineModel.detail)
            }
        }
    }

    @Test
    fun xmlAndJsonUnknownStatusesDoNotInventSuccessOrExitCodeZero() {
        for ((status, exit) in listOf(null to null, "unrecognized" to null, "unrecognized" to "0")) {
            val xmlStatus = status?.let { "<status>$it</status>" }.orEmpty()
            val xmlExit = exit?.let { "<exit-code>$it</exit-code>" }.orEmpty()
            val jsonStatus = status?.let { ",\"status\":\"$it\"" }.orEmpty()
            val jsonExit = exit?.let { ",\"exit_code\":$it" }.orEmpty()
            val payloads = listOf(
                "[系统通知 - 非用户输入]\n<task-notification><title>Unknown task</title>$xmlStatus$xmlExit</task-notification>" to false,
                """{"status":"success","notifications":[{"kind":"background_task","title":"Unknown task"$jsonStatus$jsonExit}]}""" to true,
            )
            for ((raw, toolResult) in payloads) {
                val parsed = parseSubAgentNotificationMetadata(raw, toolResult).single()
                val model = notificationRowModel(parsed)
                assertEquals("background_task", parsed.kind)
                assertEquals(status, parsed.status)
                assertEquals(exit.orEmpty(), parsed.exitCode)
                assertEquals("Unknown task", model.title)
                assertEquals(null, model.backgroundTaskOutcome)
                assertEquals(exit, model.backgroundTaskExitCode)
                assertEquals(ChatNotificationTone.TASK_FAILURE, model.tone)
            }
        }
    }

    @Test
    fun agentMessageUsesSenderAsSourceAndBodyAsDetail() {
        val model = notificationRowModel(
            SubAgentNotificationMetadata(
                ordinal = 0,
                kind = "agent_message",
                sourceTitle = "编码员",
                title = "编码员",
                body = "已完成 TerminalSession 通知合并修复，测试通过",
                status = "message",
                raw = "message",
            )
        )

        assertEquals("编码员", model.source)
        assertEquals("编码员发来一条消息", model.title)
        assertEquals("已完成 TerminalSession 通知合并修复，测试通过", model.detail)
        assertEquals(ChatNotificationTone.AGENT, model.tone)
        assertEquals("编码员", model.agentSender)
        assertEquals(null, model.agentOutcome)
    }

    @Test
    fun subAgentResultsKeepOriginalSummaryAndExposeTheirRealOutcomeSeparately() {
        for ((status, outcome) in listOf(
            "completed" to NotificationOutcome.COMPLETED,
            "failed" to NotificationOutcome.FAILED,
            "stopped" to NotificationOutcome.STOPPED,
        )) {
            val summary = "原始执行摘要：$status"
            val model = notificationRowModel(
                SubAgentNotificationMetadata(
                    ordinal = 0, kind = "subagent", title = "测试员", sourceTitle = "旧发送者",
                    summary = summary, status = status, body = "真实结果正文", raw = "result",
                ),
                time = "17:42",
            )

            assertEquals("测试员", model.source)
            assertEquals("测试员", model.agentSender)
            assertEquals(outcome, model.agentOutcome)
            assertEquals(summary, model.title)
            assertEquals("真实结果正文", model.detail)
            assertEquals("17:42", model.time)
            assertEquals(if (outcome == NotificationOutcome.COMPLETED) ChatNotificationTone.AGENT
                else ChatNotificationTone.TASK_FAILURE, model.tone)
        }
    }

    @Test
    fun timelineSubAgentResultsDoNotTurnFailureOrStopIntoCompletionOrLoseTheOriginalSummary() {
        val summaries = mapOf(
            NotificationOutcome.COMPLETED to "子代理「审核员」已执行完成",
            NotificationOutcome.FAILED to "子代理「审核员」已执行失败",
            NotificationOutcome.STOPPED to "子代理「审核员」已停止",
        )
        for ((outcome, summary) in summaries) {
            val item = notificationTimelineItem(
                PendingNotification(
                    AgentNotificationKind.SUBAGENT, "reviewer", "审核员", outcome,
                    resultExcerpt = "原始结果", eventId = "reviewer-${outcome.name}",
                ),
                timestamp = 120_000L,
            )
            val model = requireNotNull(item.notification)

            assertEquals("chat-notification:reviewer-${outcome.name}", item.key)
            assertEquals("审核员", model.agentSender)
            assertEquals(outcome, model.agentOutcome)
            assertEquals(summary, model.title)
            assertEquals("原始结果", model.detail)
            assertEquals(formatClockTime(120_000L), model.time)
        }
    }

    @Test
    fun plainAgentMessagesUseTheirSenderWithoutInventingAnExecutionOutcome() {
        for ((fromParent, source, sender) in listOf(
            Triple(false, "探索员", "探索员"),
            Triple(true, "探索员", "主代理"),
            Triple(false, "", "子代理"),
        )) {
            val model = notificationRowModel(
                SubAgentNotificationMetadata(
                    ordinal = 0, kind = "agent_message", sourceTitle = source, title = source,
                    action = AgentMessageAction.MESSAGE, fromParent = fromParent,
                    status = "message", body = "消息正文", raw = "message",
                ),
            )

            assertEquals(sender, model.agentSender)
            assertEquals(sender, model.source)
            assertEquals("${sender}发来一条消息", model.title)
            assertEquals(null, model.agentOutcome)
            assertEquals("消息正文", model.detail)
            assertEquals(ChatNotificationTone.AGENT, model.tone)
        }
    }

    @Test
    fun parentInstructionAndCorrectionUseCommandToneAndTargetAgent() {
        val instruction = notificationRowModel(
            SubAgentNotificationMetadata(
                ordinal = 0,
                kind = "agent_message",
                action = AgentMessageAction.ADD_INSTRUCTION,
                targetTitle = "探索员",
                body = "继续核对 Handler 消息积压风险",
                fromParent = true,
                raw = "instruction",
            )
        )
        val correction = notificationRowModel(
            SubAgentNotificationMetadata(
                ordinal = 1,
                kind = "agent_message",
                action = AgentMessageAction.CORRECTION,
                targetTitle = "探索员",
                body = "将列宽 resize 峰值与真实泄漏分开报告",
                fromParent = true,
                raw = "correction",
            )
        )

        assertEquals("给探索员代理添加指令", instruction.title)
        assertEquals("给探索员代理发送纠偏", correction.title)
        assertEquals(ChatNotificationTone.COMMAND, instruction.tone)
        assertEquals(ChatNotificationTone.COMMAND, correction.tone)
        assertEquals("主代理", instruction.source)
        assertEquals("主代理", correction.source)
        assertEquals(null, instruction.agentSender)
        assertEquals(null, correction.agentSender)
        assertEquals(null, instruction.agentOutcome)
        assertEquals(null, correction.agentOutcome)
        assertEquals("继续核对 Handler 消息积压风险", instruction.detail)
        assertEquals("将列宽 resize 峰值与真实泄漏分开报告", correction.detail)
    }

    @Test
    fun parentInstructionEventsAreInsertedInTimestampOrder() {
        val items = listOf(
            ChatRenderItem(
                message = com.aicode.feature.agent.presentation.AgentUIMessage(
                    id = "message", role = com.aicode.feature.agent.presentation.MessageRole.ASSISTANT,
                    content = "reply", timestamp = 100L,
                ),
                key = "message", contentType = "ASSISTANT",
            )
        )
        val events = listOf(
            SubAgentInteractionMetadata(
                id = "event-2", parentSessionId = "parent", subSessionId = "child",
                kind = "MESSAGE_FROM_PARENT", timestamp = 200L, origin = "PARENT",
                sourceMessageId = null, title = "继续排查", model = null, preview = "second",
                agentName = "探索员",
            ),
            SubAgentInteractionMetadata(
                id = "event-1", parentSessionId = "parent", subSessionId = "child",
                kind = "MESSAGE_FROM_PARENT", timestamp = 150L, origin = "USER",
                sourceMessageId = null, title = "继续排查", model = null, preview = "first",
                agentName = "探索员",
            ),
        )

        val result = projectChatNotificationItems(items, listOf(items.single().message), emptyList(), events, emptyMap())

        assertEquals(listOf("message", "chat-notification:event-1", "chat-notification:event-2"), result.map { it.key })
        assertEquals("给探索员代理添加指令", result[1].notification?.title)
        assertEquals("给探索员代理发送纠偏", result[2].notification?.title)
        assertTrue(result.all { it.contentType == "ASSISTANT" || it.contentType == CHAT_NOTIFICATION_CONTENT_TYPE })
    }

    @Test
    fun outboxNoticeAndToolResultCarrierRenderOnlyOneIndependentRow() {
        val notice = PendingNotification(
            kind = AgentNotificationKind.BACKGROUND_TASK,
            sourceId = "term-1",
            title = "Build",
            outcome = NotificationOutcome.COMPLETED,
            command = "./gradlew test",
            exitCode = 0,
            eventId = "stable-event",
        )
        val tool = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "tool", role = com.aicode.feature.agent.presentation.MessageRole.TOOL,
            content = DefaultAgentEventInjector().inject("{\"status\":\"success\"}", listOf(notice)),
            timestamp = 120L, toolName = "bash",
        )
        val messageItem = ChatRenderItem(tool, "tool", "TOOL")
        val event = ChatNotificationTimelineEvent(
            entry = com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(1, 110L, notice),
            sourceMessageId = null,
        )

        val result = projectChatNotificationItems(listOf(messageItem), listOf(tool), listOf(event), emptyList(), emptyMap())

        assertEquals(1, result.count { it.notification != null })
        assertEquals("chat-notification:stable-event", result.single { it.notification != null }.key)
        assertTrue(result.any { it.key == "tool" })
    }

    @Test
    fun duplicateLegacyToolCarriersAreCollapsedByContentSignature() {
        val raw = """{"status":"success","notifications":[{"kind":"background_task","task_id":"term-1","title":"Build","command":"make","exit_code":0,"status":"completed","summary":"finished"}]}"""
        val first = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "tool-1", role = com.aicode.feature.agent.presentation.MessageRole.TOOL,
            content = raw, timestamp = 100L, toolName = "bash",
        )
        val second = first.copy(id = "tool-2", timestamp = 200L)

        val result = projectChatNotificationItems(
            listOf(ChatRenderItem(first, first.id, "TOOL"), ChatRenderItem(second, second.id, "TOOL")),
            listOf(first, second), emptyList(), emptyList(), emptyMap(),
        )

        assertEquals(1, result.count { it.notification != null })
        val row = result.single { it.notification != null }
        assertEquals("chat-notification:legacy:100:0:term-1", row.key)
        assertEquals("Build", row.notification?.title)
        assertEquals("", row.notification?.detail)
        assertEquals("0", row.notification?.backgroundTaskExitCode)
        assertEquals(NotificationOutcome.COMPLETED, row.notification?.backgroundTaskOutcome)
    }

    @Test
    fun notificationPopulatesAnOtherwiseEmptyChatList() {
        val notice = PendingNotification(
            kind = AgentNotificationKind.BACKGROUND_TASK,
            sourceId = "term-empty", title = "compile", outcome = NotificationOutcome.COMPLETED,
            eventId = "empty-session-event",
        )
        val timeline = ChatNotificationTimelineEvent(
            com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(1, 200L, notice), null,
        )

        val result = projectChatNotificationItems(emptyList(), emptyList(), listOf(timeline), emptyList(), emptyMap())

        assertEquals(listOf("chat-notification:empty-session-event"), result.map { it.key })
    }

    @Test
    fun acceptedUserInterjectionStaysBeforeLaterAssistantMessages() {
        val before = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "before", role = com.aicode.feature.agent.presentation.MessageRole.TOOL,
            content = "tool result", timestamp = 100L,
        )
        val after = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "after", role = com.aicode.feature.agent.presentation.MessageRole.ASSISTANT,
            content = "later response", timestamp = 300L,
        )
        val notice = PendingNotification(
            kind = AgentNotificationKind.USER_MESSAGE,
            sourceId = "request-1", title = "", outcome = NotificationOutcome.COMPLETED,
            message = "请先检查这个结果", eventId = "interjection",
        )
        val timeline = ChatNotificationTimelineEvent(
            com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(1, 200L, notice), null,
        )

        val result = projectChatNotificationItems(
            listOf(ChatRenderItem(before, before.id, "TOOL"), ChatRenderItem(after, after.id, "ASSISTANT")),
            listOf(before, after), listOf(timeline), emptyList(), emptyMap(),
        )

        assertEquals(listOf("before", "chat-notification:interjection", "after"), result.map { it.key })
    }

    @Test
    fun collapsedTurnNoticeKeepsItsEventPositionWhenItsToolResultArrives() {
        val session = "parent"
        val user = AgentUIMessage("user", MessageRole.USER, "start", timestamp = 10L)
        val turn = turnKeyOf(user.id)
        val dispatch = AgentUIMessage("dispatch", MessageRole.TOOL, "child started", timestamp = 20L)
        val tool = AgentUIMessage("tool", MessageRole.TOOL, "running", timestamp = 30L)
        val answer = AgentUIMessage("answer", MessageRole.ASSISTANT, "finished", timestamp = 60L)
        val next = AgentUIMessage("next", MessageRole.USER, "next task", timestamp = 70L)
        val capsule = SubAgentTurnCapsule(turn, SubAgentTimelineCapsule("child", dispatch.id), setOf(dispatch.id), session)
        val notice = PendingNotification(
            AgentNotificationKind.AGENT_MESSAGE, "child", "探索员", NotificationOutcome.COMPLETED,
            message = "检查完毕", eventId = "child-reply",
        )
        val timeline = listOf(ChatNotificationTimelineEvent(AgentNotificationTimelineEntry(1, 40L, notice), null))
        val noticeKey = "chat-notification:child-reply"

        val pending = listOf(user, dispatch, tool)
        val activeBase = buildChatItems(pending, activeTurnKey = turn)
        val activeWithNotices = projectChatNotificationItems(activeBase, pending, timeline, emptyList(), emptyMap())
        val activeItems = insertSubAgentTurnItems(flattenChatProcesses(activeWithNotices), listOf(capsule), pending)
        val active = projectSubAgentGroup(activeItems, session, activeTurnKey = turn)
        assertEquals(listOf(user.id, dispatch.id, tool.id, noticeKey), active.bodyItems.map { it.key })

        val written = tool.copy(content = DefaultAgentEventInjector().inject("{\"status\":\"success\"}", listOf(notice)))
        val finished = listOf(user, dispatch, written, answer, next)
        val finishedBase = buildChatItems(finished)
        val finishedWithNotices = projectChatNotificationItems(finishedBase, finished, timeline, emptyList(), emptyMap())
        val finishedItems = insertSubAgentTurnItems(flattenChatProcesses(finishedWithNotices), listOf(capsule), finished)
        val history = projectSubAgentGroup(finishedItems, session)
        val groupKey = active.footerItems.single().key
        assertEquals(listOf(user.id, "$turn#header", answer.id, groupKey, next.id), history.bodyItems.map { it.key })
        assertTrue(history.footerItems.isEmpty())

        val expandedBase = buildChatItems(finished, turnOverrides = mapOf(turn to true))
        val expandedWithNotices = projectChatNotificationItems(expandedBase, finished, timeline, emptyList(), emptyMap())
        val expandedItems = insertSubAgentTurnItems(flattenChatProcesses(expandedWithNotices), listOf(capsule), finished)
        val expanded = projectSubAgentGroup(expandedItems, session)
        assertTrue(expanded.bodyItems.indexOfFirst { it.key == noticeKey } in
            expanded.bodyItems.indexOfFirst { it.key == "$turn#header" } until expanded.bodyItems.indexOfFirst { it.key == answer.id })
    }

    @Test
    fun notificationFoldsWithItsTurnAndReturnsWhenExpanded() {
        val background = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "background", role = com.aicode.feature.agent.presentation.MessageRole.USER,
            content = "[系统通知 - 非用户输入]", timestamp = 200L, isBackgroundNotification = true,
        )
        val sourceUserMessage = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "user-supplement", role = com.aicode.feature.agent.presentation.MessageRole.USER,
            content = "请检查这个结果", timestamp = 210L,
        )
        val header = ChatRenderItem(
            message = background,
            key = "turn:user#header",
            contentType = "turn-header",
            turnHeader = TurnHeaderState("turn:user", null, false, false),
            turnProcess = listOf(
                ChatRenderItem(background, "background", "USER", chatTurnKey = "turn:user"),
                ChatRenderItem(sourceUserMessage, sourceUserMessage.id, "USER", chatTurnKey = "turn:user"),
            ),
            chatTurnKey = "turn:user",
        )
        val taskNotice = PendingNotification(
            kind = AgentNotificationKind.BACKGROUND_TASK,
            sourceId = "term", title = "compile", outcome = NotificationOutcome.FAILED,
            eventId = "collapsed-event",
        )
        val userNotice = PendingNotification(
            kind = AgentNotificationKind.USER_MESSAGE,
            sourceId = "request", title = "", outcome = NotificationOutcome.COMPLETED,
            message = sourceUserMessage.content, eventId = "collapsed-user-event",
        )
        val timeline = listOf(
            ChatNotificationTimelineEvent(
                com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(1, 200L, taskNotice), background.id,
            ),
            ChatNotificationTimelineEvent(
                com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(2, 210L, userNotice), sourceUserMessage.id,
            ),
        )

        val collapsed = flattenChatProcesses(projectChatNotificationItems(
            listOf(header), listOf(background), timeline, emptyList(), emptyMap(),
        ))
        assertFalse(collapsed.any { it.key == "chat-notification:collapsed-event" })
        assertFalse(collapsed.any { it.key == "chat-notification:collapsed-user-event" })

        val expandedHeader = header.copy(turnHeader = header.turnHeader!!.copy(expanded = true))
        val expanded = flattenChatProcesses(projectChatNotificationItems(
            listOf(expandedHeader), listOf(background), timeline, emptyList(), emptyMap(),
        ))
        assertTrue(expanded.any { it.key == "chat-notification:collapsed-event" })
        val expandedUserNotice = expanded.single { it.key == "chat-notification:collapsed-user-event" }
        assertEquals(sourceUserMessage.content, expandedUserNotice.notification?.detail)
        assertFalse(expanded.any { it.key == sourceUserMessage.id })
    }

    @Test
    fun oldTimelineEventsOutsideTheLoadedMessageWindowStayOutOfTheTop() {
        val user = AgentUIMessage("user", MessageRole.USER, "current", timestamp = 100L)
        val tool = AgentUIMessage("tool", MessageRole.TOOL, "result", timestamp = 200L)
        val oldNotice = PendingNotification(
            AgentNotificationKind.BACKGROUND_TASK, "term", "old compile", NotificationOutcome.COMPLETED,
            eventId = "outside-window",
        )
        val event = ChatNotificationTimelineEvent(
            AgentNotificationTimelineEntry(1, 20L, oldNotice), sourceMessageId = null,
        )
        val eventWithUnloadedSource = ChatNotificationTimelineEvent(
            AgentNotificationTimelineEntry(2, 30L, oldNotice.copy(eventId = "outside-window-with-source")),
            sourceMessageId = "unloaded-user",
        )
        val base = buildChatItems(listOf(user, tool))

        val result = projectChatNotificationItems(
            base, listOf(user, tool), listOf(event, eventWithUnloadedSource), emptyList(), emptyMap(),
        )

        assertFalse(result.any { it.key == "chat-notification:outside-window" })
        assertFalse(result.any { it.key == "chat-notification:outside-window-with-source" })
    }

    @Test
    fun unrecognizedBackgroundNotificationMessageRemainsVisible() {
        val legacy = AgentUIMessage(
            id = "legacy-unknown", role = MessageRole.USER,
            content = "[系统通知 - 非用户输入]\\n<custom-notification><payload>old event</payload></custom-notification>",
            timestamp = 100L, isBackgroundNotification = true,
        )

        val result = projectChatNotificationItems(
            listOf(ChatRenderItem(legacy, legacy.id, "USER")), listOf(legacy), emptyList(), emptyList(), emptyMap(),
        )

        assertEquals(listOf(legacy.id), result.map { it.key })
    }

    @Test
    fun mixedRecognizedAndUnknownBackgroundNoticesKeepTheirOriginalMessage() {
        val legacy = AgentUIMessage(
            id = "legacy-mixed", role = MessageRole.USER,
            content = """[系统通知 - 非用户输入]
                <task-notification><event-id>mixed-event</event-id><title>Build</title><status>completed</status><summary>finished</summary></task-notification>
                <custom-notification><payload>old event</payload></custom-notification>""".trimIndent(),
            timestamp = 100L, isBackgroundNotification = true,
        )
        val timeline = listOf(
            ChatNotificationTimelineEvent(
                AgentNotificationTimelineEntry(
                    1, 100L,
                    PendingNotification(
                        AgentNotificationKind.BACKGROUND_TASK, "term", "Build", NotificationOutcome.COMPLETED,
                        eventId = "mixed-event",
                    ),
                ),
                sourceMessageId = null,
            )
        )

        val result = projectChatNotificationItems(
            listOf(ChatRenderItem(legacy, legacy.id, "USER")), listOf(legacy), timeline, emptyList(), emptyMap(),
        )

        assertEquals(listOf(legacy.id), result.map { it.key })
        assertFalse(result.any { it.notification != null })
    }

    @Test
    fun userInterjectionDoesNotDuplicateAnOlderBackgroundNotificationMessage() {
        val text = "先停下，保留现场"
        val old = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "legacy-notice", role = com.aicode.feature.agent.presentation.MessageRole.USER,
            content = """[系统通知 - 非用户输入]
                <user-message><message>$text</message><status>message</status><summary>用户补充了一条消息</summary></user-message>""".trimIndent(),
            timestamp = 300L, isBackgroundNotification = true,
        )
        val notice = PendingNotification(
            kind = AgentNotificationKind.USER_MESSAGE,
            sourceId = "request-1", title = "", outcome = NotificationOutcome.COMPLETED,
            message = text, eventId = "user-event",
        )
        val event = ChatNotificationTimelineEvent(
            com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(2, 301L, notice), null,
        )

        val result = projectChatNotificationItems(listOf(ChatRenderItem(old, old.id, "USER")), listOf(old),
            listOf(event), emptyList(), emptyMap())

        assertEquals(1, result.size)
        assertEquals("chat-notification:user-event", result.single().key)
    }

    @Test
    fun userInterjectionTimelineReplacesItsDuplicateBubbleButKeepsOriginalText() {
        val user = com.aicode.feature.agent.presentation.AgentUIMessage(
            id = "user-input", role = com.aicode.feature.agent.presentation.MessageRole.USER,
            content = "先停下，保留现场", timestamp = 300L,
        )
        val notice = PendingNotification(
            kind = AgentNotificationKind.USER_MESSAGE,
            sourceId = "request-1", title = "", outcome = NotificationOutcome.COMPLETED,
            message = user.content, eventId = "user-event",
        )
        val event = ChatNotificationTimelineEvent(
            com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry(2, 301L, notice),
            sourceMessageId = user.id,
        )

        val result = projectChatNotificationItems(
            listOf(ChatRenderItem(user, user.id, "USER")), listOf(user), listOf(event), emptyList(), emptyMap(),
        )

        assertEquals(1, result.size)
        assertEquals("先停下，保留现场", result.single().notification?.detail)
        assertEquals("chat-notification:user-event", result.single().key)
    }
}
