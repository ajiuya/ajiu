package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.FilterReader
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.charset.CharsetDecoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.AccessDeniedException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PermissionRulesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: PermissionRulesRepository
    private lateinit var globalFile: File
    private lateinit var target: File
    private lateinit var link: File

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any()) } returns Unit
        val context: Context = mockk()
        val workspace: WorkspaceRepository = mockk()
        val installer: ContainerInstaller = mockk()
        val settings: ContainerSettingsRepository = mockk()
        val home: PathHomeResolver = mockk()
        val projectRoot: ProjectAicodeRoot = mockk()
        val changes: FileChangeHub = mockk()
        val filesDir = tmp.newFolder("files")
        globalFile = File(filesDir, "aicode/permissions.json")
        val workspaceDir = tmp.newFolder("workspace")
        target = File(workspaceDir, "project/.aicode").apply { mkdirs() }
        link = File(workspaceDir, ".aicode")
        Files.createSymbolicLink(link.toPath(), File("/root/workspace/project/.aicode").toPath())
        every { context.filesDir } returns filesDir
        every { workspace.currentPath() } returns workspaceDir.absolutePath
        every { workspace.current } returns MutableStateFlow(null)
        every { workspace.selection } returns MutableStateFlow(WorkspaceRepository.Selection(null))
        every { installer.aicodeDir } returns File(filesDir, "aicode")
        every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
        stubSettings(settings, ProfileControls(ContainerProfile.BUILTIN_ALPINE))
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { settings.customProfilesFlow } returns emptyFlow()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns link
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        repository = PermissionRulesRepository(context, workspace, projectRoot, changes, mapper,
            ExecutionModeHolder(), settings, disconnectedRemote())
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    private class CollectorIoFailure(val marker: Any) : IOException("Collector-owned failure")
    private class CollectorCancellation(val marker: Any) : CancellationException("Collector-owned cancellation")

    private class ProfileControls(profile: ContainerProfile) {
        val selected = MutableStateFlow(profile.id)
        val profiles = MutableStateFlow(listOf(profile))
        val selectionRevision = MutableStateFlow(0L)
        val configurationRevision = MutableStateFlow(0L)
        val identity = Any()
        var onCheck: (() -> Unit)? = null
    }

    private fun stubSettings(settings: ContainerSettingsRepository, controls: ProfileControls) {
        every { settings.activeProfileIdFlow } returns controls.selected
        every { settings.customProfilesFlow } returns controls.profiles
        every { settings.activeProfileSelectionRevisions } returns controls.selectionRevision
        every { settings.profileConfigurationRevisions } returns controls.configurationRevision
        every { settings.activeProfileSelectionRevision } answers { controls.selectionRevision.value }
        every { settings.profileConfigurationRevision } answers { controls.configurationRevision.value }
        coEvery { settings.captureProfileSnapshot() } answers {
            ContainerProfileSnapshot(controls.selected.value, ContainerProfile.BUILTIN_ID, controls.profiles.value,
                controls.selectionRevision.value, controls.configurationRevision.value, controls.identity)
        }
        every { settings.checkProfileSnapshot(any()) } answers {
            controls.onCheck?.invoke()
            val snapshot = firstArg<ContainerProfileSnapshot>()
            if (snapshot.repositoryIdentity !== controls.identity ||
                snapshot.selectionRevision != controls.selectionRevision.value ||
                snapshot.configurationRevision != controls.configurationRevision.value) {
                throw CancellationException("Test profile source changed")
            }
        }
    }

    private fun disconnectedRemote(): RemoteSshConnection = mockk<RemoteSshConnection>().also { remote ->
        every { remote.config } returns null
        every { remote.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { remote.identityState } returns MutableStateFlow(null)
    }

    private data class ColdChrootFixture(
        val repository: PermissionRulesRepository,
        val globalFile: File,
        val projectFile: File,
        val installer: ContainerInstaller
    )

    private suspend fun coldChrootFixture(): ColdChrootFixture {
        val context = mockk<Context>()
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val projectRoot = mockk<ProjectAicodeRoot>()
        val changes = mockk<FileChangeHub>()
        val filesDir = tmp.newFolder("cold-files")
        val workspaceDir = tmp.newFolder("cold-workspace")
        val projectDir = File(workspaceDir, ".aicode").apply { mkdirs() }
        val selectedWorkspace = Workspace("cold-project", workspaceDir.absolutePath)
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "uncreated-chroot",
            isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = null
        )
        val profiles = ProfileControls(profile)
        every { context.filesDir } returns filesDir
        every { workspace.currentPath() } returns workspaceDir.absolutePath
        every { workspace.current } returns MutableStateFlow(selectedWorkspace)
        every { workspace.selection } returns MutableStateFlow(WorkspaceRepository.Selection(selectedWorkspace))
        every { installer.aicodeDir } returns File(filesDir, "aicode")
        every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
        stubSettings(settings, profiles)
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns projectDir
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        val ready = withTimeout(5_000) {
            mapper.runtimeProfileState.first { it.ready && it.profile == profile }
        }
        assertTrue(ready.profile.isChroot)
        assertNull(ready.profile.chrootInstance)
        assertNull(WorkspaceExecutionContext.current())
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) {
            mapper.resolveHostFile(File(filesDir, "aicode/permissions.json"))
        }
        return ColdChrootFixture(
            PermissionRulesRepository(context, workspace, projectRoot, changes, mapper,
                ExecutionModeHolder(), settings, disconnectedRemote()),
            File(filesDir, "aicode/permissions.json"),
            File(projectDir, "permissions.json"),
            installer
        )
    }

    private class WatchToken(val directory: File)
    private data class WatchActivity(
        val requested: Map<File, WatchToken> = emptyMap(),
        val active: Set<WatchToken> = emptySet()
    )

    private inner class PolicyFixture(initialProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE,
        mapperGate: CompletableDeferred<Unit>? = null) {
        val filesDir = tmp.newFolder()
        val firstWorkspace = tmp.newFolder()
        val secondWorkspace = tmp.newFolder()
        val context = mockk<Context>()
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val changes = mockk<FileChangeHub>()
        val mode = ExecutionModeHolder()
        val controls = ProfileControls(initialProfile)
        val remote = disconnectedRemote()
        val current = MutableStateFlow<Workspace?>(Workspace("first", firstWorkspace.path))
        val selection = MutableStateFlow(WorkspaceRepository.Selection(current.value))
        val events = MutableSharedFlow<FileChangeBatch>(extraBufferCapacity = 16)
        private val watchActivity = MutableStateFlow(WatchActivity())
        val projectRoot: ProjectAicodeRoot
        val mapper: WorkspacePathMapper
        val repo: PermissionRulesRepository
        var onGlobalCheck: (() -> Unit)? = null
        var onSelectionRead: (() -> Unit)? = null
        var beforeWatchCollect: suspend (File) -> Unit = { }
        val globalFile: File get() = File(filesDir, "aicode/permissions.json")
        val projectFile: File get() = File(projectRoot.current(), "permissions.json")

        init {
            val trackedFiles = object : File(filesDir.path) {
                override fun getAbsoluteFile(): File = this
                override fun getCanonicalFile(): File {
                    onGlobalCheck?.invoke()
                    return super.getCanonicalFile()
                }
            }
            every { context.filesDir } returns trackedFiles
            every { workspace.currentPath() } answers { current.value?.path ?: firstWorkspace.path }
            every { workspace.current } returns current
            every { workspace.selection } answers { onSelectionRead?.invoke(); selection }
            every { installer.aicodeDir } returns File(filesDir, "aicode")
            every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
            every { home.home() } returns "/root"
            every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
            stubSettings(settings, controls)
            if (mapperGate != null) {
                every { settings.activeProfileIdFlow } returns kotlinx.coroutines.flow.flow {
                    mapperGate.await()
                    emit(controls.selected.value)
                }
            }
            every { changes.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } answers {
                val directory = firstArg<File>()
                kotlinx.coroutines.flow.flow {
                    val token = WatchToken(directory.canonicalFile)
                    watchActivity.update { it.copy(requested = it.requested + (token.directory to token)) }
                    try {
                        beforeWatchCollect(directory)
                        emitAll(events.onSubscription {
                            watchActivity.update { it.copy(active = it.active + token) }
                        }.filter { batch -> batch.changes.any { File(it.hostPath).toPath().startsWith(directory.toPath()) } })
                    } finally {
                        watchActivity.update { state ->
                            state.copy(
                                requested = if (state.requested[token.directory] === token)
                                    state.requested - token.directory else state.requested,
                                active = state.active - token
                            )
                        }
                    }
                }
            }
            projectRoot = ProjectAicodeRoot(workspace, mode, installer, remote)
            mapper = WorkspacePathMapper(workspace, installer, settings, home)
            repo = PermissionRulesRepository(context, workspace, projectRoot, changes, mapper, mode, settings, remote)
        }

        fun select(directory: File) {
            val selected = Workspace(directory.name, directory.path)
            selection.value = WorkspaceRepository.Selection(selected)
            current.value = selected
        }

        fun peer(host: String, port: Int = 22) {
            every { remote.config } returns RemoteConnectionConfig(host, port, "tester", mockk(), "/same/remote")
        }

        fun write(file: File, rules: List<PermissionRule>) {
            file.parentFile!!.mkdirs()
            file.writeText(Json.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
        }

        suspend fun awaitProfile() = withTimeout(5_000) {
            mapper.runtimeProfileState.first { it.ready && it.profile == controls.profiles.value.first() }
        }

        suspend fun watchReady() = withTimeout(5_000) {
            val global = globalFile.parentFile.canonicalFile
            val project = projectFile.parentFile.canonicalFile
            watchActivity.first { state ->
                listOf(global, project).all { directory ->
                    state.requested[directory]?.let { it in state.active } == true
                }
            }
            Unit
        }

        suspend fun changed(file: File) {
            events.emit(FileChangeBatch(listOf(FileChange(ChangeRoot.AICODE, file.path,
                "/root/.aicode/permissions.json", ChangeKind.MODIFIED))))
        }

        fun stopWatching() {
            val field = PermissionRulesRepository::class.java.getDeclaredField("watchScope").apply { isAccessible = true }
            (field.get(repo) as CoroutineScope).cancel()
        }
    }

    private suspend fun expectFailure(label: String = "policy operation", block: suspend () -> Unit): Throwable {
        val failure = try { block(); null } catch (failure: Throwable) { failure }
        assertTrue("Expected a policy source or IO failure ($label); actual=${failure?.javaClass?.simpleName ?: "success"}", failure is Exception)
        return failure!!
    }

    @Test
    fun coldChroot_missingGlobalFile_emitsEmptyWithoutRuntimeAuthorization() = runBlocking {
        val fixture = coldChrootFixture()
        assertFalse(fixture.globalFile.exists())

        assertEquals(emptyList<PermissionRule>(), fixture.repository.globalRulesFlow.first())
        assertFalse(fixture.globalFile.exists())
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
    }

    @Test
    fun coldChroot_existingGlobalDeny_readsAndPreservesDenyWithoutRuntimeAuthorization() = runBlocking {
        val fixture = coldChrootFixture()
        fixture.globalFile.parentFile!!.mkdirs()
        fixture.globalFile.writeText("""{"permissions":{"deny":["Bash(git push)"]}}""")
        val deny = PermissionRule("Bash", "git push", PermissionDecision.DENY)

        assertEquals(listOf(deny), fixture.repository.globalRulesFlow.first())
        assertEquals(listOf(deny), fixture.repository.getGlobalRulesOnce())
        assertEquals(
            listOf(deny),
            Json.decodeFromString(PermissionFile.serializer(), fixture.globalFile.readText()).toRuleList()
        )
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
    }

    @Test
    fun coldChroot_existingHostProjectDeny_emitsAndMergesEffectiveRulesWithoutRuntimeAuthorization() = runBlocking {
        val fixture = coldChrootFixture()
        fixture.globalFile.parentFile!!.mkdirs()
        fixture.globalFile.writeText("""{"permissions":{"deny":["Bash(git push)"]}}""")
        fixture.projectFile.writeText("""{"permissions":{"deny":["writeFile"]}}""")
        val projectDeny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
        val globalDeny = PermissionRule("Bash", "git push", PermissionDecision.DENY)

        assertEquals(listOf(projectDeny), fixture.repository.currentProjectRulesFlow.first())
        assertEquals(
            listOf(projectDeny, globalDeny),
            fixture.repository.loadEffectiveForCurrentProject()
        )
        assertEquals(
            listOf(projectDeny),
            Json.decodeFromString(PermissionFile.serializer(), fixture.projectFile.readText()).toRuleList()
        )
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
    }

    @Test
    fun fixedHostPolicies_loadBeforeMapperReady_withoutGuestAuthorization() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "cold-chroot", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        val f = PolicyFixture(profile, gate)
        val global = listOf(PermissionRule("Bash", "git push", PermissionDecision.DENY))
        val project = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        f.write(f.globalFile, global)
        f.write(f.projectFile, project)
        try {
            assertFalse(f.mapper.runtimeProfileState.value.ready)
            assertEquals(global, withTimeout(5_000) { f.repo.globalRulesFlow.first() })
            assertEquals(project, withTimeout(5_000) { f.repo.currentProjectRulesFlow.first() })
            assertEquals(project + global, f.repo.loadEffectiveForCurrentProject())
            assertFalse(f.mapper.runtimeProfileState.value.ready)
            assertNull(WorkspaceExecutionContext.current())
            verify(exactly = 0) { f.installer.rootfsDirFor(any()) }
        } finally {
            gate.complete(Unit)
        }
        f.awaitProfile()
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { f.mapper.resolveHostFile(f.projectFile) }
        assertTrue(f.mapper.runtimeProfileState.value.ready)
    }

    @Test
    fun remoteSamePath_differentHostAndPort_andLocalSwitch_keepPolicyRootsAndCachesIsolated() = runBlocking {
        val f = PolicyFixture()
        f.write(f.projectFile, listOf(PermissionRule("local", "*", PermissionDecision.DENY)))
        assertEquals(listOf(PermissionRule("local", "*", PermissionDecision.DENY)), f.repo.loadEffectiveForCurrentProject())
        f.mode.setMode(ExecutionMode.REMOTE_SSH)
        f.current.value = Workspace("remote", "/same/remote/project")
        f.selection.value = WorkspaceRepository.Selection(f.current.value)
        val files = mutableSetOf<File>()
        for ((host, port, tool) in listOf(Triple("alpha", 22, "hostA"), Triple("beta", 22, "hostB"), Triple("beta", 2200, "portB"))) {
            f.peer(host, port)
            val file = f.projectFile
            files.add(file)
            val expected = listOf(PermissionRule(tool, "*", PermissionDecision.DENY))
            f.write(file, expected)
            assertEquals(expected, f.repo.projectRulesFlow("remote").first())
            assertEquals(expected, f.repo.loadEffectiveForCurrentProject())
        }
        assertEquals(3, files.size)
        f.peer("alpha", 22)
        assertEquals(listOf(PermissionRule("hostA", "*", PermissionDecision.DENY)), f.repo.loadEffectiveForCurrentProject())
        f.mode.setMode(ExecutionMode.LOCAL_PROOT)
        f.select(f.firstWorkspace)
        assertEquals(listOf(PermissionRule("local", "*", PermissionDecision.DENY)), f.repo.loadEffectiveForCurrentProject())
        coVerify(exactly = 0) { f.remote.connect(any()) }
        verify(exactly = 0) { f.installer.rootfsDirFor(any()) }
    }

    @Test
    fun effectiveRead_workspaceModeProfileAndAbaChangesDuringIo_rejectOldSource() = runBlocking {
        for (change in listOf("workspace", "workspaceABA", "mode", "modeABA", "profile", "profileABA")) {
            val f = PolicyFixture()
            val deny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
            f.write(f.projectFile, listOf(deny))
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
                            if (!release.await(15, TimeUnit.SECONDS)) throw IOException("Policy read barrier timed out")
                        }
                        return count
                    }
                    override fun close() = delegate.close()
                }
            }
            try {
                val operation = async { runCatching { f.repo.loadEffectiveForCurrentProject() } }
                withTimeout(5_000) { entered.await() }
                when (change) {
                    "workspace" -> f.select(f.secondWorkspace)
                    "workspaceABA" -> { f.select(f.secondWorkspace); f.select(f.firstWorkspace) }
                    "mode" -> f.mode.setMode(ExecutionMode.REMOTE_SSH)
                    "modeABA" -> { f.mode.setMode(ExecutionMode.REMOTE_SSH); f.mode.setMode(ExecutionMode.LOCAL_PROOT) }
                    "profile" -> {
                        f.controls.profiles.value = listOf(ContainerProfile.BUILTIN_ALPINE.copy(env = mapOf("EDITOR" to "vi")))
                        f.controls.configurationRevision.value++
                    }
                    "profileABA" -> { f.controls.selectionRevision.value += 2 }
                }
                release.countDown()
                assertTrue("Old $change source was accepted", operation.await().exceptionOrNull() is CancellationException)
            } finally {
                release.countDown()
                unmockkStatic(Channels::class)
            }
            assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(),
                File(f.firstWorkspace, ".aicode/permissions.json").readText()).toRuleList())
        }
    }

    @Test
    fun projectFlow_switchesForProfileModeAndWorkspaceWithoutPublishingOldCache() = runBlocking {
        val f = PolicyFixture()
        val local = listOf(PermissionRule("local", "*", PermissionDecision.DENY))
        val next = listOf(PermissionRule("next", "*", PermissionDecision.DENY))
        f.write(f.projectFile, local)
        f.write(File(f.secondWorkspace, ".aicode/permissions.json"), next)
        val values = Channel<List<PermissionRule>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.repo.currentProjectRulesFlow.collect { values.send(it) } }
        try {
            withTimeout(5_000) { while (values.receive() != local) Unit }
            f.select(f.secondWorkspace)
            withTimeout(5_000) { while (values.receive() != next) Unit }
            val remote = listOf(PermissionRule("remote", "*", PermissionDecision.DENY))
            f.peer("remote-host", 2222)
            f.mode.setMode(ExecutionMode.REMOTE_SSH)
            f.write(f.projectFile, remote)
            f.controls.configurationRevision.value++
            withTimeout(5_000) { while (values.receive() != remote) Unit }
            val edited = listOf(PermissionRule("profile", "*", PermissionDecision.DENY))
            f.write(f.projectFile, edited)
            f.controls.configurationRevision.value++
            withTimeout(5_000) { while (values.receive() != edited) Unit }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun legacyProjectLinks_escapeLoopDanglingAndAliases_neverReadOrWriteOutsideWorkspace() = runBlocking {
        for (kind in listOf("outside", "otherProject", "global", "rootfs", "loop", "dangling")) {
            val f = PolicyFixture()
            f.awaitProfile()
            val outside = when (kind) {
                "otherProject" -> File(f.secondWorkspace, ".aicode")
                "global" -> f.globalFile.parentFile
                "rootfs" -> File(f.filesDir, "rootfs/etc")
                else -> File(tmp.newFolder(), "outside")
            }.apply { mkdirs() }
            val original = if (kind == "global") """{"permissions":{"deny":["outside-sentinel"]}}""" else "outside-sentinel-not-json"
            val sentinel = File(outside, "permissions.json").apply { writeText(original) }
            val link = File(f.firstWorkspace, ".aicode")
            val destination = when (kind) {
                "loop" -> File(".aicode")
                "dangling" -> File("missing/.aicode")
                else -> outside
            }
            Files.createSymbolicLink(link.toPath(), destination.toPath())
            assertTrue("Unsafe $kind link was read", expectFailure { f.repo.loadEffectiveForCurrentProject() } is IOException)
            assertTrue(expectFailure { f.repo.removeProjectRule("first", PermissionRule("writeFile", "*")) } is IOException)
            assertEquals(original, sentinel.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }
    }

    @Test
    fun chrootAndRemote_projectLinksAndContainerAliases_areExplicitErrorsWithoutMapperFallback() = runBlocking {
        val chroot = ContainerProfile.BUILTIN_ALPINE.copy(id = "new-chroot", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        for (remote in listOf(false, true)) {
            val f = PolicyFixture(chroot)
            f.awaitProfile()
            if (remote) { f.mode.setMode(ExecutionMode.REMOTE_SSH); f.peer("remote") }
            f.projectFile.parentFile.parentFile!!.mkdirs()
            Files.createSymbolicLink(f.projectFile.parentFile.toPath(), File("/root/workspace/project/.aicode").toPath())
            assertTrue(expectFailure { f.repo.loadEffectiveForCurrentProject() } is IOException)
            verify(exactly = 0) { f.installer.rootfsDirFor(any()) }
        }
    }

    @Test
    fun globalPolicyLink_cannotBorrowProjectOrOutsideRules() = runBlocking {
        val f = PolicyFixture()
        val outside = tmp.newFolder()
        val sentinel = File(outside, "permissions.json").apply { writeText("outside-sentinel-not-json") }
        Files.createSymbolicLink(f.globalFile.parentFile.toPath(), outside.toPath())
        assertTrue(expectFailure { f.repo.getGlobalRulesOnce() } is IOException)
        assertTrue(expectFailure { f.repo.setGlobalRules(emptyList()) } is IOException)
        assertEquals("outside-sentinel-not-json", sentinel.readText())
    }

    @Test
    fun invalidPolicy_directoryUnreadableAndMalformed_neverBecomeEmptyOrHideDeny() = runBlocking {
        for (global in listOf(true, false)) for (kind in listOf("directory", "readIoFault", "malformed")) {
            val f = PolicyFixture()
            val file = if (global) f.globalFile else f.projectFile
            val otherFile = if (global) f.projectFile else f.globalFile
            val deny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
            f.write(file, listOf(deny))
            assertEquals(listOf(deny), f.repo.loadEffectiveForCurrentProject())
            val cacheField = PermissionRulesRepository::class.java.getDeclaredField(if (global) "globalCache" else "projectCache")
                .apply { isAccessible = true }
            val loadedCache = cacheField.get(f.repo)
            val interceptions = AtomicInteger()
            val readFailure = AccessDeniedException("policy-file", null, "Injected policy read IO failure")
            when (kind) {
                "directory" -> { Files.delete(file.toPath()); Files.createDirectory(file.toPath()) }
                "readIoFault" -> {
                    val previousSize = file.length()
                    file.appendText("\n")
                    assertEquals("$global/$kind did not invalidate the loaded file", previousSize + 1, file.length())
                    assertTrue("$global/$kind lost the real policy file", Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                    assertFalse("$global/$kind is not the fixture's only policy file", Files.exists(otherFile.toPath(), NOFOLLOW_LINKS))
                    mockkStatic(Channels::class)
                    every { Channels.newReader(any<ReadableByteChannel>(), any<CharsetDecoder>(), -1) } answers {
                        val channel = firstArg<ReadableByteChannel>()
                        assertTrue("$global/$kind did not open the real file channel", channel is FileChannel && channel.isOpen)
                        val delegate = callOriginal() as Reader
                        object : FilterReader(delegate) {
                            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                                if (length == 0) return delegate.read(buffer, offset, length)
                                interceptions.incrementAndGet()
                                throw readFailure
                            }
                            override fun close() = delegate.close()
                        }
                    }
                }
                "malformed" -> file.writeText("{not-json}")
            }
            var originalBytes = file.takeIf { kind == "readIoFault" }?.readBytes()
            try {
                when (kind) {
                    "directory" -> assertTrue("$global/$kind setup did not create directory", Files.isDirectory(file.toPath()))
                    "malformed" -> assertEquals("$global/$kind setup did not replace JSON", "{not-json}", file.readText())
                }
                if (global) {
                    val before = interceptions.get()
                    val failure = expectFailure("global/$kind/getGlobalRulesOnce") { f.repo.getGlobalRulesOnce() }
                    if (kind == "readIoFault") {
                        assertTrue("Global policy read fault was bypassed", interceptions.get() > before)
                        assertSame("Global policy read fault was replaced", readFailure, failure)
                        assertArrayEquals("Global once-read failure changed DENY bytes", originalBytes!!, file.readBytes())
                        assertSame("Global once-read failure replaced the DENY cache", loadedCache, cacheField.get(f.repo))
                        val previousSize = file.length()
                        file.appendText("\n")
                        assertEquals("Global effective read did not invalidate the file", previousSize + 1, file.length())
                        originalBytes = file.readBytes()
                    }
                }
                val before = interceptions.get()
                val failure = expectFailure("${if (global) "global" else "project"}/$kind/loadEffective") {
                    f.repo.loadEffectiveForCurrentProject()
                }
                if (kind == "readIoFault") {
                    assertTrue("$global effective read bypassed the policy IO fault", interceptions.get() > before)
                    assertSame("$global policy read fault was replaced", readFailure, failure)
                    assertArrayEquals("$global read failure changed DENY bytes", originalBytes!!, file.readBytes())
                    val cache = cacheField.get(f.repo)
                    assertTrue("$global read failure discarded the loaded cache", cache != null)
                    assertSame("$global read failure replaced the DENY cache", loadedCache, cache)
                    val cachedRules = cache.javaClass.getDeclaredField("rules").apply { isAccessible = true }.get(cache)
                    assertEquals("$global read failure discarded cached DENY", listOf(deny), cachedRules)
                }
            } finally {
                if (kind == "readIoFault") unmockkStatic(Channels::class)
            }
            if (kind == "readIoFault") {
                assertArrayEquals(originalBytes!!, file.readBytes())
                assertEquals(listOf(deny), f.repo.loadEffectiveForCurrentProject())
                if (global) assertEquals(listOf(deny), f.repo.getGlobalRulesOnce())
            }
        }
    }

    @Test
    fun watchReadinessRegression_waitsForActualSharedFlowCollector() = runBlocking {
        val f = PolicyFixture()
        val first = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        val deny = first + PermissionRule("Bash", "git push", PermissionDecision.DENY)
        f.awaitProfile()
        f.write(f.globalFile, first)
        f.write(f.projectFile, emptyList())
        val globalGateEntered = CompletableDeferred<Unit>()
        val releaseGlobalCollect = CompletableDeferred<Unit>()
        f.beforeWatchCollect = { directory ->
            if (directory.canonicalFile == f.globalFile.parentFile.canonicalFile) {
                globalGateEntered.complete(Unit)
                releaseGlobalCollect.await()
            }
        }
        val emissions = Channel<List<PermissionRule>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.globalRulesFlow.collect { emissions.send(it) }
        }
        var readiness: Deferred<Unit>? = null
        try {
            f.repo.startWatching()
            withTimeout(5_000) {
                while (emissions.receive() != first) Unit
                globalGateEntered.await()
                // GLOBAL is gated before collection, so this subscriber must be PROJECT.
                f.events.subscriptionCount.first { it >= 1 }
            }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { f.watchReady() }
            readiness = waiting
            assertFalse(
                "watchReady completed before the GLOBAL SharedFlow collector was registered",
                waiting.isCompleted
            )
            releaseGlobalCollect.complete(Unit)
            withTimeout(5_000) {
                waiting.await()
                f.events.subscriptionCount.first { it >= 2 }
            }
            f.write(f.globalFile, deny)
            f.changed(f.globalFile)
            withTimeout(5_000) { while (emissions.receive() != deny) Unit }
        } finally {
            releaseGlobalCollect.complete(Unit)
            readiness?.cancel()
            job.cancel()
            f.stopWatching()
            readiness?.join()
            job.join()
        }
    }

    @Test
    fun globalWatch_firstLoadAndRefreshErrors_recoverWithoutFatalEmptyOrStaleRules() = runBlocking {
        val f = PolicyFixture()
        f.awaitProfile()
        val warnings = Channel<Unit>(Channel.UNLIMITED)
        every { FileLogger.w(any(), any()) } answers { warnings.trySend(Unit); Unit }
        f.globalFile.parentFile!!.mkdirs()
        f.globalFile.writeText("{broken-first}")
        f.projectFile.parentFile!!.mkdirs()
        val values = CopyOnWriteArrayList<List<PermissionRule>>()
        val emissions = Channel<List<PermissionRule>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.repo.globalRulesFlow.collect { values.add(it); emissions.send(it) } }
        f.repo.startWatching()
        f.repo.startWatching()
        try {
            withTimeout(5_000) { warnings.receive() }
            assertTrue(values.isEmpty())
            f.watchReady()
            val deny = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
            f.write(f.globalFile, deny)
            f.changed(f.globalFile)
            withTimeout(5_000) { while (emissions.receive() != deny) Unit }
            while (warnings.tryReceive().isSuccess) Unit
            f.globalFile.writeText("{broken-refresh}")
            f.changed(f.globalFile)
            withTimeout(5_000) { warnings.receive() }
            assertTrue(job.isActive)
            assertTrue(values.all { it == deny })
            expectFailure { f.repo.loadEffectiveForCurrentProject() }
            val recovered = deny + PermissionRule("Bash", "git push", PermissionDecision.DENY)
            f.write(f.globalFile, recovered)
            f.changed(f.globalFile)
            withTimeout(5_000) { while (emissions.receive() != recovered) Unit }
            assertTrue(values.all { it.isNotEmpty() })
        } finally {
            job.cancel()
            f.stopWatching()
        }
    }

    @Test
    fun projectWatch_refreshErrorAndCollectorCancellation_doNotPublishFallbackData() = runBlocking {
        val f = PolicyFixture()
        f.awaitProfile()
        val warnings = Channel<Unit>(Channel.UNLIMITED)
        every { FileLogger.w(any(), any()) } answers { warnings.trySend(Unit); Unit }
        val first = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        f.write(f.projectFile, first)
        f.globalFile.parentFile!!.mkdirs()
        val values = CopyOnWriteArrayList<List<PermissionRule>>()
        val emissions = Channel<List<PermissionRule>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.repo.currentProjectRulesFlow.collect { values.add(it); emissions.send(it) } }
        f.repo.startWatching()
        try {
            withTimeout(5_000) { while (emissions.receive() != first) Unit }
            f.watchReady()
            f.projectFile.writeText("{broken-project}")
            f.changed(f.projectFile)
            withTimeout(5_000) { warnings.receive() }
            assertTrue(job.isActive)
            assertTrue(values.all { it == first })
            expectFailure { f.repo.loadEffectiveForCurrentProject() }
            val recovered = first + PermissionRule("Bash", "git push", PermissionDecision.DENY)
            f.write(f.projectFile, recovered)
            f.changed(f.projectFile)
            withTimeout(5_000) { while (emissions.receive() != recovered) Unit }
            assertTrue(values.all { it.isNotEmpty() })
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        } finally {
            job.cancel()
            f.stopWatching()
        }
    }

    @Test
    fun projectWrite_sourceChangeAndAbaDuringIo_neverWriteNewProjectOrPublishOldSource() = runBlocking {
        for (aba in listOf(false, true)) {
            val f = PolicyFixture()
            val deny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
            val first = f.projectFile
            val second = File(f.secondWorkspace, ".aicode/permissions.json")
            f.write(first, listOf(deny))
            f.write(second, listOf(deny))
            val armed = AtomicBoolean(true)
            f.onGlobalCheck = {
                if (armed.compareAndSet(true, false)) {
                    f.select(f.secondWorkspace)
                    if (aba) f.select(f.firstWorkspace)
                }
            }
            val promoted = PermissionRule("Bash", "git push", PermissionDecision.DENY)
            assertTrue(expectFailure { f.repo.promoteToGlobal("first", promoted) } is CancellationException)
            assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), first.readText()).toRuleList())
            assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), second.readText()).toRuleList())
        }
    }

    @Test
    fun projectWrite_workspaceModeProfileAndAbaChangesDuringTemporaryFileIo_rejectBeforeReplace() = runBlocking {
        for (change in listOf("workspace", "workspaceABA", "mode", "modeABA", "profile", "profileABA")) {
            val f = PolicyFixture()
            val deny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
            val first = f.projectFile
            val second = File(f.secondWorkspace, ".aicode/permissions.json")
            f.write(first, listOf(deny))
            f.write(second, listOf(deny))
            val firstBytes = first.readBytes()
            val secondBytes = second.readBytes()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val once = AtomicBoolean(true)
            f.controls.onCheck = {
                val staged = first.parentFile!!.listFiles().orEmpty().any {
                    it.name.startsWith(".permissions-") && it.name.endsWith(".tmp") &&
                        Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
                }
                if (staged && once.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    if (!release.await(15, TimeUnit.SECONDS)) throw IOException("Policy write barrier timed out")
                }
            }
            try {
                val operation = async { runCatching { f.repo.removeProjectRule("first", deny) } }
                withTimeout(5_000) {
                    select<Unit> {
                        entered.onAwait { }
                        operation.onAwait { result ->
                            throw AssertionError("$change write completed before temporary-file barrier; result=$result")
                        }
                    }
                }
                when (change) {
                    "workspace" -> f.select(f.secondWorkspace)
                    "workspaceABA" -> { f.select(f.secondWorkspace); f.select(f.firstWorkspace) }
                    "mode" -> f.mode.setMode(ExecutionMode.REMOTE_SSH)
                    "modeABA" -> { f.mode.setMode(ExecutionMode.REMOTE_SSH); f.mode.setMode(ExecutionMode.LOCAL_PROOT) }
                    "profile" -> f.controls.configurationRevision.value++
                    "profileABA" -> f.controls.selectionRevision.value += 2
                }
                release.countDown()
                assertTrue("Old $change write was accepted", operation.await().exceptionOrNull() is CancellationException)
                assertArrayEquals("The captured project was replaced for $change", firstBytes, first.readBytes())
                assertArrayEquals("The new project was written for $change", secondBytes, second.readBytes())
                assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), first.readText()).toRuleList())
                assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), second.readText()).toRuleList())
            } finally {
                release.countDown()
                f.controls.onCheck = null
            }
        }
    }

    @Test
    fun fixedGlobalCacheAndFlow_ignoreUnavailableProjectSource() = runBlocking {
        val f = PolicyFixture()
        val deny = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        f.write(f.globalFile, deny)
        assertEquals(deny, f.repo.getGlobalRulesOnce())
        coEvery { f.settings.captureProfileSnapshot() } throws IOException("Project source is unavailable")
        f.mode.setMode(ExecutionMode.REMOTE_SSH)
        f.controls.configurationRevision.value++
        assertEquals(deny, withTimeout(5_000) { f.repo.globalRulesFlow.first() })
        assertEquals(deny, f.repo.getGlobalRulesOnce())
        coVerify(exactly = 0) { f.remote.connect(any()) }
        verify(exactly = 0) { f.installer.rootfsDirFor(any()) }
    }

    @Test
    fun failedGlobalWrite_keepsPreviousFileAndCacheDeny() = runBlocking {
        val f = PolicyFixture()
        val deny = PermissionRule("writeFile", "*", PermissionDecision.DENY)
        f.write(f.globalFile, listOf(deny))
        assertEquals(listOf(deny), f.repo.getGlobalRulesOnce())
        val original = f.globalFile.readText()
        val escaped = tmp.newFolder()
        val sentinel = File(escaped, "permissions.json").apply { writeText("outside") }
        val armed = AtomicBoolean(true)
        f.onGlobalCheck = {
            if (armed.compareAndSet(true, false)) {
                Files.move(f.globalFile.parentFile.toPath(), File(f.filesDir, "saved-aicode").toPath())
                Files.createSymbolicLink(f.globalFile.parentFile.toPath(), escaped.toPath())
            }
        }
        assertTrue(expectFailure { f.repo.setGlobalRules(emptyList()) } is IOException)
        assertEquals(original, File(f.filesDir, "saved-aicode/permissions.json").readText())
        assertEquals("outside", sentinel.readText())
        f.onGlobalCheck = null
        Files.delete(f.globalFile.parentFile.toPath())
        Files.move(File(f.filesDir, "saved-aicode").toPath(), f.globalFile.parentFile.toPath())
        assertEquals(listOf(deny), f.repo.getGlobalRulesOnce())
    }

    @Test
    fun promoteGlobalFailure_preservesProjectDeny_andProjectFailureAllowsDuplicateDeny() = runBlocking {
        val deny = PermissionRule("Bash", "git push", PermissionDecision.DENY)
        val failedGlobal = PolicyFixture()
        failedGlobal.write(failedGlobal.projectFile, listOf(deny))
        failedGlobal.globalFile.parentFile!!.mkdirs()
        failedGlobal.globalFile.mkdir()
        expectFailure { failedGlobal.repo.promoteToGlobal("first", deny) }
        assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), failedGlobal.projectFile.readText()).toRuleList())

        val failedProject = PolicyFixture()
        failedProject.write(failedProject.projectFile, listOf(deny))
        val original = failedProject.projectFile.readText()
        var staged = false
        failedProject.controls.onCheck = {
            if (!staged && failedProject.globalFile.isFile && failedProject.globalFile.readText().contains("git push")) {
                staged = true
                Files.move(failedProject.projectFile.toPath(), File(failedProject.projectFile.parentFile, "saved-permissions").toPath())
                Files.createDirectory(failedProject.projectFile.toPath())
            }
        }
        expectFailure { failedProject.repo.promoteToGlobal("first", deny) }
        assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), failedProject.globalFile.readText()).toRuleList())
        assertEquals(original, File(failedProject.projectFile.parentFile, "saved-permissions").readText())
    }

    @Test
    fun sourceChangedFlow_recoversWhileRealCollectorCancellationPropagates() = runBlocking {
        val f = PolicyFixture()
        val deny = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        f.write(f.projectFile, deny)
        val armed = AtomicBoolean(true)
        f.controls.onCheck = {
            if (armed.compareAndSet(true, false)) { f.select(f.secondWorkspace); f.select(f.firstWorkspace) }
        }
        assertEquals(deny, withTimeout(5_000) { f.repo.currentProjectRulesFlow.first() })
        val entered = CompletableDeferred<Unit>()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.currentProjectRulesFlow.collect { entered.complete(Unit) }
        }
        withTimeout(5_000) { entered.await() }
        waiting.cancel()
        waiting.join()
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun finalDeliverySourceCheck_ioFailure_dropsOnlyPendingEmissionAndRecovers() = runBlocking {
        for (fault in listOf(IOException("Final policy root check unavailable"), AccessDeniedException("policy-root"))) {
            val f = PolicyFixture()
            f.awaitProfile()
            val deny = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
            val pending = deny + PermissionRule("Bash", "git push", PermissionDecision.DENY)
            f.write(f.globalFile, deny)
            val firstDelivery = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val handoff = CompletableDeferred<Unit>()
            val warning = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            val emissions = Channel<List<PermissionRule>>(Channel.UNLIMITED)
            val values = CopyOnWriteArrayList<List<PermissionRule>>()
            val failedOnce = AtomicBoolean(true)
            every { FileLogger.w(any(), any()) } answers {
                if (secondArg<String>().contains("at delivery")) warning.complete(Unit)
                Unit
            }
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                f.repo.globalRulesFlow.collect { rules ->
                    values.add(rules)
                    emissions.send(rules)
                    if (firstDelivery.complete(Unit)) releaseCollector.await()
                }
            }
            try {
                withTimeout(5_000) { firstDelivery.await() }
                f.write(f.globalFile, pending)
                f.onSelectionRead = { handoff.complete(Unit) }
                f.controls.configurationRevision.value++
                withTimeout(5_000) { handoff.await() }
                f.onGlobalCheck = {
                    if (failedOnce.compareAndSet(true, false)) {
                        checked.complete(Unit)
                        throw fault
                    }
                }
                releaseCollector.complete(Unit)
                withTimeout(5_000) { checked.await(); warning.await() }
                assertTrue("Final $fault check terminated the collector", job.isActive)
                assertEquals(listOf(deny), values.toList())
                f.onGlobalCheck = null
                f.controls.configurationRevision.value++
                withTimeout(5_000) { while (emissions.receive() != pending) Unit }
                assertTrue(values.none { it.isEmpty() })
            } finally {
                f.onGlobalCheck = null
                f.onSelectionRead = null
                releaseCollector.complete(Unit)
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun observedRules_downstreamCollectorExceptions_propagateUnchanged() = runBlocking {
        val f = PolicyFixture()
        f.write(f.globalFile, listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY)))
        f.write(f.projectFile, listOf(PermissionRule("Bash", "git push", PermissionDecision.DENY)))
        for (rules in listOf(f.repo.globalRulesFlow, f.repo.currentProjectRulesFlow)) {
            val marker = Any()
            for (expected in listOf(CollectorIoFailure(marker), CollectorCancellation(marker))) {
                val actual = try {
                    withTimeout(5_000) { rules.collect { throw expected } }
                    null
                } catch (failure: Throwable) {
                    failure
                }
                assertSame("The policy repository intercepted a downstream collector failure", expected, actual)
            }
        }
    }

    @Test
    fun loadEffectiveRules_absoluteProjectLink_loadsExistingRules() = runBlocking {
        File(target, "permissions.json").writeText("""{"permissions":{"allow":["readFile"],"deny":["writeFile"]}}""")
        assertEquals(
            listOf(
                PermissionRule("readFile", "*"),
                PermissionRule("writeFile", "*", PermissionDecision.DENY)
            ),
            repository.loadEffectiveForCurrentProject()
        )
    }

    @Test
    fun setGlobalRules_preservesAllowAndDenyDataForBackup() = runBlocking {
        val rules = listOf(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW),
            PermissionRule("writeFile", "*", PermissionDecision.DENY)
        )

        repository.setGlobalRules(rules)

        assertEquals(rules, repository.getGlobalRulesOnce())
        assertEquals(rules, Json.decodeFromString(PermissionFile.serializer(), globalFile.readText()).toRuleList())
        assertEquals(rules, repository.loadEffectiveForCurrentProject())
    }

    @Test
    fun removeGlobalRule_cleansLegacyAllowWithoutDroppingDenyRules() = runBlocking {
        val legacyAllow = PermissionRule("readFile", "*", PermissionDecision.ALLOW)
        val deny = PermissionRule("Bash", "git push", PermissionDecision.DENY)
        repository.setGlobalRules(listOf(legacyAllow, deny))

        repository.removeGlobalRule(legacyAllow)

        assertEquals(listOf(deny), repository.getGlobalRulesOnce())
        assertEquals(listOf(deny), Json.decodeFromString(PermissionFile.serializer(), globalFile.readText()).toRuleList())
        repository.removeGlobalRule(deny)
        assertTrue(repository.getGlobalRulesOnce().isEmpty())
        assertEquals(PermissionFile.EMPTY, Json.decodeFromString(PermissionFile.serializer(), globalFile.readText()))
    }

    @Test
    fun removeProjectRule_absoluteProjectLink_writesTargetAndPreservesLink() = runBlocking {
        val projectFile = File(target, "permissions.json")
        projectFile.writeText("""{"permissions":{"allow":["readFile"],"deny":["writeFile"]}}""")

        repository.removeProjectRule("project", PermissionRule("readFile", "*"))

        val remaining = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        assertEquals(remaining, repository.loadEffectiveForCurrentProject())
        assertEquals(remaining, Json.decodeFromString(PermissionFile.serializer(), projectFile.readText()).toRuleList())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("/root/workspace/project/.aicode", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun promoteToGlobal_preservesDecisionAndAvoidsDuplicateRules() = runBlocking {
        File(target, "permissions.json").writeText(
            """{"permissions":{"allow":["writeFile"],"deny":["Bash(git push)"]}}"""
        )
        val existing = PermissionRule("readFile", "*", PermissionDecision.ALLOW)
        val promoted = PermissionRule("Bash", "git push", PermissionDecision.DENY)
        repository.setGlobalRules(listOf(existing))

        repository.promoteToGlobal("project", promoted)
        repository.promoteToGlobal("project", promoted)

        assertEquals(listOf(existing, promoted), repository.getGlobalRulesOnce())
        assertEquals(
            listOf(PermissionRule("writeFile", "*"), existing, promoted),
            repository.loadEffectiveForCurrentProject()
        )
        assertEquals(
            listOf(PermissionRule("writeFile", "*")),
            Json.decodeFromString(PermissionFile.serializer(), File(target, "permissions.json").readText()).toRuleList()
        )
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }
}
