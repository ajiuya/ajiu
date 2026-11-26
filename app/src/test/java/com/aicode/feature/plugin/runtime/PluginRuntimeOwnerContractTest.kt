package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PluginRuntimeOwnerContractTest {
    private val instance = ChrootInstance("instance", 1, 2, 3)
    private val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "phone", localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = instance)
    private val owner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "plugin:installation:hash:activation:source")
    private fun capture(owner: RuntimeOwner = this.owner) = LocalRuntimeCapture(owner, profile.id, 4L, instance,
        "/work", "/root", "digest", Any()) {}

    @Test fun guardedAndBackgroundRuntimeCopiesKeepSourceOwnerAndOpaqueCapture() {
        val source = Any()
        val capture = capture()
        val authority = Any()
        val original = PluginRuntimeContext("/work", "session", "invocation") {}
            .withExecutionSource(source, owner, capture, profile).withBackgroundAuthority(authority)
        val guarded = original.guarded {}.withIdleCallbacks()
        assertSame(authority, guarded.authorityIdentity)
        assertSame(source, guarded.sourceIdentity)
        assertEquals(owner, guarded.runtimeOwner)
        assertSame(capture, guarded.localRuntimeCapture)
        assertEquals(profile, guarded.runtimeProfile)
        assertTrue(guarded.allowIdleCallbacks)
        assertNotSame(source, guarded.authorityIdentity)
    }

    @Test fun localChrootFactoryConsumesOnlyTheExplicitPluginCapture() = runTest {
        val commands = mockk<LocalRuntimeCommands>()
        val engine = mockk<LinuxContainerEngine>()
        val factory = LocalPluginProcessFactory(mockk(), engine, mockk(), commands)
        val capture = capture()
        val launch = PluginLocalLaunch(profile, "/root/.aicode/plugins/sample", listOf("node", "main.js"),
            mapOf("AICODE_PLUGIN_ID" to "sample"), owner, capture)
        val process = mockk<com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess>()
        coEvery { commands.startStdio(capture, "/bin/sh", launch.launcherArguments(), null, launch.environment, 0L) } returns process
        assertSame(process, factory.start(launch))
        coVerify(exactly = 1) { commands.startStdio(capture, "/bin/sh", launch.launcherArguments(), null, launch.environment, 0L) }
        verify(exactly = 0) { engine.startStdioProcess(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { commands.revokeOwner(any()) }
    }

    @Test fun absentOrChatOwnedCaptureCannotFallBackToTheEngine() = runTest {
        val commands = mockk<LocalRuntimeCommands>()
        val engine = mockk<LinuxContainerEngine>()
        val factory = LocalPluginProcessFactory(mockk(), engine, mockk(), commands)
        val missing = PluginLocalLaunch(profile, "/root/.aicode/plugins/sample", listOf("node"), emptyMap(), owner)
        try { factory.start(missing); fail("Expected missing plugin capture") }
        catch (failure: PluginProcessException) { assertEquals("PLUGIN_CONTEXT_REQUIRED", failure.code) }
        val chat = RuntimeOwner(RuntimeOwnerKind.AGENT, "chat")
        val wrong = missing.copy(runtimeOwner = chat, localRuntimeCapture = capture(chat))
        try { factory.start(wrong); fail("Expected independent plugin owner") }
        catch (failure: PluginProcessException) { assertEquals("PLUGIN_CAPTURE_MISMATCH", failure.code) }
        coVerify(exactly = 0) { commands.startStdio(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { engine.startStdioProcess(any(), any(), any(), any(), any()) }
    }
}
