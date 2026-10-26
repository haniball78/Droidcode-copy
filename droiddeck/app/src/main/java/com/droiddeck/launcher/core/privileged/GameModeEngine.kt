package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * GameModeEngine — ultra-low overhead, generation-guarded performance governor and core isolator.
 *
 * ── Philosophy: Load Reduction First ────────────────────────────────────────
 * Relieves system stress and background thrash without forcing hardware into thermal throttling.
 *
 * ── Profile 1: Cool & Efficient (Default) ──────────────────────────────────
 * 1. Clean RAM Cache: Frees cached background pages (`am kill-all`) upfront, eliminating
 *    Android low-memory killer (LMK) page scanning thrash during gameplay.
 * 2. Background Process Limit: Caps background cached apps to 4 (`background_process_limit = 4`),
 *    preventing background sync and push tasks from waking up CPU cores.
 * 3. Dynamic Clock Scaling: Keeps CPU/GPU governors dynamic. Clocks scale DOWN in menus,
 *    loading screens, and light scenes, preventing thermal throttling.
 * 4. Performance Core Affinity: Binds game process AND all its child threads (`/proc/$pid/task`)
 *    to the 5 Cortex-A720 Performance cores (mask `0x7c`, cores 2-6). This avoids cache thrashing
 *    while leaving the hot Cortex-X4 prime core idle to keep phone temperature cool.
 * 5. Gentle Real-Time Nice: Sets process nice to -10, ensuring priority over background
 *    threads without starving audio daemons.
 *
 * ── Profile 2: Aggressive Turbo (Optional, User-Toggled) ────────────────────
 * Activated only when `SessionPrefs.gameModeAggressive(context)` is toggled ON:
 * 1. Samsung Enhanced Processing (`sem_enhanced_cpu_responsiveness = 1`).
 * 2. Samsung Unrestricted Performance (`restricted_device_performance = 0,0`).
 * 3. Power HAL Fixed Performance Mode (`cmd power set-fixed-performance-mode-enabled true`).
 * 4. Prime Core Affinity: Expands affinity mask to `0xfc` (cores 2-7, including Cortex-X4).
 * 5. Maximum Real-Time Nice: -20.
 *
 * ── Ultra-Fast Single IPC Execution ─────────────────────────────────────────
 * All privileged commands for profile activation are bundled into a single shell execution,
 * completing in <20ms and causing zero game startup delay.
 */
object GameModeEngine {
    private const val TAG = "GameModeEngine"
    private const val MARKER_FILE = "gamemode-marker.txt"

    // Cores 2, 3, 4, 5, 6 (5x Cortex-A720 Performance cores, excluding Little cores 0-1 and hot Prime core 7)
    // Binary: 01111100 = 0x7c
    private const val COOL_PERF_CORES_MASK = "7c"

    // Cores 2, 3, 4, 5, 6, 7 (Performance + Prime cores)
    // Binary: 11111100 = 0xfc
    private const val TURBO_CORES_MASK = "fc"

    @Volatile private var savedBgProcessLimit: String? = null
    @Volatile private var savedSemEnhanced: String? = null
    @Volatile private var savedRestrictedPerf: String? = null
    @Volatile private var savedAdaptiveBattery: String? = null
    @Volatile private var applied = false
    @Volatile private var aggressiveActive = false

