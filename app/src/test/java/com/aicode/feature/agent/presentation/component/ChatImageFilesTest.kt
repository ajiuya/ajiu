package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import com.aicode.core.ui.ImageDecodeSpec
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.DelegatingFileAccess
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FilePathSource
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.localRootPath
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChatImageFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun sameSourceSharesDecodedImagesAndDifferentSpecsReuseTheClosedStreamingLease() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val execution = fixture.execution()
        withContext(execution) {
            val first = requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, ImageDecodeSpec(32, 10_000)))
            assertEquals(32, first.width)
            assertEquals(16, first.height)
            assertSame(first, ChatImageFiles.load(fixture.access, fixture.path, ImageDecodeSpec(32, 10_000)))
            val detail = requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, ImageDecodeSpec(64, 10_000)))
            assertNotSame(first, detail)
            assertEquals(64, detail.width)
            assertEquals(32, detail.height)
            assertSame(detail, ChatImageFiles.load(fixture.access, fixture.path, ImageDecodeSpec(64, 10_000)))
            assertFalse(first.asAndroidBitmap().isRecycled)
        }
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun backendIdentityChangesSeparateEqualPathsAndMetadataWithoutAnExecutionCapture() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val spec = ImageDecodeSpec(128, 10_000)
        val original = requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        fixture.identity += ":next-source"
        fixture.writePng(48, 24, Color.BLUE)
        val replacement = requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        assertNotSame(original, replacement)
        assertEquals(48, replacement.width)
        assertEquals(24, replacement.height)
        assertSame(replacement, ChatImageFiles.load(fixture.access, fixture.path, spec))
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
    }

    @Test
    fun fakeSharedBackendTokensAndMetadataCannotCrossFileOwnersOrCapturedExecutionIdentities() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val backendIdentity = fixture.identity
        val firstOwner = RuntimeOwner(RuntimeOwnerKind.FILE, "first-viewer")
        val otherOwner = RuntimeOwner(RuntimeOwnerKind.FILE, "other-viewer")
        val spec = ImageDecodeSpec(128, 10_000)
        val first = withContext(fixture.execution(firstOwner, backendIdentity)) {
            requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        withContext(fixture.execution(firstOwner, backendIdentity)) {
            assertSame(first, ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        fixture.writePng(48, 24, Color.BLUE)
        val other = withContext(fixture.execution(otherOwner, backendIdentity)) {
            requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        assertNotSame(first, other)
        assertEquals(48, other.width)
        fixture.writePng(24, 12, Color.GREEN)
        val recaptured = withContext(fixture.execution(otherOwner, "$backendIdentity:recaptured")) {
            requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        assertNotSame(other, recaptured)
        assertEquals(24, recaptured.width)
        assertEquals(12, recaptured.height)
        withContext(fixture.execution(otherOwner, "$backendIdentity:recaptured")) {
            assertSame(recaptured, ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        assertEquals(backendIdentity, fixture.identity)
        assertEquals(3, fixture.opens.get())
        assertEquals(3, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun legacyBackendSourceChangeDuringReadClosesTheStreamAndDoesNotPublishEitherCache() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val originalIdentity = fixture.identity
        fixture.streamFactory = {
            object : FilterInputStream(fixture.file.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, minOf(8, len)).also { if (it > 0) fixture.identity = "$originalIdentity:changed" }
            }
        }
        val spec = ImageDecodeSpec(128, 10_000)
        val failure = runCatching { ChatImageFiles.load(fixture.access, fixture.path, spec) }.exceptionOrNull()
        assertTrue("source changes must cancel rather than decode partial data: $failure", failure is CancellationException)
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        fixture.identity = originalIdentity
        fixture.writePng(48, 24, Color.BLUE)
        fixture.streamFactory = { fixture.file.inputStream() }
        val retry = requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        assertEquals(48, retry.width)
        assertEquals(24, retry.height)
        assertSame(retry, ChatImageFiles.load(fixture.access, fixture.path, spec))
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
    }

    @Test
    fun capturedPathChecksRejectAStreamSwitchWithoutConsultingThePlainBaseIdentity() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val routed = mockk<DelegatingFileAccess>()
        val current = AtomicBoolean(true)
        val authorizations = AtomicInteger()
        val sourceChecks = AtomicInteger()
        fun checkCurrent() {
            sourceChecks.incrementAndGet()
            if (!current.get()) throw CancellationException("Captured image path changed")
        }
        val source = FilePathSource("path-source:${temporary.root.name}", fixture.identity,
            verifyAuthorization = { authorizations.incrementAndGet(); checkCurrent() }, check = ::checkCurrent)
        every { routed.capturePathSource(fixture.path) } returns source
        every { routed.cacheIdentity() } throws LocalRuntimeAuthorizationRequired()
        every { routed.fileSize(fixture.path) } answers { fixture.access.fileSize(fixture.path) }
        every { routed.lastModified(fixture.path) } answers { fixture.access.lastModified(fixture.path) }
        every { routed.openRead(fixture.path, fixture.identity) } answers { fixture.access.openRead(fixture.path, fixture.identity) }
        fixture.streamFactory = {
            object : FilterInputStream(fixture.file.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, minOf(8, len)).also { if (it > 0) current.set(false) }
            }
        }
        val spec = ImageDecodeSpec(128, 10_000)
        val failure = runCatching { ChatImageFiles.load(routed, fixture.path, spec) }.exceptionOrNull()
        assertTrue("captured source checks must reject the stream: $failure", failure is CancellationException)
        assertTrue(authorizations.get() > 0)
        assertTrue(sourceChecks.get() > authorizations.get())
        assertEquals(1, fixture.closes.get())
        current.set(true)
        fixture.writePng(48, 24, Color.BLUE)
        fixture.streamFactory = { fixture.file.inputStream() }
        val retry = requireNotNull(ChatImageFiles.load(routed, fixture.path, spec))
        assertEquals(48, retry.width)
        assertSame(retry, ChatImageFiles.load(routed, fixture.path, spec))
        current.set(false)
        val revoked = runCatching { ChatImageFiles.load(routed, fixture.path, spec) }.exceptionOrNull()
        assertTrue("cached images still require a current path authorization: $revoked", revoked is CancellationException)
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
        verify(exactly = 0) { routed.cacheIdentity() }
        verify(exactly = 0) { routed.copyToLocal(any()) }
    }

    @Test(timeout = 15_000)
    fun cancellingTheOriginalOwnerClosesABlockedStreamAndAllowsAnUncachedRetry() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val readTimedOut = AtomicBoolean()
        val operation = Job()
        fixture.streamFactory = {
            object : InputStream() {
                override fun read(): Int = error("buffered reads required")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    entered.countDown()
                    if (!closed.await(5, TimeUnit.SECONDS)) {
                        readTimedOut.set(true)
                        throw IOException("Timed out waiting for image stream cancellation")
                    }
                    throw IOException("Image stream closed during read")
                }
                override fun close() { closed.countDown() }
            }
        }
        val execution = fixture.execution(job = operation)
        val spec = ImageDecodeSpec(128, 10_000)
        val loading = async(Dispatchers.Default) {
            withContext(execution.ownedBlockingContext(operation)) {
                ChatImageFiles.load(fixture.access, fixture.path, spec)
            }
        }
        try {
            assertTrue("image read never started", entered.await(5, TimeUnit.SECONDS))
            operation.cancel(CancellationException("Viewer closed"))
            assertTrue("cancellation did not close the blocked stream", closed.await(5, TimeUnit.SECONDS))
            assertFalse("a read timeout must not stand in for cancellation cleanup", readTimedOut.get())
            withTimeout(5_000) { operation.join() }
            val failure = runCatching { withTimeout(5_000) { loading.await() } }.exceptionOrNull()
            assertTrue("cancelled loads must not publish: $failure", failure is CancellationException)
            assertFalse("a test timeout is not cancellation evidence", failure is TimeoutCancellationException)
            assertTrue(loading.isCancelled)
            assertEquals(1, fixture.closes.get())
        } finally {
            operation.cancel()
            loading.cancelAndJoin()
        }
        fixture.streamFactory = { fixture.file.inputStream() }
        fixture.writePng(48, 24, Color.BLUE)
        val retry = withContext(fixture.execution()) {
            requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, spec))
        }
        assertEquals(48, retry.width)
        assertEquals(24, retry.height)
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
    }

    @Test
    fun independentlyAuthorizedSafImageIsNotBlockedByTheDefaultChrootIdentity() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val path = localRootPath("chat-image", temporary.root.name) + "/image.png"
        val grant = LocalFolderGrant("chat-image", temporary.root.name, "images", path.substringBeforeLast('/'), true)
        val authorizations = AtomicInteger()
        val snapshot = LocalFolderSnapshot(7, listOf(grant), listOf(CapturedLocalFolderRoot(grant, fixture.file.parentFile)),
            checkAuthorization = { authorizations.incrementAndGet() }) {}
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        val folders = mockk<LocalFolderGrantRepository>()
        every { folders.snapshot() } returns snapshot
        every { local.cacheIdentity() } throws LocalRuntimeAuthorizationRequired()
        val access = DelegatingFileAccess(ExecutionModeHolder(), local, remote, folders)
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { access.cacheIdentity() }
        val spec = ImageDecodeSpec(128, 10_000)
        val first = requireNotNull(ChatImageFiles.load(access, path, spec))
        assertEquals(64, first.width)
        assertEquals(32, first.height)
        assertSame(first, ChatImageFiles.load(access, path, spec))
        assertTrue(authorizations.get() > 0)
        verify(exactly = 0) { local.fileSize(any()) }
        verify(exactly = 0) { local.openRead(any(), any()) }
        verify(exactly = 0) { local.copyToLocal(any()) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
    }

    @Test
    fun pathSourceIdentitySeparatesEqualBaseTokensPathsAndMetadata() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val access = mockk<DelegatingFileAccess>()
        val expectedBase = fixture.identity
        var pathIdentity = "path-source:first:${temporary.root.name}"
        every { access.capturePathSource(fixture.path) } answers {
            FilePathSource(pathIdentity, expectedBase, verifyAuthorization = {}, check = {})
        }
        every { access.cacheIdentity() } throws LocalRuntimeAuthorizationRequired()
        every { access.fileSize(fixture.path) } answers { fixture.access.fileSize(fixture.path) }
        every { access.lastModified(fixture.path) } answers { fixture.access.lastModified(fixture.path) }
        every { access.openRead(fixture.path, expectedBase) } answers { fixture.access.openRead(fixture.path, expectedBase) }
        val spec = ImageDecodeSpec(128, 10_000)
        val original = requireNotNull(ChatImageFiles.load(access, fixture.path, spec))
        pathIdentity = "path-source:replacement:${temporary.root.name}"
        fixture.writePng(48, 24, Color.BLUE)
        val replacement = requireNotNull(ChatImageFiles.load(access, fixture.path, spec))
        assertNotSame(original, replacement)
        assertEquals(48, replacement.width)
        assertSame(replacement, ChatImageFiles.load(access, fixture.path, spec))
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
        verify(exactly = 0) { access.cacheIdentity() }
    }

    @Test
    fun streamingDecodeRetainsTheExistingEdgeAndPixelBounds() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        fixture.writePng(1024, 512, Color.RED)
        val image = withContext(fixture.execution()) {
            requireNotNull(ChatImageFiles.load(fixture.access, fixture.path, ImageDecodeSpec(512, 32_768)))
        }
        assertEquals(256, image.width)
        assertEquals(128, image.height)
        assertTrue(image.width.toLong() * image.height <= 32_768)
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }
}

