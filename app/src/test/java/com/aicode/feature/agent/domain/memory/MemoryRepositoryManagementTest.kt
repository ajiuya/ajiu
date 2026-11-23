package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRepositoryManagementTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var aicode: File
    private lateinit var project: File
    private lateinit var installer: ContainerInstaller
    private lateinit var holder: ExecutionModeHolder
    private lateinit var projectAicodeRoot: ProjectAicodeRoot
    private lateinit var repository: MemoryRepository
    private lateinit var target: MemoryStorageTarget

    @Before fun setUp() {
        aicode = temporary.newFolder("aicode")
        project = temporary.newFolder("workspace")
        installer = mockk { every { aicodeDir } returns aicode }
        holder = ExecutionModeHolder()
        projectAicodeRoot = mockk()
        repository = MemoryRepository(GlobalMemorySource(installer), holder, installer, projectAicodeRoot)
        target = repository.captureStorageTarget(project.path)
    }

    @Test fun listAndReadExposeActualMtimeWithoutChangingMarkdownBytesOrTimestamp() {
        target.globalRoot.mkdirs()
        val bytes = ("---\r\nname: manual-alias\r\ndescription: kept summary\r\ncustom: untouched\r\n---\r\n" +
            "# Preserve Markdown\r\n\r\n**first** body\r\n").toByteArray(Charsets.UTF_8)
        val file = File(target.globalRoot, "legacy name.v1.md").apply { writeBytes(bytes) }
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_700_000_123_456L))
        val actualModified = Files.getLastModifiedTime(file.toPath())
        assertTrue(actualModified.toMillis() > 0L)

        repeat(2) {
            val listed = success(repository.listEntries(target)).single()
            assertEquals("legacy name.v1.md", listed.fileName)
            assertEquals("manual-alias", listed.name)
            assertEquals(actualModified.toMillis(), listed.modifiedAt)
            assertDiskSnapshot(file, bytes, actualModified)

            val document = success(repository.readEntry(target, listed))
            assertEquals(listed, document.entry)
            assertEquals(actualModified.toMillis(), document.entry.modifiedAt)
            assertEquals("# Preserve Markdown\n\n**first** body", document.content)
            assertDiskSnapshot(file, bytes, actualModified)
        }
    }

    @Test fun readEntryRefreshesModifiedAtAfterSameNamedContentIsUpdated() {
        success(repository.createOnly(target, MemoryScope.PROJECT, "entry", "summary", "first body"))
        val file = File(requireNotNull(target.projectRoot), "entry.md")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_700_000_000_000L))
        val originalModified = Files.getLastModifiedTime(file.toPath())
        val listed = success(repository.listEntries(target)).single()
        assertEquals(originalModified.toMillis(), listed.modifiedAt)

        assertTrue(repository.saveMemory("entry", "summary", "# Updated body", MemoryScope.PROJECT, project.path))
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_700_086_400_000L))
        val updatedModified = Files.getLastModifiedTime(file.toPath())
        val updatedBytes = file.readBytes()
        assertTrue(updatedModified.toMillis() > originalModified.toMillis())

        val latest = success(repository.readEntry(target, listed))
        assertEquals(listed.id, latest.entry.id)
        assertEquals(listed.name, latest.entry.name)
        assertEquals(MemoryScope.PROJECT, latest.entry.scope)
        assertEquals("# Updated body", latest.content)
        assertEquals(updatedModified.toMillis(), latest.entry.modifiedAt)
        assertNotEquals(listed.modifiedAt, latest.entry.modifiedAt)
        assertNotEquals(listed.version, latest.entry.version)
        assertDiskSnapshot(file, updatedBytes, updatedModified)
        assertEquals(latest.entry, success(repository.listEntries(target)).single())
        assertDiskSnapshot(file, updatedBytes, updatedModified)
    }

    @Test fun zeroFilesystemMtimeIsUnknownForBothListingAndReading() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "unknown-time", "", "unchanged body"))
        val file = File(target.globalRoot, "unknown-time.md")
        val bytes = file.readBytes()
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(0L))
        val actualModified = Files.getLastModifiedTime(file.toPath())
        assertEquals("The fixture must really have the Unix epoch mtime", 0L, actualModified.toMillis())

        val listed = success(repository.listEntries(target)).single()
        assertNull(listed.modifiedAt)
        assertDiskSnapshot(file, bytes, actualModified)
        val document = success(repository.readEntry(target, listed))
        assertNull(document.entry.modifiedAt)
        assertEquals(listed, document.entry)
        assertEquals("unchanged body", document.content)
        assertDiskSnapshot(file, bytes, actualModified)
    }

    @Test fun aiListMemoriesKeepsNameOrderWhenFileMtimesRunInReverse() {
        target.globalRoot.mkdirs()
        val snapshots = linkedMapOf<File, Pair<ByteArray, FileTime>>()
        listOf(
            "z-last" to 1_700_172_800_000L,
            "a-first" to 1_700_000_000_000L,
            "m-middle" to 1_700_086_400_000L
        ).forEach { (name, millis) ->
            val bytes = "---\nname: $name\ndescription: kept\n---\n$name body\n".toByteArray(Charsets.UTF_8)
            val file = File(target.globalRoot, "$name.md").apply { writeBytes(bytes) }
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(millis))
            snapshots[file] = bytes to Files.getLastModifiedTime(file.toPath())
        }
        val firstModified = snapshots.getValue(File(target.globalRoot, "a-first.md")).second.toMillis()
        val middleModified = snapshots.getValue(File(target.globalRoot, "m-middle.md")).second.toMillis()
        val lastModified = snapshots.getValue(File(target.globalRoot, "z-last.md")).second.toMillis()
        assertTrue(firstModified < middleModified && middleModified < lastModified)

        val entries = success(repository.listEntries(target))
        assertEquals(listOf("a-first", "m-middle", "z-last"), entries.map { it.name })
        entries.forEach { listed ->
            assertEquals(snapshots.getValue(File(target.globalRoot, listed.fileName)).second.toMillis(), listed.modifiedAt)
        }
        snapshots.forEach { (file, snapshot) -> assertDiskSnapshot(file, snapshot.first, snapshot.second) }
        val memories = repository.listMemories(null)
        assertEquals(listOf("a-first", "m-middle", "z-last"), memories.map { it.name })
        assertEquals(listOf("a-first.md", "m-middle.md", "z-last.md"), memories.map { it.file?.name })
        assertTrue(memories.all { it.scope == MemoryScope.GLOBAL })
        assertEquals(listOf("a-first body", "m-middle body", "z-last body"), memories.map { it.content })
        snapshots.forEach { (file, snapshot) -> assertDiskSnapshot(file, snapshot.first, snapshot.second) }
    }

    @Test fun sameNameModifiedTimesRemainScopeExactEvenWhenGlobalIsNewer() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "shared", "global", "global body"))
        success(repository.createOnly(target, MemoryScope.PROJECT, "shared", "project", "project body"))
        val globalFile = File(target.globalRoot, "shared.md")
        val projectFile = File(requireNotNull(target.projectRoot), "shared.md")
        val globalBytes = globalFile.readBytes()
        val projectBytes = projectFile.readBytes()
        Files.setLastModifiedTime(globalFile.toPath(), FileTime.fromMillis(1_700_086_400_000L))
        Files.setLastModifiedTime(projectFile.toPath(), FileTime.fromMillis(1_700_000_000_000L))
        val globalModified = Files.getLastModifiedTime(globalFile.toPath())
        val projectModified = Files.getLastModifiedTime(projectFile.toPath())
        assertTrue(globalModified.toMillis() > projectModified.toMillis())

        val entries = success(repository.listEntries(target))
        assertEquals(2, entries.size)
        val global = entries.single { it.scope == MemoryScope.GLOBAL }
        val local = entries.single { it.scope == MemoryScope.PROJECT }
        assertNotEquals(global.id, local.id)
        assertEquals(globalModified.toMillis(), global.modifiedAt)
        assertEquals(projectModified.toMillis(), local.modifiedAt)
        val globalDocument = success(repository.readEntry(target, global))
        val projectDocument = success(repository.readEntry(target, local))
        assertEquals(global, globalDocument.entry)
        assertEquals(local, projectDocument.entry)
        assertEquals("global body", globalDocument.content)
        assertEquals("project body", projectDocument.content)
        val toolMemory = repository.listMemories(project.path).single()
        assertEquals(MemoryScope.PROJECT, toolMemory.scope)
        assertEquals("project body", toolMemory.content)
        assertEquals("project body", repository.loadContent("shared", project.path))
        assertDiskSnapshot(globalFile, globalBytes, globalModified)
        assertDiskSnapshot(projectFile, projectBytes, projectModified)
    }

    @Test fun sameNameStaysSeparateWhileToolAggregationRemainsProjectFirst() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "shared", "global", "global body"))
        success(repository.createOnly(target, MemoryScope.PROJECT, "shared", "project", "project body"))
        val entries = success(repository.listEntries(target))
        assertEquals(2, entries.size)
        entries.forEach {
            val body = success(repository.readEntry(target, it)).content
            assertEquals(if (it.scope == MemoryScope.GLOBAL) "global body" else "project body", body)
        }
        assertEquals("project body", repository.loadContent("shared", project.path))
        assertEquals(1, repository.listMemories(project.path).size)
        assertEquals(MemoryScope.PROJECT, repository.listMemories(project.path).single().scope)
    }

    @Test fun createOnlyConflictsWithoutOverwritingAndToolSaveStillOverwrites() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "existing", "first", "original"))
        assertFailure(MemoryManagementError.NAME_CONFLICT,
            repository.createOnly(target, MemoryScope.GLOBAL, "existing", "second", "replacement"))
        assertFailure(MemoryManagementError.NAME_CONFLICT,
            repository.createOnly(target, MemoryScope.GLOBAL, "EXISTING", "second", "replacement"))
        assertEquals("original", repository.loadContent("existing", null))
        assertTrue(repository.saveMemory("existing", "tool", "overwritten", MemoryScope.GLOBAL, null))
        assertEquals("overwritten", repository.loadContent("existing", null))
    }

    @Test fun deletionIsScopeExactAndUnknownFilesAreUntouched() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "shared", "", "global"))
        success(repository.createOnly(target, MemoryScope.PROJECT, "shared", "", "project"))
        val unknown = File(target.globalRoot, "unrelated.txt").apply { writeText("keep") }
        val projectEntry = success(repository.listEntries(target)).single { it.scope == MemoryScope.PROJECT }
        success(repository.deleteEntry(target, projectEntry))
        assertEquals("global", repository.loadContent("shared", project.path))
        assertTrue(unknown.exists())
        assertEquals(listOf(MemoryScope.GLOBAL), success(repository.listEntries(target)).map { it.scope })
    }

    @Test fun frontmatterAliasesDoNotRedirectReadOrDeleteAndLegacyFileNamesRemainUsable() {
        target.globalRoot.mkdirs()
        File(target.globalRoot, "victim.md").writeText(MemoryParser.format("victim", "", "do not delete"))
        File(target.globalRoot, "old name.v1.md").writeText("---\nname: victim\ndescription: legacy\n---\nlegacy body")
        val entries = success(repository.listEntries(target))
        assertEquals(2, entries.size)
        val alias = entries.single { it.fileName == "old name.v1.md" }
        assertEquals("victim", alias.name)
        assertEquals("legacy body", success(repository.readEntry(target, alias)).content)
        success(repository.deleteEntry(target, alias.copy(name = "anything")))
        assertFalse(File(target.globalRoot, "old name.v1.md").exists())
        assertEquals("do not delete", File(target.globalRoot, "victim.md").let { MemoryParser.parse(it, MemoryScope.GLOBAL)!!.content })
    }

    @Test fun frontmatterAliasNameConflictsAndPlainLegacyMarkdownIsReadable() {
        target.globalRoot.mkdirs()
        File(target.globalRoot, "actual.md").writeText(MemoryParser.format("alias", "", "legacy"))
        File(target.globalRoot, "old notes.md").writeText("# Plain legacy\nbody")
        assertFailure(MemoryManagementError.NAME_CONFLICT,
            repository.createOnly(target, MemoryScope.GLOBAL, "alias", "", "new"))
        val plain = success(repository.listEntries(target)).single { it.fileName == "old notes.md" }
        assertEquals("old notes", plain.name)
        assertEquals("# Plain legacy\nbody", success(repository.readEntry(target, plain)).content)
        success(repository.deleteEntry(target, plain))
    }

    @Test fun malformedFrontmatterStillUsesExistingParserFallback() {
        target.globalRoot.mkdirs()
        File(target.globalRoot, "old.md").writeText("---\nname: old\nwithout closing marker")
        val entry = success(repository.listEntries(target)).single()
        assertEquals("old", entry.name)
        assertTrue(success(repository.readEntry(target, entry)).content.startsWith("---"))
    }

    @Test fun externalUpdateWithSameLengthAndTimestampStillConflicts() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "entry", "", "first"))
        val entry = success(repository.listEntries(target)).single()
        val file = File(target.globalRoot, entry.fileName)
        val modified = Files.getLastModifiedTime(file.toPath())
        file.writeText(MemoryParser.format("entry", "", "other"))
        Files.setLastModifiedTime(file.toPath(), modified)
        assertFailure(MemoryManagementError.CHANGED, repository.deleteEntry(target, entry))
        assertTrue(file.exists())
        val latest = success(repository.readEntry(target, entry))
        assertEquals("other", latest.content)
        success(repository.deleteEntry(target, latest.entry))
    }

    @Test fun toolEditChangesDeletionVersionAndKeepsItsPartialEditSemantics() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "entry", "", "alpha beta"))
        val entry = success(repository.listEntries(target)).single()
        assertEquals(MemoryEditResult.Success,
            repository.editMemory("entry", listOf(MemoryEdit("beta", "gamma", false)), MemoryScope.GLOBAL, null))
        assertFailure(MemoryManagementError.CHANGED, repository.deleteEntry(target, entry))
        assertEquals("alpha gamma", repository.loadContent("entry", null))
    }

    @Test fun invalidNewNamesAndForgedFileNamesCannotEscapeOrAliasOtherFiles() {
        val invalid = listOf("../escape", "a/b", "a\\b", ".", "..", "", "a\nb", "a\u0000b", "a b")
        invalid.forEach { name ->
            assertFailure(MemoryManagementError.INVALID_NAME,
                repository.createOnly(target, MemoryScope.GLOBAL, name, "", "body"))
        }
        success(repository.createOnly(target, MemoryScope.GLOBAL, "valid", "", "body"))
        val entry = success(repository.listEntries(target)).single()
        listOf("../valid.md", "sub/valid.md", "valid\n.md", "valid.txt").forEach { fileName ->
            assertFailure(MemoryManagementError.INVALID_NAME, repository.readEntry(target, entry.copy(fileName = fileName)))
            assertFailure(MemoryManagementError.INVALID_NAME, repository.deleteEntry(target, entry.copy(fileName = fileName)))
        }
        assertTrue(File(target.globalRoot, "valid.md").exists())
        assertTrue(repository.saveMemory("a b", "", "tool normalization", MemoryScope.GLOBAL, null))
        assertTrue(File(target.globalRoot, "a-b.md").exists())
    }

    @Test fun symlinkRootIsRejectedByManagementAndTools() {
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "entry.md").apply { writeText("untouched") }
        Files.createSymbolicLink(target.globalRoot.toPath(), outside.toPath())
        assertFailure(MemoryManagementError.UNSAFE_PATH, repository.listEntries(target))
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            repository.createOnly(target, MemoryScope.GLOBAL, "entry", "", "overwrite"))
        assertFalse(repository.saveMemory("entry", "", "overwrite", MemoryScope.GLOBAL, null))
        assertFalse(repository.deleteMemory("entry", MemoryScope.GLOBAL, null))
        assertEquals("untouched", sentinel.readText())
        assertTrue(repository.listMemories(null).isEmpty())
    }

    @Test fun symlinkAncestorAndSymlinkFileAreRejectedIncludingLinksPointingInsideTheRoot() {
        target.globalRoot.mkdirs()
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "external.md").apply { writeText("outside") }
        Files.createSymbolicLink(File(target.globalRoot, "linked.md").toPath(), sentinel.toPath())
        File(target.globalRoot, "real.md").writeText("inside")
        Files.createSymbolicLink(File(target.globalRoot, "inside-link.md").toPath(), File(target.globalRoot, "real.md").toPath())
        assertEquals(listOf("real.md"), success(repository.listEntries(target)).map { it.fileName })
        val real = success(repository.listEntries(target)).single()
        listOf("linked.md", "inside-link.md").forEach { name ->
            assertFailure(MemoryManagementError.UNSAFE_PATH, repository.readEntry(target, real.copy(fileName = name)))
            assertFailure(MemoryManagementError.UNSAFE_PATH, repository.deleteEntry(target, real.copy(fileName = name)))
        }
        val linkedProject = temporary.root.resolve("linked-project")
        Files.createSymbolicLink(linkedProject.toPath(), project.toPath())
        val linkedTarget = repository.captureStorageTarget(linkedProject.path)
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            repository.createOnly(linkedTarget, MemoryScope.PROJECT, "entry", "", "body"))
        assertEquals("outside", sentinel.readText())
    }

    @Test fun replacingAConfirmedFileWithASymlinkDoesNotDeleteTheLinkOrItsTarget() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "entry", "", "original"))
        val entry = success(repository.listEntries(target)).single()
        val outside = temporary.newFile("outside.md").apply { writeText("outside") }
        val path = File(target.globalRoot, entry.fileName).toPath()
        Files.delete(path)
        Files.createSymbolicLink(path, outside.toPath())
        assertFailure(MemoryManagementError.UNSAFE_PATH, repository.deleteEntry(target, entry))
        assertTrue(Files.isSymbolicLink(path))
        assertEquals("outside", outside.readText())
    }

    @Test fun directoryWithMarkdownSuffixIsNotDeletedOrOverwritten() {
        target.globalRoot.mkdirs()
        val directory = File(target.globalRoot, "folder.md").apply { mkdir() }
        val keep = File(directory, "keep").apply { writeText("keep") }
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            repository.createOnly(target, MemoryScope.GLOBAL, "folder", "", "body"))
        assertTrue(success(repository.listEntries(target)).isEmpty())
        assertTrue(keep.exists())
    }

    @Test fun noWorkspaceAllowsGlobalButRejectsProjectWithoutCreatingDirectories() {
        val noWorkspace = repository.captureStorageTarget(null)
        assertNull(noWorkspace.projectRoot)
        assertFailure(MemoryManagementError.NO_WORKSPACE,
            repository.createOnly(noWorkspace, MemoryScope.PROJECT, "entry", "", "body"))
        success(repository.createOnly(noWorkspace, MemoryScope.GLOBAL, "entry", "", "global"))
        assertEquals(1, success(repository.listEntries(noWorkspace)).size)
        assertFalse(File(project, ".aicode").exists())
    }

    @Test fun concurrentCreatesHaveOneWinnerAndNeverReplaceTheWinner() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("first", "second").map { body ->
                executor.submit<MemoryManagementResult<Unit>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repository.createOnly(target, MemoryScope.GLOBAL, "racing", "", body)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is MemoryManagementResult.Success })
            assertEquals(1, results.count { it == MemoryManagementResult.Failure(MemoryManagementError.NAME_CONFLICT) })
            assertTrue(repository.loadContent("racing", null) in listOf("first", "second"))
        } finally { executor.shutdownNow() }
    }

    @Test fun managementAndToolWritesShareTheSameStorageLock() {
        success(repository.createOnly(target, MemoryScope.GLOBAL, "entry", "", "original"))
        val entry = success(repository.listEntries(target)).single()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = synchronized(MemorySource.storageLock) {
                val pending = executor.submit<Boolean> {
                    started.countDown()
                    try { repository.saveMemory("entry", "", "tool", MemoryScope.GLOBAL, null) }
                    finally { finished.countDown() }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
                success(repository.deleteEntry(target, entry))
                pending
            }
            assertTrue(future.get(5, TimeUnit.SECONDS))
            assertEquals("tool", repository.loadContent("entry", null))
        } finally { executor.shutdownNow() }
    }

    @Test fun trustedAppDirectoryAliasIsResolvedOnceAndFrozenTargetsDoNotFollowLaterRetargeting() {
        val actual = temporary.newFolder("actual-app")
        val alias = File(temporary.root, "app-alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val aliasInstaller = mockk<ContainerInstaller> { every { aicodeDir } returns File(alias, "aicode") }
        val aliasRepository = MemoryRepository(GlobalMemorySource(aliasInstaller), holder, aliasInstaller, projectAicodeRoot)
        val workspace = File(alias, "projects/workspace").apply { mkdirs() }
        val frozen = aliasRepository.captureStorageTarget(workspace.path)
        assertEquals(File(actual, "aicode/memory"), frozen.globalRoot)
        assertEquals(File(actual, "projects/workspace/.aicode/memory"), frozen.projectRoot)
        success(aliasRepository.createOnly(frozen, MemoryScope.GLOBAL, "global", "", "global"))
        success(aliasRepository.createOnly(frozen, MemoryScope.PROJECT, "project", "", "project"))
        val replacement = temporary.newFolder("replacement-app")
        Files.delete(alias.toPath())
        Files.createSymbolicLink(alias.toPath(), replacement.toPath())
        success(aliasRepository.createOnly(frozen, MemoryScope.PROJECT, "still-old", "", "frozen"))
        assertTrue(File(actual, "projects/workspace/.aicode/memory/still-old.md").exists())
        assertFalse(File(replacement, "projects").exists())
    }

    @Test fun trustedAppAncestorDoesNotAllowMemoryOrWorkspaceLinksBelowThatBoundary() {
        val actual = temporary.newFolder("actual-app")
        val alias = File(temporary.root, "app-alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val outside = temporary.newFolder("outside-app")
        File(actual, "aicode").mkdirs()
        Files.createSymbolicLink(File(actual, "aicode/memory").toPath(), outside.toPath())
        val aliasInstaller = mockk<ContainerInstaller> { every { aicodeDir } returns File(alias, "aicode") }
        val aliasRepository = MemoryRepository(GlobalMemorySource(aliasInstaller), holder, aliasInstaller, projectAicodeRoot)
        val frozen = aliasRepository.captureStorageTarget(null)
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            aliasRepository.createOnly(frozen, MemoryScope.GLOBAL, "entry", "", "body"))
        File(actual, "projects").mkdirs()
        Files.createSymbolicLink(File(actual, "projects/workspace").toPath(), outside.toPath())
        val projectTarget = aliasRepository.captureStorageTarget(File(alias, "projects/workspace").path)
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            aliasRepository.createOnly(projectTarget, MemoryScope.PROJECT, "entry", "", "body"))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun rootControlCharactersAreRejectedBeforePathResolution() {
        val unsafe = target.copy(globalRoot = File(temporary.root, "unsafe\u0000root"))
        assertFailure(MemoryManagementError.UNSAFE_PATH, repository.listEntries(unsafe))
        assertFailure(MemoryManagementError.UNSAFE_PATH,
            repository.createOnly(unsafe, MemoryScope.GLOBAL, "entry", "", "body"))
    }

    @Test fun sshTargetKeepsTheOldLocalDirectoryAndIsFrozenAcrossServerAndModeChanges() {
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val ssh = mockk<RemoteSshConnection>()
        var config = mockk<RemoteConnectionConfig> {
            every { host } returns "192.0.2.10"
            every { port } returns 2222
        }
        every { ssh.config } answers { config }
        val roots = ProjectAicodeRoot(mockk<WorkspaceRepository>(), holder, installer, ssh)
        val remoteRepository = MemoryRepository(GlobalMemorySource(installer), holder, installer, roots)
        val remotePath = "/home/test/workspace"
        val frozen = remoteRepository.captureStorageTarget(remotePath)
        val hash = BigInteger(1, MessageDigest.getInstance("MD5")
            .digest("192.0.2.10:2222:$remotePath".toByteArray(Charsets.UTF_8)))
            .toString(16).padStart(32, '0').take(8)
        assertEquals(File(aicode, "memory/projects/workspace-$hash"), frozen.projectRoot)
        config = mockk { every { host } returns "192.0.2.11"; every { port } returns 22 }
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        success(remoteRepository.createOnly(frozen, MemoryScope.PROJECT, "entry", "", "old target"))
        assertEquals("old target", success(remoteRepository.readEntry(frozen, success(remoteRepository.listEntries(frozen)).single())).content)
        assertTrue(File(frozen.projectRoot, "entry.md").exists())
        assertFalse(File(aicode, "projects").exists())
    }

    private fun assertDiskSnapshot(file: File, bytes: ByteArray, modified: FileTime) {
        assertArrayEquals("Reading must not rewrite ${file.name}", bytes, file.readBytes())
        assertEquals("Reading must not touch ${file.name} mtime", modified, Files.getLastModifiedTime(file.toPath()))
    }

    private fun <T> success(result: MemoryManagementResult<T>): T = when (result) {
        is MemoryManagementResult.Success -> result.value
        is MemoryManagementResult.Failure -> error("Unexpected failure: ${result.error}")
    }

    private fun assertFailure(error: MemoryManagementError, actual: MemoryManagementResult<*>) {
        assertEquals(MemoryManagementResult.Failure(error), actual)
    }
}
