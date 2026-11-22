package com.aicode.feature.backup.data

import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

class PreparedBackupContent(
    val metadata: BackupContentSectionMeta,
    val directory: File,
    val checkCurrent: () -> Unit = {}
)

internal object BackupContentArchive {
    const val PREFIX = "content/"
    const val FORMAT_VERSION = 1
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_SECTION_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_MANIFEST_BYTES = 16L * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")

    @Serializable
    private data class ContentFile(val path: String, val bytes: Long, val sha256: String, val executable: Boolean = false)
    @Serializable
    private data class Manifest(val directories: List<String>, val files: List<ContentFile>)

    class ImportSections internal constructor(
        val metadata: List<BackupContentSectionMeta>,
        val selection: BackupContentRestoreSelection,
        val root: File
    ) {
        val directories = linkedMapOf<String, File>()
        internal val seen = hashSetOf<String>()
        internal val manifestBytes = linkedMapOf<String, ByteArray>()
        internal val files = linkedMapOf<String, MutableMap<String, Pair<Long, String>>>()
        internal val folders = linkedMapOf<String, MutableSet<String>>()
        internal var bytes = 0L
        internal var entries = 0
        internal var pathChars = 0L
    }

    fun validateMetadata(sections: List<BackupContentSectionMeta>) {
        require(sections.size <= 512 && sections.map { it.id }.toSet().size == sections.size)
        require(sections.sumOf { it.bytes } in 0..MAX_TOTAL_BYTES)
        require(sections.sumOf { it.fileCount.toLong() } in 0..MAX_ENTRIES.toLong() * 4)
        sections.forEach { section ->
            require(idPattern.matches(section.id) && section.label.length in 1..512 && section.label.none(Char::isISOControl))
            require(section.fileCount in 0..MAX_ENTRIES && section.bytes in 0..MAX_SECTION_BYTES)
            require(section.workspaceName == null || validPart(section.workspaceName))
            require(section.sourceKey == null || validPart(section.sourceKey))
            require(section.sourceLabel == null || section.sourceLabel.length <= 1024 && section.sourceLabel.none(Char::isISOControl))
            require(when (section.kind) {
                BackupContentKind.PLUGIN -> section.location == BackupContentLocation.PLUGIN && section.pluginId != null
                BackupContentKind.SKILLS -> section.location != BackupContentLocation.PLUGIN
                BackupContentKind.MEMORY, BackupContentKind.PROMPTS -> section.location !in setOf(BackupContentLocation.PLUGIN, BackupContentLocation.SSH_GLOBAL, BackupContentLocation.SSH_PROJECT)
            })
        }
    }

    fun prepareImport(metadata: List<BackupContentSectionMeta>, selection: BackupContentRestoreSelection, root: File): ImportSections {
        validateMetadata(metadata)
        require(metadata.map { it.id }.containsAll(selection.sectionIds))
        checkDirectory(root)
        return ImportSections(metadata, selection, root).also { batch ->
            metadata.filter { it.id in selection.sectionIds }.forEach { section ->
                val directory = File(root, section.id)
                require(!Files.exists(directory.toPath(), NOFOLLOW_LINKS))
                check(directory.mkdir())
                batch.directories[section.id] = directory
            }
        }
    }

