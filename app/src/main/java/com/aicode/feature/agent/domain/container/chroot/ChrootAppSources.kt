package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.aicode.core.net.AppProxy
import com.aicode.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

internal data class ChrootAppSources(
    val profile: ContainerProfile,
    val workspacePath: String,
    val homePath: String,
    val appIdentity: ChrootAppIdentity,
    val mounts: List<ChrootMount>,
    val dns: String,
    val proxyEnvironment: Map<String, String> = emptyMap()
)

@Singleton
internal class ChrootAppSourceCapture @Inject constructor(@ApplicationContext context: Context) {
    private val appContext = context.applicationContext
    private val filesDir = context.filesDir.canonicalFile
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun capture(profile: ContainerProfile, workspacePath: String): ChrootAppSources {
        if (!profile.isChroot || profile.extraBindings.isNotEmpty() || profile.extraArgs.isNotEmpty()) {
            throw IOException("CHROOT requires a dedicated profile without PRoot arguments or bindings")
        }
        val instance = profile.chrootInstance ?: throw IOException("CHROOT instance has not been created")
        if (!instance.id.matches(Regex("[0-9a-f]{24}")) || instance.installGeneration <= 0) {
            throw IOException("CHROOT instance identity is invalid")
        }
        ChrootFileIdentity(instance.device, instance.inode)
        val identity = appIdentity()
        val workspace = File(workspacePath)
        if (!workspace.isAbsolute || workspace.path != workspace.canonicalPath) {
            throw IOException("CHROOT workspace requires an ordinary absolute source")
        }
        val home = File(filesDir, "chroot-home/${instance.id}")
        val marker = File(home, ".instance")
        captureIdentity(marker, false, identity.uid, owned = true)
        val expectedMarker = "${instance.id}\n${instance.device}\n${instance.inode}\n${instance.installGeneration}\n"
        if (readBounded(marker, 256) != expectedMarker) throw IOException("CHROOT home identity does not match the instance")
        val dnsFile = File(filesDir, "chroot-dns/${instance.id}/resolv.conf")
        ChrootNetworkConfig.currentResolvConf(connectivity)?.let { refreshResolver(dnsFile, expectedMarker, it, identity.uid) }
        val proxyEnvironment = AppProxy.proxyEnv(appContext)
        refreshAptRuntimeConfig(File(home, ".aicode/apt-runtime.conf"), proxyEnvironment, identity.uid, expectedMarker)
        val mounts = mutableListOf(
            mount(home, "/root", false, true, identity.uid),
            mount(workspace, "/root/workspace", false, true, identity.uid, owned = false),
            mount(dnsFile, "/etc/resolv.conf", true, false, identity.uid)
        )
        prepareChrootToolOutputDirectory(filesDir, identity.uid)
        CONFIG_DIRECTORIES.forEach { name ->
            val source = File(filesDir, "aicode/$name")
            if (name == "tool-output" || Files.exists(source.toPath(), NOFOLLOW_LINKS)) {
                mounts += mount(source, "/root/.aicode/$name", name in READ_ONLY_CONFIG, true, identity.uid)
            }
        }
        val frozen = profile.copy(
            extraBindings = Collections.unmodifiableList(profile.extraBindings.toList()),
            extraArgs = Collections.unmodifiableList(profile.extraArgs.toList()),
            env = Collections.unmodifiableMap(profile.env.toMap())
        )
        val dns = readBounded(dnsFile, 4096)
        ChrootLaunch.freeze(instance.id, "000000000000000000000000", "capture:validation",
            ByteArray(32) { 1 }, ChrootFileIdentity(instance.device, instance.inode), identity,
            ChrootIoMode.STDIO, 15_000, 0, "/root/workspace", listOf(profile.shellPath?.takeIf(String::isNotBlank) ?: "/bin/sh"),
            profile.env.map { "${it.key}=${it.value}" }, mounts)
        return ChrootAppSources(frozen, workspace.path, home.path, identity, Collections.unmodifiableList(mounts), dns,
            Collections.unmodifiableMap(proxyEnvironment.toMap()))
    }

