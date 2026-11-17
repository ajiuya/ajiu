package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 片段来源，优先级从高到低：项目 > 全局 > 本地 > 内置。 */
enum class PromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }

/** 一个编号最终生效的片段。 */
data class PromptFragment(
    val number: Int,
    val title: String,
    val source: PromptFragmentSource,
    val content: String,
    /** 生效层文件；来源为内置时为 null。 */
    val file: File?,
    /** 正文开头的 `<!-- ... -->` 注释，作为列表摘要。 */
    val description: String,
    val enabled: Boolean = true
) {
    val stableReorderKey: String
        get() = "$title:${content.hashCode()}"

    val body: String
        get() = content.replace(LEADING_COMMENT, "")

    companion object {
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        fun composeContent(description: String, body: String): String =
            if (description.isBlank()) body else "<!-- ${description.trim().replace("-->", "-- >")} -->\n$body"
    }

    /** 生效层是否可写（项目/全局可写，本地/内置只读）。 */
    val editable: Boolean
        get() = source == PromptFragmentSource.PROJECT || source == PromptFragmentSource.GLOBAL
}

/**
 * 提示词片段的四级来源解析：同一编号只生效优先级最高的一层。
 *
 * - 项目：工作区 `.aicode/prompts.custom/`（远程走 [ProjectAicodeRoot] 私有目录）
 * - 全局：`<aicodeDir>/prompts.custom/`
 * - 本地：`<aicodeDir>/prompts/`（启动时从内置释放的副本）
 * - 内置：`assets/prompts/`
 *
 * 编号即身份（文件名前两位数字），决定片段在系统提示词里的顺序。
 */
@Singleton
class PromptFragmentCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {

    private val globalDir: File get() = File(containerInstaller.aicodeDir, CUSTOM_DIR)
    private val localDir: File get() = File(containerInstaller.aicodeDir, LOCAL_DIR)

    /** 某工作区对应的项目层目录；无工作区返回 null。 */
    private fun projectDir(projectRoot: String?): File? =
        projectRoot?.takeIf { it.isNotBlank() }
            ?.let { File(projectAicodeRoot.forPath(it), CUSTOM_DIR) }

    /** 编辑/新增的写入层：有工作区写项目层，否则写全局层。 */
    fun writableDir(projectRoot: String?): File = projectDir(projectRoot) ?: globalDir

