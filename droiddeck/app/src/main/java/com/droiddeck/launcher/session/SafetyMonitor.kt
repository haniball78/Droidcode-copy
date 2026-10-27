package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log

interface BatterySource {
    fun read(): BatteryReading?
}

interface ThermalSource {
    fun thermalStatus(): Int
}

enum class BridgeStatus {
    OK,
    LOST,
    UNKNOWN,
}

interface BridgeProbe {
    fun status(): BridgeStatus
}

interface Clock {
    fun nowMs(): Long
}

interface SafetyListener {
    fun onWarnBattery(levelPercent: Int)
    fun onEndTweaks(reason: String)
    fun onStopCountdownTick(secondsLeft: Int)
    fun onStopCountdownFinished()
    fun onBridgeLost()
    fun onAlert(message: String)
}

class SafetyMonitor(
    private val batterySource: BatterySource,
    private val thermalSource: ThermalSource,
    private val bridgeProbe: BridgeProbe,
    private val clock: Clock,
    private val listener: SafetyListener,
    private val pollMs: Long = 5000L,
    private val heartbeatMs: Long = 15000L,
    private val thresholds: SafetyThresholds = SafetyThresholds(),
    private val countdownSeconds: Int = SafetyPolicy.COUNTDOWN_SECONDS,
) {
    private val trend = BatteryTrend()
    private var tweaksEnded = false
    private var warnedAt15 = false
    private var bridgeReported = false
    private var tweaksActive = true
    @Volatile private var running = false
    @Volatile private var countdownActive = false
    @Volatile private var countdownCancelled = false
    private var monitorThread: Thread? = null
    private var lastHeartbeatMs = 0L
    private var thermalUnknownLogged = false

    fun setTweaksActive(active: Boolean) {
        tweaksActive = active
    }

    fun start() {
        if (running) return
        running = true
        lastHeartbeatMs = clock.nowMs()
        monitorThread = Thread({
            while (running) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "safety tick", t)
                }
                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "safety-monitor").apply { start() }
    }

    fun stop() {
        running = false
        cancelCountdown()
        monitorThread?.interrupt()
        monitorThread = null
    }

    fun cancelCountdown() {
        countdownCancelled = true
    }

    fun isCountdownActive(): Boolean = countdownActive

    private fun tick() {
        val now = clock.nowMs()
        val battery = batterySource.read()
        if (battery != null) trend.add(battery)
        val bridgeStatus = if (now - lastHeartbeatMs >= heartbeatMs) {
            lastHeartbeatMs = now
            bridgeProbe.status()
        } else {
            null
        }
        val thermalStatus = thermalSource.thermalStatus()
        if (thermalStatus == SafetyPolicy.THERMAL_UNKNOWN) {
            if (!thermalUnknownLogged) {
                thermalUnknownLogged = true
                Log.w(TAG, "thermal reading unknown, no thermal action taken")
            }
        } else {
            thermalUnknownLogged = false
        }
        val decision = SafetyPolicy.evaluate(
            SafetyInput(
                battery = battery,
                thermalStatus = thermalStatus,
                bridgeOk = bridgeStatus != BridgeStatus.LOST,
                bridgeUnknown = bridgeStatus == BridgeStatus.UNKNOWN,
                tweaksActive = tweaksActive,
                tweaksAlreadyEnded = tweaksEnded,
                warnedAt15 = warnedAt15,
                stopCountdownActive = countdownActive,
                bridgeLostAlreadyReported = bridgeReported,
            ),
            thresholds,
        )
        tweaksEnded = decision.tweaksAlreadyEnded
        warnedAt15 = decision.warnedAt15
        bridgeReported = decision.bridgeLostAlreadyReported
        val action = decision.action
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.sample(
            null,
            battery?.levelPercent ?: -1,
            battery?.charging ?: false,
            thermalStatus,
            bridgeStatus?.name ?: "cached",
            tweaksActive,
            force = action != SafetyAction.NONE,
            nowMs = now,
        )
        when (action) {
            SafetyAction.NONE -> Unit
            SafetyAction.WARN_BATTERY_15 -> {
                val level = battery?.levelPercent ?: -1
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    "safety.monitor", "warn-battery-15",
                    fields = mapOf("battery" to level.toString(), "thermal" to thermalStatus.toString()),
                )
                listener.onAlert("Battery at $level%: session tweaks will end at 10%.")
                listener.onWarnBattery(level)
            }
            SafetyAction.END_TWEAKS -> {
                tweaksActive = false
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    "safety.monitor", "end-tweaks",
                    fields = mapOf(
                        "battery" to (battery?.levelPercent ?: -1).toString(),
                        "thermal" to thermalStatus.toString(),
                    ),
                )
                listener.onAlert("Battery or heat safety: performance tweaks ended, game continues.")
                listener.onEndTweaks("safety")
            }
            SafetyAction.STOP_WITH_COUNTDOWN -> startCountdown()
            SafetyAction.BRIDGE_LOST_RESTORE -> {
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    "safety.monitor", "bridge-lost-restore",
                    fields = mapOf("bridge" to (bridgeStatus?.name ?: "unknown")),
                )
                listener.onAlert("Privileged bridge lost: restoring system settings, game continues.")
                listener.onBridgeLost()
            }
        }
    }

    private fun startCountdown() {
        if (countdownActive) return
        countdownActive = true
        countdownCancelled = false
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            "safety.monitor", "stop-countdown-started",
            fields = mapOf("seconds" to countdownSeconds.toString()),
        )
        Thread({
            var left = countdownSeconds
            while (left > 0 && !countdownCancelled) {
                listener.onStopCountdownTick(left)
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                left--
            }
            countdownActive = false
            if (!countdownCancelled) listener.onStopCountdownFinished()
        }, "safety-countdown").start()
    }

    companion object {
        private const val TAG = "SafetyMonitor"
    }
}

object SafetySources {
    fun battery(context: Context): BatterySource = object : BatterySource {
        override fun read(): BatteryReading? {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val sticky = context.registerReceiver(
                    null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val level = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
                val pct = if (level >= 0) level * 100 / scale
                    else bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (pct < 0) return null
                val status = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                BatteryReading(
                    pct,
                    status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL,
                    android.os.SystemClock.elapsedRealtime(),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun thermal(context: Context): ThermalSource = object : ThermalSource {
        override fun thermalStatus(): Int {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (android.os.Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: SafetyPolicy.THERMAL_UNKNOWN
                else SafetyPolicy.THERMAL_UNKNOWN
            } catch (_: Exception) {
                SafetyPolicy.THERMAL_UNKNOWN
            }
        }
    }

    fun bridge(): BridgeProbe = object : BridgeProbe {
        override fun status(): BridgeStatus {
            return try {
                if (com.droiddeck.launcher.core.privileged.PrivilegedBridge.refresh().isActive) BridgeStatus.OK
                else BridgeStatus.LOST
            } catch (_: Exception) {
                BridgeStatus.UNKNOWN
            }
        }
    }

    fun systemClock(): Clock = object : Clock {
        override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
