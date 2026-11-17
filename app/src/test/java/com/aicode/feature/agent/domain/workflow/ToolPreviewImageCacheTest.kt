package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64

class ToolPreviewImageCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, ToolPreviewImageCache.DIRECTORY)

    private fun image(size: Int = 64, marker: Byte = 0): AgentImage {
        val bytes = ByteArray(size) { marker }
        byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(bytes)
        return AgentImage("image/png", Base64.getEncoder().encodeToString(bytes), "https://untrusted.invalid/no-read")
    }

    @Test
    fun returnedImageIsStoredWithoutFollowingItsPathOrChangingModelBytes() {
        val source = image()
        val attachment = ToolPreviewImageCache(root).store("parent", "call", "viewImage", listOf(source)).single()
        assertEquals(image(), source)
        assertEquals("image/png", attachment.mimeType)
        assertTrue(attachment.isImage)
        assertEquals(64L, attachment.sizeBytes)
        assertEquals(attachment.localPath, attachment.containerPath)
        assertTrue(ToolPreviewImageCache.contains(root, attachment.localPath))
        assertArrayEquals(Base64.getDecoder().decode(source.base64Data), File(attachment.localPath).readBytes())
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun sameResultIsReusedButParentChildCallToolIndexAndContentRemainIsolated() {
        val cache = ToolPreviewImageCache(root)
        val source = listOf(image())
        val first = cache.store("parent", "call", "viewImage", source).single()
        assertEquals(first, cache.store("parent", "call", "viewImage", source).single())
        val others = listOf(
            cache.store("child", "call", "viewImage", source).single(),
            cache.store("parent", "other", "viewImage", source).single(),
            cache.store("parent", "call", "browser", source).single(),
            cache.store("parent", "call", "viewImage", listOf(image(), image()))[1],
            cache.store("parent", "call", "viewImage", listOf(image(marker = 1))).single(),
        )
        assertEquals(6, (others + first).map { it.localPath }.toSet().size)
        assertNotEquals(
            cache.store("a\u0000b", "c", "viewImage", source).single().localPath,
            cache.store("a", "b\u0000c", "viewImage", source).single().localPath,
        )
    }

    @Test
    fun missingSessionInvalidBase64AndMismatchedMimeDoNotPublishAttachments() {
        val cache = ToolPreviewImageCache(root)
        assertTrue(cache.store(null, "call", "viewImage", listOf(image())).isEmpty())
        assertTrue(cache.store(" ", "call", "viewImage", listOf(image())).isEmpty())
        assertFalse(root.exists())
        assertTrue(cache.store("s", "call", "viewImage", listOf(
            AgentImage("image/png", "not base64!"),
            AgentImage("text/plain", image().base64Data),
            AgentImage("image/jpeg", image().base64Data),
            AgentImage("image/png", ""),
        )).isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun allSupportedMimesRequireTheirOwnMagicAndUseSafeExtensions() {
        val signatures = listOf(
            "image/jpeg" to byteArrayOf(-1, -40, -1, 0),
            "image/webp" to "RIFF0000WEBP".toByteArray(),
            "image/gif" to "GIF89a".toByteArray(),
            " IMAGE/PNG ; ignored=value" to Base64.getDecoder().decode(image().base64Data),
        )
        val result = ToolPreviewImageCache(root).store("s", "call", "browser", signatures.map { (mime, bytes) ->
            AgentImage(mime, Base64.getEncoder().encodeToString(bytes))
        })
        assertEquals(listOf("jpg", "webp", "gif", "png"), result.map { File(it.localPath).extension })
        assertTrue(result.all { ToolPreviewImageCache.contains(root, it.localPath) })
    }

    @Test
    fun imageCallByteAndCountBudgetsAreExact() {
        val cache = ToolPreviewImageCache(root)
        assertTrue(cache.store("s", "large", "viewImage", listOf(image(ToolPreviewImageCache.MAX_IMAGE_BYTES + 1))).isEmpty())
        val budget = cache.store("s", "two-large", "viewImage", List(4) { image(ToolPreviewImageCache.MAX_IMAGE_BYTES, it.toByte()) })
        assertEquals(2, budget.size)
        assertEquals(ToolPreviewImageCache.MAX_CALL_BYTES.toLong(), budget.sumOf { it.sizeBytes })
        val count = cache.store("s", "count", "viewImage", List(9) { image(marker = it.toByte()) })
        assertEquals(ToolPreviewImageCache.MAX_IMAGES_PER_CALL, count.size)
    }

    @Test
    fun byteBudgetEvictsOnlyOldSafeCacheFilesAndKeepsCurrentImages() {
        root.mkdir()
        repeat(17) { index ->
            File(root, index.toString(16).padStart(64, '0') + ".png").apply {
                RandomAccessFile(this, "rw").use { it.setLength(ToolPreviewImageCache.MAX_IMAGE_BYTES.toLong()) }
                setLastModified(index.toLong() + 1)
            }
        }
        val unrelated = File(root, "user-note.txt").apply { writeText("keep") }
        val directory = File(root, "e".repeat(64) + ".png").apply { mkdir() }
        val current = ToolPreviewImageCache(root).store("s", "current", "viewImage", listOf(image())).single()
        assertTrue(File(current.localPath).isFile)
        assertEquals("keep", unrelated.readText())
        assertTrue(directory.isDirectory)
        assertTrue(root.listFiles()!!.filter { it.isFile && it.extension == "png" }.sumOf { it.length() } <= ToolPreviewImageCache.MAX_CACHE_BYTES)
        assertFalse(File(root, "0".repeat(64) + ".png").exists())
    }

    @Test
    fun fileCountBudgetDoesNotDeleteSymlinksOrUnrelatedNames() {
        root.mkdir()
        repeat(ToolPreviewImageCache.MAX_CACHE_FILES) { index ->
            File(root, index.toString(16).padStart(64, '0') + ".png").apply { writeBytes(byteArrayOf(1)); setLastModified(index + 1L) }
        }
        val outside = temporary.newFile().apply { writeText("private") }
        val link = File(root, "e".repeat(64) + ".gif")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val temporaryName = File(root, ".preview-unowned.tmp").apply { writeText("not ours") }
        ToolPreviewImageCache(root).store("s", "current", "viewImage", listOf(image())).single()
        assertEquals(ToolPreviewImageCache.MAX_CACHE_FILES, root.listFiles()!!.count {
            ToolPreviewImageCache.contains(root, it.absolutePath) && it.isFile
        })
        assertFalse(File(root, "0".repeat(64) + ".png").exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("private", outside.readText())
        assertEquals("not ours", temporaryName.readText())
    }

    @Test
    fun directoryAndImageSymlinksAndTraversalAreRejected() {
        val outside = temporary.newFolder()
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertTrue(ToolPreviewImageCache(root).store("s", "call", "viewImage", listOf(image())).isEmpty())
        assertTrue(outside.listFiles()!!.isEmpty())
        Files.delete(root.toPath())
        val cache = ToolPreviewImageCache(root)
        val attachment = cache.store("s", "call", "viewImage", listOf(image())).single()
        val target = File(attachment.localPath)
        val foreign = File(outside, target.name).apply { writeBytes(ByteArray(64) { 7 }) }
        Files.delete(target.toPath())
        Files.createSymbolicLink(target.toPath(), foreign.toPath())
        assertTrue(cache.store("s", "call", "viewImage", listOf(image())).isEmpty())
        assertFalse(ToolPreviewImageCache.contains(root, target.absolutePath))
        assertFalse(ToolPreviewImageCache.contains(root, foreign.absolutePath))
        assertFalse(ToolPreviewImageCache.contains(root, File(root, "../${target.name}").absolutePath))
        assertArrayEquals(ByteArray(64) { 7 }, foreign.readBytes())
    }

    @Test
    fun trustedAppCacheParentAliasIsCanonicalizedWithoutFollowingTheManagedDirectory() {
        val alias = File(temporary.root, "app-cache-alias")
        val trusted = temporary.newFolder("app-cache")
        Files.createSymbolicLink(alias.toPath(), trusted.toPath())
        val managed = File(alias.canonicalFile, ToolPreviewImageCache.DIRECTORY)
        val attachment = ToolPreviewImageCache(managed).store("s", "call", "viewImage", listOf(image())).single()
        assertTrue(ToolPreviewImageCache.contains(managed, attachment.localPath))
        assertEquals(File(trusted, ToolPreviewImageCache.DIRECTORY).absolutePath, File(attachment.localPath).parent)
    }

    @Test
    fun reusedFileIsVerifiedAndReplacedAtomicallyIfCorrupted() {
        val cache = ToolPreviewImageCache(root)
        val source = image()
        val attachment = cache.store("s", "call", "viewImage", listOf(source)).single()
        File(attachment.localPath).writeBytes(ByteArray(64) { 9 })
        assertEquals(attachment, cache.store("s", "call", "viewImage", listOf(source)).single())
        assertArrayEquals(Base64.getDecoder().decode(source.base64Data), File(attachment.localPath).readBytes())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".preview-") })
    }

    @Test
    fun storageFailureDoesNotThrowOrMutateImages() {
        val blocked = temporary.newFile().apply { writeText("existing") }
        val source = image()
        assertTrue(ToolPreviewImageCache(blocked).store("s", "call", "viewImage", listOf(source)).isEmpty())
        assertEquals("existing", blocked.readText())
        assertEquals(image(), source)
    }
}
