package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private fun input(
        level: Int,
        charging: Boolean = false,
        thermal: Int = 0,
        bridgeOk: Boolean = true,
        tweaksActive: Boolean = true,
        tweaksEnded: Boolean = false,
        warned: Boolean = false,
        countdown: Boolean = false,
        bridgeReported: Boolean = false,
    ) = SafetyInput(
        battery = BatteryReading(level, charging, 0L),
        thermalStatus = thermal,
        bridgeOk = bridgeOk,
        tweaksActive = tweaksActive,
        tweaksAlreadyEnded = tweaksEnded,
        warnedAt15 = warned,
        stopCountdownActive = countdown,
        bridgeLostAlreadyReported = bridgeReported,
    )

    @Test
    fun warnAt15ThenEndTweaksAt10ThenStopAt5() {
        val warned = SafetyPolicy.evaluate(input(15))
        assertEquals(SafetyAction.WARN_BATTERY_15, warned.action)
        assertTrue(warned.warnedAt15)
        val ended = SafetyPolicy.evaluate(input(10, warned = true))
        assertEquals(SafetyAction.END_TWEAKS, ended.action)
        assertTrue(ended.tweaksAlreadyEnded)
        val emergency = SafetyPolicy.evaluate(input(5, warned = true, tweaksEnded = true, tweaksActive = false))
        assertEquals(SafetyAction.STOP_WITH_COUNTDOWN, emergency.action)
    }

    @Test
    fun endTweaksDoesNotStopTheGame() {
        val decision = SafetyPolicy.evaluate(input(10))
        assertEquals(SafetyAction.END_TWEAKS, decision.action)
        assertTrue(decision.tweaksAlreadyEnded)
    }

    @Test
    fun chargingSuppressesBatteryActions() {
        assertEquals(SafetyAction.NONE, SafetyPolicy.evaluate(input(5, charging = true)).action)
        assertEquals(SafetyAction.NONE, SafetyPolicy.evaluate(input(10, charging = true)).action)
    }

    @Test
    fun hysteresisRearmsAfterRecovery() {
        val ended = SafetyPolicy.evaluate(input(10))
        assertTrue(ended.tweaksAlreadyEnded)
        val rearmed = SafetyPolicy.evaluate(input(13, tweaksEnded = true, tweaksActive = false, warned = true))
        assertFalse(rearmed.tweaksAlreadyEnded)
        assertEquals(SafetyAction.NONE, rearmed.action)
        val again = SafetyPolicy.evaluate(input(10, tweaksEnded = false, tweaksActive = true))
        assertEquals(SafetyAction.END_TWEAKS, again.action)
    }

    @Test
    fun warnRearmsAt18() {
        val warned = SafetyPolicy.evaluate(input(15))
        assertTrue(warned.warnedAt15)
        val rearmed = SafetyPolicy.evaluate(input(18, warned = true))
        assertFalse(rearmed.warnedAt15)
    }

    @Test
    fun severeThermalEndsTweaksButModerateDoesNot() {
        assertEquals(SafetyAction.NONE, SafetyPolicy.evaluate(input(50, thermal = 2)).action)
        val severe = SafetyPolicy.evaluate(input(50, thermal = 3))
        assertEquals(SafetyAction.END_TWEAKS, severe.action)
        val critical = SafetyPolicy.evaluate(input(50, thermal = 4))
        assertEquals(SafetyAction.END_TWEAKS, critical.action)
    }

    @Test
    fun bridgeLossRestoresOnceUntilRecovery() {
        val lost = SafetyPolicy.evaluate(input(50, bridgeOk = false))
        assertEquals(SafetyAction.BRIDGE_LOST_RESTORE, lost.action)
        assertTrue(lost.bridgeLostAlreadyReported)
        assertEquals(SafetyAction.NONE, SafetyPolicy.evaluate(input(50, bridgeOk = true, bridgeReported = true)).action)
    }

    @Test
    fun unknownBatteryStillEvaluatesThermalAndBridge() {
        val noBattery = SafetyInput(null, 3, true, true, false, false, false, false)
        assertEquals(SafetyAction.END_TWEAKS, SafetyPolicy.evaluate(noBattery).action)
    }

    @Test
    fun batteryTrendEstimatesFromLevelRegression() {
        val trend = BatteryTrend()
        trend.add(BatteryReading(20, false, 0L))
        trend.add(BatteryReading(18, false, 600_000L))
        val eta10 = trend.minutesToLevel(10)
        assertTrue(eta10 != null && eta10 > 0.0)
        assertEquals(40.0, eta10!!, 0.001)
        assertNull(BatteryTrend().minutesToLevel(10))
    }

    @Test
    fun batteryTrendIgnoresChargingFlatline() {
        val trend = BatteryTrend()
        trend.add(BatteryReading(20, false, 0L))
        trend.add(BatteryReading(20, false, 600_000L))
        assertNull(trend.minutesToLevel(10))
    }

    @Test
    fun exitGateTransitions() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER))
        assertFalse(gate.request(ExitReason.USER))
        assertEquals(ExitState.REQUESTED, gate.state)
        gate.beginTeardown()
        assertEquals(ExitState.TEARDOWN, gate.state)
        assertTrue(gate.beginRestore())
        assertEquals(ExitState.RESTORING, gate.state)
        gate.restoreFailed()
        assertEquals(ExitState.RESTORE_FAILED, gate.state)
        assertFalse(gate.request(ExitReason.USER))
        assertTrue(gate.retryRestore())
        assertEquals(ExitState.RESTORING, gate.state)
        gate.restoreSucceeded()
        assertEquals(ExitState.DONE, gate.state)
        gate.reset()
        assertEquals(ExitState.IDLE, gate.state)
    }

    @Test
    fun exitGateGuestEndedDefault() {
        val gate = ExitGate()
        gate.beginTeardown()
        assertEquals(ExitReason.GUEST_ENDED, gate.reason)
    }
}
