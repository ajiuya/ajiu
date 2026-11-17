package com.aicode.feature.workspace.domain.remote

import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteMount
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineReconcileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "workspace").apply { mkdirs() }
    private val indexDir get() = File(temporary.root, "index")

    private class Client : RemoteSyncClient {
        val uploaded = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val directories = mutableListOf<String>()
        val order = mutableListOf<String>()
        var connected = true
        var upload: suspend (String) -> Unit = {}
        var reconnects = 0
        override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) = Unit
        override suspend fun disconnect() { order += "disconnect" }
        override suspend fun listFiles(remotePath: String) = emptyList<RemoteFileInfo>()
        override suspend fun downloadFile(remotePath: String, localPath: String) = Unit
        override suspend fun uploadFile(localPath: String, remotePath: String) { upload(localPath); uploaded += remotePath }
        override suspend fun createDirectory(remotePath: String) { directories += remotePath }
        override suspend fun delete(remotePath: String) { deleted += remotePath }
        override suspend fun isConnected() = connected
        override suspend fun ping() = connected
        override suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth) { reconnects++; connected = true }
    }

    private fun TestScope.engine(client: Client, store: SyncIndexStore = SyncIndexStore(indexDir)): SyncEngine = SyncEngine(
        RemoteMount("mount", "connection", "/remote", root.path),
        RemoteConnection("connection", "test", RemoteProtocol.SFTP, "test.invalid", 22, "user"),
        client, RemoteAuth.Password("test-only"), "", false, 20, store, StandardTestDispatcher(testScheduler)
    )

    @Test fun queueDeduplicatesAtEnqueueAndOverflowRetainsAnExplicitReconcileRequest() {
        val queue = SyncPendingQueue(2)
        repeat(10_000) { queue.offer("one") }
        queue.offer("two"); queue.offer("three")
        assertEquals(SyncPendingQueue.Batch(listOf("one", "two"), true), queue.take(100))
        assertEquals(SyncPendingQueue.Batch(emptyList(), false), queue.take(100))
        assertTrue(queue.wakeup.tryReceive().isSuccess)
        assertTrue(queue.wakeup.tryReceive().isFailure)
        queue.requestReconcile()
        assertTrue(queue.take(1).reconcile)
    }

    @Test fun partialDrainRewakesAndAnEventDuringProcessingIsNotLost() {
        val queue = SyncPendingQueue(3)
        queue.offer("one"); queue.offer("two"); queue.offer("three")
        queue.wakeup.tryReceive()
        assertEquals(listOf("one"), queue.take(1).paths)
        assertTrue(queue.wakeup.tryReceive().isSuccess)
        queue.offer("one")
        assertEquals(listOf("two", "three", "one"), queue.take(3).paths)
    }

    @Test fun firstConnectionAndReconcileDoNotUploadUntouchedPreexistingFiles() = runTest {
        File(root, "existing").writeText("original")
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent()
            assertTrue(client.uploaded.isEmpty())
            engine.requestReconcile(); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertTrue(client.uploaded.isEmpty())
            engine.uploadWorkspace()
            assertEquals(listOf("/remote/existing"), client.uploaded)
        } finally { engine.shutdown() }
    }

    @Test fun sameContentSaveRetriesAForceUploadAfterDisconnectWithoutTreatingTheBaselineAsSynced() = runTest {
        val file = File(root, "existing").apply { writeText("same content") }
        val client = Client()
        var attempts = 0
        client.upload = { if (++attempts == 1) throw IOException("first upload disconnected") }
        val store = SyncIndexStore(indexDir)
        val engine = engine(client, store)
        try {
            runCurrent()
            assertEquals(0, attempts)
            assertTrue(client.uploaded.isEmpty())
            file.writeText("same content")
            engine.enqueueLocalChange(file.path)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(1, client.reconnects)
            assertTrue(client.uploaded.isEmpty())
            assertTrue(store.load("mount").isEmpty())
            advanceTimeBy(999); runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(101); runCurrent()
            assertEquals(2, attempts)
            assertEquals(listOf("/remote/existing"), client.uploaded)
            assertEquals(file.length(), store.load("mount").getValue("existing").size)
        } finally { engine.shutdown() }
    }

    @Test fun explicitlyRejectedForceUploadIsNotRequeuedOrReconnected() = runTest {
        val file = File(root, "rejected").apply { writeText("unchanged") }
        val client = Client()
        var attempts = 0
        client.upload = { attempts++; throw RemoteFileRejectedException("rejected by server") }
        val engine = engine(client)
        try {
            runCurrent()
            engine.enqueueLocalChange(file.path)
            runCurrent(); advanceTimeBy(5000); runCurrent()
            assertEquals(1, attempts)
            assertEquals(0, client.reconnects)
            assertTrue(client.uploaded.isEmpty())
        } finally { engine.shutdown() }
    }

    @Test fun reconcileFailureBeforeAForceBatchPreservesTheUnexecutedSameContentSave() = runTest {
        val saved = File(root, "saved").apply { writeText("unchanged") }
        val changed = File(root, "changed").apply { writeText("old") }
        val client = Client()
        var changedAttempts = 0
        client.upload = { path ->
            if (path == changed.path && ++changedAttempts == 1) throw IOException("reconcile disconnected")
        }
        val engine = engine(client)
        try {
            runCurrent()
            assertTrue(client.uploaded.isEmpty())
            changed.writeText("new")
            engine.enqueueLocalChange(saved.path)
            engine.requestReconcile()
            runCurrent()
            assertEquals(1, changedAttempts)
            assertTrue(client.uploaded.isEmpty())
            advanceTimeBy(999); runCurrent()
            assertEquals(1, changedAttempts)
            advanceTimeBy(1001); runCurrent()
            assertEquals(2, changedAttempts)
            assertEquals(setOf("/remote/changed", "/remote/saved"), client.uploaded.toSet())
            assertEquals(1, client.uploaded.count { it == "/remote/saved" })
        } finally { engine.shutdown() }
    }

    @Test fun restartReconcileOnlyUploadsRealOfflineChangesNotUntouchedBaselineFiles() = runTest {
        File(root, "unchanged").writeText("keep")
        val modified = File(root, "modified").apply { writeText("old") }
        val first = engine(Client())
        runCurrent(); first.shutdown()
        val stamp = modified.lastModified()
        modified.writeText("new"); modified.setLastModified(stamp)
        File(root, "added").writeText("new")
        val client = Client()
        val next = engine(client, SyncIndexStore(indexDir))
        try {
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(setOf("/remote/modified", "/remote/added"), client.uploaded.toSet())
            assertFalse("/remote/unchanged" in client.uploaded)
        } finally { next.shutdown() }
    }

    @Test fun partialJournalTailDoesNotTurnOfflineChangesIntoAnUntouchedInitialBaseline() = runTest {
        val changed = File(root, "changed").apply { writeText("old") }
        File(root, "untouched").writeText("keep")
        val initial = engine(Client())
        runCurrent(); initial.shutdown()
        java.io.FileOutputStream(File(indexDir, "mount.journal"), true).use { it.write(byteArrayOf(0, 0, 0, 50, 1)) }
        changed.writeText("new")
        val client = Client()
        val resumed = engine(client, SyncIndexStore(indexDir))
        try {
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("/remote/changed"), client.uploaded)
        } finally { resumed.shutdown() }
    }

    @Test fun unavailableRootDoesNotReconcileAsADeletionOfAllRemoteFiles() = runTest {
        val localRoot = root
        val file = File(localRoot, "synced").apply { writeText("data") }
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent(); engine.uploadWorkspace()
            val moved = File(temporary.root, "detached")
            assertTrue(localRoot.renameTo(moved))
            engine.requestReconcile()
            runCurrent(); advanceTimeBy(3000); runCurrent()
            assertTrue(client.deleted.isEmpty())
            assertTrue(File(moved, file.name).exists())
        } finally { engine.shutdown() }
    }

    @Test fun duplicateEnqueuesUploadOnceAndSameStampChangesAreNotQuickCheckedAway() = runTest {
        val file = File(root, "changed").apply { writeText("old") }
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent()
            engine.uploadWorkspace()
            client.uploaded.clear()
            val stamp = file.lastModified()
            file.writeText("new"); file.setLastModified(stamp)
            repeat(5000) { engine.enqueueLocalChange(file.path) }
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("/remote/changed"), client.uploaded)
        } finally { engine.shutdown() }
    }

    @Test fun upstreamTruncationAndQueueOverflowBothRecoverAPathWithoutItsIndividualEvent() = runTest {
        val changed = File(root, "omitted").apply { writeText("old") }
        val untouched = File(root, "untouched").apply { writeText("keep") }
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent()
            changed.writeText("new")
            repeat(4097) { engine.enqueueLocalChange(File(root, "absent-$it").path) }
            runCurrent(); advanceTimeBy(500_000); runCurrent()
            assertEquals(listOf("/remote/omitted"), client.uploaded)
            assertFalse("/remote/untouched" in client.uploaded)
            assertTrue(untouched.exists())
            changed.writeText("newer")
            engine.requestReconcile()
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(2, client.uploaded.count { it == "/remote/omitted" })
        } finally { engine.shutdown() }
    }

    @Test fun reconnectExplicitlyReconcilesChangesAndRestoresConnectionState() = runTest {
        val file = File(root, "offline").apply { writeText("old") }
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent()
            file.writeText("new")
            client.connected = false
            advanceTimeBy(20_001); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertEquals(1, client.reconnects)
            assertEquals(listOf("/remote/offline"), client.uploaded)
        } finally { engine.shutdown() }
    }

    @Test fun subtreeDeletionReconcilesIndexedDescendantsDeepestFirstAndPersistsAnEmptyIndex() = runTest {
        val directory = File(root, "tree").apply { mkdir() }
        val child = File(directory, "leaf").apply { writeText("data") }
        val store = SyncIndexStore(indexDir)
        val client = Client()
        val engine = engine(client, store)
        try {
            runCurrent(); engine.uploadWorkspace()
            assertTrue(child.delete()); assertTrue(directory.delete())
            engine.enqueueLocalChange(directory.path)
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("/remote/tree/leaf", "/remote/tree"), client.deleted)
            assertTrue(SyncIndexStore(indexDir).load("mount").isEmpty())
        } finally { engine.shutdown() }
    }

    @Test fun indexFailureDoesNotPublishAFingerprintAndLaterReconcileRetriesIt() = runTest {
        var fail = false
        val io = object : SyncIndexIo() {
            override fun append(journal: File, payload: ByteArray) {
                if (fail) throw IOException("disk full")
                super.append(journal, payload)
            }
        }
        val file = File(root, "changed").apply { writeText("old") }
        val store = SyncIndexStore(indexDir, io)
        val client = Client()
        val engine = engine(client, store)
        try {
            runCurrent(); engine.uploadWorkspace()
            val durable = store.load("mount")
            fail = true
            file.writeText("newer")
            engine.enqueueLocalChange(file.path)
            runCurrent(); advanceTimeBy(100); runCurrent()
            assertEquals(durable, store.load("mount"))
            assertEquals(durable, SyncIndexStore(indexDir).load("mount"))
            fail = false
            advanceTimeBy(3000); runCurrent()
            assertEquals(file.length(), store.load("mount").getValue("changed").size)
            assertTrue(client.uploaded.count { it == "/remote/changed" } >= 3)
        } finally { engine.shutdown() }
    }

    @Test fun connectedClearDoesNotUploadByItselfAndTheNextManualUploadCannotSkipOldFingerprints() = runTest {
        File(root, "synced").writeText("data")
        val client = Client()
        val engine = engine(client)
        try {
            runCurrent(); engine.uploadWorkspace()
            client.uploaded.clear()
            engine.clearSyncIndex()
            runCurrent()
            assertTrue(client.uploaded.isEmpty())
            assertTrue(SyncIndexStore(indexDir).load("mount").isEmpty())
            engine.requestReconcile()
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertTrue(client.uploaded.isEmpty())
            engine.uploadWorkspace()
            assertEquals(listOf("/remote/synced"), client.uploaded)
        } finally { engine.shutdown() }
    }

    @Test fun failedConnectedClearDoesNotInvalidateTheDurableIndexOrItsActiveMemory() = runTest {
        var fail = false
        val io = object : SyncIndexIo() {
            override fun atomicWrite(target: File, bytes: ByteArray) {
                if (fail && target.extension == "snapshot") throw IOException("cannot publish clear")
                super.atomicWrite(target, bytes)
            }
        }
        File(root, "synced").writeText("data")
        val store = SyncIndexStore(indexDir, io)
        val client = Client()
        val engine = engine(client, store)
        try {
            runCurrent(); engine.uploadWorkspace()
            client.uploaded.clear()
            val durable = store.load("mount")
            fail = true
            assertTrue(runCatching { engine.clearSyncIndex() }.isFailure)
            assertEquals(durable, store.load("mount"))
            assertEquals(durable, SyncIndexStore(indexDir).load("mount"))
            fail = false
            engine.uploadWorkspace()
            assertTrue(client.uploaded.isEmpty())
        } finally { engine.shutdown() }
    }

    @Test fun shutdownCheckpointsASuccessfulTransferBeforeDisconnectAndRestartReconcilesItsOfflineDeletion() = runTest {
        val client = Client()
        val store = SyncIndexStore(indexDir)
        val first = engine(client, store)
        runCurrent()
        val uploaded = File(root, "new-file").apply { writeText("data") }
        first.enqueueLocalChange(uploaded.path)
        runCurrent()
        assertEquals(listOf("/remote/new-file"), client.uploaded)
        assertTrue(store.load("mount").isEmpty())
        first.shutdown()
        assertEquals(uploaded.length(), store.load("mount").getValue("new-file").size)
        assertEquals(listOf("disconnect"), client.order)
        assertTrue(uploaded.delete())
        val restartedClient = Client()
        val restarted = engine(restartedClient, SyncIndexStore(indexDir))
        try {
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("/remote/new-file"), restartedClient.deleted)
            assertTrue(SyncIndexStore(indexDir).load("mount").isEmpty())
        } finally { restarted.shutdown() }
    }

    @Test fun shutdownCheckpointFailureIsReportedButStillDisconnectsAfterJoiningOperations() = runTest {
        var fail = false
        val io = object : SyncIndexIo() {
            override fun append(journal: File, payload: ByteArray) {
                if (fail) throw IOException("shutdown disk full")
                super.append(journal, payload)
            }
        }
        val client = Client()
        val store = SyncIndexStore(indexDir, io)
        val engine = engine(client, store)
        runCurrent()
        val uploaded = File(root, "new-file").apply { writeText("data") }
        engine.enqueueLocalChange(uploaded.path)
        runCurrent()
        assertEquals(listOf("/remote/new-file"), client.uploaded)
        fail = true
        val result = runCatching { engine.shutdown() }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(store.load("mount").isEmpty())
        assertTrue(SyncIndexStore(indexDir).load("mount").isEmpty())
        assertEquals(listOf("disconnect"), client.order)
    }

    @Test fun cancellationJoinsAllOperationsBeforeDisconnecting() = runTest {
        val file = File(root, "blocked").apply { writeText("data") }
        val client = Client()
        val entered = CompletableDeferred<Unit>()
        client.upload = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { client.order += "operation-finished" }
        }
        val engine = engine(client)
        runCurrent()
        engine.enqueueLocalChange(file.path)
        runCurrent(); entered.await()
        val shutdown = async { engine.shutdown() }
        runCurrent(); shutdown.await()
        assertEquals(listOf("operation-finished", "disconnect"), client.order)
    }
}
