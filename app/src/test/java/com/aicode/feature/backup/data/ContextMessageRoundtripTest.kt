package com.aicode.feature.backup.data

import android.content.Context
import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsSnapshot
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

class ContextMessageRoundtripTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context = mockk<Context>(relaxed = true)
    private val messageDao = mockk<AgentMessageDao>(relaxed = true)
    private val sessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val themeSettings = mockk<ThemeSettingsRepository>(relaxed = true)
    private val backgroundSettings = mockk<BackgroundSettingsRepository>(relaxed = true)
    private val syncSettings = mockk<SyncSettingsRepository>(relaxed = true)
    private val generalSettings = mockk<GeneralSettingsRepository>(relaxed = true)
    private val chatBubbleSettings = mockk<ChatBubbleSettingsRepository>(relaxed = true)
    private val projectMemoryJournal = mockk<ProjectMemoryJournal>(relaxed = true)
    private val recoveryRepository = mockk<RecoveryRepository>(relaxed = true)
    private val agentDatabase = mockk<AgentDatabase>()
    private val manager = BackupManagerImpl(
        context = context,
        aiProviderDao = mockk(relaxed = true),
        remoteConnectionDao = mockk(relaxed = true),
        chatSessionDao = sessionDao,
        agentMessageDao = messageDao,
        todoItemDao = mockk(relaxed = true),
        mcpConfigRepository = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        permissionRulesRepository = mockk(relaxed = true),
        themeSettingsRepository = themeSettings,
        keepaliveSettingsRepository = mockk(relaxed = true),
        liveNotificationSettingsRepository = mockk(relaxed = true),
        approvalNotificationSettingsRepository = mockk(relaxed = true),
        screenOnSettingsRepository = mockk(relaxed = true),
        agentSoundSettingsRepository = mockk(relaxed = true),
        generalSettingsRepository = generalSettings,
        logSettingsRepository = mockk(relaxed = true),
        visionModelSettingsRepository = mockk(relaxed = true),
        compactionModelSettingsRepository = mockk(relaxed = true),
        syncSettingsRepository = syncSettings,
        workspaceRepository = mockk(relaxed = true),
        backgroundSettingsRepository = backgroundSettings,
        chatBubbleSettingsRepository = chatBubbleSettings,
        subAgentInteractionDao = mockk(relaxed = true),
        projectMemoryJournal = projectMemoryJournal,
        recoveryRepository = recoveryRepository,
        agentDatabase = agentDatabase,
        pluginArchives = mockk(relaxed = true),
        backupContent = mockk(relaxed = true)
    )
    private val noDataOptions = BackupOptions(
        providers = false,
        remoteConnections = false,
        chatHistory = false,
        mcpServers = false,
        permissionRules = false,
        appSettings = false
    )
    private val breakdown = ContextUsageBreakdown(120, 30, 1_000, 200, 4_096)
    private val message = AgentMessageEntity(
        id = "message",
        sessionId = "session",
        role = "ASSISTANT",
        content = "Reply",
        timestamp = 123,
        inputTokens = 8_000,
        cachedInputTokens = 6_000,
        contextInputTokens = 12_345,
        contextInputBudget = 100_000,
        contextEstimated = false,
        contextBreakdownJson = json.encodeToString(ContextUsageBreakdown.serializer(), breakdown)
    )

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { agentDatabase.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            arg<suspend () -> Unit>(1).invoke()
        }
        every { context.cacheDir } returns temporaryFolder.root
        every { projectMemoryJournal.exportHistory() } returns """{"schemaVersion":1,"projects":[],"entries":[],"archives":[]}"""
        coEvery { recoveryRepository.invalidateSession(any(), "backup_restore", true) } returns true
        every { syncSettings.snapshot() } returns SyncSettingsSnapshot("", true, 5)
        coEvery { themeSettings.snapshot() } returns "DARK"
        coEvery { chatBubbleSettings.userStyleSnapshot() } returns ChatBubbleStyle.ORIGINAL
        coEvery { chatBubbleSettings.assistantStyleSnapshot() } returns ChatBubbleStyle.DOCUMENT
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun persistAndUiMappingPreserveContextAndUnknownDefaults() = runTest {
        val database = mockk<AgentDatabase> {
            every { invalidationTracker } returns mockk(relaxed = true)
        }
        val saved = mutableListOf<AgentMessageEntity>()
        coEvery { messageDao.insert(any()) } answers { saved += firstArg<AgentMessageEntity>() }
        val persistence = MessagePersistenceUseCase(messageDao, database)

        persistence.persist(
            sessionId = message.sessionId,
            role = MessageRole.ASSISTANT,
            content = message.content,
            id = message.id,
            inputTokens = message.inputTokens,
            cachedInputTokens = message.cachedInputTokens,
            contextInputTokens = message.contextInputTokens,
            contextInputBudget = message.contextInputBudget,
            contextEstimated = message.contextEstimated,
            contextBreakdown = breakdown
        )
        persistence.persist(
            sessionId = message.sessionId,
            role = MessageRole.ASSISTANT,
            content = message.content,
            inputTokens = 99_999
        )

        assertEquals(2, saved.size)
        assertContext(message, saved[0])
        assertContext(message.copy(contextInputTokens = null, contextInputBudget = null, contextEstimated = true, contextBreakdownJson = null), saved[1])
        assertEquals(99_999, saved[1].inputTokens)
    }

    @Test
    fun streamingExportImportRoundtripPreservesEachRoundSnapshot() = runTest {
        val expected = listOf(
            message,
            message.copy(id = "estimated", timestamp = 124, contextInputTokens = 20_000, contextEstimated = true),
            message.copy(id = "unknown", timestamp = 125, contextInputTokens = null, contextInputBudget = null, contextEstimated = true, contextBreakdownJson = null),
            message.copy(id = "zero", timestamp = 126, contextInputTokens = 0),
            message.copy(id = "future", timestamp = 127, contextBreakdownJson = """{"systemTokens":12,"futureTokens":999}"""),
            message.copy(id = "damaged", timestamp = 128, contextBreakdownJson = "{broken")
        )
        coEvery { messageDao.getPageAfter(any(), any(), any()) } returns expected andThen emptyList()
        val output = ByteArrayOutputStream()
        manager.export(null, noDataOptions.copy(chatHistory = true), output)
        val restored = captureRestoredMessages()

        val result = manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow()

        assertEquals(expected.size, result.agentMessages)
        assertEquals(expected.map { it.id }, restored.map { it.id })
        expected.zip(restored).forEach { (original, imported) -> assertContext(original, imported) }
    }

    @Test
    fun singleSessionExportImportRoundtripPreservesContext() = runTest {
        coEvery { sessionDao.getById(message.sessionId) } returns ChatSessionEntity(
            id = message.sessionId, title = "Chat", createdAt = 1, updatedAt = 2
        )
        coEvery { messageDao.getPageBySessionAfter(message.sessionId, any(), any(), any()) } returns listOf(message) andThen emptyList()
        val output = ByteArrayOutputStream()
        manager.exportSession(message.sessionId, output)
        val entries = readArchive(output.toByteArray())
        val metadata = json.decodeFromString<BackupMetadata>(entries.getValue("metadata.json"))
        assertFalse(metadata.includesAppSettings)
        assertNull(metadata.chatTextScale)
        assertNull(metadata.chatBackgroundAlpha)
        assertNull(metadata.typewriterCharsPerSecond)
        assertNull(metadata.userChatBubbleStyleId)
        assertNull(metadata.assistantChatBubbleStyleId)
        coVerify(exactly = 0) { generalSettings.typewriterCharsPerSecond() }
        coVerify(exactly = 0) { chatBubbleSettings.userStyleSnapshot() }
        coVerify(exactly = 0) { chatBubbleSettings.assistantStyleSnapshot() }
        val restored = captureRestoredMessages()

        val result = manager.import(ByteArrayInputStream(output.toByteArray()), null).getOrThrow()

        assertEquals(1, result.agentMessages)
        assertContext(message, restored.single())
    }

    @Test
    fun legacySnapshotRoundtripPreservesContext() = runTest {
        val dto = AgentMessageDto(
            id = message.id,
            sessionId = message.sessionId,
            role = message.role,
            content = message.content,
            timestamp = message.timestamp,
            contextInputTokens = message.contextInputTokens,
            contextInputBudget = message.contextInputBudget,
            contextEstimated = message.contextEstimated,
            contextBreakdownJson = message.contextBreakdownJson
        )
        val restoredDto = json.decodeFromString<AgentMessageDto>(json.encodeToString(AgentMessageDto.serializer(), dto))
        assertEquals(dto, restoredDto)
        val snapshot = BackupSnapshot(schemaVersion = 66, createdAt = 0, agentMessages = listOf(restoredDto))
        val archive = archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
        val restored = captureRestoredMessages()

        val result = manager.import(ByteArrayInputStream(archive), null).getOrThrow()

        assertEquals(1, result.agentMessages)
        assertContext(message, restored.single())
    }

    @Test
    fun oldMessagesAndExplicitNullsStayUnknownInBothBackupFormats() = runTest {
        val restored = captureRestoredMessages()
        for (fields in listOf("", ",\"contextInputTokens\":null,\"contextInputBudget\":null,\"contextBreakdownJson\":null")) {
            val oldMessage = """{"id":"old","sessionId":"session","role":"ASSISTANT","content":"old","timestamp":1,"inputTokens":99999$fields}"""
            val dto = json.decodeFromString<AgentMessageDto>(oldMessage)
            assertNull(dto.contextInputTokens)
            assertNull(dto.contextInputBudget)
            assertTrue(dto.contextEstimated)
            assertNull(dto.contextBreakdownJson)
            val metadata = """{"schemaVersion":64,"createdAt":0}"""
            val snapshot = """{"schemaVersion":64,"createdAt":0,"agentMessages":[$oldMessage]}"""
            for (backup in listOf(
                archive("metadata.json" to metadata, "messages.jsonl" to "$oldMessage\n"),
                archive("snapshot.json" to snapshot)
            )) {
                assertEquals(1, manager.import(ByteArrayInputStream(backup), null).getOrThrow().agentMessages)
            }
        }
        assertEquals(4, restored.size)
        restored.forEach {
            assertContext(message.copy(contextInputTokens = null, contextInputBudget = null, contextEstimated = true, contextBreakdownJson = null), it)
        }
    }

    @Test
    fun titlesAndTimingSurviveStreamingAndSingleSessionExportImport() = runTest {
        val titled = AgentMessageEntity(
            id = "tool_call", sessionId = "session", role = "TOOL", content = "Raw output", timestamp = 123,
            toolCallId = "call", toolName = "readFile", toolArgs = "{\"path\":\"/config.json\"}",
            isError = true, toolActionTitle = "Read configuration", toolStartedAtMs = 1_700_000_000_000,
            toolDurationMs = 125
        )
        val expected = listOf(
            titled,
            titled.copy(id = "tool_old", toolCallId = "old", timestamp = 124, toolActionTitle = null, toolStartedAtMs = null, toolDurationMs = null),
            titled.copy(id = "tool_started", toolCallId = "started", timestamp = 125, toolDurationMs = null),
            titled.copy(id = "tool_finished", toolCallId = "finished", timestamp = 126, toolStartedAtMs = null, toolDurationMs = 0),
            AgentMessageEntity("assistant", "session", "ASSISTANT", "Reply", 127, turnDurationMs = 4_000)
        )
        coEvery { messageDao.getPageAfter(any(), any(), any()) } returns expected andThen emptyList()
        coEvery { sessionDao.getById("session") } returns ChatSessionEntity("session", "Chat", 1, 2)
        coEvery { messageDao.getPageBySessionAfter("session", any(), any(), any()) } returns expected andThen emptyList()
        val restored = captureRestoredMessages()
        val allOutput = ByteArrayOutputStream()
        val sessionOutput = ByteArrayOutputStream()

        manager.export(null, noDataOptions.copy(chatHistory = true), allOutput)
        manager.exportSession("session", sessionOutput)
        for (backup in listOf(allOutput, sessionOutput)) {
            assertEquals(expected.size, manager.import(ByteArrayInputStream(backup.toByteArray()), null).getOrThrow().agentMessages)
        }

        assertEquals(expected + expected, restored)
        assertEquals("Read configuration", restored.first().toUIMessage().toolActionTitle)
        assertNull(restored[1].toUIMessage().toolActionTitle)
        (expected + expected).zip(restored).forEach { (original, imported) -> assertTiming(original, imported) }
    }

    @Test
    fun titlesAndTimingSurviveLegacySnapshotAndDtoRoundtrip() = runTest {
        val dto = AgentMessageDto(
            id = "tool_call", sessionId = "session", role = "TOOL", content = "Raw output", timestamp = 123,
            toolCallId = "call", toolName = "readFile", toolArgs = "{\"path\":\"/config.json\"}",
            isError = true, toolActionTitle = "Read configuration", toolStartedAtMs = 1_700_000_000_000,
            toolDurationMs = 125
        )
        val assistant = AgentMessageDto("assistant", "session", "ASSISTANT", "Reply", 124, turnDurationMs = 4_000)
        val roundtripped = json.decodeFromString<AgentMessageDto>(json.encodeToString(AgentMessageDto.serializer(), dto))
        assertEquals(dto, roundtripped)
        val snapshot = BackupSnapshot(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 0, agentMessages = listOf(roundtripped, assistant))
        val backup = archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
        val restored = captureRestoredMessages()

        assertEquals(2, manager.import(ByteArrayInputStream(backup), null).getOrThrow().agentMessages)

        val expectedTool = AgentMessageEntity(
            id = dto.id, sessionId = dto.sessionId, role = dto.role, content = dto.content, timestamp = dto.timestamp,
            toolCallId = dto.toolCallId, toolName = dto.toolName, toolArgs = dto.toolArgs,
            isError = dto.isError, toolActionTitle = dto.toolActionTitle,
            toolStartedAtMs = dto.toolStartedAtMs, toolDurationMs = dto.toolDurationMs
        )
        assertEquals(expectedTool, restored.first())
        val expectedAssistant = AgentMessageEntity("assistant", "session", "ASSISTANT", "Reply", 124, turnDurationMs = 4_000)
        assertEquals(expectedAssistant, restored[1])
        assertTiming(expectedAssistant, restored[1])
        assertTiming(expectedTool, restored.first())
    }

    @Test
    fun missingAndNullToolActionTitlesStayNullInOldBackupFormats() = runTest {
        val restored = captureRestoredMessages()
        for (titleField in listOf("", ",\"toolActionTitle\":null")) {
            val oldMessage = """{"id":"tool_call","sessionId":"session","role":"TOOL","content":"Raw output","timestamp":123,"toolCallId":"call","toolName":"readFile","toolArgs":"{}"$titleField}"""
            assertNull(json.decodeFromString<AgentMessageDto>(oldMessage).toolActionTitle)
            val metadata = """{"schemaVersion":66,"createdAt":0}"""
            val snapshot = """{"schemaVersion":66,"createdAt":0,"agentMessages":[$oldMessage]}"""
            for (backup in listOf(
                archive("metadata.json" to metadata, "messages.jsonl" to "$oldMessage\n"),
                archive("snapshot.json" to snapshot)
            )) {
                assertEquals(1, manager.import(ByteArrayInputStream(backup), null).getOrThrow().agentMessages)
            }
        }

        assertEquals(4, restored.size)
        restored.forEach {
            assertNull(it.toolActionTitle)
            assertNull(it.toUIMessage().toolActionTitle)
            assertEquals("{}", it.toolArgs)
            assertEquals("Raw output", it.content)
            assertEquals(123L, it.timestamp)
        }
    }

    @Test
    fun missingAndNullTimingInOldBackupsStayUnknownAndKeepStoredTitle() = runTest {
        val restored = captureRestoredMessages()
        for (fields in listOf("", ",\"toolStartedAtMs\":null,\"toolDurationMs\":null,\"turnDurationMs\":null")) {
            val oldTool = """{"id":"tool_call","sessionId":"session","role":"TOOL","content":"Raw output","timestamp":123,"toolCallId":"call","toolActionTitle":"Saved title"$fields}"""
            val oldAssistant = """{"id":"assistant","sessionId":"session","role":"ASSISTANT","content":"Reply","timestamp":124$fields}"""
            for (encoded in listOf(oldTool, oldAssistant)) {
                val dto = json.decodeFromString<AgentMessageDto>(encoded)
                assertNull(dto.toolStartedAtMs)
                assertNull(dto.toolDurationMs)
                assertNull(dto.turnDurationMs)
            }
            val metadata = """{"schemaVersion":67,"createdAt":0}"""
            val snapshot = """{"schemaVersion":67,"createdAt":0,"agentMessages":[$oldTool,$oldAssistant]}"""
            for (backup in listOf(
                archive("metadata.json" to metadata, "messages.jsonl" to "$oldTool\n$oldAssistant\n"),
                archive("snapshot.json" to snapshot)
            )) {
                assertEquals(2, manager.import(ByteArrayInputStream(backup), null).getOrThrow().agentMessages)
            }
        }

        assertEquals(8, restored.size)
        restored.forEach {
            assertNull(it.toolStartedAtMs)
            assertNull(it.toolDurationMs)
            assertNull(it.turnDurationMs)
            val ui = it.toUIMessage()
            assertNull(ui.toolStartedAtMs)
            assertNull(ui.toolDurationMs)
            assertNull(ui.turnDurationMs)
            if (it.role == "TOOL") assertEquals("Saved title", it.toolActionTitle)
        }
    }

    @Test
    fun breakdownUiDecodeIgnoresUnknownFieldsAndKeepsMalformedSnapshotsUnknown() {
        val future = message.copy(contextBreakdownJson = """{"chatTokens":42,"futureTokens":999}""").toUIMessage()
        assertEquals(ContextUsageBreakdown(chatTokens = 42), future.contextBreakdown)
        assertEquals(message.contextInputTokens, future.contextInputTokens)
        assertEquals(message.contextInputBudget, future.contextInputBudget)
        assertFalse(future.contextEstimated)
        for (raw in listOf(null, "", "{broken", "[]", "null", """{"chatTokens":"bad"}""")) {
            val ui = message.copy(contextBreakdownJson = raw).toUIMessage()
            assertNull(ui.contextBreakdown)
            assertEquals(message.contextInputTokens, ui.contextInputTokens)
        }
        assertEquals(breakdown, message.toUIMessage().contextBreakdown)
        assertTrue(breakdown.estimatedTotal != message.contextInputTokens)
    }

    @Test
    fun typewriterExportIsScopedToAppSettings() = runTest {
        coEvery { generalSettings.typewriterCharsPerSecond() } returns 350
        val withSettings = ByteArrayOutputStream()
        val withoutSettings = ByteArrayOutputStream()

        manager.export(null, noDataOptions.copy(appSettings = true), withSettings)
        manager.export(null, noDataOptions, withoutSettings)

        val included = json.decodeFromString<BackupMetadata>(readArchive(withSettings.toByteArray()).getValue("metadata.json"))
        val excluded = json.decodeFromString<BackupMetadata>(readArchive(withoutSettings.toByteArray()).getValue("metadata.json"))
        assertTrue(included.includesAppSettings)
        assertEquals(350, included.typewriterCharsPerSecond)
        assertFalse(excluded.includesAppSettings)
        assertNull(excluded.typewriterCharsPerSecond)
        coVerify(exactly = 1) { generalSettings.typewriterCharsPerSecond() }
    }

    @Test
    fun streamingAndLegacyImportsRestoreTypewriterSetting() = runTest {
        for (speed in listOf(10, 30, 120, 350)) {
            val metadata = BackupMetadata(
                schemaVersion = 66, createdAt = 0, includesAppSettings = true, typewriterCharsPerSecond = speed
            )
            val snapshot = BackupSnapshot(
                schemaVersion = 66, createdAt = 0, themeMode = "DARK", typewriterCharsPerSecond = speed
            )
            val backups = listOf(
                archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata)),
                archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
            )

            backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }

            coVerify(exactly = 2) { generalSettings.setTypewriterCharsPerSecond(speed) }
        }
        coVerify(exactly = 8) { generalSettings.setTypewriterCharsPerSecond(any()) }
    }

    @Test
    fun missingAndNullTypewriterSettingsDoNotResetCurrentValue() = runTest {
        for (fields in listOf("", ",\"typewriterCharsPerSecond\":null")) {
            val oldSettings = """{"schemaVersion":65,"createdAt":0,"themeMode":"DARK"$fields}"""
            val backups = listOf(archive("metadata.json" to oldSettings), archive("snapshot.json" to oldSettings))

            backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }
        }

        coVerify(exactly = 0) { generalSettings.setTypewriterCharsPerSecond(any()) }
    }

    @Test
    fun importWithoutSettingsScopeIgnoresTypewriterSetting() = runTest {
        val metadata = BackupMetadata(
            schemaVersion = 66, createdAt = 0, themeMode = "DARK", includesAppSettings = false, typewriterCharsPerSecond = 120
        )
        val backup = archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata))

        manager.import(ByteArrayInputStream(backup), null).getOrThrow()

        coVerify(exactly = 0) { generalSettings.setTypewriterCharsPerSecond(any()) }
    }

    @Test
    fun reasoningPreferenceExportIsScopedToAppSettings() = runTest {
        coEvery { generalSettings.autoExpandReasoning() } returns true
        val included = ByteArrayOutputStream()
        val excluded = ByteArrayOutputStream()
        manager.export(null, noDataOptions.copy(appSettings = true), included)
        manager.export(null, noDataOptions, excluded)
        val withSettings = json.decodeFromString<BackupMetadata>(readArchive(included.toByteArray()).getValue("metadata.json"))
        val withoutSettings = json.decodeFromString<BackupMetadata>(readArchive(excluded.toByteArray()).getValue("metadata.json"))
        assertEquals(true, withSettings.autoExpandReasoning)
        assertTrue(withSettings.includesAppSettings)
        assertNull(withoutSettings.autoExpandReasoning)
        assertFalse(withoutSettings.includesAppSettings)
        coVerify(exactly = 1) { generalSettings.autoExpandReasoning() }
    }

    @Test
    fun streamingAndLegacyImportsRestoreBothReasoningPreferenceValues() = runTest {
        for (enabled in listOf(true, false)) {
            val metadata = BackupMetadata(schemaVersion = 71, createdAt = 0,
                includesAppSettings = true, autoExpandReasoning = enabled)
            val snapshot = BackupSnapshot(schemaVersion = 71, createdAt = 0,
                themeMode = "DARK", autoExpandReasoning = enabled)
            for (backup in listOf(
                archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata)),
                archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
            )) manager.import(ByteArrayInputStream(backup), null).getOrThrow()
            coVerify(exactly = 2) { generalSettings.setAutoExpandReasoning(enabled) }
        }
    }

    @Test
    fun missingNullOrOutOfScopeReasoningPreferenceDoesNotResetTheDevice() = runTest {
        for (fields in listOf("", ",\"autoExpandReasoning\":null")) {
            val content = """{"schemaVersion":71,"createdAt":0,"themeMode":"DARK"$fields}"""
            for (backup in listOf(archive("metadata.json" to content), archive("snapshot.json" to content))) {
                manager.import(ByteArrayInputStream(backup), null).getOrThrow()
            }
        }
        val excluded = BackupMetadata(schemaVersion = 71, createdAt = 0,
            includesAppSettings = false, autoExpandReasoning = true)
        manager.import(ByteArrayInputStream(archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), excluded))), null).getOrThrow()
        coVerify(exactly = 0) { generalSettings.setAutoExpandReasoning(any()) }
    }

    @Test
    fun appearanceExportIsScopedToAppSettingsAndDoesNotIncludeImages() = runTest {
        coEvery { themeSettings.chatTextScaleSnapshot() } returns 1.25f
        coEvery { backgroundSettings.chatAlphaSnapshot() } returns 0.4f
        val output = ByteArrayOutputStream()

        manager.export(null, noDataOptions.copy(appSettings = true), output)

        val entries = readArchive(output.toByteArray())
        assertEquals(setOf("metadata.json"), entries.keys)
        val metadata = json.decodeFromString<BackupMetadata>(entries.getValue("metadata.json"))
        assertTrue(metadata.includesAppSettings)
        assertEquals(1.25f, metadata.chatTextScale)
        assertEquals(0.4f, metadata.chatBackgroundAlpha)
        coVerify(exactly = 1) { themeSettings.chatTextScaleSnapshot() }
        coVerify(exactly = 1) { backgroundSettings.chatAlphaSnapshot() }
    }

    @Test
    fun exportWithoutAppSettingsDoesNotReadAppearance() = runTest {
        val output = ByteArrayOutputStream()

        manager.export(null, noDataOptions, output)

        val metadata = json.decodeFromString<BackupMetadata>(readArchive(output.toByteArray()).getValue("metadata.json"))
        assertFalse(metadata.includesAppSettings)
        assertNull(metadata.chatTextScale)
        assertNull(metadata.chatBackgroundAlpha)
        coVerify(exactly = 0) { themeSettings.chatTextScaleSnapshot() }
        coVerify(exactly = 0) { backgroundSettings.chatAlphaSnapshot() }
    }

    @Test
    fun streamingAndLegacyImportsRestoreOnlyNonNullAppearanceFields() = runTest {
        val metadata = BackupMetadata(schemaVersion = 65, createdAt = 0, includesAppSettings = true, chatTextScale = 1.25f)
        val snapshot = BackupSnapshot(schemaVersion = 65, createdAt = 0, themeMode = "DARK", chatBackgroundAlpha = 0f)
        val backups = listOf(
            archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata)),
            archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
        )

        backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }

        coVerify(exactly = 1) { themeSettings.setChatTextScale(1.25f) }
        coVerify(exactly = 1) { themeSettings.setChatTextScale(any()) }
        coVerify(exactly = 1) { backgroundSettings.setChatBackgroundAlpha(0f) }
        coVerify(exactly = 1) { backgroundSettings.setChatBackgroundAlpha(any()) }
    }

    @Test
    fun oldAppearanceBackupsDoNotResetCurrentValues() = runTest {
        val oldSettings = """{"schemaVersion":64,"createdAt":0,"themeMode":"DARK"}"""
        val backups = listOf(archive("metadata.json" to oldSettings), archive("snapshot.json" to oldSettings))

        backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }

        coVerify(exactly = 0) { themeSettings.setChatTextScale(any()) }
        coVerify(exactly = 0) { backgroundSettings.setChatBackgroundAlpha(any()) }
    }

    @Test
    fun importWithoutSettingsScopeIgnoresAppearanceValues() = runTest {
        val metadata = BackupMetadata(
            schemaVersion = 65,
            createdAt = 0,
            themeMode = "DARK",
            includesAppSettings = false,
            chatTextScale = 1.25f,
            chatBackgroundAlpha = 0.4f
        )
        val backup = archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata))

        manager.import(ByteArrayInputStream(backup), null).getOrThrow()

        coVerify(exactly = 0) { themeSettings.setChatTextScale(any()) }
        coVerify(exactly = 0) { backgroundSettings.setChatBackgroundAlpha(any()) }
    }

    @Test
    fun bubbleStyleExportIsScopedToAppSettings() = runTest {
        coEvery { chatBubbleSettings.userStyleSnapshot() } returns ChatBubbleStyle.SHADOW
        coEvery { chatBubbleSettings.assistantStyleSnapshot() } returns ChatBubbleStyle.DOCUMENT
        val includedOutput = ByteArrayOutputStream()
        val excludedOutput = ByteArrayOutputStream()

        manager.export(null, noDataOptions.copy(appSettings = true), includedOutput)
        manager.export(null, noDataOptions, excludedOutput)

        val included = json.decodeFromString<BackupMetadata>(readArchive(includedOutput.toByteArray()).getValue("metadata.json"))
        val excluded = json.decodeFromString<BackupMetadata>(readArchive(excludedOutput.toByteArray()).getValue("metadata.json"))
        assertTrue(included.includesAppSettings)
        assertEquals("shadow", included.userChatBubbleStyleId)
        assertEquals("document", included.assistantChatBubbleStyleId)
        assertFalse(excluded.includesAppSettings)
        assertNull(excluded.userChatBubbleStyleId)
        assertNull(excluded.assistantChatBubbleStyleId)
        coVerify(exactly = 1) { chatBubbleSettings.userStyleSnapshot() }
        coVerify(exactly = 1) { chatBubbleSettings.assistantStyleSnapshot() }
    }

    @Test
    fun streamingAndLegacyImportsRestoreBothAndPartialBubbleStyleSettings() = runTest {
        for ((userId, assistantId) in listOf("shadow" to "document", "classic" to null, null to "minimal")) {
            val metadata = BackupMetadata(
                schemaVersion = 68, createdAt = 0, includesAppSettings = true,
                userChatBubbleStyleId = userId, assistantChatBubbleStyleId = assistantId
            )
            val snapshot = BackupSnapshot(
                schemaVersion = 68, createdAt = 0, themeMode = "DARK",
                userChatBubbleStyleId = userId, assistantChatBubbleStyleId = assistantId
            )
            val backups = listOf(
                archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata)),
                archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
            )

            backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }

            coVerify(exactly = 2) { chatBubbleSettings.restore(userId, assistantId) }
        }
        coVerify(exactly = 6) { chatBubbleSettings.restore(any(), any()) }
    }

    @Test
    fun oldOrExplicitNullBubbleStylesDoNotResetCurrentSettings() = runTest {
        for (fields in listOf("", ",\"userChatBubbleStyleId\":null,\"assistantChatBubbleStyleId\":null")) {
            val oldSettings = """{"schemaVersion":67,"createdAt":0,"themeMode":"DARK"$fields}"""
            val backups = listOf(archive("metadata.json" to oldSettings), archive("snapshot.json" to oldSettings))

            backups.forEach { manager.import(ByteArrayInputStream(it), null).getOrThrow() }
        }

        coVerify(exactly = 0) { chatBubbleSettings.restore(any(), any()) }
    }

    @Test
    fun importWithoutAppSettingsScopeIgnoresBubbleStyles() = runTest {
        val metadata = BackupMetadata(
            schemaVersion = 68, createdAt = 0, includesAppSettings = false,
            userChatBubbleStyleId = "shadow", assistantChatBubbleStyleId = "document"
        )
        val backup = archive("metadata.json" to json.encodeToString(BackupMetadata.serializer(), metadata))

        manager.import(ByteArrayInputStream(backup), null).getOrThrow()

        coVerify(exactly = 0) { chatBubbleSettings.restore(any(), any()) }
    }

    private fun captureRestoredMessages(): MutableList<AgentMessageEntity> {
        val restored = mutableListOf<AgentMessageEntity>()
        coEvery { messageDao.insertAll(any()) } answers { restored += firstArg<List<AgentMessageEntity>>() }
        return restored
    }

    private fun assertTiming(expected: AgentMessageEntity, actual: AgentMessageEntity) {
        assertEquals(expected.toolStartedAtMs, actual.toolStartedAtMs)
        assertEquals(expected.toolDurationMs, actual.toolDurationMs)
        assertEquals(expected.turnDurationMs, actual.turnDurationMs)
        val ui = actual.toUIMessage()
        assertEquals(expected.toolStartedAtMs, ui.toolStartedAtMs)
        assertEquals(expected.toolDurationMs, ui.toolDurationMs)
        assertEquals(expected.turnDurationMs, ui.turnDurationMs)
    }

    private fun assertContext(expected: AgentMessageEntity, actual: AgentMessageEntity) {
        assertEquals(expected.contextInputTokens, actual.contextInputTokens)
        assertEquals(expected.contextInputBudget, actual.contextInputBudget)
        assertEquals(expected.contextEstimated, actual.contextEstimated)
        assertEquals(expected.contextBreakdownJson, actual.contextBreakdownJson)
        val ui = actual.toUIMessage()
        assertEquals(expected.contextInputTokens, ui.contextInputTokens)
        assertEquals(expected.contextInputBudget, ui.contextInputBudget)
        assertEquals(expected.contextEstimated, ui.contextEstimated)
        assertEquals(
            expected.contextBreakdownJson?.let { runCatching { json.decodeFromString<ContextUsageBreakdown>(it) }.getOrNull() },
            ui.contextBreakdown
        )
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(output)).use { tar ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun readArchive(archive: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(archive))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                entries[entry.name] = String(tar.readBytes(), Charsets.UTF_8)
                entry = tar.nextEntry
            }
        }
        return entries
    }
}
