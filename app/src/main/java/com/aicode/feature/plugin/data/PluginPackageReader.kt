package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipFile

data class ExtractedPackage(
    val dir: File,
    val manifestText: String,
    val archiveSha256: String,
    val manifestSha256: String,
    val fileHashes: Map<String, String>,
    val contentHash: String
)
sealed interface PackageReadResult {
    data class Ok(val pkg: ExtractedPackage) : PackageReadResult
    data class Failed(val error: PluginError) : PackageReadResult
}
class PluginPackageReader(private val limits: Limits = Limits()) {
    data class Limits(
        val maxArchiveBytes: Long = 16L * 1024 * 1024,
        val maxEntries: Int = 512,
        val maxEntryBytes: Long = 4L * 1024 * 1024,
        val maxTotalBytes: Long = 32L * 1024 * 1024
    )
    private class Rejected(val error: PluginError) : IOException()
    private fun reject(code: PluginErrorCode, detail: String = ""): Nothing = throw Rejected(PluginError(code, detail))

    fun read(input: InputStream, targetDir: File): PackageReadResult {
        var owned = false
        var archive: File? = null
        return try {
            if (Files.exists(targetDir.toPath(), NOFOLLOW_LINKS)) reject(PluginErrorCode.STORAGE_FAILED, "target exists")
            PluginFiles.ensureDirectory(targetDir.parentFile)
            Files.createDirectory(targetDir.toPath())
            owned = true
            archive = Files.createTempFile(targetDir.parentFile.toPath(), ".plugin-", ".zip").toFile()
            val archiveHash = copyArchive(input, archive)
            extract(archive, targetDir)
            val manifest = File(targetDir, MANIFEST_FILE)
            if (!Files.isRegularFile(manifest.toPath(), NOFOLLOW_LINKS)) reject(PluginErrorCode.MANIFEST_MISSING)
            if (manifest.length() > PluginManifestParser.MAX_MANIFEST_BYTES) reject(PluginErrorCode.MANIFEST_TOO_LARGE)
            val bytes = PluginFiles.readRegular(targetDir, MANIFEST_FILE, PluginManifestParser.MAX_MANIFEST_BYTES)
            val hashes = PluginFiles.hashTree(targetDir)
            PackageReadResult.Ok(ExtractedPackage(targetDir, PluginFiles.utf8(bytes), archiveHash,
                sha256Hex(bytes), hashes, PluginFiles.contentHash(hashes)))
        } catch (e: Exception) {
            if (owned) runCatching { PluginFiles.deleteOwned(targetDir) }
            PackageReadResult.Failed(if (e is Rejected) e.error else PluginError(PluginErrorCode.ARCHIVE_UNREADABLE, e.javaClass.simpleName))
        } finally { archive?.let { runCatching { Files.deleteIfExists(it.toPath()) } } }
    }
    private fun copyArchive(input: InputStream, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.outputStream().use { out ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) reject(PluginErrorCode.ARCHIVE_UNREADABLE, "zero progress")
                total += count
                if (total > limits.maxArchiveBytes) reject(PluginErrorCode.ARCHIVE_TOO_LARGE)
                digest.update(buffer, 0, count)
                out.write(buffer, 0, count)
            }
        }
        if (total == 0L) reject(PluginErrorCode.ARCHIVE_UNREADABLE, "empty")
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
    private fun extract(archive: File, target: File) {
        val seen = HashSet<String>()
        val nodes = HashMap<String, Pair<Boolean, String>>()
        var entries = 0
        var total = 0L
        ZipFile(archive).use { zip ->
            for (entry in zip.entries) {
                if (++entries > limits.maxEntries) reject(PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES)
                val rawName = entry.rawName ?: reject(PluginErrorCode.ARCHIVE_UNSAFE_PATH, "missing raw name")
                val rawPath = rawName.toString(Charsets.ISO_8859_1).removeSuffix("/")
                val rawSegments = rawPath.split('/')
                // Commons Compress normalizes FAT backslashes before exposing entry.name.
                if (rawName.size > PluginManifestParser.MAX_PATH_LENGTH * 4 + 1 ||
                    rawName.any { (it.toInt() and 255) in 0..31 || (it.toInt() and 255) == 127 } ||
                    rawPath.startsWith('/') || rawPath.contains('\\') || rawPath.contains(':') ||
                    rawSegments.size > PluginManifestParser.MAX_PATH_DEPTH ||
                    rawSegments.any { it.isEmpty() || it == "." || it == ".." }) {
                    reject(PluginErrorCode.ARCHIVE_UNSAFE_PATH, entry.name)
                }
                val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                if (!PluginManifestParser.isSafeRelativePath(name)) reject(PluginErrorCode.ARCHIVE_UNSAFE_PATH, name)
                if (entry.isUnixSymlink) reject(PluginErrorCode.ARCHIVE_SYMLINK, name)
                if (!seen.add(name.lowercase(Locale.ROOT))) reject(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, name)
                val segments = name.split('/')
                for (index in segments.indices) {
                    val path = segments.take(index + 1).joinToString("/")
                    val directory = index != segments.lastIndex || entry.isDirectory
                    val old = nodes.putIfAbsent(path.lowercase(Locale.ROOT), directory to path)
                    if (old != null && (old.first != directory || old.second != path)) reject(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, path)
                }
                val file = File(target, name)
                if (entry.isDirectory) { PluginFiles.ensureDirectory(file); continue }
                PluginFiles.ensureDirectory(file.parentFile)
                Files.createFile(file.toPath())
                var written = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) reject(PluginErrorCode.ARCHIVE_UNREADABLE, "zero progress")
                            written += count
                            total += count
                            if (written > limits.maxEntryBytes) reject(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE, name)
                            if (total > limits.maxTotalBytes) reject(PluginErrorCode.ARCHIVE_TOO_LARGE)
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (entry.crc != crc.value || (entry.size >= 0 && written != entry.size)) reject(PluginErrorCode.ARCHIVE_UNREADABLE, name)
            }
        }
    }
    companion object {
        const val MANIFEST_FILE = "plugin.json"
        fun sha256Hex(bytes: ByteArray): String = PluginFiles.sha256(bytes)
    }
}
