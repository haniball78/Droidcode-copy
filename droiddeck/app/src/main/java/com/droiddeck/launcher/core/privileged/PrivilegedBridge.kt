package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "DroidDeckPrivBridge"

enum class PrivilegedMode {
    EMBEDDED_DAEMON,
    SHIZUKU_APP,
    WIRELESS_ADB,
    NONE,
}

data class PrivilegedResult(
    val exitCode: Int,
    val output: String,
    val mode: PrivilegedMode,
)

/** Rich status snapshot exposed to the UI panel via StateFlow. */
data class PrivilegedStatus(
    val mode: PrivilegedMode,
    val daemonUid: Int = -1,
    val daemonPid: Int = -1,
    val shizukuVersion: Int = -1,
    val phantomDisabled: Boolean = false,
    val lastError: String? = null,
) {
    val isActive get() = mode != PrivilegedMode.NONE
    val modeLabel get() = when (mode) {
        PrivilegedMode.EMBEDDED_DAEMON -> "Embedded Daemon (UID $daemonUid)"
        PrivilegedMode.SHIZUKU_APP     -> "Shizuku v$shizukuVersion"
        PrivilegedMode.WIRELESS_ADB    -> "Wireless ADB"
        PrivilegedMode.NONE            -> "Not Active"
    }
    val statusIcon get() = if (isActive) "✅" else "⚠️"
    val shortLabel get() = when (mode) {
        PrivilegedMode.EMBEDDED_DAEMON -> "Daemon"
        PrivilegedMode.SHIZUKU_APP     -> "Shizuku"
        PrivilegedMode.WIRELESS_ADB    -> "ADB"
        PrivilegedMode.NONE            -> "None"
    }
}

internal object BoostUncertainty {
    fun shouldRemoveItem(boosted: Boolean, verifyExitCode: Int, verifyOutput: String?): Boolean {
        if (boosted) return false
        return verifyExitCode == 0 && verifyOutput?.trim() == "false"
    }
}

internal object FixedPerformanceState {
    fun parse(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "true" -> "true"
            "false", "null", "" -> "false"
            else -> null
        }
    }

    fun item(original: String, generation: Int): JournalItem {
        return JournalItem(
            key = "fixed-performance",
            original = original,
            restoreCmd = "cmd power set-fixed-performance-mode-enabled $original 2>/dev/null",
            verifyCmd = "cmd power get-fixed-performance-mode-enabled",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = generation,
        )
    }
}

object PrivilegedBridge {
    const val DEFAULT_TIMEOUT_MS = 4000
    private const val TOKEN_FILE = "daemon-session-token.txt"

    private val _status = MutableStateFlow(PrivilegedStatus(PrivilegedMode.NONE))
    val statusFlow: StateFlow<PrivilegedStatus> = _status.asStateFlow()

    @Volatile private var sessionToken: String? = null

    fun ensureSessionToken(context: Context): String {
        sessionToken?.let { return it }
        val file = File(context.applicationContext.noBackupFilesDir, TOKEN_FILE)
        val stored = runCatching { file.readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
        if (stored != null) {
            sessionToken = stored
            return stored
        }
        val token = UUID.randomUUID().toString()
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(token)
            tmp.renameTo(file)
        }
        sessionToken = token
        return token
    }

    fun rotateSessionToken(context: Context): String {
        val token = UUID.randomUUID().toString()
        sessionToken = token
        runCatching {
            val file = File(context.applicationContext.noBackupFilesDir, TOKEN_FILE)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(token)
            tmp.renameTo(file)
        }
        return token
    }

    fun clearSessionToken(context: Context) {
        sessionToken = null
        runCatching { File(context.applicationContext.noBackupFilesDir, TOKEN_FILE).delete() }
    }

    fun getActiveMode(): PrivilegedMode = _status.value.mode
    fun isPrivilegedAvailable(): Boolean = _status.value.isActive

