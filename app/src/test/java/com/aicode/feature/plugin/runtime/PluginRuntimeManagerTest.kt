package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRuntimeManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val empty = JsonObject(emptyMap())
    private fun plugins() = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "host/registry.json"))
    private suspend fun active(manager: PluginManager, id: String = "sample.plugin", resident: Boolean = true): PluginCapture {
        val manifest = PluginCoreFixtures.manifest(id = id,
            runtime = """{"type":"javascript","entry":"main.js","resident":$resident}""")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("main.js" to "// fake never runs".toByteArray())))
        return PluginCoreFixtures.activate(manager, id)
    }
    private fun context(session: String = "one", check: () -> Unit = {}) = PluginRuntimeContext("/workspace", session, "invocation", check)
        .withExecutionSource("test-source:$session", com.aicode.feature.agent.domain.container.runtime.RuntimeOwner(
            com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN, "test-source:$session"), null, null)
    private suspend fun invoke(runtime: PluginRuntimeManager, capture: PluginCapture, context: PluginRuntimeContext = context(),
        host: suspend (String, JsonObject) -> JsonElement = { _, _ -> JsonPrimitive("host") }): JsonElement =
        runtime.invoke(capture, requireNotNull(capture.content.runtime), "run", empty, context, host)
    private fun answer(process: PluginFakeProcess, message: JsonObject) = process.send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", requireNotNull(message["id"])); put("result", "done")
    })
    private suspend fun failure(block: suspend () -> Unit): PluginProcessException = try { block(); error("failure expected") }
        catch (error: PluginProcessException) { error }

    @Test(timeout = 20_000) fun sameAuthorityResidentReusesProcessWithFrozenLaunchMetadata() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        val context = context()
        try {
            val first = async { invoke(runtime, capture, context) }
            val process = withTimeout(5_000) { factory.started.receive() }
            answer(process, process.message())
            assertEquals(JsonPrimitive("done"), first.await())
            val second = async { invoke(runtime, capture, context) }
            answer(process, process.message())
            second.await()
            assertEquals(1, factory.launches.size)
            assertEquals(capture.installDir.absolutePath, factory.launches.single().workingDirectory)
            assertEquals(listOf("node", "main.js"), factory.launches.single().command)
            assertEquals(capture.id, factory.launches.single().environment["AICODE_PLUGIN_ID"])
            assertTrue(process.isAlive)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun newAuthoritySameWorkspaceAndSessionRestartsResident() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            val first = async { invoke(runtime, capture, context("same")) }
            val old = withTimeout(5_000) { factory.started.receive() }
            answer(old, old.message())
            first.await()
            val second = async { invoke(runtime, capture, context("same")) }
            val current = withTimeout(5_000) { factory.started.receive() }
            assertNotSame(old, current)
            assertFalse(old.isAlive)
            answer(current, current.message())
            second.await()
            assertEquals(2, factory.launches.size)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun sessionSwitchCannotDeliverOldReverseRequestIntoNewSession() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        var oldCallbacks = 0
        var newCallbacks = 0
        try {
            val first = async { invoke(runtime, capture, context("old"), host = { _, _ -> oldCallbacks++; JsonPrimitive("old") }) }
            val old = withTimeout(5_000) { factory.started.receive() }
            answer(old, old.message())
            first.await()
            val second = async { invoke(runtime, capture, context("new"), host = { _, _ -> newCallbacks++; JsonPrimitive("new") }) }
            val current = withTimeout(5_000) { factory.started.receive() }
            assertFalse(old.isAlive)
            val request = current.message()
            current.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", "host"); put("method", "fs.readText"); put("params", empty)
            })
            assertEquals(JsonPrimitive("new"), current.message()["result"])
            answer(current, request)
            second.await()
            assertEquals(0, oldCallbacks)
            assertEquals(1, newCallbacks)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun disableSnapshotClosesResidentAndOldCaptureCannotInvoke() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            val call = async { invoke(runtime, capture) }
            val process = withTimeout(5_000) { factory.started.receive() }
            answer(process, process.message())
            call.await()
            plugins.disable(capture.id)
            withTimeout(5_000) { runtime.awaitClosed() }
            assertFalse(process.isAlive)
            assertTrue(runCatching { invoke(runtime, capture) }.isFailure)
            assertEquals(1, factory.processes.size)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun identicalSameVersionReplacementNeverUsesOldResident() = runBlocking<Unit> {
        val plugins = plugins()
        val oldCapture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            val first = async { invoke(runtime, oldCapture) }
            val old = withTimeout(5_000) { factory.started.receive() }
            answer(old, old.message())
            first.await()
            val newCapture = active(plugins)
            assertNotEquals(oldCapture.installRevision, newCapture.installRevision)
            withTimeout(5_000) { runtime.awaitClosed() }
            val second = async { invoke(runtime, newCapture) }
            val current = withTimeout(5_000) { factory.started.receive() }
            assertFalse(old.isAlive)
            answer(current, current.message())
            second.await()
            assertEquals(2, factory.processes.size)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun revokeDuringLateFactoryStartDisposesReturnedProcessBeforeReleasingSlot() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        factory.beforeStart = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            val call = async { runCatching { invoke(runtime, capture) } }
            withTimeout(5_000) { entered.await() }
            plugins.disable(capture.id)
            release.countDown()
            assertTrue(call.await().isFailure)
            withTimeout(5_000) { runtime.awaitClosed() }
            assertEquals(1, factory.processes.size)
            assertFalse(factory.processes.single().isAlive)
        } finally { release.countDown(); runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun globalProcessSlotsAreBoundedUntilResourcesActuallyClose() = runBlocking<Unit> {
        val plugins = plugins()
        val captures = (1..5).map { active(plugins, "sample.plugin$it") }
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            for (capture in captures.take(4)) {
                val call = async { invoke(runtime, capture) }
                val process = withTimeout(5_000) { factory.started.receive() }
                answer(process, process.message())
                call.await()
            }
            assertEquals("PLUGIN_BUSY", failure { invoke(runtime, captures.last()) }.code)
            assertEquals(4, factory.processes.size)
            runtime.closePlugin(captures.first().id)
            assertTrue(factory.processes.first().waitFor(5, TimeUnit.SECONDS))
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun runtimeMismatchAndChangedBundleFailBeforeProcessFactoryRuns() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        try {
            val altered = requireNotNull(capture.content.runtime).copy(command = listOf("sh", "other.js"))
            assertEquals("PLUGIN_CAPTURE_MISMATCH", failure {
                runtime.invoke(capture, altered, "run", empty, context(), { _, _ -> empty })
            }.code)
            File(capture.installDir, "main.js").writeText("tampered")
            assertTrue(runCatching { invoke(runtime, capture) }.isFailure)
            assertTrue(factory.processes.isEmpty())
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test(timeout = 20_000) fun staleWorkspaceContextClosesResidentWithoutWaitingForNextRpc() = runBlocking<Unit> {
        val plugins = plugins()
        val capture = active(plugins)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(plugins, factory)
        val current = AtomicBoolean(true)
        val context = context(check = { if (!current.get()) throw PluginProcessException("STALE", "source changed") })
        try {
            val call = async { invoke(runtime, capture, context) }
            val process = withTimeout(5_000) { factory.started.receive() }
            answer(process, process.message())
            call.await()
            current.set(false)
            withTimeout(5_000) { runtime.awaitClosed() }
            assertFalse(process.isAlive)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
    @Test fun launcherQuotesOnlyFixedScriptAndForwardsUserValuesAsArgv() {
        val launch = PluginLocalLaunch(ContainerProfile.BUILTIN_ALPINE, "/tmp/with spaces;$(no)",
            listOf("node", "entry.js", "\"; rm injected"), emptyMap())
        val args = launch.launcherArguments()
        assertFalse(args[1].contains("injected"))
        assertFalse(args[1].contains("with spaces"))
        assertEquals("/tmp/with spaces;$(no)", args[3])
        assertEquals(listOf("node", "entry.js", "\"; rm injected"), args.drop(4))
    }
    @Test fun guardedContextPreservesOnlyHostObjectIdentityNotStringEquality() {
        val first = context()
        val guarded = first.guarded {}
        val identicalStrings = context()
        assertSame(first.authorityIdentity, guarded.authorityIdentity)
        assertNotSame(first.authorityIdentity, identicalStrings.authorityIdentity)
        assertEquals(first.sessionId, identicalStrings.sessionId)
        assertEquals(first.invocationId, identicalStrings.invocationId)
    }
}