internal class ChatImageTestFixture(temporary: TemporaryFolder) {
    val file: File = temporary.newFile("image.png")
    val path = "~/workspace/${temporary.root.name}/image.png"
    @Volatile var identity = "chroot:${temporary.root.name}"
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val copies = AtomicInteger()
    val metadataReads = AtomicInteger()
    var onOperation: (String) -> Unit = {}
    var streamFactory: () -> InputStream = { file.inputStream() }

    init { writePng(64, 32, Color.RED) }

    fun writePng(width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    fun execution(
        owner: RuntimeOwner = RuntimeOwner(RuntimeOwnerKind.FILE, "image-viewer"),
        fileIdentity: String = identity,
        job: Job? = null,
        check: () -> Unit = {}
    ): WorkspaceExecutionContext {
        val runtime = LocalRuntimeCapture(owner, "image-fixture", 1, ChrootInstance("image-fixture", 1, 2, 3),
            file.parent, "/root", fileIdentity, Any(), check)
        return WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, fileIdentity, access, null, null,
            runtimeOwner = owner, localRuntimeCapture = runtime, cancellationJob = job, checkSource = check)
    }

    val access: FileAccessProvider = object : FileAccessProvider by TestFileAccessProvider() {
        override fun cacheIdentity(): String {
            onOperation("identity")
            return identity
        }
        override fun fileSize(path: String): Long {
            assertEquals(this@ChatImageTestFixture.path, path)
            onOperation("size")
            metadataReads.incrementAndGet()
            return 4096L
        }
        override fun lastModified(path: String): Long {
            assertEquals(this@ChatImageTestFixture.path, path)
            onOperation("modified")
            metadataReads.incrementAndGet()
            return 42L
        }
        override fun openRead(path: String): InputStream = error("Images must open with an expected source identity")
        override fun openRead(path: String, expectedCacheIdentity: String): InputStream {
            assertEquals(this@ChatImageTestFixture.path, path)
            assertEquals(identity, expectedCacheIdentity)
            onOperation("open")
            opens.incrementAndGet()
            val raw = streamFactory()
            val closed = AtomicBoolean()
            val tracked = object : FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    onOperation("read")
                    return raw.read(b, off, len)
                }
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        closes.incrementAndGet()
                        raw.close()
                    }
                }
            }
            return WorkspaceExecutionContext.current()?.protectRead(tracked) ?: tracked
        }
        override fun copyToLocal(path: String): File {
            copies.incrementAndGet()
            onOperation("copy")
            return file
        }
        override fun readBytes(path: String): ByteArray = error("Images must stream rather than read all bytes")
        override fun readBytes(path: String, maxBytes: Long): ByteArray = error("Images must stream rather than read all bytes")
    }
}
