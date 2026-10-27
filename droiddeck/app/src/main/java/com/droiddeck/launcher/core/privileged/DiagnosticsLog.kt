package com.droiddeck.launcher.core.privileged

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.session.SessionFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticsSummary(
    val lines: Long,
    val lastWallMs: Long,
    val sizeBytes: Long,
)

internal object DiagnosticsCore {
    const val MAX_BYTES = 2L * 1024 * 1024
    const val MAX_ROTATED = 3
    const val SAMPLE_WINDOW_MS = 60_000L

    private val AUTH_LABEL = Regex("(?i)(auth:|token=|pairing[_-]?code=|password=|passwd=)[^\\s\",}]+")
    private val PAIRING_CODE_JSON = Regex("(?i)(pairing[_-]?code[\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,}]+")
    private val TS_FIELD = Regex("\"ts\":(\\d+)")

    fun shouldSample(nowMs: Long, lastMs: Long, force: Boolean): Boolean =
        force || lastMs < 0 || nowMs - lastMs >= SAMPLE_WINDOW_MS

    fun scrub(line: String): String {
        val redacted = try {
            LogRedactor.redactForShare(line)
        } catch (_: Throwable) {
            "<redaction failed; line withheld>"
        }
        val withoutAuth = AUTH_LABEL.replace(redacted) { "${it.groupValues[1]}<redacted>" }
        return PAIRING_CODE_JSON.replace(withoutAuth) { "${it.groupValues[1]}<redacted>" }
    }

    fun jsonEscape(value: String): String {
        val out = StringBuilder()
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append(String.format(Locale.US, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.toString()
    }

    fun render(wallMs: Long, elapsedMs: Long, category: String, event: String, generation: Int, fields: Map<String, String>): String {
        val out = StringBuilder()
        out.append("{\"ts\":").append(wallMs)
        out.append(",\"elapsed_ms\":").append(elapsedMs)
        out.append(",\"category\":\"").append(jsonEscape(category)).append("\"")
        out.append(",\"event\":\"").append(jsonEscape(event)).append("\"")
        out.append(",\"gen\":").append(generation)
        for ((key, value) in fields) {
            out.append(",\"").append(jsonEscape(key)).append("\":\"").append(jsonEscape(value)).append("\"")
        }
        out.append("}")
        return scrub(out.toString())
    }

    fun rotatedName(base: String, index: Int): String {
        val dot = base.lastIndexOf('.')
        return if (dot < 0) "$base.$index" else base.substring(0, dot) + ".$index" + base.substring(dot)
    }

    fun rotate(dir: File, base: String, maxBytes: Long, maxRotated: Int): Boolean {
        val current = File(dir, base)
        if (!current.isFile || current.length() < maxBytes) return true
        for (index in maxRotated downTo 1) {
            val older = File(dir, rotatedName(base, index))
            val newer = File(dir, rotatedName(base, index + 1))
            if (index == maxRotated) {
                runCatching { if (older.isFile) older.delete() }
            } else if (older.isFile) {
                runCatching { older.renameTo(newer) }
            }
        }
        return runCatching { current.renameTo(File(dir, rotatedName(base, 1))) }.getOrDefault(false)
    }

    fun append(dir: File, base: String, line: String, maxBytes: Long = MAX_BYTES, maxRotated: Int = MAX_ROTATED, enabled: Boolean = true): Boolean {
        if (!enabled) return true
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            rotate(dir, base, maxBytes, maxRotated)
            File(dir, base).appendText(line + "\n", Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteCurrentFile(dir: File, base: String): Boolean {
        val file = File(dir, base)
        if (!file.isFile) return true
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun summary(dir: File, base: String): DiagnosticsSummary? {
        val file = File(dir, base)
        if (!file.isFile) return null
        var lines = 0L
        var lastWallMs = 0L
        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines++
                    TS_FIELD.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { lastWallMs = it }
                    line = reader.readLine()
                }
            }
            DiagnosticsSummary(lines, lastWallMs, file.length())
        } catch (_: Exception) {
            null
        }
    }
}

object DiagnosticsLog {
    const val FILE_NAME = "droiddeck-diagnostics.jsonl"
    private const val TAG = "DiagnosticsLog"
    private const val PREFS = "droiddeck-diagnostics"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    fun deleteCurrentFile(context: Context): Boolean {
        return runCatching {
            DiagnosticsCore.deleteCurrentFile(SessionFiles.logDirectory(context), FILE_NAME)
        }.getOrDefault(false)
    }

    @Volatile
    private var attached: Context? = null

    @Volatile
    private var lastSampleMs = -1L
    private val lock = Any()

    fun attach(context: Context) {
        runCatching { attached = context.applicationContext }
    }

    fun log(context: Context?, category: String, event: String, generation: Int = 0, fields: Map<String, String> = emptyMap()) {
        val ctx = context ?: attached ?: return
        if (!isEnabled(ctx)) return
        try {
            val dir = SessionFiles.logDirectory(ctx)
            val line = DiagnosticsCore.render(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                category,
                event,
                generation,
                fields,
            )
            synchronized(lock) {
                DiagnosticsCore.append(dir, FILE_NAME, line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "diagnostics write failed", t)
        }
    }

    fun log(category: String, event: String, generation: Int = 0, fields: Map<String, String> = emptyMap()) {
        log(null, category, event, generation, fields)
    }

    fun sample(
        context: Context?,
        batteryPercent: Int,
        charging: Boolean,
        thermalStatus: Int,
        bridgeStatus: String,
        tweaksActive: Boolean,
        force: Boolean = false,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val ctx = context ?: attached
        if (ctx != null && !isEnabled(ctx)) return
        if (!DiagnosticsCore.shouldSample(nowMs, lastSampleMs, force)) return
        lastSampleMs = nowMs
        log(
            context,
            "safety.monitor",
            "sample",
            fields = mapOf(
                "battery" to batteryPercent.toString(),
                "charging" to charging.toString(),
                "thermal" to thermalStatus.toString(),
                "bridge" to bridgeStatus,
                "tweaks_active" to tweaksActive.toString(),
            ),
        )
    }

    fun file(context: Context): File? {
        return try {
            File(SessionFiles.logDirectory(context), FILE_NAME).takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    fun summary(context: Context): DiagnosticsSummary? {
        return try {
            DiagnosticsCore.summary(SessionFiles.logDirectory(context), FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    fun shareIntent(context: Context): Intent? {
        return try {
            val src = file(context) ?: return null
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val copy = File(shareDir, FILE_NAME)
            copy.bufferedWriter(Charsets.UTF_8).use { writer ->
                LogRedactor.scrubForShare(src, writer)
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", copy)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, FILE_NAME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(FILE_NAME, uri)
            Intent.createChooser(send, FILE_NAME).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatTime(wallMs: Long): String {
        return if (wallMs <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(wallMs))
    }
}
