package com.aicode.feature.backup.data.pluginbackup

import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginSecretStore
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSecretBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager(name: String = "source") = PluginManager(File(temporary.root, "$name/plugins"), File(temporary.root, "$name/host/registry.json"))
    private fun directory(name: String = "source") = File(temporary.root, "$name/host/secrets")
    private fun encode(prefix: String, value: String) = prefix + Base64.getEncoder().encodeToString(value.toByteArray())
    private fun decode(prefix: String, value: String) = String(Base64.getDecoder().decode(value.removePrefix(prefix)))
    private fun store(name: String = "source", prefix: String = "fixture-old:") =
        PluginSecretStore(directory(name), { encode(prefix, it) }, { decode(prefix, it) })

    @Test fun hostExportsDecryptedValuesForDisabledInstalledIdentityWithoutEnablingIt() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val store = store()
        store.call(capture, "set", "api", "fixture-secret")
        manager.disable(capture.id)
        val snapshot = manager.stableInstallSnapshots().single()
        val before = manager.snapshot.value
        assertEquals(mapOf("api" to "fixture-secret"), store.exportForBackup(snapshot))
        assertSame(before, manager.snapshot.value)
        assertNull(manager.capture(capture.id))
        assertFalse(File(directory(), "sample.plugin.json").readText().contains("fixture-secret"))
    }

    @Test fun missingStoreExportsEmptyMapWithoutCreatingSecretDirectory() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        assertFalse(directory().exists())
        assertEquals(emptyMap<String, String>(), store().exportForBackup(manager.stableInstallSnapshots().single()))
        assertFalse(directory().exists())
    }

    @Test fun restoreReencryptsEachValueUsingNewHostKeyAndDoesNotCopyCiphertext() = runBlocking<Unit> {
        val source = manager()
        PluginCoreFixtures.install(source)
        val sourceCapture = PluginCoreFixtures.activate(source)
        val oldStore = store()
        oldStore.call(sourceCapture, "set", "api", "fixture-portable")
        val portable = oldStore.exportForBackup(source.stableInstallSnapshots().single())
        val target = manager("target")
        PluginCoreFixtures.install(target)
        val newStore = store("target", "fixture-new:")
        val prepared = newStore.prepareBackupRestore(portable)
        val old = target.plugins.value.single().record
        target.withBackupRestoreFence(old.id, old.installRevision) { fence ->
            newStore.restoreFromBackup(fence.snapshot(), prepared)
        }
        val oldCipher = File(directory(), "sample.plugin.json").readText()
        val newCipher = File(directory("target"), "sample.plugin.json").readText()
        assertNotEquals(oldCipher, newCipher)
        assertTrue(newCipher.contains("fixture-new:"))
        assertFalse(newCipher.contains("fixture-old:"))
        assertFalse(newCipher.contains("fixture-portable"))
        assertEquals(portable, newStore.exportForBackup(target.stableInstallSnapshots().single()))
        assertNull(target.capture("sample.plugin"))
    }

    @Test fun restoreReplacesOnlyThisPluginsKeysAndLeavesOtherPluginSecretFileUntouched() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = "other.plugin")))
        val own = PluginCoreFixtures.activate(manager)
        val other = PluginCoreFixtures.activate(manager, "other.plugin")
        val store = store()
        store.call(own, "set", "obsolete", "fixture-obsolete")
        store.call(other, "set", "other", "fixture-other")
        val otherBytes = File(directory(), "other.plugin.json").readBytes()
        val prepared = store.prepareBackupRestore(mapOf("current" to "fixture-current"))
        manager.withBackupRestoreFence(own.id, own.installRevision) { fence -> store.restoreFromBackup(fence.snapshot(), prepared) }
        assertEquals(mapOf("current" to "fixture-current"), store.exportForBackup(manager.stableInstallSnapshots().first { it.record.id == own.id }))
        assertArrayEquals(otherBytes, File(directory(), "other.plugin.json").readBytes())
        other.checkCurrent()
    }

    @Test fun staleSnapshotCannotDecryptOrCommitSecrets() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val snapshot = manager.stableInstallSnapshots().single()
        val store = store()
        val prepared = store.prepareBackupRestore(mapOf("api" to "fixture-value"))
        PluginCoreFixtures.install(manager)
        assertTrue(runCatching { store.exportForBackup(snapshot) }.isFailure)
        assertTrue(runCatching { store.restoreFromBackup(snapshot, prepared) }.isFailure)
        assertFalse(directory().exists())
    }

    @Test fun invalidOrUnavailableDecryptionIsNotExportedAsSuccessfulEmptySecret() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        store().call(capture, "set", "api", "fixture-secret")
        val broken = PluginSecretStore(directory(), { it }, { "" })
        assertTrue(runCatching { broken.exportForBackup(manager.stableInstallSnapshots().single()) }.isFailure)
    }

    @Test fun malformedSecretJsonDuplicateKeysNonStringsAndDepthAreRejected() {
        for (json in listOf("[]", "{\"api\":1}", "{\"api\":null}", "{\"api\":true}",
            "{\"api\":\"first\",\"api\":\"second\"}", "{\"api\":{\"nested\":\"fixture\"}}", "{} trailing")) {
            assertThrows(IllegalArgumentException::class.java) { PluginSecretStore.decodeBackup(json.toByteArray()) }
        }
    }

    @Test fun secretMigrationBoundsKeysValuesAggregateAndKeyCount() = runBlocking<Unit> {
        val store = store()
        for (values in listOf(mapOf("" to "fixture"), mapOf("a".repeat(129) to "fixture"), mapOf("bad\nkey" to "fixture"),
            mapOf("api" to "a".repeat(64 * 1024 + 1)), (1..257).associate { "key$it" to "fixture" },
            (1..9).associate { "key$it" to "a".repeat(64 * 1024) })) {
            assertTrue(runCatching { store.prepareBackupRestore(values) }.isFailure)
            assertThrows(IllegalArgumentException::class.java) { PluginSecretStore.encodeBackup(values) }
        }
        assertThrows(IllegalArgumentException::class.java) { PluginSecretStore.decodeBackup(ByteArray(PluginSecretStore.MAX_BACKUP_BYTES + 1)) }
        val allowed = mapOf("a".repeat(128) to "a".repeat(64 * 1024))
        assertEquals(allowed, PluginSecretStore.decodeBackup(PluginSecretStore.encodeBackup(allowed)))
        assertFalse(directory().exists())
    }

    @Test fun encryptionFailureOrEncryptedAggregateLimitFailsBeforeWritingAnySecrets() = runBlocking<Unit> {
        val failure = PluginSecretStore(directory(), { throw java.io.IOException("fixture key unavailable") }, { it })
        assertTrue(runCatching { failure.prepareBackupRestore(mapOf("api" to "fixture")) }.isFailure)
        val tooLarge = PluginSecretStore(directory(), { "x".repeat(PluginSecretStore.MAX_BACKUP_BYTES) }, { it })
        assertTrue(runCatching { tooLarge.prepareBackupRestore(mapOf("api" to "fixture")) }.isFailure)
        assertFalse(directory().exists())
    }

    @Test fun linkedSecretStoreCannotBeExportedOrOverwritten() = runBlocking<Unit> {
        for (symbolic in listOf(true, false)) {
            val name = if (symbolic) "symbolic" else "hardlink"
            val manager = manager(name)
            PluginCoreFixtures.install(manager)
            val capture = PluginCoreFixtures.activate(manager)
            val store = store(name)
            store.call(capture, "set", "api", "fixture-secret")
            val file = File(directory(name), "sample.plugin.json")
            val outside = temporary.newFile().apply { writeBytes(file.readBytes()) }
            val oldBytes = outside.readBytes()
            file.delete()
            if (symbolic) Files.createSymbolicLink(file.toPath(), outside.toPath()) else Files.createLink(file.toPath(), outside.toPath())
            assertTrue(runCatching { store.exportForBackup(manager.stableInstallSnapshots().single()) }.isFailure)
            val prepared = store.prepareBackupRestore(mapOf("api" to "fixture-replacement"))
            manager.withBackupRestoreFence(capture.id, capture.installRevision) { fence ->
                assertTrue(runCatching { store.restoreFromBackup(fence.snapshot(), prepared) }.isFailure)
            }
            assertArrayEquals(oldBytes, outside.readBytes())
        }
    }

    @Test(timeout = 15_000) fun backupReadSharesSecretWriteMutexAndReadsCommittedValueOnly() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val store = PluginSecretStore(directory(), {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            encode("fixture:", it)
        }, { decode("fixture:", it) })
        val write = async(Dispatchers.IO) { store.call(capture, "set", "api", "fixture-committed") }
        entered.await()
        val snapshot = manager.stableInstallSnapshots().single()
        val readStarted = CompletableDeferred<Unit>()
        val read = async(Dispatchers.IO) { readStarted.complete(Unit); store.exportForBackup(snapshot) }
        readStarted.await()
        try { assertFalse(read.isCompleted) }
        finally { release.countDown() }
        write.await()
        assertEquals(mapOf("api" to "fixture-committed"), read.await())
    }

    @Test fun identityChangeDuringDecryptionIsRejectedAfterMutexProtectedRead() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        store().call(capture, "set", "api", "fixture-secret")
        val snapshot = manager.stableInstallSnapshots().single()
        val changing = PluginSecretStore(directory(), { it }, {
            runBlocking { manager.disable(capture.id) }
            decode("fixture-old:", it)
        })
        assertTrue(runCatching { changing.exportForBackup(snapshot) }.isFailure)
        assertNull(manager.capture(capture.id))
    }

    @Test fun storedEmptySecretRemainsAnExplicitEmptyValue() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val store = PluginSecretStore(directory(), { it }, { it })
        store.call(capture, "set", "empty", "")
        assertEquals(mapOf("empty" to ""), store.exportForBackup(manager.stableInstallSnapshots().single()))
    }
}
