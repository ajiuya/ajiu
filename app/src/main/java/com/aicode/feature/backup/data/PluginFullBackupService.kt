package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginOperationGrantRepository
import com.aicode.feature.plugin.data.PluginRegistryStore
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.SemVer
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile

@Singleton
internal class PluginFullBackupService internal constructor(
    private val cacheDirectory: File,
    private val manager: PluginManager,
    private val secrets: PluginSecretStore,
    private val revokeGrants: suspend (String) -> Unit,
    private val closePlugin: suspend (String) -> Boolean
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        manager: PluginManager,
        secrets: PluginSecretStore,
        capabilities: PluginCapabilityRepository,
        operationGrants: PluginOperationGrantRepository,
        runtimes: PluginRuntimeManager
    ) : this(context.cacheDir.canonicalFile, manager, secrets, { id ->
        if (capabilities.revoke(id, null) != PluginOperationResult.Success) throw Rejected("PLUGIN_AUTHORIZATION_REVOKE_FAILED")
        operationGrants.revoke(id)
    }, { id -> runtimes.closePluginAndAwait(id) })

    private data class ContentFile(val path: String, val bytes: Long, val sha256: String)
    private data class Tree(val exists: Boolean, val directories: List<String>, val files: List<ContentFile>)
    private class Rejected(val code: String) : IOException(code)
    private val operations = Mutex()
    private val ownedStages = hashSetOf<File>()

    suspend fun prepareExport(): List<PreparedBackupContent> = withContext(Dispatchers.IO) {
        operations.withLock {
            val result = arrayListOf<PreparedBackupContent>()
            var pending: File? = null
            try {
                val snapshots = manager.stableInstallSnapshots()
                snapshots.forEach { source ->
                    currentCoroutineContext().ensureActive()
                    source.checkCurrent()
                    val stage = createStage().also { pending = it }
                    writePackage(source, File(stage, PACKAGE))
                    val data = File(stage, DATA).also { Files.createDirectory(it.toPath()) }
                    copyTree(source.dataDirectory, data, excludeManaged = true)
                    val values = secrets.exportForBackup(source)
                    Files.newOutputStream(File(stage, SECRETS).toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
                        it.write(PluginSecretStore.encodeBackup(values))
                    }
                    source.checkCurrent()
                    val meta = BackupContentArchive.metadata(UUID.randomUUID().toString(), BackupContentKind.PLUGIN,
                        BackupContentLocation.PLUGIN, source.manifest.name.resolve(Locale.getDefault().toLanguageTag()), stage).copy(
                        sourceLabel = source.record.version,
                        pluginId = source.record.id,
                        pluginVersion = source.record.version,
                        pluginContentHash = source.record.contentHash
                    )
                    result += PreparedBackupContent(meta, stage, source.checkCurrent)
                    pending = null
                }
                require(result.sumOf { it.metadata.bytes } <= BackupContentArchive.MAX_TOTAL_BYTES) { "plugin backup total limit" }
                snapshots.forEach { it.checkCurrent() }
                result
            } catch (error: Exception) {
                pending?.let(::cleanupStage)
                cleanup(result)
                throw error
            }
        }
    }

    fun cleanup(sections: Collection<PreparedBackupContent>) {
        sections.forEach { cleanupStage(it.directory) }
    }

    suspend fun restore(meta: BackupContentSectionMeta, directory: File, overwrite: Boolean): BackupContentRestoreResult = withContext(Dispatchers.IO) {
        operations.withLock {
            var stage: File? = null
            var dataStage: File? = null
            var previewToken: String? = null
            var packageCommitted = false
            var dataCommitted = false
            var secretsCommitted = false
            var dataFiles = 0
            fun result(status: BackupContentRestoreStatus, code: String? = null) = BackupContentRestoreResult(
                meta.id, meta.kind, meta.label, status,
                restoredFiles = if (packageCommitted) 1 + (if (dataCommitted) dataFiles else 0) + (if (secretsCommitted) 1 else 0) else 0,
                skippedFiles = if (status == BackupContentRestoreStatus.CONFLICT) meta.fileCount else 0,
                conflicts = if (status == BackupContentRestoreStatus.CONFLICT) 1 else 0,
                code = code
            )
            try {
                validateMeta(meta)
                manager.ensureLoaded()
                if (manager.snapshot.value.errors.isNotEmpty()) throw Rejected("PLUGIN_REGISTRY_UNAVAILABLE")
                val existing = manager.plugins.value.firstOrNull { it.record.id == meta.pluginId }
                if (existing != null && !overwrite) return@withLock result(BackupContentRestoreStatus.CONFLICT, "PLUGIN_ALREADY_INSTALLED")
                val ownStage = createStage().also { stage = it }
                validatePayload(directory)
                val packageFile = File(ownStage, PACKAGE)
                copyFile(File(directory, PACKAGE), packageFile, PluginManager.BACKUP_MAX_ARCHIVE_BYTES)
                copyFile(File(directory, SECRETS), File(ownStage, SECRETS), PluginSecretStore.MAX_BACKUP_BYTES.toLong())
                val data = File(ownStage, DATA).also { Files.createDirectory(it.toPath()) }
                val dataTree = copyTree(File(directory, DATA), data, excludeManaged = false)
                require(dataTree.exists && dataTree.directories.none(::managedDirectory)) { "host-managed plugin data in backup" }
                dataFiles = dataTree.files.size
                val counted = BackupContentArchive.metadata(meta.id, meta.kind, meta.location, meta.label, ownStage)
                require(counted.fileCount == meta.fileCount && counted.bytes == meta.bytes) { "plugin payload counts changed" }
                val secretValues = PluginSecretStore.decodeBackup(PluginFiles.readRegular(ownStage, SECRETS, PluginSecretStore.MAX_BACKUP_BYTES))
                val preparedSecrets = secrets.prepareBackupRestore(secretValues)
                validateZipTypes(packageFile)
                val preview = Files.newInputStream(packageFile.toPath(), NOFOLLOW_LINKS).use {
                    when (val value = manager.previewForBackup(it)) {
                        is PluginPreviewResult.Success -> value.preview
                        is PluginPreviewResult.Failure -> throw Rejected("PLUGIN_PACKAGE_INVALID")
                    }
                }
                previewToken = preview.token
                require(preview.manifest.id == meta.pluginId && preview.manifest.version.toString() == meta.pluginVersion &&
                    preview.contentHash == meta.pluginContentHash) { "plugin package identity mismatch" }
                if (!overwrite && preview.previousRevision != null) return@withLock result(BackupContentRestoreStatus.CONFLICT, "PLUGIN_ALREADY_INSTALLED")
                if (preview.previousRevision != existing?.record?.installRevision) throw Rejected("PLUGIN_SOURCE_CHANGED")
                val preparedData = manager.createBackupDataStage().also { dataStage = it }
                copyTree(data, preparedData, excludeManaged = false)
                currentCoroutineContext().ensureActive()
                manager.withBackupRestoreFence(preview.manifest.id, preview.previousRevision) { fence ->
                    if (!closePlugin(fence.id)) throw Rejected("PLUGIN_RUNTIME_BUSY")
                    fence.checkCurrent()
                    revokeGrants(fence.id)
                    if (snapshotTree(preparedData, false) != dataTree) throw Rejected("PLUGIN_DATA_INVALID")
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        when (manager.installForBackup(preview.token, fence)) {
                            is PluginInstallResult.Failure -> throw Rejected("PLUGIN_PACKAGE_INSTALL_FAILED")
                            is PluginInstallResult.Success -> packageCommitted = true
                        }
                        fence.checkCurrent()
                        if (snapshotTree(preparedData, false) != dataTree) throw Rejected("PLUGIN_PACKAGE_RESTORED_DATA_INVALID")
                        commitData(fence, preparedData)
                        dataCommitted = true
                        secrets.restoreFromBackup(fence.snapshot(), preparedSecrets)
                        secretsCommitted = true
                    }
                }
                result(BackupContentRestoreStatus.RESTORED).copy(restoredFiles = meta.fileCount)
            } catch (error: Exception) {
                if (error is CancellationException && !packageCommitted) throw error
                if (!packageCommitted && !overwrite && manager.plugins.value.any { it.record.id == meta.pluginId }) {
                    return@withLock result(BackupContentRestoreStatus.CONFLICT, "PLUGIN_ALREADY_INSTALLED")
                }
                if (secretsCommitted && dataCommitted && packageCommitted) return@withLock result(BackupContentRestoreStatus.RESTORED)
                val code = when {
                    error is Rejected -> error.code
                    error is PluginManager.BackupSourceChangedException -> "PLUGIN_SOURCE_CHANGED"
                    packageCommitted && !dataCommitted -> "PLUGIN_PACKAGE_RESTORED_DATA_FAILED"
                    packageCommitted -> "PLUGIN_PACKAGE_DATA_RESTORED_SECRETS_FAILED"
                    error is IllegalArgumentException -> "PLUGIN_BACKUP_INVALID"
                    else -> "PLUGIN_BACKUP_FAILED"
                }
                result(if (packageCommitted) BackupContentRestoreStatus.PARTIAL else BackupContentRestoreStatus.FAILED, code)
            } finally {
                withContext(NonCancellable) {
                    previewToken?.let { runCatching { manager.discardPreview(it) } }
                    dataStage?.let { runCatching { PluginFiles.deleteOwned(it) } }
                    stage?.let(::cleanupStage)
                }
            }
        }
    }

    private fun validateMeta(meta: BackupContentSectionMeta) {
        BackupContentArchive.validateMetadata(listOf(meta))
        require(meta.kind == BackupContentKind.PLUGIN && meta.location == BackupContentLocation.PLUGIN &&
            meta.pluginId?.let(PluginManifestParser::isValidPluginId) == true &&
            meta.pluginVersion?.let(SemVer::parse) != null && meta.pluginContentHash?.let(PluginRegistryStore::isHash) == true)
    }

    private fun validatePayload(directory: File) {
        PluginFiles.checkDirectoryChain(directory.absoluteFile.toPath().normalize())
        val children = directory.listFiles() ?: throw IOException("plugin payload unavailable")
        require(children.map { it.name }.toSet() == setOf(PACKAGE, DATA, SECRETS) && children.size == 3) { "invalid plugin payload" }
        regular(File(directory, PACKAGE), PluginManager.BACKUP_MAX_ARCHIVE_BYTES)
        regular(File(directory, SECRETS), PluginSecretStore.MAX_BACKUP_BYTES.toLong())
        PluginFiles.checkDirectoryChain(File(directory, DATA).absoluteFile.toPath().normalize())
    }

    private fun createStage(): File {
        PluginFiles.checkDirectoryChain(cacheDirectory.absoluteFile.toPath().normalize())
        val directory = File(cacheDirectory, "plugin-full-backup-${UUID.randomUUID()}")
        Files.createDirectory(directory.toPath())
        check(directory.canonicalFile == directory.absoluteFile) { "unsafe plugin backup staging" }
        synchronized(ownedStages) { check(ownedStages.add(directory.absoluteFile)) }
        return directory
    }

    private fun cleanupStage(directory: File) {
        if (!synchronized(ownedStages) { directory.absoluteFile in ownedStages }) return
        if (runCatching { PluginFiles.deleteOwned(directory) }.isSuccess) {
            synchronized(ownedStages) { ownedStages.remove(directory.absoluteFile) }
        }
    }

    private fun writePackage(source: PluginManager.InstallSnapshot, destination: File) {
        source.checkCurrent()
        Files.newOutputStream(destination.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            ZipOutputStream(LimitedOutput(output, PluginManager.BACKUP_MAX_ARCHIVE_BYTES)).use { zip ->
                source.record.fileHashes.toSortedMap().forEach { (path, expectedHash) ->
                    val file = PluginFiles.checkedFile(source.installDirectory, path)
                    val attributes = regular(file, 4L * 1024 * 1024)
                    zip.putNextEntry(ZipEntry(path))
                    val copied = copyRegular(file, zip, attributes.size())
                    require(copied.sha256 == expectedHash) { "plugin backup source changed" }
                    zip.closeEntry()
                }
            }
        }
        source.checkCurrent()
    }

    private fun validateZipTypes(file: File) {
        ZipFile(file).use { zip ->
            for (entry in zip.entries) {
                val type = entry.unixMode and 0xf000
                require(!entry.isUnixSymlink && (type == 0 || type == 0x8000 && !entry.isDirectory || type == 0x4000 && entry.isDirectory)) {
                    "special plugin package entry"
                }
            }
        }
    }

    private fun snapshotTree(root: File, excludeManaged: Boolean): Tree {
        PluginFiles.checkDirectoryChain(root.absoluteFile.parentFile.toPath().normalize())
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return Tree(false, emptyList(), emptyList())
        PluginFiles.checkDirectoryChain(root.absoluteFile.toPath().normalize())
        val directories = arrayListOf<String>()
        val files = arrayListOf<ContentFile>()
        var total = 0L
        fun visit(directory: File, prefix: String) {
            PluginFiles.checkDirectoryChain(directory.absoluteFile.toPath().normalize())
            val children = directory.listFiles()?.sortedBy { it.name } ?: throw IOException("plugin data unavailable")
            children.forEach { child ->
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                require(validDataPath(path)) { "unsafe plugin data path" }
                val attrs = Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attrs.isDirectory || attrs.isRegularFile) { "special plugin data entry" }
                if (attrs.isDirectory && excludeManaged && managedDirectory(path)) return@forEach
                require(directories.size + files.size < BackupContentArchive.MAX_ENTRIES - 3) { "plugin data entries limit" }
                if (attrs.isDirectory) {
                    directories += path
                    visit(child, path)
                } else {
                    val stamp = copyRegular(child, null, BackupContentArchive.MAX_FILE_BYTES).copy(path = path)
                    require(total <= BackupContentArchive.MAX_SECTION_BYTES - stamp.bytes) { "plugin data bytes limit" }
                    total += stamp.bytes
                    files += stamp
                }
            }
        }
        visit(root, "")
        return Tree(true, directories.sorted(), files.sortedBy { it.path })
    }

    private fun copyTree(source: File, target: File, excludeManaged: Boolean): Tree {
        val tree = snapshotTree(source, excludeManaged)
        PluginFiles.checkDirectoryChain(target.absoluteFile.toPath().normalize())
        tree.directories.forEach { path -> Files.createDirectory(dataPath(target, path, directory = true).toPath()) }
        tree.files.forEach { expected ->
            val from = dataPath(source, expected.path)
            val to = dataPath(target, expected.path)
            val actual = Files.newOutputStream(to.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
                copyRegular(from, it, expected.bytes)
            }
            require(actual.bytes == expected.bytes && actual.sha256 == expected.sha256) { "plugin data source changed" }
        }
        require(snapshotTree(source, excludeManaged) == tree) { "plugin data source changed" }
        return tree
    }

    private fun dataPath(root: File, relative: String, directory: Boolean = false): File {
        require(validDataPath(relative)) { "unsafe plugin data path" }
        val target = File(root, relative)
        PluginFiles.checkDirectoryChain(target.absoluteFile.parentFile.toPath().normalize())
        require(target.toPath().normalize().startsWith(root.toPath().normalize()) && !Files.isSymbolicLink(target.toPath())) { "unsafe plugin data target" }
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            require(if (directory) Files.isDirectory(target.toPath(), NOFOLLOW_LINKS) else Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS))
        }
        return target
    }

    private fun copyFile(source: File, target: File, maximum: Long) {
        Files.newOutputStream(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { copyRegular(source, it, maximum) }
    }

    private fun regular(file: File, maximum: Long): BasicFileAttributes {
        PluginFiles.checkDirectoryChain(file.absoluteFile.parentFile.toPath().normalize())
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() in 0..maximum) { "unsafe plugin backup file" }
        val links = try { Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number }
        catch (_: UnsupportedOperationException) { null }
        catch (_: IllegalArgumentException) { null }
        require(links == null || links.toLong() == 1L) { "hardlinked plugin backup file" }
        return attrs
    }

    private fun copyRegular(source: File, output: OutputStream?, maximum: Long): ContentFile {
        val before = regular(source, maximum)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input -> copy(input, output, before.size(), digest) }
        val after = regular(source, maximum)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime()) { "plugin backup source changed" }
        val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return ContentFile(source.name, bytes, hash)
    }

    private fun copy(input: InputStream, output: OutputStream?, expected: Long, digest: MessageDigest): Long {
        val buffer = ByteArray(32 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && bytes <= expected - count) { "plugin backup file changed" }
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            bytes += count
        }
        require(bytes == expected) { "plugin backup file changed" }
        return bytes
    }

    private fun commitData(fence: PluginManager.BackupRestoreFence, prepared: File) {
        fence.checkCurrent()
        val target = fence.dataDirectory
        PluginFiles.checkDirectoryChain(target.absoluteFile.parentFile.toPath().normalize())
        val previous = File(target.parentFile, ".backup-previous-${UUID.randomUUID()}")
        var displaced = false
        try {
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                PluginFiles.checkDirectoryChain(target.absoluteFile.toPath().normalize())
                Files.move(target.toPath(), previous.toPath(), ATOMIC_MOVE)
                displaced = true
            }
            fence.checkCurrent()
            Files.move(prepared.toPath(), target.toPath(), ATOMIC_MOVE)
        } catch (error: Exception) {
            if (displaced) {
                val returned = runCatching {
                    require(!Files.exists(target.toPath(), NOFOLLOW_LINKS))
                    Files.move(previous.toPath(), target.toPath(), ATOMIC_MOVE)
                }.isSuccess
                if (!returned) throw Rejected("PLUGIN_PACKAGE_RESTORED_OLD_DATA_RETAINED")
            }
            throw Rejected("PLUGIN_PACKAGE_RESTORED_DATA_FAILED")
        }
        if (displaced) runCatching { PluginFiles.deleteOwned(previous) }
    }

    private class LimitedOutput(private val delegate: OutputStream, private val maximum: Long) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) { require(count < maximum); delegate.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count <= maximum - length)
            delegate.write(bytes, offset, length)
            count += length
        }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    internal companion object {
        private const val PACKAGE = "package.zip"
        private const val DATA = "data"
        private const val SECRETS = "secrets.json"
        internal fun validDataPath(path: String): Boolean = path.length <= 4096 && path.split('/').let { parts ->
            parts.size <= 64 && parts.all(BackupContentArchive::validPart)
        }
        private fun managedDirectory(path: String): Boolean = path == "backup-import" || path == "backup-export" ||
            path.split('/').let { it.size == 2 && it[0] == "dependencies" && PluginRegistryStore.isRevision(it[1]) }
    }
}
