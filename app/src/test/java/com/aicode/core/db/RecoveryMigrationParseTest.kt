package com.aicode.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecoveryMigrationParseTest {
    private val file = File("src/main/assets/migrations/70_add_agent_recovery.sql")
    private val statements: List<String> get() = SqlScriptSplitter.split(file.readText())

    @Test
    fun migrationAddsOnlySpecifiedSessionAndMessageColumns() {
        assertEquals(listOf(
            "ALTER TABLE chat_sessions ADD COLUMN executionGeneration INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE chat_sessions ADD COLUMN autoDispatchBlockedReason TEXT",
            "ALTER TABLE agent_messages ADD COLUMN modelInputSnapshotJson TEXT"
        ), statements.filter { it.startsWith("ALTER TABLE") })
        assertTrue(File("src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt")
            .readText().contains("const val SCHEMA_VERSION = 71"))
    }

    @Test
    fun fourTablesHaveNoSessionCascadeAndContainOnlyTheirPersistedMetadata() {
        val tables = statements.filter { it.startsWith("CREATE TABLE") }
        assertEquals(setOf("agent_recovery_requests", "agent_recovery_runs", "agent_recovery_tools", "agent_recovery_drafts"),
            tables.map { Regex("CREATE TABLE IF NOT EXISTS (\\w+)").find(it)!!.groupValues[1] }.toSet())
        assertEquals(4, tables.size)
        assertFalse(file.readText().contains("REFERENCES", ignoreCase = true))
        assertFalse(file.readText().contains("CASCADE", ignoreCase = true))
        val toolColumns = columns(tables.single { it.startsWith("CREATE TABLE IF NOT EXISTS agent_recovery_tools ") })
        assertEquals(setOf("runId", "toolCallId", "toolName", "step", "revision", "status", "assistantMessageId", "resultMessageId", "updatedAt"), toolColumns)
        val draftColumns = columns(tables.single { it.startsWith("CREATE TABLE IF NOT EXISTS agent_recovery_drafts ") })
        assertEquals(setOf("runId", "step", "revision", "text", "updatedAt"), draftColumns)
    }

    @Test
    fun queueAndStableInputIndexesAreUniqueAndAllSixIndexesAreDeclared() {
        val indexes = statements.filter { it.startsWith("CREATE") && it.contains("INDEX IF NOT EXISTS") }
        assertEquals(6, indexes.size)
        assertEquals(2, indexes.count { it.startsWith("CREATE UNIQUE INDEX") })
        assertTrue(indexes.any { it.startsWith("CREATE UNIQUE INDEX") && it.contains("(sessionId, generation, queueOrder)") })
        assertTrue(indexes.any { it.startsWith("CREATE UNIQUE INDEX") && it.contains("(inputMessageId)") })
    }

    @Test
    fun executionCommitMethodsUseRealRoomTransactionsAndOwnerGenerationFenceQuery() {
        val source = File("src/main/java/com/aicode/feature/agent/data/local/dao/AgentRecoveryDao.kt").readText()
        for (method in listOf("claimRequest", "updateQueuedRequest", "reorderQueuedRequests", "deleteRecoverySession",
            "beginRecoveryStep", "restartRecoveryStream", "saveRecoveryDraft", "declareRecoveryTools", "markRecoveryTool",
            "commitRecoveryInput", "commitRecoverySupplementalInput", "freezeRecoveryInputSnapshot", "commitRecoveryAssistant", "commitRecoveryToolPlaceholder",
            "commitRecoveryToolResult", "updateRecoveryToolTiming", "updateRecoveryTurnDuration", "finishRecoveryRun", "incrementRecoveryBudget")) {
            assertTrue(method, source.contains("@Transaction\n    suspend fun $method("))
        }
        for (fence in listOf("r.ownerFence = :ownerFence", "r.status = 'RUNNING'", "q.status = 'RUNNING'",
            "q.activeRunId = r.id", "r.generation = s.executionGeneration", "s.autoDispatchBlockedReason IS NULL")) {
            assertTrue(fence, source.contains(fence))
        }
        val sessions = File("src/main/java/com/aicode/feature/agent/data/local/dao/ChatSessionDao.kt").readText()
        val emptyQuery = sessions.substringBefore("suspend fun getEmptyRootSessionByWorkspace").substringAfterLast("@Query")
        assertTrue(emptyQuery.contains("agent_recovery_requests"))
        assertTrue(emptyQuery.contains("q.generation = chat_sessions.executionGeneration"))
        assertTrue(emptyQuery.contains("autoDispatchBlockedReason IS NULL"))
    }

    @Test
    fun continuationMigrationAddsSnapshotDisplayAndOutboxMetadata() {
        val sql = File("src/main/assets/migrations/71_add_recovery_continuation.sql").readText()
        val statements = SqlScriptSplitter.split(sql)
        assertEquals(7, statements.count { it.startsWith("ALTER TABLE") })
        val outbox = statements.single { it.startsWith("CREATE TABLE") }
        assertEquals(setOf("seq", "sessionId", "generation", "eventKey", "payloadJson", "createdAt",
            "requiresConfirmation", "deliveredAt", "retiredAt"), columns(outbox))
        assertEquals(2, statements.count { it.startsWith("CREATE") && it.contains("INDEX IF NOT EXISTS") })
        assertFalse(sql.contains("DROP", ignoreCase = true))
        assertFalse(sql.contains("REFERENCES", ignoreCase = true))
    }

    private fun columns(statement: String): Set<String> = statement.substringAfter('(').substringBeforeLast(')')
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("PRIMARY KEY") }
        .map { it.substringBefore(' ') }.toSet()
}
