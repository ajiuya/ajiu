package com.aicode.feature.agent.domain.skill

import android.content.Context
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.AgentConfigSnapshot
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteAgentConfigProfile
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.charset.CharsetDecoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import net.schmizz.sshj.SSHClient

internal class LocalSkillDefinitionFixture(root: File) {
    val files = File(root, "files").apply { mkdirs() }
    val first = File(root, "first").apply { mkdirs() }
    val second = File(root, "second").apply { mkdirs() }
    val context = mockk<Context>()
    val workspace = mockk<WorkspaceRepository>()
    val mode = ExecutionModeHolder()
    val settings = mockk<ContainerSettingsRepository>()
    val remote = mockk<RemoteSshConnection>()
    val backend = mockk<FileAccessProvider>()
    val installer = mockk<ContainerInstaller>()
    val projectRoot = mockk<ProjectAicodeRoot>()
    val hub = mockk<FileChangeHub>()
    val plugins = mockk<PluginDefinitionSource>()
    val factory = mockk<WorkspaceExecutionContextFactory>()
    val current = MutableStateFlow<Workspace?>(Workspace("first", first.path))
    val selection = MutableStateFlow(WorkspaceRepository.Selection(current.value))
    val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "uncreated-chroot", isBuiltin = false,
        localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
    val profiles = MutableStateFlow(listOf(profile))
    val selected = MutableStateFlow(profile.id)
    val selectionRevision = MutableStateFlow(0L)
    val configurationRevision = MutableStateFlow(0L)
    val snapshotOwner = Any()
    val remoteCache = RemoteAgentConfigCache(remote, settings, mode, workspace)
    var onProfileCheck: (() -> Unit)? = null
    var onRootCheck: (() -> Unit)? = null
    var contextReads = 0
    var pathReads = 0
    val source: LocalSkillDefinitionSource
    val config: SkillConfigRepository
    val repository: SkillRepository

