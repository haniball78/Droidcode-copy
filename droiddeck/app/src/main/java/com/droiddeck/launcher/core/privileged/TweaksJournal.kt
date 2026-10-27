package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import java.io.File

enum class JournalVerifyMode {
    VALUE,
    CONTAINS,
    NOT_CONTAINS,
    UNVERIFIED,
}

data class JournalItem(
    val key: String,
    val original: String?,
    val restoreCmd: String,
    val verifyCmd: String?,
    val verifyMode: JournalVerifyMode,
    val persistent: Boolean,
    val generation: Int,
    val verifyExpect: String? = null,
)

internal object JournalCodec {
    fun encode(items: List<JournalItem>): String {
        val body = items.joinToString(",") { item ->
            val verify = if (item.verifyCmd == null) "null" else "\"${escape(item.verifyCmd)}\""
            val original = if (item.original == null) "null" else "\"${escape(item.original)}\""
            val mode = when (item.verifyMode) {
                JournalVerifyMode.VALUE -> "value"
                JournalVerifyMode.CONTAINS -> "contains"
                JournalVerifyMode.NOT_CONTAINS -> "not_contains"
                JournalVerifyMode.UNVERIFIED -> "unverified"
            }
            val expect = if (item.verifyExpect == null) "null" else "\"${escape(item.verifyExpect)}\""
            "{\"key\":\"${escape(item.key)}\",\"original\":$original," +
                "\"restore\":\"${escape(item.restoreCmd)}\",\"verify\":$verify," +
                "\"mode\":\"$mode\",\"persistent\":${item.persistent},\"generation\":${item.generation}," +
                "\"verifyExpect\":$expect}"
        }
        return "{\"version\":1,\"items\":[$body]}"
    }

    fun decode(text: String): List<JournalItem>? {
        return try {
            val parsed = JsonParser(text).parseDocument()
            if (parsed !is Map<*, *>) return null
            val version = (parsed["version"] as? Number)?.toInt() ?: return null
            if (version != 1) return null
            val rawItems = parsed["items"] as? List<*> ?: return null
            rawItems.map { raw ->
                val map = raw as? Map<*, *> ?: return null
                val key = map["key"] as? String ?: return null
                if (key.isEmpty()) return null
                if (!map.containsKey("original")) return null
                val rawOriginal = map["original"]
                if (rawOriginal != null && rawOriginal !is String) return null
                val original = rawOriginal as? String?
                val restore = map["restore"] as? String ?: return null
                if (restore.isEmpty()) return null
                if (!map.containsKey("verify")) return null
                val rawVerify = map["verify"]
                if (rawVerify != null && rawVerify !is String) return null
                val verify = rawVerify as? String?
                val mode = when (map["mode"] as? String) {
                    "value" -> JournalVerifyMode.VALUE
                    "contains" -> JournalVerifyMode.CONTAINS
                    "not_contains" -> JournalVerifyMode.NOT_CONTAINS
                    "exit", "unverified" -> JournalVerifyMode.UNVERIFIED
                    else -> return null
                }
                if (mode == JournalVerifyMode.VALUE && verify == null) return null
                if ((mode == JournalVerifyMode.CONTAINS || mode == JournalVerifyMode.NOT_CONTAINS) && verify == null) return null
                val rawExpect = if (map.containsKey("verifyExpect")) map["verifyExpect"] else null
                if (rawExpect != null && rawExpect !is String) return null
                val expect = rawExpect as? String?
                if ((mode == JournalVerifyMode.CONTAINS || mode == JournalVerifyMode.NOT_CONTAINS) && expect == null) return null
                val persistent = map["persistent"] as? Boolean ?: return null
                val generation = (map["generation"] as? Number)?.toInt() ?: return null
                JournalItem(key, original, restore, verify, mode, persistent, generation, expect)
            }
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

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw IllegalStateException("unexpected end")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }.also { skipWhitespace() }
        }

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw IllegalStateException("trailing data")
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') throw IllegalStateException("expected :")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (pos >= text.length) throw IllegalStateException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalStateException("expected , or }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (pos >= text.length) throw IllegalStateException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalStateException("expected , or ]")
                }
            }
        }

        private fun parseString(): String {
            if (pos >= text.length || text[pos] != '"') throw IllegalStateException("expected string")
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val esc = text[pos++]) {
                            '\\' -> out.append('\\')
                            '"' -> out.append('"')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalStateException("bad unicode escape")
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw IllegalStateException("bad literal")
            pos += word.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+eE.")) pos++
            if (start == pos) throw IllegalStateException("bad number")
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalStateException("bad number")
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

}

internal object JournalRollback {
    data class Result(
        val success: Boolean,
        val remaining: List<JournalItem>,
        val failedKey: String?,
        val completed: List<JournalItem> = emptyList(),
    )

