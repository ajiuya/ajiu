package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.aicode.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

internal data class ChrootAppSources(
    val profile: ContainerProfile,
    val workspacePath: String,
    val homePath: String,
    val appIdentity: ChrootAppIdentity,
    val mounts: List<ChrootMount>,
    val dns: String
)

@Singleton
internal class ChrootAppSourceCapture @Inject constructor(@ApplicationContext context: Context) {
    private val filesDir = context.filesDir.canonicalFile

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
        val mounts = mutableListOf(
            mount(home, "/root", false, true, identity.uid),
            mount(workspace, "/root/workspace", false, true, identity.uid, owned = false),
            mount(dnsFile, "/etc/resolv.conf", true, false, identity.uid)
        )
        CONFIG_DIRECTORIES.forEach { name ->
            val source = File(filesDir, "aicode/$name")
            if (Files.exists(source.toPath(), NOFOLLOW_LINKS)) {
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
        return ChrootAppSources(frozen, workspace.path, home.path, identity, Collections.unmodifiableList(mounts), dns)
    }

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
        val CONFIG_DIRECTORIES = listOf("skills", "agents", "memory", "docs", "bin", "lib", "plugins", "scripts", "prompts.custom")
        val READ_ONLY_CONFIG = setOf("skills", "agents", "docs", "bin", "lib", "prompts.custom")
    }
}
