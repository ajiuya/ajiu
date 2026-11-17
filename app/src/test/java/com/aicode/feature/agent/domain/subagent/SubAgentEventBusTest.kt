package com.aicode.feature.agent.domain.subagent

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃子代理集合的维护语义：并发槽位靠它计数，只要有一条路径忘了交回槽位，
 * 用户就会在没有任何子代理运行时被告知「已达上限」，直到进程重启。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentEventBusTest {

    private suspend fun spawn(bus: SubAgentEventBus, id: String) = bus.emit(
        SubAgentEvent(subSessionId = id, parentSessionId = "parent", type = SubAgentEventType.SPAWNED)
    )

    @Test
    fun spawn_marksActive() = runTest {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertFalse(bus.isFull)
    }

    @Test
    fun terminalEvents_freeSlot() = runTest {
        listOf(
            SubAgentEventType.COMPLETED,
            SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED,
            SubAgentEventType.DELETED,
            SubAgentEventType.INTERRUPTED
        ).forEach { type ->
            val bus = SubAgentEventBus()
            spawn(bus, "sub-1")
            bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = type))

            assertEquals("$type 应交回槽位", emptySet<String>(), bus.activeSubSessionIds.value)
        }
    }

    @Test
    fun isFull_atMaxRunning() = runTest {
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { spawn(bus, "sub-$it") }

        assertTrue(bus.isFull)

        bus.emit(SubAgentEvent(subSessionId = "sub-0", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertFalse("腾出一个后应能再派发", bus.isFull)
    }

    /** 用户手动停止走 release：必须真的交回槽位，否则上限被永久占用。 */
    @Test
    fun release_freesSlotAndReportsWhetherItWasActive() = runTest {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 幂等且如实回报：TaskTool 的 stop 已经 emit 过 STOPPED 时，ViewModel 随后的 release
     * 必须返回 false，否则会给父代理重复投递一条「已被终止」通知。
     */
    @Test
    fun release_isIdempotentForUnknownId() = runTest {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        bus.release("sub-1")

        assertFalse(bus.release("sub-1"))
        assertFalse(bus.release("never-existed"))
    }

    @Test
    fun release_doesNotTouchOtherSubAgents() = runTest {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        spawn(bus, "sub-2")

        bus.release("sub-1")

        assertEquals(setOf("sub-2"), bus.activeSubSessionIds.value)
    }

    // ── 预留槽位：task(create) 的「检查+占位」必须原子，否则同一批并行工具会超额创建 ──

    @Test
    fun reserve_countsAgainstLimitUntilSpawned() = runTest {
        val bus = SubAgentEventBus()
        assertTrue(bus.tryReserveSlots(3))

        // 预留未落地时 active 集合还是空，但计数必须把预留算进去，否则并行的创建会双双通过检查
        assertEquals(0, bus.activeSubSessionIds.value.size)
        assertEquals(3, bus.activeCount)
        assertFalse("3 预留 + 3 再要 > 5，应拒绝", bus.tryReserveSlots(3))
        assertTrue("3 预留 + 2 再要 = 5，应允许", bus.tryReserveSlots(2))
    }

    @Test
    fun spawned_consumesOneReservation() = runTest {
        val bus = SubAgentEventBus()
        bus.tryReserveSlots(2)

        spawn(bus, "sub-1")

        // 1 个已落地 + 1 个尚未落地的预留，都算在占用里
        assertEquals(2, bus.activeCount)
        spawn(bus, "sub-2")
        assertEquals(2, bus.activeCount)
        // 无预留时 SPAWNED 不应把计数减到负数
        spawn(bus, "sub-3")
        assertEquals(3, bus.activeCount)
    }

    @Test
    fun releaseSlots_returnsUnusedReservations() = runTest {
        val bus = SubAgentEventBus()
        bus.tryReserveSlots(3)
        spawn(bus, "sub-1")

        bus.releaseSlots(2)

        assertEquals(1, bus.activeCount)
        // 归还多余的不应减到负
        bus.releaseSlots(10)
        assertEquals(1, bus.activeCount)
    }

    @Test
    fun reserve_beyondMaxIsRejected() = runTest {
        val bus = SubAgentEventBus()
        assertFalse(bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING + 1))
        assertTrue(bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING))
    }

    /** 并发 reserve 总占位不会超过上限：串行循环里每次读到的都是最新计数。 */
    @Test
    fun reserve_concurrentRequestsDoNotOversubscribe() = runTest {
        val bus = SubAgentEventBus()
        val acquired = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map {
            Thread {
                // 每个线程反复抢 1 个名额再立即归还，与其它线程交错执行
                repeat(50) {
                    if (bus.tryReserveSlots(1)) bus.releaseSlots(1)
                }
                // 最后真实占住：全部线程占完后总数不得超过上限
                if (bus.tryReserveSlots(1)) acquired.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发下恰好只有 5 个线程能占住名额", SubAgentEventBus.MAX_RUNNING, acquired.get())
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
    }

    @Test
    fun started_isAuditOnlyAndMarkStartedDoesNotConsumeReservations() = runTest {
        val bus = SubAgentEventBus()
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { received += it }
        }
        assertTrue(bus.tryReserveSlots(1))
        bus.emit(SubAgentEvent("child", "parent", SubAgentEventType.STARTED))
        assertTrue(received.isEmpty())
        assertTrue(bus.activeSubSessionIds.value.isEmpty())
        assertEquals(1, bus.activeCount)

        bus.markStarted("child")
        bus.markStarted("child")
        assertEquals(setOf("child"), bus.activeSubSessionIds.value)
        assertEquals(2, bus.activeCount)
        assertTrue(received.isEmpty())
        bus.releaseSlots(1)
        assertTrue(bus.release("child"))
        assertEquals(0, bus.activeCount)
    }

    @Test
    fun instructionCancelled_neverDispatchesOrChangesActiveAndReservedSlots() = runTest {
        val bus = SubAgentEventBus()
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { received += it } }
        assertTrue(bus.tryReserveSlots(2))
        assertTrue(bus.tryMarkStarted("child"))
        val cancelled = SubAgentEvent("child", "parent", SubAgentEventType.INSTRUCTION_CANCELLED,
            instructionId = "accepted-user-instruction")
        bus.emit(cancelled)
        bus.recordForDisplay(cancelled.copy())
        assertTrue(received.isEmpty())
        assertEquals(setOf("child"), bus.activeSubSessionIds.value)
        assertEquals(3, bus.activeCount)
        bus.releaseSlots(2)
        assertEquals(1, bus.activeCount)
    }

    @Test
    fun tryMarkStarted_rejectsAtCapacityAndSucceedsAfterReleaseWithoutDispatch() = runTest {
        val bus = SubAgentEventBus()
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { received += it } }
        repeat(SubAgentEventBus.MAX_RUNNING) { assertTrue(bus.tryMarkStarted("child-$it")) }
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        assertFalse(bus.tryMarkStarted("waiting"))
        assertFalse("waiting" in bus.activeSubSessionIds.value)
        assertTrue(bus.release("child-0"))
        assertTrue(bus.tryMarkStarted("waiting"))
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        assertTrue(received.isEmpty())
    }

    @Test
    fun tryMarkStarted_sameActiveIdIsIdempotentEvenAtCapacity() = runTest {
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { assertTrue(bus.tryMarkStarted("child-$it")) }
        repeat(3) { assertTrue(bus.tryMarkStarted("child-0")) }
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeSubSessionIds.value.size)
    }

    @Test
    fun tryMarkStarted_countsReservationsWithoutConsumingThem() = runTest {
        val bus = SubAgentEventBus()
        assertTrue(bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING))
        assertFalse(bus.tryMarkStarted("child"))
        assertTrue(bus.activeSubSessionIds.value.isEmpty())
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        bus.releaseSlots(1)
        assertTrue(bus.tryMarkStarted("child"))
        assertTrue(bus.tryMarkStarted("child"))
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        assertFalse(bus.tryMarkStarted("other-child"))
        bus.releaseSlots(SubAgentEventBus.MAX_RUNNING - 1)
        assertEquals(setOf("child"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertTrue(bus.tryMarkStarted("other-child"))
        assertEquals(2, bus.activeCount)
    }

    @Test
    fun tryMarkStarted_concurrentRequestsCannotOverrunReservedCapacity() = runTest {
        val bus = SubAgentEventBus()
        assertTrue(bus.tryReserveSlots(1))
        val acquired = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(20) { index ->
            Thread {
                if (bus.tryMarkStarted("child-$index")) acquired.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(SubAgentEventBus.MAX_RUNNING - 1, acquired.get())
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        bus.releaseSlots(1)
        assertEquals(SubAgentEventBus.MAX_RUNNING - 1, bus.activeCount)
    }

    @Test
    fun messagesAndDisplayOnlyEvents_doNotChangeSlots() = runTest {
        val bus = SubAgentEventBus()
        spawn(bus, "child")
        for (type in listOf(SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB)) {
            bus.emit(SubAgentEvent("child", "parent", type, "message"))
            assertEquals(setOf("child"), bus.activeSubSessionIds.value)
        }
        bus.recordForDisplay(SubAgentEvent("child", "parent", SubAgentEventType.STOPPED))
        assertEquals(1, bus.activeCount)
    }

    @Test
    fun eventDefaults_areUniqueAndStrictlyOrderedWhileExplicitMetadataSurvivesCopy() = runTest {
        val generated = List(1000) { SubAgentEvent("child", "parent", SubAgentEventType.MESSAGE_FROM_SUB) }
        assertEquals(generated.size, generated.map { it.eventId }.toSet().size)
        generated.forEach { assertEquals(it.eventId, UUID.fromString(it.eventId).toString()) }
        assertTrue(generated.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })

        val explicit = generated.first().copy(timestamp = 42, origin = "USER", instructionId = "accepted")
        assertEquals(42L, explicit.copy(detail = "new body").timestamp)
        assertEquals(explicit.eventId, explicit.copy().eventId)
        assertEquals("USER", explicit.origin)
        assertEquals("accepted", explicit.instructionId)
    }

    @Test
    fun duplicateSpawnDoesNotConsumeAnotherReservedSlot() = runTest {
        val bus = SubAgentEventBus()
        assertTrue(bus.tryReserveSlots(2))
        val event = SubAgentEvent("child", "parent", SubAgentEventType.SPAWNED)
        bus.emit(event)
        bus.emit(event)
        assertEquals(setOf("child"), bus.activeSubSessionIds.value)
        assertEquals(2, bus.activeCount)
        spawn(bus, "another")
        assertEquals(setOf("child", "another"), bus.activeSubSessionIds.value)
        assertEquals(2, bus.activeCount)
    }

    @Test
    fun explicitTestBusAwaitsFinishBeforeDispatchAndDisplayEntryAlsoFinishes() = runTest {
        val bus = SubAgentEventBus()
        val finished = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { delivered = true } }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.emit(SubAgentEvent("child", "parent", SubAgentEventType.COMPLETED)) { finished.await() }
        }
        assertFalse(delivered)
        assertFalse(job.isCompleted)
        finished.complete(true)
        job.join()
        assertTrue(delivered)
        var displayFinished = false
        bus.recordForDisplay(SubAgentEvent("child", "parent", SubAgentEventType.STOPPED)) {
            displayFinished = true
            true
        }
        assertTrue(displayFinished)
    }

    @Test
    fun eventDefaults_remainUniqueAcrossThreads() = runTest {
        val events = ConcurrentLinkedQueue<SubAgentEvent>()
        val threads = List(8) {
            Thread {
                repeat(100) { events += SubAgentEvent("child", "parent", SubAgentEventType.MESSAGE_FROM_SUB) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(800, events.map { it.eventId }.toSet().size)
        assertEquals(800, events.map { it.timestamp }.toSet().size)
    }
}
