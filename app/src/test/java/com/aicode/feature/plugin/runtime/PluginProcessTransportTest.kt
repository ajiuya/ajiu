package com.aicode.feature.plugin.runtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PluginProcessTransportTest {
    private val empty = JsonObject(emptyMap())
    private fun context(session: String = "one", check: () -> Unit = {}) = PluginRuntimeContext("/workspace", session, "invocation", check)
    private fun response(id: JsonElement, result: JsonElement = JsonPrimitive("done")) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }
    private fun peer(id: String = "host-1") = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "fs.readText"); put("params", empty)
    }
    private suspend fun request(transport: PluginProcessTransport, context: PluginRuntimeContext = context(), timeout: Long = 5_000,
        host: suspend (String, JsonObject) -> JsonElement = { _, _ -> JsonPrimitive("host") }): JsonElement =
        transport.request("tool.invoke", empty, context, host, timeout)
    private suspend fun failure(block: suspend () -> Unit): PluginProcessException = try { block(); error("failure expected") }
        catch (error: PluginProcessException) { error }

    @Test(timeout = 15_000) fun requestAndResponseUseStrictNdjsonWithoutExecutingScripts() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        try {
            val call = async { request(transport) }
            val outbound = process.message()
            assertEquals(JsonPrimitive("tool.invoke"), outbound["method"])
            process.send(response(requireNotNull(outbound["id"])))
            assertEquals(JsonPrimitive("done"), call.await())
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
        assertFalse(process.isAlive)
        assertTrue(process.stdinClosed.get())
    }
    @Test(timeout = 15_000) fun reverseHostRequestUsesCapturedAuthorityAndRepliesBeforeResult() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val called = AtomicInteger()
        try {
            val call = async { request(transport, host = { method, _ ->
                assertEquals("fs.readText", method); called.incrementAndGet(); JsonPrimitive("native answer")
            }) }
            val outbound = process.message()
            process.send(peer())
            val answer = process.message()
            assertEquals(JsonPrimitive("host-1"), answer["id"])
            assertEquals(JsonPrimitive("native answer"), answer["result"])
            process.send(response(requireNotNull(outbound["id"])))
            call.await()
            assertEquals(1, called.get())
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test(timeout = 15_000) fun nativeHostErrorReturnsTypedCodeButNeverMessageOrPath() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        try {
            val call = async { request(transport, host = { _, _ -> throw PluginHostException("SYSTEM_DENIED", "/private/secret-path must not escape") }) }
            val outbound = process.message()
            process.send(peer())
            val error = process.message()["error"] as JsonObject
            assertEquals(JsonPrimitive("SYSTEM_DENIED"), error["code"])
            assertEquals(JsonPrimitive("Plugin host request failed"), error["message"])
            assertFalse(error.toString().contains("secret-path"))
            process.send(response(requireNotNull(outbound["id"])))
            call.await()
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test(timeout = 15_000) fun differentAuthorityCannotReuseResidentEvenWithIdenticalStrings() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        try {
            val first = async { request(transport, context()) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            first.await()
            val error = failure { request(transport, context()) }
            assertEquals("PLUGIN_CONTEXT_MISMATCH", error.code)
            withTimeout(5_000) { transport.awaitClosed() }
            assertFalse(process.isAlive)
        } finally { transport.close() }
    }
    @Test(timeout = 15_000) fun sameHostAuthorityCanIssueSequentialCallsAndIdleReverseIsDenied() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val context = context()
        val called = AtomicInteger()
        try {
            val first = async { request(transport, context, host = { _, _ -> called.incrementAndGet(); JsonPrimitive("host") }) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            first.await()
            process.send(peer("idle"))
            val idle = process.message()
            assertEquals(JsonPrimitive("PLUGIN_CONTEXT_REQUIRED"), (idle["error"] as JsonObject)["code"])
            val second = async { request(transport, context.guarded(context.checkCurrent)) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            second.await()
            assertEquals(0, called.get())
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test(timeout = 15_000) fun oldHostTaskIsCancelledAndNeverRoutesIntoNewAuthority() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            val first = async {
                request(transport, host = { _, _ ->
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); JsonPrimitive("unreachable") }
                    finally { cancelled.complete(Unit) }
                })
            }
            val outbound = process.message()
            process.send(peer())
            withTimeout(5_000) { entered.await() }
            process.send(response(requireNotNull(outbound["id"])))
            first.await()
            withTimeout(5_000) { cancelled.await() }
            var newHostCalls = 0
            assertEquals("PLUGIN_CONTEXT_MISMATCH", failure { request(transport, context("two"), host = { _, _ -> newHostCalls++; JsonPrimitive("wrong") }) }.code)
            assertEquals(0, newHostCalls)
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test(timeout = 15_000) fun explicitlyGrantedBackgroundAuthorityCanPublishAfterItsHandlerReturns() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val context = context().withIdleCallbacks()
        var callbacks = 0
        try {
            val first = async { request(transport, context, host = { method, _ ->
                assertEquals("host.sessionSource.setSessions", method)
                callbacks++
                JsonPrimitive("published")
            }) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            first.await()
            process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "idle-publish"); put("method", "host.sessionSource.setSessions"); put("params", empty) })
            assertEquals(JsonPrimitive("published"), process.message()["result"])
            assertEquals(1, callbacks)
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }

    @Test(timeout = 15_000) fun idleBackgroundPublishingNeverGrantsAnArbitraryHostMethod() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        var callbacks = 0
        try {
            val first = async { request(transport, context().withIdleCallbacks(), host = { _, _ -> callbacks++; JsonPrimitive("bad") }) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            first.await()
            process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "idle-command"); put("method", "host.bash"); put("params", empty) })
            assertEquals(JsonPrimitive("PLUGIN_CONTEXT_REQUIRED"), (process.message()["error"] as JsonObject)["code"])
            assertEquals(0, callbacks)
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }

    @Test(timeout = 15_000) fun backgroundSourceRevocationPreventsAPreviouslyAllowedLatePublish() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        var callbacks = 0
        try {
            val first = async { request(transport, context { if (!active.get()) throw kotlinx.coroutines.CancellationException("revoked") }.withIdleCallbacks(),
                host = { _, _ -> callbacks++; JsonPrimitive("bad") }) }
            process.message().let { process.send(response(requireNotNull(it["id"]))) }
            first.await(); active.set(false)
            process.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "late-publish"); put("method", "host.sessionSource.setSessions"); put("params", empty) })
            withTimeout(5_000) { transport.awaitClosed() }
            assertEquals(0, callbacks)
            assertFalse(process.isAlive)
        } finally { transport.close() }
    }

    @Test(timeout = 15_000) fun timeoutClosesProcessAndFailsRequest() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        try {
            val call = async { failure { request(transport, timeout = 1_000) } }
            process.message()
            assertEquals("PLUGIN_TIMEOUT", call.await().code)
            withTimeout(5_000) { transport.awaitClosed() }
            assertFalse(process.isAlive)
        } finally { transport.close() }
    }
    @Test(timeout = 15_000) fun callerCancellationClosesStreamsAndPendingInvocation() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        try {
            val call = async { request(transport) }
            process.message()
            call.cancelAndJoin()
            withTimeout(5_000) { transport.awaitClosed() }
            assertFalse(process.isAlive)
            assertTrue(process.outputClosed.get())
            assertTrue(process.errorClosed.get())
        } finally { transport.close() }
    }
    @Test(timeout = 15_000) fun lateStartAfterCloseIsDestroyedBeforeClosedCallbackReleasesSlot() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val process = PluginFakeProcess()
        val callbacks = AtomicInteger()
        val transport = PluginProcessTransport({
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            process
        }, onClosed = { assertFalse(process.isAlive); callbacks.incrementAndGet() })
        try {
            val call = async { runCatching { request(transport) } }
            withTimeout(5_000) { entered.await() }
            transport.close()
            assertTrue(process.isAlive)
            assertEquals(0, callbacks.get())
            release.countDown()
            call.await()
            withTimeout(5_000) { transport.awaitClosed() }
            assertFalse(process.isAlive)
            assertEquals(1, callbacks.get())
        } finally { release.countDown(); transport.close() }
    }
    @Test(timeout = 15_000) fun malformedJsonDuplicateFieldsAndNumericVersionCloseRpc() = runBlocking<Unit> {
        for (line in listOf("not-json\n", "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":1,\"result\":0}\n",
            "{\"jsonrpc\":2.0,\"id\":1,\"result\":0}\n", "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":[]}\n")) {
            val process = PluginFakeProcess()
            val transport = PluginProcessTransport({ process })
            try {
                val call = async { failure { request(transport) } }
                process.message()
                process.raw(line.toByteArray())
                assertEquals("PLUGIN_RPC_INVALID", call.await().code)
                withTimeout(5_000) { transport.awaitClosed() }
            } finally { transport.close() }
        }
    }
    @Test(timeout = 15_000) fun duplicateOutstandingReverseIdsCloseWithoutSecondNativeCallback() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val transport = PluginProcessTransport({ process })
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            val call = async { failure { request(transport, host = { _, _ ->
                calls.incrementAndGet()
                entered.complete(Unit)
                CompletableDeferred<JsonElement>().await()
            }) } }
            process.message()
            process.send(peer("duplicate"))
            withTimeout(5_000) { entered.await() }
            process.send(peer("duplicate"))
            assertEquals("PLUGIN_RPC_INVALID", call.await().code)
            assertEquals(1, calls.get())
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test(timeout = 15_000) fun hostRequestBudgetIsBoundedAndReleasedAfterClose() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val budget = PluginRequestBudget(1)
        val transport = PluginProcessTransport({ process }, hostBudget = budget)
        val entered = CompletableDeferred<Unit>()
        try {
            val call = async { failure { request(transport, host = { _, _ -> entered.complete(Unit); CompletableDeferred<JsonElement>().await() }) } }
            process.message()
            process.send(peer("first"))
            withTimeout(5_000) { entered.await() }
            process.send(peer("second"))
            assertEquals("PLUGIN_HOST_BUSY", call.await().code)
            withTimeout(5_000) { transport.awaitClosed() }
            assertTrue(budget.acquire())
            budget.release()
        } finally { transport.close() }
    }
    @Test(timeout = 15_000) fun staleContextAbortsReverseRequestBeforeNativeCallback() = runBlocking<Unit> {
        val process = PluginFakeProcess()
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val transport = PluginProcessTransport({ process })
        var calls = 0
        try {
            val call = async { failure { request(transport, context(check = { if (!current.get()) throw PluginProcessException("STALE", "stale") }), host = { _, _ -> calls++; JsonPrimitive("wrong") }) } }
            process.message()
            current.set(false)
            process.send(peer())
            assertEquals("STALE", call.await().code)
            assertEquals(0, calls)
        } finally { transport.close(); withTimeout(5_000) { transport.awaitClosed() } }
    }
    @Test fun boundedReaderAcceptsSplitUtf8CrLfAndExactLimit() {
        val bytes = "é\r\nnext\n".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val reader = PluginBoundedLineReader(input, maxLineBytes = 4)
        assertEquals("é", reader.readLine())
        assertEquals("next", reader.readLine())
        assertNull(reader.readLine())
    }
    @Test fun boundedReaderRejectsMalformedUtf8TruncationZeroProgressAndLimits() {
        fun code(input: InputStream, line: Int = 10, total: Long = 100): String = try {
            PluginBoundedLineReader(input, line, total).readLine(); error("expected failure")
        } catch (error: PluginProcessException) { error.code }
        assertEquals("PLUGIN_RPC_INVALID", code(byteArrayOf(0xc3.toByte(), 0x28, 10).inputStream()))
        assertEquals("PLUGIN_RPC_TRUNCATED", code("partial".byteInputStream()))
        assertEquals("PLUGIN_LINE_LIMIT", code("longer\n".byteInputStream(), line = 5))
        assertEquals("PLUGIN_OUTPUT_LIMIT", code("okay\n".byteInputStream(), total = 4))
        assertEquals("PLUGIN_STREAM_STALLED", code(object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }))
    }
    @Test fun boundedReaderChecksSourceAroundEveryActualChunkRead() {
        var checks = 0
        val reader = PluginBoundedLineReader("one\ntwo\n".byteInputStream(), checkCurrent = { checks++ })
        assertEquals("one", reader.readLine())
        assertEquals(2, checks)
        assertEquals("two", reader.readLine())
        assertEquals(2, checks)
        assertNull(reader.readLine())
        assertEquals(4, checks)
    }
}
