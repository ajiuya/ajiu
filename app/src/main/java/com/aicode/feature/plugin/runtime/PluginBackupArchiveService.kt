package com.aicode.feature.plugin.runtime

import com.aicode.feature.backup.domain.PluginBackupSectionMeta
import com.aicode.feature.backup.domain.PluginBackupSectionResult
import com.aicode.feature.backup.domain.PluginBackupSectionStatus
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.TransferVersion
import com.aicode.feature.workspace.domain.transferStream
import com.aicode.feature.workspace.domain.transferVersion
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class PluginBackupArchiveException(val pluginId: String, val code: String, cause: Throwable? = null) : IOException("Plugin backup section failed: $pluginId ($code)", cause)

@Singleton
class PluginBackupArchiveService @Inject constructor(
    private val plugins: PluginManager,
    private val runtime: PluginRuntimeManager,
    private val gateway: Provider<PluginHostGateway>,
    private val capabilities: PluginCapabilityRepository
) {
    class Section internal constructor(val context: PluginInvocationContext, val directory: File, val metadata: PluginBackupSectionMeta,
        internal val identity: Any?, internal val version: TransferVersion? = null) {
        internal val seen = hashSetOf<String>()
        internal var bytes = 0L
        internal var files = 0
    }
    private val lock = Any()
    private val busy = hashSetOf<String>()

    suspend fun available(): List<PluginBackupSectionMeta> {
        plugins.ensureLoaded()
        return plugins.plugins.value.filter { it.isActive && it.content.backup != null }.map {
            PluginBackupSectionMeta(it.record.id, requireNotNull(it.content.backup).purpose)
        }
    }

    suspend fun prepareExport(pluginIds: Set<String>): List<Section> {
        val sections = arrayListOf<Section>()
        try {
            for (id in pluginIds.sorted()) {
                val context = authorize(id, "export")
                val section = prepare(context, EXPORT_DIR)
                sections += section
                invoke(context, PluginHookEvents.BACKUP_EXPORT_BEFORE, buildJsonObject { put("dir", "data://$EXPORT_DIR") })
                val version = boundedVersion(section)
                val files = version.nodes.count { it.stat.kind == TransferKind.FILE }
                val bytes = version.nodes.sumOf { it.bytes }
                sections[sections.lastIndex] = Section(context, section.directory,
                    section.metadata.copy(fileCount = files, bytes = bytes), section.identity, version)
            }
            return sections
        } catch (failure: Throwable) {
            cleanup(sections)
            throw failure
        }
    }

    suspend fun writeExport(tar: TarArchiveOutputStream, section: Section) {
        val version = requireNotNull(section.version)
        val check = checked(section)
        val tree = backend(section, check)
        if (boundedVersion(section) != version) throw PluginBackupArchiveException(section.context.capture.id, "SECTION_CHANGED")
        for (node in version.nodes) {
            check()
            if (node.relative.isEmpty()) continue
            if (!isValidSectionPath(node.relative) || node.stat.kind == TransferKind.LINK) throw PluginBackupArchiveException(section.context.capture.id, "PATH_INVALID")
            val entry = TarArchiveEntry("$PREFIX${section.context.capture.id}/${node.relative}" + if (node.stat.kind == TransferKind.DIRECTORY) "/" else "")
            entry.size = node.bytes
            tar.putArchiveEntry(entry)
            if (node.stat.kind == TransferKind.FILE) {
                if (tree.stat(node.relative) != node.stat) throw PluginBackupArchiveException(section.context.capture.id, "SECTION_CHANGED")
                val copied = tree.openRead(node.relative).use { transferStream(it, tar, node.bytes, check) }
                if (copied.digest != node.digest) throw PluginBackupArchiveException(section.context.capture.id, "SECTION_CHANGED")
            }
            tar.closeArchiveEntry()
        }
        if (boundedVersion(section) != version) throw PluginBackupArchiveException(section.context.capture.id, "SECTION_CHANGED")
    }

    suspend fun prepareImport(metadata: List<PluginBackupSectionMeta>, selectedIds: Set<String>): ImportSections {
        if (metadata.size > 64 || metadata.map { it.pluginId }.toSet().size != metadata.size || metadata.any {
                !PluginManifestParser.isValidPluginId(it.pluginId) || it.fileCount !in 0..MAX_ENTRIES || it.bytes !in 0..MAX_TOTAL_BYTES || it.purpose.length > 4096
            } || !metadata.map { it.pluginId }.containsAll(selectedIds)) throw IOException("Invalid plugin backup manifest")
        val result = ImportSections(metadata, selectedIds)
        try {
            for (meta in metadata) {
                if (meta.pluginId !in selectedIds) {
                    result.results += PluginBackupSectionResult(meta.pluginId, PluginBackupSectionStatus.NOT_SELECTED)
                    continue
                }
                try {
                    val context = authorize(meta.pluginId, "import")
                    result.sections[meta.pluginId] = prepare(context, IMPORT_DIR, meta)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: PluginHostException) {
                    result.results += PluginBackupSectionResult(meta.pluginId, PluginBackupSectionStatus.DENIED, failure.code)
                } catch (failure: PluginBackupArchiveException) {
                    result.results += PluginBackupSectionResult(meta.pluginId, PluginBackupSectionStatus.UNAVAILABLE, failure.code)
                } catch (_: IOException) {
                    result.results += PluginBackupSectionResult(meta.pluginId, PluginBackupSectionStatus.FAILED, "SECTION_UNAVAILABLE")
                }
            }
            return result
        } catch (failure: Throwable) { cleanup(result.sections.values); throw failure }
    }

    class ImportSections internal constructor(internal val metadata: List<PluginBackupSectionMeta>, internal val selected: Set<String>) {
        internal val sections = linkedMapOf<String, Section>()
        internal val results = arrayListOf<PluginBackupSectionResult>()
        internal val seenEntries = hashSetOf<String>()
        internal var entries = 0
        internal var actualBytes = 0L
    }

    suspend fun readImport(tar: TarArchiveInputStream, entry: TarArchiveEntry, batch: ImportSections) {
        if (!entry.name.startsWith(PREFIX)) throw IOException("Invalid plugin archive namespace")
        val parts = entry.name.removePrefix(PREFIX).trimEnd('/').split('/', limit = 2)
        if (parts.size != 2 || !PluginManifestParser.isValidPluginId(parts[0]) || !isValidSectionPath(parts[1]) ||
            entry.isSymbolicLink || entry.isLink || (!entry.isFile && !entry.isDirectory) || entry.size !in 0..MAX_ENTRY_BYTES ||
            !batch.seenEntries.add(entry.name.trimEnd('/')) || ++batch.entries > MAX_ENTRIES * 64) throw IOException("Invalid plugin archive entry")
        if (batch.metadata.none { it.pluginId == parts[0] }) throw IOException("Plugin archive entry is not declared in metadata")
        val section = batch.sections[parts[0]]
        val check = section?.let { checked(it) } ?: run { val job = currentCoroutineContext(); { job.ensureActive() } }
        val path = parts[1]
        if (entry.isDirectory) {
            if (entry.size != 0L) throw IOException("Invalid plugin directory size")
            section?.let { check(); PluginFiles.ensureDirectory(File(it.directory, path)); check() }
            return
        }
        if (section != null && (!section.seen.add(path) || ++section.files > MAX_ENTRIES)) throw IOException("Plugin section entry limit")
        val output = section?.let {
            check()
            val target = it.directory.toPath().resolve(path)
            PluginFiles.ensureDirectory(target.parent.toFile())
            PluginFiles.checkDirectoryChain(target.parent)
            Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        }
        try {
            val bytes = copyBounded(tar, output, entry.size, MAX_ENTRY_BYTES, check)
            batch.actualBytes += bytes
            if (batch.actualBytes > MAX_TOTAL_BYTES * 64) throw IOException("Plugin archive byte limit")
            if (section != null) {
                section.bytes += bytes
                if (section.bytes > MAX_TOTAL_BYTES) throw IOException("Plugin section byte limit")
            }
        } finally { output?.close() }
    }

    suspend fun finishImport(batch: ImportSections, stats: JsonObject): List<PluginBackupSectionResult> {
        for ((id, section) in batch.sections) {
            try {
                checked(section)()
                if (section.files != section.metadata.fileCount || section.bytes != section.metadata.bytes) throw PluginBackupArchiveException(id, "SECTION_SIZE_MISMATCH")
                gateway.get().requireCapability(section.context, PluginCapability.BACKUP)
                invoke(section.context, PluginHookEvents.BACKUP_IMPORT_FINISHED, buildJsonObject {
                    put("dir", "data://$IMPORT_DIR"); put("files", section.files); put("stats", stats)
                })
                batch.results += PluginBackupSectionResult(id, PluginBackupSectionStatus.RESTORED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                batch.results += PluginBackupSectionResult(id, PluginBackupSectionStatus.FAILED,
                    (failure as? PluginHostException)?.code ?: (failure as? PluginBackupArchiveException)?.code ?: "SECTION_FAILED")
            }
        }
        return batch.results.toList()
    }

    fun cleanupImport(batch: ImportSections?) { batch?.let { cleanup(it.sections.values) } }

    fun cleanup(sections: Collection<Section>) {
        for (section in sections) {
            try {
                val attrs = Files.readAttributes(section.directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isDirectory && attrs.fileKey() == section.identity) PluginFiles.deleteOwned(section.directory)
            } catch (_: IOException) { }
            finally { synchronized(lock) { busy.remove(section.context.capture.id) } }
        }
    }

    private suspend fun authorize(id: String, direction: String): PluginInvocationContext {
        if (!PluginManifestParser.isValidPluginId(id)) throw PluginBackupArchiveException(id, "PLUGIN_INVALID")
        plugins.ensureLoaded()
        val capture = plugins.capture(id) ?: throw PluginBackupArchiveException(id, "PLUGIN_UNAVAILABLE")
        val purpose = capture.content.backup?.purpose ?: throw PluginBackupArchiveException(id, "BACKUP_UNDECLARED")
        val event = if (direction == "export") PluginHookEvents.BACKUP_EXPORT_BEFORE else PluginHookEvents.BACKUP_IMPORT_FINISHED
        if (capture.content.hooks.none { it.event == event && it.mode == PluginHookModes.OBSERVE }) throw PluginBackupArchiveException(id, "HOOK_UNAVAILABLE")
        val context = gateway.get().captureContext(capture)
        gateway.get().requireCapability(context, PluginCapability.BACKUP)
        val args = buildJsonObject { put("operation", direction); put("pluginId", id); put("root", "data://backup-$direction"); put("purpose", purpose) }
        gateway.get().authorize(context, "backup", args, PluginApprovalOperation.EXECUTE_TOOL, args.toString())
        return context
    }

    private fun prepare(context: PluginInvocationContext, name: String, metadata: PluginBackupSectionMeta? = null): Section {
        context.checkIntegrity()
        synchronized(lock) { if (!busy.add(context.capture.id)) throw PluginBackupArchiveException(context.capture.id, "SECTION_BUSY") }
        val directory = File(context.dataDirectory, name)
        try {
            if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) PluginFiles.deleteOwned(directory)
            PluginFiles.ensureDirectory(directory)
            val identity = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                ?: throw IOException("Plugin backup directory identity unavailable")
            return Section(context, directory, metadata ?: PluginBackupSectionMeta(context.capture.id, requireNotNull(context.capture.content.backup).purpose), identity)
        } catch (failure: Throwable) { synchronized(lock) { busy.remove(context.capture.id) }; throw failure }
    }

    private suspend fun invoke(context: PluginInvocationContext, event: String, payload: JsonObject) {
        context.whileCurrent {
            for (hook in context.capture.content.hooks.filter { it.event == event && it.mode == PluginHookModes.OBSERVE }) {
                gateway.get().requireCapability(context, PluginCapability.BACKUP)
                runtime.invoke(context.capture, hook.runtime, hook.handler, payload, context.runtimeContext(),
                    { method, args -> gateway.get().call(context, method, args) }, "hook.invoke", 60_000)
            }
        }
    }

    private suspend fun checked(section: Section): () -> Unit {
        val job = currentCoroutineContext()
        return {
            job.ensureActive(); section.context.checkCurrent()
            if (!capabilities.checkGranted(section.context.capture, PluginCapability.BACKUP)) throw CancellationException("Plugin backup capability revoked")
            PluginFiles.checkDirectoryChain(section.directory.toPath())
            if (Files.readAttributes(section.directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != section.identity) throw IOException("Plugin backup directory changed")
        }
    }

    private fun backend(section: Section, check: () -> Unit) = HostTransferBackend({ relative, _ -> if (relative.isEmpty()) section.directory else File(section.directory, relative) }, check)

    private suspend fun boundedVersion(section: Section): TransferVersion {
        val check = checked(section)
        val tree = backend(section, check)
        var total = 0L
        val reader = object : TransferReader by tree {
            override fun stat(relative: String) = tree.stat(relative).also {
                if (relative.isNotEmpty() && !isValidSectionPath(relative) || it?.kind == TransferKind.LINK ||
                    it?.kind == TransferKind.FILE && (it.size == null || it.size !in 0..MAX_ENTRY_BYTES)) throw IOException("Invalid plugin export entry")
            }
            override fun openRead(relative: String): InputStream = object : FilterInputStream(tree.openRead(relative)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    check()
                    val count = super.read(buffer, offset, length)
                    if (count > 0) { total += count; if (total > MAX_TOTAL_BYTES) throw IOException("Plugin export byte limit") }
                    return count.also { check() }
                }
            }
        }
        val version = transferVersion(reader, check) ?: throw IOException("Plugin export directory unavailable")
        if (version.nodes.size > MAX_ENTRIES) throw IOException("Plugin export entry limit")
        return version
    }

    companion object {
        const val PREFIX = "plugins/"
        const val EXPORT_DIR = "backup-export"
        const val IMPORT_DIR = "backup-import"
        const val MAX_ENTRIES = 512
        const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

        fun isValidSectionPath(path: String): Boolean = path.length <= 4096 && path.split('/').size <= 16 &&
            path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.split('/').all { part ->
                part.isNotBlank() && part != "." && part != ".." && ':' !in part && part.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            }

        internal fun copyBounded(input: InputStream, output: java.io.OutputStream?, declared: Long, limit: Long, check: () -> Unit): Long {
            if (declared !in 0..limit) throw IOException("Plugin archive declared size exceeds limit")
            var actual = 0L
            val buffer = ByteArray(32 * 1024)
            while (true) {
                check()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - actual + 1).toInt())
                check()
                if (count < 0) break
                if (count == 0 || count > buffer.size) throw IOException("Invalid plugin archive stream progress")
                actual += count
                if (actual > limit || actual > declared) throw IOException("Plugin archive actual size exceeds limit")
                output?.write(buffer, 0, count)
            }
            if (actual != declared) throw IOException("Plugin archive actual size differs from declaration")
            return actual
        }
    }
}