    private fun refreshResolver(file: File, expectedMarker: String, contents: String, uid: Int) = synchronized(resolverLock) {
        val parent = checkNotNull(file.parentFile)
        val parentIdentity = captureIdentity(parent, true, uid, owned = true)
        val marker = File(parent, ".instance")
        captureIdentity(marker, false, uid, owned = true)
        if (readBounded(marker, 256) != expectedMarker) throw IOException("CHROOT DNS source identity does not match the instance")
        if (contents.toByteArray(Charsets.US_ASCII).size > 4096 || !contents.lines().all {
                it.isEmpty() || it.startsWith("nameserver ") && it.substringAfter(' ').matches(DNS_ADDRESS)
            } || !contents.contains("nameserver ")) {
            throw IOException("Android network supplied an invalid DNS configuration")
        }
        val oldIdentity = captureIdentity(file, false, uid, owned = true)
        if (Os.lstat(file.path).st_mode and 0x1ff != 0x180) throw IOException("CHROOT DNS source mode is invalid")
        if (readBounded(file, 4096) == contents) return@synchronized
        publishPrivateConfig(file, contents, uid, parentIdentity, oldIdentity) {
            if (readBounded(marker, 256) != expectedMarker) throw IOException("CHROOT DNS source identity changed during refresh")
        }
    }

    private fun publishPrivateConfig(
        file: File,
        content: String,
        uid: Int,
        parentIdentity: ChrootFileIdentity,
        oldIdentity: ChrootFileIdentity?,
        extraCheck: () -> Unit = {}
    ) {
        val parent = checkNotNull(file.parentFile)
        val bytes = content.toByteArray(Charsets.US_ASCII)
        if (bytes.size > 4096) throw IOException("CHROOT private network config exceeds its bound")
        val temporary = File(parent, ".${file.name}-${randomHex()}")
        var created = false
        try {
            val descriptor = Os.open(temporary.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0x180)
            created = true
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(descriptor, bytes, offset, bytes.size - offset)
                    if (written <= 0) throw IOException("Incomplete CHROOT private network config write")
                    offset += written
                }
                Os.fsync(descriptor)
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != uid || stat.st_mode and 0x1ff != 0x180 ||
                    stat.st_size != bytes.size.toLong()) throw IOException("CHROOT private network config identity is invalid")
            } finally {
                Os.close(descriptor)
            }
            if (captureIdentity(parent, true, uid, owned = true) != parentIdentity ||
                oldIdentity != null && captureIdentity(file, false, uid, owned = true) != oldIdentity) {
                throw IOException("CHROOT private network config source changed during refresh")
            }
            extraCheck()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            created = false
            if (captureIdentity(parent, true, uid, owned = true) != parentIdentity ||
                captureIdentity(file, false, uid, owned = true) == oldIdentity || readBounded(file, 4096) != content) {
                throw IOException("CHROOT private network config changed during publication")
            }
            extraCheck()
        } finally {
            if (created) Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun refreshAptRuntimeConfig(file: File, proxy: Map<String, String>, uid: Int, expectedMarker: String) = synchronized(resolverLock) {
        val parent = checkNotNull(file.parentFile)
        val parentIdentity = captureIdentity(parent, true, uid, owned = true)
        val home = checkNotNull(parent.parentFile)
        val homeIdentity = captureIdentity(home, true, uid, owned = true)
        val marker = File(home, ".instance")
        captureIdentity(marker, false, uid, owned = true)
        fun checkHomeIdentity() {
            if (captureIdentity(home, true, uid, owned = true) != homeIdentity || readBounded(marker, 256) != expectedMarker) {
                throw IOException("CHROOT APT runtime source identity does not match the instance")
            }
        }
        checkHomeIdentity()
        val content = ChrootNetworkConfig.aptRuntimeConfig(proxy)
        val oldIdentity = if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            val mode = Os.lstat(file.path).st_mode
            if (mode and 0x1ff != 0x180) throw IOException("CHROOT APT runtime config mode is invalid")
            captureIdentity(file, false, uid, owned = true)
        } else null
        if (oldIdentity != null && readBounded(file, 4096) == content) return@synchronized
        if (captureIdentity(parent, true, uid, owned = true) != parentIdentity) {
            throw IOException("CHROOT APT runtime directory changed during refresh")
        }
        publishPrivateConfig(file, content, uid, parentIdentity, oldIdentity, ::checkHomeIdentity)
    }

    private fun randomHex(): String = ByteArray(12).also(random::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun mount(
        file: File,
        target: String,
        readOnly: Boolean,
        directory: Boolean,
        uid: Int,
        owned: Boolean = true
    ) = ChrootMount(
        if (directory) ChrootMountKind.DIRECTORY else ChrootMountKind.FILE,
        readOnly, file.path, target, captureIdentity(file, directory, uid, owned)
    )

    private fun captureIdentity(file: File, directory: Boolean, uid: Int, owned: Boolean): ChrootFileIdentity {
        var ancestor = file.parentFile
        while (ancestor != null) {
            if (!OsConstants.S_ISDIR(Os.lstat(ancestor.path).st_mode)) {
                throw IOException("CHROOT mount source has an unsupported ancestor")
            }
            ancestor = ancestor.parentFile
        }
        val before = Os.lstat(file.path)
        if (!(if (directory) OsConstants.S_ISDIR(before.st_mode) else OsConstants.S_ISREG(before.st_mode)) ||
            owned && before.st_uid != uid) throw IOException("CHROOT mount source is not an ordinary App source")
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            val opened = Os.fstat(descriptor)
            if (before.st_dev != opened.st_dev || before.st_ino != opened.st_ino ||
                before.st_mode != opened.st_mode || before.st_uid != opened.st_uid) {
                throw IOException("CHROOT mount source identity changed")
            }
            return ChrootFileIdentity(opened.st_dev, opened.st_ino)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun appIdentity(): ChrootAppIdentity {
        val uid = AndroidProcess.myUid()
        if (uid < 10_000 || Os.getuid() != uid || Os.getgid() != uid) {
            throw IOException("CHROOT requires the original application identity")
        }
        val status = readBounded(File("/proc/self/status"), 16 * 1024)
        val groups = status.lineSequence().filter { it.startsWith("Groups:") }.toList()
        if (groups.size != 1) throw IOException("CHROOT application groups are unavailable")
        val parts = groups.single().substringAfter(':').trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.size > 32 || parts.any { !it.matches(Regex("[0-9]{1,9}")) }) {
            throw IOException("CHROOT application groups are invalid")
        }
        return ChrootAppIdentity(uid, Os.getgid(), parts.map(String::toInt).sorted())
    }

    private fun readBounded(file: File, maximum: Int): String = file.inputStream().use { input ->
        val bytes = ByteArray(maximum + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) throw IOException("CHROOT App source could not be read")
            size += count
        }
        if (size > maximum) throw IOException("CHROOT App source exceeds its bound")
        String(bytes, 0, size, Charsets.US_ASCII)
    }

    private companion object {
        val resolverLock = Any()
        val DNS_ADDRESS = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F:]+")
        val random = SecureRandom()
        val CONFIG_DIRECTORIES = listOf("skills", "agents", "memory", "docs", "bin", "lib", "plugins", "scripts", "prompts.custom", "tool-output")
        val READ_ONLY_CONFIG = setOf("skills", "agents", "docs", "bin", "lib", "prompts.custom")
    }
}

