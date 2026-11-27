package com.aicode.feature.agent.domain.plugin

import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.skill.GlobalDirectorySkillSource
import com.aicode.feature.agent.domain.skill.LocalSkillDefinitionSource
import com.aicode.feature.agent.domain.skill.ProjectDirectorySkillSource
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillForm
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillSaveError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionForm
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.AgentSaveError
import com.aicode.feature.agent.domain.subagent.GlobalDirectoryAgentSource
import com.aicode.feature.agent.domain.subagent.ProjectDirectoryAgentSource
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.AgentConfigSnapshot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginDefinitionRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val manager by lazy { PluginManager(File(temp.root, "plugins"), File(temp.root, "registry.json")) }
    private val pluginSource by lazy { PluginDefinitionSource(manager) }
    private val native = TestFileAccessProvider()
    private var skillDisabled = emptySet<String>()
    private var agentDisabled = emptySet<String>()
    private val skillConfig = mockk<SkillConfigRepository>().apply {
        every { disabledNames() } answers { skillDisabled }
    }
    private val agentConfig = mockk<AgentDefinitionConfigRepository>().apply {
        every { disabledNames() } answers { agentDisabled }
    }
    private val fileAccess = object : FileAccessProvider by native {
        override fun agentConfigSourceIdentity() = "host-fixture"
        override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot {
            val directory = hostFile(root)
            val files = directory.walkTopDown().filter { it.isFile && it.extension == "md" }
                .associate { root + "/" + it.relativeTo(directory).invariantSeparatorsPath to it.readText() }
            return AgentConfigSnapshot(agentConfigSourceIdentity(), "revision", files)
        }
    }
    private val localDefinitions = mockk<LocalSkillDefinitionSource>().apply {
        every { sourceIdentity() } returns "plugin-metadata"
        coEvery { capture() } returns null
        every { sourceSignals() } returns emptyFlow()
    }
    private val skills by lazy {
        SkillRepository(GlobalDirectorySkillSource(fileAccess), ProjectDirectorySkillSource(fileAccess), skillConfig, fileAccess, pluginSource, localDefinitions)
    }
    private val agents by lazy {
        AgentDefinitionRepository(GlobalDirectoryAgentSource(fileAccess), ProjectDirectoryAgentSource(fileAccess), agentConfig, fileAccess, pluginSource,
            com.aicode.feature.agent.domain.subagent.legacyAgentDefinitions(fileAccess))
    }

    private fun hostFile(path: String) = File(temp.root, "host/" + path.removePrefix("~/"))
    private fun hostDefinition(path: String, name: String, body: String) {
        hostFile(path).apply { parentFile.mkdirs(); writeText("---\nname: '$name'\ndescription: host\n---\n$body") }
    }
    private fun archive(id: String, version: String, skillBody: String, agentBody: String): InputStream {
        val manifest = """{
            "manifestVersion":1,"id":"$id","name":"Definitions","version":"$version",
            "publisher":"fixture","repository":"https://github.com/jieapi/aicode","description":"Definitions",
            "license":"MIT","engines":{"pluginApi":"^1.0.0"},
            "contributes":{"skills":[{"id":"read","file":"skills/read.md"}],
            "agents":[{"id":"review","file":"agents/review.md"}]}
        }""".trimIndent()
        val files = mapOf(
            "plugin.json" to manifest,
            "skills/read.md" to "---\nname: Explore\ndescription: skill description\nrequired_tools: [readFile]\n---\n$skillBody",
            "agents/review.md" to "---\nname: Explore\ndescription: agent description\ntools: [readFile]\ninject: [base, skills]\n---\n$agentBody"
        )
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                files.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray().inputStream()
    }
    private suspend fun install(
        id: String = "fixture.alpha",
        version: String = "1.0.0",
        skillBody: String = "complete skill body",
        agentBody: String = "complete agent body"
    ): InstalledPlugin {
        val previewResult = manager.preview(archive(id, version, skillBody, agentBody))
        assertTrue(previewResult.toString(), previewResult is PluginPreviewResult.Success)
        val result = manager.install((previewResult as PluginPreviewResult.Success).preview.token)
        assertTrue(result.toString(), result is PluginInstallResult.Success)
        return (result as PluginInstallResult.Success).plugin
    }
    private suspend fun enable(id: String = "fixture.alpha") {
        val preview = manager.prepareTrust(id)
        assertTrue(preview.errors.toString(), preview.errors.isEmpty())
        assertEquals(PluginOperationResult.Success, manager.enable(id, preview.token))
    }
    private fun tree(): Map<String, List<Byte>> = temp.root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(temp.root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun identityAndHostDefinitionsDoNotSynchronouslyLoadTheManager() {
        hostDefinition("~/.aicode/skills/host/SKILL.md", "host", "host skill")
        hostDefinition("~/.aicode/agents/host.md", "host", "host agent")
        val skillIdentity = skills.sourceIdentity()
        val agentIdentity = agents.sourceIdentity()
        assertEquals(0L, pluginSource.revision)
        assertEquals("host skill", skills.listSkills().single().instructions)
        assertEquals("host agent", agents.find("host")!!.prompt)
        skills.checkSourceIdentity(skillIdentity)
        agents.checkSourceIdentity(agentIdentity)
        assertFalse(File(temp.root, "plugins").exists())
    }

    @Test fun installedDefinitionsRequireNativeTrustBeforeTheyEnterAvailableLists() = runBlocking<Unit> {
        install()
        assertTrue(skills.listAllSkills().isEmpty())
        assertTrue(agents.listAll().isEmpty())
        assertNull(skills.loadInstructions("fixture.alpha:read"))
        assertNull(agents.findIncludingDisabled("fixture.alpha:review"))
        val oldSkillIdentity = skills.sourceIdentity()
        val oldAgentIdentity = agents.sourceIdentity()
        enable()
        assertNotEquals(oldSkillIdentity, skills.sourceIdentity())
        assertNotEquals(oldAgentIdentity, agents.sourceIdentity())
        assertEquals("fixture.alpha:read", skills.listSkills().single().name)
        assertEquals("fixture.alpha:review", agents.listEnabled().single().definition.name)
    }

    @Test fun nativeNamespacedDefinitionsKeepBodiesCapabilitiesAndOriginMetadata() = runBlocking<Unit> {
        val body = "complete body\n".repeat(1000).trim()
        val installed = install(skillBody = body, agentBody = body)
        enable()
        val skill = skills.listAllSkills().single()
        val agent = agents.listAll().single()
        assertEquals(SkillScope.PLUGIN, skill.scope)
        assertEquals(AgentDefinitionScope.PLUGIN, agent.scope)
        assertNull(skill.skill.dirPath)
        assertNull(agent.definition.filePath)
        assertEquals(listOf("readFile"), skill.skill.requiredTools)
        assertEquals(listOf("readFile"), agent.definition.allowedTools)
        assertEquals(body, skills.loadInstructions("FIXTURE.ALPHA:READ"))
        assertEquals(body, agents.find("FIXTURE.ALPHA:REVIEW")!!.prompt)
        val skillOrigin = requireNotNull(skill.pluginOrigin)
        val agentOrigin = requireNotNull(agent.pluginOrigin)
        assertEquals("fixture.alpha", skillOrigin.pluginId)
        assertEquals("read", skillOrigin.localId)
        assertEquals(installed.record.installRevision, skillOrigin.installRevision)
        assertEquals(installed.record.contentHash, agentOrigin.contentHash)
        assertEquals(manager.capture("fixture.alpha")!!.activationEpoch, agentOrigin.activationEpoch)
    }

    @Test fun pluginFrontmatterNamesCannotReplaceHostOrBuiltinNames() = runBlocking<Unit> {
        hostDefinition("~/.aicode/skills/global/SKILL.md", "Explore", "global skill")
        hostDefinition("~/workspace/.aicode/skills/project/SKILL.md", "Explore", "project skill")
        hostDefinition("~/.aicode/agents/explore.md", "Explore", "builtin agent")
        install(); enable()
        assertEquals(setOf("Explore", "fixture.alpha:read"), skills.listSkills().map { it.name }.toSet())
        assertEquals("project skill", skills.loadInstructions("Explore"))
        assertEquals("builtin agent", agents.find("Explore")!!.prompt)
        assertEquals(setOf("Explore", "fixture.alpha:review"), agents.listEnabled().map { it.definition.name }.toSet())
    }

    @Test fun explicitHostNamespacedDefinitionsStillHavePriorityOverPlugins() = runBlocking<Unit> {
        hostDefinition("~/.aicode/skills/global/SKILL.md", "fixture.alpha:read", "host skill")
        hostDefinition("~/workspace/.aicode/agents/project.md", "fixture.alpha:review", "host agent")
        install(); enable()
        assertEquals(SkillScope.GLOBAL, skills.listAllSkills().single().scope)
        assertEquals(AgentDefinitionScope.PROJECT, agents.listAll().single().scope)
        assertEquals("host skill", skills.loadInstructions("fixture.alpha:read"))
        assertEquals("host agent", agents.find("fixture.alpha:review")!!.prompt)
    }

    @Test fun hostDisableListsFilterNewDispatchButIncludingDisabledKeepsActiveDefinition() = runBlocking<Unit> {
        install(); enable()
        val original = agents.find("fixture.alpha:review")!!
        skillDisabled = setOf("fixture.alpha:read")
        agentDisabled = setOf("fixture.alpha:review")
        assertTrue(skills.listSkills().isEmpty())
        assertNull(skills.loadInstructions("fixture.alpha:read"))
        assertNull(agents.find("fixture.alpha:review"))
        assertEquals(original, agents.findIncludingDisabled("fixture.alpha:review"))
        assertEquals(PluginOperationResult.Success, manager.disable("fixture.alpha"))
        assertNull(agents.findIncludingDisabled("fixture.alpha:review"))
        assertEquals("complete agent body", original.prompt)
    }

    @Test fun disablingThePluginInvalidatesBothIdentitiesAndTheOriginalCapture() = runBlocking<Unit> {
        install(); enable()
        val capture = manager.capture("fixture.alpha")!!
        val skillIdentity = skills.sourceIdentity()
        val agentIdentity = agents.sourceIdentity()
        val version = agents.definitionsVersion
        manager.disable("fixture.alpha")
        assertTrue(agents.definitionsVersion > version)
        assertThrows(CancellationException::class.java) { skills.checkSourceIdentity(skillIdentity) }
        assertThrows(CancellationException::class.java) { agents.checkSourceIdentity(agentIdentity) }
        assertThrows(PluginCaptureException::class.java) { capture.checkCurrent() }
        assertTrue(skills.listAllSkills().isEmpty())
        assertTrue(agents.listEnabled().isEmpty())
    }

    @Test fun reinstallRequiresNewTrustAndNeverReusesTheOldCapture() = runBlocking<Unit> {
        install(); enable()
        val originalCapture = manager.capture("fixture.alpha")!!
        val originalEntry = agents.listAll().single()
        val skillIdentity = skills.sourceIdentity()
        val agentIdentity = agents.sourceIdentity()
        val oldVersion = agents.definitionsVersion
        install(version = "1.1.0", skillBody = "updated skill", agentBody = "updated agent")
        assertNull(skills.loadInstructions("fixture.alpha:read"))
        assertNull(agents.findIncludingDisabled("fixture.alpha:review"))
        assertThrows(PluginCaptureException::class.java) { originalCapture.checkCurrent() }
        assertThrows(CancellationException::class.java) { skills.checkSourceIdentity(skillIdentity) }
        assertThrows(CancellationException::class.java) { agents.checkSourceIdentity(agentIdentity) }
        enable()
        assertTrue(agents.definitionsVersion > oldVersion)
        assertNotEquals(originalEntry.pluginOrigin!!.installRevision, agents.listAll().single().pluginOrigin!!.installRevision)
        assertEquals("updated skill", skills.loadInstructions("fixture.alpha:read"))
        assertEquals("updated agent", agents.find("fixture.alpha:review")!!.prompt)
        assertEquals("complete agent body", originalEntry.definition.prompt)
    }

    @Test fun anotherPluginsSameLocalIdIsNotAFallbackForADisabledSource() = runBlocking<Unit> {
        install(); enable()
        install(id = "fixture.beta", agentBody = "beta agent"); enable("fixture.beta")
        manager.disable("fixture.alpha")
        assertNull(skills.loadInstructions("fixture.alpha:read"))
        assertNull(agents.find("fixture.alpha:review"))
        assertNull(agents.findIncludingDisabled("fixture.alpha:review"))
        assertEquals("beta agent", agents.find("fixture.beta:review")!!.prompt)
    }

    @Test fun modifiedSkillFilesAreRejectedInsteadOfReturningPublishedBodies() = runBlocking<Unit> {
        install(); enable()
        assertEquals("complete skill body", skills.listSkills().single().instructions)
        val capture = manager.capture("fixture.alpha")!!
        val identity = skills.sourceIdentity()
        File(capture.installDir, "skills/read.md").appendText("\ntampered")
        assertThrows(PluginCaptureException::class.java) { skills.loadInstructions("fixture.alpha:read") }
        assertNotEquals(identity, skills.sourceIdentity())
        assertNull(skills.loadInstructions("fixture.alpha:read"))
    }

    @Test fun modifiedAgentFilesAreRejectedByIncludingDisabledAsWell() = runBlocking<Unit> {
        install(); enable()
        val original = agents.find("fixture.alpha:review")!!
        val capture = manager.capture("fixture.alpha")!!
        File(capture.installDir, "agents/review.md").appendText("\ntampered")
        assertThrows(PluginCaptureException::class.java) { agents.findIncludingDisabled("fixture.alpha:review") }
        assertNull(agents.find("fixture.alpha:review"))
        assertEquals("complete agent body", original.prompt)
    }

    @Test fun pluginScopeNeverSavesDeletesImportsOrWritesDisableFiles() = runBlocking<Unit> {
        install(); enable()
        hostDefinition("~/workspace/.aicode/skills/victim/SKILL.md", "victim", "preserve skill")
        hostDefinition("~/workspace/.aicode/agents/victim.md", "victim", "preserve agent")
        val before = tree()
        val skillIdentity = skills.sourceIdentity()
        val agentIdentity = agents.sourceIdentity()
        assertEquals(SkillSaveError.READ_ONLY, skills.save(SkillForm("safe", "", "changed"), SkillScope.PLUGIN, "../../victim"))
        assertEquals(AgentSaveError.READ_ONLY, agents.save(AgentDefinitionForm("safe", "", prompt = "changed"), AgentDefinitionScope.PLUGIN, "../../victim"))
        assertFalse(skills.deleteSkill("victim", SkillScope.PLUGIN))
        assertFalse(agents.delete("victim", AgentDefinitionScope.PLUGIN))
        assertThrows(PluginDefinitionReadOnlyException::class.java) { skills.setSkillDisabled("victim", true, SkillScope.PLUGIN) }
        assertThrows(PluginDefinitionReadOnlyException::class.java) { agents.setDisabled("victim", true, AgentDefinitionScope.PLUGIN) }
        assertThrows(PluginDefinitionReadOnlyException::class.java) { skills.skillsRoot(SkillScope.PLUGIN) }
        assertThrows(PluginDefinitionReadOnlyException::class.java) { agents.agentsRoot(AgentDefinitionScope.PLUGIN) }
        assertThrows(PluginDefinitionReadOnlyException::class.java) { skills.importMarkdown("body", "victim", SkillScope.PLUGIN) }
        val input = mockk<InputStream>()
        assertThrows(PluginDefinitionReadOnlyException::class.java) { skills.importZip(input, "victim", SkillScope.PLUGIN) }
        verify { input wasNot Called }
        verify(exactly = 0) { skillConfig.setDisabled(any(), any(), any()) }
        verify(exactly = 0) { agentConfig.setDisabled(any(), any(), any()) }
        assertEquals(before, tree())
        skills.checkSourceIdentity(skillIdentity)
        agents.checkSourceIdentity(agentIdentity)
    }

    @Test fun directConfigWritersRejectPluginBeforeConsultingAnyRoot() {
        val installer = mockk<ContainerInstaller>()
        val projectRoot = mockk<ProjectAicodeRoot>()
        val access = mockk<FileAccessProvider>()
        val hub = mockk<FileChangeHub>()
        every { hub.watchAicode(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { hub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        val skillConfig = SkillConfigRepository(installer, projectRoot, hub, access, localDefinitions)
        val agentConfig = AgentDefinitionConfigRepository(installer, projectRoot, hub, access,
            com.aicode.feature.agent.domain.subagent.legacyAgentDefinitions(access))
        assertThrows(PluginDefinitionReadOnlyException::class.java) { skillConfig.setDisabled("any", true, SkillScope.PLUGIN) }
        assertThrows(PluginDefinitionReadOnlyException::class.java) { agentConfig.setDisabled("any", true, AgentDefinitionScope.PLUGIN) }
        verify { installer wasNot Called; projectRoot wasNot Called; access wasNot Called }
    }

    @Test fun aNewlyLoadedPublishedManagerInvalidatesTheEmptyContributionView() = runBlocking<Unit> {
        install(); enable()
        val restored = PluginManager(File(temp.root, "plugins"), File(temp.root, "registry.json"))
        val source = PluginDefinitionSource(restored)
        assertEquals(0L, source.revision)
        assertTrue(source.skills().isEmpty())
        assertTrue(source.agents().isEmpty())
        restored.ensureLoaded()
        assertTrue(source.revision > 0)
        assertEquals("fixture.alpha:read", source.skills().single().skill.name)
        assertEquals("fixture.alpha:review", source.agents().single().definition.name)
    }
}
