package com.aicode.feature.workspace.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import javax.inject.Inject

internal data class UploadFileMetadata(val name: String, val size: Long?)

internal data class FileUploadInput(val source: String, val name: String, val reader: TransferReader, val physicalSource: String? = null)

class FileUploadInputs @Inject constructor(@ApplicationContext private val context: Context) {
    internal fun files(uris: List<Uri>, check: () -> Unit): List<FileUploadInput> {
        check()
        if (uris.isEmpty() || uris.size > 128) throw IOException("Select between 1 and 128 upload files")
        val selected = uris.map { uri ->
            if (uri.scheme != "content") throw IOException("Content document URI required")
            val metadata = metadata(uri, check)
            FileUploadInput(uri.toString(), metadata.name, object : TransferReader {
                override fun stat(relative: String): TransferStat? {
                    if (relative.isNotEmpty()) throw IOException("Upload file has no children")
                    val current = metadata(uri, check)
                    if (current.name != metadata.name) throw IOException("Upload document display name changed")
                    return TransferStat(TransferKind.FILE, current.size, "", uri.toString())
                }
                override fun openRead(relative: String): InputStream {
                    if (relative.isNotEmpty()) throw IOException("Upload file has no children")
                    check()
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open upload document")
                    val checked = identityCheckedRead(input, check)
                    return WorkspaceExecutionContext.current()?.protectRead(checked) ?: checked
                }
                override fun list(relative: String): List<String> = throw IOException("Upload file is not a directory")
                override fun readLink(relative: String): String = throw IOException("Upload file is not a symbolic link")
            })
        }
        validateUploadMetadata(selected.map { UploadFileMetadata(it.name, null) })
        check()
        return selected
    }

    internal fun directory(uri: Uri, check: () -> Unit): FileUploadInput {
        check()
        if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") {
            throw IOException("This directory provider has no accessible local path")
        }
        val path = UriPathResolver.toFilePath(context, uri) ?: throw IOException("This directory provider has no accessible local path")
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: SecurityException) {
            throw IOException("Persistent directory read permission is required", failure)
        }
        fun checkPermission() {
            check()
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                throw IOException("Directory read permission is no longer available")
            }
        }
        checkPermission()
        val root = File(path).toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isReadable(root)) throw IOException("Selected directory is not directly readable")
        val canonical = root.toRealPath()
        val name = canonical.fileName?.toString() ?: throw IOException("Selected directory requires a name")
        if (!validTransferName(name)) throw IOException("Invalid upload directory name")
        val reader = HostTransferBackend({ relative, _ ->
            checkPermission()
            if (root.toRealPath() != canonical) throw IOException("Selected upload directory changed")
            val child = if (relative.isEmpty()) canonical else canonical.resolve(transferRelative(relative))
            val parent = child.parent ?: throw IOException("Upload path requires a parent")
            if (child != canonical && !parent.toRealPath().startsWith(canonical)) throw IOException("Upload path outside selected directory")
            child.toFile()
        }, ::checkPermission)
        return FileUploadInput(uri.toString(), name, reader, canonical.toString())
    }

    private fun metadata(uri: Uri, check: () -> Unit): UploadFileMetadata {
        check()
        val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("Upload document metadata is unavailable")
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0 || cursor.isNull(nameIndex)) throw IOException("Upload document display name is unavailable")
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            UploadFileMetadata(cursor.getString(nameIndex), if (sizeIndex < 0 || cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex))
        } ?: throw IOException("Upload document metadata is unavailable")
        validateUploadMetadata(listOf(metadata))
        check()
        return metadata
    }
}

internal fun validateUploadMetadata(metadata: List<UploadFileMetadata>) {
    if (metadata.isEmpty() || metadata.size > 128) throw IOException("Select between 1 and 128 upload files")
    if (metadata.any { !validTransferName(it.name) || (it.size != null && it.size < 0) }) {
        throw IOException("Invalid upload document name or size")
    }
    if (metadata.map { it.name }.toSet().size != metadata.size) throw IOException("Duplicate upload document names")
}
