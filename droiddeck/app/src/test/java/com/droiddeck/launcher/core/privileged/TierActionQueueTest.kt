package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TierActionQueueTest {
    @Test
    fun queuedActionRunsBeforeBarrierReturns() {
        val queue = TierActionQueue()
        queue.start()
        val ran = AtomicInteger(0)
        queue.submit(guard = { true }, action = { ran.incrementAndGet() })
        assertTrue(queue.stopBarrier(4000))
        assertEquals(1, ran.get())
    }

    @Test
    fun actionAfterStopDoesNotRunAndGuardFalseSkipsAction() {
        val queue = TierActionQueue()
        queue.start()
        val ran = AtomicInteger(0)
        queue.submit(guard = { false }, action = { ran.incrementAndGet() })
        assertTrue(queue.stopBarrier(4000))
        assertEquals(0, ran.get())
        queue.submit(guard = { true }, action = { ran.incrementAndGet() })
        Thread.sleep(200)
        assertEquals(0, ran.get())
    }

    @Test
    fun queuedActionsRunInSubmissionOrder() {
        val queue = TierActionQueue()
        queue.start()
        val order = mutableListOf<Int>()
        val done = CountDownLatch(3)
        for (i in 1..3) {
            queue.submit(
                guard = { true },
                action = {
                    synchronized(order) { order.add(i) }
                    done.countDown()
                },
            )
        }
        assertTrue(done.await(4, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), order)
        assertTrue(queue.stopBarrier(4000))
    }
}
