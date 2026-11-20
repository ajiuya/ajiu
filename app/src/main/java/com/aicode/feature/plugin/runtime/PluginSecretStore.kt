package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginJson
import com.aicode.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Singleton
class PluginSecretStore internal constructor(
    private val directory: File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir.canonicalFile, "plugin-secrets"), KeystoreCipher::encryptString, KeystoreCipher::decryptString)
    private val writes = Mutex()

    internal class PreparedSecrets internal constructor(internal val bytes: ByteArray)

    internal suspend fun exportForBackup(snapshot: PluginManager.InstallSnapshot): Map<String, String> = withContext(Dispatchers.IO) {
        writes.withLock {
            snapshot.checkCurrent()
            PluginFiles.checkDirectoryChain(directory.absoluteFile.parentFile.toPath().normalize())
            if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) PluginFiles.checkDirectoryChain(directory.absoluteFile.toPath().normalize())
            val file = File(directory, "${snapshot.record.id}.json")
            val encrypted = if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) emptyMap() else {
                checkSingleLink(file)
                decodeValues(PluginFiles.readRegular(directory, file.name, MAX_BYTES))
            }
            val plain = encrypted.mapValues { (_, value) ->
                decrypt(value).also { require(value.isEmpty() || it.isNotEmpty()) { "plugin secret decryption failed" } }
            }
            validateValues(plain)
            snapshot.checkCurrent()
            plain
        }
    }

    internal suspend fun prepareBackupRestore(values: Map<String, String>): PreparedSecrets = withContext(Dispatchers.IO) {
        validateValues(values)
        val bytes = encodeValues(values.mapValues { encrypt(it.value) })
        require(bytes.size <= MAX_BYTES) { "plugin encrypted secrets exceed limit" }
        PreparedSecrets(bytes)
    }

    internal suspend fun restoreFromBackup(snapshot: PluginManager.InstallSnapshot, prepared: PreparedSecrets): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            snapshot.checkCurrent()
            PluginFiles.ensureDirectory(directory)
            val file = File(directory, "${snapshot.record.id}.json")
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "unsafe plugin secret store" }
                checkSingleLink(file)
            }
            val temporary = File(directory, ".${snapshot.record.id}-${UUID.randomUUID()}.tmp")
            try {
                Files.newOutputStream(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(prepared.bytes) }
                snapshot.checkCurrent()
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                snapshot.checkCurrent()
            } finally { Files.deleteIfExists(temporary.toPath()) }
        }
    }

    internal suspend fun call(capture: PluginCapture, operation: String, key: String? = null, value: String? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            capture.checkCurrent()
            if (key != null && (key.length !in 1..128 || key.any(Char::isISOControl))) throw PluginHostException("PARAM_INVALID", "Invalid secret name")
            if (value != null && value.toByteArray(Charsets.UTF_8).size > 64 * 1024) throw PluginHostException("TOO_LARGE", "Secret value exceeds its limit")
            writes.withLock {
                PluginFiles.ensureDirectory(directory)
                val file = File(directory, "${capture.id}.json")
                val values = if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) mutableMapOf() else {
                    val parsed = com.aicode.feature.plugin.domain.PluginJson.parse(PluginFiles.readRegularText(directory, file.name, MAX_BYTES)) as? JsonObject
                        ?: throw PluginHostException("STORAGE_FAILED", "Invalid secret store")
                    parsed.mapValues { (_, entry) -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw PluginHostException("STORAGE_FAILED", "Invalid encrypted value") }.toMutableMap()
                }
                val result = when (operation) {
                    "get" -> mapOf("value" to (values[requireNotNull(key)]?.let(decrypt).orEmpty()))
                    "list" -> values.keys.associateWith { "" }
                    "set" -> { values[requireNotNull(key)] = encrypt(requireNotNull(value)); emptyMap() }
                    "delete" -> { values.remove(requireNotNull(key)); emptyMap() }
                    else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Unknown secret operation")
                }
                capture.checkCurrent()
                if (operation == "set" || operation == "delete") {
                    if (values.size > 256) throw PluginHostException("TOO_LARGE", "Secret key limit reached")
                    val bytes = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_BYTES) throw PluginHostException("TOO_LARGE", "Secret store exceeds its limit")
                    val temporary = File(directory, ".${capture.id}-${UUID.randomUUID()}.tmp")
                    try {
                        Files.newOutputStream(temporary.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
                        capture.checkCurrent()
                        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary.toPath()) }
                }
                capture.checkCurrent()
                result
            }
        }
    internal companion object {
        private const val MAX_BYTES = 512 * 1024
        const val MAX_BACKUP_BYTES = MAX_BYTES

        fun encodeBackup(values: Map<String, String>): ByteArray { validateValues(values); return encodeValues(values) }
        fun decodeBackup(bytes: ByteArray): Map<String, String> = decodeValues(bytes).also(::validateValues)

        private fun encodeValues(values: Map<String, String>): ByteArray =
            JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)

        private fun decodeValues(bytes: ByteArray): Map<String, String> {
            require(bytes.size <= MAX_BYTES) { "plugin secrets exceed limit" }
            val parsed = requireNotNull(PluginJson.parse(PluginFiles.utf8(bytes), maxDepth = 2) as? JsonObject) { "invalid plugin secrets" }
            require(parsed.size <= 256) { "plugin secret key limit" }
            return parsed.mapValues { (_, value) ->
                requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) { "invalid plugin secret value" }
            }
        }

        private fun validateValues(values: Map<String, String>) {
            require(values.size <= 256 && values.all { (key, value) ->
                key.length in 1..128 && key.none(Char::isISOControl) && value.toByteArray(Charsets.UTF_8).size <= 64 * 1024
            }) { "invalid plugin secrets" }
            require(encodeValues(values).size <= MAX_BYTES) { "plugin secrets exceed limit" }
        }

        private fun checkSingleLink(file: File) {
            val links = try { Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number }
            catch (_: UnsupportedOperationException) { return }
            catch (_: IllegalArgumentException) { return }
            require(links.toLong() == 1L) { "hardlinked plugin secret store" }
        }
    }
}
