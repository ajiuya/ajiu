package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TabInfo
import com.aicode.feature.terminal.domain.TerminalResourceOwner
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTabStatus
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PluginTerminalRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = UnconfinedTestDispatcher()
    private val changes = MutableSharedFlow<Unit>()
    private var active = true
    private var sequence = 0
    private val data by lazy { temporary.newFolder("data") }
    private val runtimeOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "stable-plugin-source")
    private val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
        runtimeOwner = runtimeOwner) {}
    private val capture by lazy { PluginCapture(PluginRecord("fixture.resource", "1.0.0", true, 1,
        archiveSha256 = "archive", manifestSha256 = "manifest", fileHashes = emptyMap(), installRevision = "revision", contentHash = "hash"), mockk(), PluginContent(), temporary.newFolder("install"), {},
        { if (!active) throw CancellationException("Plugin disabled") }) }

    private fun context(source: Any = "source", plugin: PluginCapture = capture,
        owner: RuntimeOwner = runtimeOwner, capturedExecution: WorkspaceExecutionContext = execution) = PluginInvocationContext(plugin, capturedExecution,
        "/workspace", "session", data, changes, sourceIdentity = source, runtimeOwner = owner) { if (!active) throw CancellationException("Source revoked") }

    private class Provider : TerminalSessionProvider {
        class Tab(val owner: TerminalResourceOwner, val output: (ByteArray) -> Unit, var running: Boolean = true,
            val runtimeOwner: RuntimeOwner? = null, var status: TerminalTabStatus? = null)
        val tabs = ConcurrentHashMap<String, Tab>()
        val entered = CompletableDeferred<Unit>()
        var startGate: CompletableDeferred<Unit>? = null
        var closeGate: CompletableDeferred<Unit>? = null
        val closing = CompletableDeferred<Unit>()
        var index = 0
        var closeVerified = true
        var closeCount = 0
        override val tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>()
        override suspend fun startPluginCommand(command: String, title: String?, owner: TerminalResourceOwner, onOutput: (ByteArray) -> Unit): String {
            entered.complete(Unit)
            startGate?.let { withContext(NonCancellable) { it.await() } }
            val id = "actual-${++index}"
            tabs[id] = Tab(owner, onOutput, runtimeOwner = WorkspaceExecutionContext.current()?.runtimeOwner)
            onOutput("first bytes\u001b[31m".toByteArray())
            return id
        }
        override fun pluginTabStatus(id: String, owner: TerminalResourceOwner) = tabs[id]?.takeIf { it.owner == owner }
            ?.let { it.status ?: TerminalTabStatus(it.running, if (it.running) null else 17) }
        override fun writePluginInput(id: String, owner: TerminalResourceOwner, text: String, appendNewline: Boolean) = tabs[id]?.owner == owner
        override fun interruptPluginTab(id: String, owner: TerminalResourceOwner) = tabs[id]?.owner == owner
        override suspend fun closePluginTab(id: String, owner: TerminalResourceOwner): Boolean {
            if (tabs[id]?.owner != owner) return false
            closing.complete(Unit)
            closeCount++
            closeGate?.await()
            tabs.remove(id)
            return closeVerified
        }
        override suspend fun startBackgroundCommand(command: String, title: String?, notify: Boolean, sourceSessionId: String?) = error("Unexpected shared terminal creation")
        override fun sendInput(id: String, input: String, appendNewline: Boolean) = error("Unexpected unowned input")
        override fun writeToTab(id: String, text: String) = error("Unexpected unowned write")
        override fun writeBytesToTab(id: String, vararg bytes: Int) = error("Unexpected unowned interrupt")
        override fun getTabOutput(id: String): String? = error("Screen snapshots are not raw output")
        override fun listTabs(): List<TabInfo> = error("Shared tabs are not plugin resources")
        override fun closeTab(id: String) = error("Unexpected unowned close")
    }

    @Before fun prepare() { Dispatchers.setMain(main) }
    @After fun release() { Dispatchers.resetMain() }
    private suspend fun notOwned(block: suspend () -> Unit) {
        try { block(); fail("Expected owner rejection") } catch (failure: PluginHostException) { assertEquals("TAB_NOT_FOUND", failure.code) }
    }

    @Test fun rawBytesArriveBeforeStartReturnsAndFinishedStatusUsesRealBackend() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "fixture", null).getValue("tabId").jsonPrimitive.content
        val first = runner.poll(owner, id, 0)
        assertEquals("first bytes\u001b[31m", first.getValue("output").jsonPrimitive.content)
        assertEquals("running", first.getValue("state").jsonPrimitive.content)
        assertEquals("false", first.getValue("exit_known").jsonPrimitive.content)
        assertEquals(runtimeOwner, provider.tabs.values.single().runtimeOwner)
        assertNotEquals(runtimeOwner.scopeId, provider.tabs.values.single().owner.resourceId)
        provider.tabs.values.single().running = false
        val done = runner.poll(owner, id, first.getValue("offset").jsonPrimitive.content.toLong())
        assertEquals("finished", done.getValue("state").jsonPrimitive.content)
        assertEquals("17", done.getValue("exitCode").jsonPrimitive.content)
        assertEquals("true", done.getValue("exit_known").jsonPrimitive.content)
        assertEquals("", done.getValue("output").jsonPrimitive.content)
        runner.close(owner, id)
        assertTrue(provider.tabs.isEmpty())
    }

    @Test fun foreignPluginSourceAndArbitraryTabIdsCannotReadWriteStopOrClose() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "fixture", null).getValue("tabId").jsonPrimitive.content
        for (foreign in listOf(context("different-source"), context(plugin = PluginCapture(capture.record.copy(id = "other.plugin"),
            mockk(), PluginContent(), capture.installDir, {}, {})))) {
            notOwned { runner.poll(foreign, id, 0) }
            notOwned { runner.write(foreign, id, "bad", true) }
            notOwned { runner.stop(foreign, id) }
            notOwned { runner.close(foreign, id) }
            assertEquals(0, runner.list(foreign).size)
        }
        notOwned { runner.close(owner, "actual-1") }
        assertEquals(1, runner.list(context()).size)
        assertTrue(runner.write(context(), id, "same frozen source", false))
        runner.close(owner, id)
    }

    @Test fun sourceRevocationRetainsClosingSlotUntilActualBackendCloses() = runBlocking<Unit> {
        val provider = Provider().apply { closeGate = CompletableDeferred() }
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "fixture", null).getValue("tabId").jsonPrimitive.content
        active = false
        changes.emit(Unit)
        withTimeout(5000) { provider.closing.await() }
        assertEquals(1, provider.tabs.size)
        active = true
        val next = context("new-source")
        val ids = (1..3).map { runner.start(next, "next", null).getValue("tabId").jsonPrimitive.content }
        try { runner.start(next, "too many", null); fail("Closing resource must hold capacity") }
        catch (failure: PluginHostException) { assertEquals("TERMINAL_LIMIT", failure.code) }
        provider.closeGate!!.complete(Unit)
        ids.forEach { runner.close(next, it) }
        withTimeout(5000) { while (provider.tabs.isNotEmpty()) kotlinx.coroutines.yield() }
        notOwned { runner.poll(owner, id, 0) }
    }

    @Test fun cancelledStartCleansLateBackendWithoutPublishingAHandle() = runBlocking<Unit> {
        val provider = Provider().apply { startGate = CompletableDeferred() }
        val runner = PluginTerminalRunner(provider)
        val pending = async { runner.start(context(), "late", null) }
        withTimeout(5000) { provider.entered.await() }
        pending.cancel()
        provider.startGate!!.complete(Unit)
        pending.join()
        withTimeout(5000) { provider.closing.await() }
        withTimeout(5000) { while (provider.tabs.isNotEmpty()) kotlinx.coroutines.yield() }
        assertEquals(0, runner.list(context()).size)
    }

    @Test fun stopPreservesCancelledOutputAndHandleUntilExplicitClose() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "fixture", null).getValue("tabId").jsonPrimitive.content
        provider.tabs.values.single().running = false
        assertTrue(runner.stop(owner, id))
        val stopped = runner.poll(context(), id, 0)
        assertEquals("cancelled", stopped.getValue("state").jsonPrimitive.content)
        assertEquals("first bytes\u001b[31m", stopped.getValue("output").jsonPrimitive.content)
        assertEquals("17", stopped.getValue("exitCode").jsonPrimitive.content)
        assertEquals(1, runner.list(owner).size)
        runner.close(owner, id)
        assertTrue(provider.tabs.isEmpty())
        notOwned { runner.poll(owner, id, 0) }
    }

    @Test fun aiOrMismatchedPluginRuntimeOwnerCannotStartOrBorrowAPluginResource() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val ai = RuntimeOwner(RuntimeOwnerKind.AGENT, "session")
        val wrong = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "other-stable-source")
        val aiExecution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
            runtimeOwner = ai) {}
        for (invalid in listOf(context(owner = ai, capturedExecution = aiExecution), context(owner = wrong))) {
            try { runner.start(invalid, "must-not-run", null); fail("Expected owner mismatch") }
            catch (failure: PluginHostException) { assertEquals("CONTEXT_CHANGED", failure.code) }
        }
        assertTrue(provider.tabs.isEmpty())
        val id = runner.start(context(), "valid", null).getValue("tabId").jsonPrimitive.content
        notOwned { runner.poll(context(owner = wrong), id, 0) }
        notOwned { runner.write(context(owner = wrong), id, "bad", true) }
        notOwned { runner.stop(context(owner = wrong), id) }
        notOwned { runner.close(context(owner = wrong), id) }
        assertEquals(0, runner.list(context(owner = wrong)).size)
        assertTrue(runner.close(context(), id))
    }

    @Test fun unknownTaskAndPendingCleanupStayExplicitInPollListAndUnverifiedClose() = runBlocking<Unit> {
        val provider = Provider().apply { closeVerified = false }
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "task", null).getValue("tabId").jsonPrimitive.content
        val tab = provider.tabs.values.single()
        tab.status = TerminalTabStatus(false, -1, exitKnown = false, taskStatus = "UNKNOWN", cleanupStatus = "PENDING")
        val unknown = runner.poll(owner, id, 0)
        assertEquals("unknown", unknown.getValue("state").jsonPrimitive.content)
        assertEquals("false", unknown.getValue("exit_known").jsonPrimitive.content)
        assertEquals("UNKNOWN", unknown.getValue("task_status").jsonPrimitive.content)
        assertEquals("PENDING", unknown.getValue("cleanup_status").jsonPrimitive.content)
        assertFalse(unknown.containsKey("exitCode"))
        val listed = runner.list(owner).single().jsonObject
        assertEquals("unknown", listed.getValue("state").jsonPrimitive.content)
        assertEquals("PENDING", listed.getValue("cleanup_status").jsonPrimitive.content)
        assertFalse(listed.containsKey("exitCode"))
        assertFalse(runner.close(owner, id))
        assertTrue(provider.tabs.isEmpty())
        assertEquals(1, provider.closeCount)
    }

    @Test fun stoppedTaskStatusKeepsCleanupIndependentAndRefreshesLateCleanupEvidence() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val id = runner.start(owner, "task", null).getValue("tabId").jsonPrimitive.content
        val tab = provider.tabs.values.single()
        tab.status = TerminalTabStatus(false, null, exitKnown = false, taskStatus = "UNKNOWN", cleanupStatus = "PENDING")
        assertTrue(runner.stop(owner, id))
        assertEquals("PENDING", runner.poll(owner, id, 0).getValue("cleanup_status").jsonPrimitive.content)
        tab.status = TerminalTabStatus(false, null, exitKnown = false, taskStatus = "UNKNOWN", cleanupStatus = "VERIFIED")
        val stopped = runner.poll(owner, id, 0)
        assertEquals("cancelled", stopped.getValue("state").jsonPrimitive.content)
        assertEquals("false", stopped.getValue("exit_known").jsonPrimitive.content)
        assertEquals("VERIFIED", stopped.getValue("cleanup_status").jsonPrimitive.content)
        assertFalse(stopped.containsKey("exitCode"))
        assertEquals("VERIFIED", runner.list(owner).single().jsonObject.getValue("cleanup_status").jsonPrimitive.content)
        assertTrue(runner.close(owner, id))
    }

    @Test fun closingOneOfSamePluginResourcesDoesNotCloseTheSibling() = runBlocking<Unit> {
        val provider = Provider()
        val runner = PluginTerminalRunner(provider)
        val owner = context()
        val first = runner.start(owner, "first", null).getValue("tabId").jsonPrimitive.content
        val second = runner.start(owner, "second", null).getValue("tabId").jsonPrimitive.content
        assertTrue(runner.close(owner, first))
        assertEquals(1, provider.tabs.size)
        assertEquals("running", runner.poll(owner, second, 0).getValue("state").jsonPrimitive.content)
        assertTrue(runner.write(owner, second, "still owned", false))
        assertTrue(runner.close(owner, second))
        assertEquals(2, provider.closeCount)
    }

    @Test fun outputIsBoundedAcrossLargeChunksAndSplitUtf8WithoutScreenSampling() {
        val output = PluginTerminalOutputBuffer(128)
        val bytes = "汉字".toByteArray()
        output.append(bytes.copyOfRange(0, 2)); assertEquals("", output.poll(0).first)
        output.append(bytes.copyOfRange(2, bytes.size)); assertEquals("汉字", output.poll(0).first)
        output.append(ByteArray(256 * 1024) { 'a'.code.toByte() })
        val bounded = output.poll(0)
        assertEquals(128, bounded.first.length); assertTrue(bounded.third)
        assertEquals(256 * 1024L + 2, bounded.second)
        output.append(byteArrayOf(0xe4.toByte())); output.finish()
        assertTrue(output.poll(bounded.second).first.contains('\ufffd'))
        output.append("ignored".toByteArray()); assertEquals(0, output.poll(output.poll(0).second).first.length)
    }
}
