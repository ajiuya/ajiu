package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PluginDevPackageBuilder {
    private val skipped = setOf(".git", "node_modules", ".venv", "__pycache__", ".idea")
    suspend fun zip(sourceDir: File, target: File) {
        val root = sourceDir.absoluteFile.toPath().normalize()
        PluginFiles.checkDirectoryChain(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "development directory required" }
        var count = 0
        var total = 0L
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            suspend fun visit(directory: File, prefix: String) {
                currentCoroutineContext().ensureActive()
                val children = directory.listFiles()?.sortedBy { it.name } ?: error("directory unavailable")
                for (child in children) {
                    if (child.name in skipped) continue
                    val relative = prefix + child.name
                    require(PluginManifestParser.isSafeRelativePath(relative)) { "unsafe development path" }
                    require(!Files.isSymbolicLink(child.toPath())) { "development symlink forbidden" }
                    if (Files.isDirectory(child.toPath(), NOFOLLOW_LINKS)) visit(child, "$relative/")
                    else {
                        require(Files.isRegularFile(child.toPath(), NOFOLLOW_LINKS)) { "regular file required" }
                        require(++count <= 2000) { "development file limit" }
                        zip.putNextEntry(ZipEntry(relative))
                        child.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                total += size
                                require(total <= 64L * 1024 * 1024) { "development byte limit" }
                                zip.write(buffer, 0, size)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            visit(root.toFile(), "")
        }
        require(target.length() <= 16L * 1024 * 1024) { "development archive limit" }
    }
}
