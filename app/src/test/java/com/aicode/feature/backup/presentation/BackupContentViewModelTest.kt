package com.aicode.feature.backup.presentation

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.backup.data.BackupTargetRepository
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.ImportPreview
import com.aicode.feature.backup.domain.PluginBackupSectionMeta
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.remote.BackupRemoteStorage
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.BackupTargetType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class BackupContentViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val manager = mockk<BackupManager>()
    private val repository = mockk<BackupTargetRepository>()
    private val storage = mockk<BackupRemoteStorage>()
    private val store = ViewModelStore()
    private val models = mutableListOf<BackupViewModel>()
    private val sharingJobs = mutableSetOf<Job>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val stringSets = mutableMapOf<String, Set<String>>()
    private val snapshots = CopyOnWriteArrayList<File>()
    private val imports = CopyOnWriteArrayList<ImportCall>()
    private val loggedErrors = CopyOnWriteArrayList<Pair<String, Throwable?>>()
    private val phone = BackupContentSectionMeta("phone", BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, "Phone memory")
    private val ssh = BackupContentSectionMeta("ssh", BackupContentKind.SKILLS, BackupContentLocation.SSH_PROJECT, "SSH skills")
    private val remoteTarget = BackupContentRestoreTarget("manager-supplied-identity", "Current SSH target")
    private val target = BackupTarget("target", "Backup server", BackupTargetType.WEBDAV, "https://backup.invalid")
    private var sourceBytes = "original archive".toByteArray()
    private var preview = ImportPreview(emptyList(), contentSections = listOf(phone))
    private var preparePassword: CharArray? = null
    private var preparePasswordText: String? = null
    private var exportPassword: CharArray? = null
    private var exportedOptions: BackupOptions? = null
    private var previewBytes: ByteArray? = null
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FileLogger)
        every { FileLogger.e(any(), any(), any()) } answers {
            loggedErrors += secondArg<String>() to thirdArg<Throwable?>()
            Unit
        }
        every { context.getSharedPreferences("backup_options", Context.MODE_PRIVATE) } returns preferences
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns temporaryFolder.root
        every { context.getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { preferences.getBoolean(any(), any()) } answers { booleans[firstArg()] ?: secondArg<Boolean>() }
        every { preferences.getStringSet(any(), any()) } answers { stringSets[firstArg()] ?: secondArg<Set<String>?>() }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg()] = secondArg()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            stringSets[firstArg()] = secondArg<Set<String>>().toSet()
            editor
        }
        every { editor.apply() } just Runs
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(sourceBytes) }
        every { repository.targets } returns flowOf(emptyList())
        every { repository.storageFor(target) } returns storage
        coEvery { manager.prepareImport(any(), any()) } coAnswers {
            preparePassword = arg(1)
            preparePasswordText = preparePassword?.concatToString()
            File.createTempFile("prepared", ".tar.gz", temporaryFolder.root).also {
                it.writeBytes(arg<InputStream>(0).readBytes())
                snapshots += it
            }
        }
        coEvery { manager.previewImport(any(), any()) } coAnswers {
            assertNull(arg<CharArray?>(1))
            previewBytes = arg<InputStream>(0).readBytes()
            Result.success(preview)
        }
        coEvery { manager.import(any(), any(), any(), any(), any()) } coAnswers {
            imports += ImportCall(arg<InputStream>(0).readBytes(), arg(1), arg(2), arg(3), arg(4))
            Result.success(RestoreStats(providers = 1))
        }
        coEvery { manager.export(any(), any(), any()) } coAnswers {
            exportPassword = arg(0)
            exportedOptions = arg(1)
        }
        coEvery { storage.upload(any(), any()) } returns Result.success(Unit)
        coEvery { storage.download(any(), any()) } coAnswers {
            arg<File>(1).writeBytes(sourceBytes)
            Result.success(Unit)
        }
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        try {
            store.clear()
            runTest(dispatcher) { models.forEach { awaitOperations(it) } }
        } finally {
            unmockkObject(FileLogger)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun newOptionsDefaultToFalseAndTheOriginalDefaultsArePreserved() {
        assertEquals(BackupOptions(), viewModel.exportOptions.value)
        assertFalse(viewModel.exportOptions.value.memories)
        assertFalse(viewModel.exportOptions.value.skills)
        assertFalse(viewModel.exportOptions.value.prompts)
        assertFalse(viewModel.exportOptions.value.plugins)
    }

    @Test
    fun allFourOptionsAndLegacyExtensionIdsSurviveViewModelRecreation() {
        val options = BackupOptions(
            providers = false, remoteConnections = false, chatHistory = false,
            mcpServers = false, permissionRules = false, appSettings = false, workspaceFiles = true,
            pluginIds = setOf("legacy"), memories = true, skills = true, prompts = true, plugins = true
        )
        viewModel.updateExportOptions(options)
        assertEquals(options, newViewModel().exportOptions.value)
        assertEquals(setOf("legacy"), stringSets["plugin_ids"])
    }

    @Test
    fun mutableExtensionSetsCannotChangeStoredOptionsAfterAnUpdate() {
        val ids = mutableSetOf("legacy")
        viewModel.updateExportOptions(BackupOptions(pluginIds = ids))
        ids += "later"
        assertEquals(setOf("legacy"), viewModel.exportOptions.value.pluginIds)
        assertEquals(setOf("legacy"), newViewModel().exportOptions.value.pluginIds)
    }

    @Test
    fun localExportCapturesOptionsAndClearsItsPasswordAndOutput() = runTest(dispatcher) {
        val ids = mutableSetOf("legacy")
        val options = BackupOptions(memories = true, skills = true, prompts = true, plugins = true, pluginIds = ids)
        val output = TrackingOutput()
        viewModel.export("export-password", options, output)
        ids += "later"
        viewModel.updateExportOptions(BackupOptions())
        awaitOperations()
        assertEquals(options.copy(pluginIds = setOf("legacy")), exportedOptions)
        assertTrue(requireNotNull(exportPassword).all { it == '\u0000' })
        assertTrue(output.closed)
        assertSame(BackupState.ExportDone, viewModel.state.value)
    }

    @Test
    fun failedLocalExportsAlsoClearThePasswordAndCloseTheOutput() = runTest(dispatcher) {
        coEvery { manager.export(any(), any(), any()) } coAnswers {
            exportPassword = arg(0)
            throw IllegalStateException("export failed")
        }
        val output = TrackingOutput()
        viewModel.export("export-password", BackupOptions(), output)
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.Error)
        assertTrue(requireNotNull(exportPassword).all { it == '\u0000' })
        assertTrue(output.closed)
    }

    @Test
    fun networkUploadRequiresEncryptionBeforeCreatingAnyFiles() = runTest(dispatcher) {
        viewModel.uploadToTarget(target, "")
        awaitOperations()
        assertTrue(viewModel.networkState.value is NetworkBackupState.Error)
        coVerify(exactly = 0) { manager.export(any(), any(), any()) }
        coVerify(exactly = 0) { storage.upload(any(), any()) }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun networkExportUsesTheOptionsCapturedBeforeItsCoroutineStarts() = runTest(dispatcher) {
        val options = BackupOptions(memories = true, skills = true, prompts = true, plugins = true, pluginIds = setOf("legacy"))
        viewModel.updateExportOptions(options)
        viewModel.uploadToTarget(target, "network-password")
        viewModel.updateExportOptions(BackupOptions())
        awaitOperations()
        assertEquals(options, exportedOptions)
        assertTrue(requireNotNull(exportPassword).all { it == '\u0000' })
        assertEquals(NetworkBackupState.UploadDone(target.name), viewModel.networkState.value)
        coVerify(exactly = 1) { storage.upload(any(), any()) }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedNetworkExportClearsItsPasswordAndTemporaryArchive() = runTest(dispatcher) {
        coEvery { manager.export(any(), any(), any()) } coAnswers {
            exportPassword = arg(0)
            throw IllegalStateException("export failed")
        }
        viewModel.uploadToTarget(target, "network-password")
        awaitOperations()
        assertTrue(viewModel.networkState.value is NetworkBackupState.Error)
        assertTrue(requireNotNull(exportPassword).all { it == '\u0000' })
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
        coVerify(exactly = 0) { storage.upload(any(), any()) }
    }

    @Test
    fun plaintextPreviewAndRestoreReadTheSamePreparedArchiveEvenIfSafChanges() = runTest(dispatcher) {
        val original = sourceBytes.copyOf()
        beginImport()
        assertNull(preparePassword)
        assertArrayEquals(original, previewBytes)
        sourceBytes = "changed SAF content".toByteArray()
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(phone.id)))
        awaitOperations()
        assertArrayEquals(original, imports.single().bytes)
        assertNull(imports.single().password)
        verify(exactly = 1) { resolver.openInputStream(uri) }
        coVerify(exactly = 1) { manager.prepareImport(any(), null) }
        assertFalse(snapshots.single().exists())
    }

    @Test
    fun encryptedImportClearsThePasswordAfterPreviewAndRestoresWithNullPassword() = runTest(dispatcher) {
        beginImport("import-password")
        assertEquals("import-password", preparePasswordText)
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertNull(imports.single().password)
        assertFalse(snapshots.single().exists())
    }

    @Test
    fun coreOnlyArchivesStillPrepareASnapshotBeforeAutomaticRestore() = runTest(dispatcher) {
        preview = ImportPreview(emptyList())
        viewModel.import(uri, "")
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.ImportSuccess)
        assertNull(imports.single().workspaces)
        assertEquals(BackupContentRestoreSelection(), imports.single().content)
        verify(exactly = 1) { resolver.openInputStream(uri) }
        coVerify(exactly = 1) { manager.prepareImport(any(), null) }
        assertFalse(snapshots.single().exists())
    }

    @Test
    fun contentOnlyArchivesShowSelectionAndKeepTheManagersRemoteIdentity() = runTest(dispatcher) {
        preview = ImportPreview(emptyList(), contentSections = listOf(ssh), remoteTarget = remoteTarget)
        beginImport()
        val selection = viewModel.state.value as BackupState.ContentSelection
        assertEquals(listOf(ssh), selection.contentSections)
        assertEquals(remoteTarget, selection.remoteTarget)
        assertTrue(imports.isEmpty())
    }

    @Test
    fun previewFailureDeletesThePreparedArchiveAndNeverRestores() = runTest(dispatcher) {
        coEvery { manager.previewImport(any(), any()) } returns Result.failure(IllegalArgumentException("invalid archive"))
        viewModel.import(uri, "import-password")
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.Error)
        assertFalse(snapshots.single().exists())
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        assertTrue(imports.isEmpty())
    }

    @Test
    fun prepareFailureClearsItsPasswordAndDoesNotPreviewOrRestore() = runTest(dispatcher) {
        coEvery { manager.prepareImport(any(), any()) } coAnswers {
            preparePassword = arg(1)
            throw IllegalArgumentException("invalid archive")
        }
        viewModel.import(uri, "import-password")
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.Error)
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        coVerify(exactly = 0) { manager.previewImport(any(), any()) }
        assertTrue(imports.isEmpty())
    }

    @Test
    fun duplicateImportsCannotReplaceAnInFlightOrAwaitingSnapshot() = runTest(dispatcher) {
        val otherUri = mockk<Uri>()
        viewModel.import(uri, "")
        viewModel.import(otherUri, "second-password")
        viewModel.reset()
        assertSame(BackupState.Working, viewModel.state.value)
        awaitOperations()
        val selection = viewModel.state.value
        viewModel.import(otherUri, "second-password")
        assertSame(selection, viewModel.state.value)
        verify(exactly = 0) { resolver.openInputStream(otherUri) }
        coVerify(exactly = 1) { manager.prepareImport(any(), any()) }
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertEquals(1, imports.size)
    }

    @Test
    fun rejectedExportsWhileAwaitingSelectionCloseTheirOutput() = runTest(dispatcher) {
        beginImport()
        val output = TrackingOutput()
        viewModel.export("password", BackupOptions(), output)
        assertTrue(output.closed)
        assertTrue(viewModel.state.value is BackupState.ContentSelection)
        coVerify(exactly = 0) { manager.export(any(), any(), any()) }
    }

    @Test
    fun sshRestoreRequiresTheExactTargetIdentityFromPreview() = runTest(dispatcher) {
        preview = ImportPreview(emptyList(), contentSections = listOf(ssh), remoteTarget = remoteTarget)
        beginImport()
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(ssh.id)))
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(ssh.id), remoteTargetIdentity = "other-target"))
        assertTrue(viewModel.state.value is BackupState.ContentSelection)
        assertTrue(imports.isEmpty())
        val selection = BackupContentRestoreSelection(setOf(ssh.id), overwrite = true, remoteTargetIdentity = remoteTarget.identity)
        viewModel.confirmImportSelection(emptySet(), contentSelection = selection)
        awaitOperations()
        assertEquals(selection, imports.single().content)
    }

    @Test
    fun unavailableSshCanBeSkippedWhileRestoringOnlyCoreSettings() = runTest(dispatcher) {
        preview = ImportPreview(emptyList(), contentSections = listOf(ssh))
        beginImport()
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(ssh.id), remoteTargetIdentity = "invented-target"))
        assertTrue(imports.isEmpty())
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertTrue(imports.single().content.sectionIds.isEmpty())
        assertNull(imports.single().content.remoteTargetIdentity)
    }

    @Test
    fun cachedSshMemoryAndPromptsArePhoneContentWithoutRemoteConfirmation() = runTest(dispatcher) {
        val memory = phone.copy(location = BackupContentLocation.PHONE_REMOTE_PROJECT)
        val prompts = memory.copy(id = "prompts", kind = BackupContentKind.PROMPTS)
        preview = ImportPreview(emptyList(), contentSections = listOf(memory, prompts))
        beginImport()
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(memory.id, prompts.id), remoteTargetIdentity = "stale-target"))
        awaitOperations()
        assertEquals(setOf(memory.id, prompts.id), imports.single().content.sectionIds)
        assertNull(imports.single().content.remoteTargetIdentity)
    }

    @Test
    fun fullPluginRestoreExcludesItsOwnHookButKeepsIndependentExtensions() = runTest(dispatcher) {
        val plugin = BackupContentSectionMeta("full-pet", BackupContentKind.PLUGIN, BackupContentLocation.PLUGIN, "Pet", pluginId = "pet")
        preview = ImportPreview(
            emptyList(),
            pluginSections = listOf(PluginBackupSectionMeta("pet", "Pet hook"), PluginBackupSectionMeta("other", "Other hook")),
            contentSections = listOf(plugin)
        )
        beginImport()
        viewModel.confirmImportSelection(emptySet(), setOf("pet", "other"), BackupContentRestoreSelection(setOf(plugin.id)))
        awaitOperations()
        assertEquals(setOf("other"), imports.single().extensions)
        assertEquals(setOf(plugin.id), imports.single().content.sectionIds)
    }

    @Test
    fun legacyExtensionsCanBeRestoredIndependentlyOrAllSkipped() = runTest(dispatcher) {
        preview = ImportPreview(emptyList(), pluginSections = listOf(PluginBackupSectionMeta("legacy", "Legacy data")))
        beginImport()
        viewModel.confirmImportSelection(emptySet(), setOf("legacy"))
        awaitOperations()
        assertEquals(setOf("legacy"), imports.single().extensions)
        assertTrue(imports.single().content.sectionIds.isEmpty())
        viewModel.reset()
        beginImport()
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertTrue(imports.last().extensions.isEmpty())
        assertTrue(imports.last().workspaces.orEmpty().isEmpty())
    }

    @Test
    fun selectionsAreLimitedToPreviewEntriesAndFrozenBeforeRestoreStarts() = runTest(dispatcher) {
        preview = ImportPreview(
            listOf(WorkspaceBackupMeta("project", 1)),
            pluginSections = listOf(PluginBackupSectionMeta("legacy", "Legacy data")),
            contentSections = listOf(phone)
        )
        beginImport()
        val workspaces = mutableSetOf("project", "unknown-project")
        val extensions = mutableSetOf("legacy", "unknown-extension")
        val contents = mutableSetOf(phone.id, "unknown-content")
        viewModel.confirmImportSelection(workspaces, extensions, BackupContentRestoreSelection(contents))
        workspaces.clear()
        extensions.clear()
        contents.clear()
        awaitOperations()
        assertEquals(setOf("project"), imports.single().workspaces)
        assertEquals(setOf("legacy"), imports.single().extensions)
        assertEquals(setOf(phone.id), imports.single().content.sectionIds)
    }

    @Test
    fun cancelingSelectionDeletesTheSnapshotAndNeverRestores() = runTest(dispatcher) {
        beginImport()
        assertTrue(snapshots.single().exists())
        viewModel.cancelImportSelection()
        awaitOperations()
        assertSame(BackupState.Idle, viewModel.state.value)
        assertFalse(snapshots.single().exists())
        assertTrue(imports.isEmpty())
    }

    @Test
    fun cancelingRemoteSelectionDeletesBothDownloadAndPreparedSnapshot() = runTest(dispatcher) {
        var download: File? = null
        coEvery { storage.download(any(), any()) } coAnswers {
            download = arg(1)
            requireNotNull(download).writeBytes(sourceBytes)
            Result.success(Unit)
        }
        viewModel.restoreFromRemote(target, "backup.tar.gz", "import-password")
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.ContentSelection)
        assertTrue(requireNotNull(download).exists())
        viewModel.cancelImportSelection()
        awaitOperations()
        assertFalse(requireNotNull(download).exists())
        assertFalse(snapshots.single().exists())
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        assertTrue(imports.isEmpty())
    }

    @Test
    fun remoteRestoreKeepsThePasswordUntilPreparationThenDeletesBothFiles() = runTest(dispatcher) {
        viewModel.restoreFromRemote(target, "backup.tar.gz", "remote-password")
        awaitOperations()
        assertEquals("remote-password", preparePasswordText)
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertNull(imports.single().password)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedRemoteDownloadDeletesItsTemporaryFileWithoutPreparingAnImport() = runTest(dispatcher) {
        coEvery { storage.download(any(), any()) } returns Result.failure(IllegalStateException("download failed"))
        viewModel.restoreFromRemote(target, "backup.tar.gz", "remote-password")
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.Error)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
        coVerify(exactly = 0) { manager.prepareImport(any(), any()) }
    }

    @Test
    fun clearingTheViewModelWhileAwaitingSelectionDeletesItsPreparedFile() = runTest(dispatcher) {
        beginImport()
        store.clear()
        awaitOperations()
        assertFalse(snapshots.single().exists())
        assertTrue(imports.isEmpty())
    }

    @Test
    fun cancellationDuringPreparationStillWipesThePasswordAndDeletesTheSnapshot() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { manager.prepareImport(any(), any()) } coAnswers {
            preparePassword = arg(1)
            val file = File.createTempFile("prepared", ".tar.gz", temporaryFolder.root)
            file.writeBytes(arg<InputStream>(0).readBytes())
            snapshots += file
            entered.complete(Unit)
            release.await()
            file
        }
        viewModel.import(uri, "import-password")
        try {
            entered.await()
            store.clear()
        } finally {
            release.complete(Unit)
        }
        awaitOperations()
        assertFalse(snapshots.single().exists())
        assertTrue(requireNotNull(preparePassword).all { it == '\u0000' })
        assertTrue(imports.isEmpty())
    }

    @Test
    fun partialContentResultsArePreservedRatherThanConvertedToAnErrorOrFullSuccess() = runTest(dispatcher) {
        val stats = RestoreStats(
            providers = 1,
            contentSections = listOf(BackupContentRestoreResult(phone.id, phone.kind, phone.label, BackupContentRestoreStatus.PARTIAL, 1, 2, 1))
        )
        coEvery { manager.import(any(), any(), any(), any(), any()) } returns Result.success(stats)
        beginImport()
        viewModel.confirmImportSelection(emptySet(), contentSelection = BackupContentRestoreSelection(setOf(phone.id)))
        awaitOperations()
        assertEquals(stats, (viewModel.state.value as BackupState.ImportSuccess).stats)
        assertTrue(stats.hasContentRestoreIssues())
        assertFalse(snapshots.single().exists())
    }

    @Test
    fun restoreFailuresCleanTheSnapshotWithoutLoggingEntryBodiesOrExceptions() = runTest(dispatcher) {
        val diagnostic = "private entry body"
        coEvery { manager.import(any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException(diagnostic))
        beginImport()
        viewModel.confirmImportSelection(emptySet())
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.Error)
        assertFalse(snapshots.single().exists())
        verify(exactly = 0) { FileLogger.e(any(), match { it.contains(diagnostic) }, any()) }
        assertTrue(loggedErrors.all { it.second == null })
    }

    private fun newViewModel(): BackupViewModel = BackupViewModel(context, manager, repository).also {
        store.put("backup-${models.size}", it)
        models += it
        sharingJobs += it.viewModelScope.coroutineContext.job.children.toSet()
    }

    private suspend fun awaitOperations(model: BackupViewModel = viewModel) {
        model.viewModelScope.coroutineContext.job.children.filterNot { it in sharingJobs }.toList().joinAll()
    }

    private suspend fun beginImport(password: String = "") {
        viewModel.import(uri, password)
        awaitOperations()
        assertTrue(viewModel.state.value is BackupState.ContentSelection)
        assertNotNull(snapshots.lastOrNull())
    }

    private data class ImportCall(
        val bytes: ByteArray,
        val password: CharArray?,
        val workspaces: Set<String>?,
        val extensions: Set<String>,
        val content: BackupContentRestoreSelection
    )

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
