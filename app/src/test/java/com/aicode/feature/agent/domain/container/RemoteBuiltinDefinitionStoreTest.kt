package com.aicode.feature.agent.domain.container

import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteBuiltinDefinitionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val agents = mapOf("探索员.md" to "---\nname: 探索员\n---\nprompt".toByteArray())
    private val skills = mapOf("dev-god" to mapOf(
        "SKILL.md" to "---\nname: dev-god\n---\nbody".toByteArray(),
        "source/a/b.md" to ByteArray(90000) { (it % 251).toByte() },
        "scripts/x.py" to "not executed".toByteArray()
    ))

    private inner class Server {
        val disk = temp.newFolder()
        val client = mockk<SFTPClient>(relaxed = true)
        private val engine = mockk<SFTPEngine>()
        val home = "/home/test"
        var onWrite: (String) -> Unit = {}
        var onRename: (String) -> Unit = {}
        fun file(path: String): File {
            require(path.startsWith('/') && ".." !in path.split('/'))
            return File(disk, path.removePrefix("/"))
        }
        fun exists(path: String) = Files.exists(file(path).toPath(), NOFOLLOW_LINKS)
        private fun attrs(path: String): FileAttributes {
            if (!exists(path)) throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "missing")
            val target = file(path)
            val type = when {
                Files.isSymbolicLink(target.toPath()) -> FileMode.Type.SYMLINK
                target.isDirectory -> FileMode.Type.DIRECTORY
                else -> FileMode.Type.REGULAR
            }
            return FileAttributes.Builder().withType(type).withSize(target.length()).build()
        }
        private fun handle(path: String, write: Boolean): RemoteFile {
            val remote = mockk<RemoteFile>(relaxed = true)
            if (write) {
                every { remote.write(any(), any(), any(), any()) } answers {
                    java.io.RandomAccessFile(file(path), "rw").use { output ->
                        output.seek(firstArg())
                        output.write(secondArg<ByteArray>(), thirdArg(), arg(3))
                    }
                    onWrite(path)
                }
            } else {
                every { remote.read(any(), any(), any(), any()) } answers {
                    java.io.RandomAccessFile(file(path), "r").use { input ->
                        input.seek(firstArg())
                        input.read(secondArg<ByteArray>(), thirdArg(), arg(3))
                    }
                }
            }
            return remote
        }
        init {
            file(home).mkdirs()
            every { client.sftpEngine } returns engine
            every { client.lstat(any()) } answers { attrs(firstArg()) }
            every { client.ls(any<String>()) } answers {
                file(firstArg()).listFiles().orEmpty().map { child ->
                    mockk<RemoteResourceInfo>().also { info -> every { info.name } returns child.name }
                }
            }
            every { engine.makeDir(any(), any()) } answers {
                assertEquals(448, secondArg<FileAttributes>().mode.permissionsMask)
                Files.createDirectory(file(firstArg()).toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                Unit
            }
            every { client.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
                val path = firstArg<String>()
                assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), secondArg<Set<OpenMode>>())
                assertEquals(384, thirdArg<FileAttributes>().mode.permissionsMask)
                Files.createFile(file(path).toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                handle(path, true)
            }
            every { client.open(any<String>(), any<Set<OpenMode>>()) } answers {
                assertEquals(setOf(OpenMode.READ), secondArg<Set<OpenMode>>())
                assertEquals(FileMode.Type.REGULAR, attrs(firstArg()).type)
                handle(firstArg(), false)
            }
            every { client.rename(any(), any()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                onRename(target)
                if (exists(target)) throw SFTPException(Response.StatusCode.FAILURE, "already exists")
                Files.move(file(source).toPath(), file(target).toPath())
                Unit
            }
            every { client.rm(any()) } answers { Files.delete(file(firstArg()).toPath()); Unit }
            every { client.rmdir(any()) } answers { Files.delete(file(firstArg()).toPath()); Unit }
        }
        fun store(checkCurrent: () -> Unit = {}) = RemoteBuiltinDefinitionStore(client, home, checkCurrent)
        fun install() = BuiltinDefinitionInstaller(store()).install(agents, skills)
    }

    private fun contents(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    private fun setField(connection: RemoteSshConnection, name: String, value: Any?) {
        RemoteSshConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }

    private fun connection(server: Server): Pair<RemoteSshConnection, SSHClient> {
        val owner = mockk<SSHClient>(relaxed = true)
        every { owner.isConnected } returns true
        every { owner.isAuthenticated } returns true
        every { owner.newSFTPClient() } returns server.client
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())
        setField(connection, "sshClient", owner)
        setField(connection, "remoteHome", server.home)
        setField(connection, "activeIdentity", RemoteConnectionIdentity(owner, server.home))
        return connection to owner
    }

    @Test fun localAndRemotePublishIdenticalBytesAndDeletionReceiptsAreServerScoped() {
        val local = File(temp.newFolder(), "aicode")
        val remote = Server()
        assertEquals(LocalBuiltinDefinitionStore.install(local, agents, skills), remote.install())
        assertEquals(contents(local), contents(remote.file("${remote.home}/.aicode")))
        remote.file("${remote.home}/.aicode/agents/探索员.md").delete()
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, remote.install().entries["agents/探索员.md"])
        assertFalse(remote.exists("${remote.home}/.aicode/agents/探索员.md"))
        val other = Server()
        assertTrue(other.install().entries.values.all { it == BuiltinInstallStatus.INSTALLED })
        verify(exactly = 0) { remote.client.rename(any(), any(), any()) }
    }

    @Test fun remoteAdoptsLogicalNamesInDifferentPathsAndPreservesConfigsAndUserEdits() {
        val remote = Server()
        remote.file("${remote.home}/.aicode/agents").mkdirs()
        remote.file("${remote.home}/.aicode/agents/custom.md").writeBytes(agents.getValue("探索员.md"))
        remote.file("${remote.home}/.aicode/skills/my-folder").mkdirs()
        remote.file("${remote.home}/.aicode/skills/my-folder/CLAUDE.md").writeText("---\nname: DEV-GOD\n---\nuser")
        remote.file("${remote.home}/.aicode/skills.json").writeText("disabled config")
        assertTrue(remote.install().entries.values.all { it == BuiltinInstallStatus.PRESERVED_EXISTING })
        assertFalse(remote.exists("${remote.home}/.aicode/agents/探索员.md"))
        assertFalse(remote.exists("${remote.home}/.aicode/skills/dev-god"))
        assertEquals("disabled config", remote.file("${remote.home}/.aicode/skills.json").readText())
    }

    @Test fun remoteExactPathsAndLinkedCandidatesAreNeverReadOrMerged() {
        val remote = Server()
        remote.file("${remote.home}/.aicode/agents").mkdirs()
        remote.file("${remote.home}/.aicode/skills").mkdirs()
        val outside = File(temp.newFolder(), "missing")
        Files.createSymbolicLink(remote.file("${remote.home}/.aicode/agents/unknown.md").toPath(), outside.toPath())
        Files.createSymbolicLink(remote.file("${remote.home}/.aicode/skills/dev-god").toPath(), outside.toPath())
        assertTrue(remote.install().entries.values.all { it == BuiltinInstallStatus.PRESERVED_EXISTING })
        verify(exactly = 0) { remote.client.open(match { it.endsWith("unknown.md") }, any<Set<OpenMode>>()) }
        assertFalse(outside.exists())
    }

    @Test fun remoteRejectsLinkedRootAndAncestorsAndInvalidPathsBeforeOutsideWrites() {
        for (boundary in listOf("home", "root", "skills")) {
            val remote = Server()
            val outside = temp.newFolder()
            val path = when (boundary) {
                "home" -> { remote.file(remote.home).delete(); remote.home }
                "root" -> "${remote.home}/.aicode"
                else -> { remote.file("${remote.home}/.aicode").mkdirs(); "${remote.home}/.aicode/skills" }
            }
            Files.createSymbolicLink(remote.file(path).toPath(), outside.toPath())
            assertEquals(BuiltinInstallStatus.FAILED, remote.install().entries["skills/dev-god"])
            assertEquals(0, outside.list()!!.size)
        }
        val remote = Server()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltinDefinitionInstaller(remote.store()).install(skills = mapOf("dev-god" to mapOf("SKILL.md" to byteArrayOf(), "../evil" to byteArrayOf())))
        }
        assertFalse(remote.exists("${remote.home}/.aicode"))
    }

    @Test fun remoteHalfCopyFailurePublishesNothingAndRetryCanSucceed() {
        val remote = Server()
        remote.onWrite = { if (it.endsWith("b.md")) throw IOException("partial wire write") }
        assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(remote.store()).install(skills = skills).entries["skills/dev-god"])
        assertFalse(remote.exists("${remote.home}/.aicode/skills/dev-god"))
        assertFalse(remote.exists("${remote.home}/.aicode/${BuiltinDefinitionPaths.RECEIPTS}/skills/dev-god"))
        remote.onWrite = {}
        assertEquals(BuiltinInstallStatus.INSTALLED, remote.install().entries["skills/dev-god"])
    }

    @Test fun noOverwriteRenamePreservesAUserWhoWinsTheLastRace() {
        val remote = Server()
        remote.onRename = { if (it.endsWith("探索员.md")) remote.file(it).writeText("user won") }
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, remote.install().entries["agents/探索员.md"])
        assertEquals("user won", remote.file("${remote.home}/.aicode/agents/探索员.md").readText())
        verify(exactly = 0) { remote.client.rename(any(), any(), any()) }
    }

    @Test fun changingConnectionDuringCopyCancelsRatherThanWritingTheNewServer() = runBlocking {
        val remote = Server()
        val other = Server()
        val (connection, owner) = connection(remote)
        val newOwner = mockk<SSHClient>(relaxed = true)
        val identity = connection.captureIdentity()
        remote.onWrite = {
            if (it.endsWith("SKILL.md")) {
                setField(connection, "sshClient", newOwner)
                setField(connection, "activeIdentity", RemoteConnectionIdentity(newOwner, other.home))
            }
        }
        try {
            connection.uploadBuiltinDefinitions(emptyMap(), skills, identity)
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertFalse(remote.exists("${remote.home}/.aicode/skills/dev-god"))
        assertFalse(other.exists("${other.home}/.aicode"))
        verify(exactly = 1) { owner.newSFTPClient(); remote.client.close() }
        verify(exactly = 0) { newOwner.newSFTPClient(); owner.close(); owner.disconnect() }
    }

    @Test fun reconnectToSameTransportAndHomeStillInvalidatesThePreviousGeneration() = runBlocking {
        val server = Server()
        val (connection, owner) = connection(server)
        val old = connection.captureIdentity()
        val next = RemoteConnectionIdentity(owner, old.home)
        setField(connection, "activeIdentity", next)
        assertNotEquals(old.generation, next.generation)
        assertThrows(CancellationException::class.java) { connection.checkIdentity(old) }
        try { connection.openReadChannel(old); fail("old generation must not open") } catch (_: CancellationException) { }
        verify(exactly = 0) { owner.newSFTPClient() }
        assertSame(next, connection.captureIdentity())
    }

    @Test fun ownedReadChannelRemainsOpenUntilCallerClosesItAndNeverTouchesSharedSftp() = runBlocking {
        val server = Server()
        val (connection, owner) = connection(server)
        val cached = mockk<SFTPClient>(relaxed = true)
        setField(connection, "sftpClient", cached)
        val channel = connection.openReadChannel(connection.captureIdentity())
        assertSame(server.client, channel)
        verify(exactly = 0) { server.client.close(); cached.close() }
        channel.close()
        verify(exactly = 1) { owner.newSFTPClient(); server.client.close() }
    }

    @Test fun cancellationDuringChannelAcquisitionClosesTheAcquiredResource() = runBlocking {
        val server = Server()
        val (connection, owner) = connection(server)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { owner.newSFTPClient() } answers {
            opened.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            server.client
        }
        val job = launch(Dispatchers.Default) { connection.openReadChannel(connection.captureIdentity()).close() }
        try {
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            withTimeout(10000) { job.cancelAndJoin() }
            verify(exactly = 1) { server.client.close() }
        } finally { release.countDown() }
    }

    @Test fun concurrentConnectionInstallsSerializeAndOnlyOnePayloadWins() = runBlocking {
        val server = Server()
        val (connection, owner) = connection(server)
        val identity = connection.captureIdentity()
        val first = async(Dispatchers.Default) { connection.uploadBuiltinDefinitions(agents, skills, identity) }
        val second = async(Dispatchers.Default) { connection.uploadBuiltinDefinitions(agents, skills, identity) }
        val reports = listOf(first.await(), second.await())
        assertEquals(1, reports.count { it.entries["skills/dev-god"] == BuiltinInstallStatus.INSTALLED })
        assertEquals(1, reports.count { it.entries["skills/dev-god"] == BuiltinInstallStatus.ALREADY_HANDLED })
        skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, server.file("${server.home}/.aicode/skills/dev-god/$name").readBytes()) }
        verify(exactly = 2) { owner.newSFTPClient(); server.client.close() }
    }
}
