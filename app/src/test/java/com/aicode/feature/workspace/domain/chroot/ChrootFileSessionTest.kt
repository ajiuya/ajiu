package com.aicode.feature.workspace.domain.chroot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CancellationException

class ChrootFileSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun writableHintsUseTheMostSpecificFrozenMountAndNeverGrantRootCapabilities() {
        val session = ChrootFileSession(
            "fixture", mockk(relaxed = true), temporary.root, {},
            writablePaths = listOf("/root", "/root/workspace/readonly/nested"),
            readOnlyPaths = listOf("/root/workspace/readonly", "/root/workspace/same"),
            closeOwner = {}
        )
        session.use {
            assertTrue(it.isWritable("/root/workspace/file"))
            assertFalse(it.isWritable("/root/workspace/readonly/file"))
            assertTrue(it.isWritable("/root/workspace/readonly/nested/file"))
            assertFalse(it.isWritable("/etc/hosts"))
            assertFalse(it.isWritable("/rooted/file"))
        }
        val tied = ChrootFileSession(
            "fixture", mockk(relaxed = true), temporary.root, {},
            listOf("/root/workspace"), listOf("/root/workspace"), {}
        )
        tied.use { assertFalse(it.isWritable("/root/workspace/file")) }
    }

    @Test
    fun defaultReadOnlyConfigAndDnsMountsOverrideWritableHome() {
        ChrootFileSession("fixture", mockk(relaxed = true), temporary.root, {}, listOf("/"), closeOwner = {}).use {
            assertFalse(it.isWritable("/etc/resolv.conf"))
            assertFalse(it.isWritable("/root/.aicode/agents/main.md"))
            assertFalse(it.isWritable("/root/.aicode/skills/tool/SKILL.md"))
            assertTrue(it.isWritable("/root/.aicode/memory/project.md"))
            assertTrue(it.isWritable("/root/workspace/file"))
        }
    }

    @Test
    fun mountListsAreDefensivelyCopiedAndImmutable() {
        val writable = mutableListOf("/root//workspace/")
        val readOnly = mutableListOf("/root/workspace/readonly")
        val session = ChrootFileSession("fixture", mockk(relaxed = true), temporary.root, {}, writable, readOnly, {})
        session.use {
            writable.clear()
            readOnly.clear()
            assertEquals(listOf("/root/workspace"), it.writablePaths)
            assertFalse(it.isWritable("/root/workspace/readonly/file"))
            assertThrows(UnsupportedOperationException::class.java) { (it.writablePaths as MutableList<*>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (it.readOnlyPaths as MutableList<*>).clear() }
        }
    }

    @Test
    fun closeAlwaysReleasesOwnerAfterClientAndIsIdempotentEvenIfCleanupFails() {
        val events = mutableListOf<String>()
        val client = mockk<ChrootFileWorkerClient>()
        val clientFailure = IOException("client cleanup")
        val ownerFailure = IOException("owner cleanup")
        every { client.close() } answers { events += "client"; throw clientFailure }
        val session = ChrootFileSession("fixture", client, temporary.root, {}, emptyList(), closeOwner = {
            events += "owner"
            throw ownerFailure
        })

        val failure = assertThrows(IOException::class.java) { session.close() }
        assertEquals(clientFailure, failure)
        assertEquals(listOf(ownerFailure), failure.suppressed.toList())
        session.close()
        assertEquals(listOf("client", "owner"), events)
        verify(exactly = 1) { client.close() }
        assertThrows(CancellationException::class.java) { session.checkCurrent() }
    }

    @Test
    fun sourceChecksRemainBoundToTheCapturedOwnerAndUnsafeMountDescriptionsAreRejected() {
        var current = true
        val client = mockk<ChrootFileWorkerClient>(relaxed = true)
        val session = ChrootFileSession("fixture", client, temporary.root,
            { if (!current) throw CancellationException("source changed") }, emptyList(), closeOwner = {})
        session.checkCurrent()
        current = false
        assertThrows(CancellationException::class.java) { session.checkCurrent() }
        session.close()
        for (path in listOf("relative", "/root/../other", "/root/./workspace", "/root/\u0000")) {
            assertThrows(IllegalArgumentException::class.java) {
                ChrootFileSession("fixture", client, temporary.root, {}, listOf(path), closeOwner = {})
            }
        }
    }
}
