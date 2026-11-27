package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.plugin.PluginDefinitionReadOnlyException
import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与技能、MCP 两级配置一致）。
 * 启停状态由 [AgentDefinitionConfigRepository] 持有：被禁用的定义仍出现在设置页列表里，
 * 但不进主代理的可派发清单、也不能被 [find] 派发出去。
 *
 * CHROOT 的定义与启停配置从固定 App/项目配置目录读写，不要求可运行的实例或 Root 授权。
 * PRoot/SSH 仍经 [FileAccessProvider] 访问当前环境；所有来源按配置和工作区代次校验。
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val globalSource: GlobalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val pluginSource: PluginDefinitionSource,
    private val localDefinitions: LocalAgentDefinitionSource
) {
    /**
     * 定义内容版本：经本仓库的 save/delete/setDisabled 修改定义后递增。
     * 系统提示词里的可用清单按它做缓存失效——否则会话中途新建的子代理
     * 要等到下一个会话才出现在主代理的派发清单里。外部直接改文件不经过这里，
     * 这部分维持原有的「新会话才刷新」行为。
     */
    private val definitionsVersionField = AtomicLong()
    val definitionsVersion: Long get() = definitionsVersionField.get() + BuiltinAgentPublication.revision.value + pluginSource.revision

    fun sourceIdentity(): String =
        "${localDefinitions.sourceIdentity()}:${definitionsVersionField.get() + BuiltinAgentPublication.revision.value}:plugins:${pluginSource.revision}"

    fun checkSourceIdentity(expected: String) {
        if (sourceIdentity() != expected) throw AgentDefinitionSourceChanged()
    }

    private fun bumpVersion() {
        definitionsVersionField.incrementAndGet()
    }
    data class DefinitionSnapshot(
        val sourceIdentity: String,
        val entries: List<AgentDefinitionEntry>,
        val disabledNames: Set<String>
    )

    private fun <T> blockingRead(block: suspend () -> T): T =
        runBlocking(WorkspaceExecutionContext.blockingContext()) { withContext(Dispatchers.IO) { block() } }

    private fun withPlugins(host: List<AgentDefinitionEntry>): List<AgentDefinitionEntry> {
        val names = host.map { it.definition.name.lowercase() }.toSet()
        return (host + pluginSource.agents().filterNot { it.definition.name.lowercase() in names })
            .sortedBy { it.definition.name.lowercase() }
    }

    private fun legacyEntries(forceRefresh: Boolean): List<AgentDefinitionEntry> = withPlugins(
        mergeAll(globalSource.listDefinitions(forceRefresh), projectSource.listDefinitions(forceRefresh)))

    private suspend fun localEntries(capture: LocalAgentDefinitionSource.Capture): List<AgentDefinitionEntry> =
        withPlugins(mergeAll(localDefinitions.definitions(capture, AgentDefinitionScope.GLOBAL),
            localDefinitions.definitions(capture, AgentDefinitionScope.PROJECT))).also { localDefinitions.check(capture) }

    suspend fun readDefinitionSnapshot(executionFactory: WorkspaceExecutionContextFactory, forceRefresh: Boolean = false): DefinitionSnapshot {
        val source = sourceIdentity()
        val local = localDefinitions.capture()
        val snapshot = if (local != null) withContext(Dispatchers.IO) {
            DefinitionSnapshot(source, localEntries(local), localDefinitions.disabledNames(local))
                .also { localDefinitions.check(local) }
        } else {
            val execution = executionFactory.freeze()
            withContext(Dispatchers.IO + execution) {
                DefinitionSnapshot(source, legacyEntries(forceRefresh), configRepository.disabledNames())
                    .also { execution.checkCurrent() }
            }
        }
        currentCoroutineContext().ensureActive()
        checkSourceIdentity(source)
        return snapshot
    }

    /** 全部定义（含来源作用域），未过滤禁用，按名称排序。 */
    fun listAll(): List<AgentDefinitionEntry> = listAll(forceRefresh = false)

    fun listAll(forceRefresh: Boolean): List<AgentDefinitionEntry> {
        val source = sourceIdentity()
        return blockingRead {
            val local = localDefinitions.capture()
            (if (local != null) localEntries(local) else legacyEntries(forceRefresh))
                .also { checkSourceIdentity(source) }
        }
    }

    private fun definitionsIn(scope: AgentDefinitionScope): List<AgentDefinition> {
        val source = sourceIdentity()
        return when (scope) {
            AgentDefinitionScope.GLOBAL -> globalSource.listDefinitions(forceRefresh = true)
            AgentDefinitionScope.PROJECT -> projectSource.listDefinitions(forceRefresh = true)
            AgentDefinitionScope.PLUGIN -> throw PluginDefinitionReadOnlyException()
        }.also { checkSourceIdentity(source) }
    }

    /** 已启用的定义（注入主代理的可派发清单用）。 */
    fun listEnabled(): List<AgentDefinitionEntry> = listEnabled(forceRefresh = false)

    private fun listEnabled(forceRefresh: Boolean): List<AgentDefinitionEntry> {
        val source = sourceIdentity()
        return blockingRead {
            val local = localDefinitions.capture()
            val disabled = if (local != null) localDefinitions.disabledNames(local) else configRepository.disabledNames()
            val entries = if (local != null) localEntries(local) else legacyEntries(forceRefresh)
            entries.filterNot { it.definition.name.lowercase() in disabled }.also {
                if (local != null) localDefinitions.check(local)
                checkSourceIdentity(source)
            }
        }
    }

    /** 按名称查找可派发的定义（忽略大小写）；不存在或已被禁用时返回 null。 */
    fun find(name: String): AgentDefinition? =
        listEnabled(forceRefresh = true).firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /**
     * 按名称查找定义，包含已禁用的。已经派出去的子会话要用它还原自己的提示词与工具集——
     * 禁用只该拦住新的派发，不该让在跑的子代理中途换一套配置。
     */
    fun findIncludingDisabled(name: String): AgentDefinition? =
        listAll(forceRefresh = true).firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** 当前被禁用的子代理名集合（小写归一化）。批量判断取一次即可，避免逐个重读配置文件。 */
    fun disabledNames(): Set<String> = configRepository.disabledNames()

    /** 该子代理是否在任一作用域中被禁用。 */
    fun isDisabled(name: String): Boolean = name.lowercase() in configRepository.disabledNames()

    /** 在指定作用域启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        if (scope == AgentDefinitionScope.PLUGIN) throw PluginDefinitionReadOnlyException()
        configRepository.setDisabled(name, disabled, scope)
        bumpVersion()
    }

    /**
     * 写入定义文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     */
    fun save(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ): AgentSaveError? {
        if (scope == AgentDefinitionScope.PLUGIN) return AgentSaveError.READ_ONLY
        val name = form.name.trim()
        if (!isValidName(name)) return AgentSaveError.INVALID_NAME
        if (form.prompt.isBlank()) return AgentSaveError.EMPTY_PROMPT

        try {
            blockingRead {
                val local = localDefinitions.capture()
                if (local != null) true to localDefinitions.save(local, form, scope, originalName) else false to null
            }.let { (handled, result) ->
                if (handled) { if (result == null) bumpVersion(); return result }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "保存子代理定义失败: $name", e)
            return AgentSaveError.IO_FAILED
        }

        val entries = try { definitionsIn(scope) } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "读取子代理定义失败: $name", e)
            return AgentSaveError.IO_FAILED
        }
        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = entries.any { it.name.equals(name, ignoreCase = true) }
            if (taken) return AgentSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            entries.firstOrNull { it.name.equals(old, ignoreCase = true) }?.filePath
        }

        val provider = fileAccess
        val root = agentsRoot(scope)
        val text = AgentDefinitionParser.serialize(
            name = name,
            description = form.description,
            providerId = form.providerId,
            model = form.model,
            reasoningEffort = form.reasoningEffort,
            allowedTools = form.allowedTools,
            disallowedTools = form.disallowedTools,
            inject = form.inject,
            prompt = form.prompt
        )

        return try {
            // 名字未改时写回原文件，不能按 name 重拼文件名：内置 Explore 的文件叫 explore.md
            // 而 frontmatter 里写的是 Explore，重拼会在大小写敏感的文件系统上多出一份 Explore.md。
            val target = existingFile?.takeIf { overwritingSelf && provider.isFile(it) }
                ?: "${root.trimEnd('/')}/$name.md"
            provider.writeFile(target, text, overwrite = true)
            // 改名后清掉旧文件，否则会多出一个同内容的旧名子代理
            if (!overwritingSelf && existingFile != null && existingFile != target && provider.isFile(existingFile)) {
                runCatching { provider.delete(existingFile) }.onFailure {
                    if (it is CancellationException) throw it
                    FileLogger.w(TAG, "重命名后删除旧定义失败: $existingFile")
                }
            }
            bumpVersion()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "保存子代理定义失败: $name", e)
            AgentSaveError.IO_FAILED
        }
    }

    /** 删除指定作用域的定义文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        if (scope == AgentDefinitionScope.PLUGIN) return false
        val localResult = blockingRead {
            val local = localDefinitions.capture()
            if (local != null) localDefinitions.delete(local, name, scope) else null
        }
        if (localResult != null) {
            if (localResult) bumpVersion()
            return localResult
        }
        val entry = definitionsIn(scope).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        val filePath = entry.filePath ?: return false
        val provider = fileAccess
        if (!provider.isFile(filePath)) return false
        val deleted = try { provider.delete(filePath); true } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        if (deleted) bumpVersion()
        return deleted
    }

    /** 指定作用域的定义目录（容器路径）。 */
    fun agentsRoot(scope: AgentDefinitionScope): String = when (scope) {
        AgentDefinitionScope.GLOBAL -> globalSource.agentsRoot
        AgentDefinitionScope.PROJECT -> projectSource.agentsRoot
        AgentDefinitionScope.PLUGIN -> throw PluginDefinitionReadOnlyException()
    }

    companion object {
        private const val TAG = "AgentDefinitionRepository"

        /** 名称同时用作文件名，禁掉路径分隔符与保留字符；长度上限防止极端文件名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.definition.name.lowercase() }
        }
    }
}
