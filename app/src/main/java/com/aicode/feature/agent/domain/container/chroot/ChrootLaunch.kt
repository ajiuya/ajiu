package com.aicode.feature.agent.domain.container.chroot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

enum class ChrootIoMode { STDIO, PTY }

enum class ChrootMountKind { DIRECTORY, FILE }

data class ChrootFileIdentity(val device: Long, val inode: Long) {
    init {
        require(device > 0 && inode > 0)
    }
}

class ChrootAppIdentity(uid: Int, gid: Int, groups: List<Int>) {
    val uid: Int = uid
    val gid: Int = gid
    val groups: List<Int> = Collections.unmodifiableList(groups.toList())

    init {
        require(uid >= 10_000 && uid == gid)
        require(this.groups.size <= 32 && this.groups.distinct().size == this.groups.size)
        require(this.groups.all { groupAllowed(uid, it) })
    }

    companion object {
        internal fun groupAllowed(uid: Int, group: Int): Boolean {
            if (uid < 10_000 || group < 0) return false
            if (group == 1_079 || group == 3_003 || group == 9_997 || group == 99_909_997 || group == uid) return true
            val user = uid / 100_000
            val app = uid % 100_000
            if (group.toLong() == user.toLong() * 100_000 + 9_997) return true
            if (app !in 10_000..19_999) return false
            return group.toLong() in listOf(
                uid.toLong() + 10_000, uid.toLong() + 20_000, uid.toLong() + 30_000, app.toLong() + 40_000
            )
        }
    }
}

data class ChrootMount(
    val kind: ChrootMountKind,
    val readOnly: Boolean,
    val source: String,
    val target: String,
    val identity: ChrootFileIdentity
)

