package com.aicode.feature.agent.presentation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.command.ActionCommandHandler
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryPathResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryRuntimeBindings
import com.aicode.feature.agent.domain.memory.ProjectMemorySessionCoordinator
import com.aicode.feature.agent.domain.memory.ProjectProgressTerminalTracker
import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequest
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryRun
import com.aicode.feature.agent.domain.recovery.RecoveryStatus
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import com.aicode.feature.agent.domain.subagent.SubAgentInteractionJournal
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

internal class SubAgentViewModelQueueFixture(
    context: Context,
    recovery: RecoveryRepository? = null
) : AutoCloseable {
    val sessionRows = mutableMapOf(
        PARENT to ChatSessionEntity(PARENT, "Parent", 1, 1, workspacePath = "/work", providerId = "provider", model = "model"),
        CHILD to ChatSessionEntity(CHILD, "Child", 1, 1, workspacePath = "/work", providerId = "provider", model = "model", parentId = PARENT)
    )
    val sessions = mockk<SessionUseCase>(relaxed = true)
    val chatSessions = mockk<ChatSessionDao>(relaxed = true)
    val generalSettings = mockk<GeneralSettingsRepository>(relaxed = true)
    val recoveryRepository = recovery ?: mockk<RecoveryRepository>(relaxed = true)
    val projectJournal = mockk<ProjectMemoryJournal>(relaxed = true)
    val projectIdentityResolver = mockk<ProjectMemoryIdentityResolver>(relaxed = true)
    val projectCoordinator = mockk<ProjectMemorySessionCoordinator>(relaxed = true)
    val projectBindings = ProjectMemoryRuntimeBindings()
    val projectPaths = ProjectMemoryPathResolver()
    val projectTracker = ProjectProgressTerminalTracker()
    val projectIdentity = ProjectMemoryIdentity("local_proot", projectRoot = "/work", profileId = "test")
    val projectCapture = ProjectMemoryCapture(projectIdentity) {}
    val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    val workflow = mockk<AgentWorkflow>(relaxed = true)
    val tools = mockk<ToolRegistry>(relaxed = true)
    val agentDefinitions = mockk<AgentDefinitionRepository>(relaxed = true)
    val messages = mockk<AgentMessageDao>(relaxed = true)
    val commands = mockk<SlashCommandRegistry>(relaxed = true)
    val journal = mockk<SubAgentInteractionJournal>(relaxed = true)
    val events = SubAgentEventBus(journal)
    val notifications = AgentNotificationCenter()
    val plans = PlanApprovalManager()
    private val store = ViewModelStore()
    val viewModel: AIAgentViewModel

    init {
        coEvery { journal.isCurrent(any()) } returns true
        coEvery { journal.record(any(), any()) } coAnswers {
            val event = firstArg<SubAgentEvent>()
            val finish = secondArg<(suspend () -> Boolean)?>()
            if (finish != null && !finish()) throw kotlinx.coroutines.CancellationException("Run changed")
            SubAgentInteractionEntity(event.eventId, event.parentSessionId, event.subSessionId, event.type.name,
                event.detail, event.timestamp, sourceMessageId = event.sourceMessageId,
                instructionId = event.instructionId, generation = event.generation,
                parentGeneration = event.parentGeneration, runId = event.runId)
        }
        val container = mockk<LinuxContainerEngine>(relaxed = true)
        val containerSettings = mockk<ContainerSettingsRepository>(relaxed = true)
        val osDetector = mockk<ContainerOsDetector>(relaxed = true)
        val permissions = mockk<ToolPermissionManager>(relaxed = true)
        val rootSettings = mockk<RootAccessSettingsRepository>(relaxed = true)
        val liveSettings = mockk<LiveNotificationSettingsRepository>(relaxed = true)
        val approvalSettings = mockk<ApprovalNotificationSettingsRepository>(relaxed = true)
        val terminals = mockk<TerminalSessionProvider>(relaxed = true)
        val previewFileAccess = mockk<ToolPreviewFileAccessAdapter>(relaxed = true)
        every { previewFileAccess.readers } returns flowOf(null)
        every { messages.getMessagesBySessionPaged(any(), any()) } returns flowOf(emptyList())
        every { chatSessions.getByIdFlow(any()) } answers { flowOf(sessionRows[firstArg<String>()]) }
        every { chatSessions.getRootSessionsByWorkspace(any()) } answers {
            val workspace = firstArg<String>()
            flowOf(sessionRows.values.filter { it.workspacePath == workspace && it.parentId == null })
        }
        every { chatSessions.getAllSessionsByWorkspace(any()) } answers {
            val workspace = firstArg<String>()
            flowOf(sessionRows.values.filter { it.workspacePath == workspace })
        }
        every { generalSettings.speedTierFlow } returns flowOf(SpeedTier.STANDARD)
        coEvery { generalSettings.startupSessionMode() } returns StartupSessionMode.RECENT_SESSION
        every { projectIdentityResolver.environmentChanges } returns emptyFlow()
        coEvery { projectIdentityResolver.capture(any(), any(), any()) } returns projectCapture
        every { projectJournal.sessionIdentity(any()) } returns null
        every { projectJournal.recoverProgress(any()) } returns 0
        every { projectJournal.listProjects(any()) } returns emptyList()
        every { projectCoordinator.resolve(any(), any(), any(), any(), any(), any(), any()) } returns
            ProjectMemorySessionCoordinator.Selection(null, null, true)
        if (recovery == null) {
            val requests = mutableMapOf<String, RecoveryRequest>()
            val payloads = mutableMapOf<String, RecoveryRequestPayload>()
            val runs = mutableMapOf<String, RecoveryRun>()
            every { recoveryRepository.observePending(any()) } returns flowOf(emptyList())
            coEvery { recoveryRepository.initialize() } returns Unit
            coEvery { recoveryRepository.enqueue(any(), any(), any(), any(), any()) } answers {
                val sessionId = firstArg<String>()
                val payload = secondArg<RecoveryRequestPayload>()
                val environment = thirdArg<String>()
                val requestId = arg<String>(3)
                val inputId = arg<String>(4)
                payloads[requestId] = payload
                requests.getOrPut(requestId) {
                    RecoveryRequest(requestId, sessionId, 0, environment, inputId, requests.size.toLong(),
                        RecoveryStatus.QUEUED, null, 0, 0, 0, 1, 1)
                }
            }
            coEvery { recoveryRepository.claim(any(), any(), any()) } answers {
                val request = requests.getValue(firstArg<String>())
                val runId = "run-${request.id}"
                runs.getOrPut(runId) {
                    RecoveryRun(runId, request.id, request.sessionId, request.generation, "fixture-owner",
                        RecoveryStatus.RUNNING, 0, 0, 0, 1, 1, null)
                }
            }
            coEvery { recoveryRepository.request(any()) } answers { requests[firstArg<String>()] }
            coEvery { recoveryRepository.payload(any()) } answers { payloads[firstArg<String>()] }
            coEvery { recoveryRepository.run(any()) } answers { runs[firstArg<String>()] }
            coEvery { recoveryRepository.runFor(any()) } answers {
                val requestId = firstArg<String>()
                runs.values.firstOrNull { it.requestId == requestId }
            }
            coEvery { recoveryRepository.isCurrent(any()) } returns true
            coEvery { recoveryRepository.canDispatch(any()) } returns true
            coEvery { recoveryRepository.beginStep(any()) } returns RecoveryStep(1, 0)
            coEvery { recoveryRepository.restartStream(any(), any()) } answers { secondArg<RecoveryStep>() }
            coEvery { recoveryRepository.saveDraft(any(), any(), any(), any()) } returns true
            coEvery { recoveryRepository.finish(any(), any(), any()) } returns true
            coEvery { recoveryRepository.clearExplicitHold(any()) } returns true
            coEvery { recoveryRepository.holdSession(any(), any()) } returns true
            coEvery { recoveryRepository.updateQueued(any(), any()) } returns true
            coEvery { recoveryRepository.reorderQueued(any(), any()) } returns true
        }
        every { container.initProgress } returns MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
        every { containerSettings.activeProfileFlow } returns emptyFlow()
        every { osDetector.osMap } returns MutableStateFlow(emptyMap())
        every { permissions.pendingRequest } returns MutableStateFlow(null)
        every { permissions.awaitingSessionIds } returns MutableStateFlow(emptySet())
        every { permissions.pendingForSession(any()) } returns null
        every { rootSettings.state } returns MutableStateFlow(RootAccessState())
        every { liveSettings.enabledFlow } returns flowOf(false)
        every { approvalSettings.enabledFlow } returns flowOf(false)
        every { terminals.tabFinishedEvents } returns MutableSharedFlow()
        every { commands.commands } returns MutableStateFlow(emptyList())
        every { commands.resolve(any()) } returns null
        every { tools.getAvailableTools() } returns emptyList()
        every { workflow.executeEvents(any(), any(), any()) } returns emptyFlow()
        coEvery { sessions.getSessionById(any()) } answers { sessionRows[firstArg<String>()] }
        coEvery { sessions.getMostRecentSessionOfWorkspace(any()) } answers {
            val workspace = firstArg<String>()
            sessionRows.values.firstOrNull { it.workspacePath == workspace && it.parentId == null }
        }
        coEvery { sessions.isSessionEmpty(any()) } returns true
        coEvery { sessions.deleteSession(any()) } answers {
            val id = firstArg<String>()
            sessionRows.remove(id)
            listOf(id)
        }
        coEvery { persistence.buildHistory(any(), any()) } returns emptyList()
        coEvery { workflow.generateTitle(any(), any()) } returns null

        viewModel = AIAgentViewModel(
            agentWorkflow = workflow,
            toolRegistry = tools,
            agentMessageDao = messages,
            chatSessionDao = chatSessions,
            llmCallRecordDao = mockk(relaxed = true),
            modelCostCalculator = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            defaultModelSettingsRepository = mockk(relaxed = true),
            modelReasoningEffortRepository = mockk(relaxed = true),
            toolPermissionManager = permissions,
            askUserQuestionManager = AskUserQuestionManager(),
            planApprovalManager = plans,
            containerEngine = container,
            sessionUseCase = sessions,
            messagePersistenceUseCase = persistence,
            terminalSessionManager = mockk(relaxed = true),
            terminalSessionProvider = terminals,
            slashCommandRegistry = commands,
            fileMentionRepository = mockk(relaxed = true),
            checkpointManager = mockk(relaxed = true),
            checkpointDao = mockk(relaxed = true),
            backupManager = mockk(relaxed = true),
            mcpManager = mockk(relaxed = true),
            agentSoundSettings = mockk(relaxed = true),
            containerSettingsRepository = containerSettings,
            containerOsDetector = osDetector,
            generalSettingsRepository = generalSettings,
            rootAccessSettingsRepository = rootSettings,
            liveNotificationSettings = liveSettings,
            agentLiveNotificationManager = mockk(relaxed = true),
            approvalNotificationSettings = approvalSettings,
            agentApprovalNotificationManager = mockk(relaxed = true),
            subAgentEventBus = events,
            subAgentInteractionReader = mockk(relaxed = true),
            subAgentInstructionGateway = SubAgentInstructionGateway(sessions, events),
            agentNotificationCenter = notifications,
            agentDefinitionRepository = agentDefinitions,
            todoItemDao = mockk(relaxed = true),
            fileAccess = mockk(relaxed = true),
            browserManager = mockk(relaxed = true),
            previewPathMapper = mockk(relaxed = true),
            toolPreviewFileAccessAdapter = previewFileAccess,
            fileChangeHub = mockk(relaxed = true),
            recoveryRepository = recoveryRepository,
            projectMemoryJournal = projectJournal,
            projectMemoryIdentityResolver = projectIdentityResolver,
            projectMemorySessionCoordinator = projectCoordinator,
            projectMemoryRuntimeBindings = projectBindings,
            projectMemoryPathResolver = projectPaths,
            projectProgressTerminalTracker = projectTracker,
            context = context
        )
        store.put("queue-test", viewModel)
    }

    fun installCommand(input: String): ActionCommandHandler {
        val handler = mockk<ActionCommandHandler>(relaxed = true)
        every { commands.resolve(input) } returns SlashCommandRegistry.ResolvedCommand.Action(handler, "")
        return handler
    }

    val jobs: MutableMap<String, Job> get() = state("sessionJobs")
    val stopping: MutableSet<String> get() = state("stoppingSessions")
    val liveNotifications: com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
        get() = state("agentLiveNotificationManager")
    val agentStates: MutableStateFlow<Map<String, AgentUIState>> get() = state("_agentStates")
    val runningTools: MutableStateFlow<Map<String, Map<String, RunningToolOutput>>> get() = state("_runningTools")

    // 只访问测试安排所需的运行时状态，不替代任何队列或停止逻辑。
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String): T = AIAgentViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(viewModel) as T
    }

    override fun close() {
        store.clear()
        jobs.values.toList().forEach { it.cancel() }
    }

    companion object {
        const val PARENT = "parent"
        const val CHILD = "child"
    }
}
