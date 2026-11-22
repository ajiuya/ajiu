package com.aicode.feature.plugin.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

private data class OpenPluginPage(val capture: PluginCapture, val page: PluginPage)

@Composable
fun PluginsSection(
    showDetail: Boolean,
    onOpenDetail: () -> Unit,
    viewModel: NativePluginViewModel = hiltViewModel()
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val store: PluginStoreViewModel = hiltViewModel()
    val storeState by store.state.collectAsStateWithLifecycle()
    val grants by viewModel.capabilityGrants.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storeBusy = storeState.downloading != null || storeState.preview != null
    val busy = state.busy || storeBusy
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val owner = remember { Any() }
    DisposableEffect(owner, viewModel) {
        viewModel.attach(owner)
        onDispose { viewModel.detach(owner) }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.preview(uri)
    }
    val launchImport = {
        if (!state.busy && store.state.value.downloading == null && store.state.value.preview == null) {
            zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
    }
    var uninstallId by remember { mutableStateOf<String?>(null) }
    var openPage by remember { mutableStateOf<OpenPluginPage?>(null) }
    var pageProblem by remember { mutableStateOf<PluginProblem?>(null) }
    val selected = snapshot.plugins.firstOrNull { it.record.id == state.selectedId }
    val openPluginPage: (String, PluginPage) -> Unit = { id, page ->
        try {
            val capture = viewModel.capture(id)
            if (capture.content.pages.none { it == page }) throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
            pageProblem = null
            openPage = OpenPluginPage(capture, page)
        } catch (failure: PluginCaptureException) { pageProblem = failure.error.toProblem() }
    }
    Column(Modifier.fillMaxSize()) {
        PluginProblems(state.problems + listOfNotNull(pageProblem), Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg))
        state.notice?.let { Text(stringResource(it), modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
        if (showDetail) {
            if (selected == null) PluginProblems(listOf(PluginError(PluginErrorCode.NOT_INSTALLED).toProblem()), Modifier.padding(Spacing.lg))
            else PluginDetailContent(
                plugin = selected,
                languageTag = languageTag,
                busy = busy,
                onEnable = viewModel::prepareTrust,
                onDisable = viewModel::disable,
                onUninstall = { uninstallId = it },
                onReplace = launchImport,
                onOpenPage = openPluginPage,
                onInvokeAction = viewModel::describeAction,
                onPrepareDependencies = viewModel::describeDependencies,
                capabilityContent = {
                    PluginCapabilityRows(selected, grants.keys, busy, viewModel::isCapabilityGranted, viewModel::prepareCapability, viewModel::revokeCapability)
                },
                onClearOperationGrants = { viewModel.clearOperationGrants(selected.record.id) }
            )
        } else PluginManagementTabs(viewModel, store, languageTag, launchImport, {
            viewModel.select(it)
            pageProblem = null
            onOpenDetail()
        }, onOpenPage = openPluginPage)
    }

    storeState.preview?.let { preview ->
        PluginPreviewDialog(preview.preview, languageTag, state.busy || storeState.downloading != null,
            onInstall = { token -> if (!viewModel.state.value.busy) store.install(token) },
            onDismiss = store::dismissPreview)
    }
    state.preview?.let { PluginPreviewDialog(it, languageTag, busy, viewModel::install, viewModel::dismissPreview) }
    state.capability?.let { PluginCapabilityDialog(it, state.busy, viewModel::confirmCapability, viewModel::dismissCapability) }
    state.trust?.let { PluginTrustDialog(it, languageTag, state.busy, viewModel::enable, viewModel::dismissTrust) }
    snapshot.plugins.firstOrNull { it.record.id == uninstallId }?.let { plugin ->
        PluginUninstallDialog(plugin, languageTag, state.busy, { id, deleteData ->
            uninstallId = null
            viewModel.uninstall(id, deleteData)
        }, { uninstallId = null })
    }
    state.dependency?.let { confirmation ->
        PluginDependencyDialog(confirmation.request, confirmation.capture.manifest.name.resolve(languageTag), state.busy,
            viewModel::prepareDependencies, viewModel::dismissDependency)
    }
    state.action?.let { confirmation ->
        LaunchedEffect(confirmation) {
            confirmation.context.changes.collect {
                try { confirmation.context.checkCurrent() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    viewModel.dismissAction()
                    pageProblem = PluginProblem("CONTEXT_CHANGED", R.string.plugin_error_context)
                } catch (_: Exception) {
                    viewModel.dismissAction()
                    pageProblem = PluginProblem("STALE_CAPTURE", R.string.plugin_error_stale)
                }
            }
        }
        PluginActionDialog(confirmation.entry, languageTag, state.busy, { args -> viewModel.invokeAction(confirmation, args) }, viewModel::dismissAction)
    }
    state.actionResult?.let { result ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResult,
            title = { Text(stringResource(R.string.plugin_action_result)) },
            text = { Text(result, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = viewModel::dismissResult) { Text(stringResource(R.string.plugin_close)) } }
        )
    }
    openPage?.let { opened ->
        NativePluginPage(opened.capture, opened.page, languageTag, viewModel::captureContext, viewModel::call, { openPage = null })
    }
}