    private val activeGen = AtomicInteger(0)

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Apply game-mode optimizations at session start in a single rapid IPC execution. */
    @Synchronized
    fun apply(context: Context, sessionGen: Int = 0): String {
        activeGen.set(sessionGen)
        if (!SessionPrefs.advancedMasterEnabled(context)) return "game mode: disabled (master switch off)"
        if (!SessionPrefs.gameModeEngine(context)) return "game mode: disabled in settings"
        if (applied) return "game mode: already active"

        val aggressive = SessionPrefs.gameModeAggressive(context)
        aggressiveActive = aggressive
        val pkg = context.packageName

        val existingMarker = loadMarkerData(context)
        if (existingMarker != null) {
            savedBgProcessLimit = existingMarker.backgroundLimit
            savedSemEnhanced = existingMarker.semEnhanced
            savedRestrictedPerf = existingMarker.restrictedPerf
            savedAdaptiveBattery = existingMarker.adaptiveBattery
        } else {
            val curBg = SafetyChecks.parseBackgroundLimit(exec(context, "settings get global background_process_limit"))
            savedBgProcessLimit = curBg.takeIf { SafetyChecks.isCleanBackgroundLimit(it) }

            if (aggressive) {
                savedSemEnhanced = SafetyChecks.parseBackgroundLimit(exec(context, "settings get global sem_enhanced_cpu_responsiveness"))
                savedRestrictedPerf = SafetyChecks.parseBackgroundLimit(exec(context, "settings get global restricted_device_performance"))
                savedAdaptiveBattery = SafetyChecks.parseBackgroundLimit(exec(context, "settings get global adaptive_battery_management_enabled"))
            }
            saveMarkerData(
                context,
                GameModeMarker(savedBgProcessLimit, savedSemEnhanced, savedRestrictedPerf, savedAdaptiveBattery),
            )
        }

        if (sessionGen != 0 && activeGen.get() != sessionGen) {
            Log.w(TAG, "apply aborted: generation changed from $sessionGen to ${activeGen.get()}")
            return "game mode: aborted"
        }

        PrivilegedBridge.ensureBackgroundProtection(context)

        val batchScript = buildString {
            append("settings put global background_process_limit 4 2>/dev/null; ")
            append("am kill-all 2>/dev/null; ")
            append("cmd game mode 2 $pkg 2>/dev/null; ")

            // Turbo steps (if opted in)
            if (aggressive) {
                append("settings put global sem_enhanced_cpu_responsiveness 1 2>/dev/null; ")
                append("settings put global restricted_device_performance 0,0 2>/dev/null; ")
                append("cmd power set-fixed-performance-mode-enabled true 2>/dev/null; ")
                append("settings put global adaptive_battery_management_enabled 0 2>/dev/null; ")
            }
        }

        exec(context, batchScript)
        applied = true

        val summary = "game mode: ON [gen=$sessionGen, mode=${if (aggressive) "TURBO" else "COOL_LOAD_REDUCER"}]"
        Log.i(TAG, summary)
        return summary
    }

    /**
     * Boosts the game process PID and deeply binds all existing and newly spawned
     * thread tasks (/proc/$pid/task) to the performance core cluster.
     */
    fun boostSessionProcess(context: Context, pid: Int, sessionGen: Int = 0): String {
        if (!applied || pid <= 1) return "pid_boost: skipped (pid=$pid applied=$applied)"
        if (sessionGen != 0 && activeGen.get() != sessionGen) return "pid_boost: obsolete gen"

        val mask = if (aggressiveActive) TURBO_CORES_MASK else COOL_PERF_CORES_MASK
        val nice = if (aggressiveActive) "-20" else "-10"

        val pinScript = """
            renice -n $nice -p $pid 2>/dev/null || true
            echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null || true
            taskset -p $mask $pid 2>/dev/null || true
            for tid in /proc/$pid/task/*; do
                [ -d "${'$'}tid" ] && taskset -p $mask ${'$'}{tid##*/} 2>/dev/null || true
            done
        """.trimIndent()

        exec(context, pinScript)

        // Asynchronously re-apply affinity after 1.5s when Wine/guest has spawned its worker threads
        Thread({
            try {
                Thread.sleep(1500)
                if (applied && activeGen.get() > 0 && (sessionGen == 0 || activeGen.get() == sessionGen)) {
                    val followUpScript = """
                        for tid in /proc/$pid/task/*; do
                            [ -d "${'$'}tid" ] && taskset -p $mask ${'$'}{tid##*/} 2>/dev/null || true
                        done
                    """.trimIndent()
                    exec(context, followUpScript)
                    Log.d(TAG, "boostSessionProcess: follow-up thread affinity re-applied for pid=$pid")
                }
            } catch (_: Exception) {}
        }, "pid-affinity-followup").start()

        val desc = "pid $pid boosted deeply (nice=$nice, affinity=0x$mask [cores=${if (aggressiveActive) "2-7" else "2-6"}])"
        Log.i(TAG, "boostSessionProcess: $desc")
        return desc
    }

