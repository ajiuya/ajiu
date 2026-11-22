package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import com.aicode.feature.plugin.domain.PluginJson
import java.net.URI
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.SemVer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PluginRecord(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val installedAt: Long,
    val origin: PluginInstallOriginKind = PluginInstallOriginKind.LOCAL,
    val archiveSha256: String,
    val manifestSha256: String,
    val installRevision: String,
    val contentHash: String,
    val fileHashes: Map<String, String>,
    val trustHash: String? = null,
    val activationEpoch: Long = 0,
    val dependenciesReady: Boolean = false,
    val dependenciesHash: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val indexUrl: String? = null,
    val repository: String? = null,
    val officialUpstream: Boolean = false,
    val installHookDelivered: Boolean = false
) {
    val source: PluginInstallOrigin get() = PluginInstallOrigin(origin, sourceId, sourceName, indexUrl, repository, officialUpstream)
}

@Serializable
private data class RegistryFile(val schema: Int = 2, val plugins: List<PluginRecord>)
@Serializable
internal data class PluginInstallTransaction(val id: String, val revision: String, val previousRevision: String?)

internal class PluginRegistryStore(private val file: File) {
    var loadFailed: Boolean = false
        private set
    private val journal = File(file.parentFile, "${file.name}.install")

    fun load(): List<PluginRecord> {
        loadFailed = false
        return try {
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return emptyList()
            val data = JSON.decodeFromJsonElement(RegistryFile.serializer(), parseFile(file))
            require(data.schema in 1..2)
            if (data.schema == 1) require(data.plugins.all {
                it.origin == PluginInstallOriginKind.LOCAL && it.sourceId == null && it.sourceName == null &&
                    it.indexUrl == null && it.repository == null && !it.officialUpstream
            })
            validateRecords(data.plugins)
            if (data.schema == 1) save(data.plugins)
            data.plugins.map { it.copy(fileHashes = java.util.Collections.unmodifiableMap(LinkedHashMap(it.fileHashes))) }
        } catch (_: Exception) {
            loadFailed = true
            emptyList()
        }
    }

    fun save(records: List<PluginRecord>) {
        validateRecords(records)
        atomicWrite(file, JSON.encodeToString(RegistryFile.serializer(), RegistryFile(plugins = records)))
    }
    fun saveInstallTransaction(transaction: PluginInstallTransaction) {
        validateTransaction(transaction)
        atomicWrite(journal, JSON.encodeToString(PluginInstallTransaction.serializer(), transaction))
    }
    fun loadInstallTransaction(): PluginInstallTransaction? {
        if (!Files.exists(journal.toPath(), NOFOLLOW_LINKS)) return null
        return try {
            JSON.decodeFromJsonElement(PluginInstallTransaction.serializer(), parseFile(journal)).also(::validateTransaction)
        } catch (e: Exception) { throw IOException("invalid install journal", e) }
    }
    fun clearInstallTransaction() { Files.deleteIfExists(journal.toPath()) }

    private fun parseFile(target: File) = PluginJson.parse(
        PluginFiles.readRegularText(target.parentFile, target.name, MAX_FILE_BYTES), maxDepth = 16
    )
    private fun validateRecords(records: List<PluginRecord>) {
        require(records.size <= 256 && records.map { it.id }.distinct().size == records.size)
        records.forEach { r ->
            require(PluginManifestParser.isValidPluginId(r.id) && SemVer.parse(r.version) != null)
            require(isRevision(r.installRevision) && r.activationEpoch in 0 until Long.MAX_VALUE)
            validateOrigin(r.source)
            require(listOf(r.archiveSha256, r.manifestSha256, r.contentHash).all(::isHash))
            require(r.trustHash == null || r.trustHash == r.contentHash)
            require(!r.enabled || r.trustHash == r.contentHash)
            require(r.fileHashes.isNotEmpty() && r.fileHashes.size <= 512)
            require(r.fileHashes.all { (path, hash) -> PluginManifestParser.isSafeRelativePath(path) && isHash(hash) })
            require(r.fileHashes["plugin.json"] == r.manifestSha256)
            require(PluginFiles.contentHash(r.fileHashes) == r.contentHash)
            require(!r.dependenciesReady || r.dependenciesHash?.let(::isHash) == true)
        }
    }
    private fun validateTransaction(t: PluginInstallTransaction) {
        require(PluginManifestParser.isValidPluginId(t.id) && isRevision(t.revision))
        require(t.previousRevision == null || isRevision(t.previousRevision))
        require(t.revision != t.previousRevision)
    }
    private fun atomicWrite(target: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("registry byte limit")
        val parent = target.parentFile ?: throw IOException("registry parent missing")
        PluginFiles.ensureDirectory(parent)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("registry not regular")
        }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.createFile(temporary.toPath())
            temporary.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        fun validateOrigin(source: PluginInstallOrigin) {
            fun bounded(value: String?, maximum: Int) = value == null ||
                (value.isNotBlank() && value.length <= maximum && value.none { it.isISOControl() })
            fun https(value: String?): Boolean = value == null || runCatching {
                val uri = URI(value)
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.rawFragment == null && uri.rawQuery == null
            }.getOrDefault(false)
            require(bounded(source.sourceId, 128) && bounded(source.sourceName, 200))
            require(bounded(source.indexUrl, 2048) && bounded(source.repository, 300))
            require(https(source.indexUrl) && https(source.repository))
            when (source.kind) {
                PluginInstallOriginKind.LOCAL -> require(source.sourceId == null && source.sourceName == null &&
                    source.indexUrl == null && source.repository == null && !source.officialUpstream)
                PluginInstallOriginKind.INDEX -> require(source.sourceId != null && source.indexUrl != null && source.repository != null)
                PluginInstallOriginKind.DEVELOPMENT -> require(source.indexUrl == null && !source.officialUpstream)
            }
        }
        fun isRevision(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun isHash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
