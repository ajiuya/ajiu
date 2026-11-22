package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.security.MessageDigest
import java.util.Locale

internal object PluginFiles {
    private const val MAX_ENTRY = 4L * 1024 * 1024
    private const val MAX_TOTAL = 32L * 1024 * 1024

    fun ensureDirectory(directory: File) {
        val path = directory.absoluteFile.toPath().normalize()
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            if (!Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) throw IOException("unsafe directory")
        }
    }
    fun checkedFile(root: File, relative: String): File {
        require(PluginManifestParser.isSafeRelativePath(relative)) { "unsafe relative path" }
        val base = root.absoluteFile.toPath().normalize()
        checkDirectoryChain(base)
        val path = base.resolve(relative).normalize()
        if (!path.startsWith(base)) throw IOException("outside root")
        checkDirectoryChain(path.parent)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("not regular: $relative")
        return path.toFile()
    }
    fun checkDirectoryChain(path: Path) {
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) throw IOException("unsafe directory")
        }
    }
    fun readRegular(root: File, relative: String, maximum: Int): ByteArray {
        val file = checkedFile(root, relative)
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { readBounded(it, maximum.toLong()) }
        checkedFile(root, relative)
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()) throw IOException("file replaced")
        return bytes
    }
    fun readRegularText(root: File, relative: String, maximum: Int): String = utf8(readRegular(root, relative, maximum))
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return output.toByteArray()
            if (size == 0) throw IOException("zero progress")
            count += size
            if (count > maximum) throw IOException("read limit")
            output.write(buffer, 0, size)
        }
    }
    fun hashTree(root: File): Map<String, String> {
        checkDirectoryChain(root.absoluteFile.toPath().normalize())
        val hashes = sortedMapOf<String, String>()
        val seen = HashSet<String>()
        var entries = 0
        var files = 0
        var total = 0L
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            private fun validate(path: Path) {
                val relative = root.toPath().relativize(path).joinToString("/")
                if (!PluginManifestParser.isSafeRelativePath(relative) || !seen.add(relative.lowercase(Locale.ROOT))) {
                    throw IOException("unsafe tree path")
                }
                if (++entries > 512 * PluginManifestParser.MAX_PATH_DEPTH) throw IOException("tree entries limit")
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root.toPath()) validate(dir)
                if (!attrs.isDirectory || attrs.isSymbolicLink) throw IOException("unsafe tree directory")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                validate(file)
                if (!attrs.isRegularFile || attrs.isSymbolicLink) throw IOException("unsafe tree file")
                if (++files > 512) throw IOException("tree file limit")
                val relative = root.toPath().relativize(file).joinToString("/")
                val bytes = readRegular(root, relative, MAX_ENTRY.toInt())
                total += bytes.size
                if (total > MAX_TOTAL) throw IOException("tree bytes limit")
                hashes[relative] = sha256(bytes)
                return FileVisitResult.CONTINUE
            }
        })
        return java.util.Collections.unmodifiableMap(hashes)
    }
    fun verify(root: File, expected: Map<String, String>): Boolean = hashTree(root) == expected
    fun contentHash(hashes: Map<String, String>): String = sha256(buildString {
        hashes.toSortedMap().forEach { (path, hash) -> append(path).append('\u0000').append(hash).append('\n') }
    }.toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    fun deleteOwned(directory: File) {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return
        checkDirectoryChain(directory.absoluteFile.parentFile.toPath().normalize())
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
