package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.privileged.AtomicStateFile
import java.io.File

enum class SuspendKind {
    USER,
    SAFETY,
}

data class SuspendedSession(
    val generation: Int,
    val game: String,
    val reason: SuspendKind,
    val timeMs: Long,
    val tweaksEnded: Boolean,
    val sessionId: String?,
)

internal object SuspendCodec {
    fun encode(session: SuspendedSession): String {
        val sessionId = if (session.sessionId == null) "null" else "\"${escape(session.sessionId)}\""
        return "{\"version\":1,\"generation\":${session.generation},\"game\":\"${escape(session.game)}\"," +
            "\"reason\":\"${session.reason}\",\"timeMs\":${session.timeMs}," +
            "\"tweaksEnded\":${session.tweaksEnded},\"sessionId\":$sessionId}"
    }

    fun decode(text: String): SuspendedSession? {
        return try {
            if (extractLong(text, "version") != 1L) return null
            val generation = extractLong(text, "generation")?.toInt() ?: return null
            val game = extractString(text, "game") ?: return null
            val reason = when (extractString(text, "reason")) {
                "USER" -> SuspendKind.USER
                "SAFETY" -> SuspendKind.SAFETY
                else -> return null
            }
            val timeMs = extractLong(text, "timeMs") ?: return null
            val tweaksEnded = extractBool(text, "tweaksEnded") ?: return null
            SuspendedSession(generation, game, reason, timeMs, tweaksEnded, extractStringOrNull(text, "sessionId"))
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(value: String): String {
        val out = StringBuilder()
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
                }
            }
        }
        return out.toString()
    }

    private fun fieldValue(text: String, key: String): String? {
        val start = text.indexOf("\"$key\"")
        if (start < 0) return null
        val colon = text.indexOf(':', start + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        if (text[i] == '"') {
            val out = StringBuilder()
            i++
            while (i < text.length) {
                val ch = text[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= text.length) return null
                        when (val esc = text[i++]) {
                            '\\' -> out.append('\\')
                            '"' -> out.append('"')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > text.length) return null
                                out.append(text.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> out.append(ch)
                }
            }
            return null
        }
        var end = i
        while (end < text.length && text[end] != ',' && text[end] != '}') end++
        return text.substring(i, end).trim()
    }

    private fun extractString(text: String, key: String): String? {
        val raw = fieldValue(text, key) ?: return null
        return raw
    }

    private fun extractStringOrNull(text: String, key: String): String? {
        val start = text.indexOf("\"$key\"") ?: return null
        val colon = text.indexOf(':', start + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (text.startsWith("null", i)) return null
        return fieldValue(text, key)
    }

    private fun extractLong(text: String, key: String): Long? {
        return fieldValue(text, key)?.toLongOrNull()
    }

    private fun extractBool(text: String, key: String): Boolean? {
        return when (fieldValue(text, key)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

object SuspendStore {
    private const val FILE_NAME = "suspended-session.json"

    fun save(dir: File, session: SuspendedSession): Boolean {
        if (!dir.exists()) dir.mkdirs()
        return AtomicStateFile.save(File(dir, FILE_NAME), SuspendCodec.encode(session))
    }

    fun load(dir: File): SuspendedSession? {
        val file = File(dir, FILE_NAME)
        if (!file.exists() && !AtomicStateFile.bakFile(file).exists() && !AtomicStateFile.tmpFile(file).exists()) {
            return null
        }
        return AtomicStateFile.readRecovered(file, SuspendCodec::decode)
    }

    fun clear(dir: File): Boolean {
        return AtomicStateFile.delete(File(dir, FILE_NAME))
    }
}

enum class ResumeBlock {
    NONE,
    EXPLICIT_REQUIRED,
    BRIDGE_UNAVAILABLE,
    BATTERY_LOW_SAFETY,
    THERMAL_SEVERE_SAFETY,
}

data class ResumeCheck(
    val allowed: Boolean,
    val block: ResumeBlock,
)

internal object SuspendPolicy {
    const val RESUME_WITHOUT_OPTIMIZATIONS_MIN_BATTERY = 20
    const val TWEAKS_REAPPLY_MAX_BATTERY = 10
    const val THERMAL_SEVERE = 3

    fun canResumeWithoutOptimizations(levelPercent: Int?, charging: Boolean): Boolean {
        return charging || (levelPercent != null && levelPercent >= RESUME_WITHOUT_OPTIMIZATIONS_MIN_BATTERY)
    }

    fun checkResume(
        kind: SuspendKind,
        wantsOptimizations: Boolean,
        bridgeOk: Boolean,
        batteryLevel: Int?,
        charging: Boolean,
        thermalStatus: Int,
        explicitResume: Boolean,
    ): ResumeCheck {
        if (kind == SuspendKind.SAFETY && !explicitResume) {
            return ResumeCheck(false, ResumeBlock.EXPLICIT_REQUIRED)
        }
        if (kind == SuspendKind.SAFETY && !wantsOptimizations &&
            !canResumeWithoutOptimizations(batteryLevel, charging)
        ) {
            return ResumeCheck(false, ResumeBlock.BATTERY_LOW_SAFETY)
        }
        if (wantsOptimizations && !bridgeOk) {
            return ResumeCheck(false, ResumeBlock.BRIDGE_UNAVAILABLE)
        }
        if (kind == SuspendKind.SAFETY && wantsOptimizations) {
            if (batteryLevel != null && !charging && batteryLevel <= TWEAKS_REAPPLY_MAX_BATTERY) {
                return ResumeCheck(false, ResumeBlock.BATTERY_LOW_SAFETY)
            }
            if (thermalStatus != SafetyPolicy.THERMAL_UNKNOWN && thermalStatus >= THERMAL_SEVERE) {
                return ResumeCheck(false, ResumeBlock.THERMAL_SEVERE_SAFETY)
            }
        }
        return ResumeCheck(true, ResumeBlock.NONE)
    }

    fun shouldRestoreOnLeave(
        hiddenMs: Long,
        timeoutMs: Long,
        hidden: Boolean,
        suspended: Boolean,
        tweaksActive: Boolean,
    ): Boolean {
        return hidden && !suspended && tweaksActive && hiddenMs >= timeoutMs
    }

    fun shouldReapplyOnReturn(
        leaveRestored: Boolean,
        tweaksEndedForSafety: Boolean,
        bridgeOk: Boolean,
        batteryLevel: Int?,
        charging: Boolean,
        thermalStatus: Int,
    ): Boolean {
        if (!leaveRestored || tweaksEndedForSafety || !bridgeOk) return false
        if (thermalStatus != SafetyPolicy.THERMAL_UNKNOWN && thermalStatus >= THERMAL_SEVERE) return false
        if (batteryLevel != null && !charging && batteryLevel <= TWEAKS_REAPPLY_MAX_BATTERY) return false
        return true
    }
}
