package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

class LocalFileAccessSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var mapper: WorkspacePathMapper
    private lateinit var access: LocalFileAccess

    @Before
    fun prepare() {
        root = temporary.newFolder("workspace")
        val rootfs = temporary.newFolder("rootfs")
        val global = temporary.newFolder("global")
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        every { workspace.currentPath() } returns root.absolutePath
        every { workspace.selection } returns MutableStateFlow(WorkspaceRepository.Selection(null))
        every { installer.aicodeDir } returns global
        every { installer.rootfsDirFor(any()) } returns rootfs
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { settings.customProfilesFlow } returns emptyFlow()
        every { settings.profileConfigurationRevision } returns 0L
        every { settings.activeProfileSelectionRevision } returns 0L
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            val path = firstArg<String>()
            when {
                path == "~" -> "/root"
                path.startsWith("~/") -> "/root/" + path.removePrefix("~/")
                else -> path
            }
        }
        mapper = WorkspacePathMapper(workspace, installer, settings, home)
        access = LocalFileAccess(mapper)
    }

    private fun link(name: String, target: String) {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        Files.createSymbolicLink(file.toPath(), File(target).toPath())
    }

    private fun assertRejectedWithoutMutation(source: String, target: String) {
        for (move in listOf(false, true)) {
            assertThrows(IOException::class.java) {
                if (move) access.move(source, target, overwrite = true) else access.copy(source, target, overwrite = true)
            }
            assertEquals("source", File(root, "tree/sub/source.txt").readText())
            assertEquals("keep", File(root, "tree/keep.txt").readText())
        }
    }

    private fun tree() {
        File(root, "tree/sub").mkdirs()
        File(root, "tree/sub/source.txt").writeText("source")
        File(root, "tree/keep.txt").writeText("keep")
    }

    @Test
    fun boundedReadAcceptsExactZeroAndMaximumLimitWithoutChangingLegacyRead() {
        val empty = File(root, "empty").apply { createNewFile() }
        val bytes = byteArrayOf(0, 1, 2, -1)
        File(root, "binary").writeBytes(bytes)
        assertArrayEquals(byteArrayOf(), access.readBytes(empty.name, 0))
        assertArrayEquals(bytes, access.readBytes("binary", bytes.size.toLong()))
        assertArrayEquals(bytes, access.readBytes("binary", Int.MAX_VALUE.toLong()))
        assertArrayEquals(bytes, access.readBytes("binary"))
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes("binary", 0) }
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes("binary", 3) }
    }

    @Test
    fun boundedReadRejectsInvalidLimitsBeforeResolvingOrOpening() {
        val unresolved = LocalFileAccess(mockk())
        assertThrows(IllegalArgumentException::class.java) { unresolved.readBytes("missing", -1) }
        assertThrows(IllegalArgumentException::class.java) { unresolved.readBytes("missing", Int.MAX_VALUE.toLong() + 1) }
    }

    @Test
    fun fileGrowingAfterPreflightIsRejectedByTheStreamingLimit() {
        File(root, "growing").writeBytes(byteArrayOf(1, 2, 3))
        val growing = object : File(root, "growing") {
            override fun length(): Long = super.length().also {
                File(absolutePath).appendBytes(byteArrayOf(4, 5, 6))
            }
        }
        val mapped = mockk<WorkspacePathMapper>()
        val mapping = mapper.captureExecutionMapping()
        every { mapped.captureExecutionMapping(any()) } returns mapping
        every { mapped.resolveFile(any(), any()) } answers {
            if (firstArg<String>() == "growing") growing else mapper.resolveFile(firstArg(), secondArg())
        }
        assertThrows(FileReadTooLargeException::class.java) { LocalFileAccess(mapped).readBytes("growing", 3) }
        assertEquals(6L, File(root, "growing").length())
    }

    @Test
    fun identityChangeDuringMetadataPreflightRejectsBeforeOpeningTheNewSource() {
        File(root, "source").writeText("old")
        val next = temporary.newFolder("next")
        File(next, "source").writeText("new")
        var currentRoot = root
        var sourceResolutions = 0
        val changing = object : File(root, "source") {
            override fun length(): Long = super.length().also { currentRoot = next }
        }
        val mapped = mockk<WorkspacePathMapper>()
        val mapping = mapper.captureExecutionMapping()
        every { mapped.captureExecutionMapping(any()) } answers {
            mapping.copy(workspaceRoot = currentRoot.canonicalFile, workspaceSourcePath = currentRoot.absolutePath)
        }
        every { mapped.resolveFile(any(), any()) } answers {
            when (firstArg<String>()) {
                "~/workspace" -> currentRoot
                "source" -> { sourceResolutions++; changing }
                else -> mapper.resolveFile(firstArg(), secondArg())
            }
        }
        assertThrows(CancellationException::class.java) { LocalFileAccess(mapped).readBytes("source", 10) }
        assertEquals(1, sourceResolutions)
        assertEquals("old", File(root, "source").readText())
        assertEquals("new", File(next, "source").readText())
    }

    @Test
    fun chainedLinksResolveBeforeParentSegmentsAndReadThroughTheMappedTarget() {
        File(root, "target/sub").mkdirs()
        val expected = File(root, "target/probe").apply { writeText("target") }
        File(root, "probe").writeText("lexical")
        link("relative", "target/sub")
        link("chain", "relative")
        assertEquals(expected.absolutePath, access.resolvePath("chain/../probe"))
        assertArrayEquals("target".toByteArray(), access.readBytes("chain/../probe", 6))
    }

    @Test
    fun noFollowResolutionKeepsDanglingLeafEvenWithTrailingSlashAndResolvesParentLinks() {
        File(root, "target").mkdirs()
        link("parent", "/root/workspace/target")
        link("target/leaf", "/missing/file")
        assertEquals(File(root, "target/leaf").absolutePath, access.resolvePath("parent/leaf/", false))
        assertEquals(mapper.toHostFile("/missing/file").absolutePath, access.resolvePath("parent/leaf/"))
        assertEquals(File(root, "target/missing/child").absolutePath, access.resolvePath("parent/missing/child"))
    }

    @Test
    fun loopResolutionPropagatesButLeafCanBeRenamedAndDeleted() {
        link("a", "b")
        link("b", "a")
        assertThrows(FileSystemLoopException::class.java) { access.resolvePath("a") }
        assertEquals(File(root, "a").absolutePath, access.resolvePath("a/", false))
        access.rename("a/", "renamed")
        access.delete("renamed/")
        assertFalse(Files.exists(File(root, "renamed").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(File(root, "b").toPath()))
    }

    @Test
    fun deletionAndRenameWithTrailingSlashNeverTouchLinkTargets() {
        val target = File(root, "target").apply { mkdirs() }
        File(target, "keep").writeText("keep")
        link("remove", "/root/workspace/target")
        link("rename", "/root/workspace/target")
        access.deleteRecursively("remove/")
        access.rename("rename/", "renamed/")
        assertFalse(Files.isSymbolicLink(File(root, "remove").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "renamed").toPath()))
        assertEquals("keep", File(target, "keep").readText())
    }

    @Test
    fun copyAndMoveToAncestorAreRejectedBeforeOverwriteDeletesSource() {
        tree()
        assertRejectedWithoutMutation("tree/sub", "tree")
    }

    @Test
    fun copyAndMoveToAliasedDescendantAreRejectedBeforeOverwrite() {
        tree()
        link("alias", "/root/workspace/tree")
        assertRejectedWithoutMutation("tree", "alias/sub")
        assertTrue(Files.isSymbolicLink(File(root, "alias").toPath()))
    }

    @Test
    fun copyAndMoveToFinalAliasOfSourceAreRejectedBeforeDeletingTheLink() {
        tree()
        link("alias", "/root/workspace/tree")
        assertRejectedWithoutMutation("tree", "alias/")
        assertTrue(Files.isSymbolicLink(File(root, "alias").toPath()))
    }

    @Test
    fun copyAndMoveRespectLinkParentSegmentsRatherThanLexicalNormalization() {
        tree()
        link("entry", "tree/sub")
        assertRejectedWithoutMutation("tree/sub", "entry/../")
    }

    @Test
    fun copyAndMoveReplaceTargetLinkEntryWithoutTouchingItsDirectoryTarget() {
        File(root, "source").writeText("source")
        val destination = File(root, "external").apply { mkdirs() }
        File(destination, "keep").writeText("keep")
        link("copy-target", "/root/workspace/external")
        link("move-target", "/root/workspace/external")
        access.copy("source", "copy-target/", overwrite = true)
        access.move("source", "move-target/", overwrite = true)
        assertEquals("source", File(root, "copy-target").readText())
        assertEquals("source", File(root, "move-target").readText())
        assertEquals("keep", File(destination, "keep").readText())
        assertFalse(File(root, "source").exists())
    }

    @Test
    fun sourceLinkCopyReadsContentsButMovePreservesTheLinkAndItsTarget() {
        val target = File(root, "target").apply { writeText("keep") }
        link("copy-source", "/root/workspace/target")
        link("move-source", "/root/workspace/target")
        access.copy("copy-source", "copied")
        access.move("move-source/", "moved")
        assertEquals("keep", File(root, "copied").readText())
        assertFalse(Files.isSymbolicLink(File(root, "copied").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "copy-source").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "moved").toPath()))
        assertEquals("keep", target.readText())
    }

    @Test
    fun copyingDirectoryPreservesNestedDanglingAndDirectoryLinksWithoutFollowingThem() {
        File(root, "source").mkdirs()
        File(root, "external").mkdirs()
        File(root, "external/keep").writeText("keep")
        link("source/external", "/root/workspace/external")
        link("source/dangling", "/missing")
        access.copy("source", "copied")
        assertTrue(Files.isSymbolicLink(File(root, "copied/external").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "copied/dangling").toPath()))
        access.deleteRecursively("copied")
        assertEquals("keep", File(root, "external/keep").readText())
        assertTrue(Files.isSymbolicLink(File(root, "source/external").toPath()))
    }

    @Test
    fun danglingSourceLinkCanBeMovedAndDanglingTargetLinkCanBeReplaced() {
        link("dangling", "/missing")
        access.move("dangling/", "moved")
        assertEquals(File("/missing").toPath(), Files.readSymbolicLink(File(root, "moved").toPath()))
        File(root, "source").writeText("source")
        link("destination", "/also-missing")
        access.copy("source", "destination/", overwrite = true)
        assertEquals("source", File(root, "destination").readText())
    }

    @Test
    fun staleFrozenLocalMappingCannotReadBytesFromNewWorkspace() {
        val mapping = mapper.captureExecutionMapping()
        var current = true
        val execution = WorkspaceExecutionContext(
            com.aicode.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT,
            access.cacheIdentity(), access, mapping, null
        ) { if (!current) throw CancellationException("Workspace changed") }
        current = false
        val failure = kotlinx.coroutines.runBlocking {
            runCatching { kotlinx.coroutines.withContext(execution) { access.readBytes("binary", 10) } }.exceptionOrNull()
        }
        assertTrue(failure is CancellationException)
    }
}
