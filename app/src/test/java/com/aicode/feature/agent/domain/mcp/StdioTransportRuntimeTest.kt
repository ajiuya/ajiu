package com.aicode.feature.agent.domain.mcp

import android.app.Application
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.plugin.runtime.PluginFakeProcess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.FilterOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.settings.data.repository.ExecutionMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StdioTransportRuntimeTest {
    private val instance = ChrootInstance("instance", 1, 2, 3)
    private val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "phone", localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = instance)

    private fun capture(owner: RuntimeOwner = RuntimeOwner(RuntimeOwnerKind.MCP, "project:server:revision:connection")) =
        LocalRuntimeCapture(owner, profile.id, 4, instance, "/work", "/root", "digest", Any()) {}

    private class OwnedProcess : LocalRuntimeProcess() {
        val delegate = PluginFakeProcess()
        val stops = AtomicInteger()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<LocalRuntimeCleanup>()
        val streamsClosed = CompletableDeferred<Unit>()
        override val instanceId = "instance"
        override val launchId = "launch"
        override val taskState = MutableStateFlow<LocalRuntimeTaskResult?>(null)
        override val cleanupState = MutableStateFlow<LocalRuntimeCleanup?>(null)
        override suspend fun awaitTaskExit() = LocalRuntimeTaskResult.Exited(0, null)
        override suspend fun awaitCleanup(): LocalRuntimeCleanup {
            cleanupEntered.complete(Unit)
            return cleanup.await().also { cleanupState.value = it }
        }
        override fun resize(columns: Int, rows: Int) = false
        override fun signal(signal: Int) = false
        override fun stop() { stops.incrementAndGet(); delegate.destroy() }
        override fun getOutputStream() = object : FilterOutputStream(delegate.outputStream) {
            override fun close() { super.close(); streamsClosed.complete(Unit) }
        }
        override fun getInputStream() = delegate.inputStream
        override fun getErrorStream() = delegate.errorStream
        override fun waitFor() = delegate.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
        override fun exitValue() = delegate.exitValue()
        override fun destroy() = stop()
    }

    @Test fun lazyStartConsumesOnlyTheServerCaptureAndWaitsForOwnedCleanup() = runBlocking<Unit> {
        val engine = mockk<LinuxContainerEngine>()
        val commands = mockk<LocalRuntimeCommands>()
        val capture = capture()
        val process = OwnedProcess()
        coEvery { commands.startStdio(capture, "node", listOf("server.js"), any(), any(), any()) } returns process
        val transport = StdioTransport("server", engine, "node", listOf("server.js"), "/work", runtimeProfile = profile,
            runtimeCommands = commands, runtimeCapture = capture)
        coVerify(exactly = 0) { commands.startStdio(any(), any(), any(), any(), any(), any()) }
        transport.notify("initialized")
        process.delegate.message()
        transport.close()
        assertEquals(1, process.stops.get())
        withTimeout(5_000) { process.cleanupEntered.await() }
        assertFalse(process.streamsClosed.isCompleted)
        process.cleanup.complete(LocalRuntimeCleanup.UNKNOWN)
        withTimeout(5_000) { process.streamsClosed.await() }
        assertEquals(LocalRuntimeCleanup.UNKNOWN, process.cleanupState.value)
        coVerify(exactly = 1) { commands.startStdio(capture, "node", listOf("server.js"), any(), any(), any()) }
        verify(exactly = 0) { engine.startStdioProcess(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { commands.revokeOwner(any()) }
    }

    @Test fun changedSourceBeforeLazyStartCannotBorrowAnAgentCaptureOrFallback() = runBlocking<Unit> {
        val engine = mockk<LinuxContainerEngine>()
        val commands = mockk<LocalRuntimeCommands>()
        var current = true
        val transport = StdioTransport("server", engine, "node", emptyList(), "/work", runtimeProfile = profile,
            runtimeCommands = commands, runtimeCapture = capture(), checkSource = {
                if (!current) throw CancellationException("Server source changed")
            })
        current = false
        try { transport.notify("initialize"); fail("Expected revoked source") } catch (_: CancellationException) { }
        transport.close()
        val wrong = StdioTransport("server", engine, "node", emptyList(), "/work", runtimeProfile = profile,
            runtimeCommands = commands, runtimeCapture = capture(RuntimeOwner(RuntimeOwnerKind.AGENT, "chat")))
        try { wrong.notify("initialize"); fail("Expected owner mismatch") } catch (_: McpException) { }
        wrong.close()
        coVerify(exactly = 0) { commands.startStdio(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { engine.startStdioProcess(any(), any(), any(), any(), any()) }
    }

    @Test fun revokingAConnectionStopsOnlyItsLaunchAndNeverItsSharedOwnerLease() = runBlocking<Unit> {
        val commands = mockk<LocalRuntimeCommands>()
        val process = OwnedProcess()
        coEvery { commands.startStdio(any(), any(), any(), any(), any(), any()) } returns process
        val changes = MutableStateFlow(0)
        var current = true
        val transport = StdioTransport("server", mockk(), "node", emptyList(), "/work", runtimeProfile = profile,
            runtimeCommands = commands, runtimeCapture = capture(), sourceChanges = changes.map { Unit },
            checkSource = { if (!current) throw CancellationException("Config revoked") })
        transport.notify("initialized")
        process.delegate.message()
        current = false
        changes.value++
        withTimeout(5_000) { process.cleanupEntered.await() }
        assertEquals(1, process.stops.get())
        process.cleanup.complete(LocalRuntimeCleanup.VERIFIED)
        withTimeout(5_000) { process.streamsClosed.await() }
        verify(exactly = 0) { commands.revokeOwner(any()) }
    }

    @Test fun prootLazyStartStillUsesTheExplicitProfileAndWorkspace() = runBlocking<Unit> {
        val engine = mockk<LinuxContainerEngine>()
        val process = PluginFakeProcess()
        every { engine.startStdioProcess("node", listOf("server.js"), "/work", emptyMap(), ContainerProfile.BUILTIN_ALPINE) } answers {
            assertNull(WorkspaceExecutionContext.current())
            process
        }
        val transport = StdioTransport("server", engine, "node", listOf("server.js"), "/work")
        val chat = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "chat", mockk(), null, null,
            runtimeOwner = RuntimeOwner(RuntimeOwnerKind.AGENT, "chat")) {}
        withContext(chat) { transport.notify("initialized") }
        process.message()
        transport.close()
        assertEquals(1, process.destroyed.get())
        verify(exactly = 1) { engine.startStdioProcess("node", listOf("server.js"), "/work", emptyMap(), ContainerProfile.BUILTIN_ALPINE) }
    }
}
