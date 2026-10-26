package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDVerifyTest {
    private fun tempDir(): File = Files.createTempDirectory("batch-d-test").toFile()

    private fun decode(text: String): String? = if (text.startsWith("valid:")) text else null

    @Test
    fun fixedPerformanceParseMapsUnsetToFalseAndRejectsGarbage() {
        assertEquals("true", FixedPerformanceState.parse("true"))
        assertEquals("true", FixedPerformanceState.parse("  TRUE "))
        assertEquals("false", FixedPerformanceState.parse("false"))
        assertEquals("false", FixedPerformanceState.parse("null"))
        assertEquals("false", FixedPerformanceState.parse(""))
        assertEquals("false", FixedPerformanceState.parse("   "))
        assertNull(FixedPerformanceState.parse("enabled"))
        assertNull(FixedPerformanceState.parse("2"))
    }

    @Test
    fun fixedPerformanceItemRestoresItsOriginalInsteadOfAlwaysFalse() {
        val trueItem = FixedPerformanceState.item("true", 5)
        assertEquals("cmd power set-fixed-performance-mode-enabled true 2>/dev/null", trueItem.restoreCmd)
        assertEquals("cmd power get-fixed-performance-mode-enabled", trueItem.verifyCmd)
        assertEquals(JournalVerifyMode.VALUE, trueItem.verifyMode)
        val falseItem = FixedPerformanceState.item("false", 5)
        assertEquals("cmd power set-fixed-performance-mode-enabled false 2>/dev/null", falseItem.restoreCmd)
    }

    private fun rollbackFixed(original: String, applied: String): String {
        val state = mutableMapOf(
            "background_process_limit" to "4",
            "fixed-performance" to applied,
            "adaptive_battery_management_enabled" to "0",
        )
        val items = listOf(
            JournalItem(
                key = "background_process_limit",
                original = "8",
                restoreCmd = "settings put global background_process_limit 8",
                verifyCmd = "settings get global background_process_limit",
                verifyMode = JournalVerifyMode.VALUE,
                persistent = true,
                generation = 3,
            ),
            FixedPerformanceState.item(original, 3),
            JournalItem(
                key = "adaptive_battery_management_enabled",
                original = "1",
                restoreCmd = "settings put global adaptive_battery_management_enabled 1",
                verifyCmd = "settings get global adaptive_battery_management_enabled",
                verifyMode = JournalVerifyMode.VALUE,
                persistent = true,
                generation = 3,
            ),
        )
        val result = JournalRollback.rollback(
            items,
            execute = { cmd ->
                when (cmd) {
                    "settings put global background_process_limit 8" -> state["background_process_limit"] = "8"
                    "cmd power set-fixed-performance-mode-enabled true 2>/dev/null" -> state["fixed-performance"] = "true"
                    "cmd power set-fixed-performance-mode-enabled false 2>/dev/null" -> state["fixed-performance"] = "false"
                    "settings put global adaptive_battery_management_enabled 1" -> state["adaptive_battery_management_enabled"] = "1"
                }
                0
            },
            readVerified = { item ->
                when (item.key) {
                    "background_process_limit" -> state["background_process_limit"]
                    "fixed-performance" -> state["fixed-performance"]
                    "adaptive_battery_management_enabled" -> state["adaptive_battery_management_enabled"]
                    else -> null
                }
            },
        )
        assertTrue(result.success)
        assertTrue(result.remaining.isEmpty())
        return state["fixed-performance"] ?: "missing"
    }

    @Test
    fun midApplyFailureRollbackRestoresFixedPerformanceToFalse() {
        assertEquals("false", rollbackFixed(original = "false", applied = "true"))
    }

    @Test
    fun midApplyFailureRollbackKeepsPreExistingFixedPerformanceTrue() {
        assertEquals("true", rollbackFixed(original = "true", applied = "true"))
    }

    @Test
    fun saveReportsBakRestoreFailureInsteadOfSwallowingIt() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("old")
        val saved = AtomicStateFile.save(file, "new") { src, dst ->
            if (src == AtomicStateFile.tmpFile(file) && dst == file) false
            else if (src == AtomicStateFile.bakFile(file) && dst == file) false
            else src.renameTo(dst)
        }
        assertFalse(saved)
        assertTrue(AtomicStateFile.lastBakRestoreFailed)
        assertTrue(AtomicStateFile.bakFile(file).exists())
    }

    @Test
    fun saveDoesNotFlagBakRestoreWhenItSucceeds() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("old")
        val saved = AtomicStateFile.save(file, "new") { src, dst ->
            if (src == AtomicStateFile.tmpFile(file) && dst == file) false
            else src.renameTo(dst)
        }
        assertFalse(saved)
        assertFalse(AtomicStateFile.lastBakRestoreFailed)
        assertEquals("old", file.readText())
    }

    @Test
    fun readRecoveredWriteBackFailureStillReturnsValueAndFlagsIt() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        AtomicStateFile.bakFile(file).writeText("valid:backup")
        assertTrue(AtomicStateFile.tmpFile(file).mkdir())
        assertEquals("valid:backup", AtomicStateFile.readRecovered(file, ::decode))
        assertTrue(AtomicStateFile.lastWriteBackFailed)
        assertFalse(file.exists())
    }
}