    fun writeExport(tar: TarArchiveOutputStream, section: PreparedBackupContent) {
        validateMetadata(listOf(section.metadata))
        val manifest = snapshot(section.directory, section.checkCurrent)
        require(manifest.files.size == section.metadata.fileCount && manifest.files.sumOf { it.bytes } == section.metadata.bytes)
        val bytes = json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES)
        writeEntry(tar, "$PREFIX${section.metadata.id}/manifest.json", bytes.inputStream(), bytes.size.toLong())
        for (directory in manifest.directories) {
            section.checkCurrent()
            val entry = TarArchiveEntry("$PREFIX${section.metadata.id}/files/$directory/")
            entry.size = 0
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }
        for (file in manifest.files) {
            section.checkCurrent()
            val source = safeFile(section.directory, file.path)
            val entry = TarArchiveEntry("$PREFIX${section.metadata.id}/files/${file.path}").apply { size = file.bytes }
            tar.putArchiveEntry(entry)
            val copied = Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { copy(it, tar, file.bytes) }
            require(copied.second == file.sha256)
            tar.closeArchiveEntry()
        }
        require(snapshot(section.directory, section.checkCurrent) == manifest)
    }

    fun readImport(tar: TarArchiveInputStream, entry: TarArchiveEntry, batch: ImportSections) {
        val regular = entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM
        val directory = entry.linkFlag == TarConstants.LF_DIR
        require(entry.name.startsWith(PREFIX) && !entry.isSymbolicLink && !entry.isLink && !entry.isSparse &&
            (regular && entry.isFile || directory && entry.isDirectory))
        val name = entry.name.removeSuffix("/")
        require(name.length <= 4096 + 128 && batch.pathChars <= 8L * 1024 * 1024 - name.length)
        batch.pathChars += name.length
        require(batch.seen.add(name) && ++batch.entries <= MAX_ENTRIES * 4)
        val parts = name.removePrefix(PREFIX).split('/', limit = 3)
        require(parts.size >= 2 && idPattern.matches(parts[0]))
        val id = parts[0]
        require(batch.metadata.any { it.id == id })
        if (parts.size == 2 && parts[1] == "manifest.json") {
            require(entry.isFile && entry.size in 1..MAX_MANIFEST_BYTES && id !in batch.manifestBytes)
            require(batch.manifestBytes.values.sumOf { it.size.toLong() } <= MAX_TOTAL_MANIFEST_BYTES - entry.size)
            batch.manifestBytes[id] = readBounded(tar, entry.size, MAX_MANIFEST_BYTES)
            return
        }
        require(parts.size == 3 && parts[1] == "files" && validRelative(parts[2]))
        val relative = parts[2]
        if (entry.isDirectory) {
            require(entry.size == 0L)
            batch.folders.getOrPut(id) { linkedSetOf() }.add(relative)
            batch.directories[id]?.let { mkdir(it, relative) }
        } else {
            require(entry.size in 0..MAX_FILE_BYTES && batch.bytes <= MAX_TOTAL_BYTES - entry.size)
            batch.bytes += entry.size
            val file = batch.directories[id]?.let { root ->
                relative.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { mkdir(root, it) }
                safeFile(root, relative)
            }
            val result = if (file == null) copy(tar, null, entry.size) else {
                Files.newOutputStream(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { copy(tar, it, entry.size) }
            }
            require(batch.files.getOrPut(id) { linkedMapOf() }.put(relative, result) == null)
            require(batch.files.getValue(id).size <= MAX_ENTRIES && batch.files.getValue(id).values.sumOf { it.first } <= MAX_SECTION_BYTES)
        }
    }

    fun finishImport(batch: ImportSections) {
        batch.metadata.forEach { section ->
            val manifest = json.decodeFromString(Manifest.serializer(), String(batch.manifestBytes[section.id] ?: throw IOException("Missing content manifest"), Charsets.UTF_8))
            require(manifest.files.size <= MAX_ENTRIES && manifest.files.map { it.path }.toSet().size == manifest.files.size)
            require(manifest.directories.size <= MAX_ENTRIES && manifest.directories.toSet().size == manifest.directories.size)
            require(manifest.files.size + manifest.directories.size <= MAX_ENTRIES)
            require(manifest.directories.all(::validRelative) && manifest.files.all { validRelative(it.path) && it.bytes in 0..MAX_FILE_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")) })
            require(manifest.files.none { file -> file.path in manifest.directories })
            val expected = manifest.files.associate { it.path to (it.bytes to it.sha256) }
            require(batch.files[section.id].orEmpty() == expected && batch.folders[section.id].orEmpty() == manifest.directories.toSet())
            require(manifest.files.size == section.fileCount && manifest.files.sumOf { it.bytes } == section.bytes)
            batch.directories[section.id]?.let { root ->
                manifest.files.filter { it.executable }.forEach { file ->
                    check(safeFile(root, file.path).setExecutable(true, true))
                }
            }
        }
    }

    fun metadata(id: String, kind: BackupContentKind, location: BackupContentLocation, label: String, directory: File): BackupContentSectionMeta {
        val manifest = snapshot(directory) {}
        return BackupContentSectionMeta(id, kind, location, label, manifest.files.size, manifest.files.sumOf { it.bytes })
    }

    fun validPart(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." && value.length <= 255 && value.none { it == '/' || it == '\\' || it.isISOControl() }
    fun validRelative(value: String): Boolean = value.length <= 4096 + "definitions/".length &&
        value.split('/').let { parts -> parts.size <= 65 && parts.all(::validPart) }

    fun checkDirectory(directory: File) {
        var current: File? = directory.absoluteFile
        while (current != null) {
            require(!Files.isSymbolicLink(current.toPath()))
            current = current.parentFile
        }
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
    }

    fun safeFile(root: File, relative: String): File {
        require(validRelative(relative))
        checkDirectory(root)
        val result = File(root, relative)
        var parent = result.parentFile
        while (parent != null && parent != root) {
            require(!Files.isSymbolicLink(parent.toPath()))
            parent = parent.parentFile
        }
        require(!Files.isSymbolicLink(result.toPath()))
        require(result.toPath().normalize().startsWith(root.toPath().normalize()))
        if (Files.exists(result.toPath(), NOFOLLOW_LINKS)) require(Files.isRegularFile(result.toPath(), NOFOLLOW_LINKS))
        return result
    }

    private fun mkdir(root: File, relative: String) {
        require(validRelative(relative))
        var current = root
        relative.split('/').forEach { part ->
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath()))
            if (!Files.exists(current.toPath(), NOFOLLOW_LINKS)) check(current.mkdir())
            checkDirectory(current)
        }
    }

    private fun snapshot(root: File, checkCurrent: () -> Unit): Manifest {
        checkDirectory(root)
        val files = arrayListOf<ContentFile>()
        val directories = arrayListOf<String>()
        var total = 0L
        fun visit(directory: File, prefix: String) {
            checkCurrent()
            checkDirectory(directory)
            val children = directory.listFiles()?.sortedBy { it.name } ?: throw IOException("Cannot list backup content")
            children.forEach { file ->
                require(validPart(file.name))
                val relative = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
                require(validRelative(relative) && files.size + directories.size < MAX_ENTRIES)
                if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
                    directories += relative
                    visit(file, relative)
                } else {
                    val source = safeFile(root, relative)
                    val size = Files.size(source.toPath())
                    require(size in 0..MAX_FILE_BYTES && total <= MAX_SECTION_BYTES - size)
                    val digest = Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { copy(it, null, size) }
                    files += ContentFile(relative, size, digest.second, source.canExecute())
                    total += size
                }
            }
        }
        visit(root, "")
        checkCurrent()
        return Manifest(directories.sorted(), files.sortedBy { it.path })
    }

    private fun writeEntry(tar: TarArchiveOutputStream, name: String, source: InputStream, size: Long) {
        tar.putArchiveEntry(TarArchiveEntry(name).apply { this.size = size })
        source.use { copy(it, tar, size) }
        tar.closeArchiveEntry()
    }

    private fun readBounded(input: InputStream, size: Long, maximum: Long): ByteArray {
        require(size in 0..maximum)
        return java.io.ByteArrayOutputStream(size.toInt()).use { output -> copy(input, output, size); output.toByteArray() }
    }

    private fun copy(input: InputStream, output: OutputStream?, expected: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && bytes <= expected - count)
            bytes += count
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
        require(bytes == expected)
        return bytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
