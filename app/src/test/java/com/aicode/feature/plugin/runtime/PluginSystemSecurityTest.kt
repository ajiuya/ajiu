package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSystemSecurityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun http() = PluginHttpService(OkHttpClient())
    private fun request(url: String) = buildJsonObject { put("url", url) }
    private fun secretRepository(path: File = File(temporary.root, "secrets")) = PluginSecretStore(path,
        { "test-encrypted:" + java.util.Base64.getEncoder().encodeToString(it.toByteArray()) },
        { java.util.Base64.getDecoder().decode(it.removePrefix("test-encrypted:")).toString(Charsets.UTF_8) })
    private suspend fun capture(): Pair<PluginManager, PluginCapture> {
        val manager = PluginManager(File(temporary.root, "packages"), File(temporary.root, "host/registry.json"))
        PluginCoreFixtures.install(manager)
        return manager to PluginCoreFixtures.activate(manager)
    }
    private suspend fun denial(block: suspend () -> Any?): String = try { block(); error("Denied operation succeeded") }
        catch (expected: PluginHostException) { expected.code }

    @Test fun httpAcceptsOnlyExplicitHttpEndpointsWithoutUrlCredentials() {
        val service = http()
        assertEquals("GET", service.parseRequest(request("https://example.com/service")).method)
        for (url in listOf("file:///private/key", "content://provider", "javascript:alert(1)", "https://user:password@example.com/")) {
            try { service.parseRequest(request(url)); fail(url) }
            catch (expected: PluginHostException) { assertEquals("PARAM_INVALID", expected.code) }
        }
    }
    @Test fun httpRejectsProtocolHeadersAndHeaderInjection() {
        for ((name, value) in listOf("Host" to "other.example", "Content-Length" to "0", "X-Test" to "a\r\nb")) {
            val params = JsonObject(request("https://example.com/") + ("headers" to buildJsonObject { put(name, value) }))
            try { http().parseRequest(params); fail("Unsafe header") }
            catch (expected: PluginHostException) { assertEquals("PARAM_INVALID", expected.code) }
        }
    }
    @Test fun httpBoundsRequestBodyAndExactIntegerTimeout() {
        val base = request("https://example.com/")
        val valid = JsonObject(base + mapOf("method" to JsonPrimitive("POST"), "body" to JsonPrimitive("{}"), "timeout" to JsonPrimitive(30)))
        assertEquals(30L, http().parseRequest(valid).timeoutSeconds)
        for (other in listOf(JsonObject(valid + ("timeout" to JsonPrimitive("30"))), JsonObject(valid + ("timeout" to JsonPrimitive(301))),
            JsonObject(valid + ("body" to JsonPrimitive("a".repeat(1024 * 1024 + 1)))), JsonObject(valid + ("method" to JsonPrimitive("GET"))))) {
            try { http().parseRequest(other); fail("Invalid HTTP boundary") }
            catch (expected: PluginHostException) { assertEquals("PARAM_INVALID", expected.code) }
        }
    }
    @Test fun encryptedSecretsAreScopedToPluginAndNeverListValues() = runBlocking<Unit> {
        val (_, capture) = capture()
        val secrets = secretRepository()
        secrets.call(capture, "set", "api-key", "private-value")
        assertEquals(mapOf("value" to "private-value"), secrets.call(capture, "get", "api-key"))
        assertEquals(mapOf("api-key" to ""), secrets.call(capture, "list"))
        assertFalse(File(temporary.root, "secrets/${capture.id}.json").readText().contains("private-value"))
        assertEquals(mapOf("value" to ""), secretRepository().call(capture, "get", "not-set"))
        secrets.call(capture, "delete", "api-key")
        assertEquals(mapOf("value" to ""), secrets.call(capture, "get", "api-key"))
    }
    @Test fun secretWritesCheckLiveCaptureAndRejectOversizedValues() = runBlocking<Unit> {
        val (manager, capture) = capture()
        val secrets = secretRepository()
        assertEquals("TOO_LARGE", denial { secrets.call(capture, "set", "key", "x".repeat(65537)) })
        assertEquals("PARAM_INVALID", denial { secrets.call(capture, "set", "invalid\nkey", "value") })
        assertFalse(File(temporary.root, "secrets/${capture.id}.json").exists())
        manager.disable(capture.id)
        try { secrets.call(capture, "set", "key", "value"); fail("Disabled plugin") }
        catch (expected: com.aicode.feature.plugin.domain.PluginCaptureException) { assertEquals("STALE_CAPTURE", expected.error.code.name) }
    }
    @Test fun secretStoreDoesNotFollowAFileSymlinkOutsideItsDirectory() = runBlocking<Unit> {
        val (_, capture) = capture()
        val folder = temporary.newFolder("private-secrets")
        val outside = temporary.newFile("outside.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(folder, "${capture.id}.json").toPath(), outside.toPath())
        try { secretRepository(folder).call(capture, "set", "name", "value"); fail("Symbolic secret store") }
        catch (_: java.io.IOException) { }
        assertEquals("keep", outside.readText())
    }
}
