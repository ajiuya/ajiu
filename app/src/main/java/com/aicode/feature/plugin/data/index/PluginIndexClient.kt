package com.aicode.feature.plugin.data.index

import android.content.Context
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response


data class PluginIndexSnapshot(val index: PluginIndexDocument, val stale: Boolean, val fetchedAt: Long)
data class PluginReleaseAsset(val version: String, val name: String, val url: String, val size: Long)
data class DownloadedPluginPackage(val file: File, val asset: PluginReleaseAsset, val archiveSha256: String, val downloadUrl: String) : AutoCloseable {
    override fun close() { Files.deleteIfExists(file.toPath()) }
}

@Singleton
class PluginIndexClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient,
    private val storeSettings: PluginStoreSettingsRepository
) {
    private val http = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).followSslRedirects(false).cache(null).build()
    private val cacheRoot = File(context.filesDir, "plugin-index-cache")
    private val hostVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let(SemVer::parse) }.getOrNull()

    suspend fun fetchIndex(source: PluginIndexSource, forceRefresh: Boolean = false): PluginIndexSnapshot = withContext(Dispatchers.IO) {
        val cached = File(cacheRoot, "${PluginIndexSourceRepository.sourceId(source.indexUrl)}.json")
        val bytes = try { transfer(accelerated(source.indexUrl), PluginIndexParser.MAX_INDEX_BYTES.toLong(), null, null).bytes!! }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IOException) {
            if (forceRefresh || !cached.isFile || cached.length() > PluginIndexParser.MAX_INDEX_BYTES) throw failure
            val fallback = runCatching { PluginIndexParser.parse(cached.readBytes()) }.getOrNull() ?: throw failure
            return@withContext PluginIndexSnapshot(fallback, true, cached.lastModified())
        }
        val index = PluginIndexParser.parse(bytes)
        cacheRoot.mkdirs()
        val temporary = File(cacheRoot, ".${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), cached.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
        PluginIndexSnapshot(index, false, System.currentTimeMillis())
    }

    fun isCompatible(plugin: PluginIndexPlugin): Boolean {
        val minimum = plugin.minAppVersion?.let { SemVer.parse(it) ?: return false } ?: return true
        val current = hostVersion ?: return true
        return (if (minimum.preRelease.isEmpty()) SemVer(current.major, current.minor, current.patch) else current) >= minimum
    }

    suspend fun resolveRelease(plugin: PluginIndexPlugin): PluginReleaseAsset {
        val (owner, repository) = PluginRepositoryAddress.parse(plugin.repository) ?: throw NoMatchingReleaseException("invalid GitHub repository")
        val address = "$GITHUB_API/repos/$owner/$repository/releases"
        val releases = if (plugin.version == null) listOf(decodeRelease("$address/latest"))
        else {
            val bytes = transfer("$address?per_page=100", MAX_API_BYTES, null, null).bytes!!
            PluginIndexJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubRelease.serializer()), bytes.toString(Charsets.UTF_8))
        }
        val candidates = releases.filterNot { it.draft }.flatMap { it.assets }
        val asset = if (plugin.version != null) candidates.firstOrNull { assetVersion(plugin.id, it.name) == plugin.version }
        else candidates.mapNotNull { candidate -> assetVersion(plugin.id, candidate.name)?.let { candidate to it } }
            .maxByOrNull { SemVer.parse(it.second)!! }?.first
        if (asset == null || !PluginIndexAddress.isHttps(asset.browserDownloadUrl)) throw NoMatchingReleaseException("matching HTTPS release asset not found")
        return PluginReleaseAsset(assetVersion(plugin.id, asset.name) ?: throw NoMatchingReleaseException("invalid asset version"), asset.name, asset.browserDownloadUrl, asset.size)
    }

    suspend fun download(asset: PluginReleaseAsset): DownloadedPluginPackage = withContext(Dispatchers.IO) {
        if (asset.size !in 1..MAX_ARCHIVE_BYTES) throw PluginIndexException("archive size limit")
        val address = accelerated(asset.url)
        val temporary = File.createTempFile("plugin-download-", ".zip", context.cacheDir)
        var delivered = false
        try {
            val result = transfer(address, MAX_ARCHIVE_BYTES, asset.size, temporary)
            DownloadedPluginPackage(temporary, asset, result.sha256, address).also { delivered = true }
        } finally { if (!delivered) Files.deleteIfExists(temporary.toPath()) }
    }

    suspend fun mirror(): String = storeSettings.downloadMirror.first()
    private suspend fun accelerated(address: String): String {
        require(PluginIndexAddress.isHttps(address)) { "HTTPS required" }
        val mirror = storeSettings.downloadMirror.first()
        if (mirror.isBlank() || java.net.URI(address).host !in MIRRORED_HOSTS) return address
        if (!PluginIndexAddress.isHttps(mirror)) throw PluginIndexException("invalid download mirror")
        return mirror.trimEnd('/') + "/" + address
    }

    private suspend fun decodeRelease(url: String): GitHubRelease = PluginIndexJson.decodeFromString(GitHubRelease.serializer(), transfer(url, MAX_API_BYTES, null, null).bytes!!.toString(Charsets.UTF_8))
    private data class Transfer(val bytes: ByteArray?, val sha256: String)

    private suspend fun transfer(url: String, max: Long, expected: Long?, destination: File?): Transfer = suspendCancellableCoroutine { continuation ->
        if (!PluginIndexAddress.isHttps(url)) { continuation.resumeWithException(PluginIndexException("HTTPS required")); return@suspendCancellableCoroutine }
        val request = Request.Builder().url(url).header("User-Agent", "aicode-plugin-index")
            .header("Accept", "application/vnd.github+json").cacheControl(CacheControl.Builder().noCache().build()).build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) { if (continuation.isActive) continuation.resumeWithException(failure) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val transferred = response.use {
                        if (!it.isSuccessful || it.request.url.scheme != "https") throw IOException("HTTPS response ${it.code}")
                        val body = it.body ?: throw IOException("empty response")
                        val length = body.contentLength()
                        if (length > max || (expected != null && length >= 0 && length != expected)) throw IOException("response size mismatch")
                        val digest = MessageDigest.getInstance("SHA-256")
                        val memory = if (destination == null) ByteArrayOutputStream() else null
                        if (!continuation.isActive) throw IOException("download cancelled")
                        val output = destination?.outputStream() ?: memory!!
                        output.use { stream ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    if (!continuation.isActive) throw IOException("download cancelled")
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    total += size
                                    if (total > max || (expected != null && total > expected)) throw IOException("response byte limit")
                                    stream.write(buffer, 0, size)
                                    digest.update(buffer, 0, size)
                                }
                                if (expected != null && total != expected) throw IOException("response size mismatch")
                            }
                        }
                        Transfer(memory?.toByteArray(), digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
                    }
                    if (continuation.isActive) continuation.resume(transferred)
                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                finally { if (continuation.isCancelled && destination != null) runCatching { Files.deleteIfExists(destination.toPath()) } }
            }
        })
    }

    @Serializable private data class GitHubRelease(val draft: Boolean = false, val assets: List<GitHubAsset> = emptyList())
    @Serializable private data class GitHubAsset(val name: String, @SerialName("browser_download_url") val browserDownloadUrl: String, val size: Long = 0)
    companion object {
        private const val GITHUB_API = "https://api.github.com"
        private const val MAX_API_BYTES = 4L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024
        private val MIRRORED_HOSTS = setOf("raw.githubusercontent.com", "github.com", "objects.githubusercontent.com", "codeload.github.com")
        fun assetName(id: String, version: String): String = "${id}_$version.zip"
        fun assetVersion(id: String, name: String): String? {
            if (!name.endsWith(".zip")) return null
            val prefix = listOf("${id}_", "$id-").firstOrNull { name.startsWith(it) } ?: return null
            return name.removePrefix(prefix).removeSuffix(".zip").takeIf { !it.startsWith('v') && SemVer.parse(it) != null }
        }
    }
}
