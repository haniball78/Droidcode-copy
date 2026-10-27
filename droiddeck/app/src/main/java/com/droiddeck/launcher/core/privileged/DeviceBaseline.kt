package com.droiddeck.launcher.core.privileged

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BaselineDefinition(
    val key: String,
    val readCommand: String?,
    val parse: (String) -> String?,
)

data class BaselineEntry(
    val key: String,
    val readCommand: String?,
    val value: String?,
    val unreadableReason: String?,
) {
    val display: String get() = value ?: "UNREADABLE: ${unreadableReason ?: "no value"}"
}

internal object DeviceBaseline {
    fun definitions(pkg: String): List<BaselineDefinition> = listOf(
        BaselineDefinition("settings.global.background_process_limit", "settings get global background_process_limit", { output -> parseSetting(output) }),
        BaselineDefinition("settings.global.sem_enhanced_cpu_responsiveness", "settings get global sem_enhanced_cpu_responsiveness", { output -> parseSetting(output) }),
        BaselineDefinition("settings.global.restricted_device_performance", "settings get global restricted_device_performance", { output -> parseSetting(output) }),
        BaselineDefinition("settings.global.adaptive_battery_management_enabled", "settings get global adaptive_battery_management_enabled", { output -> parseSetting(output) }),
        BaselineDefinition("settings.global.settings_enable_monitor_phantom_procs", "settings get global settings_enable_monitor_phantom_procs", { output -> parseSetting(output) }),
        BaselineDefinition("settings.global.droiddeck_deadman_expired", "settings get global droiddeck_deadman_expired", { output -> parseSetting(output) }),
        BaselineDefinition("device_config.activity_manager.max_phantom_processes", "device_config get activity_manager max_phantom_processes", { output -> parseSetting(output) }),
        BaselineDefinition("device_config.activity_manager.list", "device_config list activity_manager", { output -> parseRaw(output) }),
        BaselineDefinition("deviceidle.whitelist.$pkg", "dumpsys deviceidle whitelist", { out -> parseWhitelist(out, pkg) }),
        BaselineDefinition("appops.$pkg.RUN_IN_BACKGROUND", "cmd appops get $pkg RUN_IN_BACKGROUND", { output -> parseRaw(output) }),
        BaselineDefinition("deviceidle.force_idle", "dumpsys deviceidle", { output -> parseForceIdle(output) }),
        BaselineDefinition("deviceidle.deep_state", "cmd deviceidle get deep", { output -> parseRaw(output) }),
        BaselineDefinition("game.modes.$pkg", "cmd game list-modes $pkg", { output -> parseRaw(output) }),
        BaselineDefinition("power.fixed_performance", "cmd power get-fixed-performance-mode-enabled", { output -> parseSetting(output) }),
        BaselineDefinition("packages.suspended", "cmd package list packages -s --user 0", { output -> parseRaw(output) }),
        BaselineDefinition("permission.$pkg.WRITE_SECURE_SETTINGS", "dumpsys package $pkg", { out -> parseGranted(out, "android.permission.WRITE_SECURE_SETTINGS") }),
        BaselineDefinition("permission.$pkg.DUMP", "dumpsys package $pkg", { out -> parseGranted(out, "android.permission.DUMP") }),
        BaselineDefinition("permission.$pkg.PACKAGE_USAGE_STATS", "dumpsys package $pkg", { out -> parseGranted(out, "android.permission.PACKAGE_USAGE_STATS") }),
        BaselineDefinition("activity.child_process_restrictions", null, { null }),
    )

    fun parseSetting(output: String): String? {
        val value = output.trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    fun parseRaw(output: String): String? {
        val value = output.trim()
        return value.ifEmpty { null }
    }

    fun parseWhitelist(output: String, pkg: String): String? {
        if (output.isBlank()) return null
        return if (SafetyChecks.isWhitelisted(output, pkg)) "whitelisted" else "not whitelisted"
    }

    fun parseForceIdle(output: String): String? {
        val match = Regex("mForceIdle=(true|false)").find(output) ?: return null
        return match.groupValues[1]
    }

    fun parseGranted(output: String, permission: String): String? {
        if (output.isBlank()) return null
        val grantedSection = output.substringAfter("grantedPermissions:", "")
        if (grantedSection.isEmpty()) return null
        val section = grantedSection.substringBefore("installPermissions:")
        return if (section.contains(permission)) "granted" else "not granted"
    }

    fun entryFor(definition: BaselineDefinition, exitCode: Int, output: String?): BaselineEntry {
        if (definition.readCommand == null) {
            return BaselineEntry(definition.key, null, null, "no public getter for this value")
        }
        if (exitCode != 0 || output == null) {
            return BaselineEntry(definition.key, definition.readCommand, null, "read command failed with exit $exitCode")
        }
        val parsed = runCatching { definition.parse(output) }.getOrNull()
        return if (parsed == null) {
            BaselineEntry(definition.key, definition.readCommand, null, "read returned no usable value")
        } else {
            BaselineEntry(definition.key, definition.readCommand, parsed, null)
        }
    }

    fun fileName(timestampMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
        return "device-baseline-$stamp.md"
    }

    // Defensive collision guard for the single capture only: a second baseline
    // is prevented by the capture flag check, not by this function.
    fun resolveUniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, baseName)
        var index = 2
        while (candidate.exists()) {
            val stem = baseName.substringBeforeLast(".")
            val ext = baseName.substringAfterLast(".", "")
            candidate = File(dir, "$stem-$index.$ext")
            index++
        }
        return candidate
    }

    fun renderMarkdown(pkg: String, capturedAtMs: Long, entries: List<BaselineEntry>, notes: List<String>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date(capturedAtMs))
        return buildString {
            append("# DroidDeck device baseline\n\n")
            append("Captured: $stamp\n\n")
            append("Package: $pkg\n\n")
            append("This file records the device state before DroidDeck applied its own changes. ")
            append("It is created exactly once and never overwritten or recreated.\n\n")
            append("## Values\n\n")
            append("| Key | Value before | Read command |\n")
            append("| --- | --- | --- |\n")
            for (entry in entries) {
                val value = entry.display.replace("|", "\\|").replace("\n", " ")
                val command = (entry.readCommand ?: "none").replace("|", "\\|")
                append("| ${entry.key} | $value | `$command` |\n")
            }
            if (notes.isNotEmpty()) {
                append("\n## Notes\n\n")
                for (note in notes) append("- $note\n")
            }
        }
    }
}
