package com.aicode.feature.agent.domain.container

import android.app.Application
import com.aicode.core.util.FileLogger
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteBuiltinDocsStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val state = BuiltinDocsSync.STATE_FILE
    private val docs = mapOf("guide/a.md" to "A", "advanced/b.md" to "B")

    @Before fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
    }
    @After fun release() { unmockkObject(FileLogger) }

    private inner class Server(val disk: File = temp.newFolder()) {
        val client = mockk<SFTPClient>(relaxed = true)
        private val engine = mockk<SFTPEngine>()
        val home = "/home/fixture"
        val root = "$home/.aicode/docs"
        val publications = mutableListOf<String>()
        val reads = mutableListOf<Pair<String, Int>>()
        val removals = mutableListOf<String>()
        val sizeOverrides = mutableMapOf<String, Long>()
        var onWrite: (String) -> Unit = {}
        var onRead: (String, Long) -> Unit = { _, _ -> }
        var onOpen: (String) -> Unit = {}
        var onRename: (String, String) -> Unit = { _, _ -> }
        var corruptPart: (String) -> Boolean = { false }
        var forceReadCount: Int? = null
        var openedSizeDelta = 0L
        fun file(path: String): File {
            require(path.startsWith('/') && path.split('/').none { it == ".." })
            return File(disk, path.removePrefix("/"))
        }
        fun document(path: String) = file("$root/$path")
        fun exists(path: String) = Files.exists(file(path).toPath(), NOFOLLOW_LINKS)
        fun put(path: String, text: String) {
            file(path).parentFile!!.mkdirs()
            file(path).writeText(text)
        }
        fun backup(path: String, text: String) = "$root/${BuiltinDocsSync.BACKUP_DIR}/${BuiltinDocsSync.sha256(text.toByteArray())}/$path"
        fun attrs(path: String): FileAttributes {
            if (!exists(path)) throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "missing")
            val local = file(path)
            val type = when {
                Files.isSymbolicLink(local.toPath()) -> FileMode.Type.SYMLINK
                local.isDirectory -> FileMode.Type.DIRECTORY
                local.isFile -> FileMode.Type.REGULAR
                else -> FileMode.Type.UNKNOWN
            }
            return FileAttributes.Builder().withType(type).withSize(sizeOverrides[path] ?: local.length())
                .withAtimeMtime(0, local.lastModified()).build()
        }
        private fun handle(path: String): RemoteFile = mockk<RemoteFile>(relaxed = true).also { remote ->
            every { remote.fetchAttributes() } answers {
                val before = attrs(path)
                FileAttributes.Builder().withType(before.type).withSize(before.size + openedSizeDelta)
                    .withAtimeMtime(0, before.mtime).build()
            }
            every { remote.write(any(), any(), any(), any()) } answers {
                RandomAccessFile(file(path), "rw").use { output ->
                    output.seek(firstArg())
                    output.write(secondArg<ByteArray>(), thirdArg(), arg(3))
                }
                if (corruptPart(path)) {
                    RandomAccessFile(file(path), "rw").use { it.seek(0); it.write(0x21) }
                }
                onWrite(path)
            }
            every { remote.read(any(), any(), any(), any()) } answers {
                val requested = arg<Int>(3)
                check(requested in 1..32768)
                reads += path to requested
                onRead(path, firstArg())
                forceReadCount ?: RandomAccessFile(file(path), "r").use { input ->
                    input.seek(firstArg())
                    input.read(secondArg<ByteArray>(), thirdArg(), requested)
                }
            }
        }
        init {
            file(home).mkdirs()
            every { client.sftpEngine } returns engine
            every { client.lstat(any()) } answers { attrs(firstArg()) }
            every { engine.makeDir(any(), any()) } answers {
                assertEquals(448, secondArg<FileAttributes>().mode.permissionsMask)
                Files.createDirectory(file(firstArg()).toPath(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                Unit
            }
            every { client.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
                val path = firstArg<String>()
                assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), secondArg<Set<OpenMode>>())
                assertEquals(384, thirdArg<FileAttributes>().mode.permissionsMask)
                Files.createFile(file(path).toPath(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                handle(path)
            }
            every { client.open(any<String>(), any<Set<OpenMode>>()) } answers {
                val path = firstArg<String>()
                assertEquals(setOf(OpenMode.READ), secondArg<Set<OpenMode>>())
                assertEquals(FileMode.Type.REGULAR, attrs(path).type)
                onOpen(path)
                handle(path)
            }
            every { client.rename(any(), any(), any()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                assertEquals(setOf(RenameFlags.ATOMIC, RenameFlags.OVERWRITE), thirdArg<Set<RenameFlags>>())
                onRename(source, target)
                Files.move(file(source).toPath(), file(target).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                publications += target.removePrefix("$root/")
                Unit
            }
            every { client.rename(any(), any()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                onRename(source, target)
                if (exists(target)) throw SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "destination appeared")
                Files.move(file(source).toPath(), file(target).toPath(), ATOMIC_MOVE)
                publications += target.removePrefix("$root/")
                Unit
            }
            every { client.rm(any()) } answers {
                val path = firstArg<String>()
                Files.delete(file(path).toPath())
                removals += path
                Unit
            }
        }
        fun store(checkCurrent: () -> Unit = {}) = RemoteBuiltinDocsStore(client, home, checkCurrent)
        fun sync(documents: Map<String, String> = docs) = BuiltinDocsSync(store()).sync(BuiltinDocsSync.snapshot(documents))
    }

    private fun field(connection: RemoteSshConnection, name: String, value: Any?) {
        RemoteSshConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }
    private fun connected(server: Server): Pair<RemoteSshConnection, SSHClient> {
        val owner = mockk<SSHClient>(relaxed = true)
        every { owner.isConnected } returns true
        every { owner.isAuthenticated } returns true
        every { owner.newSFTPClient() } returns server.client
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())
        attach(connection, owner, server.home)
        return connection to owner
    }
    private fun attach(connection: RemoteSshConnection, owner: SSHClient, home: String) {
        field(connection, "sshClient", owner)
        field(connection, "remoteHome", home)
        field(connection, "activeIdentity", RemoteConnectionIdentity(owner, home))
    }
    private fun permission(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test fun firstSyncUsesSftpOnlyAndCreatesPrivateFilesAndDirectories() = runBlocking {
        val server = Server()
        val (connection, owner) = connected(server)
        connection.uploadDocs(docs)
        assertEquals("A", server.document("guide/a.md").readText())
        assertEquals("B", server.document("advanced/b.md").readText())
        assertEquals(listOf(state, "advanced/b.md", state, "guide/a.md", state, state), server.publications)
        server.file("${server.home}/.aicode").walkTopDown().forEach {
            assertEquals(if (it.isDirectory) "rwx------" else "rw-------", permission(it))
        }
        verify(exactly = 1) { owner.newSFTPClient(); server.client.close() }
        verify(exactly = 0) { owner.startSession(); owner.close(); owner.disconnect(); server.client.stat(any()) }
    }

    @Test fun unchangedSyncHasZeroPayloadAndDoesNotReadOrStatDocumentPaths() {
        val server = Server()
        server.sync()
        server.publications.clear(); server.reads.clear()
        clearMocks(server.client, answers = false)
        assertEquals(BuiltinDocsSync.Result(false, 0, 0), server.sync())
        assertTrue(server.publications.isEmpty())
        assertTrue(server.reads.all { it.first == "${server.root}/$state" })
        verify(exactly = 0) { server.client.lstat("${server.root}/guide/a.md"); server.client.lstat("${server.root}/advanced/b.md") }
        verify(exactly = 0) { server.client.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
    }

    @Test fun changedSyncOnlyPublishesChangedBodiesAndDeletesConfirmedOldBuiltin() {
        val server = Server()
        server.sync(docs + ("old.md" to "old") + ("edited.md" to "built-in"))
        server.put("${server.root}/edited.md", "user")
        server.put("${server.root}/unknown.md", "custom")
        server.publications.clear()
        assertEquals(BuiltinDocsSync.Result(true, 1, 1), server.sync(docs + ("guide/a.md" to "A2")))
        assertEquals(listOf(state, "guide/a.md", state, state), server.publications)
        assertEquals("user", server.document("edited.md").readText())
        assertEquals("custom", server.document("unknown.md").readText())
        assertFalse(server.document("old.md").exists())
    }

    @Test fun conflictingBodyIsCopiedToHashVerifiableBackupBeforeReplacement() {
        val server = Server()
        server.put("${server.root}/guide/a.md", "user body")
        server.sync()
        val backup = server.backup("guide/a.md", "user body")
        assertEquals("user body", server.file(backup).readText())
        assertEquals("A", server.document("guide/a.md").readText())
        assertEquals(BuiltinDocsSync.sha256("user body".toByteArray()), server.store().fileHash(backup.removePrefix("${server.root}/")))
        assertTrue(server.publications.indexOf(backup.removePrefix("${server.root}/")) < server.publications.indexOf("guide/a.md"))
        assertEquals("rw-------", permission(server.file(backup)))
    }

    @Test fun damagedBackupCannotAuthorizeReplacement() {
        val server = Server()
        server.put("${server.root}/guide/a.md", "user")
        server.put(server.backup("guide/a.md", "user"), "wrong")
        assertThrows(IllegalStateException::class.java) { server.sync() }
        assertEquals("user", server.document("guide/a.md").readText())
        assertFalse("guide/a.md" in server.publications)
    }

    @Test fun failedBackupWriteKeepsOriginalAndDirtyReceipt() {
        val server = Server()
        server.put("${server.root}/guide/a.md", "user")
        val backupPart = "${server.root}/${BuiltinDocsSync.temporaryPath(server.backup("guide/a.md", "user").removePrefix("${server.root}/"))}"
        server.onWrite = { if (it == backupPart) throw IOException("backup interrupted") }
        assertThrows(IOException::class.java) { server.sync() }
        assertEquals("user", server.document("guide/a.md").readText())
        assertFalse(server.file(server.backup("guide/a.md", "user")).exists())
        assertTrue(server.document(state).readText().contains("\tdirty\t"))
        server.onWrite = {}
        server.sync()
        assertEquals("user", server.file(server.backup("guide/a.md", "user")).readText())
        assertEquals("A", server.document("guide/a.md").readText())
    }

    @Test fun partialPayloadFailureLeavesOriginalAndReclaimsOwnedPartOnRetry() {
        val server = Server()
        server.sync()
        val targetPart = "${server.root}/${BuiltinDocsSync.temporaryPath("guide/a.md")}"
        server.onWrite = { if (it == targetPart) throw IOException("wire write failed") }
        assertThrows(IOException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
        assertEquals("A", server.document("guide/a.md").readText())
        assertTrue(server.document(state).readText().contains("\tdirty\t"))
        assertTrue(server.exists(targetPart))
        server.onWrite = {}
        server.sync(docs + ("guide/a.md" to "A2"))
        assertEquals("A2", server.document("guide/a.md").readText())
        assertFalse(server.exists(targetPart))
    }

    @Test fun stagedLengthAndHashAreVerifiedBeforeAnyPublication() {
        for (truncate in listOf(false, true)) {
            val server = Server()
            server.sync()
            val part = "${server.root}/${BuiltinDocsSync.temporaryPath("guide/a.md")}"
            if (truncate) server.onWrite = { if (it == part) RandomAccessFile(server.file(it), "rw").use { output -> output.setLength(0) } }
            else server.corruptPart = { it == part }
            assertThrows(IllegalStateException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
            assertEquals("A", server.document("guide/a.md").readText())
            assertTrue(server.document(state).readText().contains("\tdirty\t"))
        }
    }

    @Test fun destinationEditDuringStagingIsDetectedAndNeverOverwritten() {
        val server = Server()
        server.sync()
        val part = "${server.root}/${BuiltinDocsSync.temporaryPath("guide/a.md")}"
        server.onWrite = { if (it == part) server.document("guide/a.md").writeText("new user edit") }
        assertThrows(IllegalStateException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
        assertEquals("new user edit", server.document("guide/a.md").readText())
        server.onWrite = {}
        server.sync(docs + ("guide/a.md" to "A2"))
        assertEquals("new user edit", server.file(server.backup("guide/a.md", "new user edit")).readText())
    }

    @Test fun missingStateDoesNotAuthorizeRemovalAndDamagedStateFailsBeforeWrites() {
        val server = Server()
        server.sync(docs + ("old.md" to "old"))
        Files.delete(server.document(state).toPath())
        server.sync()
        assertEquals("old", server.document("old.md").readText())
        server.document(state).writeText("corrupt")
        server.publications.clear(); server.removals.clear()
        assertThrows(IllegalArgumentException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
        assertTrue(server.publications.isEmpty())
        assertTrue(server.removals.isEmpty())
        assertEquals("A", server.document("guide/a.md").readText())
    }

    @Test fun linkedHomeRootStateParentsAndBackupAreRejectedWithoutOutsideIo() {
        for (boundary in listOf("home", "aicode", "root", "state", "parent", "document", "backup", "staging")) {
            val server = Server()
            val outside = temp.newFolder()
            val path = when (boundary) {
                "home" -> { Files.delete(server.file(server.home).toPath()); server.home }
                "aicode" -> "${server.home}/.aicode"
                "root" -> { server.file("${server.home}/.aicode").mkdirs(); server.root }
                "state" -> { server.file(server.root).mkdirs(); "${server.root}/$state" }
                "parent" -> { server.file(server.root).mkdirs(); "${server.root}/guide" }
                "document" -> { server.file("${server.root}/guide").mkdirs(); "${server.root}/guide/a.md" }
                "backup" -> { server.put("${server.root}/guide/a.md", "user"); "${server.root}/${BuiltinDocsSync.BACKUP_DIR}" }
                else -> { server.file(server.root).mkdirs(); "${server.root}/${BuiltinDocsSync.STAGING_DIR}" }
            }
            Files.createSymbolicLink(server.file(path).toPath(), outside.toPath())
            assertThrows("boundary=$boundary", IllegalStateException::class.java) { server.sync() }
            assertEquals(0, outside.list()!!.size)
            assertTrue(server.removals.isEmpty())
            verify(exactly = 0) { server.client.stat(any()); server.client.statExistence(any()) }
        }
    }

    @Test fun linkedBackupFileAndPartCannotBeClobbered() {
        for (backup in listOf(false, true)) {
            val server = Server()
            server.put("${server.root}/guide/a.md", "user")
            val outside = File(temp.newFolder(), "outside.md").apply { writeText("outside") }
            val linked = if (backup) server.backup("guide/a.md", "user") else
                "${server.root}/${BuiltinDocsSync.temporaryPath(state)}"
            server.file(linked).parentFile!!.mkdirs()
            Files.createSymbolicLink(server.file(linked).toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { server.sync() }
            assertEquals("outside", outside.readText())
            assertEquals("user", server.document("guide/a.md").readText())
        }
    }

    @Test fun symlinkIntroducedDuringOpenIsRejectedBeforeReadingBytes() {
        val server = Server()
        server.sync()
        val outside = File(temp.newFolder(), "outside.md").apply { writeText("private") }
        server.onOpen = { if (it == "${server.root}/guide/a.md") {
            Files.delete(server.file(it).toPath())
            Files.createSymbolicLink(server.file(it).toPath(), outside.toPath())
        } }
        server.reads.clear()
        assertThrows(IllegalStateException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
        assertTrue(server.reads.none { it.first == "${server.root}/guide/a.md" })
        assertEquals("private", outside.readText())
    }

    @Test fun oversizedStateAndDocumentAreRejectedBeforeOpeningBody() {
        for (stateFile in listOf(true, false)) {
            val server = Server()
            server.sync()
            val path = if (stateFile) "${server.root}/$state" else "${server.root}/guide/a.md"
            server.sizeOverrides[path] = (if (stateFile) BuiltinDocsSync.MAX_STATE_BYTES else BuiltinDocsSync.MAX_DOCUMENT_BYTES).toLong() + 1
            clearMocks(server.client, answers = false)
            assertThrows(IllegalStateException::class.java) { server.sync(docs + ("guide/a.md" to "A2")) }
            verify(exactly = 0) { server.client.open(path, any<Set<OpenMode>>()) }
            assertEquals("A", server.document("guide/a.md").readText())
        }
    }

    @Test fun growingAndZeroProgressFilesDoNotCauseUnboundedReads() {
        for (mode in listOf("grow", "zero", "fstat")) {
            val server = Server()
            server.put("${server.root}/a.md", "abc")
            when (mode) {
                "grow" -> server.sizeOverrides["${server.root}/a.md"] = 1
                "zero" -> server.forceReadCount = 0
                else -> server.openedSizeDelta = 1
            }
            assertThrows(IllegalStateException::class.java) { server.store().fileHash("a.md") }
            assertTrue(server.reads.size <= 2)
        }
    }

    @Test fun largeBodiesAreStreamedInBoundedChunksAndMatchSourceHash() {
        val server = Server()
        val content = "文档".repeat(40000)
        server.put("${server.root}/a.md", content)
        assertEquals(BuiltinDocsSync.sha256(content.toByteArray()), server.store().fileHash("a.md"))
        assertTrue(server.reads.size > 4)
        assertTrue(server.reads.all { it.second in 1..32768 })
    }

    @Test fun pathInjectionAndInvalidHomeAreRejectedWithoutNetworkOrMutation() = runBlocking {
        val server = Server()
        val (connection, owner) = connected(server)
        for (path in listOf("../evil.md", "/evil.md", "guide\\x.md", "x\n.md", "bad';id.md")) {
            try { connection.uploadDocs(mapOf(path to "bad")); fail("invalid path") } catch (_: IllegalArgumentException) { }
        }
        for (home in listOf("relative", "/home/../root", "/home/./root", "/home//test", "/home/back\\slash", "/home/bad\nname")) {
            assertThrows(IllegalArgumentException::class.java) { RemoteBuiltinDocsStore(server.client, home) {} }
        }
        verify(exactly = 0) { owner.newSFTPClient(); server.client.lstat(any()); owner.startSession() }
        assertFalse(server.file("${server.home}/.aicode").exists())
    }

    @Test fun credentialsDefinitionsAndTheirReceiptsAreOutsideDocsResponsibility() = runBlocking {
        val server = Server()
        val unrelated = mapOf("git-credentials" to "fixture credential data", "gitconfig.credential" to "fixture config",
            "agents/custom.md" to "user agent", "skills/custom/SKILL.md" to "user skill",
            "${BuiltinDefinitionPaths.RECEIPTS}/agents/custom.md" to "",
            "skills.json" to "disabled", "agents.json" to "custom config")
        unrelated.forEach { (path, bytes) -> server.put("${server.home}/.aicode/$path", bytes) }
        val (connection, _) = connected(server)
        connection.uploadDocs(docs)
        unrelated.forEach { (path, bytes) -> assertEquals(bytes, server.file("${server.home}/.aicode/$path").readText()) }
        assertTrue(server.publications.all { it == state || it in docs })
        verify(exactly = 0) { server.client.lstat(match { it.contains("credentials") || it.contains("/agents/") || it.contains("/skills/") }) }
    }

    @Test fun reconnectToSameOwnerAndHomeStillRejectsOldGeneration() = runBlocking {
        val server = Server()
        val (connection, owner) = connected(server)
        val old = connection.captureIdentity()
        attach(connection, owner, server.home)
        assertNotEquals(old.generation, connection.captureIdentity().generation)
        try { connection.uploadDocs(docs, old); fail("must cancel") } catch (_: CancellationException) { }
        verify(exactly = 0) { owner.newSFTPClient() }
        assertFalse(server.file("${server.home}/.aicode").exists())
    }

    @Test fun queuedRequestCapturesIdentityBeforeMutexAndNeverSwitchesServers() = runBlocking {
        val server = Server()
        val other = Server()
        val (connection, owner) = connected(server)
        val (_, newOwner) = connected(other)
        val mutex = RemoteSshConnection::class.java.getDeclaredField("builtinDocsMutex").apply { isAccessible = true }.get(connection) as Mutex
        var cancelled = false
        mutex.lock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { connection.uploadDocs(docs); fail("queued stale request") } catch (_: CancellationException) { cancelled = true }
        }
        attach(connection, newOwner, other.home)
        mutex.unlock()
        withTimeout(10000) { job.join() }
        assertTrue(cancelled)
        verify(exactly = 0) { owner.newSFTPClient(); newOwner.newSFTPClient() }
        assertFalse(server.file("${server.home}/.aicode").exists())
        assertFalse(other.file("${other.home}/.aicode").exists())
    }

    @Test fun connectionChangeDuringPayloadCancelsAndClosesOnlyCapturedChannel() = runBlocking {
        val server = Server()
        val other = Server()
        val (connection, owner) = connected(server)
        val (_, newOwner) = connected(other)
        val cached = mockk<SFTPClient>(relaxed = true)
        field(connection, "sftpClient", cached)
        val part = "${server.root}/${BuiltinDocsSync.temporaryPath("guide/a.md")}"
        server.onWrite = { if (it == part) attach(connection, newOwner, other.home) }
        try { connection.uploadDocs(docs); fail("stale copy must cancel") } catch (_: CancellationException) { }
        assertFalse(server.document("guide/a.md").exists())
        assertTrue(server.document(state).readText().contains("\tdirty\t"))
        assertFalse(other.file("${other.home}/.aicode").exists())
        verify(exactly = 1) { server.client.close() }
        verify(exactly = 0) { owner.close(); owner.disconnect(); cached.close(); newOwner.newSFTPClient() }
    }

    @Test fun blockedReadCancellationClosesOwnedSftpAndUnblocksWithoutSuccess() = runBlocking {
        val server = Server()
        server.sync()
        val (connection, owner) = connected(server)
        val cached = mockk<SFTPClient>(relaxed = true)
        field(connection, "sftpClient", cached)
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { server.client.close() } answers { closed.countDown() }
        server.onRead = { path, _ -> if (path == "${server.root}/guide/a.md") {
            reading.countDown()
            check(closed.await(10, TimeUnit.SECONDS))
            throw IOException("owned subsystem closed")
        } }
        var succeeded = false
        val job = launch(Dispatchers.Default) { connection.uploadDocs(docs + ("guide/a.md" to "A2")); succeeded = true }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(10000) { job.join() }
            assertFalse(succeeded)
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals("A", server.document("guide/a.md").readText())
            verify(exactly = 1) { server.client.close() }
            verify(exactly = 0) { owner.close(); owner.disconnect(); cached.close() }
        } finally { job.cancel(); closed.countDown() }
    }

    @Test fun cancellationDuringPartWriteKeepsOldBodyAndRetryRecoversDirtyJournal() = runBlocking {
        val server = Server()
        server.sync()
        val (connection, owner) = connected(server)
        val writing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val part = "${server.root}/${BuiltinDocsSync.temporaryPath("guide/a.md")}"
        every { server.client.close() } answers { closed.countDown() }
        server.onWrite = { if (it == part) {
            writing.countDown()
            check(closed.await(10, TimeUnit.SECONDS))
        } }
        var succeeded = false
        val job = launch(Dispatchers.Default) { connection.uploadDocs(docs + ("guide/a.md" to "A2")); succeeded = true }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(10000) { job.join() }
            assertFalse(succeeded)
            assertEquals("A", server.document("guide/a.md").readText())
            assertTrue(server.document(state).readText().contains("\tdirty\t"))
            val retry = Server(server.disk)
            every { owner.newSFTPClient() } returns retry.client
            connection.uploadDocs(docs + ("guide/a.md" to "A2"))
            assertEquals("A2", retry.document("guide/a.md").readText())
            assertFalse(retry.exists(part))
        } finally { job.cancel(); closed.countDown() }
    }

    @Test fun cancellationAtWithContextReturnBoundaryCannotBeLoggedAsSuccess() = runBlocking {
        val server = Server()
        val (connection, _) = connected(server)
        val returning = CountDownLatch(1)
        val resumes = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                resumes.add(block)
                returning.countDown()
            }
        }
        var succeeded = false
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { connection.uploadDocs(docs); succeeded = true }
        try {
            assertTrue(returning.await(10, TimeUnit.SECONDS))
            assertTrue(server.document(state).readText().contains("\tready\t"))
            job.cancel()
            while (true) (resumes.poll() ?: break).run()
            withTimeout(10000) { job.join() }
            assertFalse(succeeded)
            verify(exactly = 0) { FileLogger.i("RemoteSshConnection", match { it.contains("增量同步完成") }) }
        } finally {
            job.cancel()
            while (true) (resumes.poll() ?: break).run()
        }
    }

    @Test fun cancellationDuringChannelAcquisitionClosesOnlyTheAcquiredSubsystem() = runBlocking {
        val server = Server()
        val (connection, owner) = connected(server)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { owner.newSFTPClient() } answers {
            acquired.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            server.client
        }
        var succeeded = false
        val job = launch(Dispatchers.Default) { connection.uploadDocs(docs); succeeded = true }
        try {
            assertTrue(acquired.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            withTimeout(10000) { job.join() }
            assertFalse(succeeded)
            assertFalse(server.file("${server.home}/.aicode").exists())
            verify(exactly = 1) { server.client.close() }
            verify(exactly = 0) { owner.close(); owner.disconnect() }
        } finally { job.cancel(); release.countDown() }
    }

    @Test fun concurrentRequestsSerializeAndOnlyOneBodySetIsPublished() = runBlocking {
        val server = Server()
        val (connection, owner) = connected(server)
        val identity = connection.captureIdentity()
        val first = async(Dispatchers.Default) { connection.uploadDocs(docs, identity) }
        val second = async(Dispatchers.Default) { connection.uploadDocs(docs, identity) }
        first.await(); second.await()
        assertEquals(1, server.publications.count { it == "guide/a.md" })
        assertEquals(1, server.publications.count { it == "advanced/b.md" })
        verify(exactly = 2) { owner.newSFTPClient(); server.client.close() }
    }

    @Test fun emptyUploadDoesNotRequireConnectionOrMutateAnyServer() = runBlocking {
        RemoteSshConnection(mockk(), mockk(), mockk(), mockk()).uploadDocs(emptyMap())
        val server = Server()
        server.sync()
        val (connection, owner) = connected(server)
        connection.uploadDocs(emptyMap())
        assertEquals("A", server.document("guide/a.md").readText())
        verify(exactly = 0) { owner.newSFTPClient() }
    }
}
