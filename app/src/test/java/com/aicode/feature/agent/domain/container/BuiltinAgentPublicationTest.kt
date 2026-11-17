package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.GlobalDirectoryAgentSource
import com.aicode.feature.agent.domain.subagent.ProjectDirectoryAgentSource
import com.aicode.feature.agent.domain.subagent.BuiltinAgentPublication
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BuiltinAgentPublicationTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = "---\nname: 编码员\ntools: [readFile, writeFile]\n---\n" + "prompt".repeat(18000)
    private val agents get() = mapOf("编码员.md" to payload.toByteArray())
    private fun root() = File(temp.newFolder(), "aicode")

    @Test fun parserWaitsForCompleteClaimedAgentAndRevisionPersists() {
        val root = root()
        val copied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val before = BuiltinAgentPublication.revision.value
        val store = LocalBuiltinDefinitionStore(root) { _, _ ->
            copied.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val target = File(root, "agents/编码员.md")
        val provider = mockk<FileAccessProvider>()
        every { provider.readFile(target.path) } answers { target.readText() }
        try {
            val install = pool.submit<BuiltinInstallReport> { BuiltinDefinitionInstaller(store).install(agents) }
            assertTrue(copied.await(10, TimeUnit.SECONDS))
            val parsed = pool.submit<com.aicode.feature.agent.domain.subagent.AgentDefinition?> {
                attempted.countDown()
                AgentDefinitionParser.parse(provider, target.path)
            }
            assertTrue(attempted.await(10, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { parsed.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertEquals(BuiltinInstallStatus.INSTALLED, install.get(10, TimeUnit.SECONDS).entries["agents/编码员.md"])
            assertEquals(payload.substringAfter("\n---\n"), parsed.get(10, TimeUnit.SECONDS)!!.prompt)
            assertEquals(before + 1, BuiltinAgentPublication.revision.value)
            assertEquals(payload, target.readText())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun publicationInvalidatesRepositoryCacheButDoesNotReenableDisabledNames() {
        val root = root()
        val target = File(root, "agents/编码员.md")
        val provider = mockk<FileAccessProvider>()
        every { provider.readFile(target.path) } answers { target.readText() }
        val global = mockk<GlobalDirectoryAgentSource>()
        every { global.listDefinitions() } answers {
            if (target.exists()) listOfNotNull(AgentDefinitionParser.parse(provider, target.path)) else emptyList()
        }
        val project = mockk<ProjectDirectoryAgentSource>()
        every { project.listDefinitions() } returns emptyList()
        val config = mockk<AgentDefinitionConfigRepository>()
        every { config.disabledNames() } returns setOf("编码员")
        val repository = AgentDefinitionRepository(global, project, config, provider)
        val before = repository.definitionsVersion
        assertTrue(repository.listAll().isEmpty())
        LocalBuiltinDefinitionStore.install(root, agents)
        assertEquals(before + 1, repository.definitionsVersion)
        assertEquals(listOf("编码员"), repository.listAll().map { it.definition.name })
        assertTrue(repository.listEnabled().isEmpty())
    }

    @Test fun ordinaryAgentCopyFailureRollsBackOnlyOwnPrefixAndCanRetry() {
        val root = root()
        val before = BuiltinAgentPublication.revision.value
        val store = LocalBuiltinDefinitionStore(root) { _, _ -> throw IOException("copy failed") }
        assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(store).install(agents).entries["agents/编码员.md"])
        assertFalse(File(root, "agents/编码员.md").exists())
        assertFalse(File(root, "${BuiltinDefinitionPaths.RECEIPTS}/agents/编码员").exists())
        assertEquals(before, BuiltinAgentPublication.revision.value)
        assertEquals(BuiltinInstallStatus.INSTALLED, LocalBuiltinDefinitionStore.install(root, agents).entries["agents/编码员.md"])
    }

    @Test fun cancelledAgentCopyRollsBackAndRethrowsCancellation() {
        val root = root()
        val store = LocalBuiltinDefinitionStore(root) { _, _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { BuiltinDefinitionInstaller(store).install(agents) }
        assertFalse(File(root, "agents/编码员.md").exists())
        assertEquals(BuiltinInstallStatus.INSTALLED, LocalBuiltinDefinitionStore.install(root, agents).entries["agents/编码员.md"])
    }

    @Test fun userAppendOrReplacementLinkDuringCopyIsPreservedNotRolledBack() {
        for (replacementLink in listOf(false, true)) {
            val root = root()
            val outside = temp.newFile().apply { writeText("private target") }
            var changed = false
            val store = LocalBuiltinDefinitionStore(root) { target, _ ->
                if (!changed) {
                    changed = true
                    if (replacementLink) {
                        Files.delete(target)
                        Files.createSymbolicLink(target, outside.toPath())
                    } else target.toFile().appendText("USER APPEND")
                }
            }
            assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(store).install(agents).entries["agents/编码员.md"])
            val target = File(root, "agents/编码员.md")
            if (replacementLink) assertTrue(Files.isSymbolicLink(target.toPath()))
            else assertTrue(target.readText().endsWith("USER APPEND"))
            assertEquals("private target", outside.readText())
            assertTrue(File(root, "${BuiltinDefinitionPaths.RECEIPTS}/agents/编码员").exists())
            assertTrue(Files.deleteIfExists(target.toPath()))
            assertEquals(BuiltinInstallStatus.ALREADY_HANDLED,
                LocalBuiltinDefinitionStore.install(root, agents).entries["agents/编码员.md"])
            assertFalse(target.exists())
            assertEquals("private target", outside.readText())
        }
    }

    @Test fun userClaimsExactPathAtPublishBoundaryWinsWithoutTruncation() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val racing = object : BuiltinDefinitionStore by delegate {
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                File(root, target).writeText("user won")
                return delegate.publish(staged, target, directory)
            }
        }
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, BuiltinDefinitionInstaller(racing).install(agents).entries["agents/编码员.md"])
        assertEquals("user won", File(root, "agents/编码员.md").readText())
    }

    @Test fun completeStagedSkillIsOutsideRecursiveScannerUntilPublish() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val files = mapOf("SKILL.md" to "---\nname: dev-god\n---\nbody".toByteArray(), "docs/deep/ref.md" to ByteArray(90000))
        fun visibleInstructions() = File(root, "skills").walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
        val observing = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                delegate.writeNew(path, bytes)
                assertTrue(visibleInstructions().isEmpty())
            }
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                assertTrue(visibleInstructions().isEmpty())
                val result = delegate.publish(staged, target, directory)
                files.forEach { (path, bytes) -> assertArrayEquals(bytes, File(root, "$target/$path").readBytes()) }
                return result
            }
        }
        assertEquals(BuiltinInstallStatus.INSTALLED, BuiltinDefinitionInstaller(observing).install(skills = mapOf("dev-god" to files)).entries["skills/dev-god"])
        assertEquals(1, visibleInstructions().size)
    }
}
