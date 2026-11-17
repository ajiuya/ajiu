package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.presentation.AgentAttachment
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Only stores bytes already returned by a tool; paths and model images are never rewritten. */
internal class ToolPreviewImageCache(private val directory: File) {
    companion object {
        const val DIRECTORY = "tool-preview-images"
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_CALL_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGES_PER_CALL = 4
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_FILES = 512
        private val lock = Any()
        private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val cacheName = Regex("[a-f0-9]{64}\\.(png|jpg|webp|gif)")
        private val extensions = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")

        fun contains(root: File, path: String): Boolean = try {
            val candidate = File(path)
            candidate.isAbsolute && candidate.parentFile?.absoluteFile == root.absoluteFile &&
                root.absoluteFile == root.canonicalFile && candidate.absoluteFile == candidate.canonicalFile &&
                !Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(candidate.toPath()) &&
                cacheName.matches(candidate.name)
        } catch (_: Exception) {
            false
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun store(sessionId: String?, callId: String, toolName: String, images: List<AgentImage>): List<AgentAttachment> = synchronized(lock) {
        if (sessionId.isNullOrBlank() || images.isEmpty()) return@synchronized emptyList()
        val stored = mutableListOf<AgentAttachment>()
        var remaining = MAX_CALL_BYTES
        try {
            val root = directory.absoluteFile
            if (root != root.canonicalFile || Files.isSymbolicLink(root.toPath())) return@synchronized emptyList()
            if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root.toPath())
            if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@synchronized emptyList()
            for ((index, image) in images.take(MAX_IMAGES_PER_CALL).withIndex()) {
                val mime = image.mimeType.lowercase().substringBefore(';').trim()
                val extension = extensions[mime] ?: continue
                if (image.base64Data.length > ((MAX_IMAGE_BYTES + 2) / 3) * 4 + 32) continue
                val bytes = try { Base64.getDecoder().decode(image.base64Data) } catch (_: IllegalArgumentException) { continue }
                if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || bytes.size > remaining || !matchesMime(bytes, mime)) continue
                val payloadHash = hash(bytes)
                val identity = listOf(sessionId, callId, toolName, index.toString(), payloadHash)
                    .joinToString("") { "${it.length}:$it" }
                val file = File(root, "${hash(identity.toByteArray(Charsets.UTF_8))}.$extension")
                if (!contains(root, file.absolutePath)) continue
                if (!matchesCachedBytes(file, bytes.size, payloadHash)) {
                    val staged = Files.createTempFile(root.toPath(), ".preview-", ".tmp")
                    try {
                        FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) output.write(buffer)
                            output.force(true)
                        }
                        if (!contains(root, file.absolutePath)) continue
                        Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(staged)
                    }
                }
                if (!contains(root, file.absolutePath) || file.length() != bytes.size.toLong()) continue
                file.setLastModified(System.currentTimeMillis())
                remaining -= bytes.size
                stored += AgentAttachment(file.name, file.absolutePath, file.absolutePath, mime, bytes.size.toLong(), true)
            }
            trim(root, stored.mapTo(hashSetOf()) { it.fileName })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Optional UI storage must not turn a successful tool result into an error.
        }
        stored.distinct()
    }

    private fun matchesCachedBytes(file: File, size: Int, expectedHash: String): Boolean {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != size.toLong()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > size) return false
                digest.update(buffer, 0, count)
            }
            if (total != size) return false
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expectedHash
    }

    private fun trim(root: File, protected: Set<String>) {
        val files = root.listFiles().orEmpty().filter {
            contains(root, it.absolutePath) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES && count <= MAX_CACHE_FILES) break
            if (file.name !in protected && contains(root, file.absolutePath)) {
                val size = file.length()
                if (Files.deleteIfExists(file.toPath())) { total -= size; count-- }
            }
        }
    }

    private fun matchesMime(bytes: ByteArray, mime: String): Boolean = when (mime) {
        "image/png" -> bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        else -> false
    }
}
