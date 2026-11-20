package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.BackupOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupContentService @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val files: BackupFileContentService,
    private val plugins: PluginFullBackupService
) {
    internal class Export(val sections: List<PreparedBackupContent>)
    internal class Import(val sections: BackupContentArchive.ImportSections)

    internal suspend fun prepareExport(options: BackupOptions): Export {
        val prepared = arrayListOf<PreparedBackupContent>()
        try {
            prepared += files.prepareExport(options)
            if (options.plugins) prepared += plugins.prepareExport()
            BackupContentArchive.validateMetadata(prepared.map { it.metadata })
            return Export(prepared)
        } catch (failure: Throwable) {
            cleanupExport(Export(prepared))
            throw failure
        }
    }

    internal fun writeExport(tar: TarArchiveOutputStream, export: Export) {
        export.sections.forEach { BackupContentArchive.writeExport(tar, it) }
    }

    internal fun cleanupExport(export: Export?) {
        if (export == null) return
        files.cleanup(export.sections.filter { it.metadata.kind != BackupContentKind.PLUGIN })
        plugins.cleanup(export.sections.filter { it.metadata.kind == BackupContentKind.PLUGIN })
    }

    suspend fun currentRemoteTarget(): BackupContentRestoreTarget? = files.currentRemoteTarget()

    internal fun validateMetadata(version: Int, metadata: List<BackupContentSectionMeta>) {
        require(version == BackupContentArchive.FORMAT_VERSION)
        BackupContentArchive.validateMetadata(metadata)
    }

    internal fun prepareImport(metadata: List<BackupContentSectionMeta>, selection: BackupContentRestoreSelection): Import {
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "backup-content-import-").toFile()
        try {
            return Import(BackupContentArchive.prepareImport(metadata, selection, root))
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    internal fun readImport(tar: TarArchiveInputStream, entry: TarArchiveEntry, batch: Import) =
        BackupContentArchive.readImport(tar, entry, batch.sections)

    internal fun validateImport(batch: Import) = BackupContentArchive.finishImport(batch.sections)

    internal suspend fun finishImport(batch: Import): List<BackupContentRestoreResult> {
        return batch.sections.metadata.map { metadata ->
            if (metadata.id !in batch.sections.selection.sectionIds) {
                BackupContentRestoreResult(metadata.id, metadata.kind, metadata.label, BackupContentRestoreStatus.NOT_SELECTED)
            } else {
                val directory = checkNotNull(batch.sections.directories[metadata.id])
                try {
                    if (metadata.kind == BackupContentKind.PLUGIN) {
                        plugins.restore(metadata, directory, batch.sections.selection.overwrite)
                    } else {
                        files.restore(metadata, directory, batch.sections.selection)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BackupContentRestoreResult(metadata.id, metadata.kind, metadata.label, BackupContentRestoreStatus.FAILED, code = "CONTENT_RESTORE_FAILED")
                }
            }
        }
    }

    internal fun cleanupImport(batch: Import?) {
        batch?.sections?.root?.deleteRecursively()
    }
}