    fun refresh(): PrivilegedStatus {
        val previous = _status.value
        val (embMode, daemonUid, daemonPid) = probeEmbedded()
        if (embMode == PrivilegedMode.EMBEDDED_DAEMON) {
            val s = PrivilegedStatus(embMode, daemonUid, daemonPid)
            _status.value = s
            if (!previous.isActive) DiagnosticsLog.log("adb.bridge", "restored", fields = mapOf("mode" to s.mode.name))
            return s
        }
        val (shizMode, shizVer) = probeShizuku()
        if (shizMode == PrivilegedMode.SHIZUKU_APP) {
            val s = PrivilegedStatus(shizMode, shizukuVersion = shizVer)
            _status.value = s
            if (!previous.isActive) DiagnosticsLog.log("adb.bridge", "restored", fields = mapOf("mode" to s.mode.name))
            return s
        }
        val s = PrivilegedStatus(PrivilegedMode.NONE)
        _status.value = s
        if (previous.isActive) DiagnosticsLog.log("adb.bridge", "lost", fields = mapOf("previous_mode" to previous.mode.name))
        return s
    }

    fun tryAutoStart(context: Context) {
        if (_status.value.isActive) return
        Thread {
            try {
                if (com.droiddeck.launcher.core.WirelessAdbFix.startPrivilegedDaemonWithSavedPairing(context)) {
                    Thread.sleep(600)
                    refresh()
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun execute(command: String): PrivilegedResult {
        return executeChecked(command, DEFAULT_TIMEOUT_MS)
    }

    fun executeChecked(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PrivilegedResult {
        if (_status.value.mode == PrivilegedMode.NONE) {
            refresh()
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val result = when (_status.value.mode) {
            PrivilegedMode.EMBEDDED_DAEMON -> executeViaEmbeddedServer(command, timeoutMs)
            PrivilegedMode.SHIZUKU_APP     -> executeViaShizuku(command, timeoutMs)
            else -> PrivilegedResult(-1, "No privileged backend active.", PrivilegedMode.NONE)
        }
        DiagnosticsLog.log(
            "privileged.exec", if (result.exitCode == 0) "succeeded" else "failed",
            fields = mapOf(
                "kind" to classifyCommand(command),
                "exit" to result.exitCode.toString(),
                "duration_ms" to (android.os.SystemClock.elapsedRealtime() - started).toString(),
            ),
        )
        return result
    }

    private fun classifyCommand(command: String): String = when {
        command.startsWith("dumpsys deviceidle whitelist") -> "deviceidle-whitelist"
        command.startsWith("cmd appops") -> "appops"
        command.startsWith("cmd power") -> "power"
        command.startsWith("device_config") -> "device-config"
        command.startsWith("settings ") -> "settings"
        command.startsWith("cmd game") -> "game-mode"
        command.startsWith("cmd deviceidle") || command.startsWith("cmd package suspend") -> "app-freezer"
        command.startsWith("pm grant") -> "grant"
        else -> "shell"
    }

    fun ensureBackgroundProtection(context: Context): Boolean {
        val pkg = context.packageName
        val whitelistOut = executeChecked("dumpsys deviceidle whitelist").output
        if (!SafetyChecks.isWhitelisted(whitelistOut, pkg)) {
            val applied = executeChecked("dumpsys deviceidle whitelist +$pkg").exitCode == 0
            if (!applied) return false
        }
        val appOpsOut = executeChecked("cmd appops get $pkg RUN_IN_BACKGROUND").output
        if (!SafetyChecks.isBackgroundAllowed(appOpsOut)) {
            val applied = executeChecked("cmd appops set $pkg RUN_IN_BACKGROUND allow").exitCode == 0
            if (!applied) return false
        }
        val verifiedWhitelist = SafetyChecks.isWhitelisted(
            executeChecked("dumpsys deviceidle whitelist").output, pkg
        )
        val verifiedAppOps = SafetyChecks.isBackgroundAllowed(
            executeChecked("cmd appops get $pkg RUN_IN_BACKGROUND").output
        )
        return verifiedWhitelist && verifiedAppOps
    }

    fun disablePhantomProcesses(): Boolean {
        return disablePhantomProcessesInternal()
    }

    fun disablePhantomProcesses(context: Context): Boolean {
        ensureSessionToken(context)
        val result = disablePhantomProcessesInternal()
        DiagnosticsLog.log(context, "phantom.apply", if (result) "succeeded" else "failed", fields = mapOf("action" to "disable"))
        return result
    }

    private fun disablePhantomProcessesInternal(): Boolean {
        val cmd = "device_config set_sync_disabled_for_tests persistent && " +
            "device_config put activity_manager max_phantom_processes 2147483647 && " +
            "settings put global settings_enable_monitor_phantom_procs false && " +
            "cmd activity disable-child-process-restrictions 2>/dev/null || true"
        return if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON)
            runEmbeddedAction("DISABLE_PHANTOM")
        else execute(cmd).exitCode == 0
    }

    fun armDeadMan(context: Context): Boolean {
        ensureSessionToken(context)
        val armed = deadManAction("ARM")
        DiagnosticsLog.log(context, "safety.deadman", if (armed) "armed" else "arm-failed")
        return armed
    }

    fun beatDeadMan(): Boolean = deadManAction("BEAT")

    fun disarmDeadMan(): Boolean = deadManAction("DISARM")

    private fun deadManAction(action: String): Boolean {
        if (_status.value.mode != PrivilegedMode.EMBEDDED_DAEMON) return false
        val result = runEmbeddedAction(action)
        if (action == "BEAT" && !result) DiagnosticsLog.log("safety.deadman", "beat-failed")
        if (action == "DISARM") DiagnosticsLog.log("safety.deadman", if (result) "disarm" else "disarm-failed")
        return result
    }

    fun boostAndProtectSession(targetPid: Int): Boolean {
        return boostAndProtectSession(targetPid, aggressive = false)
    }

    fun boostAndProtectSession(context: Context, targetPid: Int): Boolean {
        if (!com.droiddeck.launcher.session.SessionPrefs.advancedMasterEnabled(context)) return false
        if (!com.droiddeck.launcher.session.SessionPrefs.gameModeEngine(context)) return false
        val aggressive = com.droiddeck.launcher.session.SessionPrefs.gameModeAggressive(context)
        if (aggressive) {
            if (!Tweaks.hasKey(context, "fixed-performance")) {
                val read = executeChecked("cmd power get-fixed-performance-mode-enabled")
                val original = if (read.exitCode == 0) FixedPerformanceState.parse(read.output) else null
                if (original == null) return false
                if (!Tweaks.recordItem(context, FixedPerformanceState.item(original, 0))) return false
            }
            val boosted = boostAndProtectSession(targetPid, aggressive = true)
            if (!boosted) {
                val state = runCatching { executeChecked("cmd power get-fixed-performance-mode-enabled") }.getOrNull()
                if (BoostUncertainty.shouldRemoveItem(boosted, state?.exitCode ?: -1, state?.output)) {
                    Tweaks.removeKey(context, "fixed-performance")
                }
            }
            return boosted
        }
        return boostAndProtectSession(targetPid, aggressive = false)
    }

    fun boostAndProtectSession(targetPid: Int, aggressive: Boolean): Boolean {
        if (targetPid <= 0) return false
        val nice = if (aggressive) -20 else -10
        return if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON) {
            val action = if (aggressive) "BOOST_PERFORMANCE_TURBO:$targetPid" else "BOOST_PERFORMANCE:$targetPid"
            runEmbeddedAction(action)
        } else {
            val script = buildString {
                append("echo -1000 > /proc/$targetPid/oom_score_adj 2>/dev/null || true; ")
                append("renice -n $nice -p $targetPid 2>/dev/null || true; ")
                if (aggressive) append("cmd power set-fixed-performance-mode-enabled true 2>/dev/null || true")
            }
            execute(script).exitCode == 0
        }
    }

    fun grantDroidDeckPermissions(context: Context): Boolean {
        ensureSessionToken(context)
        val pkg = context.packageName
        val result = if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON) {
            runEmbeddedAction("GRANT_PERMS:$pkg")
        } else {
            execute(
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS && " +
                "pm grant $pkg android.permission.DUMP && " +
                "pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
            ).exitCode == 0
        }
        DiagnosticsLog.log(context, "device.change", if (result) "grants-succeeded" else "grants-failed")
        return result
    }

    fun getStarterAdbCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        val token = ensureSessionToken(context)
        return "nohup app_process -Djava.class.path=\"$apkPath\" /system/bin " +
            "--nice-name=droiddeck_privileged " +
            "com.droiddeck.launcher.core.privileged.DroidDeckPrivilegedServer $token > /dev/null 2>&1 &"
    }

    // ── Probing ──────────────────────────────────────────────────────────

    private data class EmbeddedProbe(val mode: PrivilegedMode, val uid: Int, val pid: Int)

    private fun probeEmbedded(): EmbeddedProbe {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 400)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.println("PING")
                val response = reader.readLine() ?: return EmbeddedProbe(PrivilegedMode.NONE, -1, -1)
                if (response.startsWith("PONG")) {
                    val uid = response.substringAfter("uid=", "-1").substringBefore(" ").toIntOrNull() ?: -1
                    val pid = response.substringAfter("pid=", "-1").toIntOrNull() ?: -1
                    EmbeddedProbe(PrivilegedMode.EMBEDDED_DAEMON, uid, pid)
                } else EmbeddedProbe(PrivilegedMode.NONE, -1, -1)
            }
        } catch (_: Exception) { EmbeddedProbe(PrivilegedMode.NONE, -1, -1) }
    }

    private data class ShizukuProbe(val mode: PrivilegedMode, val version: Int)

    private fun probeShizuku(): ShizukuProbe {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val alive = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!alive) return ShizukuProbe(PrivilegedMode.NONE, -1)
            val granted = (cls.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1) == 0
            if (!granted) return ShizukuProbe(PrivilegedMode.NONE, -1)
            val ver = runCatching { cls.getMethod("getVersion").invoke(null) as? Int ?: -1 }.getOrDefault(-1)
            ShizukuProbe(PrivilegedMode.SHIZUKU_APP, ver)
        } catch (_: Exception) { ShizukuProbe(PrivilegedMode.NONE, -1) }
    }

    // ── Execution Backends ────────────────────────────────────────────

    private fun executeViaEmbeddedServer(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PrivilegedResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 3000)
                socket.soTimeout = timeoutMs
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                sessionToken?.let { writer.println("AUTH:$it") }
                val encoded = Base64.encodeToString(command.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                writer.println("EXEC:$encoded")
                val resp = reader.readLine() ?: return PrivilegedResult(-1, "No response", PrivilegedMode.EMBEDDED_DAEMON)
                if (resp == "UNAUTHORIZED") DiagnosticsLog.log("adb.bridge", "auth-failed", fields = mapOf("action" to "EXEC"))
                if (resp.startsWith("EXIT:")) {
                    val parts = resp.split(":")
                    val exitCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    val outEncoded = parts.getOrNull(2) ?: ""
                    val out = String(Base64.decode(outEncoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                    PrivilegedResult(exitCode, out, PrivilegedMode.EMBEDDED_DAEMON)
                } else PrivilegedResult(-1, resp, PrivilegedMode.EMBEDDED_DAEMON)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeViaEmbeddedServer", e)
            PrivilegedResult(-1, "Daemon error: ${e.message}", PrivilegedMode.EMBEDDED_DAEMON)
        }
    }

    private fun executeViaShizuku(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PrivilegedResult {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as java.lang.Process
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                return PrivilegedResult(-1, "Shizuku timeout", PrivilegedMode.SHIZUKU_APP)
            }
            val output = process.inputStream.bufferedReader().readText()
            PrivilegedResult(process.exitValue(), output, PrivilegedMode.SHIZUKU_APP)
        } catch (e: Exception) {
            Log.e(TAG, "executeViaShizuku", e)
            PrivilegedResult(-1, "Shizuku error: ${e.message}", PrivilegedMode.SHIZUKU_APP)
        }
    }

    private fun runEmbeddedAction(action: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 2000)
                socket.soTimeout = DEFAULT_TIMEOUT_MS
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                sessionToken?.let { writer.println("AUTH:$it") }
                writer.println(action)
                val reply = reader.readLine()
                if (reply == "UNAUTHORIZED") DiagnosticsLog.log("adb.bridge", "auth-failed", fields = mapOf("action" to action))
                reply == "OK"
            }
        } catch (_: Exception) { false }
    }
}
