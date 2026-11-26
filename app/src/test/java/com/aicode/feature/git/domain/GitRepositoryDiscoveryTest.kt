package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitRepositoryDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val engine = mockk<CommandEngine>()
    private val workspace = mockk<WorkspaceRepository>()
    private val holder = ExecutionModeHolder()
    private lateinit var root: File
    private lateinit var alpha: File
    private lateinit var beta: File
    private lateinit var nonRepo: File
    private lateinit var nested: File
    private lateinit var repo: GitRepository

    @Before
    fun setUp() {
        root = temporary.newFolder("workspace")
        beta = temporary.newFolder("workspace", "beta")
        nonRepo = temporary.newFolder("workspace", "not-a-repo")
        alpha = temporary.newFolder("workspace", "alpha")
        nested = temporary.newFolder("workspace", "alpha", "nested")
        temporary.newFile("workspace/notes.txt")
        every { workspace.currentPath() } returns root.absolutePath
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
            CommandResult(if (secondArg<String>() == nonRepo.absolutePath) "false\n" else "true\n", 0)
        }
        repo = gitRepositoryForTest(engine, workspace, holder, root.absolutePath)
    }

    @Test
    fun remoteDiscoveryWithSameNamedLocalDirectoryOnlyProbesRoot() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)

        assertEquals(listOf(root.absolutePath), repo.discoverRepos())

        coVerify(exactly = 1) {
            engine.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", root.absolutePath, any())
        }
        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        coVerify(exactly = 0) {
            engine.runCommandSyncUnbounded(any(), match { it != root.absolutePath }, any())
        }
    }

    @Test
    fun remoteDiscoveryDoesNotReplaceNonGitRootWithSameNamedLocalChildren() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        coEvery {
            engine.runCommandSyncUnbounded(any(), root.absolutePath, any())
        } returns CommandResult("false\n", 0)

        assertTrue(repo.discoverRepos().isEmpty())

        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), root.absolutePath, any()) }
        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test
    fun remoteDiscoveryFailureReturnsEmptyWithoutProbingLocalChildren() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        coEvery {
            engine.runCommandSyncUnbounded(any(), root.absolutePath, any())
        } throws IllegalStateException("Remote unavailable")

        assertTrue(repo.discoverRepos().isEmpty())

        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), root.absolutePath, any()) }
        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test
    fun remoteSubdirectoriesIgnoreSameNamedLocalDirectoryWithoutEngineCalls() {
        holder.setMode(ExecutionMode.REMOTE_SSH)

        assertTrue(repo.listSubdirectories().isEmpty())

        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test
    fun localDiscoveryKeepsRootFirstAndSortedDirectGitChildren() = runTest {
        holder.setMode(ExecutionMode.LOCAL_PROOT)

        assertEquals(listOf(root.absolutePath, alpha.absolutePath, beta.absolutePath), repo.discoverRepos())

        coVerifyOrder {
            engine.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", root.absolutePath, any())
            engine.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", alpha.absolutePath, any())
            engine.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", beta.absolutePath, any())
            engine.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", nonRepo.absolutePath, any())
        }
        coVerify(exactly = 4) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), nested.absolutePath, any()) }
    }

    @Test
    fun localDiscoveryStillFindsGitChildrenWhenRootIsNotGit() = runTest {
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        coEvery {
            engine.runCommandSyncUnbounded(any(), root.absolutePath, any())
        } returns CommandResult("false\n", 0)

        assertEquals(listOf(alpha.absolutePath, beta.absolutePath), repo.discoverRepos())

        coVerify(exactly = 4) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test
    fun localSubdirectoriesIncludeNonGitDirectoriesButNotFilesOrGrandchildren() {
        holder.setMode(ExecutionMode.LOCAL_PROOT)

        assertEquals(listOf(alpha.absolutePath, beta.absolutePath, nonRepo.absolutePath), repo.listSubdirectories())

        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test
    fun existingRepositoryReadsExecutionModeAgainAfterSwitching() = runTest {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(listOf(root.absolutePath), repo.discoverRepos())
        assertTrue(repo.listSubdirectories().isEmpty())

        holder.setMode(ExecutionMode.LOCAL_PROOT)
        assertEquals(listOf(root.absolutePath, alpha.absolutePath, beta.absolutePath), repo.discoverRepos())
        assertEquals(listOf(alpha.absolutePath, beta.absolutePath, nonRepo.absolutePath), repo.listSubdirectories())

        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(listOf(root.absolutePath), repo.discoverRepos())
        assertTrue(repo.listSubdirectories().isEmpty())
        coVerify(exactly = 6) { engine.runCommandSyncUnbounded(any(), any(), any()) }
    }
}
