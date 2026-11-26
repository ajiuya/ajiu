package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.aicode.core.ui.ImageSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChatImageLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun containerCaptureRunsOnceAndBothCacheProducersRetainItsFileOwnerOriginalJobAndMarker() {
        val fixture = ChatImageTestFixture(temporary)
        val owner = RuntimeOwner(RuntimeOwnerKind.FILE, "trusted-image-viewer")
        val execution = fixture.execution(owner)
        val operation = Job()
        val marker = Marker()
        val calls = AtomicInteger()
        val observed = ConcurrentLinkedQueue<String>()
        val loader = chatImageLoader(fixture.access, 128, 10_000) { path ->
            assertEquals(fixture.path, path)
            calls.incrementAndGet()
            execution
        }
        fixture.onOperation = { operationName ->
            assertSame("$operationName lost the captured workspace", execution, WorkspaceExecutionContext.current())
            assertEquals(owner, WorkspaceExecutionContext.current()!!.runtimeOwner)
            assertSame("$operationName lost the original operation Job", operation, WorkspaceExecutionContext.blockingContext()[Job])
            assertSame("$operationName lost another context element", marker, WorkspaceExecutionContext.blockingContext()[Marker])
            observed += operationName
        }
        try {
            val image = runBlocking(execution.ownedBlockingContext(operation) + marker) {
                loader(ImageSource.ContainerPath(fixture.path))
            }
            assertNotNull(image)
            assertEquals(64, image!!.width)
            assertEquals(32, image.height)
            assertEquals(1, calls.get())
            assertTrue(observed.containsAll(listOf("identity", "size", "modified", "open", "read")))
            assertEquals(1, fixture.opens.get())
            assertEquals(1, fixture.closes.get())
            assertEquals(0, fixture.copies.get())
            assertFalse("cache work must not complete the original owner Job", operation.isCompleted)
            assertNull(WorkspaceExecutionContext.current())
        } finally { operation.cancel() }
    }

    @Test
    fun aContainerImageReplacesAnInheritedChatOwnerWithTheExplicitFileCapture() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val chat = fixture.execution(RuntimeOwner(RuntimeOwnerKind.AGENT, "chat-with-root-authorization"))
        val files = fixture.execution(RuntimeOwner(RuntimeOwnerKind.FILE, "file-viewer"))
        val calls = AtomicInteger()
        fixture.onOperation = {
            assertSame(files, WorkspaceExecutionContext.current())
            assertEquals(RuntimeOwnerKind.FILE, WorkspaceExecutionContext.current()!!.runtimeOwner!!.kind)
        }
        val loader = chatImageLoader(fixture.access, 128, 10_000) { path ->
            assertEquals(fixture.path, path)
            assertSame(chat, WorkspaceExecutionContext.current())
            calls.incrementAndGet()
            files
        }
        val image = withContext(chat) { loader(ImageSource.ContainerPath(fixture.path)) }
        assertNotNull(image)
        assertEquals(1, calls.get())
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun deniedFileCaptureCannotReadOrReuseACachedImageFromAnAuthorizedChat() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val chat = fixture.execution(RuntimeOwner(RuntimeOwnerKind.AGENT, "authorized-chat"))
        val warm = chatImageLoader(fixture.access, 128, 10_000)
        withContext(chat) { assertNotNull(warm(ImageSource.ContainerPath(fixture.path))) }
        val calls = AtomicInteger()
        val metadataBefore = fixture.metadataReads.get()
        fixture.onOperation = { fail("Denied FILE capture must not consult the inherited chat backend: $it") }
        val loader = chatImageLoader(fixture.access, 128, 10_000) { path ->
            assertEquals(fixture.path, path)
            calls.incrementAndGet()
            throw LocalRuntimeAuthorizationRequired()
        }
        withContext(chat) { assertNull(loader(ImageSource.ContainerPath(fixture.path))) }
        assertEquals(1, calls.get())
        assertEquals(metadataBefore, fixture.metadataReads.get())
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun localFilesAndBase64DecodeWithoutAFileCaptureOrAnyWorkspaceBackendReads() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val calls = AtomicInteger()
        val loader = chatImageLoader(fixture.access, 128, 10_000) {
            calls.incrementAndGet()
            throw LocalRuntimeAuthorizationRequired()
        }
        fixture.onOperation = { fail("Host attachments must not consult the workspace backend: $it") }
        val local = ImageSource.LocalFile(fixture.file.absolutePath)
        val encoded = ImageSource.Base64(Base64.getEncoder().encodeToString(fixture.file.readBytes()))
        val fromFile = requireNotNull(loader(local))
        val fromBytes = requireNotNull(loader(encoded))
        assertEquals(64, fromFile.width)
        assertEquals(32, fromFile.height)
        assertEquals(64, fromBytes.width)
        assertEquals(32, fromBytes.height)
        assertSame(fromFile, loader(local))
        assertSame(fromBytes, loader(encoded))
        assertEquals(0, calls.get())
        assertEquals(0, fixture.metadataReads.get())
        assertEquals(0, fixture.opens.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun eachContainerLoadCapturesOnceEvenWhenItsDecodedImageIsCached() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val execution = fixture.execution()
        val calls = AtomicInteger()
        val loader = chatImageLoader(fixture.access, 128, 10_000) { path ->
            assertEquals(fixture.path, path)
            calls.incrementAndGet()
            execution
        }
        val first = requireNotNull(loader(ImageSource.ContainerPath(fixture.path)))
        assertEquals(1, calls.get())
        assertSame(first, loader(ImageSource.ContainerPath(fixture.path)))
        assertEquals(2, calls.get())
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
    }

    @Test
    fun aCancelledCaptureIsPropagatedBeforeAnyBackendRead() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val cancelled = CancellationException("Image capture cancelled")
        val calls = AtomicInteger()
        val loader = chatImageLoader(fixture.access, 128, 10_000) {
            calls.incrementAndGet()
            throw cancelled
        }
        fixture.onOperation = { fail("A cancelled capture must not read: $it") }
        val failure = runCatching { loader(ImageSource.ContainerPath(fixture.path)) }.exceptionOrNull()
        assertTrue("capture cancellation must be propagated: $failure", failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertEquals(1, calls.get())
        assertEquals(0, fixture.metadataReads.get())
        assertEquals(0, fixture.opens.get())
    }

    @Test
    fun aCapturedSourceChangeDuringStreamingCancelsAndDoesNotPoisonARetry() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val current = AtomicBoolean(true)
        val execution = fixture.execution {
            if (!current.get()) throw CancellationException("File viewer source changed")
        }
        val calls = AtomicInteger()
        val loader = chatImageLoader(fixture.access, 128, 10_000) {
            calls.incrementAndGet()
            execution
        }
        fixture.onOperation = { if (it == "read") current.set(false) }
        val failure = runCatching { loader(ImageSource.ContainerPath(fixture.path)) }.exceptionOrNull()
        assertTrue("captured source changes must propagate, not turn into null: $failure", failure is CancellationException)
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(1, calls.get())
        current.set(true)
        fixture.onOperation = {}
        fixture.writePng(48, 24, Color.BLUE)
        val retry: ImageBitmap = requireNotNull(loader(ImageSource.ContainerPath(fixture.path)))
        assertEquals(48, retry.width)
        assertEquals(24, retry.height)
        assertSame(retry, loader(ImageSource.ContainerPath(fixture.path)))
        assertEquals(3, calls.get())
        assertEquals(2, fixture.opens.get())
        assertEquals(2, fixture.closes.get())
    }

    @Test
    fun markdownLocalImagesCaptureTheExactFileSourceBeforeBothCacheProducers() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val execution = fixture.execution(RuntimeOwner(RuntimeOwnerKind.FILE, "markdown-file-source"))
        val calls = AtomicInteger()
        fixture.onOperation = { assertSame(execution, WorkspaceExecutionContext.current()) }
        val transformer = MarkdownImageTransformer(fixture.access) { path ->
            assertEquals(fixture.path, path)
            calls.incrementAndGet()
            execution
        }
        assertNotNull(transformer.decode(fixture.path))
        assertNotNull(transformer.decode(fixture.path))
        assertEquals(2, calls.get())
        assertEquals(1, fixture.opens.get())
        assertEquals(1, fixture.closes.get())
        assertEquals(0, fixture.copies.get())
    }

    @Test
    fun markdownNetworkAndContentProtocolsNeverCaptureOrReadWorkspaceFiles() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val calls = AtomicInteger()
        val transformer = MarkdownImageTransformer(fixture.access) {
            calls.incrementAndGet()
            throw LocalRuntimeAuthorizationRequired()
        }
        fixture.onOperation = { fail("Non-file Markdown images must not read: $it") }
        listOf("", "https://cdn.example/image.png", "http://cdn.example/image.png", "content://image/1").forEach {
            assertNull(transformer.decode(it))
        }
        assertEquals(0, calls.get())
        assertEquals(0, fixture.metadataReads.get())
        assertEquals(0, fixture.opens.get())
    }

    @Test
    fun markdownFileAuthorizationDenialCannotBorrowChatAndCancellationStillPropagates() = runBlocking {
        val fixture = ChatImageTestFixture(temporary)
        val chat = fixture.execution(RuntimeOwner(RuntimeOwnerKind.AGENT, "markdown-chat"))
        fixture.onOperation = { fail("Denied Markdown capture must not read: $it") }
        val denied = MarkdownImageTransformer(fixture.access) { throw LocalRuntimeAuthorizationRequired() }
        withContext(chat) { assertNull(denied.decode(fixture.path)) }
        val cancelled = CancellationException("Markdown source cancelled")
        val stopped = MarkdownImageTransformer(fixture.access) { throw cancelled }
        val failure = runCatching { stopped.decode(fixture.path) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertEquals(0, fixture.metadataReads.get())
        assertEquals(0, fixture.opens.get())
    }

    private class Marker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Marker>
    }
}
