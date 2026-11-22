package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.*
import org.junit.Test

class RemoteAgentConfigSourceFenceTest {
    private val connection = mockk<RemoteSshConnection>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val workspace = mockk<WorkspaceRepository>()
    private val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
    private var selectionRevision = 0L
    private var profile = "a"
        set(value) { if (field != value) selectionRevision++; field = value }
    private var root = "/project"
    private var identity = RemoteConnectionIdentity(mockk<SSHClient>(), "/home/user")
    private var body = "original"
    private var batches = 0

    private fun access(): RemoteSftpFileAccess {
        every { settings.activeProfileIdFlow } returns flow { emit(profile) }
        every { settings.activeProfileSelectionRevision } answers { selectionRevision }
        every { workspace.currentPath() } answers { root }
        every { connection.config } returns RemoteConnectionConfig("host", username = "user", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = root)
        every { connection.remoteHome } answers { identity.home }
        every { connection.captureIdentity() } answers { identity }
        every { connection.checkIdentity(any()) } answers {
            if (firstArg<RemoteConnectionIdentity>() !== identity) throw CancellationException("changed")
        }
        setupOwner(identity.owner)
        return RemoteSftpFileAccess(connection, workspace, RemoteAgentConfigCache(connection, settings, mode, workspace))
    }

    private fun setupOwner(owner: SSHClient) {
        every { owner.startSession() } answers {
            val session = mockk<Session>(relaxed = true)
            every { session.exec(any()) } answers {
                batches++
                val manifest = firstArg<String>().contains("AICODE_CONFIG_1 M")
                val command = mockk<Session.Command>(relaxed = true)
                val path = Base64.getEncoder().encodeToString("one.md".toByteArray())
                val data = body.toByteArray()
                val wire = if (manifest) "AICODE_CONFIG_1 M\nF\t$path\t${RemoteAgentConfigProtocol.sha256(data)}\t${data.size}\nEND\n"
                    else "AICODE_CONFIG_1 B\nB\t$path\t${Base64.getEncoder().encodeToString(data)}\nEND\n"
                every { command.inputStream } returns wire.byteInputStream()
                every { command.errorStream } returns byteArrayOf().inputStream()
                every { command.outputStream } returns ByteArrayOutputStream()
                every { command.exitStatus } returns 0
                every { command.exitSignal } returns null
                command
            }
            session
        }
    }

    private fun snapshot(access: RemoteSftpFileAccess): AgentConfigSnapshot =
        requireNotNull(access.agentConfigSnapshot("~/.aicode/agents", AgentConfigKind.AGENTS))

