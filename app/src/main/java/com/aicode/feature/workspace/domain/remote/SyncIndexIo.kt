package com.aicode.feature.workspace.domain.remote

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

internal open class SyncIndexIo {
    open fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File.createTempFile(".sync-index-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(target.parentFile!!)
        } finally {
            temp.delete()
        }
    }

    open fun append(journal: File, payload: ByteArray) {
        val existed = journal.exists()
        FileOutputStream(journal, true).use { file ->
            val output = DataOutputStream(file)
            output.writeInt(payload.size)
            output.writeInt(CRC32().apply { update(payload) }.value.toInt())
            output.write(payload)
            output.flush()
            file.fd.sync()
        }
        if (!existed) syncDirectory(journal.parentFile!!)
    }

    open fun truncate(journal: File, length: Long) {
        RandomAccessFile(journal, "rw").use { file -> file.setLength(length); file.fd.sync() }
    }

    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
