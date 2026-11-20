package com.aicode.feature.agent.presentation.runtime

import android.content.Context
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryPathResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryRuntimeBindings
import com.aicode.feature.agent.domain.memory.ProjectMemorySessionCoordinator
import com.aicode.feature.agent.domain.memory.ProjectProgressTerminalTracker
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AgentRunDependencies @Inject constructor(
    val agentWorkflow: AgentWorkflow,
    val toolRegistry: ToolRegistry,
    val agentMessageDao: AgentMessageDao,
    val chatSessionDao: ChatSessionDao,
    val llmCallRecordDao: LlmCallRecordDao,
    val modelCostCalculator: ModelCostCalculator,
    val aiProviderRepository: AIProviderRepository,
    val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    val toolPermissionManager: ToolPermissionManager,
    val askUserQuestionManager: AskUserQuestionManager,
    val planApprovalManager: PlanApprovalManager,
    val sessionUseCase: SessionUseCase,
    val messagePersistenceUseCase: MessagePersistenceUseCase,
    val terminalSessionProvider: TerminalSessionProvider,
    val terminalSessionManager: TerminalSessionManager,
    val slashCommandRegistry: SlashCommandRegistry,
    val checkpointManager: CheckpointManager,
    val checkpointDao: CheckpointDao,
    val agentSoundSettings: AgentSoundSettingsRepository,
    val generalSettingsRepository: GeneralSettingsRepository,
    val rootAccessSettingsRepository: RootAccessSettingsRepository,
    val agentLiveNotificationManager: AgentLiveNotificationManager,
    val approvalNotificationSettings: ApprovalNotificationSettingsRepository,
    val agentApprovalNotificationManager: AgentApprovalNotificationManager,
    val subAgentEventBus: SubAgentEventBus,
    val agentNotificationCenter: AgentNotificationCenter,
    val agentDefinitionRepository: AgentDefinitionRepository,
    val todoItemDao: TodoItemDao,
    val recoveryRepository: RecoveryRepository,
    val projectMemoryJournal: ProjectMemoryJournal,
    val projectMemoryIdentityResolver: ProjectMemoryIdentityResolver,
    val projectMemorySessionCoordinator: ProjectMemorySessionCoordinator,
    val projectMemoryRuntimeBindings: ProjectMemoryRuntimeBindings,
    val projectMemoryPathResolver: ProjectMemoryPathResolver,
    val projectProgressTerminalTracker: ProjectProgressTerminalTracker,
    @param:ApplicationContext val context: Context,
    val fileVersions: FileVersionRegistry,
    val executionModeHolder: ExecutionModeHolder,
    val containerSettingsRepository: ContainerSettingsRepository,
    val remoteSshConnection: RemoteSshConnection,
    val workspaceExecutionContextFactory: WorkspaceExecutionContextFactory
)

data class RewindResult(
    val fillPrompt: String? = null,
    val attachments: List<AgentAttachment> = emptyList(),
    val conflicts: List<String> = emptyList()
)
