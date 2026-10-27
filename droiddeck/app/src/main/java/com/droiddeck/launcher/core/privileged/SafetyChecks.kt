package com.droiddeck.launcher.core.privileged

data class GameModeMarker(
    val backgroundLimit: String?,
    val semEnhanced: String?,
    val restrictedPerf: String?,
    val adaptiveBattery: String?,
)

internal object SafetyChecks {
    fun parseBackgroundLimit(output: String): String? {
        val value = output.trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    fun isCleanBackgroundLimit(value: String?): Boolean {
        return value != null && value != "2" && value != "4"
    }

    fun isValidBackgroundLimitValue(value: String?): Boolean {
        return value == null || value.matches(Regex("^-?[0-9]+$"))
    }

    fun isValidSettingValue(key: String, value: String?): Boolean {
        return when (key) {
            "background_process_limit" -> isValidBackgroundLimitValue(value)
            "sem_enhanced_cpu_responsiveness", "adaptive_battery_management_enabled" -> value == null || value == "0" || value == "1"
            "restricted_device_performance" -> value == null || value.matches(Regex("^[0-9]+(,[0-9]+)*$"))
            else -> value == null || value.isNotBlank()
        }
    }

    fun restoreVerified(exitCode: Int, expected: String?, actual: String?): Boolean {
        return exitCode == 0 && expected == actual
    }

    fun isWhitelisted(deviceIdleOutput: String, pkg: String): Boolean {
        return deviceIdleOutput.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == pkg || trimmed.endsWith(" $pkg") || trimmed.contains("$pkg,") || trimmed.contains("$pkg ")
        }
    }

    fun isBackgroundAllowed(appOpsOutput: String): Boolean {
        return appOpsOutput.contains("allow", ignoreCase = true)
    }

    fun encodeGameModeMarker(marker: GameModeMarker): String {
        return buildString {
            appendLine("bg=${marker.backgroundLimit ?: ""}")
            appendLine("sem=${marker.semEnhanced ?: ""}")
            appendLine("restricted=${marker.restrictedPerf ?: ""}")
            appendLine("adaptive=${marker.adaptiveBattery ?: ""}")
        }.trimEnd()
    }

    fun decodeGameModeMarker(text: String): GameModeMarker {
        val trimmed = text.trim()
        if (!trimmed.contains("=")) {
            return GameModeMarker(
                backgroundLimit = trimmed.takeIf { it.isNotEmpty() },
                semEnhanced = null,
                restrictedPerf = null,
                adaptiveBattery = null,
            )
        }
        val map = trimmed.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        fun value(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
        return GameModeMarker(
            backgroundLimit = value("bg"),
            semEnhanced = value("sem"),
            restrictedPerf = value("restricted"),
            adaptiveBattery = value("adaptive"),
        )
    }

    fun isDozeMarker(text: String): Boolean {
        return text.lineSequence().any { it.trim() == "#DOZE" }
    }

    fun encodeFreezerMarker(packages: List<String>, dozeForced: Boolean): String {
        return buildString {
            if (dozeForced) appendLine("#DOZE")
            packages.forEach { appendLine(it) }
        }.trimEnd()
    }

    fun decodeFreezerMarker(text: String): Pair<List<String>, Boolean> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.filter { it != "#DOZE" } to lines.any { it == "#DOZE" }
    }
}

data class ResolvedOriginal(
    val original: String?,
    val leftoverDetected: Boolean,
    val valid: Boolean = true,
)

internal object Preflight {
    fun resolveBackgroundLimitOriginal(rawOutput: String): ResolvedOriginal {
        val parsed = SafetyChecks.parseBackgroundLimit(rawOutput)
        if (parsed != null && !SafetyChecks.isValidBackgroundLimitValue(parsed)) {
            return ResolvedOriginal(null, false, false)
        }
        return if (parsed != null && !SafetyChecks.isCleanBackgroundLimit(parsed)) {
            ResolvedOriginal(null, true)
        } else {
            ResolvedOriginal(parsed, false)
        }
    }

    fun resolveSettingOriginal(rawOutput: String): String? {
        return SafetyChecks.parseBackgroundLimit(rawOutput)
    }
}
