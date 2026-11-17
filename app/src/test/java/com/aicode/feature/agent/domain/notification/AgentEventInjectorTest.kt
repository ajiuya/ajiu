package com.aicode.feature.agent.domain.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知搭车注入器：合法 JSON 结果注入顶层 `notifications` 字段且仍可解析；
 * 非 JSON 结果退化为文本追加，保证不丢事件。
 */
class AgentEventInjectorTest {

    private val injector = DefaultAgentEventInjector()

    private val notification = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = "term-1",
        title = "test",
        outcome = NotificationOutcome.COMPLETED,
        command = "pwd",
        exitCode = 0
    )

    @Test
    fun inject_validJson_addsNotificationsFieldAndKeepsOriginal() {
        val raw = """{"success":true,"data":{"message":"ok"}}"""

        val injected = injector.inject(raw, listOf(notification))
        val obj = Json.parseToJsonElement(injected).jsonObject

        assertEquals("ok", (obj["data"] as JsonObject)["message"]!!.jsonPrimitive.content)
        val notifications = obj["notifications"] as JsonArray
        assertEquals(1, notifications.size)
        assertEquals("background_task", (notifications[0] as JsonObject)["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun inject_notificationWithDetail_keepsDetailAndOriginalToolData() {
        val raw = """{"status":"success","data":"普通工具结果","images":[],"extra":{"request_id":"tool-1"}}"""
        val detail = "后台任务完成"
        val event = notification.copy(detail = detail)

        val injected = injector.inject(raw, listOf(event))
        val obj = Json.parseToJsonElement(injected).jsonObject

        assertEquals(Json.parseToJsonElement(raw).jsonObject, obj.filterKeys { it != "notifications" })
        assertEquals("普通工具结果", obj["data"]!!.jsonPrimitive.content)
        val notifications = obj["notifications"] as JsonArray
        assertEquals(1, notifications.size)
        val result = notifications[0].jsonObject
        assertEquals("background_task", result["kind"]!!.jsonPrimitive.content)
        assertEquals(detail, result["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun inject_nonJson_fallsBackToTextAppend() {
        val raw = "tool output is plain text"

        val injected = injector.inject(raw, listOf(notification))

        assertTrue(injected.startsWith(raw))
        assertTrue(injected.contains("<task-notification>"))
        assertTrue(injected.contains("<task-id>term-1</task-id>"))
    }

    @Test
    fun inject_emptyEvents_returnsRaw() {
        val raw = """{"success":true}"""

        assertEquals(raw, injector.inject(raw, emptyList()))
    }
}
