package com.aicode.feature.backup.data.pluginbackup

import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginFakeProcess
import com.aicode.feature.plugin.runtime.PluginFakeProcessFactory
import com.aicode.feature.plugin.runtime.PluginRuntimeContext
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginBackupRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager() = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "host/registry.json"))
    private suspend fun active(manager: PluginManager, id: String = "sample.plugin"): PluginCapture {
        val manifest = PluginCoreFixtures.manifest(id = id, runtime = """{"type":"javascript","entry":"main.js","resident":true}""")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("main.js" to "fixture-not-executed".toByteArray())))
        return PluginCoreFixtures.activate(manager, id)
    }
    private suspend fun invoke(runtime: PluginRuntimeManager, capture: PluginCapture) = runtime.invoke(capture,
        requireNotNull(capture.content.runtime), "fixture", JsonObject(emptyMap()), PluginRuntimeContext("/fixture", "session", "invocation", {})
            .withExecutionSource("backup-fixture:${capture.id}", RuntimeOwner(RuntimeOwnerKind.PLUGIN, "backup-fixture:${capture.id}"), null, null),
        { _, _ -> JsonPrimitive("fixture") })
    private fun answer(process: PluginFakeProcess, request: JsonObject) = process.send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", requireNotNull(request["id"])); put("result", "fixture")
    })

    @Test(timeout = 20_000) fun restoreWaitClosesOnlySelectedPluginNotAllResidentProcesses() = runBlocking<Unit> {
        val manager = manager()
        val own = active(manager)
        val other = active(manager, "other.plugin")
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(manager, factory)
        try {
            val ownCall = async { invoke(runtime, own) }
            val ownProcess = withTimeout(5_000) { factory.started.receive() }
            answer(ownProcess, ownProcess.message())
            ownCall.await()
            val otherCall = async { invoke(runtime, other) }
            val otherProcess = withTimeout(5_000) { factory.started.receive() }
            answer(otherProcess, otherProcess.message())
            otherCall.await()
            manager.withBackupRestoreFence(own.id, own.installRevision) {
                assertTrue(withTimeout(5_000) { runtime.closePluginAndAwait(own.id) })
                assertFalse(ownProcess.isAlive)
                assertTrue(otherProcess.isAlive)
                other.checkCurrent()
            }
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }

    @Test(timeout = 20_000) fun absentIdWaitDoesNotWaitForAnotherPluginResident() = runBlocking<Unit> {
        val manager = manager()
        val other = active(manager, "other.plugin")
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(manager, factory)
        try {
            val call = async { invoke(runtime, other) }
            val process = withTimeout(5_000) { factory.started.receive() }
            answer(process, process.message())
            call.await()
            assertTrue(withTimeout(1_000) { runtime.closePluginAndAwait("not.installed") })
            assertTrue(process.isAlive)
            other.checkCurrent()
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }

    @Test(timeout = 20_000) fun lateStartingOwnedProcessBlocksCommitUntilClosedAndCanTimeOut() = runBlocking<Unit> {
        val manager = manager()
        val capture = active(manager)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val factory = PluginFakeProcessFactory().apply {
            beforeStart = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val runtime = PluginRuntimeManager(manager, factory)
        try {
            val call = async(Dispatchers.IO) { runCatching { invoke(runtime, capture) } }
            withTimeout(5_000) { entered.await() }
            manager.withBackupRestoreFence(capture.id, capture.installRevision) {
                assertFalse(runtime.closePluginAndAwait(capture.id, 50))
                release.countDown()
                assertTrue(withTimeout(5_000) { runtime.closePluginAndAwait(capture.id) })
            }
            assertTrue(call.await().isFailure)
            assertEquals(1, factory.processes.size)
            assertFalse(factory.processes.single().isAlive)
        } finally { release.countDown(); runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }

    @Test(timeout = 20_000) fun inFlightAndQueuedOldCaptureCannotCreateAnotherProcessAfterFence() = runBlocking<Unit> {
        val manager = manager()
        val capture = active(manager)
        val factory = PluginFakeProcessFactory()
        val runtime = PluginRuntimeManager(manager, factory)
        try {
            val inFlight = async { runCatching { invoke(runtime, capture) } }
            val process = withTimeout(5_000) { factory.started.receive() }
            process.message()
            val queued = async { runCatching { invoke(runtime, capture) } }
            manager.withBackupRestoreFence(capture.id, capture.installRevision) {
                assertTrue(withTimeout(5_000) { runtime.closePluginAndAwait(capture.id) })
            }
            assertTrue(inFlight.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertEquals(1, factory.processes.size)
            assertFalse(process.isAlive)
        } finally { runtime.close(); withTimeout(5_000) { runtime.awaitClosed() } }
    }
}
