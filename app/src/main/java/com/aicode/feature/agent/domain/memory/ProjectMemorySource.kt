package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File

/** SSH 项目记忆仍保存在手机本地 memory/projects 下，沿用 IP:端口:路径的旧项目 key。 */
class ProjectMemorySource(
    private val projectRoot: String,
    executionModeHolder: ExecutionModeHolder,
    containerInstaller: ContainerInstaller,
    projectAicodeRoot: ProjectAicodeRoot
) : MemorySource {
    private val aicodeDirectory = containerInstaller.aicodeDir
    internal val rootDirectory: File = MemoryStoragePaths.withAppDirectory(
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(aicodeDirectory, "memory/projects"), projectAicodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".aicode/memory")
        },
        aicodeDirectory.parentFile
    )
    private val store = MemoryFileStore(rootDirectory, MemoryScope.PROJECT)

    override fun listMemories(): List<Memory> = if (projectRoot.isBlank()) emptyList() else store.listMemories()
    override fun loadContent(name: String): String? = if (projectRoot.isBlank()) null else store.loadContent(name)
    override fun saveMemory(name: String, description: String, content: String): Boolean =
        projectRoot.isNotBlank() && store.saveMemory(name, description, content)
    override fun editMemory(name: String, edits: List<MemoryEdit>): MemoryEditResult =
        if (projectRoot.isBlank()) MemoryEditResult.NotFound(name) else store.editMemory(name, edits)
    override fun deleteMemory(name: String): Boolean = projectRoot.isNotBlank() && store.deleteMemory(name)
}
