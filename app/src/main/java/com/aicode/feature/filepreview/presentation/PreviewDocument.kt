package com.aicode.feature.filepreview.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.filepreview.data.PreviewSource
import com.aicode.feature.filepreview.domain.PreviewArchive
import com.aicode.feature.filepreview.domain.PreviewKind
import com.aicode.feature.filepreview.domain.PreviewTable
import com.aicode.feature.filepreview.domain.PreviewText
import com.aicode.feature.filepreview.domain.PreviewTypes
import com.aicode.feature.filepreview.domain.listPreviewArchive
import com.aicode.feature.filepreview.domain.parsePreviewTable
import com.aicode.feature.filepreview.domain.prettyPreviewJson
import com.aicode.feature.filepreview.domain.readPreviewText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface DocumentContent {
    data object Loading : DocumentContent
    data object Failed : DocumentContent
    data object Information : DocumentContent
    data class Textual(val text: PreviewText) : DocumentContent
    data class Table(val table: PreviewTable) : DocumentContent
    data class Archive(val archive: PreviewArchive) : DocumentContent
    data class Local(val file: File) : DocumentContent
    data class Picture(val bitmap: ImageBitmap) : DocumentContent
    data class Package(val info: PreviewPackageInfo?) : DocumentContent
}

private data class PreviewPackageInfo(val label: String, val packageName: String, val version: String, val minSdk: Int, val icon: ImageBitmap?)

@Composable
internal fun PreviewDocument(
    source: PreviewSource,
    path: String,
    kind: PreviewKind,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onError: () -> Unit,
    mimeType: String = "",
) {
    val context = LocalContext.current
    val content by produceState<DocumentContent>(DocumentContent.Loading, source, path, kind) {
        value = try {
            withContext(Dispatchers.IO) {
                source.checkCurrent()
                when (kind) {
                    PreviewKind.TEXT, PreviewKind.JSON -> source.openRead(path).use { input ->
                        val text = readPreviewText(input)
                        DocumentContent.Textual(if (kind == PreviewKind.JSON) prettyPreviewJson(text) else text)
                    }
                    PreviewKind.TABLE -> source.openRead(path).use { input ->
                        DocumentContent.Table(parsePreviewTable(readPreviewText(input), if (path.substringBefore('#').endsWith(".tsv", true)) '\t' else ','))
                    }
                    PreviewKind.ARCHIVE -> DocumentContent.Archive(listPreviewArchive(source.localFile(path)))
                    PreviewKind.PDF -> DocumentContent.Local(source.localFile(path))
                    PreviewKind.IMAGE -> decodeSampledBitmap(source.localFile(path).absolutePath, 2048, 4L * 1024 * 1024)
                        ?.let(DocumentContent::Picture) ?: DocumentContent.Failed
                    PreviewKind.APK -> DocumentContent.Package(readPackageInfo(context, source.localFile(path)))
                    else -> DocumentContent.Information
                }.also { source.checkCurrent() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DocumentContent.Failed }
    }
    when (val document = content) {
        DocumentContent.Loading -> PreviewLoading(modifier)
        DocumentContent.Failed -> PreviewFailure(modifier)
        DocumentContent.Information -> Column(modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PreviewMetadata(stringResource(R.string.file_preview_file_name), path.substringBefore('#').substringAfterLast('/'))
            PreviewMetadata(stringResource(R.string.file_preview_file_type), PreviewTypes.mime(path, mimeType))
            PreviewMetadata(stringResource(R.string.file_preview_file_path), path)
            if (path == source.entryPath) PreviewMetadata(stringResource(R.string.file_preview_file_size), previewSize(source.size))
            PreviewNotice(if (kind == PreviewKind.OFFICE) R.string.file_preview_office_notice else R.string.file_preview_binary_notice)
        }
        is DocumentContent.Textual -> Column(modifier.fillMaxSize()) {
            if (document.text.truncated) PreviewNotice(R.string.file_preview_truncated)
            val lines = remember(document.text) { previewTextRows(document.text.text) }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    items(lines.size) { index ->
                        Text(lines[index].ifEmpty { " " }, Modifier.padding(horizontal = Spacing.lg),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = false)
                    }
                }
            }
        }
        is DocumentContent.Table -> Column(modifier.fillMaxSize()) {
            if (document.table.truncated) PreviewNotice(R.string.file_preview_truncated)
            if (document.table.rows.isEmpty()) PreviewNotice(R.string.file_preview_table_empty)
            LazyColumn(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                items(document.table.rows.size) { index ->
                    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        document.table.rows[index].forEach { cell ->
                            Text(cell, Modifier.width(180.dp), style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        is DocumentContent.Archive -> Column(modifier.fillMaxSize()) {
            PreviewNotice(R.string.file_preview_archive_notice)
            if (document.archive.truncated) PreviewNotice(R.string.file_preview_truncated)
            if (document.archive.entries.isEmpty()) PreviewNotice(R.string.file_preview_archive_empty)
            LazyColumn(Modifier.weight(1f)) {
                items(document.archive.entries) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                        if (!entry.directory && entry.size >= 0) Text(previewSize(entry.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
        is DocumentContent.Picture -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(document.bitmap, path.substringAfterLast('/'), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        is DocumentContent.Local -> PreviewPdf(document.file, modifier, onError)
        is DocumentContent.Package -> Column(modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            document.info?.let { info ->
                info.icon?.let { Image(it, info.label, Modifier.size(64.dp)) }
                Text(info.label, style = MaterialTheme.typography.titleLarge)
                PreviewMetadata(stringResource(R.string.file_preview_package_name), info.packageName)
                PreviewMetadata(stringResource(R.string.file_preview_package_version), info.version)
                PreviewMetadata(stringResource(R.string.file_preview_package_sdk), info.minSdk.toString())
                TextButton(onClick = onInstall) { Text(stringResource(R.string.file_preview_install)) }
            } ?: PreviewFailure()
        }
    }
}

@Composable
internal fun PreviewLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.file_preview_loading)) }
}

@Composable
internal fun PreviewFailure(modifier: Modifier = Modifier, environmentChanged: Boolean = false) {
    Box(modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(stringResource(if (environmentChanged) R.string.file_preview_environment_changed else R.string.file_preview_error),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PreviewNotice(resource: Int) {
    Text(stringResource(resource), Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PreviewMetadata(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun previewTextRows(text: String): List<String> = buildList {
    for (line in text.split('\n')) {
        if (line.isEmpty()) add("")
        var start = 0
        while (start < line.length) {
            var end = minOf(start + 4096, line.length)
            if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
            add(line.substring(start, end))
            start = end
        }
    }
}

internal fun previewSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun readPackageInfo(context: Context, file: File): PreviewPackageInfo? {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
    val application = info.applicationInfo ?: return null
    application.sourceDir = file.absolutePath
    application.publicSourceDir = file.absolutePath
    val icon = runCatching {
        val drawable = application.loadIcon(context.packageManager)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 128, 128)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return PreviewPackageInfo(application.loadLabel(context.packageManager).toString(), info.packageName,
        "${info.versionName.orEmpty()} ($versionCode)", application.minSdkVersion, icon)
}
