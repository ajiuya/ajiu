package com.aicode.feature.backup.data

import android.content.Context
import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.PluginBackupSectionMeta
import com.aicode.feature.backup.domain.PluginBackupSectionResult
import com.aicode.feature.backup.domain.PluginBackupSectionStatus
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginBackupArchiveService
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.repository.ChatBubbleSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsSnapshot
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Provider

class BackupContentManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context = mockk<Context>(relaxed = true)
    private val files = mockk<BackupFileContentService>()
    private val fullPlugins = mockk<PluginFullBackupService>()
    private val content = BackupContentService(context, files, fullPlugins)
    private val providerDao = mockk<AIProviderDao>(relaxed = true)
    private val connectionDao = mockk<RemoteConnectionDao>(relaxed = true)
    private val sessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val messageDao = mockk<AgentMessageDao>(relaxed = true)
    private val todoDao = mockk<TodoItemDao>(relaxed = true)
    private val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
    private val theme = mockk<ThemeSettingsRepository>(relaxed = true)
    private val sync = mockk<SyncSettingsRepository>(relaxed = true)
    private val bubbles = mockk<ChatBubbleSettingsRepository>(relaxed = true)
    private val journal = mockk<ProjectMemoryJournal>(relaxed = true)
    private val recovery = mockk<RecoveryRepository>(relaxed = true)
    private val database = mockk<AgentDatabase>()
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val mcp = mockk<McpManager>(relaxed = true)
    private val legacyPlugins = mockk<PluginManager>()
    private val legacyRuntime = mockk<PluginRuntimeManager>()
    private val legacyGateway = mockk<PluginHostGateway>()
    private val legacyCapabilities = mockk<PluginCapabilityRepository>()
    private val legacyArchives = PluginBackupArchiveService(legacyPlugins, legacyRuntime, Provider { legacyGateway }, legacyCapabilities)
    private val manager = manager()
    private lateinit var cache: File
    private var transactions = 0
    private val noCoreOptions = BackupOptions(
        providers = false, remoteConnections = false, chatHistory = false,
        mcpServers = false, permissionRules = false, appSettings = false
    )

    @Before
    fun setUp() {
        cache = temporary.newFolder("cache")
        every { context.cacheDir } returns cache
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            transactions++
            arg<suspend () -> Unit>(1).invoke()
        }
        coEvery { providerDao.getAllProvidersOnce() } returns emptyList()
        coEvery { connectionDao.getAllConnectionsOnce() } returns emptyList()
        coEvery { connectionDao.getAllMountsOnce() } returns emptyList()
        coEvery { sessionDao.getPageAfter(any(), any(), any()) } returns emptyList()
        coEvery { sessionDao.getById(any()) } returns null
        coEvery { messageDao.getPageAfter(any(), any(), any()) } returns emptyList()
        coEvery { todoDao.getPageAfter(any(), any(), any()) } returns emptyList()
        coEvery { interactionDao.getPageAfter(any(), any(), any()) } returns emptyList()
        coEvery { theme.snapshot() } returns "DARK"
        coEvery { theme.presetSnapshot() } returns "default"
        coEvery { bubbles.userStyleSnapshot() } returns ChatBubbleStyle.ORIGINAL
        coEvery { bubbles.assistantStyleSnapshot() } returns ChatBubbleStyle.DOCUMENT
        every { sync.snapshot() } returns SyncSettingsSnapshot("", true, 5)
        every { workspaces.currentPath() } returns "/workspace"
        every { journal.exportHistory() } returns """{"schemaVersion":1,"projects":[],"entries":[],"archives":[]}"""
        coEvery { files.prepareExport(any()) } returns emptyList()
        coEvery { fullPlugins.prepareExport() } returns emptyList()
        every { files.cleanup(any()) } returns Unit
        every { fullPlugins.cleanup(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun defaultExportKeepsOriginalSegmentsWithoutPreparingNewContent() = runTest {
        val output = ByteArrayOutputStream()

        manager.export(null, BackupOptions(), output)

        val entries = entries(output.toByteArray())
        assertEquals(setOf("metadata.json", "chatSessions.jsonl", "messages.jsonl", "todoItems.jsonl", "subAgentInteractions.jsonl", "project_journal_history.json"), entries.keys)
        val metadata = json.decodeFromString<BackupMetadata>(String(entries.getValue("metadata.json"), Charsets.UTF_8))
        assertTrue(metadata.includesAppSettings)
        assertEquals(1, metadata.contentFormatVersion)
        assertTrue(metadata.contentSections.isEmpty())
        assertTrue(metadata.pluginSections.isEmpty())
        assertFalse(entries.keys.any { it.startsWith("content/") })
        coVerify(exactly = 0) { files.prepareExport(any()) }
        coVerify(exactly = 0) { fullPlugins.prepareExport() }
        assertCacheClean()
    }

    @Test
    fun memoriesOnlyExportIncludesItsMetadataAndRawPayload() = runTest {
        assertContentExport(BackupContentKind.MEMORY, noCoreOptions.copy(memories = true))
    }

    @Test
    fun skillsOnlyExportIncludesItsMetadataAndCompanionPayload() = runTest {
        assertContentExport(BackupContentKind.SKILLS, noCoreOptions.copy(skills = true))
    }

    @Test
    fun promptsOnlyExportIncludesItsMetadataAndRawPayload() = runTest {
        assertContentExport(BackupContentKind.PROMPTS, noCoreOptions.copy(prompts = true))
    }

    @Test
    fun pluginsOnlyExportIncludesPackageDataAndSecrets() = runTest {
        assertContentExport(BackupContentKind.PLUGIN, noCoreOptions.copy(plugins = true))
    }

    @Test
    fun newSectionsRequireExplicitSelectionAndNeverAutomaticallyRestore() = runTest {
        val sections = listOf(section(BackupContentKind.MEMORY), section(BackupContentKind.PLUGIN))

        val stats = manager.import(ByteArrayInputStream(archive(metadata(sections), contentEntries(sections))), null).getOrThrow()

        assertEquals(sections.map { it.metadata.id }, stats.contentSections.map { it.sectionId })
        assertTrue(stats.contentSections.all { it.status == BackupContentRestoreStatus.NOT_SELECTED && it.restoredFiles == 0 })
        coVerify(exactly = 0) { files.restore(any(), any(), any()) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        assertCacheClean()
    }

    @Test
    fun onlySelectedSectionIsStagedAndReceivesTheExactRestoreSelection() = runTest {
        val selected = section(BackupContentKind.SKILLS)
        val ignored = section(BackupContentKind.MEMORY)
        val selection = BackupContentRestoreSelection(setOf(selected.metadata.id), overwrite = true, remoteTargetIdentity = "chosen-target")
        var stage: File? = null
        coEvery { files.restore(selected.metadata, any(), selection) } answers {
            val directory = secondArg<File>().also { stage = it }
            assertEquals(listOf(selected.metadata.id), directory.parentFile.list()!!.toList())
            assertFalse(File(directory.parentFile, ignored.metadata.id).exists())
            assertArrayEquals(selected.directory.resolve("definitions/sample/SKILL.md").readBytes(), directory.resolve("definitions/sample/SKILL.md").readBytes())
            restored(selected)
        }
        val sections = listOf(selected, ignored)

        val stats = manager.import(ByteArrayInputStream(archive(metadata(sections), contentEntries(sections))), null, contentSelection = selection).getOrThrow()

        assertEquals(listOf(BackupContentRestoreStatus.RESTORED, BackupContentRestoreStatus.NOT_SELECTED), stats.contentSections.map { it.status })
        assertTrue(stage != null)
        assertFalse(stage!!.exists())
        coVerify(exactly = 1) { files.restore(selected.metadata, any(), selection) }
        coVerify(exactly = 0) { files.restore(ignored.metadata, any(), any()) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        assertCacheClean()
    }

    @Test
    fun tamperedSelectedHashFailsPreflightBeforeAnyCoreWrites() = runTest {
        assertTamperingFailsBeforeCoreWrites(selected = true)
    }

    @Test
    fun tamperedUnselectedHashStillFailsPreflightBeforeAnyCoreWrites() = runTest {
        assertTamperingFailsBeforeCoreWrites(selected = false)
    }

    @Test
    fun missingManifestFailsPreflightBeforeAnyCoreWrites() = runTest {
        val section = section(BackupContentKind.MEMORY)
        val withoutManifest = contentEntries(listOf(section)).filterNot { it.first.endsWith("/manifest.json") }

        val result = manager.import(ByteArrayInputStream(archive(coreMetadata(listOf(section)), coreEntries() + withoutManifest)), null)

        assertTrue(result.isFailure)
        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun undeclaredContentEntryFailsPreflightBeforeAnyCoreWrites() = runTest {
        val section = section(BackupContentKind.MEMORY)
        val undeclared = contentEntries(listOf(section)).map { (name, bytes) -> name.replace(section.metadata.id, "undeclared") to bytes }

        val result = manager.import(ByteArrayInputStream(archive(coreMetadata(emptyList()), coreEntries() + undeclared)), null)

        assertTrue(result.isFailure)
        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun duplicateMetadataOrDuplicateSectionIdsFailBeforeAnyCoreWrites() = runTest {
        val section = section(BackupContentKind.MEMORY)
        val metadata = coreMetadata(listOf(section))
        val duplicateMetadata = archive(metadata, coreEntries() + ("metadata.json" to encode(metadata)) + contentEntries(listOf(section)))
        val duplicateIds = archive(metadata.copy(contentSections = listOf(section.metadata, section.metadata)), coreEntries() + contentEntries(listOf(section)))

        assertTrue(manager.import(ByteArrayInputStream(duplicateMetadata), null).isFailure)
        assertTrue(manager.import(ByteArrayInputStream(duplicateIds), null).isFailure)

        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun unsupportedContentFormatAndUnknownSelectionFailBeforeAnyCoreWrites() = runTest {
        val section = section(BackupContentKind.MEMORY)
        val unknownFormat = archive(coreMetadata(listOf(section)).copy(contentFormatVersion = 2), coreEntries() + contentEntries(listOf(section)))
        val unknownSelection = archive(coreMetadata(listOf(section)), coreEntries() + contentEntries(listOf(section)))

        assertTrue(manager.import(ByteArrayInputStream(unknownFormat), null).isFailure)
        assertTrue(manager.import(ByteArrayInputStream(unknownSelection), null, contentSelection = BackupContentRestoreSelection(setOf("unknown"))).isFailure)

        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun oversizedMetadataHeaderIsRejectedByImportAndPreviewWithoutAllocatingPayload() = runTest {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gzip ->
            val entry = TarArchiveEntry("metadata.json").apply { size = 16L * 1024 * 1024 + 1 }
            gzip.write(ByteArray(512).also { entry.writeEntryHeader(it) })
        }
        val bytes = output.toByteArray()

        assertTrue(manager.previewImport(ByteArrayInputStream(bytes), null).isFailure)
        assertTrue(manager.import(ByteArrayInputStream(bytes), null).isFailure)

        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun mixedLegacyAndMetadataArchivesAreRejectedInEitherOrder() = runTest {
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            agentMessages = listOf(AgentMessageDto("message", "session", "USER", "Must not restore", 1))
        )
        val legacy = "snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray()
        val metadata = "metadata.json" to encode(coreMetadata(emptyList()))

        for (ordered in listOf(listOf(legacy, metadata), listOf(metadata, legacy))) {
            assertTrue(manager.import(ByteArrayInputStream(tarGz(ordered)), null).isFailure)
        }

        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun workspaceOnlyArchiveWithoutMetadataOrLegacyCannotOverwriteExistingFiles() = runTest {
        val directory = temporary.newFolder("workspace-only-target")
        val sentinel = File(directory, "sentinel.txt").apply { writeText("Keep original workspace bytes\r\n") }
        val before = sentinel.readBytes()
        every { workspaces.workspaces } returns MutableStateFlow(listOf(Workspace("fixture", directory.path)))
        coEvery { workspaces.captureBackupLocalWorkspaces() } returns listOf(
            WorkspaceRepository.BackupLocalWorkspace(Workspace("fixture", directory.path)) {}
        )
        val input = tarGz(listOf("workspaces/fixture/sentinel.txt" to "Untrusted replacement".toByteArray()))

        val result = manager.import(ByteArrayInputStream(input), null)

        assertTrue(result.isFailure)
        assertArrayEquals(before, sentinel.readBytes())
        assertEquals(listOf("sentinel.txt"), directory.list()!!.toList())
        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun workspaceRestoreWhileCurrentWorkspaceIsRemoteCreatesOnlyPhoneProject() = runTest {
        val phoneFiles = temporary.newFolder("phone-files")
        every { context.filesDir } returns phoneFiles
        val remote = temporary.newFolder("remote-fixture")
        val remoteSentinel = File(remote, "note.md").apply { writeText("Remote content must not change\n") }
        val remoteBefore = remoteSentinel.readBytes()
        every { workspaces.workspaces } returns MutableStateFlow(listOf(Workspace("demo", remote.path, WorkspaceType.REMOTE)))
        every { workspaces.currentPath() } returns remote.path
        coEvery { workspaces.captureBackupLocalWorkspaces() } returns emptyList()
        val payload = "手机工作区正文\r\n保留非 ASCII 和换行\n".toByteArray(Charsets.UTF_8)
        val metadata = metadata(emptyList()).copy(workspaces = listOf(WorkspaceBackupMeta("demo", 1)))

        val stats = manager.import(
            ByteArrayInputStream(archive(metadata, listOf("workspaces/demo/note.md" to payload))), null,
            selectedWorkspaces = setOf("demo")
        ).getOrThrow()

        assertEquals(1, stats.workspaceFiles)
        assertTrue(stats.contentSections.isEmpty())
        assertArrayEquals(payload, File(phoneFiles, "projects/demo/note.md").readBytes())
        assertEquals(listOf("demo"), File(phoneFiles, "projects").list()!!.toList())
        assertEquals(listOf("note.md"), File(phoneFiles, "projects/demo").list()!!.toList())
        assertArrayEquals(remoteBefore, remoteSentinel.readBytes())
        assertEquals(listOf("note.md"), remote.list()!!.toList())
        coVerify(exactly = 1) { workspaces.captureBackupLocalWorkspaces() }
        coVerify(exactly = 0) { files.currentRemoteTarget() }
        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun workspaceRestoreReusesCapturedInternalDirectoryButNeverOverwritesExternalNamesake() = runTest {
        for (type in listOf(WorkspaceType.INTERNAL, WorkspaceType.EXTERNAL_LOCAL)) {
            val phoneFiles = temporary.newFolder()
            every { context.filesDir } returns phoneFiles
            val directory = if (type == WorkspaceType.INTERNAL) File(phoneFiles, "projects/demo").apply {
                assertTrue(mkdirs())
            } else temporary.newFolder()
            val sentinel = File(directory, "extra.txt").apply { writeText("Retain extra file\r\n") }
            val sentinelBefore = sentinel.readBytes()
            val existingNote = File(directory, "note.md").apply { writeText("Original $type file\n") }
            val originalNote = existingNote.readBytes()
            var guardCalls = 0
            coEvery { workspaces.captureBackupLocalWorkspaces() } returns listOf(
                WorkspaceRepository.BackupLocalWorkspace(Workspace("demo", directory.path, type)) {
                    if (type != WorkspaceType.INTERNAL) throw AssertionError("External workspace must not be a restore target")
                    guardCalls++
                }
            )
            every { workspaces.workspaces } returns MutableStateFlow(listOf(Workspace("demo", "/server/demo", WorkspaceType.REMOTE)))
            val payload = "恢复到手机的原始字节\r\n".toByteArray(Charsets.UTF_8)
            val metadata = metadata(emptyList()).copy(workspaces = listOf(WorkspaceBackupMeta("demo", 1)))

            val stats = manager.import(
                ByteArrayInputStream(archive(metadata, listOf("workspaces/demo/note.md" to payload))), null,
                selectedWorkspaces = setOf("demo")
            ).getOrThrow()

            assertEquals(1, stats.workspaceFiles)
            assertTrue(stats.contentSections.isEmpty())
            assertArrayEquals(sentinelBefore, sentinel.readBytes())
            assertEquals(setOf("note.md", "extra.txt"), directory.list()!!.toSet())
            val projects = File(phoneFiles, "projects")
            if (type == WorkspaceType.INTERNAL) {
                assertEquals(1, guardCalls)
                assertArrayEquals(payload, existingNote.readBytes())
                assertEquals(listOf("demo"), projects.list()!!.toList())
            } else {
                assertEquals(0, guardCalls)
                assertArrayEquals(originalNote, existingNote.readBytes())
                assertEquals(listOf("demo (2)"), projects.list()!!.toList())
                assertArrayEquals(payload, File(projects, "demo (2)/note.md").readBytes())
                assertEquals(listOf("note.md"), File(projects, "demo (2)").list()!!.toList())
            }
            coVerify(exactly = 0) { files.currentRemoteTarget() }
            assertNoCoreWrites()
            assertCacheClean()
        }
        coVerify(exactly = 2) { workspaces.captureBackupLocalWorkspaces() }
    }

    @Test
    fun malformedLegacySnapshotIsRejectedBeforeRoomOrWorkspaceWrites() = runTest {
        val validPrefix = json.encodeToString(BackupSnapshot.serializer(), BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            providers = coreMetadata(emptyList()).providers,
            agentMessages = listOf(AgentMessageDto("legacy-message", "session", "USER", "Must not restore", 1))
        )).dropLast(1)
        for (document in listOf("{", "$validPrefix,\"todoItems\":\"invalid-table\"}", "$validPrefix,\"schemaVersion\":\"invalid-version\"}")) {
            val result = manager.import(ByteArrayInputStream(tarGz(listOf("snapshot.json" to document.toByteArray()))), null)

            assertTrue(result.isFailure)
            assertNoCoreWrites()
            assertCacheClean()
        }
    }

    @Test
    fun legacySnapshotMixedWithWorkspaceEntriesIsRejectedInEitherOrderBeforeWrites() = runTest {
        val directory = temporary.newFolder("mixed-legacy-target")
        val sentinel = File(directory, "sentinel.txt").apply { writeText("Preserve original workspace\n") }
        val before = sentinel.readBytes()
        every { workspaces.workspaces } returns MutableStateFlow(listOf(Workspace("fixture", directory.path)))
        coEvery { workspaces.captureBackupLocalWorkspaces() } returns listOf(
            WorkspaceRepository.BackupLocalWorkspace(Workspace("fixture", directory.path)) {}
        )
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            providers = coreMetadata(emptyList()).providers,
            agentMessages = listOf(AgentMessageDto("legacy-message", "session", "USER", "Must not restore", 1))
        )
        val legacy = "snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray()
        val workspace = "workspaces/fixture/sentinel.txt" to "Untrusted replacement".toByteArray()

        for (ordered in listOf(listOf(legacy, workspace), listOf(workspace, legacy))) {
            assertTrue(manager.import(ByteArrayInputStream(tarGz(ordered)), null).isFailure)
            assertArrayEquals(before, sentinel.readBytes())
            assertEquals(listOf("sentinel.txt"), directory.list()!!.toList())
            assertNoCoreWrites()
            assertCacheClean()
        }
    }

    @Test
    fun legacySnapshotMixedWithJsonlIsRejectedInEitherOrderBeforeWrites() = runTest {
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1,
            providers = coreMetadata(emptyList()).providers,
            agentMessages = listOf(AgentMessageDto("legacy-message", "session", "USER", "Must not restore", 1))
        )
        val legacy = "snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray()
        for (jsonl in coreEntries()) {
            for (ordered in listOf(listOf(legacy, jsonl), listOf(jsonl, legacy))) {
                assertTrue(manager.import(ByteArrayInputStream(tarGz(ordered)), null).isFailure)
                assertNoCoreWrites()
                assertCacheClean()
            }
        }
    }

    @Test
    fun futureLegacySchemaIsRejectedBeforeRoomOrWorkspaceWrites() = runTest {
        val snapshot = BackupSnapshot(
            schemaVersion = AgentDatabase.SCHEMA_VERSION + 1, createdAt = 1,
            providers = coreMetadata(emptyList()).providers,
            agentMessages = listOf(AgentMessageDto("future-message", "session", "USER", "Must not restore", 1))
        )

        val result = manager.import(ByteArrayInputStream(tarGz(listOf(
            "snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray()
        ))), null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun encryptedNewContentRoundtripRejectsWrongPasswordAndTruncationBeforeCoreWrites() = runTest {
        val section = section(BackupContentKind.MEMORY)
        val options = noCoreOptions.copy(memories = true, chatHistory = true)
        val password = "fixture-content-backup-password".toCharArray()
        val wrongPassword = "fixture-wrong-password".toCharArray()
        val message = AgentMessageEntity("encrypted-message", "session", "USER", "Encrypted core payload\n", 1)
        coEvery { files.prepareExport(options) } returns listOf(section)
        coEvery { messageDao.getPageAfter(any(), any(), any()) } returns listOf(message) andThen emptyList()
        val output = ByteArrayOutputStream()
        manager.export(password, options, output)
        val encrypted = output.toByteArray()
        assertArrayEquals("AICODEBK".toByteArray(Charsets.US_ASCII), encrypted.copyOfRange(0, 8))
        assertCacheClean()

        val wrongPreview = manager.previewImport(ByteArrayInputStream(encrypted), wrongPassword)
        assertTrue(wrongPreview.isFailure)
        assertTrue(wrongPreview.exceptionOrNull() is BackupDecryptionException)
        val wrongImport = manager.import(ByteArrayInputStream(encrypted), wrongPassword,
            contentSelection = BackupContentRestoreSelection(setOf(section.metadata.id)))
        assertTrue(wrongImport.isFailure)
        assertTrue(wrongImport.exceptionOrNull() is BackupDecryptionException)
        assertNoCoreWrites()
        assertCacheClean()

        val truncated = encrypted.copyOf(encrypted.size - 1)
        val truncatedPreview = manager.previewImport(ByteArrayInputStream(truncated), password)
        assertTrue(truncatedPreview.isFailure)
        assertTrue(truncatedPreview.exceptionOrNull() is IllegalArgumentException)
        val truncatedImport = manager.import(ByteArrayInputStream(truncated), password,
            contentSelection = BackupContentRestoreSelection(setOf(section.metadata.id)))
        assertTrue(truncatedImport.isFailure)
        assertTrue(truncatedImport.exceptionOrNull() is IllegalArgumentException)
        assertNoCoreWrites()
        assertCacheClean()

        val preview = manager.previewImport(ByteArrayInputStream(encrypted), password).getOrThrow()
        assertEquals(listOf(section.metadata), preview.contentSections)
        assertFalse(preview.hasWorkspaceData)
        assertNoCoreWrites()
        assertCacheClean()

        val captured = mutableListOf<AgentMessageEntity>()
        var stage: File? = null
        coEvery { messageDao.insertAll(any()) } answers { captured += firstArg<List<AgentMessageEntity>>() }
        coEvery { files.restore(section.metadata, any(), any()) } answers {
            val directory = secondArg<File>().also { stage = it }
            assertArrayEquals(section.directory.resolve("note.md").readBytes(), directory.resolve("note.md").readBytes())
            assertEquals(listOf(message.id), captured.map { it.id })
            restored(section)
        }
        val stats = manager.import(ByteArrayInputStream(encrypted), password,
            contentSelection = BackupContentRestoreSelection(setOf(section.metadata.id))).getOrThrow()

        assertEquals(1, stats.agentMessages)
        assertEquals(listOf(restored(section)), stats.contentSections)
        assertEquals(message.id, captured.single().id)
        assertEquals(message.content, captured.single().content)
        assertTrue(transactions > 0)
        assertTrue(stage != null)
        assertFalse(stage!!.exists())
        coVerify(exactly = 1) { messageDao.insertAll(any()) }
        coVerify(exactly = 1) { files.restore(section.metadata, any(), BackupContentRestoreSelection(setOf(section.metadata.id))) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        assertCacheClean()
    }

    @Test
    fun fullPluginSelectionBlocksSameIdLegacyHooksWithAnExplicitUnavailableResult() = runTest {
        val section = section(BackupContentKind.PLUGIN)
        val pluginId = checkNotNull(section.metadata.pluginId)
        val legacy = PluginBackupSectionMeta(pluginId, "Legacy backup callback")
        val metadata = metadata(listOf(section)).copy(pluginSections = listOf(legacy))
        coEvery { fullPlugins.restore(section.metadata, any(), false) } answers { restored(section) }

        val stats = manager.import(
            ByteArrayInputStream(archive(metadata, contentEntries(listOf(section)))), null,
            selectedPluginIds = setOf(pluginId), contentSelection = BackupContentRestoreSelection(setOf(section.metadata.id))
        ).getOrThrow()

        assertEquals(listOf(restored(section)), stats.contentSections)
        assertEquals(listOf(PluginBackupSectionResult(pluginId, PluginBackupSectionStatus.UNAVAILABLE, "FULL_PLUGIN_RESTORE_SELECTED")), stats.pluginSections)
        coVerify(exactly = 1) { fullPlugins.restore(section.metadata, any(), false) }
        verify { legacyPlugins wasNot Called }
        verify { legacyRuntime wasNot Called }
        verify { legacyGateway wasNot Called }
        verify { legacyCapabilities wasNot Called }
        assertCacheClean()
    }

    @Test
    fun legacyPluginSelectionStillReachesTheOriginalArchiveRestoreCallback() = runTest {
        val archives = mockk<PluginBackupArchiveService>(relaxed = true)
        val pluginId = "fixture.legacy"
        val metadata = metadata(emptyList()).copy(pluginSections = listOf(PluginBackupSectionMeta(pluginId, "Legacy callback", 1, 3)))
        val batch = PluginBackupArchiveService.ImportSections(metadata.pluginSections, setOf(pluginId))
        val callbackResult = PluginBackupSectionResult(pluginId, PluginBackupSectionStatus.RESTORED)
        coEvery { archives.prepareImport(metadata.pluginSections, setOf(pluginId)) } returns batch
        coEvery { archives.finishImport(batch, any()) } returns listOf(callbackResult)
        val input = archive(metadata, listOf("plugins/$pluginId/note.md" to "abc".toByteArray()))

        val stats = manager(archives).import(ByteArrayInputStream(input), null, selectedPluginIds = setOf(pluginId)).getOrThrow()

        assertEquals(listOf(callbackResult), stats.pluginSections)
        assertTrue(stats.contentSections.isEmpty())
        coVerify(exactly = 1) { archives.prepareImport(metadata.pluginSections, setOf(pluginId)) }
        coVerify(exactly = 1) { archives.readImport(any(), match { it.name == "plugins/$pluginId/note.md" }, batch) }
        coVerify(exactly = 1) { archives.finishImport(batch, any()) }
        verify(exactly = 1) { archives.cleanupImport(batch) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        assertCacheClean()
    }

    @Test
    fun singleLegacySnapshotStillRestoresOriginalRoomPayload() = runTest {
        val expected = AgentMessageDto("legacy-message", "session", "USER", "历史正文\n", 1)
        val snapshot = BackupSnapshot(schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1, agentMessages = listOf(expected))
        val captured = mutableListOf<AgentMessageEntity>()
        coEvery { messageDao.insertAll(any()) } answers { captured += firstArg<List<AgentMessageEntity>>() }
        val input = tarGz(listOf("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray()))

        val stats = manager.import(ByteArrayInputStream(input), null).getOrThrow()

        assertEquals(1, stats.agentMessages)
        assertEquals(expected.id, captured.single().id)
        assertEquals(expected.content, captured.single().content)
        assertTrue(stats.contentSections.isEmpty())
        assertTrue(transactions > 0)
        coVerify(exactly = 1) { messageDao.insertAll(any()) }
        coVerify(exactly = 0) { files.restore(any(), any(), any()) }
        assertCacheClean()
    }

    @Test
    fun restoreCancellationDeletesOwnedContentStagesAndCompressedTempOnly() = runTest {
        val unrelated = File(cache, "keep.txt").apply { writeText("user cache") }
        val section = section(BackupContentKind.MEMORY)
        var stage: File? = null
        coEvery { files.restore(section.metadata, any(), any()) } answers {
            stage = secondArg<File>()
            assertTrue(stage!!.isDirectory)
            assertTrue(cache.listFiles()!!.any { it.isFile && it != unrelated })
            throw CancellationException("fixture restore cancelled")
        }

        try {
            manager.import(
                ByteArrayInputStream(archive(metadata(listOf(section)), contentEntries(listOf(section)))), null,
                contentSelection = BackupContentRestoreSelection(setOf(section.metadata.id))
            ).getOrThrow()
            throw AssertionError("Cancellation must not be converted to a content restore result")
        } catch (cancelled: CancellationException) {
            assertEquals("fixture restore cancelled", cancelled.message)
        }

        assertTrue(stage != null)
        assertFalse(stage!!.exists())
        assertEquals("user cache", unrelated.readText())
        assertEquals(listOf("keep.txt"), cache.list()!!.toList())
    }

    @Test
    fun failedPreparationDeletesItsCompressedTempAndPropagatesTheReadError() = runTest {
        val input = object : InputStream() {
            private var emitted = false
            override fun read(): Int = throw IOException("fixture source failure")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (emitted) return read()
                emitted = true
                bytes[offset] = 1
                return 1
            }
        }

        try {
            manager.prepareImport(input, null)
            throw AssertionError("Read failure must propagate")
        } catch (failure: IOException) {
            assertEquals("fixture source failure", failure.message)
        }

        assertNoCoreWrites()
        assertCacheClean()
    }

    @Test
    fun cancelledContentExportCleansAlreadyPreparedHelperSections() = runTest {
        val section = section(BackupContentKind.MEMORY)
        coEvery { files.prepareExport(any()) } returns listOf(section)
        coEvery { fullPlugins.prepareExport() } throws CancellationException("fixture export cancelled")

        try {
            manager.export(null, noCoreOptions.copy(memories = true, plugins = true), ByteArrayOutputStream())
            throw AssertionError("Export cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("fixture export cancelled", cancelled.message)
        }

        verify(exactly = 1) { files.cleanup(listOf(section)) }
        verify(exactly = 1) { fullPlugins.cleanup(emptyList()) }
        assertCacheClean()
    }

    @Test
    fun previewIncludesDeclaredContentAndCurrentRemoteTargetWithoutRestoringAnything() = runTest {
        val source = section(BackupContentKind.SKILLS)
        val remote = PreparedBackupContent(source.metadata.copy(
            location = BackupContentLocation.SSH_PROJECT, workspaceName = "project", sourceKey = "profile-1", sourceLabel = "SSH fixture"
        ), source.directory)
        val target = BackupContentRestoreTarget("current-ssh", "Current SSH")
        coEvery { files.currentRemoteTarget() } returns target

        val preview = manager.previewImport(ByteArrayInputStream(archive(metadata(listOf(remote)), contentEntries(listOf(remote)))), null).getOrThrow()

        assertEquals(listOf(remote.metadata), preview.contentSections)
        assertEquals(target, preview.remoteTarget)
        assertFalse(preview.hasWorkspaceData)
        assertTrue(preview.pluginSections.isEmpty())
        coVerify(exactly = 1) { files.currentRemoteTarget() }
        coVerify(exactly = 0) { files.restore(any(), any(), any()) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        assertNoCoreWrites()
        assertCacheClean()
    }

    private suspend fun assertContentExport(kind: BackupContentKind, options: BackupOptions) {
        val section = section(kind)
        coEvery { files.prepareExport(options) } returns if (kind == BackupContentKind.PLUGIN) emptyList() else listOf(section)
        coEvery { fullPlugins.prepareExport() } returns if (kind == BackupContentKind.PLUGIN) listOf(section) else emptyList()
        val output = ByteArrayOutputStream()

        manager.export(null, options, output)

        val entries = entries(output.toByteArray())
        val metadata = json.decodeFromString<BackupMetadata>(String(entries.getValue("metadata.json"), Charsets.UTF_8))
        assertEquals(listOf(section.metadata), metadata.contentSections)
        assertEquals(1, metadata.contentFormatVersion)
        assertTrue(metadata.pluginSections.isEmpty())
        assertFalse(metadata.includesAppSettings)
        assertTrue(metadata.providers.isEmpty())
        assertTrue(entries.containsKey("content/${section.metadata.id}/manifest.json"))
        assertFalse(entries.keys.any { it.endsWith(".jsonl") || it.startsWith("workspaces/") || it.startsWith("plugins/") })
        val exportedFiles = entries.filterKeys { it.startsWith("content/${section.metadata.id}/files/") && !it.endsWith("/") }
        assertEquals(section.metadata.fileCount, exportedFiles.size)
        assertEquals(section.metadata.bytes, exportedFiles.values.sumOf { it.size.toLong() })
        exportedFiles.forEach { (name, payload) ->
            assertArrayEquals(section.directory.resolve(name.removePrefix("content/${section.metadata.id}/files/")).readBytes(), payload)
        }
        if (kind == BackupContentKind.PLUGIN) {
            assertTrue(exportedFiles.containsKey("content/${section.metadata.id}/files/package.zip"))
            assertTrue(exportedFiles.containsKey("content/${section.metadata.id}/files/data/settings.json"))
            assertTrue(exportedFiles.containsKey("content/${section.metadata.id}/files/secrets.json"))
            coVerify(exactly = 1) { fullPlugins.prepareExport() }
            verify(exactly = 1) { fullPlugins.cleanup(listOf(section)) }
        } else {
            coVerify(exactly = 0) { fullPlugins.prepareExport() }
            verify(exactly = 1) { files.cleanup(listOf(section)) }
        }
        coVerify(exactly = 1) { files.prepareExport(options) }
        assertCacheClean()
    }

    private suspend fun assertTamperingFailsBeforeCoreWrites(selected: Boolean) {
        val section = section(BackupContentKind.MEMORY)
        val contentEntries = contentEntries(listOf(section)).map { (name, payload) ->
            name to if (name.endsWith("/files/note.md")) payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else payload
        }
        val selection = BackupContentRestoreSelection(if (selected) setOf(section.metadata.id) else emptySet())
        val input = archive(coreMetadata(listOf(section)), coreEntries() + contentEntries)

        val result = manager.import(ByteArrayInputStream(input), null, contentSelection = selection)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNoCoreWrites()
        assertCacheClean()
    }

    private fun assertNoCoreWrites() {
        assertEquals(0, transactions)
        coVerify(exactly = 0) { providerDao.insertAllProviders(any()) }
        coVerify(exactly = 0) { connectionDao.insertAllConnections(any()) }
        coVerify(exactly = 0) { connectionDao.insertAllMounts(any()) }
        coVerify(exactly = 0) { sessionDao.upsertAll(any()) }
        coVerify(exactly = 0) { messageDao.insertAll(any()) }
        coVerify(exactly = 0) { todoDao.upsertAll(any()) }
        coVerify(exactly = 0) { interactionDao.insertIgnore(any()) }
        coVerify(exactly = 0) { theme.restore(any()) }
        coVerify(exactly = 0) { mcp.reload() }
        coVerify(exactly = 0) { recovery.invalidateSession(any(), any(), any()) }
        coVerify(exactly = 0) { workspaces.createWorkspace(any()) }
        coVerify(exactly = 0) { files.restore(any(), any(), any()) }
        coVerify(exactly = 0) { fullPlugins.restore(any(), any(), any()) }
        verify(exactly = 0) { journal.importHistory(any(), any()) }
    }

    private fun assertCacheClean() {
        assertTrue(cache.list()!!.joinToString(), cache.list()!!.isEmpty())
    }

    private fun section(kind: BackupContentKind): PreparedBackupContent {
        val directory = temporary.newFolder()
        val payloads = when (kind) {
            BackupContentKind.MEMORY -> mapOf("note.md" to "---\nname: 记忆\n---\r\n完整正文\n".toByteArray())
            BackupContentKind.SKILLS -> mapOf("definitions/sample/SKILL.md" to "# 技能\n".toByteArray(), "definitions/sample/reference.txt" to byteArrayOf(0, 1, 2))
            BackupContentKind.PROMPTS -> mapOf("agent/prompt.md" to "逐字提示词\r\n".toByteArray())
            BackupContentKind.PLUGIN -> mapOf("package.zip" to byteArrayOf(1, 2, 3), "data/settings.json" to "{\"enabled\":true}".toByteArray(), "secrets.json" to "{\"fixture\":\"synthetic-value\"}".toByteArray())
        }
        payloads.forEach { (relative, payload) -> directory.resolve(relative).apply { parentFile.mkdirs(); writeBytes(payload) } }
        val id = kind.name.lowercase()
        val location = if (kind == BackupContentKind.PLUGIN) BackupContentLocation.PLUGIN else BackupContentLocation.PHONE_GLOBAL
        val metadata = BackupContentArchive.metadata(id, kind, location, "Fixture $id", directory).let {
            if (kind == BackupContentKind.PLUGIN) it.copy(pluginId = "fixture.full", pluginVersion = "1.0.0", pluginContentHash = "a".repeat(64)) else it
        }
        return PreparedBackupContent(metadata, directory)
    }

    private fun restored(section: PreparedBackupContent) = BackupContentRestoreResult(
        section.metadata.id, section.metadata.kind, section.metadata.label, BackupContentRestoreStatus.RESTORED,
        restoredFiles = section.metadata.fileCount
    )

    private fun metadata(sections: List<PreparedBackupContent>) = BackupMetadata(
        schemaVersion = AgentDatabase.SCHEMA_VERSION, createdAt = 1, contentSections = sections.map { it.metadata }
    )

    private fun coreMetadata(sections: List<PreparedBackupContent>) = metadata(sections).copy(
        providers = listOf(ProviderDto("provider", "Fixture", "OPENAI", "", "", "fixture")),
        includesAppSettings = true, themeMode = "DARK"
    )

    private fun coreEntries() = listOf(
        "chatSessions.jsonl" to (json.encodeToString(ChatSessionDto.serializer(), ChatSessionDto("session", "Title", 1, 1)) + "\n").toByteArray(),
        "messages.jsonl" to (json.encodeToString(AgentMessageDto.serializer(), AgentMessageDto("message", "session", "USER", "Original core payload", 1)) + "\n").toByteArray(),
        "todoItems.jsonl" to (json.encodeToString(TodoItemDto.serializer(), TodoItemDto(
            id = "todo", sessionId = "session", subject = "Todo", status = "PENDING", createdAt = 1, updatedAt = 1
        )) + "\n").toByteArray()
    )

    private fun contentEntries(sections: List<PreparedBackupContent>): List<Pair<String, ByteArray>> {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar -> sections.forEach { BackupContentArchive.writeExport(tar, it) } }
        val result = arrayListOf<Pair<String, ByteArray>>()
        TarArchiveInputStream(ByteArrayInputStream(output.toByteArray())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                result += entry.name to tar.readBytes()
                entry = tar.nextEntry
            }
        }
        return result
    }

    private fun encode(metadata: BackupMetadata) = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray()

    private fun archive(metadata: BackupMetadata, rest: List<Pair<String, ByteArray>>) = tarGz(listOf("metadata.json" to encode(metadata)) + rest)

    private fun tarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(output)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            entries.forEach { (name, payload) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = payload.size.toLong() })
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                result[entry.name] = tar.readBytes()
                entry = tar.nextEntry
            }
        }
        return result
    }

    private fun manager(archives: PluginBackupArchiveService = legacyArchives) = BackupManagerImpl(
        context = context, aiProviderDao = providerDao, remoteConnectionDao = connectionDao,
        chatSessionDao = sessionDao, agentMessageDao = messageDao, todoItemDao = todoDao,
        mcpConfigRepository = mockk(relaxed = true), mcpManager = mcp,
        permissionRulesRepository = mockk(relaxed = true), themeSettingsRepository = theme,
        keepaliveSettingsRepository = mockk(relaxed = true), liveNotificationSettingsRepository = mockk(relaxed = true),
        approvalNotificationSettingsRepository = mockk(relaxed = true), screenOnSettingsRepository = mockk(relaxed = true),
        agentSoundSettingsRepository = mockk(relaxed = true), generalSettingsRepository = mockk(relaxed = true),
        logSettingsRepository = mockk(relaxed = true), visionModelSettingsRepository = mockk(relaxed = true),
        compactionModelSettingsRepository = mockk(relaxed = true), syncSettingsRepository = sync,
        workspaceRepository = workspaces, backgroundSettingsRepository = mockk(relaxed = true),
        chatBubbleSettingsRepository = bubbles, subAgentInteractionDao = interactionDao,
        projectMemoryJournal = journal, recoveryRepository = recovery, agentDatabase = database,
        pluginArchives = archives, backupContent = content
    )
}
