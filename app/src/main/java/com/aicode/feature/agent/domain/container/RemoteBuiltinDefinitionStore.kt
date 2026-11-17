package com.aicode.feature.agent.domain.container

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.util.EnumSet

/** Uses an owned subsystem on one captured SSH transport, never the mutable shared sftp(). */
internal class RemoteBuiltinDefinitionStore(
    private val client: SFTPClient,
    home: String,
    private val checkCurrent: () -> Unit
) : BuiltinDefinitionStore {
    private val home = home.trimEnd('/').ifEmpty { "/" }
    private val root = this.home.trimEnd('/') + "/.aicode"
    private val owned = HashSet<String>()

    init {
        require(this.home.startsWith('/')) { "Remote home must be absolute" }
        this.home.removePrefix("/").takeIf { it.isNotEmpty() }?.let(BuiltinDefinitionPaths::relative)
    }

    private fun attributes(path: String): FileAttributes? = try {
        client.lstat(path)
    } catch (e: SFTPException) {
        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
    }

    private fun requireDirectory(path: String) {
        check(attributes(path)?.type == FileMode.Type.DIRECTORY) { "Unsafe remote builtin directory" }
    }

    private fun checkHome() {
        checkCurrent()
        requireDirectory("/")
        var path = ""
        home.split('/').filter { it.isNotEmpty() }.forEach { component ->
            path += "/$component"
            requireDirectory(path)
        }
    }

    private fun mkdir(path: String) {
        if (attributes(path) == null) {
            try {
                client.sftpEngine.makeDir(path, FileAttributes.Builder().withPermissions(448).build())
            } catch (e: SFTPException) {
                if (attributes(path)?.type != FileMode.Type.DIRECTORY) throw e
            }
        }
        requireDirectory(path)
    }

    private fun checked(path: String): String {
        BuiltinDefinitionPaths.managed(path)
        checkHome()
        requireDirectory(root)
        var parent = root
        path.split('/').dropLast(1).forEach { component ->
            parent += "/$component"
            requireDirectory(parent)
        }
        return "$root/$path"
    }

    override fun node(path: String): BuiltinNode {
        val attrs = attributes(checked(path)) ?: return BuiltinNode.MISSING
        return when (attrs.type) {
            FileMode.Type.DIRECTORY -> BuiltinNode.DIRECTORY
            FileMode.Type.REGULAR -> BuiltinNode.FILE
            else -> BuiltinNode.OTHER
        }
    }

    override fun children(path: String): List<String> {
        val dest = checked(path)
        requireDirectory(dest)
        val entries = client.ls(dest).map { it.name }.filterNot { it == "." || it == ".." }
        requireDirectory(checked(path))
        check(entries.size <= BuiltinDefinitionPaths.MAX_SCAN_ENTRIES) { "Too many remote builtin candidates" }
        return entries
    }

    override fun readDefinition(path: String, maxBytes: Int): ByteArray? {
        require(maxBytes in 1..BuiltinDefinitionPaths.MAX_DEFINITION_BYTES)
        val dest = checked(path)
        val before = attributes(dest) ?: return null
        if (before.type != FileMode.Type.REGULAR || before.size !in 0..maxBytes.toLong()) return null
        val bytes = ByteArray(before.size.toInt())
        client.open(dest, EnumSet.of(OpenMode.READ)).use { file ->
            val opened = attributes(checked(path)) ?: return null
            if (opened.type != FileMode.Type.REGULAR || opened.size != before.size || opened.mtime != before.mtime) return null
            var offset = 0
            while (offset < bytes.size) {
                checkCurrent()
                val count = file.read(offset.toLong(), bytes, offset, minOf(32 * 1024, bytes.size - offset))
                if (count <= 0) return null
                offset += count
            }
            if (file.read(offset.toLong(), ByteArray(1), 0, 1) > 0) return null
        }
        val after = attributes(checked(path)) ?: return null
        return bytes.takeIf { after.type == FileMode.Type.REGULAR && after.size == before.size && after.mtime == before.mtime }
    }

    override fun ensureDirectory(path: String) {
        BuiltinDefinitionPaths.managed(path)
        checkHome()
        mkdir(root)
        var parent = root
        path.split('/').forEach { component ->
            requireDirectory(parent)
            parent += "/$component"
            mkdir(parent)
        }
    }

    override fun createDirectory(path: String) {
        val dest = checked(path)
        client.sftpEngine.makeDir(dest, FileAttributes.Builder().withPermissions(448).build())
        owned.add(path)
        requireDirectory(dest)
    }

    override fun writeNew(path: String, bytes: ByteArray) {
        val dest = checked(path)
        client.open(dest, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
            FileAttributes.Builder().withPermissions(384).build()).use { file ->
            owned.add(path)
            var offset = 0
            while (offset < bytes.size) {
                checkCurrent()
                val count = minOf(32 * 1024, bytes.size - offset)
                file.write(offset.toLong(), bytes, offset, count)
                offset += count
            }
        }
        val attrs = attributes(checked(path))
        check(attrs?.type == FileMode.Type.REGULAR && attrs.size == bytes.size.toLong()) { "Remote builtin size mismatch" }
    }

    override fun publish(staged: String, target: String, directory: Boolean): Boolean {
        val source = checked(staged)
        val dest = checked(target)
        check(node(staged) == if (directory) BuiltinNode.DIRECTORY else BuiltinNode.FILE)
        checkCurrent()
        return try {
            // Standard SFTP RENAME rejects existing names. Do not request posix-rename/OVERWRITE.
            client.rename(source, dest)
            owned.remove(staged)
            true
        } catch (e: SFTPException) {
            if (node(target) != BuiltinNode.MISSING) false else throw e
        }
    }

    override fun isReceipt(path: String): Boolean = attributes(checked(path))?.let {
        it.type == FileMode.Type.REGULAR && it.size == 0L
    } == true

    override fun remove(path: String) {
        if (path !in owned) return
        val dest = checked(path)
        when (attributes(dest)?.type) {
            null -> Unit
            FileMode.Type.DIRECTORY -> client.rmdir(dest)
            else -> client.rm(dest)
        }
        owned.remove(path)
    }
}
