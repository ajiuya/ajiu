package com.aicode.feature.plugin.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.plugin.runtime.PluginHostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Singleton
class PluginOperationGrantRepository internal constructor(
    private val file: File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir.canonicalFile, "plugin-operation-grants.enc"),
        KeystoreCipher::encryptString, KeystoreCipher::decryptString)
    private val mutex = Mutex()
    private var loaded = false
    private val stored = linkedMapOf<String, String>()

    private fun key(context: PluginInvocationContext, tool: String, arguments: JsonObject): String? {
        if (context.persistentIdentity.isBlank() || context.capture.cleanup || tool in setOf("Root", "submitPlan")) return null
        val target = when (tool) {
            "readFile", "writeFile", "list" -> JsonObject(arguments.filterKeys { it in setOf("method", "path", "from", "to", "overwrite") })
            else -> arguments
        }
        val source = context.capture
        val material = listOf(source.id, source.installRevision, source.contentHash, source.activationEpoch.toString(),
            context.persistentIdentity, tool, canonical(target).toString()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun canRemember(context: PluginInvocationContext, tool: String): Boolean =
        context.persistentIdentity.isNotBlank() && !context.capture.cleanup && tool !in setOf("Root", "submitPlan")

    suspend fun isAllowed(context: PluginInvocationContext, tool: String, arguments: JsonObject): Boolean = withContext(Dispatchers.IO) {
        context.checkIntegrity()
        val id = key(context, tool, arguments) ?: return@withContext false
        mutex.withLock { load(); stored[id] == context.capture.id }.also { context.checkIntegrity() }
    }

    suspend fun remember(context: PluginInvocationContext, tool: String, arguments: JsonObject) = withContext(Dispatchers.IO) {
        context.checkIntegrity()
        val id = key(context, tool, arguments) ?: throw PluginHostException("SYSTEM_PROTECTED", "This decision cannot be persisted")
        mutex.withLock {
            load()
            if (id !in stored && stored.size >= 1024) throw PluginHostException("GRANT_LIMIT", "Too many persistent operation grants")
            val next = LinkedHashMap(stored).apply { put(id, context.capture.id) }
            save(next); context.checkIntegrity()
            stored.clear(); stored.putAll(next)
        }
    }

    suspend fun revoke(pluginId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val next = stored.filterValues { it != pluginId }
            save(next); stored.clear(); stored.putAll(next)
        }
    }

    private fun load() {
        if (loaded) return
        if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val encrypted = PluginFiles.readRegularText(file.parentFile, file.name, 256 * 1024)
            if (!encrypted.startsWith("AICODEENC1:")) throw PluginHostException("STORAGE_FAILED", "Operation grants are not encrypted")
            val parsed = com.aicode.feature.plugin.domain.PluginJson.parse(decrypt(encrypted)) as? JsonObject
                ?: throw PluginHostException("STORAGE_FAILED", "Invalid operation grant store")
            if (parsed.size > 1024) throw PluginHostException("GRANT_LIMIT", "Operation grant store exceeds its limit")
            parsed.forEach { (key, value) ->
                val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!PluginRegistryStore.isHash(key) || id == null || !com.aicode.feature.plugin.domain.PluginManifestParser.isValidPluginId(id)) {
                    throw PluginHostException("STORAGE_FAILED", "Invalid operation grant identity")
                }
                stored[key] = id
            }
        }
        loaded = true
    }

    private fun save(values: Map<String, String>) {
        PluginFiles.ensureDirectory(file.parentFile)
        val bytes = encrypt(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()).toByteArray(Charsets.UTF_8)
        if (bytes.size > 256 * 1024) throw PluginHostException("GRANT_LIMIT", "Operation grant store exceeds its limit")
        val temporary = File(file.parentFile, ".plugin-grants-${UUID.randomUUID()}.tmp")
        try {
            Files.newOutputStream(temporary.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun canonical(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
