package com.aicode.feature.plugin.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginCapabilityAccess
import com.aicode.feature.plugin.domain.PluginCapabilityGrant
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginNativeCapabilityChallenge
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.plugin.domain.capabilityPurpose
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class PluginCapabilityRepository internal constructor(
    private val file: File,
    private val manager: PluginManager,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis
) : PluginCapabilityAccess {
    @Inject constructor(@ApplicationContext context: Context, manager: PluginManager) : this(
        File(context.filesDir.canonicalFile, "plugin-capabilities.enc"), manager,
        KeystoreCipher::encryptBytes, KeystoreCipher::decryptBytes
    )

    private val mutex = Mutex()
    private val lock = Any()
    private val mutableGrants = MutableStateFlow<Map<String, PluginCapabilityGrant>>(emptyMap())
    override val grants: StateFlow<Map<String, PluginCapabilityGrant>> = mutableGrants.asStateFlow()
    private val challenges = LinkedHashMap<String, Pending>()
    private var loaded = false
    private var blocked = false
    private data class Pending(val challenge: PluginNativeCapabilityChallenge, val capture: PluginCapture)
    @Serializable private data class GrantFile(val schema: Int = 1, val grants: List<PluginCapabilityGrant>)

    init {
        manager.registerRevocationListener { id ->
            synchronized(lock) {
                challenges.entries.removeAll { it.value.capture.id == id }
                publish(mutableGrants.value.filterValues { it.pluginId != id })
            }
        }
    }

    override suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        manager.ensureLoaded()
        mutex.withLock { loadLocked() }
    }

    override suspend fun prepareGrant(capture: PluginCapture, capability: PluginCapability): PluginNativeCapabilityChallenge? = withContext(Dispatchers.IO) {
        ensureLoaded()
        if (capture.cleanup || blocked) return@withContext null
        val purpose = capture.manifest.capabilityPurpose(capability) ?: return@withContext null
        if (!runCatching { capture.checkCurrent() }.isSuccess) return@withContext null
        synchronized(lock) {
            challenges.entries.removeAll { clock() >= it.value.challenge.expiresAt || it.value.capture.id == capture.id }
            if (challenges.size >= 32) return@synchronized null
            PluginNativeCapabilityChallenge(UUID.randomUUID().toString(), capture.id, capture.installRevision,
                capture.contentHash, capture.activationEpoch, capability, purpose, capture.record.source, clock() + CHALLENGE_TTL).also {
                challenges[it.nativeToken] = Pending(it, capture)
            }
        }
    }

    override suspend fun confirmGrant(nativeToken: String): PluginOperationResult = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            if (blocked) return@withLock failure(PluginErrorCode.REGISTRY_CORRUPT)
            val pending = synchronized(lock) { challenges.remove(nativeToken) }
                ?: return@withLock failure(PluginErrorCode.TRUST_REQUIRED)
            val challenge = pending.challenge
            val capture = pending.capture
            if (clock() >= challenge.expiresAt) return@withLock failure(PluginErrorCode.TRUST_EXPIRED)
            if (capture.cleanup || capture.manifest.capabilityPurpose(challenge.capability) != challenge.purpose ||
                !runCatching { capture.checkCurrent() }.isSuccess) return@withLock failure(PluginErrorCode.STALE_CAPTURE)
            val grant = PluginCapabilityGrant(UUID.randomUUID().toString(), capture.id, capture.installRevision,
                capture.contentHash, capture.activationEpoch, challenge.capability, challenge.purpose, challenge.source, clock())
            synchronized(lock) { publish(mutableGrants.value + (grant.key to grant)) }
            try {
                save()
                capture.checkCurrent()
                if (!checkGranted(capture, challenge.capability)) return@withLock failure(PluginErrorCode.STALE_CAPTURE)
                PluginOperationResult.Success
            } catch (e: Exception) {
                synchronized(lock) { publish(mutableGrants.value.filterValues { it.token != grant.token }) }
                if (e is CancellationException) throw e
                failure(PluginErrorCode.STORAGE_FAILED)
            }
        }
    }

    override suspend fun revoke(pluginId: String, capability: PluginCapability?): PluginOperationResult {
        if (!com.aicode.feature.plugin.domain.PluginManifestParser.isValidPluginId(pluginId)) return failure(PluginErrorCode.FIELD_INVALID)
        synchronized(lock) {
            challenges.entries.removeAll { it.value.capture.id == pluginId && (capability == null || it.value.challenge.capability == capability) }
            publish(mutableGrants.value.filterValues { it.pluginId != pluginId || (capability != null && it.capability != capability) })
        }
        return withContext(Dispatchers.IO) {
            ensureLoaded()
            mutex.withLock {
                if (blocked) return@withLock failure(PluginErrorCode.REGISTRY_CORRUPT)
                synchronized(lock) { publish(mutableGrants.value.filterValues { it.pluginId != pluginId || (capability != null && it.capability != capability) }) }
                try { save(); PluginOperationResult.Success }
                catch (e: Exception) { if (e is CancellationException) throw e; failure(PluginErrorCode.STORAGE_FAILED) }
            }
        }
    }

    override fun checkGranted(capture: PluginCapture, capability: PluginCapability): Boolean {
        if (capture.cleanup || !runCatching { capture.checkActive() }.isSuccess) return false
        val grant = mutableGrants.value["${capture.id}:${capability.name}"] ?: return false
        return grant.installRevision == capture.installRevision && grant.contentHash == capture.contentHash &&
            grant.activationEpoch == capture.activationEpoch && grant.source == capture.record.source &&
            grant.purpose == capture.manifest.capabilityPurpose(capability)
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        try {
            val encrypted = PluginFiles.readRegular(file.parentFile, file.name, MAX_FILE_BYTES)
            val bytes = decrypt(encrypted)
            require(bytes.size <= MAX_FILE_BYTES)
            val root = com.aicode.feature.plugin.domain.PluginJson.parse(PluginFiles.utf8(bytes), maxDepth = 16)
            val parsed = JSON.decodeFromJsonElement(GrantFile.serializer(), root)
            require(parsed.schema == 1 && parsed.grants.size <= 1536 && parsed.grants.map { it.key }.distinct().size == parsed.grants.size)
            val valid = parsed.grants.filter { grant ->
                require(PluginRegistryStore.isRevision(grant.token) && PluginRegistryStore.isRevision(grant.installRevision))
                require(PluginRegistryStore.isHash(grant.contentHash) && grant.activationEpoch in 0 until Long.MAX_VALUE)
                require(grant.purpose.isNotBlank() && grant.purpose.length <= 200 && grant.purpose.none { it.isISOControl() })
                PluginRegistryStore.validateOrigin(grant.source)
                val capture = manager.capture(grant.pluginId)
                capture != null && capture.installRevision == grant.installRevision && capture.contentHash == grant.contentHash &&
                    capture.activationEpoch == grant.activationEpoch && capture.record.source == grant.source &&
                    capture.manifest.capabilityPurpose(grant.capability) == grant.purpose
            }
            synchronized(lock) { publish(valid.associateBy { it.key }) }
        } catch (_: Exception) {
            blocked = true
            synchronized(lock) { publish(emptyMap()) }
        }
    }
    private fun publish(values: Map<String, PluginCapabilityGrant>) {
        mutableGrants.value = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
    }
    private fun save() {
        val plain = JSON.encodeToString(GrantFile.serializer(), GrantFile(grants = mutableGrants.value.values.toList())).toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_FILE_BYTES) throw IOException("capability byte limit")
        val bytes = encrypt(plain)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("capability byte limit")
        PluginFiles.ensureDirectory(file.parentFile)
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("capability not regular")
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.createFile(temporary.toPath())
            temporary.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    private fun failure(code: PluginErrorCode) = PluginOperationResult.Failure(listOf(PluginError(code)))
    companion object {
        const val CHALLENGE_TTL = 2 * 60 * 1000L
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    }
}
