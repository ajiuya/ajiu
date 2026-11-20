package com.aicode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConnectionSyncRoundTest {
    @Test fun staleDocsRoundDoesNotEndCollectorOrRunLaterWritesAndNextProfileStillSyncs() = runBlocking {
        val profiles = Channel<Int>(Channel.UNLIMITED)
        val completed = Channel<Int>(Channel.UNLIMITED)
        val calls = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.receiveAsFlow().collect { profile ->
                runRemoteConnectionSyncRound {
                    calls += "connect:$profile"
                    calls += "docs:$profile"
                    if (profile == 1) throw CancellationException("stale captured identity")
                    calls += "definitions:$profile"
                }
                completed.send(profile)
            }
        }
        try {
            profiles.send(1)
            assertEquals(1, withTimeout(10000) { completed.receive() })
            assertTrue(collector.isActive)
            profiles.send(2)
            assertEquals(2, withTimeout(10000) { completed.receive() })
            assertEquals(listOf("connect:1", "docs:1", "connect:2", "docs:2", "definitions:2"), calls)
            assertTrue(collector.isActive)
        } finally {
            collector.cancelAndJoin()
            profiles.close()
            completed.close()
        }
    }

    @Test fun realCollectorCancellationPropagatesAndDoesNotStartQueuedProfileOrLaterWrites() = runBlocking {
        val profiles = Channel<Int>(Channel.UNLIMITED)
        val inDocs = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.receiveAsFlow().collect { profile ->
                runRemoteConnectionSyncRound {
                    calls += "connect:$profile"
                    calls += "docs:$profile"
                    inDocs.complete(Unit)
                    awaitCancellation()
                }
                calls += "definitions:$profile"
            }
        }
        try {
            profiles.send(1)
            withTimeout(10000) { inDocs.await() }
            profiles.send(2)
            withTimeout(10000) { collector.cancelAndJoin() }
            assertTrue(collector.isCancelled)
            assertEquals(listOf("connect:1", "docs:1"), calls)
        } finally {
            collector.cancelAndJoin()
            profiles.close()
        }
    }
}
