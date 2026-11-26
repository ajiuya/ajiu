package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillDefinitionSourceChanged
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 斜杠命令注册表：聚合内置命令（[ActionCommandHandler]）与已启用技能，
 * 统一供 `/` 菜单展示与发送时解析。
 *
 * - 菜单：[commands] 为合并后的 [SlashCommand] 列表——内置命令优先，同名技能被跳过，按名排序。
 * - 解析：[resolve] 把 `/name args` 拆成命令名与参数，命中内置命令或技能；纯内存，无 IO。
 * - 刷新：[refresh] 重新扫描技能（文件 IO），调用方负责触发时机。
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    actionHandlers: Set<@JvmSuppressWildcards ActionCommandHandler>,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context,
    private val executionContextFactory: WorkspaceExecutionContextFactory
) {
    private val actions: List<ActionCommandHandler> = actionHandlers.sortedBy { it.name.lowercase() }
    private val actionsByName: Map<String, ActionCommandHandler> =
        actions.associateBy { it.name.lowercase() }

    /** 技能快照：由 [refresh] 在 IO 线程更新，[resolve] 只读。 */
    @Volatile
    private var skills: List<Skill> = emptyList()

    private val refreshLock = Any()
    private var refreshOwner = 0L
    private var skillsSource: String? = null

    enum class RefreshState { Idle, Loading, Ready, Unavailable, Failed }

    private val _refreshState = MutableStateFlow(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()
    private val _commands = MutableStateFlow(buildCommands())

    /** `/` 菜单展示的命令列表。 */
    val commands: StateFlow<List<SlashCommand>>
        get() {
            discardChangedSkills()
            return _commands.asStateFlow()
        }

    suspend fun refresh() {
        discardChangedSkills()
        val owner = synchronized(refreshLock) {
            _refreshState.value = RefreshState.Loading
            ++refreshOwner
        }
        try {
            val snapshot = skillRepository.readDefinitionSnapshot(executionContextFactory)
            currentCoroutineContext().ensureActive()
            synchronized(refreshLock) {
                if (owner != refreshOwner) return@synchronized
                skillRepository.checkSourceIdentity(snapshot.sourceIdentity)
                skills = snapshot.enabled.map { it.skill }
                skillsSource = snapshot.sourceIdentity
                _commands.value = buildCommands()
                _refreshState.value = RefreshState.Ready
            }
        } catch (_: SkillDefinitionSourceChanged) {
            currentCoroutineContext().ensureActive()
            synchronized(refreshLock) {
                if (owner == refreshOwner) clearSkills(RefreshState.Unavailable)
            }
        } catch (cancelled: CancellationException) {
            synchronized(refreshLock) {
                if (owner == refreshOwner) {
                    _refreshState.value = if (skillsSource == null) RefreshState.Idle else RefreshState.Ready
                }
            }
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(refreshLock) {
                if (owner == refreshOwner) clearSkills(RefreshState.Failed)
            }
        }
    }

    private fun discardChangedSkills() {
        synchronized(refreshLock) {
            val source = skillsSource ?: return
            val current = try {
                skillRepository.sourceIdentity()
            } catch (_: Exception) {
                clearSkills(RefreshState.Unavailable)
                return
            }
            if (source != current) clearSkills(RefreshState.Unavailable)
        }
    }

    private fun clearSkills(state: RefreshState) {
        skills = emptyList()
        skillsSource = null
        _commands.value = buildCommands()
        _refreshState.value = state
    }

    private fun buildCommands(): List<SlashCommand> = buildList {
        actions.forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = context.getString(it.descriptionRes),
                    kind = SlashCommandKind.ACTION,
                    acceptsArgs = it.acceptsArgs
                )
            )
        }
        skills.filterNot { it.name.lowercase() in actionsByName }
            .sortedBy { it.name.lowercase() }
            .forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = it.description,
                    kind = SlashCommandKind.SKILL,
                    acceptsArgs = true
                )
            )
        }
    }

    /**
     * 解析发送文本：仅当输入以 `/` 开头且命中内置命令或技能时返回非空。
     * 内置命令不接受参数时，带参数视为未命中（回落为普通消息）。
     */
    fun resolve(input: String): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()

        actionsByName[name.lowercase()]?.let { handler ->
            if (!handler.acceptsArgs && args.isNotEmpty()) return null
            return ResolvedCommand.Action(handler, args)
        }
        discardChangedSkills()
        return synchronized(refreshLock) {
            val source = skillsSource ?: return@synchronized null
            try {
                skillRepository.checkSourceIdentity(source)
            } catch (_: Exception) {
                clearSkills(RefreshState.Unavailable)
                return@synchronized null
            }
            val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@synchronized null
            ResolvedCommand.SkillCommand(skill, args)
        }
    }

    /** 解析结果：内置动作或技能。 */
    sealed interface ResolvedCommand {
        val name: String
        val args: String

        data class Action(val handler: ActionCommandHandler, override val args: String) : ResolvedCommand {
            override val name: String get() = handler.name
        }

        data class SkillCommand(val skill: Skill, override val args: String) : ResolvedCommand {
            override val name: String get() = skill.name
        }
    }

    companion object {
        /**
         * 构造技能本轮指令：把技能正文里的 `$ARGUMENTS` 替换为 [args]；
         * 无占位且 [args] 非空时追加一行，保证用户输入不丢失。
         */
        fun buildSkillPrompt(skill: Skill, args: String): String =
            substituteArguments(skill.instructions, args)
    }
}