    init {
        val trackedFiles = object : File(files.path) {
            override fun getAbsoluteFile(): File = this
            override fun getCanonicalFile(): File {
                onRootCheck?.invoke()
                return super.getCanonicalFile()
            }
        }
        every { context.filesDir } answers { contextReads++; trackedFiles }
        every { workspace.current } returns current
        every { workspace.selection } returns selection
        every { workspace.currentPath() } answers { pathReads++; current.value?.path ?: first.path }
        every { settings.activeProfileIdFlow } returns selected
        every { settings.customProfilesFlow } returns profiles
        every { settings.activeProfileSelectionRevision } answers { selectionRevision.value }
        every { settings.profileConfigurationRevision } answers { configurationRevision.value }
        every { settings.activeProfileSelectionRevisions } returns selectionRevision
        every { settings.profileConfigurationRevisions } returns configurationRevision
        coEvery { settings.captureProfileSnapshot() } answers {
            ContainerProfileSnapshot(selected.value, ContainerProfile.BUILTIN_ID, profiles.value,
                selectionRevision.value, configurationRevision.value, snapshotOwner)
        }
        every { settings.checkProfileSnapshot(any()) } answers {
            onProfileCheck?.invoke()
            val captured = firstArg<ContainerProfileSnapshot>()
            if (captured.repositoryIdentity !== snapshotOwner || captured.selectionRevision != selectionRevision.value ||
                captured.configurationRevision != configurationRevision.value) throw CancellationException("Profile source changed")
        }
        every { remote.config } returns null
        every { remote.identityState } returns MutableStateFlow(null)
        every { remote.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { installer.aicodeDir } returns File(files, "aicode")
        every { projectRoot.current() } answers { File(current.value?.path ?: first.path, ".aicode") }
        every { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns emptyFlow()
        every { plugins.revision } returns 0L
        every { plugins.skills() } returns emptyList()
        source = LocalSkillDefinitionSource(context, workspace, mode, settings, remote, remoteCache)
        config = SkillConfigRepository(installer, projectRoot, hub, backend, source)
        repository = SkillRepository(GlobalDirectorySkillSource(backend), ProjectDirectorySkillSource(backend), config, backend, plugins, source)
    }

    fun select(root: File) {
        val next = Workspace(root.name, root.path)
        selection.value = WorkspaceRepository.Selection(next)
        current.value = next
    }

    fun configFile(scope: SkillScope): File = when (scope) {
        SkillScope.GLOBAL -> File(files, "aicode/skills.json")
        SkillScope.PROJECT -> File(current.value?.path ?: first.path, ".aicode/skills.json")
        SkillScope.PLUGIN -> error("No plugin configuration file")
    }

    fun instruction(scope: SkillScope, directory: String, name: String, body: String, fileName: String = "SKILL.md"): File {
        val parent = if (scope == SkillScope.GLOBAL) File(files, "aicode/skills")
            else File(current.value?.path ?: first.path, ".aicode/skills")
        return File(File(parent, directory), fileName).apply {
            parentFile!!.mkdirs()
            writeText("---\nname: $name\ndescription: description\nrequired_tools: [readFile]\n---\n$body")
        }
    }

    fun disabled(scope: SkillScope, vararg names: String): File = configFile(scope).apply {
        parentFile!!.mkdirs()
        writeText(SkillConfigRepository.serializeDisabled(names.toSet()))
    }
}

class LocalSkillDefinitionSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sshCacheInvalidation_inTheSameSession_revokesPublishedSkillIdentityAndReadsFreshSnapshot() = runBlocking {
        val f = LocalSkillDefinitionFixture(temporary.newFolder())
        val client = mockk<SSHClient>()
        val connection = RemoteConnectionIdentity(client, "/home/fixture")
        val identity = MutableStateFlow<RemoteConnectionIdentity?>(connection)
        f.mode.setMode(ExecutionMode.REMOTE_SSH)
        every { f.remote.config } returns RemoteConnectionConfig("fixture-host", 22, "fixture", mockk(), f.first.path)
        every { f.remote.remoteHome } returns connection.home
        every { f.remote.identityState } returns identity
        every { f.remote.checkIdentity(connection) } returns Unit
        every { f.backend.agentConfigSourceIdentity() } returns "unchanged-backend-identity"
        every { f.backend.isFile("~/.aicode/skills.json") } returns false
        var body = "before invalidation"
        every { f.backend.agentConfigSnapshot(any(), any(), any()) } answers {
            val root = firstArg<String>()
            val files = if (root == "~/.aicode/skills") mapOf("$root/one/SKILL.md" to "---\nname: one\n---\n$body")
                else emptyMap()
            AgentConfigSnapshot("unchanged-backend-identity", "snapshot", files)
        }
        val remoteSource = RemoteSftpFileAccess.ExecutionSource(connection, f.first.path,
            RemoteAgentConfigProfile(f.profile.id, f.selectionRevision.value))
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote", f.backend, null, remoteSource) { }
        coEvery { f.factory.freeze(any(), any(), any()) } returns execution
        val published = f.repository.readDefinitionSnapshot(f.factory)
        assertEquals("before invalidation", published.enabled.single().skill.instructions)
        val actualCacheIdentity = f.remoteCache.sourceIdentity(remoteSource)
        f.remoteCache.invalidation("${connection.home}/.aicode/skills/one/SKILL.md").invoke()
        assertNotEquals("Real cache invalidation was not reached", actualCacheIdentity, f.remoteCache.sourceIdentity(remoteSource))
        assertEquals(connection, identity.value)
        assertThrows(SkillDefinitionSourceChanged::class.java) { f.repository.checkSourceIdentity(published.sourceIdentity) }
        body = "after invalidation"
        val fresh = f.repository.readDefinitionSnapshot(f.factory, forceRefresh = true)
        assertNotEquals(published.sourceIdentity, fresh.sourceIdentity)
        assertEquals("after invalidation", fresh.enabled.single().skill.instructions)
        f.repository.checkSourceIdentity(fresh.sourceIdentity)
        verify(exactly = 0) { client.startSession() }
        coVerify(exactly = 0) { f.remote.connect(any()) }
    }

    @Test fun identityAndCheck_areMetadataOnly_evenBeforeAnyCapture() {
        val f = LocalSkillDefinitionFixture(temporary.newFolder())
        f.onRootCheck = { throw AssertionError("Pure identity touched the filesystem") }
        val identity = f.repository.sourceIdentity()
        repeat(5) { f.repository.checkSourceIdentity(identity) }
        assertEquals(0, f.contextReads)
        assertEquals(0, f.pathReads)
        verify { f.backend wasNot Called }
        coVerify(exactly = 0) { f.settings.captureProfileSnapshot() }
        f.configurationRevision.value++
        assertNotEquals(identity, f.repository.sourceIdentity())
        assertThrows(SkillDefinitionSourceChanged::class.java) { f.repository.checkSourceIdentity(identity) }
        assertEquals(0, f.contextReads)
    }

    @Test fun uncreatedChroot_fixedRootsReadCompleteDefinitionsAndStrictDisabledUnion_withoutGuestFreeze() = runBlocking {
        val f = LocalSkillDefinitionFixture(temporary.newFolder())
        f.instruction(SkillScope.GLOBAL, "global", "shared", "global")
        f.instruction(SkillScope.PROJECT, "nested/real-directory", "SHARED", "complete project\n".repeat(1000))
        f.instruction(SkillScope.PROJECT, "nested/real-directory", "wrong", "legacy", "CLAUDE.md")
        f.instruction(SkillScope.GLOBAL, "fallback", "fallback", "claude only", "CLAUDE.md")
        f.disabled(SkillScope.GLOBAL, "SHARED")
        f.disabled(SkillScope.PROJECT, "FALLBACK")
        val capture = requireNotNull(f.source.capture())
        assertTrue(capture.profile.activeProfile.isChroot)
        assertNull(capture.profile.activeProfile.chrootInstance)
        assertEquals(listOf("fallback", "shared"), f.source.skills(capture, SkillScope.GLOBAL).map { it.name })
        val snapshot = f.repository.readDefinitionSnapshot(f.factory, forceRefresh = true)
        assertEquals(setOf("shared", "fallback"), snapshot.disabledNames)
        assertTrue(snapshot.enabled.isEmpty())
        val project = snapshot.entries.single { it.scope == SkillScope.PROJECT }.skill
        assertEquals("SHARED", project.name)
        assertEquals("complete project\n".repeat(1000).trim(), project.instructions)
        assertEquals("~/workspace/.aicode/skills/nested/real-directory", project.dirPath)
        assertEquals(listOf("readFile"), project.requiredTools)
        assertEquals("claude only", snapshot.entries.single { it.scope == SkillScope.GLOBAL }.skill.instructions)
        verify { f.backend wasNot Called }
        coVerify(exactly = 0) { f.factory.freeze(any(), any(), any()) }
    }

    @Test fun fixedScopeLinks_escapeLoopDanglingAndGuestAliases_areRejectedWithoutReadingTargets() = runBlocking {
        for (scope in listOf(SkillScope.GLOBAL, SkillScope.PROJECT)) for (kind in listOf("outside", "loop", "dangling", "guestAlias")) {
            val f = LocalSkillDefinitionFixture(temporary.newFolder())
            val other = temporary.newFolder()
            val sentinel = File(other, "SKILL.md").apply { writeText("outside sentinel") }
            val directory = if (scope == SkillScope.GLOBAL) File(f.files, "aicode/skills") else File(f.first, ".aicode/skills")
            directory.parentFile!!.mkdirs()
            val target = when (kind) {
                "loop" -> File("skills")
                "dangling" -> File("missing")
                "guestAlias" -> File("/root/workspace/skills")
                else -> other
            }
            Files.createSymbolicLink(directory.toPath(), target.toPath())
            val capture = requireNotNull(f.source.capture())
            val failure = runCatching { f.source.skills(capture, scope) }.exceptionOrNull()
            assertTrue("$scope/$kind did not reject the link", failure is IOException)
            assertEquals("outside sentinel", sentinel.readText())
            verify { f.backend wasNot Called }
        }
    }

    @Test fun workspaceModeProfileAndAbaChanges_duringRealDefinitionRead_rejectCapturedSource() = runBlocking {
        for (change in listOf("workspace", "workspaceABA", "mode", "modeABA", "sameIdProfile", "profileABA")) {
            val f = LocalSkillDefinitionFixture(temporary.newFolder())
            f.instruction(SkillScope.GLOBAL, "one", "one", "actual body")
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val once = AtomicBoolean(true)
            mockkStatic(Channels::class)
            every { Channels.newReader(any<ReadableByteChannel>(), any<CharsetDecoder>(), -1) } answers {
                val delegate = callOriginal() as Reader
                object : FilterReader(delegate) {
                    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                        val count = delegate.read(buffer, offset, length)
                        if (count > 0 && once.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            if (!release.await(15, TimeUnit.SECONDS)) throw IOException("Definition read barrier timed out")
                        }
                        return count
                    }
                    override fun close() = delegate.close()
                }
            }
            try {
                val operation = async { runCatching { f.repository.readDefinitionSnapshot(f.factory) } }
                withTimeout(5_000) { entered.await() }
                when (change) {
                    "workspace" -> f.select(f.second)
                    "workspaceABA" -> { f.select(f.second); f.select(f.first) }
                    "mode" -> f.mode.setMode(ExecutionMode.REMOTE_SSH)
                    "modeABA" -> { f.mode.setMode(ExecutionMode.REMOTE_SSH); f.mode.setMode(ExecutionMode.LOCAL_PROOT) }
                    "sameIdProfile" -> {
                        f.profiles.value = listOf(f.profile.copy(env = mapOf("EDITOR" to "vi")))
                        f.configurationRevision.value++
                    }
                    "profileABA" -> f.selectionRevision.value += 2
                }
                release.countDown()
                assertTrue("$change accepted stale definitions", operation.await().exceptionOrNull() is SkillDefinitionSourceChanged)
            } finally {
                release.countDown()
                unmockkStatic(Channels::class)
            }
            coVerify(exactly = 0) { f.factory.freeze(any(), any(), any()) }
        }
    }
}
