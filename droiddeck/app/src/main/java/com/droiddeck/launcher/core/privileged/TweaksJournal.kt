package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

enum class JournalVerifyMode {
    VALUE,
    EXIT,
}

data class JournalItem(
    val key: String,
    val original: String?,
    val restoreCmd: String,
    val verifyCmd: String?,
    val verifyMode: JournalVerifyMode,
    val persistent: Boolean,
    val generation: Int,
)

internal object JournalCodec {
    fun encode(items: List<JournalItem>): String {
        val body = items.joinToString(",") { item ->
            val verify = if (item.verifyCmd == null) "null" else "\"${escape(item.verifyCmd)}\""
            val original = if (item.original == null) "null" else "\"${escape(item.original)}\""
            val mode = if (item.verifyMode == JournalVerifyMode.VALUE) "value" else "exit"
            "{\"key\":\"${escape(item.key)}\",\"original\":$original," +
                "\"restore\":\"${escape(item.restoreCmd)}\",\"verify\":$verify," +
                "\"mode\":\"$mode\",\"persistent\":${item.persistent},\"generation\":${item.generation}}"
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
                    "exit" -> JournalVerifyMode.EXIT
                    else -> return null
                }
                if (mode == JournalVerifyMode.VALUE && verify == null) return null
                val persistent = map["persistent"] as? Boolean ?: return null
                val generation = (map["generation"] as? Number)?.toInt() ?: return null
                JournalItem(key, original, restore, verify, mode, persistent, generation)
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
    )

    fun rollback(
        items: List<JournalItem>,
        execute: (String) -> Int,
        readVerified: (JournalItem) -> String?,
    ): Result {
        for (index in items.indices.reversed()) {
            val item = items[index]
            if (execute(item.restoreCmd) != 0) {
                return Result(false, items.subList(0, index + 1), item.key)
            }
            if (item.verifyMode == JournalVerifyMode.VALUE) {
                val actual = readVerified(item)
                if (actual != item.original) {
                    return Result(false, items.subList(0, index + 1), item.key)
                }
            }
        }
        return Result(true, emptyList(), null)
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
    private val rollbackLock = Any()

    fun recoveryNeeded(context: Context): Boolean = journalFile(context).exists()

    fun hasKey(context: Context, key: String): Boolean {
        return load(context)?.any { it.key == key } == true
    }

    fun recordItems(context: Context, items: List<JournalItem>): Boolean {
        if (items.isEmpty()) return true
        for (item in items) {
            if (item.verifyMode == JournalVerifyMode.VALUE && item.verifyCmd == null) return false
        }
        val current = load(context) ?: return false
        return save(context, JournalMerge.merge(current, items))
    }

    fun recordItem(context: Context, item: JournalItem): Boolean = recordItems(context, listOf(item))

    fun removeKey(context: Context, key: String): Boolean {
        val current = load(context) ?: return false
        return save(context, current.filter { it.key != key })
    }

    fun rollback(context: Context): Boolean {
        return synchronized(rollbackLock) { rollbackLocked(context) }
    }

    private fun rollbackLocked(context: Context): Boolean {
        val current = load(context) ?: return false
        if (current.isEmpty()) {
            runCatching { journalFile(context).delete() }
            return true
        }
        val result = JournalRollback.rollback(
            current,
            execute = { cmd -> execChecked(context, cmd).exitCode },
            readVerified = { item ->
                val verifyCmd = item.verifyCmd
                if (verifyCmd == null) {
                    null
                } else {
                    SafetyChecks.parseBackgroundLimit(exec(context, verifyCmd))
                }
            },
        )
        if (result.success) {
            runCatching { journalFile(context).delete() }
            return true
        }
        Log.w(TAG, "rollback failed at ${result.failedKey}, journal kept")
        save(context, result.remaining)
        return false
    }

    fun applyExistingKeyChange(context: Context, key: String, applyCmd: String): Boolean {
        if (!hasKey(context, key)) return false
        return execChecked(context, applyCmd).exitCode == 0
    }

    private fun load(context: Context): List<JournalItem>? {
        return try {
            val file = journalFile(context)
            if (!file.exists()) return emptyList()
            JournalCodec.decode(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun save(context: Context, items: List<JournalItem>): Boolean {
        return try {
            val file = journalFile(context)
            if (items.isEmpty()) {
                if (file.exists()) file.delete()
                return true
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { stream ->
                stream.write(JournalCodec.encode(items).toByteArray(Charsets.UTF_8))
                stream.channel.force(true)
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (t: Throwable) {
            Log.w(TAG, "journal save failed", t)
            false
        }
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
