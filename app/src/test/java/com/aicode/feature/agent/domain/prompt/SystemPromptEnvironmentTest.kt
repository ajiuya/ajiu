package com.aicode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Public build output, with real profile revisions/catalog resolution and temporary prompt files. */
class SystemPromptEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = MetadataStore()
    private val settings = ContainerSettingsRepository(store)
    private val mode = ExecutionModeHolder()
    private val installer = mockk<ContainerInstaller>()
    private val skills = mockk<SkillRepository>()
    private val agents = mockk<AgentDefinitionRepository>()
    private lateinit var aicode: File
    private lateinit var project: File
    private lateinit var provider: SystemPromptProvider
    private lateinit var context: AgentContext
    private var duringTemplates: () -> Unit = {}

    @Before fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } returns Unit
        aicode = temporary.newFolder("aicode")
        project = temporary.newFolder("project")
        every { installer.aicodeDir } returns aicode
        val android = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { android.assets } returns assets
        every { assets.list("prompts") } answers {
            duringTemplates()
            arrayOf(MAIN_FILE)
        }
        // No real app assets, guest rootfs, installer, Root, or execution factory is needed.
        writePrompt("prompts/$MAIN_FILE", "MAIN_ENV=$ENV")
        writePrompt("prompts/agent/subagent-base.md", "BASE_ENV=$ENV")
        writePrompt("prompts/agent/subagent-context.md", "CONTEXT_ENV=$ENV\nNAME={{AICODE_SUBAGENT_NAME}}")
        val projectRoot = mockk<ProjectAicodeRoot>()
        every { projectRoot.forPath(any()) } answers { File(firstArg<String>(), ".aicode") }
        val catalog = PromptFragmentCatalog(android, installer, projectRoot)
        every { skills.sourceIdentity() } returns "fixture-skills"
        every { skills.checkSourceIdentity(any()) } returns Unit
        every { skills.listSkills() } returns listOf(Skill("skill-sentinel", "skill-desc", instructions = "body"))
        val memory = mockk<MemoryRepository>()
        every { memory.listMemories(any()) } returns listOf(
            Memory("global-memory-sentinel", "global-desc", MemoryScope.GLOBAL, content = "body"),
            Memory("project-memory-sentinel", "project-desc", MemoryScope.PROJECT, content = "body")
        )
        every { agents.sourceIdentity() } returns "fixture-agents"
        every { agents.checkSourceIdentity(any()) } returns Unit
        every { agents.definitionsVersion } returns 0L
        every { agents.listEnabled() } returns listOf(AgentDefinitionEntry(
            AgentDefinition(name = "nested-agent-sentinel", description = "desc", prompt = "body"),
            AgentDefinitionScope.GLOBAL
        ))
        context = AgentContext(null, null, project.path, null, sessionId = "same-session")
        provider = SystemPromptProvider(android, skills, memory, catalog, installer, agents, mode, settings)
    }

    @After fun restoreLogger() {
        unmockkObject(FileLogger)
    }

    @Test fun prootLabelExplainsGuestRootWithoutClaimingHostRoot() {
        select(profile())
        val result = provider.build(context)
        assertTrue(result.contains("本地容器（PRoot）"))
        assertTrue(result.contains("guest 显示 root 不代表宿主 Root"))
        assertTrue(result.contains("运行状态未核验"))
        assertFalse(result.contains("CHROOT"))
        assertFalse(result.contains(ENV))
        assertEquals(1, store.reads.get())
    }

    @Test fun chrootWithInstanceDescribesGuestUidAndWritableRootfsWithoutClaimingHealthOrSu() {
        select(chroot())
        val result = provider.build(context)
        assertChroot(result)
        assertTrue(result.contains("配置已记录实例"))
        assertTrue(result.contains("健康状态未核验"))
        assertTrue(result.contains("真实 guest UID 0"))
        assertTrue(result.contains("rootfs 可写"))
        assertTrue(result.contains("不等于宿主执行或 Root 沙箱"))
        assertTrue(result.contains("不代表系统 su 已授权"))
    }

    @Test fun uncreatedChrootStillReportsChrootAndExplicitlySaysItCannotRunYet() {
        select(chroot().copy(chrootInstance = null))
        val result = provider.build(context)
        assertChroot(result)
        assertTrue(result.contains("实例未创建，尚不可运行"))
        assertFalse(result.contains("配置已记录实例"))
    }

    @Test fun remoteModeDoesNotReadLocalMetadataOrInferServerRuntime() {
        select(chroot())
        store.readFailure = AssertionError("SSH must not read local metadata")
        mode.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals("MAIN_ENV=远程 SSH 服务器", provider.build(context))
        assertEquals(0, store.reads.get())
    }

    @Test fun explicitBuiltinSelectionCanUseBuiltinFallbackEvenWhenAnotherProfileIsListed() {
        select(chroot())
        runBlocking { settings.setActiveProfile(ContainerProfile.BUILTIN_ID) }
        val result = provider.build(context)
        assertTrue(result.contains("本地容器（PRoot）"))
        assertFalse(result.contains("CHROOT"))
    }

    @Test fun missingSelectionNeverUsesTheFirstProfileOrImplicitBuiltin() {
        select(profile())
        runBlocking { settings.setActiveProfile("missing") }
        assertUnknown(provider.build(context))
        runBlocking { settings.deleteCustomProfile("selected") }
        assertUnknown(provider.build(context))
    }

    @Test fun localModeWithRemoteProfileIsUnknownInsteadOfProotOrSsh() {
        select(profile().copy(mode = ExecutionMode.REMOTE_SSH,
            rootfsSource = RootfsSource.RemoteSsh("connection", "/project")))
        val result = provider.build(context)
        assertUnknown(result)
        assertTrue(result.contains("不一致"))
        assertFalse(result.contains("远程 SSH 服务器"))
    }

    @Test fun unreadableMetadataIsUnknownAndNextBuildCanRecoverInTheSameSession() {
        select(chroot())
        store.readFailure = IOException("fixture unavailable")
        val first = provider.build(context)
        assertUnknown(first)
        assertTrue(first.contains("配置不可读或尚未加载"))
        store.readFailure = null
        assertChroot(provider.build(context))
        assertEquals(2, store.reads.get())
    }

    @Test fun metadataWithoutAnInitialSnapshotDoesNotPretendProotIsReady() {
        store.emitSnapshot = false
        assertUnknown(provider.build(context))
        assertEquals(1, store.reads.get())
    }

    @Test fun corruptSelectedProfileMetadataCannotUseRepositoryFallbackAsRuntimeEvidence() {
        select(chroot())
        runBlocking {
            store.updateData { before -> before.toMutablePreferences().apply {
                this[stringPreferencesKey("custom_profiles_json")] = "{broken"
            } }
        }
        assertUnknown(provider.build(context))
    }

    @Test fun metadataCancellationIsRethrownAndNeverCachedAsUnknown() {
        select(chroot())
        val cancelled = CancellationException("metadata cancelled")
        store.readFailure = cancelled
        val failure = assertThrows(CancellationException::class.java) { provider.build(context) }
        assertEquals(cancelled.message, failure.message)
        store.readFailure = null
        assertChroot(provider.build(context))
    }

    @Test fun sameSessionUsesNewSelectionAndSameIdRuntimeConfigurationOnTheNextBuild() {
        val original = profile()
        select(original)
        assertTrue(provider.build(context).contains("本地容器（PRoot）"))
        val selectionRevision = settings.activeProfileSelectionRevision
        val configurationRevision = settings.profileConfigurationRevision
        select(chroot())
        assertEquals(selectionRevision, settings.activeProfileSelectionRevision)
        assertTrue(settings.profileConfigurationRevision > configurationRevision)
        assertChroot(provider.build(context))
        select(profile("other"))
        assertTrue(provider.build(context).contains("本地容器（PRoot）"))
        assertEquals(3, store.reads.get())
    }

    @Test fun instancePublicationIsVisibleOnTheNextBuildWithoutChangingSessionOrProfileId() {
        val pending = chroot().copy(chrootInstance = null)
        select(pending)
        assertTrue(provider.build(context).contains("实例未创建"))
        runBlocking { settings.publishChrootInstance(settings.captureProfileSnapshot(), pending, chroot().chrootInstance) }
        val result = provider.build(context)
        assertChroot(result)
        assertTrue(result.contains("配置已记录实例"))
        assertFalse(result.contains("实例未创建"))
    }

    @Test fun sameIdRuntimeChangeDuringBuildRejectsTheCapturedDescription() {
        select(profile())
        duringTemplates = { select(chroot()) }
        assertThrows(CancellationException::class.java) { provider.build(context) }
        duringTemplates = {}
        assertChroot(provider.build(context))
    }

    @Test fun configurationAbaDuringBuildCannotPublishAnOldDescription() {
        val original = profile()
        select(original)
        duringTemplates = { select(chroot()); select(original) }
        assertThrows(CancellationException::class.java) { provider.build(context) }
        duringTemplates = {}
        assertTrue(provider.build(context).contains("本地容器（PRoot）"))
    }

    @Test fun profileSelectionAbaDuringBuildCannotPublishAnOldDescription() {
        select(chroot())
        duringTemplates = { runBlocking {
            settings.setActiveProfile("other")
            settings.setActiveProfile("selected")
        } }
        assertThrows(CancellationException::class.java) { provider.build(context) }
    }

    @Test fun modeAbaDuringBuildIsRejectedEvenWhenFinalModeMatches() {
        select(chroot())
        duringTemplates = ::modeAba
        assertThrows(CancellationException::class.java) { provider.build(context) }
        duringTemplates = {}
        assertChroot(provider.build(context))
    }

    @Test fun modeAbaDuringMetadataCaptureIsRejectedBeforeRendering() {
        var rendered = false
        duringTemplates = { rendered = true }
        store.beforeRead = ::modeAba
        assertThrows(CancellationException::class.java) { provider.build(context) }
        assertFalse(rendered)
        assertEquals(1, store.reads.get())
    }

    @Test fun remoteModeAbaDuringBuildAlsoRejectsTheOldDescriptionWithoutLocalReads() {
        mode.setMode(ExecutionMode.REMOTE_SSH)
        duringTemplates = {
            mode.setMode(ExecutionMode.LOCAL_PROOT)
            mode.setMode(ExecutionMode.REMOTE_SSH)
        }
        assertThrows(CancellationException::class.java) { provider.build(context) }
        assertEquals(0, store.reads.get())
    }

    @Test fun metadataFailureCannotHideProfileChangesDuringBuild() {
        store.readFailure = IOException("fixture unavailable")
        duringTemplates = { select(chroot()) }
        assertThrows(CancellationException::class.java) { provider.build(context) }
    }

    @Test fun subagentMainBaseDefinitionAndContextShareOneEnvironmentSnapshot() {
        select(chroot())
        writePrompt("prompts/$MAIN_FILE", "MAIN_ENV=$ENV\nMAIN_SKILLS={{AICODE_SKILLS}}\nMAIN_AGENTS={{AICODE_SUBAGENTS}}")
        writePrompt("prompts/agent/subagent-base.md", "<!-- fixture -->\nBASE_ENV=$ENV\nBASE_OTHER={{INSTRUCTION}}")
        val result = provider.build(subagent(setOf(InjectPart.MAIN_RULES, InjectPart.BASE),
            "BODY_ENV=$ENV\nBODY_WORKSPACE={{AICODE_WORKSPACE}}\nBODY_SKILLS={{AICODE_SKILLS}}\nBODY_AGENTS={{AICODE_SUBAGENTS}}\nBODY_OTHER={{INSTRUCTION}}"))
        val environments = result.lineSequence().filter { it.substringBefore('=') in ENV_KEYS }
            .map { it.substringAfter('=') }.toList()
        assertEquals(4, environments.size)
        assertEquals(1, environments.distinct().size)
        assertChroot(environments.first())
        assertFalse(result.contains(ENV))
        assertEquals(1, store.reads.get())
        assertTrue(result.contains("MAIN_SKILLS={{AICODE_SKILLS}}"))
        assertTrue(result.contains("MAIN_AGENTS={{AICODE_SUBAGENTS}}"))
        assertTrue(result.contains("BASE_OTHER={{INSTRUCTION}}"))
        assertTrue(result.contains("BODY_WORKSPACE=~/workspace"))
        assertTrue(result.contains("BODY_SKILLS=- skill-sentinel: skill-desc"))
        assertTrue(result.contains("BODY_OTHER={{INSTRUCTION}}"))
        assertTrue(result.contains("NAME=child"))
        assertFalse(result.contains("nested-agent-sentinel"))
        verify(exactly = 0) { agents.listEnabled() }
    }

    @Test fun subagentIncludesOnlySelectedRulePartsAndDoesNotAddUnselectedLists() {
        select(chroot())
        File(project, "AGENTS.md").writeText("project-rule-sentinel")
        listOf(emptySet(), setOf(InjectPart.MAIN_RULES), setOf(InjectPart.BASE),
            setOf(InjectPart.MAIN_RULES, InjectPart.BASE)).forEach { inject ->
            val result = provider.build(subagent(inject))
            assertEquals(InjectPart.MAIN_RULES in inject, result.contains("MAIN_ENV="))
            assertEquals(InjectPart.BASE in inject, result.contains("BASE_ENV="))
            assertTrue(result.contains("BODY_ENV="))
            assertTrue(result.contains("CONTEXT_ENV="))
            assertFalse(result.contains(ENV))
            assertFalse(result.contains("skill-sentinel"))
            assertFalse(result.contains("memory-sentinel"))
            assertFalse(result.contains("project-rule-sentinel"))
            assertFalse(result.contains("nested-agent-sentinel"))
        }
        assertEquals(4, store.reads.get())
        verify(exactly = 0) { agents.listEnabled() }
    }

    @Test fun subagentExplicitSkillsMemoryAndProjectRulesStillInjectTheirContent() {
        select(chroot())
        File(project, "AGENTS.md").writeText("project-rule-sentinel")
        val result = provider.build(subagent(setOf(InjectPart.SKILLS, InjectPart.MEMORY, InjectPart.PROJECT_RULES)))
        assertTrue(result.contains("skill-sentinel"))
        assertTrue(result.contains("global-memory-sentinel"))
        assertTrue(result.contains("project-memory-sentinel"))
        assertTrue(result.contains("project-rule-sentinel"))
        assertFalse(result.contains("MAIN_ENV="))
        assertFalse(result.contains("BASE_ENV="))
        assertFalse(result.contains("nested-agent-sentinel"))
    }

    @Test fun subagentReturnStillChecksTheSourceAfterRenderingSelectedRules() {
        select(chroot())
        duringTemplates = ::modeAba
        assertThrows(CancellationException::class.java) {
            provider.build(subagent(setOf(InjectPart.MAIN_RULES, InjectPart.BASE)))
        }
    }

    @Test fun customOnlyUsesOneEnvironmentAndKeepsOtherVariableExpansion() {
        select(chroot().copy(chrootInstance = null))
        disableBuiltin()
        writePrompt("prompts.custom/10-custom.md", "CUSTOM_ENV=$ENV\nAGAIN_ENV=$ENV\nWORKSPACE={{AICODE_WORKSPACE}}\nOTHER={{INSTRUCTION}}")
        val result = provider.build(context)
        assertChroot(result)
        val lines = result.lines()
        assertEquals(lines[0].substringAfter('='), lines[1].substringAfter('='))
        assertTrue(result.contains("实例未创建"))
        assertTrue(result.contains("WORKSPACE=~/workspace"))
        assertTrue(result.contains("OTHER={{INSTRUCTION}}"))
        assertFalse(result.contains("MAIN_ENV="))
        assertFalse(result.contains(ENV))
        assertEquals(1, store.reads.get())
    }

    @Test fun customOnlySourceChangesAreRejectedBeforeReturn() {
        select(chroot())
        disableBuiltin()
        writePrompt("prompts.custom/10-custom.md", "CUSTOM_ENV=$ENV")
        duringTemplates = ::modeAba
        assertThrows(CancellationException::class.java) { provider.build(context) }
    }

    @Test fun disabledBuiltinWithoutCustomFragmentsRemainsEmptyEvenIfMetadataIsUnreadable() {
        disableBuiltin()
        store.readFailure = IOException("fixture unavailable")
        assertEquals("", provider.build(context))
        verify(exactly = 0) { skills.listSkills(); agents.listEnabled() }
    }

    @Test fun emptyCustomOnlyStillRejectsModeAbaBeforeReturning() {
        disableBuiltin()
        duringTemplates = ::modeAba
        assertThrows(CancellationException::class.java) { provider.build(context) }
    }

    @Test fun emptyCustomOverrideDoesNotRestoreBuiltinEnvironment() {
        select(chroot())
        writePrompt("prompts.custom/99-empty.md", "")
        assertEquals("", provider.build(context))
        disableBuiltin()
        assertEquals("", provider.build(context))
    }

    @Test fun disabledNumberedFragmentIsNotRestoredByEnvironmentRendering() {
        select(chroot())
        writePrompt("prompts.custom/prompts.json", """{"enabled":{"99":false}}""")
        assertEquals("", provider.build(context))
        val result = provider.build(subagent(setOf(InjectPart.MAIN_RULES)))
        assertFalse(result.contains("MAIN_ENV="))
        assertChroot(result)
    }

    @Test fun subagentMainRulesRespectBuiltinDisableAndEmptyBaseOverride() {
        select(chroot())
        disableBuiltin()
        writePrompt("prompts.custom/10-custom.md", "CUSTOM_ENV=$ENV")
        writePrompt("prompts.custom/agent/subagent-base.md", "")
        val result = provider.build(subagent(setOf(InjectPart.MAIN_RULES, InjectPart.BASE)))
        assertFalse(result.contains("MAIN_ENV="))
        assertFalse(result.contains("BASE_ENV="))
        assertTrue(result.contains("CUSTOM_ENV=本地容器（CHROOT）"))
        assertFalse(result.contains(ENV))
    }

    @Test fun metadataDescriptionOnlyReadsMetadataAndNeverTouchesInstallerOrGuestOperations() {
        select(chroot())
        val writes = store.writes.get()
        val files = fixtureFiles()
        assertChroot(provider.build(context))
        assertEquals(1, store.reads.get())
        assertEquals(writes, store.writes.get())
        assertEquals(files, fixtureFiles())
        // Strict installer mock: every call, including any swallowed failure, must be accounted for.
        verify(atLeast = 1) { installer.aicodeDir }
        confirmVerified(installer)
    }

    @Test fun frozenRuntimeProfileWinsOverActiveProfileWithoutRecaptureOrGuestCalls() {
        select(profile())
        val frozenProfile = chroot("frozen")
        val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "same-session")
        var runtimeChecks = 0
        var sourceChecks = 0
        val runtime = LocalRuntimeCapture(owner, frozenProfile.id, 7L, frozenProfile.chrootInstance!!,
            project.path, "/root", "fixture-digest", Any(), frozenProfile) { runtimeChecks++ }
        val access = mockk<FileAccessProvider>()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", access, null, null,
            runtimeOwner = owner, localRuntimeCapture = runtime, checkSource = { sourceChecks++ })
        val result = execution.useCaptured { provider.build(context) }
        assertChroot(result)
        assertEquals(0, store.reads.get())
        assertTrue(runtimeChecks >= 2)
        assertTrue(sourceChecks >= 2)
        verify { access wasNot Called }
        verify(atLeast = 1) { installer.aicodeDir }
        confirmVerified(installer)
    }

    @Test fun frozenProotMappingWinsOverActiveChrootWithoutReadingMetadata() {
        select(chroot())
        val mapping = WorkspacePathMapper.ExecutionMapping(profile("frozen"), project,
            File(temporary.root, "uncreated-rootfs"), aicode, "/root")
        val access = mockk<FileAccessProvider>()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", access, mapping, null) {}
        val result = execution.useCaptured { provider.build(context) }
        assertTrue(result.contains("本地容器（PRoot）"))
        assertFalse(result.contains("CHROOT"))
        assertEquals(0, store.reads.get())
        verify { access wasNot Called }
    }

    @Test fun frozenProfileSnapshotCanDescribeUncreatedChrootWithoutRuntimeCapture() {
        select(chroot().copy(chrootInstance = null))
        val snapshot = runBlocking { settings.captureProfileSnapshot() }
        select(profile("active"))
        val reads = store.reads.get()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
            profileSnapshot = snapshot, checkSource = {})
        val result = execution.useCaptured { provider.build(context) }
        assertChroot(result)
        assertTrue(result.contains("实例未创建"))
        assertEquals(reads, store.reads.get())
    }

    @Test fun frozenLocalContextWithoutProfileStaysUnknownInsteadOfUsingActiveSelection() {
        select(profile())
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null) {}
        assertUnknown(execution.useCaptured { provider.build(context) })
        assertEquals(0, store.reads.get())
    }

    @Test fun frozenRemoteContextWinsOverGlobalLocalModeWithoutLocalReads() {
        select(chroot())
        val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "fixture", mockk(), null, null) {}
        assertEquals("MAIN_ENV=远程 SSH 服务器", execution.useCaptured { provider.build(context) })
        assertEquals(0, store.reads.get())
    }

    @Test fun revokedFrozenContextIsRejectedWithoutFallingBackToActiveSettings() {
        select(chroot())
        val cancelled = CancellationException("frozen source revoked")
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null) { throw cancelled }
        val previous = execution.updateThreadContext(execution)
        try {
            assertSame(cancelled, assertThrows(CancellationException::class.java) { provider.build(context) })
        } finally {
            execution.restoreThreadContext(execution, previous)
        }
        assertEquals(0, store.reads.get())
    }

    @Test fun frozenContextRevokedWhileRenderingCannotReturnItsDescription() {
        var revoked = false
        val mapping = WorkspacePathMapper.ExecutionMapping(profile(), project,
            File(temporary.root, "uncreated-rootfs"), aicode, "/root")
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), mapping, null) {
            if (revoked) throw CancellationException("frozen source revoked")
        }
        duringTemplates = { revoked = true }
        // Install directly so this assertion exercises provider's final check, not useCaptured's final check.
        val previous = execution.updateThreadContext(execution)
        try {
            assertThrows(CancellationException::class.java) { provider.build(context) }
        } finally {
            execution.restoreThreadContext(execution, previous)
        }
        assertEquals(0, store.reads.get())
    }

    private fun select(profile: ContainerProfile) = runBlocking {
        settings.upsertCustomProfile(profile)
        settings.setActiveProfile(profile.id)
    }

    private fun modeAba() {
        mode.setMode(ExecutionMode.REMOTE_SSH)
        mode.setMode(ExecutionMode.LOCAL_PROOT)
    }

    private fun subagent(inject: Set<InjectPart>, prompt: String = "BODY_ENV=$ENV") =
        context.copy(agentDefinition = AgentDefinition("child", "fixture", inject = inject, prompt = prompt))

    private fun disableBuiltin() = writePrompt("prompts.custom/${PromptFragmentResolver.DISABLE_BUILTIN_FILE}", "")

    private fun writePrompt(path: String, content: String) = File(aicode, path).apply {
        parentFile?.mkdirs()
        writeText(content)
    }

    private fun fixtureFiles(): Map<String, String> = temporary.root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(temporary.root).path to it.readText() }

    private fun profile(id: String = "selected") = ContainerProfile(id, "untrusted name $ENV",
        RootfsSource.LocalFile("content://fixture/rootfs"), "/bin/sh", isBuiltin = false, createdAt = 1L)

    private fun chroot(id: String = "selected") = profile(id).copy(localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = ChrootInstance("0123456789abcdef01234567", 7L, 8L, 1L))

    private fun assertChroot(result: String) {
        assertTrue(result, result.contains("本地容器（CHROOT）"))
        assertFalse(result, result.contains("PRoot"))
        assertFalse(result, result.contains("untrusted name"))
    }

    private fun assertUnknown(result: String) {
        assertTrue(result, result.contains("运行时未知"))
        assertFalse(result, result.contains("PRoot"))
        assertFalse(result, result.contains("本地容器（CHROOT）"))
        assertFalse(result, result.contains(ENV))
    }

    /** Repository mutations/revision checks are real; persistence and reads remain in memory. */
    private class MetadataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        @Volatile var readFailure: Throwable? = null
        @Volatile var emitSnapshot = true
        @Volatile var beforeRead: () -> Unit = {}

        override val data: Flow<Preferences> get() = flow {
            reads.incrementAndGet()
            beforeRead()
            readFailure?.let { throw it }
            if (emitSnapshot) emit(state.value)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it; writes.incrementAndGet() }
    }

    private companion object {
        const val ENV = "{{AICODE_ENVIRONMENT}}"
        const val MAIN_FILE = "99-runtime-context.md"
        val ENV_KEYS = setOf("MAIN_ENV", "BASE_ENV", "BODY_ENV", "CONTEXT_ENV")
    }
}
