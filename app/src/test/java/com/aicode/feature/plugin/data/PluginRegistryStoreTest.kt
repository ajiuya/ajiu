package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginCoreFixtures
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRegistryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "host/registry.json")
    private fun record(): PluginRecord {
        val hashes = mapOf("plugin.json" to PluginFiles.sha256(PluginCoreFixtures.manifest().toByteArray()))
        return PluginRecord("sample.plugin", "1.0.0", false, 10, archiveSha256 = "a".repeat(64),
            manifestSha256 = hashes.getValue("plugin.json"), installRevision = UUID.randomUUID().toString(),
            contentHash = PluginFiles.contentHash(hashes), fileHashes = hashes)
    }
    private fun largeRecords(): List<PluginRecord> {
        val base = record()
        val hashes = LinkedHashMap(base.fileHashes)
        repeat(511) { index -> hashes["assets/${"界".repeat(160)}$index.txt"] = "b".repeat(64) }
        val contentHash = PluginFiles.contentHash(hashes)
        return (0 until 12).map { base.copy(id = "sample.plugin$it", fileHashes = hashes, contentHash = contentHash) }
    }
    @Test fun missingRegistryIsEmptyWithoutFailure() {
        val store = PluginRegistryStore(file())
        assertTrue(store.load().isEmpty())
        assertFalse(store.loadFailed)
    }
    @Test fun atomicRecordRoundTripKeepsTrustAndInstallIdentity() {
        val store = PluginRegistryStore(file())
        val record = record().let { it.copy(enabled = true, trustHash = it.contentHash, activationEpoch = 2) }
        store.save(listOf(record))
        assertEquals(listOf(record), store.load())
        assertFalse(store.loadFailed)
        assertEquals(listOf("registry.json"), file().parentFile.list()!!.toList())
    }
    @Test fun oversizedUtf8RegistryIsRejectedBeforeCreatingStorage() {
        val records = largeRecords()
        val json = Json { encodeDefaults = true }
        val encoded = "{\"schema\":1,\"plugins\":[" + records.joinToString(",") { json.encodeToString(PluginRecord.serializer(), it) } + "]}"
        assertTrue(encoded.length < PluginRegistryStore.MAX_FILE_BYTES)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size > PluginRegistryStore.MAX_FILE_BYTES)
        assertThrows(java.io.IOException::class.java) { PluginRegistryStore(file()).save(records) }
        assertFalse(file().parentFile.exists())
    }
    @Test fun oversizedReplacementPreservesPreviousBytesRecordsAndJournal() {
        val store = PluginRegistryStore(file())
        val old = record()
        store.save(listOf(old))
        val journal = PluginInstallTransaction(old.id, UUID.randomUUID().toString(), old.installRevision)
        store.saveInstallTransaction(journal)
        val previousBytes = file().readBytes()
        val journalFile = File(file().parentFile, "registry.json.install")
        val previousJournal = journalFile.readBytes()
        assertThrows(java.io.IOException::class.java) { store.save(largeRecords()) }
        assertArrayEquals(previousBytes, file().readBytes())
        assertArrayEquals(previousJournal, journalFile.readBytes())
        assertEquals(listOf(old), store.load())
        assertFalse(store.loadFailed)
        assertEquals(journal, store.loadInstallTransaction())
        assertEquals(setOf("registry.json", "registry.json.install"), file().parentFile.list()!!.toSet())
    }
    @Test fun exactReadByteLimitLoadsAndOneAdditionalByteIsRejectedWithoutMutation() {
        val store = PluginRegistryStore(file())
        val old = record()
        store.save(listOf(old))
        val encoded = file().readText()
        file().writeText(encoded + " ".repeat(PluginRegistryStore.MAX_FILE_BYTES - encoded.toByteArray(Charsets.UTF_8).size))
        assertEquals(PluginRegistryStore.MAX_FILE_BYTES.toLong(), file().length())
        assertEquals(listOf(old), store.load())
        assertFalse(store.loadFailed)
        file().appendText(" ")
        val oversized = file().readBytes()
        assertTrue(store.load().isEmpty())
        assertTrue(store.loadFailed)
        assertArrayEquals(oversized, file().readBytes())
    }
    @Test fun malformedAndUnknownSchemaRegistryIsPreservedVerbatim() {
        val store = PluginRegistryStore(file())
        file().parentFile.mkdirs()
        for (text in listOf("{broken", """{"schema":99,"plugins":[]}""", """{"schema":1,"schema":1,"plugins":[]}""")) {
            file().writeText(text)
            assertTrue(store.load().isEmpty())
            assertTrue(store.loadFailed)
            assertEquals(text, file().readText())
        }
    }
    @Test fun invalidIdsDuplicateRecordsOrForgedContentHashAreRejected() {
        val store = PluginRegistryStore(file())
        val record = record()
        for (records in listOf(listOf(record.copy(id = "../escape")), listOf(record, record),
            listOf(record.copy(contentHash = "b".repeat(64))), listOf(record.copy(enabled = true)),
            listOf(record.copy(installRevision = "not-uuid")))) {
            assertThrows(IllegalArgumentException::class.java) { store.save(records) }
        }
        assertFalse(file().exists())
    }
    @Test fun unsafeSerializedRecordDoesNotLoadAsEmptySuccess() {
        val store = PluginRegistryStore(file())
        val invalid = record().copy(id = "../escape")
        val json = Json.encodeToString(PluginRecord.serializer(), invalid)
        file().parentFile.mkdirs()
        file().writeText("""{"schema":1,"plugins":[$json]}""")
        assertTrue(store.load().isEmpty())
        assertTrue(store.loadFailed)
        assertTrue(file().readText().contains("../escape"))
    }
    @Test fun registrySymlinkIsNotFollowedOrReplaced() {
        file().parentFile.mkdirs()
        val outside = temporary.newFile("outside.json").apply { writeText("keep") }
        Files.createSymbolicLink(file().toPath(), outside.toPath())
        val store = PluginRegistryStore(file())
        assertTrue(store.load().isEmpty())
        assertTrue(store.loadFailed)
        assertThrows(java.io.IOException::class.java) { store.save(listOf(record())) }
        assertTrue(Files.isSymbolicLink(file().toPath()))
        assertEquals("keep", outside.readText())
    }
    @Test fun localSchemaOneMigratesAtomicallyWithoutChangingTrustIdentityOrHashes() = runBlocking<Unit> {
        val old = record().let { it.copy(enabled = true, trustHash = it.contentHash, activationEpoch = 8,
            dependenciesReady = true, dependenciesHash = "d".repeat(64)) }
        val objectValue = Json { encodeDefaults = true }.encodeToJsonElement(PluginRecord.serializer(), old) as JsonObject
        val legacy = JsonObject(objectValue.filterKeys { it !in setOf("sourceId", "sourceName", "indexUrl", "repository", "officialUpstream", "installHookDelivered") })
        file().parentFile.mkdirs()
        file().writeText("""{"schema":1,"plugins":[$legacy]}""")
        val store = PluginRegistryStore(file())
        assertEquals(listOf(old), store.load())
        assertFalse(store.loadFailed)
        assertTrue(file().readText().contains("\"schema\":2"))
        assertEquals(listOf(old), store.load())
        assertEquals(old.fileHashes, store.load().single().fileHashes)
        assertEquals(listOf("registry.json"), file().parentFile.list()!!.toList())
    }
    @Test fun upstreamSchemaFiveIsNotTreatedAsLocalMigrationOrOverwritten() = runBlocking<Unit> {
        file().parentFile.mkdirs()
        val foreign = """{"schema":5,"plugins":[{"id":"sample.plugin","version":"1.0.0","enabled":true,"installedAt":10,"origin":"index","archiveSha256":"${"a".repeat(64)}","manifestSha256":"${"b".repeat(64)}"}]}"""
        file().writeText(foreign)
        val store = PluginRegistryStore(file())
        assertTrue(store.load().isEmpty())
        assertTrue(store.loadFailed)
        assertEquals(foreign, file().readText())
    }
    @Test fun sourceKindsAreStrictEnumsAndIndexBindingRoundTrips() = runBlocking<Unit> {
        val indexed = record().copy(origin = PluginInstallOriginKind.INDEX, sourceId = "community", sourceName = "Community",
            indexUrl = "https://example.com/index.json", repository = "https://example.com/sample/plugin", officialUpstream = true)
        val store = PluginRegistryStore(file())
        store.save(listOf(indexed))
        assertEquals(listOf(indexed), store.load())
        assertTrue(file().readText().contains("\"origin\":\"index\""))
        val invalid = file().readText().replace("\"origin\":\"index\"", "\"origin\":\"signed-official\"")
        file().writeText(invalid)
        assertTrue(store.load().isEmpty())
        assertTrue(store.loadFailed)
        assertEquals(invalid, file().readText())
    }
    @Test fun localAndDevelopmentSourcesCannotClaimAnOfficialIndex() = runBlocking<Unit> {
        val store = PluginRegistryStore(file())
        val old = record()
        for (bad in listOf(old.copy(officialUpstream = true), old.copy(indexUrl = "https://example.com/index.json"),
            old.copy(origin = PluginInstallOriginKind.INDEX),
            old.copy(origin = PluginInstallOriginKind.DEVELOPMENT, officialUpstream = true))) {
            assertThrows(IllegalArgumentException::class.java) { store.save(listOf(bad)) }
        }
        store.save(listOf(old.copy(origin = PluginInstallOriginKind.DEVELOPMENT, repository = "https://example.com/sample/plugin")))
        assertEquals(PluginInstallOriginKind.DEVELOPMENT, store.load().single().origin)
    }
    @Test fun journalRoundTripAndInvalidRevisionCannotSelectArbitraryDirectory() {
        val store = PluginRegistryStore(file())
        val transaction = PluginInstallTransaction("sample.plugin", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        store.saveInstallTransaction(transaction)
        assertEquals(transaction, store.loadInstallTransaction())
        store.clearInstallTransaction()
        assertNull(store.loadInstallTransaction())
        assertThrows(IllegalArgumentException::class.java) { store.saveInstallTransaction(transaction.copy(revision = "../user")) }
    }
}
