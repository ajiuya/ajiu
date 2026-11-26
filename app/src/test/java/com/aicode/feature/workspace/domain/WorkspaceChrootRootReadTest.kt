package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootFileSession
import com.aicode.feature.workspace.domain.chroot.ChrootFileStat
import com.aicode.feature.workspace.domain.chroot.ChrootFileWorkerClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceChrootRootReadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val mapper = mockk<WorkspacePathMapper>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val root = "/root/workspace/site"
    private val path = "$root/entry"

    private inner class Fixture {
        val owner = Job()
        val client = mockk<ChrootFileWorkerClient>()
        val stats = mutableMapOf(root to directory(), path to regular(3))
        val resolved = mutableMapOf<String, String>()
        var bytes = byteArrayOf(1, 2, 3)
        var sourceCurrent = true
        var afterOpen: () -> Unit = {}
        var afterRead: () -> Unit = {}
        val streamCloses = AtomicInteger()
        val workerCloses = AtomicInteger()
        val access = ChrootFileAccess({ "guest" }, {
            ChrootFileSession("guest", client, temporary.root, {
                if (!sourceCurrent) throw CancellationException("Source changed")
            }, emptyList(), closeOwner = {})
        })
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "guest", access, null, null) {
            if (!sourceCurrent) throw CancellationException("Source changed")
        }
        val facade = WorkspaceRootRead(mapper, remote)

        init {
            every { client.stat(any(), any()) } answers { stats.getValue(firstArg()) }
            every { client.resolve(any(), any()) } answers { resolved[firstArg()] ?: firstArg() }
            every { client.openRead(any()) } answers {
                object : ByteArrayInputStream(bytes) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                        if (it > 0) afterRead()
                    }
                    override fun close() { streamCloses.incrementAndGet() }
                }.also { afterOpen() }
            }
            every { client.close() } answers { workerCloses.incrementAndGet(); Unit }
        }

        fun capture(): RootRead = facade.capture(root, execution, owner)
        fun finish() = owner.cancel()
    }

    @Test fun guestReadsUseOnlyTheWorkerAndNeverExposeOrCopyAHostFile() {
        val fixture = Fixture()
        try {
            val read = fixture.capture()
            assertNull(read.localFile(path))
            assertEquals(3L, read.size(path))
            assertArrayEquals(byteArrayOf(1, 2, 3), read.openRead(path, 3).use { it.readBytes() })
            assertEquals(1, fixture.streamCloses.get())
            verify(exactly = 0) { mapper.resolveFile(any(), any()) }
            verify(exactly = 0) { remote.openRootRead(any(), any(), any()) }
            assertTrue(temporary.root.listFiles().isNullOrEmpty())
        } finally { fixture.finish() }
    }

    @Test fun declaredOversizeAndNonRegularTargetsFailBeforeOpening() {
        val fixture = Fixture()
        try {
            val read = fixture.capture()
            assertThrows(FileReadTooLargeException::class.java) { read.openRead(path, 2) }
            fixture.stats[path] = directory()
            assertThrows(IOException::class.java) { read.openRead(path, 3) }
            verify(exactly = 0) { fixture.client.openRead(any()) }
        } finally { fixture.finish() }
    }

    @Test fun aGrowingStreamCannotReturnBytesBeyondItsLimitEvenWithStaleMetadata() {
        val fixture = Fixture()
        fixture.stats[path] = regular(1)
        fixture.bytes = byteArrayOf(1, 2, 3, 4)
        try {
            val input = fixture.capture().openRead(path, 2)
            assertThrows(FileReadTooLargeException::class.java) { input.read(ByteArray(8)) }
            assertEquals(1, fixture.streamCloses.get())
        } finally { fixture.finish() }
    }

    @Test fun inRootLeafAndRootLinksStaySupportedButEscapingLeafLinksAreRejected() {
        val fixture = Fixture()
        val alias = "$root/alias"
        fixture.resolved[alias] = path
        fixture.stats[alias] = regular(3)
        try {
            val read = fixture.capture()
            assertArrayEquals(byteArrayOf(1, 2, 3), read.openRead(alias, 3).use { it.readBytes() })
            val input = read.openRead(alias, 3)
            fixture.resolved[alias] = "/etc/secret"
            assertThrows(IOException::class.java) { input.read() }
            assertThrows(IOException::class.java) { read.size(alias) }
            assertThrows(IOException::class.java) { read.openRead(alias, 3) }
            verify(exactly = 2) { fixture.client.openRead(alias) }
        } finally { fixture.finish() }
        val rootLink = Fixture()
        rootLink.resolved[root] = "/root/workspace/actual"
        rootLink.resolved[path] = "/root/workspace/actual/entry"
        try {
            val read = rootLink.capture()
            assertEquals(3L, read.size(path))
            rootLink.resolved[root] = "/etc"
            assertThrows(CancellationException::class.java) { read.checkCurrent() }
        } finally { rootLink.finish() }
    }

    @Test fun changedRootInodeAndChangedFileIdentityRejectAlreadyOpenedStreams() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.stats[root] = directory().copy(inode = 99) },
            { it.stats[path] = regular(3).copy(inode = 99) },
            { it.stats[path] = regular(3).copy(modifiedMs = 99) },
        )) {
            val fixture = Fixture()
            try {
                val input = fixture.capture().openRead(path, 3)
                change(fixture)
                assertThrows(CancellationException::class.java) { input.read() }
                assertEquals(1, fixture.streamCloses.get())
            } finally { fixture.finish() }
        }
    }

    @Test fun changesImmediatelyAfterOpenAndAfterAChunkNeverPublishChangedBytes() {
        val opening = Fixture()
        opening.afterOpen = { opening.stats[path] = regular(3).copy(device = 99) }
        try {
            assertThrows(CancellationException::class.java) { opening.capture().openRead(path, 3) }
            assertEquals(1, opening.streamCloses.get())
        } finally { opening.finish() }
        val reading = Fixture()
        reading.afterRead = { reading.stats[path] = regular(4) }
        try {
            val input = reading.capture().openRead(path, 4)
            assertThrows(CancellationException::class.java) { input.read() }
            assertEquals(1, reading.streamCloses.get())
        } finally { reading.finish() }
    }

    @Test fun operationOwnerAndOriginalCaptureOwnerEachCloseTheReturnedStream() {
        for (cancelOriginal in listOf(true, false)) {
            val fixture = Fixture()
            val operation = Job()
            try {
                val read = fixture.capture().forOperation(operation)
                val input = read.openRead(path, 3)
                if (cancelOriginal) fixture.owner.cancel() else operation.cancel()
                assertEquals(1, fixture.streamCloses.get())
                assertTrue(runCatching { input.read() }.isFailure)
                assertThrows(CancellationException::class.java) { read.openRead(path, 3) }
            } finally { operation.cancel(); fixture.finish() }
        }
    }

    @Test fun forbiddenRootsAndForbiddenResolvedTargetsNeverOpenAFile() {
        val fixture = Fixture()
        try {
            assertThrows(IOException::class.java) { fixture.facade.capture("/", fixture.execution, fixture.owner) }
            assertThrows(IOException::class.java) { fixture.facade.capture("/proc/self", fixture.execution, fixture.owner) }
            val read = fixture.capture()
            fixture.resolved[path] = "/proc/self/fd/1"
            assertThrows(IOException::class.java) { read.openRead(path, 10) }
            verify(exactly = 0) { fixture.client.openRead(any()) }
            verify(exactly = 0) { mapper.resolveFile(any(), any()) }
        } finally { fixture.finish() }
    }

    private fun directory() = ChrootFileStat(1, 0x41ff, 0, 7, 1, 2)
    private fun regular(size: Long) = ChrootFileStat(2, 0x81a4, size, 7, 1, 3)
}
