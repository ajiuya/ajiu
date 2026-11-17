package com.aicode.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room 官方迁移测试（Robolectric，JVM 上跑真实的 Android SQLite）。
 *
 * 本容器（Android 宿主上的 PRoot）JVM 加载不了 Robolectric 的 conscrypt native 库
 * （UnsatisfiedLinkError，java.library.path 指向 /data/app、/system 等 Android 路径），
 * 故用 [guardEnvironment] 在非标准 Linux 环境跳过——跳过不算失败；
 * CI（ubuntu x86_64）与普通开发机上真实执行。
 *
 * 历史版本（8~49）的 schema json 当年未导出，[MigrationTestHelper] 只能覆盖
 * 「当前版本及以后」的路径；历史路径的连续/无重复由 [MigrationFilesParseTest] 与
 * scripts/check_migrations.py 双向兜底。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        // 打开即触发 Room 的 identity/schema 校验，entity 不匹配会抛异常
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * 非正常降级会留下「user_version 落后于 migration_history」的库：旧版的
     * fallbackToDestructiveMigration 只 DROP Room 实体表再按旧 schema 重建（migration_history
     * 不在实体表之列因而保留），并把 user_version 置回旧值。升级时 Room 会把整条迁移链重放，
     * 历史表写入必须幂等，否则抛 SQLiteConstraintException:
     * UNIQUE constraint failed: migration_history.version。
     */
    @Test
    fun replaying_migrations_with_prerecorded_history_rows() {
        helper.createDatabase(dbName, 50).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, script_name TEXT, executed_at INTEGER)"
            )
            for (version in 51..AgentDatabase.SCHEMA_VERSION) {
                execSQL(
                    "INSERT INTO migration_history (version, script_name, executed_at) " +
                            "VALUES ($version, 'prerecorded', 0)"
                )
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val columns = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("PRAGMA table_info(`ai_providers`)")
            .use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(1) }
        db.close()

        assertTrue(
            "重放迁移应补回 53/54 加入的两列，实际列：$columns",
            columns.containsAll(listOf("scriptParams", "customHeaders"))
        )
    }

    /**
     * 未来新增迁移（51+）时把 @AutoMigration/新迁移加进来后在此补升级用例：
     * createDatabase(50) → 跑迁移数组 → 断言新列/新表存在。
     */
    @Test
    fun migrate_63_to_64_removesSessionModesAndPreservesSessionData() {
        helper.createDatabase(dbName, 63).apply {
            execSQL(
                "INSERT INTO chat_sessions (" +
                    "id, title, createdAt, updatedAt, workspacePath, mode, modeBeforePlan, " +
                    "goal, goalStatus, goalTurnCount, goalStartInputTokens, goalStartOutputTokens, " +
                    "reasoningEffort, totalInputTokens, totalOutputTokens, lastInputTokens, isPinned" +
                    ") VALUES (" +
                    "'session-1', 'Keep data', 1, 2, '~/workspace', 'AUTO', 'PLAN', " +
                    "'Finish tests', 'ACTIVE', 3, 10, 20, 'MEDIUM', 100, 200, 30, 0)"
            )
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val columns = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("PRAGMA table_info(`chat_sessions`)")
            .use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(1) }
        var values: List<String>? = null
        db.openHelper.writableDatabase
            .query("SELECT title, goal, goalStatus, goalTurnCount FROM chat_sessions WHERE id = 'session-1'")
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    values = listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3).toString())
                }
            }
        db.close()

        assertTrue("模式列应移除，实际列：$columns", "mode" !in columns && "modeBeforePlan" !in columns)
        assertEquals(listOf("Keep data", "Finish tests", "ACTIVE", "3"), values)
    }

    @Test
    fun migrate_64_to_65_preservesMessageDataAndAddsUnknownContextUsage() {
        var originalColumns: List<String> = emptyList()
        var originalValues: List<String?> = emptyList()
        helper.createDatabase(dbName, 64).apply {
            execSQL(
                "INSERT INTO agent_messages (" +
                    "id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs, " +
                    "isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, " +
                    "isCompactionMarker, inputTokens, outputTokens, thinkingBlocksJson, cachedInputTokens, " +
                    "isContextExcluded, compactedBySummaryId) VALUES (" +
                    "'message-old', 'session-1', 'ASSISTANT', 'Keep message', 123, '[]', 'call-1', 'readFile', '{}', " +
                    "0, 'reasoning', 'signature', '[]', 1, 0, 0, 321, 54, '[]', 13, 0, 'summary-1')"
            )
            query("SELECT * FROM agent_messages WHERE id = 'message-old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                originalColumns = cursor.columnNames.toList()
                originalValues = (0 until cursor.columnCount).map { cursor.getString(it) }
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val columns = linkedMapOf<String, List<String?>>()
            sqlite.query("PRAGMA table_info(`agent_messages`)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = listOf(cursor.getString(2), cursor.getInt(3).toString(), cursor.getString(4))
                }
            }
            assertEquals(
                originalColumns + listOf(
                    "contextInputTokens", "contextInputBudget", "contextEstimated", "contextBreakdownJson", "toolActionTitle",
                    "toolStartedAtMs", "toolDurationMs", "turnDurationMs", "modelInputSnapshotJson"
                ),
                columns.keys.toList()
            )
            assertEquals(listOf("INTEGER", "0", null), columns["contextInputTokens"])
            assertEquals(listOf("INTEGER", "0", null), columns["contextInputBudget"])
            assertEquals(listOf("INTEGER", "1", "1"), columns["contextEstimated"])

            val oldColumnsSql = originalColumns.joinToString(", ") { "`$it`" }
            sqlite.query("SELECT $oldColumnsSql FROM agent_messages WHERE id = 'message-old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(originalValues, (0 until cursor.columnCount).map { cursor.getString(it) })
            }
            val copiedColumnsSql = originalColumns.joinToString(", ") {
                if (it == "id") "'message-new'" else "`$it`"
            }
            sqlite.execSQL(
                "INSERT INTO agent_messages ($oldColumnsSql) " +
                    "SELECT $copiedColumnsSql FROM agent_messages WHERE id = 'message-old'"
            )
            sqlite.query("SELECT contextInputTokens, contextInputBudget, contextEstimated FROM agent_messages ORDER BY id").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    assertTrue(cursor.isNull(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals(1, cursor.getInt(2))
                    count++
                }
                assertEquals(2, count)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_65_to_66_preservesMessageDataAndAddsNullableContextBreakdown() {
        var originalColumns: List<String> = emptyList()
        var originalValues: List<String?> = emptyList()
        helper.createDatabase(dbName, 65).apply {
            execSQL(
                "INSERT INTO agent_messages (" +
                    "id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs, " +
                    "isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, " +
                    "isCompactionMarker, inputTokens, outputTokens, thinkingBlocksJson, cachedInputTokens, " +
                    "isContextExcluded, compactedBySummaryId, contextInputTokens, contextInputBudget, contextEstimated) VALUES (" +
                    "'message-old', 'session-1', 'ASSISTANT', 'Keep message', 123, '[]', 'call-1', 'readFile', '{}', " +
                    "0, 'reasoning', 'signature', '[]', 1, 0, 0, 321, 54, '[]', 13, 0, 'summary-1', 12345, 100000, 0)"
            )
            query("SELECT * FROM agent_messages WHERE id = 'message-old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                originalColumns = cursor.columnNames.toList()
                originalValues = (0 until cursor.columnCount).map { cursor.getString(it) }
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val columns = linkedMapOf<String, List<String?>>()
            sqlite.query("PRAGMA table_info(`agent_messages`)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = listOf(cursor.getString(2), cursor.getInt(3).toString(), cursor.getString(4))
                }
            }
            assertEquals(
                originalColumns + listOf("contextBreakdownJson", "toolActionTitle", "toolStartedAtMs", "toolDurationMs", "turnDurationMs", "modelInputSnapshotJson"),
                columns.keys.toList()
            )
            assertEquals(listOf("TEXT", "0", null), columns["contextBreakdownJson"])
            val oldColumnsSql = originalColumns.joinToString(", ") { "`$it`" }
            sqlite.query("SELECT $oldColumnsSql, contextBreakdownJson FROM agent_messages WHERE id = 'message-old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(originalValues, originalColumns.indices.map { cursor.getString(it) })
                assertTrue(cursor.isNull(originalColumns.size))
            }
            val copiedColumnsSql = originalColumns.joinToString(", ") {
                if (it == "id") "'message-new'" else "`$it`"
            }
            sqlite.execSQL(
                "INSERT INTO agent_messages ($oldColumnsSql) " +
                    "SELECT $copiedColumnsSql FROM agent_messages WHERE id = 'message-old'"
            )
            sqlite.query("SELECT contextBreakdownJson FROM agent_messages WHERE id = 'message-new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            val breakdownJson = """{"systemTokens":100,"chatTokens":200}"""
            sqlite.execSQL("UPDATE agent_messages SET contextBreakdownJson = ? WHERE id = 'message-new'", arrayOf(breakdownJson))
            sqlite.query("SELECT contextBreakdownJson, contextInputTokens, contextInputBudget, contextEstimated FROM agent_messages WHERE id = 'message-new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(breakdownJson, cursor.getString(0))
                assertEquals(12_345, cursor.getInt(1))
                assertEquals(100_000, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_66_to_67_preservesAllMessageDataAndAddsNullableToolActionTitle() {
        var originalColumns: List<String> = emptyList()
        var originalValues: List<String?> = emptyList()
        helper.createDatabase(dbName, 66).apply {
            execSQL(
                "INSERT INTO agent_messages (" +
                    "id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs, " +
                    "isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, " +
                    "isCompactionMarker, inputTokens, outputTokens, thinkingBlocksJson, cachedInputTokens, " +
                    "isContextExcluded, compactedBySummaryId, contextInputTokens, contextInputBudget, " +
                    "contextEstimated, contextBreakdownJson) VALUES (" +
                    "'tool_call', 'session-1', 'TOOL', 'Keep result', 123, '[]', 'call', 'readFile', '{}', " +
                    "1, 'reasoning', 'signature', '[]', 1, 0, 0, 321, 54, '[]', 13, 0, 'summary-1', " +
                    "12345, 100000, 0, '{\"chatTokens\":12345}')"
            )
            query("SELECT * FROM agent_messages WHERE id = 'tool_call'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                originalColumns = cursor.columnNames.toList()
                originalValues = (0 until cursor.columnCount).map { cursor.getString(it) }
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val columns = linkedMapOf<String, List<String?>>()
            sqlite.query("PRAGMA table_info(`agent_messages`)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = listOf(cursor.getString(2), cursor.getInt(3).toString(), cursor.getString(4))
                }
            }
            assertEquals(
                originalColumns + listOf("toolActionTitle", "toolStartedAtMs", "toolDurationMs", "turnDurationMs", "modelInputSnapshotJson"),
                columns.keys.toList()
            )
            assertEquals(listOf("TEXT", "0", null), columns["toolActionTitle"])
            val oldColumnsSql = originalColumns.joinToString(", ") { "`$it`" }
            sqlite.query("SELECT $oldColumnsSql, toolActionTitle FROM agent_messages WHERE id = 'tool_call'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(originalValues, originalColumns.indices.map { cursor.getString(it) })
                assertTrue(cursor.isNull(originalColumns.size))
            }
            val copiedColumnsSql = originalColumns.joinToString(", ") {
                if (it == "id") "'tool_new'" else "`$it`"
            }
            sqlite.execSQL(
                "INSERT INTO agent_messages ($oldColumnsSql) " +
                    "SELECT $copiedColumnsSql FROM agent_messages WHERE id = 'tool_call'"
            )
            sqlite.query("SELECT toolActionTitle FROM agent_messages WHERE id = 'tool_new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            sqlite.execSQL("UPDATE agent_messages SET toolActionTitle = ? WHERE id = 'tool_call'", arrayOf("Read configuration"))
            sqlite.query("SELECT $oldColumnsSql, toolActionTitle FROM agent_messages WHERE id = 'tool_call'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(originalValues, originalColumns.indices.map { cursor.getString(it) })
                assertEquals("Read configuration", cursor.getString(originalColumns.size))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_67_to_68_preservesAllMessageDataAndAddsNullableTiming() {
        var originalColumns: List<String> = emptyList()
        var originalValues: List<String?> = emptyList()
        helper.createDatabase(dbName, 67).apply {
            execSQL(
                "INSERT INTO agent_messages (" +
                    "id, sessionId, role, content, timestamp, toolCallId, toolName, toolArgs, isError, " +
                    "isCompacted, isContextSummary, isCompactionMarker, inputTokens, outputTokens, " +
                    "cachedInputTokens, isContextExcluded, contextInputTokens, contextInputBudget, " +
                    "contextEstimated, contextBreakdownJson, toolActionTitle) VALUES (" +
                    "'tool_call', 'session', 'TOOL', 'Keep result', 123, 'call', 'readFile', '{}', 1, " +
                    "0, 0, 0, 321, 54, 13, 0, 12345, 100000, 0, '{\"chatTokens\":12345}', 'Saved title')"
            )
            query("SELECT * FROM agent_messages WHERE id = 'tool_call'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                originalColumns = cursor.columnNames.toList()
                originalValues = (0 until cursor.columnCount).map { cursor.getString(it) }
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val columns = linkedMapOf<String, List<String?>>()
            sqlite.query("PRAGMA table_info(`agent_messages`)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = listOf(cursor.getString(2), cursor.getInt(3).toString(), cursor.getString(4))
                }
            }
            val timingColumns = listOf("toolStartedAtMs", "toolDurationMs", "turnDurationMs")
            assertEquals(originalColumns + timingColumns + "modelInputSnapshotJson", columns.keys.toList())
            timingColumns.forEach { assertEquals(listOf("INTEGER", "0", null), columns[it]) }
            sqlite.query("SELECT * FROM agent_messages WHERE id = 'tool_call'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(originalValues, originalColumns.indices.map { cursor.getString(it) })
                timingColumns.forEach { assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(it))) }
            }
            val oldColumnsSql = originalColumns.joinToString(", ") { "`$it`" }
            val copiedColumnsSql = originalColumns.joinToString(", ") {
                if (it == "id") "'tool_new'" else "`$it`"
            }
            sqlite.execSQL(
                "INSERT INTO agent_messages ($oldColumnsSql) " +
                    "SELECT $copiedColumnsSql FROM agent_messages WHERE id = 'tool_call'"
            )
            sqlite.query("SELECT toolStartedAtMs, toolDurationMs, turnDurationMs, toolActionTitle FROM agent_messages WHERE id = 'tool_new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertEquals("Saved title", cursor.getString(3))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun toolTimingUpdatesFreezeIndependentEventsAndFinalReplacementKeepsMetadataAndOrder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val dao = db.agentMessageDao()
            db.chatSessionDao().upsert(ChatSessionEntity("session", "Chat", 1, 2))
            val first = AgentMessageEntity(
                id = "tool_first", sessionId = "session", role = "TOOL", content = "[pending]", timestamp = 123,
                toolCallId = "first", toolName = "readFile", toolArgs = "{\"path\":\"/config.json\"}",
                toolActionTitle = "Saved title", contextInputTokens = 200, contextInputBudget = 1_000,
                contextEstimated = false, contextBreakdownJson = "{\"chatTokens\":200}"
            )
            val second = first.copy(id = "tool_second", toolCallId = "second", timestamp = 124)
            assertTrue(dao.insertToolMessagePreservingMetadata(first))
            assertTrue(dao.insertToolMessagePreservingMetadata(second))

            assertEquals(1, dao.updateToolTiming("session", "first", 10_000, null))
            assertEquals(first.copy(toolStartedAtMs = 10_000), dao.getMessageById(first.id))
            assertEquals(1, dao.updateToolTiming("session", "first", null, 125))
            val timed = first.copy(toolStartedAtMs = 10_000, toolDurationMs = 125)
            assertEquals(timed, dao.getMessageById(first.id))
            assertEquals(1, dao.updateToolTiming("session", "first", 99_000, 999))
            assertEquals(1, dao.updateToolTiming("session", "first", null, null))
            assertEquals(timed, dao.getMessageById(first.id))

            assertEquals(1, dao.updateToolTiming("session", "second", null, 0))
            assertEquals(1, dao.updateToolTiming("session", "second", 20_000, null))
            assertTrue(dao.insertToolMessagePreservingMetadata(second.copy(content = "Second finished", timestamp = 900)))
            assertTrue(dao.insertToolMessagePreservingMetadata(first.copy(
                content = "First failed", timestamp = 901, isError = true,
                toolActionTitle = "Ignored title", toolStartedAtMs = 99_000, toolDurationMs = 999
            )))

            assertEquals(timed.copy(content = "First failed", isError = true), dao.getMessageById(first.id))
            assertEquals(
                second.copy(content = "Second finished", toolStartedAtMs = 20_000, toolDurationMs = 0),
                dao.getMessageById(second.id)
            )
            assertEquals(listOf(first.id, second.id), dao.getToolMessagesBySession("session").first().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun timingUpdatesAreScopedToExistingMatchingRoleAndSessionAndCannotReviveRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val dao = db.agentMessageDao()
            db.chatSessionDao().upsert(ChatSessionEntity("session", "Chat", 1, 2))
            db.chatSessionDao().upsert(ChatSessionEntity("other", "Other", 1, 2))
            val tool = AgentMessageEntity(
                id = "tool_call", sessionId = "session", role = "TOOL", content = "Result", timestamp = 123,
                toolCallId = "call", toolName = "readFile", toolArgs = "{}", toolActionTitle = "Keep title"
            )
            val assistant = AgentMessageEntity("assistant", "session", "ASSISTANT", "Reply", 124)
            dao.insertAll(listOf(tool, assistant))

            assertEquals(0, dao.updateToolTiming("other", "call", 10_000, 125))
            assertEquals(0, dao.updateToolTiming("session", "tool_call", 10_000, 125))
            assertEquals(0, dao.updateToolTiming("session", "missing", 10_000, 125))
            assertEquals(0, dao.updateTurnDuration("session", tool.id, 4_000))
            assertEquals(0, dao.updateTurnDuration("other", assistant.id, 4_000))
            assertEquals(0, dao.updateTurnDuration("session", "missing", 4_000))
            assertEquals(tool, dao.getMessageById(tool.id))
            assertEquals(assistant, dao.getMessageById(assistant.id))
            assertEquals(1, dao.updateTurnDuration("session", assistant.id, 4_000))
            assertEquals(1, dao.updateTurnDuration("session", assistant.id, 9_000))
            assertEquals(assistant.copy(turnDurationMs = 4_000), dao.getMessageById(assistant.id))

            for (invalid in listOf(tool.copy(role = "USER"), tool.copy(role = "ASSISTANT"), tool.copy(toolCallId = "different"))) {
                dao.insert(invalid)
                assertEquals(0, dao.updateToolTiming("session", "call", 10_000, 125))
                assertEquals(invalid, dao.getMessageById(tool.id))
                assertFalse(dao.insertToolMessagePreservingMetadata(tool))
            }
            dao.insert(tool)
            assertFalse(dao.insertToolMessagePreservingMetadata(tool.copy(sessionId = "other")))
            db.chatSessionDao().delete("session")
            assertEquals(0, dao.updateToolTiming("session", "call", 10_000, 125))
            assertEquals(0, dao.updateTurnDuration("session", assistant.id, 4_000))
            assertFalse(dao.insertToolMessagePreservingMetadata(tool))
            assertEquals(tool, dao.getMessageById(tool.id))

            dao.deleteBySession("session")
            db.chatSessionDao().upsert(ChatSessionEntity("session", "Chat", 1, 2))
            assertEquals(0, dao.updateToolTiming("session", "call", 10_000, 125))
            assertEquals(0, dao.updateTurnDuration("session", assistant.id, 4_000))
            assertNull(dao.getMessageById(tool.id))
            assertNull(dao.getMessageById(assistant.id))
        } finally {
            db.close()
        }
    }

    @Test
    fun toolPreviewQueryFiltersSessionCompactionAndRoleAndOrdersEqualTimestampsByRowid() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val dao = db.agentMessageDao()
            val base = AgentMessageEntity("z-first", "session", "TOOL", "Result", 123, toolCallId = "first")
            dao.insertAll(listOf(
                base,
                base.copy(id = "a-second", toolCallId = "second"),
                base.copy(id = "earlier", timestamp = 100),
                base.copy(id = "compacted", isCompacted = true),
                base.copy(id = "other", sessionId = "other"),
                base.copy(id = "assistant", role = "ASSISTANT"),
                base.copy(id = "user", role = "USER"),
                base.copy(id = "excluded", isContextExcluded = true, timestamp = 124)
            ))

            val messages = dao.getToolMessagesBySession("session").first()

            assertEquals(listOf("earlier", "z-first", "a-second", "excluded"), messages.map { it.id })
            messages.forEach {
                assertNull(it.toolStartedAtMs)
                assertNull(it.toolDurationMs)
                assertNull(it.turnDurationMs)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_68_to_69_addsJournalWithoutChangingMessagesOrSessionForeignKeys() {
        helper.createDatabase(dbName, 68).apply {
            execSQL(
                "INSERT INTO agent_messages (id, sessionId, role, content, timestamp, isError, " +
                    "isCompacted, isContextSummary, isCompactionMarker, inputTokens, outputTokens, cachedInputTokens, " +
                    "isContextExcluded, contextEstimated, toolActionTitle, toolStartedAtMs, toolDurationMs, turnDurationMs) " +
                    "VALUES ('tool_saved', 'parent', 'TOOL', 'Saved output', 100, 0, 0, 0, 0, 0, 0, 0, 0, 1, " +
                    "'Saved title', 10, 20, 30)"
            )
        }.close()
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA table_info(subagent_interactions)").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertEquals(listOf("id", "parentSessionId", "subSessionId", "kind", "content", "timestamp", "origin", "sourceMessageId", "title", "model", "instructionId"), columns)
            }
            sqlite.query("PRAGMA foreign_key_list(subagent_interactions)").use { assertFalse(it.moveToFirst()) }
            sqlite.query("PRAGMA index_list(subagent_interactions)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(1)
                assertTrue(names.containsAll(listOf(
                    "index_subagent_interactions_parentSessionId_subSessionId_timestamp_id",
                    "index_subagent_interactions_parentSessionId_timestamp_id",
                    "index_subagent_interactions_timestamp_id",
                    "index_subagent_interactions_parentSessionId_subSessionId_sourceMessageId"
                )))
            }
            sqlite.query("SELECT content, toolActionTitle, toolStartedAtMs, toolDurationMs, turnDurationMs FROM agent_messages WHERE id = 'tool_saved'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(listOf("Saved output", "Saved title", "10", "20", "30"), (0..4).map { cursor.getString(it) })
            }
            sqlite.execSQL("INSERT INTO subagent_interactions (id, parentSessionId, subSessionId, kind, content, timestamp, origin) VALUES ('audit', 'deleted-parent', 'deleted-child', 'COMPLETED', 'Full output', 1, 'SUB')")
            sqlite.query("SELECT content FROM subagent_interactions WHERE id = 'audit'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Full output", cursor.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_69_to_70_preservesMessagesAndAddsRecoveryStateAndNullableInputSnapshot() {
        var messageColumns: List<String> = emptyList()
        var messageValues: List<String?> = emptyList()
        var sessionColumns: List<String> = emptyList()
        var sessionValues: List<String?> = emptyList()
        helper.createDatabase(dbName, 69).apply {
            execSQL(
                "INSERT INTO chat_sessions (id, title, createdAt, updatedAt, workspacePath, " +
                    "goalTurnCount, goalStartInputTokens, goalStartOutputTokens, reasoningEffort, " +
                    "totalInputTokens, totalOutputTokens, lastInputTokens, isPinned) " +
                    "VALUES ('session', 'Keep session', 1, 2, '/work', 0, 0, 0, 'MEDIUM', 12, 3, 12, 0)"
            )
            execSQL(
                "INSERT INTO agent_messages (id, sessionId, role, content, timestamp, isError, " +
                    "isCompacted, isContextSummary, isCompactionMarker, inputTokens, outputTokens, cachedInputTokens, " +
                    "isContextExcluded, contextEstimated, toolActionTitle, toolStartedAtMs, toolDurationMs, turnDurationMs) " +
                    "VALUES ('saved', 'session', 'TOOL', 'Keep output', 100, 0, 0, 0, 0, 12, 3, 4, 0, 1, " +
                    "'Keep title', 10, 20, 30)"
            )
            query("SELECT * FROM agent_messages WHERE id = 'saved'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                messageColumns = cursor.columnNames.toList()
                messageValues = messageColumns.indices.map { cursor.getString(it) }
            }
            query("SELECT * FROM chat_sessions WHERE id = 'session'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                sessionColumns = cursor.columnNames.toList()
                sessionValues = sessionColumns.indices.map { cursor.getString(it) }
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA table_info(agent_messages)").use { cursor ->
                val columns = linkedMapOf<String, List<String?>>()
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = listOf(cursor.getString(2), cursor.getInt(3).toString(), cursor.getString(4))
                }
                assertEquals(messageColumns + "modelInputSnapshotJson", columns.keys.toList())
                assertEquals(listOf("TEXT", "0", null), columns["modelInputSnapshotJson"])
            }
            sqlite.query("SELECT * FROM agent_messages WHERE id = 'saved'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(messageValues, messageColumns.indices.map { cursor.getString(it) })
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("modelInputSnapshotJson")))
            }
            sqlite.query("SELECT * FROM chat_sessions WHERE id = 'session'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(sessionColumns + listOf("executionGeneration", "autoDispatchBlockedReason"), cursor.columnNames.toList())
                assertEquals(sessionValues, sessionColumns.indices.map { cursor.getString(it) })
                assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("executionGeneration")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("autoDispatchBlockedReason")))
            }
            val expectedTables = mapOf(
                "agent_recovery_requests" to listOf("id", "sessionId", "generation", "environment", "inputMessageId", "queueOrder",
                    "payloadJson", "status", "activeRunId", "continuationCount", "continuationOutputTokens", "continuationChars", "createdAt", "updatedAt"),
                "agent_recovery_runs" to listOf("id", "requestId", "sessionId", "generation", "ownerFence", "status", "step", "revision",
                    "committedStep", "createdAt", "updatedAt", "endedAt"),
                "agent_recovery_tools" to listOf("runId", "toolCallId", "toolName", "step", "revision", "status",
                    "assistantMessageId", "resultMessageId", "updatedAt"),
                "agent_recovery_drafts" to listOf("runId", "step", "revision", "text", "updatedAt")
            )
            expectedTables.forEach { (table, expected) ->
                sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.moveToNext()) columns += cursor.getString(1)
                    assertEquals(table, expected, columns)
                }
                sqlite.query("SELECT * FROM `$table`").use { assertFalse(it.moveToFirst()) }
            }
            val snapshot = """{"version":1,"content":"Keep input"}"""
            sqlite.execSQL("UPDATE agent_messages SET modelInputSnapshotJson = ? WHERE id = 'saved'", arrayOf(snapshot))
            sqlite.query("SELECT modelInputSnapshotJson FROM agent_messages WHERE id = 'saved'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(snapshot, cursor.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_58_to_59_adds_backup_targets() {
        helper.createDatabase(dbName, 58).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val tables = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        db.close()

        assertTrue(
            "迁移 59 应新增 backup_targets 表，实际表：$tables",
            tables.contains("backup_targets")
        )
    }
}