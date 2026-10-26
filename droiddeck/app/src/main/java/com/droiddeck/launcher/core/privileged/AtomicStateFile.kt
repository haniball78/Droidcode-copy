package com.droiddeck.launcher.core.privileged

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal object AtomicStateFile {
    fun tmpFile(file: File): File = File(file.parentFile, "${file.name}.tmp")

    fun bakFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    fun save(file: File, text: String): Boolean {
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.exists()) parent.mkdirs()
            val tmp = tmpFile(file)
            FileOutputStream(tmp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.channel.force(true)
            }
            val bak = bakFile(file)
            if (file.exists() && !file.renameTo(bak)) return false
            if (!tmp.renameTo(file)) {
                if (bak.exists()) runCatching { bak.renameTo(file) }
                return false
            }
            syncDirectory(parent)
            if (bak.exists() && !bak.delete()) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(file: File): Boolean {
        var ok = true
        for (candidate in listOf(file, bakFile(file), tmpFile(file))) {
            if (candidate.exists() && !candidate.delete()) ok = false
        }
        return ok
    }

    fun <T> readRecovered(file: File, decode: (String) -> T?): T? {
        val parent = file.parentFile ?: return null
        if (!file.exists() && !bakFile(file).exists() && !tmpFile(file).exists()) return null
        val candidates = listOf(file, bakFile(file), tmpFile(file))
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            val text = try {
                candidate.readText()
            } catch (_: Exception) {
                continue
            }
            val decoded = try {
                decode(text)
            } catch (_: Exception) {
                null
            }
            if (decoded != null) {
                if (candidate != file) runCatching { save(file, text) }
                return decoded
            }
        }
        return null
    }

    private fun syncDirectory(dir: File) {
        try {
            FileInputStream(dir).use { stream -> stream.channel.force(true) }
        } catch (_: Exception) {
        }
    }
}
