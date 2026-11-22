package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

class PluginHookTransformTest {
    @Test fun beforeAcceptsBoundedLogsArgumentsDenyAndDelegationButNeverApprovalCommands() = runBlocking<Unit> {
        val changed = requireNotNull(PluginHookTransform.parseBefore(PluginJson.parse("""{"arguments":{"command":"pwd"},"delegate":{"handler":"delegate.run"},"logs":["changed"]}""")))
        assertFalse(changed.denied)
        assertEquals("delegate.run", changed.delegate)
        assertEquals("{\"command\":\"pwd\"}", changed.arguments.toString())
        assertEquals(listOf("changed"), PluginHookTransform.logs(PluginJson.parse("""{"logs":["changed"]}""")))
        assertTrue(requireNotNull(PluginHookTransform.parseBefore(PluginJson.parse("""{"decision":"deny","reason":"not allowed","logs":["denied"]}"""))).denied)
        for (raw in listOf("""{"decision":"approved"}""", """{"decision":true}""", """{"arguments":[]}""",
            """{"delegate":{"handler":"bad;handler"}}""", """{"delegate":{"handler":"run","approve":true}}""",
            """{"logs":[42]}""", """{"resolve":"ALLOW"}""")) assertNull(raw, PluginHookTransform.parseBefore(PluginJson.parse(raw)))
    }
    @Test fun afterOnlyTransformsDataAndDoesNotAcceptStatusImagesOrFailureOverrides() = runBlocking<Unit> {
        assertEquals(JsonNull, PluginHookTransform.parseAfter(PluginJson.parse("""{"data":null,"logs":["done"]}""")))
        assertNull(PluginHookTransform.parseAfter(PluginJson.parse("""{"data":{},"status":"success"}""")))
        assertNull(PluginHookTransform.parseAfter(PluginJson.parse("""{"data":{},"images":[]}""")))
        assertNull(PluginHookTransform.parseAfter(PluginJson.parse("""{"data":{},"error":false}""")))
    }
    @Test fun httpRequiresTypedJsonObjectBodyAndSafeUniqueHeaderNames() = runBlocking<Unit> {
        val valid = requireNotNull(PluginHookTransform.parseHttp(PluginJson.parse("""{"body":"{\"model\":\"new\"}","headers":{"X-Test":"value"},"logs":["changed"]}""")))
        assertEquals("{\"model\":\"new\"}", valid.body)
        for (raw in listOf("""{"body":"[]"}""", """{"body":{}}""", """{"url":false}""",
            """{"headers":{"Host":"evil"}}""", """{"headers":{"Proxy-Connection":"keep-alive"}}""",
            """{"headers":{"X-Test":"one","x-test":"two"}}""", """{"headers":{"X-Bad":"a\r\nb"}}""")) {
            assertNull(raw, PluginHookTransform.parseHttp(PluginJson.parse(raw)))
        }
    }
    @Test fun transformAndLogLimitsRemainBoundedInCharactersAndHttpBytes() = runBlocking<Unit> {
        val logs = (1..51).joinToString(",") { "\"line\"" }
        assertNull(PluginHookTransform.parseBefore(PluginJson.parse("""{"logs":[$logs]}""")))
        assertNull(PluginHookTransform.parseBefore(PluginJson.parse("""{"logs":["${"x".repeat(501)}"]}""")))
        assertNull(PluginHookTransform.parseBefore(PluginJson.parse("""{"arguments":{"data":"${"x".repeat(PluginHookTransform.MAX_JSON_CHARS)}"}}""")))
        val multibyte = "界".repeat(PluginHookTransform.MAX_HTTP_BODY_BYTES / 2)
        assertTrue(multibyte.length < PluginHookTransform.MAX_HTTP_BODY_BYTES)
        val json = kotlinx.serialization.json.buildJsonObject { put("body", kotlinx.serialization.json.JsonPrimitive("{\"text\":\"$multibyte\"}")) }
        assertNull(PluginHookTransform.parseHttp(json))
    }
}
