package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    private fun tempDir(): File = Files.createTempDirectory("diagnostics-test").toFile()

    @Test
    fun rotationKeepsBoundedFiles() {
        val dir = tempDir()
        val line = "x".repeat(100)
        repeat(30) {
            assertTrue(DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", line, 200L, 3))
        }
        val files = dir.listFiles()?.filter { it.name.startsWith("droiddeck-diagnostics") } ?: emptyList()
        assertTrue(files.size <= 4)
        assertTrue(File(dir, "droiddeck-diagnostics.jsonl").isFile)
        assertTrue(files.all { it.length() <= 400L })
    }

    @Test
    fun rotationLeavesSmallFileAlone() {
        val dir = tempDir()
        assertTrue(DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", "short", 1024L, 3))
        assertTrue(DiagnosticsCore.rotate(dir, "droiddeck-diagnostics.jsonl", 1024L, 3))
        assertEquals(listOf("droiddeck-diagnostics.jsonl"), dir.list()?.toList())
    }

    @Test
    fun redactionRemovesTokenPasswordAndPairingCodeValues() {
        val line = DiagnosticsCore.render(
            1700000000000L, 12345L, "adb.pair", "failed", 0,
            mapOf(
                "token" to "SECRETTOKEN123456789",
                "password" to "hunter2secretvalue",
                "pairing_code" to "123456",
                "note" to "AUTH:abc123tokenvalue",
            ),
        )
        assertFalse(line.contains("SECRETTOKEN123456789"))
        assertFalse(line.contains("hunter2secretvalue"))
        assertFalse(line.contains("123456"))
        assertFalse(line.contains("abc123tokenvalue"))
        assertTrue(line.contains("adb.pair"))
    }

    @Test
    fun renderEscapesQuotesAndNewlines() {
        val line = DiagnosticsCore.render(
            1700000000000L, 1L, "session", "started", 2,
            mapOf("mode" to "desk\"top\nmode"),
        )
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("desk\\\"top\\nmode"))
    }

    @Test
    fun throttledSampleEmitsAtMostOncePerWindow() {
        assertTrue(DiagnosticsCore.shouldSample(1000L, -1L, false))
        assertFalse(DiagnosticsCore.shouldSample(1000L, 1000L, false))
        assertFalse(DiagnosticsCore.shouldSample(60999L, 1000L, false))
        assertTrue(DiagnosticsCore.shouldSample(61000L, 1000L, false))
        assertTrue(DiagnosticsCore.shouldSample(2000L, 1000L, true))
    }

    @Test
    fun failedWritePathDoesNotThrow() {
        val dir = tempDir()
        val fileAsDir = File(dir, "not-a-dir").apply { writeText("x") }
        assertFalse(DiagnosticsCore.append(fileAsDir, "droiddeck-diagnostics.jsonl", "line"))
        val baseAsDir = tempDir()
        File(baseAsDir, "droiddeck-diagnostics.jsonl").mkdir()
        assertFalse(DiagnosticsCore.append(baseAsDir, "droiddeck-diagnostics.jsonl", "line"))
        assertNull(DiagnosticsCore.summary(tempDir(), "missing.jsonl"))
    }

    @Test
    fun disabledAppendWritesNothingAndReEnabledAppendsAgain() {
        val dir = tempDir()
        assertTrue(DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", "one", enabled = false))
        assertFalse(File(dir, "droiddeck-diagnostics.jsonl").isFile)
        assertTrue(DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", "two", enabled = true))
        val summary = DiagnosticsCore.summary(dir, "droiddeck-diagnostics.jsonl")
        assertEquals(1L, summary?.lines)
    }

    @Test
    fun deleteCurrentFileLeavesRotatedFileIntact() {
        val dir = tempDir()
        File(dir, "droiddeck-diagnostics.jsonl").writeText("current\n")
        File(dir, DiagnosticsCore.rotatedName("droiddeck-diagnostics.jsonl", 1)).writeText("rotated\n")
        assertTrue(DiagnosticsCore.deleteCurrentFile(dir, "droiddeck-diagnostics.jsonl"))
        assertFalse(File(dir, "droiddeck-diagnostics.jsonl").isFile)
        assertTrue(File(dir, DiagnosticsCore.rotatedName("droiddeck-diagnostics.jsonl", 1)).isFile)
        assertTrue(DiagnosticsCore.deleteCurrentFile(dir, "droiddeck-diagnostics.jsonl"))
    }

    @Test
    fun summaryCountsLinesAndLastTimestamp() {
        val dir = tempDir()
        DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", DiagnosticsCore.render(1000L, 1L, "a", "one", 0, emptyMap()))
        DiagnosticsCore.append(dir, "droiddeck-diagnostics.jsonl", DiagnosticsCore.render(2000L, 2L, "b", "two", 0, emptyMap()))
        val summary = DiagnosticsCore.summary(dir, "droiddeck-diagnostics.jsonl")
        assertEquals(2L, summary?.lines)
        assertEquals(2000L, summary?.lastWallMs)
    }

    @Test
    fun expandedDeviceChangeEventsRenderAsFlatFields() {
        val line = DiagnosticsCore.render(3000L, 3L, "device.change", "daemon-start-steps", 0, mapOf("steps" to "phantom:0,grant-secure:0,grant-dump:0,grant-usage:0,whitelist:0,appops:0,starter:0"))
        assertTrue(line.contains("\"device.change\""))
        assertTrue(line.contains("daemon-start-steps"))
        assertTrue(line.contains("grant-secure:0"))
        val tier = DiagnosticsCore.render(3001L, 4L, "device.change", "governor-tier", 0, mapOf("tier" to "BALANCED_COOL", "limit" to "4"))
        assertTrue(tier.contains("governor-tier"))
        assertTrue(tier.contains("BALANCED_COOL"))
    }
}
