package com.aicode.feature.backup.data.pluginbackup

import com.aicode.feature.backup.data.BackupContentArchive
import com.aicode.feature.backup.data.PluginFullBackupService
import com.aicode.feature.backup.data.PreparedBackupContent
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginSecretStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginFullBackupServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Host(val root: File) {
        val manager = PluginManager(File(root, "plugins"), File(root, "host/registry.json"))
        val secretDirectory = File(root, "host/secrets")
        val secrets = PluginSecretStore(secretDirectory, { "fixture-new:$it" }, { it.removePrefix("fixture-new:") })
        val cache = File(root, "cache").apply { mkdirs() }
        val closed = arrayListOf<String>()
        val revoked = arrayListOf<String>()
        var closeAllowed = true
        var beforeRevoke: suspend (String) -> Unit = {}
        val service = PluginFullBackupService(cache, manager, secrets,
            { id -> beforeRevoke(id); revoked += id }, { id -> closed += id; closeAllowed })
        fun data(id: String = "sample.plugin") = File(root, "plugins/data/$id")
    }
    private fun host() = Host(temporary.newFolder())
    private suspend fun backup(host: Host, extra: List<Pair<String, ByteArray>> = emptyList()): PreparedBackupContent {
        PluginCoreFixtures.install(host.manager, PluginCoreFixtures.archive(extra = extra))
        return host.service.prepareExport().single()
    }
    private fun counted(section: PreparedBackupContent) = BackupContentArchive.metadata(section.metadata.id, BackupContentKind.PLUGIN,
        BackupContentLocation.PLUGIN, section.metadata.label, section.directory).copy(pluginId = section.metadata.pluginId,
        pluginVersion = section.metadata.pluginVersion, pluginContentHash = section.metadata.pluginContentHash)
    private fun put(root: File, relative: String, value: String) = File(root, relative).apply { parentFile.mkdirs(); writeText(value) }

    @Test fun exportsDisabledAndEnabledWithoutEnablingOrCreatingPrivateData() = runBlocking<Unit> {
        val host = host()
        PluginCoreFixtures.install(host.manager)
        PluginCoreFixtures.install(host.manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = "sample.second")))
        val active = PluginCoreFixtures.activate(host.manager, "sample.second")
        val before = host.manager.snapshot.value
        val sections = host.service.prepareExport()
        try {
            assertEquals(setOf("sample.plugin", "sample.second"), sections.map { it.metadata.pluginId }.toSet())
            assertSame(before, host.manager.snapshot.value)
            assertFalse(host.data().exists())
            assertFalse(host.data("sample.second").exists())
            assertNull(host.manager.capture("sample.plugin"))
            active.checkCurrent()
            assertTrue(host.closed.isEmpty())
            assertTrue(host.revoked.isEmpty())
        } finally { host.service.cleanup(sections) }
    }

    @Test fun packageIncludesRecordedNodeModulesVenvAndAllHashes() = runBlocking<Unit> {
        val host = host()
        val section = backup(host, listOf("node_modules/legal/index.js" to "fixture-node".toByteArray(),
            ".venv/legal.py" to "fixture-python".toByteArray(), "assets/image.bin" to byteArrayOf(0, 1, 2)))
        try {
            val contents = linkedMapOf<String, ByteArray>()
            ZipInputStream(File(section.directory, "package.zip").inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; contents[entry.name] = zip.readBytes() }
            }
            val record = host.manager.plugins.value.single().record
            assertEquals(record.fileHashes.keys, contents.keys)
            assertEquals("fixture-node", contents.getValue("node_modules/legal/index.js").toString(Charsets.UTF_8))
            assertEquals("fixture-python", contents.getValue(".venv/legal.py").toString(Charsets.UTF_8))
            assertEquals(record.contentHash, section.metadata.pluginContentHash)
            assertEquals(record.version, section.metadata.sourceLabel)
        } finally { host.service.cleanup(listOf(section)) }
    }

    @Test fun copiesLongDeepDataAndEmptyDirectoriesWithoutPackagePathLimits() = runBlocking<Unit> {
        val host = host()
        PluginCoreFixtures.install(host.manager)
        val path = (1..63).joinToString("/") { "segment$it" } + "/settings.json"
        put(host.data(), path, "fixture-config")
        File(host.data(), "empty/folder").mkdirs()
        val section = host.service.prepareExport().single()
        val target = host()
        try {
            assertEquals("fixture-config", File(section.directory, "data/$path").readText())
            val result = target.service.restore(section.metadata, section.directory, false)
            assertEquals(BackupContentRestoreStatus.RESTORED, result.status)
            assertEquals("fixture-config", File(target.data(), path).readText())
            assertTrue(File(target.data(), "empty/folder").isDirectory)
        } finally { host.service.cleanup(listOf(section)) }
    }

    @Test fun excludesOnlyHostManagedDependencyRevisionsAndRootBackupStagingDirectories() = runBlocking<Unit> {
        val host = host()
        val installed = PluginCoreFixtures.install(host.manager)
        put(host.data(), "dependencies/${installed.record.installRevision}/node_modules/generated.js", "generated")
        put(host.data(), "dependencies/user-config/preferences.json", "keep-dependencies")
        put(host.data(), "nested/backup-import/keep", "keep-nested")
        put(host.data(), "backup-import/temporary", "host-temporary")
        put(host.data(), "backup-export/temporary", "host-temporary")
        put(host.data(), "cache/important", "keep-cache")
        put(host.data(), "node_modules/user-payload", "keep-user")
        val section = host.service.prepareExport().single()
        try {
            val data = File(section.directory, "data")
            assertFalse(File(data, "dependencies/${installed.record.installRevision}").exists())
            assertFalse(File(data, "backup-import").exists())
            assertFalse(File(data, "backup-export").exists())
            assertEquals("keep-dependencies", File(data, "dependencies/user-config/preferences.json").readText())
            assertEquals("keep-nested", File(data, "nested/backup-import/keep").readText())
            assertEquals("keep-cache", File(data, "cache/important").readText())
            assertEquals("keep-user", File(data, "node_modules/user-payload").readText())
        } finally { host.service.cleanup(listOf(section)) }
    }

    @Test fun cleanupOnlyRemovesStagesOwnedByThisService() = runBlocking<Unit> {
        val host = host()
        val section = backup(host)
        val foreign = temporary.newFolder()
        put(foreign, "keep", "fixture")
        host.service.cleanup(listOf(PreparedBackupContent(section.metadata, foreign)))
        host.service.cleanup(listOf(PreparedBackupContent(section.metadata, host.cache)))
        assertTrue(section.directory.exists())
        assertTrue(foreign.exists())
        assertTrue(host.cache.exists())
        host.service.cleanup(listOf(section, section))
        assertFalse(section.directory.exists())
        assertEquals("fixture", File(foreign, "keep").readText())
    }

    @Test fun checkCurrentRejectsInstallRevisionChangedAfterPreparation() = runBlocking<Unit> {
        val host = host()
        val section = backup(host)
        try {
            PluginCoreFixtures.install(host.manager)
            assertThrows(IllegalStateException::class.java) { section.checkCurrent() }
        } finally { host.service.cleanup(listOf(section)) }
    }

    @Test fun modifiedInstalledBundleFailsExportAndDoesNotLeavePlaintextStaging() = runBlocking<Unit> {
        val host = host()
        val installed = PluginCoreFixtures.install(host.manager)
        put(File(host.root, "plugins/installed/${installed.record.id}/${installed.record.installRevision}"), "skills/hello.md", "tampered")
        assertTrue(runCatching { host.service.prepareExport() }.isFailure)
        assertTrue(host.cache.listFiles()!!.isEmpty())
        assertFalse(host.data().exists())
    }

    @Test fun existingIdDefaultsToWholeItemConflictWithoutTouchingTrustDataOrSecrets() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        PluginCoreFixtures.install(target.manager)
        val active = PluginCoreFixtures.activate(target.manager)
        put(target.data(), "config", "current-fixture")
        target.secrets.call(active, "set", "key", "fixture-current-secret")
        val before = target.manager.snapshot.value
        val secretBytes = File(target.secretDirectory, "sample.plugin.json").readBytes()
        try {
            val result = target.service.restore(section.metadata, section.directory, false)
            assertEquals(BackupContentRestoreStatus.CONFLICT, result.status)
            assertEquals(1, result.conflicts)
            assertEquals(0, result.restoredFiles)
            assertSame(before, target.manager.snapshot.value)
            active.checkCurrent()
            assertEquals("current-fixture", File(target.data(), "config").readText())
            assertArrayEquals(secretBytes, File(target.secretDirectory, "sample.plugin.json").readBytes())
            assertTrue(target.closed.isEmpty())
            assertTrue(target.revoked.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun restoreInstallsNewLocalDisabledRevisionAndReplacesDataInsteadOfMerging() = runBlocking<Unit> {
        val source = host()
        PluginCoreFixtures.install(source.manager)
        val activeSource = PluginCoreFixtures.activate(source.manager)
        put(source.data(), "config.json", "backup-config")
        source.secrets.call(activeSource, "set", "api", "fixture-backup-secret")
        val section = source.service.prepareExport().single()
        val target = host()
        val newer = PluginCoreFixtures.install(target.manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(version = "2.0.0")))
        val previous = PluginCoreFixtures.activate(target.manager)
        put(target.data(), "old-only", "old-data")
        try {
            val result = target.service.restore(section.metadata, section.directory, true)
            assertEquals(BackupContentRestoreStatus.RESTORED, result.status)
            assertEquals(section.metadata.fileCount, result.restoredFiles)
            val record = target.manager.plugins.value.single().record
            assertNotEquals(newer.record.installRevision, record.installRevision)
            assertNotEquals(activeSource.installRevision, record.installRevision)
            assertEquals("1.0.0", record.version)
            assertEquals(PluginInstallOriginKind.LOCAL, record.origin)
            assertFalse(record.officialUpstream)
            assertNull(record.sourceId)
            assertFalse(record.enabled)
            assertFalse(record.dependenciesReady)
            assertNull(record.trustHash)
            assertNull(target.manager.capture(record.id))
            assertThrows(IllegalStateException::class.java) { previous.checkCurrent() }
            assertFalse(File(target.data(), "old-only").exists())
            assertEquals("backup-config", File(target.data(), "config.json").readText())
            assertEquals(listOf(record.id), target.closed)
            assertEquals(listOf(record.id), target.revoked)
            val migrated = target.secrets.exportForBackup(target.manager.stableInstallSnapshots().single())
            assertEquals(mapOf("api" to "fixture-backup-secret"), migrated)
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun concurrentLocalInstallBeforeBackupPreviewBecomesConflictWithoutRevokingNewTrust() = runBlocking<Unit> {
        val source = host()
        PluginCoreFixtures.install(source.manager)
        val sourceCapture = PluginCoreFixtures.activate(source.manager)
        source.secrets.call(sourceCapture, "set", "api", "fixture-backup-secret")
        val section = source.service.prepareExport().single()
        val target = host()
        var current: com.aicode.feature.plugin.domain.PluginCapture? = null
        val racingSecrets = PluginSecretStore(target.secretDirectory, {
            runBlocking {
                PluginCoreFixtures.install(target.manager)
                current = PluginCoreFixtures.activate(target.manager)
            }
            "fixture-cipher:$it"
        }, { it.removePrefix("fixture-cipher:") })
        var touched = false
        val service = PluginFullBackupService(target.cache, target.manager, racingSecrets,
            { touched = true }, { touched = true; true })
        try {
            val result = service.restore(section.metadata, section.directory, false)
            assertEquals(BackupContentRestoreStatus.CONFLICT, result.status)
            requireNotNull(current).checkCurrent()
            assertFalse(touched)
            assertFalse(target.secretDirectory.exists())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun emptyBackupDataReplacesOldDataAndEmptySecretsClearOldKeys() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        PluginCoreFixtures.install(target.manager)
        val active = PluginCoreFixtures.activate(target.manager)
        put(target.data(), "obsolete", "fixture-old")
        target.secrets.call(active, "set", "old", "fixture-old-secret")
        try {
            assertEquals(BackupContentRestoreStatus.RESTORED, target.service.restore(section.metadata, section.directory, true).status)
            assertTrue(target.data().listFiles()!!.isEmpty())
            assertEquals(emptyMap<String, String>(), target.secrets.exportForBackup(target.manager.stableInstallSnapshots().single()))
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun cannotRestoreDataWithoutPackageFallback() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        File(section.directory, "package.zip").delete()
        val target = host()
        try {
            val result = target.service.restore(section.metadata, section.directory, false)
            assertEquals(BackupContentRestoreStatus.FAILED, result.status)
            assertTrue(target.manager.plugins.value.isEmpty())
            assertTrue(target.closed.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun rejectsMetadataPackageIdentityMismatchBeforeRevokingOrInstalling() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        try {
            for (meta in listOf(section.metadata.copy(pluginId = "other.plugin"), section.metadata.copy(pluginVersion = "9.0.0"),
                section.metadata.copy(pluginContentHash = "0".repeat(64)))) {
                assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(meta, section.directory, true).status)
                assertTrue(target.manager.plugins.value.isEmpty())
                assertTrue(target.closed.isEmpty())
            }
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun rejectsUnsafeMetadataIdsAndInvalidKindsBeforeTouchingPluginStorage() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        try {
            for (meta in listOf(section.metadata.copy(pluginId = "../outside"), section.metadata.copy(pluginId = "/absolute"),
                section.metadata.copy(kind = BackupContentKind.MEMORY), section.metadata.copy(pluginContentHash = null),
                section.metadata.copy(pluginVersion = "bad"))) {
                assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(meta, section.directory, true).status)
            }
            assertTrue(target.manager.plugins.value.isEmpty())
            assertTrue(target.cache.listFiles()!!.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun rejectsUnexpectedRootPayloadOrCountMismatchBeforeAnyCommit() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        try {
            assertEquals(BackupContentRestoreStatus.FAILED,
                target.service.restore(section.metadata.copy(fileCount = section.metadata.fileCount + 1), section.directory, true).status)
            put(section.directory, "trust.json", "not-restorable")
            assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(section.metadata, section.directory, true).status)
            assertTrue(target.revoked.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun invalidSecretsAreStagedAndRejectedBeforePackageCommit() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        File(section.directory, "secrets.json").writeText("{\"fixture\":true}")
        val target = host()
        try {
            assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(counted(section), section.directory, false).status)
            assertTrue(target.manager.plugins.value.isEmpty())
            assertTrue(target.closed.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun badPackageContentIsRejectedWithoutChangingExistingInstall() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        File(section.directory, "package.zip").writeBytes(PluginCoreFixtures.archive(extra = listOf("changed" to byteArrayOf(1))))
        val target = host()
        val installed = PluginCoreFixtures.install(target.manager)
        val active = PluginCoreFixtures.activate(target.manager)
        try {
            assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(counted(section), section.directory, true).status)
            assertEquals(installed.record.installRevision, target.manager.plugins.value.single().record.installRevision)
            active.checkCurrent()
            assertTrue(target.revoked.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun runtimeCloseFailureNeverInstallsPackageOrTouchesOldData() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        val old = PluginCoreFixtures.install(target.manager)
        PluginCoreFixtures.activate(target.manager)
        put(target.data(), "keep", "fixture-old-data")
        target.closeAllowed = false
        try {
            val result = target.service.restore(section.metadata, section.directory, true)
            assertEquals(BackupContentRestoreStatus.FAILED, result.status)
            assertEquals("PLUGIN_RUNTIME_BUSY", result.code)
            assertEquals(old.record.installRevision, target.manager.plugins.value.single().record.installRevision)
            assertEquals("fixture-old-data", File(target.data(), "keep").readText())
            assertFalse(target.manager.plugins.value.single().isActive)
            assertTrue(target.revoked.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun stagedDataChangeDuringFenceIsRejectedBeforePackageCommit() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        val old = PluginCoreFixtures.install(target.manager)
        put(target.data(), "keep", "fixture-old-data")
        target.beforeRevoke = {
            val pending = File(target.root, "plugins/data").listFiles()!!.single { it.name.startsWith(".backup-import-") }
            File(pending, "intruder").writeText("fixture-tamper")
        }
        try {
            val result = target.service.restore(section.metadata, section.directory, true)
            assertEquals(BackupContentRestoreStatus.FAILED, result.status)
            assertEquals("PLUGIN_DATA_INVALID", result.code)
            assertEquals(old.record.installRevision, target.manager.plugins.value.single().record.installRevision)
            assertEquals("fixture-old-data", File(target.data(), "keep").readText())
            assertFalse(File(target.root, "plugins/data").listFiles()!!.any { it.name.startsWith(".backup-import-") })
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun dataCommitFailureReportsPartialPackageAndPreservesOldDataEntry() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        val old = PluginCoreFixtures.install(target.manager)
        target.data().writeText("fixture-old-data-entry")
        try {
            val result = target.service.restore(section.metadata, section.directory, true)
            assertEquals(BackupContentRestoreStatus.PARTIAL, result.status)
            assertEquals("PLUGIN_PACKAGE_RESTORED_DATA_FAILED", result.code)
            assertEquals(1, result.restoredFiles)
            assertNotEquals(old.record.installRevision, target.manager.plugins.value.single().record.installRevision)
            assertEquals("fixture-old-data-entry", target.data().readText())
            assertNull(target.manager.capture("sample.plugin"))
            assertFalse(File(target.secretDirectory, "sample.plugin.json").exists())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun secretCommitFailureReportsPackageAndDataRestoredNotFullRollback() = runBlocking<Unit> {
        val source = host()
        PluginCoreFixtures.install(source.manager)
        put(source.data(), "config", "fixture-backup-data")
        val section = source.service.prepareExport().single()
        val target = host()
        File(target.secretDirectory, "sample.plugin.json").mkdirs()
        try {
            val result = target.service.restore(section.metadata, section.directory, false)
            assertEquals(BackupContentRestoreStatus.PARTIAL, result.status)
            assertEquals("PLUGIN_PACKAGE_DATA_RESTORED_SECRETS_FAILED", result.code)
            assertEquals(2, result.restoredFiles)
            assertEquals("fixture-backup-data", File(target.data(), "config").readText())
            assertFalse(target.manager.plugins.value.single().isActive)
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun grantRevocationFailurePreventsPackageCommit() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        val old = PluginCoreFixtures.install(target.manager)
        target.beforeRevoke = { throw java.io.IOException("fixture failure") }
        try {
            assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(section.metadata, section.directory, true).status)
            assertEquals(old.record.installRevision, target.manager.plugins.value.single().record.installRevision)
            assertTrue(target.cache.listFiles()!!.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun rejectsSymbolicPrivateDataAndLeavesOutsideFilesUntouched() = runBlocking<Unit> {
        val host = host()
        PluginCoreFixtures.install(host.manager)
        host.data().mkdirs()
        val outside = temporary.newFile().apply { writeText("outside-fixture") }
        Files.createSymbolicLink(File(host.data(), "linked").toPath(), outside.toPath())
        assertTrue(runCatching { host.service.prepareExport() }.isFailure)
        assertEquals("outside-fixture", outside.readText())
        assertTrue(host.cache.listFiles()!!.isEmpty())
    }

    @Test fun rejectsHardlinkedPrivateData() = runBlocking<Unit> {
        val host = host()
        PluginCoreFixtures.install(host.manager)
        host.data().mkdirs()
        val outside = temporary.newFile().apply { writeText("outside-fixture") }
        Files.createLink(File(host.data(), "linked").toPath(), outside.toPath())
        assertTrue(runCatching { host.service.prepareExport() }.isFailure)
        assertEquals("outside-fixture", outside.readText())
    }

    @Test fun rejectsSymlinkPackageFileAndSymlinkRootDataOnImport() = runBlocking<Unit> {
        for (path in listOf("package.zip", "data")) {
            val source = host()
            val section = backup(source)
            val target = host()
            val original = File(section.directory, path)
            val outside = temporary.newFolder()
            if (path == "package.zip") File(outside, "package.zip").writeBytes(original.readBytes())
            original.delete()
            Files.createSymbolicLink(original.toPath(), if (path == "data") outside.toPath() else File(outside, "package.zip").toPath())
            try {
                assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(section.metadata, section.directory, true).status)
                assertTrue(target.manager.plugins.value.isEmpty())
            } finally { source.service.cleanup(listOf(section)) }
        }
    }

    @Test fun rejectsHostManagedTemporaryDataInUntrustedPayload() = runBlocking<Unit> {
        for (path in listOf("backup-import/fixture", "backup-export/fixture", "dependencies/${UUID.randomUUID()}/fixture")) {
            val source = host()
            val section = backup(source)
            put(File(section.directory, "data"), path, "fixture")
            val target = host()
            try {
                assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(counted(section), section.directory, true).status)
                assertTrue(target.closed.isEmpty())
            } finally { source.service.cleanup(listOf(section)) }
        }
    }

    @Test fun rejectsSpecialUnixZipPayloadEvenIfCommonPackageReaderWouldMaterializeItAsFile() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { zip ->
            val entry = ZipArchiveEntry("special").apply { unixMode = 0x1000 or 0x180 }
            zip.putArchiveEntry(entry); zip.closeArchiveEntry()
        }
        File(section.directory, "package.zip").writeBytes(bytes.toByteArray())
        val target = host()
        try {
            assertEquals(BackupContentRestoreStatus.FAILED, target.service.restore(counted(section), section.directory, true).status)
            assertTrue(target.closed.isEmpty())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun dataPathsUseIndependent4096CharactersAnd64SegmentsLimits() {
        assertTrue(PluginFullBackupService.validDataPath((1..63).joinToString("/") { "folder" } + "/config"))
        assertFalse(PluginFullBackupService.validDataPath((1..64).joinToString("/") { "folder" } + "/config"))
        val longest = (1..16).joinToString("/") { "a".repeat(254) } + "/" + "b".repeat(16)
        assertEquals(4096, longest.length)
        assertTrue(PluginFullBackupService.validDataPath(longest))
        assertFalse(PluginFullBackupService.validDataPath(longest + "x"))
        assertFalse(PluginFullBackupService.validDataPath("../outside"))
        assertFalse(PluginFullBackupService.validDataPath("/absolute"))
        assertFalse(PluginFullBackupService.validDataPath("folder\\file"))
        assertFalse(PluginFullBackupService.validDataPath("a".repeat(256)))
    }

    @Test fun sharedArchiveRoundTripKeepsFullPayloadInContentNamespaceAndRestoreIgnoresExecutionBits() = runBlocking<Unit> {
        val source = host()
        PluginCoreFixtures.install(source.manager)
        put(source.data(), "config", "fixture-config").setExecutable(true, true)
        val section = source.service.prepareExport().single()
        File(section.directory, "data/config").setExecutable(true, true)
        val bytes = ByteArrayOutputStream()
        val imported = temporary.newFolder()
        val batch = BackupContentArchive.prepareImport(listOf(section.metadata), BackupContentRestoreSelection(setOf(section.metadata.id)), imported)
        try {
            TarArchiveOutputStream(bytes).use { BackupContentArchive.writeExport(it, section) }
            TarArchiveInputStream(bytes.toByteArray().inputStream()).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    assertTrue(entry.name.startsWith("content/"))
                    BackupContentArchive.readImport(tar, entry, batch)
                }
            }
            BackupContentArchive.finishImport(batch)
            val target = host()
            assertEquals(BackupContentRestoreStatus.RESTORED,
                target.service.restore(section.metadata, batch.directories.getValue(section.metadata.id), false).status)
            assertEquals("fixture-config", File(target.data(), "config").readText())
            assertFalse(File(target.data(), "config").canExecute())
        } finally { source.service.cleanup(listOf(section)) }
    }

    @Test fun restorationNeverDispatchesLifecycleHooksOrTouchesOtherPlugin() = runBlocking<Unit> {
        val source = host()
        val section = backup(source)
        val target = host()
        PluginCoreFixtures.install(target.manager)
        PluginCoreFixtures.activate(target.manager)
        PluginCoreFixtures.install(target.manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = "other.plugin")))
        val other = PluginCoreFixtures.activate(target.manager, "other.plugin")
        val lifecycle = arrayListOf<String>()
        target.manager.registerLifecycleSink { lifecycle += it.event }
        try {
            assertEquals(BackupContentRestoreStatus.RESTORED, target.service.restore(section.metadata, section.directory, true).status)
            assertTrue(lifecycle.isEmpty())
            other.checkCurrent()
            assertEquals(listOf("sample.plugin"), target.closed)
            assertEquals(listOf("sample.plugin"), target.revoked)
        } finally { source.service.cleanup(listOf(section)) }
    }
}