    /** 按编号升序列出片段，保留逐条停用的片段供管理。 */
    @Synchronized
    fun list(projectRoot: String?): List<PromptFragment> {
        val builtin = builtinFiles()
        val project = numberedFragments(projectDir(projectRoot))
        val global = numberedFragments(globalDir)
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtin.keys)
            addAll(project.keys)
            addAll(global.keys)
        }
        val enabled = enabledOverrides(projectRoot)
        val all = numbers.sorted().mapNotNull { number ->
            resolve(number, builtin, project, global)?.copy(enabled = enabled[number] ?: true)
        }
        // 「完全禁用内置提示词」时列表只保留自定义来源，与注入结果一致。
        return if (isBuiltinDisabled()) {
            all.filter {
                it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL
            }
        } else {
            all
        }
    }

    @Synchronized
    fun fragment(number: Int, projectRoot: String?): PromptFragment? = resolve(
        number,
        builtinFiles(),
        numberedFragments(projectDir(projectRoot)),
        numberedFragments(globalDir)
    )?.copy(enabled = enabledOverrides(projectRoot)[number] ?: true)

    /** 静态基线正文：所有生效片段去掉前导注释后按编号拼接。 */
    @Synchronized
    fun renderStatic(projectRoot: String?): String =
        list(projectRoot)
            .filter { it.enabled }
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 「仅自定义片段」模式：只取项目层与全局层的自定义片段正文。 */
    @Synchronized
    fun renderCustomOnly(projectRoot: String?): String =
        list(projectRoot)
            .filter { it.enabled && (it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL) }
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** 是否存在「完全禁用内置提示词」标记（全局层）。 */
    @Synchronized
    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(globalDir)

    @Synchronized
    fun setFragmentEnabled(number: Int, enabled: Boolean, projectRoot: String?): Boolean {
        if (number !in 0..99) return false
        return try {
            val dir = writableDir(projectRoot)
            val state = readEnabledState(dir).toMutableMap()
            state[number] = enabled
            writeEnabledState(dir, state)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换提示词片段失败: $number", e)
            false
        }
    }

    /**
     * 保存某编号的覆盖。
     *
     * - [target] 指定写入层（项目/全局）；null 表示写到该编号当前生效的层（只读层则回落可写层）。
     * - [previousNumber] 编辑时若改了编号，传原编号，用于清掉旧编号的覆盖。
     * - 同一编号只保留一份：清掉各可写层里的同编号旧文件。
     */
    @Synchronized
    fun saveOverride(
        number: Int,
        title: String,
        content: String,
        projectRoot: String?,
        target: PromptFragmentSource? = null,
        previousNumber: Int? = null
    ): Boolean {
        if (number !in 0..99 || (previousNumber != null && previousNumber !in 0..99)) return false
        val project = projectDir(projectRoot)
        val originalNumber = previousNumber ?: number
        val dir = when (target) {
            PromptFragmentSource.PROJECT -> project ?: globalDir
            PromptFragmentSource.GLOBAL -> globalDir
            else -> fragment(originalNumber, projectRoot)?.takeIf { it.editable }?.file?.parentFile
                ?: writableDir(projectRoot)
        }
        return try {
            val dirs = listOfNotNull(project, globalDir).distinct()
            val states = dirs.associateWith { readEnabledState(it).toMutableMap() }
            val globalState = states.getValue(globalDir)
            val projectState = project?.let { states.getValue(it) }
            val originalGlobalState = globalState[originalNumber] ?: true
            val originalProjectState = projectState?.get(originalNumber)
            val enabled = originalProjectState ?: originalGlobalState
            states.values.forEach { state ->
                state.remove(originalNumber)
                state.remove(number)
            }
            states.getValue(dir)[number] = enabled
            // 全局正文编辑不应丢掉当前项目对该编号的显式覆盖。
            if (project != null && dir == globalDir && originalProjectState != null) {
                globalState[number] = originalGlobalState
                projectState?.set(number, originalProjectState)
            }
            states.forEach { (scope, state) -> writeEnabledState(scope, state) }
            val file = File(dir, "%02d-%s.md".format(number, sanitizeTitle(title)))
            writeTextAtomically(file, content)
            if (originalNumber != number) dirs.forEach { deleteOverridesFor(originalNumber, it) }
            dirs.forEach { deleteOverridesFor(number, it, keep = file) }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "写提示词覆盖失败: $number", e)
            false
        }
    }

    /**
     * 按拖拽后的顺序重新编号并落盘：编号集合保持不变，只把各片段内容依次映射到这些编号，
     * 从而改变注入顺序（数字越小越靠前）。写入可写层。
     */
    @Synchronized
    fun reorder(reordered: List<PromptFragment>, projectRoot: String?): Boolean {
        if (reordered.isEmpty()) return false
        val project = projectDir(projectRoot)
        val dir = writableDir(projectRoot)
        val numbers = reordered.map { it.number }.sorted()
        if (numbers.any { it !in 0..99 } || numbers.distinct().size != numbers.size) return false
        return try {
            val dirs = listOfNotNull(project, globalDir).distinct()
            val states = dirs.associateWith { readEnabledState(it).toMutableMap() }
            states.values.forEach { state -> numbers.forEach { state.remove(it) } }
            reordered.forEachIndexed { index, fragment ->
                states.getValue(dir)[numbers[index]] = fragment.enabled
            }
            states.forEach { (scope, state) -> writeEnabledState(scope, state) }
            val written = reordered.mapIndexed { index, fragment ->
                File(dir, "%02d-%s.md".format(numbers[index], sanitizeTitle(fragment.title))).also {
                    writeTextAtomically(it, fragment.content)
                }
            }.toSet()
            dirs.forEach { scope ->
                scope.listFiles()?.filter {
                    val number = PromptFragmentResolver.parseNumber(it.name)
                    it.isFile && number != null && number in numbers && it !in written
                }?.forEach { file ->
                    if (!file.delete()) throw IOException("无法删除提示词覆盖: ${file.path}")
                }
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "重排提示词失败", e)
            false
        }
    }

    /** 删除某编号的覆盖（仅当生效层可写），删后自动回退到下一层。 */
    @Synchronized
    fun deleteOverride(number: Int, projectRoot: String?): Boolean {
        val file = fragment(number, projectRoot)?.takeIf { it.editable }?.file ?: return false
        return try {
            val dir = file.parentFile ?: return false
            val state = readEnabledState(dir).toMutableMap()
            state.remove(number)
            if (!file.delete()) return false
            writeEnabledState(dir, state)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除提示词覆盖失败: $number", e)
            false
        }
    }

    /**
     * 清空所有自定义提示词：删掉项目层与全局层里的全部 `<两位数字>-*.md`，
     * 各编号随即回落到内置 / 本地层，等于把提示词「重置」回默认。
     *
     * 清理带编号的片段及启停状态，全局层里的「完全禁用内置提示词」标记等其它文件原样保留。
     * @return 实际删除的文件数。
     */
    @Synchronized
    fun deleteAllCustom(projectRoot: String?): Int {
        val dirs = listOfNotNull(projectDir(projectRoot), globalDir).distinct()
        var deleted = 0
        try {
            dirs.forEach { readEnabledState(it) }
            dirs.forEach { dir ->
                writeEnabledState(dir, emptyMap())
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && PromptFragmentResolver.parseNumber(file.name) != null && file.delete()) {
                        deleted++
                    }
                }
            }
            FileLogger.i(TAG, "已清空自定义提示词，删除 $deleted 个文件")
        } catch (e: Exception) {
            FileLogger.e(TAG, "重置提示词失败", e)
        }
        return deleted
    }

    /** 切换「完全禁用内置提示词」标记（全局层）。 */
    @Synchronized
    fun setBuiltinDisabled(disabled: Boolean): Boolean {
        return try {
            val marker = File(globalDir, PromptFragmentResolver.DISABLE_BUILTIN_FILE)
            if (disabled) {
                if (!globalDir.exists()) globalDir.mkdirs()
                marker.writeText("")
            } else if (marker.isFile) {
                marker.delete()
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换禁用内置提示词失败", e)
            false
        }
    }

    private fun resolve(
        number: Int,
        builtin: Map<Int, String>,
        project: Map<Int, File>,
        global: Map<Int, File>
    ): PromptFragment? {
        project[number]?.let { return readFragment(it, number, PromptFragmentSource.PROJECT) }
        global[number]?.let { return readFragment(it, number, PromptFragmentSource.GLOBAL) }
        builtin[number]?.let { name ->
            File(localDir, name).takeIf { it.isFile }?.let {
                return readFragment(it, number, PromptFragmentSource.LOCAL)
            }
            readAsset(name)?.let { content ->
                return PromptFragment(
                    number,
                    titleOf(name),
                    PromptFragmentSource.BUILTIN,
                    content,
                    null,
                    extractDescription(content)
                )
            }
        }
        return null
    }

    private fun readFragment(file: File, number: Int, source: PromptFragmentSource): PromptFragment? =
        readText(file)?.let { content ->
            PromptFragment(number, titleOf(file.name), source, content, file, extractDescription(content))
        }

    /** 目录顶层 `<两位数字>-<名称>.md`，按编号去重（同编号取字典序首个）。 */
    private fun numberedFragments(dir: File?): Map<Int, File> {
        val files = dir?.listFiles() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            PromptFragmentResolver.parseNumber(file.name)?.let { byNumber.putIfAbsent(it, file) }
        }
        return byNumber
    }

    /** `assets/prompts` 顶层带编号的 md：编号 → 文件名。 */
    private fun builtinFiles(): Map<Int, String> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, String>()
        names.filter { it.endsWith(".md") }.sorted().forEach { name ->
            PromptFragmentResolver.parseNumber(name)?.let { byNumber.putIfAbsent(it, name) }
        }
        return byNumber
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun enabledOverrides(projectRoot: String?): Map<Int, Boolean> {
        fun read(dir: File?): Map<Int, Boolean> = try {
            dir?.let { readEnabledState(it) }.orEmpty()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词开关失败: $dir", e)
            emptyMap()
        }
        return read(globalDir) + read(projectDir(projectRoot))
    }

    private fun readEnabledState(dir: File): Map<Int, Boolean> {
        val file = File(dir, STATE_FILE)
        if (!file.exists()) return emptyMap()
        val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            ?: throw IOException("提示词开关配置必须为 JSON 对象: ${file.path}")
        val enabled = root["enabled"] as? JsonObject
            ?: throw IOException("提示词开关配置缺少 enabled 对象: ${file.path}")
        val state = mutableMapOf<Int, Boolean>()
        enabled.forEach { (key, value) ->
            val number = key.toIntOrNull()
            val primitive = value as? JsonPrimitive
            val flag = primitive?.takeUnless { it.isString }?.booleanOrNull
            if (number == null || number !in 0..99 || flag == null || state.containsKey(number)) {
                throw IOException("提示词开关配置条目无效: ${file.path}")
            }
            state[number] = flag
        }
        return state
    }

    private fun writeEnabledState(dir: File, state: Map<Int, Boolean>) {
        val file = File(dir, STATE_FILE)
        if (state.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("无法删除提示词开关配置: ${file.path}")
            return
        }
        val enabled = JsonObject(state.toSortedMap().mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) })
        writeTextAtomically(file, JsonObject(mapOf("enabled" to enabled)).toString())
    }

    private fun writeTextAtomically(file: File, content: String) {
        val dir = file.parentFile ?: throw IOException("提示词文件缺少父目录: ${file.path}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建提示词目录: ${dir.path}")
        val tmp = File.createTempFile(".${file.name}", ".tmp", dir)
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(file)) throw IOException("无法替换提示词文件: ${file.path}")
        } finally {
            tmp.delete()
        }
    }

    private fun deleteOverridesFor(number: Int, dir: File, keep: File? = null) {
        dir.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number && file != keep
        }?.forEach { file ->
            if (!file.delete()) throw IOException("无法删除提示词覆盖: ${file.path}")
        }
    }

    private fun titleOf(fileName: String): String {
        val base = fileName.removeSuffix(".md")
        return base.substringAfter('-', missingDelimiterValue = base)
    }

    /** 正文开头的 `<!-- ... -->` 注释即摘要（内置片段都带一行）。 */
    private fun extractDescription(content: String): String =
        DESCRIPTION.find(content)?.groupValues?.get(1)?.trim().orEmpty()

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    private companion object {
        const val TAG = "PromptFragmentCatalog"
        const val ASSET_DIR = "prompts"
        const val CUSTOM_DIR = "prompts.custom"
        const val LOCAL_DIR = "prompts"
        const val STATE_FILE = "prompts.json"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        val DESCRIPTION = Regex("^\\s*<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
    }
}
