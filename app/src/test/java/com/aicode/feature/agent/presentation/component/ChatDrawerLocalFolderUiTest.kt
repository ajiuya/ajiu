package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.ChatSearchState
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.workspace.domain.FileEntry
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ChatDrawerLocalFolderUiTest {
    @Test(timeout = 30_000)
    fun localRootHasNoDestructiveActionsAndDetachesOnlyAfterTheSafetyConfirmation() = render {
        openMenu(LOCAL_LABEL)
        val menu = dialogRoot()
        assertNoRootMutationActions(menu)
        assertEnabled(textAction(menu, text(R.string.file_browser_remove_local_folder)))
        clickText(menu, text(R.string.file_browser_remove_local_folder))

        val confirmation = text(R.string.file_browser_remove_local_folder_confirm, LOCAL_LABEL)
        waitFor("Unlink must explain that files will not be deleted") { dialogHasText(confirmation) }
        assertTrue(removedGrants.isEmpty())
        assertTrue(mutations.isEmpty())
        clickText(dialogRoot(), text(R.string.file_browser_remove_local_folder))
        assertEquals(listOf(GRANT_ID), removedGrants)
        assertTrue(mutations.isEmpty())
    }

    @Test(timeout = 30_000)
    fun cancellingUnlinkKeepsTheGrantAndNeverDeletesTheFolder() = render {
        openMenu(LOCAL_LABEL)
        clickText(dialogRoot(), text(R.string.file_browser_remove_local_folder))
        waitFor("Unlink confirmation must be visible") {
            dialogHasText(text(R.string.file_browser_remove_local_folder_confirm, LOCAL_LABEL))
        }
        clickText(dialogRoot(), text(R.string.common_cancel))
        assertTrue(removedGrants.isEmpty())
        assertTrue(mutations.isEmpty())
        assertTrue(hostHasText(LOCAL_LABEL))
    }

    @Test(timeout = 30_000)
    fun workspaceAndRemoteContainerRootsNeverExposeRootMutationOrUnlinkActions() = render {
        for (label in listOf("workspace", REMOTE_LABEL)) {
            openMenu(label)
            assertNoRootMutationActions(dialogRoot())
            assertFalse(dialogHasText(text(R.string.file_browser_remove_local_folder)))
            dismissSheet()
        }
        assertTrue(mutations.isEmpty())
        assertTrue(removedGrants.isEmpty())
    }

    @Test(timeout = 30_000)
    fun directoryUploadCallbacksUseTheControllerNodePathRatherThanTheLabelOrGrant() = render {
        openMenu(CHILD_NAME)
        clickText(dialogRoot(), text(R.string.file_browser_upload_files))
        assertEquals(listOf(CHILD_PATH), uploadedFiles)
        assertTrue(uploadedDirectories.isEmpty())

        openMenu(CHILD_NAME)
        clickText(dialogRoot(), text(R.string.file_browser_upload_directory))
        assertEquals(listOf(CHILD_PATH), uploadedDirectories)
        assertTrue(mutations.isEmpty())
    }

    @Test(timeout = 30_000)
    fun availableDeviceRootCanUploadEvenWhenTheContainerRootIsRemote() = render {
        openMenu(LOCAL_LABEL)
        assertEnabled(textAction(dialogRoot(), text(R.string.file_browser_upload_files)))
        assertEnabled(textAction(dialogRoot(), text(R.string.file_browser_upload_directory)))
        clickText(dialogRoot(), text(R.string.file_browser_upload_files))
        assertEquals(listOf(LOCAL_PATH), uploadedFiles)

        openMenu(LOCAL_LABEL)
        clickText(dialogRoot(), text(R.string.file_browser_upload_directory))
        assertEquals(listOf(LOCAL_PATH), uploadedDirectories)
        assertTrue(mutations.isEmpty())
    }

    @Test(timeout = 30_000)
    fun unavailableRootShowsReauthorizationAndDisablesDirectoryWritesWithoutExpanding() = render {
        browseState.value = FileBrowseState.Success(listOf(localRoot(available = false)))
        clipboard = BrowseClipboard("~/workspace/source.txt", "source.txt", false)
        pump(12)
        clickText(hostRoot(), LOCAL_LABEL)
        waitFor("Unavailable folders must explain the expired access") {
            dialogHasText(text(R.string.file_browser_local_folder_unavailable))
        }
        assertTrue(expanded.isEmpty())
        assertNoRootMutationActions(dialogRoot())
        for (resource in listOf(R.string.file_browser_new_file, R.string.file_browser_new_folder,
            R.string.file_browser_paste_here, R.string.file_browser_upload_files, R.string.file_browser_upload_directory)) {
            assertDisabled(textAction(dialogRoot(), text(resource)))
        }
        assertEnabled(textAction(dialogRoot(), text(R.string.file_browser_remove_local_folder)))
        clickText(dialogRoot(), text(R.string.file_browser_reauthorize_local_folder))
        assertEquals(1, addRequests)
        assertTrue(uploadedFiles.isEmpty())
        assertTrue(uploadedDirectories.isEmpty())
        assertTrue(mutations.isEmpty())
    }

    @Test(timeout = 30_000)
    fun activeTransferKeepsAnAccessibleCancelAndDisablesCompetingActions() = render {
        transferActive = true
        clipboard = BrowseClipboard("~/workspace/source.txt", "source.txt", false)
        pump(12)
        assertEnabled(descriptionAction(text(R.string.file_browser_cancel_transfer)))
        assertDisabled(descriptionAction(text(R.string.file_browser_add_local_folder)))
        assertDisabled(descriptionAction(text(R.string.file_browser_refresh)))
        assertTrue(hostRoot().semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text(R.string.file_browser_transfer_in_progress)) == true
        })

        openMenu(LOCAL_LABEL)
        for (resource in listOf(R.string.file_browser_new_file, R.string.file_browser_new_folder,
            R.string.file_browser_paste_here, R.string.file_browser_upload_files,
            R.string.file_browser_upload_directory, R.string.file_browser_remove_local_folder)) {
            assertDisabled(textAction(dialogRoot(), text(resource)))
        }
        dismissSheet()
        clickDescription(text(R.string.file_browser_cancel_transfer))
        assertEquals(1, cancellations)
        assertEquals(0, addRequests)
        assertTrue(uploadedFiles.isEmpty())
        assertTrue(uploadedDirectories.isEmpty())
        assertTrue(mutations.isEmpty())

        transferActive = false
        pump(12)
        assertFalse(hostRoot().semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text(R.string.file_browser_cancel_transfer)) == true
        })
        assertEnabled(descriptionAction(text(R.string.file_browser_add_local_folder)))
        clickDescription(text(R.string.file_browser_add_local_folder))
        assertEquals(1, addRequests)
    }

    @Test(timeout = 30_000)
    fun fileMenuDoesNotOfferDirectoryUploadsAndLoadingStillAllowsTransferCancellation() = render {
        openMenu(FILE_NAME)
        assertFalse(dialogHasText(text(R.string.file_browser_upload_files)))
        assertFalse(dialogHasText(text(R.string.file_browser_upload_directory)))
        assertEnabled(textAction(dialogRoot(), text(R.string.common_copy)))
        dismissSheet()

        browseState.value = FileBrowseState.Loading
        transferActive = true
        pump(12)
        assertEnabled(descriptionAction(text(R.string.file_browser_cancel_transfer)))
        clickDescription(text(R.string.file_browser_cancel_transfer))
        assertEquals(1, cancellations)
        assertTrue(mutations.isEmpty())
    }

    private class DrawerUi(
        private val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit
    ) {
        val browseState = MutableStateFlow<FileBrowseState>(FileBrowseState.Success(listOf(
            node("workspace", "~/workspace", root = FileTreeRoot.Workspace),
            node(REMOTE_LABEL, "/", root = FileTreeRoot.Container(REMOTE_LABEL, null, true)),
            localRoot(),
            node(CHILD_NAME, CHILD_PATH, depth = 1),
            node(FILE_NAME, "$LOCAL_PATH/$FILE_NAME", depth = 1, directory = false)
        )))
        var transferActive by mutableStateOf(false)
        var clipboard by mutableStateOf<BrowseClipboard?>(null)
        val removedGrants = mutableListOf<String>()
        val uploadedFiles = mutableListOf<String>()
        val uploadedDirectories = mutableListOf<String>()
        val expanded = mutableListOf<String>()
        val mutations = mutableListOf<String>()
        var addRequests = 0
        var cancellations = 0

        fun text(resource: Int, vararg arguments: Any): String = activity.getString(resource, *arguments)
        fun pump(count: Int = 1) = repeat(count) { advanceFrame() }
        fun hostRoot(): ViewRootForTest = requireNotNull(findRoot(view))
        fun dialogRoot(): ViewRootForTest = requireNotNull(ShadowDialog.getLatestDialog()
            ?.takeIf { it.isShowing }?.window?.decorView?.let(::findRoot))
        private fun hasText(root: ViewRootForTest, text: String): Boolean = root.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
        fun hostHasText(text: String): Boolean = hasText(hostRoot(), text)
        fun dialogHasText(text: String): Boolean = ShadowDialog.getLatestDialog()
            ?.takeIf { it.isShowing }?.window?.decorView?.let(::findRoot)?.let { hasText(it, text) } == true
        fun waitFor(message: String, condition: () -> Boolean) {
            repeat(120) {
                pump()
                if (condition()) return
            }
            assertTrue(message, condition())
        }
        fun textAction(root: ViewRootForTest, text: String): SemanticsNode = root.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = true).single {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
        fun descriptionAction(text: String): SemanticsNode = hostRoot().semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = true).single {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
            }
        fun assertEnabled(node: SemanticsNode) {
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action != null)
        }
        fun assertDisabled(node: SemanticsNode) {
            assertTrue(node.config.contains(SemanticsProperties.Disabled))
        }
        fun clickText(root: ViewRootForTest, text: String) {
            val node = textAction(root, text)
            assertEnabled(node)
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            pump(6)
        }
        fun clickDescription(text: String) {
            val node = descriptionAction(text)
            assertEnabled(node)
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            pump(6)
        }
        fun openMenu(text: String) {
            val node = hostRoot().semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single {
                it.config.getOrNull(SemanticsActions.OnLongClick) != null &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnLongClick].action).invoke())
            waitFor("File action sheet must show $text") { dialogHasText(text) }
            pump(24)
        }
        fun dismissSheet() {
            val action = dialogRoot().semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                .firstNotNullOf { it.config.getOrNull(SemanticsActions.Dismiss)?.action }
            assertTrue(action.invoke())
            waitFor("File action sheet must close") { ShadowDialog.getLatestDialog()?.isShowing != true }
        }
        fun assertNoRootMutationActions(root: ViewRootForTest) {
            for (resource in listOf(R.string.common_copy, R.string.common_cut, R.string.common_rename, R.string.common_delete)) {
                assertFalse("Root must not expose ${text(resource)}", hasText(root, text(resource)))
            }
        }
    }

    private fun render(inspect: DrawerUi.() -> Unit) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        fun layout(window: View) {
            window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        val ui = DrawerUi(activity, view) {
            looper.idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::layout)
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        ChatDrawerContent(
                            sessions = emptyList(), currentSessionId = null, agentStates = emptyMap(),
                            onSelect = {}, onDelete = {}, onRename = { _, _ -> }, onTogglePin = {}, onExport = {},
                            browseStateFlow = ui.browseState, sidebarVisible = true, expandedPaths = emptySet(),
                            transferActive = ui.transferActive, clipboard = ui.clipboard,
                            onToggleExpand = { ui.expanded += it }, onOpenFile = { ui.mutations += "open:$it" },
                            onRefreshBrowse = {},
                            onCreateFile = { parent, name -> ui.mutations += "createFile:$parent/$name" },
                            onCreateFolder = { parent, name -> ui.mutations += "createFolder:$parent/$name" },
                            onRenameEntry = { path, name -> ui.mutations += "rename:$path:$name" },
                            onDeleteEntry = { ui.mutations += "delete:$it" },
                            onCopyEntry = { path, _ -> ui.mutations += "copy:$path" },
                            onCutEntry = { path, _ -> ui.mutations += "cut:$path" },
                            onPasteEntry = { path, result -> ui.mutations += "paste:$path"; result(true) },
                            onPasteOverwrite = { ui.mutations += "overwrite" }, onCancelPasteOverwrite = {},
                            onClearClipboard = { ui.clipboard = null },
                            onAddLocalFolder = { ui.addRequests++ },
                            onRemoveLocalFolder = { ui.removedGrants += it },
                            onUploadFiles = { ui.uploadedFiles += it },
                            onUploadDirectory = { ui.uploadedDirectories += it },
                            onCancelTransfer = { ui.cancellations++ },
                            onNavigateToSettings = { ui.mutations += "settings" },
                            searchQuery = "", searchState = ChatSearchState(),
                            onSearchQueryChange = {}, onClearSearch = {}, onOpenSearchHit = {}
                        )
                    }
                }
            }
            activity.setContentView(view)
            ui.pump(12)
            ui.clickText(ui.hostRoot(), ui.text(R.string.drawer_tab_files))
            ui.pump(12)
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }

    companion object {
        private const val GRANT_ID = "opaque-grant-id"
        private const val LOCAL_LABEL = "Device projects"
        private const val LOCAL_PATH = "/storage/emulated/0/Projects"
        private const val REMOTE_LABEL = "Remote container"
        private const val CHILD_NAME = "Target folder"
        private const val CHILD_PATH = "$LOCAL_PATH/actual-child-directory"
        private const val FILE_NAME = "note.txt"

        private fun localRoot(available: Boolean = true): FileTreeNode = node(
            "filesystem-name", LOCAL_PATH, root = FileTreeRoot.LocalFolder(GRANT_ID, LOCAL_LABEL, available)
        )
        private fun node(
            name: String,
            path: String,
            depth: Int = 0,
            root: FileTreeRoot? = null,
            directory: Boolean = true
        ) = FileTreeNode(FileEntry(name, directory, 0L, 0L), path, depth, root, isExpanded = false)
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }
}
