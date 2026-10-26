package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ExitGateConcurrencyTest {
    @Test
    fun concurrentRequestsHaveExactlyOneWinner() {
        val gate = ExitGate()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val futures = (1..64).map {
            pool.submit {
                start.await()
                if (gate.request(ExitReason.USER)) wins.incrementAndGet()
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, wins.get())
        assertEquals(ExitState.REQUESTED, gate.state)
        assertEquals(ExitReason.USER, gate.reason)
    }

    @Test
    fun staleGenerationCannotMoveGate() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER, 7))
        assertEquals(7, gate.generation)
        gate.beginTeardown(9)
        assertEquals(ExitState.REQUESTED, gate.state)
        gate.beginTeardown(7)
        assertEquals(ExitState.TEARDOWN, gate.state)
        assertFalse(gate.beginRestore(9))
        assertEquals(ExitState.TEARDOWN, gate.state)
        assertTrue(gate.beginRestore(7))
        assertEquals(ExitState.RESTORING, gate.state)
    }

    @Test
    fun illegalTransitionsAreNoOpsAndResetClearsGeneration() {
        val gate = ExitGate()
        assertFalse(gate.beginRestore())
        assertEquals(ExitState.IDLE, gate.state)
        gate.restoreSucceeded()
        assertEquals(ExitState.IDLE, gate.state)
        gate.restoreFailed()
        assertEquals(ExitState.IDLE, gate.state)
        assertFalse(gate.retryRestore())
        assertTrue(gate.request(ExitReason.SAFETY, 4))
        gate.beginTeardown()
        gate.beginRestore()
        gate.restoreFailed()
        assertEquals(ExitState.RESTORE_FAILED, gate.state)
        assertFalse(gate.request(ExitReason.USER))
        assertTrue(gate.retryRestore())
        assertEquals(ExitState.RESTORING, gate.state)
        gate.restoreSucceeded()
        assertEquals(ExitState.DONE, gate.state)
        assertTrue(gate.request(ExitReason.USER))
        gate.reset()
        assertEquals(ExitState.IDLE, gate.state)
        assertEquals(0, gate.generation)
        assertEquals(null, gate.reason)
    }

    @Test
    fun beginTeardownFromTeardownIsIdempotentNoOp() {
        val gate = ExitGate()
        gate.beginTeardown()
        assertEquals(ExitState.TEARDOWN, gate.state)
        assertEquals(ExitReason.GUEST_ENDED, gate.reason)
        gate.beginTeardown()
        assertEquals(ExitState.TEARDOWN, gate.state)
    }
}
