package com.aicode.feature.plugin.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginHttpHeadersTest {
    private val headers = linkedMapOf("Authorization" to "Bearer private-key", "X-Api-Key" to "another-key",
        "Proxy-Authorization" to "Basic proxy-secret", "Cookie" to "session=cookie-secret; other=short",
        "Set-Cookie" to "new=new-cookie", "X-Copied" to "prefix private-key suffix", "X-Plain" to "public")
    @Test fun hookPayloadRedactsSensitiveHeadersAndCopiedTokensAndCookieValues() = runBlocking<Unit> {
        val redacted = PluginHttpHeaders.redact(headers + ("X-Cookie-Copy" to "cookie-secret"))
        assertEquals("public", redacted["X-Plain"])
        assertTrue(redacted.filterKeys { it != "X-Plain" }.values.all { it == PluginHttpHeaders.PLACEHOLDER })
        assertFalse(redacted.toString().contains("private-key"))
        assertFalse(redacted.toString().contains("cookie-secret"))
    }
    @Test fun sameOriginPreservesRealCredentialsButNeverAcceptsPluginCredentialReplacements() = runBlocking<Unit> {
        val merged = PluginHttpHeaders.merge("https://example.com/v1/chat/completions", headers,
            mapOf("authorization" to "Bearer forged", "x-plain" to "changed", "X-New" to "added", "X-Copied-New" to "private-key"),
            "https://example.com/v2/chat/completions")
        assertEquals("Bearer private-key", merged["Authorization"])
        assertEquals("changed", merged["x-plain"])
        assertFalse(merged.containsKey("X-Plain"))
        assertEquals("added", merged["X-New"])
        assertFalse(merged.containsKey("X-Copied-New"))
        assertFalse(merged.values.contains("Bearer forged"))
    }
    @Test fun crossOriginAlwaysStripsOriginalApiCookieProxyAndCopiedSecrets() = runBlocking<Unit> {
        for (target in listOf("https://other.example/v1", "http://example.com/v1", "https://example.com:8443/v1")) {
            val merged = PluginHttpHeaders.merge("https://example.com/v1", headers,
                mapOf("Authorization" to PluginHttpHeaders.PLACEHOLDER, "X-Forward" to "cookie-secret", "X-New" to "public-value"), target)
            assertEquals(mapOf("X-Plain" to "public", "X-New" to "public-value"), merged)
        }
    }
    @Test fun hookLogsAreScopedSanitizedAndCredentialRedacted() = runBlocking<Unit> {
        val line = PluginHttpHeaders.redactText("private-key cookie-secret password=hidden\nnext", headers)
        assertFalse(line.contains("private-key"))
        assertFalse(line.contains("cookie-secret"))
        assertFalse(line.contains("hidden"))
        assertFalse(line.contains('\n'))
        assertEquals(500, PluginHttpHeaders.redactText("x".repeat(501)).length)
    }
}