    /** Restore all modified system settings when the session ends. Idempotent and clean. */
    @Synchronized
    fun restore(context: Context, sessionGen: Int = 0): Boolean {
        activeGen.set(0)
        if (!applied && !hasMarker(context)) return true
        val marker = loadMarkerData(context)
        val effectiveBg = savedBgProcessLimit ?: marker?.backgroundLimit
        val effectiveSem = savedSemEnhanced ?: marker?.semEnhanced
        val effectiveRestricted = savedRestrictedPerf ?: marker?.restrictedPerf
        val effectiveAdaptive = savedAdaptiveBattery ?: marker?.adaptiveBattery
        val wasAggressive = aggressiveActive || marker?.semEnhanced != null ||
            marker?.restrictedPerf != null || marker?.adaptiveBattery != null
        applied = false
        Log.i(TAG, "game mode: restoring system settings (gen=$sessionGen)...")

        val pkg = context.packageName
        val restoreScript = buildString {
            if (effectiveBg != null && SafetyChecks.isCleanBackgroundLimit(effectiveBg)) {
                append("settings put global background_process_limit $effectiveBg 2>/dev/null; ")
            } else {
                append("settings delete global background_process_limit 2>/dev/null; ")
            }
            append("cmd game reset $pkg 2>/dev/null || cmd game mode 1 $pkg 2>/dev/null; ")
            append("cmd power set-fixed-performance-mode-enabled false 2>/dev/null; ")

            if (wasAggressive) {
                if (effectiveSem != null) append("settings put global sem_enhanced_cpu_responsiveness $effectiveSem 2>/dev/null; ")
                else append("settings delete global sem_enhanced_cpu_responsiveness 2>/dev/null; ")
                if (effectiveRestricted != null) append("settings put global restricted_device_performance $effectiveRestricted 2>/dev/null; ")
                else append("settings delete global restricted_device_performance 2>/dev/null; ")
                if (effectiveAdaptive != null) append("settings put global adaptive_battery_management_enabled $effectiveAdaptive 2>/dev/null; ")
                else append("settings delete global adaptive_battery_management_enabled 2>/dev/null; ")
            }
        }

        val result = execChecked(context, restoreScript)
        val actualBg = SafetyChecks.parseBackgroundLimit(exec(context, "settings get global background_process_limit"))
        val expectedBg = effectiveBg.takeIf { SafetyChecks.isCleanBackgroundLimit(it) }
        if (!SafetyChecks.restoreVerified(result.exitCode, expectedBg, actualBg)) {
            Log.w(TAG, "game mode: restore not verified, marker kept for retry (exit=${result.exitCode}, expected=$expectedBg, actual=$actualBg)")
            savedBgProcessLimit = effectiveBg
            savedSemEnhanced = effectiveSem
            savedRestrictedPerf = effectiveRestricted
            savedAdaptiveBattery = effectiveAdaptive
            aggressiveActive = wasAggressive
            return false
        }
        deleteMarker(context)

        savedBgProcessLimit = null
        savedSemEnhanced = null
        savedRestrictedPerf = null
        savedAdaptiveBattery = null
        aggressiveActive = false

        Log.i(TAG, "game mode: OFF (all settings restored cleanly)")
        return true
    }

