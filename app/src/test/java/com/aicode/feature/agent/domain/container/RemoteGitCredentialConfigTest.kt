package com.aicode.feature.agent.domain.container

import android.app.Application
import com.aicode.core.util.FileLogger
import com.aicode.feature.credentials.domain.model.GitCredential
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
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
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteGitCredentialConfigTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = mockk<SSHClient>(relaxed = true)
    private val channel = mockk<SFTPClient>(relaxed = true)
    private val installer = mockk<ContainerInstaller>()
    private lateinit var connection: RemoteSshConnection
    private lateinit var disk: File
    private val home = "/home/fixture"
    private val root = "/projects/selected"
    private var onWrite: (String) -> Unit = {}
    private var onRename: (String) -> Unit = {}
    private var onRemove: (String) -> Unit = {}
    private val permissions = mutableMapOf<String, Int>()

    private fun remote(path: String) = File(disk, path.removePrefix("/"))

    private fun field(name: String, value: Any?) {
        connection.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }

    @Before
    fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        disk = temp.newFolder()
        val local = temp.newFolder()
        File(local, "git-credential-aicode").writeText("#!/bin/sh\nexit 0\n")
        every { installer.aicodeDir } returns local
        every { installer.extractCredentialHelper() } just Runs
        every { owner.isConnected } returns true
        every { owner.isAuthenticated } returns true
        every { owner.newSFTPClient() } returns channel
        every { channel.mkdirs(any()) } answers { remote(firstArg()).mkdirs(); Unit }
        every { channel.open(any<String>(), any<Set<OpenMode>>()) } answers {
            val path = firstArg<String>()
            assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), secondArg<Set<OpenMode>>())
            assertTrue(remote(path).createNewFile())
            mockk<RemoteFile>(relaxed = true).also { handle ->
                every { handle.write(any(), any(), any(), any()) } answers {
                    RandomAccessFile(remote(path), "rw").use { file ->
                        file.seek(firstArg())
                        file.write(secondArg<ByteArray>(), thirdArg(), arg(3))
                    }
                    onWrite(path)
                }
            }
        }
        every { channel.chmod(any(), any()) } answers {
            permissions[firstArg()] = secondArg()
            Unit
        }
        every { channel.size(any()) } answers { remote(firstArg()).length() }
        every { channel.rename(any(), any(), any<Set<RenameFlags>>()) } answers {
            val from = firstArg<String>()
            val to = secondArg<String>()
            assertEquals(setOf(RenameFlags.ATOMIC, RenameFlags.OVERWRITE), thirdArg<Set<RenameFlags>>())
            Files.move(remote(from).toPath(), remote(to).toPath(), REPLACE_EXISTING)
            onRename(to)
            Unit
        }
        every { channel.statExistence(any()) } answers {
            remote(firstArg()).takeIf { it.exists() }?.let { FileAttributes.Builder().withSize(it.length()).build() }
        }
        every { channel.rm(any()) } answers {
            val path = firstArg<String>()
            onRemove(path)
            remote(path).delete()
            Unit
        }
        connection = RemoteSshConnection(mockk(), mockk(), mockk(), installer)
        field("sshClient", owner)
        field("remoteHome", home)
        field("activeIdentity", RemoteConnectionIdentity(owner, home))
    }

    @After
    fun release() { unmockkObject(FileLogger) }

    @Test
    fun uploadPublishesOnlyAfterThreeAtomicFilesAndPreservesServerConfigs() = runBlocking {
        val legacy = remote("$home/.aicode/gitconfig").apply { parentFile!!.mkdirs(); writeText("legacy user config") }
        val global = remote("$home/.gitconfig").apply { writeText("global identity") }
        val xdg = remote("$home/.config/git/config").apply { parentFile!!.mkdirs(); writeText("xdg proxy") }
        onRename = { assertNull(connection.gitCredentialWorkspaceRoot) }
        connection.uploadGitCredentialConfig(emptyList(), "$root/")
        assertEquals(root, connection.gitCredentialWorkspaceRoot)
        assertEquals("legacy user config", legacy.readText())
        assertEquals("global identity", global.readText())
        assertEquals("xdg proxy", xdg.readText())
        assertEquals(setOf("gitconfig", "git-credential-aicode", "git-credentials", "gitconfig.credential"), legacy.parentFile!!.list()!!.toSet())
        assertTrue(remote("$home/.aicode/git-credential-aicode").readText().contains("export AICODE_CRED_NO_PROMPT=1"))
        assertTrue(remote("$home/.aicode/gitconfig.credential").readText().contains("helper = \"!AICODE_CRED_NO_PROMPT=1 '"))
        assertEquals(1, permissions.values.count { it == 448 })
        assertEquals(2, permissions.values.count { it == 384 })
        verify(exactly = 1) { owner.newSFTPClient() }
        verify(exactly = 1) { channel.close() }
        verify(exactly = 0) { owner.startSession() }
    }

    @Test
    fun failedRefreshDisablesInjectionAndKeepsThePreviousAtomicCredentialFile() = runBlocking {
        connection.uploadGitCredentialConfig(emptyList(), root)
        val credentials = remote("$home/.aicode/git-credentials").apply { writeText("previous encoded fixture") }
        onWrite = { if (it.contains("/git-credentials.tmp.")) throw IOException("fixture write failed") }
        connection.uploadGitCredentialConfig(listOf(GitCredential("fixture.invalid", "fixture.invalid", "fixture", "fixture-only")), "/projects/next")
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertEquals("previous encoded fixture", credentials.readText())
        assertTrue(credentials.parentFile!!.list()!!.none { it.contains(".tmp.") })
        verify(exactly = 2) { channel.close() }
    }

    @Test
    fun reconnectDuringUploadCannotPublishOldScopeOrWriteToNewTransport() = runBlocking {
        val replacement = mockk<SSHClient>(relaxed = true)
        onRename = { path ->
            if (path.endsWith("/git-credential-aicode")) {
                field("sshClient", replacement)
                field("activeIdentity", RemoteConnectionIdentity(replacement, home))
            }
        }
        val failure = runCatching { connection.uploadGitCredentialConfig(emptyList(), root) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertFalse(remote("$home/.aicode/git-credentials").exists())
        verify(exactly = 0) { replacement.newSFTPClient() }
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun sameHomeReconnectDoesNotReuseAnEarlierInjection() = runBlocking {
        connection.uploadGitCredentialConfig(emptyList(), root)
        field("activeIdentity", RemoteConnectionIdentity(owner, home))
        assertNull(connection.gitCredentialWorkspaceRoot)
        connection.uploadGitCredentialConfig(emptyList(), root)
        assertEquals(root, connection.gitCredentialWorkspaceRoot)
        connection.disconnect()
        assertNull(connection.gitCredentialWorkspaceRoot)
        field("sshClient", owner)
        field("remoteHome", home)
        field("activeIdentity", RemoteConnectionIdentity(owner, home))
        assertNull(connection.gitCredentialWorkspaceRoot)
    }

    @Test
    fun revokeClearsScopeBeforeDeletingAndKeepsLegacyUserConfig() = runBlocking {
        connection.uploadGitCredentialConfig(emptyList(), root)
        val legacy = remote("$home/.aicode/gitconfig").apply { writeText("user identity") }
        onRemove = { assertNull(connection.gitCredentialWorkspaceRoot) }
        connection.removeGitCredentialConfig()
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertEquals("user identity", legacy.readText())
        assertEquals(listOf("gitconfig"), legacy.parentFile!!.list()!!.toList())
    }

    @Test
    fun revokeFailureStillDisablesNewCommandsWithoutDeletingLegacyConfig() = runBlocking {
        connection.uploadGitCredentialConfig(emptyList(), root)
        val legacy = remote("$home/.aicode/gitconfig").apply { writeText("user proxy") }
        onRemove = { throw IOException("fixture revoke failed") }
        connection.removeGitCredentialConfig()
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertEquals("user proxy", legacy.readText())
        assertTrue(remote("$home/.aicode/gitconfig.credential").exists())
    }

    @Test
    fun cancellationPropagatesAndNeverPublishesAPartialInjection() = runBlocking {
        onWrite = { throw CancellationException("fixture cancelled") }
        assertTrue(runCatching { connection.uploadGitCredentialConfig(emptyList(), root) }.exceptionOrNull() is CancellationException)
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertTrue(remote("$home/.aicode").list()!!.none { it.contains(".tmp.") })
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun queuedUploadKeepsTheIdentityFromBeforeItWaitedForTheCredentialLock() = runBlocking {
        val lock = connection.javaClass.getDeclaredField("credentialSyncMutex").apply { isAccessible = true }.get(connection) as Mutex
        lock.lock()
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching { connection.uploadGitCredentialConfig(emptyList(), root) }.exceptionOrNull()
        }
        val replacement = mockk<SSHClient>(relaxed = true)
        field("sshClient", replacement)
        field("activeIdentity", RemoteConnectionIdentity(replacement, home))
        lock.unlock()
        job.join()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { owner.newSFTPClient() }
        verify(exactly = 0) { replacement.newSFTPClient() }
        verify(exactly = 0) { channel.mkdirs(any()) }
    }

    @Test
    fun queuedRevokeCannotDeleteFilesFromAReplacementConnection() = runBlocking {
        val lock = connection.javaClass.getDeclaredField("credentialSyncMutex").apply { isAccessible = true }.get(connection) as Mutex
        lock.lock()
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching { connection.removeGitCredentialConfig() }.exceptionOrNull()
        }
        val replacement = mockk<SSHClient>(relaxed = true)
        field("sshClient", replacement)
        field("activeIdentity", RemoteConnectionIdentity(replacement, home))
        lock.unlock()
        job.join()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { owner.newSFTPClient() }
        verify(exactly = 0) { replacement.newSFTPClient() }
        verify(exactly = 0) { channel.rm(any()) }
    }

    @Test
    fun cancellationAfterScopePublicationClearsItAtTheIoReturnBoundary() = runBlocking {
        val published = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { channel.close() } answers {
            assertEquals(root, connection.gitCredentialWorkspaceRoot)
            published.countDown()
            check(release.await(3, TimeUnit.SECONDS)) { "Fixture did not release channel close" }
        }
        val job = launch(Dispatchers.Default) { connection.uploadGitCredentialConfig(emptyList(), root) }
        try {
            assertTrue(withContext(Dispatchers.IO) { published.await(3, TimeUnit.SECONDS) })
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(connection.gitCredentialWorkspaceRoot)
        assertTrue(remote("$home/.aicode/gitconfig.credential").isFile)
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun disconnectedUploadAndRevokeDoNotOpenASftpChannel() = runBlocking {
        every { owner.isConnected } returns false
        connection.uploadGitCredentialConfig(emptyList(), root)
        connection.removeGitCredentialConfig()
        assertNull(connection.gitCredentialWorkspaceRoot)
        verify(exactly = 0) { owner.newSFTPClient() }
    }
}
