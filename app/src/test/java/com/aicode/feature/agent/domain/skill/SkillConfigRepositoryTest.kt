package com.aicode.feature.agent.domain.skill

import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.FilterReader
import java.io.Reader
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.charset.CharsetDecoder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillConfigRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: SkillConfigRepository
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var localGlobalRoot: File
    private lateinit var activeGlobalRoot: File
    private lateinit var projectRoot: File

    @Before
    fun setUp() {
        localGlobalRoot = tempFolder.newFolder("local-global")
        activeGlobalRoot = tempFolder.newFolder("remote-global")
        projectRoot = tempFolder.newFolder("project-config")
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns localGlobalRoot
        val projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.current() } returns projectRoot
        val hub = mockk<FileChangeHub>()
        every { hub.watchAicode(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { hub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        fileAccess = mockk()
        every { fileAccess.isFile("~/.aicode/skills.json") } answers {
            File(activeGlobalRoot, "skills.json").isFile
        }
        every { fileAccess.readFile("~/.aicode/skills.json") } answers {
            File(activeGlobalRoot, "skills.json").readText()
        }
        every { fileAccess.writeFile("~/.aicode/skills.json", any(), any(), any()) } answers {
            File(activeGlobalRoot, "skills.json").writeText(secondArg())
        }
        val localDefinitions = mockk<LocalSkillDefinitionSource>()
        coEvery { localDefinitions.capture() } returns null
        every { localDefinitions.sourceSignals() } returns emptyFlow()
        repository = SkillConfigRepository(installer, projectAicodeRoot, hub, fileAccess, localDefinitions)
    }

    @Test
    fun chrootDisabledReadWrite_usesFixedHostScopes_andPreservesTheOtherScope() {
        val f = LocalSkillDefinitionFixture(tempFolder.newFolder())
        f.disabled(SkillScope.GLOBAL, "GLOBAL")
        f.disabled(SkillScope.PROJECT, "PROJECT")
        val initial = f.repository.sourceIdentity()
        assertEquals(setOf("global", "project"), f.config.disabledNames())
        f.config.setDisabled("new", true, SkillScope.GLOBAL)
        assertEquals(setOf("global", "new", "project"), f.config.disabledNames())
        f.config.setDisabled("PROJECT", false, SkillScope.PROJECT)
        assertEquals(setOf("global", "new"), f.config.disabledNames())
        assertEquals(setOf("global", "new"), SkillConfigRepository.parseDisabled(f.configFile(SkillScope.GLOBAL).readText()))
        assertTrue(SkillConfigRepository.parseDisabled(f.configFile(SkillScope.PROJECT).readText()).isEmpty())
        assertThrows(SkillDefinitionSourceChanged::class.java) { f.repository.checkSourceIdentity(initial) }
        verify { f.backend wasNot Called }
    }

    @Test
    fun chrootMalformedOrInvalidConfig_neverBecomesAllEnabled_orOverwritesOriginalBytes() {
        for (scope in listOf(SkillScope.GLOBAL, SkillScope.PROJECT)) {
            for (invalid in listOf("{broken", "[]", "{\"disabled\":true}", "{\"disabled\":[true]}")) {
                val f = LocalSkillDefinitionFixture(tempFolder.newFolder())
                val file = f.disabled(scope, "blocked")
                file.writeText(invalid)
                val original = file.readBytes()
                assertTrue("$scope accepted $invalid", runCatching { f.config.disabledNames() }.isFailure)
                assertTrue("$scope overwrote invalid config", runCatching { f.config.setDisabled("blocked", false, scope) }.isFailure)
                assertArrayEquals(original, file.readBytes())
                f.disabled(scope, "blocked")
                assertEquals(setOf("blocked"), f.config.disabledNames())
                verify { f.backend wasNot Called }
            }
        }
    }

    @Test
    fun chrootConfigReadIoFailure_remainsAnError_andRetainsDisabledData() {
        for (scope in listOf(SkillScope.GLOBAL, SkillScope.PROJECT)) {
            val f = LocalSkillDefinitionFixture(tempFolder.newFolder())
            val file = f.disabled(scope, "blocked")
            val original = file.readBytes()
            val count = AtomicInteger()
            val failure = AccessDeniedException("skills.json", null, "Injected skill config read failure")
            mockkStatic(Channels::class)
            every { Channels.newReader(any<ReadableByteChannel>(), any<CharsetDecoder>(), -1) } answers {
                val delegate = callOriginal() as Reader
                object : FilterReader(delegate) {
                    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                        count.incrementAndGet()
                        throw failure
                    }
                    override fun close() = delegate.close()
                }
            }
            try {
                val before = count.get()
                assertSame(failure, runCatching { f.config.disabledNames() }.exceptionOrNull())
                assertTrue(count.get() > before)
                val writeBefore = count.get()
                assertSame(failure, runCatching { f.config.setDisabled("blocked", false, scope) }.exceptionOrNull())
                assertTrue(count.get() > writeBefore)
                assertArrayEquals(original, file.readBytes())
            } finally {
                unmockkStatic(Channels::class)
            }
            assertEquals(setOf("blocked"), f.config.disabledNames())
            verify { f.backend wasNot Called }
        }
    }

    @Test
    fun chrootConfigWrite_workspaceModeProfileAndAbaChangesAfterStaging_preserveBothProjects() {
        for (change in listOf("workspace", "workspaceABA", "modeABA", "sameIdProfile", "profileABA")) {
            val f = LocalSkillDefinitionFixture(tempFolder.newFolder())
            val file = f.disabled(SkillScope.PROJECT, "blocked")
            val second = File(f.second, ".aicode/skills.json").apply { parentFile!!.mkdirs(); writeBytes(file.readBytes()) }
            val firstBytes = file.readBytes()
            val secondBytes = second.readBytes()
            val armed = AtomicBoolean(true)
            var staged = false
            f.onProfileCheck = {
                val temporaryExists = file.parentFile!!.listFiles().orEmpty().any {
                    it.name.startsWith(".skills-") && it.name.endsWith(".tmp") && Files.isRegularFile(it.toPath())
                }
                if (temporaryExists && armed.compareAndSet(true, false)) {
                    staged = true
                    when (change) {
                        "workspace" -> f.select(f.second)
                        "workspaceABA" -> { f.select(f.second); f.select(f.first) }
                        "modeABA" -> { f.mode.setMode(com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
                            f.mode.setMode(com.aicode.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT) }
                        "sameIdProfile" -> f.configurationRevision.value++
                        "profileABA" -> f.selectionRevision.value += 2
                    }
                }
            }
            val failure = runCatching { f.config.setDisabled("blocked", false, SkillScope.PROJECT) }.exceptionOrNull()
            assertTrue("$change did not reach real temporary-file IO", staged)
            assertTrue("$change accepted an obsolete write", failure is SkillDefinitionSourceChanged)
            assertArrayEquals(firstBytes, file.readBytes())
            assertArrayEquals(secondBytes, second.readBytes())
            verify { f.backend wasNot Called }
        }
    }

    @Test
    fun chrootConfigLinksAndDirectories_areErrors_withoutFollowingOrReplacingTargets() {
        for (scope in listOf(SkillScope.GLOBAL, SkillScope.PROJECT)) for (directory in listOf(false, true)) {
            val f = LocalSkillDefinitionFixture(tempFolder.newFolder())
            val file = f.configFile(scope)
            file.parentFile!!.mkdirs()
            val sentinel = File(tempFolder.newFolder(), "sentinel").apply { writeText("outside disabled sentinel") }
            if (directory) file.mkdir() else Files.createSymbolicLink(file.toPath(), sentinel.toPath())
            assertThrows(IOException::class.java) { f.config.disabledNames() }
            assertThrows(IOException::class.java) { f.config.setDisabled("blocked", true, scope) }
            assertEquals("outside disabled sentinel", sentinel.readText())
            if (directory) assertTrue(file.isDirectory) else assertTrue(Files.isSymbolicLink(file.toPath()))
        }
    }

    @Test
    fun disabledNames_followsExecutionEnvironment_withoutMigratingLocalConfig() {
        File(localGlobalRoot, "skills.json").writeText("""{"disabled":["Local"]}""")
        File(activeGlobalRoot, "skills.json").writeText("""{"disabled":["Remote"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["Project"]}""")

        assertEquals(setOf("remote", "project"), repository.disabledNames())
        activeGlobalRoot = localGlobalRoot
        assertEquals(setOf("local", "project"), repository.disabledNames())
    }

    @Test
    fun setDisabled_global_readsAndWritesActiveBackend_only() {
        val localFile = File(localGlobalRoot, "skills.json")
        localFile.writeText("""{"disabled":["local"]}""")
        File(activeGlobalRoot, "skills.json").writeText("""{"disabled":["existing"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["project"]}""")

        repository.setDisabled("new", true, SkillScope.GLOBAL)
        assertEquals(setOf("existing", "new", "project"), repository.disabledNames())
        repository.setDisabled("existing", false, SkillScope.GLOBAL)
        assertEquals(setOf("new", "project"), repository.disabledNames())
        assertEquals(setOf("local"), SkillConfigRepository.readDisabled(localFile))
        assertEquals(setOf("project"), SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")))
        verify(exactly = 2) { fileAccess.writeFile("~/.aicode/skills.json", any(), any(), any()) }
    }

    @Test
    fun setDisabled_project_keepsLocalProjectRoot_withoutBackendAccess() {
        repository.setDisabled("project", true, SkillScope.PROJECT)
        assertEquals(setOf("project"), SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")))
        repository.setDisabled("project", false, SkillScope.PROJECT)
        assertTrue(SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")).isEmpty())
        verify { fileAccess wasNot Called }
    }

    @Test
    fun disabledNames_missingOrCorruptedRemoteConfig_doesNotFallBackToLocal() {
        File(localGlobalRoot, "skills.json").writeText("""{"disabled":["local"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["project"]}""")
        assertEquals(setOf("project"), repository.disabledNames())
        File(activeGlobalRoot, "skills.json").writeText("{broken")
        assertEquals(setOf("project"), repository.disabledNames())
    }

    @Test
    fun serialize_parse_roundtrip() {
        val json = SkillConfigRepository.serializeDisabled(setOf("b-skill", "a-skill"))
        val parsed = SkillConfigRepository.parseDisabled(json)

        assertEquals(setOf("a-skill", "b-skill"), parsed)
    }

    @Test
    fun parseDisabled_emptyJson_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("""{"disabled":[]}""").isEmpty())
        assertTrue(SkillConfigRepository.parseDisabled("").isEmpty())
    }

    @Test
    fun parseDisabled_corruptedJson_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("{not valid json!!").isEmpty())
    }

    @Test
    fun parseDisabled_missingField_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("""{"other":1}""").isEmpty())
    }

    @Test
    fun readDisabled_missingFile_returnsEmpty() {
        assertTrue(SkillConfigRepository.readDisabled(File(tempFolder.root, "not-exists.json")).isEmpty())
    }

    @Test
    fun writeAndReadDisabled_persists() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("plotrail", "prototype"))
        val read = SkillConfigRepository.readDisabled(file)

        assertEquals(setOf("plotrail", "prototype"), read)
    }

    @Test
    fun writeDisabled_overwritesPrevious() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("a"))
        SkillConfigRepository.writeDisabled(file, setOf("b"))

        assertEquals(setOf("b"), SkillConfigRepository.readDisabled(file))
    }

    @Test
    fun writeDisabled_noTempLeftover() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("a"))

        assertTrue(File(tempFolder.root, "skills.json.tmp").let { !it.exists() })
    }
}