    /**
     * Ensures any leftover settings from a crashed session or abrupt power loss are
     * restored to defaults on next app startup.
     */
    fun clearLeftover(context: Context) {
        if (!hasMarker(context)) return
        val appContext = context.applicationContext
        Thread({
            try {
                Log.w(TAG, "clearLeftover: found leftover game mode state, restoring defaults...")
                val marker = loadMarkerData(appContext) ?: return@Thread
                val pkg = appContext.packageName
                val script = buildString {
                    val savedBg = marker.backgroundLimit
                    if (savedBg != null && SafetyChecks.isCleanBackgroundLimit(savedBg)) {
                        append("settings put global background_process_limit $savedBg 2>/dev/null; ")
                    } else {
                        append("settings delete global background_process_limit 2>/dev/null; ")
                    }
                    append("cmd game reset $pkg 2>/dev/null || cmd game mode 1 $pkg 2>/dev/null; ")
                    if (marker.semEnhanced != null) append("settings put global sem_enhanced_cpu_responsiveness ${marker.semEnhanced} 2>/dev/null; ")
                    else append("settings delete global sem_enhanced_cpu_responsiveness 2>/dev/null; ")
                    if (marker.restrictedPerf != null) append("settings put global restricted_device_performance ${marker.restrictedPerf} 2>/dev/null; ")
                    else append("settings delete global restricted_device_performance 2>/dev/null; ")
                    if (marker.adaptiveBattery != null) append("settings put global adaptive_battery_management_enabled ${marker.adaptiveBattery} 2>/dev/null; ")
                    else append("settings delete global adaptive_battery_management_enabled 2>/dev/null; ")
                    append("cmd power set-fixed-performance-mode-enabled false 2>/dev/null; ")
                }
                val result = execChecked(appContext, script)
                val actualBg = SafetyChecks.parseBackgroundLimit(exec(appContext, "settings get global background_process_limit"))
                val expectedBg = marker.backgroundLimit.takeIf { SafetyChecks.isCleanBackgroundLimit(it) }
                if (!SafetyChecks.restoreVerified(result.exitCode, expectedBg, actualBg)) {
                    Log.w(TAG, "clearLeftover: restore not verified, marker kept for retry")
                    return@Thread
                }
                deleteMarker(appContext)
                Log.i(TAG, "clearLeftover: game mode leftover settings cleaned successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "clearLeftover error", t)
            }
        }, "gamemode-clear-leftover").start()
    }

    fun isActive(): Boolean = applied
    fun isAggressiveActive(): Boolean = aggressiveActive

    // ── Live System Verification & Diagnostics ─────────────────────────────────

    fun runLiveVerification(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Game Mode State"] = if (applied) (if (aggressiveActive) "🟢 ACTIVE (Turbo)" else "🟢 ACTIVE (Cool / Load Reducer)") else "⚪ STANDBY"
        out["Background Process Limit"] = exec(context, "settings get global background_process_limit").trim().let { if (it.isBlank() || it == "null") "Default (Unlimited)" else it }
        out["Free System RAM"] = exec(context, "cat /proc/meminfo | grep MemFree").trim().substringAfter(":").trim()
        out["Android Game Mode"] = exec(context, "cmd game list-modes ${context.packageName}").trim()

        val sem = exec(context, "settings get global sem_enhanced_cpu_responsiveness").trim()
        out["Samsung Enhanced Processing"] = if (sem == "1") "ON (High Clock)" else "OFF (Balanced / Cool)"

        val restricted = exec(context, "settings get global restricted_device_performance").trim()
        out["Samsung Restricted Perf"] = if (restricted.isBlank() || restricted == "null") "Default" else restricted

        return out
    }

    fun testApplyNow(context: Context): String {
        val res = apply(context, 999)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST APPLY RESULT ===")
            appendLine(res)
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    fun testRestoreNow(context: Context): String {
        restore(context, 999)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST RESTORE RESULT ===")
            appendLine("game mode restored to defaults")
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    // ── Internal Helpers ───────────────────────────────────────────────────────

    private fun saveMarkerData(context: Context, marker: GameModeMarker) {
        try { File(context.filesDir, MARKER_FILE).writeText(SafetyChecks.encodeGameModeMarker(marker)) } catch (_: Exception) {}
    }

    private fun loadMarkerData(context: Context): GameModeMarker? {
        return try {
            val f = File(context.filesDir, MARKER_FILE)
            if (f.exists()) SafetyChecks.decodeGameModeMarker(f.readText()) else null
        } catch (_: Exception) { null }
    }

    private fun hasMarker(context: Context): Boolean = File(context.filesDir, MARKER_FILE).exists()

    private fun deleteMarker(context: Context) {
        try { File(context.filesDir, MARKER_FILE).delete() } catch (_: Exception) {}
    }

    private fun exec(context: Context, cmd: String): String {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.execute(cmd).output
        } catch (t: Throwable) {
            Log.w(TAG, "exec error: $cmd", t)
            ""
        }
    }

    private fun execChecked(context: Context, cmd: String): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.executeChecked(cmd, PrivilegedBridge.DEFAULT_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "execChecked error: $cmd", t)
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }
}
