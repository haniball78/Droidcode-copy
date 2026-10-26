package com.droiddeck.launcher.session

data class BatteryReading(
    val levelPercent: Int,
    val charging: Boolean,
    val nowMs: Long,
)

enum class SafetyAction {
    NONE,
    WARN_BATTERY_15,
    END_TWEAKS,
    STOP_WITH_COUNTDOWN,
    BRIDGE_LOST_RESTORE,
}

data class SafetyInput(
    val battery: BatteryReading?,
    val thermalStatus: Int,
    val bridgeOk: Boolean,
    val tweaksActive: Boolean,
    val tweaksAlreadyEnded: Boolean,
    val warnedAt15: Boolean,
    val stopCountdownActive: Boolean,
    val bridgeLostAlreadyReported: Boolean,
    val bridgeUnknown: Boolean = false,
)

data class SafetyDecision(
    val action: SafetyAction,
    val tweaksAlreadyEnded: Boolean,
    val warnedAt15: Boolean,
    val bridgeLostAlreadyReported: Boolean,
)

data class SafetyThresholds(
    val warnLevel: Int = 15,
    val tweaksLevel: Int = 10,
    val emergencyLevel: Int = 5,
)

internal object SafetyPolicy {
    const val WARN_LEVEL = 15
    const val TWEAKS_LEVEL = 10
    const val EMERGENCY_LEVEL = 5
    const val HYSTERESIS = 3
    const val COUNTDOWN_SECONDS = 20
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    const val THERMAL_UNKNOWN = -1

    fun evaluate(input: SafetyInput, thresholds: SafetyThresholds = SafetyThresholds()): SafetyDecision {
        var tweaksEnded = input.tweaksAlreadyEnded
        var warned = input.warnedAt15
        var bridgeReported = input.bridgeLostAlreadyReported
        val battery = input.battery
        if (battery != null && !battery.charging && battery.levelPercent >= 0) {
            if (battery.levelPercent >= thresholds.warnLevel + HYSTERESIS) warned = false
            if (battery.levelPercent >= thresholds.tweaksLevel + HYSTERESIS) tweaksEnded = false
            if (battery.levelPercent <= thresholds.emergencyLevel && !input.stopCountdownActive) {
                return SafetyDecision(SafetyAction.STOP_WITH_COUNTDOWN, tweaksEnded, warned, bridgeReported)
            }
            if (battery.levelPercent <= thresholds.tweaksLevel && input.tweaksActive && !tweaksEnded) {
                tweaksEnded = true
                return SafetyDecision(SafetyAction.END_TWEAKS, tweaksEnded, warned, bridgeReported)
            }
            if (battery.levelPercent <= thresholds.warnLevel && !warned) {
                warned = true
                return SafetyDecision(SafetyAction.WARN_BATTERY_15, tweaksEnded, warned, bridgeReported)
            }
        }
        if (input.thermalStatus != THERMAL_UNKNOWN && input.thermalStatus >= THERMAL_SEVERE && input.tweaksActive && !tweaksEnded) {
            tweaksEnded = true
            return SafetyDecision(SafetyAction.END_TWEAKS, tweaksEnded, warned, bridgeReported)
        }
        if (input.bridgeUnknown) {
            return SafetyDecision(SafetyAction.NONE, tweaksEnded, warned, bridgeReported)
        }
        if (!input.bridgeOk && !bridgeReported) {
            bridgeReported = true
            return SafetyDecision(SafetyAction.BRIDGE_LOST_RESTORE, tweaksEnded, warned, bridgeReported)
        }
        if (input.bridgeOk) bridgeReported = false
        return SafetyDecision(SafetyAction.NONE, tweaksEnded, warned, bridgeReported)
    }
}

internal class BatteryTrend(private val maxSamples: Int = 12) {
    private val samples = ArrayList<BatteryReading>()

    fun add(reading: BatteryReading) {
        if (reading.levelPercent < 0) return
        samples.add(reading)
        while (samples.size > maxSamples) samples.removeAt(0)
    }

    fun minutesToLevel(targetPercent: Int): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dtMinutes = (last.nowMs - first.nowMs) / 60000.0
        if (dtMinutes <= 0.0) return null
        val dropPerMinute = (first.levelPercent - last.levelPercent) / dtMinutes
        if (dropPerMinute <= 0.0) return null
        val remaining = last.levelPercent - targetPercent
        if (remaining <= 0) return 0.0
        return remaining / dropPerMinute
    }
}