    fun rollback(
        items: List<JournalItem>,
        execute: (String) -> Int,
        readVerified: (JournalItem) -> String?,
    ): Result {
        val completed = ArrayList<JournalItem>()
        for (index in items.indices.reversed()) {
            val item = items[index]
            if (!SafetyChecks.isValidSettingValue(item.key, item.original) || item.restoreCmd.contains("Daemon error")) {
                return Result(false, items.subList(0, index + 1), item.key, completed)
            }
            if (execute(item.restoreCmd) != 0) {
                return Result(false, items.subList(0, index + 1), item.key, completed)
            }
            val verified = when (item.verifyMode) {
                JournalVerifyMode.VALUE -> {
                    val raw = readVerified(item) ?: return Result(false, items.subList(0, index + 1), item.key, completed)
                    SafetyChecks.parseBackgroundLimit(raw) == item.original
                }
                JournalVerifyMode.CONTAINS -> {
                    val output = readVerified(item) ?: return Result(false, items.subList(0, index + 1), item.key, completed)
                    output.contains(item.verifyExpect ?: "")
                }
                JournalVerifyMode.NOT_CONTAINS -> {
                    val output = readVerified(item) ?: return Result(false, items.subList(0, index + 1), item.key, completed)
                    !output.contains(item.verifyExpect ?: "")
                }
                JournalVerifyMode.UNVERIFIED -> true
            }
            if (!verified) {
                return Result(false, items.subList(0, index + 1), item.key, completed)
            }
            completed += item
        }
        return Result(true, emptyList(), null, completed)
    }
}

internal enum class RepairDecision {
    CORRUPT,
    ALREADY_OK,
    RETRY,
    UNREADABLE,
}

internal object JournalRepair {
    fun classify(item: JournalItem, currentValue: String?): RepairDecision {
        if (!SafetyChecks.isValidSettingValue(item.key, item.original) || item.restoreCmd.contains("Daemon error")) {
            return RepairDecision.CORRUPT
        }
        if (currentValue == null) {
            return RepairDecision.UNREADABLE
        }
        if (item.verifyMode == JournalVerifyMode.VALUE) {
            return if (SafetyChecks.parseBackgroundLimit(currentValue) == item.original) RepairDecision.ALREADY_OK else RepairDecision.RETRY
        }
        return RepairDecision.RETRY
    }
}

internal object JournalMerge {
    fun merge(current: List<JournalItem>, newItems: List<JournalItem>): List<JournalItem> {
        val merged = current.toMutableList()
        for (item in newItems) {
            if (merged.none { it.key == item.key }) merged.add(item)
        }
        return merged
    }
}

object Tweaks {
    private const val TAG = "TweaksJournal"
    private const val JOURNAL_FILE = "safety-journal.json"
    private const val REPAIR_BACKUP_FILE = "safety-journal-repair-backup.json"
    private const val QUARANTINE_FILE = "safety-journal-quarantine.json"
    private val journalLock = Any()

    fun recoveryNeeded(context: Context): Boolean {
        return synchronized(journalLock) {
            val file = journalFile(context)
            file.exists() || AtomicStateFile.bakFile(file).exists() || AtomicStateFile.tmpFile(file).exists()
        }
    }

    fun hasKey(context: Context, key: String): Boolean {
        return synchronized(journalLock) { load(context)?.any { it.key == key } == true }
    }

    fun journalSnapshot(context: Context): List<JournalItem> {
        return synchronized(journalLock) { load(context) ?: emptyList() }
    }

    fun recordItems(context: Context, items: List<JournalItem>): Boolean {
        return synchronized(journalLock) {
            if (items.isEmpty()) return@synchronized true
            for (item in items) {
                if (item.verifyMode == JournalVerifyMode.VALUE && item.verifyCmd == null) return@synchronized false
                if ((item.verifyMode == JournalVerifyMode.CONTAINS || item.verifyMode == JournalVerifyMode.NOT_CONTAINS) &&
                    (item.verifyCmd == null || item.verifyExpect == null)
                ) return@synchronized false
            }
            val current = load(context) ?: return@synchronized false
            val saved = save(context, JournalMerge.merge(current, items))
            DiagnosticsLog.log(context, "safety.journal", if (saved) "saved" else "save-failed", fields = mapOf("items" to items.size.toString()))
            saved
        }
    }

    fun recordItem(context: Context, item: JournalItem): Boolean = recordItems(context, listOf(item))

    internal fun effectiveVerifyCmd(item: JournalItem): String? {
        val stored = item.verifyCmd ?: return null
        if (item.key != "app-freezer-packages" || !stored.contains("list packages -s")) return stored
        val targets = storedTargets(item.restoreCmd)
        if (targets.isEmpty()) return stored
        return AppFreezerEngine.packagesVerifyCmd(targets)
    }

    internal fun storedTargets(restoreCmd: String): List<String> {
        val found = ArrayList<String>()
        val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
        for ((index, segment) in restoreCmd.split("unsuspend").withIndex()) {
            if (index == 0) continue
            for (token in segment.trim().split(Regex("\\s+"))) {
                if (token.startsWith("2>")) break
                if (packagePattern.matches(token) && !found.contains(token)) found.add(token)
            }
        }
        return found
    }

