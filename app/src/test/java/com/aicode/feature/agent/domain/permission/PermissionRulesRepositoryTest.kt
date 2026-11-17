package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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
        every { installer.aicodeDir } returns File(filesDir, "aicode")
        every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns link
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        repository = PermissionRulesRepository(context, workspace, projectRoot, changes, mapper)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
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