class ChrootLaunch private constructor(
    val instanceId: String,
    val launchId: String,
    val ownerIdentity: String,
    val rootfsIdentity: ChrootFileIdentity,
    val appIdentity: ChrootAppIdentity,
    val ioMode: ChrootIoMode,
    val leaseMs: Int,
    val commandTimeoutMs: Int,
    val cwd: String,
    val argv: List<String>,
    val environment: List<String>,
    val mounts: List<ChrootMount>,
    private val tokenBytes: ByteArray,
    private val payloadBytes: ByteArray
) {
    val digest: String = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun token(): ByteArray = tokenBytes.copyOf()

    fun payload(): ByteArray = payloadBytes.copyOf()

    override fun toString(): String =
        "ChrootLaunch(instance=$instanceId, launch=$launchId, mode=$ioMode, argc=${argv.size}, envc=${environment.size}, mounts=${mounts.size})"

    companion object {
        private val nonce = Regex("[0-9a-f]{24}")
        private val environmentKey = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val configDirectories = setOf(
            "skills", "agents", "memory", "docs", "bin", "lib", "plugins", "scripts", "prompts.custom", "tool-output"
        )
        private val readOnlyConfigDirectories = setOf("skills", "agents", "docs", "bin", "lib", "prompts.custom")
        const val MAX_PAYLOAD_BYTES = 1024 * 1024

        fun freeze(
            instanceId: String,
            launchId: String,
            ownerIdentity: String,
            token: ByteArray,
            rootfsIdentity: ChrootFileIdentity,
            appIdentity: ChrootAppIdentity,
            ioMode: ChrootIoMode,
            leaseMs: Int,
            commandTimeoutMs: Int,
            cwd: String,
            argv: List<String>,
            environment: List<String>,
            mounts: List<ChrootMount>
        ): ChrootLaunch {
            require(nonce.matches(instanceId) && nonce.matches(launchId))
            require(token.size == 32 && token.any { it != 0.toByte() })
            require(ownerIdentity.isNotBlank())
            text(ownerIdentity, 512)
            require(leaseMs in 5_000..60_000)
            require(commandTimeoutMs in 0..1_800_000)
            path(cwd)
            require(argv.size in 1..256 && argv.first().isNotEmpty())
            argv.forEach { text(it, 65_536, allowEmpty = true) }
            require(environment.size <= 256)
            val keys = HashSet<String>()
            environment.forEach {
                text(it, 65_536)
                val key = it.substringBefore('=')
                require('=' in it && environmentKey.matches(key) && keys.add(key))
            }
            require(mounts.size <= 32 && mounts.map { it.target }.distinct().size == mounts.size)
            mounts.forEach { mount ->
                path(mount.source)
                path(mount.target)
                when {
                    mount.target == "/etc/resolv.conf" -> require(mount.kind == ChrootMountKind.FILE && mount.readOnly)
                    mount.target == "/root" || mount.target == "/root/workspace" -> require(mount.kind == ChrootMountKind.DIRECTORY)
                    mount.target.startsWith("/root/.aicode/") -> {
                        val name = mount.target.removePrefix("/root/.aicode/")
                        require(name in configDirectories && mount.kind == ChrootMountKind.DIRECTORY)
                        require(name !in readOnlyConfigDirectories || mount.readOnly)
                    }
                    else -> throw IllegalArgumentException("Unsupported chroot mount target")
                }
                require(sourceAllowed(instanceId, appIdentity.uid, mount))
            }
            val frozenToken = token.copyOf()
            val frozenIdentity = ChrootAppIdentity(appIdentity.uid, appIdentity.gid, appIdentity.groups)
            val frozenArgv = Collections.unmodifiableList(argv.toList())
            val frozenEnvironment = Collections.unmodifiableList(environment.toList())
            val frozenMounts = Collections.unmodifiableList(mounts.sortedBy { it.target.count { c -> c == '/' } })
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.write(frozenToken)
                output.writeInt(frozenIdentity.uid)
                output.writeInt(frozenIdentity.gid)
                output.writeInt(frozenIdentity.groups.size)
                frozenIdentity.groups.forEach(output::writeInt)
                output.writeInt(ioMode.ordinal)
                output.writeInt(leaseMs)
                output.writeInt(commandTimeoutMs)
                output.writeLong(rootfsIdentity.device)
                output.writeLong(rootfsIdentity.inode)
                fun string(value: String, limit: Int = 4096, allowEmpty: Boolean = false) {
                    val bytes = text(value, limit, allowEmpty)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    require(buffer.size() <= MAX_PAYLOAD_BYTES)
                }
                string(instanceId)
                string(launchId)
                string(ownerIdentity, 512)
                string(cwd)
                output.writeInt(frozenArgv.size)
                frozenArgv.forEach { string(it, 65_536, allowEmpty = true) }
                output.writeInt(frozenEnvironment.size)
                frozenEnvironment.forEach { string(it, 65_536) }
                output.writeInt(frozenMounts.size)
                frozenMounts.forEach { mount ->
                    output.writeInt(mount.kind.ordinal + 1)
                    output.writeInt(if (mount.readOnly) 1 else 0)
                    output.writeLong(mount.identity.device)
                    output.writeLong(mount.identity.inode)
                    string(mount.source)
                    string(mount.target)
                }
            }
            require(buffer.size() <= MAX_PAYLOAD_BYTES)
            return ChrootLaunch(
                instanceId, launchId, ownerIdentity, rootfsIdentity, frozenIdentity, ioMode,
                leaseMs, commandTimeoutMs, cwd, frozenArgv, frozenEnvironment, frozenMounts,
                frozenToken, buffer.toByteArray()
            )
        }

        private fun sourceAllowed(instanceId: String, uid: Int, mount: ChrootMount): Boolean {
            val user = uid / 100_000
            val files = listOf("com.ajiu", "com.ajiu.debug", "com.ajiu.beta")
                .map { "/data/user/$user/$it/files/" }
                .firstOrNull { mount.source.startsWith(it) }
            val relative = files?.let(mount.source::removePrefix)
            return when {
                mount.target == "/root" -> relative == "chroot-home/$instanceId"
                mount.target == "/etc/resolv.conf" -> relative == "chroot-dns/$instanceId/resolv.conf"
                mount.target.startsWith("/root/.aicode/") ->
                    relative == "aicode/${mount.target.removePrefix("/root/.aicode/")}"
                mount.target == "/root/workspace" ->
                    relative?.let { it.startsWith("projects/") && it.length > "projects/".length } == true ||
                        mount.source.startsWith("/storage/emulated/$user/")
                else -> false
            }
        }

        private fun text(value: String, limit: Int, allowEmpty: Boolean = false): ByteArray {
            require('\u0000' !in value)
            val bytes = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            require(bytes.remaining() <= limit && (allowEmpty || bytes.hasRemaining()))
            return ByteArray(bytes.remaining()).also(bytes::get)
        }

        private fun path(value: String) {
            text(value, 4096)
            require(value.startsWith('/'))
            if (value == "/") return
            require(value.drop(1).split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && text(it, 255).isNotEmpty()
            })
        }
    }
}
