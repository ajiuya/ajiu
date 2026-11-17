package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    fun captureStorageTarget(projectRoot: String?): MemoryStorageTarget = MemoryStorageTarget(
        globalRoot = globalMemorySource.rootDirectory.absoluteFile,
        projectRoot = projectRoot?.takeIf { it.isNotBlank() }?.let { projectSource(it).rootDirectory.absoluteFile }
    )

    fun listEntries(target: MemoryStorageTarget): MemoryManagementResult<List<MemoryEntry>> = managementOperation {
        MemoryFileStore(target.globalRoot, MemoryScope.GLOBAL).entries() +
            (target.projectRoot?.let { MemoryFileStore(it, MemoryScope.PROJECT).entries() } ?: emptyList())
    }

    fun readEntry(target: MemoryStorageTarget, entry: MemoryEntry): MemoryManagementResult<MemoryDocument> = managementOperation {
        managementSource(target, entry.scope).read(entry.fileName)
            ?: throw MemoryStorageException(MemoryManagementError.NOT_FOUND)
    }

    fun createOnly(
        target: MemoryStorageTarget,
        scope: MemoryScope,
        name: String,
        description: String,
        content: String
    ): MemoryManagementResult<Unit> = managementOperation {
        managementSource(target, scope).createOnly(name, description, content)
    }

    fun deleteEntry(target: MemoryStorageTarget, entry: MemoryEntry): MemoryManagementResult<Unit> = managementOperation {
        managementSource(target, entry.scope).deleteEntry(entry)
    }

    private fun managementSource(target: MemoryStorageTarget, scope: MemoryScope): MemoryFileStore = MemoryFileStore(
        when (scope) {
            MemoryScope.GLOBAL -> target.globalRoot
            MemoryScope.PROJECT -> target.projectRoot ?: throw MemoryStorageException(MemoryManagementError.NO_WORKSPACE)
        },
        scope
    )

    private fun <T> managementOperation(action: () -> T): MemoryManagementResult<T> = synchronized(MemorySource.storageLock) {
        try {
            MemoryManagementResult.Success(action())
        } catch (e: MemoryStorageException) {
            MemoryManagementResult.Failure(e.error)
        } catch (_: java.nio.file.NoSuchFileException) {
            MemoryManagementResult.Failure(MemoryManagementError.NOT_FOUND)
        } catch (_: java.io.IOException) {
            MemoryManagementResult.Failure(MemoryManagementError.IO_FAILED)
        } catch (_: SecurityException) {
            MemoryManagementResult.Failure(MemoryManagementError.IO_FAILED)
        }
    }

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）
        return allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content)
            }
        }
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }
}
