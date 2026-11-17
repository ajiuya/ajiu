package com.aicode.feature.filepreview.presentation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.util.ApkInstaller
import com.aicode.feature.filepreview.data.PreviewSource
import com.aicode.feature.filepreview.domain.PreviewKind
import com.aicode.feature.filepreview.domain.PreviewTypes
import com.aicode.feature.player.domain.PlayerMediaTypes
import com.aicode.feature.player.presentation.PlayerActivity
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.MoreVertical
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePreviewHost(
    state: FilePreviewState,
    onEdit: (String) -> Unit,
    viewModel: FilePreviewViewModel = hiltViewModel(),
) {
    val request = state.request ?: return
    LaunchedEffect(request, state.revision) { viewModel.open(request) }
    DisposableEffect(state) { onDispose { viewModel.close() } }
    val observed by viewModel.load.collectAsState()
    val load = when (val current = observed) {
        is PreviewLoad.Ready -> if (current.request == request) current else PreviewLoad.Loading
        is PreviewLoad.Failed -> if (current.request == request) current else PreviewLoad.Loading
        PreviewLoad.Loading -> current
    }
    var paths by remember(request, state.revision) { mutableStateOf(emptyList<String>()) }
    val source = (load as? PreviewLoad.Ready)?.source
    val path = paths.lastOrNull() ?: source?.entryPath ?: request.path
    val title = if (paths.isEmpty()) request.title.ifBlank { path.substringBefore('#').substringAfterLast('/') } else path.substringBefore('#').substringAfterLast('/')
    val mime = PreviewTypes.mime(path, if (paths.isEmpty()) request.mimeType else "")
    val kind = PreviewTypes.kind(path, mime)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    var failed by remember(source, path) { mutableStateOf(false) }
    var actionRunning by remember(source, path) { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    fun toast(resource: Int) { Toast.makeText(context, resource, Toast.LENGTH_SHORT).show() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Destination unavailable")
                }
                toast(R.string.file_preview_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.file_preview_action_failed) }
        }
    }
    val close = { viewModel.close(); state.close() }
    val back = { if (paths.isNotEmpty()) paths = paths.dropLast(1) else close() }
    fun action(block: suspend (PreviewSource) -> Unit) {
        val current = source ?: return
        if (actionRunning) return
        actionRunning = true
        scope.launch {
            try { block(current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.file_preview_action_failed) }
            finally { actionRunning = false }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        BackHandler(onBack = back)
        Scaffold(Modifier.fillMaxSize().systemBarsPadding(), topBar = {
            TopAppBar(title = {
                Column {
                    Text(title.ifBlank { stringResource(R.string.file_preview_title) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(mime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                IconButton(onClick = back) { Icon(FeatherIcons.ArrowLeft, stringResource(R.string.common_back)) }
            }, actions = {
                Box {
                    IconButton(onClick = { menu = true }, enabled = !actionRunning) {
                        Icon(FeatherIcons.MoreVertical, stringResource(R.string.file_preview_title))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_preview_share)) }, enabled = source != null, onClick = {
                            menu = false
                            action { current ->
                                val file = withContext(Dispatchers.IO) { sharedFile(context.cacheDir, current, path, title) }
                                withContext(Dispatchers.IO) { current.checkCurrent() }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_preview_save_as)) }, enabled = source != null, onClick = {
                            menu = false
                            action { current ->
                                pendingSave = withContext(Dispatchers.IO) { sharedFile(context.cacheDir, current, path, title) }
                                withContext(Dispatchers.IO) { current.checkCurrent() }
                                save.launch(title)
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_preview_open_external)) }, enabled = source != null, onClick = {
                            menu = false
                            action { current ->
                                val file = withContext(Dispatchers.IO) { sharedFile(context.cacheDir, current, path, title) }
                                withContext(Dispatchers.IO) { current.checkCurrent() }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        })
                        if (kind in setOf(PreviewKind.MARKDOWN, PreviewKind.HTML, PreviewKind.TEXT, PreviewKind.JSON, PreviewKind.TABLE)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menu = false; close(); onEdit(path) })
                        }
                        if (request.path.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.file_preview_reload)) }, onClick = { menu = false; reload = true })
                    }
                }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (source != null && !source.relatedFilesAvailable) {
                    PreviewNotice(R.string.file_preview_cached)
                    if (request.path.isNotBlank()) TextButton(onClick = { reload = true }) { Text(stringResource(R.string.file_preview_reload)) }
                }
                if (kind == PreviewKind.HTML) PreviewNotice(R.string.file_preview_local_only)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = load) {
                        PreviewLoad.Loading -> PreviewLoading()
                        is PreviewLoad.Failed -> Column {
                            PreviewFailure(Modifier.weight(1f), current.environmentChanged)
                            if (request.path.isNotBlank()) TextButton(onClick = { reload = true }) { Text(stringResource(R.string.file_preview_reload)) }
                        }
                        is PreviewLoad.Ready -> if (failed) PreviewFailure() else key(current.source, path) {
                            if (kind == PreviewKind.MEDIA) {
                                Column(Modifier.padding(Spacing.lg)) {
                                    PreviewMetadata(stringResource(R.string.file_preview_file_name), title)
                                    TextButton(onClick = { PlayerActivity.open(context, PlayerMediaTypes.request(path, title, mime)) }) {
                                        Text(stringResource(R.string.file_preview_play))
                                    }
                                }
                            } else if (kind == PreviewKind.HTML || kind == PreviewKind.MARKDOWN) {
                                PreviewWebView(current.source, path, kind == PreviewKind.MARKDOWN, Modifier.fillMaxSize(),
                                    onLocalLink = { next -> if (next != path) paths = paths + next },
                                    onExternalLink = { externalLink = it }, onError = { failed = true }, mimeType = mime)
                            } else PreviewDocument(current.source, path, kind, Modifier.fillMaxSize(), onInstall = {
                                action { input ->
                                    val installable = withContext(Dispatchers.IO) { sharedFile(context.cacheDir, input, path, title) }
                                    withContext(Dispatchers.IO) { input.checkCurrent() }
                                    when (ApkInstaller.install(context, installable)) {
                                        ApkInstaller.Result.STARTED -> Unit
                                        ApkInstaller.Result.NEED_PERMISSION -> {
                                            toast(R.string.chat_open_apk_permission)
                                            ApkInstaller.openInstallPermissionSettings(context)
                                        }
                                        ApkInstaller.Result.NO_HANDLER -> toast(R.string.chat_open_file_no_app)
                                    }
                                }
                            }, onError = { failed = true }, mimeType = mime)
                        }
                    }
                }
                HorizontalDivider()
                Text(if (paths.isEmpty()) source?.displayPath ?: request.path else path, Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text(stringResource(R.string.file_preview_reload_title)) },
            text = { Text(stringResource(R.string.file_preview_reload_message, request.path)) },
            confirmButton = { TextButton(onClick = { reload = false; state.open(request.copy(cachedPath = "", recordedLocalPath = "")) }) { Text(stringResource(R.string.file_preview_reload)) } },
            dismissButton = { TextButton(onClick = { reload = false }) { Text(stringResource(R.string.common_cancel)) } })
        externalLink?.let { url ->
            AlertDialog(onDismissRequest = { externalLink = null }, title = { Text(stringResource(R.string.file_preview_open_web_title)) },
                text = { Text(stringResource(R.string.file_preview_open_web_message, url)) },
                confirmButton = { TextButton(onClick = {
                    externalLink = null
                    val parsed = Uri.parse(url)
                    if (parsed.scheme in setOf("http", "https")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
                        .onFailure { toast(R.string.common_open_link_failed) }
                }) { Text(stringResource(R.string.file_preview_open_web)) } },
                dismissButton = { TextButton(onClick = { externalLink = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
}

private fun sharedFile(cache: File, source: PreviewSource, path: String, displayName: String): File {
    val root = File(cache, "shared_previews").apply { mkdirs() }
    root.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000 }?.forEach { it.deleteRecursively() }
    val name = displayName.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }
        .take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
    val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    val target = File(folder, name)
    try {
        source.openRead(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        source.checkCurrent()
        return target
    } catch (failure: Throwable) {
        target.delete()
        folder.delete()
        throw failure
    }
}
