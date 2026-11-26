package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PlayerLocalFileAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun localAccess(): Triple<LocalFileAccess, File, File> {
        val workspace = temporary.newFolder("workspace")
        val rootfs = temporary.newFolder("rootfs")
        val config = temporary.newFolder("config")
        val repository = mockk<WorkspaceRepository>()
        val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace(workspace.name, workspace.absolutePath)))
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val initialized = CompletableDeferred<Job>()
        every { repository.currentPath() } answers { checkNotNull(selection.value.workspace).path }
        every { repository.selection } returns selection
        every { installer.rootfsDirFor(any()) } returns rootfs
        every { installer.aicodeDir } returns config
        every { settings.customProfilesFlow } returns flowOf(listOf(ContainerProfile.BUILTIN_ALPINE))
        every { settings.activeProfileSelectionRevision } returns 0L
        every { settings.profileConfigurationRevision } returns 0L
        every { settings.activeProfileIdFlow } returns flow {
            emit(ContainerProfile.BUILTIN_ID)
            initialized.complete(currentCoroutineContext()[Job]!!)
        }
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            val path = firstArg<String>()
            when {
                path == "~" -> "/root"
                path.startsWith("~/") -> "/root" + path.removePrefix("~")
                else -> path
            }
        }
        val mapper = WorkspacePathMapper(repository, installer, settings, home)
        runBlocking { withTimeout(5000) { initialized.await().join() } }
        return Triple(LocalFileAccess(mapper), workspace, rootfs)
    }

    @Test fun realLocalMappingListsAndOpensWorkspaceFileWithoutRemappingHostPathIntoRootfs() {
        val (access, workspace, _) = localAccess()
        val file = File(workspace, "clip.mp4").apply { writeText("0123456789") }
        PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(),
            createCopy = { error("Local files must use their original descriptor") }).use { library ->
            val items = library.json().getValue("items").jsonArray
            assertEquals(1, items.size)
            assertEquals("clip.mp4", items.single().jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("/T/local?path=~%2Fworkspace%2Fclip.mp4", items.single().jsonObject.getValue("url").jsonPrimitive.content)
            library.openValidated("~/workspace/clip.mp4").use { opened ->
                assertEquals(file.length(), opened.size)
                assertEquals("0123456789", ByteArray(10).also { opened.descriptor!!.readFully(it) }.toString(Charsets.UTF_8))
            }
            library.openValidated("/root/workspace/clip.mp4").use { assertEquals(48, it.descriptor!!.read()) }
            library.close()
            assertTrue(file.exists())
        }
    }

    @Test fun realLocalSymlinkOutsideSessionRootIsExcludedAndCannotBeOpened() {
        val (access, workspace, rootfs) = localAccess()
        File(workspace, "clip.mp4").writeText("safe")
        File(rootfs, "etc/private.mp4").apply { parentFile!!.mkdirs(); writeText("private") }
        Files.createSymbolicLink(File(workspace, "escape.mp4").toPath(), File("/etc/private.mp4").toPath())
        PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(),
            createCopy = { error("Local files must not be copied") }).use { library ->
            val names = library.json().getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("clip.mp4"), names)
            assertEquals(403, assertThrows(PlayerHttpException::class.java) { library.openValidated("~/workspace/escape.mp4") }.status)
            assertEquals(403, assertThrows(PlayerHttpException::class.java) { library.openValidated("/etc/private.mp4") }.status)
        }
    }

    @Test fun realLocalRelativeSymlinkWithinWorkspaceRemainsPlayable() {
        val (access, workspace, _) = localAccess()
        val media = File(workspace, "media").apply { mkdirs() }
        File(media, "clip.mp4").writeText("safe")
        Files.createSymbolicLink(File(workspace, "alias.mp4").toPath(), File("media/clip.mp4").toPath())
        PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(),
            createCopy = { error("Local files must not be copied") }).use { library ->
            val names = library.json().getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(names.contains("alias.mp4"))
            library.openValidated("~/workspace/alias.mp4").use { opened ->
                assertEquals(4L, opened.size)
                assertEquals("safe", ByteArray(4).also { opened.descriptor!!.readFully(it) }.toString(Charsets.UTF_8))
            }
        }
    }
}
