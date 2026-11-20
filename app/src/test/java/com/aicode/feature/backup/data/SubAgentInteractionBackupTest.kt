package com.aicode.feature.backup.data

import android.content.Context
import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectProgressDraft
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.SubAgentInteractionDto
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class SubAgentInteractionBackupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context = mockk<Context>(relaxed = true)
    private val sessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val messageDao = mockk<AgentMessageDao>(relaxed = true)
    private val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
    private val todoDao = mockk<TodoItemDao>(relaxed = true)
    private val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
    private val mcpManager = mockk<McpManager>(relaxed = true)
    private val projectMemoryJournal = mockk<ProjectMemoryJournal>(relaxed = true)
    private val recoveryRepository = mockk<RecoveryRepository>(relaxed = true)
    private val agentDatabase = mockk<AgentDatabase>()
    private var restoreTransactionDepth = 0
    private val sessions = linkedMapOf<String, ChatSessionEntity>()
    private val messages = linkedMapOf<String, AgentMessageEntity>()
    private val interactions = linkedMapOf<String, SubAgentInteractionEntity>()
    private val manager = BackupManagerImpl(
        context = context,
        aiProviderDao = mockk(relaxed = true),
        remoteConnectionDao = mockk(relaxed = true),
        chatSessionDao = sessionDao,
        agentMessageDao = messageDao,
        todoItemDao = todoDao,
        mcpConfigRepository = mockk(relaxed = true),
        mcpManager = mcpManager,
        permissionRulesRepository = mockk(relaxed = true),
        themeSettingsRepository = mockk(relaxed = true),
        keepaliveSettingsRepository = mockk(relaxed = true),
        liveNotificationSettingsRepository = mockk(relaxed = true),
        approvalNotificationSettingsRepository = mockk(relaxed = true),
        screenOnSettingsRepository = mockk(relaxed = true),
        agentSoundSettingsRepository = mockk(relaxed = true),
        generalSettingsRepository = mockk(relaxed = true),
        logSettingsRepository = mockk(relaxed = true),
        visionModelSettingsRepository = mockk(relaxed = true),
        compactionModelSettingsRepository = mockk(relaxed = true),
        syncSettingsRepository = mockk(relaxed = true),
        workspaceRepository = workspaceRepository,
        backgroundSettingsRepository = mockk(relaxed = true),
        chatBubbleSettingsRepository = mockk(relaxed = true),
        subAgentInteractionDao = interactionDao,
        projectMemoryJournal = projectMemoryJournal,
        recoveryRepository = recoveryRepository,
        agentDatabase = agentDatabase,
        pluginArchives = mockk(relaxed = true),
        backupContent = mockk(relaxed = true)
    )
    private val options = BackupOptions(
        providers = false, remoteConnections = false, chatHistory = true, mcpServers = false,
        permissionRules = false, appSettings = false
    )

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { agentDatabase.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            restoreTransactionDepth++
            try {
                arg<suspend () -> Unit>(1).invoke()
            } finally {
                restoreTransactionDepth--
            }
        }
        every { context.cacheDir } returns temporaryFolder.root
        every { workspaceRepository.currentPath() } returns "/workspace"
        every { projectMemoryJournal.exportHistory() } returns """{"schemaVersion":1,"projects":[],"entries":[],"archives":[]}"""
        coEvery { recoveryRepository.invalidateSession(any(), any(), any()) } answers {
            assertTrue(restoreTransactionDepth > 0)
            val id = firstArg<String>()
            val existing = sessions[id]
            if (existing == null) false else {
                sessions[id] = existing.copy(
                    executionGeneration = existing.executionGeneration + if (thirdArg<Boolean>()) 1 else 0,
                    autoDispatchBlockedReason = secondArg()
                )
                true
            }
        }
        coEvery { sessionDao.getById(any()) } answers { sessions[firstArg<String>()] }
        coEvery { sessionDao.upsertAll(any()) } answers {
            assertTrue(restoreTransactionDepth > 0)
            firstArg<List<ChatSessionEntity>>().forEach { sessions[it.id] = it }
        }
        coEvery { sessionDao.getSubSessionsByParentOnce(any()) } answers {
            val parent = firstArg<String>()
            sessions.values.filter { it.parentId == parent }
        }
        coEvery { sessionDao.getPageAfter(any(), any(), any()) } answers {
            val timestamp = firstArg<Long>()
            val id = secondArg<String>()
            sessions.values.sortedWith(compareBy({ it.updatedAt }, { it.id }))
                .filter { it.updatedAt > timestamp || (it.updatedAt == timestamp && it.id > id) }
                .take(thirdArg())
        }
        coEvery { messageDao.getMessageById(any()) } answers { messages[firstArg<String>()] }
        coEvery { messageDao.insertAll(any()) } answers {
            assertTrue(restoreTransactionDepth > 0)
            firstArg<List<AgentMessageEntity>>().forEach { messages[it.id] = it }
        }
        coEvery { messageDao.getPageBySessionAfter(any(), any(), any(), any()) } answers {
            val session = firstArg<String>()
            val timestamp = secondArg<Long>()
            val id = thirdArg<String>()
            messages.values.sortedWith(compareBy({ it.timestamp }, { it.id }))
                .filter { it.sessionId == session && (it.timestamp > timestamp || (it.timestamp == timestamp && it.id > id)) }
                .take(arg(3))
        }
        coEvery { messageDao.getPageAfter(any(), any(), any()) } answers {
            val timestamp = firstArg<Long>()
            val id = secondArg<String>()
            messages.values.sortedWith(compareBy({ it.timestamp }, { it.id }))
                .filter { it.timestamp > timestamp || (it.timestamp == timestamp && it.id > id) }
                .take(thirdArg())
        }
        coEvery { interactionDao.getById(any()) } answers { interactions[firstArg<String>()] }
        coEvery { interactionDao.getParentIdsByChildOnce(any()) } answers {
            val child = firstArg<String>()
            interactions.values.filter { it.subSessionId == child }.map { it.parentSessionId }.distinct().sorted()
        }
        coEvery { interactionDao.insertIgnore(any()) } answers {
            val entry = firstArg<SubAgentInteractionEntity>()
            if (entry.id in interactions) -1L else {
                interactions[entry.id] = entry
                interactions.size.toLong()
            }
        }
        coEvery { interactionDao.getPageAfter(any(), any(), any()) } answers {
            val timestamp = firstArg<Long>()
            val id = secondArg<String>()
            interactions.values.sortedWith(compareBy({ it.timestamp }, { it.id }))
                .filter { it.timestamp > timestamp || (it.timestamp == timestamp && it.id > id) }
                .take(thirdArg())
        }
        coEvery { interactionDao.getPageByParentAfter(any(), any(), any(), any()) } answers {
            val parent = firstArg<String>()
            val timestamp = secondArg<Long>()
            val id = thirdArg<String>()
            interactions.values.sortedWith(compareBy({ it.timestamp }, { it.id }))
                .filter { it.parentSessionId == parent && (it.timestamp > timestamp || (it.timestamp == timestamp && it.id > id)) }
                .take(arg(3))
        }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun streamingFullBackupPagesJournalAndRoundtripsFullTextAndSnapshotsIdempotently() = runTest {
        sessions["parent"] = session("parent")
        val expected = (1..501).map { index ->
            entry("event-${index.toString().padStart(3, '0')}", child = "deleted-child", timestamp = 123)
                .copy(content = "Full body\n".repeat(100), title = "Retained title", model = "Retained model", sourceMessageId = "deleted-source", agentName = "编码员")
        }
        expected.forEach { interactions[it.id] = it }
        val output = ByteArrayOutputStream()
        manager.export(null, options, output)
        coVerify(exactly = 1) { interactionDao.getPageAfter(123, "event-500", 500) }
        coVerify(exactly = 0) { interactionDao.getAllOnce() }
        interactions.clear()
        sessions.clear()

        repeat(2) { manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow() }

        assertEquals(expected, interactions.values.toList())
        assertNull(sessions["deleted-child"])
        coVerify(exactly = 0) { interactionDao.deleteAll() }
        coVerify(exactly = 0) { mcpManager.reload() }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun singleParentIncludesLiveChildrenMessagesAndDeletedChildAuditButNotAnotherParent() = runTest {
        sessions["parent"] = session("parent")
        sessions["child"] = session("child", "parent")
        sessions["other"] = session("other")
        messages["child-message"] = AgentMessageEntity("child-message", "child", "TOOL", "Output", 1,
            toolActionTitle = "Read source", toolStartedAtMs = 10, toolDurationMs = 20)
        interactions["live"] = entry("live")
        interactions["deleted"] = entry("deleted", child = "deleted-child")
        interactions["foreign"] = entry("foreign", parent = "other", child = "foreign-child")
        val output = ByteArrayOutputStream()
        manager.exportSession("parent", output)
        val files = readArchive(output.toByteArray())
        val exportedSessions = files.getValue("chatSessions.jsonl").lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString(ChatSessionDto.serializer(), it).id }.toList()
        val exportedJournal = files.getValue("subAgentInteractions.jsonl").lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString(SubAgentInteractionDto.serializer(), it).id }.toList()
        assertEquals(listOf("parent", "child"), exportedSessions)
        assertEquals(listOf("deleted", "live"), exportedJournal)
        val metadata = json.decodeFromString(BackupMetadata.serializer(), files.getValue("metadata.json"))
        assertFalse(metadata.includesAppSettings)
        coVerify(exactly = 1) { todoDao.getBySessionPageAfter("child", any(), any(), any()) }
        sessions.clear()
        messages.clear()
        interactions.clear()

        manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow()

        assertEquals(setOf("parent", "child"), sessions.keys)
        assertEquals(setOf("deleted", "live"), interactions.keys)
        assertEquals("parent", sessions.getValue("child").parentId)
        assertEquals("Read source", messages.getValue("child-message").toolActionTitle)
        assertEquals(10L, messages.getValue("child-message").toolStartedAtMs)
        assertEquals(20L, messages.getValue("child-message").toolDurationMs)
    }

    @Test
    fun disabledChatBackupDoesNotReadOrExportJournal() = runTest {
        interactions["event"] = entry("event")
        val output = ByteArrayOutputStream()

        manager.export(null, options.copy(chatHistory = false), output)

        assertFalse("subAgentInteractions.jsonl" in readArchive(output.toByteArray()))
        coVerify(exactly = 0) { interactionDao.getPageAfter(any(), any(), any()) }
    }

    @Test
    fun legacySnapshotExportsAndRestoresJournalWithDeletedChildAndOptionalDefaults() = runTest {
        val dto = dto("audit", child = "deleted-child").copy(content = "Full\n".repeat(500), title = "Title", model = "Model")
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            chatSessions = listOf(sessionDto("parent")), subAgentInteractions = listOf(dto)
        )
        val serialized = json.encodeToString(BackupSnapshot.serializer(), snapshot)
        assertEquals(snapshot, json.decodeFromString(BackupSnapshot.serializer(), serialized))
        repeat(2) {
            manager.import(ByteArrayInputStream(archive("snapshot.json" to serialized)), null).getOrThrow()
        }
        assertEquals(dto.content, interactions.getValue("audit").content)
        assertEquals(dto.title, interactions.getValue("audit").title)
        assertNull(interactions.getValue("audit").instructionId)
        assertNull(sessions["deleted-child"])
        val oldSnapshot = json.decodeFromString(BackupSnapshot.serializer(), """{"schemaVersion":68,"createdAt":1}""")
        assertTrue(oldSnapshot.subAgentInteractions.isEmpty())
        val minimalDto = json.decodeFromString(SubAgentInteractionDto.serializer(), """{"id":"x","parentSessionId":"p","subSessionId":"c","kind":"COMPLETED","content":"body","timestamp":1}""")
        assertEquals("SUB", minimalDto.origin)
        assertNull(minimalDto.sourceMessageId)
        assertNull(minimalDto.instructionId)
    }

    @Test
    fun legacySnapshotIsolatesMalformedInteractionDtosInsteadOfRejectingValidHistory() = runTest {
        val valid = json.encodeToString(SubAgentInteractionDto.serializer(), dto("valid", child = "deleted-child"))
        val parent = json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent"))
        val input = """{"schemaVersion":69,"createdAt":1,"chatSessions":[$parent],"subAgentInteractions":[{},$valid,{"id":17}]}"""

        manager.import(ByteArrayInputStream(archive("snapshot.json" to input)), null).getOrThrow()

        assertEquals(setOf("valid"), interactions.keys)
    }

    @Test
    fun streamingImportWaitsForSessionsAndIsolatesMalformedAndCrossParentEntries() = runTest {
        sessions["foreign-child"] = session("foreign-child", "other")
        sessions["not-imported"] = session("not-imported")
        messages["foreign-source"] = AgentMessageEntity("foreign-source", "foreign-child", "ASSISTANT", "Foreign", 1)
        val dtos = listOf(
            dto("valid", child = "deleted-child"),
            dto("cross-child", child = "foreign-child"),
            dto("self", child = "parent"),
            dto("absent-parent", parent = "absent"),
            dto("unscoped-parent", parent = "not-imported"),
            dto("unknown-kind").copy(kind = "UNKNOWN"),
            dto("unknown-origin").copy(origin = "UNKNOWN"),
            dto("negative-time").copy(timestamp = -1),
            dto(" "),
            dto("bad-source").copy(sourceMessageId = "foreign-source"),
            dto("good-after-invalid", child = "deleted-child")
        )
        val contents = dtos.joinToString("\n") { json.encodeToString(SubAgentInteractionDto.serializer(), it) } + "\n{}\n{broken}\n"
        val input = archive(
            "subAgentInteractions.jsonl" to contents,
            "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
            "metadata.json" to metadata()
        )

        manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(setOf("valid", "good-after-invalid"), interactions.keys)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun legacyImportRejectsCrossParentItemsAndDoesNotOverwriteExistingEventUuid() = runTest {
        sessions["foreign-child"] = session("foreign-child", "other")
        val existing = entry("duplicate").copy(content = "Original audit")
        interactions[existing.id] = existing
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            chatSessions = listOf(sessionDto("parent"), sessionDto("other"), sessionDto("foreign-child", "parent")),
            subAgentInteractions = listOf(
                dto("duplicate").copy(content = "Replacement"),
                dto("cross-parent", child = "foreign-child"),
                dto("valid", child = "deleted-child")
            )
        )

        manager.import(ByteArrayInputStream(archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))), null).getOrThrow()

        assertEquals(existing, interactions.getValue("duplicate"))
        assertEquals(setOf("duplicate", "valid"), interactions.keys)
        assertEquals("other", sessions.getValue("foreign-child").parentId)
    }

    @Test
    fun startedInstructionsResolveAcrossArchiveOrderAndRejectForeignOrMissingReferences() = runTest {
        val accepted = dto("instruction").copy(kind = "MESSAGE_FROM_PARENT", origin = "USER", timestamp = 2)
        val foreign = dto("foreign-instruction", parent = "other", child = "other-child").copy(kind = "MESSAGE_FROM_PARENT", origin = "USER")
        val started = dto("started").copy(kind = "STARTED", origin = "SYSTEM", instructionId = accepted.id, timestamp = 1)
        val dtos = listOf(
            started,
            started.copy(id = "foreign-reference", instructionId = foreign.id),
            started.copy(id = "missing-reference", instructionId = "missing"),
            started.copy(id = "bad-source", sourceMessageId = "source"),
            dto("bad-instruction-kind").copy(instructionId = accepted.id),
            accepted, foreign
        )
        val input = archive(
            "metadata.json" to metadata(),
            "subAgentInteractions.jsonl" to dtos.joinToString("\n") { json.encodeToString(SubAgentInteractionDto.serializer(), it) },
            "chatSessions.jsonl" to listOf(sessionDto("parent"), sessionDto("other")).joinToString("\n") { json.encodeToString(ChatSessionDto.serializer(), it) }
        )

        manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(setOf("instruction", "foreign-instruction", "started"), interactions.keys)
        assertEquals("instruction", interactions.getValue("started").instructionId)
    }

    @Test
    fun cancelledInstructionStreamingRoundtripResolvesLaterAcceptedUserAndKeepsDeletedChildAudit() = runTest {
        sessions["parent"] = session("parent")
        val accepted = entry("instruction", child = "deleted-child", timestamp = 2).copy(
            kind = "MESSAGE_FROM_PARENT", origin = "USER", content = "Accepted instruction"
        )
        val cancelled = entry("cancelled", child = "deleted-child", timestamp = 1).copy(
            kind = "INSTRUCTION_CANCELLED", origin = "SYSTEM", instructionId = accepted.id,
            content = "Cancelled instruction", title = "Deleted child", model = "Saved model"
        )
        interactions[accepted.id] = accepted
        interactions[cancelled.id] = cancelled
        val output = ByteArrayOutputStream()
        manager.export(null, options, output)
        interactions.clear()
        sessions.clear()

        repeat(2) { manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow() }

        assertEquals(accepted, interactions.getValue(accepted.id))
        assertEquals(cancelled, interactions.getValue(cancelled.id))
        assertEquals(2, interactions.size)
        assertNull(sessions["deleted-child"])
        coVerify(exactly = 0) { mcpManager.reload() }
    }

    @Test
    fun cancelledInstructionLegacySnapshotRoundtripResolvesUserAcrossBatches() = runTest {
        val accepted = dto("instruction", child = "deleted-child").copy(kind = "MESSAGE_FROM_PARENT", origin = "USER")
        val cancelled = dto("cancelled", child = "deleted-child").copy(
            kind = "INSTRUCTION_CANCELLED", origin = "SYSTEM", instructionId = accepted.id
        )
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            chatSessions = listOf(sessionDto("parent")),
            subAgentInteractions = listOf(cancelled) + (1..500).map { dto("history-$it", child = "deleted-child") } + accepted
        )
        val serialized = json.encodeToString(BackupSnapshot.serializer(), snapshot)
        assertEquals(snapshot, json.decodeFromString(BackupSnapshot.serializer(), serialized))

        repeat(2) { manager.import(ByteArrayInputStream(archive("snapshot.json" to serialized)), null).getOrThrow() }

        val restored = interactions.getValue(cancelled.id)
        assertEquals(cancelled.kind, restored.kind)
        assertEquals(accepted.id, restored.instructionId)
        assertEquals(cancelled.content, restored.content)
        assertEquals(502, interactions.size)
        assertNull(sessions["deleted-child"])
    }

    @Test
    fun cancelledInstructionImportsRejectForeignParentChildAndNonUserReferencesInBothFormats() = runTest {
        val accepted = dto("instruction").copy(kind = "MESSAGE_FROM_PARENT", origin = "USER")
        val foreignParent = dto("foreign-parent-instruction", parent = "other", child = "other-child")
            .copy(kind = "MESSAGE_FROM_PARENT", origin = "USER")
        val foreignChild = dto("foreign-child-instruction", child = "sibling")
            .copy(kind = "MESSAGE_FROM_PARENT", origin = "USER")
        val nonUser = accepted.copy(id = "parent-instruction", origin = "PARENT")
        val wrongKind = dto("progress")
        val cancelled = dto("cancelled").copy(
            kind = "INSTRUCTION_CANCELLED", origin = "SYSTEM", instructionId = accepted.id
        )
        val dtos = listOf(
            cancelled,
            cancelled.copy(id = "foreign-parent-reference", instructionId = foreignParent.id),
            cancelled.copy(id = "foreign-child-reference", instructionId = foreignChild.id),
            cancelled.copy(id = "non-user-reference", instructionId = nonUser.id),
            cancelled.copy(id = "wrong-kind-reference", instructionId = wrongKind.id),
            cancelled.copy(id = "missing-reference", instructionId = "missing"),
            cancelled.copy(id = "null-reference", instructionId = null),
            cancelled.copy(id = "blank-reference", instructionId = " "),
            cancelled.copy(id = "source-reference", sourceMessageId = "source"),
            accepted, foreignParent, foreignChild, nonUser, wrongKind
        )
        val sessionDtos = listOf(sessionDto("parent"), sessionDto("other"))
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            chatSessions = sessionDtos, subAgentInteractions = dtos
        )
        val inputs = listOf(
            archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot)),
            archive(
                "metadata.json" to metadata(),
                "subAgentInteractions.jsonl" to dtos.joinToString("\n") { json.encodeToString(SubAgentInteractionDto.serializer(), it) },
                "chatSessions.jsonl" to sessionDtos.joinToString("\n") { json.encodeToString(ChatSessionDto.serializer(), it) }
            )
        )

        inputs.forEach { input ->
            interactions.clear()
            sessions.clear()
            manager.import(ByteArrayInputStream(input), null).getOrThrow()

            assertEquals(setOf(accepted.id, foreignParent.id, foreignChild.id, nonUser.id, wrongKind.id, cancelled.id), interactions.keys)
            assertEquals(accepted.id, interactions.getValue(cancelled.id).instructionId)
            assertNull(interactions.getValue(cancelled.id).sourceMessageId)
        }
    }

    @Test
    fun separateArchivesCannotReassignDeletedChildAuditToAnotherParent() = runTest {
        interactions["original"] = entry("original", parent = "other", child = "deleted-child")
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            chatSessions = listOf(sessionDto("parent")),
            subAgentInteractions = listOf(dto("forged", child = "deleted-child"), dto("valid", child = "new-child"))
        )
        manager.import(ByteArrayInputStream(archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))), null).getOrThrow()
        assertEquals(setOf("original", "valid"), interactions.keys)

        val input = archive(
            "metadata.json" to metadata(),
            "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
            "subAgentInteractions.jsonl" to listOf(dto("forged-again", child = "deleted-child"), dto("valid-again", child = "new-child"))
                .joinToString("\n") { json.encodeToString(SubAgentInteractionDto.serializer(), it) }
        )
        manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(setOf("original", "valid", "valid-again"), interactions.keys)
        coVerify(exactly = 2) { interactionDao.getParentIdsByChildOnce("deleted-child") }
        coVerify(exactly = 0) { interactionDao.getAllOnce() }
    }

    @Test
    fun journalImportFailureStillCleansTemporaryFileAndDoesNotStartRuntime() = runTest {
        coEvery { interactionDao.insertIgnore(any()) } throws IllegalStateException("storage failed")
        val input = archive(
            "metadata.json" to metadata(),
            "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
            "subAgentInteractions.jsonl" to json.encodeToString(SubAgentInteractionDto.serializer(), dto("event"))
        )

        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
        coVerify(exactly = 0) { mcpManager.reload() }
    }

    @Test
    fun modelInputSnapshotRoundtripsWithoutChangingVisibleTextOrFullToolMetadata() = runTest {
        sessions["parent"] = session("parent").copy(executionGeneration = 12, autoDispatchBlockedReason = "plan_rejected")
        val snapshot = """{"version":1,"text":"Frozen project context at send time"}"""
        val toolArgs = """{"command":"${"long argument ".repeat(500)}","tool_title":"Original title"}"""
        val expected = listOf(
            AgentMessageEntity("user", "parent", "USER", "Visible request", 1, modelInputSnapshotJson = snapshot),
            AgentMessageEntity("tool_call", "parent", "TOOL", "Full result", 2,
                toolCallId = "call", toolName = "Bash", toolArgs = toolArgs, toolActionTitle = "Original title",
                toolStartedAtMs = 1_000, toolDurationMs = 321),
            AgentMessageEntity("assistant", "parent", "ASSISTANT", "Visible reply", 3, turnDurationMs = 654),
            AgentMessageEntity("resume", "parent", "USER", "Continue", 4,
                continuationOfMessageId = "user", continuationDraft = "Incomplete visible response")
        )
        expected.forEach { messages[it.id] = it }
        val output = ByteArrayOutputStream()

        manager.export(null, options, output)
        val files = readArchive(output.toByteArray())
        assertFalse(files.keys.any { it.contains("recovery", ignoreCase = true) || it.contains("approval", ignoreCase = true) })
        assertFalse(files.getValue("chatSessions.jsonl").contains("executionGeneration"))
        assertFalse(files.getValue("chatSessions.jsonl").contains("autoDispatchBlockedReason"))
        messages.clear()
        manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow()

        assertEquals(expected, messages.values.toList())
        assertEquals("Visible request", messages.getValue("user").toUIMessage().content)
        assertEquals(snapshot, messages.getValue("user").modelInputSnapshotJson)
        assertEquals("user", messages.getValue("resume").toUIMessage().continuationOfMessageId)
        assertEquals("Incomplete visible response", messages.getValue("resume").toUIMessage().continuationDraft)
        assertEquals(13L, sessions.getValue("parent").executionGeneration)
        assertEquals("backup_restore", sessions.getValue("parent").autoDispatchBlockedReason)
        coVerify(exactly = 1) { recoveryRepository.invalidateSession("parent", "backup_restore", true) }
        coVerify(exactly = 0) { recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { recoveryRepository.claim(any(), any(), any()) }
    }

    @Test
    fun oldBackupsNeverReviveCurrentOrLegacyRunningToolMarkers() = runTest {
        val dtos = listOf(
            AgentMessageDto("tool_current", "parent", "TOOL", "${SessionUseCase.PENDING_TOOL_MARKER} waiting", 1,
                toolCallId = "current", toolName = "Bash", toolArgs = "full current args", toolStartedAtMs = 10),
            AgentMessageDto("tool_legacy", "parent", "TOOL", "${SessionUseCase.LEGACY_PENDING_TOOL_MARKER} waiting", 2,
                toolCallId = "legacy", toolName = "Bash", toolArgs = "full legacy args", toolDurationMs = 30),
            AgentMessageDto("user", "parent", "USER", "${SessionUseCase.PENDING_TOOL_MARKER} is visible user text", 3)
        )
        val snapshot = BackupSnapshot(schemaVersion = 69, createdAt = 1,
            chatSessions = listOf(sessionDto("parent")), agentMessages = dtos)
        val inputs = listOf(
            archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot)),
            archive("metadata.json" to metadata(),
                "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
                "messages.jsonl" to dtos.joinToString("\n") { json.encodeToString(AgentMessageDto.serializer(), it) })
        )
        for (input in inputs) {
            sessions.clear()
            messages.clear()
            manager.import(ByteArrayInputStream(input), null).getOrThrow()

            for (id in listOf("tool_current", "tool_legacy")) {
                assertEquals(SessionUseCase.INTERRUPTED_TOOL_TEXT, messages.getValue(id).content)
                assertTrue(messages.getValue(id).isError)
            }
            assertEquals("full current args", messages.getValue("tool_current").toolArgs)
            assertEquals(10L, messages.getValue("tool_current").toolStartedAtMs)
            assertEquals("full legacy args", messages.getValue("tool_legacy").toolArgs)
            assertEquals(30L, messages.getValue("tool_legacy").toolDurationMs)
            assertEquals(dtos.last().content, messages.getValue("user").content)
            assertTrue(messages.values.all { it.modelInputSnapshotJson == null })
            assertEquals(0L, sessions.getValue("parent").executionGeneration)
        }
        coVerify(exactly = 0) { recoveryRepository.invalidateSession(any(), any(), any()) }
    }

    @Test
    fun outOfOrderRestoreFencesExistingSessionBeforeMessagesAndKeepsNewGeneration() = runTest {
        sessions["parent"] = session("parent").copy(executionGeneration = 41)
        val message = AgentMessageDto("user", "parent", "USER", "Restored", 1)
        val encodedSession = json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent"))
            .removeSuffix("}") + ",\"executionGeneration\":0,\"autoDispatchBlockedReason\":null}"
        val input = archive(
            "metadata.json" to metadata(),
            "messages.jsonl" to json.encodeToString(AgentMessageDto.serializer(), message),
            "chatSessions.jsonl" to encodedSession
        )

        manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(42L, sessions.getValue("parent").executionGeneration)
        assertEquals("backup_restore", sessions.getValue("parent").autoDispatchBlockedReason)
        coVerifyOrder {
            recoveryRepository.invalidateSession("parent", "backup_restore", true)
            messageDao.insertAll(any())
            sessionDao.upsertAll(match { it.single().executionGeneration == 42L })
        }
        coVerify(exactly = 1) { recoveryRepository.invalidateSession("parent", "backup_restore", true) }
    }

    @Test
    fun generationChangeDuringRestoreBlocksSubsequentMessageWrites() = runTest {
        coEvery { sessionDao.upsertAll(any()) } answers {
            assertTrue(restoreTransactionDepth > 0)
            firstArg<List<ChatSessionEntity>>().forEach { sessions[it.id] = it.copy(executionGeneration = it.executionGeneration + 1) }
        }
        val input = archive(
            "metadata.json" to metadata(),
            "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
            "messages.jsonl" to json.encodeToString(AgentMessageDto.serializer(), AgentMessageDto("user", "parent", "USER", "Restored", 1))
        )

        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        assertEquals(1L, sessions.getValue("parent").executionGeneration)
        coVerify(exactly = 0) { messageDao.insertAll(any()) }
    }

    @Test
    fun holdOrPendingRequestChangedDuringRestoreBlocksSubsequentMessageWrites() = runTest {
        val input = archive(
            "metadata.json" to metadata(),
            "chatSessions.jsonl" to json.encodeToString(ChatSessionDto.serializer(), sessionDto("parent")),
            "messages.jsonl" to json.encodeToString(AgentMessageDto.serializer(), AgentMessageDto("user", "parent", "USER", "Restored", 1))
        )
        coEvery { sessionDao.upsertAll(any()) } answers {
            firstArg<List<ChatSessionEntity>>().forEach { sessions[it.id] = it.copy(autoDispatchBlockedReason = "plan_rejected") }
        }
        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)
        coEvery { sessionDao.upsertAll(any()) } answers {
            firstArg<List<ChatSessionEntity>>().forEach { sessions[it.id] = it }
        }
        sessions.clear()
        coEvery { sessionDao.hasPendingRecovery("parent") } returns true
        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        coVerify(exactly = 0) { messageDao.insertAll(any()) }
    }

    @Test
    fun legacyRestoreUsesBoundedTransactionsForSessionMessageAndTodoBatches() = runTest {
        val count = 1001
        val snapshot = BackupSnapshot(
            schemaVersion = 70, createdAt = 1, chatSessions = listOf(sessionDto("parent")),
            agentMessages = (1..count).map { AgentMessageDto("message-$it", "parent", "USER", "Request", it.toLong()) },
            todoItems = (1..count).map { TodoItemDto("todo-$it", "parent", "Historical todo", createdAt = 1, updatedAt = 1) }
        )
        val todoBatchSizes = mutableListOf<Int>()
        coEvery { todoDao.upsertAll(any()) } answers {
            assertTrue(restoreTransactionDepth > 0)
            todoBatchSizes.add(firstArg<List<TodoItemEntity>>().size)
            Unit
        }
        val input = archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))

        val result = manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(count, result.agentMessages)
        assertEquals(count, result.todoItems)
        assertEquals(count, messages.size)
        assertEquals(listOf(500, 500, 1), todoBatchSizes)
        coVerify(exactly = 7) { agentDatabase.withTransaction(any<suspend () -> Unit>()) }
        coVerify(exactly = 3) { messageDao.insertAll(match { it.size <= 500 }) }
    }

    @Test
    fun projectHistoryBackupContainsOnlyStateAndArchivesAndMergesBothFormatsWithoutBindings() = runTest {
        val source = ProjectMemoryJournal(temporaryFolder.newFolder("source-journal"))
        val identity = ProjectMemoryIdentity("local_proot", projectRoot = "/workspace/project", profileId = "profile")
        val project = source.registerProject(identity, "Project")
        val active = source.append(project.id, "Historical note", "Verified historical fact", sourceSessionId = "parent")
        val old = source.append(project.id, "Archived note", "Archived body")
        val archived = source.archive(old.id, old.revision)
        source.bindSessionProject("parent", identity, project.id)
        source.setWorkspaceProject(identity, project.id)
        source.checkpointProgress("pending-turn", identity,
            listOf(ProjectProgressDraft(project.id, "Pending checkpoint must not be backed up")), listOf("term-1"),
            sourceSessionId = "parent")
        useProjectJournal(source)
        val output = ByteArrayOutputStream()

        manager.export(null, options, output)

        val files = readArchive(output.toByteArray())
        val history = files.getValue("project_journal_history.json")
        assertEquals(source.exportHistory(), history)
        assertFalse(history.contains("Pending checkpoint"))
        assertFalse(history.contains("pendingTabs"))
        assertFalse(history.contains("hasProjectBinding"))
        assertFalse(files.keys.any { it.startsWith("session-") || it.startsWith("workspace-") || it.startsWith("progress-") })
        val legacy = BackupSnapshot(schemaVersion = 70, createdAt = 1, projectJournalHistoryJson = history)
        for ((index, input) in listOf(output.toByteArray(), archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), legacy))).withIndex()) {
            val targetDir = temporaryFolder.newFolder("target-journal-$index")
            val target = ProjectMemoryJournal(targetDir)
            val existing = target.registerProject(identity.copy(projectRoot = "/other"), "Existing project")
            useProjectJournal(target)
            repeat(2) { manager.import(ByteArrayInputStream(input), null).getOrThrow() }

            assertEquals(setOf(existing.id, project.id), target.listProjects().map { it.id }.toSet())
            assertEquals(active, target.read(active.id))
            assertEquals(archived, target.read(archived.id))
            assertFalse(target.hasSessionProjectBinding("parent", identity))
            assertFalse(target.hasWorkspaceProjectBinding(identity))
            assertFalse(targetDir.listFiles().orEmpty().any { it.name.startsWith("progress-") })
        }
        verify(exactly = 0) { projectMemoryJournal.bindSessionProject(any(), any(), any()) }
        verify(exactly = 0) { projectMemoryJournal.recoverProgress(any()) }
        coVerify(exactly = 0) { recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mcpManager.reload() }
    }

    @Test
    fun projectHistoryImportRejectsConflictsAndKeepsUserEdits() = runTest {
        val journal = ProjectMemoryJournal(temporaryFolder.newFolder("journal"))
        val project = journal.registerProject(ProjectMemoryIdentity("local_proot", projectRoot = "/project", profileId = "profile"), "Project")
        val note = journal.append(project.id, "Original note", "Original body")
        val incoming = journal.exportHistory()
        val edited = journal.edit(note.id, note.revision, "User edited note", "User body")
        val current = journal.exportHistory()
        useProjectJournal(journal)
        val input = archive("metadata.json" to metadata(), "project_journal_history.json" to incoming)

        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        assertEquals(current, journal.exportHistory())
        assertEquals(edited, journal.read(note.id))
        verify(exactly = 0) { projectMemoryJournal.importHistory(any(), any()) }
    }

    @Test
    fun disabledChatAndSingleSessionExportsDoNotReadProjectHistory() = runTest {
        val output = ByteArrayOutputStream()
        manager.export(null, options.copy(chatHistory = false), output)
        assertFalse("project_journal_history.json" in readArchive(output.toByteArray()))
        sessions["parent"] = session("parent")
        val sessionOutput = ByteArrayOutputStream()
        manager.exportSession("parent", sessionOutput)

        assertFalse("project_journal_history.json" in readArchive(sessionOutput.toByteArray()))
        verify(exactly = 0) { projectMemoryJournal.exportHistory() }
    }

    @Test
    fun projectHistoryValidationRejectsUuidScopePathsAndRuntimePayloads() = runTest {
        val source = ProjectMemoryJournal(temporaryFolder.newFolder("source"))
        val project = source.registerProject(ProjectMemoryIdentity("local_proot", projectRoot = "/project", profileId = "profile"), "Project")
        source.append(project.id, "Historical note", "Verified historical body")
        val history = source.exportHistory()
        val target = ProjectMemoryJournal(temporaryFolder.newFolder("target"))
        val before = target.exportHistory()
        useProjectJournal(target)
        val invalid = listOf(
            history.replace(project.id, "../../outside"),
            history.replace("\"host\":\"\"", "\"host\":\"unexpected-host\""),
            history.replace("\"projectRoot\":\"/project\"", "\"projectRoot\":\"/../outside\""),
            history.removeSuffix("}") + ",\"sessionBindings\":{}}",
            history.removeSuffix("}") + ",\"pendingRequests\":[{\"approval\":true}]}"
        )
        for (text in invalid) {
            val input = archive("metadata.json" to metadata(), "project_journal_history.json" to text)
            assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)
            assertEquals(before, target.exportHistory())
        }
        verify(exactly = 0) { projectMemoryJournal.importHistory(any(), any()) }
        coVerify(exactly = 0) { recoveryRepository.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun duplicateAndOversizedProjectHistoryEntriesAreRejectedBeforeImport() = runTest {
        val history = """{"schemaVersion":1,"projects":[],"entries":[],"archives":[]}"""
        val duplicate = archive("metadata.json" to metadata(), "project_journal_history.json" to history, "project_journal_history.json" to history)
        val oversized = archive("metadata.json" to metadata(), "project_journal_history.json" to " ".repeat(16 * 1024 * 1024 + 1))

        assertTrue(manager.import(ByteArrayInputStream(duplicate), null).isFailure)
        assertTrue(manager.import(ByteArrayInputStream(oversized), null).isFailure)

        verify(exactly = 0) { projectMemoryJournal.importHistory(any(), any()) }
    }

    @Test
    fun projectHistoryDoesNotFollowJournalRootSymlink() = runTest {
        val outside = temporaryFolder.newFolder("outside")
        val source = ProjectMemoryJournal(outside)
        val project = source.registerProject(ProjectMemoryIdentity("local_proot", projectRoot = "/project", profileId = "profile"), "Project")
        source.append(project.id, "Existing history", "Must remain untouched")
        val before = source.exportHistory()
        val link = File(temporaryFolder.root, "linked-journal")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        useProjectJournal(ProjectMemoryJournal(link))
        val input = archive("metadata.json" to metadata(), "project_journal_history.json" to before)

        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        assertEquals(before, source.exportHistory())
        verify(exactly = 0) { projectMemoryJournal.importHistory(any(), any()) }
    }

    @Test
    fun projectHistoryVersionChangedAfterValidationPreventsImport() = runTest {
        val target = ProjectMemoryJournal(temporaryFolder.newFolder("target"))
        val identity = ProjectMemoryIdentity("local_proot", projectRoot = "/project", profileId = "profile")
        val project = target.registerProject(identity, "Project")
        val before = target.exportHistory()
        useProjectJournal(target)
        every { projectMemoryJournal.importHistory(any(), any()) } answers {
            target.append(project.id, "Concurrent note", "User note created after validation")
            target.importHistory(firstArg(), secondArg())
        }
        val input = archive("metadata.json" to metadata(), "project_journal_history.json" to before)

        assertTrue(manager.import(ByteArrayInputStream(input), null).isFailure)

        assertEquals("Concurrent note", target.listRecent(project.id).single().summary)
    }

    private fun useProjectJournal(journal: ProjectMemoryJournal) {
        every { projectMemoryJournal.exportHistory() } answers { journal.exportHistory() }
        every { projectMemoryJournal.validateHistory(any()) } answers { journal.validateHistory(firstArg()) }
        every { projectMemoryJournal.historyVersion() } answers { journal.historyVersion() }
        every { projectMemoryJournal.validateImportHistory(any(), any()) } answers { journal.validateImportHistory(firstArg(), secondArg()) }
        every { projectMemoryJournal.importHistory(any(), any()) } answers { journal.importHistory(firstArg(), secondArg()) }
    }

    private fun session(id: String, parent: String? = null) = ChatSessionEntity(id, id, 1, 1, parentId = parent)
    private fun sessionDto(id: String, parent: String? = null) = ChatSessionDto(id, id, 1, 1, parentId = parent)
    private fun entry(id: String, parent: String = "parent", child: String = "child", timestamp: Long = 1) =
        SubAgentInteractionEntity(id, parent, child, "MESSAGE_FROM_SUB", "Body", timestamp)
    private fun dto(id: String, parent: String = "parent", child: String = "child") =
        SubAgentInteractionDto(id, parent, child, "MESSAGE_FROM_SUB", "Body", 1)
    private fun metadata() = json.encodeToString(BackupMetadata.serializer(), BackupMetadata(AgentDatabase.SCHEMA_VERSION, createdAt = 1))

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { (name, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun readArchive(bytes: ByteArray): Map<String, String> = buildMap {
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                put(entry.name, String(tar.readBytes(), Charsets.UTF_8))
                entry = tar.nextEntry
            }
        }
    }
}
