package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class TerminalTaskScopeResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun resolverFreezesWithoutStartingContainerAndStableSourcesSurviveNewExecutionOwners() = runBlocking {
        val mapping = mapping()
        val factory = mockk<WorkspaceExecutionContextFactory>()
        coEvery { factory.freeze(any()) } returns execution(mapping)
        val first = TerminalTaskScopeResolver(factory).capture()
        coVerify(exactly = 1) { factory.freeze(null) }
        assertNotNull(first)
        assertEquals(first, captureTerminalTaskScope(execution(mapping, identity = "new-app-owner")))
        assertEquals(mapping.workspaceRoot.canonicalPath, first!!.workspaceRoot)
        assertEquals(mapping.rootfsRoot.canonicalPath, first.rootfsPath)
        assertFalse(first.rootfsSourceIdentity.contains("alpine"))
    }

    @Test
    fun remoteAndUnknownLocalSourcesHaveNoFallbackScope() = runBlocking {
        val remote = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote", mockk(), null, null) {}
        assertNull(captureTerminalTaskScope(remote))
        val unknown = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "local", mockk(), null, null) {}
        assertNull(captureTerminalTaskScope(unknown))
        val mapping = mapping()
        assertTrue(File(mapping.rootfsRoot, ".installed").renameTo(File(mapping.rootfsRoot, "old-marker")))
        assertNull(captureTerminalTaskScope(execution(mapping)))
    }

    @Test
    fun switchingProfileImageWorkspaceOrInstallationMarkerSeparatesHistoryScopes() = runBlocking {
        val mapping = mapping()
        val original = captureTerminalTaskScope(execution(mapping))!!
        val otherProfile = mapping.copy(profile = mapping.profile.copy(id = "other"))
        assertNotEquals(original, captureTerminalTaskScope(execution(otherProfile)))
        val otherSource = mapping.copy(profile = mapping.profile.copy(rootfsSource = RootfsSource.Asset("different.bin")))
        assertNotEquals(original, captureTerminalTaskScope(execution(otherSource)))
        val otherWorkspace = mapping.copy(workspaceRoot = temporary.newFolder("another-workspace"))
        assertNotEquals(original, captureTerminalTaskScope(execution(otherWorkspace)))
        File(mapping.rootfsRoot, ".installed").writeText("new-installed-version")
        assertNotEquals(original, captureTerminalTaskScope(execution(mapping)))
    }

    @Test
    fun recreatedRootfsAtTheSameCanonicalPathCannotReadOldSourceHistory() = runBlocking {
        val mapping = mapping()
        val first = captureTerminalTaskScope(execution(mapping))!!
        Files.move(mapping.rootfsRoot.toPath(), File(temporary.root, "old-rootfs").toPath())
        assertTrue(mapping.rootfsRoot.mkdir())
        File(mapping.rootfsRoot, ".installed").writeText("version")
        val second = captureTerminalTaskScope(execution(mapping))!!
        assertEquals(first.rootfsPath, second.rootfsPath)
        assertNotEquals(first.rootfsIdentity, second.rootfsIdentity)
    }

    @Test
    fun replacedWorkspaceAtTheSamePathHasADifferentIdentity() = runBlocking {
        val mapping = mapping()
        val first = captureTerminalTaskScope(execution(mapping))!!
        Files.move(mapping.workspaceRoot.toPath(), File(temporary.root, "old-workspace").toPath())
        assertTrue(mapping.workspaceRoot.mkdir())
        val second = captureTerminalTaskScope(execution(mapping))!!
        assertEquals(first.workspaceRoot, second.workspaceRoot)
        assertNotEquals(first.workspaceIdentity, second.workspaceIdentity)
    }

    @Test
    fun sourceChecksBeforeAndAfterCaptureAreNotSwallowedAsUnknown() = runBlocking {
        val mapping = mapping()
        var checks = 0
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "local", mockk(), mapping, null) {
            if (++checks > 1) throw CancellationException("source switched")
        }
        assertTrue(runCatching { captureTerminalTaskScope(execution) }.exceptionOrNull() is CancellationException)
        assertEquals(2, checks)
    }

    @Test
    fun onlyNotifyingAiTasksUseHistoryAndUuidTabIdsDoNotConsumeLegacyNumbers() {
        assertTrue(terminalTaskHistoryEligible(true, "session"))
        assertFalse(terminalTaskHistoryEligible(false, "session"))
        assertFalse(terminalTaskHistoryEligible(true, null))
        assertFalse(terminalTaskHistoryEligible(true, ""))
        var counter = 0
        val next = { "term-${++counter}" }
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        assertEquals("term-$first", terminalTaskTabId(first, next))
        assertNotEquals(terminalTaskTabId(first, next), terminalTaskTabId(second, next))
        assertEquals("term-1", terminalTaskTabId(null, next))
        assertEquals("term-2", terminalTaskTabId(null, next))
        assertTrue(runCatching { terminalTaskTabId("../invalid", next) }.isFailure)
    }

    private fun mapping(): WorkspacePathMapper.ExecutionMapping {
        val rootfs = temporary.newFolder("rootfs")
        File(rootfs, ".installed").writeText("version")
        return WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE,
            temporary.newFolder("workspace"), rootfs, temporary.newFolder("aicode"), "/root")
    }

    private fun execution(mapping: WorkspacePathMapper.ExecutionMapping, identity: String = "local") =
        WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, identity, mockk(), mapping, null) {}
}
