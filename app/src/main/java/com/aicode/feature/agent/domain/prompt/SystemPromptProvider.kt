package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 多数 Source 维护内容缓存，避免重复读取与格式化；静态基线片段除外，每次读盘以保证编辑即时生效。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`，可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，行为提示词只由自定义数字片段组成。
 * 可用子代理名称与派发参数仍作为工具元数据提供；其它动态内容通过 `{{AICODE_*}}` 按需取回。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private val restoredContentRevision = AtomicLong()

    fun invalidateAfterBackupRestore() {
        restoredContentRevision.incrementAndGet()
    }

    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // 每次都重新读盘：未编辑时字符串一致，KV Cache 照常命中；编辑后立即生效，无需重启。
        // 技能/记忆等运行期内容由末尾的 99-runtime-context.md 片段用 {{AICODE_*}} 变量承载。
        override fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val source = try { skillRepository.sourceIdentity() } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot, sourceIdentity = source,
                restoredContentVersion = restoredContentRevision.get())
            val cached = cachedByKey[key]
            if (cached != null) {
                skillRepository.checkSourceIdentity(source)
                return cached.ifEmpty { null }
            }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
            skillRepository.checkSourceIdentity(source)
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                trimIfNeeded()
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" } }" }
            val content = list
            skillRepository.checkSourceIdentity(source)
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        // 每次读盘：与静态基线同理，未编辑时内容一致不影响 KV 缓存，编辑 subagent-base.md 后立即生效。
        override fun build(ctx: AgentContext): String =
            resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
    }

    /**
     * 可用子代理清单（仅注入主代理）：让 AI 知道有哪些自定义 agent 可派发。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     * 缓存 key 带定义版本：设置页里新建/编辑/启停子代理后，下一轮即可见，不必开新会话。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            if (ctx.isSubagent) return null
            val source = try { agentDefinitionRepository.sourceIdentity() } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
            val key = SourceCacheKey(
                ctx.sessionId,
                ctx.projectRoot,
                agentDefinitionRepository.definitionsVersion,
                source
            )
            val cached = cachedByKey[key]
            if (cached != null) {
                agentDefinitionRepository.checkSourceIdentity(source)
                return cached.ifEmpty { null }
            }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            agentDefinitionRepository.checkSourceIdentity(source)
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                trimIfNeeded()
                return null
            }

            // 每行除「名称: 用途」外再标注读/写/命令能力，避免把任务派给缺少所需工具的子代理。
            val list = entries.joinToString("\n") { entry ->
                val definition = entry.definition
                "- ${definition.name}: ${definition.description.ifBlank { "（无描述）" }}" +
                    "｜${definition.capabilitySummary()}"
            }
            val content = list
            agentDefinitionRepository.checkSourceIdentity(source)
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource {
        @Volatile private var cached: ProjectRules? = null
        private var lastSignature: String = ""

        /**
         * 同时读取全局规则（`~/.aicode`）与工作区规则（项目根目录）：各自 `AGENTS.md` 优先、`CLAUDE.md` 兜底。
         * 任一来源为空则该项为 null；两处文件签名（路径 + mtime）未变时复用缓存。
         */
        fun build(ctx: AgentContext): ProjectRules? {
            val global = resolveRuleFile(containerInstaller.aicodeDir)
            val project = ctx.projectRoot.takeIf { it.isNotBlank() }?.let { resolveRuleFile(File(it)) }
            if (global == null && project == null) return null

            val signature = buildString {
                append(global?.first?.absolutePath).append(':').append(global?.first?.lastModified() ?: 0)
                append('|')
                append(project?.first?.absolutePath).append(':').append(project?.first?.lastModified() ?: 0)
            }
            if (signature == lastSignature && cached != null) return cached

            val rules = ProjectRules(
                global = global?.first?.let { readRule(it) },
                project = project?.first?.let { readRule(it) }
            )
            cached = rules
            lastSignature = signature
            return rules
        }

        private fun resolveRuleFile(dir: File): Pair<File, String>? {
            val agents = File(dir, AGENTS_FILE)
            if (agents.isFile && agents.canRead()) return agents to AGENTS_FILE
            val claude = File(dir, CLAUDE_FILE)
            if (claude.isFile && claude.canRead()) return claude to CLAUDE_FILE
            return null
        }

        private fun readRule(file: File): String? {
            val text = try { file.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            return if (text.length > MAX_AGENTS_CHARS) {
                (text.take(MAX_AGENTS_CHARS) + "\n…（规则过长，已截断）").trim()
            } else {
                text.trim()
            }
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            if (ctx.projectRoot.isNotBlank()) "~/workspace" else "（未选择工作区）"
    }

    private class EnvironmentCapture(val content: String, val checkCurrent: () -> Unit)

    /** 每次 build 只读一次配置元数据；描述运行时不需要创建实例、冻结执行或申请 Root。 */
    private inner class EnvironmentSource {
        fun capture(): EnvironmentCapture {
            val execution = WorkspaceExecutionContext.current()
            if (execution != null) {
                execution.checkCurrent()
                val content = when (execution.mode) {
                    ExecutionMode.REMOTE_SSH -> "远程 SSH 服务器"
                    ExecutionMode.LOCAL_PROOT -> describeLocal(
                        execution.localRuntimeCapture?.profile ?: execution.localMapping?.profile
                            ?: execution.profileSnapshot?.let(::selectedProfile)
                    )
                }
                return EnvironmentCapture(content, execution::checkCurrent).also { it.checkCurrent() }
            }

            // LOCAL_PROOT 是历史上的本地通道标识，具体运行时必须来自同一份 profile 快照。
            val modeRevision = executionModeHolder.modeSelectionRevision
            val mode = executionModeHolder.currentMode()
            fun checkMode() {
                if (executionModeHolder.modeSelectionRevision != modeRevision || executionModeHolder.currentMode() != mode) {
                    throw CancellationException("Prompt execution mode changed")
                }
            }
            checkMode()
            if (mode == ExecutionMode.REMOTE_SSH) {
                return EnvironmentCapture("远程 SSH 服务器", ::checkMode)
            }

            val selectionRevision = containerSettingsRepository.activeProfileSelectionRevision
            val configurationRevision = containerSettingsRepository.profileConfigurationRevision
            val snapshot = try {
                runBlocking(Dispatchers.IO) { containerSettingsRepository.captureProfileSnapshot() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
            fun checkSource() {
                checkMode()
                if (snapshot != null) {
                    containerSettingsRepository.checkProfileSnapshot(snapshot)
                } else if (containerSettingsRepository.activeProfileSelectionRevision != selectionRevision ||
                    containerSettingsRepository.profileConfigurationRevision != configurationRevision) {
                    throw CancellationException("Prompt container configuration changed")
                }
                checkMode()
            }
            checkSource()
            val content = if (snapshot == null) {
                "本地容器（运行时未知：配置不可读或尚未加载）"
            } else {
                describeLocal(selectedProfile(snapshot))
            }
            return EnvironmentCapture(content, ::checkSource).also { it.checkCurrent() }
        }

        // 不使用 snapshot.activeProfile 的首项兜底，以免把缺失的自选 profile 冒充为 PRoot。
        private fun selectedProfile(snapshot: ContainerProfileSnapshot): ContainerProfile? =
            snapshot.profiles.firstOrNull { it.id == snapshot.selectedProfileId }
                ?: ContainerProfile.BUILTIN_ALPINE.takeIf { snapshot.selectedProfileId == ContainerProfile.BUILTIN_ID }

        private fun describeLocal(profile: ContainerProfile?): String = when {
            profile == null -> "本地容器（运行时未知：所选配置缺失或尚未加载）"
            profile.mode != ExecutionMode.LOCAL_PROOT -> "本地容器（运行时未知：所选配置与本地执行模式不一致）"
            profile.isChroot -> {
                val instance = if (profile.chrootInstance == null) "实例未创建，尚不可运行" else "配置已记录实例，健康状态未核验"
                "本地容器（CHROOT）；$instance。运行时语义：容器内为真实 guest UID 0，rootfs 可写；" +
                    "不等于宿主执行或 Root 沙箱，也不代表系统 su 已授权。"
            }
            else -> "本地容器（PRoot）；guest 显示 root 不代表宿主 Root；运行状态未核验。"
        }
    }

    /** 记忆清单拆分为全局/项目两组纯列表，供 `{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` 变量使用。 */
    private data class MemoryLists(val global: String?, val project: String?)

    /** 项目规则正文拆分为全局（`~/.aicode`）与工作区两组，供 `{{AICODE_PROJECT_RULES_GLOBAL}}` / `{{AICODE_PROJECT_RULES_PROJECT}}` 变量使用。 */
    private data class ProjectRules(val global: String?, val project: String?)

    private inner class MemoryListSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空组用 null 字段表示。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, MemoryLists>()

        fun build(ctx: AgentContext): MemoryLists {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot, restoredContentVersion = restoredContentRevision.get())
            cachedByKey[key]?.let { return it }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) {
                return MemoryLists(null, null)
            }
            val global = memories.filter { it.scope == MemoryScope.GLOBAL }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val project = memories.filter { it.scope == MemoryScope.PROJECT }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val result = MemoryLists(global, project)
            cachedByKey[key] = result
            trimIfNeeded()
            return result
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /**
     * 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。
     *
     * [mode] 只有内容依赖模式的来源才传（子代理清单的能力摘要）；
     * [definitionVersion] 只有子代理清单传（定义变更后失效缓存），其余来源留默认值以免白白碎片化缓存。
     */
    private data class SourceCacheKey(
        val sessionId: String?,
        val projectRoot: String,
        val definitionVersion: Long = 0,
        val sourceIdentity: String = "",
        val restoredContentVersion: Long = 0
    )

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()

    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val environmentSource = EnvironmentSource()

    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun build(agentContext: AgentContext): String {
        val environment = environmentSource.capture()
        val definition = agentContext.agentDefinition
        val content = when {
            definition != null -> buildForSubAgent(definition, agentContext, environment.content)
            PromptFragmentResolver.isBuiltinDisabled(customDir) -> buildCustomOnly(agentContext, environment.content)
            else -> buildForMainAgent(agentContext, environment.content)
        }
        environment.checkCurrent()
        return content
    }

    private fun buildForMainAgent(agentContext: AgentContext, environmentContent: String): String {
        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        val workspaceContent = workspaceSource.build(agentContext)

        // 2. 变量就地展开：`{{AICODE_*}}` 替换为纯数据；未写变量的片段原样保留。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memories.global,
            memories.project,
            subAgentsContent,
            projectRules?.global,
            projectRules?.project,
            workspaceContent,
            environmentContent,
            currentDate(),
            goal = renderGoalBlock(agentContext)
        )

        // 4. 压缩空清单展开留下的多余空行；稳定基线在前、动态内容集中在末尾片段，均利于 KV Cache。
        return collapseBlankLines(staticContent)
    }

    /**
     * 禁用内置行为片段不移除 task 选择命名代理所需的目录；自定义片段已有清单变量时不重复列表。
     * 其它来源仍只通过自定义片段的变量展示。
     */
    private fun buildCustomOnly(ctx: AgentContext, environmentContent: String): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，仅保留可用子代理元数据"
            )
        }
        val subAgents = subAgentListSource.build(ctx)
        val memories = if (MEMORY_GLOBAL_VAR in content || MEMORY_PROJECT_VAR in content) memoryListSource.build(ctx)
            else MemoryLists(null, null)
        val projectRules = if (PROJECT_RULES_GLOBAL_VAR in content || PROJECT_RULES_PROJECT_VAR in content || PROJECT_RULES_VAR in content)
            projectRuleSource.build(ctx) else null
        val rendered = renderVariables(
            content,
            if (SKILLS_VAR in content) activeSkillsSource.build(ctx) else null,
            memories.global,
            memories.project,
            subAgents,
            projectRules?.global,
            projectRules?.project,
            workspaceSource.build(ctx),
            environmentContent,
            currentDate(),
            goal = if (GOAL_VAR in content) renderGoalBlock(ctx) else null
        )
        if (subAgents.isNullOrBlank()) return rendered
        val catalog = resolvePrompt(SUBAGENT_CATALOG_FILE)
            .replace(SUBAGENTS_VAR, if (SUBAGENTS_VAR in content) "" else subAgents)
        return collapseBlankLines(listOf(rendered, catalog).filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理清单（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext,
        environmentContent: String
    ): String = buildString {
        // 已选基线只补环境变量展开，保留其它变量和 InjectPart 的既有语义。
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext).replace(ENVIRONMENT_VAR, environmentContent))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext).replace(ENVIRONMENT_VAR, environmentContent))
            append("\n\n")
        }

        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        append(
            renderVariables(
                definition.prompt,
                activeSkillsSource.build(agentContext),
                memories.global,
                memories.project,
                // 可用子代理清单不注入子代理（无嵌套派发），此处恒为空，防止定义正文
                // 里写 {{AICODE_SUBAGENTS}} 把清单漏给不该看到它的子代理
                null,
                projectRules?.global,
                projectRules?.project,
                workspaceSource.build(agentContext),
                environmentContent,
                currentDate()
            )
        )

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            listOfNotNull(memories.global, memories.project).takeIf { it.isNotEmpty() }?.let { groups ->
                append("\n\n")
                append(groups.joinToString("\n\n"))
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            val rules = projectRuleSource.build(agentContext)
            listOfNotNull(rules?.global, rules?.project).takeIf { it.isNotEmpty() }?.let { parts ->
                append("\n\n")
                append(parts.joinToString("\n\n"))
            }
        }

        append("\n\n")
        append(
            renderVariables(
                resolvePrompt(SUBAGENT_CONTEXT_FILE),
                null,
                null,
                null,
                null,
                null,
                null,
                workspaceSource.build(agentContext),
                environmentContent,
                currentDate(),
                definition.name
            )
        )
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memoryGlobal: String?,
        memoryProject: String?,
        subAgents: String?,
        projectRulesGlobal: String?,
        projectRulesProject: String?,
        workspace: String,
        environment: String,
        date: String,
        subAgentName: String = "",
        goal: String? = null
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_GLOBAL_VAR, memoryGlobal.orEmpty())
        out = out.replace(MEMORY_PROJECT_VAR, memoryProject.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_GLOBAL_VAR, projectRulesGlobal.orEmpty())
        out = out.replace(PROJECT_RULES_PROJECT_VAR, projectRulesProject.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRulesProject.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(ENVIRONMENT_VAR, environment)
        out = out.replace(DATE_VAR, date)
        out = out.replace(SUBAGENT_NAME_VAR, subAgentName)
        out = out.replace(GOAL_VAR, goal.orEmpty())
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** 会话目标注入块：仅当会话处于 goal 自主循环（ACTIVE）时非空；文本取自 prompts/agent/goal.md，用户可覆盖。 */
    private fun renderGoalBlock(ctx: AgentContext): String? {
        if (ctx.goalStatus != GoalStatus.ACTIVE) return null
        val goal = ctx.goal?.takeIf { it.isNotBlank() } ?: return null
        return resolvePrompt(GOAL_FILE).replace(GOAL_TEXT_PLACEHOLDER, goal).trim()
    }

    /** 把连续 3 个以上换行压成 2 个：变量展开后空清单会留下额外空行。 */
    private fun collapseBlankLines(text: String): String =
        text.trim().replace(Regex("\\n{3,}"), "\n\n")

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)?.let { number ->
            val fragment = promptFragmentCatalog.fragment(number, null)
            return fragment?.takeIf { it.enabled }?.content.orEmpty()
        }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val SUBAGENT_CONTEXT_FILE = "agent/subagent-context.md"
        const val SUBAGENT_CATALOG_FILE = "agent/subagent-catalog.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_GLOBAL_VAR = "{{AICODE_MEMORY_GLOBAL}}"
        const val MEMORY_PROJECT_VAR = "{{AICODE_MEMORY_PROJECT}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val PROJECT_RULES_GLOBAL_VAR = "{{AICODE_PROJECT_RULES_GLOBAL}}"
        const val PROJECT_RULES_PROJECT_VAR = "{{AICODE_PROJECT_RULES_PROJECT}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val ENVIRONMENT_VAR = "{{AICODE_ENVIRONMENT}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
        const val GOAL_VAR = "{{AICODE_GOAL}}"
        /** goal 自主循环的注入块模板（prompts/agent/goal.md）；占位符换成目标文本。 */
        const val GOAL_FILE = "agent/goal.md"
        const val GOAL_TEXT_PLACEHOLDER = "{{GOAL_TEXT}}"
        const val SUBAGENT_NAME_VAR = "{{AICODE_SUBAGENT_NAME}}"
    }
}