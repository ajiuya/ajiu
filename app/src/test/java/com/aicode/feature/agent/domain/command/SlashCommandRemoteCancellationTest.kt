package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.feature.agent.domain.skill.GlobalDirectorySkillSource
import com.aicode.feature.agent.domain.skill.LocalSkillDefinitionSource
import com.aicode.feature.agent.domain.skill.ProjectDirectorySkillSource
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.*
import org.junit.Test

class SlashCommandRemoteCancellationTest {
    @Test fun actualRefreshCancellationReachesLastCacheWaiterAndOwnedSshSession() = runBlocking {
        val connection = mockk<RemoteSshConnection>()
        val settings = mockk<ContainerSettingsRepository>()
        val workspace = mockk<WorkspaceRepository>()
        val owner = mockk<SSHClient>()
        val identity = RemoteConnectionIdentity(owner, "/home/user")
        val session = mockk<Session>(relaxed = true)
        val command = mockk<Session.Command>(relaxed = true)
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val output = object : InputStream() {
            override fun read(): Int { reading.countDown(); check(closed.await(10, TimeUnit.SECONDS)); throw IOException("closed") }
            override fun read(bytes: ByteArray, off: Int, len: Int) = read()
        }
        every { settings.activeProfileIdFlow } returns flowOf("a")
        every { settings.activeProfileSelectionRevision } returns 0L
        every { settings.profileConfigurationRevision } returns 0L
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "a", mode = ExecutionMode.REMOTE_SSH)
        val profileSnapshot = ContainerProfileSnapshot("a", "a", listOf(profile), 0L, 0L, settings)
        coEvery { settings.captureProfileSnapshot() } returns profileSnapshot
        every { settings.checkProfileSnapshot(any()) } answers {
            check(firstArg<ContainerProfileSnapshot>() === profileSnapshot)
        }
        every { workspace.currentPath() } returns "/project"
        every { connection.config } returns RemoteConnectionConfig("host", username = "user", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = "/project")
        every { connection.captureIdentity() } returns identity
        every { connection.checkIdentity(identity) } returns Unit
        every { owner.startSession() } returns session
        every { session.exec(any()) } returns command
        every { command.outputStream } returns ByteArrayOutputStream()
        every { command.inputStream } returns output
        every { command.errorStream } returns byteArrayOf().inputStream()
        every { session.close() } answers { closed.countDown() }
        val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
        val access = RemoteSftpFileAccess(connection, workspace, RemoteAgentConfigCache(connection, settings, mode, workspace))
        val local = mockk<LocalFileAccess>()
        val folders = mockk<LocalFolderGrantRepository>()
        coEvery { folders.awaitReady() } returns Unit
        every { folders.snapshot() } returns LocalFolderSnapshot.EMPTY
        val factory = WorkspaceExecutionContextFactory(access, mode, local, access, settings, folders)
        val config = mockk<SkillConfigRepository>()
        every { config.disabledNames() } returns emptySet()
        val pluginSource = mockk<PluginDefinitionSource>()
        every { pluginSource.revision } returns 0
        every { pluginSource.skills() } returns emptyList()
        val localDefinitions = mockk<LocalSkillDefinitionSource>()
        every { localDefinitions.sourceIdentity() } returns "remote-cancellation-fixture"
        coEvery { localDefinitions.capture() } returns null
        val repository = SkillRepository(
            GlobalDirectorySkillSource(access), ProjectDirectorySkillSource(access), config, access, pluginSource, localDefinitions
        )
        val registry = SlashCommandRegistry(emptySet(), repository, mockk<Context>(), factory)
        val refresh = async(Dispatchers.Default) { registry.refresh() }
        assertTrue(withContext(Dispatchers.IO) { reading.await(10, TimeUnit.SECONDS) })
        refresh.cancelAndJoin()
        assertTrue(withContext(Dispatchers.IO) { closed.await(10, TimeUnit.SECONDS) })
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { owner.close() }
        verify(exactly = 0) { connection.startExecSessionWithStdin(any()) }
        coVerify(exactly = 1) { settings.captureProfileSnapshot() }
        coVerify(exactly = 1) { folders.awaitReady() }
        verify { local wasNot Called }
    }
}
