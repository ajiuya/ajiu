package com.aicode.feature.workspace.domain

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FileUploadInputsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private lateinit var inputs: FileUploadInputs

    @Before
    fun prepare() {
        every { context.contentResolver } returns resolver
        inputs = FileUploadInputs(context)
    }

    @After
    fun release() { unmockkObject(UriPathResolver) }

    private fun uri(id: String = "one", provider: String = "documents.example"): Uri =
        Uri.parse("content://$provider/$id")

    private fun metadata(uri: Uri, name: String, size: Long?) {
        every { resolver.query(uri, any(), null, null, null) } answers {
            mockk<Cursor>(relaxed = true) {
                every { moveToFirst() } returns true
                every { getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
                every { getColumnIndex(OpenableColumns.SIZE) } returns 1
                every { isNull(0) } returns false
                every { isNull(1) } returns (size == null)
                every { getString(0) } returns name
                every { getLong(1) } returns (size ?: 0)
            }
        }
    }

    @Test
    fun namesMustBeLegalSingleSegmentsAndDuplicateNamesAreRejected() {
        for (name in listOf("", " ", ".", "..", "../a", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            assertThrows(IOException::class.java) { validateUploadMetadata(listOf(UploadFileMetadata(name, null))) }
        }
        assertThrows(IOException::class.java) { validateUploadMetadata(listOf(UploadFileMetadata("same", 1), UploadFileMetadata("same", 2))) }
        assertThrows(IOException::class.java) { validateUploadMetadata(listOf(UploadFileMetadata("file", -1))) }
        validateUploadMetadata(listOf(UploadFileMetadata("中文 document.txt", null), UploadFileMetadata(".hidden", 0)))
    }

    @Test
    fun fullBatchMetadataIsValidatedBeforeOpeningAnyStream() {
        val first = uri("one")
        val second = uri("two")
        metadata(first, "same", 1)
        metadata(second, "same", 1)
        assertThrows(IOException::class.java) { inputs.files(listOf(first, second)) {} }
        verify(exactly = 0) { resolver.openInputStream(any()) }
        assertThrows(IOException::class.java) { inputs.files(List(129) { first }) {} }
        assertThrows(IOException::class.java) { inputs.files(emptyList()) {} }
    }

    @Test
    fun nullInputStreamAndProviderFailureAreNotTreatedAsEmptyFiles() {
        val uri = uri()
        metadata(uri, "file", null)
        val reader = inputs.files(listOf(uri)) {}.single().reader
        every { resolver.openInputStream(uri) } returns null
        assertThrows(IOException::class.java) { transferVersion(reader) {} }
        every { resolver.openInputStream(uri) } throws IOException("Provider offline")
        assertEquals("Provider offline", assertThrows(IOException::class.java) { transferVersion(reader) {} }.message)
    }

    @Test
    fun knownSizeMustMatchTheActualStreamButUnknownSizeAcceptsEof() {
        val uri = uri()
        metadata(uri, "file", 4)
        every { resolver.openInputStream(uri) } answers { byteArrayOf(1, 2, 3).inputStream() }
        assertThrows(IOException::class.java) { transferVersion(inputs.files(listOf(uri)) {}.single().reader) {} }
        metadata(uri, "file", null)
        val version = transferVersion(inputs.files(listOf(uri)) {}.single().reader) {}!!
        assertEquals(3L, version.nodes.single().bytes)
        metadata(uri, "file", 0)
        every { resolver.openInputStream(uri) } answers { byteArrayOf().inputStream() }
        assertEquals(0L, transferVersion(inputs.files(listOf(uri)) {}.single().reader) {}!!.nodes.single().bytes)
    }

    @Test
    fun streamCancellationIsPropagatedAndTheInputIsClosed() {
        val uri = uri()
        metadata(uri, "file", null)
        var closed = false
        every { resolver.openInputStream(uri) } answers {
            object : InputStream() {
                override fun read(): Int = throw CancellationException("Picker scope changed")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw CancellationException("Picker scope changed")
                override fun close() { closed = true }
            }
        }
        assertThrows(CancellationException::class.java) { transferVersion(inputs.files(listOf(uri)) {}.single().reader) {} }
        assertTrue(closed)
    }

    @Test
    fun cancellingTheOwnerClosesABlockedDocumentStream() {
        val uri = uri()
        metadata(uri, "file", null)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val owner = Job()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null) {}
        every { resolver.openInputStream(uri) } answers {
            object : InputStream() {
                override fun read(): Int = throw AssertionError()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    entered.countDown()
                    assertTrue(closed.await(2, TimeUnit.SECONDS))
                    throw IOException("Stream closed")
                }
                override fun close() { closed.countDown() }
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Throwable?> {
                runCatching {
                    runBlocking(execution.ownedBlockingContext(owner)) {
                        val input = inputs.files(listOf(uri)) { owner.ensureActive() }.single().reader.openRead("")
                        input.use { it.read(ByteArray(16)) }
                    }
                }.exceptionOrNull()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.cancel()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(result.get(2, TimeUnit.SECONDS) is CancellationException)
        } finally {
            owner.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun cancellationDuringInputAcquisitionClosesTheReturnedDescriptor() {
        val uri = uri()
        metadata(uri, "file", null)
        val owner = Job()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null) {}
        var closed = false
        every { resolver.openInputStream(uri) } answers {
            owner.cancel()
            object : InputStream() {
                override fun read(): Int = -1
                override fun close() { closed = true }
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking(execution.ownedBlockingContext(owner)) {
                inputs.files(listOf(uri)) { owner.ensureActive() }.single().reader.openRead("")
            }
        }
        assertTrue(closed)
    }

    @Test
    fun unknownDirectoryProviderIsRejectedBeforePersistingPermission() {
        val unknown = uri()
        assertThrows(IOException::class.java) { inputs.directory(unknown) {} }
        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
    }

    @Test
    fun directoryRequiresPersistentReadPermissionAndDirectFileAccess() {
        val tree = uri("tree", "com.android.externalstorage.documents")
        val root = temporary.newFolder("tree")
        mockkObject(UriPathResolver)
        every { UriPathResolver.toFilePath(context, tree) } returns root.absolutePath
        every { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } throws SecurityException("grant unavailable")
        assertThrows(IOException::class.java) { inputs.directory(tree) {} }
        every { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns Unit
        every { resolver.persistedUriPermissions } returns emptyList()
        assertThrows(IOException::class.java) { inputs.directory(tree) {} }
        val grant = mockk<UriPermission> {
            every { uri } returns tree
            every { isReadPermission } returns true
        }
        every { resolver.persistedUriPermissions } returns listOf(grant)
        File(root, "file").writeText("content")
        val source = inputs.directory(tree) {}
        assertEquals(root.name, source.name)
        assertEquals(2, transferVersion(source.reader) {}!!.nodes.size)
        every { resolver.persistedUriPermissions } returns emptyList()
        assertThrows(IOException::class.java) { transferVersion(source.reader) {} }
    }

    @Test
    fun uploadConflictHasABoundTicketAndConfirmationUsesTheUriStream() = runBlocking {
        val fixture = UploadFixture()
        val uri = uri()
        metadata(uri, "file", 3)
        every { resolver.openInputStream(uri) } answers { "new".byteInputStream() }
        File(fixture.root, "file").writeText("old")
        val result = fixture.service.uploadFiles(fixture.execution, fixture.rootPath, listOf(uri)) {}.single() as TransferResult.Conflict
        assertEquals("old", File(fixture.root, "file").readText())
        assertEquals(uri.toString(), result.ticket.source)
        assertTrue(fixture.service.confirm(fixture.execution, result.ticket) {} is TransferResult.Completed)
        assertEquals("new", File(fixture.root, "file").readText())
        assertTrue(fixture.root.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
    }

    @Test
    fun uploadUriContentChangeInvalidatesConflictEvenWhenNameAndSizeMatch() = runBlocking {
        val fixture = UploadFixture()
        val uri = uri()
        metadata(uri, "file", 3)
        var data = "new"
        every { resolver.openInputStream(uri) } answers { data.byteInputStream() }
        File(fixture.root, "file").writeText("old")
        val result = fixture.service.uploadFiles(fixture.execution, fixture.rootPath, listOf(uri)) {}.single() as TransferResult.Conflict
        data = "NEW"
        assertTrue(fixture.service.confirm(fixture.execution, result.ticket) {} is TransferResult.Failed)
        assertEquals("old", File(fixture.root, "file").readText())
    }

    @Test
    fun batchFailureReportsEarlierCompletedFilesInsteadOfPretendingRollback() = runBlocking {
        val fixture = UploadFixture()
        val one = uri("one")
        val two = uri("two")
        metadata(one, "one", 3)
        metadata(two, "two", 3)
        every { resolver.openInputStream(one) } answers { "one".byteInputStream() }
        every { resolver.openInputStream(two) } throws IOException("Second provider failed")
        val result = fixture.service.uploadFiles(fixture.execution, fixture.rootPath, listOf(one, two)) {}
        assertEquals(2, result.size)
        assertTrue(result.first() is TransferResult.Completed)
        assertTrue(result.last() is TransferResult.Failed)
        assertEquals("one", File(fixture.root, "one").readText())
        assertFalse(File(fixture.root, "two").exists())
    }

    @Test
    fun duplicateBatchDoesNotModifyExistingTargets() = runBlocking {
        val fixture = UploadFixture()
        File(fixture.root, "same").writeText("keep")
        val one = uri("one")
        val two = uri("two")
        metadata(one, "same", 1)
        metadata(two, "same", 1)
        val result = fixture.service.uploadFiles(fixture.execution, fixture.rootPath, listOf(one, two)) {}
        assertTrue(result.single() is TransferResult.Failed)
        assertEquals("keep", File(fixture.root, "same").readText())
        verify(exactly = 0) { resolver.openInputStream(any()) }
        assertEquals(1, fixture.root.listFiles()!!.size)
    }

    private inner class UploadFixture {
        val root = temporary.newFolder()
        private val grant = LocalFolderGrant("target", "e1", "Target", localRootPath("target", "e1"), true)
        val rootPath = grant.rootPath
        private val snapshot = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, root))) {}
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "execution", mockk(), null, null, snapshot) {}
        val service = FileTransferService(FileBackendRouting(), inputs, WorkspaceFileLock())
    }
}
