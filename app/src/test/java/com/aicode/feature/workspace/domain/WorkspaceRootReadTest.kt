package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceRootReadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = mockk<WorkspaceRepository>()
    private val installer = mockk<ContainerInstaller>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val home = mockk<PathHomeResolver>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val current = AtomicBoolean(true)
    private lateinit var workspaceRoot: File
    private lateinit var rootfs: File
    private lateinit var mounted: File
    private lateinit var mapper: WorkspacePathMapper
    private lateinit var execution: WorkspaceExecutionContext
    private lateinit var facade: WorkspaceRootRead

    @Before
    fun prepare() {
        workspaceRoot = temporary.newFolder("workspace")
        rootfs = temporary.newFolder("rootfs")
        mounted = temporary.newFolder("mounted")
        File(workspaceRoot, "site").mkdir()
        File(workspaceRoot, "site/entry.html").writeText("entry")
        File(rootfs, "etc").mkdir()
        File(rootfs, "etc/secret").writeText("outside")
        File(mounted, "report.txt").writeText("mounted")
        val config = temporary.newFolder("config")
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(extraBindings = listOf("${mounted.absolutePath}:/mnt/external"))
        every { workspace.currentPath() } returns workspaceRoot.path
        every { installer.rootfsDirFor(any()) } returns rootfs
        every { installer.aicodeDir } returns config
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            firstArg<String>().let { if (it == "~") "/root" else if (it.startsWith("~/")) "/root" + it.removePrefix("~") else it }
        }
        val initialized = CompletableDeferred<Job>()
        every { settings.customProfilesFlow } returns flowOf(listOf(profile))
        every { settings.activeProfileIdFlow } returns flow {
            emit(profile.id)
            initialized.complete(currentCoroutineContext()[Job]!!)
        }
        mapper = WorkspacePathMapper(workspace, installer, settings, home)
        runBlocking { withTimeout(5_000) { initialized.await().join() } }
        val mapping = mapper.captureExecutionMapping()
        execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "captured", mockk(), mapping, null) {
            if (!current.get() || mapper.captureExecutionMapping() != mapping) throw CancellationException("Mapping changed")
        }
        facade = WorkspaceRootRead(mapper, remote)
    }

    @Test
    fun synchronousReadsCarryFrozenMappingAndRestoreTheCallingThreadContext() {
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertEquals(5L, read.size("~/workspace/site/entry.html"))
            assertEquals("entry", read.openRead("~/workspace/site/entry.html", 100).bufferedReader().use { it.readText() })
            assertEquals(File(workspaceRoot, "site/entry.html").canonicalFile, read.localFile("~/workspace/site/entry.html"))
            assertEquals(null, WorkspaceExecutionContext.current())
        } finally { owner.cancel() }
    }

    @Test
    fun absoluteContainerSymlinkInsideTheLogicalParentUsesContainerMapping() {
        Files.createSymbolicLink(File(workspaceRoot, "site/link.html").toPath(), java.nio.file.Paths.get("/root/workspace/site/entry.html"))
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertEquals("entry", read.openRead("~/workspace/site/link.html", 100).bufferedReader().use { it.readText() })
        } finally { owner.cancel() }
    }

    @Test
    fun leafSymlinkOutsideTheOriginalParentDoesNotAuthorizeItsTargetParent() {
        Files.createSymbolicLink(File(workspaceRoot, "site/escape").toPath(), java.nio.file.Paths.get("/etc/secret"))
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertThrows(IOException::class.java) { read.size("~/workspace/site/escape") }
            assertThrows(IOException::class.java) { read.openRead("~/workspace/site/escape", 100) }
            assertThrows(IOException::class.java) { read.localFile("~/workspace/site/escape") }
        } finally { owner.cancel() }
    }

    @Test
    fun relativeSymlinkEscapeAndNonRegularFilesAreRejected() {
        Files.createSymbolicLink(File(workspaceRoot, "site/up").toPath(), java.nio.file.Paths.get("../../outside"))
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertThrows(IOException::class.java) { read.openRead("~/workspace/site/up", 100) }
            assertThrows(IOException::class.java) { read.openRead("~/workspace/site", 100) }
            assertThrows(IOException::class.java) { read.openRead("~/workspace/site/missing", 100) }
        } finally { owner.cancel() }
    }

    @Test
    fun rootCanonicalPathIsFrozenAndCannotFollowALaterSymlinkReplacement() {
        val site = File(workspaceRoot, "site")
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertTrue(site.renameTo(File(workspaceRoot, "old-site")))
            Files.createSymbolicLink(site.toPath(), java.nio.file.Paths.get("/etc"))
            assertThrows(CancellationException::class.java) { read.checkCurrent() }
            assertThrows(CancellationException::class.java) { read.openRead("~/workspace/site/secret", 100) }
        } finally { owner.cancel() }
    }

    @Test
    fun returnedStreamRejectsAChangedRootBeforeReturningAnotherByte() {
        val site = File(workspaceRoot, "site")
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            read.openRead("~/workspace/site/entry.html", 100).use { input ->
                assertEquals('e'.code, input.read())
                assertTrue(site.renameTo(File(workspaceRoot, "old-site")))
                Files.createSymbolicLink(site.toPath(), java.nio.file.Paths.get("/etc"))
                assertThrows(CancellationException::class.java) { input.read() }
            }
        } finally { owner.cancel() }
    }

    @Test
    fun fullExtraBindingMappingIsKeptWhenResolvingAReadRoot() {
        val owner = Job()
        try {
            val read = facade.capture("/mnt/external", execution, owner)
            assertEquals(File(mounted, "report.txt").canonicalFile, read.localFile("/mnt/external/report.txt"))
            assertEquals("mounted", read.openRead("/mnt/external/report.txt", 100).bufferedReader().use { it.readText() })
        } finally { owner.cancel() }
    }

    @Test
    fun bindingChangeInvalidatesTheCaptureEvenIfCacheIdentityWouldBeUnchanged() {
        val owner = Job()
        try {
            val read = facade.capture("/mnt/external", execution, owner)
            val replacement = ContainerProfile.BUILTIN_ALPINE.copy(extraBindings = emptyList())
            WorkspacePathMapper::class.java.getDeclaredField("currentProfile").apply { isAccessible = true }.set(mapper, replacement)
            assertThrows(CancellationException::class.java) { read.openRead("/mnt/external/report.txt", 100) }
        } finally { owner.cancel() }
    }

    @Test
    fun ownerCancellationRejectsFutureReadsWithoutOpeningAnotherFile() {
        val owner = Job()
        val read = facade.capture("~/workspace/site", execution, owner)
        owner.cancel()
        assertThrows(CancellationException::class.java) { read.openRead("~/workspace/site/entry.html", 100) }
    }

    @Test
    fun metadataSizeLimitIsAppliedBeforeOpeningALocalStream() {
        val owner = Job()
        try {
            val read = facade.capture("~/workspace/site", execution, owner)
            assertThrows(IOException::class.java) { read.openRead("~/workspace/site/entry.html", 4) }
            assertEquals("entry", read.openRead("~/workspace/site/entry.html", 5).bufferedReader().use { it.readText() })
        } finally { owner.cancel() }
    }

    @Test
    fun filesystemRootAndUnboundedHomeCannotBeCaptured() {
        val owner = Job()
        try {
            listOf("/", "~", "~/").forEach { path ->
                assertThrows(IOException::class.java) { facade.capture(path, execution, owner) }
            }
        } finally { owner.cancel() }
    }
}
