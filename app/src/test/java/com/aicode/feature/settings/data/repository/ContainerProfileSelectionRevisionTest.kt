package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerProfileSelectionRevisionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualSelectionAToBToAWithoutCacheQueriesInvalidatesOldFrozenDefinitions() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        settings.setActiveProfile("a")
        val firstRevision = settings.activeProfileSelectionRevision
        settings.setActiveProfile("a")
        assertEquals(firstRevision, settings.activeProfileSelectionRevision)
        val connection = mockk<RemoteSshConnection>()
        val workspace = mockk<WorkspaceRepository>()
        val identity = RemoteConnectionIdentity(mockk(), "/home/user")
        every { connection.config } returns RemoteConnectionConfig("host", username = "user", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = "/project")
        every { connection.captureIdentity() } returns identity
        every { connection.checkIdentity(identity) } returns Unit
        every { workspace.currentPath() } returns "/project"
        val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
        val access = RemoteSftpFileAccess(connection, workspace, RemoteAgentConfigCache(connection, settings, mode, workspace))
        val source = access.captureExecutionSource()
        val frozen = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) { access.checkExecutionSource(source) }
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertEquals(firstRevision + 2, settings.activeProfileSelectionRevision)
        val failure = runCatching { withContext(frozen) { access.agentConfigSnapshot("~/.aicode/agents", AgentConfigKind.AGENTS) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { identity.owner.startSession() }
    }

    @Test fun observableSelectionRevisionSurvivesAnAToBToAWithoutCollectingIntermediateProfiles() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        assertEquals(0L, settings.activeProfileSelectionRevisions.value)
        settings.setActiveProfile("a")
        val before = settings.activeProfileSelectionRevisions.value
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertEquals(before + 2, settings.activeProfileSelectionRevisions.value)
        assertEquals(settings.activeProfileSelectionRevision, settings.activeProfileSelectionRevisions.value)
        assertEquals(listOf(settings.activeProfileSelectionRevision), settings.activeProfileSelectionRevisions.replayCache)
    }

    @Test fun selectingTheSameProfileDoesNotEmitANewSourceRevision() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        settings.setActiveProfile("a")
        val before = settings.activeProfileSelectionRevisions.value
        repeat(3) { settings.setActiveProfile("a") }
        assertEquals(before, settings.activeProfileSelectionRevisions.value)
        assertEquals(before, settings.activeProfileSelectionRevision)
    }
}
