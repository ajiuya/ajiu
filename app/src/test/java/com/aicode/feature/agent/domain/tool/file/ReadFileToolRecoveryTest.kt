package com.aicode.feature.agent.domain.tool.file

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.RECOVERY_READ_REQUIRED_CODE
import com.aicode.feature.agent.domain.tool.STALE_FILE_CODE
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadFileToolRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()

    private fun context() = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.path,
        language = "text",
        sessionId = "s1",
        requireFreshFileRead = true
    )

    private fun writeArgs(file: File): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(file.path),
        "content" to JsonPrimitive("updated\n")
    )

    @Test
    fun partialRead_registersFullFileFingerprintAndAllowsWrite() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("first\nsecond\nthird\n") }
        versions.beginRecovery("s1")

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf(
                "path" to JsonPrimitive(file.path),
                "start_line" to JsonPrimitive(2),
                "end_line" to JsonPrimitive(2)
            ), context()
        )

        assertTrue(read is ToolResult.Success)
        assertEquals("second", ((read as ToolResult.Success).data as JsonObject)["content"]?.jsonPrimitive?.content)
        assertEquals(fileAccess.fingerprint(file.path), versions.get("s1", file.path))
        assertTrue(versions.wasReadSinceRecovery("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Success)
        assertEquals("updated\n", file.readText())
    }

    @Test
    fun partialRead_detectsChangesOutsideReturnedWindow() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("first\nsecond\nthird\n") }
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf(
                "path" to JsonPrimitive(file.path),
                "start_line" to JsonPrimitive(2),
                "end_line" to JsonPrimitive(2)
            ), context()
        )
        assertTrue(read is ToolResult.Success)
        file.writeText("external\nsecond\nthird\n")

        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )

        assertTrue(result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("external\nsecond\nthird\n", file.readText())
    }

    @Test
    fun lineLimitedRead_stillRegistersFullFileFingerprint() = runTest {
        val file = tmp.newFile("long.txt").apply { writeText((1..2001).joinToString("\n")) }
        versions.beginRecovery("s1")

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context()
        )

        assertTrue(read is ToolResult.Success)
        val data = (read as ToolResult.Success).data as JsonObject
        assertEquals(true, data["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(fileAccess.fingerprint(file.path), versions.get("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Success)
        assertEquals("updated\n", file.readText())
    }

    @Test
    fun missingBlankOrThrowingFingerprint_doesNotAuthorizeWrite() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("original\n") }
        for (failure in listOf("missing", "blank", "throw")) {
            versions.beginRecovery("s1")
            val unavailable = object : FileAccessProvider by fileAccess {
                override fun fingerprint(path: String): String? = when (failure) {
                    "missing" -> null
                    "blank" -> " "
                    else -> throw IllegalStateException("hash unavailable")
                }
            }

            val read = ReadFileTool(unavailable, versions).executeWithContext(
                mapOf("path" to JsonPrimitive(file.path)), context()
            )

            assertTrue(read is ToolResult.Success)
            assertNull(versions.get("s1", file.path))
            assertFalse(versions.wasReadSinceRecovery("s1", file.path))
            val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
                writeArgs(file), context()
            )
            assertTrue(result is ToolResult.Error)
            assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
            assertEquals("original\n", file.readText())
        }
    }

    @Test
    fun fingerprintMustBeAvailableBeforeAndAfterRead() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("original\n") }
        for (missingCall in listOf(1, 2)) {
            versions.beginRecovery("s1")
            var calls = 0
            val intermittent = object : FileAccessProvider by fileAccess {
                override fun fingerprint(path: String): String? {
                    calls++
                    return if (calls == missingCall) null else fileAccess.fingerprint(path)
                }
            }

            val read = ReadFileTool(intermittent, versions).executeWithContext(
                mapOf("path" to JsonPrimitive(file.path)), context()
            )

            assertTrue(read is ToolResult.Success)
            assertFalse(versions.wasReadSinceRecovery("s1", file.path))
            val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
                writeArgs(file), context()
            )
            assertTrue(result is ToolResult.Error)
            assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
            assertEquals("original\n", file.readText())
        }
    }

    @Test
    fun failedRead_doesNotAuthorizeWrite() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("first\nsecond\n") }
        val broken = object : FileAccessProvider by fileAccess {
            override fun readLines(path: String): Sequence<String> = sequence {
                yield("first")
                throw IllegalStateException("read interrupted")
            }
        }
        versions.beginRecovery("s1")

        val read = ReadFileTool(broken, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context()
        )

        assertTrue(read is ToolResult.Error)
        assertEquals("READ_ERROR", (read as ToolResult.Error).code)
        assertFalse(versions.wasReadSinceRecovery("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("first\nsecond\n", file.readText())
    }

    @Test
    fun fileChangedDuringRead_doesNotRegisterUnseenVersion() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("original\n") }
        val changing = object : FileAccessProvider by fileAccess {
            override fun readLines(path: String): Sequence<String> = sequence {
                yieldAll(fileAccess.readLines(path))
                file.writeText("external\n")
            }
        }
        versions.beginRecovery("s1")

        val read = ReadFileTool(changing, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context()
        )

        assertTrue(read is ToolResult.Success)
        assertEquals("original", ((read as ToolResult.Success).data as JsonObject)["content"]?.jsonPrimitive?.content)
        assertFalse(versions.wasReadSinceRecovery("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("external\n", file.readText())
    }

    @Test
    fun emptyWindowForNonemptyFile_doesNotAuthorizeWrite() = runTest {
        val file = tmp.newFile("a.txt").apply { writeText("original\n") }
        versions.beginRecovery("s1")

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path), "start_line" to JsonPrimitive(2)), context()
        )

        assertTrue(read is ToolResult.Success)
        assertFalse(versions.wasReadSinceRecovery("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("original\n", file.readText())
    }

    @Test
    fun emptyFileRead_allowsOverwrite() = runTest {
        val file = tmp.newFile("empty.txt")
        versions.beginRecovery("s1")

        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context()
        )

        assertTrue(read is ToolResult.Success)
        assertTrue(versions.wasReadSinceRecovery("s1", file.path))
        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(file), context()
        )
        assertTrue(result is ToolResult.Success)
        assertEquals("updated\n", file.readText())
    }

    @Test
    fun readingAnotherFile_doesNotAuthorizeWrite() = runTest {
        val readFile = tmp.newFile("a.txt").apply { writeText("read\n") }
        val unreadFile = tmp.newFile("b.txt").apply { writeText("unread\n") }
        versions.beginRecovery("s1")
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(readFile.path)), context()
        )
        assertTrue(read is ToolResult.Success)

        val result = WriteFileTool(fileAccess, WorkspaceFileLock(), versions).executeWithContext(
            writeArgs(unreadFile), context()
        )

        assertTrue(result is ToolResult.Error)
        assertEquals(RECOVERY_READ_REQUIRED_CODE, (result as ToolResult.Error).code)
        assertEquals("unread\n", unreadFile.readText())
    }
}
