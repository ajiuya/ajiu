package com.aicode.feature.agent.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, manifest = Config.NONE)
class SubAgentInteractionDaoTest {
    private lateinit var database: AgentDatabase
    private val interactions get() = database.subAgentInteractionDao()
    private val messages get() = database.agentMessageDao()
    private val sessions get() = database.chatSessionDao()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric SQLite requires a supported host runtime", !androidContainer)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun uuidConflictNeverReplacesBodyButRepeatedDeliberateSendIsRetained() = runBlocking {
        val original = interaction("event-a", content = "body".repeat(1_000))
        assertTrue(interactions.insertIgnore(original) != -1L)
        assertEquals(-1L, interactions.insertIgnore(original.copy(content = "replacement")))
        interactions.insertIgnore(original.copy(id = "event-b"))

        assertEquals(original, interactions.getById("event-a"))
        assertEquals(listOf("event-a", "event-b"), interactions.getAllOnce().map { it.id })
        assertNull(interactions.getById("missing"))
    }

    @Test
    fun collapsedMetadataTruncatesOnlyPreviewAndRetainsSnapshots() = runBlocking {
        val entry = interaction("event").copy(
            content = "正文".repeat(400), title = "Child", model = "model", sourceMessageId = "source",
            instructionId = "accepted"
        )
        interactions.insertIgnore(entry)

        val metadata = interactions.observeByParent("parent").first().single()
        assertEquals(entry.content.take(240), metadata.preview)
        assertEquals("source", metadata.sourceMessageId)
        assertEquals("accepted", metadata.instructionId)
        assertEquals("Child", metadata.title)
        assertEquals("model", metadata.model)
        assertEquals(listOf(metadata), interactions.getMetadataByParentOnce("parent"))
        assertEquals(entry, interactions.getByChildPaged("parent", "child", 1).first().single())
        assertTrue(interactions.observeByParent("other").first().isEmpty())
    }

    @Test
    fun childPagesUseStableTimestampIdOrderAndParentOwnership() = runBlocking {
        listOf("c", "a", "b").forEach { interactions.insertIgnore(interaction(it)) }
        interactions.insertIgnore(interaction("other", parent = "other"))

        assertEquals(listOf("b", "c"), interactions.getByChildPaged("parent", "child", 2).first().map { it.id })
        assertEquals(3, interactions.observeCountByChild("parent", "child").first())
        assertEquals(0, interactions.observeCountByChild("stranger", "child").first())
        assertTrue(interactions.getByChildPaged("stranger", "child", 10).first().isEmpty())
    }

    @Test
    fun keysetExportIncludesDeletedChildrenWithoutRepeatingEqualTimestamps() = runBlocking {
        val entries = listOf(
            interaction("a", timestamp = 0),
            interaction("b", timestamp = 1),
            interaction("c", parent = "other", timestamp = 1),
            interaction("d", timestamp = 1)
        )
        interactions.insertAllIgnore(entries)
        val first = interactions.getPageAfter(Long.MIN_VALUE, "", 2)
        val second = interactions.getPageAfter(first.last().timestamp, first.last().id, 2)
        assertEquals(entries, first + second)
        assertEquals(listOf("a", "b", "d"), interactions.getPageByParentAfter("parent", Long.MIN_VALUE, "", 10).map { it.id })
        assertTrue(interactions.getPageAfter(1, "d", 2).isEmpty())
    }

    @Test
    fun sessionReplaceAndChildDeletionDoNotCascadeIntoParentAudit() = runBlocking {
        sessions.upsert(session("parent"))
        sessions.upsert(session("child", "parent"))
        interactions.insertIgnore(interaction("audit"))
        sessions.upsert(session("parent").copy(title = "Renamed"))
        sessions.upsert(session("child", "parent").copy(model = "new-model"))
        sessions.delete("child")

        assertFalse(sessions.observeExists("child").first())
        assertTrue(sessions.observeExists("parent").first())
        assertEquals("audit", interactions.getAllOnce().single().id)
        interactions.deleteByParent("child")
        assertEquals("audit", interactions.getAllOnce().single().id)
        interactions.deleteByParent("parent")
        assertTrue(interactions.getAllOnce().isEmpty())
    }

    @Test
    fun deletingOwnedParentNeverDeletesAnotherParentsAudit() = runBlocking {
        interactions.insertIgnore(interaction("owned"))
        interactions.insertIgnore(interaction("ancestor", parent = "ancestor", child = "parent"))
        interactions.deleteByParent("parent")
        assertEquals(listOf("ancestor"), interactions.getAllOnce().map { it.id })
        interactions.deleteAll()
        assertTrue(interactions.getAllOnce().isEmpty())
    }

    @Test
    fun deletedChildOwnershipProjectionReturnsDistinctParentsWithoutBodies() = runBlocking {
        interactions.insertIgnore(interaction("first", parent = "a"))
        interactions.insertIgnore(interaction("second", parent = "a"))
        interactions.insertIgnore(interaction("third", parent = "b"))
        interactions.insertIgnore(interaction("another-child", parent = "c", child = "another-child"))

        assertEquals(listOf("a", "b"), interactions.getParentIdsByChildOnce("child"))
        assertTrue(interactions.getParentIdsByChildOnce("missing").isEmpty())
    }

    @Test
    fun statsCountVisibleUserAssistantAndToolsWithoutReadingReasoning() = runBlocking {
        sessions.upsert(session("child", "parent"))
        sessions.upsert(session("foreign-child", "foreign"))
        messages.insertAll(listOf(
            message("user", "USER", "Question", 1),
            message("attachment", "USER", "", 2).copy(attachmentsJson = "[{\"name\":\"file\"}]"),
            message("assistant", "ASSISTANT", "Reply".repeat(100), 3).copy(reasoning = "private"),
            message("tool", "TOOL", "output", 4),
            message("thinking", "ASSISTANT", "", 20).copy(reasoning = "private-only"),
            message("summary", "ASSISTANT", "internal", 21).copy(isContextSummary = true),
            message("marker", "USER", "internal", 22).copy(isCompactionMarker = true),
            message("system", "SYSTEM", "hidden", 23),
            message("foreign", "ASSISTANT", "foreign", 99).copy(sessionId = "foreign-child")
        ))

        val stats = messages.observeSubAgentHistoryStats("parent").first().single()
        assertEquals(SubAgentHistoryStats("child", 2, 1, 1, 4, "Reply".repeat(100).take(240)), stats)
        assertTrue(messages.observeSubAgentHistoryStats("unknown").first().isEmpty())
    }

    @Test
    fun executionPageIsBoundedVisibleParentGatedAndRedactsPrivateReplayFields() = runBlocking {
        sessions.upsert(session("child", "parent"))
        val body = message("body", "ASSISTANT", "Public reply", 1).copy(
            reasoning = "private", signature = "signature", thinkingBlocksJson = "private-blocks", toolCallsJson = "private-replay",
            toolActionTitle = "Action", toolStartedAtMs = 10, toolDurationMs = 20, turnDurationMs = 30
        )
        val tool = message("tool", "TOOL", "Result", 1).copy(toolName = "readFile", toolArgs = "{}")
        val attachment = message("attachment", "USER", "", 2).copy(attachmentsJson = "[{\"name\":\"file\"}]")
        messages.insertAll(listOf(
            body, tool, attachment,
            message("thinking", "ASSISTANT", "", 3).copy(reasoning = "hidden"),
            message("summary", "ASSISTANT", "hidden", 4).copy(isContextSummary = true)
        ))

        val page = messages.observeSubAgentExecutionMessages("parent", "child", 3).first()
        assertEquals(listOf("body", "tool", "attachment"), page.map { it.id })
        assertEquals(body.copy(reasoning = null, signature = null, thinkingBlocksJson = null, toolCallsJson = null), page.first())
        assertEquals(tool, page[1])
        assertEquals(attachment.attachmentsJson, page.last().attachmentsJson)
        assertEquals(listOf("attachment"), messages.observeSubAgentExecutionMessages("parent", "child", 1).first().map { it.id })
        assertTrue(messages.observeSubAgentExecutionMessages("other", "child", 10).first().isEmpty())
        assertTrue(messages.observeSubAgentLegacyMessages("other", "child", 10).first().isEmpty())
    }

    @Test
    fun legacyPreservesPublicMessageParentArgumentsWithoutExposingThinkingOrExecutionReplay() = runBlocking {
        sessions.upsert(session("child", "parent"))
        val calls = """[{"id":"call","name":"messageParent","arguments":{"message":"Full progress"}}]"""
        val source = message("call-source", "ASSISTANT", "", 1).copy(
            toolCallsJson = calls, reasoning = "private", signature = "signature", thinkingBlocksJson = "private blocks"
        )
        messages.insert(source)

        val legacy = messages.observeSubAgentLegacyMessages("parent", "child", 10).first().single()

        assertEquals(source.copy(reasoning = null, signature = null, thinkingBlocksJson = null), legacy)
        assertTrue(messages.observeSubAgentExecutionMessages("parent", "child", 10).first().isEmpty())
    }

    @Test
    fun legacyPageStopsAtJournalBoundaryAndExcludesRecordedSources() = runBlocking {
        sessions.upsert(session("child", "parent"))
        messages.insertAll(listOf(
            message("old-user", "USER", "Question", 1),
            message("old-answer", "ASSISTANT", "Already recorded", 2),
            message("thinking", "ASSISTANT", "", 3).copy(reasoning = "hidden"),
            message("new-user", "USER", "New instruction", 10),
            message("new-answer", "ASSISTANT", "New reply", 11)
        ))
        interactions.insertIgnore(interaction("wrong-parent", parent = "other", timestamp = 0))
        interactions.insertIgnore(interaction("boundary", timestamp = 10).copy(sourceMessageId = "old-answer"))

        assertEquals(listOf("old-user"), messages.observeSubAgentLegacyMessages("parent", "child", 2).first().map { it.id })
        assertEquals(4, messages.observeSubAgentExecutionMessages("parent", "child", 10).first().size)
        sessions.delete("child")
        assertTrue(messages.observeSubAgentLegacyMessages("parent", "child", 10).first().isEmpty())
        assertEquals(1, interactions.observeCountByChild("parent", "child").first())
    }

    @Test
    fun unfinishedExecutionQueryKeepsLatestStartAndIgnoresLateDifferentRunTerminal() = runBlocking {
        interactions.insertAllIgnore(listOf(
            interaction("spawn", timestamp = 1).copy(kind = "SPAWNED", generation = 0, parentGeneration = 0),
            interaction("run-one", timestamp = 2).copy(kind = "STARTED", generation = 0, runId = "one"),
            interaction("done-one", timestamp = 3).copy(kind = "COMPLETED", generation = 0, runId = "one"),
            interaction("run-two", timestamp = 4).copy(kind = "STARTED", generation = 0, runId = "two"),
            interaction("late-stop-one", timestamp = 5).copy(kind = "STOPPED", generation = 0, runId = "one"),
            interaction("other-start", child = "other-child", timestamp = 1).copy(kind = "STARTED", runId = "other"),
            interaction("other-interrupted", child = "other-child", timestamp = 2).copy(kind = "INTERRUPTED", runId = "other")
        ))
        assertEquals(listOf("run-two"), interactions.getUnfinishedExecutions().map { it.id })
        assertEquals("run-two", interactions.latestExecution("parent", "child", "two")!!.id)
        assertNull(interactions.latestExecution("stranger", "child", "two"))
        interactions.insertIgnore(interaction("interrupted-two", timestamp = 6)
            .copy(kind = "INTERRUPTED", generation = 0, runId = "two"))
        assertTrue(interactions.getUnfinishedExecutions().isEmpty())
    }

    @Test
    fun changedFileProjectionOnlyReadsCurrentPublicFileToolArguments() = runBlocking {
        messages.insertAll(listOf(
            message("write", "TOOL", "result", 1).copy(toolName = "writeFile", toolArgs = "{\"path\":\"/work/result.kt\"}"),
            message("read", "TOOL", "result", 2).copy(toolName = "readFile", toolArgs = "{\"path\":\"/work/input.kt\"}"),
            message("hidden", "TOOL", "result", 3).copy(toolName = "editFile", toolArgs = "secret", isContextExcluded = true),
            message("foreign", "TOOL", "result", 4).copy(sessionId = "foreign-child", toolName = "writeFile", toolArgs = "secret"),
            message("assistant", "ASSISTANT", "body", 5).copy(reasoning = "private thinking", toolCallsJson = "private protocol")
        ))
        assertEquals(listOf(SubAgentChangedFileSource("write", "writeFile", "{\"path\":\"/work/result.kt\"}")),
            interactions.changedFileSources("child", listOf("write", "read", "hidden", "foreign", "assistant")))
    }

    private fun session(id: String, parent: String? = null) = ChatSessionEntity(
        id = id, title = id, createdAt = 0, updatedAt = 0, parentId = parent
    )

    private fun interaction(
        id: String,
        parent: String = "parent",
        child: String = "child",
        timestamp: Long = 1,
        content: String = "Body"
    ) = SubAgentInteractionEntity(id, parent, child, "MESSAGE_FROM_SUB", content, timestamp)

    private fun message(id: String, role: String, content: String, timestamp: Long) = AgentMessageEntity(
        id = id, sessionId = "child", role = role, content = content, timestamp = timestamp
    )
}
