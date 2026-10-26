package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendStateTest {
    @Test
    fun codecRoundTripsSuspendedSession() {
        val session = SuspendedSession(42, "game:\"quoted\"", SuspendKind.SAFETY, 1700000000000L, true, "sess-1")
        val decoded = SuspendCodec.decode(SuspendCodec.encode(session))
        assertEquals(session, decoded)
    }

    @Test
    fun codecRoundTripsNullSessionId() {
        val session = SuspendedSession(1, "steam", SuspendKind.USER, 1000L, false, null)
        val decoded = SuspendCodec.decode(SuspendCodec.encode(session))
        assertEquals(session, decoded)
    }

    @Test
    fun codecRejectsCorruptInput() {
        assertNull(SuspendCodec.decode(""))
        assertNull(SuspendCodec.decode("{\"version\":2,\"generation\":1}"))
        assertNull(SuspendCodec.decode("{\"version\":1,\"generation\":1,\"game\":\"x\"}"))
        assertNull(SuspendCodec.decode("{\"version\":1,\"generation\":1,\"game\":\"x\",\"reason\":\"OTHER\",\"timeMs\":1,\"tweaksEnded\":false}"))
        assertNull(SuspendCodec.decode("not json"))
    }

    @Test
    fun storeRoundTripsAndClears() {
        val dir = File(System.getProperty("java.io.tmpdir"), "suspend-state-test-${System.nanoTime()}")
        try {
            val session = SuspendedSession(7, "steam", SuspendKind.USER, 2000L, false, null)
            assertTrue(SuspendStore.save(dir, session))
            assertEquals(session, SuspendStore.load(dir))
            assertTrue(SuspendStore.clear(dir))
            assertNull(SuspendStore.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun canResumeWithoutOptimizationsRequiresChargerOrTwentyPercent() {
        assertTrue(SuspendPolicy.canResumeWithoutOptimizations(null, true))
        assertTrue(SuspendPolicy.canResumeWithoutOptimizations(20, false))
        assertTrue(SuspendPolicy.canResumeWithoutOptimizations(55, false))
        assertFalse(SuspendPolicy.canResumeWithoutOptimizations(19, false))
        assertFalse(SuspendPolicy.canResumeWithoutOptimizations(null, false))
    }

    @Test
    fun safetyResumeWithoutOptimizationsBlockedBelowTwenty() {
        val blocked = SuspendPolicy.checkResume(
            SuspendKind.SAFETY, false, true, 19, false, 0, true,
        )
        assertFalse(blocked.allowed)
        assertEquals(ResumeBlock.BATTERY_LOW_SAFETY, blocked.block)
        val allowed = SuspendPolicy.checkResume(
            SuspendKind.SAFETY, false, true, 20, false, 0, true,
        )
        assertTrue(allowed.allowed)
    }

    @Test
    fun safetyResumeRequiresExplicitAction() {
        val blocked = SuspendPolicy.checkResume(
            SuspendKind.SAFETY, true, true, 80, false, 0, false,
        )
        assertFalse(blocked.allowed)
        assertEquals(ResumeBlock.EXPLICIT_REQUIRED, blocked.block)
    }

    @Test
    fun safetyResumeWithOptimizationsBlockedByLowBatteryAndHeat() {
        assertEquals(
            ResumeBlock.BATTERY_LOW_SAFETY,
            SuspendPolicy.checkResume(SuspendKind.SAFETY, true, true, 10, false, 0, true).block,
        )
        assertEquals(
            ResumeBlock.THERMAL_SEVERE_SAFETY,
            SuspendPolicy.checkResume(SuspendKind.SAFETY, true, true, 80, false, 3, true).block,
        )
        assertTrue(
            SuspendPolicy.checkResume(SuspendKind.SAFETY, true, true, 80, false, 2, true).allowed,
        )
    }

    @Test
    fun userResumeWithoutOptimizationsNeedsNoBridge() {
        assertTrue(
            SuspendPolicy.checkResume(SuspendKind.USER, false, false, null, false, 0, false).allowed,
        )
        assertFalse(
            SuspendPolicy.checkResume(SuspendKind.USER, true, false, null, false, 0, false).allowed,
        )
    }

    @Test
    fun leaveTimeoutRequiresHiddenUnsuspendedActiveAndElapsed() {
        assertTrue(SuspendPolicy.shouldRestoreOnLeave(90000L, 90000L, true, false, true))
        assertFalse(SuspendPolicy.shouldRestoreOnLeave(89999L, 90000L, true, false, true))
        assertFalse(SuspendPolicy.shouldRestoreOnLeave(90000L, 90000L, false, false, true))
        assertFalse(SuspendPolicy.shouldRestoreOnLeave(90000L, 90000L, true, true, true))
        assertFalse(SuspendPolicy.shouldRestoreOnLeave(90000L, 90000L, true, false, false))
    }

    @Test
    fun leaveReturnReapplyRefusesSafetyAndBadConditions() {
        assertTrue(
            SuspendPolicy.shouldReapplyOnReturn(true, false, true, 80, false, 0),
        )
        assertFalse(
            SuspendPolicy.shouldReapplyOnReturn(false, false, true, 80, false, 0),
        )
        assertFalse(
            SuspendPolicy.shouldReapplyOnReturn(true, true, true, 80, false, 0),
        )
        assertFalse(
            SuspendPolicy.shouldReapplyOnReturn(true, false, false, 80, false, 0),
        )
        assertFalse(
            SuspendPolicy.shouldReapplyOnReturn(true, false, true, 10, false, 0),
        )
        assertFalse(
            SuspendPolicy.shouldReapplyOnReturn(true, false, true, 80, false, 3),
        )
    }
}
