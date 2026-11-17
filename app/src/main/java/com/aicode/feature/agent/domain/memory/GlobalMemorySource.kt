package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalMemorySource @Inject constructor(
    containerInstaller: ContainerInstaller
) : MemorySource {
    private val aicodeDirectory = containerInstaller.aicodeDir
    internal val rootDirectory = MemoryStoragePaths.withAppDirectory(
        File(aicodeDirectory, "memory"), aicodeDirectory.parentFile
    )
    private val store = MemoryFileStore(rootDirectory, MemoryScope.GLOBAL)

    override fun listMemories(): List<Memory> = store.listMemories()
    override fun loadContent(name: String): String? = store.loadContent(name)
    override fun saveMemory(name: String, description: String, content: String): Boolean = store.saveMemory(name, description, content)
    override fun editMemory(name: String, edits: List<MemoryEdit>): MemoryEditResult = store.editMemory(name, edits)
    override fun deleteMemory(name: String): Boolean = store.deleteMemory(name)
}
