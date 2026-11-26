package com.aicode.feature.agent.domain.skill

import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.testutil.TestFileAccessProvider
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val provider = TestFileAccessProvider()
    private val globalSource = spyk(GlobalDirectorySkillSource(provider)).apply {
        every { listSkills(any()) } returns emptyList()
    }
    private val projectSource = spyk(ProjectDirectorySkillSource(provider)).apply {
        every { listSkills(any()) } returns emptyList()
    }
    private val config = mockk<SkillConfigRepository>().apply {
        every { disabledNames() } returns emptySet()
    }
    private val pluginSource = mockk<PluginDefinitionSource>().apply {
        every { revision } returns 0
        every { skills() } returns emptyList()
    }
    private val localDefinitions = mockk<LocalSkillDefinitionSource>().apply {
        every { sourceIdentity() } returns "legacy-fixture"
        coEvery { capture() } returns null
    }
    private val repository = SkillRepository(globalSource, projectSource, config, provider, pluginSource, localDefinitions)

    private fun skill(name: String) = Skill(
        name = name,
        description = "desc of $name",
        instructions = "body of $name"
    )

    @Test
    fun chrootSynchronousReads_useTheSameFixedDefinitionsAndDisabledUnion() {
        val f = LocalSkillDefinitionFixture(temporary.newFolder())
        f.instruction(SkillScope.GLOBAL, "global", "shared", "global body")
        f.instruction(SkillScope.PROJECT, "project", "SHARED", "project body")
        f.instruction(SkillScope.GLOBAL, "keep", "keep", "enabled body")
        f.disabled(SkillScope.PROJECT, "shared")
        val plugin = SkillEntry(skill("plugin"), SkillScope.PLUGIN)
        every { f.plugins.skills() } returns listOf(plugin)
        assertEquals(listOf("keep", "plugin", "SHARED"), f.repository.listAllSkills().map { it.skill.name })
        assertEquals(listOf("keep", "plugin"), f.repository.listSkills().map { it.name })
        assertNull(f.repository.loadInstructions("SHARED"))
        assertEquals("enabled body", f.repository.loadInstructions("KEEP"))
        assertEquals(plugin.skill.instructions, f.repository.loadInstructions("plugin"))
        f.repository.setSkillDisabled("shared", false, SkillScope.PROJECT)
        assertEquals("project body", f.repository.loadInstructions("shared"))
        verify { f.backend wasNot Called }
    }

    @Test
    fun nonChrootSnapshot_freezesTheExistingBackendAndPreservesScopeAndDisableSemantics() = runBlocking {
        val factory = mockk<WorkspaceExecutionContextFactory>()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, provider.cacheIdentity(), provider, null, null) { }
        coEvery { factory.freeze(any(), any(), any()) } returns execution
        every { globalSource.listSkills(any()) } returns listOf(skill("global"), skill("shared"))
        every { projectSource.listSkills(any()) } returns listOf(skill("shared").copy(instructions = "project"))
        every { config.disabledNames() } returns setOf("shared")
        val snapshot = repository.readDefinitionSnapshot(factory, forceRefresh = true)
        assertEquals(listOf("global", "shared"), snapshot.entries.map { it.skill.name })
        assertEquals(listOf("global"), snapshot.enabled.map { it.skill.name })
        assertEquals("project", snapshot.entries.single { it.scope == SkillScope.PROJECT }.skill.instructions)
        repository.checkSourceIdentity(snapshot.sourceIdentity)
        coVerify(exactly = 1) { factory.freeze(any(), any(), any()) }
        verify(exactly = 1) { globalSource.listSkills(true) }
        verify(exactly = 1) { projectSource.listSkills(true) }
    }

    @Test
    fun chrootSynchronousRead_doesNotStartIoFromACancelledCaller() = runBlocking {
        val f = LocalSkillDefinitionFixture(temporary.newFolder())
        val cancelled = Job().apply { cancel() }
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "captured", f.backend, null, null,
            cancellationJob = cancelled) { cancelled.ensureActive() }
        val failure = try {
            withContext(execution + cancelled) { f.repository.listAllSkills() }
            null
        } catch (failure: Throwable) { failure }
        assertTrue(failure is CancellationException)
        assertEquals(0, f.contextReads)
        verify { f.backend wasNot Called }
    }

    @Test
    fun inheritedActiveExecutionAfterSourceSwitch_rejectsAsyncAndSyncChrootReads_withoutRestrictingUiReads() = runBlocking {
        for (change in listOf("workspaceAndProfile", "sameWorkspaceProfile")) {
            val f = LocalSkillDefinitionFixture(temporary.newFolder())
            val proot = f.profile.copy(localRuntime = LocalContainerRuntime.PROOT)
            f.profiles.value = listOf(proot)
            f.configurationRevision.value++
            every { f.installer.rootfsDirFor(any()) } returns File(f.files, "rootfs")
            val home = mockk<PathHomeResolver>()
            every { home.home() } returns "/root"
            every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
            val mapper = WorkspacePathMapper(f.workspace, f.installer, f.settings, home)
            withTimeout(5_000) { mapper.runtimeProfileState.first { it.ready && it.profile == proot } }
            val local = LocalFileAccess(mapper)
            val folders = mockk<LocalFolderGrantRepository>()
            coEvery { folders.awaitReady() } returns Unit
            every { folders.snapshot() } returns LocalFolderSnapshot.EMPTY
            val remote = mockk<RemoteSftpFileAccess>()
            val factory = WorkspaceExecutionContextFactory(local, f.mode, local, remote, f.settings, folders)
            val owner = requireNotNull(currentCoroutineContext()[Job])
            val capturedA = factory.freeze()
            capturedA.checkCurrent()
            assertTrue(owner.isActive)
            if (change == "workspaceAndProfile") f.select(f.second)
            f.profiles.value = listOf(f.profile)
            f.configurationRevision.value++
            withTimeout(5_000) { mapper.runtimeProfileState.first { it.ready && it.profile == f.profile } }
            f.instruction(SkillScope.PROJECT, "selected-b", "selected-b", "new source B")
            f.disabled(SkillScope.PROJECT, "blocked")
            assertThrows(CancellationException::class.java) { capturedA.checkCurrent() }
            assertTrue("The old execution owner was cancelled instead of its source becoming stale", owner.isActive)
            val failures = linkedMapOf<String, Throwable?>()
            for (api in listOf("snapshot", "listAll", "listSkills", "loadInstructions", "config")) {
                failures[api] = try {
                    withContext(capturedA) {
                        when (api) {
                            "snapshot" -> f.repository.readDefinitionSnapshot(factory)
                            "listAll" -> f.repository.listAllSkills()
                            "listSkills" -> f.repository.listSkills()
                            "loadInstructions" -> f.repository.loadInstructions("selected-b")
                            else -> f.config.disabledNames()
                        }
                    }
                    null
                } catch (failure: Throwable) { failure }
            }
            val fromUi = f.repository.readDefinitionSnapshot(factory)
            assertEquals("new source B", fromUi.enabled.single().skill.instructions)
            assertEquals("new source B", f.repository.loadInstructions("selected-b"))
            assertEquals(setOf("blocked"), f.config.disabledNames())
            assertTrue(owner.isActive)
            for ((api, failure) in failures) {
                assertTrue("$change/$api read source B through obsolete active execution A; failure=$failure", failure is CancellationException)
            }
            verify { f.backend wasNot Called }
        }
    }

    @Test
    fun loadInstructions_projectOverridesSameNameWithDifferentBody() {
        val global = skill("same").copy(instructions = "global instructions")
        val project = skill("same").copy(instructions = "project instructions")
        every { globalSource.listSkills(any()) } returns listOf(global)
        every { projectSource.listSkills(any()) } returns listOf(project)

        val listed = repository.listSkills().single()
        assertEquals(project, listed)
        assertEquals("project instructions", repository.loadInstructions(listed.name))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_projectOverridesCaseInsensitiveName() {
        val global = skill("MixedCase").copy(instructions = "global instructions")
        val project = skill("mixedcase").copy(instructions = "project instructions")
        every { globalSource.listSkills(any()) } returns listOf(global)
        every { projectSource.listSkills(any()) } returns listOf(project)

        assertEquals(listOf(project), repository.listSkills())
        assertEquals("project instructions", repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_fallsBackToGlobalWhenProjectHasNoMatch() {
        val global = skill("GlobalOnly").copy(instructions = "global instructions")
        every { globalSource.listSkills(any()) } returns listOf(global)
        every { projectSource.listSkills(any()) } returns listOf(skill("other"))

        assertEquals("global instructions", repository.loadInstructions("GLOBALONLY"))
        verify(exactly = 1) { projectSource.loadInstructions("GLOBALONLY") }
        verify(exactly = 1) { globalSource.loadInstructions("GLOBALONLY") }
    }

    @Test
    fun loadInstructions_returnsNullWhenNeitherSourceHasMatch() {
        every { globalSource.listSkills(any()) } returns listOf(skill("global"))
        every { projectSource.listSkills(any()) } returns listOf(skill("project"))

        assertNull(repository.loadInstructions("missing"))
        verify(exactly = 1) { projectSource.loadInstructions("missing") }
        verify(exactly = 1) { globalSource.loadInstructions("missing") }
    }

    @Test
    fun loadInstructions_disabledNameBlocksBothSourcesCaseInsensitively() {
        every { globalSource.listSkills(any()) } returns listOf(skill("MixedCase"))
        every { projectSource.listSkills(any()) } returns listOf(skill("mixedcase"))
        every { config.disabledNames() } returns setOf("mixedcase")

        assertTrue(repository.listSkills().isEmpty())
        assertNull(repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { projectSource.loadInstructions(any()) }
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun mergeAll_combinesGlobalAndProject() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("a"), skill("b")),
            project = listOf(skill("c"))
        )

        assertEquals(listOf("a", "b", "c"), merged.map { it.skill.name })
        assertTrue(merged.all { it.scope == SkillScope.GLOBAL || it.scope == SkillScope.PROJECT })
        assertEquals(
            listOf(SkillScope.GLOBAL, SkillScope.GLOBAL, SkillScope.PROJECT),
            merged.map { it.scope }
        )
    }

    @Test
    fun mergeAll_projectOverridesSameName() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("same")),
            project = listOf(skill("same"))
        )

        assertEquals(1, merged.size)
        assertEquals("same", merged[0].skill.name)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_caseInsensitiveDedup() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("MixedCase")),
            project = listOf(skill("mixedcase"))
        )

        assertEquals(1, merged.size)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_sortedByName() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("zeta"), skill("alpha")),
            project = listOf(skill("middle"))
        )

        assertEquals(listOf("alpha", "middle", "zeta"), merged.map { it.skill.name })
    }

    @Test
    fun filterDisabled_removesDisabledSkills() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("keep"), skill("drop")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("drop"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun filterDisabled_caseInsensitive() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("Keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("keep"))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun filterDisabled_unknownNamesIgnored() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("not-exists"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun safeDeleteSkillDir_deletesOnlySkillDirectories() {
        val temp = java.nio.file.Files.createTempDirectory("skills-test").toFile()
        try {
            val skillDir = File(temp, "my-skill")
            skillDir.mkdirs()
            File(skillDir, "SKILL.md").writeText("---\nname: my-skill\n---\n正文")
            File(skillDir, "run.py").writeText("print(1)")

            // 含 SKILL.md 的目录可删
            assertTrue(SkillRepository.safeDeleteSkillDir(provider, skillDir.absolutePath))
            assertTrue(!skillDir.exists())

            // 不含指令文件的目录拒绝删除
            val plainDir = File(temp, "not-a-skill")
            plainDir.mkdirs()
            File(plainDir, "readme.txt").writeText("x")
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, plainDir.absolutePath))
            assertTrue(plainDir.exists())

            // 不存在的目录返回 false
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, File(temp, "ghost").absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun isValidName_rejectsPathTraversalAndSeparators() {
        assertTrue(SkillRepository.isValidName("pdf-report"))
        assertTrue(SkillRepository.isValidName("中文技能名"))
        assertTrue(!SkillRepository.isValidName(""))
        assertTrue(!SkillRepository.isValidName("   "))
        assertTrue(!SkillRepository.isValidName("."))
        assertTrue(!SkillRepository.isValidName(".."))
        assertTrue(!SkillRepository.isValidName("a/b"))
        assertTrue(!SkillRepository.isValidName("a\\b"))
        assertTrue(!SkillRepository.isValidName("a:b"))
        assertTrue(!SkillRepository.isValidName("a\u0000b"))
        assertTrue(!SkillRepository.isValidName("x".repeat(65)))
    }

    @Test
    fun instructionFile_prefersSkillMdThenClaudeMd() {
        val temp = java.nio.file.Files.createTempDirectory("skills-instruction-test").toFile()
        try {
            val both = File(temp, "both").apply { mkdirs() }
            File(both, "CLAUDE.md").writeText("x")
            File(both, "skill.md").writeText("x")
            assertEquals("skill.md", SkillRepository.instructionFile(provider, both.absolutePath)?.substringAfterLast('/'))

            val claudeOnly = File(temp, "claude-only").apply { mkdirs() }
            File(claudeOnly, "CLAUDE.md").writeText("x")
            assertEquals("CLAUDE.md", SkillRepository.instructionFile(provider, claudeOnly.absolutePath)?.substringAfterLast('/'))

            val empty = File(temp, "empty").apply { mkdirs() }
            assertEquals(null, SkillRepository.instructionFile(provider, empty.absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }
}