    private fun frozen(access: RemoteSftpFileAccess): WorkspaceExecutionContext {
        val source = access.captureExecutionSource()
        return WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) {
            access.checkExecutionSource(source)
        }
    }

    @Test fun frozenSourceCreatedBeforeProfileSwitchIsRejectedBeforeItsFirstCacheRead() = runBlocking {
        val access = access()
        val frozen = frozen(access)
        profile = "b"
        val failure = runCatching { withContext(frozen) { snapshot(access) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, batches)
        verify(exactly = 0) { identity.owner.startSession() }
    }

    @Test fun aToBToADoesNotMakeOldFrozenProfileCurrentAgain() = runBlocking {
        val access = access()
        val old = frozen(access)
        val first = access.agentConfigSourceIdentity()
        profile = "b"
        profile = "a"
        val third = access.agentConfigSourceIdentity()
        assertNotEquals(first, third)
        assertTrue(runCatching { withContext(old) { access.agentConfigSourceIdentity() } }.exceptionOrNull() is CancellationException)
    }

    @Test fun sameHostReconnectUsesNewUuidAndNeverOldWarmResult() {
        val access = access()
        val old = snapshot(access)
        val captured = identity
        identity = RemoteConnectionIdentity(mockk(), captured.home)
        setupOwner(identity.owner)
        body = "reconnected"
        val latest = snapshot(access)
        assertNotEquals(old.identity, latest.identity)
        assertEquals("reconnected", latest.files.values.single())
        assertEquals(4, batches)
    }

    @Test fun writeInvalidationRefreshesSamePhysicalGlobalRootAcrossWorkspaces() {
        val access = access()
        snapshot(access)
        root = "/other"
        snapshot(access)
        val channel = mockk<SFTPClient>(relaxed = true)
        every { connection.sftpOperationMutex } returns Mutex()
        coEvery { connection.sftp() } returns channel
        every { channel.statExistence(any()) } returns null
        every { channel.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) } returns mockk(relaxed = true)
        access.writeFile("~/.aicode/agents/one.md", "changed")
        body = "changed"
        root = "/project"
        assertEquals("changed", snapshot(access).files.values.single())
        root = "/other"
        assertEquals("changed", snapshot(access).files.values.single())
        assertEquals(8, batches)
    }

    @Test fun uncacheableAliasReturnsOptionalSnapshotAndOriginalProviderStillParsesDefinitions() = runBlocking {
        val access = access()
        val session = mockk<Session>(relaxed = true)
        val command = mockk<Session.Command>(relaxed = true)
        every { identity.owner.startSession() } returns session
        every { session.exec(any()) } returns command
        every { command.inputStream } answers { "AICODE_CONFIG_1 M\n".byteInputStream() }
        every { command.errorStream } answers { byteArrayOf().inputStream() }
        every { command.outputStream } returns ByteArrayOutputStream()
        every { command.exitStatus } returns 31
        every { command.exitSignal } returns null
        assertNull(access.agentConfigSnapshot("~/.aicode/agents", AgentConfigKind.AGENTS))
        val channel = mockk<SFTPClient>(relaxed = true)
        val info = mockk<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        val file = mockk<net.schmizz.sshj.sftp.RemoteFile>(relaxed = true)
        val text = "---\nname: linked\n---\noriginal provider body".toByteArray()
        coEvery { connection.openReadChannel(any()) } returns channel
        every { channel.statExistence(any()) } answers {
            val fileType = if (firstArg<String>().endsWith(".md")) net.schmizz.sshj.sftp.FileMode.Type.REGULAR else net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
            mockk<net.schmizz.sshj.sftp.FileAttributes> {
                every { type } returns fileType
                every { size } returns text.size.toLong()
            }
        }
        every { info.name } returns "linked.md"
        every { info.isDirectory } returns false
        every { info.attributes } returns mockk(relaxed = true)
        every { channel.ls(any()) } returns listOf(info)
        every { channel.open(any<String>()) } returns file
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>().toInt()
            val count = minOf(arg<Int>(3), text.size - offset)
            if (count <= 0) -1 else { text.copyInto(secondArg(), thirdArg(), offset, offset + count); count }
        }
        val execution = frozen(access)
        val parsed = withContext(execution) { com.aicode.feature.agent.domain.subagent.GlobalDirectoryAgentSource(access).listDefinitions() }
        assertEquals("linked", parsed.single().name)
        assertEquals("original provider body", parsed.single().prompt)
        verify(exactly = 2) { identity.owner.startSession() }
        verify(exactly = 1) { channel.open("/home/user/.aicode/agents/linked.md") }
    }

    @Test fun unrelatedWriteDoesNotInvalidateDefinitionsAndFrozenFileChecksKeepTheirOriginalMeaning() = runBlocking {
        val access = access()
        val frozen = frozen(access)
        val snapshot = snapshot(access)
        val channel = mockk<SFTPClient>(relaxed = true)
        every { connection.sftpOperationMutex } returns Mutex()
        coEvery { connection.sftp() } returns channel
        every { channel.statExistence(any()) } returns null
        every { channel.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) } returns mockk(relaxed = true)
        access.writeFile("~/workspace/readme.txt", "unrelated")
        assertSame(snapshot, snapshot(access))
        profile = "b"
        withContext(frozen) { frozen.checkCurrent() }
        assertEquals(2, batches)
    }
}
