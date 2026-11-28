package com.aicode.feature.agent.domain.container.chroot

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.IdentityHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChrootNetworkSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private enum class Source { RESOLVER, APT }

    private data class Fixture(
        val capture: ChrootAppSourceCapture,
        val source: Source,
        val file: File,
        val marker: File,
        val uid: Int
    )

    private data class FileSnapshot(
        val device: Long,
        val inode: Long,
        val uid: Int,
        val permissions: Set<PosixFilePermission>,
        val content: String
    )

    private data class HostDescriptor(val file: File, val channel: FileChannel, val opened: StructStat)

    private val descriptors = IdentityHashMap<FileDescriptor, HostDescriptor>()
    private val creationPermissions = mutableListOf<Set<PosixFilePermission>>()
    private val permissionOverrides = mutableMapOf<String, Int>()
    private var afterSync: ((File) -> Unit)? = null
    private var writeBudget: Int? = null
    private var maximumWriteSize = Int.MAX_VALUE

    @Before
    fun bridgeAndroidOsToHostFiles() {
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers { hostStat(File(firstArg<String>())) }
        every { Os.open(any(), any(), any()) } answers {
            openHostFile(File(firstArg<String>()), secondArg(), thirdArg())
        }
        every { Os.fstat(any()) } answers {
            val opened = descriptors.getValue(firstArg())
            val stat = opened.opened
            StructStat(stat.st_dev, stat.st_ino, stat.st_mode, stat.st_nlink, stat.st_uid, stat.st_gid,
                stat.st_rdev, opened.channel.size(), 0L, 0L, 0L, 0L, 0L)
        }
        every { Os.write(any(), any<ByteArray>(), any(), any()) } answers {
            val budget = writeBudget
            if (budget == 0) throw IOException("Fixture disk full")
            val length = minOf(arg<Int>(3), maximumWriteSize, budget ?: Int.MAX_VALUE)
            val written = descriptors.getValue(firstArg()).channel.write(ByteBuffer.wrap(arg(1), arg(2), length))
            if (budget != null) writeBudget = budget - written
            written
        }
        every { Os.fsync(any()) } answers {
            val opened = descriptors.getValue(firstArg())
            opened.channel.force(true)
            val callback = afterSync
            afterSync = null
            callback?.invoke(opened.file)
            Unit
        }
        every { Os.close(any()) } answers {
            descriptors.remove(firstArg())!!.channel.close()
        }
    }

    @After
    fun closeHostDescriptorsAndRestoreOs() {
        val leaked = descriptors.size
        try {
            descriptors.values.forEach { it.channel.close() }
        } finally {
            descriptors.clear()
            unmockkStatic(Os::class)
        }
        assertEquals("Refresh must close every descriptor, including on failure", 0, leaked)
    }

    @Test
    fun consecutiveResolverRefreshesKeepOldDescriptorsOnTheirOriginalContents() {
        val fixture = fixture(Source.RESOLVER)
        val original = snapshot(fixture.file)
        maximumWriteSize = 5

        FileInputStream(fixture.file).use { firstDescriptor ->
            refresh(fixture, dns = SECOND_DNS)
            val second = snapshot(fixture.file)
            assertNotEquals(original.inode, second.inode)
            assertEquals(SECOND_DNS, second.content)
            assertEquals(privatePermissions(), second.permissions)
            assertEquals(FIRST_DNS, contents(firstDescriptor))

            FileInputStream(fixture.file).use { secondDescriptor ->
                refresh(fixture, dns = THIRD_DNS)
                val third = snapshot(fixture.file)
                assertNotEquals(original.inode, third.inode)
                assertNotEquals(second.inode, third.inode)
                assertEquals(THIRD_DNS, third.content)
                assertEquals(privatePermissions(), third.permissions)
                assertEquals(FIRST_DNS, contents(firstDescriptor))
                assertEquals(SECOND_DNS, contents(secondDescriptor))
            }
        }
        assertEquals(setOf(".instance", "resolv.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun unchangedResolverContentKeepsItsInodeAndDoesNotCreateTemporaryFiles() {
        val fixture = fixture(Source.RESOLVER)
        val original = snapshot(fixture.file)

        repeat(2) { refresh(fixture, dns = FIRST_DNS) }

        assertEquals(original, snapshot(fixture.file))
        assertTrue(creationPermissions.isEmpty())
        assertEquals(setOf(".instance", "resolv.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun privateConfigsRejectSymlinkedSourceMarkerDirectoryAndAncestorWithoutChangingTargets() {
        for (source in Source.values()) {
            for (location in listOf("source", "marker", "directory", "ancestor")) {
                val fixture = fixture(source)
                val entry = when (location) {
                    "source" -> fixture.file
                    "marker" -> fixture.marker
                    "directory" -> fixture.file.parentFile!!
                    else -> fixture.file.parentFile!!.parentFile!!
                }
                val target = File(entry.parentFile, "${entry.name}-target")
                Files.move(entry.toPath(), target.toPath(), ATOMIC_MOVE)
                Files.createSymbolicLink(entry.toPath(), target.toPath())
                val realSource = fixture.file.canonicalFile
                val realMarker = fixture.marker.canonicalFile
                val original = snapshot(realSource)
                val marker = snapshot(realMarker)
                val originalEntries = entries(realSource.parentFile!!)
                val creations = creationPermissions.size

                assertThrows("$source/$location", IOException::class.java) { refresh(fixture) }

                assertEquals(original, snapshot(realSource))
                assertEquals(marker, snapshot(realMarker))
                assertTrue("$source/$location must remain a symlink", Files.isSymbolicLink(entry.toPath()))
                assertEquals(originalEntries, entries(realSource.parentFile!!))
                assertEquals(creations, creationPermissions.size)
            }
        }
    }

    @Test
    fun privateConfigsRejectForeignOwnerMetadataWithoutChangingSourceOrMarker() {
        for (source in Source.values()) {
            for (location in listOf("source", "marker", "directory", "ancestor")) {
                val fixture = fixture(source)
                val foreign = when (location) {
                    "source" -> fixture.file
                    "marker" -> fixture.marker
                    "directory" -> fixture.file.parentFile!!
                    else -> fixture.marker.parentFile!!
                }
                val original = snapshot(fixture.file)
                val marker = snapshot(fixture.marker)
                every { Os.lstat(foreign.path) } answers { hostStat(foreign, fixture.uid + 1) }

                val failure = assertThrows("$source/$location", IOException::class.java) { refresh(fixture) }

                assertEquals("CHROOT mount source is not an ordinary App source", failure.message)
                assertSourceUnchanged(fixture, original)
                assertEquals(marker, snapshot(fixture.marker))
            }
        }
        assertTrue(creationPermissions.isEmpty())
    }

    @Test
    fun privateConfigsRejectEveryNon0600SourceModeWithoutRepairingOrReplacingIt() {
        val modes = listOf("rw-r--r--" to 0x1a4, "rw-rw----" to 0x1b0, "r--------" to 0x100, "rwx------" to 0x1c0)
        for (source in Source.values()) {
            for ((permissions, mode) in modes) {
                val fixture = fixture(source)
                val host = hostStat(fixture.file)
                permissionOverrides[fixture.file.path] = mode
                val supplied = Os.lstat(fixture.file.path)
                assertEquals("$source/$permissions fixture must supply the requested mode", mode, supplied.st_mode and 0x1ff)
                assertEquals(host.st_mode and 0x1ff.inv(), supplied.st_mode and 0x1ff.inv())
                assertEquals(host.st_uid, supplied.st_uid)
                assertEquals(host.st_dev, supplied.st_dev)
                assertEquals(host.st_ino, supplied.st_ino)
                val original = snapshot(fixture.file)
                val marker = snapshot(fixture.marker)

                val failure = assertThrows("$source/$permissions", IOException::class.java) { refresh(fixture) }

                val label = if (source == Source.RESOLVER) "DNS source" else "APT runtime config"
                assertEquals("CHROOT $label mode is invalid", failure.message)
                assertSourceUnchanged(fixture, original)
                assertEquals(marker, snapshot(fixture.marker))
            }
        }
        assertTrue(creationPermissions.isEmpty())
    }

    @Test
    fun privateConfigsRejectAnotherInstallGenerationWithoutChangingEitherFile() {
        for (source in Source.values()) {
            val fixture = fixture(source)
            fixture.marker.writeText(OTHER_MARKER, Charsets.US_ASCII)
            val original = snapshot(fixture.file)
            val marker = snapshot(fixture.marker)

            val failure = assertThrows(IOException::class.java) { refresh(fixture) }

            val label = if (source == Source.RESOLVER) "DNS source" else "APT runtime source"
            assertEquals("CHROOT $label identity does not match the instance", failure.message)
            assertSourceUnchanged(fixture, original)
            assertEquals(marker, snapshot(fixture.marker))
        }
        assertTrue(creationPermissions.isEmpty())
    }

    @Test
    fun invalidOrOversizedResolverContentNeverChangesThePublishedSource() {
        val fixture = fixture(Source.RESOLVER)
        val original = snapshot(fixture.file)
        val invalidContents = listOf("", "nameserver proxy.example\n", "search example.test\n",
            "$SECOND_DNS options rotate\n", SECOND_DNS.repeat(256))

        for (invalid in invalidContents) {
            val failure = assertThrows(IOException::class.java) { refresh(fixture, dns = invalid) }
            assertEquals("Android network supplied an invalid DNS configuration", failure.message)
            assertSourceUnchanged(fixture, original)
        }
        assertTrue(creationPermissions.isEmpty())
    }

    @Test
    fun resolverMarkerChangedAfterWritingAbortsPublicationAndRemovesTheTemporaryFile() {
        val fixture = fixture(Source.RESOLVER)
        val original = snapshot(fixture.file)
        afterSync = { fixture.marker.writeText(OTHER_MARKER, Charsets.US_ASCII) }

        val failure = assertThrows(IOException::class.java) { refresh(fixture) }

        assertEquals("CHROOT DNS source identity changed during refresh", failure.message)
        assertSourceUnchanged(fixture, original)
        assertEquals(OTHER_MARKER, fixture.marker.readText(Charsets.US_ASCII))
        assertEquals(1, creationPermissions.size)
        assertEquals(setOf(".instance", "resolv.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun resolverReplacedAfterWritingIsNotOverwrittenByThePendingPublication() {
        val fixture = fixture(Source.RESOLVER)
        val competing = File(fixture.file.parentFile, "competing.conf")
        writePrivate(competing, THIRD_DNS)
        val replacement = snapshot(competing)
        afterSync = {
            Files.move(competing.toPath(), fixture.file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        val failure = assertThrows(IOException::class.java) { refresh(fixture) }

        assertEquals("CHROOT private network config source changed during refresh", failure.message)
        assertSourceUnchanged(fixture, replacement)
        assertEquals(1, creationPermissions.size)
        assertEquals(setOf(".instance", "resolv.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun incompleteWriteKeepsTheOriginalResolverAndDeletesThePartialTemporaryFile() {
        val fixture = fixture(Source.RESOLVER)
        val original = snapshot(fixture.file)
        writeBudget = 7

        val failure = assertThrows(IOException::class.java) { refresh(fixture) }

        assertEquals("Fixture disk full", failure.message)
        assertEquals(0, writeBudget)
        assertSourceUnchanged(fixture, original)
        assertEquals(1, creationPermissions.size)
        assertEquals(setOf(".instance", "resolv.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun aptRuntimeIs0600FromItsFirstCreationAndPublishesTheCompleteProxyConfig() {
        val fixture = fixture(Source.APT, existing = false)
        assertFalse(Files.exists(fixture.file.toPath(), NOFOLLOW_LINKS))

        refresh(fixture)

        assertEquals(listOf(privatePermissions()), creationPermissions)
        val published = snapshot(fixture.file)
        assertEquals(fixture.uid, published.uid)
        assertEquals(privatePermissions(), published.permissions)
        assertEquals(PROXY_APT, published.content)
        assertEquals(setOf("apt-runtime.conf"), entries(fixture.file.parentFile!!))
        assertEquals(MARKER, fixture.marker.readText(Charsets.US_ASCII))
    }

    @Test
    fun disablingProxyReplacesAptRuntimeWithoutLeavingCredentialsOrProxyDirectives() {
        val fixture = fixture(Source.APT, existing = false)
        refresh(fixture)
        val proxied = snapshot(fixture.file)
        assertEquals(PROXY_APT, proxied.content)

        refresh(fixture, proxy = emptyMap())

        val disabled = snapshot(fixture.file)
        assertNotEquals(proxied.inode, disabled.inode)
        assertEquals(NO_PROXY_APT, disabled.content)
        assertEquals(privatePermissions(), disabled.permissions)
        assertEquals(setOf("apt-runtime.conf"), entries(fixture.file.parentFile!!))
        fixture.file.parentFile!!.listFiles()!!.forEach { file ->
            val content = file.readText(Charsets.US_ASCII)
            assertFalse(content.contains("fixture-user"))
            assertFalse(content.contains("fixture-password"))
            assertFalse(content.contains("proxy.example"))
            assertFalse(content.contains("Acquire::"))
        }
    }

    @Test
    fun unchangedAptRuntimeKeepsItsInodeAndDoesNotCreateAnotherTemporaryFile() {
        val fixture = fixture(Source.APT, existing = false)
        refresh(fixture)
        val original = snapshot(fixture.file)
        val creations = creationPermissions.size

        repeat(2) { refresh(fixture) }

        assertEquals(original, snapshot(fixture.file))
        assertEquals(1, creations)
        assertEquals(creations, creationPermissions.size)
        assertEquals(setOf("apt-runtime.conf"), entries(fixture.file.parentFile!!))
    }

    @Test
    fun aptMarkerChangedDuringFirstPublicationLeavesNoConfigOrCredentialBearingTemporary() {
        val fixture = fixture(Source.APT, existing = false)
        afterSync = { fixture.marker.writeText(OTHER_MARKER, Charsets.US_ASCII) }

        val failure = assertThrows(IOException::class.java) { refresh(fixture) }

        assertEquals("CHROOT APT runtime source identity does not match the instance", failure.message)
        assertFalse(Files.exists(fixture.file.toPath(), NOFOLLOW_LINKS))
        assertTrue(entries(fixture.file.parentFile!!).isEmpty())
        assertEquals(OTHER_MARKER, fixture.marker.readText(Charsets.US_ASCII))
        assertEquals(1, creationPermissions.size)
    }

    private fun fixture(source: Source, existing: Boolean = true): Fixture {
        val files = temporary.newFolder().canonicalFile
        val root = File(files, if (source == Source.RESOLVER) "chroot-dns/instance" else "chroot-home/instance")
        assertTrue(root.mkdirs())
        val marker = File(root, ".instance")
        writePrivate(marker, MARKER)
        val parent = if (source == Source.RESOLVER) root else File(root, ".aicode").apply { assertTrue(mkdir()) }
        val file = File(parent, if (source == Source.RESOLVER) "resolv.conf" else "apt-runtime.conf")
        if (existing) writePrivate(file, if (source == Source.RESOLVER) FIRST_DNS else NO_PROXY_APT)
        val context = mockk<Context>()
        val connectivity = mockk<ConnectivityManager>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns files
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        return Fixture(ChrootAppSourceCapture(context), source, file, marker, ownerUid(parent))
    }

    private fun refresh(fixture: Fixture, dns: String = SECOND_DNS, proxy: Map<String, String> = PROXY) {
        val method = if (fixture.source == Source.RESOLVER) {
            ChrootAppSourceCapture::class.java.getDeclaredMethod("refreshResolver", File::class.java,
                String::class.java, String::class.java, Int::class.javaPrimitiveType)
        } else {
            ChrootAppSourceCapture::class.java.getDeclaredMethod("refreshAptRuntimeConfig", File::class.java,
                Map::class.java, Int::class.javaPrimitiveType, String::class.java)
        }.apply { isAccessible = true }
        try {
            if (fixture.source == Source.RESOLVER) method.invoke(fixture.capture, fixture.file, MARKER, dns, fixture.uid)
            else method.invoke(fixture.capture, fixture.file, proxy, fixture.uid, MARKER)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun assertSourceUnchanged(fixture: Fixture, original: FileSnapshot) {
        assertEquals(original, snapshot(fixture.file))
        assertFalse(entries(fixture.file.parentFile!!).any { it.startsWith(".${fixture.file.name}-") })
    }

    private fun snapshot(file: File): FileSnapshot {
        val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,uid", NOFOLLOW_LINKS)
        return FileSnapshot((attributes.getValue("dev") as Number).toLong(),
            (attributes.getValue("ino") as Number).toLong(), (attributes.getValue("uid") as Number).toInt(),
            Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS), file.readText(Charsets.US_ASCII))
    }

    private fun ownerUid(file: File): Int = (Files.getAttribute(file.toPath(), "unix:uid", NOFOLLOW_LINKS) as Number).toInt()

    private fun entries(directory: File): Set<String> = directory.list()!!.toSet()

    private fun privatePermissions(): Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    private fun writePrivate(file: File, content: String) {
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(privatePermissions()))
        file.writeText(content, Charsets.US_ASCII)
    }

    private fun contents(input: FileInputStream): String {
        input.channel.position(0)
        return String(input.readBytes(), Charsets.US_ASCII)
    }

    private fun hostStat(file: File, uidOverride: Int? = null): StructStat {
        val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,mode,nlink,uid,gid,rdev,size", NOFOLLOW_LINKS)
        fun number(name: String) = attributes.getValue(name) as Number
        val hostMode = number("mode").toInt()
        val mode = permissionOverrides[file.path]?.let { (hostMode and 0x1ff.inv()) or it } ?: hostMode
        return StructStat(number("dev").toLong(), number("ino").toLong(), mode,
            number("nlink").toLong(), uidOverride ?: number("uid").toInt(), number("gid").toInt(),
            number("rdev").toLong(), number("size").toLong(), 0L, 0L, 0L, 0L, 0L)
    }

    private fun openHostFile(file: File, flags: Int, mode: Int): FileDescriptor {
        val options = mutableSetOf<OpenOption>(if (flags and OsConstants.O_WRONLY != 0) WRITE else READ)
        if (flags and OsConstants.O_NOFOLLOW != 0) options += NOFOLLOW_LINKS
        if (flags and OsConstants.O_TRUNC != 0) options += TRUNCATE_EXISTING
        val channel = if (flags and OsConstants.O_CREAT != 0) {
            options += if (flags and OsConstants.O_EXCL != 0) CREATE_NEW else CREATE
            val permissions = "rwxrwxrwx".mapIndexed { index, permission ->
                if (mode and (1 shl (8 - index)) != 0) permission else '-'
            }.joinToString("")
            FileChannel.open(file.toPath(), options,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))).also {
                creationPermissions += Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS)
            }
        } else FileChannel.open(file.toPath(), options)
        return try {
            FileDescriptor().also { descriptors[it] = HostDescriptor(file, channel, hostStat(file)) }
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }

    private companion object {
        const val MARKER = "0123456789abcdef01234567\n5\n19\n42\n"
        const val OTHER_MARKER = "0123456789abcdef01234567\n5\n19\n43\n"
        const val FIRST_DNS = "nameserver 192.0.2.53\n"
        const val SECOND_DNS = "nameserver 198.51.100.53\nnameserver 2001:db8::53\n"
        const val THIRD_DNS = "nameserver 203.0.113.53\n"
        const val NO_PROXY_APT = "APT::Sandbox::User \"root\";\n"
        const val PROXY_APT = NO_PROXY_APT +
            "Acquire::http::Proxy \"http://fixture-user:fixture-password@proxy.example:3128\";\n" +
            "Acquire::https::Proxy \"http://fixture-user:fixture-password@proxy.example:3128\";\n"
        val PROXY = mapOf("HTTP_PROXY" to "http://fixture-user:fixture-password@proxy.example:3128",
            "HTTPS_PROXY" to "http://fixture-user:fixture-password@proxy.example:3128")
    }
}