internal fun prepareChrootToolOutputDirectory(root: File, uid: Int): File {
    fun identity(directory: File): ChrootFileIdentity {
        var ancestor: File? = directory
        while (ancestor != null) {
            if (!Files.isDirectory(ancestor.toPath(), NOFOLLOW_LINKS)) {
                throw IOException("CHROOT output directory has an unsupported ancestor")
            }
            ancestor = ancestor.parentFile
        }
        val attributes = Files.readAttributes(directory.toPath(), "unix:uid,dev,ino,isDirectory", NOFOLLOW_LINKS)
        if (attributes["isDirectory"] != true || (attributes["uid"] as Number).toInt() != uid) {
            throw IOException("CHROOT output directory is not owned by the application")
        }
        return ChrootFileIdentity((attributes["dev"] as Number).toLong(), (attributes["ino"] as Number).toLong())
    }

    var parent = root
    val rootIdentity = identity(root)
    for (name in listOf("aicode", "tool-output")) {
        val parentIdentity = identity(parent)
        val directory = File(parent, name)
        try {
            Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: FileAlreadyExistsException) {
        }
        identity(directory)
        if (identity(parent) != parentIdentity || identity(root) != rootIdentity) {
            throw IOException("CHROOT output directory source changed")
        }
        parent = directory
    }
    return parent
}
