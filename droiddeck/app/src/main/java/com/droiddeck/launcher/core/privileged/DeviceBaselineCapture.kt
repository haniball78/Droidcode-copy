package com.droiddeck.launcher.core.privileged

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.droiddeck.launcher.core.WirelessAdbFix
import com.flyfishxu.kadb.Kadb
import java.io.File

data class BaselineInfo(
    val captured: Boolean,
    val fileName: String?,
    val capturedAtMs: Long,
)

object DeviceBaselineCapture {
    // Baseline is created exactly once: every capture entry point checks the flag
    // under this object's lock and save() checks it again, so a second capture
    // cannot slip through concurrently; a failed attempt leaves the flag unset.
    private const val TAG = "DeviceBaseline"
    private const val PREFS = "device-baseline"
    private const val KEY_CAPTURED = "baseline_captured"
    private const val KEY_FILE = "baseline_file"
    private const val KEY_TIME = "baseline_time_ms"
    private const val DIR_NAME = "device-baseline"

    fun info(context: Context): BaselineInfo {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BaselineInfo(
            captured = prefs.getBoolean(KEY_CAPTURED, false),
            fileName = prefs.getString(KEY_FILE, null),
            capturedAtMs = prefs.getLong(KEY_TIME, 0L),
        )
    }

    fun baselineFile(context: Context): File? {
        val name = info(context).fileName ?: return null
        return primaryDir(context)?.let { File(it, name) }?.takeIf { it.isFile }
            ?: context.getExternalFilesDir(null)?.let { File(File(it, DIR_NAME), name) }?.takeIf { it.isFile }
    }

    fun shareIntent(context: Context, file: File): Intent? {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val copy = File(shareDir, file.name)
        runCatching { file.copyTo(copy, overwrite = true) }.onFailure { return null }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, file.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Synchronized
    fun captureViaBridge(context: Context): File? {
        if (info(context).captured) return baselineFile(context)
        val pkg = context.packageName
        val entries = DeviceBaseline.definitions(pkg).map { definition ->
            val command = definition.readCommand
            if (command == null) {
                DeviceBaseline.entryFor(definition, -1, null)
            } else {
                val result = runCatching { PrivilegedBridge.executeChecked(command) }.getOrNull()
                DeviceBaseline.entryFor(definition, result?.exitCode ?: -1, result?.output)
            }
        }
        return save(context, entries, listOf("Captured through the privileged bridge."))
    }

    @Synchronized
    fun captureViaAdb(context: Context, host: String, port: Int): File? {
        if (info(context).captured) return baselineFile(context)
        val pkg = context.packageName
        val entries = try {
            Kadb.create(host, port).use { adb ->
                DeviceBaseline.definitions(pkg).map { definition ->
                    val command = definition.readCommand
                    if (command == null) {
                        DeviceBaseline.entryFor(definition, -1, null)
                    } else {
                        val result = runCatching { adb.shell(command) }.getOrNull()
                        DeviceBaseline.entryFor(definition, result?.exitCode ?: -1, result?.allOutput)
                    }
                }
            }
        } catch (_: Exception) {
            DiagnosticsLog.log(context, "baseline.capture", "failed", fields = mapOf("reason" to "adb-error"))
            return null
        }
        return save(context, entries, listOf("Captured through wireless ADB before the privileged daemon started."))
    }

    @Synchronized
    fun ensureViaSavedPairing(context: Context): File? {
        val appContext = context.applicationContext
        if (info(appContext).captured) return baselineFile(appContext)
        if (PrivilegedBridge.isPrivilegedAvailable()) return captureViaBridge(appContext)
        if (!WirelessAdbFix.isPaired(appContext)) return null
        val address = WirelessAdbFix.savedConnectionAddress(appContext)
        val host = address?.substringBefore(":")?.takeIf { it.isNotEmpty() } ?: WirelessAdbFix.LOOPBACK
        val savedPort = address?.substringAfter(":", "")?.toIntOrNull()
        val port = WirelessAdbFix.localConnectPort(appContext) ?: savedPort ?: return null
        return runCatching { captureViaAdb(appContext, host, port) }.getOrNull()
    }

    @Synchronized
    fun ensureAfterPairing(context: Context, host: String, port: Int): File? {
        val appContext = context.applicationContext
        if (info(appContext).captured) return baselineFile(appContext)
        return runCatching { captureViaAdb(appContext, host, port) }.getOrNull()
    }

    @Synchronized
    private fun save(context: Context, entries: List<BaselineEntry>, notes: List<String>): File? {
        val appContext = context.applicationContext
        if (info(appContext).captured) return baselineFile(appContext)
        val now = System.currentTimeMillis()
        val text = DeviceBaseline.renderMarkdown(appContext.packageName, now, entries, notes)
        val dir = primaryDir(appContext) ?: appContext.getExternalFilesDir(null)?.let { File(it, DIR_NAME) } ?: return null
        runCatching { dir.mkdirs() }.onFailure { return null }
        val target = DeviceBaseline.resolveUniqueFile(dir, DeviceBaseline.fileName(now))
        val written = AtomicStateFile.save(target, text)
        if (!written) {
            DiagnosticsLog.log(appContext, "baseline.capture", "failed")
            return null
        }
        DiagnosticsLog.log(
            appContext, "baseline.capture", "succeeded",
            fields = mapOf(
                "readable" to entries.count { it.value != null }.toString(),
                "unreadable" to entries.count { it.value == null }.toString(),
            ),
        )
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CAPTURED, true)
            .putString(KEY_FILE, target.name)
            .putLong(KEY_TIME, now)
            .apply()
        Log.i(TAG, "baseline saved to ${target.path}")
        return target
    }

    private fun primaryDir(context: Context): File? {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, "DroidDeck")
        } catch (_: Exception) {
            null
        }
    }
}