    fun removeKey(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            save(context, current.filter { it.key != key })
        }
    }

    fun backupJournal(context: Context): Boolean {
        return synchronized(journalLock) {
            val dir = context.applicationContext.noBackupFilesDir
            val file = journalFile(context)
            val source = listOf(file, AtomicStateFile.bakFile(file), AtomicStateFile.tmpFile(file)).firstOrNull { it.exists() }
                ?: return@synchronized true
            val text = try {
                source.readText()
            } catch (_: Exception) {
                return@synchronized false
            }
            AtomicStateFile.save(File(dir, REPAIR_BACKUP_FILE), text)
        }
    }

    fun quarantineKey(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            val item = current.firstOrNull { it.key == key } ?: return@synchronized false
            val quarantineFile = File(context.applicationContext.noBackupFilesDir, QUARANTINE_FILE)
            val existing = AtomicStateFile.readRecovered(quarantineFile, JournalCodec::decode) ?: emptyList()
            if (!AtomicStateFile.save(quarantineFile, JournalCodec.encode(existing + item))) return@synchronized false
            save(context, current.filter { it.key != key })
        }
    }

    fun rollbackOne(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            val item = current.firstOrNull { it.key == key } ?: return@synchronized false
            val result = JournalRollback.rollback(
                listOf(item),
                execute = { cmd -> execChecked(context, cmd).exitCode },
                readVerified = { verified ->
                    val verifyCmd = effectiveVerifyCmd(verified)
                    if (verifyCmd == null) {
                        null
                    } else {
                        val read = execChecked(context, verifyCmd)
                        if (read.exitCode != 0) null else read.output
                    }
                },
            )
            if (result.success) {
                save(context, current.filter { it.key != key })
            } else {
                false
            }
        }
    }

    fun rollback(context: Context): Boolean {
        return synchronized(journalLock) { rollbackLocked(context) }
    }

    private fun rollbackLocked(context: Context): Boolean {
        val current = load(context) ?: return false
        if (current.isEmpty()) {
            return AtomicStateFile.delete(journalFile(context))
        }
        DiagnosticsLog.log(context, "safety.journal", "rollback-started", fields = mapOf("items" to current.size.toString()))
        val result = JournalRollback.rollback(
            current,
            execute = { cmd -> execChecked(context, cmd).exitCode },
            readVerified = { item ->
                val verifyCmd = effectiveVerifyCmd(item)
                if (verifyCmd == null) {
                    null
                } else {
                    val result = execChecked(context, verifyCmd)
                    if (result.exitCode != 0) null else result.output
                }
            },
        )
        for (item in result.completed) {
            DiagnosticsLog.log(
                context, "safety.restore", "restored",
                fields = mapOf(
                    "key" to item.key,
                    "verify" to if (item.verifyMode == JournalVerifyMode.UNVERIFIED) "unverified" else "verified",
                ),
            )
        }
        result.failedKey?.let { key ->
            DiagnosticsLog.log(context, "safety.restore", "failed", fields = mapOf("key" to key, "verify" to "failed"))
        }
        DiagnosticsLog.log(
            context, "safety.journal", if (result.success) "rollback-finished" else "rollback-failed",
            fields = mapOf("restored" to result.completed.size.toString(), "remaining" to result.remaining.size.toString()),
        )
        if (result.success) {
            return AtomicStateFile.delete(journalFile(context))
        }
        Log.w(TAG, "rollback failed at ${result.failedKey}, journal kept")
        save(context, result.remaining)
        return false
    }

    fun applyExistingKeyChange(context: Context, key: String, applyCmd: String): Boolean {
        return synchronized(journalLock) {
            if (load(context)?.any { it.key == key } != true) return@synchronized false
            execChecked(context, applyCmd).exitCode == 0
        }
    }

    private fun load(context: Context): List<JournalItem>? {
        val file = journalFile(context)
        if (!file.exists() && !AtomicStateFile.bakFile(file).exists() && !AtomicStateFile.tmpFile(file).exists()) {
            return emptyList()
        }
        return AtomicStateFile.readRecovered(file, JournalCodec::decode)
    }

    private fun save(context: Context, items: List<JournalItem>): Boolean {
        val file = journalFile(context)
        if (items.isEmpty()) return AtomicStateFile.delete(file)
        val saved = AtomicStateFile.save(file, JournalCodec.encode(items))
        if (!saved) Log.w(TAG, "journal save failed")
        return saved
    }

    private fun journalFile(context: Context): File {
        return File(context.applicationContext.noBackupFilesDir, JOURNAL_FILE)
    }

    private fun exec(context: Context, cmd: String): String {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.execute(cmd).output
        } catch (_: Throwable) {
            ""
        }
    }

    private fun execChecked(context: Context, cmd: String): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.executeChecked(cmd)
        } catch (_: Throwable) {
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }
}
