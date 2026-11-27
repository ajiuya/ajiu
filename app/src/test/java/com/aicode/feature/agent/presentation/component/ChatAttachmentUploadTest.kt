package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import com.aicode.core.util.ImageDownscaler
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChatAttachmentUploadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun imagePickerWithoutExecutionCapturesFileOnceAndKeepsOriginalAndScaledData() = runBlocking {
        val original = imageBytes(2048, 1024, noisy = true)
        val fixture = UploadFixture(temporary, original)
        val expected = ImageDownscaler.downscale(original, "image/png")
        assertTrue("The fixture must exercise actual downscaling", expected.bytes.size < original.size)
        assertNull(WorkspaceExecutionContext.current())

        val uploaded = fixture.upload(includeImageData = true)

        fixture.assertOperations("exists", "writeBytes", "copyToLocal")
        fixture.assertUploaded(uploaded)
        val image = requireNotNull(uploaded.image)
        assertEquals(uploaded.containerPath, image.path)
        assertEquals(expected.mimeType, image.mimeType)
        val encodedBytes = Base64.getDecoder().decode(image.base64Data)
        assertArrayEquals(expected.bytes, encodedBytes)
        assertFalse("The model must receive the scaled copy", original.contentEquals(encodedBytes))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, bounds)
        assertEquals(1568, bounds.outWidth)
        assertEquals(784, bounds.outHeight)
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun ordinaryFileStreamsWithoutWriteBytesAndClosesItsResolverInput() = runBlocking {
        val bytes = ByteArray(3 * 8192 + 17) { (it % 251).toByte() }
        val fixture = UploadFixture(temporary, bytes, "report.pdf", "application/pdf")
        assertNull(WorkspaceExecutionContext.current())

        val uploaded = fixture.upload()

        fixture.assertOperations("exists", "writeStream", "copyToLocal")
        fixture.assertUploaded(uploaded)
        assertNull(uploaded.image)
        assertTrue("The provider must be consumed in multiple reads", fixture.source.reads.get() > 1)
        assertEquals(bytes.size, fixture.source.bytesRead.get())
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun cameraContentUriUsesTheSameFileCaptureAndPreservesTheSmallJpeg() = runBlocking {
        val bytes = imageBytes(format = Bitmap.CompressFormat.JPEG)
        val context = RuntimeEnvironment.getApplication()
        val cameraUri = Uri.parse("content://${context.packageName}.fileprovider/cache/camera_123.jpg")
        val fixture = UploadFixture(temporary, bytes, "camera_123.jpg", "image/jpeg", cameraUri)
        assertNull(WorkspaceExecutionContext.current())

        val uploaded = fixture.upload(includeImageData = true)

        fixture.assertOperations("exists", "writeBytes", "copyToLocal")
        fixture.assertUploaded(uploaded)
        val image = requireNotNull(uploaded.image)
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(uploaded.containerPath, image.path)
        assertArrayEquals(bytes, Base64.getDecoder().decode(image.base64Data))
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun inheritedAgentExecutionIsReplacedByTheExplicitFileCaptureAndRestoredAfterUpload() = runBlocking {
        val fixture = UploadFixture(temporary, imageBytes())
        val agent = fixture.executionFor(RuntimeOwner(RuntimeOwnerKind.AGENT, "authorized-chat"))
        fixture.expectedCaptureContext = agent

        val uploaded = withContext(agent) {
            fixture.upload(includeImageData = true).also {
                assertSame("The caller must retain its AGENT binding", agent, WorkspaceExecutionContext.current())
            }
        }

        fixture.assertOperations("exists", "writeBytes", "copyToLocal")
        fixture.assertUploaded(uploaded)
        assertArrayEquals(fixture.source.bytes, Base64.getDecoder().decode(requireNotNull(uploaded.image).base64Data))
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun cancelledFileCapturePropagatesWithoutFallingBackToTheInheritedAgent() {
        val fixture = UploadFixture(temporary, imageBytes())
        val agent = fixture.executionFor(RuntimeOwner(RuntimeOwnerKind.AGENT, "authorized-chat"))
        fixture.expectedCaptureContext = agent
        val cancelled = CancellationException("Attachment capture cancelled")
        fixture.onCapture = { throw cancelled }

        val failure = assertThrows(CancellationException::class.java) {
            runBlocking { withContext(agent) { fixture.upload(includeImageData = true) } }
        }

        assertEquals(cancelled.message, failure.message)
        fixture.assertOperations()
        assertEquals(0, fixture.source.opens.get())
        assertEquals(0, fixture.source.closes.get())
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun deniedFileCaptureCannotBorrowTheInheritedAgentAuthorization() {
        val fixture = UploadFixture(temporary, imageBytes())
        val agent = fixture.executionFor(RuntimeOwner(RuntimeOwnerKind.AGENT, "authorized-chat"))
        fixture.expectedCaptureContext = agent
        fixture.onCapture = { throw LocalRuntimeAuthorizationRequired() }

        assertThrows(LocalRuntimeAuthorizationRequired::class.java) {
            runBlocking { withContext(agent) { fixture.upload(includeImageData = true) } }
        }

        fixture.assertOperations()
        assertEquals(0, fixture.source.opens.get())
        assertEquals(0, fixture.source.closes.get())
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun sourceChangesAtUploadBoundariesCancelWithoutPublishingOrRecapturing() {
        for (image in listOf(true, false)) {
            for (point in SourceChangePoint.entries) {
                val fixture = if (image) UploadFixture(temporary, imageBytes())
                    else UploadFixture(temporary, "streamed document".toByteArray(), "notes.txt", "text/plain")
                val write = if (image) "writeBytes" else "writeStream"
                val label = "image=$image, change=$point"
                when (point) {
                    SourceChangePoint.CAPTURE_RETURN -> fixture.onCapture = { fixture.current.set(false) }
                    SourceChangePoint.EXISTS_RETURN -> fixture.afterOperation = {
                        if (it == "exists") fixture.current.set(false)
                    }
                    SourceChangePoint.INPUT_CLOSE -> fixture.source.onClose = { fixture.current.set(false) }
                    SourceChangePoint.WRITE_RETURN -> fixture.afterOperation = {
                        if (it == write) fixture.current.set(false)
                    }
                    SourceChangePoint.LOCAL_COPY_RETURN -> fixture.afterOperation = {
                        if (it == "copyToLocal") fixture.current.set(false)
                    }
                }

                // The provider/backend returns normally after changing the source. Only the helper's
                // own checkCurrent calls can reject these results before the next stage/publication.
                val failure = assertThrows(label, CancellationException::class.java) {
                    runBlocking { fixture.upload(includeImageData = image) }
                }

                assertEquals(label, SOURCE_CHANGED, failure.message)
                val expected = when (point) {
                    SourceChangePoint.CAPTURE_RETURN -> emptyList()
                    SourceChangePoint.EXISTS_RETURN -> listOf("exists")
                    SourceChangePoint.INPUT_CLOSE -> if (image) listOf("exists") else listOf("exists", write)
                    SourceChangePoint.WRITE_RETURN -> listOf("exists", write)
                    SourceChangePoint.LOCAL_COPY_RETURN -> listOf("exists", write, "copyToLocal")
                }
                fixture.assertOperations(*expected.toTypedArray())
                val opened = if (point == SourceChangePoint.CAPTURE_RETURN || point == SourceChangePoint.EXISTS_RETURN) 0 else 1
                assertEquals(label, opened, fixture.source.opens.get())
                assertEquals("$label must close every acquired stream", opened, fixture.source.closes.get())
                assertNull(label, WorkspaceExecutionContext.current())
            }
        }
    }

    @Test
    fun cancellingTheCoroutineDuringImageReadStopsWritesEvenWhenTheSourceIsStillCurrent() {
        val fixture = UploadFixture(temporary, imageBytes())
        val operation = Job()
        val cancelled = CancellationException("Attachment owner cancelled")
        fixture.source.onRead = { operation.cancel(cancelled) }
        try {
            val failure = assertThrows(CancellationException::class.java) {
                runBlocking { withContext(operation) { fixture.upload(includeImageData = true) } }
            }

            assertEquals(cancelled.message, failure.message)
            assertTrue(operation.isCancelled)
            assertTrue("A current source must not hide coroutine cancellation", fixture.current.get())
            fixture.assertOperations("exists")
            assertEquals(1, fixture.source.opens.get())
            assertEquals(1, fixture.source.closes.get())
            assertNull(WorkspaceExecutionContext.current())
        } finally {
            operation.cancel()
        }
    }

    @Test
    fun cancellationThrownByTheResolverStreamPropagatesAndClosesBothKindsOfInput() {
        for (image in listOf(true, false)) {
            val fixture = UploadFixture(temporary, imageBytes())
            val cancelled = CancellationException("Content source cancelled: image=$image")
            fixture.source.onRead = { throw cancelled }

            val failure = assertThrows(CancellationException::class.java) {
                runBlocking { fixture.upload(includeImageData = image) }
            }

            assertEquals(cancelled.message, failure.message)
            if (image) fixture.assertOperations("exists") else fixture.assertOperations("exists", "writeStream")
            assertEquals(1, fixture.source.opens.get())
            assertEquals(1, fixture.source.closes.get())
            assertNull(WorkspaceExecutionContext.current())
        }
    }

    @Test
    fun duplicateNamesKeepExistingBytesAndUseOneCaptureForEveryCandidateAndResultPath() = runBlocking {
        val fixture = UploadFixture(temporary, imageBytes())
        val existing = "existing original".toByteArray()
        val existingSuffix = "existing suffix".toByteArray()
        fixture.stored["$ATTACHMENTS_DIR/image.png"] = existing
        fixture.stored["$ATTACHMENTS_DIR/image-1.png"] = existingSuffix

        val uploaded = fixture.upload(includeImageData = true)

        fixture.assertOperations("exists", "exists", "exists", "writeBytes", "copyToLocal")
        assertEquals(
            listOf("$ATTACHMENTS_DIR/image.png", "$ATTACHMENTS_DIR/image-1.png", "$ATTACHMENTS_DIR/image-2.png"),
            fixture.operations.filter { it.first == "exists" }.map { it.second }
        )
        fixture.assertUploaded(uploaded, "image-2.png")
        assertEquals(uploaded.containerPath, requireNotNull(uploaded.image).path)
        assertArrayEquals(fixture.source.bytes, Base64.getDecoder().decode(requireNotNull(uploaded.image).base64Data))
        assertArrayEquals(existing, fixture.stored["$ATTACHMENTS_DIR/image.png"])
        assertArrayEquals(existingSuffix, fixture.stored["$ATTACHMENTS_DIR/image-1.png"])
        assertEquals(3, fixture.stored.size)
        assertNull(WorkspaceExecutionContext.current())
    }

    private enum class SourceChangePoint {
        CAPTURE_RETURN, EXISTS_RETURN, INPUT_CLOSE, WRITE_RETURN, LOCAL_COPY_RETURN
    }

    private class UploadFixture(
        temporary: TemporaryFolder,
        bytes: ByteArray,
        name: String = "image.png",
        mimeType: String = "image/png",
        uri: Uri = Uri.parse("content://chat.attachment.tests/document/1")
    ) {
        val context = RuntimeEnvironment.getApplication()
        val source = ResolverSource(context, uri, name, mimeType, bytes)
        private val localRoot = temporary.newFolder()
        private val owner = RuntimeOwner(RuntimeOwnerKind.FILE, "attachment-session")
        private val identity = "chroot:attachment:${localRoot.name}"
        val current = AtomicBoolean(true)
        private val sourceChecks = AtomicInteger()
        val captures = mutableListOf<String>()
        val operations = mutableListOf<Pair<String, String>>()
        val stored = linkedMapOf<String, ByteArray>()
        private val localCopies = mutableMapOf<String, File>()
        val access = mockk<FileAccessProvider>()
        val execution = executionFor(owner)
        var expectedCaptureContext: WorkspaceExecutionContext? = null
        var onCapture: () -> Unit = {}
        var afterOperation: (String) -> Unit = {}

        init {
            every { access.exists(any()) } answers {
                val path = firstArg<String>()
                record("exists", path)
                stored.containsKey(path).also { afterOperation("exists") }
            }
            every { access.writeBytes(any(), any(), any()) } answers {
                val path = firstArg<String>()
                record("writeBytes", path)
                assertTrue("Images retain the helper's overwrite contract", thirdArg<Boolean>())
                stored[path] = secondArg<ByteArray>().copyOf()
                afterOperation("writeBytes")
            }
            every { access.writeStream(any(), any(), any()) } answers {
                val path = firstArg<String>()
                record("writeStream", path)
                assertTrue(thirdArg<Boolean>())
                assertEquals("Ordinary files must not be read eagerly", 0, source.reads.get())
                assertEquals("The stream must stay open throughout writeStream", 0, source.closes.get())
                val output = ByteArrayOutputStream()
                val count = secondArg<InputStream>().copyTo(output)
                stored[path] = output.toByteArray()
                afterOperation("writeStream")
                count
            }
            every { access.copyToLocal(any()) } answers {
                val path = firstArg<String>()
                record("copyToLocal", path)
                assertEquals("The source must be closed before copying to local", 1, source.closes.get())
                val file = File(localRoot, path.substringAfterLast('/'))
                file.writeBytes(requireNotNull(stored[path]) { "Local copy must use the uploaded path: $path" })
                localCopies[path] = file
                afterOperation("copyToLocal")
                file
            }
        }

        fun executionFor(runtimeOwner: RuntimeOwner): WorkspaceExecutionContext {
            val runtime = LocalRuntimeCapture(
                runtimeOwner, "attachment-fixture", 1, ChrootInstance("attachment-fixture", 1, 2, 3),
                localRoot.absolutePath, "/root", identity, Any(), ::checkSource
            )
            return WorkspaceExecutionContext(
                ExecutionMode.LOCAL_PROOT, identity, access, null, null,
                runtimeOwner = runtimeOwner, localRuntimeCapture = runtime, checkSource = ::checkSource
            )
        }

        private fun checkSource() {
            sourceChecks.incrementAndGet()
            if (!current.get()) throw CancellationException(SOURCE_CHANGED)
        }

        private suspend fun capture(path: String): WorkspaceExecutionContext {
            captures += path
            assertEquals(ATTACHMENTS_DIR, path)
            assertSame("Capture must run after entering IO", Dispatchers.IO, currentCoroutineContext()[ContinuationInterceptor])
            assertSame(expectedCaptureContext, WorkspaceExecutionContext.current())
            onCapture()
            return execution
        }

        suspend fun upload(includeImageData: Boolean = false): UploadedWorkspaceFile = copyUriToWorkspace(
            context, source.uri, access, captureFile = ::capture, includeImageData = includeImageData
        )

        private fun record(operation: String, path: String) {
            val installed = WorkspaceExecutionContext.current() ?: throw LocalRuntimeAuthorizationRequired()
            assertSame("$operation must retain the one FILE capture", execution, installed)
            assertEquals(owner, installed.runtimeOwner)
            assertEquals(RuntimeOwnerKind.FILE, installed.runtimeOwner?.kind)
            assertSame(execution.localRuntimeCapture, installed.localRuntimeCapture)
            assertEquals(ATTACHMENTS_DIR, path.substringBeforeLast('/'))
            operations += operation to path
            // Do not call checkCurrent here: the helper must detect source changes itself.
        }

        fun assertOperations(vararg expected: String) {
            assertEquals("Every attempt captures the attachment directory exactly once", listOf(ATTACHMENTS_DIR), captures)
            assertEquals(expected.toList(), operations.map { it.first })
        }

        fun assertUploaded(uploaded: UploadedWorkspaceFile, expectedName: String = source.name) {
            val path = "$ATTACHMENTS_DIR/$expectedName"
            assertTrue("The captured source must be checked", sourceChecks.get() > 0)
            assertEquals(expectedName, uploaded.fileName)
            assertEquals(path, uploaded.containerPath)
            assertEquals(source.mimeType, uploaded.mimeType)
            assertEquals(source.bytes.size.toLong(), uploaded.sizeBytes)
            assertArrayEquals("The workspace retains original bytes", source.bytes, stored[path])
            assertEquals(requireNotNull(localCopies[path]).absolutePath, uploaded.localPath)
            assertArrayEquals("The local copy also retains original bytes", source.bytes, File(uploaded.localPath).readBytes())
            assertEquals(1, source.opens.get())
            assertEquals(1, source.closes.get())
        }
    }

    private class ResolverSource(
        context: Application,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val bytes: ByteArray
    ) {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        val bytesRead = AtomicInteger()
        var onRead: () -> Unit = {}
        var onClose: () -> Unit = {}

        init {
            // Metadata and MIME go through an actual ContentResolver/ContentProvider. Register only
            // the byte stream with Robolectric so its lifetime and cancellation remain observable.
            val provider = object : ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun getType(uri: Uri): String {
                    assertEquals(this@ResolverSource.uri, uri)
                    return mimeType
                }
                override fun query(
                    uri: Uri, projection: Array<out String>?, selection: String?,
                    selectionArgs: Array<out String>?, sortOrder: String?
                ): Cursor {
                    assertEquals(this@ResolverSource.uri, uri)
                    val columns = projection?.toList()?.toTypedArray()
                        ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                    return MatrixCursor(columns).apply {
                        addRow(columns.map { column ->
                            when (column) {
                                OpenableColumns.DISPLAY_NAME -> name
                                OpenableColumns.SIZE -> bytes.size.toLong()
                                else -> throw AssertionError("Unexpected metadata column: $column")
                            }
                        }.toTypedArray())
                    }
                }
                override fun insert(uri: Uri, values: ContentValues?): Uri = throw AssertionError("Read-only source")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
                    throw AssertionError("Read-only source")
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                    throw AssertionError("Read-only source")
            }
            val authority = requireNotNull(uri.authority)
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
                opens.incrementAndGet()
                object : ByteArrayInputStream(bytes) {
                    private val closed = AtomicBoolean()
                    override fun read(): Int = throw AssertionError("Upload must use bulk reads")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        assertFalse("Cannot read a closed provider stream", closed.get())
                        reads.incrementAndGet()
                        val count = super.read(buffer, offset, minOf(length, 4096))
                        if (count > 0) {
                            bytesRead.addAndGet(count)
                            onRead()
                        }
                        return count
                    }
                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            closes.incrementAndGet()
                            super.close()
                            onClose()
                        }
                    }
                }
            }
        }
    }

    private fun imageBytes(
        width: Int = 64,
        height: Int = 32,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        noisy: Boolean = false
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (noisy) {
                val random = Random(42)
                val pixels = IntArray(width * height) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                bitmap.eraseColor(Color.RED)
            }
            if (format == Bitmap.CompressFormat.JPEG) bitmap.setHasAlpha(false)
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ATTACHMENTS_DIR = "~/workspace/.aicode/attachments"
        const val SOURCE_CHANGED = "Attachment source changed"
    }
}
