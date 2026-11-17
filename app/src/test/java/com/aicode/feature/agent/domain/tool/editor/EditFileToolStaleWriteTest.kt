package com.aicode.feature.agent.domain.tool.editor

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.RECOVERY_READ_REQUIRED_CODE
import com.aicode.feature.agent.domain.tool.STALE_FILE_CODE
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.agent.domain.tool.file.ReadFileTool
import com.aicode.feature.agent.domain.tool.file.WriteFileTool
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `editFile` 的写前版本校验：模拟「本会话登记版本后，文件被并发改动」这一核心场景。
 *
 * 这些用例守护的是**静默丢更新**：没有校验时，过期的那次编辑会直接覆盖别人的改动，
 * 且返回成功——正是本次改动要消灭的行为。
 */
class EditFileToolStaleWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()

    private fun tool() = EditFileTool(fileAccess, WorkspaceFileLock(), versions)

    private fun context(sessionId: String, requireFreshFileRead: Boolean = false) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.absolutePath,
        language = "kotlin",
        sessionId = sessionId,
        requireFreshFileRead = requireFreshFileRead
    )

    private fun editArgs(path: String, old: String, new: String): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "edits" to JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "old_string" to JsonPrimitive(old),
                        "new_string" to JsonPrimitive(new)
                    )
                )
            )
        )
    )

    /** 模拟「本会话读过这个文件」：登记当前内容的指纹。 */
    private fun recordCurrentVersion(sessionId: String, file: File) {
        versions.record(sessionId, fileAccess.toDisplayPath(file.path), fileAccess.fingerprint(file.path)!!)
    }

    @Test
    fun staleWrite_isRejectedAndFileUntouched() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val session = "s1"
        recordCurrentVersion(session, file)

        // 另一个会话（或用户）在此期间改了同一个文件
        file.writeText("val a = 1\nval b = 2\n")

        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 100"),
            context(session)
        )

        assertTrue("过期写必须被拒绝，实际=$result", result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertTrue(
            "错误信息要给出自愈路径（提示重新读取）",
            result.message.contains("重新读取")
        )
        assertEquals(
            "被拒绝的写入绝不能落盘",
            "val a = 1\nval b = 2\n",
            file.readText()
        )
    }

    @Test
    fun freshWrite_succeeds() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val session = "s1"
        recordCurrentVersion(session, file)

        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context(session)
        )

        assertTrue("版本未变时应正常写入，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun neverReadFile_isAllowedThrough() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }

        // 没有为该会话登记过任何版本：无从判断过期，放行（避免打断正常流程）
        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context("s-fresh")
        )

        assertTrue("未读过的文件应放行，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun otherSessionStale_doesNotAffectThisSession() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)
        // s1 写入后登记新版本；s2 从未读过，因此不受 s1 的影响
        tool().executeWithContext(editArgs(file.path, "v1", "v2"), context("s1"))
        recordCurrentVersion("s1", file)

        val result = tool().executeWithContext(editArgs(file.path, "v2", "v3"), context("s1"))
        assertTrue("同一会话连续编辑不应被自己判为冲突，实际=$result", result is ToolResult.Success)
        assertEquals("v3\n", file.readText())
    }

    @Test
    fun writeRequiresRead_afterExternalChangeIsRefused() = runTest {
        val file = tmp.newFile("b.kt").apply { writeText("original\n") }
        recordCurrentVersion("s1", file)
        file.writeText("changed-by-someone-else\n")

        val result = tool().executeWithContext(
            editArgs(file.path, "original", "mine"),
            context("s1")
        )

        assertTrue(result is ToolResult.Error)
        assertEquals("changed-by-someone-else\n", file.readText())
    }

    @Test
    fun fingerprintUnavailable_doesNotBlockEdit() = runTest {
        // 远端文件超过读取上限时 fingerprint 会抛异常。版本校验是辅助保护，
        // 它自己算不出指纹时**必须放行**，否则用户会因为"算不出指纹"而改不了文件。
        val file = tmp.newFile("big.kt").apply { writeText("val a = 1\n") }
        val throwing = object : FileAccessProvider by fileAccess {
            override fun fingerprint(path: String): String? =
                throw IllegalStateException("模拟：文件过大，无法计算指纹")
        }
        val guardedTool = EditFileTool(throwing, WorkspaceFileLock(), versions)
        // 先登记一条版本，确保校验逻辑真的会走到 fingerprint 调用
        versions.record("s1", fileAccess.toDisplayPath(file.path), "sha256:whatever")

        val result = guardedTool.executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context("s1")
        )

        assertTrue("算不出指纹时必须放行，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun recovery_previousReadDoesNotAuthorizeEdit() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context("s1")
        )
        assertTrue(read is ToolResult.Success)
        versions.beginRecovery("s1")

        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"), context("s1", true)
        )

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertTrue(result.message.contains("read the file again"))
        assertTrue(result.message.contains(file.path))
        assertEquals("val a = 1\n", file.readText())
    }

    @Test
    fun recovery_requiredReadMessageUsesApplicationResources() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val localizedMessage = "Localized read requirement for ${file.path}"
        val appContext = mockk<Context> {
            every { getString(R.string.recovery_file_read_required, file.path) } returns localizedMessage
        }
        versions.beginRecovery("s1")
        val editor = EditFileTool(fileAccess, WorkspaceFileLock(), versions, appContext)

        val result = editor.executeWithContext(editArgs(file.path, "v1", "v2"), context("s1", true))

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals(localizedMessage, result.message)
        assertEquals("v1\n", file.readText())
        verify(exactly = 1) { appContext.getString(R.string.recovery_file_read_required, file.path) }
    }

    @Test
    fun recovery_readAllowsConsecutiveEdits() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)

        val first = tool().executeWithContext(editArgs(file.path, "val a = 1", "val a = 2"), context)
        val second = tool().executeWithContext(editArgs(file.path, "val a = 2", "val a = 3"), context)

        assertTrue(first is ToolResult.Success)
        assertTrue(second is ToolResult.Success)
        assertEquals("val a = 3\n", file.readText())
    }

    @Test
    fun recovery_readThenExternalChangeRejectsEvenMatchingEdit() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        file.appendText("val external = 2\n")

        val result = tool().executeWithContext(editArgs(file.path, "val a = 1", "val a = 3"), context)

        assertTrue(result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("val a = 1\nval external = 2\n", file.readText())
    }

    @Test
    fun recovery_otherSessionsReadDoesNotAuthorizeEdit() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        versions.beginRecovery("s1")
        versions.beginRecovery("s2")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context("s1", true)
        )
        assertTrue(read is ToolResult.Success)

        val rejected = tool().executeWithContext(editArgs(file.path, "v1", "s2"), context("s2", true))
        assertTrue(rejected is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (rejected as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
        val allowed = tool().executeWithContext(editArgs(file.path, "v1", "s1"), context("s1", true))
        assertTrue(allowed is ToolResult.Success)
        assertEquals("s1\n", file.readText())
    }

    @Test
    fun recovery_createdFileMustBeReadBeforeEditing() = runTest {
        val file = File(tmp.root, "new.kt")
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val created = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path), "content" to JsonPrimitive("v1\n")), context
        )
        assertTrue(created is ToolResult.Success)

        val rejected = tool().executeWithContext(editArgs(file.path, "v1", "v2"), context)
        assertTrue(rejected is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (rejected as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val allowed = tool().executeWithContext(editArgs(file.path, "v1", "v2"), context)
        assertTrue(allowed is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun recovery_missingOrThrowingCurrentFingerprintRejectsEdit() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)

        for (throws in listOf(false, true)) {
            val unavailable = object : FileAccessProvider by fileAccess {
                override fun fingerprint(path: String): String? {
                    if (throws) throw IllegalStateException("hash unavailable")
                    return null
                }
            }
            val editor = EditFileTool(unavailable, WorkspaceFileLock(), versions)
            val result = editor.executeWithContext(editArgs(file.path, "v1", "v2"), context)

            assertTrue(result is ToolResult.Error)
            assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
            assertEquals("v1\n", file.readText())
        }
    }

    @Test
    fun recovery_externalRecordCannotReplaceRead() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        file.writeText("external\n")
        versions.recordExternal(file.path, fileAccess.fingerprint(file.path)!!)

        val result = tool().executeWithContext(editArgs(file.path, "external", "mine"), context)

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("external\n", file.readText())
    }

    @Test
    fun recovery_fileChangedWhileWaitingForLockIsRejected() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val lock = WorkspaceFileLock()
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock(file.path) { release.await() }
        }
        val editor = EditFileTool(fileAccess, lock, versions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            editor.executeWithContext(editArgs(file.path, "v1", "mine"), context)
        }
        assertFalse(pending.isCompleted)
        file.appendText("external\n")
        release.complete(Unit)
        holder.join()

        val result = pending.await()

        assertTrue(result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("v1\nexternal\n", file.readText())
    }

    @Test
    fun recovery_readStateClearedWhileWaitingForLockIsRechecked() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val lock = WorkspaceFileLock()
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock(file.path) { release.await() }
        }
        val editor = EditFileTool(fileAccess, lock, versions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            editor.executeWithContext(editArgs(file.path, "v1", "mine"), context)
        }
        assertFalse(pending.isCompleted)
        versions.beginRecovery("s1")
        release.complete(Unit)
        holder.join()

        val result = pending.await()

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }

    @Test
    fun directExecution_stillAllowsEditAfterRecoveryBegins() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        versions.beginRecovery("s1")

        val result = tool().execute(editArgs(file.path, "v1", "v2"))

        assertTrue(result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun recovery_missingFileStillReportsFileNotFound() = runTest {
        val file = File(tmp.root, "missing.kt")
        versions.beginRecovery("s1")

        val result = tool().executeWithContext(editArgs(file.path, "v1", "v2"), context("s1", true))

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_NOT_FOUND", (result as ToolResult.Error).code)
        assertFalse(file.exists())
    }
}
