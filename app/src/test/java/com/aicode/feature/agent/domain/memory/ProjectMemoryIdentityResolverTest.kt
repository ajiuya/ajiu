package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProjectMemoryIdentityResolverTest {
    private val holder = ExecutionModeHolder()
    private val profiles = MutableStateFlow("profile-one")
    private val settings = mockk<ContainerSettingsRepository> { every { activeProfileIdFlow } returns profiles }
    private val remote = mockk<RemoteSshConnection>()
    private val resolver = ProjectMemoryIdentityResolver(holder, settings, remote)

    private fun transport(): RemoteConnectionIdentity = mockk {
        every { generation } returns UUID.randomUUID()
    }

    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block(); fail("Stale capture must be cancelled") } catch (_: CancellationException) { }
    }

    @Test fun localCaptureFreezesProfileAndChecksItBeforeLaterIo() = runTest {
        val capture = resolver.capture("/workspace/demo")!!
        assertEquals("profile-one", capture.identity.profileId)
        assertNull(capture.channelGeneration)
        profiles.value = "profile-two"
        cancelled { capture.assertCurrent() }
        verify(exactly = 0) { remote.captureIdentity() }
    }

    @Test fun localCaptureCannotContinueAfterExecutionModeSwitch() = runTest {
        val capture = resolver.capture("/workspace/demo")!!
        holder.setMode(ExecutionMode.REMOTE_SSH)
        cancelled { capture.assertCurrent() }
    }

    @Test fun sameHostReconnectKeepsPersistentScopeButInvalidatesOldRuntimeCapture() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val first = transport()
        val second = transport()
        var current = first
        every { remote.config } returns RemoteConnectionConfig("FIXTURE.INVALID", 22, "alice", RemoteAuth.Password("not-used"), "/work")
        every { remote.captureIdentity() } answers { current }
        every { remote.checkIdentity(any()) } answers { if (firstArg<RemoteConnectionIdentity>() !== current) throw CancellationException("changed") }
        val old = resolver.capture("/work/./demo/")!!
        current = second
        val next = resolver.capture("/work/demo", expected = old.identity)!!
        assertEquals(old.identity, next.identity)
        assertNotEquals(old.channelGeneration, next.channelGeneration)
        cancelled { old.assertCurrent() }
        next.assertCurrent()
        assertFalse(kotlinx.serialization.json.Json.encodeToString(ProjectMemoryIdentity.serializer(), next.identity).contains(next.channelGeneration!!))
    }

    @Test fun headlessRemoteRequiresPersistedEndpointAndNeverOpensAConnection() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertNull(resolver.capture("/work/demo", headless = true))
        verify(exactly = 0) { remote.captureIdentity() }
    }

    @Test fun candidatesRequireModeProfileHostAccountPortAndDirectoryBoundary() {
        val local = ProjectMemoryIdentity("local_proot", projectRoot = "/work", profileId = "one")
        val child = ProjectMemoryProject("id", "Child", identity = local.copy(projectRoot = "/work/child"))
        assertEquals(listOf(child), resolver.candidates(local, listOf(child)))
        assertTrue(resolver.candidates(local, listOf(child.copy(identity = child.identity.copy(profileId = "two")))).isEmpty())
        assertTrue(resolver.candidates(local, listOf(child.copy(identity = child.identity.copy(projectRoot = "/workspace/child")))).isEmpty())
        val ssh = ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
        assertFalse(ProjectMemoryIdentityResolver.sameEndpoint(ssh, ssh.copy(username = "bob")))
        assertFalse(ProjectMemoryIdentityResolver.sameEndpoint(ssh, ssh.copy(port = 2222)))
        assertFalse(ProjectMemoryIdentityResolver.sameEndpoint(ssh, ssh.copy(host = "other.invalid")))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun environmentChangesDoNotCollapseDifferentTransitionsIntoOneUnitValue() = runTest {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        every { remote.connectionState } returns state
        val events = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { resolver.environmentChanges.collect { events.add(it) } }
        runCurrent()
        profiles.value = "profile-two"
        runCurrent()
        holder.setMode(ExecutionMode.REMOTE_SSH)
        runCurrent()
        state.value = ConnectionState.CONNECTED
        runCurrent()
        assertEquals(3, events.size)
        job.cancel()
    }

    @Test fun rootsRejectRelativeControlTraversalAndCredentialShapedEndpoints() {
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryIdentityResolver.normalizeRoot("relative") }
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryIdentityResolver.normalizeRoot("/../work") }
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryIdentityResolver.normalizeRoot("/work\nother") }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMemoryIdentityResolver.normalize(ProjectMemoryIdentity("remote_ssh", "alice:password@fixture.invalid", 22, "alice", "/work"))
        }
    }
}
