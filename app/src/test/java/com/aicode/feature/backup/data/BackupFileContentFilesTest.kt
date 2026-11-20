package com.aicode.feature.backup.data

import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.TransferStage
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class BackupFileContentFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun host(root: File): TransferBackend = HostTransferBackend({ path, _ ->
        if (path.isEmpty()) root else File(root, path)
    }, {})

    private fun restore(source: TransferReader, target: File, overwrite: Boolean = false, check: () -> Unit = {}): BackupFileRestoreProgress {
        val tree = BackupFileContentFiles.snapshot(source, {})!!
        return BackupFileRestoreProgress().also { progress ->
            BackupFileContentFiles.restore(source, tree, { path -> host(if (path.isEmpty()) target else File(target, path)) }, overwrite, progress, check)
        }
    }

    @Test
    fun snapshotPreservesRawMemoryBytesAndOriginalNames() {
        val source = temporary.newFolder()
        val bytes = "---\r\nname: Logical name\r\nunknown: preserved\r\n---\r\nBody\r\n".toByteArray()
        File(source, "original name.md").writeBytes(bytes)
        val tree = BackupFileContentFiles.snapshot(host(source), {})!!
        val stage = temporary.newFolder()
        BackupFileContentFiles.copyToDirectory(host(source), tree, stage) {}
        assertTrue(bytes.contentEquals(File(stage, "original name.md").readBytes()))
        assertEquals(listOf("original name.md"), tree.files.map { it.path })
    }

    @Test
    fun skillSnapshotIncludesAssetsScriptsAndEmptyDirectoriesButNotDependencies() {
        val source = temporary.newFolder()
        File(source, "demo/scripts").mkdirs()
        File(source, "demo/references").mkdirs()
        File(source, "demo/assets/empty").mkdirs()
        File(source, "demo/SKILL.md").writeText("name: demo")
        File(source, "demo/scripts/run.py").writeText("print('test')")
        File(source, "demo/references/help.txt").writeText("reference")
        File(source, "demo/node_modules/package").mkdirs()
        File(source, "demo/node_modules/package/index.js").writeText("runtime dependency")
        File(source, "demo/.venv/lib").mkdirs()
        File(source, "demo/.venv/lib/runtime.py").writeText("runtime dependency")
        val tree = BackupFileContentFiles.snapshot(host(source), {}, BackupFileContentFiles::includeSkill)!!
        assertEquals(setOf("demo/SKILL.md", "demo/scripts/run.py", "demo/references/help.txt"), tree.files.map { it.path }.toSet())
        assertTrue(tree.nodes.any { it.path == "demo/assets/empty" && it.stat.kind == TransferKind.DIRECTORY })
        assertFalse(tree.nodes.any { "node_modules" in it.path || ".venv" in it.path })
    }

    @Test
    fun linksAndOversizeFilesAreRejectedWithoutFollowingThem() {
        val source = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("private") }
        Files.createSymbolicLink(File(source, "link.md").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { BackupFileContentFiles.snapshot(host(source), {}) }
        assertEquals("private", outside.readText())
        val oversized = mockk<TransferReader> {
            io.mockk.every { stat("") } returns com.aicode.feature.workspace.domain.TransferStat(TransferKind.FILE,
                BackupContentArchive.MAX_FILE_BYTES + 1, "fixture")
        }
        assertThrows(IOException::class.java) { BackupFileContentFiles.snapshot(oversized, {}) }
        io.mockk.verify(exactly = 0) { oversized.openRead(any()) }
    }

    @Test
    fun missingIsEmptyButReadFailureIsNeverReportedAsEmpty() {
        val parent = temporary.newFolder()
        assertEquals(null, BackupFileContentFiles.snapshot(host(File(parent, "missing")), {}))
        val reader = object : TransferReader by host(parent) {
            override fun list(relative: String): List<String> = throw IOException("permission denied")
        }
        assertThrows(IOException::class.java) { BackupFileContentFiles.snapshot(reader, {}) }
    }

    @Test
    fun defaultRestoreSkipsExistingFilesAndMergesOnlyArchivedEntries() {
        val source = temporary.newFolder()
        File(source, "same.md").writeText("from backup")
        File(source, "new.md").writeText("new")
        val target = temporary.newFolder()
        File(target, "same.md").writeText("existing")
        File(target, "unarchived.md").writeText("keep")
        val result = restore(host(source), target)
        assertEquals(1, result.restored)
        assertEquals(1, result.skipped)
        assertEquals(1, result.conflicts)
        assertEquals("existing", File(target, "same.md").readText())
        assertEquals("new", File(target, "new.md").readText())
        assertEquals("keep", File(target, "unarchived.md").readText())
    }

    @Test
    fun overwriteReplacesOnlyMatchingFilesWithoutDeletingUnarchivedChildren() {
        val source = temporary.newFolder()
        File(source, "directory").mkdir()
        File(source, "directory/same").writeText("replacement")
        val target = temporary.newFolder()
        File(target, "directory").mkdir()
        File(target, "directory/same").writeText("old")
        File(target, "directory/extra").writeText("keep")
        val result = restore(host(source), target, overwrite = true)
        assertEquals(1, result.restored)
        assertEquals("replacement", File(target, "directory/same").readText())
        assertEquals("keep", File(target, "directory/extra").readText())
    }

    @Test
    fun fileDirectoryConflictsDoNotDeleteExistingTreesEvenWithOverwrite() {
        val source = temporary.newFolder()
        File(source, "blocked").writeText("file")
        File(source, "nested").mkdir()
        File(source, "nested/entry").writeText("nested")
        val target = temporary.newFolder()
        File(target, "blocked").mkdir()
        File(target, "blocked/extra").writeText("keep")
        File(target, "nested").writeText("keep file")
        val result = restore(host(source), target, overwrite = true)
        assertEquals(0, result.restored)
        assertEquals(2, result.skipped)
        assertTrue(result.conflicts >= 2)
        assertEquals("keep", File(target, "blocked/extra").readText())
        assertEquals("keep file", File(target, "nested").readText())
    }

    @Test
    fun executableBitIsAppliedInsideStagingBeforeCommit() {
        val source = temporary.newFolder()
        File(source, "run.sh").apply { writeText("exit 0"); assertTrue(setExecutable(true, true)) }
        val target = temporary.newFolder()
        val result = restore(host(source), target)
        assertEquals(1, result.restored)
        assertTrue(File(target, "run.sh").canExecute())
        assertTrue(target.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
    }

    @Test
    fun changedSourceHashCannotPublishAPartialFile() {
        val source = temporary.newFolder()
        val file = File(source, "entry").apply { writeText("original") }
        val backend = host(source)
        val tree = BackupFileContentFiles.snapshot(backend, {})!!
        val reader = object : TransferReader by backend {
            override fun openRead(relative: String): InputStream = "tampered".byteInputStream()
        }
        val target = temporary.newFolder()
        val progress = BackupFileRestoreProgress()
        assertThrows(IOException::class.java) {
            BackupFileContentFiles.restore(reader, tree, { path -> host(if (path.isEmpty()) target else File(target, path)) }, false, progress, {})
        }
        assertFalse(File(target, file.name).exists())
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test
    fun concurrentTargetEditBeforeCommitIsPreserved() {
        val source = temporary.newFolder()
        File(source, "entry").writeText("from backup")
        val target = temporary.newFolder()
        val entry = File(target, "entry").apply { writeText("existing") }
        val tree = BackupFileContentFiles.snapshot(host(source), {})!!
        val progress = BackupFileRestoreProgress()
        assertThrows(IOException::class.java) {
            BackupFileContentFiles.restore(host(source), tree, { path ->
                val original = host(if (path.isEmpty()) target else File(target, path))
                if (path.isEmpty()) original else object : TransferBackend by original {
                    override fun createStage(kind: TransferKind): TransferStage {
                        val stage = original.createStage(kind)
                        return object : TransferStage by stage {
                            override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) {
                                entry.writeText("concurrent user edit")
                                stage.commit(overwrite, checkBeforeCommit)
                            }
                        }
                    }
                }
            }, true, progress, {})
        }
        assertEquals("concurrent user edit", entry.readText())
        assertEquals(0, progress.restored)
        assertTrue(target.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
    }

    @Test
    fun cancelledWriteDoesNotPublishOrLeaveStaging() {
        val source = temporary.newFolder()
        File(source, "entry").writeText("from backup")
        val target = temporary.newFolder()
        val tree = BackupFileContentFiles.snapshot(host(source), {})!!
        val progress = BackupFileRestoreProgress()
        assertThrows(CancellationException::class.java) {
            BackupFileContentFiles.restore(host(source), tree, { path ->
                val original = host(if (path.isEmpty()) target else File(target, path))
                object : TransferBackend by original {
                    override fun createStage(kind: TransferKind): TransferStage {
                        val stage = original.createStage(kind)
                        return object : TransferStage by stage {
                            override fun writeFile(relative: String, writer: (OutputStream) -> Unit) {
                                stage.writeFile(relative) { output ->
                                    output.write(1)
                                    throw CancellationException("fixture cancellation")
                                }
                            }
                        }
                    }
                }
            }, false, progress, {})
        }
        assertTrue(target.listFiles()!!.isEmpty())
        assertEquals(0, progress.restored)
    }

    @Test
    fun unsafeRelativeNamesAreRejected() {
        for (path in listOf("../escape", "/absolute", "dir/../escape", "dir//entry", "dir\\escape")) {
            assertFalse(BackupFileContentFiles.validRelative(path))
        }
        assertTrue(BackupFileContentFiles.validRelative("normal/scripts/run.py"))
    }
}
