package com.aicode.feature.plugin.data.index

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPackageExpectation
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.PluginStatus
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginIndexClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val settings = mockk<PluginStoreSettingsRepository>()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var files: File
    private lateinit var cache: File

    @Before fun setUp(): Unit {
        files = temporary.newFolder("files")
        cache = temporary.newFolder("cache")
        every { context.filesDir } returns files
        every { context.cacheDir } returns cache
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.aicode.test"
        every { packageManager.getPackageInfo("com.aicode.test", 0) } returns PackageInfo().apply { versionName = "1.13.0" }
        every { settings.downloadMirror } returns flowOf("")
    }

    @Test fun officialDesktopPetFixedVersionDownloadsPreviewsAndInstallsDisabled(): Unit = runBlocking<Unit> {
        val plugin = plugin()
        val manifest = PluginCoreFixtures.manifest(id = plugin.id, version = "1.0.0")
            .replace("https://example.com/sample/plugin", plugin.repository)
        val archive = PluginCoreFixtures.archive(manifest)
        val digest = PluginFiles.sha256(archive)
        val releaseBody = "[${release(asset("${PLUGIN_ID}_1.1.0.zip"))},${release(asset(OFFICIAL_ASSET, archive.size.toLong()))}]"
        val client = client { request ->
            when (request.url.toString()) {
                RELEASES_URL -> json(releaseBody)
                PACKAGE_URL -> archive.toResponseBody("application/zip".toMediaType())
                else -> throw IOException("unexpected request: ${request.url}")
            }
        }
        val asset = client.resolveRelease(plugin)
        assertEquals("1.0.0", asset.version)
        assertEquals("jieapi.desktop-pet_1.0.0.zip", asset.name)
        assertEquals(archive.size.toLong(), asset.size)
        val manager = PluginManager(File(files, "plugins"), File(files, "plugin-registry.json"))
        val origin = PluginInstallOrigin(PluginInstallOriginKind.INDEX, "test-source", "Test source",
            "https://example.invalid/index.json", plugin.repository, false)
        val downloaded = client.download(asset)
        val downloadFile = downloaded.file
        val preview = downloaded.use {
            assertEquals(asset, it.asset)
            assertEquals(PACKAGE_URL, it.downloadUrl)
            assertEquals(digest, it.archiveSha256)
            assertEquals(cache.canonicalFile, it.file.parentFile.canonicalFile)
            assertArrayEquals(archive, it.file.readBytes())
            val expected = PluginPackageExpectation(plugin.id, asset.version, plugin.author, plugin.repository, it.archiveSha256)
            val result = it.file.inputStream().use { input -> manager.preview(input, origin, expected) }
            assertTrue(result.toString(), result is PluginPreviewResult.Success)
            (result as PluginPreviewResult.Success).preview
        }
        assertFalse(downloadFile.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals(digest, preview.archiveSha256)
        assertEquals(origin, preview.source)
        assertEquals("1.0.0", preview.manifest.version.toString())
        assertTrue(manager.plugins.value.isEmpty())
        val result = manager.install(preview.token)
        assertTrue(result.toString(), result is PluginInstallResult.Success)
        val installed = (result as PluginInstallResult.Success).plugin
        assertEquals(plugin.id, installed.record.id)
        assertEquals("1.0.0", installed.record.version)
        assertEquals(digest, installed.record.archiveSha256)
        assertEquals(origin, installed.record.source)
        assertEquals(PluginStatus.DISABLED, installed.status)
        assertFalse(installed.record.enabled)
        assertNull(installed.record.trustHash)
        assertFalse(installed.isActive)
        assertNull(manager.capture(plugin.id))
        assertTrue(manager.skills().isEmpty())
        assertTrue(File(files, "plugins/staging").listFiles()!!.isEmpty())
        assertEquals(listOf(RELEASES_URL, PACKAGE_URL), requests.toList())
    }

    @Test fun missingFixedVersionNeverFallsBackToNewerOrPrerelease(): Unit = runBlocking<Unit> {
        val client = client { json("[${release(asset("${PLUGIN_ID}_1.1.0.zip"), asset("${PLUGIN_ID}_1.0.0-rc.1.zip"))}]") }
        val failure = runCatching { client.resolveRelease(plugin()) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is NoMatchingReleaseException)
        assertEquals(listOf(RELEASES_URL), requests.toList())
    }

    @Test fun fixedVersionRequiresExactBuildMetadataRatherThanSemVerPrecedence(): Unit = runBlocking<Unit> {
        val client = client { json("[${release(asset("${PLUGIN_ID}_1.0.0+build.2.zip"), asset("${PLUGIN_ID}_1.1.0.zip"))}]") }
        val failure = runCatching { client.resolveRelease(plugin("1.0.0+build.1")) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is NoMatchingReleaseException)
        assertEquals(listOf(RELEASES_URL), requests.toList())
    }

    @Test fun fixedVersionIgnoresDraftAssetBeforePublishedMatch(): Unit = runBlocking<Unit> {
        val draft = release(asset(OFFICIAL_ASSET, url = "https://example.invalid/draft.zip"), draft = true)
        val published = release(asset(OFFICIAL_ASSET))
        val client = client { json("[$draft,$published]") }
        assertEquals(PACKAGE_URL, client.resolveRelease(plugin()).url)
        assertEquals(listOf(RELEASES_URL), requests.toList())
    }

    @Test fun draftOnlyReleaseIsRejectedForFixedAndLatestVersions(): Unit = runBlocking<Unit> {
        for (version in listOf("1.0.0", null)) {
            val draft = release(asset(OFFICIAL_ASSET), draft = true)
            val client = client { json(if (version == null) draft else "[$draft]") }
            val failure = runCatching { client.resolveRelease(plugin(version)) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is NoMatchingReleaseException)
        }
        assertEquals(listOf(RELEASES_URL, LATEST_URL), requests.toList())
    }

    @Test fun latestUsesHighestValidUnderscoreSemVerForExactPluginId(): Unit = runBlocking<Unit> {
        val body = release(
            asset("${PLUGIN_ID}_1.9.0.zip"), asset("${PLUGIN_ID}_1.10.0-rc.10.zip"),
            asset("${PLUGIN_ID}_1.10.0.zip"), asset("other.desktop-pet_99.0.0.zip"),
            asset("${PLUGIN_ID}.extra_99.0.0.zip"), asset("${PLUGIN_ID}_v99.0.0.zip"),
            asset("${PLUGIN_ID}_099.0.0.zip"), asset("${PLUGIN_ID}_99.0.0-01.zip"),
            asset("${PLUGIN_ID}_99.0.0.zip.sig"), asset("${PLUGIN_ID}_1.2.0.zip")
        )
        val client = client { json(body) }
        val asset = client.resolveRelease(plugin(null))
        assertEquals("1.10.0", asset.version)
        assertEquals("${PLUGIN_ID}_1.10.0.zip", asset.name)
        assertEquals(listOf(LATEST_URL), requests.toList())
    }

    @Test fun latestComparesPrereleaseNumbersNumerically(): Unit = runBlocking<Unit> {
        val client = client { json(release(asset("${PLUGIN_ID}_2.0.0-rc.2.zip"), asset("${PLUGIN_ID}_2.0.0-rc.10.zip"), asset("${PLUGIN_ID}_1.99.0.zip"))) }
        assertEquals("2.0.0-rc.10", client.resolveRelease(plugin(null)).version)
        assertEquals(listOf(LATEST_URL), requests.toList())
    }

    @Test fun localHyphenArchivesResolveForFixedAndLatestPrerelease(): Unit = runBlocking<Unit> {
        for (version in listOf("1.0.0-rc.1", null)) {
            val body = release(asset("$PLUGIN_ID-1.0.0-rc.1.zip"))
            val client = client { json(if (version == null) body else "[$body]") }
            val asset = client.resolveRelease(plugin(version))
            assertEquals("1.0.0-rc.1", asset.version)
            assertEquals("$PLUGIN_ID-1.0.0-rc.1.zip", asset.name)
        }
        assertEquals(listOf(RELEASES_URL, LATEST_URL), requests.toList())
    }

    @Test fun releaseResolutionRejectsInsecureOrCredentialBearingAssetUrls(): Unit = runBlocking<Unit> {
        for (url in unsafeUrls()) {
            val client = client { json("[${release(asset(OFFICIAL_ASSET, url = url))}]") }
            val failure = runCatching { client.resolveRelease(plugin()) }.exceptionOrNull()
            assertTrue("$url: $failure", failure is NoMatchingReleaseException)
        }
        assertEquals(List(unsafeUrls().size) { RELEASES_URL }, requests.toList())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun downloadRejectsUnsafeHttpsAndInvalidSizesBeforeTransfer(): Unit = runBlocking<Unit> {
        val client = client { throw IOException("download must be rejected before HTTP") }
        for (url in unsafeUrls()) {
            val failure = runCatching { client.download(PluginReleaseAsset("1.0.0", OFFICIAL_ASSET, url, 1)) }.exceptionOrNull()
            assertTrue("$url: $failure", failure is IllegalArgumentException)
        }
        for (size in listOf(-1L, 0L, PluginIndexClient.MAX_ARCHIVE_BYTES + 1)) {
            val failure = runCatching { client.download(PluginReleaseAsset("1.0.0", OFFICIAL_ASSET, PACKAGE_URL, size)) }.exceptionOrNull()
            assertTrue("$size: $failure", failure is PluginIndexException)
        }
        assertTrue(requests.isEmpty())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun mismatchedContentLengthFailsAndRemovesTemporaryDownload(): Unit = runBlocking<Unit> {
        val client = client { byteArrayOf(1, 2, 3).toResponseBody() }
        val failure = runCatching { client.download(PluginReleaseAsset("1.0.0", OFFICIAL_ASSET, PACKAGE_URL, 4)) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException)
        assertEquals("response size mismatch", failure?.message)
        assertEquals(listOf(PACKAGE_URL), requests.toList())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun unknownContentLengthStillRejectsTruncatedBodyAndCleansPartialFile(): Unit = runBlocking<Unit> {
        val client = client {
            object : ResponseBody() {
                private val buffer = Buffer().write(byteArrayOf(1, 2, 3))
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength(): Long = -1
                override fun source() = buffer
            }
        }
        val failure = runCatching { client.download(PluginReleaseAsset("1.0.0", OFFICIAL_ASSET, PACKAGE_URL, 4)) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException)
        assertEquals("response size mismatch", failure?.message)
        assertEquals(listOf(PACKAGE_URL), requests.toList())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun previewRejectsDownloadedArchiveWithWrongExpectedDigestAndCleansStaging(): Unit = runBlocking<Unit> {
        val plugin = plugin()
        val archive = PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = plugin.id).replace("https://example.com/sample/plugin", plugin.repository))
        val client = client { request ->
            when (request.url.toString()) {
                RELEASES_URL -> json("[${release(asset(OFFICIAL_ASSET, archive.size.toLong()))}]")
                PACKAGE_URL -> archive.toResponseBody()
                else -> throw IOException("unexpected request: ${request.url}")
            }
        }
        val asset = client.resolveRelease(plugin)
        val manager = PluginManager(File(files, "plugins"), File(files, "plugin-registry.json"))
        val downloaded = client.download(asset)
        val downloadFile = downloaded.file
        downloaded.use {
            assertEquals(PluginFiles.sha256(archive), it.archiveSha256)
            val expected = PluginPackageExpectation(plugin.id, asset.version, plugin.author, plugin.repository, "0".repeat(64))
            val result = it.file.inputStream().use { input -> manager.preview(input, expected = expected) }
            assertTrue(result.toString(), result is PluginPreviewResult.Failure)
            assertEquals(PluginErrorCode.CONTENT_CHANGED, (result as PluginPreviewResult.Failure).errors.single().code)
            assertTrue(manager.plugins.value.isEmpty())
            assertTrue(File(files, "plugins/staging").listFiles()!!.isEmpty())
        }
        assertFalse(downloadFile.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals(listOf(RELEASES_URL, PACKAGE_URL), requests.toList())
    }

    private fun client(reply: (Request) -> ResponseBody): PluginIndexClient {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(request)).build()
        }
        return PluginIndexClient(context, OkHttpClient.Builder().addInterceptor(interceptor).build(), settings)
    }
    private fun json(text: String): ResponseBody = text.toResponseBody("application/json".toMediaType())
    private fun release(vararg assets: String, draft: Boolean = false): String = """{"draft":$draft,"assets":[${assets.joinToString(",")}]}"""
    private fun asset(name: String, size: Long = 1, url: String = PACKAGE_URL): String = """{"name":"$name","browser_download_url":"$url","size":$size}"""
    private fun plugin(version: String? = "1.0.0") = PluginIndexPlugin(PLUGIN_ID, LocalizedText.single("Desktop pet"),
        LocalizedText.single("Desktop pet fixture"), "jieapi", "https://github.com/jieapi/aicode-plugin-desktop-pet", version = version)
    private fun unsafeUrls(): List<String> = listOf("http://example.invalid/plugin.zip", "https://user:password@example.invalid/plugin.zip",
        "https://example.invalid:80/plugin.zip", "https://example.invalid/plugin.zip#fragment")

    private companion object {
        const val PLUGIN_ID = "jieapi.desktop-pet"
        const val OFFICIAL_ASSET = "jieapi.desktop-pet_1.0.0.zip"
        const val RELEASES_URL = "https://api.github.com/repos/jieapi/aicode-plugin-desktop-pet/releases?per_page=100"
        const val LATEST_URL = "https://api.github.com/repos/jieapi/aicode-plugin-desktop-pet/releases/latest"
        const val PACKAGE_URL = "https://example.invalid/jieapi.desktop-pet_1.0.0.zip"
    }
}
