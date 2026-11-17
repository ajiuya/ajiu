package com.aicode.feature.agent.domain.tool.file

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.RECOVERY_READ_REQUIRED_CODE
import com.aicode.feature.agent.domain.tool.STALE_FILE_CODE
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `writeFile` 的写前版本校验：覆盖已有文件时必须基于最新内容，新建文件不校验。
 */
class WriteFileToolStaleWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()

    private fun tool() = WriteFileTool(fileAccess, WorkspaceFileLock(), versions)

    private fun context(sessionId: String, requireFreshFileRead: Boolean = false) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.absolutePath,
        language = "kotlin",
        sessionId = sessionId,
        requireFreshFileRead = requireFreshFileRead
    )

    private fun writeArgs(path: String, content: String, overwrite: Boolean = true): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "content" to JsonPrimitive(content),
        "overwrite" to JsonPrimitive(overwrite)
    )

    private fun recordCurrentVersion(sessionId: String, file: File) {
        versions.record(sessionId, fileAccess.toDisplayPath(file.path), fileAccess.fingerprint(file.path)!!)
    }

    @Test
    fun overwriteAfterExternalChange_isRejected() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)
        file.writeText("v2-changed-by-other\n")

        val result = tool().executeWithContext(writeArgs(file.path, "v3-overwrite\n"), context("s1"))

        assertTrue("过期覆盖必须被拒绝，实际=$result", result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("被拒绝的写入绝不能落盘", "v2-changed-by-other\n", file.readText())
    }

    @Test
    fun overwriteWithCurrentVersion_succeeds() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n"), context("s1"))

        assertTrue("版本一致时应正常写入，实际=$result", result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun creatingNewFile_isNotBlockedByRegistry() = runTest {
        // 登记表里有一条同路径记录，但磁盘上文件不存在（如刚被删除）：应视为新建，不拦截。
        val target = File(tmp.root, "new.kt")
        versions.record("s1", fileAccess.toDisplayPath(target.path), "sha256:stale-value")

        val result = tool().executeWithContext(writeArgs(target.path, "fresh\n"), context("s1"))

        assertTrue("新建文件不应被版本校验拦截，实际=$result", result is ToolResult.Success)
        assertEquals("fresh\n", target.readText())
    }

    @Test
    fun unreadExistingFile_isAllowedThrough() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n"), context("s-never-read"))

        assertTrue("未读过的覆盖应放行（避免打断既有流程），实际=$result", result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun overwriteFalse_stillReportsFileExists() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n", overwrite = false), context("s1"))

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_EXISTS", (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }

    @Test
    fun recovery_discardsPreviousReadAndRejectsOverwrite() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context("s1")
        )
        assertTrue(read is ToolResult.Success)
        versions.beginRecovery("s1")

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n"), context("s1", true))

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertTrue(result.message.contains("read the file again"))
        assertTrue(result.message.contains(file.path))
        assertEquals("v1\n", file.readText())
    }

    @Test
    fun recovery_requiredReadMessageUsesApplicationResources() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val localizedMessage = "Localized read requirement for ${file.path}"
        val appContext = mockk<Context> {
            every { getString(R.string.recovery_file_read_required, file.path) } returns localizedMessage
        }
        versions.beginRecovery("s1")
        val writer = WriteFileTool(fileAccess, WorkspaceFileLock(), versions, appContext)

        val result = writer.executeWithContext(writeArgs(file.path, "v2\n"), context("s1", true))

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals(localizedMessage, result.message)
        assertEquals("v1\n", file.readText())
        verify(exactly = 1) { appContext.getString(R.string.recovery_file_read_required, file.path) }
    }

    @Test
    fun recovery_afterReadAllowsConsecutiveOverwrites() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)

        val first = tool().executeWithContext(writeArgs(file.path, "v2\n"), context)
        val second = tool().executeWithContext(writeArgs(file.path, "v3\n"), context)

        assertTrue(first is ToolResult.Success)
        assertTrue(second is ToolResult.Success)
        assertEquals("v3\n", file.readText())
    }

    @Test
    fun recovery_afterReadRejectsExternalChanges() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        file.writeText("external\n")

        val result = tool().executeWithContext(writeArgs(file.path, "mine\n"), context)

        assertTrue(result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("external\n", file.readText())
    }

    @Test
    fun recovery_newFileIsAllowedButWriteRecordDoesNotAuthorizeOverwrite() = runTest {
        val file = File(tmp.root, "new.kt")
        val context = context("s1", true)
        versions.beginRecovery("s1")

        val created = tool().executeWithContext(writeArgs(file.path, "v1\n"), context)
        assertTrue(created is ToolResult.Success)
        assertFalse(versions.wasReadSinceRecovery("s1", file.path))
        val rejected = tool().executeWithContext(writeArgs(file.path, "v2\n"), context)
        assertTrue(rejected is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (rejected as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val overwritten = tool().executeWithContext(writeArgs(file.path, "v2\n"), context)
        assertTrue(overwritten is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun recovery_otherSessionsReadDoesNotAuthorizeOverwrite() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        versions.beginRecovery("s1")
        versions.beginRecovery("s2")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context("s1", true)
        )
        assertTrue(read is ToolResult.Success)

        val rejected = tool().executeWithContext(writeArgs(file.path, "s2\n"), context("s2", true))
        assertTrue(rejected is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (rejected as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
        val allowed = tool().executeWithContext(writeArgs(file.path, "s1\n"), context("s1", true))
        assertTrue(allowed is ToolResult.Success)
        assertEquals("s1\n", file.readText())
    }

    @Test
    fun recovery_missingCurrentFingerprintRejectsOverwrite() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val unavailable = object : FileAccessProvider by fileAccess {
            override fun fingerprint(path: String): String? = null
        }
        val writer = WriteFileTool(unavailable, WorkspaceFileLock(), versions)

        val result = writer.executeWithContext(writeArgs(file.path, "v2\n"), context)

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }

    @Test
    fun recovery_fingerprintExceptionRejectsOverwrite() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        val context = context("s1", true)
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        )
        assertTrue(read is ToolResult.Success)
        val unavailable = object : FileAccessProvider by fileAccess {
            override fun fingerprint(path: String): String? = throw IllegalStateException("hash unavailable")
        }
        val writer = WriteFileTool(unavailable, WorkspaceFileLock(), versions)

        val result = writer.executeWithContext(writeArgs(file.path, "v2\n"), context)

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }

    @Test
    fun recovery_fileCreatedWhileWaitingForLockCannotBeOverwritten() = runTest {
        val file = File(tmp.root, "new.kt")
        val lock = WorkspaceFileLock()
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock(file.path) { release.await() }
        }
        versions.beginRecovery("s1")
        val writer = WriteFileTool(fileAccess, lock, versions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            writer.executeWithContext(writeArgs(file.path, "mine\n"), context("s1", true))
        }
        assertFalse(pending.isCompleted)
        file.writeText("external\n")
        release.complete(Unit)
        holder.join()

        val result = pending.await()

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
        val writer = WriteFileTool(fileAccess, lock, versions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            writer.executeWithContext(writeArgs(file.path, "mine\n"), context)
        }
        assertFalse(pending.isCompleted)
        file.writeText("external\n")
        release.complete(Unit)
        holder.join()

        val result = pending.await()

        assertTrue(result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("external\n", file.readText())
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
        val writer = WriteFileTool(fileAccess, lock, versions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            writer.executeWithContext(writeArgs(file.path, "mine\n"), context)
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
    fun ordinarySession_fingerprintExceptionStillAllowsOverwrite() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)
        val unavailable = object : FileAccessProvider by fileAccess {
            override fun fingerprint(path: String): String? = throw IllegalStateException("hash unavailable")
        }
        val writer = WriteFileTool(unavailable, WorkspaceFileLock(), versions)

        val result = writer.executeWithContext(writeArgs(file.path, "v2\n"), context("s1"))

        assertTrue(result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun directExecution_stillAllowsOverwriteAfterRecoveryBegins() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        versions.beginRecovery("s1")

        val result = tool().execute(writeArgs(file.path, "v2\n"))

        assertTrue(result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun recovery_overwriteFalseStillReportsFileExists() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        versions.beginRecovery("s1")

        val result = tool().executeWithContext(
            writeArgs(file.path, "v2\n", overwrite = false), context("s1", true)
        )

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_EXISTS", (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }
}
