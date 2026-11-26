package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.plugin.PluginDefinitionReadOnlyException
import com.aicode.feature.agent.domain.plugin.PluginDefinitionSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 仓库，聚合各 [SkillSource]（全局目录 + 项目目录）提供的技能，
 * 并按 [SkillConfigRepository] 的禁用名单过滤注入清单。
 *
 * CHROOT 定义从固定宿主配置读取；其余定义与所有技能文件修改仍经 [FileAccessProvider]
 * 跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 */
@Singleton
class SkillRepository @Inject constructor(
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val pluginSource: PluginDefinitionSource,
    private val localDefinitions: LocalSkillDefinitionSource
) {
    private val version = AtomicLong()

    fun invalidateAfterBackupRestore() {
        version.incrementAndGet()
    }

    data class DefinitionSnapshot(
        val sourceIdentity: String,
        val entries: List<SkillEntry>,
        val disabledNames: Set<String>
    ) {
        val enabled: List<SkillEntry> get() = filterDisabled(entries, disabledNames)
    }

    fun sourceIdentity(): String = "${localDefinitions.sourceIdentity()}:${version.get()}:plugins:${pluginSource.revision}"

    fun checkSourceIdentity(expected: String) {
        if (sourceIdentity() != expected) throw SkillDefinitionSourceChanged()
    }

    suspend fun readDefinitionSnapshot(
        executionFactory: WorkspaceExecutionContextFactory,
        forceRefresh: Boolean = false
    ): DefinitionSnapshot {
        val identity = sourceIdentity()
        val snapshot = try {
            val local = localDefinitions.capture()
            if (local != null) withContext(Dispatchers.IO) {
                val entries = withPlugins(mergeAll(localDefinitions.skills(local, SkillScope.GLOBAL),
                    localDefinitions.skills(local, SkillScope.PROJECT)))
                val disabled = localDefinitions.disabledNames(local)
                localDefinitions.check(local)
                DefinitionSnapshot(identity, entries, disabled)
            } else {
                val execution = executionFactory.freeze()
                withContext(Dispatchers.IO + execution) {
                    legacyRead {
                        DefinitionSnapshot(identity, legacyEntries(forceRefresh), skillConfigRepository.disabledNames())
                    }.also { execution.checkCurrent() }
                }
            }
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            checkSourceIdentity(identity)
            throw failure
        }
        currentCoroutineContext().ensureActive()
        checkSourceIdentity(identity)
        return snapshot
    }

    private fun <T> blockingRead(block: suspend () -> T): T =
        runBlocking(WorkspaceExecutionContext.blockingContext()) { withContext(Dispatchers.IO) { block() } }

    private fun <T> legacyRead(block: () -> T): T {
        val metadata = sourceIdentity()
        val identity = fileAccess.agentConfigSourceIdentity()
        return try {
            block().also {
                if (fileAccess.agentConfigSourceIdentity() != identity) throw SkillDefinitionSourceChanged()
            }
        } catch (failure: CancellationException) {
            WorkspaceExecutionContext.blockingContext()[Job]?.ensureActive()
            checkSourceIdentity(metadata)
            if (fileAccess.agentConfigSourceIdentity() != identity) throw SkillDefinitionSourceChanged()
            throw failure
        }
    }

    private fun withPlugins(host: List<SkillEntry>): List<SkillEntry> {
        val hostNames = host.map { it.skill.name.lowercase() }.toSet()
        return (host + pluginSource.skills().filterNot { it.skill.name.lowercase() in hostNames })
            .sortedBy { it.skill.name.lowercase() }
    }

    private fun legacyEntries(forceRefresh: Boolean): List<SkillEntry> = withPlugins(mergeAll(
        globalDirectorySkillSource.listSkills(forceRefresh), projectDirectorySkillSource.listSkills(forceRefresh)))

    /** 全部技能（含来源作用域），未过滤禁用；同名技能项目级优先（与 MCP 两级配置一致）。 */
    fun listAllSkills(): List<SkillEntry> = listAllSkills(forceRefresh = false)

    fun listAllSkills(forceRefresh: Boolean): List<SkillEntry> {
        val source = sourceIdentity()
        return blockingRead {
            val local = localDefinitions.capture()
            val entries = if (local != null) {
                withPlugins(mergeAll(localDefinitions.skills(local, SkillScope.GLOBAL),
                    localDefinitions.skills(local, SkillScope.PROJECT))).also { localDefinitions.check(local) }
            } else legacyRead { legacyEntries(forceRefresh) }
            checkSourceIdentity(source)
            entries
        }
    }

    private fun skillsIn(scope: SkillScope): List<Skill> {
        val source = sourceIdentity()
        return legacyRead {
            when (scope) {
                SkillScope.GLOBAL -> globalDirectorySkillSource.listSkills(forceRefresh = true)
                SkillScope.PROJECT -> projectDirectorySkillSource.listSkills(forceRefresh = true)
                SkillScope.PLUGIN -> throw PluginDefinitionReadOnlyException()
            }
        }.also { checkSourceIdentity(source) }
    }

    /** 启用的技能列表（注入系统提示词用），禁用技能被过滤。 */
    fun listSkills(): List<Skill> {
        val source = sourceIdentity()
        return blockingRead {
            val local = localDefinitions.capture()
            val skills = if (local != null) {
                val entries = withPlugins(mergeAll(localDefinitions.skills(local, SkillScope.GLOBAL),
                    localDefinitions.skills(local, SkillScope.PROJECT)))
                filterDisabled(entries, localDefinitions.disabledNames(local)).map { it.skill }
                    .also { localDefinitions.check(local) }
            } else legacyRead {
                filterDisabled(legacyEntries(false), skillConfigRepository.disabledNames()).map { it.skill }
            }
            checkSourceIdentity(source)
            skills
        }
    }

    /** 当前被禁用的技能名集合（小写归一化）。批量判断取一次即可，避免逐个重读配置文件。 */
    fun disabledNames(): Set<String> = skillConfigRepository.disabledNames()

    /** 读取指定 skill 的完整指令正文；不存在 / 解析失败 / 已被禁用时返回 null。 */
    fun loadInstructions(name: String): String? {
        val source = sourceIdentity()
        return blockingRead {
            val local = localDefinitions.capture()
            val instructions = if (local != null) {
                if (name.lowercase() in localDefinitions.disabledNames(local)) null else {
                    val entries = withPlugins(mergeAll(localDefinitions.skills(local, SkillScope.GLOBAL),
                        localDefinitions.skills(local, SkillScope.PROJECT)))
                    entries.firstOrNull { it.skill.name.equals(name, ignoreCase = true) }?.skill?.instructions
                }.also { localDefinitions.check(local) }
            } else legacyRead {
                if (name.lowercase() in skillConfigRepository.disabledNames()) null else {
                    projectDirectorySkillSource.loadInstructions(name)
                        ?: globalDirectorySkillSource.loadInstructions(name)
                        ?: pluginSource.skills().firstOrNull { it.skill.name.equals(name, ignoreCase = true) }?.skill?.instructions
                }
            }
            checkSourceIdentity(source)
            instructions
        }
    }

    /** 技能是否在任一作用域中被禁用。 */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** 在指定作用域启用/禁用某个技能。 */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        if (scope == SkillScope.PLUGIN) throw PluginDefinitionReadOnlyException()
        skillConfigRepository.setDisabled(name, disabled, scope)
        version.incrementAndGet()
    }

    /**
     * 写入技能文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；返回 null 表示成功。
     *
     * 编辑时写回原目录里的原指令文件（可能叫 CLAUDE.md），改名只改 frontmatter 的 name、不动目录名——
     * 技能正文常按 `~/.aicode/skills/<目录>/run.py` 引用同目录脚本，跟着改名会把这些引用打断。
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        if (scope == SkillScope.PLUGIN) return SkillSaveError.READ_ONLY
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = try { skillsIn(scope) } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "读取技能失败: $name", e)
            return SkillSaveError.IO_FAILED
        }
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull { it.name.equals(old, ignoreCase = true) }?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions
        )

        val provider = fileAccess
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            version.incrementAndGet()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "保存技能失败: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** 指定作用域的技能根目录（容器路径）。 */
    fun skillsRoot(scope: SkillScope): String = when (scope) {
        SkillScope.GLOBAL -> globalDirectorySkillSource.skillsRoot
        SkillScope.PROJECT -> projectDirectorySkillSource.skillsRoot
        SkillScope.PLUGIN -> throw PluginDefinitionReadOnlyException()
    }

    /**
     * 从 Markdown 文本导入技能到指定作用域；[fallbackName] 为 frontmatter 缺 name 时的兜底名。
     * 名称非法 / 同名冲突 / 正文为空时整体失败，不落盘。
     */
    fun importMarkdown(text: String, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importMarkdown(fileAccess, skillsRoot(scope), existingNamesIn(scope), text, fallbackName)
            .also { if (it.imported.isNotEmpty()) version.incrementAndGet() }

    /** 从 zip 输入流导入技能（可含多个技能目录）到指定作用域。 */
    fun importZip(input: InputStream, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importArchive(fileAccess, skillsRoot(scope), existingNamesIn(scope), input, fallbackName)
            .also { if (it.imported.isNotEmpty()) version.incrementAndGet() }

    /** 指定作用域下已有技能名（小写），供导入查重。 */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        skillsIn(scope).map { it.name.lowercase() }.toSet()

    /** 删除指定作用域的技能（删除其目录，不可恢复）。返回是否成功。 */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        if (scope == SkillScope.PLUGIN) return false
        val entry = skillsIn(scope).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        val dirPath = entry.dirPath ?: return false
        return safeDeleteSkillDir(fileAccess, dirPath).also { if (it) version.incrementAndGet() }
    }

    companion object {
        private const val TAG = "SkillRepository"

        /** 新建技能时写入的指令文件名。 */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** 名称同时用作目录名，禁掉路径分隔符与保留字符；长度上限防止极端目录名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 目录里已有的指令文件完整路径：SKILL.md 优先，其次 CLAUDE.md（与 [SkillParser.parse] 同一套回退）。 */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = try { provider.listFiles(dirPath) } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(global: List<Skill>, project: List<Skill>): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** 过滤禁用技能（禁用名单已归一化为小写）。 */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** 仅当目录存在且含 SKILL.md/CLAUDE.md 指令文件时才删除，避免误删非技能目录。 */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = try { provider.listFiles(dirPath) } catch (e: Exception) {
                if (e is CancellationException) throw e
                return false
            }.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            }
            if (!hasInstruction) return false
            return try { provider.deleteRecursively(dirPath); true } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
        }
    }
}
