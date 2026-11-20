package com.aicode.feature.plugin.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginInstallTransaction
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.PluginRegistryStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 10_000L
    private fun root() = File(temporary.root, "plugins")
    private fun registry() = File(temporary.root, "host/registry.json")
    private fun manager() = PluginManager(root(), registry()) { now }
    private fun operationError(result: PluginOperationResult) = (result as PluginOperationResult.Failure).errors.first().code
    private fun installedPath(revision: String, id: String = "sample.plugin") = File(root(), "installed/$id/$revision")

    @Test fun installStartsDisabledAndPreviewNeverExecutesContent() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        assertFalse(installed.record.enabled)
        assertNull(installed.record.trustHash)
        assertFalse(installed.isActive)
        assertNull(manager.capture("sample.plugin"))
        assertTrue(manager.skills().isEmpty())
        assertTrue(installedPath(installed.record.installRevision).isDirectory)
    }
    @Test fun onlyNativeTrustChallengeEnablesAndChallengeIsSingleUse() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        assertEquals(PluginErrorCode.TRUST_REQUIRED, operationError(manager.enable("sample.plugin", "invented")))
        val trust = manager.prepareTrust("sample.plugin")
        assertTrue(trust.errors.isEmpty())
        assertEquals(PluginOperationResult.Success, manager.enable("sample.plugin", trust.token))
        assertEquals(PluginErrorCode.TRUST_REQUIRED, operationError(manager.enable("sample.plugin", trust.token)))
        val capture = requireNotNull(manager.capture("sample.plugin"))
        capture.checkActive()
        capture.checkCurrent()
        assertTrue(manager.checkCurrent(capture))
        assertEquals(capture.contentHash, capture.record.trustHash)
    }
    @Test fun trustTokenIsBoundToPluginId() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = "sample.second")))
        val trust = manager.prepareTrust("sample.plugin")
        assertEquals(PluginErrorCode.STALE_CAPTURE, operationError(manager.enable("sample.second", trust.token)))
        assertNull(manager.capture("sample.plugin"))
        assertNull(manager.capture("sample.second"))
    }
    @Test fun expiredTrustAndPreviewTokensFail() = runBlocking<Unit> {
        val manager = manager()
        val preview = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        now = preview.expiresAt
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, (manager.install(preview.token) as PluginInstallResult.Failure).errors.first().code)
        PluginCoreFixtures.install(manager)
        val trust = manager.prepareTrust("sample.plugin")
        now = trust.expiresAt
        assertEquals(PluginErrorCode.TRUST_EXPIRED, operationError(manager.enable("sample.plugin", trust.token)))
    }
    @Test fun discardPreviewReleasesAllSlotsImmediatelyAndPreservesOtherStaging() = runBlocking<Unit> {
        val manager = manager()
        val previews = (0 until 8).map {
            (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        }
        val staging = File(root(), "staging")
        val originalDirectories = staging.listFiles()!!.toSet()
        assertEquals(8, originalDirectories.size)
        assertEquals(PluginErrorCode.STORAGE_FAILED, (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Failure).errors.single().code)
        val userFile = File(staging, "user-owned/keep").apply { parentFile.mkdirs(); writeText("keep") }
        manager.discardPreview(previews.first().token)
        manager.discardPreview(previews.first().token)
        assertEquals(7, originalDirectories.count { it.exists() })
        assertEquals("keep", userFile.readText())
        previews.drop(1).forEach { manager.discardPreview(it.token) }
        repeat(12) {
            val preview = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
            manager.discardPreview(preview.token)
        }
        assertEquals(listOf(userFile.parentFile), staging.listFiles()!!.toList())
        assertEquals("keep", userFile.readText())
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, (manager.install(previews.first().token) as PluginInstallResult.Failure).errors.single().code)
    }
    @Test fun discardUnknownForeignOrInstalledTokenDoesNotTouchPackagesDataOrOtherManagerPreview() = runBlocking<Unit> {
        val manager = manager()
        val preview = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        val installed = (manager.install(preview.token) as PluginInstallResult.Success).plugin
        val capture = PluginCoreFixtures.activate(manager)
        val data = File(manager.dataDirectory(installed.record.id), "keep").apply { writeText("keep") }
        val other = manager()
        val foreign = (other.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        val foreignDirectory = File(root(), "staging").listFiles()!!.single()
        val before = manager.snapshot.value
        manager.discardPreview("unknown")
        manager.discardPreview("../../installed/${installed.record.id}")
        manager.discardPreview(foreign.token)
        manager.discardPreview(preview.token)
        manager.discardPreview(preview.token)
        assertSame(before, manager.snapshot.value)
        assertTrue(foreignDirectory.isDirectory)
        assertEquals("keep", data.readText())
        capture.checkCurrent()
        other.discardPreview(foreign.token)
        assertFalse(foreignDirectory.exists())
        capture.checkCurrent()
    }
    @Test fun stalePreviewCannotReplaceSameVersionNewRevisionOrRevokeItsTrust() = runBlocking<Unit> {
        val manager = manager()
        val original = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val stale = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        val replacement = PluginCoreFixtures.install(manager)
        val current = PluginCoreFixtures.activate(manager)
        assertEquals(original.record.version, replacement.record.version)
        assertEquals(original.record.contentHash, replacement.record.contentHash)
        assertNotEquals(original.record.installRevision, replacement.record.installRevision)
        val before = manager.snapshot.value
        val bytes = registry().readBytes()
        val failed = manager.install(stale.token) as PluginInstallResult.Failure
        assertEquals(PluginErrorCode.STALE_CAPTURE, failed.errors.single().code)
        assertSame(before, manager.snapshot.value)
        assertArrayEquals(bytes, registry().readBytes())
        assertEquals(current.installRevision, manager.capture(current.id)?.installRevision)
        assertTrue(manager.plugins.value.single().isActive)
        current.checkCurrent()
        assertTrue(File(root(), "staging").listFiles()!!.isEmpty())
        assertEquals(1, current.installDir.parentFile.listFiles()!!.size)
    }
    @Test fun previewOfMissingInstallCannotOverwriteLaterInstallAndPreviewOfRemovedInstallCannotRecreateIt() = runBlocking<Unit> {
        val manager = manager()
        val absent = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        assertNull(absent.installedVersion)
        PluginCoreFixtures.install(manager)
        val current = PluginCoreFixtures.activate(manager)
        val before = manager.snapshot.value
        assertEquals(PluginErrorCode.STALE_CAPTURE, (manager.install(absent.token) as PluginInstallResult.Failure).errors.single().code)
        assertSame(before, manager.snapshot.value)
        current.checkCurrent()
        val removed = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        manager.uninstall(current.id)
        val uninstalled = manager.snapshot.value
        assertEquals(PluginErrorCode.STALE_CAPTURE, (manager.install(removed.token) as PluginInstallResult.Failure).errors.single().code)
        assertSame(uninstalled, manager.snapshot.value)
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(File(root(), "staging").listFiles()!!.isEmpty())
    }
    @Test fun disableThenReenableNeverRevivesOldCapture() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val first = PluginCoreFixtures.activate(manager)
        assertEquals(PluginOperationResult.Success, manager.disable(first.id))
        assertThrows(PluginCaptureException::class.java) { first.checkActive() }
        val second = PluginCoreFixtures.activate(manager)
        assertTrue(second.activationEpoch > first.activationEpoch)
        second.checkCurrent()
        assertFalse(manager.checkCurrent(first))
        assertThrows(PluginCaptureException::class.java) { first.checkActive() }
    }
    @Test fun sameVersionIdenticalReinstallAlwaysGetsNewRevisionAndNeedsNewTrust() = runBlocking<Unit> {
        val manager = manager()
        val first = PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val second = PluginCoreFixtures.install(manager)
        assertEquals(first.record.version, second.record.version)
        assertEquals(first.record.contentHash, second.record.contentHash)
        assertNotEquals(first.record.installRevision, second.record.installRevision)
        assertNull(second.record.trustHash)
        assertFalse(second.isActive)
        assertNull(manager.capture(capture.id))
        assertFalse(manager.checkCurrent(capture))
        assertFalse(installedPath(first.record.installRevision).exists())
    }
    @Test fun changedSameVersionReinstallDoesNotInheritTrustOrContent() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val bytes = PluginCoreFixtures.archive(extra = listOf("main.js" to "new contents".toByteArray()))
        val replacement = PluginCoreFixtures.install(manager, bytes)
        assertEquals(old.record.version, replacement.record.version)
        assertNotEquals(old.record.contentHash, replacement.record.contentHash)
        assertFalse(replacement.record.enabled)
        assertNull(replacement.record.trustHash)
    }
    @Test fun challengeForOldRevisionCannotEnableReplacement() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val old = manager.prepareTrust("sample.plugin")
        PluginCoreFixtures.install(manager)
        assertTrue(manager.enable("sample.plugin", old.token) is PluginOperationResult.Failure)
        assertNull(manager.capture("sample.plugin"))
    }
    @Test fun pendingTrustIsInvalidatedByDisable() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val pending = manager.prepareTrust("sample.plugin")
        manager.disable("sample.plugin")
        assertTrue(manager.enable("sample.plugin", pending.token) is PluginOperationResult.Failure)
    }
    @Test fun trustChecksAllResourcesBeforeAndAfterNativeConfirmation() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(extra = listOf("assets/side.js" to "first".toByteArray())))
        val trust = manager.prepareTrust("sample.plugin")
        File(installedPath(installed.record.installRevision), "assets/side.js").writeText("other")
        assertEquals(PluginErrorCode.CONTENT_CHANGED, operationError(manager.enable("sample.plugin", trust.token)))
        assertNull(manager.capture("sample.plugin"))
        assertEquals(PluginErrorCode.CONTENT_CHANGED, manager.prepareTrust("sample.plugin").errors.single().code)
    }
    @Test fun fullCaptureCheckRejectsModifiedAddedAndRemovedResources() = runBlocking<Unit> {
        for (change in listOf("modified", "added", "removed")) {
            val sub = temporary.newFolder()
            val manager = PluginManager(File(sub, "plugins"), File(sub, "registry.json")) { now }
            PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(extra = listOf("assets/side.js" to "first".toByteArray())))
            val capture = PluginCoreFixtures.activate(manager)
            when (change) {
                "modified" -> File(capture.installDir, "assets/side.js").writeText("other")
                "added" -> File(capture.installDir, "extra.py").writeText("new")
                else -> assertTrue(File(capture.installDir, "assets/side.js").delete())
            }
            assertFalse(change, manager.checkCurrent(capture))
            assertNull(manager.capture(capture.id))
            assertThrows(PluginCaptureException::class.java) { capture.checkActive() }
        }
    }
    @Test fun resourceSymlinkIsRejectedEvenWhenTargetHasSameBytes() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val resource = File(capture.installDir, "skills/hello.md")
        val outside = temporary.newFile("outside.md").apply { writeText(resource.readText()) }
        assertTrue(resource.delete())
        Files.createSymbolicLink(resource.toPath(), outside.toPath())
        assertFalse(manager.checkCurrent(capture))
        assertEquals("---\nname: forged\ndescription: useful\n---\nDo good work.", outside.readText())
    }
    @Test fun activeContributionSourceNeverReselectsNewInstall() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val source = manager.skills().single()
        assertEquals("sample.plugin:hello", source.value.skill.name)
        PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        assertThrows(PluginCaptureException::class.java) { source.capture.checkActive() }
        assertNotEquals(source.capture.installRevision, manager.skills().single().capture.installRevision)
    }
    @Test fun enabledRecordRestoresOnlyIfWholeBundleStillMatches() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val restored = manager()
        restored.ensureLoaded()
        assertNotNull(restored.capture("sample.plugin"))
        val capture = requireNotNull(restored.capture("sample.plugin"))
        File(capture.installDir, "extra").writeText("tampered")
        val failed = manager()
        failed.ensureLoaded()
        assertNull(failed.capture("sample.plugin"))
        assertEquals(PluginStatus.INVALID, failed.plugins.value.single().status)
    }
    @Test fun disableIsPersistedAndCannotReactivateAfterRestart() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        manager.disable(capture.id)
        val restored = manager()
        restored.ensureLoaded()
        assertNull(restored.capture(capture.id))
        assertNull(restored.plugins.value.single().record.trustHash)
    }
    @Test fun corruptedRegistryPreservesPackagesDataAndUnknownDirectories() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        val data = File(manager.dataDirectory(installed.record.id), "user.txt").apply { writeText("keep") }
        val unknown = File(root(), "installed/user-owned/keep").apply { parentFile.mkdirs(); writeText("unknown") }
        val corrupt = "{broken registry"
        registry().writeText(corrupt)
        val recovered = manager()
        recovered.ensureLoaded()
        assertTrue(recovered.plugins.value.isEmpty())
        assertEquals(PluginErrorCode.REGISTRY_CORRUPT, recovered.snapshot.value.errors.single().code)
        assertTrue(installedPath(installed.record.installRevision).isDirectory)
        assertEquals("keep", data.readText())
        assertEquals("unknown", unknown.readText())
        assertEquals(corrupt, registry().readText())
        assertTrue(recovered.preview(PluginCoreFixtures.archive().inputStream()) is PluginPreviewResult.Failure)
    }
    @Test fun registryCommitFailureRollsBackNewRevisionAndPreservesOldPackage() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val preview = (manager.preview(PluginCoreFixtures.archive(extra = listOf("new" to byteArrayOf(2))).inputStream()) as PluginPreviewResult.Success).preview
        val registryFile = registry()
        assertTrue(registryFile.delete())
        assertTrue(registryFile.mkdir())
        File(registryFile, "block").writeText("block commit")
        assertTrue(manager.install(preview.token) is PluginInstallResult.Failure)
        assertEquals(old.record.installRevision, manager.plugins.value.single().record.installRevision)
        assertTrue(installedPath(old.record.installRevision).isDirectory)
        assertFalse(manager.plugins.value.single().isActive)
        assertEquals(1, installedPath(old.record.installRevision).parentFile.listFiles()!!.size)
    }
    @Test fun registryByteLimitFailureRollsBackReplacementPackageAndRetainsPersistedRecords() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val store = PluginRegistryStore(registry())
        val persisted = store.load().single()
        val hashes = LinkedHashMap(persisted.fileHashes)
        repeat(510) { hashes["assets/${"x".repeat(180)}$it.bin"] = "b".repeat(64) }
        val padding = (0 until 14).map {
            persisted.copy(id = "padding.plugin$it", enabled = false, trustHash = null,
                fileHashes = hashes, contentHash = PluginFiles.contentHash(hashes))
        }
        store.save(listOf(persisted) + padding)
        val previousRegistry = registry().readBytes()
        assertTrue(previousRegistry.size < PluginRegistryStore.MAX_FILE_BYTES)
        val reloaded = manager()
        reloaded.ensureLoaded()
        assertNotNull(reloaded.capture(persisted.id))
        val sharedDirectory = "assets/${"界".repeat(80)}/${"界".repeat(80)}"
        val extra = (0 until 510).map { "$sharedDirectory/$it.bin" to byteArrayOf(1) }
        extra.forEach { (path, _) ->
            assertTrue(PluginManifestParser.isSafeRelativePath(path))
            assertTrue(path.length <= PluginManifestParser.MAX_PATH_LENGTH)
            assertTrue(path.split('/').all { it.toByteArray(Charsets.UTF_8).size <= 255 })
        }
        val previewResult = reloaded.preview(PluginCoreFixtures.archive(extra = extra).inputStream())
        assertTrue("Package preview must succeed: $previewResult", previewResult is PluginPreviewResult.Success)
        val preview = (previewResult as PluginPreviewResult.Success).preview
        val stage = File(root(), "staging").listFiles()!!.single()
        extra.forEach { (path, _) ->
            assertTrue(Files.isRegularFile(File(stage, path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
        val replacement = persisted.copy(enabled = false, trustHash = null,
            archiveSha256 = preview.archiveSha256, manifestSha256 = preview.manifestSha256,
            installRevision = UUID.randomUUID().toString(), contentHash = preview.contentHash,
            fileHashes = PluginFiles.hashTree(stage))
        val encoder = Json { encodeDefaults = true }
        val proposedRegistry = "{\"schema\":1,\"plugins\":[" + (padding + replacement).joinToString(",") {
            encoder.encodeToString(PluginRecord.serializer(), it)
        } + "]}"
        assertTrue(proposedRegistry.toByteArray(Charsets.UTF_8).size > PluginRegistryStore.MAX_FILE_BYTES)
        val result = reloaded.install(preview.token) as PluginInstallResult.Failure
        assertEquals(PluginErrorCode.STORAGE_FAILED, result.errors.single().code)
        assertArrayEquals(previousRegistry, registry().readBytes())
        assertEquals(listOf(persisted) + padding, store.load())
        assertFalse(store.loadFailed)
        assertEquals(old.record.installRevision, reloaded.plugins.value.single { it.record.id == persisted.id }.record.installRevision)
        assertTrue(installedPath(old.record.installRevision).isDirectory)
        assertEquals(1, installedPath(old.record.installRevision).parentFile.listFiles()!!.size)
        assertTrue(File(root(), "staging").listFiles()!!.isEmpty())
        assertFalse(File(registry().parentFile, "registry.json.install").exists())
    }
    @Test fun uncommittedCrashJournalRemovesOnlyItsNewRevision() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        val abandoned = UUID.randomUUID().toString()
        val newDir = installedPath(abandoned).apply { mkdirs(); File(this, "new").writeText("new") }
        val unrelated = File(root(), "staging/user-owned/keep").apply { parentFile.mkdirs(); writeText("keep") }
        PluginRegistryStore(registry()).saveInstallTransaction(PluginInstallTransaction(old.record.id, abandoned, old.record.installRevision))
        val restored = manager()
        restored.ensureLoaded()
        assertFalse(newDir.exists())
        assertTrue(installedPath(old.record.installRevision).isDirectory)
        assertEquals("keep", unrelated.readText())
        assertEquals(old.record.installRevision, restored.plugins.value.single().record.installRevision)
    }
    @Test fun committedCrashJournalKeepsDisabledNewRevisionAndCleansOnlyOld() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        val oldRevision = UUID.randomUUID().toString()
        val oldDir = installedPath(oldRevision).apply { mkdirs(); File(this, "old").writeText("old") }
        PluginRegistryStore(registry()).saveInstallTransaction(PluginInstallTransaction(installed.record.id, installed.record.installRevision, oldRevision))
        val restored = manager()
        restored.ensureLoaded()
        assertFalse(oldDir.exists())
        assertTrue(installedPath(installed.record.installRevision).isDirectory)
        assertNull(restored.capture(installed.record.id))
        assertFalse(restored.plugins.value.single().record.enabled)
    }
    @Test fun malformedCrashJournalBlocksLoadingWithoutDeletingAnyPackage() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        File(registry().parentFile, "registry.json.install").writeText("{bad")
        val restored = manager()
        restored.ensureLoaded()
        assertTrue(restored.plugins.value.isEmpty())
        assertTrue(installedPath(installed.record.installRevision).isDirectory)
        assertEquals(PluginErrorCode.REGISTRY_CORRUPT, restored.snapshot.value.errors.single().code)
    }
    @Test fun uninstallKeepsOwnDataByDefaultAndNeverDeletesAnotherPluginsData() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val own = File(manager.dataDirectory(capture.id), "keep").apply { writeText("own") }
        val other = File(manager.dataDirectory("other.plugin"), "keep").apply { writeText("other") }
        assertEquals(PluginOperationResult.Success, manager.uninstall(capture.id))
        assertEquals("own", own.readText())
        assertEquals("other", other.readText())
        assertThrows(PluginCaptureException::class.java) { capture.checkActive() }
        PluginCoreFixtures.install(manager)
        assertEquals(PluginOperationResult.Success, manager.uninstall(capture.id, deleteData = true))
        assertFalse(own.exists())
        assertEquals("other", other.readText())
    }
    @Test fun dataDirectoryRejectsUnsafeIdAndSymlinkParents() = runBlocking<Unit> {
        val manager = manager()
        manager.ensureLoaded()
        assertThrows(IllegalArgumentException::class.java) { manager.dataDirectory("../escape") }
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root(), "data/sample.plugin").toPath(), outside.toPath())
        assertThrows(java.io.IOException::class.java) { manager.dataDirectory("sample.plugin") }
        assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun depsPreparationIsIndependentOfBundleAndDoesNotGrantTrustOrNeedReadyToCapture() = runBlocking<Unit> {
        val manager = manager()
        val manifest = PluginCoreFixtures.manifest(runtime = """{"type":"javascript","entry":"main.js","dependencies":{"tool":"npm","file":"package.json"}}""")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("main.js" to "// not run".toByteArray(), "package.json" to "{}".toByteArray())))
        val capture = PluginCoreFixtures.activate(manager)
        assertFalse(manager.areDependenciesPrepared(capture))
        val deps = manager.dependencyDirectory(capture)
        File(deps, "node_modules/dependency/index.js").apply { parentFile.mkdirs(); writeText("dependency") }
        assertFalse(deps.toPath().startsWith(capture.installDir.toPath()))
        assertEquals(PluginErrorCode.CONTENT_CHANGED, operationError(manager.markDependenciesPrepared(capture, "f".repeat(64))))
        assertEquals(PluginOperationResult.Success, manager.markDependenciesPrepared(capture))
        assertTrue(manager.areDependenciesPrepared(capture))
        assertTrue(manager.checkCurrent(capture))
        manager.disable(capture.id)
        assertTrue(manager.markDependenciesPrepared(capture) is PluginOperationResult.Failure)
        assertNull(manager.capture(capture.id))
    }
    @Test fun snapshotKeepsRevisionAndListAtomicAcrossLifecycle() = runBlocking<Unit> {
        val manager = manager()
        manager.ensureLoaded()
        val initial = manager.snapshot.value
        PluginCoreFixtures.install(manager)
        val installed = manager.snapshot.value
        assertTrue(installed.revision > initial.revision)
        assertEquals(installed.plugins, manager.plugins.value)
        assertEquals(installed.revision, manager.revision.value)
        PluginCoreFixtures.activate(manager)
        val enabled = manager.snapshot.value
        assertTrue(enabled.revision > installed.revision)
        assertTrue(enabled.plugins.single().isActive)
        assertFalse(installed.plugins.single().isActive)
    }
    @Test fun previewContentMutationBeforeInstallIsRejectedAndOldInstallPreserved() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        val preview = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        File(File(root(), "staging").listFiles()!!.single(), "skills/hello.md").writeText("changed")
        assertEquals(PluginErrorCode.CONTENT_CHANGED, (manager.install(preview.token) as PluginInstallResult.Failure).errors.single().code)
        assertEquals(old.record.installRevision, manager.plugins.value.single().record.installRevision)
        assertTrue(installedPath(old.record.installRevision).exists())
    }
    @Test fun androidSystemFilesAliasIsNormalizedBeforeAppendingPluginPaths() = runBlocking<Unit> {
        val trusted = temporary.newFolder("android-files")
        val alias = File(temporary.root, "android-system-alias")
        Files.createSymbolicLink(alias.toPath(), trusted.toPath())
        val context = mockk<Context>()
        val installer = mockk<ContainerInstaller>()
        every { context.filesDir } returns alias
        every { installer.aicodeDir } returns File(alias, "aicode")
        val manager = PluginManager(context, installer)
        val installed = PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        assertEquals(File(trusted.canonicalFile, "aicode/plugins/installed/${installed.record.id}/${installed.record.installRevision}"), capture.installDir)
        assertEquals(File(trusted.canonicalFile, "aicode/plugins/data/${installed.record.id}"), manager.dataDirectory(installed.record.id))
        assertTrue(File(trusted, "plugin-registry.json").isFile)
        capture.checkCurrent()
        val reloaded = PluginManager(context, installer)
        reloaded.ensureLoaded()
        assertNotNull(reloaded.capture(installed.record.id))
    }
    @Test fun androidFilesAliasDoesNotNormalizeUntrustedAicodeOrPluginRootSymlinks() = runBlocking<Unit> {
        for (child in listOf("aicode", "aicode/plugins")) {
            val trusted = temporary.newFolder()
            val alias = File(temporary.root, "alias-${UUID.randomUUID()}")
            Files.createSymbolicLink(alias.toPath(), trusted.toPath())
            val outside = temporary.newFolder()
            File(outside, "keep").writeText("untouched")
            val link = File(trusted, child)
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val context = mockk<Context>()
            val installer = mockk<ContainerInstaller>()
            every { context.filesDir } returns alias
            every { installer.aicodeDir } returns File(alias, "aicode")
            val manager = PluginManager(context, installer)
            assertTrue(manager.preview(PluginCoreFixtures.archive().inputStream()) is PluginPreviewResult.Failure)
            assertEquals(PluginErrorCode.REGISTRY_CORRUPT, manager.snapshot.value.errors.single().code)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(listOf("keep"), outside.list()!!.toList())
            assertEquals("untouched", File(outside, "keep").readText())
        }
    }
    @Test fun androidFilesAliasStillRejectsSymlinkPluginDataAndPackageResources() = runBlocking<Unit> {
        val trusted = temporary.newFolder("android-files")
        val alias = File(temporary.root, "android-system-alias")
        Files.createSymbolicLink(alias.toPath(), trusted.toPath())
        val context = mockk<Context>()
        val installer = mockk<ContainerInstaller>()
        every { context.filesDir } returns alias
        every { installer.aicodeDir } returns File(alias, "aicode")
        val manager = PluginManager(context, installer)
        val installed = PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val outside = temporary.newFolder("outside-owned-paths")
        File(outside, "keep").writeText("keep")
        Files.createSymbolicLink(File(trusted, "aicode/plugins/data/${installed.record.id}").toPath(), outside.toPath())
        assertThrows(java.io.IOException::class.java) { manager.dataDirectory(installed.record.id) }
        val resource = File(capture.installDir, "skills/hello.md")
        val replacement = File(outside, "replacement.md").apply { writeText(resource.readText()) }
        assertTrue(resource.delete())
        Files.createSymbolicLink(resource.toPath(), replacement.toPath())
        assertFalse(manager.checkCurrent(capture))
        assertEquals("keep", File(outside, "keep").readText())
        assertTrue(replacement.isFile)
    }
    @Test fun disableRevokesBeforeWaitingForAnotherIoOperation() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val archive = PluginCoreFixtures.archive()
        val blockedInput = object : java.io.ByteArrayInputStream(archive) {
            var waited = false
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!waited) { waited = true; entered.complete(Unit); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                return super.read(b, off, len)
            }
        }
        val blocked = async(Dispatchers.IO) { manager.preview(blockedInput) }
        entered.await()
        val disable = async(Dispatchers.Unconfined) { manager.disable(capture.id) }
        try {
            assertThrows(PluginCaptureException::class.java) { capture.checkActive() }
            assertFalse(disable.isCompleted)
        } finally { release.countDown() }
        blocked.await()
        assertEquals(PluginOperationResult.Success, disable.await())
    }
}
