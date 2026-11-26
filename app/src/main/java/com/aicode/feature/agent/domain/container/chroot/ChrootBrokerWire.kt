package com.aicode.feature.agent.domain.container.chroot

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

internal object ChrootBrokerWire {
    const val HELLO = 1
    const val HELLO_ACK = 2
    const val START = 3
    const val STOP = 4
    const val SHUTDOWN = 5
    const val STARTED = 6
    const val FAILED = 7
    const val STOPPED = 8
    const val BYE = 9
    const val RUNTIME = 1
    const val INSTALL = 2
    const val REMOVE = 3
    const val MAX_BODY = 256
    private const val MAGIC = 0x414a4252
    private const val EXIT_MAGIC = 0x414a4558

    fun frame(type: Int, body: ByteArray): ByteArray {
        require(type in HELLO..BYE && body.size <= MAX_BODY)
        return ByteBuffer.allocate(12 + body.size).putInt(MAGIC).putShort(1).putShort(type.toShort())
            .putInt(body.size).put(body).array()
    }

    fun header(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size != 12) throw IOException("Root channel header is incomplete")
        val input = ByteBuffer.wrap(bytes)
        if (input.int != MAGIC || input.short.toInt() != 1) throw IOException("Root channel header is invalid")
        val type = input.short.toInt() and 0xffff
        val length = input.int
        if (type !in HELLO..BYE || length !in 0..MAX_BODY) throw IOException("Root channel frame is invalid")
        return type to length
    }

    fun checkHello(body: ByteArray, nonce: ByteArray, uid: Int, pid: Int, start: Long, brokerPid: Int) {
        if (body.size != 56 || nonce.size != 32 || brokerPid <= 1) throw IOException("Root channel identity is invalid")
        val input = ByteBuffer.wrap(body)
        val received = ByteArray(32).also { input.get(it) }
        if (!MessageDigest.isEqual(received, nonce) || input.int != uid || input.int != pid ||
            input.long != start || input.int != brokerPid || input.int != 0) {
            throw IOException("Root channel identity does not match this process")
        }
    }

    fun start(id: Long, operation: Int, instanceId: String? = null, device: Long = 0, inode: Long = 0): ByteArray {
        require(id > 0 && operation in RUNTIME..REMOVE)
        val instance = if (operation == RUNTIME) {
            require(instanceId == null && device == 0L && inode == 0L)
            ByteArray(24)
        } else {
            require(instanceId != null && instanceId.matches(Regex("[0-9a-f]{24}")))
            if (operation == INSTALL) require(device == 0L && inode == 0L)
            else require(device >= 0 && inode > 0)
            instanceId.toByteArray(Charsets.US_ASCII)
        }
        return ByteBuffer.allocate(56).putLong(id).putInt(operation).putInt(0).put(instance)
            .putLong(device).putLong(inode).array()
    }

    fun checkStarted(body: ByteArray, id: Long): Int {
        if (body.size != 16) throw IOException("Root channel start response is incomplete")
        val input = ByteBuffer.wrap(body)
        if (input.long != id) throw IOException("Root channel request identity changed")
        val pid = input.int
        if (pid <= 1 || input.int != 0) throw IOException("Root channel child identity is invalid")
        return pid
    }

    fun checkFailed(body: ByteArray, id: Long) {
        if (body.size != 16) throw IOException("Root channel failure response is incomplete")
        val input = ByteBuffer.wrap(body)
        if (input.long != id || input.int !in 1..4 || input.int !in 1..4095) {
            throw IOException("Root channel failure response is invalid")
        }
    }

    fun exitStatus(bytes: ByteArray): Int {
        if (bytes.size != 16) throw IOException("Root helper exit status is unknown")
        val input = ByteBuffer.wrap(bytes)
        if (input.int != EXIT_MAGIC || input.short.toInt() != 1) throw IOException("Root helper exit status is invalid")
        val outcome = input.short.toInt()
        val value = input.int
        if (input.int != 0) throw IOException("Root helper exit status is invalid")
        return when {
            outcome == 1 && value in 0..255 -> value
            outcome == 2 && value in 1..64 -> -value
            outcome == 3 && value == 0 -> throw IOException("Root helper exit status is unknown")
            else -> throw IOException("Root helper exit status is invalid")
        }
    }
}
