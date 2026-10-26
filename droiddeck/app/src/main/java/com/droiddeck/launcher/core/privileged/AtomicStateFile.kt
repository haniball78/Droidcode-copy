package com.droiddeck.launcher.core.privileged

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal object AtomicStateFile {
    @Volatile var lastBakRestoreFailed: Boolean = false
        private set

    @Volatile var lastWriteBackFailed: Boolean = false
        private set

    fun tmpFile(file: File): File = File(file.parentFile, "${file.name}.tmp")

    fun bakFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    fun save(file: File, text: String, rename: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) }): Boolean {
        lastBakRestoreFailed = false
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.exists()) parent.mkdirs()
            val tmp = tmpFile(file)
            FileOutputStream(tmp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.channel.force(true)
            }
            val bak = bakFile(file)
            if (file.exists() && !rename(file, bak)) return false
            if (!rename(tmp, file)) {
                if (bak.exists()) {
                    val restored = runCatching { rename(bak, file) }.getOrDefault(false)
                    if (!restored) lastBakRestoreFailed = true
                }
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
        lastWriteBackFailed = false
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
                if (candidate != file) {
                    val written = runCatching { save(file, text) }.getOrDefault(false)
                    if (!written) lastWriteBackFailed = true
                }
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
