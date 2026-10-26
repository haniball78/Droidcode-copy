package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadManTest {
    @Test
    fun startsDisarmed() {
        val deadMan = DeadMan()
        assertEquals(DeadManState.DISARMED, deadMan.state)
        assertFalse(deadMan.isExpired(0L))
    }

    @Test
    fun armThenBeatKeepsArmed() {
        val deadMan = DeadMan()
        assertTrue(deadMan.arm(1000L))
        assertTrue(deadMan.beat(2000L))
        assertEquals(DeadManState.ARMED, deadMan.state)
        assertEquals(2000L, deadMan.lastBeatMs)
        assertFalse(deadMan.isExpired(2000L + DeadMan.WATCHDOG_MS))
    }

    @Test
    fun watchdogBoundaryIsStrictlyGreater() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        assertFalse(deadMan.isExpired(DeadMan.WATCHDOG_MS))
        assertTrue(deadMan.isExpired(DeadMan.WATCHDOG_MS + 1))
    }

    @Test
    fun checkExpiryTransitionsOnce() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        assertTrue(deadMan.checkExpiry(DeadMan.WATCHDOG_MS + 1))
        assertEquals(DeadManState.EXPIRED, deadMan.state)
        assertFalse(deadMan.checkExpiry(DeadMan.WATCHDOG_MS + 2))
    }

    @Test
    fun beatAfterExpiryIsRejected() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        deadMan.checkExpiry(DeadMan.WATCHDOG_MS + 1)
        assertFalse(deadMan.beat(DeadMan.WATCHDOG_MS + 2))
        assertEquals(DeadManState.EXPIRED, deadMan.state)
    }

    @Test
    fun beatWhileDisarmedIsRejected() {
        val deadMan = DeadMan()
        assertFalse(deadMan.beat(1000L))
        assertEquals(DeadManState.DISARMED, deadMan.state)
    }

    @Test
    fun disarmAfterExpiryReturnsToDisarmed() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        deadMan.checkExpiry(DeadMan.WATCHDOG_MS + 1)
        assertTrue(deadMan.disarm())
        assertEquals(DeadManState.DISARMED, deadMan.state)
        assertEquals(-1L, deadMan.lastBeatMs)
        assertFalse(deadMan.isExpired(DeadMan.WATCHDOG_MS + 100000L))
    }

    @Test
    fun armAfterExpiryRearms() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        deadMan.checkExpiry(DeadMan.WATCHDOG_MS + 1)
        assertTrue(deadMan.arm(DeadMan.WATCHDOG_MS + 2))
        assertEquals(DeadManState.ARMED, deadMan.state)
        assertTrue(deadMan.beat(DeadMan.WATCHDOG_MS + 3))
    }

    @Test
    fun rearmResetsWatchdogTimer() {
        val deadMan = DeadMan()
        deadMan.arm(0L)
        deadMan.arm(DeadMan.WATCHDOG_MS - 1)
        assertFalse(deadMan.isExpired(DeadMan.WATCHDOG_MS + DeadMan.WATCHDOG_MS - 2))
        assertTrue(deadMan.isExpired(DeadMan.WATCHDOG_MS + DeadMan.WATCHDOG_MS))
    }

    @Test
    fun beatIntervalIsWithinWatchdog() {
        assertTrue(DeadMan.BEAT_INTERVAL_MS < DeadMan.WATCHDOG_MS)
        assertTrue(DeadMan.BEAT_INTERVAL_MS * 4 < DeadMan.WATCHDOG_MS)
    }

    @Test
    fun shutdownDeadlineBoundary() {
        assertTrue(ShutdownDeadline.withinDeadline(1000L, 1000L + DeadMan.SHUTDOWN_DEADLINE_MS))
        assertFalse(ShutdownDeadline.withinDeadline(1000L, 1000L + DeadMan.SHUTDOWN_DEADLINE_MS + 1))
    }

    @Test
    fun shutdownRemainingCountsDownToZero() {
        assertEquals(DeadMan.SHUTDOWN_DEADLINE_MS, ShutdownDeadline.remainingMs(1000L, 1000L))
        assertEquals(500L, ShutdownDeadline.remainingMs(1000L, 1000L + DeadMan.SHUTDOWN_DEADLINE_MS - 500L))
        assertEquals(0L, ShutdownDeadline.remainingMs(1000L, 1000L + DeadMan.SHUTDOWN_DEADLINE_MS + 5000L))
    }
}
