package com.aicode.feature.agent.presentation.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.util.concurrent.Executors

class PreviewImageLeaseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun filePayloadUsesIndependentClosedStreamsAndSeparate480And2048Samples() = runBlocking {
        val file = temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val streams = mutableListOf<FileInputStream>()
        for ((edge, expected) in listOf(480 to 32, 2048 to 4)) {
            val value = loadVersionedPreviewFileImage(
                read = { PreviewImageFile(PreviewImageKey("source", "/image.png", "version"), file, { true }, { true }) },
                maxEdge = edge, decodeBytes = { error("local files must not be read into bytes") },
                decodeBounds = { streams += it as FileInputStream; 8192 to 4096 },
                decodeFile = { input, sample -> streams += input as FileInputStream; assertEquals(expected, sample); "image-$edge" },
                discard = { fail("published image must not be discarded") },
            )
            assertEquals("image-$edge", value)
        }
        assertEquals(4, streams.size)
        assertTrue(streams.all { !it.fd.valid() })
        assertNotSame(streams[0], streams[1])
    }

    @Test fun sourceOrVersionSwitchBetweenBoundsAndPixelsRejectsWithoutPublishing() = runBlocking {
        val file = temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1)) }
        for (targetSwitch in listOf(false, true)) {
            var current = true
            var sameTarget = true
            var pixels = 0
            val value = loadVersionedPreviewFileImage(
                read = { PreviewImageFile(PreviewImageKey("s", "/image.png", "v1"), file, { current }, { sameTarget }) },
                maxEdge = 480, decodeBytes = { error("unexpected bytes") },
                decodeBounds = { if (targetSwitch) sameTarget = false else current = false; 8000 to 4000 },
                decodeFile = { _, _ -> pixels++; "stale image" }, discard = { fail("pixels must not be decoded") },
            )
            assertNull(value)
            assertEquals(0, pixels)
        }
    }

    @Test fun validationAfterPixelsDiscardsOnlyUndeliveredImages() = runBlocking {
        val file = temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1)) }
        var current = true
        var discarded = 0
        val value = loadVersionedPreviewFileImage(
            read = { PreviewImageFile(PreviewImageKey("s", "/image.png", "v"), file, { current }, { true }) },
            maxEdge = 480, decodeBytes = { error("unexpected bytes") }, decodeBounds = { 480 to 320 },
            decodeFile = { _, _ -> current = false; "stale" }, discard = { assertEquals("stale", it); discarded++ },
        )
        assertNull(value)
        assertEquals(1, discarded)
    }

    @Test fun closingOrCancellingDuringDecodePreventsPublicationAndDiscardsPendingImage() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease = PreviewImageLease<String>(PreviewImageKey("source", "/image.png", "v1"))
            var discarded = 0
            val loading = async {
                loadPreviewImage(lease, read = { byteArrayOf(1) }, decode = {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                    "late"
                }, discard = { discarded++ }, io = worker, cpu = worker)
            }
            entered.await()
            lease.close()
            release.complete(Unit)
            assertNull(loading.await())
            assertEquals(1, discarded)
        }
    }

    @Test fun bytePayloadRejectsAConnectionSwitchDuringDecode() = runBlocking {
        var current = true
        var discarded = 0
        val value = loadVersionedPreviewFileImage(
            read = { PreviewImageBytes(PreviewImageKey("remote:a", "/image.png", "v"), byteArrayOf(1), { current }) },
            maxEdge = 480,
            decodeBytes = { current = false; "old server image" },
            decodeBounds = { error("unexpected file") },
            decodeFile = { _, _ -> error("unexpected file") },
            discard = { assertEquals("old server image", it); discarded++ },
        )
        assertNull(value)
        assertEquals(1, discarded)
    }

    @Test fun cancellationInterruptsBlockedImageReadsAndClosesTheirOwnedResource() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val loading = async {
            loadVersionedPreviewFileImage(
                read = {
                    try {
                        entered.countDown()
                        CountDownLatch(1).await()
                        error("read should have been interrupted")
                    } finally { closed.countDown() }
                },
                maxEdge = 480, decodeBytes = { "image" },
                decodeBounds = { error("unexpected file") }, decodeFile = { _, _ -> error("unexpected file") },
                discard = { fail("no image was decoded") },
            )
        }
        kotlinx.coroutines.yield()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        loading.cancelAndJoin()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun readerValidatesPathsAndKeepsSourcePathVersionOwnershipExplicit() {
        var calls = 0
        val reader = PreviewFileReader("remote:a") { path ->
            calls++
            PreviewImageBytes(PreviewImageKey("remote:a", path, "v1"), byteArrayOf(1))
        }
        assertNull(reader.readVersionedFile("https://example.test/image.png"))
        assertNull(reader.readVersionedFile("/work/private.key"))
        assertEquals(0, calls)
        val first = reader.readVersionedFile("/work/a.png")!!
        assertEquals(PreviewImageKey("remote:a", "/work/a.png", "v1"), first.key)
        assertNotEquals(first.key, first.key.copy(source = "remote:b"))
        assertNotEquals(first.key, first.key.copy(version = "v2"))
        assertArrayEquals(byteArrayOf(1), reader("/work/a.png"))
    }

    @Test fun framesCannotCrossCallsTabsSessionsOrThumbnailAndDetailSampling() {
        val target = ToolPreviewTarget("call", "browser", """{"action":"click","tabId":"tab-1"}""", "", ToolPreviewStage.RUNNING,
            sourceSessionId = "parent")
        val key = previewFrameKey(target, 480)
        assertNotNull(key)
        assertNotEquals(key, previewFrameKey(target.copy(id = "another-call"), 480))
        assertNotEquals(key, previewFrameKey(target.copy(sourceSessionId = "child"), 480))
        assertNotEquals(key, previewFrameKey(target.copy(arguments = """{"action":"click","tabId":"tab-2"}"""), 480))
        assertNotEquals(key, previewFrameKey(target, 2048))
        assertEquals(key, previewFrameKey(target.copy(stage = ToolPreviewStage.COMPLETED), 480))
    }
}
