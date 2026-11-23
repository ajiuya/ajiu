package com.aicode.feature.terminal.data.repository

import android.app.Application
import com.aicode.feature.terminal.domain.MAX_TERMINAL_OUTPUT_CHARS
import com.aicode.feature.terminal.domain.MAX_TERMINAL_TASK_TITLE_CHARS
import com.aicode.feature.terminal.domain.TERMINAL_HISTORY_CORRUPT_ERROR
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import com.aicode.feature.terminal.domain.boundTerminalOutput
import com.aicode.feature.terminal.domain.takeTerminalChars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AtomicTerminalTaskHistoryFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TerminalTaskScope("profile", "a".repeat(64), "/rootfs", "1:2", "/workspace", "1:3")

    @Test
    fun recordRoundTripsEscapedOutputWithNoCommandOrAuthorizationFields() {
        val root = temporary.newFolder("history").canonicalFile
        val store = AtomicTerminalTaskHistoryFiles(root)
        val record = record().copy(output = "\"quoted\"\n\tunicode 中文\\end")
        store.write(record)
        assertEquals(record, store.read(record.historyId))
        assertEquals(listOf(record.historyId), store.ids())
        val json = File(root, "${record.historyId}.json").readText()
        assertFalse(json.contains("\"command\""))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("authorization"))
        assertFalse(File(root, "${record.historyId}.json.bak").exists())
    }

    @Test
    fun truncatingAcrossEmojiAtBothOutputBoundariesRoundTripsThroughAtomicUtf8() {
        val store = AtomicTerminalTaskHistoryFiles(temporary.newFolder("emoji-output").canonicalFile)
        val half = MAX_TERMINAL_OUTPUT_CHARS / 2
        val head = "head😀" + "h".repeat(half - "head😀".length - 1)
        val tail = "t".repeat(half - "😀tail".length - 1) + "😀tail"
        val source = head + "😀" + "m".repeat(MAX_TERMINAL_OUTPUT_CHARS) + "😀" + tail
        val bounded = boundTerminalOutput(source)
        assertTrue(bounded.startsWith(head))
        assertTrue(bounded.endsWith(tail))
        assertTrue(bounded.contains("truncated"))
        assertTrue(bounded.length <= MAX_TERMINAL_OUTPUT_CHARS + 64)
        assertEquals(bounded, bounded.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        val record = record().copy(output = bounded)
        store.write(record)
        assertEquals(record, store.read(record.historyId))
    }

    @Test
    fun titleCutAcrossThe256CharEmojiBoundaryRoundTripsThroughAtomicUtf8() {
        val store = AtomicTerminalTaskHistoryFiles(temporary.newFolder("emoji-title").canonicalFile)
        val prefix = "😀" + "t".repeat(MAX_TERMINAL_TASK_TITLE_CHARS - 3)
        val title = (prefix + "😀tail").takeTerminalChars(MAX_TERMINAL_TASK_TITLE_CHARS)
        assertEquals(prefix, title)
        assertEquals(MAX_TERMINAL_TASK_TITLE_CHARS - 1, title.length)
        assertEquals(title, title.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        val record = record().copy(title = title)
        store.write(record)
        assertEquals(record, store.read(record.historyId))
    }

    @Test
    fun backupRecoveryKeepsACompletedResult() = runBlocking {
        val root = temporary.newFolder("history").canonicalFile
        val store = AtomicTerminalTaskHistoryFiles(root)
        val record = record().copy(status = TerminalTaskStatus.FINISHED, exitCode = 19, finishedAt = 2, updatedAt = 2)
        store.write(record)
        val base = File(root, "${record.historyId}.json")
        assertTrue(base.renameTo(File(base.path + ".bak")))
        base.writeText("interrupted write")
        val loaded = TerminalTaskHistoryRepository(store, Dispatchers.Unconfined).load()
        assertEquals(listOf(record), loaded.records)
        assertTrue(base.exists())
        assertFalse(File(base.path + ".bak").exists())
    }

    @Test
    fun corruptJsonAndUnrecognizedFilesDoNotBecomeEmptySuccess() = runBlocking {
        val root = temporary.newFolder("history").canonicalFile
        File(root, "${UUID.randomUUID()}.json").writeText("{not valid json")
        val loaded = TerminalTaskHistoryRepository(AtomicTerminalTaskHistoryFiles(root), Dispatchers.Unconfined).load()
        assertEquals(TERMINAL_HISTORY_CORRUPT_ERROR, loaded.error)
        val other = temporary.newFolder("bad-names").canonicalFile
        File(other, "not-a-uuid.json").writeText("{}")
        assertEquals(TERMINAL_HISTORY_CORRUPT_ERROR,
            TerminalTaskHistoryRepository(AtomicTerminalTaskHistoryFiles(other), Dispatchers.Unconfined).load().error)
    }

    @Test
    fun symbolicLinkRecordsAreRejectedWithoutReadingTheirTargets() = runBlocking {
        val root = temporary.newFolder("history").canonicalFile
        val target = temporary.newFile("outside.json").apply { writeText("must stay unchanged") }
        Files.createSymbolicLink(File(root, "${UUID.randomUUID()}.json").toPath(), target.toPath())
        assertNotNull(TerminalTaskHistoryRepository(AtomicTerminalTaskHistoryFiles(root), Dispatchers.Unconfined).load().error)
        assertEquals("must stay unchanged", target.readText())
    }

    @Test
    fun injectedRepositoryUsesNoBackupInsteadOfFilesOrWorkspace() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = TerminalTaskHistoryRepository(context)
        val record = record()
        repository.create(record)
        assertTrue(File(context.noBackupFilesDir, "terminal-task-history/${record.historyId}.json").isFile)
        assertFalse(File(context.filesDir, "terminal-task-history/${record.historyId}.json").exists())
    }

    private fun record(): TerminalTaskRecord {
        val id = UUID.randomUUID().toString()
        return TerminalTaskRecord(id, "term-$id", "Build", "session", scope, 1, 1)
    }
}
