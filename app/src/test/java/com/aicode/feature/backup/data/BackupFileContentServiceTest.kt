package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteAgentConfigProfile
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class BackupFileContentServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val installer = mockk<ContainerInstaller>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val modes = mockk<ExecutionModeHolder>()
    private val connection = mockk<RemoteSshConnection>()
    private val remoteFiles = mockk<RemoteSftpFileAccess>()
    private val executions = mockk<WorkspaceExecutionContextFactory>()
    private val remoteCache = mockk<RemoteAgentConfigCache>()
    private val skills = mockk<SkillRepository>(relaxed = true)
    private val prompts = mockk<SystemPromptProvider>(relaxed = true)
    private lateinit var files: File
    private lateinit var cache: File
    private lateinit var aicode: File
    private lateinit var service: BackupFileContentService
    private var localWorkspaces = emptyList<WorkspaceRepository.BackupLocalWorkspace>()
    private val selected = MutableStateFlow(WorkspaceRepository.Selection(null))

    @Before
    fun setUp() {
        files = temporary.newFolder("files")
        cache = temporary.newFolder("cache")
        aicode = File(files, "aicode").apply { mkdir() }
        every { context.filesDir } returns files
        every { context.cacheDir } returns cache
        every { installer.aicodeDir } returns aicode
        every { modes.currentMode() } returns ExecutionMode.LOCAL_PROOT
        every { connection.isConnected() } returns false
        every { workspaces.selection } returns selected
        coEvery { workspaces.captureBackupLocalWorkspaces() } answers { localWorkspaces }
        every { remoteCache.invalidation(any()) } returns {}
        service = BackupFileContentService(context, installer, workspaces, modes, connection, remoteFiles, executions, remoteCache, skills, prompts)
    }

    private fun local(name: String = "project"): File {
        val directory = File(files, "projects/$name").apply { mkdirs() }
        localWorkspaces = localWorkspaces + WorkspaceRepository.BackupLocalWorkspace(Workspace(name, directory.path)) {}
        return directory
    }

    private fun stage(kind: BackupContentKind, location: BackupContentLocation, workspace: String? = null, key: String? = null, fill: (File) -> Unit): PreparedBackupContent {
        val directory = temporary.newFolder()
        fill(directory)
        return PreparedBackupContent(BackupContentArchive.metadata("fixture-id", kind, location, "fixture", directory)
            .copy(workspaceName = workspace, sourceKey = key), directory)
    }

    @Test
    fun defaultOptionsDoNotReadAnyFileContent() = runBlocking {
        assertTrue(service.prepareExport(BackupOptions()).isEmpty())
        verify(exactly = 0) { installer.aicodeDir }
        verify(exactly = 0) { remoteFiles.transferBackend(any(), any()) }
    }

    @Test
    fun phoneFilesAreExportedEvenWhileCurrentModeIsSsh() = runBlocking {
        val project = local()
        File(aicode, "memory").mkdir()
        File(aicode, "memory/raw file.md").writeText("---\nunknown: exact\n---\nbody")
        File(aicode, "memory/projects/old-project-12345678").mkdirs()
        File(aicode, "memory/projects/old-project-12345678/project.md").writeText("project memory")
        File(project, ".aicode/memory").mkdirs()
        File(project, ".aicode/memory/local.md").writeText("local project memory")
        every { modes.currentMode() } returns ExecutionMode.REMOTE_SSH
        val exported = service.prepareExport(BackupOptions(memories = true))
        try {
            assertEquals(3, exported.size)
            val global = exported.single { it.metadata.location == BackupContentLocation.PHONE_GLOBAL }
            assertEquals(listOf("raw file.md"), global.directory.list()!!.toList())
            assertEquals(1, global.metadata.fileCount)
            assertEquals("---\nunknown: exact\n---\nbody", File(global.directory, "raw file.md").readText())
            assertEquals(1, exported.count { it.metadata.location == BackupContentLocation.PHONE_PROJECT })
            assertEquals(1, exported.count { it.metadata.location == BackupContentLocation.PHONE_REMOTE_PROJECT })
        } finally { service.cleanup(exported) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun promptShadowLayersAndControlFilesArePreservedIndependently() = runBlocking {
        val project = local()
        val global = File(aicode, "prompts.custom").apply { mkdir() }
        File(global, "agent").mkdir()
        File(global, "01-global.md").writeText("shadowed global")
        File(global, "01-second.md").writeText("same-number shadow")
        File(global, "agent/leaf.md").writeText("leaf")
        File(global, "prompts.json").writeText("{\"enabled\":{\"1\":false}}")
        File(global, ".no-builtin").writeText("")
        File(project, ".aicode/prompts.custom").mkdirs()
        File(project, ".aicode/prompts.custom/01-project.md").writeText("project")
        File(aicode, "permissions.json").writeText("must not be scanned")
        val exported = service.prepareExport(BackupOptions(prompts = true))
        try {
            val phone = exported.single { it.metadata.location == BackupContentLocation.PHONE_GLOBAL }.directory
            assertEquals("shadowed global", File(phone, "01-global.md").readText())
            assertEquals("same-number shadow", File(phone, "01-second.md").readText())
            assertEquals("leaf", File(phone, "agent/leaf.md").readText())
            assertTrue(File(phone, ".no-builtin").isFile)
            assertFalse(File(phone, "permissions.json").exists())
            assertEquals("project", File(exported.single { it.metadata.location == BackupContentLocation.PHONE_PROJECT }.directory, "01-project.md").readText())
        } finally { service.cleanup(exported) }
    }

    @Test
    fun skillDefinitionsAndPhoneRemoteDisabledConfigurationKeepSeparateLayouts() = runBlocking {
        val global = File(aicode, "skills/user-seed/scripts").apply { mkdirs() }
        File(global.parentFile, "SKILL.md").writeText("modified seed must be kept")
        File(global, "run.py").writeText("print('fixture')")
        File(aicode, "skills.json").writeText("{\"disabled\":[\"user-seed\"]}")
        File(aicode, "projects/server-project-deadbeef").mkdirs()
        File(aicode, "projects/server-project-deadbeef/skills.json").writeText("{\"disabled\":[\"project-only\"]}")
        val exported = service.prepareExport(BackupOptions(skills = true))
        try {
            val phone = exported.single { it.metadata.location == BackupContentLocation.PHONE_GLOBAL }
            assertEquals("modified seed must be kept", File(phone.directory, "definitions/user-seed/SKILL.md").readText())
            assertTrue(File(phone.directory, "definitions/user-seed/scripts/run.py").isFile)
            assertTrue(File(phone.directory, "skills.json").isFile)
            val project = exported.single { it.metadata.location == BackupContentLocation.PHONE_REMOTE_PROJECT }
            assertEquals(listOf("skills.json"), project.directory.list()!!.toList())
        } finally { service.cleanup(exported) }
    }

    @Test
    fun invalidConfigurationAndLinkedMemoryAbortExportAndCleanOwnedStages() {
        File(aicode, "skills.json").writeText("{\"disabled\":[],\"token\":\"not a skill config\"}")
        assertThrows(IOException::class.java) { runBlocking { service.prepareExport(BackupOptions(skills = true)) } }
        assertTrue(cache.listFiles()!!.isEmpty())
        File(aicode, "memory").mkdir()
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(aicode, "memory/link.md").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { runBlocking { service.prepareExport(BackupOptions(memories = true)) } }
        assertEquals("keep", outside.readText())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun hostHardLinksAreRejectedWhenUnixLinkCountsAreAvailable() {
        val memory = File(aicode, "memory").apply { mkdir() }
        val outside = temporary.newFile().apply { writeText("private") }
        Files.createLink(File(memory, "linked.md").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { runBlocking { service.prepareExport(BackupOptions(memories = true)) } }
        assertEquals("private", outside.readText())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun cleanupOnlyDeletesStagesOwnedByThisService() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/entry.md").writeText("memory")
        val exported = service.prepareExport(BackupOptions(memories = true))
        val foreign = temporary.newFolder()
        File(foreign, "keep").writeText("keep")
        service.cleanup(listOf(PreparedBackupContent(exported.single().metadata, foreign)))
        assertEquals("keep", File(foreign, "keep").readText())
        assertTrue(exported.single().directory.isDirectory)
        service.cleanup(exported)
        service.cleanup(exported)
        assertFalse(exported.single().directory.exists())
    }

    @Test
    fun preparedCheckRemainsValidAfterTheIoPreparationChildCompletes() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/entry.md").writeText("memory")
        val exported = service.prepareExport(BackupOptions(memories = true))
        try {
            exported.single().checkCurrent()
            assertTrue(exported.single().directory.isDirectory)
        } finally { service.cleanup(exported) }
    }

    @Test
    fun preparedCheckAllowsDirectoryMtimeChangesAfterCreatingAChild() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/entry.md").writeText("memory")
        val exported = service.prepareExport(BackupOptions(memories = true))
        try {
            val before = Files.readAttributes(aicode.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            assertTrue(File(aicode, "new-child").mkdir())
            Files.setLastModifiedTime(aicode.toPath(), FileTime.fromMillis(1L))
            val after = Files.readAttributes(aicode.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            assertNotNull(before.fileKey())
            assertEquals(before.fileKey(), after.fileKey())
            assertNotEquals(before.lastModifiedTime(), after.lastModifiedTime())
            exported.single().checkCurrent()
            assertEquals("memory", File(exported.single().directory, "entry.md").readText())
        } finally { service.cleanup(exported) }
    }

    @Test
    fun localProjectRestoreAllowsDirectoryMtimeChangesDuringParentCreation() = runBlocking {
        val project = local("mtime-project")
        var time = 1L
        localWorkspaces = listOf(WorkspaceRepository.BackupLocalWorkspace(Workspace("mtime-project", project.path)) {
            Files.setLastModifiedTime(project.toPath(), FileTime.fromMillis(time++))
        })
        val content = stage(BackupContentKind.MEMORY, BackupContentLocation.PHONE_PROJECT, workspace = "mtime-project") {
            File(it, "entry.md").writeText("restored")
        }
        val result = service.restore(content.metadata, content.directory, BackupContentRestoreSelection())
        assertEquals("result=$result", BackupContentRestoreStatus.RESTORED, result.status)
        assertEquals(1, result.restoredFiles)
        assertEquals("restored", File(project, ".aicode/memory/entry.md").readText())
    }

    @Test
    fun preparedCheckRejectsDirectoryReplacementEvenWhenTimestampsMatch() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/entry.md").writeText("memory")
        val exported = service.prepareExport(BackupOptions(memories = true))
        try {
            val before = Files.readAttributes(aicode.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val relocated = File(files, "relocated-aicode")
            Files.move(aicode.toPath(), relocated.toPath())
            Files.createDirectory(aicode.toPath())
            File(aicode, "foreign").writeText("keep")
            Files.setLastModifiedTime(aicode.toPath(), before.lastModifiedTime())
            val after = Files.readAttributes(aicode.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
            assertNotEquals(before.fileKey(), after.fileKey())
            assertThrows(IOException::class.java) { exported.single().checkCurrent() }
            assertEquals("keep", File(aicode, "foreign").readText())
            assertEquals("memory", File(relocated, "memory/entry.md").readText())
        } finally { service.cleanup(exported) }
    }

    @Test
    fun cleanupDoesNotDeleteAReplacementOfAnOwnedStage() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/entry.md").writeText("memory")
        val exported = service.prepareExport(BackupOptions(memories = true))
        val directory = exported.single().directory
        val relocated = File(cache, "relocated")
        Files.move(directory.toPath(), relocated.toPath())
        directory.mkdir()
        File(directory, "foreign").writeText("keep")
        service.cleanup(exported)
        assertEquals("keep", File(directory, "foreign").readText())
        assertTrue(File(relocated, "entry.md").isFile)
    }

    @Test
    fun restoreDoesNotSelectBySectionIdsAndDefaultsToKeepingConflicts() = runBlocking {
        File(aicode, "memory").mkdir()
        File(aicode, "memory/existing.md").writeText("keep")
        val content = stage(BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL) {
            File(it, "existing.md").writeText("backup")
            File(it, "new.md").writeText("---\nunknown: preserved\n---\nnew")
        }
        val result = service.restore(content.metadata, content.directory, BackupContentRestoreSelection())
        assertEquals(BackupContentRestoreStatus.PARTIAL, result.status)
        assertEquals(1, result.restoredFiles)
        assertEquals(1, result.skippedFiles)
        assertEquals(1, result.conflicts)
        assertEquals("keep", File(aicode, "memory/existing.md").readText())
        assertEquals("---\nunknown: preserved\n---\nnew", File(aicode, "memory/new.md").readText())
        verify(exactly = 1) { prompts.invalidateAfterBackupRestore() }
    }

    @Test
    fun localProjectRestoreUsesRegisteredNameAndIgnoresBackupAbsoluteSource() = runBlocking {
        val project = local("matched")
        val content = stage(BackupContentKind.PROMPTS, BackupContentLocation.PHONE_PROJECT, workspace = "matched") {
            File(it, "01-entry.md").writeText("restored")
        }
        val meta = content.metadata.copy(sourceLabel = "/untrusted/old/absolute/path")
        val result = service.restore(meta, content.directory, BackupContentRestoreSelection())
        assertEquals(BackupContentRestoreStatus.RESTORED, result.status)
        assertEquals("restored", File(project, ".aicode/prompts.custom/01-entry.md").readText())
    }

    @Test
    fun newDeviceCreatesOnlyAnInternalNamedWorkspace() = runBlocking {
        val content = stage(BackupContentKind.MEMORY, BackupContentLocation.PHONE_PROJECT, workspace = "new project") {
            File(it, "entry.md").writeText("restored")
        }
        val result = service.restore(content.metadata, content.directory, BackupContentRestoreSelection())
        assertEquals(BackupContentRestoreStatus.RESTORED, result.status)
        assertEquals("restored", File(files, "projects/new project/.aicode/memory/entry.md").readText())
    }

    @Test
    fun newDeviceRestoresPhoneRemoteCachesAtTheirOriginalPrivateKeys() = runBlocking {
        val memory = stage(BackupContentKind.MEMORY, BackupContentLocation.PHONE_REMOTE_PROJECT, key = "project-deadbeef") {
            File(it, "entry.md").writeText("memory")
        }
        val disabled = stage(BackupContentKind.SKILLS, BackupContentLocation.PHONE_REMOTE_PROJECT, key = "project-deadbeef") {
            File(it, "skills.json").writeText("{\"disabled\":[\"demo\"]}")
        }
        assertEquals(BackupContentRestoreStatus.RESTORED, service.restore(memory.metadata, memory.directory, BackupContentRestoreSelection()).status)
        assertEquals(BackupContentRestoreStatus.RESTORED, service.restore(disabled.metadata, disabled.directory, BackupContentRestoreSelection()).status)
        assertEquals("memory", File(aicode, "memory/projects/project-deadbeef/entry.md").readText())
        assertFalse(File(aicode, "memory/projects/project-deadbeef/memory").exists())
        assertEquals("{\"disabled\":[\"demo\"]}", File(aicode, "projects/project-deadbeef/skills.json").readText())
        assertFalse(File(aicode, "skills.json").exists())
        verify(exactly = 0) { remoteFiles.transferBackend(any(), any()) }
        verify(exactly = 1) { skills.invalidateAfterBackupRestore() }
    }

    @Test
    fun invalidLayoutAndUnsafeKeysCannotWriteOutsideKindDirectories() = runBlocking {
        val content = stage(BackupContentKind.SKILLS, BackupContentLocation.PHONE_GLOBAL) {
            File(it, "permissions.json").writeText("not skills")
        }
        assertEquals(BackupContentRestoreStatus.FAILED, service.restore(content.metadata, content.directory, BackupContentRestoreSelection()).status)
        assertFalse(File(aicode, "permissions.json").exists())
        val memory = stage(BackupContentKind.MEMORY, BackupContentLocation.PHONE_REMOTE_PROJECT, key = "../escape") {
            File(it, "entry.md").writeText("memory")
        }
        assertEquals(BackupContentRestoreStatus.FAILED, service.restore(memory.metadata, memory.directory, BackupContentRestoreSelection()).status)
        assertFalse(File(files, "escape").exists())
    }

    @Test
    fun unavailableExternalWorkspaceCannotBecomeAnEmptySuccessfulBackup() {
        localWorkspaces = listOf(WorkspaceRepository.BackupLocalWorkspace(Workspace("external", "/unavailable", WorkspaceType.EXTERNAL_LOCAL, false)) {
            throw IOException("permission revoked")
        })
        assertThrows(IOException::class.java) { runBlocking { service.prepareExport(BackupOptions(memories = true)) } }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun disconnectedSshHasNoRestoreTargetAndNeverFallsBackToPhone() = runBlocking {
        every { modes.currentMode() } returns ExecutionMode.REMOTE_SSH
        assertNull(service.currentRemoteTarget())
        val content = stage(BackupContentKind.SKILLS, BackupContentLocation.SSH_GLOBAL) {
            File(it, "definitions/demo").mkdirs()
            File(it, "definitions/demo/SKILL.md").writeText("remote")
        }
        val result = service.restore(content.metadata, content.directory, BackupContentRestoreSelection(remoteTargetIdentity = "old"))
        assertEquals(BackupContentRestoreStatus.UNAVAILABLE, result.status)
        assertFalse(File(aicode, "skills").exists())
    }

    private fun connectedRemote(): File {
        val root = temporary.newFolder("remote")
        File(root, "home/test").mkdirs()
        File(root, "srv/project").mkdirs()
        val identity = RemoteConnectionIdentity(mockk(), "/home/test")
        val config = RemoteConnectionConfig("host.invalid", username = "test", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = "/srv")
        val source = RemoteSftpFileAccess.ExecutionSource(identity, "/srv/project", RemoteAgentConfigProfile("fixture", 0))
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote-fixture", remoteFiles, null, source) {}
        selected.value = WorkspaceRepository.Selection(Workspace("project", "/srv/project", WorkspaceType.REMOTE))
        every { modes.currentMode() } returns ExecutionMode.REMOTE_SSH
        every { connection.isConnected() } returns true
        every { connection.config } returns config
        every { connection.checkIdentity(identity) } returns Unit
        coEvery { executions.freeze(any()) } returns execution
        every { remoteFiles.transferBackend(any(), any()) } answers {
            val path = firstArg<String>()
            val check = secondArg<() -> Unit>()
            val directory = if (path == "/") root else File(root, path.removePrefix("/"))
            HostTransferBackend({ relative, _ -> if (relative.isEmpty()) directory else File(directory, relative) }, check)
        }
        return root
    }

    @Test
    fun frozenRemoteIdentityMustMatchAndLabelsDescribeCurrentTarget() = runBlocking {
        val remote = connectedRemote()
        val current = service.currentRemoteTarget()
        assertNotNull(current)
        assertTrue(current!!.label.contains("test@host.invalid:22"))
        assertTrue(current.label.contains("/srv/project"))
        val content = stage(BackupContentKind.SKILLS, BackupContentLocation.SSH_GLOBAL) {
            File(it, "definitions/demo").mkdirs()
            File(it, "definitions/demo/SKILL.md").writeText("remote")
            File(it, "skills.json").writeText("{\"disabled\":[\"demo\"]}")
        }
        val wrong = service.restore(content.metadata, content.directory, BackupContentRestoreSelection(remoteTargetIdentity = "different"))
        assertEquals(BackupContentRestoreStatus.UNAVAILABLE, wrong.status)
        assertFalse(File(remote, "home/test/.aicode").exists())
        val restored = service.restore(content.metadata, content.directory, BackupContentRestoreSelection(remoteTargetIdentity = current.identity))
        assertEquals(BackupContentRestoreStatus.RESTORED, restored.status)
        assertEquals("remote", File(remote, "home/test/.aicode/skills/demo/SKILL.md").readText())
        assertEquals("{\"disabled\":[\"demo\"]}", File(remote, "home/test/.aicode/skills.json").readText())
        assertFalse(File(aicode, "skills").exists())
    }

    @Test
    fun remoteProjectDefinitionsNeverCarryOrRestorePhoneDisabledConfiguration() = runBlocking {
        val remote = connectedRemote()
        File(remote, "home/test/.aicode/skills/global").mkdirs()
        File(remote, "home/test/.aicode/skills/global/SKILL.md").writeText("global")
        File(remote, "home/test/.aicode/skills.json").writeText("{\"disabled\":[\"global\"]}")
        File(remote, "srv/project/.aicode/skills/project").mkdirs()
        File(remote, "srv/project/.aicode/skills/project/SKILL.md").writeText("project")
        File(remote, "srv/project/.aicode/skills.json").writeText("not the app project configuration")
        val exported = service.prepareExport(BackupOptions(skills = true))
        try {
            val global = exported.single { it.metadata.location == BackupContentLocation.SSH_GLOBAL }
            val project = exported.single { it.metadata.location == BackupContentLocation.SSH_PROJECT }
            assertTrue(File(global.directory, "skills.json").isFile)
            assertFalse(File(project.directory, "skills.json").exists())
            assertEquals("project", File(project.directory, "definitions/project/SKILL.md").readText())
        } finally { service.cleanup(exported) }
        val invalid = stage(BackupContentKind.SKILLS, BackupContentLocation.SSH_PROJECT) {
            File(it, "skills.json").writeText("{\"disabled\":[]}")
        }
        assertEquals(BackupContentRestoreStatus.FAILED, service.restore(invalid.metadata, invalid.directory,
            BackupContentRestoreSelection(remoteTargetIdentity = service.currentRemoteTarget()!!.identity)).status)
    }

    @Test
    fun remoteSourceChangeAfterOneCommitReturnsPartialWithoutCancellingTheCaller() = runBlocking {
        val remote = connectedRemote()
        File(remote, "srv/project/.aicode/skills/demo").mkdirs()
        val target = service.currentRemoteTarget()!!
        val content = stage(BackupContentKind.SKILLS, BackupContentLocation.SSH_PROJECT) {
            File(it, "definitions/demo").mkdirs()
            File(it, "definitions/demo/a.md").writeText("first")
            File(it, "definitions/demo/b.md").writeText("second")
        }
        every { remoteCache.invalidation(any()) } returns {
            selected.value = WorkspaceRepository.Selection(Workspace("other", "/srv/other", WorkspaceType.REMOTE))
        }
        val result = service.restore(content.metadata, content.directory,
            BackupContentRestoreSelection(remoteTargetIdentity = target.identity))
        assertEquals(BackupContentRestoreStatus.PARTIAL, result.status)
        assertEquals(1, result.restoredFiles)
        assertEquals("first", File(remote, "srv/project/.aicode/skills/demo/a.md").readText())
        assertFalse(File(remote, "srv/project/.aicode/skills/demo/b.md").exists())
        assertFalse(File(aicode, "skills").exists())
        assertTrue(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun remoteTargetSelectionChangesAndLinkedParentsAreUnavailable() = runBlocking {
        val remote = connectedRemote()
        val target = service.currentRemoteTarget()!!
        selected.value = WorkspaceRepository.Selection(Workspace("project", "/srv/project", WorkspaceType.REMOTE))
        val content = stage(BackupContentKind.SKILLS, BackupContentLocation.SSH_PROJECT) {
            File(it, "definitions/demo").mkdirs()
            File(it, "definitions/demo/SKILL.md").writeText("remote")
        }
        assertEquals(BackupContentRestoreStatus.UNAVAILABLE, service.restore(content.metadata, content.directory,
            BackupContentRestoreSelection(remoteTargetIdentity = target.identity)).status)
        val outside = temporary.newFolder()
        Files.createSymbolicLink(File(remote, "srv/project/.aicode").toPath(), outside.toPath())
        val refreshed = service.currentRemoteTarget()!!
        val result = service.restore(content.metadata, content.directory, BackupContentRestoreSelection(remoteTargetIdentity = refreshed.identity))
        assertEquals(BackupContentRestoreStatus.UNAVAILABLE, result.status)
        assertTrue(outside.listFiles()!!.isEmpty())
        assertFalse(File(aicode, "skills").exists())
    }
}
