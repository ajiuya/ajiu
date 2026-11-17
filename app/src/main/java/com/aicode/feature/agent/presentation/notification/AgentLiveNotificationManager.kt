package com.aicode.feature.agent.presentation.notification

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.R
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.terminal.domain.KeepaliveNotificationState
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Singleton
class AgentLiveNotificationManager internal constructor(
    private val context: Context,
    private val settings: LiveNotificationSettingsRepository,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(@ApplicationContext context: Context, settings: LiveNotificationSettingsRepository) :
        this(context, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    data class RunningSession(
        val sessionId: String,
        val title: String,
        val statusLabel: String
    )

    private companion object {
        const val LIVE_LEASE_OWNER = "agent-live-notification"
    }

    private val renderLock = Any()
    private val policy = AgentLiveNotificationPolicy()
    private var enabled = false
    private var inForeground = false
    private var completionJob: Job? = null
    private var completionGeneration = 0L
    private var liveLeaseHeld = false

    init {
        scope.launch(Dispatchers.Main.immediate) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            synchronized(renderLock) {
                inForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    synchronized(renderLock) {
                        inForeground = true
                        renderLocked()
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    synchronized(renderLock) {
                        inForeground = false
                        renderLocked()
                    }
                }
            })
        }
        scope.launch {
            settings.enabledFlow.collect { value ->
                synchronized(renderLock) {
                    enabled = value
                    renderLocked()
                }
            }
        }
    }

    fun update(running: List<RunningSession>) {
        synchronized(renderLock) {
            val actions = allowedActions()
            val fallback = context.getString(R.string.keepalive_task_action_running)
            val sessions = running.map {
                AgentLiveNotificationState.Session(
                    sessionId = it.sessionId,
                    title = "",
                    statusLabel = AgentLiveNotificationContent.safeAction(it.statusLabel, actions, fallback)
                )
            }
            KeepaliveNotificationState.updateAgents(sessions)
            policy.update(sessions)
            renderLocked()
        }
    }

    fun dismiss() {
        synchronized(renderLock) {
            policy.dismiss()
            KeepaliveNotificationState.updateAgents(emptyList())
            clearLocked()
        }
    }

    fun hasLiveNotificationEnabled(): Boolean =
        AgentLiveNotificationState.state.value.active && TaskKeepaliveController.isProtected()

    fun markSessionStarted(sessionId: String) {
        synchronized(renderLock) {
            policy.markSessionStarted(sessionId)
            if (policy.snapshot != AgentLiveNotificationState.state.value) {
                cancelCompletionClearLocked()
                publishLocked(policy.snapshot)
            }
        }
    }

    fun markCompletionOutcome(sessionId: String, failed: Boolean) {
        synchronized(renderLock) {
            policy.markCompletionOutcome(sessionId, failed)
            if (policy.snapshot != AgentLiveNotificationState.state.value) publishLocked(policy.snapshot)
        }
    }

    private fun renderLocked() {
        val wasCompleted = policy.snapshot.completed
        val snapshot = policy.render(enabled, inForeground, SystemClock.elapsedRealtime())
        if (!snapshot.active) {
            clearLocked()
            return
        }
        if (!snapshot.completed) cancelCompletionClearLocked()
        publishLocked(snapshot)
        if (snapshot.completed && !wasCompleted) scheduleCompletionClearLocked()
    }

    private fun publishLocked(snapshot: AgentLiveNotificationState.Snapshot) {
        AgentLiveNotificationState.publish(snapshot)
        if (snapshot.active) {
            liveLeaseHeld = true
            TaskKeepaliveController.acquire(context, LIVE_LEASE_OWNER, cpu = false, network = false)
        } else {
            releaseLiveLease()
        }
    }

    private fun scheduleCompletionClearLocked() {
        cancelCompletionClearLocked()
        val generation = completionGeneration
        completionJob = scope.launch {
            delay(AgentLiveNotificationPolicy.COMPLETION_LINGER_MS)
            synchronized(renderLock) {
                if (generation == completionGeneration && policy.snapshot.completed) {
                    completionJob = null
                    clearLocked()
                }
            }
        }
    }

    private fun cancelCompletionClearLocked() {
        completionGeneration++
        completionJob?.cancel()
        completionJob = null
    }

    private fun clearLocked() {
        cancelCompletionClearLocked()
        policy.clear()
        AgentLiveNotificationState.clear()
        releaseLiveLease()
    }

    private fun releaseLiveLease() {
        if (!liveLeaseHeld) return
        liveLeaseHeld = false
        TaskKeepaliveController.release(context, LIVE_LEASE_OWNER)
    }

    private fun allowedActions(): Map<String, String> = listOf(
        R.string.chat_status_thinking to R.string.keepalive_task_action_thinking,
        R.string.chat_status_editing_file to R.string.keepalive_task_action_editing,
        R.string.chat_status_reading_file to R.string.keepalive_task_action_reading,
        R.string.chat_status_viewing_image to R.string.keepalive_task_action_image,
        R.string.chat_status_searching_web to R.string.keepalive_task_action_search,
        R.string.chat_status_browsing_web to R.string.keepalive_task_action_browser,
        R.string.chat_status_running_command to R.string.keepalive_task_action_command,
        R.string.chat_status_asking_user to R.string.keepalive_task_action_question,
        R.string.chat_status_generating_image to R.string.keepalive_task_action_generate,
        R.string.chat_status_updating_todo to R.string.keepalive_task_action_todo,
        R.string.chat_status_starting_subagent to R.string.keepalive_task_action_subagent,
        R.string.chat_status_reading_memory to R.string.keepalive_task_action_memory,
        R.string.chat_status_calling_tool to R.string.keepalive_task_action_tool,
        R.string.chat_compressing_context to R.string.keepalive_task_action_context,
        R.string.notification_approval_needed to R.string.keepalive_task_action_approval,
        R.string.plan_approval_waiting to R.string.keepalive_task_action_approval
    ).associate { (source, display) -> context.getString(source) to context.getString(display) }
}
