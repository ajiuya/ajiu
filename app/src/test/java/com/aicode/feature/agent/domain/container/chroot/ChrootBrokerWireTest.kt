package com.aicode.feature.agent.domain.container.chroot

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChrootBrokerWireTest {
    private val instanceId = "0123456789abcdef01234567"
    private val nonce = ByteArray(32) { (it + 1).toByte() }
    private val uid = 10445
    private val pid = 2048
    private val brokerPid = 4096
    private val startTime = Long.MAX_VALUE

    private fun header(type: Int = 1, length: Int = 0, version: Int = 1, magic: Int = 0x414a4252): ByteArray =
        ByteBuffer.allocate(12).putInt(magic).putShort(version.toShort()).putShort(type.toShort()).putInt(length).array()

    private fun hello(): ByteArray = ByteBuffer.allocate(56).put(nonce).putInt(uid).putInt(pid)
        .putLong(startTime).putInt(brokerPid).putInt(0).array()

    private fun checkHello(body: ByteArray, expectedNonce: ByteArray = nonce, expectedBrokerPid: Int = brokerPid) =
        ChrootBrokerWire.checkHello(body, expectedNonce, uid, pid, startTime, expectedBrokerPid)

    private fun started(id: Long = Long.MAX_VALUE, child: Int = 2, reserved: Int = 0): ByteArray =
        ByteBuffer.allocate(16).putLong(id).putInt(child).putInt(reserved).array()

    private fun failed(id: Long = Long.MAX_VALUE, code: Int = 1, errno: Int = 1): ByteArray =
        ByteBuffer.allocate(16).putLong(id).putInt(code).putInt(errno).array()

    private fun exit(outcome: Int = 1, value: Int = 0, reserved: Int = 0, version: Int = 1): ByteArray =
        ByteBuffer.allocate(16).putInt(0x414a4558).putShort(version.toShort()).putShort(outcome.toShort())
            .putInt(value).putInt(reserved).array()

    @Test
    fun frameUsesExactTwelveByteAjbrVersionOneHeaderAndCopiesBody() {
        val body = byteArrayOf(17, 34)
        val encoded = ChrootBrokerWire.frame(3, body)
        assertArrayEquals(byteArrayOf(65, 74, 66, 82, 0, 1, 0, 3, 0, 0, 0, 2, 17, 34), encoded)
        body.fill(0)
        assertArrayEquals(byteArrayOf(17, 34), encoded.copyOfRange(12, encoded.size))
        assertEquals(3 to 2, ChrootBrokerWire.header(encoded.copyOfRange(0, 12)))
    }

    @Test
    fun allNineTypesAcceptEmptyAndExactly256ByteBodiesButNoLargerBody() {
        for (type in 1..9) {
            for (length in listOf(0, 256)) {
                val body = ByteArray(length) { it.toByte() }
                val encoded = ChrootBrokerWire.frame(type, body)
                assertEquals(12 + length, encoded.size)
                assertArrayEquals(header(type, length), encoded.copyOfRange(0, 12))
                assertArrayEquals(body, encoded.copyOfRange(12, encoded.size))
                assertEquals(type to length, ChrootBrokerWire.header(encoded.copyOfRange(0, 12)))
            }
        }
        assertEquals(256, ChrootBrokerWire.MAX_BODY)
        assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.frame(1, ByteArray(257)) }
        for (type in listOf(Int.MIN_VALUE, -1, 0, 10, 65535, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.frame(type, byteArrayOf()) }
        }
    }

    @Test
    fun headerRejectsFragmentsTrailingBytesWrongMagicVersionTypeAndLength() {
        for (size in (0..11).toList() + listOf(13, 24)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.header(header().copyOf(size)) }
        }
        for (offset in 0..3) {
            val invalid = header().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { ChrootBrokerWire.header(invalid) }
        }
        for (version in listOf(0, 2, 65535)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.header(header(version = version)) }
        }
        for (type in listOf(0, 10, 32768, 65535)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.header(header(type = type)) }
        }
        for (length in listOf(Int.MIN_VALUE, -1, 257, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.header(header(length = length)) }
        }
    }

    @Test
    fun helloRequiresAll32NonceBytesAndEveryCapturedPeerFieldIncludingReserved() {
        val body = hello()
        assertEquals(56, body.size)
        checkHello(body)
        for (offset in body.indices) {
            val changed = body.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { checkHello(changed) }
        }
        assertEquals(Long.MAX_VALUE, ByteBuffer.wrap(body).getLong(40))
    }

    @Test
    fun helloRejectsEveryShortBodyTrailingBytesWrongNonceSizeAndInvalidBrokerPid() {
        for (size in (0..55).toList() + listOf(57, 112)) {
            assertThrows(IOException::class.java) { checkHello(hello().copyOf(size)) }
        }
        for (size in listOf(0, 31, 33)) {
            assertThrows(IOException::class.java) { checkHello(hello(), nonce.copyOf(size)) }
        }
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertThrows(IOException::class.java) { checkHello(hello(), expectedBrokerPid = invalid) }
        }
        assertThrows(IOException::class.java) { checkHello(hello(), expectedBrokerPid = brokerPid + 1) }
    }

    @Test
    fun runtimeStartIsExactly56BytesWithAllIdentityAndRuntimeFieldsZero() {
        for (id in listOf(1L, Long.MAX_VALUE)) {
            val body = ChrootBrokerWire.start(id, ChrootBrokerWire.RUNTIME)
            val expected = ByteBuffer.allocate(56).putLong(id).putInt(1).putInt(0).put(ByteArray(40)).array()
            assertArrayEquals(expected, body)
            assertEquals(56, body.size)
        }
    }

    @Test
    fun installAndRemoveEncodeOnlyFixedHexInstanceAndBoundedUnsignedIdentities() {
        val install = ChrootBrokerWire.start(Long.MAX_VALUE, ChrootBrokerWire.INSTALL, instanceId)
        assertArrayEquals(ByteBuffer.allocate(56).putLong(Long.MAX_VALUE).putInt(2).putInt(0)
            .put(instanceId.toByteArray(Charsets.US_ASCII)).putLong(0).putLong(0).array(), install)
        for ((device, inode) in listOf(0L to 1L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            val remove = ChrootBrokerWire.start(Long.MAX_VALUE, ChrootBrokerWire.REMOVE, instanceId, device, inode)
            assertArrayEquals(ByteBuffer.allocate(56).putLong(Long.MAX_VALUE).putInt(3).putInt(0)
                .put(instanceId.toByteArray(Charsets.US_ASCII)).putLong(device).putLong(inode).array(), remove)
        }
    }

    @Test
    fun recoveryRemovalUsesTheFixedOperationAndExactPositiveInstanceIdentity() {
        for ((device, inode) in listOf(1L to 1L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            val body = ChrootBrokerWire.start(Long.MAX_VALUE, ChrootBrokerWire.RECOVER_REMOVE, instanceId, device, inode)
            assertArrayEquals(ByteBuffer.allocate(56).putLong(Long.MAX_VALUE).putInt(4).putInt(0)
                .put(instanceId.toByteArray(Charsets.US_ASCII)).putLong(device).putLong(inode).array(), body)
        }
        for ((device, inode) in listOf(0L to 1L, 1L to 0L, 0L to 0L, -1L to 1L, 1L to -1L,
            Long.MIN_VALUE to 1L, 1L to Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ChrootBrokerWire.start(1, ChrootBrokerWire.RECOVER_REMOVE, instanceId, device, inode)
            }
        }
    }

    @Test
    fun startRejectsInvalidRequestIdsOperationsAndAnyRuntimeInstanceOrIdentity() {
        for (id in listOf(Long.MIN_VALUE, -1L, 0L)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(id, 1) }
        }
        for (operation in listOf(Int.MIN_VALUE, -1, 0, 5, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(1, operation, instanceId, 1, 1) }
        }
        for (candidate in listOf("", instanceId, "/bin/sh", "sh -c anything")) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(1, 1, candidate) }
        }
        for ((device, inode) in listOf(1L to 0L, 0L to 1L, -1L to 0L, 0L to -1L)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(1, 1, device = device, inode = inode) }
        }
    }

    @Test
    fun instanceOperationsRejectNonHexNamesCommandArgumentsAndInvalidDeviceOrInode() {
        for (operation in listOf(ChrootBrokerWire.INSTALL, ChrootBrokerWire.REMOVE, ChrootBrokerWire.RECOVER_REMOVE)) {
            for (candidate in listOf(null, "", instanceId.dropLast(1), instanceId + "0", instanceId.uppercase(),
                "g".repeat(24), "../$instanceId", "$instanceId --remove", "/bin/sh -c anything")) {
                assertThrows(IllegalArgumentException::class.java) {
                    val identity = if (operation == ChrootBrokerWire.INSTALL) 0L else 1L
                    ChrootBrokerWire.start(1, operation, candidate, device = identity, inode = identity)
                }
            }
        }
        for ((device, inode) in listOf(1L to 0L, 0L to 1L, -1L to 0L, 0L to -1L)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(1, 2, instanceId, device, inode) }
        }
        for ((device, inode) in listOf(-1L to 1L, Long.MIN_VALUE to 1L, 0L to 0L, 0L to -1L, 0L to Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ChrootBrokerWire.start(1, 3, instanceId, device, inode) }
        }
    }

    @Test
    fun startedRequiresExactly16BytesMatchingRequestPositiveChildAndZeroReserved() {
        for (child in listOf(2, Int.MAX_VALUE)) {
            assertEquals(child, ChrootBrokerWire.checkStarted(started(child = child), Long.MAX_VALUE))
        }
        for (size in (0..15).toList() + listOf(17, 32)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkStarted(started().copyOf(size), Long.MAX_VALUE) }
        }
        assertThrows(IOException::class.java) { ChrootBrokerWire.checkStarted(started(id = 1), Long.MAX_VALUE) }
        for (child in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkStarted(started(child = child), Long.MAX_VALUE) }
        }
        for (reserved in listOf(-1, 1, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkStarted(started(reserved = reserved), Long.MAX_VALUE) }
        }
    }

    @Test
    fun failedAcceptsOnlyCodesOneThroughFourAndErrnoOneThrough4095() {
        for (code in 1..4) {
            for (errno in listOf(1, 4095)) ChrootBrokerWire.checkFailed(failed(code = code, errno = errno), Long.MAX_VALUE)
        }
        for (code in listOf(Int.MIN_VALUE, -1, 0, 5, 6, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkFailed(failed(code = code), Long.MAX_VALUE) }
        }
        for (errno in listOf(Int.MIN_VALUE, -1, 0, 4096, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkFailed(failed(errno = errno), Long.MAX_VALUE) }
        }
    }

    @Test
    fun failedRejectsEveryShortBodyTrailingBytesAndDifferentRequestId() {
        for (size in (0..15).toList() + listOf(17, 32)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkFailed(failed().copyOf(size), Long.MAX_VALUE) }
        }
        for (id in listOf(Long.MIN_VALUE, -1L, 0L, 1L)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.checkFailed(failed(id = id), Long.MAX_VALUE) }
        }
    }

    @Test
    fun ajexVersionOneMapsEveryExitAndSignalWithoutConflatingSignalOneAndUnknown() {
        assertArrayEquals(byteArrayOf(65, 74, 69, 88, 0, 1, 0, 1, 0, 0, 0, 37, 0, 0, 0, 0), exit(value = 37))
        for (code in 0..255) assertEquals(code, ChrootBrokerWire.exitStatus(exit(value = code)))
        for (signal in 1..64) assertEquals(-signal, ChrootBrokerWire.exitStatus(exit(2, signal)))
        assertEquals(-1, ChrootBrokerWire.exitStatus(exit(2, 1)))
        assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(exit(3, 0)) }
    }

    @Test
    fun ajexIncompleteTrailingBadMagicVersionAndReservedNeverBecomeSuccess() {
        for (size in (0..15).toList() + listOf(17, 32)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(exit().copyOf(size)) }
        }
        for (offset in 0..3) {
            val invalid = exit().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(invalid) }
        }
        for (version in listOf(0, 2, 65535)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(exit(version = version)) }
        }
        for (reserved in listOf(Int.MIN_VALUE, -1, 1, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(exit(reserved = reserved)) }
        }
    }

    @Test
    fun ajexRejectsMalformedOutcomesExitCodesAndSignalsRatherThanReturningZero() {
        for ((outcome, value) in listOf(0 to 0, 3 to 0, 3 to 1, 4 to 0, 32768 to 0, 65535 to 0,
            1 to -1, 1 to 256, 1 to Int.MAX_VALUE, 1 to Int.MIN_VALUE, 2 to 0, 2 to -1, 2 to 65, 2 to Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { ChrootBrokerWire.exitStatus(exit(outcome, value)) }
        }
    }
}
