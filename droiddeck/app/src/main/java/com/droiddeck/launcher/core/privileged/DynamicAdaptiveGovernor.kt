package com.droiddeck.launcher.core.privileged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.droiddeck.launcher.session.SessionPrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DynamicAdaptiveGovernor — lightweight, real-time thermal and load balancer.
 *
 * ── Objectives ──────────────────────────────────────────────────────────────
 * 1. Flexible & Elastic Adaptation: Monitors phone thermal state, battery temperature,
 *    and Power HAL headroom in real-time, flexing system load dynamically.
 * 2. Default Balanced Baseline: Keeps system cool and stable with capped background tasks
 *    and isolated Cortex-A720 performance cores by default.
 * 3. Thermal Relief (Hot State): Automatically tightens background process limits,
 *    relaxes CPU scheduler pressure, clears memory cache, and guarantees prime core remains idle.
 * 4. Safe Performance Boost (Cool State): If the device has ample thermal margin (<37°C),
 *    smoothly prioritizes game threads for maximum frame stability.
 * 5. Zero-Polling Overhead & Anti-Flapping: Uses OS hardware thermal listeners and a gentle
 *    15-second heartbeat with a 30-second hysteresis hold to prevent rapid state bouncing.
 */
internal class TierActionQueue {
    private var executor: ExecutorService? = null

    @Synchronized
    fun start() {
        if (executor == null || executor!!.isShutdown) {
            executor = Executors.newSingleThreadExecutor { Thread(it, "governor-action") }
        }
    }

    fun submit(guard: () -> Boolean, action: () -> Unit) {
        val current = synchronized(this) { executor } ?: return
        runCatching {
            current.submit {
                if (guard()) action()
            }
        }
    }

    @Synchronized
    fun stopBarrier(timeoutMs: Long): Boolean {
        val current = executor
        executor = null
        if (current == null) return true
        current.shutdown()
        return try {
            current.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

object DynamicAdaptiveGovernor {
    private const val TAG = "AdaptiveGovernor"

    enum class AdaptiveTier(val labelAr: String, val labelEn: String) {
        BALANCED_COOL("❄️ متوازن ومستقر (افتراضي)", "❄️ Balanced Cool (Default)"),
        COOLING_RELIEF("🧊 تبريد وتخفيف الضغط", "🧊 Thermal Relief (Active Cooling)"),
        HEADROOM_BOOST("⚡ أداء معزز (حرارة منخفضة)", "⚡ Safe Headroom Boost")
    }

    @Volatile private var currentTier = AdaptiveTier.BALANCED_COOL
    @Volatile private var lastTransitionTime = 0L
    @Volatile private var activePid: Int = -1
    @Volatile private var activeSessionGen: Int = 0
    @Volatile private var running = false

    private val isMonitoring = AtomicBoolean(false)
    private var thermalListener: Any? = null
    private var monitorThread: Thread? = null
    private val actionQueue = TierActionQueue()

    // ── Public Lifecycle ───────────────────────────────────────────────────────

    /** Start adaptive monitoring for an active game session. */
    @Synchronized
    fun start(context: Context, sessionGen: Int, pid: Int) {
        if (!SessionPrefs.advancedMasterEnabled(context) || !SessionPrefs.dynamicGovernor(context)) {
            Log.i(TAG, "governor: disabled in settings or master switch off")
            return
        }
        if (!SessionPrefs.gameModeEngine(context) || !GameModeEngine.isActive()) {
            Log.i(TAG, "governor: game mode is not active, refusing to write system limits")
            return
        }
        if (!Tweaks.hasKey(context, "background_process_limit")) {
            Log.i(TAG, "governor: game mode journal has no background limit, refusing to write system limits")
            return
        }
        stop(context) // clean previous if any

        activeSessionGen = sessionGen
        activePid = pid
        running = true
        actionQueue.start()
        currentTier = AdaptiveTier.BALANCED_COOL
        lastTransitionTime = 0L

        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        // 1. Zero-polling hardware thermal listener (Android 10+)
        if (Build.VERSION.SDK_INT >= 29 && pm != null) {
            try {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    if (running && activeSessionGen == sessionGen) {
                        evaluateState(appContext, status, force = false)
                    }
                }
                pm.addThermalStatusListener(ContextCompat.getMainExecutor(appContext), listener)
                thermalListener = listener
            } catch (t: Throwable) {
                Log.w(TAG, "could not register thermal listener", t)
            }
        }

        // Initial instant evaluation based on current temperature at launch
        val initialThermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) pm.currentThermalStatus else 0
        evaluateState(appContext, initialThermal, force = true)

        // 2. Ultra-lightweight 15-second heartbeat for battery temp & headroom
        monitorThread = Thread({
            Log.i(TAG, "governor: started monitoring for pid=$pid gen=$sessionGen")
            while (running && activeSessionGen == sessionGen) {
                try {
                    Thread.sleep(15000)
                    if (!running || activeSessionGen != sessionGen) break
                    val currentThermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) pm.currentThermalStatus else 0
                    evaluateState(appContext, currentThermal, force = false)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat tick non-fatal error", t)
                }
            }
            Log.i(TAG, "governor: monitor loop finished")
        }, "adaptive-governor").apply { start() }
    }

    /** Stop governor and restore baseline settings. */
    @Synchronized
    fun stop(context: Context) {
        running = false
        activeSessionGen = 0
        activePid = -1

        monitorThread?.interrupt()
        monitorThread = null
        actionQueue.stopBarrier(4000)

        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= 29 && pm != null && thermalListener != null) {
            try {
                pm.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
            } catch (_: Throwable) {}
            thermalListener = null
        }

