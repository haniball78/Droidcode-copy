package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.privileged.TierActionQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCStressTest {
    @Test
    fun unknownThermalDoesNotBlockResumeReapply() {
        val check = SuspendPolicy.checkResume(
            kind = SuspendKind.SAFETY,
            wantsOptimizations = true,
            bridgeOk = true,
            batteryLevel = 80,
            charging = false,
            thermalStatus = SafetyPolicy.THERMAL_UNKNOWN,
            explicitResume = true,
        )
        assertTrue(check.allowed)
        assertTrue(
            SuspendPolicy.shouldReapplyOnReturn(
                leaveRestored = true,
                tweaksEndedForSafety = false,
                bridgeOk = true,
                batteryLevel = 80,
                charging = false,
                thermalStatus = SafetyPolicy.THERMAL_UNKNOWN,
            )
        )
        val severe = SuspendPolicy.checkResume(
            kind = SuspendKind.SAFETY,
            wantsOptimizations = true,
            bridgeOk = true,
            batteryLevel = 80,
            charging = false,
            thermalStatus = SafetyPolicy.THERMAL_SEVERE,
            explicitResume = true,
        )
        assertFalse(severe.allowed)
    }

    @Test
    fun hundredInterleavedGovernorActionsRunBeforeBarrierAndNoneAfter() {
        val queue = TierActionQueue()
        queue.start()
        val ran = AtomicInteger(0)
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..100).map { n ->
            pool.submit {
                start.await()
                queue.submit({ true }) {
                    order.add(n)
                    ran.incrementAndGet()
                }
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertTrue(queue.stopBarrier(5000L))
        assertEquals(100, ran.get())
        assertEquals((1..100).toSet(), order.toSet())
        queue.submit({ true }) { ran.incrementAndGet() }
        Thread.sleep(100)
        assertEquals(100, ran.get())
    }

    @Test
    fun concurrentExitGateOperationsNeverReachIllegalState() {
        val gate = ExitGate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val futures = (1..50).map {
            pool.submit {
                start.await()
                if (gate.request(ExitReason.USER, 9)) wins.incrementAndGet()
                gate.beginTeardown(9)
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, wins.get())
        assertEquals(ExitState.TEARDOWN, gate.state)
        assertEquals(9, gate.generation)
        assertFalse(gate.beginRestore(8))
        assertTrue(gate.beginRestore(9))
        assertEquals(ExitState.RESTORING, gate.state)
    }
}
