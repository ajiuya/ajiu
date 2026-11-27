package com.aicode.feature.settings.presentation

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LogLevel
import com.aicode.feature.agent.data.local.dao.CallSummary
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerImageCatalogData
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillDefinitionSourceChanged
import com.aicode.feature.agent.domain.skill.SkillEntry
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillRepository.DefinitionSnapshot
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.SubagentTierSelection
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.UpdateChannel
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSkillDefinitionRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val skillRepository = mockk<SkillRepository>()
    private val agentRepository = mockk<com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository>()
    private var agentSource = "fixture-subagents"
    private var agentSnapshot = com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository.DefinitionSnapshot(agentSource, emptyList(), emptySet())
    private val definitionFactory = mockk<WorkspaceExecutionContextFactory>()
    private val skillChanges = MutableSharedFlow<Unit>()
    private val chrootProfile = ContainerProfile(
        id = "settings-chroot-fixture",
        name = "CHROOT fixture",
        rootfsSource = RootfsSource.Asset("fixture-rootfs.bin"),
        shellPath = "/bin/sh",
        isBuiltin = false,
        localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = null
    )
    private var currentSource = "host-definitions:fixture-a"
    private var nextSnapshot = DefinitionSnapshot(currentSource, emptyList(), emptySet())
    private var readSnapshot: suspend () -> DefinitionSnapshot = { nextSnapshot }
    private var snapshotReads = 0
    private var startupSnapshotReads = 0
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { skillRepository.sourceIdentity() } answers { currentSource }
        every { skillRepository.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != currentSource) throw SkillDefinitionSourceChanged()
        }
        coEvery { skillRepository.readDefinitionSnapshot(definitionFactory, forceRefresh = true) } coAnswers {
            snapshotReads++
            readSnapshot()
        }
        every { agentRepository.sourceIdentity() } answers { agentSource }
        every { agentRepository.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != agentSource) throw com.aicode.feature.agent.domain.subagent.AgentDefinitionSourceChanged()
        }
        coEvery { agentRepository.readDefinitionSnapshot(definitionFactory, forceRefresh = true) } answers { agentSnapshot }
        coEvery { definitionFactory.freeze(null, null, null) } throws
            AssertionError("Settings definition refresh must not freeze guest execution")
        viewModel = createViewModel()
        store.put("settings-skills", viewModel)
        settle()
        assertEquals(listOf(chrootProfile), viewModel.customProfiles.value)
        assertEquals(ExecutionMode.LOCAL_PROOT, viewModel.executionMode.value)
        startupSnapshotReads = snapshotReads
        clearMocks(
            definitionFactory,
            answers = false,
            recordedCalls = true,
            childMocks = false,
            verificationMarks = false,
            exclusionRules = false
        )
    }

    @After
    fun tearDown() {
        try {
            store.clear()
            settle()
        } finally {
            unmockkObject(FileLogger)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun subagentRefreshIncludesDisabledRolesWithoutFreezingUncreatedChroot() {
        val role = com.aicode.feature.agent.domain.subagent.AgentDefinition(name = "编码员", description = "code", prompt = "body")
        agentSnapshot = agentSnapshot.copy(entries = listOf(com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry(
            role, com.aicode.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL)), disabledNames = setOf("编码员"))
        viewModel.refreshSubAgents()
        settle()
        assertEquals("编码员", viewModel.subAgents.value.single().name)
        assertTrue(viewModel.subAgents.value.single().disabled)
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun subagentSourceChangeCannotPublishAStaleSnapshot() {
        val role = com.aicode.feature.agent.domain.subagent.AgentDefinition(name = "旧角色", description = "", prompt = "body")
        agentSnapshot = agentSnapshot.copy(entries = listOf(com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry(
            role, com.aicode.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL)))
        viewModel.refreshSubAgents()
        settle()
        assertEquals("旧角色", viewModel.subAgents.value.single().name)
        agentSource = "another-workspace"
        viewModel.refreshSubAgents()
        settle()
        assertTrue(viewModel.subAgents.value.isEmpty())
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun explicitSkillRefreshDoesNotFreezeGuestExecution() {
        val global = entry("MiXeD", SkillScope.GLOBAL, listOf("readFile", "search"))
        val project = entry("ProjectSkill", SkillScope.PROJECT)
        val plugin = entry("PluginSkill", SkillScope.PLUGIN)
        nextSnapshot = DefinitionSnapshot(
            currentSource,
            listOf(global, project, plugin),
            setOf("mixed", "missing-skill")
        )

        viewModel.refreshSkills()
        settle()

        assertTrue(chrootProfile.isChroot)
        assertNull(chrootProfile.chrootInstance)
        assertEquals(
            listOf(
                uiEntry("MiXeD", SkillScope.GLOBAL, disabled = true, requiredTools = listOf("readFile", "search")),
                uiEntry("ProjectSkill", SkillScope.PROJECT),
                uiEntry("PluginSkill", SkillScope.PLUGIN)
            ),
            viewModel.skills.value
        )
        coVerify(exactly = startupSnapshotReads + 1) {
            skillRepository.readDefinitionSnapshot(definitionFactory, forceRefresh = true)
        }
        verify(exactly = 0) { skillRepository.disabledNames() }
        verify(exactly = 0) { skillRepository.listAllSkills() }
        verify(exactly = 0) { skillRepository.listAllSkills(any()) }
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun setSkillEnabledWritesThenRefreshesTheSameSource() = runTest(dispatcher) {
        publish(entry("ProjectSkill", SkillScope.PROJECT))
        val readsBeforeWrite = snapshotReads
        val writeEntered = CompletableDeferred<Triple<String, Boolean, SkillScope>>()
        val finishWrite = CompletableDeferred<Unit>()
        val writeSource = CompletableDeferred<String>()
        val refreshed = CompletableDeferred<DefinitionSnapshot>()
        every { skillRepository.setSkillDisabled(any(), any(), any()) } answers {
            writeEntered.complete(Triple(firstArg(), secondArg(), thirdArg()))
            runBlocking { finishWrite.await() }
            nextSnapshot = nextSnapshot.copy(disabledNames = setOf("projectskill"))
            writeSource.complete(currentSource)
        }
        readSnapshot = { nextSnapshot.also { refreshed.complete(it) } }

        try {
            viewModel.setSkillEnabled("ProjectSkill", enabled = false, scope = SkillScope.PROJECT)
            runCurrent()
            assertEquals(Triple("ProjectSkill", true, SkillScope.PROJECT), writeEntered.await())
            assertEquals(readsBeforeWrite, snapshotReads)
            assertEquals(listOf(uiEntry("ProjectSkill", SkillScope.PROJECT)), viewModel.skills.value)

            finishWrite.complete(Unit)
            val sourceUsedForWrite = writeSource.await()
            val snapshotUsedForRefresh = refreshed.await()
            runCurrent()

            assertEquals(sourceUsedForWrite, snapshotUsedForRefresh.sourceIdentity)
            assertEquals(setOf("projectskill"), snapshotUsedForRefresh.disabledNames)
            assertEquals(readsBeforeWrite + 1, snapshotReads)
            assertEquals(
                listOf(uiEntry("ProjectSkill", SkillScope.PROJECT, disabled = true)),
                viewModel.skills.value
            )
            verify(exactly = 1) {
                skillRepository.setSkillDisabled("ProjectSkill", true, SkillScope.PROJECT)
            }
            assertNoGuestFreezeForExplicitSkillOperations()
        } finally {
            finishWrite.complete(Unit)
        }
    }

    @Test
    fun olderConcurrentRefreshCannotOverwriteNewerSnapshot() {
        val oldResult = CompletableDeferred<DefinitionSnapshot>()
        val newResult = CompletableDeferred<DefinitionSnapshot>()
        val oldEntered = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        var request = 0
        readSnapshot = {
            when (++request) {
                1 -> {
                    oldEntered.complete(Unit)
                    oldResult.await().also { oldReturned.complete(Unit) }
                }
                2 -> {
                    newEntered.complete(Unit)
                    newResult.await()
                }
                else -> error("Unexpected fixture snapshot request")
            }
        }

        viewModel.refreshSkills()
        settle()
        assertTrue(oldEntered.isCompleted)
        viewModel.refreshSkills()
        settle()
        assertTrue(newEntered.isCompleted)

        newResult.complete(snapshot(entry("NewSkill", SkillScope.PROJECT)))
        settle()
        assertEquals(listOf(uiEntry("NewSkill", SkillScope.PROJECT)), viewModel.skills.value)
        assertFalse(oldReturned.isCompleted)

        oldResult.complete(snapshot(entry("OldSkill", SkillScope.GLOBAL)))
        settle()
        assertTrue(oldReturned.isCompleted)
        assertEquals(listOf(uiEntry("NewSkill", SkillScope.PROJECT)), viewModel.skills.value)
        assertEquals(startupSnapshotReads + 2, snapshotReads)
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun sourceChangeDuringReadClearsOldSkillsAndAllowsRefreshFromTheNewSource() {
        publish(entry("OldSkill", SkillScope.GLOBAL))
        val entered = CompletableDeferred<Unit>()
        val sourceChanged = CompletableDeferred<Unit>()
        readSnapshot = {
            entered.complete(Unit)
            sourceChanged.await()
            throw SkillDefinitionSourceChanged()
        }
        viewModel.refreshSkills()
        settle()
        assertTrue(entered.isCompleted)
        assertEquals(listOf(uiEntry("OldSkill", SkillScope.GLOBAL)), viewModel.skills.value)

        currentSource = "host-definitions:fixture-b"
        sourceChanged.complete(Unit)
        settle()
        assertTrue(viewModel.skills.value.isEmpty())
        verify(exactly = 0) { FileLogger.w("SettingsViewModel", "刷新技能失败", any()) }

        nextSnapshot = snapshot(entry("NewSkill", SkillScope.PROJECT))
        readSnapshot = { nextSnapshot }
        viewModel.refreshSkills()
        settle()
        assertEquals(listOf(uiEntry("NewSkill", SkillScope.PROJECT)), viewModel.skills.value)
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun sourceChangeImmediatelyBeforePublishRejectsTheStaleSnapshot() {
        publish(entry("OldSkill", SkillScope.GLOBAL))
        val stale = snapshot(entry("LateSkill", SkillScope.PROJECT))
        readSnapshot = {
            currentSource = "host-definitions:fixture-b"
            stale
        }

        viewModel.refreshSkills()
        settle()

        assertTrue(viewModel.skills.value.isEmpty())
        verify { skillRepository.checkSourceIdentity(stale.sourceIdentity) }
        verify(exactly = 0) { FileLogger.w("SettingsViewModel", "刷新技能失败", any()) }
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun genuineCancellationCancelsRefreshWithoutClearingPublishedSkills() {
        publish(entry("ExistingSkill", SkillScope.PROJECT))
        val cancellation = CancellationException("fixture-real-cancellation")
        val completion = CompletableDeferred<Throwable?>()
        lateinit var refreshJob: Job
        readSnapshot = {
            refreshJob = currentCoroutineContext().job
            refreshJob.invokeOnCompletion { completion.complete(it) }
            throw cancellation
        }

        viewModel.refreshSkills()
        settle()

        assertTrue(refreshJob.isCancelled)
        assertTrue(completion.isCompleted)
        assertSame(cancellation, completion.getCompleted())
        assertEquals(listOf(uiEntry("ExistingSkill", SkillScope.PROJECT)), viewModel.skills.value)
        verify(exactly = 0) { FileLogger.w("SettingsViewModel", "刷新技能失败", any()) }

        nextSnapshot = snapshot(entry("RecoveredSkill", SkillScope.PROJECT))
        readSnapshot = { nextSnapshot }
        viewModel.refreshSkills()
        settle()
        assertEquals(listOf(uiEntry("RecoveredSkill", SkillScope.PROJECT)), viewModel.skills.value)
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun clearingViewModelStoreCancelsAnInFlightSnapshotRead() {
        publish(entry("ExistingSkill", SkillScope.GLOBAL))
        val entered = CompletableDeferred<Unit>()
        val readFinished = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Throwable?>()
        lateinit var refreshJob: Job
        readSnapshot = {
            refreshJob = currentCoroutineContext().job
            refreshJob.invokeOnCompletion { completion.complete(it) }
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                readFinished.complete(Unit)
            }
        }
        viewModel.refreshSkills()
        settle()
        assertTrue(entered.isCompleted)
        assertFalse(readFinished.isCompleted)

        store.clear()
        settle()

        assertTrue(refreshJob.isCancelled)
        assertTrue(readFinished.isCompleted)
        assertTrue(completion.isCompleted)
        assertTrue(completion.getCompleted() is CancellationException)
        assertEquals(listOf(uiEntry("ExistingSkill", SkillScope.GLOBAL)), viewModel.skills.value)
        verify(exactly = 0) { FileLogger.w("SettingsViewModel", "刷新技能失败", any()) }
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    @Test
    fun ordinaryReadFailureIsLoggedRatherThanReportedAsAnEmptySuccess() {
        publish(entry("ExistingSkill", SkillScope.GLOBAL))
        val failure = IOException("fixture-snapshot-read-failure")
        readSnapshot = { throw failure }

        viewModel.refreshSkills()
        settle()

        assertTrue(viewModel.skills.value.isEmpty())
        verify(exactly = 1) { FileLogger.w("SettingsViewModel", "刷新技能失败", failure) }
        nextSnapshot = snapshot(entry("RecoveredSkill", SkillScope.PROJECT))
        readSnapshot = { nextSnapshot }
        viewModel.refreshSkills()
        settle()
        assertEquals(listOf(uiEntry("RecoveredSkill", SkillScope.PROJECT)), viewModel.skills.value)
        assertNoGuestFreezeForExplicitSkillOperations()
    }

    private fun createViewModel() = SettingsViewModel(
        context = RuntimeEnvironment.getApplication(),
        repository = mockk(relaxed = true) {
            every { getAllProviders() } returns MutableStateFlow(emptyList())
        },
        modelApiService = mockk(relaxed = true),
        modelMetadataService = mockk(relaxed = true),
        logSettingsRepository = mockk(relaxed = true) {
            every { levelFlow } returns MutableStateFlow(LogLevel.NONE)
        },
        themeSettingsRepository = mockk(relaxed = true) {
            every { themeModeFlow } returns MutableStateFlow(AppThemeMode.AUTO)
            every { themePresetIdFlow } returns MutableStateFlow<String?>(null)
            every { dynamicColorFlow } returns MutableStateFlow(false)
            every { chatTextScaleFlow } returns MutableStateFlow(ThemeSettingsRepository.DEFAULT_CHAT_TEXT_SCALE)
        },
        chatBubbleSettingsRepository = mockk(relaxed = true) {
            every { userStyleFlow } returns MutableStateFlow(ChatBubbleStyle.ORIGINAL)
            every { assistantStyleFlow } returns MutableStateFlow(ChatBubbleStyle.DOCUMENT)
        },
        backgroundSettingsRepository = mockk(relaxed = true) {
            every { imagePathFlow } returns MutableStateFlow<String?>(null)
            every { alphaFlow } returns MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
            every { chatImagePathFlow } returns MutableStateFlow<String?>(null)
            every { chatAlphaFlow } returns MutableStateFlow(BackgroundSettingsRepository.DEFAULT_CHAT_ALPHA)
        },
        keepaliveSettingsRepository = mockk(relaxed = true) {
            every { enabledFlow } returns MutableStateFlow(false)
        },
        screenOnSettingsRepository = mockk(relaxed = true) {
            every { enabledFlow } returns MutableStateFlow(false)
        },
        agentSoundSettingsRepository = mockk(relaxed = true) {
            every { enabledFlow } returns MutableStateFlow(false)
        },
        liveNotificationSettingsRepository = mockk(relaxed = true) {
            every { enabledFlow } returns MutableStateFlow(false)
        },
        approvalNotificationSettingsRepository = mockk(relaxed = true) {
            every { enabledFlow } returns MutableStateFlow(false)
        },
        generalSettingsRepository = mockk(relaxed = true) {
            every { typewriterCharsPerSecondFlow } returns MutableStateFlow(GeneralSettingsRepository.DEFAULT_TYPEWRITER_CHARS_PER_SECOND)
            every { autoExpandReasoningFlow } returns MutableStateFlow(false)
            every { autoRemoveStaleModelsFlow } returns MutableStateFlow(false)
            every { startupSessionModeFlow } returns MutableStateFlow(StartupSessionMode.NEW_SESSION)
            every { firstByteTimeoutSecFlow } returns MutableStateFlow(300)
            every { streamIdleTimeoutSecFlow } returns MutableStateFlow(300)
            every { maxNetworkRetriesFlow } returns MutableStateFlow(0)
            every { enterToSendFlow } returns MutableStateFlow(false)
            every { compactionThresholdPercentFlow } returns MutableStateFlow(85)
            every { sendFileMaxSizeMbFlow } returns MutableStateFlow(100)
            every { goalMaxTurnsFlow } returns MutableStateFlow(50)
            every { goalMaxTokensFlow } returns MutableStateFlow(500_000)
            every { deleteExternalWorkspaceSessionsFlow } returns MutableStateFlow(false)
            every { promptsAnnouncementShownHashFlow } returns MutableStateFlow<String?>(null)
        },
        rootAccessSettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(RootAccessState())
        },
        rootCommandManager = mockk(relaxed = true),
        toolPermissionManager = mockk(relaxed = true),
        languageSettingsRepository = mockk(relaxed = true) {
            every { languageFlow } returns MutableStateFlow<String?>("en")
        },
        mcpConfigRepository = mockk(relaxed = true) {
            every { effectiveEntriesFlow } returns MutableStateFlow(emptyList())
        },
        mcpManager = mockk(relaxed = true) {
            every { statuses } returns MutableStateFlow(emptyList())
        },
        permissionRulesRepository = mockk(relaxed = true) {
            every { currentProjectNameFlow } returns MutableStateFlow<String?>(null)
            every { globalRulesFlow } returns MutableStateFlow(emptyList())
            every { currentProjectRulesFlow } returns MutableStateFlow(emptyList())
        },
        skillRepository = skillRepository,
        agentDefinitionRepository = agentRepository,
        agentDefinitionConfigRepository = mockk(relaxed = true) {
            every { changes } returns MutableSharedFlow()
        },
        toolRegistry = mockk(relaxed = true),
        skillConfigRepository = mockk(relaxed = true) {
            every { changes } returns skillChanges
        },
        visionModelSettingsRepository = mockk(relaxed = true) {
            every { providerIdFlow } returns MutableStateFlow("")
            every { modelFlow } returns MutableStateFlow("")
        },
        imageGenModelSettingsRepository = mockk(relaxed = true) {
            every { providerIdFlow } returns MutableStateFlow("")
            every { modelFlow } returns MutableStateFlow("")
        },
        compactionModelSettingsRepository = mockk(relaxed = true) {
            every { providerIdFlow } returns MutableStateFlow("")
            every { modelFlow } returns MutableStateFlow("")
        },
        titleModelSettingsRepository = mockk(relaxed = true) {
            every { providerIdFlow } returns MutableStateFlow("")
            every { modelFlow } returns MutableStateFlow("")
        },
        subagentTierSettingsRepository = mockk(relaxed = true) {
            every { selectionFlow(any()) } returns MutableStateFlow(SubagentTierSelection())
        },
        defaultModelSettingsRepository = mockk(relaxed = true) {
            every { providerIdFlow } returns MutableStateFlow("")
            every { modelFlow } returns MutableStateFlow("")
        },
        containerSettingsRepository = mockk(relaxed = true) {
            every { activeProfileIdFlow } returns MutableStateFlow(chrootProfile.id)
            every { defaultContainerIdFlow } returns MutableStateFlow(chrootProfile.id)
            every { customProfilesFlow } returns MutableStateFlow(listOf(chrootProfile))
            every { downloadedImagesFlow } returns MutableStateFlow(emptyList())
            every { announcementShownHashFlow } returns MutableStateFlow<String?>(null)
        },
        containerImageCatalog = mockk(relaxed = true) {
            every { catalog } returns MutableStateFlow(ContainerImageCatalogData())
        },
        containerImageDownloader = mockk(relaxed = true),
        containerImageVersionProbe = mockk(relaxed = true),
        containerInstaller = mockk(relaxed = true),
        localRuntimeUserActions = mockk(relaxed = true),
        containerOsDetector = mockk(relaxed = true) {
            every { osMap } returns MutableStateFlow(emptyMap())
        },
        executionModeRepository = mockk(relaxed = true),
        executionModeHolder = ExecutionModeHolder(),
        remoteSshConnection = mockk(relaxed = true) {
            every { connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        },
        remoteRepository = mockk(relaxed = true) {
            every { getConnections() } returns MutableStateFlow(emptyList())
        },
        llmCallRecordDao = mockk(relaxed = true) {
            every { getDayStats(any(), any(), any(), any()) } returns MutableStateFlow(emptyList())
            every { getSummary(any(), any(), any()) } returns MutableStateFlow(CallSummary(0, 0, 0, 0, 0, 0, null, null))
            every { getProviderStats(any(), any(), any()) } returns MutableStateFlow(emptyList())
            every { getModelStats(any(), any(), any()) } returns MutableStateFlow(emptyList())
            every { getModelProviderCostStats(any(), any(), any()) } returns MutableStateFlow(emptyList())
            every { getCallsCount(any(), any(), any()) } returns MutableStateFlow(0)
            every { getRecentCalls(any(), any(), any(), any(), any()) } returns MutableStateFlow(emptyList())
        },
        modelCostCalculator = mockk(relaxed = true),
        updateCheckSettingsRepository = mockk(relaxed = true) {
            every { autoCheckEnabled } returns false
            every { channel } returns UpdateChannel.STABLE
            every { downloadMirror } returns ""
        },
        updateCheckService = mockk(relaxed = true),
        apkDownloader = mockk(relaxed = true),
        apkVerifier = mockk(relaxed = true),
        providerDashboardRunner = mockk(relaxed = true),
        terminalSettingsRepository = mockk(relaxed = true) {
            every { settingsFlow } returns MutableStateFlow(TerminalSettings())
        },
        proxySettingsRepository = mockk(relaxed = true) {
            every { config } returns MutableStateFlow(ProxyConfig())
        },
        definitionExecutionContextFactory = definitionFactory
    )

    private fun publish(vararg entries: SkillEntry) {
        nextSnapshot = snapshot(*entries)
        readSnapshot = { nextSnapshot }
        viewModel.refreshSkills()
        settle()
        assertEquals(entries.map { it.skill.name }, viewModel.skills.value.map { it.name })
    }

    private fun snapshot(vararg entries: SkillEntry) =
        DefinitionSnapshot(currentSource, entries.toList(), emptySet())

    private fun entry(name: String, scope: SkillScope, requiredTools: List<String> = emptyList()) = SkillEntry(
        Skill(
            name = name,
            description = "fixture-description-$name",
            requiredTools = requiredTools,
            instructions = "fixture-instructions-$name"
        ),
        scope
    )

    private fun uiEntry(
        name: String,
        scope: SkillScope,
        disabled: Boolean = false,
        requiredTools: List<String> = emptyList()
    ) = SkillUiEntry(
        name = name,
        description = "fixture-description-$name",
        scope = scope,
        disabled = disabled,
        instructions = "fixture-instructions-$name",
        requiredTools = requiredTools
    )

    private fun assertNoGuestFreezeForExplicitSkillOperations() {
        coVerify(exactly = 0) { definitionFactory.freeze(any(), any(), any()) }
    }

    private fun settle() = dispatcher.scheduler.runCurrent()
}