        currentTier = AdaptiveTier.BALANCED_COOL
        Log.i(TAG, "governor: stopped")
    }

    // ── Adaptive State Logic & Hysteresis ──────────────────────────────────────

    private fun evaluateState(context: Context, thermalStatus: Int, force: Boolean) {
        if (!running) return

        val now = android.os.SystemClock.elapsedRealtime()
        // Minimum hold time: 30 seconds between state transitions to avoid flapping (skipped on initial force evaluation)
        if (!force && lastTransitionTime != 0L && now - lastTransitionTime < 30000) return

        val batteryTemp = getBatteryTemperature(context)
        val headroom = getThermalHeadroom(context)

        val nextTier = when {
            // High Heat -> Cooling Relief
            batteryTemp >= 41.0f || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.95f -> {
                AdaptiveTier.COOLING_RELIEF
            }
            // Cool & Comfortable -> Safe Boost
            batteryTemp <= 36.5f && thermalStatus == PowerManager.THERMAL_STATUS_NONE && headroom <= 0.65f -> {
                AdaptiveTier.HEADROOM_BOOST
            }
            // Balanced Middle
            else -> {
                if (currentTier == AdaptiveTier.COOLING_RELIEF) {
                    // Hysteresis: wait until cooled to <= 39.0°C and thermal <= LIGHT before releasing relief
                    if (batteryTemp <= 39.0f && thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT) {
                        AdaptiveTier.BALANCED_COOL
                    } else {
                        AdaptiveTier.COOLING_RELIEF
                    }
                } else if (currentTier == AdaptiveTier.HEADROOM_BOOST) {
                    // Step down from boost if warming up
                    if (batteryTemp >= 38.5f || thermalStatus > PowerManager.THERMAL_STATUS_NONE) {
                        AdaptiveTier.BALANCED_COOL
                    } else {
                        AdaptiveTier.HEADROOM_BOOST
                    }
                } else {
                    AdaptiveTier.BALANCED_COOL
                }
            }
        }

        if (nextTier != currentTier || force) {
            val prev = currentTier
            currentTier = nextTier
            lastTransitionTime = now
            Log.i(TAG, "governor transition: $prev -> $nextTier [temp=${batteryTemp}°C, thermal=$thermalStatus, headroom=$headroom]")
            applyTierAction(context, nextTier, activePid, activeSessionGen)
        }
    }

    private fun applyTierAction(context: Context, tier: AdaptiveTier, pid: Int, sessionGen: Int) {
        val limit = when (tier) {
            AdaptiveTier.COOLING_RELIEF -> "2"
            AdaptiveTier.BALANCED_COOL -> "4"
            AdaptiveTier.HEADROOM_BOOST -> "4"
        }
        val limitCmd = "settings put global background_process_limit $limit 2>/dev/null"
        val script = buildString {
            when (tier) {
                AdaptiveTier.COOLING_RELIEF -> {
                    append("am kill-all 2>/dev/null; ")
                    if (pid > 1) {
                        append("renice -n -5 -p $pid 2>/dev/null; ")
                        append("taskset -p 7c $pid 2>/dev/null; ")
                    }
                }
                AdaptiveTier.BALANCED_COOL -> {
                    if (pid > 1) {
                        append("renice -n -10 -p $pid 2>/dev/null; ")
                        append("taskset -p 7c $pid 2>/dev/null; ")
                    }
                }
                AdaptiveTier.HEADROOM_BOOST -> {
                    if (pid > 1) {
                        append("renice -n -14 -p $pid 2>/dev/null; ")
                    }
                }
            }
        }

        actionQueue.submit(
            guard = { running && (activeSessionGen == 0 || sessionGen == activeSessionGen) },
            action = {
                try {
                    if (!Tweaks.applyExistingKeyChange(context, "background_process_limit", limitCmd)) {
                        Log.w(TAG, "applyTierAction refused: game mode journal has no background limit")
                        return@submit
                    }
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        context, "device.change", "governor-tier",
                        fields = mapOf("tier" to tier.name, "limit" to limit),
                    )
                    if (script.isNotEmpty()) {
                        PrivilegedBridge.executeChecked(script, PrivilegedBridge.DEFAULT_TIMEOUT_MS)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "applyTierAction error: $limitCmd", t)
                }
            },
        )
    }

    // ── Diagnostic & Telemetry ─────────────────────────────────────────────────

    fun getCurrentTier(): AdaptiveTier = currentTier

    fun getDiagnosticReport(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Adaptive Governor"] = if (running) "🟢 ACTIVE" else "⚪ STANDBY (Runs with game)"
        out["Active Adaptive Tier"] = "${currentTier.labelEn} / ${currentTier.labelAr}"
        val temp = getBatteryTemperature(context)
        out["Battery Temperature"] = String.format("%.1f°C", temp)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) pm.currentThermalStatus else 0
        val thermalDesc = when (thermal) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE (Normal)"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT (Minor Warmth)"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE (Warm)"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE (Hot - Throttling)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL (Emergency Heat)"
            else -> "Status: $thermal"
        }
        out["Hardware Thermal Status"] = thermalDesc

        val headroom = getThermalHeadroom(context)
        out["Thermal Headroom"] = if (headroom.isNaN()) "Not reported" else String.format("%.2f (Margin)", headroom)

        return out
    }

    fun getBatteryTemperature(context: Context): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            tempTenths / 10.0f
        } catch (_: Exception) { 0.0f }
    }

    fun getThermalHeadroom(context: Context): Float {
        if (Build.VERSION.SDK_INT >= 30) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return try { pm?.getThermalHeadroom(10) ?: Float.NaN } catch (_: Exception) { Float.NaN }
        }
        return Float.NaN
    }
}
